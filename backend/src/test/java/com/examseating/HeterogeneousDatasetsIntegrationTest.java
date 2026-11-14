package com.examseating;

import com.examseating.dto.ArrangeRequest;
import com.examseating.dto.ArrangeRequest.StrictnessLevel;
import com.examseating.dto.ImportResult;
import com.examseating.dto.SeatingResponse;
import com.examseating.engine.SeatingArrangementEngine.GroupMatchMode;
import com.examseating.engine.SeatingArrangementEngine.StudentAttribute;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Subscription;
import com.examseating.repository.BenchRepository;
import com.examseating.repository.CohortRuleRepository;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end integration test suite verifying heterogeneous dataset imports
 * (universities, medical colleges, polytechnics, backlog/arrear sessions,
 * national entrance exams), mixed room geometries, and dynamic seating.
 */
@SpringBootTest
public class HeterogeneousDatasetsIntegrationTest {

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
    @Autowired private CohortRuleRepository cohortRuleRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private TenantSupport tenantSupport;

    @BeforeEach
    void setupDatabaseAndSubscription() {
        allocationRepository.deleteAllInBatch();
        roundRepository.deleteAllInBatch();
        benchRepository.deleteAllInBatch();
        examRoomRepository.deleteAllInBatch();
        studentRepository.deleteAllInBatch();
        cohortRuleRepository.deleteAllInBatch();
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

    @Test
    @DisplayName("Verify import of all 6 heterogeneous Excel files (Rooms & Students)")
    void testAllHeterogeneousFilesImportSuccessfully() throws Exception {
        File roomsFile = new File("sample_excel_files/rooms_heterogeneous_complex.xlsx");
        assertTrue(roomsFile.exists());
        try (InputStream in = new FileInputStream(roomsFile)) {
            ImportResult res = excelImportService.importRooms(in);
            assertEquals(7, res.imported());
            assertEquals(0, res.errors().size(), "Room errors: " + res.errors());
        }
        int totalCapacity = examRoomRepository.findAll().stream().mapToInt(ExamRoom::getCapacity).sum();
        assertEquals(494, totalCapacity);

        Object[][] datasets = {
                {"sample_excel_files/students_multidisciplinary_university_250.xlsx", 250},
                {"sample_excel_files/students_medical_health_sciences_150.xlsx", 150},
                {"sample_excel_files/students_backlog_arrear_supplementary_100.xlsx", 100},
                {"sample_excel_files/students_polytechnic_vocational_160.xlsx", 160},
                {"sample_excel_files/students_national_entrance_alphanumeric_200.xlsx", 200}
        };

        for (Object[] ds : datasets) {
            studentRepository.deleteAllInBatch();
            cohortRuleRepository.deleteAllInBatch();
        schemeRepository.deleteAllInBatch();
            File f = new File((String) ds[0]);
            int expected = (int) ds[1];
            assertTrue(f.exists(), "Missing sample file: " + f);
            try (InputStream in = new FileInputStream(f)) {
                ImportResult res = excelImportService.importStudents(in);
                assertEquals(expected, res.imported(), f.getName() + " errors: " + res.errors());
                assertEquals(0, res.errors().size());
            }
            assertEquals(expected, studentRepository.count());
        }
    }

    @Test
    @DisplayName("Solve seating for 250 Multidisciplinary students across 7 complex rooms")
    void testMultidisciplinarySeatingInHeterogeneousRooms() throws Exception {
        try (InputStream in = new FileInputStream("sample_excel_files/rooms_heterogeneous_complex.xlsx")) {
            excelImportService.importRooms(in);
        }
        try (InputStream in = new FileInputStream("sample_excel_files/students_multidisciplinary_university_250.xlsx")) {
            excelImportService.importStudents(in);
        }

        ArrangeRequest request = new ArrangeRequest(
                StrictnessLevel.NORMAL,
                null, null, null,
                List.of(StudentAttribute.of("department")),
                GroupMatchMode.ALL,
                null, null, null, true);

        SeatingResponse response = seatingService.arrange(request);
        assertEquals(250, response.seated());
        assertEquals(0, response.unplaced());
        assertTrue(response.solved());

        List<SeatingAllocation> allocations = allocationRepository.findAllWithDetails(null);
        assertEquals(250, allocations.size());

        for (int i = 0; i < allocations.size(); i++) {
            for (int j = i + 1; j < allocations.size(); j++) {
                SeatingAllocation a1 = allocations.get(i);
                SeatingAllocation a2 = allocations.get(j);
                if (Objects.equals(a1.getBench().getId(), a2.getBench().getId())) {
                    assertNotEquals(
                            a1.getStudent().getAttributes().get("department").trim().toLowerCase(),
                            a2.getStudent().getAttributes().get("department").trim().toLowerCase(),
                            "Bench-mates must have different departments");
                }
            }
        }

        byte[] excelBytes = excelExportService.export(null);
        assertNotNull(excelBytes);
        assertTrue(excelBytes.length > 5000);

        byte[] pdfBytes = pdfExportService.doorNotices(null);
        assertNotNull(pdfBytes);
        assertEquals('%', (char) pdfBytes[0]);
        assertEquals('P', (char) pdfBytes[1]);
        assertEquals('D', (char) pdfBytes[2]);
        assertEquals('F', (char) pdfBytes[3]);
    }

    @Test
    @DisplayName("Solve seating for 150 Medical & Healthcare candidates using dynamic 'stream' attribute")
    void testMedicalHealthSciencesSeating() throws Exception {
        try (InputStream in = new FileInputStream("sample_excel_files/rooms_heterogeneous_complex.xlsx")) {
            excelImportService.importRooms(in);
        }
        try (InputStream in = new FileInputStream("sample_excel_files/students_medical_health_sciences_150.xlsx")) {
            excelImportService.importStudents(in);
        }

        ArrangeRequest request = new ArrangeRequest(
                StrictnessLevel.NORMAL,
                null, null, null,
                List.of(StudentAttribute.of("stream")),
                GroupMatchMode.ALL,
                null, null, null, true);

        SeatingResponse response = seatingService.arrange(request);
        assertEquals(150, response.seated());
        assertEquals(0, response.unplaced());
        assertTrue(response.solved());
    }

    @Test
    @DisplayName("Filter and arrange specific Arrear paper (MATH101-ARREAR) using dynamic scopeAttribute")
    void testBacklogArrearIncrementalScopeRounds() throws Exception {
        try (InputStream in = new FileInputStream("sample_excel_files/rooms_heterogeneous_complex.xlsx")) {
            excelImportService.importRooms(in);
        }
        try (InputStream in = new FileInputStream("sample_excel_files/students_backlog_arrear_supplementary_100.xlsx")) {
            excelImportService.importStudents(in);
        }

        ArrangeRequest request = new ArrangeRequest(
                StrictnessLevel.NORMAL,
                null, null, null,
                List.of(StudentAttribute.of("branch")),
                GroupMatchMode.ALL,
                "subjectcode", "MATH101-ARREAR",
                null, true);

        SeatingResponse response = seatingService.arrange(request);
        assertEquals(18, response.seated());
        assertEquals(0, response.unplaced());
        assertTrue(response.solved());
    }

    @Test
    @DisplayName("Solve seating for 200 National Entrance candidates with alphanumeric regional IDs")
    void testNationalEntranceAlphanumericSeating() throws Exception {
        try (InputStream in = new FileInputStream("sample_excel_files/rooms_heterogeneous_complex.xlsx")) {
            excelImportService.importRooms(in);
        }
        try (InputStream in = new FileInputStream("sample_excel_files/students_national_entrance_alphanumeric_200.xlsx")) {
            excelImportService.importStudents(in);
        }

        ArrangeRequest request = new ArrangeRequest(
                StrictnessLevel.NORMAL,
                null, null, null,
                List.of(StudentAttribute.of("stream")),
                GroupMatchMode.ALL,
                null, null, null, true);

        SeatingResponse response = seatingService.arrange(request);
        assertEquals(200, response.seated());
        assertEquals(0, response.unplaced());
        assertTrue(response.solved());
    }
}
