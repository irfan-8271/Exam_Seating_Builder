package com.examseating.service;

import com.examseating.entity.ActivityLog;
import com.examseating.entity.Institution;
import com.examseating.entity.User;
import com.examseating.repository.ActivityLogRepository;
import com.examseating.repository.InstitutionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes what happened, so the platform administrator can watch the service
 * without opening the database.
 *
 * <p>The log is written in its own transaction: an entry must survive even
 * when the action that produced it is rolled back — a failed sign-in is
 * exactly the kind of thing an operator wants to see.</p>
 */
@Service
@RequiredArgsConstructor
public class ActivityLogService {

    private final ActivityLogRepository activityLogRepository;
    private final InstitutionRepository institutionRepository;

    /** Records an event about an account. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ActivityLog.Type type, User user, String detail) {
        String institutionName = null;
        if (user != null && user.getInstitutionId() != null) {
            institutionName = institutionRepository.findById(user.getInstitutionId())
                    .map(Institution::getName)
                    .orElse(null);
        }
        activityLogRepository.save(ActivityLog.builder()
                .type(type)
                .userId(user == null ? null : user.getId())
                .username(user == null ? null : user.getUsername())
                .institutionId(user == null ? null : user.getInstitutionId())
                .institutionName(institutionName)
                .detail(truncate(detail))
                .occurredAt(java.time.Instant.now())
                .build());
    }

    /** Records an event that is not about an account — a plan change, say. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(ActivityLog.Type type, Institution institution, String detail) {
        activityLogRepository.save(ActivityLog.builder()
                .type(type)
                .institutionId(institution == null ? null : institution.getId())
                .institutionName(institution == null ? null : institution.getName())
                .detail(truncate(detail))
                .occurredAt(java.time.Instant.now())
                .build());
    }

    private static String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= 400 ? detail : detail.substring(0, 397) + "...";
    }
}
