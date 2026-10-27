package com.examseating.service;

import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Student;
import com.examseating.repository.SeatingAllocationRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.FileOutputStream;
import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PdfExportServiceTest {

    @Mock
    private SeatingAllocationRepository allocationRepository;

    @InjectMocks
    private PdfExportService pdfExportService;

    @Test
    void testDoorNoticesDualSeater() throws Exception {
        ExamRoom room = ExamRoom.builder()
                .id(1L)
                .hallName("Auditorium Hall A")
                .rows(3)
                .columns(4)
                .benchType(ExamRoom.BenchType.DUAL_SEATER)
                .build();

        List<SeatingAllocation> allocations = new ArrayList<>();
        int id = 1;
        String[] depts = {"Computer Science", "Electronics", "Mechanical", "Civil"};

        // Create allocations for 3 rows x 4 cols dual benches (Slot 1 and Slot 2)
        for (int r = 1; r <= 3; r++) {
            for (int c = 1; c <= 4; c++) {
                Bench bench = Bench.builder()
                        .room(room)
                        .rowIndex(r)
                        .colIndex(c)
                        .benchType(ExamRoom.BenchType.DUAL_SEATER)
                        .build();

                Student s1 = Student.builder()
                        .id((long) id++)
                        .rollNo("10100" + id)
                        .name("Student " + id)
                        .department(depts[(r + c) % 4])
                        .yearSession("2024")
                        .subjectCode("CS101")
                        .build();

                Student s2 = Student.builder()
                        .id((long) id++)
                        .rollNo("20200" + id)
                        .name("Student " + id)
                        .department(depts[(r + c + 1) % 4])
                        .yearSession("2025")
                        .subjectCode("CS101")
                        .build();

                allocations.add(SeatingAllocation.builder().bench(bench).student(s1).slotNumber(1).build());
                allocations.add(SeatingAllocation.builder().bench(bench).student(s2).slotNumber(2).build());
            }
        }

        when(allocationRepository.findAllWithDetails(null)).thenReturn(allocations);

        byte[] pdfBytes = pdfExportService.doorNotices(null);
        assertNotNull(pdfBytes, "PDF byte array must not be null");
        assertTrue(pdfBytes.length > 500, "PDF must have non-trivial size");

        // Verify PDF Magic Bytes "%PDF-"
        assertEquals('%', (char) pdfBytes[0]);
        assertEquals('P', (char) pdfBytes[1]);
        assertEquals('D', (char) pdfBytes[2]);
        assertEquals('F', (char) pdfBytes[3]);

        // Save to target for local inspection
        File demoFile = new File("target/door-notices-dual-demo.pdf");
        demoFile.getParentFile().mkdirs();
        try (FileOutputStream fos = new FileOutputStream(demoFile)) {
            fos.write(pdfBytes);
        }
        assertTrue(demoFile.exists(), "Demo PDF file should be generated successfully");
    }

    @Test
    void testDoorNoticesSingleSeater() throws Exception {
        ExamRoom room = ExamRoom.builder()
                .id(2L)
                .hallName("Seminar Room 202")
                .rows(3)
                .columns(3)
                .benchType(ExamRoom.BenchType.SINGLE_SEATER)
                .build();

        List<SeatingAllocation> allocations = new ArrayList<>();
        int id = 1;
        String[] depts = {"Computer Science", "Electronics", "Mechanical"};

        // 3x3 single-seater hall
        for (int r = 1; r <= 3; r++) {
            for (int c = 1; c <= 3; c++) {
                if (r == 3 && c == 3) continue; // leave 1 seat vacant to test vacant desk rendering

                Bench bench = Bench.builder()
                        .room(room)
                        .rowIndex(r)
                        .colIndex(c)
                        .benchType(ExamRoom.BenchType.SINGLE_SEATER)
                        .build();

                Student s = Student.builder()
                        .id((long) id++)
                        .rollNo("30300" + id)
                        .name("Single Student " + id)
                        .department(depts[(r + c) % 3])
                        .yearSession("2024")
                        .subjectCode("ENGG201")
                        .build();

                allocations.add(SeatingAllocation.builder().bench(bench).student(s).slotNumber(1).build());
            }
        }

        when(allocationRepository.findAllWithDetails(null)).thenReturn(allocations);

        byte[] pdfBytes = pdfExportService.doorNotices(null);
        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 500);

        File demoFile = new File("target/door-notices-single-demo.pdf");
        try (FileOutputStream fos = new FileOutputStream(demoFile)) {
            fos.write(pdfBytes);
        }
        assertTrue(demoFile.exists());
    }

    @Test
    void testInvigilatorSheets() throws Exception {
        ExamRoom room = ExamRoom.builder()
                .id(1L)
                .hallName("Auditorium Hall A")
                .rows(2)
                .columns(2)
                .benchType(ExamRoom.BenchType.DUAL_SEATER)
                .build();

        Bench b1 = Bench.builder().room(room).rowIndex(1).colIndex(1).benchType(ExamRoom.BenchType.DUAL_SEATER).build();
        Student s1 = Student.builder().id(1L).rollNo("101001").name("Alice").department("CSE").yearSession("2024").subjectCode("CS101").build();

        List<SeatingAllocation> allocations = List.of(
                SeatingAllocation.builder().bench(b1).student(s1).slotNumber(1).build()
        );

        when(allocationRepository.findAllWithDetails(null)).thenReturn(allocations);

        byte[] pdfBytes = pdfExportService.invigilatorSheets(null);
        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 500);
    }
}
