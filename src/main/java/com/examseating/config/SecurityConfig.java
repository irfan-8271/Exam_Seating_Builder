package com.examseating.config;

import com.examseating.entity.User;
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
import org.springframework.security.web.authentication.www.BasicAuthenticationEntryPoint;

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
                                           @Value("${app.security.mode:open}") String securityMode) throws Exception {
        boolean enforced = "enforced".equalsIgnoreCase(securityMode);
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        if (enforced) {
            BasicAuthenticationEntryPoint entryPoint = new BasicAuthenticationEntryPoint();
            entryPoint.setRealmName("ExamSeating");
            http
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/", "/index.html", "/style.css", "/app.js", "/favicon.ico", "/error").permitAll()
                    .anyRequest().authenticated())
                .httpBasic(basic -> basic.authenticationEntryPoint(entryPoint));
            log.info("Security enforced (app.security.mode=enforced) — API access requires HTTP Basic credentials");
        } else {
            http
                .authorizeHttpRequests(auth -> auth
                    .requestMatchers("/", "/index.html", "/style.css", "/app.js", "/favicon.ico", "/error").permitAll()
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
}
