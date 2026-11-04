package com.examseating.service;

import com.examseating.dto.ArrangeRequest;
import com.examseating.dto.SeatingResponse;
import com.examseating.engine.SeatingArrangementEngine;
import com.examseating.engine.SeatingArrangementEngine.ConflictScope;
import com.examseating.engine.SeatingArrangementEngine.InsufficientCapacityException;
import com.examseating.engine.SeatingArrangementEngine.SeatingConfig;
import com.examseating.engine.SeatingArrangementEngine.SeatingPlan;
import com.examseating.engine.SeatingArrangementEngine.SeatKey;
import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Student;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.tenancy.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Orchestrates a seating round: load data from the DB → run the engine →
 * persist the result → map it to DTOs.
 *
 * <p>The whole method is one {@code @Transactional} unit, so a failed solve or
 * a duplicate-seat error rolls back every deletion and insertion.</p>
 *
 * <p><b>Multi-round safety:</b> when a round is limited to a subject (or to a
 * subset of halls), allocations belonging to other rounds are preserved and
 * their seats are passed to the engine as pre-occupied. Without that, the
 * engine could reuse a seat that is already stored for another paper and the
 * unique {@code (bench_id, slot_number)} constraint would fail.</p>
 */
@Service
@RequiredArgsConstructor
public class SeatingService {

    private final StudentRepository studentRepository;
    private final ExamRoomRepository examRoomRepository;
    private final SeatingAllocationRepository allocationRepository;
    private final TenantSupport tenant;
    private final QuotaService quotas;

    /** The engine is stateless — one shared instance is fine. */
    private final SeatingArrangementEngine engine = new SeatingArrangementEngine();

    /**
     * Runs a full seating round and persists it.
     *
     * @return response DTO (built inside the transaction on purpose)
     */
    @Transactional
    public SeatingResponse arrange(ArrangeRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("A JSON request body is required");
        }

        // 1) Load halls (all, or only the requested ones).
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

        String subjectCode = blankToNull(request.subjectCode());
        boolean clearExisting = request.clearExisting() == null || request.clearExisting();
        // Validate the scope BEFORE doing any expensive solve.
        if (!clearExisting && subjectCode == null) {
            throw new IllegalArgumentException(
                    "clearExisting=false requires a subjectCode filter so other rounds are preserved");
        }

        // 2) Work out which persisted seats are being replaced and which must be
        //    treated as pre-occupied by the engine.
        List<SeatingAllocation> existing = tenant.visible(allocationRepository.findAllWithDetails(null));
        List<SeatingAllocation> toDelete = new ArrayList<>();
        Set<SeatKey> occupied = new HashSet<>();
        for (SeatingAllocation allocation : existing) {
            boolean inScope = !roomSubset || roomIdSet.contains(roomIdOf(allocation));
            if (!inScope) {
                continue; // untouched round in another hall
            }
            boolean replaced = (subjectCode == null)
                    ? clearExisting
                    : Objects.equals(blankToNull(allocation.getSubjectCode()), subjectCode);
            if (replaced) {
                toDelete.add(allocation);
            } else {
                occupied.add(SeatKey.of(allocation.getBench(), allocation.getSlotNumber()));
            }
        }

        int freeSeats = rooms.stream().mapToInt(ExamRoom::getCapacity).sum() - occupied.size();
        if (freeSeats < 0) {
            freeSeats = 0;
        }

        // 3) Load students — all, or only those sitting the requested paper.
        List<Student> students = tenant.visible((subjectCode == null)
                ? studentRepository.findAll()
                : studentRepository.findBySubjectCode(subjectCode));
        // Generation quota is checked before the expensive solve, counting the
        // in-scope students as the seats this round will consume.
        quotas.checkGeneration(students.size());
        if (students.size() > freeSeats) {
            throw new InsufficientCapacityException(
                    "Only %d seats are free for this round but %d students were requested"
                            .formatted(freeSeats, students.size()));
        }

        // 4) Build the engine configuration from the request: explicit gaps win,
        //    otherwise the difficulty preset decides.
        ArrangeRequest.StrictnessLevel level = (request.strictness() != null)
                ? request.strictness()
                : ArrangeRequest.StrictnessLevel.BASIC;
        int dH = (request.horizontalGap() != null) ? request.horizontalGap() : 1;
        int dV = (request.verticalGap() != null) ? request.verticalGap()
                : (level == ArrangeRequest.StrictnessLevel.INTERMEDIATE
                        || level == ArrangeRequest.StrictnessLevel.ADVANCED
                        || level == ArrangeRequest.StrictnessLevel.EXTREME ? 1 : 0);
        int dD = (request.diagonalGap() != null) ? request.diagonalGap()
                : (level == ArrangeRequest.StrictnessLevel.ADVANCED
                        || level == ArrangeRequest.StrictnessLevel.EXTREME ? 1 : 0);
        ConflictScope scope = (request.conflictScope() != null) ? request.conflictScope() :
                ConflictScope.DEPARTMENT_AND_YEAR;
        com.examseating.engine.SeatingArrangementEngine.BenchPairingRule pairingRule =
                (request.benchPairingRule() != null)
                        ? request.benchPairingRule()
                        : com.examseating.engine.SeatingArrangementEngine.BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR;
        boolean horizontalDeptIsolation = request.horizontalDeptIsolation() == null || request.horizontalDeptIsolation();

        SeatingConfig config = new SeatingConfig(
                dH,
                dV,
                dD,
                scope,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                pairingRule,
                horizontalDeptIsolation,
                request.groupAttributes(),
                request.groupMatchMode());

        // 5) Solve, honouring seats held by other rounds.
        SeatingPlan plan = (subjectCode == null)
                ? engine.arrange(rooms, students, config, occupied)
                : engine.arrangeForSubject(rooms, students, subjectCode, config, occupied);

        // 6) Replace only the in-scope allocations. Flushing the deletes before
        //    the inserts guarantees the unique (bench, slot) index is free.
        for (SeatingAllocation allocation : toDelete) {
            allocationRepository.delete(allocation);
        }
        allocationRepository.flush();

        // 7) Persist every seat.
        List<SeatingAllocation> saved = new ArrayList<>(plan.allocations().size());
        for (SeatingAllocation allocation : plan.allocations()) {
            allocation.setId(null);
            tenant.stamp(allocation);
            saved.add(allocationRepository.save(allocation));
        }
        quotas.recordGeneration(subjectCode, saved.size());
        List<SeatingResponse.AllocationDto> dtos = saved.stream()
                .map(SeatingResponse.AllocationDto::from)
                .toList();

        return SeatingResponse.of(dtos.size(), plan.unplacedStudents().size(),
                plan.totalSlots(), plan.totalBacktrackSteps(), plan.solved(), dtos);
    }

    /**
     * Read-only view of the persisted allocations. Metrics are derived from the
     * saved plan (not guessed from room capacities) so GET and POST agree.
     */
    @Transactional(readOnly = true)
    public SeatingResponse view(String subjectCode) {
        String subject = blankToNull(subjectCode);
        List<SeatingAllocation> allocations = tenant.visible(allocationRepository.findAllWithDetails(subject));
        List<SeatingResponse.AllocationDto> dtos = allocations.stream()
                .map(SeatingResponse.AllocationDto::from)
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

        List<Student> students = tenant.visible((subject == null)
                ? studentRepository.findAll()
                : studentRepository.findBySubjectCode(subject));
        int unplaced = 0;
        for (Student student : students) {
            if (student.getId() == null || !seatedStudents.contains(student.getId())) {
                unplaced++;
            }
        }
        return SeatingResponse.of(dtos.size(), unplaced, totalSlots, 0, unplaced == 0, dtos);
    }

    /** Clears persisted allocations for one subject (or all when subject is null). */
    @Transactional
    public void clear(String subjectCode) {
        // Bulk deletes ignore tenant ownership, so resolve the owned rows first.
        List<SeatingAllocation> owned = tenant.visible(
                allocationRepository.findAllWithDetails(blankToNull(subjectCode)));
        if (!owned.isEmpty()) {
            allocationRepository.deleteAll(owned);
            allocationRepository.flush();
        }
    }

    /**
     * If a hall was created without its bench grid, persist an M×N grid of
     * benches so the engine can reference persisted Bench rows.
     */
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
                        .benchType(room.getBenchType())
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

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
