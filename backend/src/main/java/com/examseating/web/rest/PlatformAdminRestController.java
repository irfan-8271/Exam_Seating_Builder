package com.examseating.web.rest;

import com.examseating.dto.AdminDtos.AdminInstitutionView;
import com.examseating.dto.AdminDtos.AdminStatusView;
import com.examseating.dto.AdminDtos.AdminUserView;
import com.examseating.dto.AdminDtos.PlanChangeRequest;
import com.examseating.dto.AdminDtos.PlatformGenerationView;
import com.examseating.dto.AdminDtos.PlatformUsageView;
import com.examseating.dto.AdminDtos.UserChangeRequest;
import com.examseating.entity.User;
import com.examseating.service.PlatformAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * The operator's API — the view behind the admin screen of the frontend.
 *
 * <p>Every endpoint here needs a platform administrator: an account whose
 * role is {@code PLATFORM_ADMIN}, signed in through
 * {@code POST /api/v1/auth/login}. The check is done here and not only
 * through {@code @PreAuthorize}, because {@code @PreAuthorize} is inert while
 * the service runs in its default open mode — and an operator view must never
 * be reachable by an ordinary customer, in either mode.</p>
 *
 * <p>These endpoints cross tenant boundaries on purpose: they report on every
 * institution and change their subscriptions.</p>
 */
@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
public class PlatformAdminRestController {

    private final PlatformAdminService admin;

    /**
     * GET /api/v1/admin/status — is an operator account configured at all?
     *
     * <p>Public on purpose: the login screen uses it to tell the user that the
     * admin view needs {@code PLATFORM_ADMIN_PASSWORD} in the environment,
     * instead of failing with "invalid username or password".</p>
     */
    @GetMapping("/status")
    public AdminStatusView status() {
        return admin.status();
    }

    /** GET /api/v1/admin/institutions — every customer, with its usage. */
    @GetMapping("/institutions")
    public List<AdminInstitutionView> institutions() {
        requirePlatformAdmin();
        return admin.institutions();
    }

    /** GET /api/v1/admin/institutions/{id} — one customer in detail. */
    @GetMapping("/institutions/{id}")
    public AdminInstitutionView institution(@PathVariable Long id) {
        requirePlatformAdmin();
        return admin.institution(id);
    }

    /**
     * PUT /api/v1/admin/institutions/{id}/subscription — move an institution
     * to another plan and/or set the limits it may exceed them by.
     */
    @PutMapping("/institutions/{id}/subscription")
    public AdminInstitutionView changeSubscription(@PathVariable Long id,
                                                    @RequestBody PlanChangeRequest request) {
        requirePlatformAdmin();
        return admin.changePlan(id, request);
    }

    /** PUT /api/v1/admin/institutions/{id}/status — suspend or wake an institution. */
    @PutMapping("/institutions/{id}/status")
    public AdminInstitutionView changeStatus(@PathVariable Long id,
                                             @RequestParam String status) {
        requirePlatformAdmin();
        return admin.setStatus(id, status);
    }

    /** GET /api/v1/admin/users — every account of the service. */
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

    /** PUT /api/v1/admin/users/{id} — change a role, an institution or a password. */
    @PutMapping("/users/{id}")
    public AdminUserView changeUser(@PathVariable Long id, @RequestBody UserChangeRequest request) {
        requirePlatformAdmin();
        return admin.changeUser(id, request);
    }

    /** GET /api/v1/admin/usage — the service as a whole. */
    @GetMapping("/usage")
    public PlatformUsageView usage() {
        requirePlatformAdmin();
        return admin.usage();
    }

    /** GET /api/v1/admin/generations — the most recent plans of the whole service. */
    @GetMapping("/generations")
    public List<PlatformGenerationView> generations(@RequestParam(defaultValue = "50") int limit) {
        requirePlatformAdmin();
        return admin.generations(limit);
    }

    /**
     * Refuses everybody who is not a platform administrator.
     *
     * <p>Works in both security modes: in {@code enforced} mode the filter
     * chain already demands credentials, and here the role is checked; in
     * {@code open} mode the role check is the only thing standing between a
     * customer and the operator's view.</p>
     */
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
