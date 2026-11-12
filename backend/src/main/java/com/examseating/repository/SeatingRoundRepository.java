package com.examseating.repository;

import com.examseating.entity.SeatingRound;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

/** Spring Data JPA repository for {@link SeatingRound}. */
public interface SeatingRoundRepository extends JpaRepository<SeatingRound, Long> {

    Optional<SeatingRound> findByInstitutionIdAndScopeKey(Long institutionId, String scopeKey);

    List<SeatingRound> findByInstitutionIdOrderByUpdatedAtDesc(Long institutionId);
}
