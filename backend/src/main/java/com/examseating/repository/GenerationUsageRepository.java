package com.examseating.repository;

import com.examseating.entity.GenerationUsage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface GenerationUsageRepository extends JpaRepository<GenerationUsage, Long> {
    long countByInstitutionIdAndCreatedAtBetween(Long institutionId, Instant start, Instant end);
    List<GenerationUsage> findTop20ByInstitutionIdOrderByCreatedAtDesc(Long institutionId);

    /** The generations of one institution within one period. */
    List<GenerationUsage> findByInstitutionIdAndCreatedAtBetween(Long institutionId,
                                                                  Instant start, Instant end);

    /** The most recent generations of the whole service. */
    List<GenerationUsage> findTop500ByOrderByCreatedAtDesc();
}
