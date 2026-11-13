package com.examseating;

import com.examseating.dto.ArrangeRequest;
import com.examseating.dto.ArrangeRequest.StrictnessLevel;
import com.examseating.dto.ImportResult;
import com.examseating.dto.SeatingResponse;
import com.examseating.engine.SeatingArrangementEngine;
import com.examseating.engine.SeatingArrangementEngine.GroupMatchMode;
import com.examseating.engine.SeatingArrangementEngine.SeatingConfig;
import com.examseating.engine.SeatingArrangementEngine.SeatingPlan;
import com.examseating.engine.SeatingArrangementEngine.StudentAttribute;
import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Student;
import com.examseating.entity.StudentAttributes;
import com.examseating.entity.Subscription;
import com.examseating.repository.BenchRepository;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.repository.SeatingRoundRepository;
import com.examseating.repository.StudentIdentitySchemeRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.repository.SubscriptionRepository;
import com.examseating.service.ExcelExportService;
import com.examseating.service.ExcelImportService;
import com.examseating.service.PdfExportService;
import com.examseating.service.SeatingService;
import com.examseating.tenancy.TenantSupport;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Full-matrix integration test suite over realistic campus rooms, diverse
 * institutional Excel rosters, strictness presets ({@code NORMAL}, {@code INTERMEDIATE},
 * {@code HARD}), custom gap matrices, {@code ALL} vs {@code ANY} cohort matching,
 * incremental multi-scope rounds ({@code clearExisting = false}), hall selection,
 * feasibility pre-checks, Excel & PDF exports, and {@code /api/v1/*} REST endpoints.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class ComprehensiveCombinationIntegrationTest {

    @Autowired private ExcelImportService excelImportService;
    @Autowired private SeatingService seatingService;
    @Autowired private ExcelExportService excelExportService;
    @Autowired private PdfExportService pdfExportService;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ExamRoomRepository examRoomRepository;
    @Autowired private BenchRepository benchRepository;
    @Autowired private SeatingAllocationRepository allocationRepository;
    @Autowired private SeatingRoundRepository roundRepository;
    @Autowired private StudentIdentitySchemeRepository schemeRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private TenantSupport tenantSupport;
    @Autowired private MockMvc mockMvc;

    private final SeatingArrangementEngine engine = new SeatingArrangementEngine();

    @BeforeEach
    void cleanDatabase() {
        allocationRepository.deleteAllInBatch();
        roundRepository.deleteAllInBatch();
        benchRepository.deleteAllInBatch();
        examRoomRepository.deleteAllInBatch();
        studentRepository.deleteAllInBatch();
        schemeRepository.deleteAllInBatch();

        Long defaultTenantId = tenantSupport.currentId();
        Subscription sub = subscriptionRepository.findByInstitutionId(defaultTenantId)
                .orElseGet(() -> Subscription.builder().institutionId(defaultTenantId).build());
        sub.setPlanCode("ENTERPRISE");
        sub.setStatus(Subscription.Status.ACTIVE);
        sub.setPeriodStart(Instant.now().minus(1, ChronoUnit.DAYS));
        sub.setPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
        subscriptionRepository.save(sub);
    }

    private static Student buildStudent(long id, String name, String identity, String... kvPairs) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("id", identity);
        for (int i = 0; i + 1 < kvPairs.length; i += 2) {
            attrs.put(kvPairs[i], kvPairs[i + 1]);
        }
        return Student.builder().id(id).name(name).identityKey(identity).attributes(attrs).build();
    }

    @Test
    @DisplayName("Import all sample and realistic room & student Excel files")
    void testImportAllSampleFilesAndRealisticDatasets() throws Exception {
        try (InputStream in = new FileInputStream("sample_excel_files/rooms_sample.xlsx")) {
            ImportResult res = excelImportService.importRooms(in);
            assertEquals(5, res.imported());
            assertEquals(0, res.errors().size());
        }
        try (InputStream in = new FileInputStream("sample_excel_files/rooms_campus_realistic.xlsx")) {
            ImportResult res = excelImportService.importRooms(in);
            assertEquals(5, res.imported());
            assertEquals(0, res.errors().size());
        }
        assertEquals(10, examRoomRepository.count());

        try (InputStream in = new FileInputStream("sample_excel_files/students_university_realistic.xlsx")) {
            ImportResult res = excelImportService.importStudents(in);
            assertEquals(180, res.imported());
            assertEquals(0, res.errors().size());
        }
        try (InputStream in = new FileInputStream("sample_excel_files/students_highschool_cbse.xlsx")) {
            ImportResult res = excelImportService.importStudents(in);
            assertEquals(120, res.imported());
            assertEquals(0, res.errors().size());
        }
        try (InputStream in = new FileInputStream("sample_excel_files/students_edge_cases.xlsx")) {
            ImportResult res = excelImportService.importStudents(in);
            assertEquals(60, res.imported());
            assertEquals(0, res.errors().size());
        }
        assertEquals(360, studentRepository.count());
    }

    @Test
    @DisplayName("Test all strictness presets: NORMAL, INTERMEDIATE, HARD")
    void testStrictnessPresetsCombinations() throws Exception {
        importRoomsFile("sample_excel_files/rooms_campus_realistic.xlsx");
        importStudentsFile("sample_excel_files/students_university_realistic.xlsx");

        List<StudentAttribute> cohortKeys = List.of(
                StudentAttribute.of("department"), StudentAttribute.of("yearsession"));

        for (StrictnessLevel level : StrictnessLevel.values()) {
            ArrangeRequest request = new ArrangeRequest(
                    level,
                    null, null, null,
                    cohortKeys, GroupMatchMode.ALL,
                    null, null, null, true);

            SeatingResponse response = seatingService.arrange(request);
            assertEquals(180, response.seated(), "Level " + level + " should seat all 180 students");
            assertEquals(0, response.unplaced());
            assertTrue(response.solved());

            List<SeatingAllocation> allocations = allocationRepository.findAllWithDetails(null);
            assertEquals(180, allocations.size());

            int dH = 1;
            int dV = (level == StrictnessLevel.INTERMEDIATE || level == StrictnessLevel.EXTREME) ? 1 : 0;
            int dD = (level == StrictnessLevel.EXTREME) ? 1 : 0;

            SeatingConfig expectedConfig = new SeatingConfig(
                    dH, dV, dD,
                    SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                    cohortKeys, GroupMatchMode.ALL);
            verifyAllocationsConstraints(allocations, expectedConfig);
        }
    }

    @Test
    @DisplayName("Test custom gap combinations: (0,0,0), (1,0,0), (1,1,0), (1,1,1)")
    void testCustomGapCombinations() throws Exception {
        importRoomsFile("sample_excel_files/rooms_campus_realistic.xlsx");
        importStudentsFile("sample_excel_files/students_university_realistic.xlsx");

        List<StudentAttribute> cohortKeys = List.of(
                StudentAttribute.of("department"), StudentAttribute.of("yearsession"));
        int[][] gapMatrix = {{0, 0, 0}, {1, 0, 0}, {1, 1, 0}, {1, 1, 1}};

        for (int[] gaps : gapMatrix) {
            ArrangeRequest request = new ArrangeRequest(
                    null,
                    gaps[0], gaps[1], gaps[2],
                    cohortKeys, GroupMatchMode.ALL,
                    "subjectcode", "MATH101",
                    null, true);

            SeatingResponse response = seatingService.arrange(request);
            assertEquals(60, response.seated());
            assertTrue(response.solved());

            List<SeatingAllocation> allocations = allocationRepository.findAllWithDetails("MATH101");
            SeatingConfig cfg = new SeatingConfig(
                    gaps[0], gaps[1], gaps[2],
                    SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                    cohortKeys, GroupMatchMode.ALL);
            verifyAllocationsConstraints(allocations, cfg);
        }
    }

    @Test
    @DisplayName("Test dual-bench pairing under ANY vs ALL match modes")
    void testDualBenchPairingMatchModes() {
        ExamRoom dualRoom = examRoomRepository.save(ExamRoom.builder()
                .hallName("Single Dual Bench Hall")
                .rows(1)
                .columns(1)
                .benchType(ExamRoom.BenchType.DUAL_SEATER)
                .build());
        dualRoom.getBenches().add(Bench.builder().room(dualRoom).rowIndex(1).colIndex(1).build());
        examRoomRepository.save(dualRoom);

        studentRepository.saveAll(List.of(
                Student.builder().name("A1").identityKey("R1")
                        .attributes(Map.of("rollno", "R1", "department", "CSE", "yearsession", "2024")).build(),
                Student.builder().name("B1").identityKey("R2")
                        .attributes(Map.of("rollno", "R2", "department", "ECE", "yearsession", "2024")).build()));

        List<StudentAttribute> attrs = List.of(
                StudentAttribute.of("department"), StudentAttribute.of("yearsession"));

        // 1. Under ANY mode, sharing yearsession=2024 makes them conflict -> cannot share the lone dual bench!
        ArrangeRequest anyRequest = new ArrangeRequest(
                StrictnessLevel.NORMAL, null, null, null,
                attrs, GroupMatchMode.ANY,
                null, null, null, true);
        assertThrows(Exception.class, () -> seatingService.arrange(anyRequest));

        // 2. Under ALL mode, (CSE,2024) != (ECE,2024) -> they are distinct cohorts and share the dual bench!
        ArrangeRequest allRequest = new ArrangeRequest(
                StrictnessLevel.NORMAL, null, null, null,
                attrs, GroupMatchMode.ALL,
                null, null, null, true);
        SeatingResponse response = seatingService.arrange(allRequest);
        assertEquals(2, response.seated());
    }

    @Test
    @DisplayName("Test Configurable Group Attributes across 1, 2, and 3 attributes with ALL and ANY")
    void testGroupAttributesAndMatchModes() {
        ExamRoom room = ExamRoom.builder()
                .id(1L)
                .hallName("Test Hall")
                .rows(2)
                .columns(4)
                .benchType(ExamRoom.BenchType.SINGLE_SEATER)
                .build();

        List<Student> students = List.of(
                buildStudent(1L, "A", "1", "department", "CSE", "course", "B.Tech", "yearsession", "2024"),
                buildStudent(2L, "B", "2", "department", "ECE", "course", "B.Tech", "yearsession", "2024"),
                buildStudent(3L, "C", "3", "department", "CSE", "course", "M.Tech", "yearsession", "2025"),
                buildStudent(4L, "D", "4", "department", "ECE", "course", "M.Tech", "yearsession", "2025"));

        List<SeatingConfig> configs = List.of(
                SeatingConfig.normal("course"),
                SeatingConfig.normal("department", "yearsession"),
                new SeatingConfig(1, 0, 0, SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                        List.of(StudentAttribute.of("department"), StudentAttribute.of("yearsession")),
                        GroupMatchMode.ANY),
                SeatingConfig.normal("department", "course", "yearsession"));

        for (SeatingConfig cfg : configs) {
            SeatingPlan plan = engine.arrange(List.of(room), students, cfg);
            assertEquals(4, plan.allocations().size());
            verifyAllocationsConstraints(plan.allocations(), cfg);
        }
    }

    @Test
    @DisplayName("Test dynamic scope filtering and incremental rounds with clearExisting=false")
    void testDynamicScopeFilterAndIncrementalRounds() throws Exception {
        importRoomsFile("sample_excel_files/rooms_campus_realistic.xlsx");
        importStudentsFile("sample_excel_files/students_university_realistic.xlsx");

        List<ExamRoom> rooms = examRoomRepository.findAllByOrderByHallNameAsc();
        Long mainHallId = rooms.stream().filter(r -> r.getHallName().contains("Main")).findFirst().orElseThrow().getId();
        Long sciHallId = rooms.stream().filter(r -> r.getHallName().contains("Science")).findFirst().orElseThrow().getId();

        List<StudentAttribute> cohortKeys = List.of(
                StudentAttribute.of("department"), StudentAttribute.of("yearsession"));

        // Round 1: MATH101 in Main Hall
        SeatingResponse res1 = seatingService.arrange(new ArrangeRequest(
                StrictnessLevel.NORMAL, null, null, null,
                cohortKeys, GroupMatchMode.ALL,
                "subjectcode", "MATH101",
                List.of(mainHallId), true));
        assertEquals(60, res1.seated());
        assertEquals(60, allocationRepository.count());

        // Round 2: PHYS101 in Science Block with clearExisting=false (preserves MATH101!)
        SeatingResponse res2 = seatingService.arrange(new ArrangeRequest(
                StrictnessLevel.NORMAL, null, null, null,
                cohortKeys, GroupMatchMode.ALL,
                "subjectcode", "PHYS101",
                List.of(sciHallId), false));
        assertEquals(60, res2.seated());

        assertEquals(120, allocationRepository.count());
        assertEquals(60, allocationRepository.findAllWithDetails("MATH101").size());
        assertEquals(60, allocationRepository.findAllWithDetails("PHYS101").size());
    }

    @Test
    @DisplayName("Test Hall Selection (subset of room IDs) across mixed single/dual seater halls")
    void testHallSelectionAndMixedHalls() throws Exception {
        importRoomsFile("sample_excel_files/rooms_campus_realistic.xlsx");
        importStudentsFile("sample_excel_files/students_university_realistic.xlsx");

        List<Long> selectedHalls = examRoomRepository.findAll().stream()
                .filter(r -> r.getHallName().contains("Science Block") || r.getHallName().contains("Seminar Hall B"))
                .map(ExamRoom::getId)
                .toList();
        assertEquals(2, selectedHalls.size());

        SeatingResponse response = seatingService.arrange(new ArrangeRequest(
                StrictnessLevel.NORMAL, null, null, null,
                List.of(StudentAttribute.of("department"), StudentAttribute.of("yearsession")),
                GroupMatchMode.ALL,
                "subjectcode", "MATH101",
                selectedHalls, true));
        assertEquals(60, response.seated());

        for (SeatingAllocation a : allocationRepository.findAllWithDetails("MATH101")) {
            assertTrue(selectedHalls.contains(a.getBench().getRoom().getId()));
        }
    }

    @Test
    @DisplayName("Test Feasibility Pre-checks: capacity overflow and cohort density bound")
    void testFeasibilityPrechecks() {
        ExamRoom tinyRoom = ExamRoom.builder()
                .id(1L)
                .hallName("Tiny Hall")
                .rows(2)
                .columns(2)
                .benchType(ExamRoom.BenchType.SINGLE_SEATER)
                .build();

        List<Student> tooMany = List.of(
                buildStudent(1L, "A", "1", "stream", "CSE"),
                buildStudent(2L, "B", "2", "stream", "ECE"),
                buildStudent(3L, "C", "3", "stream", "ME"),
                buildStudent(4L, "D", "4", "stream", "CE"),
                buildStudent(5L, "E", "5", "stream", "IT"));

        assertThrows(SeatingArrangementEngine.InsufficientCapacityException.class,
                () -> engine.arrange(List.of(tinyRoom), tooMany, SeatingConfig.normal("stream")));

        List<Student> denseGroup = List.of(
                buildStudent(1L, "A", "1", "stream", "CSE"),
                buildStudent(2L, "B", "2", "stream", "CSE"),
                buildStudent(3L, "C", "3", "stream", "CSE"),
                buildStudent(4L, "D", "4", "stream", "ECE"));

        assertThrows(SeatingArrangementEngine.SeatingEngineException.class,
                () -> engine.arrange(List.of(tinyRoom), denseGroup, SeatingConfig.normal("stream")));
    }

    @Test
    @DisplayName("Verify Excel & PDF exports on realistic campus data")
    void testExcelAndPdfExportVerification() throws Exception {
        importRoomsFile("sample_excel_files/rooms_campus_realistic.xlsx");
        importStudentsFile("sample_excel_files/students_university_realistic.xlsx");

        seatingService.arrange(new ArrangeRequest(
                StrictnessLevel.NORMAL, null, null, null,
                List.of(StudentAttribute.of("department")),
                GroupMatchMode.ALL,
                null, null, null, true));

        byte[] xlsxBytes = excelExportService.export(null);
        assertNotNull(xlsxBytes);
        assertTrue(xlsxBytes.length > 1000);

        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(xlsxBytes))) {
            Sheet masterSheet = wb.getSheet("Master List");
            assertNotNull(masterSheet);
            assertEquals("Student ID", masterSheet.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Student Name", masterSheet.getRow(0).getCell(1).getStringCellValue());
            assertEquals("Cohort", masterSheet.getRow(0).getCell(2).getStringCellValue());
            assertTrue(masterSheet.getLastRowNum() >= 180);

            Sheet legendSheet = wb.getSheet("Legend");
            assertNotNull(legendSheet);
            assertEquals("Cohort / Group", legendSheet.getRow(0).getCell(0).getStringCellValue());
            assertTrue(legendSheet.getLastRowNum() >= 4);
        }

        byte[] doorPdf = pdfExportService.doorNotices(null);
        assertNotNull(doorPdf);
        assertEquals('%', (char) doorPdf[0]);

        byte[] invigPdf = pdfExportService.invigilatorSheets(null);
        assertNotNull(invigPdf);
        assertEquals('%', (char) invigPdf[0]);
    }

    @Test
    @DisplayName("Verify pure /api/v1/* REST API endpoints end-to-end: imports, arrange, current plan, exports")
    void testRestApiEndpointsEndToEnd() throws Exception {
        byte[] roomBytes = Files.readAllBytes(new File("sample_excel_files/rooms_campus_realistic.xlsx").toPath());
        MockMultipartFile roomFile = new MockMultipartFile("file", "rooms.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", roomBytes);

        mockMvc.perform(multipart("/api/v1/rooms/imports").file(roomFile))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(5))
                .andExpect(jsonPath("$.totalRows").value(5));

        byte[] studentBytes = Files.readAllBytes(new File("sample_excel_files/students_highschool_cbse.xlsx").toPath());
        MockMultipartFile studentFile = new MockMultipartFile("file", "cbse_students.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", studentBytes);

        mockMvc.perform(multipart("/api/v1/students/imports").file(studentFile))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(120))
                .andExpect(jsonPath("$.totalRows").value(120));

        String arrangeJson = """
                {
                  "strictness": "NORMAL",
                  "groupAttributes": ["department", "yearsession"],
                  "groupMatchMode": "ALL",
                  "clearExisting": true
                }
                """;

        mockMvc.perform(post("/api/v1/seating-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(arrangeJson))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.seated").value(120))
                .andExpect(jsonPath("$.unplaced").value(0))
                .andExpect(jsonPath("$.solved").value(true));

        mockMvc.perform(get("/api/v1/seating-plans/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seated").value(120));

        mockMvc.perform(get("/api/v1/seating-plans/current/workbook"))
                .andExpect(status().isOk())
                .andExpect(result -> assertTrue(result.getResponse().getContentAsByteArray().length > 500));

        mockMvc.perform(get("/api/v1/seating-plans/current/door-notices"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    byte[] content = result.getResponse().getContentAsByteArray();
                    assertTrue(content.length > 500);
                    assertEquals('%', (char) content[0]);
                });

        mockMvc.perform(get("/api/v1/seating-plans/current/invigilator-sheets"))
                .andExpect(status().isOk())
                .andExpect(result -> {
                    byte[] content = result.getResponse().getContentAsByteArray();
                    assertTrue(content.length > 500);
                    assertEquals('%', (char) content[0]);
                });
    }

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
        Map<String, SeatingAllocation> seatMap = new HashMap<>();
        Set<Long> studentIds = new HashSet<>();

        for (SeatingAllocation a : allocations) {
            long roomId = a.getBench().getRoom().getId() != null ? a.getBench().getRoom().getId() : 1L;
            String key = roomId + ":" + a.getBench().getRowIndex() + ":"
                    + a.getBench().getColIndex() + ":" + a.getSlotNumber();
            assertFalse(seatMap.containsKey(key), "Seat must not be double booked: " + key);
            seatMap.put(key, a);
            if (a.getStudent().getId() != null) {
                assertTrue(studentIds.add(a.getStudent().getId()),
                        "Student must not be seated twice: " + StudentAttributes.displayLabel(a.getStudent()));
            }
        }

        Map<Long, List<SeatingAllocation>> byRoom = new HashMap<>();
        for (SeatingAllocation a : allocations) {
            long roomId = a.getBench().getRoom().getId() != null ? a.getBench().getRoom().getId() : 1L;
            byRoom.computeIfAbsent(roomId, k -> new ArrayList<>()).add(a);
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

                    boolean isConflict = keyConflicts(s1, s2, config);
                    boolean sameBench = (b1.getId() != null && b2.getId() != null)
                            ? b1.getId().equals(b2.getId())
                            : b1 == b2;
                    if (sameBench) {
                        assertFalse(isConflict, "Dual-bench mates must never belong to conflicting cohorts");
                    }

                    int seatsPerBench = b1.getBenchType() == ExamRoom.BenchType.DUAL_SEATER ? 2 : 1;
                    int physCol1 = (b1.getColIndex() - 1) * seatsPerBench + a1.getSlotNumber();
                    int physCol2 = (b2.getColIndex() - 1) * seatsPerBench + a2.getSlotNumber();
                    int dr = Math.abs(b1.getRowIndex() - b2.getRowIndex());
                    int seatDc = Math.abs(physCol1 - physCol2);

                    if (isConflict) {
                        if (config.horizontalGap() > 0 && dr == 0) {
                            assertTrue(seatDc > config.horizontalGap(),
                                    "Conflicting students in same row must be > dH apart");
                        }
                        if (config.verticalGap() > 0 && seatDc == 0) {
                            assertTrue(dr > config.verticalGap(),
                                    "Conflicting students in same seat column must be > dV apart");
                        }
                        if (config.diagonalGap() > 0 && dr > 0 && seatDc > 0) {
                            assertTrue(dr > config.diagonalGap() || seatDc > config.diagonalGap(),
                                    "Conflicting students diagonally placed must be > dD apart");
                        }
                    }
                }
            }
        }
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
        }
        for (StudentAttribute attr : attrs) {
            String va = canon(attr.value(a));
            String vb = canon(attr.value(b));
            if (va.isEmpty() || vb.isEmpty() || !va.equals(vb)) {
                return false;
            }
        }
        return true;
    }

    private String canon(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase();
    }
}
