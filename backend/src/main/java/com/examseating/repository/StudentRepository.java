package com.examseating.repository;

import com.examseating.entity.Student;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** Spring Data JPA repository for {@link Student}. */
public interface StudentRepository extends JpaRepository<Student, Long> {

    long countByInstitutionId(Long institutionId);

    long countByInstitutionIdIsNull();

    List<Student> findByInstitutionId(Long institutionId);
}
