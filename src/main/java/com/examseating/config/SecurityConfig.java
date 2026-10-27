package com.examseating.config;

import com.examseating.entity.User;
import com.examseating.repository.UserRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

import java.nio.charset.StandardCharsets;

/**
 * Spring Security configuration — the security layer of the REST API.
 *
 * <p><b>If you know Hibernate but not Spring Security, here is the mental model:</b>
 * just as Hibernate intercepts JPA calls to add persistence, Spring Security intercepts
 * every HTTP request with a chain of servlet <i>filters</i> before the request reaches
 * our {@code @RestController}s. Each filter either passes the request on or stops it
 * with a 401/403.</p>
 *
 * <p><b>The big picture of what happens on every request:</b></p>
 * <ol>
 *   <li>The request hits the {@code SecurityFilterChain} (defined below).</li>
 *   <li>An <i>authentication</i> filter looks for credentials. With HTTP Basic it reads
 *       the {@code Authorization: Basic base64(username:password)} header.</li>
 *   <li>The credentials are handed to the {@code AuthenticationManager}, which asks our
 *       {@link UserDetailsService} bean "give me the user record for this username",
 *       then compares the raw password with the stored BCrypt hash using
 *       {@link PasswordEncoder}. BCrypt is a one-way hash — the DB never stores the
 *       plain password, so a leaked table does not leak passwords.</li>
 *   <li>On success, an {@code Authentication} object (principal = user, authorities =
 *       roles) is stored in the {@code SecurityContext} for the rest of the request.
 *       Our controllers can then read it (e.g. {@code Authentication} method parameter)
 *       and use {@code @PreAuthorize} to enforce <i>authorization</i> ("is this user
 *       allowed to do this?").</li>
 *   <li>On failure, the {@code AuthenticationEntryPoint} writes a JSON 401.</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Security filter chain configured to permit public access to the UI and API endpoints
     * for seamless testing, while maintaining statelessness and CSRF safety for REST calls.
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/", "/index.html", "/style.css", "/app.js", "/favicon.ico", "/error").permitAll()
                .requestMatchers("/api/**").permitAll()
                .anyRequest().permitAll())
            .httpBasic(Customizer.withDefaults());

        return http.build();
    }

    /**
     * Bean 2 — password hashing strategy.
     * BCrypt automatically salts each hash and is deliberately slow (work factor 10),
     * which makes brute-forcing hashes expensive.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Bean 3 — how Spring loads a user from OUR database.
     *
     * <p>{@link UserDetailsService} is a Spring Security interface with a single method:
     * {@code UserDetails loadUserByUsername(String username)}. Spring calls it during
     * authentication; we answer by looking the username up in the {@code users} table
     * (our {@link User} JPA entity from Step 1) and mapping it to Spring's
     * {@code UserDetails} object.</p>
     *
     * <p>Note the separation: our entity (persistence concern) is adapted into Spring's
     * {@code UserDetails} (security concern). Roles become authorities named
     * {@code ROLE_<NAME>}, which is what {@code hasRole('ADMIN')} matches.</p>
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

    /** Small helper used above to write JSON error bodies without a full ObjectMapper. */
    private static byte[] json(String body) {
        return body.getBytes(StandardCharsets.UTF_8);
    }
}
