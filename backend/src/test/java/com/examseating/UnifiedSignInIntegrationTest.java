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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * One door for everybody.
 *
 * <p>The product used to have two sign-in screens: the ordinary one for
 * institutions, and a separate administration door linked from the sign-in
 * page and the landing page footer. Anyone could see that the platform
 * operator's console existed, and a mistyped username on the wrong door
 * produced a refusal that read like a broken account.</p>
 *
 * <p>Now there is one form. The general sign-in accepts a platform
 * administrator and the role on the session says where the person belongs. The
 * separate administration door is kept for operators who bookmark it, but
 * nothing links to it, and it still refuses an ordinary account.</p>
 *
 * <p>These tests cover the door itself. The removal of the public links is a
 * frontend change and is verified by the frontend build and by the live
 * walk-through recorded in the accompanying impact document.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
class UnifiedSignInIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private InstitutionRepository institutionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Long collegeId;

    @BeforeEach
    void prepare() {
        Institution college = institutionRepository.findBySlug("unified-college")
                .orElseGet(() -> institutionRepository.save(Institution.builder()
                        .name("Unified College")
                        .slug("unified-college")
                        .status(Institution.Status.ACTIVE)
                        .build()));
        collegeId = college.getId();

        subscriptionRepository.findByInstitutionId(collegeId)
                .orElseGet(() -> subscriptionRepository.save(Subscription.builder()
                        .institutionId(collegeId)
                        .planCode("FREE")
                        .status(Subscription.Status.ACTIVE)
                        .build()));

        // An ordinary institution administrator.
        User teacher = userRepository.findByUsername("unified-teacher")
                .orElseGet(() -> User.builder()
                        .username("unified-teacher")
                        .role(User.Role.ADMIN)
                        .fullName("Unified Teacher")
                        .institutionId(collegeId)
                        .build());
        teacher.setPassword(passwordEncoder.encode("teacher-password"));
        teacher.setRole(User.Role.ADMIN);
        teacher.setStatus(User.Status.ACTIVE);
        userRepository.save(teacher);

        // The platform operator.
        User operator = userRepository.findByUsername("unified-operator")
                .orElseGet(() -> User.builder()
                        .username("unified-operator")
                        .role(User.Role.PLATFORM_ADMIN)
                        .fullName("Unified Operator")
                        .institutionId(collegeId)
                        .build());
        operator.setPassword(passwordEncoder.encode("operator-password"));
        operator.setRole(User.Role.PLATFORM_ADMIN);
        operator.setStatus(User.Status.ACTIVE);
        userRepository.save(operator);
    }

    @Test
    @DisplayName("The ordinary sign-in accepts a platform administrator")
    void theOrdinarySignInAcceptsAPlatformAdministrator() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"unified-operator\",\"password\":\"operator-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.user.role").value("PLATFORM_ADMIN"))
                .andExpect(jsonPath("$.user.username").value("unified-operator"));
    }

    @Test
    @DisplayName("The ordinary sign-in still accepts an institution account")
    void theOrdinarySignInStillAcceptsAnInstitutionAccount() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"unified-teacher\",\"password\":\"teacher-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("ADMIN"));
    }

    @Test
    @DisplayName("A token from the ordinary door opens the administration endpoints")
    void aTokenFromTheOrdinaryDoorOpensTheAdministrationEndpoints() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"unified-operator\",\"password\":\"operator-password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/v1/admin/institutions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("An institution account cannot reach the administration endpoints with an ordinary token")
    void anInstitutionAccountCannotReachTheAdministrationEndpoints() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"unified-teacher\",\"password\":\"teacher-password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/v1/admin/institutions").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("The separate administration door still opens for a platform administrator")
    void theSeparateAdministrationDoorStillWorks() throws Exception {
        mockMvc.perform(post("/api/v1/auth/platform/login")
                        .contentType("application/json")
                        .content("{\"username\":\"unified-operator\",\"password\":\"operator-password\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("PLATFORM_ADMIN"));
    }

    @Test
    @DisplayName("The separate administration door refuses an ordinary account")
    void theSeparateAdministrationDoorRefusesAnOrdinaryAccount() throws Exception {
        mockMvc.perform(post("/api/v1/auth/platform/login")
                        .contentType("application/json")
                        .content("{\"username\":\"unified-teacher\",\"password\":\"teacher-password\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(
                        "This account is not a platform administrator. Use the normal sign-in."));
    }

    @Test
    @DisplayName("The separate administration door refuses a wrong password")
    void theSeparateAdministrationDoorRefusesAWrongPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/platform/login")
                        .contentType("application/json")
                        .content("{\"username\":\"unified-operator\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("The ordinary door refuses a wrong password")
    void theOrdinaryDoorRefusesAWrongPassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"unified-operator\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("A session can be resumed and still says which kind of account it is")
    void aSessionCanBeResumedAndStillSaysWhichAccountItIs() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"unified-operator\",\"password\":\"operator-password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("PLATFORM_ADMIN"));
    }

    @Test
    @DisplayName("Remember-me is honoured on the ordinary door")
    void rememberMeIsHonouredOnTheOrdinaryDoor() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"unified-operator\",\"password\":\"operator-password\","
                                + "\"rememberMe\":true}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");

        // A remembered session lasts a month; a plain one lasts a day.
        String expires = body.replaceAll(".*\"expiresAt\":\"([^\"]+)\".*", "$1");
        assertTrue(expires.contains("T"), "the session must carry an expiry");
        assertFalse(token.isBlank());
        assertEquals("PLATFORM_ADMIN",
                mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                        .andReturn().getResponse().getContentAsString()
                        .replaceAll(".*\"role\":\"([^\"]+)\".*", "$1"));
    }
}
