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
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
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
    @DisplayName("The status endpoint tells whether an operator account exists")
    void theStatusEndpointIsPublic() throws Exception {
        mockMvc.perform(get("/api/v1/admin/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").exists())
                .andExpect(jsonPath("$.institutions").exists());
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
}
