package com.examseating.web.rest;

import com.examseating.dto.AccountDtos.AuthSession;
import com.examseating.dto.AccountDtos.GenerationView;
import com.examseating.dto.AccountDtos.InstitutionUpdate;
import com.examseating.dto.AccountDtos.InstitutionView;
import com.examseating.dto.AccountDtos.LoginRequest;
import com.examseating.dto.AccountDtos.PlanChangeRequest;
import com.examseating.dto.AccountDtos.PlanView;
import com.examseating.dto.AccountDtos.RegisterRequest;
import com.examseating.dto.AccountDtos.SubscriptionView;
import com.examseating.dto.AccountDtos.UsageView;
import com.examseating.service.AccountService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Institutional accounts, plans, subscriptions and usage.
 *
 * <p>Register/login are public; everything else is readable by an authenticated
 * member of the institution and writable only by an ADMIN. The bearer token is
 * resolved by {@code TenantFilter} into the request's tenant context.</p>
 */
@RestController
@RequiredArgsConstructor
public class AccountRestController {

    private final AccountService accountService;

    /** POST /api/v1/auth/register — create an institution, its admin and a FREE subscription. */
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

    /** PUT /api/v1/subscriptions/current — upgrade or downgrade (409/429 if usage blocks it). */
    @PutMapping("/api/v1/subscriptions/current")
    @PreAuthorize("hasRole('ADMIN')")
    public SubscriptionView changePlan(@Valid @RequestBody PlanChangeRequest request) {
        return accountService.changePlan(request);
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
