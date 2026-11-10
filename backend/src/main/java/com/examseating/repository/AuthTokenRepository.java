package com.examseating.repository;

import com.examseating.entity.AuthToken;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Optional;

public interface AuthTokenRepository extends JpaRepository<AuthToken, Long> {
    Optional<AuthToken> findByTokenAndExpiresAtAfter(String token, Instant now);
    void deleteByToken(String token);
}
