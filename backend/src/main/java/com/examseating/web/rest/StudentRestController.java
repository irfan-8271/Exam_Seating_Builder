package com.examseating.web.rest;

import com.examseating.dto.IdentitySchemeView;
import com.examseating.dto.ImportPreview;
import com.examseating.dto.ImportResult;
import com.examseating.dto.PageResponse;
import com.examseating.dto.RosterSchemaView;
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
import java.util.Locale;
import java.util.Map;

/**
 * Primary v1 REST resource for the pure dynamic student roster and schema.
 */
@RestController
@RequestMapping("/api/v1/students")
@RequiredArgsConstructor
public class StudentRestController {

    private final StudentRepository studentRepository;
    private final ExcelImportService excelImportService;
    private final TenantSupport tenant;
    private final QuotaService quotas;
    private final StudentIdentityService identityService;

    /**
     * GET /api/v1/students — paged, searchable, dynamic-attribute-filterable collection.
     */
    @GetMapping
    public PageResponse<Student> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String filterAttribute,
            @RequestParam(required = false) String filterValue,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "asc") String direction) {
        List<Student> students = tenant.visible(studentRepository.findAll());

        if (!isBlank(filterAttribute) && !isBlank(filterValue)) {
            String attr = StudentAttributes.normalize(filterAttribute);
            String val = filterValue.trim();
            students = students.stream()
                    .filter(s -> {
                        String sv = StudentAttributes.value(s, attr);
                        return sv != null && sv.equalsIgnoreCase(val);
                    })
                    .toList();
        }

        if (!isBlank(q)) {
            String needle = q.trim().toLowerCase(Locale.ROOT);
            students = students.stream()
                    .filter(s -> matchesSearch(s, needle))
                    .toList();
        }

        if (!"asc".equalsIgnoreCase(direction) && !"desc".equalsIgnoreCase(direction)) {
            throw new IllegalArgumentException("direction must be 'asc' or 'desc'");
        }
        String sort = (sortBy == null || sortBy.isBlank()) ? "id" : sortBy.trim();
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

    /** POST /api/v1/students — 201 Created. */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public ResponseEntity<Student> create(@RequestBody Student student) {
        quotas.checkStudents(1);
        requireName(student);
        tenant.stamp(student);
        identityService.applyIdentity(student, null);
        validateIdentityForSave(student, null);
        Student saved = studentRepository.save(student);
        return ResponseEntity.created(URI.create("/api/v1/students/" + saved.getId())).body(saved);
    }

    /** PUT /api/v1/students/{id} — full replacement. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public Student replace(@PathVariable Long id, @RequestBody Student updates) {
        Student existing = get(id);
        existing.setName(updates.getName());
        existing.setAttributes(updates.getAttributes() == null
                ? new LinkedHashMap<>()
                : new LinkedHashMap<>(updates.getAttributes()));
        requireName(existing);
        identityService.applyIdentity(existing, null);
        validateIdentityForSave(existing, existing.getId());
        return studentRepository.save(existing);
    }

    /** PATCH /api/v1/students/{id} — partial update of {@code name} and/or {@code attributes}. */
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public Student patch(@PathVariable Long id, @RequestBody Map<String, Object> changes) {
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("PATCH body must contain at least one field");
        }
        for (String key : changes.keySet()) {
            if (!"name".equals(key) && !"attributes".equals(key)) {
                throw new IllegalArgumentException("Unknown or immutable field '" + key
                        + "'; allowed: name, attributes");
            }
        }
        Student existing = get(id);
        if (changes.containsKey("name")) {
            existing.setName(stringValue(changes, "name"));
        }
        if (changes.containsKey("attributes")) {
            existing.setAttributes(attributeMap(changes.get("attributes")));
        }
        requireName(existing);
        identityService.applyIdentity(existing, null);
        validateIdentityForSave(existing, existing.getId());
        return studentRepository.save(existing);
    }

    /** GET /api/v1/students/schema — live schema inspection across all students. */
    @GetMapping("/schema")
    public RosterSchemaView schema() {
        return identityService.inspectSchema();
    }

    /** GET /api/v1/students/identity — current identity, cohort, and scope scheme. */
    @GetMapping("/identity")
    public IdentitySchemeView identity() {
        return identityService.currentScheme()
                .map(scheme -> new IdentitySchemeView(
                        scheme.getId(),
                        scheme.describe(),
                        scheme.attributeList(),
                        scheme.effectiveDisplayAttribute(),
                        scheme.classmateAttributeList(),
                        scheme.getScopeAttribute()))
                .orElseGet(() -> new IdentitySchemeView(null, "not configured", List.of(), null, List.of(), null));
    }

    /** PUT /api/v1/students/identity — updates and live-rekeys the institution's roster scheme. */
    @PutMapping("/identity")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public IdentitySchemeView saveIdentity(@Valid @RequestBody IdentitySchemeView.IdentitySchemeRequest request) {
        StudentIdentityScheme scheme = identityService.saveScheme(
                request.label(),
                request.attributes(),
                request.displayAttribute(),
                request.classmateAttributes(),
                request.scopeAttribute());
        return new IdentitySchemeView(
                scheme.getId(),
                scheme.describe(),
                scheme.attributeList(),
                scheme.effectiveDisplayAttribute(),
                scheme.classmateAttributeList(),
                scheme.getScopeAttribute());
    }

    /** DELETE /api/v1/students/identity — clears stored scheme. */
    @DeleteMapping("/identity")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> clearIdentity() {
        identityService.clearScheme();
        return ResponseEntity.noContent().build();
    }

    /** POST /api/v1/students/imports/preview — inspects an uploaded Excel file without saving. */
    @PostMapping(value = "/imports/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportPreview previewImport(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A non-empty 'file' part is required");
        }
        return excelImportService.previewStudents(file.getInputStream());
    }

    /** POST /api/v1/students/imports — imports an Excel roster and optionally persists scheme rules. */
    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public ImportResult importExcel(@RequestParam("file") MultipartFile file,
                                    @RequestParam(required = false) String identityAttributes,
                                    @RequestParam(required = false) String classmateAttributes,
                                    @RequestParam(required = false) String scopeAttribute) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A non-empty 'file' part is required");
        }
        List<String> identity = splitAttributes(identityAttributes);
        List<String> classmates = splitAttributes(classmateAttributes);
        ImportResult result = excelImportService.importStudents(file.getInputStream(), identity);
        if ((!classmates.isEmpty() || !isBlank(scopeAttribute)) && result.imported() > 0) {
            List<String> idToSave = !identity.isEmpty() ? identity : identityService.currentAttributes();
            if (idToSave.isEmpty()) {
                RosterSchemaView inspected = identityService.inspectSchema();
                if (!inspected.suggestedIdentities().isEmpty()) {
                    idToSave = inspected.suggestedIdentities().get(0).attributes();
                }
            }
            if (!idToSave.isEmpty()) {
                identityService.saveScheme(
                        null,
                        idToSave,
                        null,
                        classmates.isEmpty() ? null : classmates,
                        isBlank(scopeAttribute) ? null : scopeAttribute.trim());
            }
        }
        return result;
    }

    /** DELETE /api/v1/students/{id} — removes one student. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        get(id);
        studentRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** DELETE /api/v1/students — clears all students of the current institution. */
    @DeleteMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteAll() {
        List<Student> owned = tenant.visible(studentRepository.findAll());
        if (!owned.isEmpty()) {
            studentRepository.deleteAll(owned);
        }
        return ResponseEntity.noContent().build();
    }

    private void validateIdentityForSave(Student student, Long currentStudentId) {
        List<String> required = identityService.currentAttributes();
        if (!required.isEmpty() && student.getIdentityKey() == null) {
            throw new IllegalArgumentException("Missing required identifying attribute(s): "
                    + String.join(" + ", required));
        }
        String key = student.getIdentityKey();
        if (key != null && !key.isBlank()) {
            boolean duplicate = tenant.visible(studentRepository.findAll()).stream()
                    .anyMatch(other -> (currentStudentId == null || !currentStudentId.equals(other.getId()))
                            && key.equalsIgnoreCase(other.getIdentityKey()));
            if (duplicate) {
                throw new IllegalArgumentException(
                        "A student with identity '" + key.replace("|", " · ") + "' already exists.");
            }
        }
    }

    private static boolean matchesSearch(Student s, String needle) {
        if (s.getName() != null && s.getName().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        if (s.getIdentityKey() != null && s.getIdentityKey().toLowerCase(Locale.ROOT).contains(needle)) {
            return true;
        }
        for (String v : StudentAttributes.allPopulatedAttributes(s).values()) {
            if (v.toLowerCase(Locale.ROOT).contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static Comparator<Student> studentComparator(String field) {
        if ("id".equalsIgnoreCase(field)) {
            return Comparator.comparingLong(s -> s.getId() == null ? 0L : s.getId());
        }
        if ("name".equalsIgnoreCase(field)) {
            return Comparator.comparing(s -> nullToEmpty(s.getName()), String.CASE_INSENSITIVE_ORDER);
        }
        if ("identityKey".equalsIgnoreCase(field)) {
            return Comparator.comparing(s -> nullToEmpty(s.getIdentityKey()), String.CASE_INSENSITIVE_ORDER);
        }
        String attrKey = StudentAttributes.normalize(field);
        return Comparator.comparing(s -> nullToEmpty(StudentAttributes.value(s, attrKey)),
                String.CASE_INSENSITIVE_ORDER);
    }

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
            if (key.isEmpty() || StudentAttributes.NAME.equals(key)) {
                continue;
            }
            Object attributeValue = entry.getValue();
            if (attributeValue == null) {
                continue;
            }
            if (!(attributeValue instanceof String text)) {
                throw new IllegalArgumentException("Attribute '" + key + "' must be a string");
            }
            if (!text.isBlank()) {
                attributes.put(key, text.trim());
            }
        }
        return attributes;
    }

    private static void requireName(Student student) {
        if (student.getName() == null || student.getName().isBlank()) {
            throw new IllegalArgumentException("Name must not be blank");
        }
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
