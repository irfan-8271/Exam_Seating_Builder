package com.examseating;

import com.examseating.dto.ArrangeRequest;
import com.examseating.dto.PageResponse;
import com.examseating.engine.SeatingArrangementEngine;
import com.examseating.engine.SeatingArrangementEngine.IllegalSeatingConfigurationException;
import com.examseating.engine.SeatingArrangementEngine.SeatingConfig;
import com.examseating.engine.SeatingArrangementEngine.SeatKey;
import com.examseating.engine.SeatingArrangementEngine.SeatingEngineException;
import com.examseating.engine.SeatingArrangementEngine.StudentAttribute;
import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.Student;
import com.examseating.exception.GlobalExceptionHandler;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.service.ExcelImportService;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure-JUnit regression tests for the defect fixes and REST conventions.
 * No Spring context, MySQL, or servlet container is required.
 */
class RegressionFixesTest {

    private static ExamRoom room(String name, int rows, int cols, ExamRoom.BenchType type) {
        ExamRoom r = ExamRoom.builder().id(1L).hallName(name).rows(rows).columns(cols).benchType(type).build();
        for (int i = 1; i <= rows; i++) {
            for (int j = 1; j <= cols; j++) {
                r.getBenches().add(Bench.builder().room(r).rowIndex(i).colIndex(j).benchType(type).build());
            }
        }
        return r;
    }

    private static Student student(long id, String dept, String year) {
        return Student.builder().id(id).name("S" + id).rollNo("R" + id)
                .department(dept).course("B.Tech").yearSession(year).subjectCode("M").build();
    }

    // ---------------------------------------------------------------- engine

    @Test
    void mixedBenchTypesAreRejected() {
        ExamRoom r = room("Hall", 2, 2, ExamRoom.BenchType.SINGLE_SEATER);
        r.getBenches().get(0).setBenchType(ExamRoom.BenchType.DUAL_SEATER);
        assertThrows(IllegalSeatingConfigurationException.class,
                () -> new SeatingArrangementEngine().arrange(List.of(r),
                        List.of(student(1, "CSE", "2025")), SeatingConfig.of(1, 0, 0)));
    }

    @Test
    void invalidRoomRejectedEvenWithEmptyRoster() {
        ExamRoom r = room("Hall", 0, 0, ExamRoom.BenchType.SINGLE_SEATER);
        assertThrows(IllegalSeatingConfigurationException.class,
                () -> new SeatingArrangementEngine().arrange(List.of(r), List.of(), SeatingConfig.of(1, 0, 0)));
    }

    @Test
    void nullRoomOrStudentEntriesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new SeatingArrangementEngine().arrange(Arrays.asList((ExamRoom) null),
                        List.of(), SeatingConfig.of(1, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> new SeatingArrangementEngine().arrange(
                        List.of(room("Hall", 1, 1, ExamRoom.BenchType.SINGLE_SEATER)),
                        Arrays.asList((Student) null), SeatingConfig.of(1, 0, 0)));
    }

    @Test
    void impossibleInstanceIsRejectedQuickly() {
        ExamRoom r = room("Hall", 2, 3, ExamRoom.BenchType.DUAL_SEATER);
        List<Student> students = new ArrayList<>();
        for (int i = 1; i <= 12; i++) students.add(student(i, "CSE", "2025"));
        long start = System.currentTimeMillis();
        assertThrows(SeatingEngineException.class,
                () -> new SeatingArrangementEngine().arrange(List.of(r), students, SeatingConfig.of(1, 0, 0)));
        assertTrue(System.currentTimeMillis() - start < 2_000,
                "provably impossible instances must fail fast, not burn the step budget");
    }

    @Test
    void seatKeySurvivesDifferentEntityInstances() {
        ExamRoom first = room("Hall", 1, 1, ExamRoom.BenchType.DUAL_SEATER);
        ExamRoom second = room("Hall", 1, 1, ExamRoom.BenchType.DUAL_SEATER);
        assertEquals(SeatKey.of(first.getBenches().get(0), 1),
                SeatKey.of(second.getBenches().get(0), 1));
        Set<SeatKey> occupied = Set.of(SeatKey.of(first.getBenches().get(0), 1));
        assertTrue(occupied.contains(SeatKey.of(second.getBenches().get(0), 1)));
    }

    @Test
    void capacityOverflowIsNotSilent() {
        ExamRoom huge = ExamRoom.builder().hallName("Huge").rows(50_000).columns(50_000)
                .benchType(ExamRoom.BenchType.DUAL_SEATER).build();
        assertThrows(ArithmeticException.class, huge::getCapacity);
    }

    // ---------------------------------------------------------------- DTOs

    @Test
    void pageResponseEnvelopeAndValidation() {
        PageResponse<Integer> first = PageResponse.of(List.of(1, 2, 3, 4, 5), 0, 2);
        assertEquals(2, first.content().size());
        assertEquals(5, first.totalElements());
        assertEquals(3, first.totalPages());
        assertTrue(first.first());
        assertFalse(first.last());
        PageResponse<Integer> last = PageResponse.of(List.of(1, 2, 3, 4, 5), 2, 2);
        assertEquals(1, last.content().size());
        assertTrue(last.last());
        assertThrows(IllegalArgumentException.class, () -> PageResponse.of(List.of(1), -1, 10));
        assertThrows(IllegalArgumentException.class, () -> PageResponse.of(List.of(1), 0, 0));
        assertThrows(IllegalArgumentException.class, () -> PageResponse.of(List.of(1), 0, 201));
    }

    @Test
    void arrangeRequestRejectsNullGroupAttributes() {
        assertThrows(IllegalArgumentException.class, () -> new ArrangeRequest(
                null, 1, 0, 0, null, null, null,
                Arrays.asList((StudentAttribute) null), null, null, null, null));
    }

    // ---------------------------------------------------------------- errors

    @Test
    void errorEnvelopeIsUniform() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        GlobalExceptionHandler.ErrorResponse body = handler
                .handleNotFound(new ResourceNotFoundException("Student 9 not found"),
                        new MockHttpServletRequest("GET", "/api/v1/students/9"))
                .getBody();
        assertNotNull(body);
        assertEquals(404, body.status());
        assertEquals("NOT_FOUND", body.code());
        assertEquals("/api/v1/students/9", body.path());
        assertNotNull(body.details());
        assertTrue(body.details().isEmpty());
    }

    // ---------------------------------------------------------------- import

    private static TransactionTemplate transactionTemplate() {
        PlatformTransactionManager tm = new PlatformTransactionManager() {
            @Override public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }
            @Override public void commit(TransactionStatus status) { }
            @Override public void rollback(TransactionStatus status) { }
        };
        return new TransactionTemplate(tm);
    }

    private static byte[] workbookBytes(String[] headers, String[]... rows) throws Exception {
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Sheet1");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) header.createCell(i).setCellValue(headers[i]);
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) row.createCell(c).setCellValue(rows[r][c]);
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void dottedHeadersAreAccepted() throws Exception {
        StudentRepository students = mock(StudentRepository.class);
        ExamRoomRepository rooms = mock(ExamRoomRepository.class);
        when(students.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));
        ExcelImportService service = new ExcelImportService(students, rooms, transactionTemplate());
        byte[] bytes = workbookBytes(new String[]{".Name", "Roll No."}, new String[]{"Alice", "1001"});
        var result = service.importStudents(new ByteArrayInputStream(bytes));
        assertEquals(1, result.imported());
        assertTrue(result.errors().isEmpty(), () -> result.errors().toString());
    }

    @Test
    void overlongFieldIsReportedBeforeInsert() throws Exception {
        StudentRepository students = mock(StudentRepository.class);
        ExamRoomRepository rooms = mock(ExamRoomRepository.class);
        when(students.save(any(Student.class))).thenAnswer(inv -> inv.getArgument(0));
        ExcelImportService service = new ExcelImportService(students, rooms, transactionTemplate());
        byte[] bytes = workbookBytes(new String[]{"Name", "Roll No"},
                new String[]{"A".repeat(101), "1001"}, new String[]{"Bob", "1002"});
        var result = service.importStudents(new ByteArrayInputStream(bytes));
        assertEquals(1, result.imported());
        assertTrue(result.errors().stream().anyMatch(e -> e.contains("exceeds the maximum length")),
                () -> result.errors().toString());
    }
}
