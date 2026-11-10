package com.examseating.web;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Minimal auth endpoints to demonstrate HTTP Basic login.
 *
 * <p>{@code Authentication} is Spring Security's object representing "who is logged
 * in". When a method declares it as a parameter, Spring fills it from the
 * SecurityContext (which the authentication filter populated). No explicit lookup —
 * that is dependency injection of the security state.</p>
 *
 * @deprecated legacy alias kept for existing clients. Use the resource-oriented
 * <code>/api/v1/auth</code> endpoints instead.
 */
@Deprecated
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> me(Authentication authentication) {
        // Method security is only enabled in "enforced" mode, so in the
        // default "open" mode this method runs without an Authentication.
        // Refuse cleanly instead of throwing a NullPointerException (500).
        if (authentication == null
                || !(authentication.getPrincipal() instanceof org.springframework.security.core.userdetails.User principal)) {
            throw new org.springframework.security.access.AccessDeniedException("Authentication required");
        }
        return Map.of(
                "username", principal.getUsername(),
                "roles", principal.getAuthorities().stream()
                        .map(Object::toString)
                        .toList());
    }
}
