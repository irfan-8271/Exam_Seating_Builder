package com.examseating.repository;

import com.examseating.entity.Student;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Spring Data JPA repository for {@link Student}. */
public interface StudentRepository extends JpaRepository<Student, Long> {

    long countByInstitutionId(Long institutionId);

    long countByInstitutionIdIsNull();

    List<Student> findByInstitutionId(Long institutionId);
    /** Removes every student of one institution. */
    @Modifying
    @Transactional
    @Query("DELETE FROM Student s WHERE s.institutionId = :id")
    int deleteAllByInstitutionId(@Param("id") Long id);
}
