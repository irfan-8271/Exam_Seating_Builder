package com.examseating.service;

import com.examseating.entity.Student;
import com.examseating.entity.StudentAttributes;
import com.examseating.entity.StudentIdentityScheme;
import com.examseating.repository.StudentIdentitySchemeRepository;
import com.examseating.tenancy.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Decides — and remembers — how an institution identifies its students.
 *
 * <p>The institution owns that decision, not the code. It can be expressed in
 * three ways, in order of precedence:</p>
 * <ol>
 *   <li><b>Per import</b> — the upload names the identifying attributes
 *       explicitly ({@code identityAttributes}). This is what the import
 *       preview is for: the file is analysed, the candidate columns are
 *       shown, and the user picks one attribute or a combination.</li>
 *   <li><b>Per institution</b> — a stored {@link StudentIdentityScheme} says
 *       which attributes identify a student. Every later upload and every
 *       manually added student uses it.</li>
 *   <li><b>Auto-detected</b> — nothing configured: the importer looks for a
 *       column that is known to be an identifier, else the single column that
 *       is unique across the file.</li>
 * </ol>
 *
 * <p>Whatever the source, the chosen attribute values are folded into a
 * canonical {@link Student#getIdentityKey() key} that is unique per
 * institution — that is what stops the same student being imported twice
 * under a different column layout.</p>
 */
@Service
@RequiredArgsConstructor
public class StudentIdentityService {

    /** Separates the components of a composite identity key. */
    public static final String KEY_SEPARATOR = "|";

    /** Longest component kept in a composite identity key. */
    private static final int MAX_COMPONENT = 60;

    private final StudentIdentitySchemeRepository schemeRepository;
    private final TenantSupport tenant;

    /** The scheme configured for the current institution, if any. */
    @Transactional(readOnly = true)
    public Optional<StudentIdentityScheme> currentScheme() {
        return schemeRepository.findByInstitutionId(tenant.currentId());
    }

    /** The identifying attributes configured for the current institution. */
    @Transactional(readOnly = true)
    public List<String> currentAttributes() {
        return currentScheme().map(StudentIdentityScheme::attributeList).orElseGet(List::of);
    }

    /**
     * Stores the identity scheme of the current institution, replacing any
     * previous one.
     *
     * @param label   human-readable name, may be null
     * @param attributes the identifying attributes, in the order they are joined
     */
    @Transactional
    public StudentIdentityScheme saveScheme(String label, List<String> attributes) {
        List<String> keys = new ArrayList<>();
        for (String raw : attributes == null ? List.<String>of() : attributes) {
            String key = StudentAttributes.normalize(raw);
            if (!key.isEmpty() && !keys.contains(key)) {
                keys.add(key);
            }
        }
        if (keys.isEmpty()) {
            throw new IllegalArgumentException(
                    "An identity scheme needs at least one attribute to identify students");
        }
        StudentIdentityScheme scheme = schemeRepository.findByInstitutionId(tenant.currentId())
                .orElseGet(StudentIdentityScheme::new);
        scheme.setInstitutionId(tenant.currentId());
        scheme.setLabel(label == null || label.isBlank() ? null : label.trim());
        scheme.setAttributeList(keys);
        scheme.setDisplayAttribute(scheme.effectiveDisplayAttribute());
        return schemeRepository.save(scheme);
    }

    /** Removes the scheme — the institution goes back to auto-detection. */
    @Transactional
    public void clearScheme() {
        schemeRepository.deleteByInstitutionId(tenant.currentId());
    }

    /**
     * Builds the canonical identity key of a student from the given
     * attributes. The key is case-insensitive and whitespace-insensitive, so
     * {@code "CSE 2025 001"} and {@code "cse-2025-001"} are the same
     * identity only when the underlying values are equal — the components are
     * compared case-insensitively.
     *
     * @return the key, or {@code null} when one of the attributes has no value
     */
    public String identityKey(Student student, List<String> attributes) {
        if (student == null) {
            return null;
        }
        List<String> keys = (attributes == null || attributes.isEmpty())
                ? currentAttributes()
                : attributes;
        if (keys.isEmpty()) {
            // No scheme configured: the roll / registration number identifies
            // the student. That is the historical behaviour and a sane
            // default.
            if (student.getRollNo() == null || student.getRollNo().isBlank()) {
                return null;
            }
            keys = List.of(StudentAttributes.ROLL_NO);
        }
        List<String> components = new ArrayList<>(keys.size());
        for (String attribute : keys) {
            String value = StudentAttributes.value(student, attribute);
            if (value == null || value.isBlank()) {
                // An incomplete identity cannot identify anybody.
                return null;
            }
            components.add(canonicalComponent(value));
        }
        String key = String.join(KEY_SEPARATOR, components);
        return key.length() > 255 ? key.substring(0, 255) : key;
    }

    /**
     * Recomputes and stores the identity key of a student using the given
     * attributes (or the institution's scheme when none are given).
     *
     * <p>When the institution has configured no scheme at all, a student's
     * roll / registration number identifies them — that is the historical
     * behaviour and a sane default. A student with neither a scheme nor a
     * roll number simply has no identity key: nothing claims to identify
     * them uniquely.</p>
     */
    @Transactional
    public Student applyIdentity(Student student, List<String> attributes) {
        student.setIdentityKey(identityKey(student, attributes));
        return student;
    }

    /** Canonical form of one identity component: trimmed, collapsed, upper-cased. */
    private static String canonicalComponent(String value) {
        String canonical = value.trim().replaceAll("\\s+", " ").toUpperCase(java.util.Locale.ROOT);
        return canonical.length() > MAX_COMPONENT ? canonical.substring(0, MAX_COMPONENT) : canonical;
    }

    /** The label to show for a student — the institution's identifier, or a fallback. */
    public String displayLabel(Student student) {
        return StudentAttributes.displayLabel(student);
    }
}
