package com.examseating.config;

import com.examseating.entity.Institution;
import com.examseating.entity.User;
import com.examseating.repository.InstitutionRepository;
import com.examseating.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates the platform administrator — the operator of the service as a
 * whole, not of one institution.
 *
 * <p>The account is created on every boot from the environment, so the
 * credentials never live in the repository:</p>
 *
 * <pre>
 * PLATFORM_ADMIN_USERNAME=platform-admin
 * PLATFORM_ADMIN_PASSWORD=&lt;a strong password&gt;
 * PLATFORM_ADMIN_EMAIL=ops@example.com      (optional)
 * </pre>
 *
 * <p>A {@code .env} file next to the project (loaded through
 * {@code spring.config.import}) is the usual place for those three lines.
 * <b>When no password is configured, no platform administrator is created</b> —
 * the service then simply has no operator account, and the login screen says
 * so instead of pretending.</p>
 *
 * <p>The password is only written when the account is created or when the
 * configured password differs from the stored one — so changing the password
 * in {@code .env} and restarting is enough to rotate it.</p>
 */
@Component
@RequiredArgsConstructor
public class PlatformAdminSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminSeeder.class);

    /** The institution that owns the platform operator. */
    public static final String PLATFORM_SLUG = "platform";

    private final InstitutionRepository institutionRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${PLATFORM_ADMIN_USERNAME:}")
    private String username;

    @Value("${PLATFORM_ADMIN_PASSWORD:}")
    private String password;

    @Value("${PLATFORM_ADMIN_EMAIL:}")
    private String email;

    @Override
    @Transactional
    public void run(String... args) {
        if (password == null || password.isBlank()) {
            log.info("No PLATFORM_ADMIN_PASSWORD configured — no platform administrator is created. "
                    + "Set PLATFORM_ADMIN_USERNAME and PLATFORM_ADMIN_PASSWORD (for example in a .env file) "
                    + "to enable the admin view.");
            return;
        }
        if (username == null || username.isBlank()) {
            username = "platform-admin";
        }

        Institution platform = institutionRepository.findBySlug(PLATFORM_SLUG)
                .orElseGet(() -> institutionRepository.save(Institution.builder()
                        .name("Platform")
                        .slug(PLATFORM_SLUG)
                        .status(Institution.Status.ACTIVE)
                        .platform(true)
                        .build()));

        User admin = userRepository.findByUsername(username.trim()).orElse(null);
        if (admin == null) {
            admin = User.builder()
                    .username(username.trim())
                    .password(passwordEncoder.encode(password))
                    .role(User.Role.PLATFORM_ADMIN)
                    .fullName("Platform administrator")
                    .email(email == null || email.isBlank() ? null : email.trim())
                    .institutionId(platform.getId())
                    .build();
            userRepository.save(admin);
            log.info("Platform administrator '{}' created. Sign in with it to open the admin view.",
                    admin.getUsername());
            return;
        }

        // The account exists — keep it in step with the configuration.
        boolean changed = false;
        if (!admin.getRole().equals(User.Role.PLATFORM_ADMIN)) {
            admin.setRole(User.Role.PLATFORM_ADMIN);
            changed = true;
        }
        if (!admin.getInstitutionId().equals(platform.getId())) {
            admin.setInstitutionId(platform.getId());
            changed = true;
        }
        if (!passwordEncoder.matches(password, admin.getPassword())) {
            admin.setPassword(passwordEncoder.encode(password));
            changed = true;
            log.info("Password of the platform administrator '{}' was updated from the configuration.",
                    admin.getUsername());
        }
        if (changed) {
            userRepository.save(admin);
        }
    }
}
