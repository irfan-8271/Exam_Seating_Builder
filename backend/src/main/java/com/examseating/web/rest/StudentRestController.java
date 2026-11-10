package com.examseating.web.rest;

import com.examseating.dto.ImportPreview;
import com.examseating.dto.ImportResult;
import com.examseating.dto.IdentitySchemeView;
import com.examseating.dto.PageResponse;
import com.examseating.entity.Student;
import com.examseating.entity.StudentAttributes;
import com.examseating.entity.StudentIdentityScheme;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.repository.StudentRepository;
import com.examseating.service.ExcelImportService;
import com.examseating.service.QuotaService;
import com.examseating.service.StudentIdentityService;
import com.examseating.tenancy.TenantSupport;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Primary v1 REST resource for the student roster.
 *
 * <p>Collection endpoints are paged, filtered and sortable; single-item
 * endpoints use proper verbs and status codes (201 + Location, 204 on delete).
 * The legacy {@code /api/students} paths remain as deprecated aliases for the
 * bundled single-page app.</p>
 *
 * <p><b>Identification is configurable.</b> A student is identified by
 * whichever attributes the institution declared in its identity scheme — a
 * roll number, a registration number, a batch plus a serial, or any
 * combination. The scheme itself is managed here
 * ({@code GET/PUT /identity}), and an uploaded spreadsheet is analysed by
 * {@code POST /imports/preview} before it is imported.</p>
 */
@RestController
@RequestMapping("/api/v1/students")
@RequiredArgsConstructor
public class StudentRestController {

    private static final Set<String> SORTABLE = Set.of("id", "name", "rollNo", "department", "yearSession", "subjectCode");
    private static final Set<String> PATCHABLE = Set.of("name", "rollNo", "department", "course", "yearSession", "subjectCode");

    private final StudentRepository studentRepository;
    private final ExcelImportService excelImportService;
    private final TenantSupport tenant;
    private final QuotaService quotas;
    private final StudentIdentityService identityService;

    /** GET /api/v1/students — paged, filtered, sortable collection. */
    @GetMapping
    public PageResponse<Student> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String subjectCode,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "asc") String direction) {
        List<Student> students = tenant.visible((isBlank(subjectCode))
                ? studentRepository.findAll()
                : studentRepository.findBySubjectCode(subjectCode.trim()));
        if (!isBlank(department)) {
            String dept = department.trim();
            students = students.stream()
                    .filter(s -> dept.equalsIgnoreCase(s.getDepartment()))
                    .toList();
        }
        String sort = (sortBy == null || sortBy.isBlank()) ? "id" : sortBy.trim();
        if (!SORTABLE.contains(sort)) {
            throw new IllegalArgumentException("sortBy must be one of " + SORTABLE);
        }
        if (!"asc".equalsIgnoreCase(direction) && !"desc".equalsIgnoreCase(direction)) {
            throw new IllegalArgumentException("direction must be 'asc' or 'desc'");
        }
        Comparator<Student> comparator = studentComparator(sort);
        if ("desc".equalsIgnoreCase(direction)) {
            comparator = comparator.reversed();
        }
        List<Student> sorted = students.stream().sorted(comparator).toList();
        return PageResponse.of(sorted, page, size);
    }

    /** GET /api/v1/students/{id} — one student or 404. */
    @GetMapping("/{id}")
    public Student get(@PathVariable Long id) {
        Student student = studentRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Student " + id + " not found"));
        tenant.require(student, "Student " + id);
        return student;
    }

    /** POST /api/v1/students — 201 Created with a Location header. */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public ResponseEntity<Student> create(@RequestBody Student student) {
        quotas.checkStudents(1);
        tenant.stamp(student);
        identityService.applyIdentity(student, null);
        Student saved = studentRepository.save(student);
        return ResponseEntity.created(URI.create("/api/v1/students/" + saved.getId())).body(saved);
    }

    /** PUT /api/v1/students/{id} — full replacement. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public Student replace(@PathVariable Long id, @RequestBody Student updates) {
        Student existing = get(id);
        existing.setName(updates.getName());
        existing.setRollNo(updates.getRollNo());
        existing.setDepartment(updates.getDepartment());
        existing.setCourse(updates.getCourse());
        existing.setYearSession(updates.getYearSession());
        existing.setSubjectCode(updates.getSubjectCode());
        if (updates.getAttributes() != null && !updates.getAttributes().isEmpty()) {
            existing.setAttributes(new LinkedHashMap<>(updates.getAttributes()));
        }
        requireName(existing);
        identityService.applyIdentity(existing, null);
        return studentRepository.save(existing);
    }

    /**
     * PATCH /api/v1/students/{id} — partial update of whitelisted fields.
     *
     * <p>Besides the typed fields, the dynamic {@code attributes} map may be
     * patched: {@code {"attributes": {"batchNumber": "2024-A"}}} sets one
     * attribute, and sending {@code null} for it clears the whole map.</p>
     */
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public Student patch(@PathVariable Long id, @RequestBody Map<String, Object> changes) {
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("PATCH body must contain at least one field");
        }
        for (String key : changes.keySet()) {
            if (!PATCHABLE.contains(key) && !"attributes".equals(key)) {
                throw new IllegalArgumentException("Unknown or immutable field '" + key
                        + "'; allowed: " + PATCHABLE + ", attributes");
            }
        }
        Student existing = get(id);
        if (changes.containsKey("name")) existing.setName(stringValue(changes, "name"));
        if (changes.containsKey("rollNo")) existing.setRollNo(stringValue(changes, "rollNo"));
        if (changes.containsKey("department")) existing.setDepartment(stringValue(changes, "department"));
        if (changes.containsKey("course")) existing.setCourse(stringValue(changes, "course"));
        if (changes.containsKey("yearSession")) existing.setYearSession(stringValue(changes, "yearSession"));
        if (changes.containsKey("subjectCode")) existing.setSubjectCode(stringValue(changes, "subjectCode"));
        if (changes.containsKey("attributes")) existing.setAttributes(attributeMap(changes.get("attributes")));
        requireName(existing);
        identityService.applyIdentity(existing, null);
        return studentRepository.save(existing);
    }

    /**
     * GET /api/v1/students/identity — the institution's student-identification
     * rule. Returns the stored scheme, or an empty scheme when the institution
     * relies on auto-detection.
     */
    @GetMapping("/identity")
    public IdentitySchemeView identity() {
        return identityService.currentScheme()
                .map(scheme -> new IdentitySchemeView(scheme.getId(), scheme.describe(),
                        scheme.attributeList(), scheme.effectiveDisplayAttribute()))
                .orElseGet(() -> new IdentitySchemeView(null, "not configured", List.of(), null));
    }

    /**
     * PUT /api/v1/students/identity — declares which attributes identify a
     * student: one attribute (a registration number) or a combination (roll
     * number + batch number).
     */
    @PutMapping("/identity")
    @PreAuthorize("hasRole('ADMIN')")
    public IdentitySchemeView saveIdentity(@Valid @RequestBody IdentitySchemeView.IdentitySchemeRequest request) {
        StudentIdentityScheme scheme = identityService.saveScheme(request.label(), request.attributes());
        return new IdentitySchemeView(scheme.getId(), scheme.describe(), scheme.attributeList(),
                scheme.effectiveDisplayAttribute());
    }

    /** DELETE /api/v1/students/identity — back to auto-detection. */
    @DeleteMapping("/identity")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> clearIdentity() {
        identityService.clearScheme();
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/v1/students/imports/preview — analyses a roster file without
     * saving anything: which columns it has, which of them could identify a
     * student, and which single attributes or combinations would work.
     *
     * <p>This is what a client shows the institution before the import:
     * "your sheet has Registration Number, Batch Number and Department —
     * which one identifies a student?"</p>
     */
    @PostMapping(value = "/imports/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportPreview previewImport(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A non-empty 'file' part is required");
        }
        return excelImportService.previewStudents(file.getInputStream());
    }

    /** DELETE /api/v1/students/{id} — 204 No Content. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        get(id);
        studentRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * POST /api/v1/students/imports — Excel roster upload (multipart).
     *
     * @param file               the workbook
     * @param identityAttributes optional: the attributes that identify a
     *                           student in this file — one, or a combination.
     *                           When omitted, the institution's identity scheme
     *                           is used, and failing that the importer detects
     *                           a usable column itself.
     */
    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public ImportResult importExcel(@RequestParam("file") MultipartFile file,
                                     @RequestParam(required = false) String identityAttributes) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A non-empty 'file' part is required");
        }
        return excelImportService.importStudents(file.getInputStream(), splitAttributes(identityAttributes));
    }

    /** Splits a comma-separated list of attribute names. */
    private static List<String> splitAttributes(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        List<String> keys = new ArrayList<>();
        for (String part : raw.split(",")) {
            String key = part.trim();
            if (!key.isEmpty() && !keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** Reads the dynamic attribute map out of a PATCH body. */
    private static Map<String, String> attributeMap(Object value) {
        if (value == null) {
            return new LinkedHashMap<>();
        }
        if (!(value instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("Field 'attributes' must be an object of name/value pairs");
        }
        Map<String, String> attributes = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : raw.entrySet()) {
            if (entry.getKey() == null) {
                continue;
            }
            String key = StudentAttributes.normalize(String.valueOf(entry.getKey()));
            if (key.isEmpty()) {
                continue;
            }
            Object attributeValue = entry.getValue();
            if (attributeValue == null) {
                continue;
            }
            if (!(attributeValue instanceof String text)) {
                throw new IllegalArgumentException("Attribute '" + key + "' must be a string");
            }
            attributes.put(key, text.trim());
        }
        return attributes;
    }

    private static void requireName(Student student) {
        if (student.getName() == null || student.getName().isBlank()) {
            throw new IllegalArgumentException("Name must not be blank");
        }
    }

    private static Comparator<Student> studentComparator(String field) {
        return switch (field) {
            case "name" -> Comparator.<Student, String>comparing((Student s) -> nullToEmpty(s.getName()));
            case "rollNo" -> Comparator.<Student, String>comparing((Student s) -> nullToEmpty(s.getRollNo()));
            case "department" -> Comparator.<Student, String>comparing((Student s) -> nullToEmpty(s.getDepartment()));
            case "yearSession" -> Comparator.<Student, String>comparing((Student s) -> nullToEmpty(s.getYearSession()));
            case "subjectCode" -> Comparator.<Student, String>comparing((Student s) -> nullToEmpty(s.getSubjectCode()));
            default -> Comparator.<Student>comparingLong((Student s) -> s.getId() == null ? 0L : s.getId());
        };
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String stringValue(Map<String, Object> changes, String key) {
        Object value = changes.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("Field '" + key + "' must be a string or null");
        }
        return s.isBlank() ? null : s.trim();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
