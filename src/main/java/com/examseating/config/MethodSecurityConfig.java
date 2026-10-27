package com.examseating.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;

/**
 * Turns on {@code @PreAuthorize} enforcement for the role-based endpoints.
 *
 * <p>Method security is <b>opt-in</b> via {@code app.security.mode=enforced}
 * so the demo build stays open out of the box ("seamless testing"), while a
 * real deployment flips one property to enforce the ADMIN / EXAM_CELL /
 * INVIGILATOR role model declared on the controllers. In open mode the
 * {@code @PreAuthorize} annotations are simply not enforced — everything is
 * permitted, and {@link SecurityConfig} logs a warning at startup.</p>
 */
@Configuration
@ConditionalOnProperty(name = "app.security.mode", havingValue = "enforced")
@EnableMethodSecurity
public class MethodSecurityConfig {
}
