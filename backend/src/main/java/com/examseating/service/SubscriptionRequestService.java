package com.examseating.service;

import com.examseating.dto.PaymentDtos;
import com.examseating.dto.PaymentDtos.CreateSubscriptionRequestBody;
import com.examseating.dto.PaymentDtos.PaymentConfigUpdateRequest;
import com.examseating.dto.PaymentDtos.PaymentConfigView;
import com.examseating.dto.PaymentDtos.ReviewSubscriptionRequest;
import com.examseating.dto.PaymentDtos.SubscriptionRequestView;
import com.examseating.entity.ActivityLog;
import com.examseating.entity.Institution;
import com.examseating.entity.PaymentConfig;
import com.examseating.entity.Plan;
import com.examseating.entity.Subscription;
import com.examseating.entity.SubscriptionRequest;
import com.examseating.entity.User;
import com.examseating.exception.ForbiddenException;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.exception.UnauthorizedException;
import com.examseating.repository.InstitutionRepository;
import com.examseating.repository.PaymentConfigRepository;
import com.examseating.repository.PlanRepository;
import com.examseating.repository.SubscriptionRepository;
import com.examseating.repository.SubscriptionRequestRepository;
import com.examseating.repository.UserRepository;
import com.examseating.tenancy.TenantContext;
import com.examseating.tenancy.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Manages manual payment configuration and request-based subscription / limit
 * upgrades.
 *
 * <p>Core invariant: submitting a {@link SubscriptionRequest} <b>never</b>
 * modifies {@link Subscription} or raises any quota. Entitlements change only
 * when a {@code PLATFORM_ADMIN} invokes {@link #approve(Long, ReviewSubscriptionRequest)}.</p>
 */
@Service
@RequiredArgsConstructor
public class SubscriptionRequestService {

    public static final long MAX_IMAGE_BYTES = 5L * 1024L * 1024L;

    private static final Set<String> ALLOWED_IMAGE_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/jpg",
            "image/webp",
            "image/gif"
    );

    private final PaymentConfigRepository paymentConfigRepository;
    private final SubscriptionRequestRepository requestRepository;
    private final SubscriptionRepository subscriptionRepository;
    private final PlanRepository planRepository;
    private final InstitutionRepository institutionRepository;
    private final UserRepository userRepository;
    private final TenantSupport tenant;
    private final ActivityLogService activityLog;

    public record BinaryAsset(byte[] bytes, String contentType, String filename) {
    }

    // ------------------------------------------------------------------
    // Payment configuration
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public PaymentConfigView getPaymentConfigForAuthenticatedUser() {
        requireAuthenticatedUser();
        return toView(loadOrDefaultConfig());
    }

    @Transactional(readOnly = true)
    public BinaryAsset getPaymentQrForAuthenticatedUser() {
        requireAuthenticatedUser();
        PaymentConfig config = loadOrDefaultConfig();
        if (!config.hasQrImage()) {
            throw new ResourceNotFoundException("No payment QR code image has been configured.");
        }
        return new BinaryAsset(
                config.getQrImage(),
                config.getQrContentType() != null ? config.getQrContentType() : "image/png",
                config.getQrFilename() != null ? config.getQrFilename() : "payment-qr.png");
    }

    @Transactional
    public PaymentConfigView updatePaymentConfig(PaymentConfigUpdateRequest update) {
        User admin = requirePlatformAdmin();
        PaymentConfig config = loadOrCreateConfig();

        if (update.paymentMode() != null && !update.paymentMode().isBlank()) {
            try {
                config.setPaymentMode(PaymentConfig.PaymentMode.valueOf(
                        update.paymentMode().trim().toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("paymentMode must be MANUAL or GATEWAY");
            }
        }
        if (update.upiId() != null) {
            config.setUpiId(blankToNull(update.upiId()));
        }
        if (update.payeeName() != null) {
            config.setPayeeName(blankToNull(update.payeeName()));
        }
        if (update.instructions() != null) {
            config.setInstructions(blankToNull(update.instructions()));
        }
        if (update.additionalInfo() != null) {
            config.setAdditionalInfo(blankToNull(update.additionalInfo()));
        }
        if (update.requireScreenshot() != null) {
            config.setRequireScreenshot(update.requireScreenshot());
        }
        if (update.gatewayProvider() != null) {
            config.setGatewayProvider(blankToNull(update.gatewayProvider()));
        }
        config.setUpdatedBy(admin.getUsername());
        config.setUpdatedAt(Instant.now());
        paymentConfigRepository.save(config);

        activityLog.record(ActivityLog.Type.PAYMENT_CONFIG_UPDATED, admin,
                "Updated payment verification settings (UPI: "
                        + (config.getUpiId() != null ? config.getUpiId() : "none") + ")");
        return toView(config);
    }

    @Transactional
    public PaymentConfigView uploadPaymentQr(MultipartFile file) {
        User admin = requirePlatformAdmin();
        BinaryAsset image = validateAndReadImage(file, "Payment QR image");
        PaymentConfig config = loadOrCreateConfig();
        config.setQrImage(image.bytes());
        config.setQrContentType(image.contentType());
        config.setQrFilename(image.filename());
        config.setUpdatedBy(admin.getUsername());
        config.setUpdatedAt(Instant.now());
        paymentConfigRepository.save(config);

        activityLog.record(ActivityLog.Type.PAYMENT_CONFIG_UPDATED, admin,
                "Uploaded payment QR image (" + image.filename() + ")");
        return toView(config);
    }

    @Transactional
    public PaymentConfigView removePaymentQr() {
        User admin = requirePlatformAdmin();
        PaymentConfig config = loadOrCreateConfig();
        config.setQrImage(null);
        config.setQrContentType(null);
        config.setQrFilename(null);
        config.setUpdatedBy(admin.getUsername());
        config.setUpdatedAt(Instant.now());
        paymentConfigRepository.save(config);

        activityLog.record(ActivityLog.Type.PAYMENT_CONFIG_UPDATED, admin,
                "Removed payment QR image");
        return toView(config);
    }

    // ------------------------------------------------------------------
    // Client upgrade / limit-increase requests
    // ------------------------------------------------------------------

    @Transactional
    public SubscriptionRequestView submitRequest(CreateSubscriptionRequestBody body, MultipartFile screenshot) {
        User actor = requireAuthenticatedCustomerAdminOrWorkspaceUser();
        Long institutionId = actor != null && actor.getInstitutionId() != null
                ? actor.getInstitutionId()
                : tenant.currentId();
        Institution institution = institutionRepository.findById(institutionId)
                .orElseThrow(() -> new ResourceNotFoundException("Institution not found"));
        if (institution.isPlatform()) {
            throw new ForbiddenException("Platform administrator workspace cannot submit customer upgrade requests.");
        }

        if (body == null) {
            throw new IllegalArgumentException("Upgrade request details are required.");
        }

        String requestedPlanCode = blankToNull(body.requestedPlanCode());
        Plan requestedPlan = null;
        if (requestedPlanCode != null) {
            requestedPlanCode = requestedPlanCode.toUpperCase(Locale.ROOT);
            String codeLookup = requestedPlanCode;
            requestedPlan = planRepository.findByCode(codeLookup)
                    .filter(Plan::isActive)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown plan code '" + codeLookup + "'"));
        }

        boolean hasCustomLimits = body.requestedMaxStudents() != null
                || body.requestedMaxRooms() != null
                || body.requestedMaxGenerations() != null
                || body.requestedSeatsPerGeneration() != null
                || body.requestedStorageLimitMb() != null;

        if (requestedPlan == null && !hasCustomLimits) {
            throw new IllegalArgumentException(
                    "Select a target subscription plan or specify at least one custom limit increase.");
        }

        String currentPlanCode = subscriptionRepository.findByInstitutionId(institutionId)
                .map(Subscription::getPlanCode)
                .orElse("FREE");

        if (requestedPlan != null && !hasCustomLimits && requestedPlan.getCode().equalsIgnoreCase(currentPlanCode)) {
            throw new IllegalArgumentException(
                    "Your workspace is already on the " + requestedPlan.getName()
                    + " plan. Choose a higher plan or request custom limit increases.");
        }

        String txRef = blankToNull(body.transactionReference());
        if (txRef == null) {
            throw new IllegalArgumentException("Transaction ID / UTR number is required.");
        }
        boolean duplicateTx = requestRepository.existsByTransactionReferenceIgnoreCaseAndStatusIn(
                txRef, EnumSet.of(SubscriptionRequest.Status.PENDING, SubscriptionRequest.Status.APPROVED));
        if (duplicateTx) {
            throw new IllegalArgumentException(
                    "Transaction ID / UTR '" + txRef + "' has already been submitted for verification.");
        }

        BigDecimal amount = body.paymentAmount();
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Payment amount must be greater than zero.");
        }

        LocalDate paymentDate = body.paymentDate();
        if (paymentDate == null) {
            throw new IllegalArgumentException("Payment date is required.");
        }
        if (paymentDate.isAfter(LocalDate.now().plusDays(1))) {
            throw new IllegalArgumentException("Payment date cannot be in the future.");
        }

        PaymentConfig paymentConfig = loadOrDefaultConfig();
        boolean screenshotProvided = screenshot != null && !screenshot.isEmpty();
        if (paymentConfig.isRequireScreenshot() && !screenshotProvided) {
            throw new IllegalArgumentException(
                    "A payment screenshot is required by the administrator for verification.");
        }

        BinaryAsset screenshotAsset = screenshotProvided
                ? validateAndReadImage(screenshot, "Payment screenshot")
                : null;

        SubscriptionRequest.PaymentMethod method = parsePaymentMethod(body.paymentMethod());
        SubscriptionRequest.RequestType type = (requestedPlan != null && hasCustomLimits)
                ? SubscriptionRequest.RequestType.PLAN_AND_LIMITS
                : (requestedPlan != null
                        ? SubscriptionRequest.RequestType.PLAN_UPGRADE
                        : SubscriptionRequest.RequestType.LIMIT_INCREASE);

        SubscriptionRequest entity = SubscriptionRequest.builder()
                .institutionId(institution.getId())
                .institutionName(institution.getName())
                .requestedByUserId(actor != null ? actor.getId() : null)
                .requestedByUsername(actor != null ? actor.getUsername() : "workspace-admin")
                .requestType(type)
                .currentPlanCode(currentPlanCode)
                .requestedPlanCode(requestedPlan != null ? requestedPlan.getCode() : null)
                .requestedMaxStudents(body.requestedMaxStudents())
                .requestedMaxRooms(body.requestedMaxRooms())
                .requestedMaxGenerations(body.requestedMaxGenerations())
                .requestedSeatsPerGeneration(body.requestedSeatsPerGeneration())
                .requestedStorageLimitMb(body.requestedStorageLimitMb())
                .clientNotes(blankToNull(body.clientNotes()))
                .paymentMethod(method)
                .transactionReference(txRef)
                .paymentAmount(amount)
                .paymentDate(paymentDate)
                .screenshotBytes(screenshotAsset != null ? screenshotAsset.bytes() : null)
                .screenshotContentType(screenshotAsset != null ? screenshotAsset.contentType() : null)
                .screenshotFilename(screenshotAsset != null ? screenshotAsset.filename() : null)
                .status(SubscriptionRequest.Status.PENDING)
                .build();

        SubscriptionRequest saved = requestRepository.save(entity);

        String targetSummary = describeRequestTarget(saved);
        if (actor != null) {
            activityLog.record(ActivityLog.Type.UPGRADE_REQUESTED, actor,
                    "Submitted upgrade request (" + targetSummary + ", UTR " + txRef + ")");
        } else {
            activityLog.record(ActivityLog.Type.UPGRADE_REQUESTED, institution,
                    "Submitted upgrade request (" + targetSummary + ", UTR " + txRef + ")");
        }

        return toView(saved, planNames(), true);
    }

    @Transactional(readOnly = true)
    public List<SubscriptionRequestView> listForCurrentInstitution() {
        User actor = requireAuthenticatedUser();
        Long institutionId = (actor != null && actor.getInstitutionId() != null)
                ? actor.getInstitutionId()
                : tenant.currentId();
        Map<String, String> plans = planNames();
        return requestRepository.findByInstitutionIdOrderByCreatedAtDesc(institutionId).stream()
                .map(r -> toView(r, plans, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public BinaryAsset getScreenshotForCurrentInstitution(Long requestId) {
        User actor = requireAuthenticatedUser();
        Long institutionId = (actor != null && actor.getInstitutionId() != null)
                ? actor.getInstitutionId()
                : tenant.currentId();
        SubscriptionRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription request not found"));
        if (!institutionId.equals(req.getInstitutionId())) {
            throw new ForbiddenException("You do not have access to this subscription request.");
        }
        return requireScreenshotAsset(req);
    }

    // ------------------------------------------------------------------
    // Platform administrator verification & lifecycle controls
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public List<SubscriptionRequestView> listAllForAdmin(String statusFilter) {
        requirePlatformAdmin();
        List<SubscriptionRequest> rows;
        if (statusFilter != null && !statusFilter.isBlank() && !"ALL".equalsIgnoreCase(statusFilter.trim())) {
            SubscriptionRequest.Status status;
            try {
                status = SubscriptionRequest.Status.valueOf(statusFilter.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("status must be PENDING, APPROVED, REJECTED, or ALL");
            }
            rows = requestRepository.findByStatusOrderByCreatedAtDesc(status);
        } else {
            rows = requestRepository.findAllByOrderByCreatedAtDesc();
        }
        Map<String, String> plans = planNames();
        return rows.stream().map(r -> toView(r, plans, false)).toList();
    }

    @Transactional(readOnly = true)
    public BinaryAsset getScreenshotForAdmin(Long requestId) {
        requirePlatformAdmin();
        SubscriptionRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription request not found"));
        return requireScreenshotAsset(req);
    }

    @Transactional
    public SubscriptionRequestView approve(Long requestId, ReviewSubscriptionRequest review) {
        User admin = requirePlatformAdmin();
        SubscriptionRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription request " + requestId + " not found"));
        if (req.getStatus() != SubscriptionRequest.Status.PENDING) {
            throw new IllegalArgumentException(
                    "Only PENDING requests can be approved (current status: " + req.getStatus() + ").");
        }

        Institution institution = institutionRepository.findById(req.getInstitutionId())
                .orElseThrow(() -> new ResourceNotFoundException("Institution not found"));

        Instant now = Instant.now();
        Subscription subscription = subscriptionRepository.findByInstitutionId(institution.getId())
                .orElseGet(() -> Subscription.builder()
                        .institutionId(institution.getId())
                        .planCode(req.getCurrentPlanCode() != null ? req.getCurrentPlanCode() : "FREE")
                        .status(Subscription.Status.ACTIVE)
                        .startedAt(now)
                        .periodStart(now)
                        .periodEnd(now.plus(30, ChronoUnit.DAYS))
                        .updatedAt(now)
                        .build());

        if (req.getRequestedPlanCode() != null && !req.getRequestedPlanCode().isBlank()) {
            Plan newPlan = planRepository.findByCode(req.getRequestedPlanCode())
                    .filter(Plan::isActive)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Requested plan '" + req.getRequestedPlanCode() + "' is no longer active."));
            subscription.setPlanCode(newPlan.getCode());
            subscription.setStatus(Subscription.Status.ACTIVE);
            subscription.setPeriodStart(now);
            subscription.setPeriodEnd(now.plus(30, ChronoUnit.DAYS));
            if (subscription.getStartedAt() == null) {
                subscription.setStartedAt(now);
            }
            // Clear any stale lower custom overrides from an earlier tier so the new plan's
            // higher baseline limits take full effect unless explicitly overridden below.
            if (subscription.getMaxStudentsOverride() != null
                    && subscription.getMaxStudentsOverride() < newPlan.getMaxStudents()) {
                subscription.setMaxStudentsOverride(null);
            }
            if (subscription.getMaxRoomsOverride() != null
                    && subscription.getMaxRoomsOverride() < newPlan.getMaxRooms()) {
                subscription.setMaxRoomsOverride(null);
            }
            if (subscription.getMaxGenerationsOverride() != null
                    && subscription.getMaxGenerationsOverride() < newPlan.getMaxGenerationsPerMonth()) {
                subscription.setMaxGenerationsOverride(null);
            }
            if (subscription.getSeatsPerGenerationOverride() != null
                    && subscription.getSeatsPerGenerationOverride() < newPlan.getSeatsPerGeneration()) {
                subscription.setSeatsPerGenerationOverride(null);
            }
            if (subscription.getStorageLimitMbOverride() != null
                    && subscription.getStorageLimitMbOverride() < newPlan.getStorageLimitMb()) {
                subscription.setStorageLimitMbOverride(null);
            }
        }

        if (req.getRequestedMaxStudents() != null) {
            subscription.setMaxStudentsOverride(req.getRequestedMaxStudents());
        }
        if (req.getRequestedMaxRooms() != null) {
            subscription.setMaxRoomsOverride(req.getRequestedMaxRooms());
        }
        if (req.getRequestedMaxGenerations() != null) {
            subscription.setMaxGenerationsOverride(req.getRequestedMaxGenerations());
        }
        if (req.getRequestedSeatsPerGeneration() != null) {
            subscription.setSeatsPerGenerationOverride(req.getRequestedSeatsPerGeneration());
        }
        if (req.getRequestedStorageLimitMb() != null) {
            subscription.setStorageLimitMbOverride(req.getRequestedStorageLimitMb());
        }
        subscription.setUpdatedAt(now);
        subscriptionRepository.save(subscription);

        req.setStatus(SubscriptionRequest.Status.APPROVED);
        req.setReviewedByUserId(admin.getId());
        req.setReviewedByUsername(admin.getUsername());
        req.setReviewedAt(now);
        req.setRejectionReason(null);
        req.setAdminNotes(review != null ? blankToNull(review.adminNotes()) : null);
        requestRepository.save(req);

        activityLog.record(ActivityLog.Type.UPGRADE_APPROVED, institution,
                "Approved upgrade request #" + req.getId() + " (" + describeRequestTarget(req)
                        + ") by " + admin.getUsername());

        return toView(req, planNames(), false);
    }

    @Transactional
    public SubscriptionRequestView reject(Long requestId, ReviewSubscriptionRequest review) {
        User admin = requirePlatformAdmin();
        SubscriptionRequest req = requestRepository.findById(requestId)
                .orElseThrow(() -> new ResourceNotFoundException("Subscription request " + requestId + " not found"));
        if (req.getStatus() != SubscriptionRequest.Status.PENDING) {
            throw new IllegalArgumentException(
                    "Only PENDING requests can be rejected (current status: " + req.getStatus() + ").");
        }

        Instant now = Instant.now();
        req.setStatus(SubscriptionRequest.Status.REJECTED);
        req.setReviewedByUserId(admin.getId());
        req.setReviewedByUsername(admin.getUsername());
        req.setReviewedAt(now);
        req.setRejectionReason(review != null ? blankToNull(review.reason()) : null);
        req.setAdminNotes(review != null ? blankToNull(review.adminNotes()) : null);
        requestRepository.save(req);

        Institution institution = institutionRepository.findById(req.getInstitutionId()).orElse(null);
        String reasonNote = req.getRejectionReason() != null ? ": " + req.getRejectionReason() : "";
        activityLog.record(ActivityLog.Type.UPGRADE_REJECTED, institution,
                "Rejected upgrade request #" + req.getId() + " by " + admin.getUsername() + reasonNote);

        return toView(req, planNames(), false);
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    private PaymentConfig loadOrDefaultConfig() {
        return paymentConfigRepository.findByConfigKey(PaymentConfig.DEFAULT_KEY)
                .orElseGet(() -> PaymentConfig.builder()
                        .configKey(PaymentConfig.DEFAULT_KEY)
                        .paymentMode(PaymentConfig.PaymentMode.MANUAL)
                        .upiId("billing@examseating")
                        .payeeName("ExamSeating Billing")
                        .instructions("Complete payment using the UPI ID or QR code, then submit your UTR / Transaction ID for verification.")
                        .requireScreenshot(false)
                        .updatedAt(Instant.now())
                        .build());
    }

    private PaymentConfig loadOrCreateConfig() {
        return paymentConfigRepository.findByConfigKey(PaymentConfig.DEFAULT_KEY)
                .orElseGet(() -> paymentConfigRepository.save(loadOrDefaultConfig()));
    }

    private User resolveCurrentUser() {
        User fromTenant = TenantContext.account();
        if (fromTenant != null) {
            return fromTenant;
        }
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getPrincipal())) {
            return userRepository.findByUsername(auth.getName()).orElse(null);
        }
        return null;
    }

    private User requireAuthenticatedUser() {
        User user = resolveCurrentUser();
        if (user == null) {
            throw new UnauthorizedException("Sign in is required to access payment and subscription request details.");
        }
        return user;
    }

    private User requireAuthenticatedCustomerAdminOrWorkspaceUser() {
        User user = requireAuthenticatedUser();
        if (user.getRole() == User.Role.PLATFORM_ADMIN) {
            throw new ForbiddenException("Platform administrator accounts manage requests from the Admin Console.");
        }
        if (user.getRole() != User.Role.ADMIN) {
            throw new ForbiddenException("Only institution administrators can submit subscription upgrade requests.");
        }
        return user;
    }

    private User requirePlatformAdmin() {
        User user = resolveCurrentUser();
        if (user == null) {
            throw new UnauthorizedException("Platform administrator authentication is required.");
        }
        if (user.getRole() != User.Role.PLATFORM_ADMIN) {
            throw new ForbiddenException("Platform administrator access is required.");
        }
        return user;
    }

    private static BinaryAsset validateAndReadImage(MultipartFile file, String label) {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException(label + " file is empty.");
        }
        if (file.getSize() > MAX_IMAGE_BYTES) {
            throw new IllegalArgumentException(label + " must be 5 MB or smaller.");
        }
        String contentType = file.getContentType() != null
                ? file.getContentType().trim().toLowerCase(Locale.ROOT)
                : "";
        String original = file.getOriginalFilename() != null ? file.getOriginalFilename().trim() : "image.png";
        String lowerName = original.toLowerCase(Locale.ROOT);
        boolean validMime = ALLOWED_IMAGE_TYPES.contains(contentType);
        boolean validExt = lowerName.endsWith(".png") || lowerName.endsWith(".jpg")
                || lowerName.endsWith(".jpeg") || lowerName.endsWith(".webp") || lowerName.endsWith(".gif");
        if (!validMime && !validExt) {
            throw new IllegalArgumentException(label + " must be a PNG, JPEG, WEBP, or GIF image.");
        }
        if (!validMime) {
            contentType = lowerName.endsWith(".png") ? "image/png"
                    : lowerName.endsWith(".webp") ? "image/webp"
                    : lowerName.endsWith(".gif") ? "image/gif"
                    : "image/jpeg";
        } else if ("image/jpg".equals(contentType)) {
            contentType = "image/jpeg";
        }
        try {
            byte[] bytes = file.getBytes();
            if (bytes.length == 0) {
                throw new IllegalArgumentException(label + " file is empty.");
            }
            return new BinaryAsset(bytes, contentType, sanitizeFilename(original));
        } catch (IOException ex) {
            throw new IllegalArgumentException("Could not read uploaded " + label.toLowerCase(Locale.ROOT) + ".");
        }
    }

    private static BinaryAsset requireScreenshotAsset(SubscriptionRequest req) {
        if (!req.hasScreenshot()) {
            throw new ResourceNotFoundException("No payment screenshot was attached to this request.");
        }
        return new BinaryAsset(
                req.getScreenshotBytes(),
                req.getScreenshotContentType() != null ? req.getScreenshotContentType() : "image/png",
                req.getScreenshotFilename() != null ? req.getScreenshotFilename() : "payment-proof.png");
    }

    private static SubscriptionRequest.PaymentMethod parsePaymentMethod(String raw) {
        if (raw == null || raw.isBlank()) {
            return SubscriptionRequest.PaymentMethod.MANUAL_UPI;
        }
        try {
            return SubscriptionRequest.PaymentMethod.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return SubscriptionRequest.PaymentMethod.MANUAL_UPI;
        }
    }

    private Map<String, String> planNames() {
        Map<String, String> names = new LinkedHashMap<>();
        for (Plan p : planRepository.findAll()) {
            names.put(p.getCode(), p.getName());
        }
        return names;
    }

    private static PaymentConfigView toView(PaymentConfig c) {
        return new PaymentConfigView(
                c.getPaymentMode() != null ? c.getPaymentMode().name() : PaymentConfig.PaymentMode.MANUAL.name(),
                c.getUpiId(),
                c.getPayeeName(),
                c.getInstructions(),
                c.getAdditionalInfo(),
                c.isRequireScreenshot(),
                c.hasQrImage(),
                c.getQrFilename(),
                c.getGatewayProvider(),
                c.getUpdatedAt());
    }

    private static SubscriptionRequestView toView(
            SubscriptionRequest r, Map<String, String> planNames, boolean newlySubmitted) {
        String planName = r.getRequestedPlanCode() != null
                ? planNames.getOrDefault(r.getRequestedPlanCode(), r.getRequestedPlanCode())
                : null;
        String confirmation = (newlySubmitted || r.getStatus() == SubscriptionRequest.Status.PENDING)
                ? PaymentDtos.CONFIRMATION_MESSAGE
                : null;
        return new SubscriptionRequestView(
                r.getId(),
                r.getInstitutionId(),
                r.getInstitutionName(),
                r.getRequestedByUserId(),
                r.getRequestedByUsername(),
                r.getRequestType().name(),
                r.getCurrentPlanCode(),
                r.getRequestedPlanCode(),
                planName,
                r.getRequestedMaxStudents(),
                r.getRequestedMaxRooms(),
                r.getRequestedMaxGenerations(),
                r.getRequestedSeatsPerGeneration(),
                r.getRequestedStorageLimitMb(),
                r.getClientNotes(),
                r.getPaymentMethod().name(),
                r.getTransactionReference(),
                r.getPaymentAmount(),
                r.getPaymentDate(),
                r.hasScreenshot(),
                r.getScreenshotFilename(),
                r.getStatus().name(),
                r.getReviewedByUsername(),
                r.getReviewedAt(),
                r.getRejectionReason(),
                r.getAdminNotes(),
                r.getCreatedAt(),
                confirmation);
    }

    private static String describeRequestTarget(SubscriptionRequest r) {
        StringBuilder sb = new StringBuilder();
        if (r.getRequestedPlanCode() != null) {
            sb.append("Plan -> ").append(r.getRequestedPlanCode());
        }
        if (r.hasRequestedLimits()) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append("Custom limits");
        }
        return sb.length() == 0 ? r.getRequestType().name() : sb.toString();
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String sanitizeFilename(String filename) {
        String cleaned = filename.replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "image.png" : (cleaned.length() > 180 ? cleaned.substring(cleaned.length() - 180) : cleaned);
    }
}
