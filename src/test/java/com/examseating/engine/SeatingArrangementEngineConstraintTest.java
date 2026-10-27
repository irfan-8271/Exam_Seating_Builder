package com.examseating.engine;

import com.examseating.engine.SeatingArrangementEngine.BenchPairingRule;
import com.examseating.engine.SeatingArrangementEngine.ConflictScope;
import com.examseating.engine.SeatingArrangementEngine.GroupMatchMode;
import com.examseating.engine.SeatingArrangementEngine.SeatingConfig;
import com.examseating.engine.SeatingArrangementEngine.SeatingPlan;
import com.examseating.engine.SeatingArrangementEngine.SeatingUnsatisfiableException;
import com.examseating.engine.SeatingArrangementEngine.StudentAttribute;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Student;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the unified gap semantics, configurable grouping key,
 * case-insensitive grouping, difficulty-level density bounds and the
 * horizontal-isolation flag (parity between the layer-decomposed and the
 * general solver paths).
 */
public class SeatingArrangementEngineConstraintTest {

    private static Student student(long id, String name, String roll, String dept, String year) {
        return Student.builder().id(id).name(name).rollNo(roll).department(dept).yearSession(year).build();
    }

    private static ExamRoom room(long id, String name, int rows, int cols, ExamRoom.BenchType type) {
        return ExamRoom.builder().id(id).hallName(name).rows(rows).columns(cols).benchType(type).build();
    }

    /** Positions, keyed "row:col:slot", of a plan — for adjacency assertions. */
    private static Map<String, String> positionsBySeat(SeatingPlan plan) {
        Map<String, String> map = new HashMap<>();
        for (SeatingAllocation a : plan.allocations()) {
            map.put(a.getBench().getRowIndex() + ":" + a.getBench().getColIndex() + ":" + a.getSlotNumber(),
                    a.getStudent().getRollNo());
        }
        return map;
    }

    // ------------------------------------------------------------------
    // C1: staggered density bound (previously false-rejected)
    // ------------------------------------------------------------------

    @Test
    void testIntermediateStaggeredBoundSeatsFullRowAlternation() {
        // 4x4 single-seater, gaps (1,1,0): one class can fill 2 alternating
        // columns of every row = 8 seats. The old bound (ceil(R/2)*ceil(C/2)=4)
        // falsely rejected such instances.
        ExamRoom r = room(1L, "Hall S", 4, 4, ExamRoom.BenchType.SINGLE_SEATER);
        List<Student> students = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            students.add(student(i, "S" + i, "R" + i, "CSE", i % 2 == 0 ? "2024" : "2025"));
        }
        SeatingConfig config = new SeatingConfig(1, 1, 0,
                ConflictScope.DEPARTMENT, SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS);

        SeatingPlan plan = new SeatingArrangementEngine().arrange(List.of(r), students, config);
        assertEquals(8, plan.allocations().size(), "staggered packing must seat all 8 classmates");
        assertEquals(0, plan.unplacedStudents().size());

        // verify: no two same-department students share a row within dH=1
        Map<Integer, List<Integer>> rowCols = new HashMap<>();
        for (SeatingAllocation a : plan.allocations()) {
            rowCols.computeIfAbsent(a.getBench().getRowIndex(), k -> new ArrayList<>())
                    .add(a.getBench().getColIndex());
        }
        for (List<Integer> cols : rowCols.values()) {
            cols.sort(Integer::compareTo);
            for (int i = 1; i < cols.size(); i++) {
                assertTrue(cols.get(i) - cols.get(i - 1) > 1,
                        "same-class students must be more than 1 column apart in a row");
            }
        }
    }

    @Test
    void testBasicModeBoundIsExactForSingleGroup() {
        // BASIC (1,0,0) on 4x4: one class can occupy exactly every other seat = 8.
        ExamRoom r = room(1L, "Hall B", 4, 4, ExamRoom.BenchType.SINGLE_SEATER);
        List<Student> students = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            students.add(student(i, "S" + i, "R" + i, "CSE", "2024"));
        }
        SeatingConfig config = SeatingConfig.of(1, 0, 0);
        SeatingPlan plan = new SeatingArrangementEngine().arrange(List.of(r), students, config);
        assertEquals(8, plan.allocations().size());
    }

    // ------------------------------------------------------------------
    // Isolation toggle contrast + solver-path parity
    // ------------------------------------------------------------------

    @Test
    void testHorizontalIsolationToggleOnGeneralPath() {
        // 1x4 single-seater row: 3 CSE + 1 ECE. With isolation ON, two CSE must
        // land side-by-side somewhere (pigeonhole) -> impossible; with it OFF
        // the conflict key (classmates = dept+year) finds no conflict -> seats.
        List<Student> students = List.of(
                student(1, "a", "1", "CSE", "2024"),
                student(2, "b", "2", "CSE", "2025"),
                student(3, "c", "3", "CSE", "2023"),
                student(4, "d", "4", "ECE", "2024"));

        SeatingConfig isolationOn = new SeatingConfig(1, 0, 0, ConflictScope.DEPARTMENT_AND_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR, true);
        assertThrows(SeatingUnsatisfiableException.class, () -> new SeatingArrangementEngine()
                .arrange(List.of(room(1L, "Hall", 1, 4, ExamRoom.BenchType.SINGLE_SEATER)), students, isolationOn),
                "3 same-department students cannot avoid side-by-side placement in a 4-seat row");

        SeatingConfig isolationOff = new SeatingConfig(1, 0, 0, ConflictScope.DEPARTMENT_AND_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR, false);
        SeatingPlan plan = new SeatingArrangementEngine()
                .arrange(List.of(room(1L, "Hall", 1, 4, ExamRoom.BenchType.SINGLE_SEATER)), students, isolationOff);
        assertEquals(4, plan.allocations().size(), "without isolation the key finds no conflicts");
    }

    @Test
    void testIsolationParityOnLayerDecomposedPath() {
        // 1x2 dual-seater (4 seats), exactly two years -> layer fast path. The
        // two benches' junction seat-pair forces a same-department horizontal
        // contact whatever the arrangement, so isolation ON must fail and OFF
        // must seat — exercising the same semantics in the layer solver.
        List<Student> students = List.of(
                student(1, "a", "1", "CSE", "2024"),
                student(2, "b", "2", "ECE", "2025"),
                student(3, "c", "3", "ECE", "2024"),
                student(4, "d", "4", "CSE", "2025"));

        SeatingConfig isolationOn = new SeatingConfig(1, 0, 0, ConflictScope.DEPARTMENT_AND_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR, true);
        assertThrows(SeatingUnsatisfiableException.class, () -> new SeatingArrangementEngine()
                .arrange(List.of(room(1L, "Dual", 1, 2, ExamRoom.BenchType.DUAL_SEATER)), students, isolationOn),
                "the bench junction forces same-department horizontal contact");

        SeatingConfig isolationOff = new SeatingConfig(1, 0, 0, ConflictScope.DEPARTMENT_AND_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR, false);
        SeatingPlan plan = new SeatingArrangementEngine()
                .arrange(List.of(room(1L, "Dual", 1, 2, ExamRoom.BenchType.DUAL_SEATER)), students, isolationOff);
        assertEquals(4, plan.allocations().size(), "cross pairings seat all four without isolation");
    }

    // ------------------------------------------------------------------
    // Configurable grouping key
    // ------------------------------------------------------------------

    @Test
    void testCustomGroupKeyCourseForbidsAdjacentClassmates() {
        // Institution defines "classmates" as the course: every student shares
        // it, so no two may sit side-by-side. 2x4 single-seater, dH=1.
        List<Student> students = new ArrayList<>();
        String[] depts = {"CSE", "ECE", "ME", "CE"};
        for (int i = 0; i < 4; i++) {
            Student s = student(i, "s" + i, "R" + i, depts[i], "2024");
            s.setCourse("B.Tech");
            students.add(s);
        }
        SeatingConfig config = new SeatingConfig(1, 0, 0,
                ConflictScope.DEPARTMENT_AND_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR, false,
                List.of(StudentAttribute.COURSE), GroupMatchMode.ALL);

        SeatingPlan plan = new SeatingArrangementEngine()
                .arrange(List.of(room(1L, "Hall", 2, 4, ExamRoom.BenchType.SINGLE_SEATER)), students, config);
        assertEquals(4, plan.allocations().size());

        Map<Integer, List<Integer>> rowCols = new HashMap<>();
        for (SeatingAllocation a : plan.allocations()) {
            rowCols.computeIfAbsent(a.getBench().getRowIndex(), k -> new ArrayList<>())
                    .add(a.getBench().getColIndex());
        }
        for (List<Integer> cols : rowCols.values()) {
            cols.sort(Integer::compareTo);
            for (int i = 1; i < cols.size(); i++) {
                assertTrue(cols.get(i) - cols.get(i - 1) > 1,
                        "same-course students must be more than 1 column apart");
            }
        }
    }

    @Test
    void testCustomGroupKeyANYModeSeparatesOnSharedAttribute() {
        // ANY mode: sharing EITHER department or year makes two students
        // classmates. 1x2 single-seater: two students sharing only the year
        // must not be adjacent -> impossible at dH=1 in a 2-seat row.
        List<Student> students = List.of(
                student(1, "a", "1", "CSE", "2024"),
                student(2, "b", "2", "ECE", "2024"));
        SeatingConfig anyMode = new SeatingConfig(1, 0, 0,
                ConflictScope.DEPARTMENT_AND_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR, false,
                List.of(StudentAttribute.DEPARTMENT, StudentAttribute.YEAR_SESSION), GroupMatchMode.ANY);
        assertThrows(SeatingUnsatisfiableException.class, () -> new SeatingArrangementEngine()
                .arrange(List.of(room(1L, "Hall", 1, 2, ExamRoom.BenchType.SINGLE_SEATER)), students, anyMode),
                "sharing one attribute (year) must separate them in ANY mode");
    }

    // ------------------------------------------------------------------
    // Case-insensitive grouping (C14)
    // ------------------------------------------------------------------

    @Test
    void testGroupingIsCaseInsensitive() {
        // "CSE" and "cse" are the same class: a 2-seat row at dH=1 cannot seat
        // both under key=[DEPARTMENT] (bound = 1 per row), whatever the case.
        ExamRoom r = room(1L, "Tiny", 1, 2, ExamRoom.BenchType.SINGLE_SEATER);
        List<Student> clash = List.of(
                student(1, "a", "1", "CSE", "2024"),
                student(2, "b", "2", "cse", "2025"));
        SeatingConfig config = new SeatingConfig(1, 0, 0,
                ConflictScope.DEPARTMENT, SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS);

        assertThrows(SeatingUnsatisfiableException.class,
                () -> new SeatingArrangementEngine().arrange(List.of(r), clash, config),
                "CSE and cse must count as one group");

        // Control: different departments seat fine.
        List<Student> ok = List.of(
                student(1, "a", "1", "CSE", "2024"),
                student(2, "b", "2", "ECE", "2025"));
        SeatingPlan plan = new SeatingArrangementEngine().arrange(List.of(r), ok, config);
        assertEquals(2, plan.allocations().size());
    }

    // ------------------------------------------------------------------
    // Difficulty levels: Basic bound sanity (0-gap axes disable rules)
    // ------------------------------------------------------------------

    @Test
    void testZeroGapsDisableRules() {
        // gaps (0,0,0): no rules at all — two classmates may share a bench.
        List<Student> students = List.of(
                student(1, "a", "1", "CSE", "2024"),
                student(2, "b", "2", "CSE", "2024"));
        SeatingConfig config = new SeatingConfig(0, 0, 0,
                ConflictScope.DEPARTMENT_AND_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR, false);
        // pairing rule still applies to a shared bench (additive), so use
        // single-seater benches to isolate the gap behaviour
        SeatingPlan plan = new SeatingArrangementEngine()
                .arrange(List.of(room(1L, "Hall", 1, 2, ExamRoom.BenchType.SINGLE_SEATER)), students, config);
        assertEquals(2, plan.allocations().size());
        // With gaps 0 they may be adjacent — assert adjacency is NOT prevented:
        Map<String, String> seats = positionsBySeat(plan);
        assertTrue(seats.size() == 2, "both seats filled");
    }
}
