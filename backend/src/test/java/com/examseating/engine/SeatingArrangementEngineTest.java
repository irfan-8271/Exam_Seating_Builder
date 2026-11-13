package com.examseating.engine;

import com.examseating.engine.SeatingArrangementEngine.GroupMatchMode;
import com.examseating.engine.SeatingArrangementEngine.SeatingConfig;
import com.examseating.engine.SeatingArrangementEngine.SeatingPlan;
import com.examseating.engine.SeatingArrangementEngine.StudentAttribute;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Student;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class SeatingArrangementEngineTest {

    private static Student buildStudent(long id, String name, String identity, String... kvPairs) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("id", identity);
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

    @Test
    void testSingleSeaterNormalMode() {
        SeatingArrangementEngine engine = new SeatingArrangementEngine();
        ExamRoom room = ExamRoom.builder()
                .id(1L)
                .hallName("Single Hall 1")
                .rows(4)
                .columns(4)
                .benchType(ExamRoom.BenchType.SINGLE_SEATER)
                .build();

        List<Student> students = new ArrayList<>();
        long id = 1;
        for (int i = 0; i < 6; i++) {
            students.add(buildStudent(id++, "CSE-" + i, "10" + i, "stream", "CSE", "batch", i % 2 == 0 ? "A" : "B"));
        }
        for (int i = 0; i < 5; i++) {
            students.add(buildStudent(id++, "ECE-" + i, "20" + i, "stream", "ECE", "batch", i % 2 == 0 ? "A" : "B"));
        }
        for (int i = 0; i < 5; i++) {
            students.add(buildStudent(id++, "ME-" + i, "30" + i, "stream", "ME", "batch", i % 2 == 0 ? "A" : "B"));
        }

        SeatingConfig config = SeatingConfig.normal("stream");
        SeatingPlan plan = engine.arrange(List.of(room), students, config);
        assertEquals(16, plan.allocations().size(), "All 16 students should be seated");
        assertEquals(0, plan.unplacedStudents().size());

        verifyHorizontalAttributeIsolation(plan, 4, 4, "stream");
    }

    @Test
    void testSingleSeaterHardModeWithAnyMatchMode() {
        SeatingArrangementEngine engine = new SeatingArrangementEngine();
        ExamRoom room = ExamRoom.builder()
                .id(1L)
                .hallName("Single Hall Hard")
                .rows(4)
                .columns(4)
                .benchType(ExamRoom.BenchType.SINGLE_SEATER)
                .build();

        List<Student> students = new ArrayList<>();
        long id = 1;
        String[] streams = {"CSE", "ECE", "ME", "CE"};
        String[] years = {"2022", "2023", "2024", "2025"};
        for (int d = 0; d < 4; d++) {
            for (int y = 0; y < 4; y++) {
                String yr = years[(d + y) % 4];
                students.add(buildStudent(id, streams[d] + "-" + yr, "R" + id,
                        "stream", streams[d], "year", yr));
                id++;
            }
        }

        SeatingConfig config = new SeatingConfig(
                1, 1, 1,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                List.of(StudentAttribute.of("stream"), StudentAttribute.of("year")),
                GroupMatchMode.ANY);

        SeatingPlan plan = engine.arrange(List.of(room), students, config);
        assertEquals(16, plan.allocations().size(), "All 16 students must be seated in Hard + ANY Mode");
        assertEquals(0, plan.unplacedStudents().size());

        verifyMooreNeighborhoodAnyIsolation(plan, 4, 4, "stream", "year");
    }

    @Test
    void testSingleSeaterWithSpareCapacity() {
        SeatingArrangementEngine engine = new SeatingArrangementEngine();
        ExamRoom room = ExamRoom.builder()
                .id(1L)
                .hallName("Single Hall 1")
                .rows(3)
                .columns(3)
                .benchType(ExamRoom.BenchType.SINGLE_SEATER)
                .build();

        List<Student> students = new ArrayList<>();
        long id = 1;
        for (int i = 0; i < 4; i++) {
            students.add(buildStudent(id++, "G10A-" + i, "10" + i, "cohort", "10-A"));
            students.add(buildStudent(id++, "G10B-" + i, "20" + i, "cohort", "10-B"));
        }

        SeatingPlan plan = engine.arrange(List.of(room), students, SeatingConfig.normal("cohort"));
        assertEquals(8, plan.allocations().size());
        assertEquals(0, plan.unplacedStudents().size());
        verifyHorizontalAttributeIsolation(plan, 3, 3, "cohort");
    }

    @Test
    void testDualSeaterNormalMode() {
        SeatingArrangementEngine engine = new SeatingArrangementEngine();
        ExamRoom room = ExamRoom.builder()
                .id(1L)
                .hallName("Dual Hall 1")
                .rows(4)
                .columns(2)
                .benchType(ExamRoom.BenchType.DUAL_SEATER)
                .build();

        List<Student> students = new ArrayList<>();
        long id = 1;
        for (int i = 0; i < 4; i++) {
            students.add(buildStudent(id++, "CSE-" + i, "10" + i, "stream", "CSE", "year", "2024"));
            students.add(buildStudent(id++, "ECE-" + i, "20" + i, "stream", "ECE", "year", "2025"));
            students.add(buildStudent(id++, "ME-" + i, "30" + i, "stream", "ME", "year", "2024"));
            students.add(buildStudent(id++, "CE-" + i, "40" + i, "stream", "CE", "year", "2025"));
        }

        SeatingConfig config = new SeatingConfig(
                1, 0, 0,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                List.of(StudentAttribute.of("stream"), StudentAttribute.of("year")),
                GroupMatchMode.ANY);

        SeatingPlan plan = engine.arrange(List.of(room), students, config);
        assertEquals(16, plan.allocations().size());
        assertEquals(0, plan.unplacedStudents().size());

        for (SeatingAllocation a1 : plan.allocations()) {
            for (SeatingAllocation a2 : plan.allocations()) {
                if (a1 != a2 && a1.getBench() == a2.getBench()) {
                    assertNotEquals(a1.getStudent().getAttributes().get("stream"),
                            a2.getStudent().getAttributes().get("stream"));
                    assertNotEquals(a1.getStudent().getAttributes().get("year"),
                            a2.getStudent().getAttributes().get("year"));
                }
            }
        }
    }

    @Test
    void testTenHallsDualSeaterNormalMode300Students() {
        SeatingArrangementEngine engine = new SeatingArrangementEngine();
        List<ExamRoom> rooms = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            rooms.add(ExamRoom.builder()
                    .id((long) i)
                    .hallName("Hall " + i)
                    .rows(10)
                    .columns(3)
                    .benchType(ExamRoom.BenchType.DUAL_SEATER)
                    .build());
        }

        List<Student> students = new ArrayList<>();
        long id = 1;
        String[] depts = {"Computer Science", "Electronics", "Mechanical"};
        for (String dept : depts) {
            for (int i = 0; i < 100; i++) {
                students.add(buildStudent(id, "Student " + id, dept.substring(0, 2) + id,
                        "department", dept, "yearsession", i < 50 ? "2024" : "2025"));
                id++;
            }
        }

        SeatingPlan plan = engine.arrange(rooms, students, SeatingConfig.normal("department", "yearsession"));
        assertEquals(300, plan.allocations().size());
        assertEquals(0, plan.unplacedStudents().size());
    }

    @Test
    void testActualExcel300StudentsWithTenHalls() throws Exception {
        File file = new File("sample_excel_files/students_combined_300.xlsx");
        assertTrue(file.exists(), "students_combined_300.xlsx must exist");

        List<Student> students = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(file, null, true)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) continue;
                String roll = formatter.formatCellValue(row.getCell(0));
                students.add(buildStudent(
                        i,
                        formatter.formatCellValue(row.getCell(1)),
                        roll,
                        "rollno", roll,
                        "department", formatter.formatCellValue(row.getCell(2)),
                        "course", formatter.formatCellValue(row.getCell(3)),
                        "yearsession", formatter.formatCellValue(row.getCell(4)),
                        "subjectcode", formatter.formatCellValue(row.getCell(5))));
            }
        }
        assertEquals(300, students.size());

        List<ExamRoom> rooms = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            rooms.add(ExamRoom.builder()
                    .id((long) i)
                    .hallName("Hall " + i)
                    .rows(10)
                    .columns(3)
                    .benchType(ExamRoom.BenchType.DUAL_SEATER)
                    .build());
        }

        SeatingArrangementEngine engine = new SeatingArrangementEngine();
        SeatingPlan plan = engine.arrange(rooms, students, SeatingConfig.normal("department", "yearsession"));
        assertEquals(300, plan.allocations().size(), "All 300 students must be seated");
        assertEquals(0, plan.unplacedStudents().size());
    }

    @Test
    void testCombined300StudentsInTenHallsHardMode() {
        SeatingArrangementEngine engine = new SeatingArrangementEngine();
        List<ExamRoom> rooms = new ArrayList<>();
        for (int i = 1; i <= 10; i++) {
            rooms.add(ExamRoom.builder()
                    .id((long) i)
                    .hallName("Hall " + i)
                    .rows(10)
                    .columns(3)
                    .benchType(ExamRoom.BenchType.DUAL_SEATER)
                    .build());
        }

        List<Student> students = new ArrayList<>();
        long id = 1;
        String[] depts = {"Computer Science", "Electronics", "Mechanical"};
        for (String dept : depts) {
            for (int i = 1; i <= 50; i++) {
                students.add(buildStudent(id++, dept + " 2024-" + i,
                        dept.substring(0, 2).toUpperCase() + "-24-" + i,
                        "department", dept, "yearsession", "2024"));
                students.add(buildStudent(id++, dept + " 2025-" + i,
                        dept.substring(0, 2).toUpperCase() + "-25-" + i,
                        "department", dept, "yearsession", "2025"));
            }
        }
        assertEquals(300, students.size());

        SeatingConfig config = SeatingConfig.hard("department", "yearsession");
        SeatingPlan plan = engine.arrange(rooms, students, config);
        assertEquals(300, plan.allocations().size(), "All 300 students must be seated in Hard Mode across 10 halls");
        assertEquals(0, plan.unplacedStudents().size());

        // Verify full Moore-neighborhood separation on physical seat grid
        for (SeatingAllocation a1 : plan.allocations()) {
            for (SeatingAllocation a2 : plan.allocations()) {
                if (a1 == a2 || a1.getBench().getRoom() != a2.getBench().getRoom()) continue;
                int p1 = (a1.getBench().getColIndex() - 1) * 2 + a1.getSlotNumber();
                int p2 = (a2.getBench().getColIndex() - 1) * 2 + a2.getSlotNumber();
                int dr = Math.abs(a1.getBench().getRowIndex() - a2.getBench().getRowIndex());
                int dc = Math.abs(p1 - p2);
                if (dr <= 1 && dc <= 1) {
                    boolean sameClass = a1.getStudent().getAttributes().get("department")
                            .equals(a2.getStudent().getAttributes().get("department"))
                            && a1.getStudent().getAttributes().get("yearsession")
                            .equals(a2.getStudent().getAttributes().get("yearsession"));
                    assertFalse(sameClass, "Hard mode forbids classmates anywhere in 8-way Moore neighborhood");
                }
            }
        }
    }

    private void verifyHorizontalAttributeIsolation(SeatingPlan plan, int rows, int cols, String attr) {
        Student[][] grid = new Student[rows + 1][cols + 1];
        for (SeatingAllocation a : plan.allocations()) {
            grid[a.getBench().getRowIndex()][a.getBench().getColIndex()] = a.getStudent();
        }
        for (int r = 1; r <= rows; r++) {
            for (int c = 1; c < cols; c++) {
                Student left = grid[r][c];
                Student right = grid[r][c + 1];
                if (left != null && right != null) {
                    assertNotEquals(left.getAttributes().get(attr), right.getAttributes().get(attr),
                            "Horizontally adjacent desks at row " + r + " must not share " + attr);
                }
            }
        }
    }

    private void verifyMooreNeighborhoodAnyIsolation(SeatingPlan plan, int rows, int cols,
                                                     String attr1, String attr2) {
        Student[][] grid = new Student[rows + 1][cols + 1];
        for (SeatingAllocation a : plan.allocations()) {
            grid[a.getBench().getRowIndex()][a.getBench().getColIndex()] = a.getStudent();
        }
        for (int r = 1; r <= rows; r++) {
            for (int c = 1; c <= cols; c++) {
                Student s = grid[r][c];
                if (s == null) continue;
                for (int dr = -1; dr <= 1; dr++) {
                    for (int dc = -1; dc <= 1; dc++) {
                        if (dr == 0 && dc == 0) continue;
                        int nr = r + dr;
                        int nc = c + dc;
                        if (nr >= 1 && nr <= rows && nc >= 1 && nc <= cols && grid[nr][nc] != null) {
                            Student nb = grid[nr][nc];
                            assertNotEquals(s.getAttributes().get(attr1), nb.getAttributes().get(attr1));
                            assertNotEquals(s.getAttributes().get(attr2), nb.getAttributes().get(attr2));
                        }
                    }
                }
            }
        }
    }

    @Test
    void testAnalyzeFeasibilityEmptySeatsVsImpossible() {
        SeatingArrangementEngine engine = new SeatingArrangementEngine();
        SeatingConfig extremeCfg = SeatingConfig.extreme("branch");
        assertEquals(1, extremeCfg.horizontalGap());
        assertEquals(1, extremeCfg.verticalGap());
        assertEquals(1, extremeCfg.diagonalGap());

        // 8 CSE + 2 ECE = 10 students (dominant cohort = 8, other = 2)
        List<Student> skewed = new ArrayList<>();
        long id = 1;
        for (int i = 1; i <= 8; i++) {
            skewed.add(buildStudent(id++, "CSE-" + i, "C" + i, "branch", "CSE"));
        }
        for (int i = 1; i <= 2; i++) {
            skewed.add(buildStudent(id++, "ECE-" + i, "E" + i, "branch", "ECE"));
        }

        // 1) 4x4 SINGLE_SEATER (16 seats >= 10 students), EXTREME (1,1,1):
        //    Max CSE seats in 4x4 under EXTREME is ceil(4/2)*ceil(4/2) = 4 < 8 -> IMPOSSIBLE!
        ExamRoom tightSingle = ExamRoom.builder()
                .id(1L).hallName("Tight 4x4").rows(4).columns(4)
                .benchType(ExamRoom.BenchType.SINGLE_SEATER).build();
        var impossible = engine.analyzeFeasibility(List.of(tightSingle), skewed, extremeCfg);
        assertFalse(impossible.feasible());
        assertEquals("INSUFFICIENT_SEATS_FOR_SEPARATION", impossible.status());
        assertEquals(4, impossible.maxCohortSeatsInCurrentHalls());
        assertTrue(impossible.additionalSeatsRequired() > 0);
        assertTrue(impossible.emptyBufferSeatsRequired() > 0);

        // 2) 5x7 SINGLE_SEATER (35 seats), EXTREME (1,1,1):
        //    Max CSE seats = ceil(5/2)*ceil(7/2) = 3*4 = 12 >= 8 -> FEASIBLE_WITH_EMPTY_SEATS!
        ExamRoom spaciousSingle = ExamRoom.builder()
                .id(2L).hallName("Spacious 5x7").rows(5).columns(7)
                .benchType(ExamRoom.BenchType.SINGLE_SEATER).build();
        var withEmpty = engine.analyzeFeasibility(List.of(spaciousSingle), skewed, extremeCfg);
        assertTrue(withEmpty.feasible());
        assertTrue(withEmpty.requiresEmptySeats());
        assertEquals("FEASIBLE_WITH_EMPTY_SEATS", withEmpty.status());
        assertTrue(withEmpty.emptyBufferSeatsRequired() > 0);
        assertEquals(0, withEmpty.additionalSeatsRequired());

        // 3) Balanced 5 CSE + 5 ECE in 5x2 DUAL_SEATER (20 seats) under NORMAL -> OPTIMAL (0 mandatory empty seats)
        List<Student> balanced = new ArrayList<>();
        for (int i = 1; i <= 5; i++) {
            balanced.add(buildStudent(id++, "BC-" + i, "BC" + i, "branch", "CSE"));
            balanced.add(buildStudent(id++, "BE-" + i, "BE" + i, "branch", "ECE"));
        }
        ExamRoom dualHall = ExamRoom.builder()
                .id(3L).hallName("Dual 5x2").rows(5).columns(2)
                .benchType(ExamRoom.BenchType.DUAL_SEATER).build();
        var optimal = engine.analyzeFeasibility(List.of(dualHall), balanced, SeatingConfig.normal("branch"));
        assertTrue(optimal.feasible());
        assertFalse(optimal.requiresEmptySeats());
        assertEquals("OPTIMAL", optimal.status());
        assertEquals(0, optimal.emptyBufferSeatsRequired());
    }
}
