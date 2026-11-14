package com.examseating.repository;

import com.examseating.entity.CohortRule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CohortRuleRepository extends JpaRepository<CohortRule, Long> {

    List<CohortRule> findByInstitutionId(Long institutionId);

    Optional<CohortRule> findByInstitutionIdAndScopeSignature(Long institutionId, String scopeSignature);

    void deleteByInstitutionId(Long institutionId);
}
