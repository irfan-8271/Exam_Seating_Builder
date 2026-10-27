package com.examseating.engine;

import com.examseating.engine.SeatingArrangementEngine.ConflictScope;
import com.examseating.engine.SeatingArrangementEngine.SeatingConfig;
import com.examseating.engine.SeatingArrangementEngine.SeatingPlan;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Student;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class SeatingArrangementEngineTest {

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

        // 16 students across 3 departments: 6 CSE, 5 ECE, 5 MECH
        List<Student> students = new ArrayList<>();
        int id = 1;
        for (int i = 0; i < 6; i++) {
            students.add(Student.builder().id((long) id++).name("CSE-" + i).rollNo("10" + i).department("CSE").yearSession(i % 2 == 0 ? "2024" : "2025").subjectCode("CS1").build());
        }
        for (int i = 0; i < 5; i++) {
            students.add(Student.builder().id((long) id++).name("ECE-" + i).rollNo("20" + i).department("ECE").yearSession(i % 2 == 0 ? "2024" : "2025").subjectCode("EC1").build());
        }
        for (int i = 0; i < 5; i++) {
            students.add(Student.builder().id((long) id++).name("ME-" + i).rollNo("30" + i).department("ME").yearSession(i % 2 == 0 ? "2024" : "2025").subjectCode("ME1").build());
        }

        SeatingConfig config = new SeatingConfig(
                1, 0, 0,
                ConflictScope.DEPARTMENT,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS);

        SeatingPlan plan = engine.arrange(List.of(room), students, config);
        assertEquals(16, plan.allocations().size(), "All 16 students should be seated");
        assertEquals(0, plan.unplacedStudents().size(), "Zero students should be unplaced");

        // Verify constraint: horizontally adjacent single-seater desks must have different departments
        verifyHorizontalConstraint(plan, 4, 4);
    }

    @Test
    void testSingleSeaterNormalModeWithDefaultConfig() {
        SeatingArrangementEngine engine = new SeatingArrangementEngine();
        ExamRoom room = ExamRoom.builder()
                .id(1L)
                .hallName("Single Hall 1")
                .rows(4)
                .columns(4)
                .benchType(ExamRoom.BenchType.SINGLE_SEATER)
                .build();

        // 16 students across 3 departments: 6 CSE, 5 ECE, 5 MECH
        // Notice: CSE students have different years (2024 and 2025).
        // Under pure DEPARTMENT_AND_YEAR, they would not conflict with each other.
        // But our engine ensures horizontally adjacent single-seater desks do NOT share department!
        List<Student> students = new ArrayList<>();
        int id = 1;
        for (int i = 0; i < 6; i++) {
            students.add(Student.builder().id((long) id++).name("CSE-" + i).rollNo("10" + i).department("CSE").yearSession(i % 2 == 0 ? "2024" : "2025").subjectCode("CS1").build());
        }
        for (int i = 0; i < 5; i++) {
            students.add(Student.builder().id((long) id++).name("ECE-" + i).rollNo("20" + i).department("ECE").yearSession(i % 2 == 0 ? "2024" : "2025").subjectCode("EC1").build());
        }
        for (int i = 0; i < 5; i++) {
            students.add(Student.builder().id((long) id++).name("ME-" + i).rollNo("30" + i).department("ME").yearSession(i % 2 == 0 ? "2024" : "2025").subjectCode("ME1").build());
        }

        // SeatingConfig.of(1, 0, 0) uses default DEPARTMENT_AND_YEAR
        SeatingConfig config = SeatingConfig.of(1, 0, 0);

        SeatingPlan plan = engine.arrange(List.of(room), students, config);
        assertEquals(16, plan.allocations().size(), "All 16 students should be seated");
        assertEquals(0, plan.unplacedStudents().size());

        // Must still satisfy horizontal department isolation!
        verifyHorizontalConstraint(plan, 4, 4);
    }

    @Test
    void testSingleSeaterExtremeMode() {
        SeatingArrangementEngine engine = new SeatingArrangementEngine();
        ExamRoom room = ExamRoom.builder()
                .id(1L)
                .hallName("Single Hall Extreme")
                .rows(4)
                .columns(4)
                .benchType(ExamRoom.BenchType.SINGLE_SEATER)
                .build();

        // In a 4x4 room with Extreme Mode (dH=1, dV=1, dD=1), a group can occupy at most ceil(4/2)*ceil(4/2) = 4 seats.
        // We have 4 departments (CSE, ECE, ME, CE) with 4 students each, and 4 years (2022, 2023, 2024, 2025) with 4 students each.
        List<Student> students = new ArrayList<>();
        int id = 1;
        String[] depts = {"CSE", "ECE", "ME", "CE"};
        String[] years = {"2022", "2023", "2024", "2025"};
        for (int d = 0; d < 4; d++) {
            for (int y = 0; y < 4; y++) {
                students.add(Student.builder()
                        .id((long) id++)
                        .name(depts[d] + "-" + years[y])
                        .rollNo("R" + id)
                        .department(depts[d])
                        .yearSession(years[(d + y) % 4]) // perfectly balanced 4 of each year
                        .subjectCode("SUB-" + depts[d])
                        .build());
            }
        }

        SeatingConfig config = new SeatingConfig(
                1, 1, 1,
                ConflictScope.DEPARTMENT_OR_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS);

        SeatingPlan plan = engine.arrange(List.of(room), students, config);
        assertEquals(16, plan.allocations().size(), "All 16 students must be seated in Extreme Mode");
        assertEquals(0, plan.unplacedStudents().size());

        // Verify Extreme Mode: no horizontally, vertically, or diagonally adjacent desk shares department or year
        verifyExtremeConstraint(plan, 4, 4);
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

        // 8 students in 9 seats (1 spare seat): 4 CSE, 4 ECE
        List<Student> students = new ArrayList<>();
        int id = 1;
        for (int i = 0; i < 4; i++) {
            students.add(Student.builder().id((long) id++).name("CSE-" + i).rollNo("10" + i).department("CSE").yearSession("2024").subjectCode("CS1").build());
            students.add(Student.builder().id((long) id++).name("ECE-" + i).rollNo("20" + i).department("ECE").yearSession("2024").subjectCode("EC1").build());
        }

        SeatingConfig config = new SeatingConfig(
                1, 0, 0,
                ConflictScope.DEPARTMENT,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS);

        SeatingPlan plan = engine.arrange(List.of(room), students, config);
        assertEquals(8, plan.allocations().size(), "8 students should be seated");
        assertEquals(0, plan.unplacedStudents().size(), "0 unplaced students");

        verifyHorizontalConstraint(plan, 3, 3);
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

        // 4 rows x 2 columns = 8 benches (16 seats).
        // 4 CSE (2024), 4 ECE (2025), 4 ME (2024), 4 CE (2025) = 16 students
        List<Student> students = new ArrayList<>();
        int id = 1;
        for (int i = 0; i < 4; i++) {
            students.add(Student.builder().id((long) id++).name("CSE-" + i).rollNo("10" + i).department("CSE").yearSession("2024").subjectCode("CS1").build());
            students.add(Student.builder().id((long) id++).name("ECE-" + i).rollNo("20" + i).department("ECE").yearSession("2025").subjectCode("EC1").build());
            students.add(Student.builder().id((long) id++).name("ME-" + i).rollNo("30" + i).department("ME").yearSession("2024").subjectCode("ME1").build());
            students.add(Student.builder().id((long) id++).name("CE-" + i).rollNo("40" + i).department("CE").yearSession("2025").subjectCode("CE1").build());
        }

        SeatingConfig config = new SeatingConfig(
                1, 0, 0,
                ConflictScope.DEPARTMENT,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS);

        SeatingPlan plan = engine.arrange(List.of(room), students, config);
        assertEquals(16, plan.allocations().size());
        assertEquals(0, plan.unplacedStudents().size());

        // Verify bench-mates differ in department AND year
        for (SeatingAllocation a1 : plan.allocations()) {
            for (SeatingAllocation a2 : plan.allocations()) {
                if (a1 != a2 && a1.getBench() == a2.getBench()) {
                    assertNotEquals(a1.getStudent().getDepartment(), a2.getStudent().getDepartment(), "Bench-mates must not share department");
                    assertNotEquals(a1.getStudent().getYearSession(), a2.getStudent().getYearSession(), "Bench-mates must not share year");
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

        // 300 students: 100 CSE, 100 ECE, 100 ME (half 2024, half 2025)
        List<Student> students = new ArrayList<>();
        int id = 1;
        String[] depts = {"Computer Science", "Electronics", "Mechanical"};
        for (String dept : depts) {
            for (int i = 0; i < 100; i++) {
                students.add(Student.builder()
                        .id((long) id++)
                        .rollNo(dept.substring(0, 2) + id)
                        .name("Student " + id)
                        .department(dept)
                        .yearSession(i < 50 ? "2024" : "2025")
                        .subjectCode("SUB1")
                        .build());
            }
        }

        SeatingConfig config = new SeatingConfig(
                1, 0, 0,
                ConflictScope.DEPARTMENT_AND_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS);

        SeatingPlan plan = engine.arrange(rooms, students, config);
        assertEquals(300, plan.allocations().size());
        assertEquals(0, plan.unplacedStudents().size());
    }

    @Test
    void testActualExcel300StudentsWithTenHalls() throws Exception {
        java.io.File file = new java.io.File("sample_excel_files/students_combined_300.xlsx");
        assertTrue(file.exists(), "students_combined_300.xlsx must exist");

        List<Student> students = new ArrayList<>();
        try (org.apache.poi.ss.usermodel.Workbook wb = org.apache.poi.ss.usermodel.WorkbookFactory.create(file)) {
            org.apache.poi.ss.usermodel.Sheet sheet = wb.getSheetAt(0);
            org.apache.poi.ss.usermodel.DataFormatter formatter = new org.apache.poi.ss.usermodel.DataFormatter();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                org.apache.poi.ss.usermodel.Row row = sheet.getRow(i);
                if (row == null) continue;
                students.add(Student.builder()
                        .id((long) i)
                        .rollNo(formatter.formatCellValue(row.getCell(0)))
                        .name(formatter.formatCellValue(row.getCell(1)))
                        .department(formatter.formatCellValue(row.getCell(2)))
                        .course(formatter.formatCellValue(row.getCell(3)))
                        .yearSession(formatter.formatCellValue(row.getCell(4)))
                        .subjectCode(formatter.formatCellValue(row.getCell(5)))
                        .build());
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
        SeatingConfig config = new SeatingConfig(
                1, 0, 0,
                ConflictScope.DEPARTMENT_AND_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS);

        SeatingPlan plan = engine.arrange(rooms, students, config);
        assertEquals(300, plan.allocations().size(), "All 300 students must be seated");
        assertEquals(0, plan.unplacedStudents().size(), "Zero unplaced students");
    }

    private void verifyHorizontalConstraint(SeatingPlan plan, int rows, int cols) {
        Student[][] grid = new Student[rows + 1][cols + 1];
        for (SeatingAllocation a : plan.allocations()) {
            grid[a.getBench().getRowIndex()][a.getBench().getColIndex()] = a.getStudent();
        }
        for (int r = 1; r <= rows; r++) {
            for (int c = 1; c < cols; c++) {
                Student left = grid[r][c];
                Student right = grid[r][c + 1];
                if (left != null && right != null) {
                    assertNotEquals(left.getDepartment(), right.getDepartment(),
                            "Horizontally adjacent single-seater desks at row " + r + " (col " + c + " and " + (c + 1) + ") must not share department!");
                }
            }
        }
    }

    private void verifyExtremeConstraint(SeatingPlan plan, int rows, int cols) {
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
                        if (nr >= 1 && nr <= rows && nc >= 1 && nc <= cols) {
                            Student neighbor = grid[nr][nc];
                            if (neighbor != null) {
                                assertNotEquals(s.getDepartment(), neighbor.getDepartment(),
                                        "Extreme mode violation: desk (" + r + "," + c + ") and neighbor (" + nr + "," + nc + ") share department: " + s.getDepartment());
                                assertNotEquals(s.getYearSession(), neighbor.getYearSession(),
                                        "Extreme mode violation: desk (" + r + "," + c + ") and neighbor (" + nr + "," + nc + ") share year: " + s.getYearSession());
                            }
                        }
                    }
                }
            }
        }
    }

    @Test
    void testCombined300StudentsInTenHallsExtremeMode() {
        SeatingArrangementEngine engine = new SeatingArrangementEngine();

        // 10 halls: 10 rows x 3 columns, DUAL_SEATER (60 seats each, 600 total spots)
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

        // 300 students: exactly matching students_combined_300.xlsx
        // 100 Mechanical (50 in 2024, 50 in 2025)
        // 100 Computer Science (50 in 2024, 50 in 2025)
        // 100 Electronics (50 in 2024, 50 in 2025)
        List<Student> students = new ArrayList<>();
        long id = 1;
        String[] depts = {"Computer Science", "Electronics", "Mechanical"};
        for (String dept : depts) {
            for (int i = 1; i <= 50; i++) {
                students.add(Student.builder()
                        .id(id++)
                        .name(dept + " Student 2024-" + i)
                        .rollNo(dept.substring(0, 2).toUpperCase() + "-24-" + String.format("%03d", i))
                        .department(dept)
                        .yearSession("2024")
                        .subjectCode("ENGG101")
                        .build());
            }
            for (int i = 1; i <= 50; i++) {
                students.add(Student.builder()
                        .id(id++)
                        .name(dept + " Student 2025-" + i)
                        .rollNo(dept.substring(0, 2).toUpperCase() + "-25-" + String.format("%03d", i))
                        .department(dept)
                        .yearSession("2025")
                        .subjectCode("ENGG101")
                        .build());
            }
        }

        assertEquals(300, students.size());

        // Extreme Mode: dH=1, dV=1, dD=1, ConflictScope.DEPARTMENT_AND_YEAR (Classmates)
        SeatingConfig config = new SeatingConfig(
                1, 1, 1,
                ConflictScope.DEPARTMENT_AND_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS);

        SeatingPlan plan = engine.arrange(rooms, students, config);
        assertEquals(300, plan.allocations().size(), "All 300 students must be seated in Extreme Mode across 10 halls");
        assertEquals(0, plan.unplacedStudents().size(), "Zero students unplaced");

        // Verify dual-seater bench constraints: occupants must differ in department AND year
        for (SeatingAllocation a1 : plan.allocations()) {
            for (SeatingAllocation a2 : plan.allocations()) {
                if (a1 != a2 && a1.getBench() == a2.getBench()) {
                    assertNotEquals(a1.getStudent().getDepartment(), a2.getStudent().getDepartment(),
                            "Bench-mates on the same bench must belong to different departments");
                    assertNotEquals(a1.getStudent().getYearSession(), a2.getStudent().getYearSession(),
                            "Bench-mates on the same bench must belong to different years");
                }
            }
        }

        // Verify Extreme Mode isolation: no two classmates (same dept & same year) within Moore neighborhood
        for (SeatingAllocation a1 : plan.allocations()) {
            for (SeatingAllocation a2 : plan.allocations()) {
                if (a1 == a2 || a1.getBench() == a2.getBench()) continue;
                if (a1.getBench().getRoom() == a2.getBench().getRoom()) {
                    int p1 = (a1.getBench().getColIndex() - 1) * 2 + a1.getSlotNumber();
                    int p2 = (a2.getBench().getColIndex() - 1) * 2 + a2.getSlotNumber();
                    int seatDc = Math.abs(p1 - p2);
                    int dr = Math.abs(a1.getBench().getRowIndex() - a2.getBench().getRowIndex());
                    boolean adjacentHorizontal = dr == 0 && seatDc <= 1;
                    boolean adjacentVertical = a1.getBench().getColIndex() == a2.getBench().getColIndex() && dr <= 1;
                    boolean adjacentDiagonal = dr == 1 && seatDc <= 1;

                    if (adjacentHorizontal || adjacentVertical || adjacentDiagonal) {
                        boolean sameClass = a1.getStudent().getDepartment().equals(a2.getStudent().getDepartment())
                                && a1.getStudent().getYearSession().equals(a2.getStudent().getYearSession());
                        assertFalse(sameClass, "Classmate conflict in Extreme Mode between adjacent seats: "
                                + a1.getStudent().getName() + " and " + a2.getStudent().getName());
                    }
                }
            }
        }
    }

    @Test
    void testTwoDepartmentsSameYearSeatingCleanColumns() {
        SeatingArrangementEngine engine = new SeatingArrangementEngine();

        // 1 hall: 5 rows x 3 columns of DUAL_SEATER benches = 15 benches = 30 seats
        ExamRoom room = ExamRoom.builder()
                .id(1L)
                .hallName("Auditorium A")
                .rows(5)
                .columns(3)
                .benchType(ExamRoom.BenchType.DUAL_SEATER)
                .build();

        // Exactly TWO departments of the SAME YEAR (e.g. 15 CSE-2024 and 15 ECE-2024 = 30 students)
        List<Student> students = new ArrayList<>();
        long id = 1;
        for (int i = 1; i <= 15; i++) {
            students.add(Student.builder()
                    .id(id++)
                    .name("CSE Student " + i)
                    .rollNo("CS-" + i)
                    .department("CSE")
                    .yearSession("2024")
                    .subjectCode("ENGG101")
                    .build());
            students.add(Student.builder()
                    .id(id++)
                    .name("ECE Student " + i)
                    .rollNo("EC-" + i)
                    .department("ECE")
                    .yearSession("2024")
                    .subjectCode("ENGG101")
                    .build());
        }

        assertEquals(30, students.size());

        // Normal mode, classmates separated, dual-bench allows DIFFERENT_DEPARTMENT_ONLY (same year),
        // and horizontal stream isolation enabled
        SeatingConfig config = new SeatingConfig(
                1, 0, 0,
                ConflictScope.DEPARTMENT_AND_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                SeatingArrangementEngine.BenchPairingRule.DIFFERENT_DEPARTMENT_ONLY,
                true);

        SeatingPlan plan = engine.arrange(List.of(room), students, config);
        assertEquals(30, plan.allocations().size(), "All 30 students must be seated");
        assertEquals(0, plan.unplacedStudents().size(), "Zero unplaced students");

        // 1. Verify bench-mate constraint: on every bench, students belong to different departments!
        for (SeatingAllocation a1 : plan.allocations()) {
            for (SeatingAllocation a2 : plan.allocations()) {
                if (a1 != a2 && a1.getBench() == a2.getBench()) {
                    assertNotEquals(a1.getStudent().getDepartment(), a2.getStudent().getDepartment(),
                            "Students on the same bench must belong to different departments");
                }
            }
        }

        // 2. Verify horizontal stream separation across the row:
        // No two students sitting horizontally side-by-side share the same department!
        // In each row r, across cols 1..3:
        // Col 1 Slot 1, Col 1 Slot 2, Col 2 Slot 1, Col 2 Slot 2, Col 3 Slot 1, Col 3 Slot 2
        for (int r = 1; r <= 5; r++) {
            Student prev = null;
            for (int c = 1; c <= 3; c++) {
                final int row = r;
                final int col = c;
                SeatingAllocation slot1 = plan.allocations().stream()
                        .filter(a -> a.getBench().getRowIndex() == row && a.getBench().getColIndex() == col && a.getSlotNumber() == 1)
                        .findFirst().orElse(null);
                SeatingAllocation slot2 = plan.allocations().stream()
                        .filter(a -> a.getBench().getRowIndex() == row && a.getBench().getColIndex() == col && a.getSlotNumber() == 2)
                        .findFirst().orElse(null);

                assertNotNull(slot1);
                assertNotNull(slot2);

                if (prev != null) {
                    assertNotEquals(prev.getDepartment(), slot1.getStudent().getDepartment(),
                            "Horizontal aisle adjacent seats at row " + r + " must have different departments!");
                }
                assertNotEquals(slot1.getStudent().getDepartment(), slot2.getStudent().getDepartment(),
                        "Desk occupants at (" + r + "," + c + ") must have different departments!");
                prev = slot2.getStudent();
            }
        }

        // 3. Verify neat column alignment:
        // In each column and slot, students sitting behind each other belong to the same department!
        for (int c = 1; c <= 3; c++) {
            for (int slot = 1; slot <= 2; slot++) {
                final int col = c;
                final int sNum = slot;
                String colDept = null;
                for (int r = 1; r <= 5; r++) {
                    final int row = r;
                    SeatingAllocation alloc = plan.allocations().stream()
                            .filter(a -> a.getBench().getRowIndex() == row && a.getBench().getColIndex() == col && a.getSlotNumber() == sNum)
                            .findFirst().orElse(null);
                    assertNotNull(alloc);
                    if (colDept == null) {
                        colDept = alloc.getStudent().getDepartment();
                    } else {
                        assertEquals(colDept, alloc.getStudent().getDepartment(),
                                "Students sitting behind each other in column " + col + " (slot " + sNum + ") should form a clean department column!");
                    }
                }
            }
        }
    }
}
