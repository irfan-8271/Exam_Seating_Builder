package com.examseating.service;

import com.examseating.dto.ImportPreview;
import com.examseating.dto.ImportResult;
import com.examseating.entity.Student;
import com.examseating.entity.StudentAttributes;
import com.examseating.entity.StudentIdentityScheme;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.StudentIdentitySchemeRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.tenancy.TenantSupport;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Student identification must not assume a roll number.
 *
 * <p>Every institution identifies its students differently — by roll number,
 * registration number, admission number, a combination of two columns, or by
 * nothing at all that looks like a number. These tests pin that behaviour
 * down: the importer accepts any column layout, keeps unknown columns as
 * attributes, and identifies students by whatever the institution configured
 * (or by the best candidate it found).</p>
 */
@ExtendWith(MockitoExtension.class)
public class DynamicStudentIdentityTest {

    @Mock
    private StudentRepository studentRepository;

    @Mock
    private ExamRoomRepository examRoomRepository;

    @Mock
    private StudentIdentitySchemeRepository schemeRepository;

    @Mock
    private TenantSupport tenant;

    // ------------------------------------------------------------------
    // Building blocks
    // ------------------------------------------------------------------

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

    private ExcelImportService importer() {
        return new ExcelImportService(studentRepository, examRoomRepository, noopTemplate(),
                tenant, mock(QuotaService.class),
                new StudentIdentityService(schemeRepository, tenant));
    }

    private static byte[] sheet(String[] headers, String[][] rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook()) {
            Sheet sheet = wb.createSheet("Students");
            Row header = sheet.createRow(0);
            for (int c = 0; c < headers.length; c++) {
                header.createCell(c).setCellValue(headers[c]);
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

    private void savingSucceeds() {
        when(studentRepository.save(any(Student.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
    }

    // ------------------------------------------------------------------
    // 1. Identified by a single attribute that is not a roll number
    // ------------------------------------------------------------------

    @Test
    void anUnknownColumnCanIdentifyTheStudent() throws Exception {
        savingSucceeds();
        // "Application Id" is not a roll number — the system does not know
        // the column, but the institution may still identify by it.
        byte[] xlsx = sheet(
                new String[]{"Student Name", "Application Id", "Department"},
                new String[][]{{"Ann", "APP-001", "CSE"}, {"Bob", "APP-002", "ECE"}});

        ImportResult result = importer().importStudents(
                new ByteArrayInputStream(xlsx), List.of("applicationid"));

        assertEquals(2, result.imported(), () -> "errors: " + result.errors());
        ArgumentCaptor<Student> captor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository, times(2)).save(captor.capture());
        Student first = captor.getAllValues().get(0);
        assertEquals("APP-001", first.getIdentityKey(),
                "the application id must become the identity");
        assertEquals("APP-001", first.getAttributes().get("applicationid"));
        assertNull(first.getRollNo(), "there is no roll number in this file");
    }

    @Test
    void aRegistrationNumberIsStillTheRollNumber() throws Exception {
        savingSucceeds();
        // "Registration Number" is one of the spellings of the institution's
        // identifier — that behaviour must not change.
        byte[] xlsx = sheet(
                new String[]{"Student Name", "Registration Number", "Department"},
                new String[][]{{"Ann", "REG-001", "CSE"}, {"Bob", "REG-002", "ECE"}});

        ImportResult result = importer().importStudents(new ByteArrayInputStream(xlsx));

        assertEquals(2, result.imported(), () -> "errors: " + result.errors());
        ArgumentCaptor<Student> captor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository, times(2)).save(captor.capture());
        Student first = captor.getAllValues().get(0);
        assertEquals("REG-001", first.getRollNo());
        assertEquals("REG-001", first.getIdentityKey());
    }

    // ------------------------------------------------------------------
    // 2. Identified by a combination of two attributes
    // ------------------------------------------------------------------

    @Test
    void twoAttributesTogetherIdentifyTheStudent() throws Exception {
        savingSucceeds();
        // "Roll" alone repeats; "Batch" alone repeats; together they are unique.
        byte[] xlsx = sheet(
                new String[]{"Name", "Roll", "Batch"},
                new String[][]{{"Ann", "1", "A"}, {"Bob", "1", "B"}, {"Cid", "2", "A"}});

        ImportResult result = importer().importStudents(
                new ByteArrayInputStream(xlsx), List.of("roll", "batch"));

        assertEquals(3, result.imported(), () -> "errors: " + result.errors());
        ArgumentCaptor<Student> captor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository, times(3)).save(captor.capture());
        String key = captor.getAllValues().get(0).getIdentityKey();
        assertNotNull(key);
        assertTrue(key.contains("1") && key.contains("A"),
                "the identity must combine both attributes, was: " + key);
    }

    @Test
    void aSingleAttributeThatRepeatsIsRejectedAsAnIdentity() throws Exception {
        byte[] xlsx = sheet(
                new String[]{"Name", "Roll", "Batch"},
                new String[][]{{"Ann", "1", "A"}, {"Bob", "1", "B"}});

        // "roll" on its own repeats, so it cannot identify anybody — the
        // importer must refuse it rather than merge two students.
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> importer().importStudents(new ByteArrayInputStream(xlsx), List.of("roll")));

        assertTrue(failure.getMessage().contains("does not identify"),
                "the message must explain the problem: " + failure.getMessage());
        verify(studentRepository, never()).save(any(Student.class));
    }

    // ------------------------------------------------------------------
    // 3. No roll number at all
    // ------------------------------------------------------------------

    @Test
    void aRosterWithoutAnyRollNumberIsAccepted() throws Exception {
        savingSucceeds();
        byte[] xlsx = sheet(
                new String[]{"Name", "Father Name", "City"},
                new String[][]{{"Ann", "Ram", "Kolkata"}, {"Bob", "Shyam", "Delhi"}});

        ImportResult result = importer().importStudents(new ByteArrayInputStream(xlsx));

        assertEquals(2, result.imported(), () -> "errors: " + result.errors());
        ArgumentCaptor<Student> captor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository, times(2)).save(captor.capture());
        Student first = captor.getAllValues().get(0);
        assertNull(first.getRollNo(), "no roll number was supplied");
        assertNotNull(first.getIdentityKey(), "the student must still be identifiable");
    }

    @Test
    void aRosterWithoutAnyRollNumberCanBeIdentifiedByTheUser() throws Exception {
        savingSucceeds();
        byte[] xlsx = sheet(
                new String[]{"Name", "Admission Id", "City"},
                new String[][]{{"Ann", "ADM-1", "Kolkata"}, {"Bob", "ADM-2", "Delhi"}});

        ImportResult result = importer().importStudents(
                new ByteArrayInputStream(xlsx), List.of("admissionid"));

        assertEquals(2, result.imported(), () -> "errors: " + result.errors());
        ArgumentCaptor<Student> captor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository, times(2)).save(captor.capture());
        assertEquals("ADM-1", captor.getAllValues().get(0).getIdentityKey());
    }

    // ------------------------------------------------------------------
    // 4. Unknown columns are kept, not thrown away
    // ------------------------------------------------------------------

    @Test
    void unknownColumnsAreKeptAsAttributes() throws Exception {
        savingSucceeds();
        byte[] xlsx = sheet(
                new String[]{"Name", "Roll No", "Hostel Block", "Blood Group"},
                new String[][]{{"Ann", "1", "H7", "B+"}, {"Bob", "2", "H8", "O-"}});

        ImportResult result = importer().importStudents(new ByteArrayInputStream(xlsx));

        assertEquals(2, result.imported(), () -> "errors: " + result.errors());
        ArgumentCaptor<Student> captor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository, times(2)).save(captor.capture());
        Student first = captor.getAllValues().get(0);
        assertEquals("H7", first.getAttributes().get("hostelblock"),
                "an unrecognised column must survive the import");
        assertEquals("B+", first.getAttributes().get("bloodgroup"));
    }

    @Test
    void everyColumnOfTheSheetIsReportedInThePreview() throws Exception {
        byte[] xlsx = sheet(
                new String[]{"Name", "Registration Number", "Hostel Block"},
                new String[][]{{"Ann", "REG-1", "H7"}, {"Bob", "REG-2", "H8"}});

        ImportPreview preview = importer().previewStudents(new ByteArrayInputStream(xlsx));

        assertEquals(3, preview.columns().size(),
                "the preview must list every column, not only the known ones");
        assertEquals(2, preview.totalRows());
        assertTrue(preview.columns().stream().anyMatch(c -> "hostelblock".equals(c.key())),
                "the unknown column must be listed: " + preview.columns());
        assertTrue(preview.columns().stream().anyMatch(c -> "name".equals(c.key())));
    }

    // ------------------------------------------------------------------
    // 5. The preview suggests how students can be identified
    // ------------------------------------------------------------------

    @Test
    void thePreviewSuggestsASingleAttributeIdentity() throws Exception {
        byte[] xlsx = sheet(
                new String[]{"Name", "Registration Number"},
                new String[][]{{"Ann", "REG-1"}, {"Bob", "REG-2"}});

        ImportPreview preview = importer().previewStudents(new ByteArrayInputStream(xlsx));

        assertFalse(preview.suggestedIdentities().isEmpty(),
                "the preview must propose at least one identity");
        ImportPreview.IdentityOption option = preview.suggestedIdentities().get(0);
        assertEquals(List.of("rollno"), option.attributes(),
                "a registration number is the institution's roll number");
        assertEquals("Registration Number", option.label(),
                "the label must use the header as it appears in the file");
        assertTrue(option.unique());
    }

    @Test
    void thePreviewSuggestsACombinedIdentityWhenNoSingleColumnIsUnique() throws Exception {
        // Neither "Roll" nor "Batch" is unique — but together they are.
        byte[] xlsx = sheet(
                new String[]{"Name", "Roll", "Batch"},
                new String[][]{{"Ann", "1", "A"}, {"Bob", "1", "B"}, {"Cid", "2", "A"}});

        ImportPreview preview = importer().previewStudents(new ByteArrayInputStream(xlsx));

        boolean composite = preview.suggestedIdentities().stream()
                .anyMatch(o -> o.attributes().size() > 1 && o.unique());
        assertTrue(composite,
                "the preview must propose the combination, was: " + preview.suggestedIdentities());
    }

    @Test
    void thePreviewNeverProposesTheNameAsAnIdentity() throws Exception {
        byte[] xlsx = sheet(
                new String[]{"Name", "Registration Number"},
                new String[][]{{"Ann", "REG-1"}, {"Bob", "REG-2"}});

        ImportPreview preview = importer().previewStudents(new ByteArrayInputStream(xlsx));

        assertTrue(preview.suggestedIdentities().stream()
                        .noneMatch(o -> o.attributes().contains(StudentAttributes.NAME)),
                "a name must never be proposed as an identifier");
    }

    // ------------------------------------------------------------------
    // 6. The institution's configured scheme wins over the auto-detection
    // ------------------------------------------------------------------

    @Test
    void theConfiguredSchemeIsUsedWhenNoAttributesAreGiven() throws Exception {
        savingSucceeds();
        StudentIdentityScheme scheme = new StudentIdentityScheme();
        scheme.setInstitutionId(1L);
        scheme.setLabel("Registration number");
        scheme.setAttributes("registrationnumber");
        when(schemeRepository.findByInstitutionId(1L)).thenReturn(Optional.of(scheme));
        when(tenant.currentId()).thenReturn(1L);

        byte[] xlsx = sheet(
                new String[]{"Name", "Registration Number", "Roll No"},
                new String[][]{{"Ann", "REG-1", "77"}});

        ImportResult result = importer().importStudents(new ByteArrayInputStream(xlsx));

        assertEquals(1, result.imported(), () -> "errors: " + result.errors());
        ArgumentCaptor<Student> captor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository).save(captor.capture());
        assertEquals("REG-1", captor.getValue().getIdentityKey(),
                "the configured scheme must win over the auto-detected roll number");
    }

    @Test
    void theConfiguredSchemeIsReportedInThePreview() throws Exception {
        StudentIdentityScheme scheme = new StudentIdentityScheme();
        scheme.setInstitutionId(1L);
        scheme.setLabel("Reg no + batch");
        scheme.setAttributes("regno,batch");
        when(schemeRepository.findByInstitutionId(1L)).thenReturn(Optional.of(scheme));
        when(tenant.currentId()).thenReturn(1L);

        byte[] xlsx = sheet(
                new String[]{"Name", "Reg No", "Batch"},
                new String[][]{{"Ann", "1", "A"}});

        ImportPreview preview = importer().previewStudents(new ByteArrayInputStream(xlsx));

        assertNotNull(preview.currentScheme(), "the preview must show the configured scheme");
        assertEquals(List.of("regno", "batch"), preview.currentScheme().attributes());
    }

    // ------------------------------------------------------------------
    // 7. One bad row must not spoil the others
    // ------------------------------------------------------------------

    @Test
    void oneBadRowDoesNotStopTheRest() throws Exception {
        savingSucceeds();
        byte[] xlsx = sheet(
                new String[]{"Name", "Registration Number"},
                new String[][]{{"Ann", "REG-1"}, {"Bob", ""}, {"Cid", "REG-3"}});

        ImportResult result = importer().importStudents(
                new ByteArrayInputStream(xlsx), List.of("registrationnumber"));

        assertEquals(2, result.imported(), "the good rows must survive");
        assertEquals(1, result.errors().size(), () -> "errors: " + result.errors());
        assertTrue(result.errors().get(0).contains("Row 3"),
                "the error must name the row: " + result.errors());
    }

    // ------------------------------------------------------------------
    // 8. The identity scheme itself
    // ------------------------------------------------------------------

    @Test
    void aSchemeOfTwoAttributesIsStoredAndReadBack() {
        StudentIdentityService service = new StudentIdentityService(schemeRepository, tenant);
        when(tenant.currentId()).thenReturn(1L);
        when(schemeRepository.findByInstitutionId(1L)).thenReturn(Optional.empty());

        service.saveScheme("Reg no + batch", List.of("regno", "batch"));

        ArgumentCaptor<StudentIdentityScheme> captor =
                ArgumentCaptor.forClass(StudentIdentityScheme.class);
        verify(schemeRepository).save(captor.capture());
        StudentIdentityScheme saved = captor.getValue();
        assertEquals(1L, saved.getInstitutionId());
        assertEquals("Reg no + batch", saved.getLabel());
        assertEquals("regno,batch", saved.getAttributes());
    }

    @Test
    void withoutASchemeTheIdentityFallsBackToTheRollNumber() {
        when(schemeRepository.findByInstitutionId(1L)).thenReturn(Optional.empty());
        when(tenant.currentId()).thenReturn(1L);
        StudentIdentityService service = new StudentIdentityService(schemeRepository, tenant);

        Student student = new Student();
        student.setRollNo("42");

        assertEquals("42", service.identityKey(student, List.of()));
    }

    @Test
    void theDisplayLabelUsesTheIdentityAndNotTheRollNumber() {
        StudentIdentityService service = new StudentIdentityService(schemeRepository, tenant);

        Student student = new Student();
        student.setRollNo("42");
        student.setName("Ann");

        // With no scheme the roll number is the label — the old behaviour.
        assertEquals("42", service.displayLabel(student));
    }
}
