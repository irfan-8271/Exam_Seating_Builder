package com.examseating;

import com.examseating.entity.ExamRoom;
import com.examseating.entity.Institution;
import com.examseating.entity.User;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.InstitutionRepository;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Two institutions share one database — and neither may collide with the
 * other.
 *
 * <p>The interesting case is a name: two institutions may both call a hall
 * "Main Hall A". The uniqueness of a hall name belongs to the institution, not
 * to the service.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
public class TenantIsolationIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InstitutionRepository institutionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ExamRoomRepository roomRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private Long firstInstitutionId;
    private Long secondInstitutionId;

    @BeforeEach
    void prepare() {
        firstInstitutionId = institution("tenant-test-alpha", "Tenant Test Alpha");
        secondInstitutionId = institution("tenant-test-beta", "Tenant Test Beta");
    }

    private Long institution(String slug, String name) {
        Institution institution = institutionRepository.findBySlug(slug)
                .orElseGet(() -> institutionRepository.save(Institution.builder()
                        .name(name)
                        .slug(slug)
                        .status(Institution.Status.ACTIVE)
                        .build()));
        String username = slug + "-admin";
        userRepository.findByUsername(username)
                .orElseGet(() -> userRepository.save(User.builder()
                        .username(username)
                        .password(passwordEncoder.encode("tenant-test-password"))
                        .role(User.Role.ADMIN)
                        .fullName(name + " administrator")
                        .institutionId(institution.getId())
                        .build()));
        return institution.getId();
    }

    private String signIn(String slug) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"" + slug + "-admin\",\"password\":\"tenant-test-password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    @Test
    @DisplayName("Two institutions may each have a hall with the same name")
    void twoInstitutionsMayUseTheSameHallName() throws Exception {
        String firstToken = signIn("tenant-test-alpha");
        String secondToken = signIn("tenant-test-beta");

        mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType("application/json")
                        .content("{\"hallName\":\"Main Hall A\",\"rows\":2,\"columns\":2,\"benchType\":\"SINGLE_SEATER\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + secondToken)
                        .contentType("application/json")
                        .content("{\"hallName\":\"Main Hall A\",\"rows\":2,\"columns\":2,\"benchType\":\"SINGLE_SEATER\"}"))
                .andExpect(status().isCreated());

        // Both halls exist, and each belongs to its own institution.
        assertEquals(2, roomRepository.findByHallName("Main Hall A").size());
        assertTrue(roomRepository.findByInstitutionIdAndHallName(firstInstitutionId, "Main Hall A").isPresent(),
                "the first institution has its own Main Hall A");
        assertTrue(roomRepository.findByInstitutionIdAndHallName(secondInstitutionId, "Main Hall A").isPresent(),
                "the second institution has its own Main Hall A");
    }

    @Test
    @DisplayName("One institution may not have the same hall name twice")
    void oneInstitutionMayNotRepeatAHallName() throws Exception {
        String firstToken = signIn("tenant-test-alpha");

        mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType("application/json")
                        .content("{\"hallName\":\"Lecture Block\",\"rows\":2,\"columns\":2,\"benchType\":\"SINGLE_SEATER\"}"))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType("application/json")
                        .content("{\"hallName\":\"Lecture Block\",\"rows\":3,\"columns\":3,\"benchType\":\"SINGLE_SEATER\"}"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("An institution sees only its own halls")
    void anInstitutionSeesOnlyItsOwnHalls() throws Exception {
        String firstToken = signIn("tenant-test-alpha");
        mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType("application/json")
                        .content("{\"hallName\":\"Alpha Annex\",\"rows\":2,\"columns\":2,\"benchType\":\"SINGLE_SEATER\"}"))
                .andExpect(status().isCreated());

        String secondToken = signIn("tenant-test-beta");
        String body = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get("/api/v1/rooms")
                        .header("Authorization", "Bearer " + secondToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertTrue(!body.contains("Alpha Annex"),
                "the second institution must not see the first one's halls");
    }
}
