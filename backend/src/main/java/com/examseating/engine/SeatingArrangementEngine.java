package com.examseating.engine;

import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Student;
import com.examseating.entity.StudentAttributes;
import com.fasterxml.jackson.annotation.JsonCreator;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Pure dynamic deterministic constraint-satisfaction engine that distributes
 * students across exam halls while enforcing geometric anti-cheating separation
 * rules over arbitrary institution-defined cohort attributes.
 *
 * <h2>Inputs</h2>
 * <ul>
 *   <li>{@link ExamRoom} halls: each hall is an {@code M × N} grid of benches
 *       (1-based row/col) with {@code SINGLE_SEATER} or {@code DUAL_SEATER} capacity.</li>
 *   <li>{@link Student} roster: dynamic key-value attributes (e.g. {@code class},
 *       {@code section}, {@code wing}, {@code stream}, {@code batch}, {@code department}).</li>
 *   <li>{@link SeatingConfig}: horizontal gap {@code dH}, vertical gap {@code dV},
 *       diagonal gap {@code dD}, dynamic {@code groupAttributes}, {@link GroupMatchMode},
 *       and backtrack budget.</li>
 * </ul>
 *
 * <h2>Constraints enforced</h2>
 * <ol>
 *   <li><b>Dual-seater bench rule</b> — two students sharing the same dual bench
 *       may never conflict under the configured {@code (groupAttributes, groupMatchMode)}.</li>
 *   <li><b>Horizontal gap {@code dH}</b> — conflicting students in the same bench row
 *       must sit more than {@code dH} physical seats apart
 *       ({@code |physicalCol(a) - physicalCol(b)| > dH}).</li>
 *   <li><b>Vertical gap {@code dV}</b> — conflicting students in the same bench column
 *       must sit more than {@code dV} rows apart ({@code |r1 - r2| > dV}).</li>
 *   <li><b>Diagonal gap {@code dD}</b> — conflicting students in different rows and
 *       bench columns must satisfy {@code |r1 - r2| > dD} or {@code |c1 - c2| > dD}.</li>
 * </ol>
 */
public class SeatingArrangementEngine {

    /** Default safety budget for backtracking search (overridable per call). */
    public static final int DEFAULT_MAX_BACKTRACK_STEPS = 500_000;

    private static final int[][] SEARCH_PROFILES = {
            {0, 1, 2},
            {0, 2, 1},
            {2, 1, 0},
            {1, 2, 0},
    };

    private static final int STALL_LIMIT = 25_000;
    private static final long GRID_SEARCH_STEPS = 100_000;
    private static final long MAX_SOLVE_NANOS =
            TimeUnit.SECONDS.toNanos(Long.getLong("app.engine.solve-timeout-seconds", 30));

    // ------------------------------------------------------------------
    //  Public API
    // ------------------------------------------------------------------

    /** Seats all given students across the given rooms. */
    public SeatingPlan arrange(List<ExamRoom> rooms, List<Student> students, SeatingConfig config) {
        return arrange(rooms, students, config, Set.of());
    }

    /**
     * Seats all given students across the given rooms while treating
     * {@code occupiedSeats} as unavailable (used when multiple exam rounds share halls).
     */
    public SeatingPlan arrange(List<ExamRoom> rooms, List<Student> students, SeatingConfig config,
                               Set<SeatKey> occupiedSeats) {
        Objects.requireNonNull(rooms, "rooms must not be null");
        Objects.requireNonNull(students, "students must not be null");
        Objects.requireNonNull(config, "config must not be null");
        Objects.requireNonNull(occupiedSeats, "occupiedSeats must not be null");
        if (rooms.isEmpty()) {
            throw new IllegalSeatingConfigurationException("At least one ExamRoom is required");
        }
        for (ExamRoom room : rooms) {
            if (room == null) {
                throw new IllegalArgumentException("rooms must not contain null entries");
            }
        }
        for (Student student : students) {
            if (student == null) {
                throw new IllegalArgumentException("students must not contain null entries");
            }
        }
        validateRooms(rooms);
        List<ExamRoom> orderedRooms = orderedRooms(rooms);
        if (students.isEmpty()) {
            return new SeatingPlan(List.of(), List.of(), List.copyOf(orderedRooms), 0, 0, true);
        }
        validateCapacity(rooms, students.size(), occupiedSeats);
        validateGroupAttributesConfigured(students, config);
        validateGroupFeasibility(rooms, students, config);
        validateAttributeBalance(rooms, students, config);
        List<Student> orderedStudents = orderedStudents(students, config);

        SearchControl control = new SearchControl(System.nanoTime() + MAX_SOLVE_NANOS);
        SeatingPlan layerPlan = occupiedSeats.isEmpty()
                ? solveLayerDecomposed(orderedRooms, orderedStudents, config)
                : null;
        if (layerPlan != null) {
            return layerPlan;
        }

        SeatingSearchFailureException lastFailure = null;
        for (int profile = 0; profile < SEARCH_PROFILES.length; profile++) {
            try {
                return solve(orderedRooms, orderedStudents, config, profile, control, occupiedSeats);
            } catch (SeatingSearchFailureException e) {
                lastFailure = e;
            }
        }
        throw new SeatingUnsatisfiableException(
                "No seating layout found after trying %d deterministic search strategies: %s"
                        .formatted(SEARCH_PROFILES.length,
                                lastFailure == null ? "search exhausted" : lastFailure.getMessage()));
    }

    /**
     * Convenience: filters {@code students} to those whose dynamic {@code scopeAttribute}
     * matches {@code scopeValue} (case-insensitive), solves, and stamps {@code scopeKey}
     * on every resulting allocation.
     */
    public SeatingPlan arrangeForScope(List<ExamRoom> rooms, List<Student> students,
                                       String scopeAttribute, String scopeValue,
                                       SeatingConfig config) {
        return arrangeForScope(rooms, students, scopeAttribute, scopeValue, config, Set.of());
    }

    /** Scope-filtered arrange with pre-occupied seats (see {@link #arrange}). */
    public SeatingPlan arrangeForScope(List<ExamRoom> rooms, List<Student> students,
                                       String scopeAttribute, String scopeValue,
                                       SeatingConfig config, Set<SeatKey> occupiedSeats) {
        Objects.requireNonNull(scopeAttribute, "scopeAttribute must not be null");
        Objects.requireNonNull(scopeValue, "scopeValue must not be null");
        StudentAttribute attr = StudentAttribute.of(scopeAttribute);
        List<Student> filtered = students.stream()
                .filter(s -> attrEquals(attr.value(s), scopeValue))
                .toList();
        SeatingPlan raw = arrange(rooms, filtered, config, occupiedSeats);
        String trimmedScope = scopeValue.trim();
        for (SeatingAllocation allocation : raw.allocations()) {
            allocation.setScopeKey(trimmedScope);
        }
        return raw;
    }

    // ------------------------------------------------------------------
    //  Cohort-distribution & empty-seat feasibility analysis
    // ------------------------------------------------------------------

    public SeatingAdvisory analyzeFeasibility(List<ExamRoom> rooms, List<Student> students, SeatingConfig config) {
        return analyzeFeasibility(rooms, students, config, Set.of());
    }

    /**
     * Evaluates whether {@code students} can be seated in {@code rooms} under
     * {@code config} without relaxing any restriction, and calculates the minimum
     * empty buffer seating positions (and any additional seats/benches) required
     * when non-conflicting student diversity is insufficient to fill separation gaps.
     */
    public SeatingAdvisory analyzeFeasibility(List<ExamRoom> rooms,
                                              List<Student> students,
                                              SeatingConfig config,
                                              Set<SeatKey> occupiedSeats) {
        return analyzeFeasibility(rooms, students, config, occupiedSeats, true);
    }

    public SeatingAdvisory analyzeFeasibility(List<ExamRoom> rooms,
                                              List<Student> students,
                                              SeatingConfig config,
                                              Set<SeatKey> occupiedSeats,
                                              boolean verifyWithSolver) {
        Objects.requireNonNull(config, "config must not be null");
        Set<SeatKey> occupied = occupiedSeats == null ? Set.of() : occupiedSeats;
        int studentCount = students == null ? 0 : students.size();

        if (rooms == null || rooms.isEmpty()) {
            return new SeatingAdvisory(
                    false, false, "NO_HALLS",
                    studentCount, 0, studentCount, 0,
                    studentCount, studentCount,
                    null, 0, 0, 0,
                    "No examination halls are available or selected. Create or select at least one hall before generating.",
                    List.of("Add an examination hall on the Halls page or include more halls in the hall filter."));
        }

        validateRooms(rooms);
        List<ExamRoom> sortedRooms = new ArrayList<>(orderedRooms(rooms));
        sortedRooms.sort(Comparator.comparingDouble((ExamRoom r) -> {
            int cap = Math.max(1, r.getCapacity());
            return -((double) roomGroupBound(r, config) / cap);
        }));

        int totalFreeSeats = 0;
        int dominantSeatsPerBench = 1;
        int dualRoomCount = 0;
        int totalRowsSum = 0;
        for (ExamRoom room : sortedRooms) {
            int spb = room.getBenchType() != null ? room.getBenchType().getSeatsPerBench() : 1;
            if (spb > 1) {
                dualRoomCount++;
            }
            totalRowsSum += Math.max(1, room.getRows());
            for (Bench bench : normalizedBenchGrid(room)) {
                for (int slot = 1; slot <= spb; slot++) {
                    if (!occupied.contains(SeatKey.of(bench, slot))) {
                        totalFreeSeats++;
                    }
                }
            }
        }
        if (dualRoomCount * 2 >= sortedRooms.size()) {
            dominantSeatsPerBench = 2;
        }
        int refRows = Math.max(1, Math.round((float) totalRowsSum / sortedRooms.size()));

        if (studentCount == 0) {
            return new SeatingAdvisory(
                    true, false, "NO_STUDENTS",
                    0, totalFreeSeats, 0, 0, 0, 0,
                    null, 0, 0, 0,
                    "No students match the selected roster scope.",
                    List.of("Import or add students on the Students page, or clear the round scope filter."));
        }

        // 1) Identify largest mutually-conflicting student cohort.
        String dominantLabel = "";
        int dominantSize = 0;
        if (config.groupMatchMode() == GroupMatchMode.ANY) {
            for (StudentAttribute attr : config.effectiveGroupKey()) {
                Map<String, Integer> counts = new LinkedHashMap<>();
                Map<String, String> rawLabels = new LinkedHashMap<>();
                for (Student s : students) {
                    String rawVal = attr.value(s);
                    String cVal = canon(rawVal);
                    counts.merge(cVal, 1, Integer::sum);
                    rawLabels.putIfAbsent(cVal, (rawVal == null || rawVal.isBlank()) ? "(none)" : rawVal.trim());
                }
                for (Map.Entry<String, Integer> e : counts.entrySet()) {
                    if (e.getValue() > dominantSize) {
                        dominantSize = e.getValue();
                        dominantLabel = attr.key() + "=" + rawLabels.get(e.getKey());
                    }
                }
            }
        } else {
            Map<Group, Integer> counts = new LinkedHashMap<>();
            for (Student s : students) {
                counts.merge(groupOf(s, config), 1, Integer::sum);
            }
            Group bestKey = null;
            for (Map.Entry<Group, Integer> e : counts.entrySet()) {
                if (e.getValue() > dominantSize) {
                    dominantSize = e.getValue();
                    bestKey = e.getKey();
                }
            }
            if (bestKey != null) {
                dominantLabel = groupLabel(bestKey, config);
            }
        }
        int otherStudents = Math.max(0, studentCount - dominantSize);

        // 2) Greedily pack a single cohort across actual free seats in the rooms to measure:
        //    (a) exact single-cohort seat ceiling in current halls (`maxCohortSeats`), and
        //    (b) exact seat span + non-cohort gap seats needed to place `dominantSize` members.
        int maxCohortSeats = 0;
        int traversedFreeSeats = 0;
        int spanSeatsForDominant = -1;
        int dualBenchMateGapsForDominant = 0;

        for (ExamRoom room : sortedRooms) {
            int spb = room.getBenchType() != null ? room.getBenchType().getSeatsPerBench() : 1;
            List<Bench> benches = normalizedBenchGrid(room);
            List<Bench> placedBenchesInRoom = new ArrayList<>();
            List<Integer> placedSlotsInRoom = new ArrayList<>();

            for (Bench bench : benches) {
                boolean freeSlot1 = !occupied.contains(SeatKey.of(bench, 1));
                boolean freeSlot2 = spb > 1 && !occupied.contains(SeatKey.of(bench, 2));
                for (int slot = 1; slot <= spb; slot++) {
                    if (occupied.contains(SeatKey.of(bench, slot))) {
                        continue;
                    }
                    traversedFreeSeats++;
                    boolean conflicts = false;
                    for (int idx = 0; idx < placedBenchesInRoom.size(); idx++) {
                        Bench prevBench = placedBenchesInRoom.get(idx);
                        int prevSlot = placedSlotsInRoom.get(idx);
                        if (sameBench(bench, prevBench)
                                || withinGap(bench, slot, prevBench, prevSlot, config)) {
                            conflicts = true;
                            break;
                        }
                    }
                    if (!conflicts) {
                        placedBenchesInRoom.add(bench);
                        placedSlotsInRoom.add(slot);
                        maxCohortSeats++;
                        if (maxCohortSeats <= dominantSize && spb > 1 && freeSlot1 && freeSlot2) {
                            dualBenchMateGapsForDominant++;
                        }
                        if (maxCohortSeats == dominantSize) {
                            spanSeatsForDominant = traversedFreeSeats;
                        }
                    }
                }
            }
        }

        // 3) Case A: Current halls CANNOT fit the dominant cohort (or even raw headcount).
        if (totalFreeSeats < studentCount || maxCohortSeats < dominantSize) {
            int unplacedCohort = Math.max(0, dominantSize - maxCohortSeats);
            int extraSeatsForCohort = unplacedCohort > 0
                    ? estimateAdditionalSeatsForCohort(unplacedCohort, config, dominantSeatsPerBench, refRows)
                    : 0;
            int minTotalSeats = Math.max(studentCount,
                    unplacedCohort > 0
                            ? totalFreeSeats + extraSeatsForCohort
                            : studentCount + Math.max(0, Math.max(spanSeatsForDominant - dominantSize, dualBenchMateGapsForDominant) - otherStudents));
            int emptyBufferSeats = Math.max(0, minTotalSeats - studentCount);
            int additionalSeats = Math.max(1, minTotalSeats - totalFreeSeats);
            int additionalBenches = ceilDiv(additionalSeats, dominantSeatsPerBench);

            boolean headcountShortage = totalFreeSeats < studentCount;
            String status = headcountShortage
                    ? "INSUFFICIENT_SEATS_FOR_HEADCOUNT"
                    : "INSUFFICIENT_SEATS_FOR_SEPARATION";

            String msg;
            if (headcountShortage && emptyBufferSeats > 0) {
                msg = ("No valid arrangement is possible: %d students require at least %d total seating positions "
                        + "(%d student seats + %d mandatory empty buffer positions for cohort '%s' with %d students "
                        + "and %d other-group student(s)), while only %d seat(s) are currently available. "
                        + "Add at least %d seating position(s) (%d bench(es)).")
                        .formatted(studentCount, minTotalSeats, studentCount, emptyBufferSeats,
                                dominantLabel, dominantSize, otherStudents, totalFreeSeats,
                                additionalSeats, additionalBenches);
            } else if (headcountShortage) {
                msg = ("No valid arrangement is possible: %d students are scheduled, while only %d free seat(s) "
                        + "are available across the selected halls. Add at least %d seating position(s) (%d bench(es)).")
                        .formatted(studentCount, totalFreeSeats, additionalSeats, additionalBenches);
            } else {
                msg = ("No valid arrangement is possible under the configured seating restrictions without relaxing rules: "
                        + "cohort '%s' has %d students, which can occupy at most %d seat(s) across the current %d seat(s) "
                        + "when only %d other-group student(s) are available to fill separation gaps. "
                        + "At least %d empty buffer position(s) (%d total seats) are needed — add at least %d additional "
                        + "seating position(s) (%d bench(es)).")
                        .formatted(dominantLabel, dominantSize, maxCohortSeats, totalFreeSeats,
                                otherStudents, emptyBufferSeats, minTotalSeats, additionalSeats, additionalBenches);
            }

            List<String> suggestions = new ArrayList<>();
            suggestions.add("Add or select halls providing at least %d more seating position(s) (~%d bench(es))."
                    .formatted(additionalSeats, additionalBenches));
            if (otherStudents < dominantSize) {
                suggestions.add("Combine another non-conflicting student group/paper into the same session to fill separation gaps.");
            }
            if (config.diagonalGap() > 0 || config.verticalGap() > 0) {
                suggestions.add("If hall capacity cannot be expanded, consider switching from Extreme / Rigid or Intermediate to a lower restriction level.");
            }
            return new SeatingAdvisory(
                    false,
                    emptyBufferSeats > 0,
                    status,
                    studentCount,
                    totalFreeSeats,
                    minTotalSeats,
                    emptyBufferSeats,
                    additionalSeats,
                    additionalBenches,
                    dominantLabel,
                    dominantSize,
                    otherStudents,
                    maxCohortSeats,
                    msg,
                    suggestions);
        }

        // 4) Single-cohort bound and raw capacity both pass; compute mandatory empty buffer seats.
        int gapPositionsNeeded = Math.max(
                Math.max(0, spanSeatsForDominant - dominantSize),
                dualBenchMateGapsForDominant);
        int emptyBufferSeats = Math.max(0, gapPositionsNeeded - otherStudents);
        int minTotalSeats = Math.min(totalFreeSeats, studentCount + emptyBufferSeats);

        // 5) Verify multi-cohort geometric satisfiability via a bounded read-only dry-run solve.
        if (verifyWithSolver && studentCount <= 600) {
            SeatingConfig probeConfig = new SeatingConfig(
                    config.horizontalGap(),
                    config.verticalGap(),
                    config.diagonalGap(),
                    Math.min(config.maxBacktrackSteps(), 3_000),
                    config.groupAttributes(),
                    config.groupMatchMode());
            try {
                this.arrange(rooms, students, probeConfig, occupied);
            } catch (SeatingEngineException ex) {
                int extraSeats = Math.max(dominantSeatsPerBench * 2, ceilDiv(studentCount, 5));
                int extraBenches = ceilDiv(extraSeats, dominantSeatsPerBench);
                int estEmpty = Math.max(emptyBufferSeats, (totalFreeSeats + extraSeats) - studentCount);
                String msg = ("No valid arrangement is possible in the current %d seat(s) without violating your "
                        + "separation rules (%s). Multiple student cohorts contend for separated grid positions — "
                        + "add approximately %d+ additional seating position(s) (%d+ bench(es)) so empty buffer "
                        + "seats can absorb geometric conflicts.")
                        .formatted(totalFreeSeats, ex.getMessage(), extraSeats, extraBenches);
                return new SeatingAdvisory(
                        false,
                        true,
                        "INSUFFICIENT_SEATS_FOR_SEPARATION",
                        studentCount,
                        totalFreeSeats,
                        studentCount + estEmpty,
                        estEmpty,
                        extraSeats,
                        extraBenches,
                        dominantLabel,
                        dominantSize,
                        otherStudents,
                        maxCohortSeats,
                        msg,
                        List.of(
                                "Add at least %d more seating position(s) (%d bench(es)) to provide separation buffer space."
                                        .formatted(extraSeats, extraBenches),
                                "Or reduce the restriction level (for example from Extreme / Rigid to Intermediate or Normal)."));
            }
        }

        if (emptyBufferSeats > 0) {
            int spareSeatsLeft = Math.max(0, totalFreeSeats - minTotalSeats);
            String msg = ("Based on the current student distribution and selected seating restrictions, "
                    + "an additional %d empty seating position(s) are required to generate a valid arrangement "
                    + "(minimum %d total seats for %d students; %d seat(s) available, %d spare after buffer). "
                    + "Cohort '%s' has %d students while only %d student(s) from other groups are available to fill separation gaps.")
                    .formatted(emptyBufferSeats, minTotalSeats, studentCount, totalFreeSeats, spareSeatsLeft,
                            dominantLabel, dominantSize, otherStudents);
            return new SeatingAdvisory(
                    true,
                    true,
                    "FEASIBLE_WITH_EMPTY_SEATS",
                    studentCount,
                    totalFreeSeats,
                    minTotalSeats,
                    emptyBufferSeats,
                    0,
                    0,
                    dominantLabel,
                    dominantSize,
                    otherStudents,
                    maxCohortSeats,
                    msg,
                    List.of(
                            "Your selected halls have enough capacity (%d seats >= %d required) — the engine will automatically leave empty buffer seats to preserve 100%% rule compliance."
                                    .formatted(totalFreeSeats, minTotalSeats),
                            "To reduce empty seats, combine another non-conflicting student group into this exam session."));
        }

        return new SeatingAdvisory(
                true,
                false,
                "OPTIMAL",
                studentCount,
                totalFreeSeats,
                studentCount,
                0,
                0,
                0,
                dominantLabel,
                dominantSize,
                otherStudents,
                maxCohortSeats,
                "Student group diversity and hall capacity (%d seats for %d students) can satisfy all selected restrictions without mandatory empty buffer seats."
                        .formatted(totalFreeSeats, studentCount),
                List.of());
    }

    /**
     * Computes the minimum seats in an additional hall (matching the institution's
     * typical bench type and row count) needed to place {@code remainingCohort}
     * members of a single cohort under {@code config}.
     */
    private static int estimateAdditionalSeatsForCohort(int remainingCohort,
                                                        SeatingConfig config,
                                                        int seatsPerBench,
                                                        int refRows) {
        if (remainingCohort <= 0) {
            return 0;
        }
        ExamRoom.BenchType benchType = seatsPerBench == 2
                ? ExamRoom.BenchType.DUAL_SEATER
                : ExamRoom.BenchType.SINGLE_SEATER;
        int minRows = refRows <= 1 ? 1 : 2;
        int maxRows = Math.max(minRows + 2, refRows + 2);
        int bestSeats = Integer.MAX_VALUE;

        for (int r = minRows; r <= maxRows; r++) {
            int low = 1;
            int high = Math.max(4, remainingCohort * (Math.max(1, config.horizontalGap()) + 1) + 2);
            while (low < high) {
                int mid = (low + high) >>> 1;
                ExamRoom probe = ExamRoom.builder()
                        .hallName("probe")
                        .rows(r)
                        .columns(mid)
                        .benchType(benchType)
                        .build();
                if (roomGroupBound(probe, config) >= remainingCohort) {
                    high = mid;
                } else {
                    low = mid + 1;
                }
            }
            ExamRoom candidate = ExamRoom.builder()
                    .hallName("probe")
                    .rows(r)
                    .columns(low)
                    .benchType(benchType)
                    .build();
            if (roomGroupBound(candidate, config) >= remainingCohort) {
                // Measure exact prefix seat span inside this candidate hall for `remainingCohort` placements
                int placed = 0;
                int traversed = 0;
                List<Bench> placedBenches = new ArrayList<>();
                List<Integer> placedSlots = new ArrayList<>();
                for (Bench bench : normalizedBenchGrid(candidate)) {
                    for (int slot = 1; slot <= seatsPerBench; slot++) {
                        traversed++;
                        boolean conflict = false;
                        for (int idx = 0; idx < placedBenches.size(); idx++) {
                            if (sameBench(bench, placedBenches.get(idx))
                                    || withinGap(bench, slot, placedBenches.get(idx), placedSlots.get(idx), config)) {
                                conflict = true;
                                break;
                            }
                        }
                        if (!conflict) {
                            placedBenches.add(bench);
                            placedSlots.add(slot);
                            placed++;
                            if (placed == remainingCohort) {
                                int seatsNeeded = (seatsPerBench == 2 && slot == 1) ? traversed + 1 : traversed;
                                bestSeats = Math.min(bestSeats, seatsNeeded);
                                break;
                            }
                        }
                    }
                    if (placed == remainingCohort) {
                        break;
                    }
                }
                bestSeats = Math.min(bestSeats, r * low * seatsPerBench);
            }
        }
        return bestSeats == Integer.MAX_VALUE ? remainingCohort * seatsPerBench : Math.max(remainingCohort, bestSeats);
    }

    // ------------------------------------------------------------------
    //  Placement validation (manual seat moves)
    // ------------------------------------------------------------------

    /**
     * Checks whether {@code candidate} may take {@code (targetBench, targetSlot)}
     * given the students already seated in that hall under {@code config}.
     */
    public static PlacementVerdict checkPlacement(Student candidate, Bench targetBench, int targetSlot,
                                                  SeatingConfig config, List<SeatOccupant> occupants) {
        Objects.requireNonNull(candidate, "candidate must not be null");
        Objects.requireNonNull(targetBench, "targetBench must not be null");
        Objects.requireNonNull(config, "config must not be null");
        if (occupants == null || occupants.isEmpty()) {
            return PlacementVerdict.ok();
        }
        ExamRoom targetRoom = targetBench.getRoom();
        for (SeatOccupant occupant : occupants) {
            if (occupant == null || occupant.student() == null || occupant.bench() == null) {
                continue;
            }
            Student other = occupant.student();
            Bench otherBench = occupant.bench();
            if (sameBench(targetBench, otherBench)) {
                if (keyConflicts(candidate, other, config)) {
                    return PlacementVerdict.blocked(
                            "the bench-mate seat is taken by '%s' from a conflicting cohort"
                                    .formatted(StudentAttributes.displayLabel(other)), other);
                }
                continue;
            }
            if (targetRoom != null && otherBench.getRoom() != null
                    && !sameRoom(targetRoom, otherBench.getRoom())) {
                continue;
            }
            if (!withinGap(targetBench, targetSlot, otherBench, occupant.slotNumber(), config)) {
                continue;
            }
            boolean horizontal = horizontalBetween(targetBench, targetSlot, otherBench,
                    occupant.slotNumber(), config);
            if (keyConflicts(candidate, other, config)) {
                return PlacementVerdict.blocked(
                        horizontal
                                ? "the adjacent seat in this row is occupied by conflicting student '%s'"
                                        .formatted(StudentAttributes.displayLabel(other))
                                : "the seat is within the separation gap of '%s'"
                                        .formatted(StudentAttributes.displayLabel(other)),
                        other);
            }
        }
        return PlacementVerdict.ok();
    }

    private static boolean sameBench(Bench a, Bench b) {
        if (a == b) {
            return true;
        }
        if (a.getId() != null && a.getId().equals(b.getId())) {
            return true;
        }
        if (a.getRowIndex() != b.getRowIndex() || a.getColIndex() != b.getColIndex()) {
            return false;
        }
        return sameRoom(a.getRoom(), b.getRoom());
    }

    private static boolean sameRoom(ExamRoom a, ExamRoom b) {
        if (a == b) {
            return true;
        }
        if (a == null || b == null) {
            return true;
        }
        return a.getId() != null && a.getId().equals(b.getId());
    }

    // ------------------------------------------------------------------
    //  Nested types
    // ------------------------------------------------------------------

    /** Dynamic student attribute used to define conflict groups or round scopes. */
    public record StudentAttribute(String key) implements Comparable<StudentAttribute> {

        public StudentAttribute {
            String normalized = StudentAttributes.normalize(key);
            if (normalized.isEmpty()) {
                throw new IllegalArgumentException("An attribute name must not be blank");
            }
            key = normalized;
        }

        @JsonCreator
        public static StudentAttribute of(String raw) {
            return new StudentAttribute(raw);
        }

        public String value(Student student) {
            return StudentAttributes.value(student, key);
        }

        @Override
        public int compareTo(StudentAttribute other) {
            return key.compareTo(other.key);
        }

        @Override
        public String toString() {
            return key;
        }
    }

    /** How multiple {@link StudentAttribute} keys combine to define a conflict. */
    public enum GroupMatchMode {
        /** Two students conflict when they share <b>all</b> group attributes (classmates). */
        ALL,
        /** Two students conflict when they share <b>any</b> group attribute (strict isolation). */
        ANY
    }

    /**
     * Pure dynamic engine configuration.
     *
     * @param horizontalGap     dH: minimum physical-seat separation in the same row
     * @param verticalGap       dV: minimum row separation in the same bench column
     * @param diagonalGap       dD: minimum Chebyshev row/column separation on diagonals
     * @param maxBacktrackSteps safety budget per deterministic search strategy
     * @param groupAttributes   non-empty dynamic attributes defining classmate/conflict cohorts
     * @param groupMatchMode    {@link GroupMatchMode#ALL} (default) or {@link GroupMatchMode#ANY}
     */
    public record SeatingConfig(
            int horizontalGap,
            int verticalGap,
            int diagonalGap,
            int maxBacktrackSteps,
            List<StudentAttribute> groupAttributes,
            GroupMatchMode groupMatchMode) {

        public SeatingConfig {
            if (horizontalGap < 0 || verticalGap < 0 || diagonalGap < 0) {
                throw new IllegalSeatingConfigurationException(
                        "Gap values must be >= 0 (0 disables the axis): dH=%d dV=%d dD=%d"
                                .formatted(horizontalGap, verticalGap, diagonalGap));
            }
            if (maxBacktrackSteps <= 0) {
                maxBacktrackSteps = DEFAULT_MAX_BACKTRACK_STEPS;
            }
            if (groupAttributes == null || groupAttributes.isEmpty()) {
                throw new IllegalSeatingConfigurationException(
                        "At least one classmate/conflict group attribute is required");
            }
            for (StudentAttribute attribute : groupAttributes) {
                if (attribute == null) {
                    throw new IllegalSeatingConfigurationException(
                            "groupAttributes must not contain null entries");
                }
            }
            groupAttributes = List.copyOf(groupAttributes);
            if (groupMatchMode == null) {
                groupMatchMode = GroupMatchMode.ALL;
            }
        }

        public static SeatingConfig of(int horizontalGap, int verticalGap, int diagonalGap,
                                       List<StudentAttribute> groupAttributes) {
            return new SeatingConfig(horizontalGap, verticalGap, diagonalGap,
                    DEFAULT_MAX_BACKTRACK_STEPS, groupAttributes, GroupMatchMode.ALL);
        }

        public static SeatingConfig of(int horizontalGap, int verticalGap, int diagonalGap,
                                       String... attributeKeys) {
            List<StudentAttribute> attrs = Arrays.stream(attributeKeys)
                    .map(StudentAttribute::of)
                    .toList();
            return of(horizontalGap, verticalGap, diagonalGap, attrs);
        }

        /** Normal preset: horizontal separation + front-to-back column continuity (1, 0, 0). */
        public static SeatingConfig normal(List<StudentAttribute> groupAttributes) {
            return of(1, 0, 0, groupAttributes);
        }

        public static SeatingConfig normal(String... attributeKeys) {
            return of(1, 0, 0, attributeKeys);
        }

        /** Intermediate preset: horizontal + vertical separation (1, 1, 0). */
        public static SeatingConfig intermediate(List<StudentAttribute> groupAttributes) {
            return of(1, 1, 0, groupAttributes);
        }

        public static SeatingConfig intermediate(String... attributeKeys) {
            return of(1, 1, 0, attributeKeys);
        }

        /** Extreme / Rigid preset: full 8-way horizontal + vertical + diagonal separation (1, 1, 1). */
        public static SeatingConfig extreme(List<StudentAttribute> groupAttributes) {
            return of(1, 1, 1, groupAttributes);
        }

        public static SeatingConfig extreme(String... attributeKeys) {
            return of(1, 1, 1, attributeKeys);
        }

        /** Alias for {@link #extreme(List)} retained for backward compatibility. */
        public static SeatingConfig hard(List<StudentAttribute> groupAttributes) {
            return extreme(groupAttributes);
        }

        /** Alias for {@link #extreme(String...)} retained for backward compatibility. */
        public static SeatingConfig hard(String... attributeKeys) {
            return extreme(attributeKeys);
        }

        public List<StudentAttribute> effectiveGroupKey() {
            return groupAttributes;
        }
    }

    /**
     * Pre-flight and post-solve feasibility / empty-seat diagnostic summary.
     *
     * <p>Distinguishes three exact situations without ever silently relaxing
     * configured restrictions:</p>
     * <ul>
     *   <li>{@code OPTIMAL} — a valid arrangement exists and student diversity is
     *       sufficient to pack seats with zero mandatory empty buffer positions.</li>
     *   <li>{@code FEASIBLE_WITH_EMPTY_SEATS} — a valid arrangement exists in the
     *       selected halls, and at least {@code emptyBufferSeatsRequired} seating
     *       positions must remain empty because not enough students from other
     *       groups exist to fill separation gaps around the largest cohort.</li>
     *   <li>{@code INSUFFICIENT_SEATS_FOR_HEADCOUNT} / {@code INSUFFICIENT_SEATS_FOR_SEPARATION}
     *       / {@code NO_HALLS} — no valid arrangement is possible under the
     *       configured restrictions and available hall capacity; reports exact
     *       {@code additionalSeatsRequired} and {@code additionalBenchesRequired}.</li>
     * </ul>
     */
    public record SeatingAdvisory(
            boolean feasible,
            boolean requiresEmptySeats,
            String status,
            int totalStudents,
            int availableSeats,
            int minTotalSeatsRequired,
            int emptyBufferSeatsRequired,
            int additionalSeatsRequired,
            int additionalBenchesRequired,
            String dominantCohort,
            int dominantCohortSize,
            int otherCohortStudents,
            int maxCohortSeatsInCurrentHalls,
            String message,
            List<String> suggestions) {

        public SeatingAdvisory {
            suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        }
    }

    /** Result of an allocation run. */
    public record SeatingPlan(List<SeatingAllocation> allocations,
                              List<Student> unplacedStudents,
                              List<ExamRoom> roomsInFillOrder,
                              int totalSlots,
                              int totalBacktrackSteps,
                              boolean solved) {

        public SeatingPlan {
            allocations = List.copyOf(allocations);
            unplacedStudents = List.copyOf(unplacedStudents);
            roomsInFillOrder = List.copyOf(roomsInFillOrder);
        }

        public int seatedCount() {
            return allocations.size();
        }

        public Map<ExamRoom, List<SeatingAllocation>> allocationsByRoom() {
            Map<ExamRoom, List<SeatingAllocation>> byIdentity = new IdentityHashMap<>();
            for (ExamRoom room : roomsInFillOrder) {
                byIdentity.put(room, new ArrayList<>());
            }
            for (SeatingAllocation allocation : allocations) {
                ExamRoom room = roomOf(allocation);
                List<SeatingAllocation> list = byIdentity.get(room);
                if (list != null) {
                    list.add(allocation);
                } else {
                    for (ExamRoom candidate : roomsInFillOrder) {
                        if (samePersistedRoom(candidate, room)) {
                            byIdentity.get(candidate).add(allocation);
                            break;
                        }
                    }
                }
            }
            Map<ExamRoom, List<SeatingAllocation>> result = new LinkedHashMap<>();
            for (ExamRoom room : roomsInFillOrder) {
                result.put(room, Collections.unmodifiableList(byIdentity.get(room)));
            }
            return result;
        }

        private static ExamRoom roomOf(SeatingAllocation allocation) {
            return allocation.getBench() == null ? null : allocation.getBench().getRoom();
        }

        private static boolean samePersistedRoom(ExamRoom a, ExamRoom b) {
            if (a == b) {
                return true;
            }
            if (a == null || b == null) {
                return false;
            }
            return a.getId() != null && a.getId().equals(b.getId());
        }
    }

    /** Identity of one physical seat, independent of JPA entity instances. */
    public record SeatKey(String roomKey, int row, int col, int slot) {
        public static SeatKey of(Bench bench, int slot) {
            Objects.requireNonNull(bench, "bench must not be null");
            ExamRoom room = bench.getRoom();
            Long id = room == null ? null : room.getId();
            String key = id != null ? "id:" + id : "obj:" + System.identityHashCode(room);
            return new SeatKey(key, bench.getRowIndex(), bench.getColIndex(), slot);
        }
    }

    /** Occupied seat snapshot used by {@link #checkPlacement}. */
    public record SeatOccupant(Student student, Bench bench, int slotNumber) {
        public static SeatOccupant of(SeatingAllocation allocation) {
            return new SeatOccupant(allocation.getStudent(), allocation.getBench(),
                    allocation.getSlotNumber());
        }
    }

    /** Outcome of a manual seat placement check. */
    public record PlacementVerdict(boolean allowed, String reason, Student conflictingStudent) {
        static PlacementVerdict ok() {
            return new PlacementVerdict(true, null, null);
        }

        static PlacementVerdict blocked(String reason, Student conflictingStudent) {
            return new PlacementVerdict(false, reason, conflictingStudent);
        }
    }

    // ------------------------------------------------------------------
    //  Exceptions
    // ------------------------------------------------------------------

    public static class SeatingEngineException extends RuntimeException {
        private final List<String> suggestions;

        public SeatingEngineException(String message) {
            this(message, List.of());
        }

        public SeatingEngineException(String message, List<String> suggestions) {
            super(message);
            this.suggestions = suggestions == null ? List.of() : List.copyOf(suggestions);
        }

        public List<String> getSuggestions() {
            return suggestions;
        }
    }

    public static class IllegalSeatingConfigurationException extends SeatingEngineException {
        public IllegalSeatingConfigurationException(String message) {
            super(message);
        }
    }

    public static class InsufficientCapacityException extends SeatingEngineException {
        public InsufficientCapacityException(String message) {
            super(message);
        }

        public InsufficientCapacityException(String message, List<String> suggestions) {
            super(message, suggestions);
        }
    }

    public static class SeatingUnsatisfiableException extends SeatingEngineException {
        public SeatingUnsatisfiableException(String message) {
            super(message);
        }

        public SeatingUnsatisfiableException(String message, List<String> suggestions) {
            super(message, suggestions);
        }
    }

    private static final class SeatingSearchFailureException extends SeatingEngineException {
        SeatingSearchFailureException(String message) {
            super(message);
        }
    }

    private static final class SearchControl {
        final long deadlineNanos;
        int lastDeadSeat = -1;
        int consecutive = 0;

        SearchControl(long deadlineNanos) {
            this.deadlineNanos = deadlineNanos;
        }
    }

    // ------------------------------------------------------------------
    //  Internal state types
    // ------------------------------------------------------------------

    private static final class Slot {
        final int roomIndex;
        final Bench bench;
        final int slotNumber;
        Student occupant;

        Slot(int roomIndex, Bench bench, int slotNumber) {
            this.roomIndex = roomIndex;
            this.bench = bench;
            this.slotNumber = slotNumber;
        }
    }

    private static final class RoomContext {
        final int index;
        final ExamRoom room;
        final List<Slot> occupiedSlots = new ArrayList<>();
        final Map<Group, Integer> groupCount = new HashMap<>();

        RoomContext(int index, ExamRoom room) {
            this.index = index;
            this.room = room;
        }
    }

    private static final class Decision {
        final int seatIndex;
        final List<Integer> candidates;
        int nextCandidateIndex;

        Decision(int seatIndex, List<Integer> candidates, int nextCandidateIndex) {
            this.seatIndex = seatIndex;
            this.candidates = candidates;
            this.nextCandidateIndex = nextCandidateIndex;
        }
    }

    private record Group(List<String> values) {
    }

    private static Group groupOf(Student student, SeatingConfig config) {
        List<StudentAttribute> key = config.effectiveGroupKey();
        List<String> values = new ArrayList<>(key.size());
        for (StudentAttribute attribute : key) {
            values.add(canon(attribute.value(student)));
        }
        return new Group(values);
    }

    private static String canon(String value) {
        return (value == null || value.isBlank())
                ? ""
                : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static boolean attrEquals(String a, String b) {
        return canon(a).equals(canon(b));
    }

    private static boolean keyConflicts(Student a, Student b, SeatingConfig config) {
        boolean shareAll = true;
        boolean shareAny = false;
        for (StudentAttribute attribute : config.effectiveGroupKey()) {
            boolean same = attrEquals(attribute.value(a), attribute.value(b));
            shareAll &= same;
            shareAny |= same;
        }
        return config.groupMatchMode() == GroupMatchMode.ANY ? shareAny : shareAll;
    }

    // ------------------------------------------------------------------
    //  Generalized 2-layer decomposition fast path (2-attribute ALL key)
    // ------------------------------------------------------------------

    /**
     * Generalized 2-layer decomposition fast path for dual-seater halls when
     * {@code groupAttributes} has 2 attributes in {@code ALL} mode and one of
     * those attributes partitions the roster into 2 balanced layers (e.g.
     * {@code [stream, batch]} with 2 batches, or {@code [grade, wing]} with 2 wings).
     *
     * <p>If the roster has 3+ layer values, unbalanced layer counts, or hits the
     * fast-path step cap, returns {@code null} immediately so the general MRV +
     * forward-checking solver handles the full state space without restriction.</p>
     */
    private SeatingPlan solveLayerDecomposed(List<ExamRoom> orderedRooms, List<Student> orderedStudents,
                                             SeatingConfig config) {
        if (config.groupMatchMode() != GroupMatchMode.ALL || config.effectiveGroupKey().size() != 2) {
            return null;
        }
        boolean hasDual = orderedRooms.stream()
                .anyMatch(r -> r.getBenchType() == ExamRoom.BenchType.DUAL_SEATER);
        if (!hasDual) {
            return null;
        }

        StudentAttribute k0 = config.effectiveGroupKey().get(0);
        StudentAttribute k1 = config.effectiveGroupKey().get(1);

        LayerSplit split = tryBuildLayerSplit(orderedStudents, k1, k0);
        if (split == null) {
            split = tryBuildLayerSplit(orderedStudents, k0, k1);
        }
        if (split == null) {
            return null;
        }

        List<Bench> cells = new ArrayList<>();
        List<Integer> cellSlots = new ArrayList<>();
        List<List<Integer>> poolsPerCell = new ArrayList<>();
        int[] mate = new int[countSeats(orderedRooms)];
        Arrays.fill(mate, -1);

        int dualBenchCount = 0;
        int singleSeatCount = 0;
        for (ExamRoom room : orderedRooms) {
            List<Bench> grid = normalizedBenchGrid(room);
            if (room.getBenchType() == ExamRoom.BenchType.DUAL_SEATER) {
                for (Bench b : grid) {
                    int c1 = cells.size();
                    cells.add(b);
                    cellSlots.add(1);
                    poolsPerCell.add(split.layerAIndices());
                    int c2 = cells.size();
                    cells.add(b);
                    cellSlots.add(2);
                    poolsPerCell.add(split.layerBIndices());
                    mate[c1] = c2;
                    mate[c2] = c1;
                    dualBenchCount++;
                }
            } else {
                for (Bench b : grid) {
                    cells.add(b);
                    cellSlots.add(1);
                    poolsPerCell.add(split.allIndices());
                    singleSeatCount++;
                }
            }
        }

        if (split.layerAIndices().size() > dualBenchCount + singleSeatCount
                || split.layerBIndices().size() > dualBenchCount + singleSeatCount) {
            return null;
        }

        int[][] neighbors = gridNeighbors(cells, cellSlots, config);
        CellSearch search = new CellSearch(orderedStudents, cells, cellSlots, poolsPerCell,
                mate, neighbors, config, split.mateAttr());
        if (!search.solve()) {
            return null;
        }

        List<SeatingAllocation> allocations = new ArrayList<>(orderedStudents.size());
        for (int c = 0; c < cells.size(); c++) {
            int pi = search.assign[c];
            if (pi >= 0) {
                allocations.add(toAllocation(orderedStudents.get(pi), cells.get(c), cellSlots.get(c)));
            }
        }
        return new SeatingPlan(allocations, List.of(), orderedRooms,
                cells.size(), (int) Math.min(search.steps, Integer.MAX_VALUE), true);
    }

    private record LayerSplit(
            StudentAttribute layerAttr,
            StudentAttribute mateAttr,
            List<Integer> layerAIndices,
            List<Integer> layerBIndices,
            List<Integer> allIndices) {
    }

    private static LayerSplit tryBuildLayerSplit(List<Student> students,
                                                 StudentAttribute candidateLayer,
                                                 StudentAttribute candidateMate) {
        Set<String> distinctLayerValues = new LinkedHashSet<>();
        for (Student s : students) {
            String lv = canon(candidateLayer.value(s));
            String mv = canon(candidateMate.value(s));
            if (lv.isEmpty() || mv.isEmpty()) {
                return null;
            }
            distinctLayerValues.add(lv);
            if (distinctLayerValues.size() > 2) {
                return null;
            }
        }
        if (distinctLayerValues.size() != 2) {
            return null;
        }
        List<String> layers = new ArrayList<>(distinctLayerValues);
        String vA = layers.get(0);
        List<Integer> idxA = new ArrayList<>();
        List<Integer> idxB = new ArrayList<>();
        List<Integer> all = new ArrayList<>(students.size());
        for (int i = 0; i < students.size(); i++) {
            all.add(i);
            if (vA.equals(canon(candidateLayer.value(students.get(i))))) {
                idxA.add(i);
            } else {
                idxB.add(i);
            }
        }
        return new LayerSplit(candidateLayer, candidateMate, idxA, idxB, all);
    }

    private static int countSeats(List<ExamRoom> rooms) {
        int total = 0;
        for (ExamRoom r : rooms) {
            total += r.getRows() * r.getColumns() * r.getBenchType().getSeatsPerBench();
        }
        return total;
    }

    private static final class CellSearch {
        private static final int SKIPPED = -2;

        final List<Student> students;
        final List<Bench> cells;
        final List<Integer> cellSlots;
        final int n;
        final int[] mate;
        final int[][] neighbors;
        final SeatingConfig config;
        final StudentAttribute mateAttr;
        final int[] assign;
        final boolean[] used;
        final int totalStudents;
        int usedCount = 0;
        long steps = 0;

        final List<BitSet> cand;
        final int[] candCount;
        final List<Integer>[] cellsOfStudent;
        final Map<String, List<Integer>>[] byMateValue;
        final int[] roomOfCell;
        final Map<String, Integer>[] mateValueCount;
        final Map<Group, Integer> remaining;

        @SuppressWarnings("unchecked")
        CellSearch(List<Student> students, List<Bench> cells, List<Integer> cellSlots,
                   List<List<Integer>> poolsPerCell, int[] mate, int[][] neighbors,
                   SeatingConfig config, StudentAttribute mateAttr) {
            this.students = students;
            this.cells = cells;
            this.cellSlots = cellSlots;
            this.n = cells.size();
            this.mate = mate;
            this.neighbors = neighbors;
            this.config = config;
            this.mateAttr = mateAttr;
            this.assign = new int[n];
            Arrays.fill(this.assign, -1);
            this.used = new boolean[students.size()];
            this.totalStudents = students.size();
            this.cand = new ArrayList<>(n);
            this.candCount = new int[n];
            this.cellsOfStudent = new List[students.size()];
            for (int i = 0; i < students.size(); i++) {
                cellsOfStudent[i] = new ArrayList<>();
            }
            this.byMateValue = new Map[n];
            Map<ExamRoom, Integer> roomOrdinal = new IdentityHashMap<>();
            for (Bench cell : cells) {
                roomOrdinal.computeIfAbsent(cell.getRoom(), r -> roomOrdinal.size());
            }
            this.roomOfCell = new int[n];
            this.mateValueCount = new Map[roomOrdinal.size()];
            for (int r = 0; r < mateValueCount.length; r++) {
                mateValueCount[r] = new HashMap<>();
            }
            for (int c = 0; c < n; c++) {
                roomOfCell[c] = roomOrdinal.get(cells.get(c).getRoom());
            }
            this.remaining = new HashMap<>();
            for (Student s : students) {
                remaining.merge(groupOf(s, config), 1, Integer::sum);
            }
            for (int c = 0; c < n; c++) {
                cand.add(new BitSet(students.size()));
                byMateValue[c] = new HashMap<>();
                for (int pi : poolsPerCell.get(c)) {
                    cellsOfStudent[pi].add(c);
                    byMateValue[c]
                            .computeIfAbsent(canon(mateAttr.value(students.get(pi))), k -> new ArrayList<>())
                            .add(pi);
                }
            }
            recomputeAll();
        }

        boolean solve() {
            return search(0);
        }

        private boolean search(int placed) {
            if (usedCount == totalStudents) {
                return true;
            }
            if (placed == n) {
                return false;
            }
            if (++steps > GRID_SEARCH_STEPS) {
                return false;
            }
            int best = -1;
            int bestCount = Integer.MAX_VALUE;
            for (int c = 0; c < n; c++) {
                if (assign[c] != -1) {
                    continue;
                }
                if (candCount[c] < bestCount) {
                    bestCount = candCount[c];
                    best = c;
                }
            }
            if (best < 0) {
                return true;
            }
            if (bestCount == 0) {
                if (n > totalStudents) {
                    assign[best] = SKIPPED;
                    if (search(placed + 1)) {
                        return true;
                    }
                    assign[best] = -1;
                    return false;
                }
                return false;
            }
            Map<String, Integer> roomMateCounts = mateValueCount[roomOfCell[best]];
            List<Integer> ordered = new ArrayList<>();
            BitSet bits = cand.get(best);
            for (int pi = bits.nextSetBit(0); pi >= 0; pi = bits.nextSetBit(pi + 1)) {
                ordered.add(pi);
            }
            ordered.sort(Comparator
                    .comparingInt((Integer pi) -> roomMateCounts.getOrDefault(
                            canon(mateAttr.value(students.get(pi))), 0))
                    .thenComparing((a, b) -> Integer.compare(
                            remaining.getOrDefault(groupOf(students.get(b), config), 0),
                            remaining.getOrDefault(groupOf(students.get(a), config), 0)))
                    .thenComparingInt(pi -> pi));
            for (int pi : ordered) {
                if (!legal(best, pi)) {
                    throw new IllegalStateException("FC under-prune at cell " + best);
                }
                place(best, pi);
                if (search(placed + 1)) {
                    return true;
                }
                unplace(best, pi);
            }
            return false;
        }

        private void place(int c, int pi) {
            assign[c] = pi;
            used[pi] = true;
            usedCount++;
            Student placed = students.get(pi);
            mateValueCount[roomOfCell[c]].merge(canon(mateAttr.value(placed)), 1, Integer::sum);
            remaining.merge(groupOf(placed, config), -1, Integer::sum);
            for (int c2 : cellsOfStudent[pi]) {
                if (c2 != c && assign[c2] == -1 && cand.get(c2).get(pi)) {
                    cand.get(c2).clear(pi);
                    candCount[c2]--;
                }
            }
            for (int k : neighbors[c]) {
                removeBlocked(k, neighborBlockers(k, placed), pi);
            }
            if (mate[c] >= 0) {
                removeBlocked(mate[c], mateBlockers(mate[c], placed), pi);
            }
        }

        private List<Integer> mateBlockers(int mateCell, Student s) {
            List<Integer> blockers = new ArrayList<>(
                    byMateValue[mateCell].getOrDefault(canon(mateAttr.value(s)), List.of()));
            for (int pi : poolOf(mateCell)) {
                if (keyConflicts(s, students.get(pi), config)) {
                    blockers.add(pi);
                }
            }
            return blockers;
        }

        private void removeBlocked(int c, List<Integer> blockers, int pi) {
            if (assign[c] != -1) {
                return;
            }
            BitSet bits = cand.get(c);
            for (int q : blockers) {
                if (bits.get(q)) {
                    bits.clear(q);
                    candCount[c]--;
                }
            }
            if (bits.get(pi)) {
                bits.clear(pi);
                candCount[c]--;
            }
        }

        private void unplace(int c, int pi) {
            assign[c] = -1;
            used[pi] = false;
            usedCount--;
            Student s = students.get(pi);
            mateValueCount[roomOfCell[c]].merge(canon(mateAttr.value(s)), -1, Integer::sum);
            remaining.merge(groupOf(s, config), 1, Integer::sum);
            for (int c2 : cellsOfStudent[pi]) {
                if (c2 != c && assign[c2] == -1 && !cand.get(c2).get(pi) && legal(c2, pi)) {
                    cand.get(c2).set(pi);
                    candCount[c2]++;
                }
            }
            for (int k : neighbors[c]) {
                for (int q : neighborBlockers(k, s)) {
                    if (q != pi && !used[q] && !cand.get(k).get(q) && legal(k, q)) {
                        cand.get(k).set(q);
                        candCount[k]++;
                    }
                }
            }
            if (mate[c] >= 0) {
                for (int q : mateBlockers(mate[c], s)) {
                    if (q != pi && !used[q] && !cand.get(mate[c]).get(q) && legal(mate[c], q)) {
                        cand.get(mate[c]).set(q);
                        candCount[mate[c]]++;
                    }
                }
            }
        }

        private List<Integer> neighborBlockers(int k, Student s) {
            List<Integer> result = new ArrayList<>();
            for (int pi : poolOf(k)) {
                if (keyConflicts(s, students.get(pi), config)) {
                    result.add(pi);
                }
            }
            return result;
        }

        private boolean legal(int c, int pi) {
            Student s = students.get(pi);
            if (mate[c] >= 0 && assign[mate[c]] >= 0) {
                Student m = students.get(assign[mate[c]]);
                if (attrEquals(mateAttr.value(s), mateAttr.value(m)) || keyConflicts(s, m, config)) {
                    return false;
                }
            }
            for (int k : neighbors[c]) {
                int other = assign[k];
                if (other >= 0 && keyConflicts(s, students.get(other), config)) {
                    return false;
                }
            }
            return true;
        }

        private void recomputeAll() {
            for (int c = 0; c < n; c++) {
                BitSet bits = cand.get(c);
                bits.clear();
                int count = 0;
                for (int pi : poolOf(c)) {
                    if (!used[pi] && legal(c, pi)) {
                        bits.set(pi);
                        count++;
                    }
                }
                candCount[c] = count;
            }
        }

        private List<Integer> poolOf(int c) {
            List<Integer> pool = new ArrayList<>();
            for (List<Integer> list : byMateValue[c].values()) {
                pool.addAll(list);
            }
            return pool;
        }
    }

    private static int[][] gridNeighbors(List<Bench> cells, List<Integer> cellSlots, SeatingConfig config) {
        int[][] neighbors = new int[cells.size()][];
        for (int c = 0; c < cells.size(); c++) {
            List<Integer> list = new ArrayList<>();
            for (int k = 0; k < cells.size(); k++) {
                if (k != c && cells.get(k).getRoom() == cells.get(c).getRoom()
                        && withinGap(cells.get(c), cellSlots.get(c), cells.get(k), cellSlots.get(k), config)) {
                    list.add(k);
                }
            }
            neighbors[c] = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                neighbors[c][i] = list.get(i);
            }
        }
        return neighbors;
    }

    private static boolean withinGap(Bench a, int slotA, Bench b, int slotB, SeatingConfig config) {
        if (a == b) {
            return false;
        }
        int dr = Math.abs(a.getRowIndex() - b.getRowIndex());
        int benchDc = Math.abs(a.getColIndex() - b.getColIndex());
        int seatDc = Math.abs(physicalCol(a, slotA) - physicalCol(b, slotB));
        if (config.horizontalGap() > 0 && dr == 0 && seatDc <= config.horizontalGap()) {
            return true;
        }
        if (config.verticalGap() > 0 && benchDc == 0 && dr <= config.verticalGap()) {
            return true;
        }
        return config.diagonalGap() > 0 && dr > 0 && benchDc > 0
                && dr <= config.diagonalGap() && benchDc <= config.diagonalGap();
    }

    private static boolean horizontalBetween(Bench a, int slotA, Bench b, int slotB, SeatingConfig config) {
        int dr = Math.abs(a.getRowIndex() - b.getRowIndex());
        return config.horizontalGap() > 0 && dr == 0
                && Math.abs(physicalCol(a, slotA) - physicalCol(b, slotB)) <= config.horizontalGap();
    }

    private static int physicalCol(Bench bench, int slotNumber) {
        if (bench.getBenchType() == ExamRoom.BenchType.DUAL_SEATER) {
            return (bench.getColIndex() - 1) * 2 + slotNumber;
        }
        return bench.getColIndex();
    }

    private static SeatingAllocation toAllocation(Student student, Bench bench, int slotNumber) {
        return SeatingAllocation.builder()
                .student(student)
                .bench(bench)
                .slotNumber(slotNumber)
                .allocatedAt(Instant.now())
                .build();
    }

    // ------------------------------------------------------------------
    //  General seat-level CSP solver (MRV + forward checking + backjumping)
    // ------------------------------------------------------------------

    private SeatingPlan solve(List<ExamRoom> orderedRooms, List<Student> orderedStudents,
                              SeatingConfig config, int profile, SearchControl control,
                              Set<SeatKey> occupiedSeats) {
        List<RoomContext> contexts = new ArrayList<>();
        List<Slot> slots = new ArrayList<>();
        int[] roomStart = new int[orderedRooms.size()];
        int[] roomEnd = new int[orderedRooms.size()];
        for (int ri = 0; ri < orderedRooms.size(); ri++) {
            ExamRoom room = orderedRooms.get(ri);
            List<Bench> grid = normalizedBenchGrid(room);
            contexts.add(new RoomContext(ri, room));
            roomStart[ri] = slots.size();
            for (Bench bench : grid) {
                int seats = bench.getBenchType().getSeatsPerBench();
                for (int slotNumber = 1; slotNumber <= seats; slotNumber++) {
                    slots.add(new Slot(ri, bench, slotNumber));
                }
            }
            roomEnd[ri] = slots.size();
        }
        int totalSeats = slots.size();

        long[] laterBoundPerRoom = new long[orderedRooms.size()];
        long runningLaterBound = 0;
        for (int ri = orderedRooms.size() - 1; ri >= 0; ri--) {
            laterBoundPerRoom[ri] = runningLaterBound;
            runningLaterBound += roomGroupBound(orderedRooms.get(ri), config);
        }

        boolean[] placed = new boolean[orderedStudents.size()];
        int[] unplacedCount = new int[]{orderedStudents.size()};
        Map<Group, Integer> remaining = new HashMap<>();
        for (Student s : orderedStudents) {
            remaining.merge(groupOf(s, config), 1, Integer::sum);
        }

        List<Map<Group, Integer>> roomQuotaMaps = new ArrayList<>();
        for (int i = 0; i < orderedRooms.size(); i++) {
            roomQuotaMaps.add(null);
        }
        boolean[] quotaComputed = new boolean[orderedRooms.size()];

        boolean[] seatAssigned = new boolean[totalSeats];
        for (int t = 0; t < totalSeats; t++) {
            Slot slot = slots.get(t);
            if (occupiedSeats.contains(SeatKey.of(slot.bench, slot.slotNumber))) {
                seatAssigned[t] = true;
            }
        }
        List<BitSet> seatCandidates = new ArrayList<>(totalSeats);
        int[] seatCandidateCount = new int[totalSeats];
        for (int t = 0; t < totalSeats; t++) {
            seatCandidates.add(new BitSet(orderedStudents.size()));
            recomputeCandidates(t, slots, orderedStudents, placed, seatAssigned, seatCandidates,
                    seatCandidateCount, contexts, config);
        }

        Deque<Decision> stack = new ArrayDeque<>();
        int[] backtracks = new int[1];
        int steps = 0;
        int currentRoomIndex = -1;

        while (true) {
            if (System.nanoTime() > control.deadlineNanos) {
                throw new SeatingSearchFailureException(
                        "wall-clock solve budget exceeded ("
                                + TimeUnit.NANOSECONDS.toSeconds(MAX_SOLVE_NANOS)
                                + "s) — relax dH/dV/dD, add rooms, or split the roster");
            }
            if (unplacedCount[0] == 0) {
                break;
            }

            int roomIndex = -1;
            for (int ri = 0; ri < orderedRooms.size(); ri++) {
                if (roomStart[ri] < roomEnd[ri] && !roomFullyAssigned(roomStart[ri], roomEnd[ri], seatAssigned)) {
                    roomIndex = ri;
                    break;
                }
            }
            if (roomIndex < 0) {
                break;
            }

            if (roomIndex != currentRoomIndex) {
                currentRoomIndex = roomIndex;
                if (!quotaComputed[roomIndex]) {
                    quotaComputed[roomIndex] = true;
                    Map<Group, Integer> quota = computeRoomQuota(roomIndex, laterBoundPerRoom, remaining);
                    roomQuotaMaps.set(roomIndex, quota);
                    long totalQuota = 0;
                    for (int q : quota.values()) {
                        totalQuota += q;
                    }
                    if (totalQuota > (long) roomEnd[roomIndex] - roomStart[roomIndex]) {
                        throw new SeatingUnsatisfiableException(
                                ("Hall '%s' has %d seats but %d students must sit here (they cannot fit "
                                        + "in later halls with the current gaps) — add halls or relax dH/dV/dD.")
                                        .formatted(orderedRooms.get(roomIndex).getHallName(),
                                                roomEnd[roomIndex] - roomStart[roomIndex], totalQuota));
                    }
                }
            }

            if (++steps > config.maxBacktrackSteps()) {
                throw new SeatingSearchFailureException(
                        ("backtrack budget of %d steps exhausted after %d backtracks (search strategy %d) — "
                                + "relax dH/dV/dD, add rooms, or split students by exam round")
                                .formatted(config.maxBacktrackSteps(), backtracks[0], profile));
            }

            int forcedSeat = -1;
            if (!stack.isEmpty()) {
                Decision top = stack.peek();
                Slot topSlot = slots.get(top.seatIndex);
                if (topSlot.slotNumber == 1 && top.seatIndex + 1 < totalSeats
                        && slots.get(top.seatIndex + 1).bench == topSlot.bench
                        && !seatAssigned[top.seatIndex + 1]
                        && seatCandidateCount[top.seatIndex + 1] > 0) {
                    forcedSeat = top.seatIndex + 1;
                }
            }

            int bestSeat = -1;
            int bestCount = Integer.MAX_VALUE;
            if (forcedSeat >= 0) {
                bestSeat = forcedSeat;
                bestCount = seatCandidateCount[forcedSeat];
            } else {
                if (profile == 3) {
                    for (int t = roomEnd[roomIndex] - 1; t >= roomStart[roomIndex]; t--) {
                        if (!seatAssigned[t] && seatCandidateCount[t] > 0 && seatCandidateCount[t] < bestCount) {
                            bestCount = seatCandidateCount[t];
                            bestSeat = t;
                        }
                    }
                } else {
                    for (int t = roomStart[roomIndex]; t < roomEnd[roomIndex]; t++) {
                        if (!seatAssigned[t] && seatCandidateCount[t] > 0 && seatCandidateCount[t] < bestCount) {
                            bestCount = seatCandidateCount[t];
                            bestSeat = t;
                        }
                    }
                }
                if (bestSeat < 0) {
                    for (int ri = 0; ri < orderedRooms.size(); ri++) {
                        if (ri == roomIndex) {
                            continue;
                        }
                        for (int t = roomStart[ri]; t < roomEnd[ri]; t++) {
                            if (!seatAssigned[t] && seatCandidateCount[t] > 0 && seatCandidateCount[t] < bestCount) {
                                bestCount = seatCandidateCount[t];
                                bestSeat = t;
                            }
                        }
                        if (bestSeat >= 0) {
                            break;
                        }
                    }
                }
            }

            if (bestSeat < 0 || bestCount == 0) {
                int deadSeat = forcedSeat >= 0 ? forcedSeat : (stack.isEmpty() ? 0 : stack.peek().seatIndex);
                if (!resolveDeadEnd(deadSeat, stack, slots, orderedStudents, placed, unplacedCount,
                        contexts, remaining, seatAssigned, seatCandidates, seatCandidateCount, config,
                        backtracks, control)) {
                    throw new SeatingSearchFailureException(
                            ("no legal candidate for unplaced students in hall '%s' (search strategy %d)")
                                    .formatted(orderedRooms.get(roomIndex).getHallName(), profile));
                }
                continue;
            }

            int chosenRoomIndex = slots.get(bestSeat).roomIndex;
            List<Integer> candidates = collectCandidates(bestSeat, slots, orderedStudents, placed,
                    seatAssigned, contexts, config, remaining, roomQuotaMaps.get(chosenRoomIndex), profile);
            if (candidates.isEmpty()) {
                if (!resolveDeadEnd(bestSeat, stack, slots, orderedStudents, placed, unplacedCount,
                        contexts, remaining, seatAssigned, seatCandidates, seatCandidateCount, config,
                        backtracks, control)) {
                    throw new SeatingSearchFailureException(
                            ("no candidate available for seat %d in search strategy %d").formatted(bestSeat, profile));
                }
                continue;
            }

            int chosen = candidates.get(0);
            place(chosen, bestSeat, slots, orderedStudents, placed, unplacedCount, contexts, remaining,
                    seatAssigned, seatCandidates, seatCandidateCount, config);
            stack.push(new Decision(bestSeat, candidates, 1));

            if (unplacedCount[0] > 0) {
                if (++steps > config.maxBacktrackSteps()) {
                    throw new SeatingSearchFailureException(
                            ("backtrack budget of %d steps exhausted after %d backtracks (search strategy %d) — "
                                    + "relax dH/dV/dD, add rooms, or split students by exam round")
                                    .formatted(config.maxBacktrackSteps(), backtracks[0], profile));
                }
                int fillable = 0;
                for (int t = 0; t < totalSeats; t++) {
                    if (!seatAssigned[t] && seatCandidateCount[t] > 0) {
                        fillable++;
                    }
                }
                if (fillable < unplacedCount[0]) {
                    if (!resolveDeadEnd(bestSeat, stack, slots, orderedStudents, placed, unplacedCount,
                            contexts, remaining, seatAssigned, seatCandidates, seatCandidateCount, config,
                            backtracks, control)) {
                        throw new SeatingSearchFailureException(
                                ("dead end detected by forward checking while seating hall '%s' (search strategy %d)")
                                        .formatted(orderedRooms.get(roomIndex).getHallName(), profile));
                    }
                }
            }
        }

        List<SeatingAllocation> allocations = new ArrayList<>();
        for (int t = 0; t < totalSeats; t++) {
            Student occupant = slots.get(t).occupant;
            if (occupant == null) {
                continue;
            }
            Slot slot = slots.get(t);
            allocations.add(toAllocation(occupant, slot.bench, slot.slotNumber));
        }
        List<Student> unplaced = new ArrayList<>();
        for (int i = 0; i < placed.length; i++) {
            if (!placed[i]) {
                unplaced.add(orderedStudents.get(i));
            }
        }
        return new SeatingPlan(List.copyOf(allocations), List.copyOf(unplaced),
                List.copyOf(orderedRooms), totalSeats, backtracks[0], unplaced.isEmpty());
    }

    private static boolean roomFullyAssigned(int start, int end, boolean[] seatAssigned) {
        for (int t = start; t < end; t++) {
            if (!seatAssigned[t]) {
                return false;
            }
        }
        return true;
    }

    private static boolean resolveDeadEnd(int deadSeat, Deque<Decision> stack, List<Slot> slots,
                                          List<Student> orderedStudents, boolean[] placed, int[] unplacedCount,
                                          List<RoomContext> contexts, Map<Group, Integer> remaining,
                                          boolean[] seatAssigned, List<BitSet> seatCandidates,
                                          int[] seatCandidateCount, SeatingConfig config, int[] backtracks,
                                          SearchControl control) {
        if (deadSeat == control.lastDeadSeat) {
            if (++control.consecutive > STALL_LIMIT) {
                throw new SeatingSearchFailureException(
                        ("search strategy stalled after %d consecutive dead ends on the same seat")
                                .formatted(control.consecutive));
            }
        } else {
            control.lastDeadSeat = deadSeat;
            control.consecutive = 0;
        }
        while (!stack.isEmpty() && !influences(stack.peek().seatIndex, deadSeat, slots, config)) {
            Decision d = stack.pop();
            unplace(d.seatIndex, d.candidates.get(d.nextCandidateIndex - 1), slots, orderedStudents, placed,
                    unplacedCount, contexts, remaining, seatAssigned, seatCandidates, seatCandidateCount, config);
            backtracks[0]++;
        }
        if (stack.isEmpty()) {
            return false;
        }
        return backtrack(stack, slots, orderedStudents, placed, unplacedCount, contexts, remaining,
                seatAssigned, seatCandidates, seatCandidateCount, config, backtracks);
    }

    private static boolean backtrack(Deque<Decision> stack, List<Slot> slots, List<Student> orderedStudents,
                                     boolean[] placed, int[] unplacedCount, List<RoomContext> contexts,
                                     Map<Group, Integer> remaining, boolean[] seatAssigned,
                                     List<BitSet> seatCandidates, int[] seatCandidateCount, SeatingConfig config,
                                     int[] backtracks) {
        if (stack.isEmpty()) {
            return false;
        }
        Decision d = stack.pop();
        unplace(d.seatIndex, d.candidates.get(d.nextCandidateIndex - 1), slots, orderedStudents, placed,
                unplacedCount, contexts, remaining, seatAssigned, seatCandidates, seatCandidateCount, config);
        backtracks[0]++;
        while (d.nextCandidateIndex >= d.candidates.size() && !stack.isEmpty()) {
            d = stack.pop();
            unplace(d.seatIndex, d.candidates.get(d.nextCandidateIndex - 1), slots, orderedStudents, placed,
                    unplacedCount, contexts, remaining, seatAssigned, seatCandidates, seatCandidateCount, config);
            backtracks[0]++;
        }
        if (d.nextCandidateIndex >= d.candidates.size()) {
            return false;
        }
        int nextStudent = d.candidates.get(d.nextCandidateIndex);
        d.nextCandidateIndex++;
        place(nextStudent, d.seatIndex, slots, orderedStudents, placed, unplacedCount, contexts, remaining,
                seatAssigned, seatCandidates, seatCandidateCount, config);
        stack.push(d);
        return true;
    }

    private static Map<Group, Integer> computeRoomQuota(int roomIndex, long[] laterBoundPerRoom,
                                                        Map<Group, Integer> remaining) {
        Map<Group, Integer> quota = new HashMap<>();
        long laterBound = laterBoundPerRoom[roomIndex];
        for (Map.Entry<Group, Integer> e : remaining.entrySet()) {
            long q = Math.max(0, e.getValue() - laterBound);
            if (q > 0) {
                quota.put(e.getKey(), (int) q);
            }
        }
        return quota;
    }

    @SuppressWarnings("unchecked")
    private static List<Integer> collectCandidates(int seatIndex, List<Slot> slots, List<Student> orderedStudents,
                                                   boolean[] placed, boolean[] seatAssigned,
                                                   List<RoomContext> contexts,
                                                   SeatingConfig config, Map<Group, Integer> remaining,
                                                   Map<Group, Integer> roomQuota, int profile) {
        Slot slot = slots.get(seatIndex);
        RoomContext ctx = contexts.get(slot.roomIndex);
        Student benchMate = benchMateOf(seatIndex, slots, seatAssigned);
        List<Integer> result = new ArrayList<>();
        for (int i = 0; i < orderedStudents.size(); i++) {
            if (!placed[i] && canPlace(orderedStudents.get(i), slot, ctx, config, benchMate)) {
                result.add(i);
            }
        }
        Slot frontSlot = findFrontSlot(slot, ctx);
        Student frontStudent = frontSlot != null ? frontSlot.occupant : null;

        // Column continuity in Normal mode (verticalGap == 0): prefer continuing the
        // front seat's cohort down the bench column AFTER mandatory hall quotas.
        Comparator<Integer> columnAlignment = (a, b) -> {
            if (profile != 0 || config.verticalGap() > 0 || frontStudent == null) {
                return 0;
            }
            Group frontGroup = groupOf(frontStudent, config);
            boolean aMatches = groupOf(orderedStudents.get(a), config).equals(frontGroup);
            boolean bMatches = groupOf(orderedStudents.get(b), config).equals(frontGroup);
            return Boolean.compare(bMatches, aMatches);
        };

        Comparator<Integer> mustPlaceFirst = (a, b) -> Integer.compare(
                quotaRemaining(orderedStudents.get(b), roomQuota, ctx, config),
                quotaRemaining(orderedStudents.get(a), roomQuota, ctx, config));
        Comparator<Integer> leastRepresentedFirst =
                Comparator.comparingInt(idx -> ctx.groupCount.getOrDefault(groupOf(orderedStudents.get(idx), config), 0));
        Comparator<Integer> largestRemainingGroupFirst = (a, b) -> Integer.compare(
                remaining.getOrDefault(groupOf(orderedStudents.get(b), config), 0),
                remaining.getOrDefault(groupOf(orderedStudents.get(a), config), 0));

        Comparator<Integer>[] priorities = new Comparator[]{
                mustPlaceFirst, leastRepresentedFirst, largestRemainingGroupFirst
        };
        int[] order = SEARCH_PROFILES[profile];
        Comparator<Integer> chain = mustPlaceFirst.thenComparing(columnAlignment);
        for (int idx : order) {
            if (idx != 0) {
                chain = chain.thenComparing(priorities[idx]);
            }
        }
        Comparator<Integer> indexTie = profile == 3
                ? (a, b) -> Integer.compare(b, a)
                : Comparator.comparingInt(idx -> idx);
        result.sort(chain.thenComparing(indexTie));
        return result;
    }

    private static Slot findFrontSlot(Slot slot, RoomContext ctx) {
        int r = slot.bench.getRowIndex();
        if (r <= 1) {
            return null;
        }
        for (Slot other : ctx.occupiedSlots) {
            if (other.bench.getColIndex() == slot.bench.getColIndex()
                    && other.bench.getRowIndex() == r - 1
                    && other.slotNumber == slot.slotNumber) {
                return other;
            }
        }
        return null;
    }

    private static int quotaRemaining(Student student, Map<Group, Integer> roomQuota, RoomContext ctx,
                                      SeatingConfig config) {
        if (roomQuota == null) {
            return 0;
        }
        int quota = roomQuota.getOrDefault(groupOf(student, config), 0);
        int placedHere = ctx.groupCount.getOrDefault(groupOf(student, config), 0);
        return Math.max(0, quota - placedHere);
    }

    private static void recomputeCandidates(int t, List<Slot> slots, List<Student> orderedStudents,
                                            boolean[] placed, boolean[] seatAssigned, List<BitSet> seatCandidates,
                                            int[] seatCandidateCount, List<RoomContext> contexts,
                                            SeatingConfig config) {
        if (seatAssigned[t]) {
            seatCandidates.get(t).clear();
            seatCandidateCount[t] = 0;
            return;
        }
        Slot slot = slots.get(t);
        RoomContext ctx = contexts.get(slot.roomIndex);
        Student benchMate = benchMateOf(t, slots, seatAssigned);
        BitSet bits = seatCandidates.get(t);
        bits.clear();
        int count = 0;
        for (int i = 0; i < orderedStudents.size(); i++) {
            if (!placed[i] && canPlace(orderedStudents.get(i), slot, ctx, config, benchMate)) {
                bits.set(i);
                count++;
            }
        }
        seatCandidateCount[t] = count;
    }

    private static boolean influences(int s, int t, List<Slot> slots, SeatingConfig config) {
        Slot a = slots.get(s);
        Slot b = slots.get(t);
        if (a.bench == b.bench) {
            return true;
        }
        if (a.roomIndex != b.roomIndex) {
            return false;
        }
        return withinGap(a.bench, a.slotNumber, b.bench, b.slotNumber, config);
    }

    private static Student benchMateOf(int seatIndex, List<Slot> slots, boolean[] seatAssigned) {
        Slot slot = slots.get(seatIndex);
        int other = -1;
        if (slot.slotNumber == 2) {
            other = seatIndex - 1;
        } else if (seatIndex + 1 < slots.size()
                && slots.get(seatIndex + 1).bench == slot.bench
                && slots.get(seatIndex + 1).slotNumber == 2) {
            other = seatIndex + 1;
        }
        if (other >= 0 && seatAssigned[other]) {
            return slots.get(other).occupant;
        }
        return null;
    }

    private static void place(int studentIndex, int seatIndex, List<Slot> slots, List<Student> orderedStudents,
                              boolean[] placed, int[] unplacedCount, List<RoomContext> contexts,
                              Map<Group, Integer> remaining, boolean[] seatAssigned,
                              List<BitSet> seatCandidates, int[] seatCandidateCount, SeatingConfig config) {
        Student s = orderedStudents.get(studentIndex);
        Slot slot = slots.get(seatIndex);
        slot.occupant = s;
        placed[studentIndex] = true;
        unplacedCount[0]--;
        seatAssigned[seatIndex] = true;
        RoomContext ctx = contexts.get(slot.roomIndex);
        ctx.occupiedSlots.add(slot);
        Group g = groupOf(s, config);
        ctx.groupCount.merge(g, 1, Integer::sum);
        remaining.merge(g, -1, Integer::sum);

        updateCountsAfterChange(seatIndex, studentIndex, slots, orderedStudents, placed,
                seatAssigned, seatCandidates, seatCandidateCount, contexts, config, true);
    }

    private static void unplace(int seatIndex, int studentIndex, List<Slot> slots, List<Student> orderedStudents,
                                boolean[] placed, int[] unplacedCount, List<RoomContext> contexts,
                                Map<Group, Integer> remaining, boolean[] seatAssigned,
                                List<BitSet> seatCandidates, int[] seatCandidateCount, SeatingConfig config) {
        Student s = orderedStudents.get(studentIndex);
        Slot slot = slots.get(seatIndex);
        slot.occupant = null;
        placed[studentIndex] = false;
        unplacedCount[0]++;
        seatAssigned[seatIndex] = false;
        RoomContext ctx = contexts.get(slot.roomIndex);
        Slot tail = ctx.occupiedSlots.remove(ctx.occupiedSlots.size() - 1);
        if (tail != slot) {
            throw new IllegalStateException("Corrupted backtrack state at seat " + seatIndex);
        }
        Group g = groupOf(s, config);
        ctx.groupCount.merge(g, -1, Integer::sum);
        remaining.merge(g, 1, Integer::sum);

        updateCountsAfterChange(seatIndex, studentIndex, slots, orderedStudents, placed,
                seatAssigned, seatCandidates, seatCandidateCount, contexts, config, false);
    }

    private static void updateCountsAfterChange(int seatIndex, int studentIndex, List<Slot> slots,
                                                List<Student> orderedStudents, boolean[] placed,
                                                boolean[] seatAssigned, List<BitSet> seatCandidates,
                                                int[] seatCandidateCount, List<RoomContext> contexts,
                                                SeatingConfig config, boolean placing) {
        Student x = orderedStudents.get(studentIndex);
        for (int t = 0; t < slots.size(); t++) {
            if (t == seatIndex || seatAssigned[t]) {
                continue;
            }
            Slot tSlot = slots.get(t);
            if (influences(seatIndex, t, slots, config)) {
                BitSet bits = seatCandidates.get(t);
                int count = seatCandidateCount[t];
                if (placing) {
                    if (bits.get(studentIndex)) {
                        bits.clear(studentIndex);
                        count--;
                    }
                    for (int i = 0; i < orderedStudents.size(); i++) {
                        if (!placed[i] && bits.get(i) && keyConflicts(orderedStudents.get(i), x, config)) {
                            bits.clear(i);
                            count--;
                        }
                    }
                } else {
                    Student mate = benchMateOf(t, slots, seatAssigned);
                    RoomContext tCtx = contexts.get(tSlot.roomIndex);
                    if (!bits.get(studentIndex) && canPlace(x, tSlot, tCtx, config, mate)) {
                        bits.set(studentIndex);
                        count++;
                    }
                    for (int i = 0; i < orderedStudents.size(); i++) {
                        if (i != studentIndex && !placed[i] && !bits.get(i)
                                && keyConflicts(orderedStudents.get(i), x, config)
                                && canPlace(orderedStudents.get(i), tSlot, tCtx, config, mate)) {
                            bits.set(i);
                            count++;
                        }
                    }
                }
                seatCandidateCount[t] = count;
            } else if (placing) {
                if (seatCandidates.get(t).get(studentIndex)) {
                    seatCandidates.get(t).clear(studentIndex);
                    seatCandidateCount[t]--;
                }
            } else {
                if (!seatCandidates.get(t).get(studentIndex)
                        && canPlace(x, tSlot, contexts.get(tSlot.roomIndex), config,
                                benchMateOf(t, slots, seatAssigned))) {
                    seatCandidates.get(t).set(studentIndex);
                    seatCandidateCount[t]++;
                }
            }
        }
    }

    private static boolean canPlace(Student candidate, Slot slot, RoomContext ctx, SeatingConfig config,
                                    Student benchMate) {
        if (benchMate != null && keyConflicts(candidate, benchMate, config)) {
            return false;
        }
        for (Slot other : ctx.occupiedSlots) {
            if (other.bench == slot.bench) {
                continue;
            }
            if (withinGap(slot.bench, slot.slotNumber, other.bench, other.slotNumber, config)
                    && keyConflicts(candidate, other.occupant, config)) {
                return false;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    //  Validation & deterministic ordering helpers
    // ------------------------------------------------------------------

    private static void validateRooms(List<ExamRoom> rooms) {
        for (ExamRoom room : rooms) {
            Objects.requireNonNull(room, "rooms must not contain null entries");
            if (room.getRows() <= 0 || room.getColumns() <= 0) {
                throw new IllegalSeatingConfigurationException(
                        "Room '%s' must have positive rows and columns (got %dx%d)"
                                .formatted(room.getHallName(), room.getRows(), room.getColumns()));
            }
            if (room.getBenchType() == null) {
                throw new IllegalSeatingConfigurationException(
                        "Room '%s' must specify a BenchType".formatted(room.getHallName()));
            }
            if (room.getBenches() != null) {
                for (Bench bench : room.getBenches()) {
                    if (bench != null && bench.getRoom() != null && !sameRoom(bench.getRoom(), room)) {
                        throw new IllegalSeatingConfigurationException(
                                "Room '%s' contains a bench bound to another room ('%s')"
                                        .formatted(room.getHallName(), bench.getRoom().getHallName()));
                    }
                }
            }
        }
    }

    private static void validateCapacity(List<ExamRoom> rooms, int studentCount, Set<SeatKey> occupiedSeats) {
        long totalSeats = 0;
        for (ExamRoom room : rooms) {
            totalSeats += (long) room.getRows() * room.getColumns() * room.getBenchType().getSeatsPerBench();
        }
        long freeSeats = Math.max(0L, totalSeats - occupiedSeats.size());
        if (freeSeats < studentCount) {
            throw new InsufficientCapacityException(
                    "Insufficient seating capacity: %d students require seats, only %d free across %d hall(s)"
                            .formatted(studentCount, freeSeats, rooms.size()));
        }
    }

    private static void validateGroupAttributesConfigured(List<Student> students, SeatingConfig config) {
        if (students.isEmpty()) {
            return;
        }
        List<StudentAttribute> attrs = config.effectiveGroupKey();
        List<String> missingEverywhere = new ArrayList<>();
        for (StudentAttribute attr : attrs) {
            boolean anyPresent = false;
            for (Student s : students) {
                if (!canon(attr.value(s)).isEmpty()) {
                    anyPresent = true;
                    break;
                }
            }
            if (!anyPresent) {
                missingEverywhere.add(attr.key());
            }
        }
        if (missingEverywhere.size() == attrs.size()) {
            throw new IllegalSeatingConfigurationException(
                    "Classmate grouping attribute(s) %s have no values on the selected students — choose attributes present in your student roster."
                            .formatted(missingEverywhere));
        }
        List<String> blankStudents = new ArrayList<>();
        for (Student s : students) {
            boolean hasAny = false;
            for (StudentAttribute attr : attrs) {
                if (!canon(attr.value(s)).isEmpty()) {
                    hasAny = true;
                    break;
                }
            }
            if (!hasAny && blankStudents.size() < 5) {
                blankStudents.add(StudentAttributes.displayLabel(s));
            }
        }
        if (!blankStudents.isEmpty()) {
            throw new IllegalSeatingConfigurationException(
                    "Students %s have no values for the configured classmate grouping attributes %s."
                            .formatted(blankStudents, attrs.stream().map(StudentAttribute::key).toList()));
        }
    }

    private static void validateGroupFeasibility(List<ExamRoom> rooms, List<Student> students,
                                                 SeatingConfig config) {
        long totalBound = 0;
        for (ExamRoom room : rooms) {
            totalBound += roomGroupBound(room, config);
        }

        List<String> offenders = new ArrayList<>();
        if (config.groupMatchMode() == GroupMatchMode.ANY) {
            for (StudentAttribute attribute : config.effectiveGroupKey()) {
                Map<String, Integer> perValue = new HashMap<>();
                for (Student s : students) {
                    perValue.merge(canon(attribute.value(s)), 1, Integer::sum);
                }
                for (Map.Entry<String, Integer> e : perValue.entrySet()) {
                    if (e.getValue() > totalBound) {
                        offenders.add("%s '%s' has %d students but can occupy at most %d seats with current gaps"
                                .formatted(attribute, e.getKey().isEmpty() ? "(none)" : e.getKey(),
                                        e.getValue(), totalBound));
                    }
                }
            }
        } else {
            Map<Group, Integer> perGroup = new HashMap<>();
            for (Student s : students) {
                perGroup.merge(groupOf(s, config), 1, Integer::sum);
            }
            for (Map.Entry<Group, Integer> e : perGroup.entrySet()) {
                if (e.getValue() > totalBound) {
                    offenders.add("cohort '%s' has %d students but can occupy at most %d seats with current gaps"
                            .formatted(groupLabel(e.getKey(), config), e.getValue(), totalBound));
                }
            }
        }

        if (!offenders.isEmpty()) {
            throw new SeatingUnsatisfiableException(
                    "Seating is impossible with the current configuration: " + String.join("; ", offenders)
                            + ". Reduce the cohort size, add more halls, or relax the strictness level.");
        }
    }

    private static long roomGroupBound(ExamRoom room, SeatingConfig config) {
        int rows = room.getRows();
        int cols = room.getColumns();
        int seatsPerBench = room.getBenchType() != null ? room.getBenchType().getSeatsPerBench() : 1;
        int seatsPerRow = cols * seatsPerBench;
        long benchCap = (long) rows * cols;
        long rowCap = (long) rows * ceilDiv(seatsPerRow, config.horizontalGap() + 1);
        long colCap = config.verticalGap() > 0
                ? (long) cols * ceilDiv(rows, config.verticalGap() + 1)
                : Long.MAX_VALUE;
        long kingCap = (config.diagonalGap() > 0
                && config.verticalGap() >= config.diagonalGap()
                && config.horizontalGap() >= config.diagonalGap())
                ? (long) ceilDiv(rows, config.diagonalGap() + 1) * ceilDiv(seatsPerRow, config.diagonalGap() + 1)
                : Long.MAX_VALUE;
        return Math.min(Math.min(rowCap, colCap), Math.min(benchCap, kingCap));
    }

    private static void validateAttributeBalance(List<ExamRoom> rooms, List<Student> students, SeatingConfig config) {
        boolean hasDual = rooms.stream().anyMatch(r -> r.getBenchType() == ExamRoom.BenchType.DUAL_SEATER);
        if (!hasDual) {
            return;
        }
        long benchCap = 0;
        for (ExamRoom room : rooms) {
            benchCap += (long) room.getRows() * room.getColumns();
        }
        List<String> offenders = new ArrayList<>();
        if (config.groupMatchMode() == GroupMatchMode.ANY) {
            for (StudentAttribute attribute : config.effectiveGroupKey()) {
                Map<String, Integer> perValue = new HashMap<>();
                for (Student s : students) {
                    perValue.merge(canon(attribute.value(s)), 1, Integer::sum);
                }
                for (Map.Entry<String, Integer> e : perValue.entrySet()) {
                    if (e.getValue() > benchCap) {
                        offenders.add("%s '%s' has %d students but dual benches can hold at most %d"
                                .formatted(attribute, e.getKey().isEmpty() ? "(none)" : e.getKey(),
                                        e.getValue(), benchCap));
                    }
                }
            }
        } else {
            Map<Group, Integer> perGroup = new HashMap<>();
            for (Student s : students) {
                perGroup.merge(groupOf(s, config), 1, Integer::sum);
            }
            for (Map.Entry<Group, Integer> e : perGroup.entrySet()) {
                if (e.getValue() > benchCap) {
                    offenders.add("cohort '%s' has %d students but benches can hold at most %d of one cohort"
                            .formatted(groupLabel(e.getKey(), config), e.getValue(), benchCap));
                }
            }
        }
        if (!offenders.isEmpty()) {
            throw new SeatingUnsatisfiableException(
                    "Seating is impossible: " + String.join("; ", offenders)
                            + ". Balance the cohort mix or use SINGLE_SEATER halls.");
        }
    }

    private static String groupLabel(Group group, SeatingConfig config) {
        List<StudentAttribute> key = config.effectiveGroupKey();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < key.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            String v = i < group.values().size() ? group.values().get(i) : "";
            sb.append(key.get(i)).append('=').append(v.isEmpty() ? "(none)" : v);
        }
        return sb.toString();
    }

    private static List<Bench> normalizedBenchGrid(ExamRoom room) {
        final int expected;
        try {
            expected = Math.multiplyExact(room.getRows(), room.getColumns());
        } catch (ArithmeticException ex) {
            throw new IllegalSeatingConfigurationException(
                    "Room '%s' dimensions overflow (%dx%d)".formatted(room.getHallName(),
                            room.getRows(), room.getColumns()));
        }
        List<Bench> benches = room.getBenches();
        if (benches != null && !benches.isEmpty() && benches.size() != expected) {
            throw new IllegalSeatingConfigurationException(
                    "Room '%s' has %d benches but its %dx%d layout requires %d — rebuild the hall grid"
                            .formatted(room.getHallName(), benches.size(),
                                    room.getRows(), room.getColumns(), expected));
        }
        if (benches == null || benches.isEmpty()) {
            List<Bench> fresh = new ArrayList<>(expected);
            for (int r = 1; r <= room.getRows(); r++) {
                for (int c = 1; c <= room.getColumns(); c++) {
                    fresh.add(Bench.builder()
                            .room(room)
                            .rowIndex(r)
                            .colIndex(c)
                            .build());
                }
            }
            return fresh;
        }
        List<Bench> copy = new ArrayList<>(benches);
        copy.sort(Comparator.comparingInt(Bench::getRowIndex)
                .thenComparingInt(Bench::getColIndex));
        for (Bench bench : copy) {
            if (bench.getRoom() == null) {
                bench.setRoom(room);
            }
        }
        return copy;
    }

    private static List<ExamRoom> orderedRooms(List<ExamRoom> rooms) {
        return rooms.stream()
                .sorted(Comparator.comparing(ExamRoom::getHallName, Comparator.nullsLast(String::compareTo))
                        .thenComparingInt(ExamRoom::getTotalBenches)
                        .thenComparingLong(r -> r.getId() == null ? Long.MAX_VALUE : r.getId()))
                .toList();
    }

    private static List<Student> orderedStudents(List<Student> students, SeatingConfig config) {
        Comparator<Student> groupComparator = (a, b) -> {
            List<String> ga = groupOf(a, config).values();
            List<String> gb = groupOf(b, config).values();
            for (int i = 0; i < Math.min(ga.size(), gb.size()); i++) {
                int cmp = ga.get(i).compareTo(gb.get(i));
                if (cmp != 0) {
                    return cmp;
                }
            }
            return 0;
        };
        return students.stream()
                .sorted(groupComparator
                        .thenComparing(Student::getIdentityKey, Comparator.nullsLast(String::compareTo))
                        .thenComparing(s -> StudentAttributes.displayLabel(s))
                        .thenComparing(Student::getName, Comparator.nullsLast(String::compareTo))
                        .thenComparingLong(s -> s.getId() == null ? Long.MAX_VALUE : s.getId()))
                .toList();
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }
}
