package com.examseating.entity;

import com.examseating.tenancy.TenantScoped;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ForeignKey;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.MapKeyColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.BatchSize;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A student registered for an exam.
 *
 * <p><b>Identity is configurable, not assumed.</b> One row = one student, but
 * <i>which</i> attributes identify that student is decided per institution
 * (see {@link StudentIdentityScheme}): a roll number, a registration number,
 * a batch plus a serial, a registration number plus a department, or any
 * combination the institution's spreadsheet contains. The chosen attributes
 * are folded into {@link #identityKey}, which is unique per institution.
 * {@code rollNo} is therefore just one attribute among many — nullable, and
 * no longer the uniqueness key.</p>
 *
 * <p>The classification attributes {@code department}, {@code course} and
 * {@code yearSession} stay free-form on purpose: schools may store class and
 * section there, colleges a department and a batch, universities a programme
 * and a semester. The seating engine decides <i>which</i> attributes define
 * grouping and separation (see {@code SeatingConfig.groupAttributes}), and may
 * also group on the dynamic attributes in {@link #attributes}.
 * {@code subjectCode} is optional metadata — used only to filter seating
 * rounds and exports when an institution runs per-paper arrangements.</p>
 */
@Entity
@Table(name = "students",
       uniqueConstraints = @UniqueConstraint(name = "uk_students_identity",
               columnNames = {"institution_id", "identity_key"}),
       indexes = @Index(name = "idx_students_dept_year", columnList = "department, year_session"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Student implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /** Owning institution; null on rows written before multi-tenancy (default institution). */
    @Column(name = "institution_id")
    private Long institutionId;

    @NotBlank(message = "Name is required")
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String name;

    /**
     * The institution's identifier for this student — roll number,
     * registration number, admission number, ... Optional: an institution may
     * identify its students by a combination of other attributes instead.
     */
    @Size(max = 30)
    @Column(name = "roll_no", length = 30)
    private String rollNo;

    /**
     * The canonical composite identity, built from the attributes the
     * institution's identity scheme selects. Unique per institution. Null on
     * rows written before identity schemes existed.
     */
    @Size(max = 255)
    @Column(name = "identity_key", length = 255)
    private String identityKey;

    /**
     * Every further attribute the institution supplied (batch number,
     * section, registration number, house, ...). Keyed by the normalized
     * attribute name, so the engine and the API can address them without
     * knowing anything about the institution's spreadsheet.
     */
    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "student_attributes",
            joinColumns = @JoinColumn(name = "student_id",
                    foreignKey = @ForeignKey(name = "fk_student_attributes_student")))
    @MapKeyColumn(name = "attribute_key", length = 64)
    @Column(name = "attribute_value", length = 255)
    @BatchSize(size = 200)
    @Builder.Default
    private Map<String, String> attributes = new LinkedHashMap<>();

    /** Free-form classification attribute (department, stream, class, ...). */
    @Size(max = 60)
    @Column(length = 60)
    private String department;

    /** Free-form classification attribute (course, programme, ...). */
    @Size(max = 60)
    @Column(length = 60)
    private String course;

    /** Free-form classification attribute, e.g. "2nd Year / 2025-26", "Section A", "Sem 4". */
    @Size(max = 30)
    @Column(name = "year_session", length = 30)
    private String yearSession;

    /** Optional: subject/paper code — only used to filter rounds and exports. */
    @Size(max = 20)
    @Column(name = "subject_code", length = 20)
    private String subjectCode;

    /** Blank strings mean "not provided" — normalize them to null. */
    @PrePersist
    @PreUpdate
    void normalizeBlanks() {
        if (name != null) {
            name = name.trim().replaceAll("\\s+", " ");
        }
        if (rollNo != null) {
            rollNo = rollNo.trim();
        }
        department = blankToNull(department);
        course = blankToNull(course);
        yearSession = blankToNull(yearSession);
        subjectCode = blankToNull(subjectCode);
        if (identityKey != null) {
            identityKey = identityKey.trim();
        }
        if (attributes != null) {
            attributes.replaceAll((key, value) -> blankToNull(value));
            attributes.keySet().removeIf(key -> key == null || key.isBlank());
        }
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim().replaceAll("\\s+", " ");
    }
}
