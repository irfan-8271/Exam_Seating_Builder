package com.examseating.repository;

import com.examseating.entity.ActivityLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

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
}
