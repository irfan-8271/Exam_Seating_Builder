package com.examseating;

import com.examseating.entity.Student;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.repository.SeatingRoundRepository;
import com.examseating.repository.StudentIdentitySchemeRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.service.StudentIdentityService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The REST surface of dynamic student identification.
 *
 * <p>Three things must work over HTTP: the institution can declare how it
 * identifies its students, an uploaded roster can be analysed before it is
 * imported, and the seating round that produced the current plan can be read
 * back — with the rules it was solved with.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
public class StudentIdentityRestIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private StudentIdentitySchemeRepository schemeRepository;

    @Autowired
    private StudentIdentityService identityService;

    @Autowired
    private SeatingRoundRepository roundRepository;

    @Autowired
    private SeatingAllocationRepository allocationRepository;

    @Autowired
    private ExamRoomRepository examRoomRepository;

    @Autowired
    private com.examseating.tenancy.TenantSupport tenantSupport;

    @BeforeEach
    void clean() {
        allocationRepository.deleteAll();
        roundRepository.deleteAll();
        studentRepository.deleteAll();
        schemeRepository.deleteAll();
        examRoomRepository.deleteAll();
    }

    /** A roster that identifies students by an unknown column, not by a roll number. */
    private static byte[] roster() throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Roster");
            Row header = sheet.createRow(0);
            header.createCell(0).setCellValue("Student Name");
            header.createCell(1).setCellValue("Application Id");
            header.createCell(2).setCellValue("Department");
            for (int i = 0; i < 3; i++) {
                Row row = sheet.createRow(i + 1);
                row.createCell(0).setCellValue("Student " + i);
                row.createCell(1).setCellValue("APP-00" + i);
                row.createCell(2).setCellValue("CSE");
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static MockMultipartFile rosterFile() throws Exception {
        return new MockMultipartFile("file", "roster.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", roster());
    }

    // ------------------------------------------------------------------
    // The identity scheme
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The identity scheme can be read, written and cleared")
    void theIdentitySchemeIsManagedOverHttp() throws Exception {
        mockMvc.perform(get("/api/v1/students/identity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes").isArray());

        mockMvc.perform(put("/api/v1/students/identity")
                        .contentType("application/json")
                        .content("{\"label\":\"Application id + batch\",\"attributes\":[\"applicationid\",\"batch\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes[0]").value("applicationid"))
                .andExpect(jsonPath("$.attributes[1]").value("batch"))
                .andExpect(jsonPath("$.label").value("Application id + batch"));

        mockMvc.perform(get("/api/v1/students/identity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes[0]").value("applicationid"));

        mockMvc.perform(delete("/api/v1/students/identity"))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/students/identity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes").isEmpty());
    }

    @Test
    @DisplayName("An identity scheme needs at least one attribute")
    void anEmptyIdentitySchemeIsRefused() throws Exception {
        mockMvc.perform(put("/api/v1/students/identity")
                        .contentType("application/json")
                        .content("{\"attributes\":[]}"))
                .andExpect(status().is4xxClientError());
    }

    // ------------------------------------------------------------------
    // The import preview
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The preview lists every column and suggests how students can be identified")
    void thePreviewAnalysesTheFile() throws Exception {
        mockMvc.perform(multipart("/api/v1/students/imports/preview").file(rosterFile()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(3))
                .andExpect(jsonPath("$.columns.length()").value(3))
                .andExpect(jsonPath("$.columns[?(@.key == 'applicationid')].title")
                        .value("Application Id"))
                .andExpect(jsonPath("$.columns[?(@.key == 'applicationid')].unique").value(true))
                .andExpect(jsonPath("$.suggestedIdentities[0].attributes[0]").value("applicationid"));
    }

    @Test
    @DisplayName("A roster without a roll number can be imported with the chosen attribute")
    void aRosterWithoutARollNumberIsImported() throws Exception {
        mockMvc.perform(multipart("/api/v1/students/imports")
                        .file(rosterFile())
                        .param("identityAttributes", "applicationid"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.imported").value(3))
                .andExpect(jsonPath("$.errors").isEmpty());

        assertEquals(3, studentRepository.count());
        Student first = studentRepository.findAll().get(0);
        assertEquals("APP-000", first.getIdentityKey());
    }

    // ------------------------------------------------------------------
    // The seating round
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The seating round that produced the plan can be read back")
    void theSeatingRoundIsReadable() throws Exception {
        // No round yet — the endpoint says so rather than inventing one.
        mockMvc.perform(get("/api/v1/seating-rounds/current"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/seating-rounds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        // A round that was arranged is readable, with the rules it used.
        com.examseating.entity.SeatingRound round = new com.examseating.entity.SeatingRound();
        round.setInstitutionId(tenantSupport.currentId());
        round.setSubjectKey("CS301");
        round.setHorizontalGap(1);
        round.setVerticalGap(1);
        round.setDiagonalGap(1);
        round.setSeatedCount(42);
        round.setConflictScope(com.examseating.engine.SeatingArrangementEngine.ConflictScope.DEPARTMENT_AND_YEAR);
        round.setBenchPairingRule(com.examseating.engine.SeatingArrangementEngine.BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR);
        round.setGroupMatchMode(com.examseating.engine.SeatingArrangementEngine.GroupMatchMode.ALL);
        round.setHorizontalDeptIsolation(true);
        round.setCreatedAt(java.time.Instant.now());
        round.setUpdatedAt(java.time.Instant.now());
        roundRepository.save(round);

        mockMvc.perform(get("/api/v1/seating-rounds/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectKey").value("CS301"))
                .andExpect(jsonPath("$.seatedCount").value(42))
                .andExpect(jsonPath("$.horizontalGap").value(1));

        mockMvc.perform(get("/api/v1/seating-rounds/current").param("subjectCode", "CS301"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subjectCode").value("CS301"));
    }
}
