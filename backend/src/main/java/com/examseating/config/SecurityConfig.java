package com.examseating.config;

import com.examseating.entity.User;
import com.examseating.tenancy.TenantFilter;
import com.examseating.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;

import java.util.List;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Spring Security configuration — the security layer of the REST API.
 *
 * <p>Two modes via {@code app.security.mode} (env {@code SECURITY_MODE}):</p>
 * <ul>
 *   <li>{@code open} (default, demo/testing) — the UI and API are reachable without
 *       credentials and {@code @PreAuthorize} is not enforced (see
 *       {@link MethodSecurityConfig}); a warning is logged at startup.</li>
 *   <li>{@code enforced} — every request must authenticate (HTTP Basic) and the
 *       ADMIN / EXAM_CELL / INVIGILATOR role model is enforced. The 401 challenge
 *       makes browsers prompt for credentials (including for PDF/Excel downloads).</li>
 * </ul>
 *
 * <p>BCrypt hashes are stored per user; passwords never appear in the database
 * in plain text.</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private static final Logger log = LoggerFactory.getLogger(SecurityConfig.class);

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           @Value("${app.security.mode:open}") String securityMode,
                                           TenantFilter tenantFilter) throws Exception {
        boolean enforced = "enforced".equalsIgnoreCase(securityMode);
        http
            // No cookie-based session and no browser session auth (stateless HTTP Basic),
            // so CSRF tokens do not apply. Re-enable if cookie auth is ever introduced.
            .csrf(csrf -> csrf.disable())
            .cors(Customizer.withDefaults())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        // Resolves the tenant and turns an opaque bearer token into a Spring
        // Security authentication before authorization decisions are made.
        http.addFilterBefore(tenantFilter, UsernamePasswordAuthenticationFilter.class);

        if (enforced) {
            BasicAuthenticationEntryPoint entryPoint = new BasicAuthenticationEntryPoint();
            entryPoint.setRealmName("ExamSeating");
            http
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/", "/error").permitAll()
                    .requestMatchers("/api/v1/auth/register", "/api/v1/auth/login",
                                    "/api/v1/auth/platform/login", "/api/v1/admin/status",
                                    "/api/v1/plans").permitAll()
                    .anyRequest().authenticated())
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint));
            log.info("Security enforced (app.security.mode=enforced) — API access requires HTTP Basic credentials");
        } else {
            http
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/", "/error").permitAll()
                    .requestMatchers("/api/**").permitAll()
                    .anyRequest().permitAll())
                .httpBasic(Customizer.withDefaults());
            log.warn("app.security.mode=open — authentication is DISABLED. "
                    + "Set app.security.mode=enforced (or SECURITY_MODE=enforced) for role-based access control.");
        }

        return http.build();
    }

    /** Password hashing strategy (BCrypt, salted, deliberately slow). */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Loads a user from the {@code users} table and adapts it to Spring Security's
     * {@link UserDetailsService} contract (roles become {@code ROLE_<NAME>} authorities).
     */
    @Bean
    public UserDetailsService userDetailsService(UserRepository userRepository) {
        return username -> {
            User user = userRepository.findByUsername(username)
                    .orElseThrow(() -> new UsernameNotFoundException("No user found for: " + username));
            return org.springframework.security.core.userdetails.User
                    .withUsername(user.getUsername())
                    .password(user.getPassword())      // already a BCrypt hash
                    .roles(user.getRole().name())       // "ADMIN" -> authority ROLE_ADMIN
                    .build();
        };
    }

    /**
     * Where the frontend may come from.
     *
     * <p>Set {@code CORS_ALLOWED_ORIGINS} to the origins that should be
     * allowed — a comma-separated list — when the frontend is served from
     * somewhere else. The default allows every origin, which is convenient in
     * development and should be narrowed for a public deployment.</p>
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource(
            @Value("${app.cors.allowed-origins:*}") String allowedOrigins) {
        CorsConfiguration config = new CorsConfiguration();
        if ("*".equals(allowedOrigins.trim())) {
            config.addAllowedOriginPattern("*");
        } else {
            for (String origin : allowedOrigins.split(",")) {
                config.addAllowedOrigin(origin.trim());
            }
        }
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setExposedHeaders(List.of("Authorization", "Content-Disposition"));
        config.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

}
