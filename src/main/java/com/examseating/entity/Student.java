package com.examseating.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A student registered for an exam.
 *
 * <p>One row = one exam registration (the subject code denotes the paper the
 * student is sitting, which the engine can use to run a separate seating round
 * per subject/session).</p>
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

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "roll_no", nullable = false, length = 30)
    private String rollNo;

    @Column(nullable = false, length = 60)
    private String department;

    @Column(nullable = false, length = 60)
    private String course;

    /** e.g. "2nd Year / 2025-26". */
    @Column(name = "year_session", nullable = false, length = 30)
    private String yearSession;

    /** Subject code of the paper this registration belongs to. */
    @Column(name = "subject_code", nullable = false, length = 20)
    private String subjectCode;
}
