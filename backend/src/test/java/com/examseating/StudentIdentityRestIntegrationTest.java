package com.examseating;

import com.examseating.engine.SeatingArrangementEngine.GroupMatchMode;
import com.examseating.entity.SeatingRound;
import com.examseating.entity.Student;
import com.examseating.repository.CohortRuleRepository;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.repository.SeatingRoundRepository;
import com.examseating.repository.StudentIdentitySchemeRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.tenancy.TenantSupport;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * REST integration tests for dynamic student identification, import preview,
 * and dynamic seating round retrieval.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class StudentIdentityRestIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private StudentRepository studentRepository;
    @Autowired private StudentIdentitySchemeRepository schemeRepository;
    @Autowired private CohortRuleRepository cohortRuleRepository;
    @Autowired private SeatingRoundRepository roundRepository;
    @Autowired private SeatingAllocationRepository allocationRepository;
    @Autowired private ExamRoomRepository examRoomRepository;
    @Autowired private TenantSupport tenantSupport;

    @BeforeEach
    void clean() {
        allocationRepository.deleteAll();
        roundRepository.deleteAll();
        studentRepository.deleteAll();
        cohortRuleRepository.deleteAll();
        schemeRepository.deleteAll();
        examRoomRepository.deleteAll();
    }

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

    @Test
    @DisplayName("The identity scheme can be read, written and cleared")
    void theIdentitySchemeIsManagedOverHttp() throws Exception {
        mockMvc.perform(get("/api/v1/students/identity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes").isArray());

        mockMvc.perform(put("/api/v1/students/identity")
                        .contentType("application/json")
                        .content("{\"label\":\"Application id + batch\",\"attributes\":[\"applicationid\",\"batch\"],\"classmateAttributes\":[\"department\"],\"scopeAttribute\":\"session\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.attributes[0]").value("applicationid"))
                .andExpect(jsonPath("$.attributes[1]").value("batch"))
                .andExpect(jsonPath("$.classmateAttributes[0]").value("department"))
                .andExpect(jsonPath("$.scopeAttribute").value("session"))
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

    @Test
    @DisplayName("The seating round that produced the plan can be read back")
    void theSeatingRoundIsReadable() throws Exception {
        mockMvc.perform(get("/api/v1/seating-rounds/current"))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/seating-rounds"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        SeatingRound round = new SeatingRound();
        round.setInstitutionId(tenantSupport.currentId());
        round.setScopeKey("CS301");
        round.setScopeAttribute("papercode");
        round.setStrictness("HARD");
        round.setHorizontalGap(1);
        round.setVerticalGap(1);
        round.setDiagonalGap(1);
        round.setSeatedCount(42);
        round.setGroupAttributes("department,semester");
        round.setGroupMatchMode(GroupMatchMode.ALL);
        round.setCreatedAt(Instant.now());
        round.setUpdatedAt(Instant.now());
        roundRepository.save(round);

        mockMvc.perform(get("/api/v1/seating-rounds/current"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKey").value("CS301"))
                .andExpect(jsonPath("$.scopeAttribute").value("papercode"))
                .andExpect(jsonPath("$.strictness").value("HARD"))
                .andExpect(jsonPath("$.seatedCount").value(42))
                .andExpect(jsonPath("$.horizontalGap").value(1));

        mockMvc.perform(get("/api/v1/seating-rounds/current").param("scopeKey", "CS301"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopeKey").value("CS301"))
                .andExpect(jsonPath("$.groupAttributes[0]").value("department"));
    }
}
