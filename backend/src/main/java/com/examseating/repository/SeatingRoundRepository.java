package com.examseating.repository;

import com.examseating.entity.SeatingRound;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data JPA repository for {@link SeatingRound} — the parameters a
 * seating round was generated with.
 *
 * <p>There is at most one row per institution and subject scope; the next
 * round of the same scope overwrites it.</p>
 */
public interface SeatingRoundRepository extends JpaRepository<SeatingRound, Long> {

    /** The round recorded for one institution and subject scope ("" = whole roster). */
    Optional<SeatingRound> findByInstitutionIdAndSubjectKey(Long institutionId, String subjectKey);

    /** Every round of one institution, the newest first. */
    List<SeatingRound> findByInstitutionIdOrderByUpdatedAtDesc(Long institutionId);
}
