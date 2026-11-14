package com.examseating.service;

import com.examseating.dto.AdminDtos.StorageCategoryView;
import com.examseating.dto.AdminDtos.StorageCleanupPreview;
import com.examseating.dto.AdminDtos.StorageCleanupResult;
import com.examseating.dto.AdminDtos.StorageUsageView;
import com.examseating.dto.AdminDtos.StoredFileCleanupRequest;
import com.examseating.dto.AdminDtos.ScreenshotCleanupRequest;
import com.examseating.entity.ActivityLog;
import com.examseating.entity.StoredFile;
import com.examseating.entity.SubscriptionRequest;
import com.examseating.entity.User;
import com.examseating.repository.StoredFileRepository;
import com.examseating.repository.SubscriptionRequestRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Lets the platform operator see what the database is holding and reclaim
 * space that is no longer needed.
 *
 * <p>Documents and images live in the database rather than on the server's
 * disk, so a busy platform accumulates bytes inside the database itself. On a
 * small hosted database — a free tier with a 1 GB disk, for instance — that
 * becomes the limiting factor, and the operator needs a way to free space
 * without waiting for someone to notice.
 *
 * <p><strong>What can be deleted is deliberately narrow:</strong>
 *
 * <ul>
 *   <li><strong>Stored files</strong> — uploaded rosters and documents. These
 *       are working copies: the information they carry has already been
 *       imported into the roster tables, so removing the file loses the
 *       original spreadsheet but not the students.</li>
 *   <li><strong>Screenshots on resolved upgrade requests</strong> — only
 *       requests that have already been approved or rejected. A PENDING
 *       request is still awaiting verification and its screenshot is the
 *       evidence the operator needs, so it is never touched.</li>
 *   <li><strong>The payment QR image</strong> — never deleted here. It is one
 *       live image for the whole platform, replaced in place from the payment
 *       settings screen, and it is not "older" in any meaningful sense.</li>
 * </ul>
 *
 * <p>Every cleanup is a two-step operation: a preview that reports exactly
 * what would go, and an execution that requires an explicit confirmation.
 * A mistaken request therefore costs nothing.
 */
@Service
@RequiredArgsConstructor
public class StorageAdminService {

    /** How many examples of a deletion the preview lists. */
    private static final int SAMPLE_SIZE = 5;

    /** The status values whose screenshots may be reclaimed. */
    private static final Set<SubscriptionRequest.Status> RESOLVED_STATUSES =
            EnumSet.of(SubscriptionRequest.Status.APPROVED, SubscriptionRequest.Status.REJECTED);

    private final StoredFileRepository storedFileRepository;
    private final SubscriptionRequestRepository subscriptionRequestRepository;
    private final ActivityLogService activityLog;
    private final PlatformAdminService platformAdmin;

    // ------------------------------------------------------------------
    //  Usage reporting
    // ------------------------------------------------------------------

    /** The whole storage picture: what is held, and what could be freed. */
    @Transactional(readOnly = true)
    public StorageUsageView usage() {
        List<StoredFile> files = storedFileRepository.findAllOldestFirst();
        long storedFileBytes = files.stream().mapToLong(StoredFile::getSizeBytes).sum();

        List<SubscriptionRequest> withScreenshots =
                subscriptionRequestRepository.findResolvedWithScreenshots(RESOLVED_STATUSES, null);
        long resolvedScreenshotBytes = withScreenshots.stream()
                .mapToLong(r -> r.getScreenshotBytes() == null ? 0 : r.getScreenshotBytes().length)
                .sum();

        // Screenshots still awaiting review are evidence, not clutter.
        long pendingEvidenceBytes = 0;
        long pendingEvidenceCount = 0;
        for (SubscriptionRequest request : subscriptionRequestRepository
                .findByStatusOrderByCreatedAtDesc(SubscriptionRequest.Status.PENDING)) {
            if (request.hasScreenshot()) {
                pendingEvidenceBytes += request.getScreenshotBytes().length;
                pendingEvidenceCount++;
            }
        }

        long qrBytes = 0;
        long qrCount = 0;

        List<StorageCategoryView> categories = List.of(
                new StorageCategoryView(
                        "stored-files",
                        "Uploaded files",
                        storedFileBytes,
                        storedFileBytes,
                        files.size(),
                        files.size(),
                        "Rosters and documents uploaded by institutions. Their contents have already "
                                + "been imported into the roster tables, so removing the file does not "
                                + "remove the students."),
                new StorageCategoryView(
                        "request-screenshots",
                        "Screenshots on resolved requests",
                        resolvedScreenshotBytes,
                        resolvedScreenshotBytes,
                        withScreenshots.size(),
                        withScreenshots.size(),
                        "Payment proofs attached to upgrade requests that have already been approved "
                                + "or rejected. Safe to remove once the request is settled."),
                new StorageCategoryView(
                        "pending-evidence",
                        "Screenshots awaiting review",
                        pendingEvidenceBytes,
                        0,
                        pendingEvidenceCount,
                        0,
                        "Payment proofs on requests that are still waiting for a decision. These are "
                                + "the evidence used to approve or reject the request, so they are "
                                + "never deleted."),
                new StorageCategoryView(
                        "payment-qr",
                        "Payment QR image",
                        qrBytes,
                        0,
                        qrCount,
                        0,
                        "The single UPI QR image institutions scan to pay. It is replaced in place "
                                + "from the payment settings screen, so there is nothing to clean up."));

        long totalBytes = storedFileBytes + resolvedScreenshotBytes + pendingEvidenceBytes + qrBytes;
        long reclaimableBytes = storedFileBytes + resolvedScreenshotBytes;
        long protectedBytes = pendingEvidenceBytes + qrBytes;

        List<String> notes = List.of(
                "Deleting a row frees the space for new uploads straight away.",
                "MySQL does not shrink the database file on its own — the freed space is reused "
                        + "internally but the file keeps its size until an OPTIMIZE TABLE rebuilds it. "
                        + "On a managed host you can ask the provider to optimise, or run "
                        + "OPTIMIZE TABLE stored_files, subscription_requests;.",
                "A PENDING request's screenshot is never removed — it is the evidence used to "
                        + "approve or reject the request.");

        return new StorageUsageView(categories, totalBytes, reclaimableBytes, protectedBytes, notes);
    }

    // ------------------------------------------------------------------
    //  Preview — report what would go, without changing anything
    // ------------------------------------------------------------------

    /** What a stored-file cleanup would remove. */
    @Transactional(readOnly = true)
    public StorageCleanupPreview previewStoredFileCleanup(Integer olderThanDays, Long institutionId) {
        List<StoredFile> victims = storedFileRepository.findRemovable(cutoff(olderThanDays), institutionId);
        long bytes = victims.stream().mapToLong(StoredFile::getSizeBytes).sum();
        Set<Long> institutions = new LinkedHashSet<>();
        victims.forEach(f -> institutions.add(f.getInstitutionId()));
        List<String> sample = victims.stream()
                .limit(SAMPLE_SIZE)
                .map(f -> "#" + f.getId() + " " + f.getOriginalName()
                        + " (" + f.getSizeBytes() + " bytes, uploaded " + f.getUploadedAt() + ")")
                .toList();
        return new StorageCleanupPreview("stored-files", victims.size(), bytes, institutions.size(), sample);
    }

    /** What a screenshot cleanup would remove. */
    @Transactional(readOnly = true)
    public StorageCleanupPreview previewScreenshotCleanup(Integer olderThanDays) {
        List<SubscriptionRequest> victims =
                subscriptionRequestRepository.findResolvedWithScreenshots(RESOLVED_STATUSES, cutoff(olderThanDays));
        long bytes = victims.stream()
                .mapToLong(r -> r.getScreenshotBytes() == null ? 0 : r.getScreenshotBytes().length)
                .sum();
        Set<Long> institutions = new LinkedHashSet<>();
        victims.forEach(r -> institutions.add(r.getInstitutionId()));
        List<String> sample = victims.stream()
                .limit(SAMPLE_SIZE)
                .map(r -> "request #" + r.getId() + " from " + r.getInstitutionName()
                        + " (" + r.getStatus() + ", created " + r.getCreatedAt() + ")")
                .toList();
        return new StorageCleanupPreview("request-screenshots", victims.size(), bytes,
                institutions.size(), sample);
    }

    // ------------------------------------------------------------------
    //  Cleanup — the confirmed operation
    // ------------------------------------------------------------------

    /**
     * Removes stored files that are older than the given age.
     *
     * @throws IllegalArgumentException when the request is not confirmed
     */
    @Transactional
    public StorageCleanupResult cleanupStoredFiles(StoredFileCleanupRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.confirm())) {
            throw new IllegalArgumentException(
                    "Deletion must be confirmed: send confirm=true to remove stored files.");
        }
        Integer olderThanDays = request.olderThanDays();
        Long institutionId = request.institutionId();
        List<StoredFile> victims = storedFileRepository.findRemovable(cutoff(olderThanDays), institutionId);
        if (victims.isEmpty()) {
            return new StorageCleanupResult("stored-files", 0, 0,
                    "No stored files matched — nothing was removed.");
        }
        long bytes = victims.stream().mapToLong(StoredFile::getSizeBytes).sum();
        Set<Long> institutions = new LinkedHashSet<>();
        victims.forEach(f -> institutions.add(f.getInstitutionId()));

        storedFileRepository.deleteAll(victims);

        User operator = platformAdmin.currentOperator();
        if (operator != null) {
            activityLog.record(ActivityLog.Type.STORAGE_CLEANUP, operator,
                    "Removed " + victims.size() + " stored file(s) totalling " + bytes
                            + " bytes across " + institutions.size() + " institution(s)."
                            + describeAge(olderThanDays));
        }

        return new StorageCleanupResult("stored-files", victims.size(), bytes,
                "Removed " + victims.size() + " stored file(s), freeing " + bytes + " bytes.");
    }

    /**
     * Removes the payment screenshot from requests that have already been
     * approved or rejected.
     *
     * <p>The screenshot itself goes; the request, its amounts and its
     * decision stay, so the upgrade history remains intact.
     *
     * @throws IllegalArgumentException when the request is not confirmed
     */
    @Transactional
    public StorageCleanupResult cleanupScreenshots(ScreenshotCleanupRequest request) {
        if (request == null || !Boolean.TRUE.equals(request.confirm())) {
            throw new IllegalArgumentException(
                    "Deletion must be confirmed: send confirm=true to remove screenshots.");
        }
        Integer olderThanDays = request.olderThanDays();
        List<SubscriptionRequest> victims =
                subscriptionRequestRepository.findResolvedWithScreenshots(RESOLVED_STATUSES, cutoff(olderThanDays));
        if (victims.isEmpty()) {
            return new StorageCleanupResult("request-screenshots", 0, 0,
                    "No resolved requests with screenshots matched — nothing was removed.");
        }
        long bytes = 0;
        for (SubscriptionRequest victim : victims) {
            if (victim.getScreenshotBytes() != null) {
                bytes += victim.getScreenshotBytes().length;
            }
            victim.setScreenshotBytes(null);
            victim.setScreenshotFilename(null);
            victim.setScreenshotContentType(null);
        }
        subscriptionRequestRepository.saveAll(victims);

        User operator = platformAdmin.currentOperator();
        if (operator != null) {
            activityLog.record(ActivityLog.Type.STORAGE_CLEANUP, operator,
                    "Removed payment screenshots from " + victims.size()
                            + " resolved upgrade request(s), freeing " + bytes + " bytes."
                            + describeAge(olderThanDays));
        }

        return new StorageCleanupResult("request-screenshots", victims.size(), bytes,
                "Removed the screenshot from " + victims.size()
                        + " resolved request(s), freeing " + bytes + " bytes.");
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    /**
     * The instant before which content counts as "old enough to remove".
     *
     * <p>A negative age is treated as "no age limit" rather than as an error,
     * so a mistyped value removes more than intended only if the caller also
     * confirms — and the preview always shows what would go first.
     */
    private static Instant cutoff(Integer olderThanDays) {
        if (olderThanDays == null || olderThanDays <= 0) {
            return null;
        }
        return Instant.now().minus(olderThanDays, ChronoUnit.DAYS);
    }

    private static String describeAge(Integer olderThanDays) {
        if (olderThanDays == null || olderThanDays <= 0) {
            return " (no age limit)";
        }
        return " (older than " + olderThanDays + " day(s))";
    }
}
