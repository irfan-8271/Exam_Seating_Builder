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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Pure dynamic student identification unit tests:
 * no fixed roll-number assumption, exact preservation of every Excel column
 * under its normalized header key, single & composite attribute identification,
 * and classmate cohort / scope schema metadata.
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

    @Test
    void anArbitraryColumnCanIdentifyTheStudent() throws Exception {
        savingSucceeds();
        byte[] xlsx = sheet(
                new String[]{"Student Name", "Application Id", "Department"},
                new String[][]{{"Ann", "APP-001", "CSE"}, {"Bob", "APP-002", "ECE"}});

        ImportResult result = importer().importStudents(
                new ByteArrayInputStream(xlsx), List.of("applicationid"));

        assertEquals(2, result.imported(), () -> "errors: " + result.errors());
        ArgumentCaptor<Student> captor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository, times(2)).save(captor.capture());
        Student first = captor.getAllValues().get(0);
        assertEquals("APP-001", first.getIdentityKey());
        assertEquals("APP-001", first.getAttributes().get("applicationid"));
        assertFalse(first.getAttributes().containsKey("rollno"),
                "no synthetic 'rollno' attribute is invented when absent from the sheet");
    }

    @Test
    void registrationNumberPreservesItsOwnColumnKeyAndAutoDetectsIdentity() throws Exception {
        savingSucceeds();
        byte[] xlsx = sheet(
                new String[]{"Student Name", "Registration Number", "Department"},
                new String[][]{{"Ann", "REG-001", "CSE"}, {"Bob", "REG-002", "ECE"}});

        ImportResult result = importer().importStudents(new ByteArrayInputStream(xlsx));

        assertEquals(2, result.imported(), () -> "errors: " + result.errors());
        ArgumentCaptor<Student> captor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository, times(2)).save(captor.capture());
        Student first = captor.getAllValues().get(0);
        assertEquals("REG-001", first.getAttributes().get("registrationnumber"));
        assertEquals("REG-001", first.getIdentityKey());
    }

    @Test
    void twoAttributesTogetherIdentifyTheStudent() throws Exception {
        savingSucceeds();
        byte[] xlsx = sheet(
                new String[]{"Name", "Roll", "Batch"},
                new String[][]{{"Ann", "1", "A"}, {"Bob", "1", "B"}, {"Cid", "2", "A"}});

        ImportResult result = importer().importStudents(
                new ByteArrayInputStream(xlsx), List.of("roll", "batch"));

        assertEquals(3, result.imported(), () -> "errors: " + result.errors());
        ArgumentCaptor<Student> captor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository, times(3)).save(captor.capture());
        String key = captor.getAllValues().get(0).getIdentityKey();
        assertEquals("1|A", key);
    }

    @Test
    void aSingleAttributeThatRepeatsIsRejectedAsAnIdentity() throws Exception {
        byte[] xlsx = sheet(
                new String[]{"Name", "Roll", "Batch"},
                new String[][]{{"Ann", "1", "A"}, {"Bob", "1", "B"}});

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> importer().importStudents(new ByteArrayInputStream(xlsx), List.of("roll")));

        assertTrue(failure.getMessage().contains("does not uniquely identify"),
                "the message must explain the problem: " + failure.getMessage());
        verify(studentRepository, never()).save(any(Student.class));
    }

    @Test
    void aRosterWithoutAnyNumericColumnIsAccepted() throws Exception {
        savingSucceeds();
        byte[] xlsx = sheet(
                new String[]{"Name", "Father Name", "City"},
                new String[][]{{"Ann", "Ram", "Kolkata"}, {"Bob", "Shyam", "Delhi"}});

        ImportResult result = importer().importStudents(new ByteArrayInputStream(xlsx));

        assertEquals(2, result.imported(), () -> "errors: " + result.errors());
        ArgumentCaptor<Student> captor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository, times(2)).save(captor.capture());
        Student first = captor.getAllValues().get(0);
        assertNotNull(first.getIdentityKey(), "the student must still be identifiable");
    }

    @Test
    void allColumnsArePreservedVerbatimUnderNormalizedKeys() throws Exception {
        savingSucceeds();
        byte[] xlsx = sheet(
                new String[]{"Name", "Admission Id", "Hostel Block", "Blood Group"},
                new String[][]{{"Ann", "ADM-1", "H7", "B+"}, {"Bob", "ADM-2", "H8", "O-"}});

        ImportResult result = importer().importStudents(
                new ByteArrayInputStream(xlsx), List.of("admissionid"));

        assertEquals(2, result.imported(), () -> "errors: " + result.errors());
        ArgumentCaptor<Student> captor = ArgumentCaptor.forClass(Student.class);
        verify(studentRepository, times(2)).save(captor.capture());
        Student first = captor.getAllValues().get(0);
        assertEquals("ADM-1", first.getIdentityKey());
        assertEquals("H7", first.getAttributes().get("hostelblock"));
        assertEquals("B+", first.getAttributes().get("bloodgroup"));
    }

    @Test
    void everyColumnOfTheSheetIsReportedInThePreview() throws Exception {
        byte[] xlsx = sheet(
                new String[]{"Name", "Registration Number", "Hostel Block"},
                new String[][]{{"Ann", "REG-1", "H7"}, {"Bob", "REG-2", "H8"}});

        ImportPreview preview = importer().previewStudents(new ByteArrayInputStream(xlsx));

        assertEquals(3, preview.columns().size());
        assertEquals(2, preview.totalRows());
        assertTrue(preview.columns().stream().anyMatch(c -> "registrationnumber".equals(c.key())));
        assertTrue(preview.columns().stream().anyMatch(c -> "hostelblock".equals(c.key())));
        assertTrue(preview.columns().stream().anyMatch(c -> "name".equals(c.key())));
    }

    @Test
    void thePreviewSuggestsASingleAttributeIdentityUsingExactHeader() throws Exception {
        byte[] xlsx = sheet(
                new String[]{"Name", "Registration Number"},
                new String[][]{{"Ann", "REG-1"}, {"Bob", "REG-2"}});

        ImportPreview preview = importer().previewStudents(new ByteArrayInputStream(xlsx));

        assertFalse(preview.suggestedIdentities().isEmpty());
        ImportPreview.IdentityOption option = preview.suggestedIdentities().get(0);
        assertEquals(List.of("registrationnumber"), option.attributes());
        assertEquals("Registration Number", option.label());
        assertTrue(option.unique());
    }

    @Test
    void thePreviewSuggestsACombinedIdentityWhenNoSingleColumnIsUnique() throws Exception {
        byte[] xlsx = sheet(
                new String[]{"Name", "Roll", "Batch"},
                new String[][]{{"Ann", "1", "A"}, {"Bob", "1", "B"}, {"Cid", "2", "A"}});

        ImportPreview preview = importer().previewStudents(new ByteArrayInputStream(xlsx));

        boolean composite = preview.suggestedIdentities().stream()
                .anyMatch(o -> o.attributes().size() > 1 && o.unique());
        assertTrue(composite, "the preview must propose the composite combination");
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

    @Test
    void theConfiguredSchemeWinsOverAutoDetection() throws Exception {
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
        assertEquals("REG-1", captor.getValue().getIdentityKey());
    }

    @Test
    void theConfiguredSchemeIsReportedInThePreview() throws Exception {
        StudentIdentityScheme scheme = new StudentIdentityScheme();
        scheme.setInstitutionId(1L);
        scheme.setLabel("Reg no + batch");
        scheme.setAttributes("regno,batch");
        scheme.setClassmateAttributes("grade,section");
        scheme.setScopeAttribute("papercode");
        when(schemeRepository.findByInstitutionId(1L)).thenReturn(Optional.of(scheme));
        when(tenant.currentId()).thenReturn(1L);

        byte[] xlsx = sheet(
                new String[]{"Name", "Reg No", "Batch"},
                new String[][]{{"Ann", "1", "A"}});

        ImportPreview preview = importer().previewStudents(new ByteArrayInputStream(xlsx));

        assertNotNull(preview.currentScheme());
        assertEquals(List.of("regno", "batch"), preview.currentScheme().attributes());
        assertEquals(List.of("grade", "section"), preview.currentScheme().classmateAttributes());
        assertEquals("papercode", preview.currentScheme().scopeAttribute());
    }

    @Test
    void oneBadRowDoesNotStopTheRest() throws Exception {
        savingSucceeds();
        byte[] xlsx = sheet(
                new String[]{"Name", "Registration Number"},
                new String[][]{{"Ann", "REG-1"}, {"Bob", ""}, {"Cid", "REG-3"}});

        ImportResult result = importer().importStudents(
                new ByteArrayInputStream(xlsx), List.of("registrationnumber"));

        assertEquals(2, result.imported());
        assertEquals(1, result.errors().size());
        assertTrue(result.errors().get(0).contains("Row 3"));
    }

    @Test
    void aSchemeIsStoredWithIdentityClassmatesAndScope() {
        StudentIdentityService service = new StudentIdentityService(schemeRepository, tenant);
        when(tenant.currentId()).thenReturn(1L);
        when(schemeRepository.findByInstitutionId(1L)).thenReturn(Optional.empty());

        service.saveScheme("Reg no + batch", List.of("regno", "batch"), null,
                List.of("grade", "section"), "examcode");

        ArgumentCaptor<StudentIdentityScheme> captor =
                ArgumentCaptor.forClass(StudentIdentityScheme.class);
        verify(schemeRepository).save(captor.capture());
        StudentIdentityScheme saved = captor.getValue();
        assertEquals(1L, saved.getInstitutionId());
        assertEquals("Reg no + batch", saved.getLabel());
        assertEquals("regno,batch", saved.getAttributes());
        assertEquals("grade,section", saved.getClassmateAttributes());
        assertEquals("examcode", saved.getScopeAttribute());
    }

    @Test
    void displayLabelFormatsIdentityCleanly() {
        StudentIdentityService service = new StudentIdentityService(schemeRepository, tenant);
        when(tenant.currentId()).thenReturn(1L);
        when(schemeRepository.findByInstitutionId(1L)).thenReturn(Optional.empty());

        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("admissionid", "ADM-42");
        Student student = Student.builder()
                .name("Ann")
                .identityKey("ADM-42")
                .attributes(attrs)
                .build();

        assertEquals("ADM-42", service.displayLabel(student));
    }
}
