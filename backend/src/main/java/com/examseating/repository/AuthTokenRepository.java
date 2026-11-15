package com.examseating.repository;

import com.examseating.entity.AuthToken;
import java.time.Instant;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {
    Optional<AuthToken> findByTokenAndExpiresAtAfter(String token, Instant now);
    Optional<AuthToken> findByToken(String token);
    void deleteByToken(String token);
    /** Removes every session of one institution. */
    @Modifying
    @Transactional
    @Query("DELETE FROM AuthToken t WHERE t.institutionId = :id")
    int deleteAllByInstitutionId(@Param("id") Long id);

    /** Removes every session belonging to one account. */
    @Modifying
    @Transactional
    @Query("DELETE FROM AuthToken t WHERE t.userId = :id")
    int deleteAllByUserId(@Param("id") Long id);

    /** How many sessions one institution currently has. */
    long countByInstitutionId(Long institutionId);

    /** How many sessions one account currently has. */
    long countByUserId(Long userId);

}
