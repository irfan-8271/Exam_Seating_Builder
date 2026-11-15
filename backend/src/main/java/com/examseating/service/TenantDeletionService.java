package com.examseating.service;

import com.examseating.dto.AdminDtos.DeletionCategoryView;
import com.examseating.dto.AdminDtos.DeletionPreview;
import com.examseating.dto.AdminDtos.DeletionRequest;
import com.examseating.dto.AdminDtos.DeletionResult;
import com.examseating.entity.ActivityLog;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.Institution;
import com.examseating.entity.StoredFile;
import com.examseating.entity.User;
import com.examseating.exception.ForbiddenException;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.repository.ActivityLogRepository;
import com.examseating.repository.AuthTokenRepository;
import com.examseating.repository.BenchRepository;
import com.examseating.repository.CohortRuleRepository;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.GenerationUsageRepository;
import com.examseating.repository.InstitutionRepository;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.repository.SeatingRoundRepository;
import com.examseating.repository.StoredFileRepository;
import com.examseating.repository.StudentIdentitySchemeRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.repository.SubscriptionRepository;
import com.examseating.repository.SubscriptionRequestRepository;
import com.examseating.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Removes a college, or a single account, from the service.
 *
 * <p>A platform operator occasionally has to take a customer out of the
 * service entirely — a trial that never converted, a college that asked to
 * be removed, a duplicate registration. And an account has to go on its own
 * when someone leaves, or when an account is found to be abused.</p>
 *
 * <p><strong>Every removal is a two-step operation.</strong> A preview states
 * exactly what would go, and the removal itself requires an explicit
 * confirmation. A mistaken request therefore costs nothing, and the operator
 * never has to guess how much is about to disappear.</p>
 *
 * <p><strong>Three things are deliberately protected.</strong></p>
 *
 * <ul>
 *   <li><strong>The platform institution itself</strong> — it owns the
 *       operator's own account. Removing it would lock the service out of its
 *       own administration, permanently and irreversibly.</li>
 *   <li><strong>The last platform administrator</strong> — for the same
 *       reason. If the removal would leave no one able to administer the
 *       service, it is refused.</li>
 *   <li><strong>The activity log</strong> — the audit trail of what happened
 *       and who did it. It is kept even when the institution it describes is
 *       gone, because a trail that vanishes with its subject is not a trail.
 *       The log rows carry the institution's name and the account's name as
 *       plain text, so they stay readable after the rows they describe are
 *       gone.</li>
 * </ul>
 *
 * <p>Rows are removed children-first, so no deletion ever trips a foreign
 * key.</p>
 */
@Service
@RequiredArgsConstructor
public class TenantDeletionService {

    private final InstitutionRepository institutionRepository;
    private final UserRepository userRepository;
    private final StudentRepository studentRepository;
    private final ExamRoomRepository examRoomRepository;
    private final BenchRepository benchRepository;
    private final SeatingAllocationRepository allocationRepository;
    private final SeatingRoundRepository roundRepository;
    private final CohortRuleRepository cohortRuleRepository;
    private final StudentIdentitySchemeRepository schemeRepository;
    private final StoredFileRepository storedFileRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final SubscriptionRequestRepository subscriptionRequestRepository;
    private final GenerationUsageRepository generationUsageRepository;
    private final AuthTokenRepository authTokenRepository;
    private final ActivityLogRepository activityLogRepository;
    private final ActivityLogService activityLog;
    private final PlatformAdminService platformAdmin;

    // ------------------------------------------------------------------
    //  Preview — what would go
    // ------------------------------------------------------------------

    /**
     * Describes what removing one college would take with it.
     *
     * @throws ResourceNotFoundException when no such institution exists
     */
    @Transactional(readOnly = true)
    public DeletionPreview previewInstitution(Long institutionId) {
        Institution institution = institutionRepository.findById(institutionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Institution " + institutionId + " not found"));

        List<DeletionCategoryView> categories = new ArrayList<>();
        long total = 0;

        total += add(categories, "users", "Accounts", userRepository.countByInstitutionId(institutionId),
                "Every account that belongs to this college, including its administrators.");
        total += add(categories, "students", "Students", studentRepository.countByInstitutionId(institutionId),
                "Every student on the roster, with their roster attributes.");
        total += add(categories, "rooms", "Examination halls", examRoomRepository.countByInstitutionId(institutionId),
                "Every hall, together with its benches.");
        total += add(categories, "allocations", "Seat allocations",
                allocationRepository.countByInstitutionId(institutionId),
                "Every seat assignment produced by a seating generation.");
        total += add(categories, "rounds", "Seating rounds",
                roundRepository.countByInstitutionId(institutionId),
                "Every recorded seating round and the name given to it.");
        total += add(categories, "cohort-rules", "Cohort rules",
                cohortRuleRepository.findByInstitutionId(institutionId).size(),
                "The rules that decide which students are kept apart.");
        total += add(categories, "identity-schemes", "Roster schema",
                schemeRepository.findByInstitutionId(institutionId).isPresent() ? 1 : 0,
                "The identification scheme: which column names a student.");
        total += add(categories, "stored-files", "Uploaded files",
                storedFileRepository.findByInstitutionIdOrderByUploadedAtDesc(institutionId).size(),
                "Rosters and documents uploaded by this college, with their content.");
        total += add(categories, "subscriptions", "Subscription",
                subscriptionRepository.findByInstitutionId(institutionId).isPresent() ? 1 : 0,
                "The college's plan and its quota overrides.");
        total += add(categories, "upgrade-requests", "Upgrade requests",
                subscriptionRequestRepository.findByInstitutionIdOrderByCreatedAtDesc(institutionId).size(),
                "Plan upgrade requests raised by this college, with their payment screenshots.");
        total += add(categories, "generation-ledger", "Generation ledger",
                generationUsageRepository.findByInstitutionIdAndCreatedAtBetween(
                        institutionId, java.time.Instant.EPOCH, java.time.Instant.now().plusSeconds(86400)).size(),
                "The record of how many generations the college used against its plan.");
        total += add(categories, "sessions", "Active sessions",
                authTokenRepository.countByInstitutionId(institutionId),
                "Sign-in tokens currently valid for this college. They stop working at once.");

        long freedBytes = storedFileRepository.sumSizeByInstitutionId(institutionId);

        List<String> warnings = new ArrayList<>();
        warnings.add("The college's sign-in stops working immediately; its users are not told why.");
        long pending = subscriptionRequestRepository
                .countByInstitutionIdAndStatus(institutionId,
                        com.examseating.entity.SubscriptionRequest.Status.PENDING);
        if (pending > 0) {
            warnings.add(pending + " upgrade request(s) are still awaiting payment verification. "
                    + "Their screenshots are evidence and will be removed with the rest.");
        }
        warnings.add("The activity log is kept. It records who removed this college and when.");

        // The activity log rows for this institution are counted but kept.
        long retained = activityLogRepository.countByInstitutionId(institutionId);

        String blocked = null;
        if (institution.isPlatform()) {
            blocked = "This is the platform institution, which owns the operator's own account. "
                    + "It cannot be removed.";
        } else if (userRepository.countByRole(User.Role.PLATFORM_ADMIN) <= 1
                && userRepository.countByInstitutionId(institutionId) > 0
                && hasPlatformAdmin(institutionId)) {
            blocked = "This college holds the last platform administrator account. "
                    + "Removing it would leave the service with nobody who can administer it.";
        }

        return new DeletionPreview(
                "institution", institution.getId(), institution.getName(),
                institution.getName(), categories, total, freedBytes, retained,
                warnings, blocked == null, blocked);
    }

    /**
     * Describes what removing one account would take with it.
     *
     * @throws ResourceNotFoundException when no such account exists
     */
    @Transactional(readOnly = true)
    public DeletionPreview previewUser(Long userId) {
        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account " + userId + " not found"));

        List<DeletionCategoryView> categories = new ArrayList<>();
        long total = 0;

        total += add(categories, "sessions", "Active sessions",
                authTokenRepository.countByUserId(userId),
                "Sign-in tokens currently valid for this account. They stop working at once.");

        long retained = activityLogRepository.countByUserId(userId);

        List<String> warnings = new ArrayList<>();
        warnings.add("Only this account is removed. The college's students, halls and seating plans stay.");
        warnings.add("The activity log for this account is kept.");

        String blocked = null;
        if (target.getRole() == User.Role.PLATFORM_ADMIN
                && userRepository.countByRole(User.Role.PLATFORM_ADMIN) <= 1) {
            blocked = "This is the last platform administrator account. "
                    + "Removing it would leave the service with nobody who can administer it.";
        }

        String institutionName = target.getInstitutionId() == null
                ? null
                : institutionRepository.findById(target.getInstitutionId())
                        .map(Institution::getName).orElse(null);

        return new DeletionPreview(
                "user", target.getId(), target.getUsername(),
                institutionName, categories, total, 0, retained,
                warnings, blocked == null, blocked);
    }

    // ------------------------------------------------------------------
    //  Removal — the confirmed operation
    // ------------------------------------------------------------------

    /**
     * Removes a college and everything belonging to it.
     *
     * @throws IllegalArgumentException when the request is not confirmed
     * @throws ForbiddenException       when the removal is refused outright
     */
    @Transactional
    public DeletionResult deleteInstitution(DeletionRequest request) {
        requireConfirmed(request, "remove a college");
        Long institutionId = request.institutionId();
        if (institutionId == null) {
            throw new IllegalArgumentException("No college was named.");
        }

        // The preview is the authority on whether this may happen, so the
        // refusal rules cannot drift between the two paths.
        DeletionPreview preview = previewInstitution(institutionId);
        if (!preview.canDelete()) {
            throw new ForbiddenException(preview.blockedReason());
        }

        Institution institution = institutionRepository.findById(institutionId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Institution " + institutionId + " not found"));

        long deleted = 0;
        long freedBytes = 0;
        long retained = preview.retainedRows();

        // ---- children first, so no deletion trips a foreign key ----

        // Seat assignments point at students and benches.
        deleted += allocationRepository.deleteAllByInstitutionId(institutionId);

        // Benches point at halls.
        List<ExamRoom> rooms = examRoomRepository.findAll().stream()
                .filter(r -> institutionId.equals(r.getInstitutionId()))
                .toList();
        if (!rooms.isEmpty()) {
            deleted += benchRepository.deleteAllByRoomIdIn(
                    rooms.stream().map(ExamRoom::getId).toList());
        }

        // The rest of the tenant's own rows.
        deleted += studentRepository.deleteAllByInstitutionId(institutionId);
        deleted += examRoomRepository.deleteAllByInstitutionId(institutionId);
        deleted += roundRepository.deleteAllByInstitutionId(institutionId);
        // These two report no count, so the preview's figures stand as the record.
        cohortRuleRepository.deleteByInstitutionId(institutionId);
        schemeRepository.deleteByInstitutionId(institutionId);
        deleted += subscriptionRepository.deleteAllByInstitutionId(institutionId);
        deleted += subscriptionRequestRepository.deleteAllByInstitutionId(institutionId);
        deleted += generationUsageRepository.deleteAllByInstitutionId(institutionId);

        // Uploaded files carry their bytes with them.
        List<StoredFile> files = storedFileRepository.findByInstitutionIdOrderByUploadedAtDesc(institutionId);
        freedBytes = files.stream().mapToLong(StoredFile::getSizeBytes).sum();
        deleted += storedFileRepository.deleteAllByInstitutionId(institutionId);

        // Sessions first, then the accounts themselves.
        deleted += authTokenRepository.deleteAllByInstitutionId(institutionId);
        deleted += userRepository.deleteAllByInstitutionId(institutionId);

        // The college itself, last.
        institutionRepository.delete(institution);

        String summary = "Removed the college \"" + institution.getName() + "\" and "
                + deleted + " of its records (" + freedBytes + " bytes of uploaded content).";
        recordDeletion("Removed the college \"" + institution.getName() + "\" ("
                + institution.getSlug() + ") and " + deleted + " record(s), releasing "
                + freedBytes + " bytes. " + preview.retainedRows()
                + " activity log entries were kept.");

        return new DeletionResult("institution", institution.getId(), institution.getName(),
                deleted, freedBytes, retained, summary);
    }

    /**
     * Removes one account.
     *
     * @throws IllegalArgumentException when the request is not confirmed
     * @throws ForbiddenException       when the removal is refused outright
     */
    @Transactional
    public DeletionResult deleteUser(DeletionRequest request) {
        requireConfirmed(request, "remove an account");
        Long userId = request.userId();
        if (userId == null) {
            throw new IllegalArgumentException("No account was named.");
        }

        DeletionPreview preview = previewUser(userId);
        if (!preview.canDelete()) {
            throw new ForbiddenException(preview.blockedReason());
        }

        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("Account " + userId + " not found"));

        // The account's own sessions first, then the account.
        long deleted = authTokenRepository.deleteAllByUserId(userId);
        userRepository.delete(target);

        String summary = "Removed the account \"" + target.getUsername() + "\" and "
                + deleted + " of its sessions.";
        recordDeletion("Removed the account \"" + target.getUsername() + "\" ("
                + target.getId() + ") and " + deleted + " session(s). "
                + preview.retainedRows() + " activity log entries were kept.");

        return new DeletionResult("user", target.getId(), target.getUsername(),
                deleted, 0, preview.retainedRows(), summary);
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /** Refuses anything that has not been explicitly confirmed. */
    private void requireConfirmed(DeletionRequest request, String what) {
        if (request == null || !Boolean.TRUE.equals(request.confirm())) {
            throw new IllegalArgumentException(
                    "Deletion must be confirmed: send confirm=true to " + what + ".");
        }
    }

    /** Whether the given college holds a platform administrator account. */
    private boolean hasPlatformAdmin(Long institutionId) {
        return userRepository.findByInstitutionId(institutionId).stream()
                .anyMatch(u -> u.getRole() == User.Role.PLATFORM_ADMIN);
    }

    /** Adds a category to the preview and returns its count. */
    private static long add(List<DeletionCategoryView> categories, String key, String label,
                            long count, String note) {
        categories.add(new DeletionCategoryView(key, label, count, note));
        return count;
    }

    /** Writes the audit entry. The log outlives what it describes. */
    private void recordDeletion(String detail) {
        User operator = platformAdmin.currentOperator();
        if (operator != null) {
            activityLog.record(ActivityLog.Type.INSTITUTION_DELETED, operator, detail);
        }
    }

    /** The distinct institutions named in a preview's warnings, for the interface. */
    public Set<String> institutionsInvolved(DeletionPreview preview) {
        Set<String> names = new LinkedHashSet<>();
        if (preview != null && preview.institutionName() != null) {
            names.add(preview.institutionName());
        }
        return names;
    }
}
