package com.examseating;

import com.examseating.dto.ArrangeRequest;
import com.examseating.dto.SeatingResponse;
import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Student;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.repository.SeatingRoundRepository;
import com.examseating.repository.StudentIdentitySchemeRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.service.ExcelImportService;
import com.examseating.service.SeatingService;
import com.examseating.service.StudentIdentityService;
import com.examseating.tenancy.TenantSupport;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
public class DynamicInstitutionWorkflowIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private StudentIdentitySchemeRepository schemeRepository;

    @Autowired
    private StudentIdentityService identityService;

    @Autowired
    private ExamRoomRepository examRoomRepository;

    @Autowired
    private SeatingAllocationRepository allocationRepository;

    @Autowired
    private SeatingRoundRepository roundRepository;

    @Autowired
    private ExcelImportService excelImportService;

    @Autowired
    private SeatingService seatingService;

    @Autowired
    private TenantSupport tenantSupport;

    @BeforeEach
    void setUp() {
        allocationRepository.deleteAll();
        roundRepository.deleteAll();
        studentRepository.deleteAll();
        schemeRepository.deleteAll();
        examRoomRepository.deleteAll();
    }

    /**
     * Builds a workbook representing a school or non-traditional institution:
     * Headers: Candidate Name, Admission No, Grade, Division, House
     */
    private byte[] createSchoolWorkbook() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Students");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Candidate Name");
            header.createCell(1).setCellValue("Admission No");
            header.createCell(2).setCellValue("Grade");
            header.createCell(3).setCellValue("Division");
            header.createCell(4).setCellValue("House");

            int rowIdx = 1;
            // 10 students in Grade 10-A (Red House)
            for (int i = 1; i <= 10; i++) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue("Student 10A-" + i);
                row.createCell(1).setCellValue("ADM-10A-" + String.format("%03d", i));
                row.createCell(2).setCellValue("10");
                row.createCell(3).setCellValue("A");
                row.createCell(4).setCellValue("Red");
            }
            // 10 students in Grade 10-B (Blue House)
            for (int i = 1; i <= 10; i++) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue("Student 10B-" + i);
                row.createCell(1).setCellValue("ADM-10B-" + String.format("%03d", i));
                row.createCell(2).setCellValue("10");
                row.createCell(3).setCellValue("B");
                row.createCell(4).setCellValue("Blue");
            }
            // 10 students in Grade 11-A (Green House)
            for (int i = 1; i <= 10; i++) {
                Row row = sheet.createRow(rowIdx++);
                row.createCell(0).setCellValue("Student 11A-" + i);
                row.createCell(1).setCellValue("ADM-11A-" + String.format("%03d", i));
                row.createCell(2).setCellValue("11");
                row.createCell(3).setCellValue("A");
                row.createCell(4).setCellValue("Green");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    @Transactional
    @DisplayName("End-to-End: Upload dynamic roster, configure identity & classmate grouping, and generate across difficulty levels")
    void testDynamicSchoolWorkflowEndToEnd() throws Exception {
        byte[] bytes = createSchoolWorkbook();
        MockMultipartFile file = new MockMultipartFile("file", "students.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", bytes);

        // 1. Preview Excel: system detects available columns and suggests identities and classmate groups
        mockMvc.perform(multipart("/api/v1/students/imports/preview").file(file))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(30))
                .andExpect(jsonPath("$.columns[?(@.key == 'rollno')].unique").value(true))
                .andExpect(jsonPath("$.suggestedClassmateGroups").isArray());

        // 2. Configure identity and classmate grouping:
        // Student identifier: Admission No (rollno)
        // Classmates: grade + division
        mockMvc.perform(put("/api/v1/students/identity")
                        .contentType("application/json")
                        .content("{\"label\":\"Admission No (School Scheme)\",\"attributes\":[\"rollno\"],\"classmateAttributes\":[\"grade\",\"division\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes[0]").value("rollno"))
                .andExpect(jsonPath("$.classmateAttributes[0]").value("grade"))
                .andExpect(jsonPath("$.classmateAttributes[1]").value("division"));

        // Verify GET /identity reflects the saved scheme
        mockMvc.perform(get("/api/v1/students/identity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.classmateAttributes[0]").value("grade"))
                .andExpect(jsonPath("$.classmateAttributes[1]").value("division"));

        // 3. Import students using the stored scheme
        mockMvc.perform(multipart("/api/v1/students/imports")
                        .file(file)
                        .param("identityAttributes", "rollno")
                        .param("classmateAttributes", "grade,division"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(30));

        assertEquals(30, studentRepository.count());
        Student sample = studentRepository.findAll().get(0);
        assertNotNull(sample.getIdentityKey());
        assertTrue(sample.getIdentityKey().startsWith("ADM-"));
        assertEquals("10", sample.getAttributes().get("grade"));
        assertEquals("A", sample.getAttributes().get("division"));

        // 4. Create an Exam Hall (Dual Seater Benches: 6 rows × 6 columns = 36 benches = 72 seats)
        ExamRoom room = ExamRoom.builder()
                .hallName("Auditorium")
                .rows(6)
                .columns(6)
                .benchType(ExamRoom.BenchType.DUAL_SEATER)
                .build();
        for (int r = 1; r <= 6; r++) {
            for (int c = 1; c <= 6; c++) {
                room.getBenches().add(Bench.builder()
                        .room(room)
                        .rowIndex(r)
                        .colIndex(c)
                        .benchType(ExamRoom.BenchType.DUAL_SEATER)
                        .build());
            }
        }
        tenantSupport.stamp(room);
        examRoomRepository.save(room);

        // 5. Test Seating Generation with NORMAL difficulty level and DIFFERENT_GROUP_ONLY bench pairing
        List<com.examseating.engine.SeatingArrangementEngine.StudentAttribute> groupAttrs = List.of(
                new com.examseating.engine.SeatingArrangementEngine.StudentAttribute("grade"),
                new com.examseating.engine.SeatingArrangementEngine.StudentAttribute("division")
        );

        ArrangeRequest requestNormal = new ArrangeRequest(
                ArrangeRequest.StrictnessLevel.NORMAL,
                null, null, null,
                null,
                com.examseating.engine.SeatingArrangementEngine.BenchPairingRule.DIFFERENT_GROUP_ONLY,
                false,
                groupAttrs,
                com.examseating.engine.SeatingArrangementEngine.GroupMatchMode.ALL,
                null, null, true
        );

        SeatingResponse resultNormal = seatingService.arrange(requestNormal);
        assertEquals(30, resultNormal.seated(), "All 30 school students should be seated");
        assertEquals(0, resultNormal.unplaced());

        // Verify bench mates never share the same (grade + division)
        List<SeatingAllocation> allocations = allocationRepository.findAll();
        assertEquals(30, allocations.size());

        Map<Long, List<SeatingAllocation>> byBench = allocations.stream()
                .collect(java.util.stream.Collectors.groupingBy(a -> a.getBench().getId()));

        for (Map.Entry<Long, List<SeatingAllocation>> entry : byBench.entrySet()) {
            List<SeatingAllocation> benchAllocs = entry.getValue();
            if (benchAllocs.size() == 2) {
                Student s1 = benchAllocs.get(0).getStudent();
                Student s2 = benchAllocs.get(1).getStudent();
                String group1 = s1.getAttributes().get("grade") + "-" + s1.getAttributes().get("division");
                String group2 = s2.getAttributes().get("grade") + "-" + s2.getAttributes().get("division");
                assertTrue(!group1.equals(group2),
                        "Dual bench mates must have different classmate groups! Found: " + group1 + " vs " + group2);
            }
        }

        // 6. Test with INTERMEDIATE difficulty
        ArrangeRequest requestIntermediate = new ArrangeRequest(
                ArrangeRequest.StrictnessLevel.INTERMEDIATE,
                null, null, null,
                null,
                com.examseating.engine.SeatingArrangementEngine.BenchPairingRule.DIFFERENT_GROUP_ONLY,
                false,
                groupAttrs,
                com.examseating.engine.SeatingArrangementEngine.GroupMatchMode.ALL,
                null, null, true
        );

        SeatingResponse resultIntermediate = seatingService.arrange(requestIntermediate);
        assertEquals(30, resultIntermediate.seated());

        // 7. Test with HARD (Rigid) difficulty
        ArrangeRequest requestHard = new ArrangeRequest(
                ArrangeRequest.StrictnessLevel.HARD,
                null, null, null,
                null,
                com.examseating.engine.SeatingArrangementEngine.BenchPairingRule.DIFFERENT_GROUP_ONLY,
                false,
                groupAttrs,
                com.examseating.engine.SeatingArrangementEngine.GroupMatchMode.ALL,
                null, null, true
        );

        SeatingResponse resultHard = seatingService.arrange(requestHard);
        assertTrue(resultHard.seated() > 0, "Hard mode arranges students within available capacity");
    }
}
