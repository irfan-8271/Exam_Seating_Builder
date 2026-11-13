package com.examseating;

import com.examseating.entity.Institution;
import com.examseating.entity.Subscription;
import com.examseating.entity.User;
import com.examseating.repository.InstitutionRepository;
import com.examseating.repository.SubscriptionRepository;
import com.examseating.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The operator's view: who may see it, and what it may change.
 *
 * <p>The important property is the first one: an ordinary customer must never
 * reach the operator's endpoints — not even while the service runs in its
 * default open mode, where {@code @PreAuthorize} is not enforced.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
public class PlatformAdminIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InstitutionRepository institutionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long customerId;

    @BeforeEach
    void prepare() {
        Institution customer = institutionRepository.findBySlug("test-college")
                .orElseGet(() -> institutionRepository.save(Institution.builder()
                        .name("Test College")
                        .slug("test-college")
                        .status(Institution.Status.ACTIVE)
                        .build()));
        customerId = customer.getId();

        subscriptionRepository.findByInstitutionId(customerId)
                .orElseGet(() -> subscriptionRepository.save(Subscription.builder()
                        .institutionId(customerId)
                        .planCode("FREE")
                        .status(Subscription.Status.ACTIVE)
                        .build()));

        // A platform administrator to sign in as.
        User operator = userRepository.findByUsername("test-platform-admin")
                .orElseGet(() -> userRepository.save(User.builder()
                        .username("test-platform-admin")
                        .password(passwordEncoder.encode("operator-password"))
                        .role(User.Role.PLATFORM_ADMIN)
                        .fullName("Test operator")
                        .institutionId(customerId)
                        .build()));
        operator.setRole(User.Role.PLATFORM_ADMIN);
        operator.setPassword(passwordEncoder.encode("operator-password"));
        userRepository.save(operator);
    }

    private String operatorToken() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"test-platform-admin\",\"password\":\"operator-password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    @DisplayName("The status endpoint tells whether an operator account exists without leaking credentials")
    void theStatusEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/admin/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").exists())
                .andExpect(jsonPath("$.username").doesNotExist());
    }

    @Test
    @DisplayName("An anonymous caller gets no operator view")
    void anAnonymousCallerIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/admin/institutions"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/api/v1/admin/users"))
                .andExpect(status().is4xxClientError());
        mockMvc.perform(get("/api/v1/admin/usage"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("A signed-in operator sees the institutions, the users and the usage")
    void anOperatorSeesEverything() throws Exception {
        String token = operatorToken();

        mockMvc.perform(get("/api/v1/admin/institutions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(get("/api/v1/admin/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());

        mockMvc.perform(get("/api/v1/admin/usage").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.institutions").exists())
                .andExpect(jsonPath("$.users").exists())
                .andExpect(jsonPath("$.students").exists())
                .andExpect(jsonPath("$.byPlan").isArray());
    }

    @Test
    @DisplayName("An operator raises an institution's limits and moves it to another plan")
    void anOperatorChangesTheSubscription() throws Exception {
        String token = operatorToken();

        mockMvc.perform(put("/api/v1/admin/institutions/{id}/subscription", customerId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"planCode\":\"STARTER\",\"limits\":{\"maxStudents\":250}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value("STARTER"))
                .andExpect(jsonPath("$.studentsLimit").value(250))
                .andExpect(jsonPath("$.overrides.maxStudents").value(250));

        Subscription subscription = subscriptionRepository.findByInstitutionId(customerId).orElseThrow();
        assertEquals("STARTER", subscription.getPlanCode());
        assertEquals(250, subscription.getMaxStudentsOverride());

        // The override really is in force for that institution.
        mockMvc.perform(get("/api/v1/admin/institutions/" + customerId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentsLimit").value(250));
    }

    @Test
    @DisplayName("An operator clears an override again")
    void anOperatorClearsAnOverride() throws Exception {
        String token = operatorToken();

        mockMvc.perform(put("/api/v1/admin/institutions/{id}/subscription", customerId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"limits\":{\"maxStudents\":null}}"))
                .andExpect(status().isOk());

        Subscription subscription = subscriptionRepository.findByInstitutionId(customerId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertNull(subscription.getMaxStudentsOverride());
    }

    @Test
    @DisplayName("An operator changes a user's role and institution")
    void anOperatorManagesUsers() throws Exception {
        String token = operatorToken();

        // A customer account — not the operator itself, so the operator keeps
        // its access for the rest of the test.
        User customer = userRepository.findByUsername("test-college-admin")
                .orElseGet(() -> userRepository.save(User.builder()
                        .username("test-college-admin")
                        .password(passwordEncoder.encode("customer-password"))
                        .role(User.Role.ADMIN)
                        .fullName("Test college administrator")
                        .institutionId(customerId)
                        .build()));
        assertNotNull(customer.getId());

        mockMvc.perform(put("/api/v1/admin/users/{id}", customer.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"role\":\"EXAM_CELL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("EXAM_CELL"))
                .andExpect(jsonPath("$.institutionName").exists());

        // Put it back, so the next test starts from a known state.
        mockMvc.perform(put("/api/v1/admin/users/{id}", customer.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"));
    }

    @Test
    @DisplayName("An operator cannot demote themselves out of the operator view")
    void anOperatorCannotLoseAccessMidFlight() throws Exception {
        String token = operatorToken();
        User operator = userRepository.findByUsername("test-platform-admin").orElseThrow();

        // The access follows the account, so a self-demotion would lock the
        // operator out before they could undo it. It is refused.
        mockMvc.perform(put("/api/v1/admin/users/{id}", operator.getId())
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"role\":\"EXAM_CELL\"}"))
                .andExpect(status().is4xxClientError());

        // The operator keeps their access.
        mockMvc.perform(get("/api/v1/admin/institutions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("An unknown plan is refused")
    void anUnknownPlanIsRefused() throws Exception {
        String token = operatorToken();

        mockMvc.perform(put("/api/v1/admin/institutions/{id}/subscription", customerId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"planCode\":\"GOLD\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("Platform currency defaults to INR (₹) and is configurable by the platform administrator")
    void currencyDefaultsToInrAndCanBeConfigured() throws Exception {
        String token = operatorToken();

        mockMvc.perform(get("/api/v1/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].currencyCode").value("INR"))
                .andExpect(jsonPath("$[0].currencySymbol").value("₹"));

        mockMvc.perform(put("/api/v1/admin/payment-config")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"paymentMode\":\"MANUAL\",\"currencyCode\":\"USD\",\"currencySymbol\":\"$\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencyCode").value("USD"))
                .andExpect(jsonPath("$.currencySymbol").value("$"));

        mockMvc.perform(get("/api/v1/plans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].currencyCode").value("USD"))
                .andExpect(jsonPath("$[0].currencySymbol").value("$"));

        // Restore default INR (₹)
        mockMvc.perform(put("/api/v1/admin/payment-config")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"paymentMode\":\"MANUAL\",\"currencyCode\":\"INR\",\"currencySymbol\":\"₹\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currencyCode").value("INR"))
                .andExpect(jsonPath("$.currencySymbol").value("₹"));
    }

    @Test
    @DisplayName("Platform administrator manages global plan tiers and Free tier cannot be deactivated")
    void operatorManagesGlobalPlanCatalog() throws Exception {
        String token = operatorToken();

        // Anonymous callers cannot access admin plan catalog
        mockMvc.perform(get("/api/v1/admin/plans"))
                .andExpect(status().is4xxClientError());

        // Ensure customer is on FREE with no overrides
        mockMvc.perform(put("/api/v1/admin/institutions/{id}/subscription", customerId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"planCode\":\"FREE\",\"limits\":{}}"))
                .andExpect(status().isOk());

        // Deactivating FREE is refused
        mockMvc.perform(put("/api/v1/admin/plans/FREE")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {
                                  "code": "FREE",
                                  "name": "Free",
                                  "monthlyPrice": 0,
                                  "maxStudents": 50,
                                  "maxRooms": 3,
                                  "maxGenerationsPerMonth": 5,
                                  "storageLimitMb": 10,
                                  "maxFileSizeMb": 2,
                                  "seatsPerGeneration": 100,
                                  "active": false
                                }
                                """))
                .andExpect(status().is4xxClientError());

        // Modifying FREE limits immediately propagates to institutions on FREE
        mockMvc.perform(put("/api/v1/admin/plans/FREE")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {
                                  "code": "FREE",
                                  "name": "Free Starter",
                                  "monthlyPrice": 0,
                                  "maxStudents": 80,
                                  "maxRooms": 4,
                                  "maxGenerationsPerMonth": 8,
                                  "storageLimitMb": 15,
                                  "maxFileSizeMb": 2,
                                  "seatsPerGeneration": 120,
                                  "active": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.maxStudents").value(80));

        mockMvc.perform(get("/api/v1/admin/institutions/{id}", customerId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentsLimit").value(80))
                .andExpect(jsonPath("$.roomsLimit").value(4));

        // Create a new custom tier, verify in public catalog, then hide it
        mockMvc.perform(post("/api/v1/admin/plans")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {
                                  "code": "CAMPUS_PLUS",
                                  "name": "Campus Plus",
                                  "monthlyPrice": 1499.00,
                                  "maxStudents": 2500,
                                  "maxRooms": 50,
                                  "maxGenerationsPerMonth": 200,
                                  "storageLimitMb": 512,
                                  "maxFileSizeMb": 10,
                                  "seatsPerGeneration": 2500,
                                  "sortOrder": 25,
                                  "active": true
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("CAMPUS_PLUS"))
                .andExpect(jsonPath("$.active").value(true));

        mockMvc.perform(put("/api/v1/admin/plans/CAMPUS_PLUS")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {
                                  "code": "CAMPUS_PLUS",
                                  "name": "Campus Plus",
                                  "monthlyPrice": 1499.00,
                                  "maxStudents": 2500,
                                  "maxRooms": 50,
                                  "maxGenerationsPerMonth": 200,
                                  "storageLimitMb": 512,
                                  "maxFileSizeMb": 10,
                                  "seatsPerGeneration": 2500,
                                  "sortOrder": 25,
                                  "active": false
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));

        // Restore FREE baseline defaults for subsequent tests
        mockMvc.perform(put("/api/v1/admin/plans/FREE")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {
                                  "code": "FREE",
                                  "name": "Free",
                                  "monthlyPrice": 0.00,
                                  "maxStudents": 50,
                                  "maxRooms": 3,
                                  "maxGenerationsPerMonth": 5,
                                  "storageLimitMb": 10,
                                  "maxFileSizeMb": 2,
                                  "seatsPerGeneration": 100,
                                  "sortOrder": 10,
                                  "active": true
                                }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Temporary promotional overrides apply while active and automatically revert once expired")
    void promotionalOverridesExpireAutomatically() throws Exception {
        String token = operatorToken();
        java.time.Instant futureExpiry = java.time.Instant.now().plusSeconds(7L * 24 * 3600);
        java.time.Instant pastExpiry = java.time.Instant.now().minusSeconds(300);

        // Grant future promotional boost on FREE plan
        mockMvc.perform(put("/api/v1/admin/institutions/{id}/subscription", customerId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {
                                  "planCode": "FREE",
                                  "limits": { "maxStudents": 600, "maxRooms": 15 },
                                  "overrideExpiresAt": "%s",
                                  "overrideReason": "Semester Promo Boost"
                                }
                                """.formatted(futureExpiry.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentsLimit").value(600))
                .andExpect(jsonPath("$.roomsLimit").value(15))
                .andExpect(jsonPath("$.overrideActive").value(true))
                .andExpect(jsonPath("$.overrideExpired").value(false))
                .andExpect(jsonPath("$.overrideReason").value("Semester Promo Boost"));

        // Expire the promotional override -> institution automatically reverts to FREE limits (50 / 3)
        mockMvc.perform(put("/api/v1/admin/institutions/{id}/subscription", customerId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("""
                                {
                                  "planCode": "FREE",
                                  "limits": { "maxStudents": 600, "maxRooms": 15 },
                                  "overrideExpiresAt": "%s",
                                  "overrideReason": "Semester Promo Boost"
                                }
                                """.formatted(pastExpiry.toString())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentsLimit").value(50))
                .andExpect(jsonPath("$.roomsLimit").value(3))
                .andExpect(jsonPath("$.overrideActive").value(false))
                .andExpect(jsonPath("$.overrideExpired").value(true));

        // Clean up overrides
        mockMvc.perform(put("/api/v1/admin/institutions/{id}/subscription", customerId)
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"planCode\":\"FREE\",\"limits\":{}}"))
                .andExpect(status().isOk());
    }
}
