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

    /** Tenant-scoped counters used by the quota service. */
    long countByInstitutionId(Long institutionId);

    long countByInstitutionIdIsNull();

    java.util.List<Student> findByInstitutionId(Long institutionId);
}
