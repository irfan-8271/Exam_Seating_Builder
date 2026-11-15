package com.examseating.repository;

import com.examseating.entity.Subscription;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface SubscriptionRepository extends JpaRepository<Subscription, Long> {
    Optional<Subscription> findByInstitutionId(Long institutionId);
    /** Removes the subscription of one institution. */
    @Modifying
    @Transactional
    @Query("DELETE FROM Subscription s WHERE s.institutionId = :id")
    int deleteAllByInstitutionId(@Param("id") Long id);
}
