package com.examseating.entity;

import com.examseating.tenancy.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * The institution's rule for identifying a student.
 *
 * <p>This is the answer to "some institutions use roll numbers, others
 * registration numbers, some a batch plus a serial, some no identifier at
 * all". Instead of hard-coding one column, each institution declares which
 * of its spreadsheet's attributes together identify a student:</p>
 *
 * <ul>
 *   <li>{@code attributes = ["rollno"]} — the roll number alone</li>
 *   <li>{@code attributes = ["registrationnumber"]} — the registration number</li>
 *   <li>{@code attributes = ["rollno", "batchnumber"]} — roll number + batch</li>
 *   <li>{@code attributes = ["registrationnumber", "department"]} — registration + department</li>
 * </ul>
 *
 * <p>The order matters: it is the order the values are joined in to build
 * {@link Student#identityKey()}. {@code displayAttribute} names the attribute
 * shown to a human (the plan, the exports, the door notices); when it is not
 * set, the first attribute is displayed.</p>
 *
 * <p>One scheme per institution. An institution without a scheme falls back
 * to auto-detection at import time, and the import preview always shows what
 * was detected.</p>
 */
@Entity
@Table(name = "student_identity_schemes",
       uniqueConstraints = @UniqueConstraint(name = "uk_identity_scheme_institution",
               columnNames = "institution_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class StudentIdentityScheme implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /** Owning institution — one scheme per institution. */
    @Column(name = "institution_id")
    private Long institutionId;

    /** Human-readable name of the scheme, e.g. "Roll Number + Batch Number". */
    @Size(max = 120)
    @Column(name = "label", length = 120)
    private String label;

    /**
     * The identifying attributes, comma separated, in the order they are
     * joined into the identity key. Stored as text so the list can be
     * extended without a schema change.
     */
    @Size(max = 255)
    @Column(name = "identity_attributes", length = 255)
    private String attributes;

    /** Which attribute is shown to humans; empty means "the first one". */
    @Size(max = 64)
    @Column(name = "display_attribute", length = 64)
    private String displayAttribute;

    /** Which attribute represents classmate/cohort grouping; empty means default. */
    @Size(max = 255)
    @Column(name = "classmate_attributes", length = 255)
    private String classmateAttributes;

    @Column(name = "created_at")
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        updatedAt = Instant.now();
    }

    /** The identifying attributes as a list, normalized and de-duplicated. */
    public List<String> attributeList() {
        List<String> list = new ArrayList<>();
        if (attributes == null || attributes.isBlank()) {
            return list;
        }
        for (String part : attributes.split(",")) {
            String key = StudentAttributes.normalize(part);
            if (!key.isEmpty() && !list.contains(key)) {
                list.add(key);
            }
        }
        return list;
    }

    /** Stores the attribute list, preserving its order. */
    public void setAttributeList(List<String> list) {
        if (list == null || list.isEmpty()) {
            this.attributes = null;
            return;
        }
        List<String> normalized = new ArrayList<>();
        for (String part : list) {
            String key = StudentAttributes.normalize(part);
            if (!key.isEmpty() && !normalized.contains(key)) {
                normalized.add(key);
            }
        }
        this.attributes = normalized.isEmpty() ? null : String.join(",", normalized);
    }

    /** A readable label for the scheme, e.g. "Roll Number + Batch Number". */
    public String describe() {
        List<String> keys = attributeList();
        if (keys.isEmpty()) {
            return label == null || label.isBlank() ? "not configured" : label;
        }
        if (label != null && !label.isBlank()) {
            return label;
        }
        return String.join(" + ", keys);
    }

    /** The attribute shown to humans — the display attribute, else the first one. */
    public String effectiveDisplayAttribute() {
        String display = StudentAttributes.normalize(displayAttribute);
        if (!display.isEmpty() && attributeList().contains(display)) {
            return display;
        }
        List<String> keys = attributeList();
        return keys.isEmpty() ? null : keys.get(0);
    }

    /** Returns the classmate/cohort grouping attributes in order. */
    public List<String> classmateAttributeList() {
        if (classmateAttributes == null || classmateAttributes.isBlank()) {
            return List.of();
        }
        List<String> list = new ArrayList<>();
        for (String part : classmateAttributes.split(",")) {
            String key = StudentAttributes.normalize(part);
            if (!key.isEmpty() && !list.contains(key)) {
                list.add(key);
            }
        }
        return list;
    }

    /** Stores the classmate grouping attribute list, preserving its order. */
    public void setClassmateAttributeList(List<String> list) {
        if (list == null || list.isEmpty()) {
            this.classmateAttributes = null;
            return;
        }
        List<String> normalized = new ArrayList<>();
        for (String part : list) {
            String key = StudentAttributes.normalize(part);
            if (!key.isEmpty() && !normalized.contains(key)) {
                normalized.add(key);
            }
        }
        this.classmateAttributes = normalized.isEmpty() ? null : String.join(",", normalized);
    }

    /** Convenience for building the scheme from a list of raw attribute names. */
    public static StudentIdentityScheme of(Long institutionId, String label, List<String> attributes) {
        StudentIdentityScheme scheme = new StudentIdentityScheme();
        scheme.setInstitutionId(institutionId);
        scheme.setLabel(label);
        scheme.setAttributeList(attributes);
        return scheme;
    }

    /** Splits the stored CSV — kept public for tests and diagnostics. */
    public List<String> splitAttributes() {
        return attributes == null ? List.of() : Arrays.asList(attributes.split(","));
    }
}
