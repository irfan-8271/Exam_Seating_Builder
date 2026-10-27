package com.examseating.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
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

/**
 * A student registered for an exam.
 *
 * <p>One row = one student, identified by {@code rollNo} (roll number,
 * registration number, admission number — the label is institution-specific).
 * The classification attributes {@code department}, {@code course} and
 * {@code yearSession} are free-form on purpose: schools may store class and
 * section there, colleges a department and a batch, universities a programme
 * and a semester. The seating engine decides <i>which</i> of these attributes
 * define identification, grouping and separation (see
 * {@code SeatingConfig.groupAttributes}). {@code subjectCode} is optional
 * metadata — used only to filter seating rounds and exports when an institution
 * runs per-paper arrangements.</p>
 */
@Entity
@Table(name = "students",
       uniqueConstraints = @UniqueConstraint(name = "uk_students_roll_no", columnNames = "roll_no"),
       indexes = @Index(name = "idx_students_dept_year", columnList = "department, year_session"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Student {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @NotBlank(message = "Name is required")
    @Size(max = 100)
    @Column(nullable = false, length = 100)
    private String name;

    /** Institution identifier: roll number, registration number, admission number, ... */
    @NotBlank(message = "Roll / registration number is required")
    @Size(max = 30)
    @Column(name = "roll_no", nullable = false, length = 30)
    private String rollNo;

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
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim().replaceAll("\\s+", " ");
    }
}
