package com.examseating.tenancy;

import com.examseating.entity.AuthToken;
import com.examseating.entity.User;
import com.examseating.repository.AuthTokenRepository;
import com.examseating.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Resolves the institution for every request and publishes a Spring Security
 * authentication for opaque bearer tokens.
 *
 * <p>Resolution order: a valid {@code Authorization: Bearer} token, then (in
 * open/demo mode only) an {@code X-Institution-Id} header, then the default
 * institution. In {@code SECURITY_MODE=enforced} the header is ignored so a
 * caller cannot switch tenant by hand.</p>
 */
public class TenantFilter extends OncePerRequestFilter {

    private final AuthTokenRepository tokenRepository;
    private final UserRepository userRepository;
    private final TenantSupport tenantSupport;
    private final boolean enforced;

    public TenantFilter(AuthTokenRepository tokenRepository,
                        UserRepository userRepository,
                        TenantSupport tenantSupport,
                        boolean enforced) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
        this.tenantSupport = tenantSupport;
        this.enforced = enforced;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        try {
            Long institutionId = resolveFromToken(request);
            if (institutionId == null && !enforced) {
                institutionId = parseHeader(request.getHeader("X-Institution-Id"));
            }
            TenantContext.set(institutionId != null ? institutionId : tenantSupport.defaultId());
            chain.doFilter(request, response);
        } finally {
            TenantContext.clear();
        }
    }

    private Long resolveFromToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        String token = header.substring(7).trim();
        if (token.isEmpty()) {
            return null;
        }
        Optional<AuthToken> stored = tokenRepository.findByTokenAndExpiresAtAfter(token, Instant.now());
        if (stored.isEmpty()) {
            return null;
        }
        AuthToken authToken = stored.get();
        Optional<User> user = userRepository.findById(authToken.getUserId());
        if (user.isEmpty() || user.get().getInstitutionId() == null) {
            return null;
        }
        User account = user.get();
        UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                account.getUsername(), null,
                List.of(new SimpleGrantedAuthority("ROLE_" + account.getRole().name())));
        SecurityContextHolder.getContext().setAuthentication(authentication);
        return account.getInstitutionId();
    }

    private static Long parseHeader(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Long.valueOf(raw.trim());
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
