package com.examseating;

import com.examseating.entity.Institution;
import com.examseating.entity.StoredFile;
import com.examseating.entity.SubscriptionRequest;
import com.examseating.entity.User;
import com.examseating.repository.ActivityLogRepository;
import com.examseating.repository.InstitutionRepository;
import com.examseating.repository.StoredFileRepository;
import com.examseating.repository.SubscriptionRequestRepository;
import com.examseating.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The operator's storage console: what the database is holding, and what may
 * be reclaimed.
 *
 * <p>The property that matters most is the guard on pending requests: a
 * screenshot attached to an upgrade request that is still awaiting a decision
 * is the evidence used to approve or reject it. A cleanup that removed it
 * would destroy the proof of payment rather than tidy up after it, so those
 * are never eligible.</p>
 *
 * <p>The second property is that nothing is deleted without an explicit
 * confirmation, and the preview reports exactly what would go first.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class StorageAdminIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InstitutionRepository institutionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private StoredFileRepository storedFileRepository;

    @Autowired
    private SubscriptionRequestRepository subscriptionRequestRepository;

    @Autowired
    private ActivityLogRepository activityLogRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long customerId;

    @BeforeEach
    void prepare() {
        storedFileRepository.deleteAll();
        subscriptionRequestRepository.deleteAll();

        Institution customer = institutionRepository.findBySlug("storage-college")
                .orElseGet(() -> institutionRepository.save(Institution.builder()
                        .name("Storage College")
                        .slug("storage-college")
                        .status(Institution.Status.ACTIVE)
                        .build()));
        customerId = customer.getId();

        User operator = userRepository.findByUsername("storage-admin")
                .orElseGet(() -> userRepository.save(User.builder()
                        .username("storage-admin")
                        .password(passwordEncoder.encode("operator-password"))
                        .role(User.Role.PLATFORM_ADMIN)
                        .fullName("Storage operator")
                        .institutionId(customerId)
                        .build()));
        operator.setRole(User.Role.PLATFORM_ADMIN);
        operator.setPassword(passwordEncoder.encode("operator-password"));
        userRepository.save(operator);
    }

    private String operatorToken() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"storage-admin\",\"password\":\"operator-password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    private StoredFile storeFile(String name, int sizeBytes, int ageDays) {
        byte[] bytes = new byte[sizeBytes];
        java.util.Arrays.fill(bytes, (byte) 'x');
        StoredFile file = StoredFile.builder()
                .institutionId(customerId)
                .originalName(name)
                .storedName("store-" + name + "-" + ageDays)
                .content(bytes)
                .contentType("application/octet-stream")
                .sizeBytes(sizeBytes)
                // uploaded_at is `updatable = false`, so it has to be set before
                // the first insert for the age filters to have anything to match.
                .uploadedAt(Instant.now().minus(ageDays, ChronoUnit.DAYS))
                .build();
        return storedFileRepository.save(file);
    }

    private SubscriptionRequest storeRequest(String status, byte[] screenshot, int ageDays) {
        SubscriptionRequest request = SubscriptionRequest.builder()
                .institutionId(customerId)
                .institutionName("Storage College")
                .requestType(SubscriptionRequest.RequestType.PLAN_UPGRADE)
                .currentPlanCode("FREE")
                .requestedPlanCode("STARTER")
                .paymentMethod(SubscriptionRequest.PaymentMethod.MANUAL_UPI)
                .transactionReference("UTR-" + status + "-" + ageDays + "-" + System.nanoTime())
                .paymentAmount(new java.math.BigDecimal("199.00"))
                .paymentDate(java.time.LocalDate.now().minusDays(ageDays))
                .status(SubscriptionRequest.Status.valueOf(status))
                .screenshotBytes(screenshot)
                .screenshotFilename("proof.png")
                .screenshotContentType("image/png")
                .build();
        request.setCreatedAt(Instant.now().minus(ageDays, ChronoUnit.DAYS));
        return subscriptionRequestRepository.save(request);
    }

    // ------------------------------------------------------------------
    //  Access control
    // ------------------------------------------------------------------

    @Test
    @DisplayName("An anonymous caller gets no storage view")
    void anAnonymousCallerIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/admin/storage"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("An operator sees the storage breakdown")
    void anOperatorSeesTheStorageBreakdown() throws Exception {
        String token = operatorToken();
        storeFile("roster.xlsx", 1000, 3);

        mockMvc.perform(get("/api/v1/admin/storage").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.categories").isArray())
                .andExpect(jsonPath("$.totalBytes").exists())
                .andExpect(jsonPath("$.reclaimableBytes").exists())
                .andExpect(jsonPath("$.protectedBytes").exists())
                .andExpect(jsonPath("$.notes").isArray());
    }

    // ------------------------------------------------------------------
    //  Stored files
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A stored-file preview reports what would go without removing it")
    void aPreviewRemovesNothing() throws Exception {
        String token = operatorToken();
        storeFile("old.xlsx", 500, 90);
        storeFile("new.xlsx", 500, 1);

        mockMvc.perform(post("/api/v1/admin/storage/stored-files/preview")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"olderThanDays\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(1))
                .andExpect(jsonPath("$.bytes").value(500));

        assertEquals(2, storedFileRepository.count(), "a preview must not delete anything");
    }

    @Test
    @DisplayName("A stored-file cleanup removes only the files older than the given age")
    void aCleanupRespectsTheAgeLimit() throws Exception {
        String token = operatorToken();
        StoredFile old = storeFile("old.xlsx", 500, 90);
        StoredFile fresh = storeFile("new.xlsx", 500, 1);

        mockMvc.perform(post("/api/v1/admin/storage/stored-files/cleanup")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"olderThanDays\":30,\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(1))
                .andExpect(jsonPath("$.freedBytes").value(500));

        assertTrue(storedFileRepository.findById(old.getId()).isEmpty());
        assertTrue(storedFileRepository.findById(fresh.getId()).isPresent(),
                "a file inside the age limit must survive");
    }

    @Test
    @DisplayName("A cleanup without confirmation deletes nothing")
    void aCleanupNeedsConfirmation() throws Exception {
        String token = operatorToken();
        storeFile("roster.xlsx", 500, 90);

        mockMvc.perform(post("/api/v1/admin/storage/stored-files/cleanup")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"olderThanDays\":0,\"confirm\":false}"))
                .andExpect(status().is4xxClientError());

        assertEquals(1, storedFileRepository.count(), "nothing may be deleted without confirmation");
    }

    // ------------------------------------------------------------------
    //  Screenshots
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A screenshot on a pending request is never eligible for deletion")
    void aPendingScreenshotIsProtected() throws Exception {
        String token = operatorToken();
        SubscriptionRequest pending = storeRequest("PENDING", "proof-bytes".getBytes(StandardCharsets.UTF_8), 120);

        // A preview must not list it.
        mockMvc.perform(post("/api/v1/admin/storage/screenshots/preview")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"olderThanDays\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemCount").value(0));

        // And a cleanup must not remove it.
        mockMvc.perform(post("/api/v1/admin/storage/screenshots/cleanup")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"olderThanDays\":0,\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(0));

        SubscriptionRequest reloaded = subscriptionRequestRepository.findById(pending.getId()).orElseThrow();
        assertNotNull(reloaded.getScreenshotBytes(),
                "the evidence used to decide a pending request must never be deleted");
    }

    @Test
    @DisplayName("A screenshot on an approved request is removed, but the request stays")
    void anApprovedScreenshotIsRemovedButTheRequestRemains() throws Exception {
        String token = operatorToken();
        SubscriptionRequest approved = storeRequest("APPROVED", "proof-bytes".getBytes(StandardCharsets.UTF_8), 120);

        mockMvc.perform(post("/api/v1/admin/storage/screenshots/cleanup")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"olderThanDays\":0,\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(1));

        SubscriptionRequest reloaded = subscriptionRequestRepository.findById(approved.getId()).orElseThrow();
        assertNull(reloaded.getScreenshotBytes(), "the screenshot should be gone");
        assertNull(reloaded.getScreenshotFilename());
        assertNull(reloaded.getScreenshotContentType());
        assertEquals(SubscriptionRequest.Status.APPROVED, reloaded.getStatus(),
                "the request and its decision must survive the cleanup");
        assertNotNull(reloaded.getRequestedPlanCode());
    }

    @Test
    @DisplayName("A screenshot cleanup only touches requests older than the given age")
    void aScreenshotCleanupRespectsTheAgeLimit() throws Exception {
        String token = operatorToken();
        SubscriptionRequest old = storeRequest("REJECTED", "a".getBytes(StandardCharsets.UTF_8), 90);
        SubscriptionRequest fresh = storeRequest("REJECTED", "b".getBytes(StandardCharsets.UTF_8), 2);

        mockMvc.perform(post("/api/v1/admin/storage/screenshots/cleanup")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"olderThanDays\":30,\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deletedCount").value(1));

        assertNull(subscriptionRequestRepository.findById(old.getId()).orElseThrow().getScreenshotBytes());
        assertNotNull(subscriptionRequestRepository.findById(fresh.getId()).orElseThrow().getScreenshotBytes());
    }

    // ------------------------------------------------------------------
    //  Reporting & audit
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The breakdown separates reclaimable bytes from protected evidence")
    void theBreakdownSeparatesReclaimableFromProtected() throws Exception {
        String token = operatorToken();
        storeFile("roster.xlsx", 1000, 5);
        storeRequest("APPROVED", "a".getBytes(StandardCharsets.UTF_8), 5);
        storeRequest("PENDING", "b".getBytes(StandardCharsets.UTF_8), 5);

        mockMvc.perform(get("/api/v1/admin/storage").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reclaimableBytes").value(1001))
                .andExpect(jsonPath("$.protectedBytes").value(1));
    }

    @Test
    @DisplayName("A cleanup is written to the activity audit trail")
    void aCleanupIsAudited() throws Exception {
        String token = operatorToken();
        storeFile("roster.xlsx", 500, 90);

        mockMvc.perform(post("/api/v1/admin/storage/stored-files/cleanup")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"olderThanDays\":0,\"confirm\":true}"))
                .andExpect(status().isOk());

        boolean logged = activityLogRepository.findAll().stream()
                .anyMatch(entry -> "STORAGE_CLEANUP".equals(String.valueOf(entry.getType())));
        assertTrue(logged, "a destructive action must leave a trace in the audit trail");
    }

    @Test
    @DisplayName("An ordinary customer cannot reach the storage console")
    void aCustomerIsRefused() throws Exception {
        Institution other = institutionRepository.findBySlug("storage-customer")
                .orElseGet(() -> institutionRepository.save(Institution.builder()
                        .name("Storage Customer")
                        .slug("storage-customer")
                        .status(Institution.Status.ACTIVE)
                        .build()));
        userRepository.save(User.builder()
                .username("storage-customer-user")
                .password(passwordEncoder.encode("customer-password"))
                .role(User.Role.ADMIN)
                .fullName("Ordinary admin")
                .institutionId(other.getId())
                .build());

        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"storage-customer-user\",\"password\":\"customer-password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/v1/admin/storage").header("Authorization", "Bearer " + token))
                .andExpect(status().is4xxClientError());
    }
}
