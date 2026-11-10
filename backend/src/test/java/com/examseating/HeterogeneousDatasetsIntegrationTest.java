package com.examseating;

import com.examseating.dto.ArrangeRequest;
import com.examseating.dto.ArrangeRequest.StrictnessLevel;
import com.examseating.dto.ImportResult;
import com.examseating.dto.SeatingResponse;
import com.examseating.engine.SeatingArrangementEngine.BenchPairingRule;
import com.examseating.engine.SeatingArrangementEngine.ConflictScope;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Subscription;
import com.examseating.repository.BenchRepository;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.SeatingAllocationRepository;
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
 * End-to-end integration test suite verifying heterogeneous dataset imports,
 * complex mixed room geometries, and multi-disciplinary seating arrangements.
 */
@SpringBootTest
public class HeterogeneousDatasetsIntegrationTest {

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
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private TenantSupport tenantSupport;

    @BeforeEach
    void setupDatabaseAndSubscription() {
        allocationRepository.deleteAllInBatch();
        benchRepository.deleteAllInBatch();
        examRoomRepository.deleteAllInBatch();
        studentRepository.deleteAllInBatch();

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
        // 1. Complex heterogeneous rooms (7 rooms, 494 seats)
        File roomsFile = new File("sample_excel_files/rooms_heterogeneous_complex.xlsx");
        assertTrue(roomsFile.exists(), "rooms_heterogeneous_complex.xlsx must exist");
        try (InputStream in = new FileInputStream(roomsFile)) {
            ImportResult res = excelImportService.importRooms(in);
            assertEquals(7, res.imported(), "Must import 7 heterogeneous halls");
            assertEquals(0, res.errors().size(), "Room errors: " + res.errors());
        }
        int totalCapacity = examRoomRepository.findAll().stream().mapToInt(ExamRoom::getCapacity).sum();
        assertEquals(494, totalCapacity, "Total capacity of heterogeneous complex must be 494 seats");

        // 2. Multidisciplinary University (250 students)
        File uniFile = new File("sample_excel_files/students_multidisciplinary_university_250.xlsx");
        assertTrue(uniFile.exists());
        try (InputStream in = new FileInputStream(uniFile)) {
            ImportResult res = excelImportService.importStudents(in);
            assertEquals(250, res.imported(), "Multidisciplinary roster must import 250 students");
            assertEquals(0, res.errors().size(), "Student errors: " + res.errors());
        }
        assertEquals(250, studentRepository.count());

        // 3. Medical & Health Sciences (150 students)
        studentRepository.deleteAllInBatch();
        File medFile = new File("sample_excel_files/students_medical_health_sciences_150.xlsx");
        assertTrue(medFile.exists());
        try (InputStream in = new FileInputStream(medFile)) {
            ImportResult res = excelImportService.importStudents(in);
            assertEquals(150, res.imported(), "Medical roster must import 150 students");
            assertEquals(0, res.errors().size(), "Medical errors: " + res.errors());
        }
        assertEquals(150, studentRepository.count());

        // 4. Backlog / Arrear Re-sit (100 students)
        studentRepository.deleteAllInBatch();
        File arrearFile = new File("sample_excel_files/students_backlog_arrear_supplementary_100.xlsx");
        assertTrue(arrearFile.exists());
        try (InputStream in = new FileInputStream(arrearFile)) {
            ImportResult res = excelImportService.importStudents(in);
            assertEquals(100, res.imported(), "Arrear roster must import 100 students");
            assertEquals(0, res.errors().size(), "Arrear errors: " + res.errors());
        }
        assertEquals(100, studentRepository.count());

        // 5. Polytechnic Vocational (160 students)
        studentRepository.deleteAllInBatch();
        File polyFile = new File("sample_excel_files/students_polytechnic_vocational_160.xlsx");
        assertTrue(polyFile.exists());
        try (InputStream in = new FileInputStream(polyFile)) {
            ImportResult res = excelImportService.importStudents(in);
            assertEquals(160, res.imported(), "Polytechnic roster must import 160 students");
            assertEquals(0, res.errors().size(), "Polytechnic errors: " + res.errors());
        }
        assertEquals(160, studentRepository.count());

        // 6. National Entrance Alphanumeric (200 students)
        studentRepository.deleteAllInBatch();
        File natFile = new File("sample_excel_files/students_national_entrance_alphanumeric_200.xlsx");
        assertTrue(natFile.exists());
        try (InputStream in = new FileInputStream(natFile)) {
            ImportResult res = excelImportService.importStudents(in);
            assertEquals(200, res.imported(), "National entrance roster must import 200 students");
            assertEquals(0, res.errors().size(), "National entrance errors: " + res.errors());
        }
        assertEquals(200, studentRepository.count());
    }

    @Test
    @DisplayName("Solve seating for 250 Multidisciplinary students across 7 complex rooms")
    void testMultidisciplinarySeatingInHeterogeneousRooms() throws Exception {
        // Import rooms & students
        try (InputStream in = new FileInputStream("sample_excel_files/rooms_heterogeneous_complex.xlsx")) {
            excelImportService.importRooms(in);
        }
        try (InputStream in = new FileInputStream("sample_excel_files/students_multidisciplinary_university_250.xlsx")) {
            excelImportService.importStudents(in);
        }

        ArrangeRequest request = new ArrangeRequest(
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

        SeatingResponse response = seatingService.arrange(request);
        assertEquals(250, response.seated(), "All 250 students must be seated");
        assertEquals(0, response.unplaced());
        assertTrue(response.solved(), "Seating plan must be marked as solved");

        // Verify bench pairing rules in database
        List<SeatingAllocation> allocations = allocationRepository.findAllWithDetails(null);
        assertEquals(250, allocations.size());

        for (int i = 0; i < allocations.size(); i++) {
            for (int j = i + 1; j < allocations.size(); j++) {
                SeatingAllocation a1 = allocations.get(i);
                SeatingAllocation a2 = allocations.get(j);
                if (Objects.equals(a1.getBench().getId(), a2.getBench().getId())) {
                    assertNotEquals(
                            a1.getStudent().getDepartment().trim().toLowerCase(),
                            a2.getStudent().getDepartment().trim().toLowerCase(),
                            "Bench-mates must have different departments"
                    );
                }
            }
        }

        // Verify Excel export
        byte[] excelBytes = excelExportService.export(null);
        assertNotNull(excelBytes);
        assertTrue(excelBytes.length > 5000, "Excel export must contain master list and 7 hall maps");

        // Verify PDF door notices
        byte[] pdfBytes = pdfExportService.doorNotices(null);
        assertNotNull(pdfBytes);
        assertEquals('%', (char) pdfBytes[0]);
        assertEquals('P', (char) pdfBytes[1]);
        assertEquals('D', (char) pdfBytes[2]);
        assertEquals('F', (char) pdfBytes[3]);
    }

    @Test
    @DisplayName("Solve seating for 150 Medical & Healthcare candidates")
    void testMedicalHealthSciencesSeating() throws Exception {
        try (InputStream in = new FileInputStream("sample_excel_files/rooms_heterogeneous_complex.xlsx")) {
            excelImportService.importRooms(in);
        }
        try (InputStream in = new FileInputStream("sample_excel_files/students_medical_health_sciences_150.xlsx")) {
            excelImportService.importStudents(in);
        }

        ArrangeRequest request = new ArrangeRequest(
                StrictnessLevel.BASIC,
                1, 0, 0,
                ConflictScope.DEPARTMENT,
                BenchPairingRule.DIFFERENT_DEPARTMENT_ONLY,
                true,
                null, null,
                null,
                null,
                true
        );

        SeatingResponse response = seatingService.arrange(request);
        assertEquals(150, response.seated(), "All 150 medical candidates must be seated");
        assertEquals(0, response.unplaced());
        assertTrue(response.solved());
    }

    @Test
    @DisplayName("Filter and arrange specific Arrear paper (MATH101-ARREAR) from repeat exam dataset")
    void testBacklogArrearIncrementalSubjectRounds() throws Exception {
        try (InputStream in = new FileInputStream("sample_excel_files/rooms_heterogeneous_complex.xlsx")) {
            excelImportService.importRooms(in);
        }
        try (InputStream in = new FileInputStream("sample_excel_files/students_backlog_arrear_supplementary_100.xlsx")) {
            excelImportService.importStudents(in);
        }

        ArrangeRequest request = new ArrangeRequest(
                StrictnessLevel.BASIC,
                1, 0, 0,
                ConflictScope.DEPARTMENT,
                BenchPairingRule.DIFFERENT_DEPARTMENT_ONLY,
                true,
                null, null,
                "MATH101-ARREAR",
                null,
                true
        );

        SeatingResponse response = seatingService.arrange(request);
        assertEquals(18, response.seated(), "All 18 MATH101-ARREAR students must be seated");
        assertEquals(0, response.unplaced());
        assertTrue(response.solved());
    }

    @Test
    @DisplayName("Solve seating for 200 National Entrance candidates with alphanumeric regional roll numbers")
    void testNationalEntranceAlphanumericSeating() throws Exception {
        try (InputStream in = new FileInputStream("sample_excel_files/rooms_heterogeneous_complex.xlsx")) {
            excelImportService.importRooms(in);
        }
        try (InputStream in = new FileInputStream("sample_excel_files/students_national_entrance_alphanumeric_200.xlsx")) {
            excelImportService.importStudents(in);
        }

        ArrangeRequest request = new ArrangeRequest(
                StrictnessLevel.BASIC,
                1, 0, 0,
                ConflictScope.DEPARTMENT,
                BenchPairingRule.DIFFERENT_DEPARTMENT_ONLY,
                true,
                null, null,
                null,
                null,
                true
        );

        SeatingResponse response = seatingService.arrange(request);
        assertEquals(200, response.seated(), "All 200 entrance candidates must be seated");
        assertEquals(0, response.unplaced());
        assertTrue(response.solved());
    }
}
