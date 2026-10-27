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
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @GetMapping("/me")
    @PreAuthorize("isAuthenticated()")
    public Map<String, Object> me(Authentication authentication) {
        org.springframework.security.core.userdetails.User principal =
                (org.springframework.security.core.userdetails.User) authentication.getPrincipal();
        return Map.of(
                "username", principal.getUsername(),
                "roles", principal.getAuthorities().stream()
                        .map(Object::toString)
                        .toList());
    }
}
