package com.examseating.repository;

import com.examseating.entity.SeatingRound;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/** Spring Data JPA repository for {@link SeatingRound}. */
public interface SeatingRoundRepository extends JpaRepository<SeatingRound, Long> {

    Optional<SeatingRound> findByInstitutionIdAndScopeKey(Long institutionId, String scopeKey);

    List<SeatingRound> findByInstitutionIdOrderByUpdatedAtDesc(Long institutionId);
    /** Removes every seating round of one institution. */
    @Modifying
    @Transactional
    @Query("DELETE FROM SeatingRound r WHERE r.institutionId = :id")
    int deleteAllByInstitutionId(@Param("id") Long id);

    /** How many seating rounds one institution holds. */
    long countByInstitutionId(Long institutionId);

}
