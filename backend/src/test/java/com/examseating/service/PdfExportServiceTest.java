package com.examseating.service;

import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Student;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.repository.SeatingRoundRepository;
import com.examseating.tenancy.TenantSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
public class PdfExportServiceTest {

    @Mock
    private SeatingAllocationRepository allocationRepository;

    @Mock
    private StudentIdentityService identityService;

    @Mock
    private SeatingRoundRepository roundRepository;

    @Mock
    private TenantSupport tenant;

    @InjectMocks
    private PdfExportService pdfExportService;

    @BeforeEach
    void setUp() {
        lenient().when(tenant.visible(any())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(identityService.currentScheme()).thenReturn(Optional.empty());
        lenient().when(identityService.currentClassmateAttributes()).thenReturn(List.of("department"));
        lenient().when(roundRepository.findByInstitutionIdAndScopeKey(any(), any())).thenReturn(Optional.empty());
    }

    private static Student buildStudent(long id, String identity, String name, String dept, String year, String scope) {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("rollno", identity);
        attrs.put("department", dept);
        attrs.put("year", year);
        attrs.put("paper", scope);
        return Student.builder()
                .id(id)
                .name(name)
                .identityKey(identity)
                .attributes(attrs)
                .build();
    }

    @Test
    void testDoorNoticesDualSeater() {
        ExamRoom room = ExamRoom.builder()
                .id(1L)
                .hallName("Auditorium Hall A")
                .rows(3)
                .columns(4)
                .benchType(ExamRoom.BenchType.DUAL_SEATER)
                .build();

        List<SeatingAllocation> allocations = new ArrayList<>();
        long id = 1;
        String[] depts = {"Computer Science", "Electronics", "Mechanical", "Civil"};

        for (int r = 1; r <= 3; r++) {
            for (int c = 1; c <= 4; c++) {
                Bench bench = Bench.builder().room(room).rowIndex(r).colIndex(c).build();
                Student s1 = buildStudent(id, "10100" + id, "Student " + id, depts[(r + c) % 4], "2024", "CS101");
                id++;
                Student s2 = buildStudent(id, "20200" + id, "Student " + id, depts[(r + c + 1) % 4], "2025", "CS101");
                id++;

                allocations.add(SeatingAllocation.builder().bench(bench).student(s1).slotNumber(1).scopeKey("CS101").build());
                allocations.add(SeatingAllocation.builder().bench(bench).student(s2).slotNumber(2).scopeKey("CS101").build());
            }
        }

        when(allocationRepository.findAllWithDetails(null)).thenReturn(allocations);

        byte[] pdfBytes = pdfExportService.doorNotices(null);
        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 500);
        assertEquals('%', (char) pdfBytes[0]);
        assertEquals('P', (char) pdfBytes[1]);
        assertEquals('D', (char) pdfBytes[2]);
        assertEquals('F', (char) pdfBytes[3]);
    }

    @Test
    void testDoorNoticesSingleSeater() {
        ExamRoom room = ExamRoom.builder()
                .id(2L)
                .hallName("Seminar Room 202")
                .rows(3)
                .columns(3)
                .benchType(ExamRoom.BenchType.SINGLE_SEATER)
                .build();

        List<SeatingAllocation> allocations = new ArrayList<>();
        long id = 1;
        String[] depts = {"Computer Science", "Electronics", "Mechanical"};

        for (int r = 1; r <= 3; r++) {
            for (int c = 1; c <= 3; c++) {
                if (r == 3 && c == 3) continue;
                Bench bench = Bench.builder().room(room).rowIndex(r).colIndex(c).build();
                Student s = buildStudent(id, "30300" + id, "Single Student " + id, depts[(r + c) % 3], "2024", "ENGG201");
                id++;
                allocations.add(SeatingAllocation.builder().bench(bench).student(s).slotNumber(1).scopeKey("ENGG201").build());
            }
        }

        when(allocationRepository.findAllWithDetails(null)).thenReturn(allocations);

        byte[] pdfBytes = pdfExportService.doorNotices(null);
        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 500);
    }

    @Test
    void testInvigilatorSheets() {
        ExamRoom room = ExamRoom.builder()
                .id(1L)
                .hallName("Auditorium Hall A")
                .rows(2)
                .columns(2)
                .benchType(ExamRoom.BenchType.DUAL_SEATER)
                .build();

        Bench b1 = Bench.builder().room(room).rowIndex(1).colIndex(1).build();
        Student s1 = buildStudent(1L, "101001", "Alice", "CSE", "2024", "CS101");

        when(allocationRepository.findAllWithDetails(null)).thenReturn(
                List.of(SeatingAllocation.builder().bench(b1).student(s1).slotNumber(1).scopeKey("CS101").build()));

        byte[] pdfBytes = pdfExportService.invigilatorSheets(null);
        assertNotNull(pdfBytes);
        assertTrue(pdfBytes.length > 500);
    }
}
