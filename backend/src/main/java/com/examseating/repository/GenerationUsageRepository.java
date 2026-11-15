package com.examseating.repository;

import com.examseating.entity.GenerationUsage;
import java.time.Instant;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface GenerationUsageRepository extends JpaRepository<GenerationUsage, Long> {
    long countByInstitutionIdAndCreatedAtBetween(Long institutionId, Instant start, Instant end);
    List<GenerationUsage> findTop20ByInstitutionIdOrderByCreatedAtDesc(Long institutionId);

    /** The generations of one institution within one period. */
    List<GenerationUsage> findByInstitutionIdAndCreatedAtBetween(Long institutionId,
                                                                  Instant start, Instant end);

    /** The most recent generations of the whole service. */
    List<GenerationUsage> findTop500ByOrderByCreatedAtDesc();
    /** Removes the generation ledger of one institution. */
    @Modifying
    @Transactional
    @Query("DELETE FROM GenerationUsage g WHERE g.institutionId = :id")
    int deleteAllByInstitutionId(@Param("id") Long id);
}
