package com.examseating.engine;

import com.examseating.engine.SeatingArrangementEngine.GroupMatchMode;
import com.examseating.engine.SeatingArrangementEngine.IllegalSeatingConfigurationException;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Constraint & geometry tests for the pure dynamic CSP engine:
 * staggered packing bounds, configurable multi-attribute cohort keys,
 * ALL vs ANY match modes, case-insensitive attribute canonicalization,
 * and dynamic scope filtering via {@code arrangeForScope}.
 */
public class SeatingArrangementEngineConstraintTest {

    private static Student student(long id, String name, String identity, String... kvPairs) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("id", identity);
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            attrs.put(kvPairs[i], kvPairs[i + 1]);
        }
        return Student.builder().id(id).name(name).identityKey(identity).attributes(attrs).build();
    }

    private static ExamRoom room(long id, String name, int rows, int cols, ExamRoom.BenchType type) {
        return ExamRoom.builder().id(id).hallName(name).rows(rows).columns(cols).benchType(type).build();
    }

    @Test
    void testIntermediateCheckerboardBoundSeatsHalfOfGrid() {
        // 4x4 single-seater, gaps (1,1,0): checkerboard allows 8 seats of the same cohort!
        ExamRoom r = room(1L, "Hall S", 4, 4, ExamRoom.BenchType.SINGLE_SEATER);
        List<Student> students = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            students.add(student(i + 1, "S" + i, "R" + i, "grade", "10", "division", "A"));
        }
        SeatingConfig config = SeatingConfig.intermediate("grade", "division");

        SeatingPlan plan = new SeatingArrangementEngine().arrange(List.of(r), students, config);
        assertEquals(8, plan.allocations().size(), "checkerboard packing must seat all 8 classmates at (1,1,0)");
        assertEquals(0, plan.unplacedStudents().size());

        boolean[][] grid = new boolean[5][5];
        for (SeatingAllocation a : plan.allocations()) {
            grid[a.getBench().getRowIndex()][a.getBench().getColIndex()] = true;
        }
        for (int row = 1; row <= 4; row++) {
            for (int col = 1; col <= 4; col++) {
                if (!grid[row][col]) continue;
                if (col < 4) assertFalse(grid[row][col + 1], "horizontal adjacency forbidden");
                if (row < 4) assertFalse(grid[row + 1][col], "vertical adjacency forbidden");
            }
        }
    }

    @Test
    void testNormalModeBoundIsExactForSingleCohort() {
        // NORMAL (1,0,0) on 4x4: one cohort can occupy every other column in every row = 8 seats.
        ExamRoom r = room(1L, "Hall B", 4, 4, ExamRoom.BenchType.SINGLE_SEATER);
        List<Student> students = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            students.add(student(i + 1, "S" + i, "R" + i, "batch", "JEE-Foundation"));
        }
        SeatingPlan plan = new SeatingArrangementEngine()
                .arrange(List.of(r), students, SeatingConfig.normal("batch"));
        assertEquals(8, plan.allocations().size());
    }

    @Test
    void testSingleVsCompositeCohortKeyContrast() {
        // 1x4 single-seater row: 3 students share stream=CSE (with different batches), 1 has stream=ECE.
        // Under cohort=[stream], 3 CSE in 4 horizontal seats is impossible by pigeonhole.
        // Under cohort=[stream, batch], all 4 belong to distinct cohorts and seat cleanly!
        List<Student> students = List.of(
                student(1, "a", "1", "stream", "CSE", "batch", "2024"),
                student(2, "b", "2", "stream", "CSE", "batch", "2025"),
                student(3, "c", "3", "stream", "CSE", "batch", "2026"),
                student(4, "d", "4", "stream", "ECE", "batch", "2024"));

        ExamRoom rowHall = room(1L, "Row", 1, 4, ExamRoom.BenchType.SINGLE_SEATER);
        assertThrows(SeatingUnsatisfiableException.class,
                () -> new SeatingArrangementEngine().arrange(
                        List.of(rowHall), students, SeatingConfig.normal("stream")),
                "3 same-stream students cannot avoid side-by-side placement in a 4-seat row");

        SeatingPlan plan = new SeatingArrangementEngine().arrange(
                List.of(rowHall), students, SeatingConfig.normal("stream", "batch"));
        assertEquals(4, plan.allocations().size());
    }

    @Test
    void testCustomCohortAttributeCourseForbidsAdjacentClassmates() {
        List<Student> students = new ArrayList<>();
        String[] tracks = {"AI", "Robotics", "Systems", "Theory"};
        for (int i = 0; i < 4; i++) {
            students.add(student(i + 1, "s" + i, "R" + i, "track", tracks[i], "program", "M.Sc"));
        }
        SeatingPlan plan = new SeatingArrangementEngine().arrange(
                List.of(room(1L, "Hall", 2, 4, ExamRoom.BenchType.SINGLE_SEATER)),
                students,
                SeatingConfig.normal("program"));
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
                        "same-program students must be more than 1 column apart");
            }
        }
    }

    @Test
    void testCustomCohortKeyANYModeSeparatesOnSharedAttribute() {
        List<Student> students = List.of(
                student(1, "a", "1", "house", "Red", "grade", "10"),
                student(2, "b", "2", "house", "Blue", "grade", "10"));
        SeatingConfig anyMode = new SeatingConfig(
                1, 0, 0,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                List.of(StudentAttribute.of("house"), StudentAttribute.of("grade")),
                GroupMatchMode.ANY);
        assertThrows(SeatingUnsatisfiableException.class,
                () -> new SeatingArrangementEngine().arrange(
                        List.of(room(1L, "Hall", 1, 2, ExamRoom.BenchType.SINGLE_SEATER)),
                        students, anyMode),
                "sharing one attribute (grade) must separate them in ANY mode");
    }

    @Test
    void testGroupingIsCaseAndWhitespaceInsensitive() {
        ExamRoom r = room(1L, "Tiny", 1, 2, ExamRoom.BenchType.SINGLE_SEATER);
        List<Student> clash = List.of(
                student(1, "a", "1", "stream", "Computer  Science"),
                student(2, "b", "2", "stream", "  computer science "));
        SeatingConfig config = SeatingConfig.normal("stream");

        assertThrows(SeatingUnsatisfiableException.class,
                () -> new SeatingArrangementEngine().arrange(List.of(r), clash, config),
                "case/whitespace variants must canonicalize to the same cohort");

        List<Student> ok = List.of(
                student(1, "a", "1", "stream", "Computer Science"),
                student(2, "b", "2", "stream", "Biotechnology"));
        SeatingPlan plan = new SeatingArrangementEngine().arrange(List.of(r), ok, config);
        assertEquals(2, plan.allocations().size());
    }

    @Test
    void testMissingGroupAttributesRejectedExplicitly() {
        ExamRoom r = room(1L, "Tiny", 2, 2, ExamRoom.BenchType.SINGLE_SEATER);
        List<Student> students = List.of(student(1, "a", "1", "grade", "10"));
        assertThrows(IllegalSeatingConfigurationException.class, () -> {
            SeatingConfig emptyCfg = new SeatingConfig(1, 0, 0, 1000, List.of(), GroupMatchMode.ALL);
            new SeatingArrangementEngine().arrange(List.of(r), students, emptyCfg);
        });
    }

    @Test
    void testArrangeForScopeFiltersDynamicallyByAnyAttribute() {
        ExamRoom r = room(1L, "Hall", 2, 4, ExamRoom.BenchType.SINGLE_SEATER);
        List<Student> all = List.of(
                student(1, "A", "1", "cohort", "C1", "session", "Morning"),
                student(2, "B", "2", "cohort", "C2", "session", "Morning"),
                student(3, "C", "3", "cohort", "C1", "session", "Afternoon"),
                student(4, "D", "4", "cohort", "C2", "session", "Afternoon"));
        SeatingPlan morningPlan = new SeatingArrangementEngine().arrangeForScope(
                List.of(r), all, "session", "morning", SeatingConfig.normal("cohort"));
        assertEquals(2, morningPlan.allocations().size());
        for (SeatingAllocation alloc : morningPlan.allocations()) {
            assertEquals("Morning", alloc.getStudent().getAttributes().get("session"));
            assertEquals("morning", alloc.getScopeKey());
        }
    }
}
