package com.examseating.service;

import com.examseating.dto.ArrangeRequest;
import com.examseating.dto.MoveAllocationRequest;
import com.examseating.dto.RosterSchemaView;
import com.examseating.dto.SeatingResponse;
import com.examseating.engine.SeatingArrangementEngine;
import com.examseating.engine.SeatingArrangementEngine.GroupMatchMode;
import com.examseating.engine.SeatingArrangementEngine.IllegalSeatingConfigurationException;
import com.examseating.engine.SeatingArrangementEngine.InsufficientCapacityException;
import com.examseating.engine.SeatingArrangementEngine.PlacementVerdict;
import com.examseating.engine.SeatingArrangementEngine.SeatKey;
import com.examseating.engine.SeatingArrangementEngine.SeatOccupant;
import com.examseating.engine.SeatingArrangementEngine.SeatingAdvisory;
import com.examseating.engine.SeatingArrangementEngine.SeatingConfig;
import com.examseating.engine.SeatingArrangementEngine.SeatingEngineException;
import com.examseating.engine.SeatingArrangementEngine.SeatingPlan;
import com.examseating.engine.SeatingArrangementEngine.SeatingUnsatisfiableException;
import com.examseating.engine.SeatingArrangementEngine.StudentAttribute;
import com.examseating.entity.Bench;
import com.examseating.entity.CohortRule;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.SeatingRound;
import com.examseating.entity.Student;
import com.examseating.entity.StudentAttributes;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.exception.SeatingConflictException;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.repository.SeatingRoundRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.tenancy.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Pure dynamic seating orchestration service: resolves halls, optional exam-round
 * scope ({@code scopeAttribute = scopeValue}), and dynamic classmate attributes
 * (from the request, the institution's {@code StudentIdentityScheme}, or live
 * schema discovery), runs {@link SeatingArrangementEngine}, and persists the
 * resulting allocations and {@link SeatingRound}.
 */
@Service
@RequiredArgsConstructor
public class SeatingService {

    private final StudentRepository studentRepository;
    private final ExamRoomRepository examRoomRepository;
    private final SeatingAllocationRepository allocationRepository;
    private final SeatingRoundRepository roundRepository;
    private final TenantSupport tenant;
    private final QuotaService quotas;
    private final StudentIdentityService identityService;
    private final CohortService cohortService;

    private final SeatingArrangementEngine engine = new SeatingArrangementEngine();

    @Transactional
    public SeatingResponse arrange(ArrangeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("A JSON request body is required");
        }

        // 1) Load halls (all, or only the requested subset).
        boolean roomSubset = request.roomIds() != null && !request.roomIds().isEmpty();
        List<ExamRoom> rooms = tenant.visible(roomSubset
                ? examRoomRepository.findAllByIdIn(request.roomIds())
                : examRoomRepository.findAllByOrderByHallNameAsc());
        if (rooms.isEmpty()) {
            throw new ResourceNotFoundException("No exam halls found — create a room first.");
        }
        if (roomSubset && rooms.size() != request.roomIds().size()) {
            throw new ResourceNotFoundException("One or more requested hall ids do not exist.");
        }
        rooms.forEach(this::ensureBenchGridPersisted);
        Set<Long> roomIdSet = new HashSet<>();
        for (ExamRoom room : rooms) {
            if (room.getId() != null) {
                roomIdSet.add(room.getId());
            }
        }

        // 2) Resolve optional dynamic round scope (e.g. examdate = "2026-11-15").
        String scopeValue = blankToNull(request.scopeValue());
        String scopeAttr = blankToNull(request.scopeAttribute());
        if (scopeValue != null && scopeAttr == null) {
            scopeAttr = blankToNull(identityService.currentScopeAttribute());
        }
        if (scopeValue != null && scopeAttr == null) {
            throw new IllegalArgumentException(
                    "scopeAttribute is required when scopeValue ('" + scopeValue + "') is provided");
        }
        if (scopeAttr != null) {
            scopeAttr = StudentAttributes.normalize(scopeAttr);
        }

        boolean clearExisting = request.clearExisting() == null || request.clearExisting();

        // 3) Load and filter students (by optional round scope AND optional multi-category filter).
        List<Student> allStudents = tenant.visible(studentRepository.findAll());
        final String resolvedScopeAttr = scopeAttr;
        final String resolvedScopeVal = scopeValue;
        List<Student> scopedStudents = (resolvedScopeVal == null)
                ? allStudents
                : allStudents.stream()
                        .filter(s -> equalsIgnoreCaseTrimmed(
                                StudentAttributes.value(s, resolvedScopeAttr), resolvedScopeVal))
                        .toList();

        boolean hasCategoryFilter = request.filterAttribute() != null
                && !request.filterAttribute().isBlank()
                && request.filterValues() != null
                && !request.filterValues().isEmpty();
        List<Student> students = hasCategoryFilter
                ? applyCategoryFilter(scopedStudents, request.filterAttribute(), request.filterValues())
                : scopedStudents;

        if (hasCategoryFilter && students.isEmpty()) {
            throw new IllegalArgumentException(
                    "No students match the selected student categories for '" + request.filterAttribute() + "'.");
        }

        Set<Long> targetStudentIds = students.stream()
                .map(Student::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        // 4) Partition persisted seats into those being replaced vs pre-occupied by preserved allocations.
        List<SeatingAllocation> existing = tenant.visible(allocationRepository.findAllWithDetails(null));
        List<SeatingAllocation> toDelete = new ArrayList<>();
        Set<SeatKey> occupied = new HashSet<>();
        int preservedSameScopeCount = 0;

        for (SeatingAllocation allocation : existing) {
            boolean sameScope = (resolvedScopeVal == null)
                    ? (allocation.getScopeKey() == null || allocation.getScopeKey().isBlank())
                    : equalsIgnoreCaseTrimmed(allocation.getScopeKey(), resolvedScopeVal);
            Long allocStudentId = allocation.getStudent() != null ? allocation.getStudent().getId() : null;

            boolean replaced;
            if (clearExisting) {
                // Replace all allocations for this scope (including unselected halls so no stale seats remain)
                replaced = (resolvedScopeVal == null) || sameScope;
            } else {
                // Incremental mode: preserve existing halls/categories, replacing only students being re-seated now
                replaced = sameScope && allocStudentId != null && targetStudentIds.contains(allocStudentId);
            }

            if (replaced) {
                toDelete.add(allocation);
            } else {
                if (sameScope) {
                    preservedSameScopeCount++;
                }
                boolean inSelectedRooms = !roomSubset || roomIdSet.contains(roomIdOf(allocation));
                if (inSelectedRooms && allocation.getBench() != null) {
                    occupied.add(SeatKey.of(allocation.getBench(), allocation.getSlotNumber()));
                }
            }
        }

        int freeSeats = Math.max(0, rooms.stream().mapToInt(ExamRoom::getCapacity).sum() - occupied.size());

        quotas.checkGeneration(students.size());

        // 5) Resolve difficulty gaps and dynamic classmate grouping attributes.
        ArrangeRequest.StrictnessLevel level = (request.strictness() != null)
                ? request.strictness()
                : ArrangeRequest.StrictnessLevel.NORMAL;
        int dH = (request.horizontalGap() != null) ? request.horizontalGap() : 1;
        int dV = (request.verticalGap() != null) ? request.verticalGap()
                : (level == ArrangeRequest.StrictnessLevel.INTERMEDIATE
                        || level == ArrangeRequest.StrictnessLevel.EXTREME ? 1 : 0);
        int dD = (request.diagonalGap() != null) ? request.diagonalGap()
                : (level == ArrangeRequest.StrictnessLevel.EXTREME ? 1 : 0);

        List<StudentAttribute> effectiveGroupAttrs = effectiveSeparationAttributes(request.groupAttributes(), students);
        GroupMatchMode matchMode = request.groupMatchMode() != null ? request.groupMatchMode() : GroupMatchMode.ALL;

        SeatingConfig config = new SeatingConfig(
                dH,
                dV,
                dD,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                effectiveGroupAttrs,
                matchMode);

        if (students.size() > freeSeats) {
            SeatingAdvisory capAdvisory = engine.analyzeFeasibility(rooms, students, config, occupied, false);
            throw new InsufficientCapacityException(capAdvisory.message(), capAdvisory.suggestions());
        }

        // 6) Solve via the pure dynamic CSP engine (never silently relaxing restrictions).
        SeatingPlan plan;
        try {
            plan = (resolvedScopeVal == null)
                    ? engine.arrange(rooms, students, config, occupied)
                    : engine.arrangeForScope(rooms, students, resolvedScopeAttr, resolvedScopeVal, config, occupied);
        } catch (InsufficientCapacityException ex) {
            SeatingAdvisory adv = engine.analyzeFeasibility(rooms, students, config, occupied, false);
            throw new InsufficientCapacityException(
                    !adv.feasible() ? adv.message() : ex.getMessage(),
                    adv.suggestions());
        } catch (SeatingUnsatisfiableException ex) {
            SeatingAdvisory adv = engine.analyzeFeasibility(rooms, students, config, occupied, false);
            throw new SeatingUnsatisfiableException(
                    !adv.feasible() ? adv.message() : ex.getMessage(),
                    adv.suggestions());
        }

        SeatingAdvisory postAdvisory = engine.analyzeFeasibility(rooms, students, config, occupied, false);

        // 7) Replace in-scope allocations atomically.
        for (SeatingAllocation allocation : toDelete) {
            allocationRepository.delete(allocation);
        }
        allocationRepository.flush();

        List<SeatingAllocation> saved = new ArrayList<>(plan.allocations().size());
        for (SeatingAllocation allocation : plan.allocations()) {
            allocation.setId(null);
            if (resolvedScopeVal != null) {
                allocation.setScopeKey(resolvedScopeVal);
            }
            tenant.stamp(allocation);
            saved.add(allocationRepository.save(allocation));
        }

        quotas.recordGeneration(resolvedScopeVal, saved.size());
        int totalSeatedInScope = preservedSameScopeCount + saved.size();
        int totalTargetInScope = preservedSameScopeCount + students.size();
        recordRound(
                resolvedScopeAttr,
                resolvedScopeVal,
                level,
                config,
                totalSeatedInScope,
                totalTargetInScope,
                hasCategoryFilter ? normalizeFilterAttribute(request.filterAttribute()) : null,
                hasCategoryFilter ? request.filterValues() : null);

        var scheme = identityService.currentScheme().orElse(null);
        List<String> groupKeys = config.effectiveGroupKey().stream()
                .map(StudentAttribute::key)
                .toList();
        List<SeatingResponse.AllocationDto> dtos = saved.stream()
                .map(a -> SeatingResponse.AllocationDto.from(a, scheme, groupKeys))
                .toList();

        return SeatingResponse.of(dtos.size(), plan.unplacedStudents().size(),
                plan.totalSlots(), plan.totalBacktrackSteps(), plan.solved(), dtos, postAdvisory);
    }

    /**
     * Read-only pre-flight analysis evaluating cohort separation feasibility,
     * mandatory empty buffer seats, and any additional seats/benches required.
     */
    @Transactional(readOnly = true)
    public SeatingAdvisory analyze(ArrangeRequest rawRequest) {
        ArrangeRequest request = rawRequest != null
                ? rawRequest
                : new ArrangeRequest(ArrangeRequest.StrictnessLevel.NORMAL,
                        null, null, null, null, null, null, null, null, true);

        boolean roomSubset = request.roomIds() != null && !request.roomIds().isEmpty();
        List<ExamRoom> rooms = tenant.visible(roomSubset
                ? examRoomRepository.findAllByIdIn(request.roomIds())
                : examRoomRepository.findAllByOrderByHallNameAsc());
        Set<Long> roomIdSet = new HashSet<>();
        for (ExamRoom room : rooms) {
            if (room.getId() != null) {
                roomIdSet.add(room.getId());
            }
        }

        String scopeValue = blankToNull(request.scopeValue());
        String scopeAttr = blankToNull(request.scopeAttribute());
        if (scopeValue != null && scopeAttr == null) {
            scopeAttr = blankToNull(identityService.currentScopeAttribute());
        }
        if (scopeAttr != null) {
            scopeAttr = StudentAttributes.normalize(scopeAttr);
        }
        boolean clearExisting = request.clearExisting() == null || request.clearExisting();

        List<Student> allStudents = tenant.visible(studentRepository.findAll());
        final String resolvedScopeAttr = scopeAttr;
        final String resolvedScopeVal = scopeValue;
        List<Student> scopedStudents = (resolvedScopeVal == null || resolvedScopeAttr == null)
                ? allStudents
                : allStudents.stream()
                        .filter(s -> equalsIgnoreCaseTrimmed(
                                StudentAttributes.value(s, resolvedScopeAttr), resolvedScopeVal))
                        .toList();

        boolean hasCategoryFilter = request.filterAttribute() != null
                && !request.filterAttribute().isBlank()
                && request.filterValues() != null
                && !request.filterValues().isEmpty();
        List<Student> students = hasCategoryFilter
                ? applyCategoryFilter(scopedStudents, request.filterAttribute(), request.filterValues())
                : scopedStudents;

        Set<Long> targetStudentIds = students.stream()
                .map(Student::getId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        List<SeatingAllocation> existing = tenant.visible(allocationRepository.findAllWithDetails(null));
        Set<SeatKey> occupied = new HashSet<>();
        for (SeatingAllocation allocation : existing) {
            boolean sameScope = (resolvedScopeVal == null)
                    ? (allocation.getScopeKey() == null || allocation.getScopeKey().isBlank())
                    : equalsIgnoreCaseTrimmed(allocation.getScopeKey(), resolvedScopeVal);
            Long allocStudentId = allocation.getStudent() != null ? allocation.getStudent().getId() : null;
            boolean replaced = clearExisting
                    ? ((resolvedScopeVal == null) || sameScope)
                    : (sameScope && allocStudentId != null && targetStudentIds.contains(allocStudentId));
            if (!replaced) {
                boolean inSelectedRooms = !roomSubset || roomIdSet.contains(roomIdOf(allocation));
                if (inSelectedRooms && allocation.getBench() != null) {
                    occupied.add(SeatKey.of(allocation.getBench(), allocation.getSlotNumber()));
                }
            }
        }

        ArrangeRequest.StrictnessLevel level = (request.strictness() != null)
                ? request.strictness()
                : ArrangeRequest.StrictnessLevel.NORMAL;
        int dH = (request.horizontalGap() != null) ? request.horizontalGap() : 1;
        int dV = (request.verticalGap() != null) ? request.verticalGap()
                : (level == ArrangeRequest.StrictnessLevel.INTERMEDIATE
                        || level == ArrangeRequest.StrictnessLevel.EXTREME ? 1 : 0);
        int dD = (request.diagonalGap() != null) ? request.diagonalGap()
                : (level == ArrangeRequest.StrictnessLevel.EXTREME ? 1 : 0);

        List<StudentAttribute> effectiveGroupAttrs;
        try {
            effectiveGroupAttrs = effectiveSeparationAttributes(request.groupAttributes(), students);
        } catch (RuntimeException ex) {
            effectiveGroupAttrs = List.of(StudentAttribute.of("cohort"));
        }
        GroupMatchMode matchMode = request.groupMatchMode() != null ? request.groupMatchMode() : GroupMatchMode.ALL;
        SeatingConfig config = new SeatingConfig(
                dH, dV, dD, SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS, effectiveGroupAttrs, matchMode);

        return engine.analyzeFeasibility(rooms, students, config, occupied, true);
    }

    private List<StudentAttribute> resolveGroupAttributes(List<StudentAttribute> requested,
                                                          List<Student> students) {
        if (requested != null && !requested.isEmpty()) {
            return requested;
        }
        List<String> storedClassmates = identityService.currentClassmateAttributes();
        if (!storedClassmates.isEmpty()) {
            return storedClassmates.stream().map(StudentAttribute::of).toList();
        }
        RosterSchemaView inspected = identityService.inspectSchema();
        if (!inspected.suggestedClassmateGroups().isEmpty()) {
            return inspected.suggestedClassmateGroups().get(0).attributes().stream()
                    .map(StudentAttribute::of)
                    .toList();
        }
        // Fallback if every column is unique or small test roster: pick first non-name attribute on students
        for (Student s : students) {
            for (String k : StudentAttributes.allPopulatedAttributes(s).keySet()) {
                if (!StudentAttributes.looksLikeIdentifier(k)) {
                    return List.of(StudentAttribute.of(k));
                }
            }
            for (String k : StudentAttributes.allPopulatedAttributes(s).keySet()) {
                return List.of(StudentAttribute.of(k));
            }
        }
        throw new IllegalSeatingConfigurationException(
                "No classmate grouping attributes found — configure classmate attributes in Roster Schema or pass groupAttributes.");
    }

    /**
     * The classmate attributes actually used for separation.
     *
     * <p>Starts from the institution's base classmate attributes (chosen once per
     * Excel upload) and appends every attribute used by a bucket-level
     * "Configure cohort" refinement, so a bucket that was split further is
     * separated at that finer granularity too.</p>
     */
    private List<StudentAttribute> effectiveSeparationAttributes(List<StudentAttribute> requested,
                                                                  List<Student> students) {
        List<StudentAttribute> base = resolveGroupAttributes(requested, students);
        List<String> keys = new ArrayList<>();
        for (StudentAttribute attribute : base) {
            keys.add(attribute.key());
        }
        for (CohortRule rule : cohortService.rules()) {
            for (String key : rule.groupAttributeList()) {
                if (!keys.contains(key)) {
                    keys.add(key);
                }
            }
        }
        return keys.stream().map(StudentAttribute::of).toList();
    }

    private void recordRound(String scopeAttribute,
                             String scopeValue,
                             ArrangeRequest.StrictnessLevel level,
                             SeatingConfig config,
                             int seated,
                             int targetCount,
                             String filterAttribute,
                             List<String> filterValues) {
        Long institutionId = tenant.currentId();
        String scopeKey = SeatingRound.scopeKeyOf(scopeValue);
        SeatingRound round = roundRepository
                .findByInstitutionIdAndScopeKey(institutionId, scopeKey)
                .orElseGet(SeatingRound::new);
        round.setInstitutionId(institutionId);
        round.setScopeKey(scopeKey);
        round.setScopeAttribute(scopeAttribute);
        String canonicalStrictness = switch (level) {
            case EXTREME -> "EXTREME";
            case INTERMEDIATE -> "INTERMEDIATE";
            case NORMAL -> config.diagonalGap() > 0 ? "EXTREME" : (config.verticalGap() > 0 ? "INTERMEDIATE" : "NORMAL");
        };
        round.setStrictness(canonicalStrictness);
        round.setHorizontalGap(config.horizontalGap());
        round.setVerticalGap(config.verticalGap());
        round.setDiagonalGap(config.diagonalGap());
        round.setGroupAttributes(SeatingRound.formatGroupAttributes(config.effectiveGroupKey()));
        round.setGroupMatchMode(config.groupMatchMode());
        round.setSeatedCount(seated);
        round.setTargetCount(targetCount);
        round.setFilterAttribute(filterAttribute);
        if (filterValues != null && !filterValues.isEmpty()) {
            String joined = String.join("||", filterValues);
            round.setFilterValuesSummary(joined.length() > 995 ? joined.substring(0, 995) : joined);
        } else {
            round.setFilterValuesSummary(null);
        }
        roundRepository.save(round);
    }

    @Transactional
    public SeatingResponse.AllocationDto moveAllocation(Long allocationId, MoveAllocationRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("A JSON request body is required");
        }

        SeatingAllocation allocation = allocationRepository.findByIdWithDetails(allocationId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Seating allocation " + allocationId + " not found"));
        tenant.require(allocation, "Seating allocation " + allocationId);

        ExamRoom room = examRoomRepository.findWithBenchesById(request.roomId())
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Exam hall " + request.roomId() + " not found"));
        tenant.require(room, "Exam hall " + request.roomId());
        ensureBenchGridPersisted(room);

        Bench bench = room.getBenches().stream()
                .filter(b -> b.getRowIndex() == request.benchRow() && b.getColIndex() == request.benchCol())
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Hall '%s' has no bench at row %d, column %d".formatted(
                                room.getHallName(), request.benchRow(), request.benchCol())));
        int slot = request.slot();
        if (slot > bench.getBenchType().getSeatsPerBench()) {
            throw new IllegalArgumentException(
                    "Bench %d,%d in hall '%s' is a %s and has no slot %d".formatted(
                            bench.getRowIndex(), bench.getColIndex(), room.getHallName(),
                            bench.getBenchType(), slot));
        }

        List<SeatingAllocation> inRoom = tenant.visible(
                allocationRepository.findByBenchRoomIdWithDetails(room.getId()));
        for (SeatingAllocation other : inRoom) {
            if (Objects.equals(other.getId(), allocationId)) {
                continue;
            }
            if (Objects.equals(other.getBench().getId(), bench.getId()) && other.getSlotNumber() == slot) {
                throw new SeatingConflictException(
                        "Seat row %d, column %d, slot %d in hall '%s' is already taken"
                                .formatted(bench.getRowIndex(), bench.getColIndex(), slot, room.getHallName()));
            }
        }

        SeatingConfig config = roundConfigFor(allocation);
        PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                allocation.getStudent(),
                bench,
                slot,
                config,
                inRoom.stream()
                        .filter(other -> !Objects.equals(other.getId(), allocationId))
                        .map(SeatOccupant::of)
                        .toList());
        var scheme = identityService.currentScheme().orElse(null);
        if (!verdict.allowed()) {
            throw new SeatingConflictException(
                    "Cannot seat '%s' at row %d, column %d, slot %d: %s".formatted(
                            StudentAttributes.displayLabel(allocation.getStudent(), scheme),
                            bench.getRowIndex(),
                            bench.getColIndex(),
                            slot,
                            verdict.reason()));
        }

        allocation.setBench(bench);
        allocation.setSlotNumber(slot);
        List<String> moveGroupKeys = config.effectiveGroupKey().stream()
                .map(StudentAttribute::key)
                .toList();
        return SeatingResponse.AllocationDto.from(
                allocationRepository.saveAndFlush(allocation), scheme, moveGroupKeys);
    }

    private SeatingConfig roundConfigFor(SeatingAllocation allocation) {
        Long institutionId = tenant.currentId();
        Optional<SeatingRound> round = roundRepository.findByInstitutionIdAndScopeKey(
                institutionId, SeatingRound.scopeKeyOf(allocation.getScopeKey()));
        if (round.isEmpty()) {
            round = roundRepository.findByInstitutionIdAndScopeKey(
                    institutionId, SeatingRound.WHOLE_ROSTER_KEY);
        }
        return round.map(SeatingRound::toSeatingConfig)
                .orElseGet(() -> {
                    List<StudentAttribute> fallback = resolveGroupAttributes(
                            null, List.of(allocation.getStudent()));
                    return SeatingConfig.normal(fallback);
                });
    }

    @Transactional(readOnly = true)
    public SeatingResponse view(String rawScopeKey) {
        String scope = blankToNull(rawScopeKey);
        List<SeatingAllocation> allocations = tenant.visible(allocationRepository.findAllWithDetails(scope));
        var scheme = identityService.currentScheme().orElse(null);
        List<String> defaultGroupKeys = identityService.currentClassmateAttributes();
        Optional<SeatingRound> activeRound = roundRepository.findByInstitutionIdAndScopeKey(
                tenant.currentId(), SeatingRound.scopeKeyOf(scope));
        if (activeRound.isEmpty() && scope == null) {
            activeRound = roundRepository.findByInstitutionIdOrderByUpdatedAtDesc(tenant.currentId())
                    .stream().findFirst();
        }
        List<String> roundGroupKeys = activeRound
                .map(SeatingRound::parseGroupAttributes)
                .filter(list -> !list.isEmpty())
                .map(list -> list.stream().map(StudentAttribute::key).toList())
                .orElse(defaultGroupKeys);

        List<SeatingResponse.AllocationDto> dtos = allocations.stream()
                .map(a -> SeatingResponse.AllocationDto.from(a, scheme, roundGroupKeys))
                .toList();

        Map<Long, ExamRoom> roomsInPlan = new LinkedHashMap<>();
        Set<Long> seatedStudents = new HashSet<>();
        for (SeatingAllocation allocation : allocations) {
            if (allocation.getStudent() != null && allocation.getStudent().getId() != null) {
                seatedStudents.add(allocation.getStudent().getId());
            }
            ExamRoom room = allocation.getBench() == null ? null : allocation.getBench().getRoom();
            if (room != null && room.getId() != null) {
                roomsInPlan.putIfAbsent(room.getId(), room);
            }
        }
        int totalSlots = roomsInPlan.values().stream().mapToInt(ExamRoom::getCapacity).sum();

        int unplaced = 0;
        if (!dtos.isEmpty() && activeRound.isPresent() && activeRound.get().getTargetCount() != null) {
            unplaced = Math.max(0, activeRound.get().getTargetCount() - dtos.size());
        } else {
            String scopeAttr = activeRound.map(SeatingRound::getScopeAttribute)
                    .orElseGet(identityService::currentScopeAttribute);
            List<Student> allStudents = tenant.visible(studentRepository.findAll());
            List<Student> inScopeStudents = (scope == null || scopeAttr == null || scopeAttr.isBlank())
                    ? allStudents
                    : allStudents.stream()
                            .filter(s -> equalsIgnoreCaseTrimmed(StudentAttributes.value(s, scopeAttr), scope))
                            .toList();
            for (Student student : inScopeStudents) {
                if (student.getId() == null || !seatedStudents.contains(student.getId())) {
                    unplaced++;
                }
            }
        }
        return SeatingResponse.of(dtos.size(), unplaced, totalSlots, 0, unplaced == 0, dtos);
    }

    @Transactional
    public void clear(String rawScopeKey) {
        List<SeatingAllocation> owned = tenant.visible(
                allocationRepository.findAllWithDetails(blankToNull(rawScopeKey)));
        if (!owned.isEmpty()) {
            allocationRepository.deleteAll(owned);
            allocationRepository.flush();
        }
    }

    private void ensureBenchGridPersisted(ExamRoom room) {
        final int expected;
        try {
            expected = Math.multiplyExact(room.getRows(), room.getColumns());
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("Hall '" + room.getHallName()
                    + "' has dimensions that overflow its bench grid");
        }
        if (room.getBenches().size() == expected) {
            return;
        }
        if (!room.getBenches().isEmpty()) {
            throw new IllegalArgumentException("Hall '" + room.getHallName()
                    + "' has an incomplete bench grid (" + room.getBenches().size()
                    + " of " + expected + ") — recreate the hall to rebuild its grid");
        }
        for (int r = 1; r <= room.getRows(); r++) {
            for (int c = 1; c <= room.getColumns(); c++) {
                room.getBenches().add(Bench.builder()
                        .room(room)
                        .rowIndex(r)
                        .colIndex(c)
                        .build());
            }
        }
        examRoomRepository.save(room);
    }

    private static Long roomIdOf(SeatingAllocation allocation) {
        Bench bench = allocation.getBench();
        ExamRoom room = bench == null ? null : bench.getRoom();
        return room == null ? null : room.getId();
    }

    private static boolean equalsIgnoreCaseTrimmed(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        return a.trim().equalsIgnoreCase(b.trim());
    }

    private static String normalizeFilterAttribute(String rawFilterAttribute) {
        if (rawFilterAttribute == null || rawFilterAttribute.isBlank()) {
            return null;
        }
        return Arrays.stream(rawFilterAttribute.split("\\+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(StudentAttributes::normalize)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.joining("+"));
    }

    /**
     * Computes a student's category label for single-column (e.g. {@code "branch"})
     * or composite (e.g. {@code "department+semester"}) category filtering.
     */
    public static String categoryLabelOf(Student student, String rawFilterAttribute) {
        List<String> keys = Arrays.stream((rawFilterAttribute == null ? "" : rawFilterAttribute).split("\\+"))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(StudentAttributes::normalize)
                .filter(s -> !s.isEmpty())
                .toList();
        if (keys.isEmpty()) {
            return "Unspecified";
        }
        if (keys.size() == 1) {
            String v = blankToNull(StudentAttributes.value(student, keys.get(0)));
            return v != null ? v : "Unspecified";
        }
        return keys.stream()
                .map(k -> {
                    String v = blankToNull(StudentAttributes.value(student, k));
                    return v != null ? v : "Unspecified";
                })
                .collect(Collectors.joining(" · "));
    }

    private static List<Student> applyCategoryFilter(List<Student> source,
                                                     String rawFilterAttribute,
                                                     List<String> filterValues) {
        if (rawFilterAttribute == null || rawFilterAttribute.isBlank()
                || filterValues == null || filterValues.isEmpty()) {
            return source;
        }
        Set<String> allowed = new HashSet<>();
        for (String val : filterValues) {
            if (val == null) continue;
            String trimmed = val.trim();
            if (trimmed.isEmpty()) continue;
            if ("__UNSPECIFIED__".equalsIgnoreCase(trimmed) || "—".equals(trimmed)) {
                allowed.add("unspecified");
            } else {
                allowed.add(trimmed.toLowerCase(Locale.ROOT));
            }
        }
        if (allowed.isEmpty()) {
            return source;
        }
        return source.stream()
                .filter(s -> allowed.contains(
                        categoryLabelOf(s, rawFilterAttribute).trim().toLowerCase(Locale.ROOT)))
                .toList();
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
