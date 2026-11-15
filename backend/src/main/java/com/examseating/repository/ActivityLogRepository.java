package com.examseating.repository;

import com.examseating.entity.ActivityLog;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * The activity log the platform administrator reads.
 *
 * <p>The log spans every institution on purpose: it is the operator's view,
 * not a tenant's.</p>
 */
public interface ActivityLogRepository extends JpaRepository<ActivityLog, Long> {

    /** The most recent events across the whole service. */
    List<ActivityLog> findTop200ByOrderByOccurredAtDesc();

    /** The most recent events of one account. */
    List<ActivityLog> findTop20ByUserIdOrderByOccurredAtDesc(Long userId);

    /**
     * How many log entries describe one institution.
     *
     * <p>These rows are kept when the institution is removed — the trail
     * outlives what it describes — but the operator is told how many there
     * are, so "kept" is a number rather than a vague reassurance.</p>
     */
    long countByInstitutionId(Long institutionId);

    /** How many log entries describe one account. */
    long countByUserId(Long userId);
}
