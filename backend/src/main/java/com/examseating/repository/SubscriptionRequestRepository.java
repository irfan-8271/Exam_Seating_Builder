package com.examseating.repository;

import com.examseating.entity.SubscriptionRequest;
import org.springframework.data.jpa.repository.JpaRepository;

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
}
