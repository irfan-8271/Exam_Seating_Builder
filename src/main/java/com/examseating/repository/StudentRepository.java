package com.examseating.repository;

import com.examseating.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link Student}.
 *
 * <p>Reminder of the wrapper vs Hibernate: these method names are parsed into JPQL at
 * startup (e.g. {@code findBySubjectCode} → {@code select s from Student s where s.subjectCode = ?1}),
 * and each call runs inside a Spring-managed transaction (one is opened around the call
 * and closed afterwards).</p>
 */
public interface StudentRepository extends JpaRepository<Student, Long> {

    /** All students registered for one subject code (one seating round per subject). */
    List<Student> findBySubjectCode(String subjectCode);

    /** All students of one department. */
    List<Student> findByDepartment(String department);

    /** One student by unique roll number (used for imports/validation). */
    java.util.Optional<Student> findByRollNo(String rollNo);

    /** Counts are handy for capacity checks and import summaries. */
    long countBySubjectCode(String subjectCode);
}
