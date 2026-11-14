package com.examseating.repository;

import com.examseating.entity.SubscriptionRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface SubscriptionRequestRepository extends JpaRepository<SubscriptionRequest, Long> {

    List<SubscriptionRequest> findByInstitutionIdOrderByCreatedAtDesc(Long institutionId);

    List<SubscriptionRequest> findAllByOrderByCreatedAtDesc();

    List<SubscriptionRequest> findByStatusOrderByCreatedAtDesc(SubscriptionRequest.Status status);

    long countByStatus(SubscriptionRequest.Status status);

    long countByInstitutionIdAndStatus(Long institutionId, SubscriptionRequest.Status status);

    boolean existsByTransactionReferenceIgnoreCaseAndStatusIn(
            String transactionReference, Collection<SubscriptionRequest.Status> statuses);

    /**
     * Resolved requests that still carry a screenshot, oldest first.
     *
     * <p>Only APPROVED and REJECTED requests qualify. A PENDING request is
     * still awaiting verification, and its screenshot is the evidence the
     * operator needs to approve or reject it — deleting that would destroy
     * the proof of payment rather than clean up after it.
     *
     * <p>The screenshot is matched with {@code is not null} rather than a
     * length test: the column is a BLOB, and {@code length()} maps to
     * {@code character_length()}, which rejects a byte array.
     *
     * @param statuses the resolved statuses to include
     * @param cutoff   only requests created at or before this instant; null means no cutoff
     */
    @Query("select r from SubscriptionRequest r "
            + "where r.screenshotBytes is not null "
            + "and r.status in :statuses "
            + "and (:cutoff is null or r.createdAt <= :cutoff) "
            + "order by r.createdAt asc")
    List<SubscriptionRequest> findResolvedWithScreenshots(
            @Param("statuses") Collection<SubscriptionRequest.Status> statuses,
            @Param("cutoff") Instant cutoff);
}
