package com.examseating.web.rest;

import com.examseating.dto.AdminDtos.AdminActivityView;
import com.examseating.dto.AdminDtos.AdminInstitutionView;
import com.examseating.dto.AdminDtos.AdminPlanUpsertRequest;
import com.examseating.dto.AdminDtos.AdminPlanView;
import com.examseating.dto.AdminDtos.AdminStatusView;
import com.examseating.dto.AdminDtos.AdminUserView;
import com.examseating.dto.AdminDtos.PlanChangeRequest;
import com.examseating.dto.AdminDtos.PlatformGenerationView;
import com.examseating.dto.AdminDtos.PlatformUsageView;
import com.examseating.dto.AdminDtos.StorageCleanupPreview;
import com.examseating.dto.AdminDtos.StorageCleanupResult;
import com.examseating.dto.AdminDtos.StorageUsageView;
import com.examseating.dto.AdminDtos.StoredFileCleanupRequest;
import com.examseating.dto.AdminDtos.ScreenshotCleanupRequest;
import com.examseating.dto.AdminDtos.UserChangeRequest;
import com.examseating.dto.AdminDtos.UserLimitsRequest;
import com.examseating.dto.PaymentDtos.PaymentConfigUpdateRequest;
import com.examseating.dto.PaymentDtos.PaymentConfigView;
import com.examseating.dto.PaymentDtos.ReviewSubscriptionRequest;
import com.examseating.dto.PaymentDtos.SubscriptionRequestView;
import com.examseating.entity.User;
import com.examseating.service.PlatformAdminService;
import com.examseating.service.StorageAdminService;
import com.examseating.service.SubscriptionRequestService;
import com.examseating.service.SubscriptionRequestService.BinaryAsset;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.List;

/**
 * Dedicated platform administrator REST API ({@code /api/v1/admin/**}).
 *
 * <p>Every non-status endpoint enforces {@code ROLE_PLATFORM_ADMIN} directly in
 * Java regardless of {@code app.security.mode}, ensuring normal client accounts
 * and unauthenticated callers can never access platform administration views or
 * payment/approval controls.</p>
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class PlatformAdminRestController {

    private final PlatformAdminService admin;
    private final SubscriptionRequestService subscriptionRequests;
    private final StorageAdminService storageAdmin;

    /** GET /api/v1/admin/status — public boolean probe (never exposes usernames or counts). */
    @GetMapping("/status")
    public AdminStatusView status() {
        return admin.status();
    }

    /** GET /api/v1/admin/plans — complete subscription tier catalog (active & inactive) with subscriber counts. */
    @GetMapping("/plans")
    public List<AdminPlanView> plans() {
        requirePlatformAdmin();
        return admin.plans();
    }

    /** POST /api/v1/admin/plans — create a new subscription tier in the catalog. */
    @PostMapping("/plans")
    public ResponseEntity<AdminPlanView> createPlan(@Valid @RequestBody AdminPlanUpsertRequest request) {
        requirePlatformAdmin();
        AdminPlanView created = admin.createPlan(request);
        return ResponseEntity.created(URI.create("/api/v1/admin/plans/" + created.code())).body(created);
    }

    /** PUT /api/v1/admin/plans/{code} — update an existing subscription tier's limits, price, or visibility. */
    @PutMapping("/plans/{code}")
    public AdminPlanView updatePlan(@PathVariable String code,
                                    @Valid @RequestBody AdminPlanUpsertRequest request) {
        requirePlatformAdmin();
        return admin.updatePlan(code, request);
    }

    /** GET /api/v1/admin/institutions — every customer institution, with usage & limits. */
    @GetMapping("/institutions")
    public List<AdminInstitutionView> institutions() {
        requirePlatformAdmin();
        return admin.institutions();
    }

    /** GET /api/v1/admin/institutions/{id} — one customer institution in detail. */
    @GetMapping("/institutions/{id}")
    public AdminInstitutionView institution(@PathVariable Long id) {
        requirePlatformAdmin();
        return admin.institution(id);
    }

    /** PUT /api/v1/admin/institutions/{id}/subscription — change plan and/or custom limits. */
    @PutMapping("/institutions/{id}/subscription")
    public AdminInstitutionView changeSubscription(@PathVariable Long id,
                                                   @RequestBody PlanChangeRequest request) {
        requirePlatformAdmin();
        return admin.changePlan(id, request);
    }

    /** PUT /api/v1/admin/institutions/{id}/status — suspend or restore an institution. */
    @PutMapping("/institutions/{id}/status")
    public AdminInstitutionView changeStatus(@PathVariable Long id,
                                             @RequestParam String status) {
        requirePlatformAdmin();
        return admin.setStatus(id, status);
    }

    /** GET /api/v1/admin/users — every account across the platform. */
    @GetMapping("/users")
    public List<AdminUserView> users() {
        requirePlatformAdmin();
        return admin.users();
    }

    /** GET /api/v1/admin/users/{id} — one account in detail. */
    @GetMapping("/users/{id}")
    public AdminUserView user(@PathVariable Long id) {
        requirePlatformAdmin();
        return admin.user(id);
    }

    /** PUT /api/v1/admin/users/{id} — change role, status, institution, or password. */
    @PutMapping("/users/{id}")
    public AdminUserView changeUser(@PathVariable Long id, @RequestBody UserChangeRequest request) {
        requirePlatformAdmin();
        return admin.changeUser(id, request);
    }

    /** PUT /api/v1/admin/users/{id}/limits — set per-account limit overrides. */
    @PutMapping("/users/{id}/limits")
    public AdminUserView changeUserLimits(@PathVariable Long id,
                                          @RequestBody UserLimitsRequest request) {
        requirePlatformAdmin();
        return admin.setUserLimits(id, request);
    }

    /** GET /api/v1/admin/activity — platform-wide audit log. */
    @GetMapping("/activity")
    public List<AdminActivityView> activity(@RequestParam(defaultValue = "100") int limit) {
        requirePlatformAdmin();
        return admin.activity(limit);
    }

    /** GET /api/v1/admin/usage — platform-wide totals and subscription request queue stats. */
    @GetMapping("/usage")
    public PlatformUsageView usage() {
        requirePlatformAdmin();
        return admin.usage();
    }

    /** GET /api/v1/admin/generations — recent seating generations across all institutions. */
    @GetMapping("/generations")
    public List<PlatformGenerationView> generations(@RequestParam(defaultValue = "50") int limit) {
        requirePlatformAdmin();
        return admin.generations(limit);
    }

    // ------------------------------------------------------------------
    // Manual payment configuration
    // ------------------------------------------------------------------

    /** GET /api/v1/admin/payment-config — read current payment verification settings. */
    @GetMapping("/payment-config")
    public PaymentConfigView getPaymentConfig() {
        requirePlatformAdmin();
        return subscriptionRequests.getPaymentConfigForAuthenticatedUser();
    }

    /** PUT /api/v1/admin/payment-config — update UPI ID, payee name, instructions, screenshot requirement. */
    @PutMapping("/payment-config")
    public PaymentConfigView updatePaymentConfig(@Valid @RequestBody PaymentConfigUpdateRequest request) {
        requirePlatformAdmin();
        return subscriptionRequests.updatePaymentConfig(request);
    }

    /** POST /api/v1/admin/payment-config/qr — upload or replace payment QR code image. */
    @PostMapping(value = "/payment-config/qr", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public PaymentConfigView uploadPaymentQr(@RequestParam("file") MultipartFile file) {
        requirePlatformAdmin();
        return subscriptionRequests.uploadPaymentQr(file);
    }

    /** DELETE /api/v1/admin/payment-config/qr — remove payment QR code image. */
    @DeleteMapping("/payment-config/qr")
    public PaymentConfigView deletePaymentQr() {
        requirePlatformAdmin();
        return subscriptionRequests.removePaymentQr();
    }

    /** GET /api/v1/admin/payment-config/qr — preview payment QR code image. */
    @GetMapping("/payment-config/qr")
    public ResponseEntity<byte[]> previewPaymentQr() {
        requirePlatformAdmin();
        BinaryAsset qr = subscriptionRequests.getPaymentQrForAuthenticatedUser();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(qr.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + qr.filename() + "\"")
                .body(qr.bytes());
    }

    // ------------------------------------------------------------------
    // Storage management — reclaiming database space
    // ------------------------------------------------------------------

    /**
     * GET /api/v1/admin/storage — what the database is holding, split by kind,
     * and how much of it could be reclaimed.
     */
    @GetMapping("/storage")
    public StorageUsageView storage() {
        requirePlatformAdmin();
        return storageAdmin.usage();
    }

    /**
     * POST /api/v1/admin/storage/stored-files/preview — report what a stored
     * file cleanup would remove, without removing anything.
     */
    @PostMapping("/storage/stored-files/preview")
    public StorageCleanupPreview previewStoredFileCleanup(@RequestBody(required = false)
                                                            StoredFileCleanupRequest request) {
        requirePlatformAdmin();
        Integer olderThanDays = request == null ? null : request.olderThanDays();
        Long institutionId = request == null ? null : request.institutionId();
        return storageAdmin.previewStoredFileCleanup(olderThanDays, institutionId);
    }

    /**
     * POST /api/v1/admin/storage/stored-files/cleanup — remove stored files.
     *
     * <p>Nothing is removed unless the request carries {@code confirm=true}.
     */
    @PostMapping("/storage/stored-files/cleanup")
    public StorageCleanupResult cleanupStoredFiles(@RequestBody StoredFileCleanupRequest request) {
        requirePlatformAdmin();
        return storageAdmin.cleanupStoredFiles(request);
    }

    /**
     * POST /api/v1/admin/storage/screenshots/preview — report what a screenshot
     * cleanup would remove, without removing anything.
     */
    @PostMapping("/storage/screenshots/preview")
    public StorageCleanupPreview previewScreenshotCleanup(@RequestBody(required = false)
                                                          ScreenshotCleanupRequest request) {
        requirePlatformAdmin();
        Integer olderThanDays = request == null ? null : request.olderThanDays();
        return storageAdmin.previewScreenshotCleanup(olderThanDays);
    }

    /**
     * POST /api/v1/admin/storage/screenshots/cleanup — remove the payment
     * screenshot from requests that have already been approved or rejected.
     *
     * <p>A pending request is never touched: its screenshot is the evidence
     * used to decide the request.
     */
    @PostMapping("/storage/screenshots/cleanup")
    public StorageCleanupResult cleanupScreenshots(@RequestBody ScreenshotCleanupRequest request) {
        requirePlatformAdmin();
        return storageAdmin.cleanupScreenshots(request);
    }

    // ------------------------------------------------------------------
    // Subscription upgrade & payment verification requests    // ------------------------------------------------------------------

    /** GET /api/v1/admin/subscription-requests — list requests (optional ?status=PENDING|APPROVED|REJECTED|ALL). */
    @GetMapping("/subscription-requests")
    public List<SubscriptionRequestView> listSubscriptionRequests(
            @RequestParam(required = false) String status) {
        requirePlatformAdmin();
        return subscriptionRequests.listAllForAdmin(status);
    }

    /** GET /api/v1/admin/subscription-requests/{id}/screenshot — inspect uploaded payment proof. */
    @GetMapping("/subscription-requests/{id}/screenshot")
    public ResponseEntity<byte[]> requestScreenshot(@PathVariable Long id) {
        requirePlatformAdmin();
        BinaryAsset asset = subscriptionRequests.getScreenshotForAdmin(id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(asset.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + asset.filename() + "\"")
                .body(asset.bytes());
    }

    /** POST /api/v1/admin/subscription-requests/{id}/approve — verify payment and activate plan/limits. */
    @PostMapping("/subscription-requests/{id}/approve")
    public SubscriptionRequestView approveRequest(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ReviewSubscriptionRequest body) {
        requirePlatformAdmin();
        return subscriptionRequests.approve(id, body);
    }

    /** POST /api/v1/admin/subscription-requests/{id}/reject — reject request with optional reason. */
    @PostMapping("/subscription-requests/{id}/reject")
    public SubscriptionRequestView rejectRequest(
            @PathVariable Long id,
            @Valid @RequestBody(required = false) ReviewSubscriptionRequest body) {
        requirePlatformAdmin();
        return subscriptionRequests.reject(id, body);
    }

    private void requirePlatformAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        boolean allowed = authentication != null
                && authentication.getAuthorities().stream()
                .anyMatch(authority -> ("ROLE_" + User.Role.PLATFORM_ADMIN.name())
                        .equals(authority.getAuthority()));
        if (!allowed) {
            throw new AccessDeniedException(
                    "This view is reserved for the platform administrator");
        }
    }
}
