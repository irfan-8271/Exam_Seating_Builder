package com.examseating;

import com.examseating.entity.ExamRoom;
import com.examseating.entity.Institution;
import com.examseating.entity.User;
import com.examseating.repository.InstitutionRepository;
import com.examseating.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end verification of:
 * <ol>
 *   <li>Platform Admin authentication, authorization boundaries, and scrubbed public status.</li>
 *   <li>Backend blocking of direct client subscription self-upgrades.</li>
 *   <li>Manual payment configuration (UPI + QR image + screenshot policy) and request-based
 *       PENDING -> APPROVED / REJECTED lifecycle (never auto-activating on submission).</li>
 *   <li>Canonical {@code EXTREME} difficulty level (with {@code HARD}/{@code RIGID} JSON aliases)
 *       and empty-seat / cohort-shortage feasibility diagnostics.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
public class SubscriptionWorkflowAndAdvisoryIntegrationTest {

    // Minimal valid 1x1 PNG image bytes
    private static final byte[] TINY_PNG = new byte[]{
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A,
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89,
            0x00, 0x00, 0x00, 0x0A, 0x49, 0x44, 0x41, 0x54,
            0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00, 0x05, 0x00, 0x01,
            0x0D, 0x0A, 0x2D, (byte) 0xB4,
            0x00, 0x00, 0x00, 0x00, 0x49, 0x45, 0x4E, 0x44,
            (byte) 0xAE, 0x42, 0x60, (byte) 0x82
    };

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private InstitutionRepository institutionRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void ensurePlatformOperatorExists() {
        Institution platform = institutionRepository.findBySlug("platform")
                .orElseGet(() -> institutionRepository.save(Institution.builder()
                        .name("Platform")
                        .slug("platform")
                        .status(Institution.Status.ACTIVE)
                        .platform(true)
                        .build()));

        User op = userRepository.findByUsername("saas-platform-admin")
                .orElseGet(() -> User.builder()
                        .username("saas-platform-admin")
                        .role(User.Role.PLATFORM_ADMIN)
                        .status(User.Status.ACTIVE)
                        .fullName("Platform Operator")
                        .email("ops@examseating.example")
                        .institutionId(platform.getId())
                        .build());
        op.setRole(User.Role.PLATFORM_ADMIN);
        op.setStatus(User.Status.ACTIVE);
        op.setInstitutionId(platform.getId());
        op.setPassword(passwordEncoder.encode("PlatformSecret#2026"));
        userRepository.save(op);
    }

    private String platformAdminToken() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/platform/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"saas-platform-admin\",\"password\":\"PlatformSecret#2026\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.role").value("PLATFORM_ADMIN"))
                .andReturn().getResponse().getContentAsString();
        return extractString(body, "token");
    }

    private String registerCollegeAdmin(String prefix) throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String username = prefix + "_" + suffix;
        String payload = """
                {
                  "username": "%s",
                  "password": "CollegeAdmin#2026",
                  "fullName": "Dean %s",
                  "email": "%s@college.edu",
                  "institutionName": "College %s"
                }
                """.formatted(username, suffix, username, suffix);
        String body = mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return extractString(body, "token");
    }

    @Test
    @DisplayName("Direct client self-upgrades are blocked (403) and normal clients cannot call admin endpoints")
    void clientCannotSelfUpgradeOrAccessAdminApis() throws Exception {
        String collegeToken = registerCollegeAdmin("sec_college");

        // 1. Direct plan change is strictly forbidden on backend
        mockMvc.perform(put("/api/v1/subscriptions/current")
                        .header("Authorization", "Bearer " + collegeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"planCode\":\"PRO\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message", containsString("Direct subscription changes are disabled")));

        // Verify plan remained FREE
        mockMvc.perform(get("/api/v1/subscriptions/current")
                        .header("Authorization", "Bearer " + collegeToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value("FREE"));

        // 2. Normal college ADMIN cannot use platform login or platform admin endpoints
        mockMvc.perform(get("/api/v1/admin/subscription-requests")
                        .header("Authorization", "Bearer " + collegeToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/admin/payment-config")
                        .header("Authorization", "Bearer " + collegeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"upiId\":\"hacker@upi\"}"))
                .andExpect(status().isForbidden());

        // 3. Anonymous callers cannot read payment config
        mockMvc.perform(get("/api/v1/payment-config"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Full manual payment verification & subscription upgrade/rejection workflow")
    void manualPaymentVerificationAndApprovalLifecycle() throws Exception {
        String adminToken = platformAdminToken();
        String collegeAToken = registerCollegeAdmin("college_a");
        String collegeBToken = registerCollegeAdmin("college_b");

        // 1. Platform Admin configures UPI ID, instructions, requireScreenshot=true, and uploads QR image
        mockMvc.perform(put("/api/v1/admin/payment-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "paymentMode": "MANUAL",
                                  "upiId": "admissions.billing@okaxis",
                                  "payeeName": "ExamSeating Cloud Pvt Ltd",
                                  "instructions": "Pay exact plan fee via UPI and upload screenshot.",
                                  "additionalInfo": "NEFT/IMPS also accepted.",
                                  "requireScreenshot": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upiId").value("admissions.billing@okaxis"))
                .andExpect(jsonPath("$.requireScreenshot").value(true));

        MockMultipartFile qrFile = new MockMultipartFile(
                "file", "upi-qr.png", "image/png", TINY_PNG);
        mockMvc.perform(multipart("/api/v1/admin/payment-config/qr")
                        .file(qrFile)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.hasQrImage").value(true))
                .andExpect(jsonPath("$.qrFilename").value("upi-qr.png"));

        // 2. Signed-in college admin fetches payment instructions & QR bytes
        mockMvc.perform(get("/api/v1/payment-config")
                        .header("Authorization", "Bearer " + collegeAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.upiId").value("admissions.billing@okaxis"))
                .andExpect(jsonPath("$.hasQrImage").value(true))
                .andExpect(jsonPath("$.requireScreenshot").value(true));

        byte[] downloadedQr = mockMvc.perform(get("/api/v1/payment-config/qr")
                        .header("Authorization", "Bearer " + collegeAToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(TINY_PNG, downloadedQr);

        // 3. Because requireScreenshot=true, submitting without screenshot is rejected
        String today = LocalDate.now().toString();
        mockMvc.perform(post("/api/v1/subscription-requests")
                        .header("Authorization", "Bearer " + collegeAToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "requestedPlanCode": "STARTER",
                                  "transactionReference": "UTR-NO-SHOT-001",
                                  "paymentAmount": 19.00,
                                  "paymentDate": "%s"
                                }
                                """.formatted(today)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("screenshot is required")));

        // 4. College A submits valid multipart upgrade + custom limit increase request
        String uniqueUtr = "UTR-" + UUID.randomUUID().toString().substring(0, 10).toUpperCase();
        MockMultipartFile proof = new MockMultipartFile(
                "screenshot", "receipt.png", "image/png", TINY_PNG);
        String createBody = mockMvc.perform(multipart("/api/v1/subscription-requests")
                        .file(proof)
                        .param("requestedPlanCode", "STARTER")
                        .param("requestedMaxStudents", "750")
                        .param("clientNotes", "Mid-semester exam intake")
                        .param("paymentMethod", "MANUAL_UPI")
                        .param("transactionReference", uniqueUtr)
                        .param("paymentAmount", "19.00")
                        .param("paymentDate", today)
                        .header("Authorization", "Bearer " + collegeAToken))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requestType").value("PLAN_AND_LIMITS"))
                .andExpect(jsonPath("$.hasScreenshot").value(true))
                .andExpect(jsonPath("$.confirmationMessage", containsString("2–3 working days")))
                .andReturn().getResponse().getContentAsString();
        long requestAId = extractLong(createBody, "id");

        // CRITICAL INVARIANT: Submitting payment proof MUST NOT auto-activate plan or limits!
        mockMvc.perform(get("/api/v1/subscriptions/current")
                        .header("Authorization", "Bearer " + collegeAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value("FREE"));
        mockMvc.perform(get("/api/v1/usage")
                        .header("Authorization", "Bearer " + collegeAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentsLimit").value(50));

        // Duplicate UTR submission is rejected
        mockMvc.perform(multipart("/api/v1/subscription-requests")
                        .file(proof)
                        .param("requestedPlanCode", "PRO")
                        .param("transactionReference", uniqueUtr)
                        .param("paymentAmount", "79.00")
                        .param("paymentDate", today)
                        .header("Authorization", "Bearer " + collegeAToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message", containsString("already been submitted")));

        // Cross-tenant screenshot isolation: College B cannot view College A's payment proof
        mockMvc.perform(get("/api/v1/subscription-requests/{id}/screenshot", requestAId)
                        .header("Authorization", "Bearer " + collegeBToken))
                .andExpect(status().isForbidden());

        // 5. Platform Admin sees pending request in queue, views screenshot, and approves
        mockMvc.perform(get("/api/v1/admin/usage")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pendingRequests", greaterThan(0)));

        byte[] adminViewedProof = mockMvc.perform(
                        get("/api/v1/admin/subscription-requests/{id}/screenshot", requestAId)
                                .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertArrayEquals(TINY_PNG, adminViewedProof);

        mockMvc.perform(post("/api/v1/admin/subscription-requests/{id}/approve", requestAId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adminNotes\":\"Verified UTR against axis bank statement\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.reviewedByUsername").value("saas-platform-admin"));

        // Now — and ONLY now — College A's plan is STARTER and studentsLimit is 750!
        mockMvc.perform(get("/api/v1/subscriptions/current")
                        .header("Authorization", "Bearer " + collegeAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value("STARTER"));
        mockMvc.perform(get("/api/v1/usage")
                        .header("Authorization", "Bearer " + collegeAToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.studentsLimit").value(750))
                .andExpect(jsonPath("$.roomsLimit").value(20));

        // 6. Rejection path: College B submits a request and Admin rejects with reason
        String utrB = "UTR-B-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        String reqBBody = mockMvc.perform(multipart("/api/v1/subscription-requests")
                        .file(proof)
                        .param("requestedPlanCode", "PRO")
                        .param("transactionReference", utrB)
                        .param("paymentAmount", "79.00")
                        .param("paymentDate", today)
                        .header("Authorization", "Bearer " + collegeBToken))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        long requestBId = extractLong(reqBBody, "id");

        mockMvc.perform(post("/api/v1/admin/subscription-requests/{id}/reject", requestBId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"UTR number could not be matched to bank credit\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("UTR number could not be matched to bank credit"));

        // College B remains on FREE
        mockMvc.perform(get("/api/v1/subscriptions/current")
                        .header("Authorization", "Bearer " + collegeBToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.planCode").value("FREE"));

        // Reset requireScreenshot=false so unrelated tests are unaffected
        mockMvc.perform(put("/api/v1/admin/payment-config")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requireScreenshot\":false}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Seating engine calculates mandatory empty buffer seats vs impossible capacity shortage without relaxing rules")
    void emptySeatCalculationAndImpossibilityDistinction() throws Exception {
        String collegeToken = registerCollegeAdmin("seating_college");

        // Add 10 Grade-10A students and only 2 Grade-10B students (12 students total; cohort shortage!)
        for (int i = 1; i <= 10; i++) {
            addStudent(collegeToken, "10A-%02d".formatted(i), "Student A" + i, "10-A");
        }
        for (int i = 1; i <= 2; i++) {
            addStudent(collegeToken, "10B-%02d".formatted(i), "Student B" + i, "10-B");
        }

        // Hall 1: 3x3 DUAL_SEATER = 9 benches = 18 seats (raw seats 18 >= 12 students, BUT max 9 of cohort 10-A!)
        long tightHallId = addHall(collegeToken, "Tight Dual Hall", 3, 3, ExamRoom.BenchType.DUAL_SEATER);

        // 1) Analyze in Tight Dual Hall (18 seats): must report IMPOSSIBLE under separation rules,
        //    requiring 8 empty buffer seats (20 total positions -> 2 additional seats / 1 bench needed)!
        String impossibleReq = """
                {
                  "strictness": "NORMAL",
                  "groupAttributes": ["classsection"],
                  "roomIds": [%d]
                }
                """.formatted(tightHallId);

        mockMvc.perform(post("/api/v1/seating-plans/analyze")
                        .header("Authorization", "Bearer " + collegeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(impossibleReq))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(false))
                .andExpect(jsonPath("$.status").value("INSUFFICIENT_SEATS_FOR_SEPARATION"))
                .andExpect(jsonPath("$.totalStudents").value(12))
                .andExpect(jsonPath("$.availableSeats").value(18))
                .andExpect(jsonPath("$.emptyBufferSeatsRequired").value(8))
                .andExpect(jsonPath("$.minTotalSeatsRequired").value(20))
                .andExpect(jsonPath("$.additionalSeatsRequired").value(2))
                .andExpect(jsonPath("$.additionalBenchesRequired").value(1))
                .andExpect(jsonPath("$.suggestions", hasItem(containsString("2 more seating position"))));

        // Attempting to generate in Tight Dual Hall MUST fail with 422 (never silently relaxing restrictions!)
        mockMvc.perform(post("/api/v1/seating-plans")
                        .header("Authorization", "Bearer " + collegeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(impossibleReq))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("SEATING_ERROR"))
                .andExpect(jsonPath("$.details").isArray());

        // 2) Add Hall 2: 5x3 DUAL_SEATER = 15 benches = 30 seats -> valid WITH 8 mandatory empty buffer seats!
        long spaciousHallId = addHall(collegeToken, "Spacious Dual Hall", 5, 3, ExamRoom.BenchType.DUAL_SEATER);
        String feasibleWithEmptyReq = """
                {
                  "strictness": "NORMAL",
                  "groupAttributes": ["classsection"],
                  "roomIds": [%d]
                }
                """.formatted(spaciousHallId);

        mockMvc.perform(post("/api/v1/seating-plans/analyze")
                        .header("Authorization", "Bearer " + collegeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feasibleWithEmptyReq))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(true))
                .andExpect(jsonPath("$.requiresEmptySeats").value(true))
                .andExpect(jsonPath("$.status").value("FEASIBLE_WITH_EMPTY_SEATS"))
                .andExpect(jsonPath("$.emptyBufferSeatsRequired").value(8))
                .andExpect(jsonPath("$.minTotalSeatsRequired").value(20))
                .andExpect(jsonPath("$.additionalSeatsRequired").value(0))
                .andExpect(jsonPath("$.message", containsString("8 empty seating position")));

        // Generating in Spacious Dual Hall succeeds with all 12 students seated & advisory attached!
        mockMvc.perform(post("/api/v1/seating-plans")
                        .header("Authorization", "Bearer " + collegeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(feasibleWithEmptyReq))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.solved").value(true))
                .andExpect(jsonPath("$.seated").value(12))
                .andExpect(jsonPath("$.advisory.requiresEmptySeats").value(true))
                .andExpect(jsonPath("$.advisory.emptyBufferSeatsRequired").value(8));

        // 3) Verify EXTREME and RIGID alias on single-seater hall
        long singleHallId = addHall(collegeToken, "Extreme Single Hall", 5, 7, ExamRoom.BenchType.SINGLE_SEATER);
        String extremeBody = mockMvc.perform(post("/api/v1/seating-plans/analyze")
                        .header("Authorization", "Bearer " + collegeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "strictness": "RIGID",
                                  "groupAttributes": ["classsection"],
                                  "roomIds": [%d]
                                }
                                """.formatted(singleHallId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.feasible").value(true))
                .andExpect(jsonPath("$.requiresEmptySeats").value(true))
                .andReturn().getResponse().getContentAsString();
        assertTrue(extremeBody.contains("FEASIBLE_WITH_EMPTY_SEATS"));

        mockMvc.perform(post("/api/v1/seating-plans")
                        .header("Authorization", "Bearer " + collegeToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "strictness": "EXTREME",
                                  "groupAttributes": ["classsection"],
                                  "roomIds": [%d]
                                }
                                """.formatted(singleHallId)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.solved").value(true))
                .andExpect(jsonPath("$.seated").value(12));
    }

    private void addStudent(String token, String id, String name, String classSection) throws Exception {
        mockMvc.perform(post("/api/v1/students")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "name": "%s",
                                  "identityKey": "%s",
                                  "attributes": {
                                    "rollno": "%s",
                                    "classsection": "%s"
                                  }
                                }
                                """.formatted(name, id, id, classSection)))
                .andExpect(status().isCreated());
    }

    private long addHall(String token, String name, int rows, int cols, ExamRoom.BenchType type) throws Exception {
        String body = mockMvc.perform(post("/api/v1/rooms")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "hallName": "%s",
                                  "rows": %d,
                                  "columns": %d,
                                  "benchType": "%s"
                                }
                                """.formatted(name, rows, cols, type.name())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return extractLong(body, "id");
    }

    private static String extractString(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*\"([^\"]+)\"").matcher(json);
        if (!m.find()) {
            throw new AssertionError("Field '" + field + "' not found in: " + json);
        }
        return m.group(1);
    }

    private static long extractLong(String json, String field) {
        Matcher m = Pattern.compile("\"" + Pattern.quote(field) + "\"\\s*:\\s*(\\d+)").matcher(json);
        if (!m.find()) {
            throw new AssertionError("Numeric field '" + field + "' not found in: " + json);
        }
        return Long.parseLong(m.group(1));
    }
}
