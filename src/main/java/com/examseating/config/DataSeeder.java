package com.examseating.config;

import com.examseating.entity.User;
import com.examseating.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Creates demo user accounts on first startup (users table empty).
 *
 * <p>{@link CommandLineRunner}: a Spring hook — after the application context is fully
 * built and ready, Boot executes every {@code CommandLineRunner} bean's
 * {@code run(...)} once. Handy for seeding reference data.</p>
 */
@Component
@RequiredArgsConstructor
public class DataSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataSeeder.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @Override
    public void run(String... args) {
        if (userRepository.count() > 0) {
            return;
        }
        userRepository.save(User.builder()
                .username("admin")
                .password(passwordEncoder.encode("admin123"))
                .role(User.Role.ADMIN)
                .fullName("System Administrator")
                .build());
        userRepository.save(User.builder()
                .username("examcell")
                .password(passwordEncoder.encode("exam123"))
                .role(User.Role.EXAM_CELL)
                .fullName("Exam Cell Officer")
                .build());
        userRepository.save(User.builder()
                .username("invigilator")
                .password(passwordEncoder.encode("invig123"))
                .role(User.Role.INVIGILATOR)
                .fullName("Hall Invigilator")
                .build());
        log.info("Seeded demo users: admin/admin123 (ADMIN), examcell/exam123 (EXAM_CELL), invigilator/invig123 (INVIGILATOR)");
    }
}
