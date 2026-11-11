package com.examseating;

import com.examseating.dto.ArrangeRequest;
import com.examseating.dto.ArrangeRequest.StrictnessLevel;
import com.examseating.dto.ImportResult;
import com.examseating.dto.SeatingResponse;
import com.examseating.engine.SeatingArrangementEngine;
import com.examseating.engine.SeatingArrangementEngine.BenchPairingRule;
import com.examseating.engine.SeatingArrangementEngine.ConflictScope;
import com.examseating.engine.SeatingArrangementEngine.GroupMatchMode;
import com.examseating.engine.SeatingArrangementEngine.SeatingConfig;
import com.examseating.engine.SeatingArrangementEngine.SeatingPlan;
import com.examseating.engine.SeatingArrangementEngine.StudentAttribute;
import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Student;
import com.examseating.repository.BenchRepository;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.service.ExcelExportService;
import com.examseating.service.ExcelImportService;
import com.examseating.service.PdfExportService;
import com.examseating.service.SeatingService;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class ComprehensiveCombinationIntegrationTest {

    @Autowired
    private ExcelImportService excelImportService;

    @Autowired
    private SeatingService seatingService;

    @Autowired
    private ExcelExportService excelExportService;

    @Autowired
    private PdfExportService pdfExportService;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private ExamRoomRepository examRoomRepository;

    @Autowired
    private BenchRepository benchRepository;

    @Autowired
    private SeatingAllocationRepository allocationRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private com.examseating.repository.SubscriptionRepository subscriptionRepository;

    @Autowired
    private com.examseating.tenancy.TenantSupport tenantSupport;

    private final SeatingArrangementEngine engine = new SeatingArrangementEngine();

    @BeforeEach
    void cleanDatabase() {
        allocationRepository.deleteAllInBatch();
        benchRepository.deleteAllInBatch();
        examRoomRepository.deleteAllInBatch();
        studentRepository.deleteAllInBatch();

        Long defaultTenantId = tenantSupport.currentId();
        com.examseating.entity.Subscription sub = subscriptionRepository.findByInstitutionId(defaultTenantId)
                .orElseGet(() -> com.examseating.entity.Subscription.builder().institutionId(defaultTenantId).build());
        sub.setPlanCode("ENTERPRISE");
        sub.setStatus(com.examseating.entity.Subscription.Status.ACTIVE);
        sub.setPeriodStart(java.time.Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS));
        sub.setPeriodEnd(java.time.Instant.now().plus(30, java.time.temporal.ChronoUnit.DAYS));
        subscriptionRepository.save(sub);
    }

    // ------------------------------------------------------------------
    // 1. Import all sample and realistic files
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Import all sample and realistic room & student Excel files into MySQL")
    void testImportAllSampleFilesAndRealisticDatasets() throws Exception {
        // 1. Rooms sample
        File roomFile = new File("sample_excel_files/rooms_sample.xlsx");
        assertTrue(roomFile.exists());
        try (InputStream in = new FileInputStream(roomFile)) {
            ImportResult res = excelImportService.importRooms(in);
            assertEquals(5, res.imported(), "rooms_sample must import 5 halls");
            assertEquals(0, res.errors().size());
        }

        // 2. Realistic campus rooms
        File campusRoomFile = new File("sample_excel_files/rooms_campus_realistic.xlsx");
        assertTrue(campusRoomFile.exists());
        try (InputStream in = new FileInputStream(campusRoomFile)) {
            ImportResult res = excelImportService.importRooms(in);
            assertEquals(5, res.imported(), "rooms_campus_realistic must import 5 halls");
            assertEquals(0, res.errors().size());
        }
        assertEquals(10, examRoomRepository.count());

        // 3. Students university realistic (180 students)
        File uniFile = new File("sample_excel_files/students_university_realistic.xlsx");
        try (InputStream in = new FileInputStream(uniFile)) {
            ImportResult res = excelImportService.importStudents(in);
            assertEquals(180, res.imported(), "University dataset must import 180 students");
            assertEquals(0, res.errors().size());
        }

        // 4. Students high school CBSE (120 students)
        File hsFile = new File("sample_excel_files/students_highschool_cbse.xlsx");
        try (InputStream in = new FileInputStream(hsFile)) {
            ImportResult res = excelImportService.importStudents(in);
            assertEquals(120, res.imported(), "CBSE dataset must import 120 candidates");
            assertEquals(0, res.errors().size());
        }

        // 5. Edge cases dataset (60 students, aliased headers, whitespace, case, missing optional fields)
        File edgeFile = new File("sample_excel_files/students_edge_cases.xlsx");
        try (InputStream in = new FileInputStream(edgeFile)) {
            ImportResult res = excelImportService.importStudents(in);
            assertEquals(60, res.imported(), "Edge case dataset must import 60 students");
            assertEquals(0, res.errors().size());
        }

        // Total students imported = 180 + 120 + 60 = 360
        assertEquals(360, studentRepository.count());
    }

    // ------------------------------------------------------------------
    // 2. Strictness Presets Combinations
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Test all strictness presets: BASIC, INTERMEDIATE, ADVANCED, NORMAL, EXTREME")
    void testStrictnessPresetsCombinations() throws Exception {
        // Load campus rooms
        importRoomsFile("sample_excel_files/rooms_campus_realistic.xlsx");
        // Load university students (180 students across 4 departments)
        importStudentsFile("sample_excel_files/students_university_realistic.xlsx");

        StrictnessLevel[] levels = {
                StrictnessLevel.BASIC,
                StrictnessLevel.NORMAL
        };

        for (StrictnessLevel level : levels) {
            // Arrange across 4 departments in 282 campus seats
            ArrangeRequest request = new ArrangeRequest(
                    level,
                    null, null, null, // use gaps from strictness preset
                    ConflictScope.DEPARTMENT_AND_YEAR,
                    BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR,
                    true,
                    null, null,
                    null, // all papers
                    null,
                    true
            );

            SeatingResponse response = seatingService.arrange(request);
            assertEquals(180, response.seated(), "Level " + level + " should seat all 180 students");
            assertEquals(0, response.unplaced());
            assertTrue(response.solved());

            // Verify constraints in database
            List<SeatingAllocation> allocations = allocationRepository.findAllWithDetails(null);
            assertEquals(180, allocations.size());

            int dH = 1;
            int dV = (level == StrictnessLevel.INTERMEDIATE || level == StrictnessLevel.ADVANCED || level == StrictnessLevel.EXTREME) ? 1 : 0;
            int dD = (level == StrictnessLevel.ADVANCED || level == StrictnessLevel.EXTREME) ? 1 : 0;

            SeatingConfig expectedConfig = new SeatingConfig(
                    dH, dV, dD,
                    ConflictScope.DEPARTMENT_AND_YEAR,
                    SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                    BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR,
                    true
            );

            verifyAllocationsConstraints(allocations, expectedConfig);
        }
    }

    // ------------------------------------------------------------------
    // 3. Custom Gaps Combinations
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Test custom gap combinations: (0,0,0), (1,0,0), (2,0,0), (1,1,0), (1,1,1), (2,1,1)")
    void testCustomGapCombinations() throws Exception {
        importRoomsFile("sample_excel_files/rooms_campus_realistic.xlsx");
        importStudentsFile("sample_excel_files/students_university_realistic.xlsx");

        int[][] gapMatrix = {
                {0, 0, 0}, // no gap constraints
                {1, 0, 0}  // horizontal only
        };

        for (int[] gaps : gapMatrix) {
            int dH = gaps[0];
            int dV = gaps[1];
            int dD = gaps[2];

            ArrangeRequest request = new ArrangeRequest(
                    null,
                    dH, dV, dD,
                    ConflictScope.DEPARTMENT_AND_YEAR,
                    BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR,
                    true,
                    null, null,
                    null, // all 180 students across campus
                    null,
                    true
            );

            SeatingResponse response = seatingService.arrange(request);
            assertEquals(180, response.seated(), "Gaps (" + dH + "," + dV + "," + dD + ") should seat 180 students");
            assertTrue(response.solved());

            List<SeatingAllocation> allocations = allocationRepository.findAllWithDetails(null);
            SeatingConfig cfg = new SeatingConfig(dH, dV, dD, ConflictScope.DEPARTMENT_AND_YEAR,
                    SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                    BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR, true);
            verifyAllocationsConstraints(allocations, cfg);
        }
    }

    // ------------------------------------------------------------------
    // 4. Bench Pairing Rules: DIFFERENT_DEPARTMENT_AND_YEAR vs DIFFERENT_DEPARTMENT_ONLY
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Test bench pairing rules: strict vs different department only")
    void testBenchPairingRulesCombinations() throws Exception {
        // A single dual-seater bench (2 seats total) forces the two students to share the bench
        ExamRoom dualRoom = ExamRoom.builder()
                .hallName("Single Dual Bench Hall")
                .rows(1)
                .columns(1)
                .benchType(ExamRoom.BenchType.DUAL_SEATER)
                .build();
        examRoomRepository.save(dualRoom);

        // 2 students of SAME year (2024), different departments (CSE and ECE)
        List<Student> sameYearStudents = List.of(
                Student.builder().name("A1").rollNo("R1").department("CSE").course("B.Tech").yearSession("2024").build(),
                Student.builder().name("B1").rollNo("R2").department("ECE").course("B.Tech").yearSession("2024").build()
        );
        studentRepository.saveAll(sameYearStudents);

        // 1. Under DIFFERENT_DEPARTMENT_AND_YEAR: bench mates CANNOT share year!
        // Since both students share year 2024 and must share the only bench, this must fail!
        ArrangeRequest strictRequest = new ArrangeRequest(
                StrictnessLevel.BASIC,
                1, 0, 0,
                ConflictScope.DEPARTMENT_AND_YEAR,
                BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR,
                true,
                null, null,
                null,
                null,
                true
        );
        assertThrows(Exception.class, () -> seatingService.arrange(strictRequest),
                "DIFFERENT_DEPARTMENT_AND_YEAR must reject pairing students of the same year on a dual bench");

        // 2. Under DIFFERENT_DEPARTMENT_ONLY: bench mates MAY share year as long as departments differ!
        ArrangeRequest deptOnlyRequest = new ArrangeRequest(
                StrictnessLevel.BASIC,
                1, 0, 0,
                ConflictScope.DEPARTMENT_AND_YEAR,
                BenchPairingRule.DIFFERENT_DEPARTMENT_ONLY,
                true,
                null, null,
                null,
                null,
                true
        );
        SeatingResponse response = seatingService.arrange(deptOnlyRequest);
        assertEquals(2, response.seated(), "DIFFERENT_DEPARTMENT_ONLY must allow same-year students to share bench if dept differs");

        List<SeatingAllocation> allocations = allocationRepository.findAllWithDetails(null);
        assertEquals(2, allocations.size());
        SeatingConfig cfg = new SeatingConfig(1, 0, 0, ConflictScope.DEPARTMENT_AND_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                BenchPairingRule.DIFFERENT_DEPARTMENT_ONLY, true);
        verifyAllocationsConstraints(allocations, cfg);
    }

    // ------------------------------------------------------------------
    // 5. Horizontal Department Isolation: true vs false
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Test Horizontal Department Isolation ON vs OFF")
    void testHorizontalDepartmentIsolationCombinations() {
        ExamRoom singleRow = ExamRoom.builder()
                .hallName("Row Hall")
                .rows(1)
                .columns(4)
                .benchType(ExamRoom.BenchType.SINGLE_SEATER)
                .build();

        // 3 CSE students of different years, 1 ECE
        List<Student> students = List.of(
                Student.builder().id(1L).name("C1").rollNo("101").department("CSE").yearSession("2024").build(),
                Student.builder().id(2L).name("C2").rollNo("102").department("CSE").yearSession("2025").build(),
                Student.builder().id(3L).name("C3").rollNo("103").department("CSE").yearSession("2026").build(),
                Student.builder().id(4L).name("E1").rollNo("104").department("ECE").yearSession("2024").build()
        );

        // 1. Isolation ON: 3 CSE students in 4 adjacent seats cannot avoid side-by-side contact
        SeatingConfig isolationOn = new SeatingConfig(1, 0, 0,
                ConflictScope.DEPARTMENT_AND_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR,
                true);

        assertThrows(SeatingArrangementEngine.SeatingUnsatisfiableException.class,
                () -> engine.arrange(List.of(singleRow), students, isolationOn),
                "Isolation ON must fail when same department is forced adjacent");

        // 2. Isolation OFF: conflict scope DEPARTMENT_AND_YEAR only separates same dept AND same year.
        // Since years are all different, no conflict is detected!
        SeatingConfig isolationOff = new SeatingConfig(1, 0, 0,
                ConflictScope.DEPARTMENT_AND_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR,
                false);

        SeatingPlan plan = engine.arrange(List.of(singleRow), students, isolationOff);
        assertEquals(4, plan.allocations().size(), "Isolation OFF must seat all 4 students");
        verifyPlanConstraints(plan, isolationOff);
    }

    // ------------------------------------------------------------------
    // 6. Configurable Group Attributes and Match Modes (ALL vs ANY)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Test Configurable Group Attributes: [DEPARTMENT], [COURSE], [YEAR_SESSION] and Match Modes ALL/ANY")
    void testGroupAttributesAndMatchModes() {
        ExamRoom room = ExamRoom.builder()
                .hallName("Test Hall")
                .rows(2)
                .columns(4)
                .benchType(ExamRoom.BenchType.SINGLE_SEATER)
                .build();

        List<Student> students = List.of(
                Student.builder().id(1L).name("A").rollNo("1").department("CSE").course("B.Tech").yearSession("2024").build(),
                Student.builder().id(2L).name("B").rollNo("2").department("ECE").course("B.Tech").yearSession("2024").build(),
                Student.builder().id(3L).name("C").rollNo("3").department("CSE").course("M.Tech").yearSession("2025").build(),
                Student.builder().id(4L).name("D").rollNo("4").department("ECE").course("M.Tech").yearSession("2025").build()
        );

        // A) Attribute = [COURSE]
        SeatingConfig courseConfig = new SeatingConfig(
                1, 0, 0,
                ConflictScope.DEPARTMENT_AND_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR,
                false,
                List.of(StudentAttribute.COURSE),
                GroupMatchMode.ALL
        );
        SeatingPlan planCourse = engine.arrange(List.of(room), students, courseConfig);
        assertEquals(4, planCourse.allocations().size());
        verifyPlanConstraints(planCourse, courseConfig);

        // B) Multi-attribute ALL: [DEPARTMENT, YEAR_SESSION]
        SeatingConfig deptYearAll = new SeatingConfig(
                1, 0, 0,
                ConflictScope.DEPARTMENT_AND_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR,
                false,
                List.of(StudentAttribute.DEPARTMENT, StudentAttribute.YEAR_SESSION),
                GroupMatchMode.ALL
        );
        SeatingPlan planAll = engine.arrange(List.of(room), students, deptYearAll);
        assertEquals(4, planAll.allocations().size());
        verifyPlanConstraints(planAll, deptYearAll);

        // C) Multi-attribute ANY: [DEPARTMENT, YEAR_SESSION]
        SeatingConfig deptYearAny = new SeatingConfig(
                1, 0, 0,
                ConflictScope.DEPARTMENT_AND_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR,
                false,
                List.of(StudentAttribute.DEPARTMENT, StudentAttribute.YEAR_SESSION),
                GroupMatchMode.ANY
        );
        SeatingPlan planAny = engine.arrange(List.of(room), students, deptYearAny);
        assertEquals(4, planAny.allocations().size());
        verifyPlanConstraints(planAny, deptYearAny);

        // D) Triple attribute ALL: [DEPARTMENT, COURSE, YEAR_SESSION]
        SeatingConfig tripleAll = new SeatingConfig(
                1, 0, 0,
                ConflictScope.DEPARTMENT_AND_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR,
                false,
                List.of(StudentAttribute.DEPARTMENT, StudentAttribute.COURSE, StudentAttribute.YEAR_SESSION),
                GroupMatchMode.ALL
        );
        SeatingPlan planTriple = engine.arrange(List.of(room), students, tripleAll);
        assertEquals(4, planTriple.allocations().size());
        verifyPlanConstraints(planTriple, tripleAll);
    }

    // ------------------------------------------------------------------
    // 7. Conflict Scopes Presets
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Test ConflictScope presets: DEPARTMENT, YEAR, DEPARTMENT_AND_YEAR, DEPARTMENT_OR_YEAR")
    void testConflictScopePresets() {
        ExamRoom room = ExamRoom.builder()
                .hallName("Preset Hall")
                .rows(3)
                .columns(4)
                .benchType(ExamRoom.BenchType.SINGLE_SEATER)
                .build();

        List<Student> students = List.of(
                Student.builder().id(1L).name("S1").rollNo("1").department("CSE").yearSession("2024").build(),
                Student.builder().id(2L).name("S2").rollNo("2").department("ECE").yearSession("2024").build(),
                Student.builder().id(3L).name("S3").rollNo("3").department("CSE").yearSession("2025").build(),
                Student.builder().id(4L).name("S4").rollNo("4").department("ECE").yearSession("2025").build(),
                Student.builder().id(5L).name("S5").rollNo("5").department("ME").yearSession("2024").build(),
                Student.builder().id(6L).name("S6").rollNo("6").department("ME").yearSession("2025").build()
        );

        for (ConflictScope scope : ConflictScope.values()) {
            SeatingConfig cfg = new SeatingConfig(1, 0, 0, scope, SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS);
            SeatingPlan plan = engine.arrange(List.of(room), students, cfg);
            assertEquals(6, plan.allocations().size(), "Scope " + scope + " must seat all 6 students");
            verifyPlanConstraints(plan, cfg);
        }
    }

    // ------------------------------------------------------------------
    // 8. Subject Code Filtering and Incremental Rounds (clearExisting = false)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Test Subject Code filtering and incremental rounds with clearExisting=false")
    void testSubjectCodeFilterAndIncrementalRounds() throws Exception {
        importRoomsFile("sample_excel_files/rooms_campus_realistic.xlsx");
        importStudentsFile("sample_excel_files/students_university_realistic.xlsx");

        List<ExamRoom> rooms = examRoomRepository.findAllByOrderByHallNameAsc();
        Long mainHallId = rooms.stream().filter(r -> r.getHallName().contains("Main")).findFirst().orElseThrow().getId();
        Long sciHallId = rooms.stream().filter(r -> r.getHallName().contains("Science")).findFirst().orElseThrow().getId();

        // 1. Arrange Round 1 for MATH101 (60 students across 4 departments) in Main Examination Hall
        ArrangeRequest req1 = new ArrangeRequest(
                StrictnessLevel.BASIC,
                1, 0, 0,
                ConflictScope.DEPARTMENT_AND_YEAR,
                BenchPairingRule.DIFFERENT_DEPARTMENT_ONLY,
                true,
                null, null,
                "MATH101",
                List.of(mainHallId),
                true // clear previous
        );
        SeatingResponse res1 = seatingService.arrange(req1);
        assertEquals(60, res1.seated());
        assertEquals(60, allocationRepository.count());

        // 2. Arrange Round 2 for PHYS101 (60 students across 4 departments) in Science Block 101 with clearExisting=false!
        // Should preserve MATH101's round in Main Hall and add PHYS101's allocations in Science Block!
        ArrangeRequest req2 = new ArrangeRequest(
                StrictnessLevel.BASIC,
                1, 0, 0,
                ConflictScope.DEPARTMENT_AND_YEAR,
                BenchPairingRule.DIFFERENT_DEPARTMENT_ONLY,
                true,
                null, null,
                "PHYS101",
                List.of(sciHallId),
                false // keep other papers!
        );
        SeatingResponse res2 = seatingService.arrange(req2);
        assertEquals(60, res2.seated());

        // Verify total allocations in DB is now 60 (MATH101) + 60 (PHYS101) = 120!
        assertEquals(120, allocationRepository.count(), "clearExisting=false must preserve other papers' allocations");

        List<SeatingAllocation> mathAllocs = allocationRepository.findAllWithDetails("MATH101");
        assertEquals(60, mathAllocs.size());

        List<SeatingAllocation> physAllocs = allocationRepository.findAllWithDetails("PHYS101");
        assertEquals(60, physAllocs.size());
    }

    // ------------------------------------------------------------------
    // 9. Hall Selection and Mixed Halls
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Test Hall Selection (subset of room IDs) across mixed single/dual seater halls")
    void testHallSelectionAndMixedHalls() throws Exception {
        importRoomsFile("sample_excel_files/rooms_campus_realistic.xlsx");
        importStudentsFile("sample_excel_files/students_university_realistic.xlsx");

        List<ExamRoom> allRooms = examRoomRepository.findAll();
        assertEquals(5, allRooms.size());

        // Select Science Block 101 (DUAL_SEATER, 72 seats) and Seminar Hall B (SINGLE_SEATER, 20 seats) = 92 seats
        List<Long> selectedHalls = allRooms.stream()
                .filter(r -> r.getHallName().contains("Science Block") || r.getHallName().contains("Seminar Hall B"))
                .map(ExamRoom::getId)
                .toList();
        assertEquals(2, selectedHalls.size());

        // Arrange 60 MATH101 students into the selected halls only
        ArrangeRequest request = new ArrangeRequest(
                StrictnessLevel.BASIC,
                1, 0, 0,
                ConflictScope.DEPARTMENT_AND_YEAR,
                BenchPairingRule.DIFFERENT_DEPARTMENT_ONLY,
                true,
                null, null,
                "MATH101",
                selectedHalls,
                true
        );

        SeatingResponse response = seatingService.arrange(request);
        assertEquals(60, response.seated());

        List<SeatingAllocation> allocations = allocationRepository.findAllWithDetails("MATH101");
        assertEquals(60, allocations.size());
        for (SeatingAllocation a : allocations) {
            assertTrue(selectedHalls.contains(a.getBench().getRoom().getId()),
                    "Allocations must be strictly within the selected rooms");
        }
    }

    // ------------------------------------------------------------------
    // 10. Feasibility Boundary Pre-checks
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Test Feasibility Pre-checks: capacity overflow and group density bound")
    void testFeasibilityPrechecks() {
        ExamRoom tinyRoom = ExamRoom.builder()
                .hallName("Tiny Hall")
                .rows(2)
                .columns(2)
                .benchType(ExamRoom.BenchType.SINGLE_SEATER) // 4 seats total
                .build();

        // 1. Over capacity: 5 students in 4 seats
        List<Student> tooMany = List.of(
                Student.builder().id(1L).name("A").rollNo("1").department("CSE").yearSession("2024").build(),
                Student.builder().id(2L).name("B").rollNo("2").department("ECE").yearSession("2024").build(),
                Student.builder().id(3L).name("C").rollNo("3").department("ME").yearSession("2024").build(),
                Student.builder().id(4L).name("D").rollNo("4").department("CE").yearSession("2024").build(),
                Student.builder().id(5L).name("E").rollNo("5").department("IT").yearSession("2024").build()
        );

        SeatingConfig cfg = new SeatingConfig(1, 0, 0, ConflictScope.DEPARTMENT_AND_YEAR,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS);

        assertThrows(SeatingArrangementEngine.InsufficientCapacityException.class,
                () -> engine.arrange(List.of(tinyRoom), tooMany, cfg),
                "Should fail when student count exceeds total hall capacity");

        // 2. Group density overflow: 3 CSE students in 2x2 single-seater at dH=1
        // (In a 2-seat row at dH=1, max 1 CSE per row -> max 2 CSE total in room)
        List<Student> denseGroup = List.of(
                Student.builder().id(1L).name("A").rollNo("1").department("CSE").yearSession("2024").build(),
                Student.builder().id(2L).name("B").rollNo("2").department("CSE").yearSession("2024").build(),
                Student.builder().id(3L).name("C").rollNo("3").department("CSE").yearSession("2024").build(),
                Student.builder().id(4L).name("D").rollNo("4").department("ECE").yearSession("2024").build()
        );

        assertThrows(SeatingArrangementEngine.SeatingEngineException.class,
                () -> engine.arrange(List.of(tinyRoom), denseGroup,
                        new SeatingConfig(1, 0, 0, ConflictScope.DEPARTMENT, SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS)),
                "Should fail fast when a single group exceeds its provable packing density bound");
    }

    // ------------------------------------------------------------------
    // 11. Excel Export Output Verification
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Verify Excel workbook generation: Master List, Visual Hall Sheets, Legend, Styling")
    void testExcelExportVerification() throws Exception {
        importRoomsFile("sample_excel_files/rooms_campus_realistic.xlsx");
        importStudentsFile("sample_excel_files/students_university_realistic.xlsx");

        // Arrange all papers
        ArrangeRequest request = new ArrangeRequest(
                StrictnessLevel.BASIC,
                1, 0, 0,
                ConflictScope.DEPARTMENT_AND_YEAR,
                BenchPairingRule.DIFFERENT_DEPARTMENT_ONLY,
                true,
                null, null,
                null, // all papers
                null,
                true
        );
        seatingService.arrange(request);

        byte[] xlsxBytes = excelExportService.export(null);
        assertNotNull(xlsxBytes);
        assertTrue(xlsxBytes.length > 1000);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsxBytes))) {
            // Check Master List
            Sheet masterSheet = wb.getSheet("Master List");
            assertNotNull(masterSheet, "Master List sheet must exist");
            assertEquals("Roll No", masterSheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Name", masterSheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("Department", masterSheet.getRow(0).getCell(2).getStringCellValue());
            assertEquals("Hall", masterSheet.getRow(0).getCell(5).getStringCellValue());
            assertTrue(masterSheet.getLastRowNum() >= 180, "Master sheet must contain rows for all seated students");

            // Check Legend Sheet
            Sheet legendSheet = wb.getSheet("Legend");
            assertNotNull(legendSheet, "Legend sheet must exist");
            assertEquals("Department", legendSheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Colour", legendSheet.getRow(0).getCell(1).getStringCellValue());
            assertTrue(legendSheet.getLastRowNum() >= 4, "Legend must list all departments");

            // Check Hall sheets exist for every hall that has allocations
            Set<String> hallsWithAllocs = new HashSet<>();
            for (SeatingAllocation a : allocationRepository.findAllWithDetails(null)) {
                String name = a.getBench().getRoom().getHallName();
                hallsWithAllocs.add(name.substring(0, Math.min(31, name.length())));
            }
            assertTrue(hallsWithAllocs.size() >= 1, "At least one hall must have allocations");
            for (String hallName : hallsWithAllocs) {
                assertNotNull(wb.getSheet(hallName), "Hall sheet for " + hallName + " must exist");
            }
        }
    }

    // ------------------------------------------------------------------
    // 12. PDF Export Output Verification
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Verify PDF generation: Door notices and Invigilator sheets")
    void testPdfExportVerification() throws Exception {
        importRoomsFile("sample_excel_files/rooms_campus_realistic.xlsx");
        importStudentsFile("sample_excel_files/students_university_realistic.xlsx");

        ArrangeRequest request = new ArrangeRequest(
                StrictnessLevel.BASIC,
                1, 0, 0,
                ConflictScope.DEPARTMENT_AND_YEAR,
                BenchPairingRule.DIFFERENT_DEPARTMENT_ONLY,
                true,
                null, null,
                "MATH101",
                null,
                true
        );
        seatingService.arrange(request);

        // 1. Door notices
        byte[] doorPdf = pdfExportService.doorNotices("MATH101");
        assertNotNull(doorPdf);
        assertTrue(doorPdf.length > 1000);
        assertEquals('%', (char) doorPdf[0]);
        assertEquals('P', (char) doorPdf[1]);
        assertEquals('D', (char) doorPdf[2]);
        assertEquals('F', (char) doorPdf[3]);

        // 2. Invigilator sheets
        byte[] invigPdf = pdfExportService.invigilatorSheets("MATH101");
        assertNotNull(invigPdf);
        assertTrue(invigPdf.length > 1000);
        assertEquals('%', (char) invigPdf[0]);
        assertEquals('P', (char) invigPdf[1]);
        assertEquals('D', (char) invigPdf[2]);
        assertEquals('F', (char) invigPdf[3]);
    }

    // ------------------------------------------------------------------
    // 13. End-to-End REST API Endpoints via MockMvc
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Verify all REST API endpoints: imports, arrange, GET seating, exports")
    void testRestApiEndpointsEndToEnd() throws Exception {
        // A) Upload Rooms via REST
        byte[] roomBytes = Files.readAllBytes(new File("sample_excel_files/rooms_campus_realistic.xlsx").toPath());
        MockMultipartFile roomFile = new MockMultipartFile("file", "rooms.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", roomBytes);

        mockMvc.perform(multipart("/api/rooms/import").file(roomFile))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(5))
                .andExpect(jsonPath("$.totalRows").value(5));

        // B) Upload Students via REST
        byte[] studentBytes = Files.readAllBytes(new File("sample_excel_files/students_highschool_cbse.xlsx").toPath());
        MockMultipartFile studentFile = new MockMultipartFile("file", "cbse_students.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", studentBytes);

        mockMvc.perform(multipart("/api/students/import").file(studentFile))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(120))
                .andExpect(jsonPath("$.totalRows").value(120));

        // C) POST /api/seating/arrange
        String arrangeJson = """
                {
                  "strictness": "BASIC",
                  "horizontalGap": 1,
                  "verticalGap": 0,
                  "diagonalGap": 0,
                  "conflictScope": "DEPARTMENT_AND_YEAR",
                  "benchPairingRule": "DIFFERENT_DEPARTMENT_AND_YEAR",
                  "horizontalDeptIsolation": true,
                  "clearExisting": true
                }
                """;

        mockMvc.perform(post("/api/seating/arrange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arrangeJson))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seated").value(120))
                .andExpect(jsonPath("$.unplaced").value(0))
                .andExpect(jsonPath("$.solved").value(true));

        // D) GET /api/seating/allocations
        mockMvc.perform(get("/api/seating/allocations"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seated").value(120));

        // E) GET /api/export/excel
        mockMvc.perform(get("/api/export/excel"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    byte[] content = result.getResponse().getContentAsByteArray();
                    assertTrue(content.length > 500);
                });

        // F) GET /api/export/pdf/door-notices
        mockMvc.perform(get("/api/export/pdf/door-notices"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    byte[] content = result.getResponse().getContentAsByteArray();
                    assertTrue(content.length > 500);
                    assertEquals('%', (char) content[0]);
                });

        // G) GET /api/export/pdf/invigilator
        mockMvc.perform(get("/api/export/pdf/invigilator"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    byte[] content = result.getResponse().getContentAsByteArray();
                    assertTrue(content.length > 500);
                    assertEquals('%', (char) content[0]);
                });
    }

    // ------------------------------------------------------------------
    //  Helper Methods
    // ------------------------------------------------------------------

    private void importRoomsFile(String path) throws Exception {
        try (InputStream in = new FileInputStream(path)) {
            excelImportService.importRooms(in);
        }
    }

    private void importStudentsFile(String path) throws Exception {
        try (InputStream in = new FileInputStream(path)) {
            excelImportService.importStudents(in);
        }
    }

    private void verifyAllocationsConstraints(List<SeatingAllocation> allocations, SeatingConfig config) {
        // Map allocations by bench and slot
        Map<String, SeatingAllocation> seatMap = new HashMap<>();
        Set<Long> studentIds = new HashSet<>();

        for (SeatingAllocation a : allocations) {
            String key = a.getBench().getRoom().getId() + ":" + a.getBench().getRowIndex() + ":" + a.getBench().getColIndex() + ":" + a.getSlotNumber();
            assertFalse(seatMap.containsKey(key), "Seat must not be double booked: " + key);
            seatMap.put(key, a);

            assertTrue(studentIds.add(a.getStudent().getId()), "Student must not be seated twice: " + a.getStudent().getRollNo());
        }

        // Pairwise constraint checks within each room
        Map<Long, List<SeatingAllocation>> byRoom = new HashMap<>();
        for (SeatingAllocation a : allocations) {
            byRoom.computeIfAbsent(a.getBench().getRoom().getId(), k -> new ArrayList<>()).add(a);
        }

        for (List<SeatingAllocation> roomAllocs : byRoom.values()) {
            for (int i = 0; i < roomAllocs.size(); i++) {
                for (int j = i + 1; j < roomAllocs.size(); j++) {
                    SeatingAllocation a1 = roomAllocs.get(i);
                    SeatingAllocation a2 = roomAllocs.get(j);
                    Bench b1 = a1.getBench();
                    Bench b2 = a2.getBench();
                    Student s1 = a1.getStudent();
                    Student s2 = a2.getStudent();

                    // 1. Dual-seater bench rule
                    boolean sameBench = (b1.getId() != null && b2.getId() != null)
                            ? b1.getId().equals(b2.getId())
                            : b1 == b2;
                    if (sameBench) {
                        assertNotEquals(s1.getDepartment(), s2.getDepartment(), "Bench mates must differ in department");
                        if (config.benchPairingRule() == BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR) {
                            assertNotEquals(s1.getYearSession(), s2.getYearSession(), "Bench mates must differ in year");
                        }
                    }

                    int seatsPerBench = b1.getBenchType() == ExamRoom.BenchType.DUAL_SEATER ? 2 : 1;
                    int physCol1 = (b1.getColIndex() - 1) * seatsPerBench + a1.getSlotNumber();
                    int physCol2 = (b2.getColIndex() - 1) * seatsPerBench + a2.getSlotNumber();
                    int dr = Math.abs(b1.getRowIndex() - b2.getRowIndex());
                    int seatDc = Math.abs(physCol1 - physCol2);
                    int benchDc = Math.abs(b1.getColIndex() - b2.getColIndex());

                    // 2. Horizontal Dept Isolation
                    if (config.horizontalDeptIsolation() && config.horizontalGap() > 0 && dr == 0 && seatDc == 1) {
                        assertNotEquals(s1.getDepartment(), s2.getDepartment(),
                                "Horizontally adjacent seats must not share department: " + s1.getRollNo() + " vs " + s2.getRollNo());
                    }

                    // 3. Gap rules
                    boolean isConflict = keyConflicts(s1, s2, config);
                    if (isConflict) {
                        if (config.horizontalGap() > 0 && dr == 0) {
                            assertTrue(seatDc > config.horizontalGap(),
                                    "Conflicting students in same row must be > dH apart: " + s1.getRollNo() + " vs " + s2.getRollNo());
                        }
                        if (config.verticalGap() > 0 && benchDc == 0) {
                            assertTrue(dr > config.verticalGap(),
                                    "Conflicting students in same bench column must be > dV apart: " + s1.getRollNo() + " vs " + s2.getRollNo());
                        }
                        if (config.diagonalGap() > 0 && dr > 0 && benchDc > 0) {
                            assertTrue(dr > config.diagonalGap() || benchDc > config.diagonalGap(),
                                    "Conflicting students diagonally placed must be > dD apart: " + s1.getRollNo() + " vs " + s2.getRollNo());
                        }
                    }
                }
            }
        }
    }

    private void verifyPlanConstraints(SeatingPlan plan, SeatingConfig config) {
        verifyAllocationsConstraints(plan.allocations(), config);
    }

    private boolean keyConflicts(Student a, Student b, SeatingConfig config) {
        List<StudentAttribute> attrs = config.effectiveGroupKey();
        GroupMatchMode mode = config.groupMatchMode() != null ? config.groupMatchMode() : GroupMatchMode.ALL;

        if (mode == GroupMatchMode.ANY) {
            for (StudentAttribute attr : attrs) {
                String va = canon(attr.value(a));
                String vb = canon(attr.value(b));
                if (!va.isEmpty() && !vb.isEmpty() && va.equals(vb)) {
                    return true;
                }
            }
            return false;
        } else {
            for (StudentAttribute attr : attrs) {
                String va = canon(attr.value(a));
                String vb = canon(attr.value(b));
                if (va.isEmpty() || vb.isEmpty() || !va.equals(vb)) {
                    return false;
                }
            }
            return true;
        }
    }

    private String canon(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase();
    }
}
