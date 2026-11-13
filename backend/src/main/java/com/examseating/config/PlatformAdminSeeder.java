package com.examseating.config;

import com.examseating.entity.Institution;
import com.examseating.entity.PaymentConfig;
import com.examseating.entity.User;
import com.examseating.repository.InstitutionRepository;
import com.examseating.repository.PaymentConfigRepository;
import com.examseating.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

/**
 * Seeds the platform administrator account from environment variables and
 * ensures a baseline {@link PaymentConfig} row exists for manual payment
 * verification.
 *
 * <pre>
 * PLATFORM_ADMIN_USERNAME=platform-admin
 * PLATFORM_ADMIN_PASSWORD=&lt;a strong password&gt;
 * PLATFORM_ADMIN_EMAIL=ops@example.com      (optional)
 * </pre>
 */
@Component
@RequiredArgsConstructor
public class PlatformAdminSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminSeeder.class);

    /** The institution that owns the platform operator. */
    public static final String PLATFORM_SLUG = "platform";

    private final InstitutionRepository institutionRepository;
    private final UserRepository userRepository;
    private final PaymentConfigRepository paymentConfigRepository;
    private final PasswordEncoder passwordEncoder;

    @Value("${PLATFORM_ADMIN_USERNAME:}")
    private String username;

    @Value("${PLATFORM_ADMIN_PASSWORD:}")
    private String password;

    @Value("${PLATFORM_ADMIN_EMAIL:}")
    private String email;

    @Value("${PAYMENT_UPI_ID:}")
    private String defaultUpiId;

    @Value("${PAYMENT_PAYEE_NAME:ExamSeating Billing}")
    private String defaultPayeeName;

    @Value("${PAYMENT_INSTRUCTIONS:}")
    private String defaultInstructions;

    @Override
    @Transactional
    public void run(String... args) {
        seedDefaultPaymentConfig();

        if (password == null || password.isBlank()) {
            log.info("No PLATFORM_ADMIN_PASSWORD configured — no platform administrator is created. "
                    + "Set PLATFORM_ADMIN_USERNAME and PLATFORM_ADMIN_PASSWORD (for example in a .env file) "
                    + "to enable the admin view.");
            return;
        }
        String resolvedUsername = (username == null || username.isBlank()) ? "platform-admin" : username.trim();
        String resolvedEmail = (email == null || email.isBlank()) ? null : email.trim();

        Institution platform = institutionRepository.findBySlug(PLATFORM_SLUG)
                .orElseGet(() -> institutionRepository.save(Institution.builder()
                        .name("Platform")
                        .slug(PLATFORM_SLUG)
                        .status(Institution.Status.ACTIVE)
                        .platform(true)
                        .build()));

        User admin = userRepository.findByUsername(resolvedUsername).orElse(null);
        if (admin == null) {
            admin = User.builder()
                    .username(resolvedUsername)
                    .password(passwordEncoder.encode(password))
                    .role(User.Role.PLATFORM_ADMIN)
                    .status(User.Status.ACTIVE)
                    .fullName("Platform administrator")
                    .email(resolvedEmail)
                    .institutionId(platform.getId())
                    .build();
            userRepository.save(admin);
            log.info("Platform administrator '{}' created. Sign in with it to open the admin view.",
                    admin.getUsername());
            return;
        }

        boolean changed = false;
        if (!admin.getRole().equals(User.Role.PLATFORM_ADMIN)) {
            admin.setRole(User.Role.PLATFORM_ADMIN);
            changed = true;
        }
        if (admin.effectiveStatus() != User.Status.ACTIVE) {
            admin.setStatus(User.Status.ACTIVE);
            changed = true;
        }
        if (!Objects.equals(admin.getInstitutionId(), platform.getId())) {
            admin.setInstitutionId(platform.getId());
            changed = true;
        }
        if (resolvedEmail != null && !Objects.equals(admin.getEmail(), resolvedEmail)) {
            admin.setEmail(resolvedEmail);
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

    private void seedDefaultPaymentConfig() {
        paymentConfigRepository.findByConfigKey(PaymentConfig.DEFAULT_KEY).orElseGet(() -> {
            String instructions = (defaultInstructions != null && !defaultInstructions.isBlank())
                    ? defaultInstructions.trim()
                    : "Complete the payment for your selected plan or custom capacity package using the UPI ID "
                      + "or QR code shown here, then enter the 12-digit UTR / Transaction ID, amount paid, and "
                      + "payment date below. Your workspace entitlements are updated after platform administrator verification.";
            return paymentConfigRepository.save(PaymentConfig.builder()
                    .configKey(PaymentConfig.DEFAULT_KEY)
                    .paymentMode(PaymentConfig.PaymentMode.MANUAL)
                    .upiId(defaultUpiId != null && !defaultUpiId.isBlank() ? defaultUpiId.trim() : "billing@examseating")
                    .payeeName(defaultPayeeName != null && !defaultPayeeName.isBlank() ? defaultPayeeName.trim() : "ExamSeating Billing")
                    .instructions(instructions)
                    .additionalInfo("Include your institution name in the payment remark for faster verification.")
                    .requireScreenshot(false)
                    .updatedAt(Instant.now())
                    .build());
        });
    }
}
