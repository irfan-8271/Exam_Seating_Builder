package com.examseating;

import com.examseating.entity.ExamRoom;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.repository.SeatingRoundRepository;
import com.examseating.repository.StudentIdentitySchemeRepository;
import com.examseating.repository.StudentRepository;
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

import java.io.ByteArrayOutputStream;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class CategorySelectionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ExamRoomRepository examRoomRepository;
    @Autowired private SeatingAllocationRepository allocationRepository;
    @Autowired private SeatingRoundRepository roundRepository;
    @Autowired private StudentIdentitySchemeRepository schemeRepository;

    @BeforeEach
    void cleanDatabase() {
        allocationRepository.deleteAll();
        roundRepository.deleteAll();
        studentRepository.deleteAll();
        examRoomRepository.deleteAll();
        schemeRepository.deleteAll();
    }

    @Test
    @DisplayName("Multi-category student selection + hall subset selection + analyze & current unplaced accuracy")
    void multiCategoryAndHallCheckboxSelectionEndToEnd() throws Exception {
        byte[] workbookBytes = buildCampusRosterWorkbook();
        MockMultipartFile file = new MockMultipartFile(
                "file", "campus_roster.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                workbookBytes);

        // Upload 18 students: 6 BCA (Sem 1), 6 BBA (Sem 1), 4 BCom (Sem 3), 2 Unspecified course
        mockMvc.perform(multipart("/api/v1/students/imports")
                        .file(file)
                        .param("identityAttributes", "Roll No")
                        .param("classmateAttributes", "Course,Semester"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(18));

        ExamRoom hallA = examRoomRepository.save(ExamRoom.builder()
                .hallName("Block A - 101")
                .rows(3)
                .columns(2)
                .benchType(ExamRoom.BenchType.DUAL_SEATER) // 12 seats
                .build());
        ExamRoom hallB = examRoomRepository.save(ExamRoom.builder()
                .hallName("Block B - 202")
                .rows(3)
                .columns(2)
                .benchType(ExamRoom.BenchType.DUAL_SEATER) // 12 seats
                .build());

        // 1. Analyze ONLY BCA + BBA (12 students) in ONLY Hall A (12 seats): exact fit!
        String analyzePayload = """
                {
                  "strictness": "NORMAL",
                  "groupAttributes": ["course", "semester"],
                  "groupMatchMode": "ALL",
                  "roomIds": [%d],
                  "clearExisting": true,
                  "filterAttribute": "course",
                  "filterValues": ["BCA", "BBA"]
                }
                """.formatted(hallA.getId());

        mockMvc.perform(post("/api/v1/seating-plans/analyze")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(analyzePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(true))
                .andExpect(jsonPath("$.totalStudents").value(12))
                .andExpect(jsonPath("$.availableSeats").value(12))
                .andExpect(jsonPath("$.emptyBufferSeatsRequired").value(0));

        // 2. Arrange ONLY BCA + BBA in Hall A
        mockMvc.perform(post("/api/v1/seating-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(analyzePayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.solved").value(true))
                .andExpect(jsonPath("$.seated").value(12))
                .andExpect(jsonPath("$.totalSlots").value(12));

        // 3. Verify /current shows 12 seated and unplaced=0 (unselected BCom/Unspecified are NOT counted as unplaced)
        mockMvc.perform(get("/api/v1/seating-plans/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seated").value(12))
                .andExpect(jsonPath("$.unplaced").value(0))
                .andExpect(jsonPath("$.allocations", hasSize(12)));

        // Verify /seating-rounds records targetCount and filterValues
        mockMvc.perform(get("/api/v1/seating-rounds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].targetCount").value(12))
                .andExpect(jsonPath("$[0].filterAttribute").value("course"))
                .andExpect(jsonPath("$[0].filterValues", containsInAnyOrder("BCA", "BBA")));

        // 4. Incremental mode (clearExisting=false): seat remaining BCom + Unspecified (6 students) into Hall B
        //    without wiping Hall A's 12 allocations!
        String incrementalPayload = """
                {
                  "strictness": "NORMAL",
                  "groupAttributes": ["course", "semester"],
                  "groupMatchMode": "ALL",
                  "roomIds": [%d],
                  "clearExisting": false,
                  "filterAttribute": "course",
                  "filterValues": ["BCom", "Unspecified"]
                }
                """.formatted(hallB.getId());

        mockMvc.perform(post("/api/v1/seating-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(incrementalPayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.solved").value(true))
                .andExpect(jsonPath("$.seated").value(6));

        assertEquals(18L, allocationRepository.count(),
                "Hall A (12) + Hall B (6) allocations must both persist in incremental mode");

        // 5. Composite cohort filtering ("course+semester") with clearExisting=true in Hall B ONLY:
        //    must wipe stale allocations in unselected Hall A as well and seat only the 10 matching students!
        String compositeReplacePayload = """
                {
                  "strictness": "NORMAL",
                  "groupAttributes": ["course", "semester"],
                  "groupMatchMode": "ALL",
                  "roomIds": [%d],
                  "clearExisting": true,
                  "filterAttribute": "Course + Semester",
                  "filterValues": ["BCA · Sem 1", "BCom · Sem 3"]
                }
                """.formatted(hallB.getId());

        mockMvc.perform(post("/api/v1/seating-plans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(compositeReplacePayload))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.solved").value(true))
                .andExpect(jsonPath("$.seated").value(10));

        assertEquals(10L, allocationRepository.count(),
                "clearExisting=true must clear prior Hall A & Hall B allocations and keep only the 10 newly seated students");

        mockMvc.perform(get("/api/v1/seating-plans/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.seated").value(10))
                .andExpect(jsonPath("$.unplaced").value(0))
                .andExpect(jsonPath("$.allocations[*].hallName", hasItem("Block B - 202")));
    }

    private byte[] buildCampusRosterWorkbook() throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Students");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Roll No");
            header.createCell(1).setCellValue("Name");
            header.createCell(2).setCellValue("Course");
            header.createCell(3).setCellValue("Semester");

            int rowIdx = 1;
            for (int i = 1; i <= 6; i++) {
                Row r = sheet.createRow(rowIdx++);
                r.createCell(0).setCellValue("BCA-" + i);
                r.createCell(1).setCellValue("BCA Student " + i);
                r.createCell(2).setCellValue("BCA");
                r.createCell(3).setCellValue("Sem 1");
            }
            for (int i = 1; i <= 6; i++) {
                Row r = sheet.createRow(rowIdx++);
                r.createCell(0).setCellValue("BBA-" + i);
                r.createCell(1).setCellValue("BBA Student " + i);
                r.createCell(2).setCellValue("BBA");
                r.createCell(3).setCellValue("Sem 1");
            }
            for (int i = 1; i <= 4; i++) {
                Row r = sheet.createRow(rowIdx++);
                r.createCell(0).setCellValue("BCOM-" + i);
                r.createCell(1).setCellValue("BCom Student " + i);
                r.createCell(2).setCellValue("BCom");
                r.createCell(3).setCellValue("Sem 3");
            }
            for (int i = 1; i <= 2; i++) {
                Row r = sheet.createRow(rowIdx++);
                r.createCell(0).setCellValue("OPEN-" + i);
                r.createCell(1).setCellValue("Open Elective Student " + i);
                r.createCell(2).setCellValue("");
                r.createCell(3).setCellValue("Sem 5");
            }
            wb.write(out);
            return out.toByteArray();
        }
    }
}
