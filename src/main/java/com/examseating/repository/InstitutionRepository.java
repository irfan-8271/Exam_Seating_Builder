package com.examseating.repository;

import com.examseating.entity.Institution;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface InstitutionRepository extends JpaRepository<Institution, Long> {
    Optional<Institution> findBySlug(String slug);
    boolean existsBySlug(String slug);
}
