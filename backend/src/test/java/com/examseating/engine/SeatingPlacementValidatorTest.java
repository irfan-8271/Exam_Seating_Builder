package com.examseating.engine;

import com.examseating.engine.SeatingArrangementEngine.GroupMatchMode;
import com.examseating.engine.SeatingArrangementEngine.PlacementVerdict;
import com.examseating.engine.SeatingArrangementEngine.SeatOccupant;
import com.examseating.engine.SeatingArrangementEngine.SeatingConfig;
import com.examseating.engine.SeatingArrangementEngine.StudentAttribute;
import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Student;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for {@link SeatingArrangementEngine#checkPlacement} — the dynamic rule
 * check behind manual seat moves.
 *
 * <p>The validator must agree with the solver: any seat the solver would reject
 * under horizontal ({@code dH}), vertical ({@code dV}), diagonal ({@code dD}),
 * or dual-seater bench-mate cohort separation must be refused, and any seat
 * outside those separation neighbourhoods must be accepted.</p>
 */
public class SeatingPlacementValidatorTest {

    private static Student student(long id, String name, String identity, String... kvPairs) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("idcode", identity);
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            attrs.put(kvPairs[i], kvPairs[i + 1]);
        }
        return Student.builder()
                .id(id)
                .name(name)
                .identityKey(identity)
                .attributes(attrs)
                .build();
    }

    private static ExamRoom room(long id, String name, int rows, int cols, ExamRoom.BenchType type) {
        return ExamRoom.builder().id(id).hallName(name).rows(rows).columns(cols).benchType(type).build();
    }

    private static Bench bench(ExamRoom room, int row, int col) {
        return Bench.builder().room(room).rowIndex(row).colIndex(col).build();
    }

    private static SeatOccupant occupant(Student s, Bench b, int slot) {
        return new SeatOccupant(s, b, slot);
    }

    private static SeatingConfig config(int dH, int dV, int dD, String... groupKeys) {
        List<StudentAttribute> attrs = new ArrayList<>();
        for (String k : groupKeys) {
            attrs.add(StudentAttribute.of(k));
        }
        return new SeatingConfig(
                dH, dV, dD,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                attrs,
                GroupMatchMode.ALL);
    }

    // ------------------------------------------------------------------
    // Allowed placements
    // ------------------------------------------------------------------

    @Test
    void testFarAwaySeatIsAllowed() {
        ExamRoom hall = room(1L, "Hall A", 3, 3, ExamRoom.BenchType.SINGLE_SEATER);
        Student candidate = student(1L, "A", "ID-1", "grade", "10", "section", "A");
        Bench target = bench(hall, 1, 1);
        SeatOccupant other = occupant(
                student(2L, "B", "ID-2", "grade", "11", "section", "B"),
                bench(hall, 3, 3), 1);

        PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                candidate, target, 1, config(1, 1, 1, "grade", "section"), List.of(other));

        assertTrue(verdict.allowed(), "a seat outside every separation gap is allowed");
        assertNull(verdict.reason());
        assertNull(verdict.conflictingStudent());
    }

    @Test
    void testSeatInAnotherHallIsAlwaysAllowed() {
        ExamRoom hallA = room(1L, "Hall A", 2, 2, ExamRoom.BenchType.SINGLE_SEATER);
        ExamRoom hallB = room(2L, "Hall B", 2, 2, ExamRoom.BenchType.SINGLE_SEATER);
        Student candidate = student(1L, "A", "ID-1", "grade", "10", "section", "A");
        SeatOccupant other = occupant(
                student(2L, "B", "ID-2", "grade", "10", "section", "A"),
                bench(hallB, 1, 1), 1);

        PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                candidate, bench(hallA, 1, 1), 1, config(1, 1, 1, "grade", "section"), List.of(other));

        assertTrue(verdict.allowed(), "separation rules apply inside one hall only");
    }

    @Test
    void testDisabledGapAxisIsNotEnforced() {
        ExamRoom hall = room(1L, "Hall A", 3, 3, ExamRoom.BenchType.SINGLE_SEATER);
        Student candidate = student(1L, "A", "ID-1", "grade", "10", "section", "A");
        SeatOccupant other = occupant(
                student(2L, "B", "ID-2", "grade", "10", "section", "A"),
                bench(hall, 1, 2), 1);

        PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                candidate, bench(hall, 1, 1), 1, config(0, 0, 0, "grade", "section"), List.of(other));

        assertTrue(verdict.allowed(), "a gap of 0 disables that axis");
    }

    @Test
    void testDifferentCohortNextToEachOtherIsAllowed() {
        ExamRoom hall = room(1L, "Hall A", 3, 3, ExamRoom.BenchType.SINGLE_SEATER);
        Student candidate = student(1L, "A", "ID-1", "grade", "10", "section", "A");
        SeatOccupant other = occupant(
                student(2L, "B", "ID-2", "grade", "10", "section", "B"),
                bench(hall, 1, 2), 1);

        PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                candidate, bench(hall, 1, 1), 1, config(1, 1, 1, "grade", "section"), List.of(other));

        assertTrue(verdict.allowed(), "different classmate cohorts may sit next to each other");
    }

    // ------------------------------------------------------------------
    // Refused placements
    // ------------------------------------------------------------------

    @Test
    void testHorizontalCohortConflictIsEnforced() {
        ExamRoom hall = room(1L, "Hall A", 3, 3, ExamRoom.BenchType.SINGLE_SEATER);
        Student candidate = student(1L, "A", "ID-1", "grade", "10", "section", "A");
        SeatOccupant other = occupant(
                student(2L, "B", "ID-2", "grade", "10", "section", "A"),
                bench(hall, 1, 2), 1);

        PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                candidate, bench(hall, 1, 1), 1, config(1, 0, 0, "grade", "section"), List.of(other));

        assertFalse(verdict.allowed(), "classmates may not sit next to each other");
        assertNotNull(verdict.reason());
        assertEquals("ID-2", verdict.conflictingStudent().getIdentityKey());
    }

    @Test
    void testVerticalGapIsEnforced() {
        ExamRoom hall = room(1L, "Hall A", 3, 3, ExamRoom.BenchType.SINGLE_SEATER);
        Student candidate = student(1L, "A", "ID-1", "batch", "Morning");
        SeatOccupant other = occupant(
                student(2L, "B", "ID-2", "batch", "Morning"),
                bench(hall, 2, 1), 1);

        PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                candidate, bench(hall, 1, 1), 1, config(0, 1, 0, "batch"), List.of(other));

        assertFalse(verdict.allowed(), "classmates may not sit in the same column one row apart when dV=1");
    }

    @Test
    void testDiagonalGapIsEnforcedOnSeatColumns() {
        ExamRoom hall = room(1L, "Hall A", 3, 3, ExamRoom.BenchType.DUAL_SEATER);
        Student candidate = student(1L, "A", "ID-1", "cohort", "C1");
        // Bench (1,1) slot 2 is seatCol 2; bench (2,2) slot 1 is seatCol 3 -> |dr|=1, |seatDc|=1 (diagonal!)
        SeatOccupant other = occupant(
                student(2L, "B", "ID-2", "cohort", "C1"),
                bench(hall, 2, 2), 1);

        PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                candidate, bench(hall, 1, 1), 2, config(0, 0, 1, "cohort"), List.of(other));

        assertFalse(verdict.allowed(), "classmates may not sit diagonally adjacent across dual benches when dD=1");
    }

    @Test
    void testDualBenchMateForbidsSameClassmateCohort() {
        ExamRoom hall = room(1L, "Hall A", 2, 2, ExamRoom.BenchType.DUAL_SEATER);
        Student candidate = student(1L, "A", "ID-1", "branch", "CSE", "semester", "4");
        Bench targetBench = bench(hall, 1, 1);
        SeatOccupant mate = occupant(
                student(2L, "B", "ID-2", "branch", "CSE", "semester", "4"),
                targetBench, 2);

        PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                candidate, targetBench, 1, config(1, 0, 0, "branch", "semester"), List.of(mate));

        assertFalse(verdict.allowed(), "dual-bench mates may never share the same classmate cohort");
    }

    @Test
    void testDualBenchMateAllowsDifferentClassmateCohort() {
        ExamRoom hall = room(1L, "Hall A", 2, 2, ExamRoom.BenchType.DUAL_SEATER);
        Student candidate = student(1L, "A", "ID-1", "branch", "CSE", "semester", "4");
        Bench targetBench = bench(hall, 1, 1);
        SeatOccupant mate = occupant(
                student(2L, "B", "ID-2", "branch", "CSE", "semester", "6"),
                targetBench, 2);

        PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                candidate, targetBench, 1, config(1, 0, 0, "branch", "semester"), List.of(mate));

        assertTrue(verdict.allowed(), "students of different semesters are distinct cohorts under ALL match mode");
    }

    @Test
    void testAnyMatchModeForbidsSharingAnyAttribute() {
        ExamRoom hall = room(1L, "Hall A", 3, 3, ExamRoom.BenchType.SINGLE_SEATER);
        Student candidate = student(1L, "A", "ID-1", "branch", "CSE", "semester", "4");
        SeatOccupant other = occupant(
                student(2L, "B", "ID-2", "branch", "ECE", "semester", "4"),
                bench(hall, 1, 2), 1);
        SeatingConfig anyCfg = new SeatingConfig(
                1, 0, 0,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                List.of(StudentAttribute.of("branch"), StudentAttribute.of("semester")),
                GroupMatchMode.ANY);

        PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                candidate, bench(hall, 1, 1), 1, anyCfg, List.of(other));

        assertFalse(verdict.allowed(), "ANY match mode separates students sharing even one configured attribute");
    }

    // ------------------------------------------------------------------
    // Robustness & solver-validator parity
    // ------------------------------------------------------------------

    @Test
    void testEmptyAndNullOccupantListAllowsPlacement() {
        ExamRoom hall = room(1L, "Hall A", 2, 2, ExamRoom.BenchType.DUAL_SEATER);
        Student candidate = student(1L, "A", "ID-1", "grade", "10");

        assertTrue(SeatingArrangementEngine.checkPlacement(
                candidate, bench(hall, 1, 1), 1, config(1, 1, 1, "grade"), List.of()).allowed());
        assertTrue(SeatingArrangementEngine.checkPlacement(
                candidate, bench(hall, 1, 1), 1, config(1, 1, 1, "grade"), null).allowed());
    }

    @Test
    void testGeneratedPlanStaysValidUnderTheValidator() {
        ExamRoom hall = room(1L, "Hall A", 4, 4, ExamRoom.BenchType.SINGLE_SEATER);
        List<Student> students = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            students.add(student(i + 1, "S" + i, "ID-" + i,
                    "stream", "Sci", "batch", i % 2 == 0 ? "Morning" : "Evening"));
        }
        SeatingConfig cfg = SeatingConfig.hard("stream", "batch");
        SeatingArrangementEngine.SeatingPlan plan =
                new SeatingArrangementEngine().arrange(List.of(hall), students, cfg);
        assertEquals(8, plan.allocations().size());

        for (SeatingAllocation allocation : plan.allocations()) {
            Bench b = allocation.getBench();
            List<SeatOccupant> others = plan.allocations().stream()
                    .filter(a -> a != allocation)
                    .map(a -> new SeatOccupant(a.getStudent(), a.getBench(), a.getSlotNumber()))
                    .toList();
            PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                    allocation.getStudent(), b, allocation.getSlotNumber(), cfg, others);
            assertTrue(verdict.allowed(), "the solver's own layout must pass checkPlacement");
        }
    }
}
