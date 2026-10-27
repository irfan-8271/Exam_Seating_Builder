package com.examseating.service;

import com.examseating.dto.ImportResult;
import com.examseating.entity.Student;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.StudentRepository;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Import behaviour: one bad row must not roll back the good rows (each row
 * commits in its own transaction), subject code is optional, and headers are
 * matched by alias.
 */
@ExtendWith(MockitoExtension.class)
public class ExcelImportServiceTransactionTest {

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private ExamRoomRepository examRoomRepository;

    private static TransactionTemplate noopTemplate() {
        PlatformTransactionManager tx = new PlatformTransactionManager() {
            @Override
            public TransactionStatus getTransaction(TransactionDefinition definition) {
                return new SimpleTransactionStatus();
            }

            @Override
            public void commit(TransactionStatus status) {
            }

            @Override
            public void rollback(TransactionStatus status) {
            }
        };
        return new TransactionTemplate(tx);
    }

    private static byte[] studentSheet(String[] headers, String[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("students");
            Row header = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                header.createCell(i).setCellValue(headers[i]);
            }
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 0; c < rows[r].length; c++) {
                    row.createCell(c).setCellValue(rows[r][c]);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            wb.write(out);
            return out.toByteArray();
        }
    }

    @Test
    void testOneBadRowDoesNotPoisonTheOthers() throws Exception {
        ExcelImportService service =
                new ExcelImportService(studentRepository, examRoomRepository, noopTemplate());

        // Row 2 violates a unique constraint; rows 1 and 3 are fine.
        when(studentRepository.save(any(Student.class))).thenAnswer(invocation -> {
            Student s = invocation.getArgument(0);
            if ("B".equals(s.getRollNo())) {
                throw new DataIntegrityViolationException("duplicate key uk_students_roll_no");
            }
            return s;
        });

        byte[] xlsx = studentSheet(
                new String[]{"Name", "Roll No", "Department", "Course", "Year/Session", "Subject Code"},
                new String[][]{
                        {"Ann", "A", "CSE", "B.Tech", "2024", "CS1"},
                        {"Bob", "B", "ECE", "B.Tech", "2024", "CS1"},
                        {"Cat", "C", "ME", "B.Tech", "2025", ""}
                });

        ImportResult result = service.importStudents(new ByteArrayInputStream(xlsx));

        assertEquals(3, result.totalRows());
        assertEquals(2, result.imported(), "good rows must survive a failing row");
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("Row 3"), "error must name the failing row");
    }

    @Test
    void testSubjectCodeIsOptionalAndHeadersAreAliased() throws Exception {
        ExcelImportService service =
                new ExcelImportService(studentRepository, examRoomRepository, noopTemplate());
        when(studentRepository.save(any(Student.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // No Subject Code column at all; alias headers ("Reg No", "Sem", "Section").
        byte[] xlsx = studentSheet(
                new String[]{"Full Name", "Reg No", "Dept", "Programme", "Sem"},
                new String[][]{
                        {"Ann", "A1", "CSE", "B.Tech", "4"},
                });

        ImportResult result = service.importStudents(new ByteArrayInputStream(xlsx));

        assertEquals(1, result.imported());
        ArgumentCaptor<Student> captor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository).save(captor.capture());
        Student saved = captor.getValue();
        assertEquals("A1", saved.getRollNo());
        assertEquals("4", saved.getYearSession(), "'Sem' must map to year/session");
        assertNull(saved.getSubjectCode(), "subject code stays null when the column is absent");
    }

    @Test
    void testMissingRequiredColumnFailsFast() throws Exception {
        ExcelImportService service =
                new ExcelImportService(studentRepository, examRoomRepository, noopTemplate());
        byte[] xlsx = studentSheet(new String[]{"Name"}, new String[][]{{"Ann"}});
        assertThrows(IllegalArgumentException.class,
                () -> service.importStudents(new ByteArrayInputStream(xlsx)));
    }

    @Test
    void testDuplicateRollMessageIsHumanFriendly() throws Exception {
        ExcelImportService service =
                new ExcelImportService(studentRepository, examRoomRepository, noopTemplate());
        when(studentRepository.save(any(Student.class)))
                .thenThrow(new DataIntegrityViolationException("uk_students_roll_no"));

        byte[] xlsx = studentSheet(
                new String[]{"Name", "Roll No"},
                new String[][]{{"Ann", "A"}});
        ImportResult result = service.importStudents(new ByteArrayInputStream(xlsx));
        List<String> errors = result.errors();
        assertEquals(1, errors.size());
        assertTrue(errors.get(0).toLowerCase().contains("duplicate"),
                "raw DB message must be translated: " + errors.get(0));
    }
}
