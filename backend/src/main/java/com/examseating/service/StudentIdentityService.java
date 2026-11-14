package com.examseating.service;

import com.examseating.dto.IdentitySchemeView;
import com.examseating.dto.ImportPreview;
import com.examseating.dto.RosterSchemaView;
import com.examseating.entity.Student;
import com.examseating.entity.StudentAttributes;
import com.examseating.entity.StudentIdentityScheme;
import com.examseating.repository.CohortRuleRepository;
import com.examseating.repository.StudentIdentitySchemeRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.tenancy.TenantSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Discovers, validates, persists, and live-rekeys an institution's dynamic
 * student identity scheme, classmate cohort attributes, and optional exam-scope
 * attribute.
 */
@Service
public class StudentIdentityService {

    public static final String KEY_SEPARATOR = "|";

    private static final int MAX_SUGGESTIONS = 12;
    private static final int MAX_SAMPLES = 3;
    private static final int MAX_SCOPE_DISTINCT_VALUES = 60;

    private final StudentIdentitySchemeRepository schemeRepository;
    private final TenantSupport tenant;
    private final StudentRepository studentRepository;
    private CohortRuleRepository cohortRuleRepository;
    private final ConcurrentHashMap<Long, Map<String, String>> recentColumnLabels = new ConcurrentHashMap<>();

    public StudentIdentityService(StudentIdentitySchemeRepository schemeRepository, TenantSupport tenant) {
        this(schemeRepository, tenant, null);
    }

    @Autowired
    public StudentIdentityService(StudentIdentitySchemeRepository schemeRepository,
                                  TenantSupport tenant,
                                  StudentRepository studentRepository) {
        this.schemeRepository = schemeRepository;
        this.tenant = tenant;
        this.studentRepository = studentRepository;
    }

    /**
     * Wires the bucket-refinement repository. Kept as a setter so the service can
     * still be constructed directly in unit tests without a Spring context.
     */
    @Autowired
    public void setCohortRuleRepository(CohortRuleRepository cohortRuleRepository) {
        this.cohortRuleRepository = cohortRuleRepository;
    }

    @Transactional(readOnly = true)
    public Optional<StudentIdentityScheme> currentScheme() {
        return schemeRepository.findByInstitutionId(tenant.currentId());
    }

    @Transactional(readOnly = true)
    public List<String> currentAttributes() {
        return currentScheme().map(StudentIdentityScheme::attributeList).orElseGet(List::of);
    }

    @Transactional(readOnly = true)
    public List<String> currentClassmateAttributes() {
        return currentScheme().map(StudentIdentityScheme::classmateAttributeList).orElseGet(List::of);
    }

    @Transactional(readOnly = true)
    public String currentScopeAttribute() {
        return currentScheme().map(StudentIdentityScheme::getScopeAttribute).orElse(null);
    }

    @Transactional
    public StudentIdentityScheme saveScheme(String label, List<String> attributes) {
        return saveScheme(label, attributes, null, null, null);
    }

    @Transactional
    public StudentIdentityScheme saveScheme(String label, List<String> attributes, List<String> classmateAttributes) {
        return saveScheme(label, attributes, null, classmateAttributes, null);
    }

    @Transactional
    public StudentIdentityScheme saveScheme(String label,
                                            List<String> attributes,
                                            String displayAttribute,
                                            List<String> classmateAttributes) {
        return saveScheme(label, attributes, displayAttribute, classmateAttributes, null);
    }

    /**
     * Validates against existing students, atomically re-keys all students on
     * file, and persists the updated {@link StudentIdentityScheme}.
     */
    @Transactional
    public StudentIdentityScheme saveScheme(String label,
                                            List<String> attributes,
                                            String displayAttribute,
                                            List<String> classmateAttributes,
                                            String scopeAttribute) {
        List<String> keys = normalizeKeys(attributes);
        if (keys.isEmpty()) {
            throw new IllegalArgumentException(
                    "An identity scheme needs at least one attribute to identify students");
        }
        List<String> classmateKeys = classmateAttributes == null ? null : normalizeKeys(classmateAttributes);
        String normScope = scopeAttribute == null ? null : StudentAttributes.normalize(scopeAttribute);
        if (normScope != null && normScope.isEmpty()) {
            normScope = null;
        }

        if (studentRepository != null) {
            List<Student> existing = tenant.visible(studentRepository.findAll());
            if (!existing.isEmpty()) {
                Map<String, Student> seen = new LinkedHashMap<>();
                Map<Long, String> recomputed = new LinkedHashMap<>();
                for (Student student : existing) {
                    for (String key : keys) {
                        String val = StudentAttributes.value(student, key);
                        if (val == null || val.isBlank()) {
                            throw new IllegalArgumentException(
                                    "Cannot identify students by " + String.join(" + ", keys)
                                            + ": student '" + fallbackName(student)
                                            + "' has no value for '" + key + "'.");
                        }
                    }
                    String candidateKey = computeIdentityKey(student, keys);
                    Student clash = seen.putIfAbsent(candidateKey, student);
                    if (clash != null) {
                        throw new IllegalArgumentException(
                                "Cannot identify students by " + String.join(" + ", keys)
                                        + ": duplicate identity '" + candidateKey.replace("|", " · ")
                                        + "' shared by '" + fallbackName(clash) + "' and '"
                                        + fallbackName(student) + "'. Select an additional attribute to make it unique.");
                    }
                    recomputed.put(student.getId(), candidateKey);
                }

                if (classmateKeys != null && !classmateKeys.isEmpty()) {
                    List<String> missingClassmateCols = new ArrayList<>();
                    for (String ck : classmateKeys) {
                        boolean anyPresent = existing.stream()
                                .anyMatch(s -> notBlank(StudentAttributes.value(s, ck)));
                        if (!anyPresent) {
                            missingClassmateCols.add(ck);
                        }
                    }
                    if (!missingClassmateCols.isEmpty()) {
                        throw new IllegalArgumentException(
                                "Classmate grouping attribute(s) " + missingClassmateCols
                                        + " have no values on any student in the roster.");
                    }
                }

                for (Student student : existing) {
                    student.setIdentityKey(null);
                }
                studentRepository.saveAllAndFlush(existing);
                for (Student student : existing) {
                    student.setIdentityKey(recomputed.get(student.getId()));
                }
                studentRepository.saveAll(existing);
            }
        }

        StudentIdentityScheme scheme = schemeRepository.findByInstitutionId(tenant.currentId())
                .orElseGet(StudentIdentityScheme::new);
        List<String> previousKeys = scheme.attributeList();
        scheme.setInstitutionId(tenant.currentId());
        if (label != null && !label.isBlank()) {
            scheme.setLabel(label.trim());
        } else if (!keys.equals(previousKeys)) {
            scheme.setLabel(null);
        }
        scheme.setAttributeList(keys);

        String normDisplay = StudentAttributes.normalize(displayAttribute);
        if (!normDisplay.isEmpty() && keys.contains(normDisplay)) {
            scheme.setDisplayAttribute(normDisplay);
        } else {
            scheme.setDisplayAttribute(keys.size() == 1 ? keys.get(0) : null);
        }

        if (classmateKeys != null) {
            List<String> previousClassmates = scheme.classmateAttributeList();
            scheme.setClassmateAttributeList(classmateKeys);
            if (!previousClassmates.equals(classmateKeys) && cohortRuleRepository != null) {
                // Bucket-level refinements are keyed by the base classmate
                // attributes, so changing the base rule invalidates them.
                cohortRuleRepository.deleteByInstitutionId(tenant.currentId());
            }
        }
        if (scopeAttribute != null) {
            scheme.setScopeAttribute(normScope);
        }

        Long instKey = tenant.currentId() == null ? -1L : tenant.currentId();
        Map<String, String> cachedLabels = recentColumnLabels.get(instKey);
        if (cachedLabels != null && !cachedLabels.isEmpty()) {
            scheme.mergeColumnLabels(cachedLabels);
        }
        return schemeRepository.save(scheme);
    }

    @Transactional
    public void recordColumnLabels(Map<String, String> labels) {
        if (labels == null || labels.isEmpty()) {
            return;
        }
        Long instKey = tenant.currentId() == null ? -1L : tenant.currentId();
        Map<String, String> merged = new LinkedHashMap<>(
                recentColumnLabels.getOrDefault(instKey, Map.of()));
        labels.forEach((k, v) -> {
            String nk = StudentAttributes.normalize(k);
            if (!nk.isEmpty() && v != null && !v.isBlank()) {
                merged.put(nk, v.trim());
            }
        });
        recentColumnLabels.put(instKey, Map.copyOf(merged));
        if (studentRepository == null) {
            return;
        }
        schemeRepository.findByInstitutionId(tenant.currentId()).ifPresent(scheme -> {
            scheme.mergeColumnLabels(labels);
            schemeRepository.save(scheme);
        });
    }

    @Transactional
    public void clearScheme() {
        Long instKey = tenant.currentId() == null ? -1L : tenant.currentId();
        recentColumnLabels.remove(instKey);
        schemeRepository.deleteByInstitutionId(tenant.currentId());
        // Bucket-level cohort refinements are defined against the base classmate
        // attributes, so they cannot outlive the scheme they refine.
        if (cohortRuleRepository != null) {
            cohortRuleRepository.deleteByInstitutionId(tenant.currentId());
        }
    }

    /** Builds the canonical identity key of a student from explicit or configured attributes. */
    public String identityKey(Student student, List<String> attributes) {
        if (student == null) {
            return null;
        }
        List<String> keys = (attributes == null || attributes.isEmpty())
                ? currentAttributes()
                : normalizeKeys(attributes);
        if (keys.isEmpty()) {
            Set<String> takenKeys = new HashSet<>();
            if (studentRepository != null) {
                for (Student other : tenant.visible(studentRepository.findAll())) {
                    if (student.getId() != null && student.getId().equals(other.getId())) {
                        continue;
                    }
                    if (other.getIdentityKey() != null && !other.getIdentityKey().isBlank()) {
                        takenKeys.add(other.getIdentityKey().toUpperCase(Locale.ROOT));
                    }
                }
            }
            Map<String, String> populated = StudentAttributes.allPopulatedAttributes(student);
            for (String candidate : populated.keySet()) {
                if (StudentAttributes.looksLikeIdentifier(candidate)) {
                    String candidateKey = computeIdentityKey(student, List.of(candidate));
                    if (candidateKey != null && !takenKeys.contains(candidateKey.toUpperCase(Locale.ROOT))) {
                        return candidateKey;
                    }
                }
            }
            if (!populated.isEmpty()) {
                String composite = computeIdentityKey(student, new ArrayList<>(populated.keySet()));
                if (composite != null && !takenKeys.contains(composite.toUpperCase(Locale.ROOT))) {
                    return composite;
                }
            }
            String rawFallback = fallbackName(student) + "|" + populated;
            return "AUTO-" + sha256Short(rawFallback);
        }
        return computeIdentityKey(student, keys);
    }

    @Transactional
    public Student applyIdentity(Student student, List<String> attributes) {
        student.setIdentityKey(identityKey(student, attributes));
        return student;
    }

    /** Inspects the live student dataset schema, unique ID options, classmate cohorts, and scope values. */
    @Transactional(readOnly = true)
    public RosterSchemaView inspectSchema() {
        List<Student> students = studentRepository == null
                ? List.of()
                : tenant.visible(studentRepository.findAll());
        Optional<StudentIdentityScheme> optScheme = currentScheme();
        Long instKey = tenant.currentId() == null ? -1L : tenant.currentId();
        Map<String, String> savedLabels = new LinkedHashMap<>(
                recentColumnLabels.getOrDefault(instKey, Map.of()));
        optScheme.ifPresent(s -> savedLabels.putAll(s.columnLabelMap()));

        Set<String> keys = new LinkedHashSet<>();
        for (Student s : students) {
            keys.addAll(StudentAttributes.allPopulatedAttributes(s).keySet());
        }
        optScheme.ifPresent(s -> {
            keys.addAll(s.attributeList());
            keys.addAll(s.classmateAttributeList());
            if (s.getScopeAttribute() != null && !s.getScopeAttribute().isBlank()) {
                keys.add(StudentAttributes.normalize(s.getScopeAttribute()));
            }
            keys.addAll(savedLabels.keySet());
        });
        keys.remove(StudentAttributes.NAME);

        int total = students.size();
        List<ImportPreview.ColumnPreview> columns = new ArrayList<>();
        Map<String, List<String>> distinctValuesByAttribute = new LinkedHashMap<>();

        for (String key : keys) {
            int present = 0;
            Map<String, String> canonicalToDisplay = new LinkedHashMap<>();
            List<String> samples = new ArrayList<>();
            for (Student s : students) {
                String v = StudentAttributes.value(s, key);
                if (notBlank(v)) {
                    present++;
                    canonicalToDisplay.putIfAbsent(canonicalComponent(v), v.trim());
                    if (samples.size() < MAX_SAMPLES && !samples.contains(v.trim())) {
                        samples.add(v.trim());
                    }
                }
            }
            if (total > 0 && present == 0
                    && optScheme.map(sc -> !sc.attributeList().contains(key)
                            && !sc.classmateAttributeList().contains(key)
                            && !key.equals(sc.getScopeAttribute())).orElse(true)) {
                continue;
            }
            boolean complete = total > 0 && present == total;
            boolean unique = complete && canonicalToDisplay.size() == total;
            String title = savedLabels.getOrDefault(key, StudentAttributes.defaultTitle(key));
            columns.add(new ImportPreview.ColumnPreview(
                    key, title, "attribute", complete, unique, canonicalToDisplay.size(), samples));
            if (!unique && canonicalToDisplay.size() > 0 && canonicalToDisplay.size() <= MAX_SCOPE_DISTINCT_VALUES) {
                distinctValuesByAttribute.put(key, List.copyOf(canonicalToDisplay.values()));
            }
        }

        List<ImportPreview.IdentityOption> suggestedIdentities = suggestIdentitiesForStudents(students, columns);
        List<ImportPreview.IdentityOption> suggestedGroups = suggestClassmateGroupsForStudents(students, columns);

        IdentitySchemeView schemeView = optScheme
                .map(s -> new IdentitySchemeView(
                        s.getId(),
                        s.describe(),
                        s.attributeList(),
                        s.effectiveDisplayAttribute(),
                        s.classmateAttributeList(),
                        s.getScopeAttribute()))
                .orElseGet(() -> new IdentitySchemeView(null, "not configured", List.of(), null, List.of(), null));

        return new RosterSchemaView(
                columns,
                suggestedIdentities,
                suggestedGroups,
                schemeView,
                distinctValuesByAttribute,
                total);
    }

    /** Resolves column labels for exports and UI tables. */
    @Transactional(readOnly = true)
    public Map<String, String> effectiveColumnLabels() {
        Long instKey = tenant.currentId() == null ? -1L : tenant.currentId();
        Map<String, String> labels = new LinkedHashMap<>(recentColumnLabels.getOrDefault(instKey, Map.of()));
        if (schemeRepository != null) {
            currentScheme().ifPresent(s -> labels.putAll(s.columnLabelMap()));
        }
        return labels;
    }

    public String displayLabel(Student student) {
        Optional<StudentIdentityScheme> scheme = schemeRepository == null ? Optional.empty() : currentScheme();
        return StudentAttributes.displayLabel(student, scheme.orElse(null));
    }

    private static String computeIdentityKey(Student student, List<String> keys) {
        List<String> components = new ArrayList<>(keys.size());
        for (String attribute : keys) {
            String value = StudentAttributes.value(student, attribute);
            if (value == null || value.isBlank()) {
                return null;
            }
            components.add(canonicalComponent(value));
        }
        String key = String.join(KEY_SEPARATOR, components);
        if (key.length() <= 255) {
            return key;
        }
        return key.substring(0, 200) + "#" + sha256Short(key);
    }

    private static List<ImportPreview.IdentityOption> suggestIdentitiesForStudents(
            List<Student> students, List<ImportPreview.ColumnPreview> columns) {
        List<ImportPreview.IdentityOption> options = new ArrayList<>();
        if (students.isEmpty()) {
            return options;
        }
        List<ImportPreview.ColumnPreview> completeCols = columns.stream()
                .filter(ImportPreview.ColumnPreview::complete)
                .toList();

        completeCols.stream()
                .filter(ImportPreview.ColumnPreview::unique)
                .sorted(Comparator.comparing((ImportPreview.ColumnPreview c) ->
                        !StudentAttributes.looksLikeIdentifier(c.key())))
                .forEach(c -> {
                    if (options.size() < MAX_SUGGESTIONS) {
                        options.add(new ImportPreview.IdentityOption(List.of(c.key()), c.title(), true));
                    }
                });

        for (int i = 0; i < completeCols.size() && options.size() < MAX_SUGGESTIONS; i++) {
            for (int j = i + 1; j < completeCols.size() && options.size() < MAX_SUGGESTIONS; j++) {
                var a = completeCols.get(i);
                var b = completeCols.get(j);
                if (a.unique() && b.unique()) {
                    continue;
                }
                List<String> combo = List.of(a.key(), b.key());
                if (isUniqueStudentCombo(students, combo)) {
                    options.add(new ImportPreview.IdentityOption(combo, a.title() + " + " + b.title(), true));
                }
            }
        }

        for (int i = 0; i < completeCols.size() && options.size() < MAX_SUGGESTIONS; i++) {
            for (int j = i + 1; j < completeCols.size() && options.size() < MAX_SUGGESTIONS; j++) {
                for (int k = j + 1; k < completeCols.size() && options.size() < MAX_SUGGESTIONS; k++) {
                    var a = completeCols.get(i);
                    var b = completeCols.get(j);
                    var c = completeCols.get(k);
                    if (a.unique() || b.unique() || c.unique()) {
                        continue;
                    }
                    List<String> combo = List.of(a.key(), b.key(), c.key());
                    if (isUniqueStudentCombo(students, combo)) {
                        options.add(new ImportPreview.IdentityOption(
                                combo, a.title() + " + " + b.title() + " + " + c.title(), true));
                    }
                }
            }
        }
        return options;
    }

    private static List<ImportPreview.IdentityOption> suggestClassmateGroupsForStudents(
            List<Student> students, List<ImportPreview.ColumnPreview> columns) {
        List<ImportPreview.IdentityOption> options = new ArrayList<>();
        if (students.size() < 2) {
            return options;
        }
        List<ImportPreview.ColumnPreview> candidates = columns.stream()
                .filter(c -> !c.unique())
                .filter(c -> !StudentAttributes.looksLikeIdentifier(c.key()))
                .filter(c -> !StudentAttributes.looksLikeScopeField(c.key()))
                .filter(c -> c.distinctValues() > 1 && c.distinctValues() < students.size())
                .toList();

        // Prefer multi-attribute academic cohorts first when available, plus single-attribute options
        for (int i = 0; i < candidates.size() && options.size() < MAX_SUGGESTIONS; i++) {
            for (int j = i + 1; j < candidates.size() && options.size() < MAX_SUGGESTIONS; j++) {
                var a = candidates.get(i);
                var b = candidates.get(j);
                List<String> combo = List.of(a.key(), b.key());
                if (!isUniqueStudentCombo(students, combo)) {
                    options.add(new ImportPreview.IdentityOption(combo, a.title() + " + " + b.title(), false));
                }
            }
        }
        for (int i = 0; i < candidates.size() && options.size() < MAX_SUGGESTIONS; i++) {
            for (int j = i + 1; j < candidates.size() && options.size() < MAX_SUGGESTIONS; j++) {
                for (int k = j + 1; k < candidates.size() && options.size() < MAX_SUGGESTIONS; k++) {
                    var a = candidates.get(i);
                    var b = candidates.get(j);
                    var c = candidates.get(k);
                    List<String> combo = List.of(a.key(), b.key(), c.key());
                    if (!isUniqueStudentCombo(students, combo)) {
                        options.add(new ImportPreview.IdentityOption(
                                combo, a.title() + " + " + b.title() + " + " + c.title(), false));
                    }
                }
            }
        }
        for (var c : candidates) {
            if (options.size() >= MAX_SUGGESTIONS) {
                break;
            }
            options.add(new ImportPreview.IdentityOption(List.of(c.key()), c.title(), false));
        }
        return options;
    }

    private static boolean isUniqueStudentCombo(List<Student> students, List<String> keys) {
        Set<String> seen = new HashSet<>();
        for (Student s : students) {
            List<String> parts = new ArrayList<>(keys.size());
            for (String k : keys) {
                String v = StudentAttributes.value(s, k);
                if (v == null || v.isBlank()) {
                    return false;
                }
                parts.add(canonicalComponent(v));
            }
            if (!seen.add(String.join(KEY_SEPARATOR, parts))) {
                return false;
            }
        }
        return seen.size() == students.size();
    }

    private static List<String> normalizeKeys(List<String> rawList) {
        List<String> keys = new ArrayList<>();
        if (rawList == null) {
            return keys;
        }
        for (String raw : rawList) {
            String key = StudentAttributes.normalize(raw);
            if (!key.isEmpty() && !StudentAttributes.NAME.equals(key) && !keys.contains(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static String canonicalComponent(String value) {
        return value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.ROOT);
    }

    private static String sha256Short(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String fallbackName(Student student) {
        if (student.getName() != null && !student.getName().isBlank()) {
            return student.getName();
        }
        String label = StudentAttributes.displayLabel(student);
        return label.isEmpty() ? "Student #" + student.getId() : label;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
