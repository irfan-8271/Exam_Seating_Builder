package com.examseating.engine;

import com.examseating.engine.SeatingArrangementEngine.BenchPairingRule;
import com.examseating.engine.SeatingArrangementEngine.ConflictScope;
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
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link SeatingArrangementEngine#checkPlacement} — the rule check
 * behind manual seat moves.
 *
 * <p>The validator must agree with the solver: a seat the solver would never
 * pick must be refused, and a seat that breaks no rule must be accepted. The
 * cases below mirror the gap semantics documented in the engine: horizontal
 * (same row, within dH), vertical (same bench column, within dV) and diagonal
 * (within dD in both axes), plus the bench-mate rule and the optional
 * horizontal department isolation.</p>
 */
public class SeatingPlacementValidatorTest {

        private static Student student(long id, String name, String roll, String dept, String year) {
                return Student.builder().id(id).name(name).rollNo(roll).department(dept).yearSession(year).build();
        }

        private static ExamRoom room(long id, String name, int rows, int cols, ExamRoom.BenchType type) {
                return ExamRoom.builder().id(id).hallName(name).rows(rows).columns(cols).benchType(type).build();
        }

        private static Bench bench(ExamRoom room, int row, int col) {
                return Bench.builder().room(room).rowIndex(row).colIndex(col)
                                .benchType(room.getBenchType()).build();
        }

        private static SeatOccupant occupant(Student s, Bench b, int slot) {
                return new SeatOccupant(s, b, slot);
        }

        private static SeatingConfig config(int dH, int dV, int dD) {
                return new SeatingConfig(dH, dV, dD, ConflictScope.DEPARTMENT_AND_YEAR,
                                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS);
        }

        // ------------------------------------------------------------------
        // Allowed placements
        // ------------------------------------------------------------------

        @Test
        void testFarAwaySeatIsAllowed() {
                ExamRoom hall = room(1L, "Hall A", 3, 3, ExamRoom.BenchType.SINGLE_SEATER);
                Student candidate = student(1L, "A", "R1", "CSE", "2024");
                Bench target = bench(hall, 1, 1);
                SeatOccupant other = occupant(student(2L, "B", "R2", "ECE", "2025"), bench(hall, 3, 3), 1);

                PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                                candidate, target, 1, config(1, 1, 1), List.of(other));

                assertTrue(verdict.allowed(), "a seat outside every separation gap is allowed");
                assertNull(verdict.reason());
                assertNull(verdict.conflictingStudent());
        }

        @Test
        void testSeatInAnotherHallIsAlwaysAllowed() {
                ExamRoom hallA = room(1L, "Hall A", 2, 2, ExamRoom.BenchType.SINGLE_SEATER);
                ExamRoom hallB = room(2L, "Hall B", 2, 2, ExamRoom.BenchType.SINGLE_SEATER);
                Student candidate = student(1L, "A", "R1", "CSE", "2024");
                // Same row, same column, same everything — but a different hall.
                SeatOccupant other = occupant(student(2L, "B", "R2", "CSE", "2024"), bench(hallB, 1, 1), 1);

                PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                                candidate, bench(hallA, 1, 1), 1, config(1, 1, 1), List.of(other));

                assertTrue(verdict.allowed(), "separation rules apply inside one hall only");
        }

        @Test
        void testDisabledGapAxisIsNotEnforced() {
                ExamRoom hall = room(1L, "Hall A", 3, 3, ExamRoom.BenchType.SINGLE_SEATER);
                Student candidate = student(1L, "A", "R1", "CSE", "2024");
                SeatOccupant other = occupant(student(2L, "B", "R2", "CSE", "2024"), bench(hall, 1, 2), 1);

                // dH = dV = dD = 0 switches every separation axis off.
                PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                                candidate, bench(hall, 1, 1), 1, config(0, 0, 0), List.of(other));

                assertTrue(verdict.allowed(), "a gap of 0 disables that axis");
        }

        @Test
        void testDifferentDepartmentNextToEachOtherIsAllowed() {
                ExamRoom hall = room(1L, "Hall A", 3, 3, ExamRoom.BenchType.SINGLE_SEATER);
                Student candidate = student(1L, "A", "R1", "CSE", "2024");
                SeatOccupant other = occupant(student(2L, "B", "R2", "ECE", "2025"), bench(hall, 1, 2), 1);

                PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                                candidate, bench(hall, 1, 1), 1, config(1, 1, 1), List.of(other));

                assertTrue(verdict.allowed(), "different department and year may sit next to each other");
        }

        // ------------------------------------------------------------------
        // Refused placements
        // ------------------------------------------------------------------

        @Test
        void testHorizontalConflictKeyIsEnforced() {
                ExamRoom hall = room(1L, "Hall A", 3, 3, ExamRoom.BenchType.SINGLE_SEATER);
                Student candidate = student(1L, "A", "R1", "CSE", "2024");
                SeatOccupant other = occupant(student(2L, "B", "R2", "CSE", "2024"), bench(hall, 1, 2), 1);

                PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                                candidate, bench(hall, 1, 1), 1, config(1, 0, 0), List.of(other));

                assertFalse(verdict.allowed(), "classmates may not sit next to each other");
                assertNotNull(verdict.reason());
                assertEquals("R2", verdict.conflictingStudent().getRollNo());
        }

        @Test
        void testHorizontalDepartmentIsolationIsEnforced() {
                ExamRoom hall = room(1L, "Hall A", 3, 3, ExamRoom.BenchType.SINGLE_SEATER);
                Student candidate = student(1L, "A", "R1", "CSE", "2024");
                // Same department, different year: not a conflict under DEPARTMENT_AND_YEAR,
                // but the horizontal isolation still forbids it.
                SeatOccupant other = occupant(student(2L, "B", "R2", "CSE", "2025"), bench(hall, 1, 2), 1);

                PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                                candidate, bench(hall, 1, 1), 1, config(1, 0, 0), List.of(other));

                assertFalse(verdict.allowed(), "horizontally adjacent seats may not share a department");
                assertEquals("R2", verdict.conflictingStudent().getRollNo());
        }

        @Test
        void testHorizontalDepartmentIsolationCanBeSwitchedOff() {
                ExamRoom hall = room(1L, "Hall A", 3, 3, ExamRoom.BenchType.SINGLE_SEATER);
                Student candidate = student(1L, "A", "R1", "CSE", "2024");
                SeatOccupant other = occupant(student(2L, "B", "R2", "CSE", "2025"), bench(hall, 1, 2), 1);
                SeatingConfig relaxed = new SeatingConfig(1, 0, 0, ConflictScope.DEPARTMENT_AND_YEAR,
                                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                                BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR, false);

                PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                                candidate, bench(hall, 1, 1), 1, relaxed, List.of(other));

                assertTrue(verdict.allowed(), "without the isolation flag only the conflict key applies");
        }

        @Test
        void testVerticalGapIsEnforced() {
                ExamRoom hall = room(1L, "Hall A", 3, 3, ExamRoom.BenchType.SINGLE_SEATER);
                Student candidate = student(1L, "A", "R1", "CSE", "2024");
                SeatOccupant other = occupant(student(2L, "B", "R2", "CSE", "2024"), bench(hall, 2, 1), 1);

                PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                                candidate, bench(hall, 1, 1), 1, config(0, 1, 0), List.of(other));

                assertFalse(verdict.allowed(), "classmates may not sit in the same bench column one row apart");
        }

        @Test
        void testDiagonalGapIsEnforced() {
                ExamRoom hall = room(1L, "Hall A", 3, 3, ExamRoom.BenchType.SINGLE_SEATER);
                Student candidate = student(1L, "A", "R1", "CSE", "2024");
                SeatOccupant other = occupant(student(2L, "B", "R2", "CSE", "2024"), bench(hall, 2, 2), 1);

                PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                                candidate, bench(hall, 1, 1), 1, config(0, 0, 1), List.of(other));

                assertFalse(verdict.allowed(), "classmates may not sit diagonally next to each other");
        }

        @Test
        void testBenchMateRuleForbidsSameDepartment() {
                ExamRoom hall = room(1L, "Hall A", 2, 2, ExamRoom.BenchType.DUAL_SEATER);
                Student candidate = student(1L, "A", "R1", "CSE", "2024");
                Bench bench = bench(hall, 1, 1);
                SeatOccupant mate = occupant(student(2L, "B", "R2", "CSE", "2025"), bench, 2);

                PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                                candidate, bench, 1, config(1, 0, 0), List.of(mate));

                assertFalse(verdict.allowed(), "bench mates may not share a department");
        }

        @Test
        void testBenchMateRuleForbidsSameYear() {
                ExamRoom hall = room(1L, "Hall A", 2, 2, ExamRoom.BenchType.DUAL_SEATER);
                Student candidate = student(1L, "A", "R1", "CSE", "2024");
                Bench bench = bench(hall, 1, 1);
                SeatOccupant mate = occupant(student(2L, "B", "R2", "ECE", "2024"), bench, 2);

                PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                                candidate, bench, 1, config(1, 0, 0), List.of(mate));

                assertFalse(verdict.allowed(), "bench mates may not share a year/session");
        }

        @Test
        void testBenchMateRuleCanAllowSameYear() {
                ExamRoom hall = room(1L, "Hall A", 2, 2, ExamRoom.BenchType.DUAL_SEATER);
                Student candidate = student(1L, "A", "R1", "CSE", "2024");
                Bench bench = bench(hall, 1, 1);
                SeatOccupant mate = occupant(student(2L, "B", "R2", "ECE", "2024"), bench, 2);
                SeatingConfig relaxed = new SeatingConfig(1, 0, 0, ConflictScope.DEPARTMENT_AND_YEAR,
                                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                                BenchPairingRule.DIFFERENT_DEPARTMENT_ONLY, true);

                PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                                candidate, bench, 1, relaxed, List.of(mate));

                assertTrue(verdict.allowed(), "DIFFERENT_DEPARTMENT_ONLY allows a shared year/session");
        }

        // ------------------------------------------------------------------
        // Configurable conflict group key
        // ------------------------------------------------------------------

        @Test
        void testCustomGroupAttributeDecidesTheConflict() {
                ExamRoom hall = room(1L, "Hall A", 3, 3, ExamRoom.BenchType.SINGLE_SEATER);
                Student candidate = Student.builder().id(1L).name("A").rollNo("R1")
                                .department("CSE").yearSession("2024").course("MATH").build();
                // Different department and year, but the same course: with COURSE as the
                // group key the two students are classmates.
                Student otherStudent = Student.builder().id(2L).name("B").rollNo("R2")
                                .department("ECE").yearSession("2025").course("MATH").build();
                SeatOccupant other = occupant(otherStudent, bench(hall, 1, 2), 1);
                SeatingConfig byCourse = new SeatingConfig(1, 0, 0, ConflictScope.DEPARTMENT_AND_YEAR,
                                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                                BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR, true,
                                List.of(StudentAttribute.COURSE), null);

                PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                                candidate, bench(hall, 1, 1), 1, byCourse, List.of(other));

                assertFalse(verdict.allowed(), "the configured group key decides who conflicts");
        }

        @Test
        void testCustomGroupAttributeAllowsNonConflictingNeighbour() {
                ExamRoom hall = room(1L, "Hall A", 3, 3, ExamRoom.BenchType.SINGLE_SEATER);
                Student candidate = Student.builder().id(1L).name("A").rollNo("R1")
                                .department("CSE").yearSession("2024").course("MATH").build();
                Student otherStudent = Student.builder().id(2L).name("B").rollNo("R2")
                                .department("ECE").yearSession("2025").course("PHYSICS").build();
                SeatOccupant other = occupant(otherStudent, bench(hall, 1, 2), 1);
                SeatingConfig byCourse = new SeatingConfig(1, 0, 0, ConflictScope.DEPARTMENT_AND_YEAR,
                                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                                BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR, true,
                                List.of(StudentAttribute.COURSE), null);

                PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                                candidate, bench(hall, 1, 1), 1, byCourse, List.of(other));

                assertTrue(verdict.allowed(), "a different course is not a conflict under the COURSE key");
        }

        // ------------------------------------------------------------------
        // Robustness
        // ------------------------------------------------------------------

        @Test
        void testEmptyHallAlwaysAllowsThePlacement() {
                ExamRoom hall = room(1L, "Hall A", 2, 2, ExamRoom.BenchType.DUAL_SEATER);
                Student candidate = student(1L, "A", "R1", "CSE", "2024");

                PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                                candidate, bench(hall, 1, 1), 1, config(1, 1, 1), List.of());

                assertTrue(verdict.allowed());
        }

        @Test
        void testNullOccupantListIsTreatedAsAnEmptyHall() {
                ExamRoom hall = room(1L, "Hall A", 2, 2, ExamRoom.BenchType.DUAL_SEATER);
                Student candidate = student(1L, "A", "R1", "CSE", "2024");

                PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                                candidate, bench(hall, 1, 1), 1, config(1, 1, 1), null);

                assertTrue(verdict.allowed());
        }

        /**
         * The validator must accept exactly the seats the solver would accept.
         * A generated plan is checked seat by seat: moving every student to the
         * seat it already occupies must be allowed.
         */
        @Test
        void testGeneratedPlanStaysValidUnderTheValidator() {
                ExamRoom hall = room(1L, "Hall A", 4, 4, ExamRoom.BenchType.SINGLE_SEATER);
                List<Student> students = new ArrayList<>();
                for (int i = 0; i < 8; i++) {
                        students.add(student(i, "S" + i, "R" + i, "CSE", i % 2 == 0 ? "2024" : "2025"));
                }
                SeatingConfig cfg = new SeatingConfig(1, 1, 1, ConflictScope.DEPARTMENT_AND_YEAR,
                                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS);
                SeatingArrangementEngine.SeatingPlan plan =
                                new SeatingArrangementEngine().arrange(List.of(hall), students, cfg);
                assertEquals(8, plan.allocations().size());

                for (SeatingAllocation allocation : plan.allocations()) {
                        Bench bench = allocation.getBench();
                        List<SeatOccupant> others = plan.allocations().stream()
                                        .filter(a -> a != allocation)
                                        .map(a -> new SeatOccupant(a.getStudent(), a.getBench(), a.getSlotNumber()))
                                        .toList();
                        PlacementVerdict verdict = SeatingArrangementEngine.checkPlacement(
                                        allocation.getStudent(), bench, allocation.getSlotNumber(), cfg, others);
                        assertTrue(verdict.allowed(),
                                        "the solver's own layout must pass the placement check");
                }
        }
}
