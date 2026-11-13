package com.examseating.web.rest;

import com.examseating.dto.AccountDtos.AuthSession;
import com.examseating.dto.AccountDtos.GenerationView;
import com.examseating.dto.AccountDtos.InstitutionUpdate;
import com.examseating.dto.AccountDtos.InstitutionView;
import com.examseating.dto.AccountDtos.LoginRequest;
import com.examseating.dto.AccountDtos.PlanChangeRequest;
import com.examseating.dto.AccountDtos.PlanView;
import com.examseating.dto.AccountDtos.PlatformLoginRequest;
import com.examseating.dto.AccountDtos.RegisterRequest;
import com.examseating.dto.AccountDtos.SubscriptionView;
import com.examseating.dto.AccountDtos.UsageView;
import com.examseating.dto.PaymentDtos.CreateSubscriptionRequestBody;
import com.examseating.dto.PaymentDtos.PaymentConfigView;
import com.examseating.dto.PaymentDtos.SubscriptionRequestView;
import com.examseating.service.AccountService;
import com.examseating.service.SubscriptionRequestService;
import com.examseating.service.SubscriptionRequestService.BinaryAsset;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.net.URI;
import java.time.LocalDate;
import java.util.List;

/**
 * Institutional accounts, plans, subscriptions, payment instructions, upgrade
 * requests, and usage counters.
 */
@RestController
@RequiredArgsConstructor
public class AccountRestController {

    private final AccountService accountService;
    private final SubscriptionRequestService subscriptionRequestService;

    /** POST /api/v1/auth/register — create an account and its workspace. */
    @PostMapping("/api/v1/auth/register")
    public ResponseEntity<AuthSession> register(@Valid @RequestBody RegisterRequest request) {
        AuthSession session = accountService.register(request);
        return ResponseEntity.created(URI.create("/api/v1/institutions/current")).body(session);
    }

    /** POST /api/v1/auth/login — exchange credentials for an opaque bearer token. */
    @PostMapping("/api/v1/auth/login")
    public AuthSession login(@Valid @RequestBody LoginRequest request) {
        return accountService.login(request);
    }

    /** POST /api/v1/auth/platform/login — platform administrator sign-in. */
    @PostMapping("/api/v1/auth/platform/login")
    public AuthSession platformLogin(@Valid @RequestBody PlatformLoginRequest request) {
        return accountService.platformLogin(request);
    }

    /** GET /api/v1/auth/me — current session (401 if the token is missing/expired). */
    @GetMapping("/api/v1/auth/me")
    public AuthSession me(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        return accountService.me(authorization);
    }

    /** POST /api/v1/auth/logout — revoke the current token (204). */
    @PostMapping("/api/v1/auth/logout")
    public ResponseEntity<Void> logout(
            @RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        accountService.logout(authorization);
        return ResponseEntity.noContent().build();
    }

    /** GET /api/v1/institutions/current — the tenant resolved for this request. */
    @GetMapping("/api/v1/institutions/current")
    public InstitutionView currentInstitution() {
        return accountService.currentInstitution();
    }

    /** PATCH /api/v1/institutions/current — update name / contact email. */
    @PatchMapping("/api/v1/institutions/current")
    @PreAuthorize("hasRole('ADMIN')")
    public InstitutionView updateInstitution(@RequestBody InstitutionUpdate update) {
        return accountService.updateInstitution(update);
    }

    /** GET /api/v1/plans — the public plan catalogue. */
    @GetMapping("/api/v1/plans")
    public List<PlanView> plans() {
        return accountService.plans();
    }

    /** GET /api/v1/subscriptions/current — active plan and billing period. */
    @GetMapping("/api/v1/subscriptions/current")
    public SubscriptionView currentSubscription() {
        return accountService.currentSubscription();
    }

    /**
     * PUT /api/v1/subscriptions/current — blocked (403 Forbidden).
     *
     * <p>Clients cannot self-modify plans or limits directly; upgrades require
     * submitting a {@code POST /api/v1/subscription-requests} request and
     * receiving platform administrator approval.</p>
     */
    @PutMapping("/api/v1/subscriptions/current")
    @PreAuthorize("hasRole('ADMIN')")
    public SubscriptionView changePlan(@Valid @RequestBody PlanChangeRequest request) {
        return accountService.changePlan(request);
    }

    /** GET /api/v1/payment-config — payment instructions, UPI ID, and QR metadata for signed-in users. */
    @GetMapping("/api/v1/payment-config")
    public PaymentConfigView paymentConfig() {
        return subscriptionRequestService.getPaymentConfigForAuthenticatedUser();
    }

    /** GET /api/v1/payment-config/qr — configured payment QR image for signed-in users. */
    @GetMapping("/api/v1/payment-config/qr")
    public ResponseEntity<byte[]> paymentQr() {
        BinaryAsset qr = subscriptionRequestService.getPaymentQrForAuthenticatedUser();
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(qr.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + qr.filename() + "\"")
                .body(qr.bytes());
    }

    /** GET /api/v1/subscription-requests — upgrade & limit-increase requests of the current institution. */
    @GetMapping("/api/v1/subscription-requests")
    public List<SubscriptionRequestView> mySubscriptionRequests() {
        return subscriptionRequestService.listForCurrentInstitution();
    }

    /** POST /api/v1/subscription-requests (JSON) — submit an upgrade/limit request without file attachment. */
    @PostMapping(value = "/api/v1/subscription-requests", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SubscriptionRequestView> submitSubscriptionRequestJson(
            @Valid @RequestBody CreateSubscriptionRequestBody body) {
        SubscriptionRequestView created = subscriptionRequestService.submitRequest(body, null);
        return ResponseEntity.created(URI.create("/api/v1/subscription-requests/" + created.id())).body(created);
    }

    /** POST /api/v1/subscription-requests (multipart) — submit an upgrade/limit request with optional/required screenshot. */
    @PostMapping(value = "/api/v1/subscription-requests", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SubscriptionRequestView> submitSubscriptionRequestMultipart(
            @RequestParam(required = false) String requestedPlanCode,
            @RequestParam(required = false) Integer requestedMaxStudents,
            @RequestParam(required = false) Integer requestedMaxRooms,
            @RequestParam(required = false) Integer requestedMaxGenerations,
            @RequestParam(required = false) Integer requestedSeatsPerGeneration,
            @RequestParam(required = false) Long requestedStorageLimitMb,
            @RequestParam(required = false) String clientNotes,
            @RequestParam(required = false) String paymentMethod,
            @RequestParam String transactionReference,
            @RequestParam BigDecimal paymentAmount,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate paymentDate,
            @RequestParam(value = "screenshot", required = false) MultipartFile screenshot) {
        CreateSubscriptionRequestBody body = new CreateSubscriptionRequestBody(
                requestedPlanCode,
                requestedMaxStudents,
                requestedMaxRooms,
                requestedMaxGenerations,
                requestedSeatsPerGeneration,
                requestedStorageLimitMb,
                clientNotes,
                paymentMethod,
                transactionReference,
                paymentAmount,
                paymentDate);
        SubscriptionRequestView created = subscriptionRequestService.submitRequest(body, screenshot);
        return ResponseEntity.created(URI.create("/api/v1/subscription-requests/" + created.id())).body(created);
    }

    /** GET /api/v1/subscription-requests/{id}/screenshot — view the payment screenshot uploaded for own request. */
    @GetMapping("/api/v1/subscription-requests/{id}/screenshot")
    public ResponseEntity<byte[]> myRequestScreenshot(@PathVariable Long id) {
        BinaryAsset asset = subscriptionRequestService.getScreenshotForCurrentInstitution(id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(asset.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + asset.filename() + "\"")
                .body(asset.bytes());
    }

    /** GET /api/v1/usage — all quota counters against the current plan. */
    @GetMapping("/api/v1/usage")
    public UsageView usage() {
        return accountService.usage();
    }

    /** GET /api/v1/generations — recent generation-limit events. */
    @GetMapping("/api/v1/generations")
    public List<GenerationView> generations() {
        return accountService.generations();
    }
}
