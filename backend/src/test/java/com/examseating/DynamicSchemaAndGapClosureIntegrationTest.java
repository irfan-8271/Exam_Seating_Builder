package com.examseating;

import com.examseating.engine.SeatingArrangementEngine;
import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Student;
import com.examseating.repository.CohortRuleRepository;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.repository.SeatingRoundRepository;
import com.examseating.repository.StudentIdentitySchemeRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.tenancy.TenantSupport;
import com.lowagie.text.pdf.PdfReader;
import com.lowagie.text.pdf.parser.PdfTextExtractor;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Comprehensive verification of dynamic institutional schema discovery,
 * 3-column composite student IDs & classmate cohorts, exact header preservation,
 * live student re-keying, stored classmate-group auto-fallback during generation,
 * and dynamic ID + cohort rendering across `/api/v1/*` REST responses, Excel, and PDF exports.
 */
@SpringBootTest
@AutoConfigureMockMvc
class DynamicSchemaAndGapClosureIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ExamRoomRepository examRoomRepository;
    @Autowired private SeatingAllocationRepository allocationRepository;
    @Autowired private SeatingRoundRepository roundRepository;
    @Autowired private StudentIdentitySchemeRepository schemeRepository;
    @Autowired private CohortRuleRepository cohortRuleRepository;
    @Autowired private TenantSupport tenantSupport;
    private final SeatingArrangementEngine engine = new SeatingArrangementEngine();

    @BeforeEach
    void cleanDatabase() {
        allocationRepository.deleteAll();
        roundRepository.deleteAll();
        studentRepository.deleteAll();
        examRoomRepository.deleteAll();
        cohortRuleRepository.deleteAll();
        schemeRepository.deleteAll();
    }

    @Test
    @DisplayName("3-column composite ID + 3-column classmate group + auto-fallback arrange + dynamic Excel/PDF exports")
    void threeColumnCompositeIdentityAndAutoClassmateFallbackWorkflow() throws Exception {
        byte[] workbookBytes = buildThreeColumnWorkbook();
        MockMultipartFile file = new MockMultipartFile(
                "file", "composite_school.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", workbookBytes);

        // 1. Preview detects 3-column composite identity AND classmate group suggestions
        mockMvc.perform(multipart("/api/v1/students/imports/preview").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(24))
                .andExpect(jsonPath("$.suggestedIdentities.length()").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.suggestedIdentities[0].unique").value(true))
                .andExpect(jsonPath("$.suggestedIdentities[0].attributes.length()").value(3))
                .andExpect(jsonPath("$.suggestedClassmateGroups.length()").value(greaterThanOrEqualTo(1)));

        // 2. Import with 3-column identity (wing,section,rollserial) and 3-column classmate cohort (wing,grade,section)
        mockMvc.perform(multipart("/api/v1/students/imports")
                        .file(file)
                        .param("identityAttributes", "wing,section,rollserial")
                        .param("classmateAttributes", "wing,grade,section"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(24));

        // Verify live schema endpoint reflects original headers & stored scheme
        mockMvc.perform(get("/api/v1/students/schema"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalStudents").value(24))
                .andExpect(jsonPath("$.currentScheme.attributes.length()").value(3))
                .andExpect(jsonPath("$.currentScheme.classmateAttributes.length()").value(3))
                .andExpect(jsonPath("$.columns[?(@.key == 'rollserial')].title", hasItem("Roll Serial")));

        // 3. Create dual-seater hall (5 rows x 4 cols = 20 benches = 40 seats)
        createHall("Main Block Hall", 5, 4, ExamRoom.BenchType.DUAL_SEATER);

        // 4. Call POST /api/v1/seating-plans with ONLY strictness (NO groupAttributes in body!)
        for (String strictness : List.of("NORMAL", "INTERMEDIATE", "EXTREME", "HARD")) {
            mockMvc.perform(post("/api/v1/seating-plans")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"strictness\":\"" + strictness + "\",\"clearExisting\":true}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.solved").value(true))
                    .andExpect(jsonPath("$.seated").value(24))
                    .andExpect(jsonPath("$.unplaced").value(0))
                    .andExpect(jsonPath("$.allocations[0].identity", containsString(" · ")))
                    .andExpect(jsonPath("$.allocations[0].cohort", containsString(" · ")));

            assertAdjacencySatisfied(strictness, List.of("wing", "grade", "section"));
        }

        // Verify SeatingRoundView exposes canonical strictness ("EXTREME") + groupAttributes
        mockMvc.perform(get("/api/v1/seating-rounds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].strictness").value("EXTREME"))
                .andExpect(jsonPath("$[0].groupAttributes[0]").value("wing"))
                .andExpect(jsonPath("$[0].groupAttributes[1]").value("grade"))
                .andExpect(jsonPath("$[0].groupAttributes[2]").value("section"));

        // Verify GET /api/v1/seating-plans/current also returns full composite identity & cohort
        mockMvc.perform(get("/api/v1/seating-plans/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.allocations[0].identity", containsString(" · ")))
                .andExpect(jsonPath("$.allocations[0].cohort", containsString(" · ")));

        // 5. Verify Excel export contains dynamic headers, composite IDs & dynamic classmate cohorts
        MvcResult xlsxResult = mockMvc.perform(get("/api/v1/seating-plans/current/workbook"))
                .andExpect(status().isOk())
                .andReturn();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(xlsxResult.getResponse().getContentAsByteArray()))) {
            Sheet master = wb.getSheet("Master List");
            assertNotNull(master);
            assertEquals("Student ID", master.getRow(0).getCell(0).getStringCellValue());
            assertEquals("Cohort", master.getRow(0).getCell(2).getStringCellValue());

            Row firstData = master.getRow(1);
            assertNotNull(firstData);
            String exportedId = firstData.getCell(0).getStringCellValue();
            String exportedCohort = firstData.getCell(2).getStringCellValue();
            assertEquals(3, exportedId.split(" · ").length,
                    "Expected 3-part composite ID in Excel Master List: " + exportedId);
            assertEquals(3, exportedCohort.split(" · ").length,
                    "Expected 3-part dynamic cohort in Excel Master List: " + exportedCohort);

            Sheet legend = wb.getSheet("Legend");
            assertNotNull(legend);
            assertTrue(legend.getLastRowNum() >= 4, "Legend should list dynamic cohorts");
            assertTrue(legend.getRow(1).getCell(0).getStringCellValue().contains(" · "));
        }

        // 6. Verify PDF exports render dynamic composite IDs & cohorts cleanly
        for (String endpoint : List.of(
                "/api/v1/seating-plans/current/door-notices",
                "/api/v1/seating-plans/current/invigilator-sheets")) {
            byte[] pdfBytes = mockMvc.perform(get(endpoint))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsByteArray();
            assertTrue(pdfBytes.length > 500);
            String extracted = extractAllPdfText(pdfBytes);
            assertTrue(extracted.contains("Junior"), "PDF " + endpoint + " should include dynamic attributes");
        }
    }

    @Test
    @DisplayName("Similar header names in the same Excel sheet preserve every column under its own normalized key")
    void similarHeaderNamesPreserveAllColumns() throws Exception {
        byte[] bytes;
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Students");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Student Name");
            header.createCell(1).setCellValue("Admission No");
            header.createCell(2).setCellValue("Roll Number");
            header.createCell(3).setCellValue("Program");
            header.createCell(4).setCellValue("Degree");

            for (int i = 1; i <= 6; i++) {
                Row row = sheet.createRow(i);
                row.createCell(0).setCellValue("Candidate " + i);
                row.createCell(1).setCellValue("ADM-2026-" + i);
                row.createCell(2).setCellValue(String.valueOf((i - 1) % 3 + 1));
                row.createCell(3).setCellValue("Integrated");
                row.createCell(4).setCellValue(i <= 3 ? "BTech" : "BS");
            }
            wb.write(out);
            bytes = out.toByteArray();
        }

        MockMultipartFile file = new MockMultipartFile(
                "file", "similar_headers.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);

        mockMvc.perform(multipart("/api/v1/students/imports/preview").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.columns[?(@.key == 'admissionno')].unique", hasItem(true)))
                .andExpect(jsonPath("$.columns[?(@.key == 'rollnumber')].unique", hasItem(false)))
                .andExpect(jsonPath("$.columns[?(@.key == 'program')]").isNotEmpty())
                .andExpect(jsonPath("$.columns[?(@.key == 'degree')]").isNotEmpty());

        mockMvc.perform(multipart("/api/v1/students/imports")
                        .file(file)
                        .param("identityAttributes", "degree,rollnumber")
                        .param("classmateAttributes", "program,degree"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(6));

        List<Student> imported = studentRepository.findAll();
        assertEquals(6, imported.size());
        for (Student s : imported) {
            assertTrue(s.getAttributes().get("admissionno").startsWith("ADM-2026-"));
            assertNotNull(s.getAttributes().get("rollnumber"));
            assertEquals("Integrated", s.getAttributes().get("program"));
            assertTrue(Set.of("BTech", "BS").contains(s.getAttributes().get("degree")));
        }
    }

    @Test
    @DisplayName("Live scheme update validates uniqueness, re-keys existing students atomically, and enforces manual entry rules")
    void liveSchemeReKeyingAndManualEntryValidation() throws Exception {
        String[][] rows = {
                {"Aarav", "G10", "SecA", "01", "ADM-101"},
                {"Diya",  "G10", "SecA", "02", "ADM-102"},
                {"Kabir", "G10", "SecB", "01", "ADM-103"},
                {"Meera", "G10", "SecB", "02", "ADM-104"}
        };
        for (String[] r : rows) {
            String body = String.format(
                    "{\"name\":\"%s\",\"attributes\":{\"grade\":\"%s\",\"section\":\"%s\",\"classroll\":\"%s\",\"admno\":\"%s\"}}",
                    r[0], r[1], r[2], r[3], r[4]);
            mockMvc.perform(post("/api/v1/students")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated());
        }

        // 2. Attempting to set non-unique identity ("section") must fail with 400 Bad Request
        mockMvc.perform(put("/api/v1/students/identity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Section Only\",\"attributes\":[\"section\"]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("duplicate identity")));

        // 3. Setting valid composite identity ("section" + "classroll") re-keys all 4 existing students
        mockMvc.perform(put("/api/v1/students/identity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Section + Class Roll\",\"attributes\":[\"section\",\"classroll\"],\"classmateAttributes\":[\"grade\",\"section\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes[0]").value("section"))
                .andExpect(jsonPath("$.attributes[1]").value("classroll"));

        Set<String> keys = new HashSet<>();
        for (Student s : studentRepository.findAll()) {
            assertNotNull(s.getIdentityKey());
            assertTrue(s.getIdentityKey().contains("|"));
            keys.add(s.getIdentityKey());
        }
        assertEquals(4, keys.size());

        // 4. Switching again to single-attribute "admno" re-keys cleanly without unique-constraint collision
        mockMvc.perform(put("/api/v1/students/identity")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Admission Number\",\"attributes\":[\"admno\"],\"classmateAttributes\":[\"grade\",\"section\"]}"))
                .andExpect(status().isOk());

        for (Student s : studentRepository.findAll()) {
            assertTrue(s.getIdentityKey().startsWith("ADM-"));
        }

        // 5. Manual student creation missing required 'admno' or duplicating 'ADM-101' returns 400
        mockMvc.perform(post("/api/v1/students")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Missing ID\",\"attributes\":{\"grade\":\"G10\",\"section\":\"SecA\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("Missing required identifying attribute")));

        mockMvc.perform(post("/api/v1/students")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Duplicate ID\",\"attributes\":{\"admno\":\"ADM-101\",\"grade\":\"G10\",\"section\":\"SecA\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("already exists")));
    }

    @Test
    @DisplayName("Engine rejects unpopulated groupAttributes instead of silently collapsing to empty-tuple conflict")
    void engineRejectsUnpopulatedGroupAttributes() {
        Student s1 = Student.builder().name("S1").identityKey("1").attributes(Map.of("grade", "10")).build();
        Student s2 = Student.builder().name("S2").identityKey("2").attributes(Map.of("grade", "11")).build();
        ExamRoom room = ExamRoom.builder().hallName("H1").rows(2).columns(2).benchType(ExamRoom.BenchType.DUAL_SEATER).build();
        for (int r = 1; r <= 2; r++) {
            for (int c = 1; c <= 2; c++) {
                room.getBenches().add(Bench.builder().room(room).rowIndex(r).colIndex(c).build());
            }
        }

        SeatingArrangementEngine.SeatingConfig badConfig = SeatingArrangementEngine.SeatingConfig.normal("nonexistent_column");

        SeatingArrangementEngine.IllegalSeatingConfigurationException ex = assertThrows(
                SeatingArrangementEngine.IllegalSeatingConfigurationException.class,
                () -> engine.arrange(List.of(room), List.of(s1, s2), badConfig));
        assertTrue(ex.getMessage().contains("nonexistentcolumn"));
    }

    private ExamRoom createHall(String name, int rows, int cols, ExamRoom.BenchType type) {
        ExamRoom room = ExamRoom.builder()
                .hallName(name)
                .rows(rows)
                .columns(cols)
                .benchType(type)
                .build();
        for (int r = 1; r <= rows; r++) {
            for (int c = 1; c <= cols; c++) {
                room.getBenches().add(Bench.builder().room(room).rowIndex(r).colIndex(c).build());
            }
        }
        tenantSupport.stamp(room);
        return examRoomRepository.save(room);
    }

    private byte[] buildThreeColumnWorkbook() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Roster");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Student Name");
            header.createCell(1).setCellValue("Wing");
            header.createCell(2).setCellValue("Grade");
            header.createCell(3).setCellValue("Section");
            header.createCell(4).setCellValue("Roll Serial");

            int rowNum = 1;
            for (String wing : List.of("Junior", "Senior")) {
                for (String section : List.of("A", "B")) {
                    for (int gIdx = 0; gIdx < 2; gIdx++) {
                        String grade = gIdx == 0 ? "11" : "12";
                        for (int s = 1; s <= 3; s++) {
                            int serial = gIdx * 3 + s;
                            Row row = sheet.createRow(rowNum++);
                            row.createCell(0).setCellValue("Student " + rowNum);
                            row.createCell(1).setCellValue(wing);
                            row.createCell(2).setCellValue(grade);
                            row.createCell(3).setCellValue(section);
                            row.createCell(4).setCellValue(String.format("%02d", serial));
                        }
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private void assertAdjacencySatisfied(String strictness, List<String> groupKeys) {
        List<SeatingAllocation> allocations = allocationRepository.findAllWithDetails(null);
        Map<String, SeatingAllocation> grid = new HashMap<>();
        for (SeatingAllocation a : allocations) {
            int seatCol = a.getBench().getBenchType() == ExamRoom.BenchType.DUAL_SEATER
                    ? (a.getBench().getColIndex() - 1) * 2 + a.getSlotNumber()
                    : a.getBench().getColIndex();
            grid.put(a.getBench().getRoom().getId() + ":" + a.getBench().getRowIndex() + ":" + seatCol, a);
        }

        for (SeatingAllocation a : allocations) {
            int r = a.getBench().getRowIndex();
            int seatCol = a.getBench().getBenchType() == ExamRoom.BenchType.DUAL_SEATER
                    ? (a.getBench().getColIndex() - 1) * 2 + a.getSlotNumber()
                    : a.getBench().getColIndex();
            long roomId = a.getBench().getRoom().getId();

            SeatingAllocation right = grid.get(roomId + ":" + r + ":" + (seatCol + 1));
            if (right != null) {
                assertFalse(sameCohort(a.getStudent(), right.getStudent(), groupKeys),
                        strictness + ": horizontal classmates seated adjacent");
            }

            boolean isExtreme = "EXTREME".equals(strictness) || "HARD".equals(strictness) || "RIGID".equals(strictness);
            if ("INTERMEDIATE".equals(strictness) || isExtreme) {
                SeatingAllocation below = grid.get(roomId + ":" + (r + 1) + ":" + seatCol);
                if (below != null) {
                    assertFalse(sameCohort(a.getStudent(), below.getStudent(), groupKeys),
                            strictness + ": vertical classmates seated adjacent");
                }
            }

            if (isExtreme) {
                for (int dc : List.of(-1, 1)) {
                    SeatingAllocation diag = grid.get(roomId + ":" + (r + 1) + ":" + (seatCol + dc));
                    if (diag != null) {
                        assertFalse(sameCohort(a.getStudent(), diag.getStudent(), groupKeys),
                                strictness + ": diagonal classmates seated adjacent");
                    }
                }
            }
        }
    }

    private boolean sameCohort(Student a, Student b, List<String> keys) {
        for (String k : keys) {
            String va = a.getAttributes().getOrDefault(k, "");
            String vb = b.getAttributes().getOrDefault(k, "");
            if (!va.equalsIgnoreCase(vb)) return false;
        }
        return true;
    }

    private String extractAllPdfText(byte[] pdfBytes) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (PdfReader reader = new PdfReader(pdfBytes)) {
            PdfTextExtractor extractor = new PdfTextExtractor(reader);
            for (int page = 1; page <= reader.getNumberOfPages(); page++) {
                sb.append(extractor.getTextFromPage(page)).append('\n');
            }
        }
        return sb.toString();
    }
}
