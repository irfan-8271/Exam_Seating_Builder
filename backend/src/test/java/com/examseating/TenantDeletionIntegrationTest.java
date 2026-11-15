package com.examseating;

import com.examseating.entity.ActivityLog;
import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.GenerationUsage;
import com.examseating.entity.Institution;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.SeatingRound;
import com.examseating.entity.Student;
import com.examseating.entity.StoredFile;
import com.examseating.entity.Subscription;
import com.examseating.entity.SubscriptionRequest;
import com.examseating.entity.User;
import com.examseating.repository.ActivityLogRepository;
import com.examseating.repository.AuthTokenRepository;
import com.examseating.repository.BenchRepository;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.GenerationUsageRepository;
import com.examseating.repository.InstitutionRepository;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.repository.SeatingRoundRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.repository.StoredFileRepository;
import com.examseating.repository.SubscriptionRepository;
import com.examseating.repository.SubscriptionRequestRepository;
import com.examseating.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Removing a college, or a single account, from the service.
 *
 * <p>Four properties matter here, and each of them is a way the operation
 * could go wrong if it were not deliberate:</p>
 *
 * <ol>
 *   <li><strong>Nothing goes without a confirmation.</strong> The preview
 *       reports what would go; the removal requires an explicit
 *       {@code confirm=true}.</li>
 *   <li><strong>The whole footprint goes.</strong> Not the college and its
 *       students — the college, its students, halls, benches, seat
 *       allocations, rounds, cohort rules, uploads, subscription, upgrade
 *       requests and ledger.</li>
 *   <li><strong>The audit trail survives.</strong> The activity log describes
 *       what happened; deleting the subject must not delete the record of
 *       it.</li>
 *   <li><strong>The service cannot lock itself out.</strong> Removing the
 *       platform institution, or the last platform administrator, is
 *       refused.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
class TenantDeletionIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private InstitutionRepository institutionRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ExamRoomRepository examRoomRepository;
    @Autowired private BenchRepository benchRepository;
    @Autowired private SeatingAllocationRepository allocationRepository;
    @Autowired private SeatingRoundRepository roundRepository;
    @Autowired private StoredFileRepository storedFileRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private SubscriptionRequestRepository subscriptionRequestRepository;
    @Autowired private GenerationUsageRepository generationUsageRepository;
    @Autowired private AuthTokenRepository authTokenRepository;
    @Autowired private ActivityLogRepository activityLogRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private Long collegeId;
    private Long operatorId;
    private Long teacherId;

    @BeforeEach
    void prepare() {
        // Start from a clean slate: earlier tests in this class may have
        // removed the college, and its data must be seeded afresh.
        clearTenantData();

        Institution college = institutionRepository.findBySlug("deletable-college")
                .orElseGet(() -> institutionRepository.save(Institution.builder()
                        .name("Deletable College")
                        .slug("deletable-college")
                        .status(Institution.Status.ACTIVE)
                        .build()));
        collegeId = college.getId();

        subscriptionRepository.findByInstitutionId(collegeId)
                .orElseGet(() -> subscriptionRepository.save(Subscription.builder()
                        .institutionId(collegeId)
                        .planCode("FREE")
                        .status(Subscription.Status.ACTIVE)
                        .build()));

        // The operator belongs to the platform institution, not to the college
        // under test — otherwise the operator would be deleting its own home.
        Institution platform = institutionRepository.findBySlug("platform")
                .orElseGet(() -> institutionRepository.save(Institution.builder()
                        .name("Platform")
                        .slug("platform")
                        .status(Institution.Status.ACTIVE)
                        .platform(true)
                        .build()));

        User operator = userRepository.findByUsername("delete-operator")
                .orElseGet(() -> User.builder()
                        .username("delete-operator")
                        .role(User.Role.PLATFORM_ADMIN)
                        .fullName("Deletion operator")
                        .build());
        operator.setPassword(passwordEncoder.encode("operator-password"));
        operator.setRole(User.Role.PLATFORM_ADMIN);
        operator.setStatus(User.Status.ACTIVE);
        operator.setInstitutionId(platform.getId());
        userRepository.save(operator);
        operatorId = operator.getId();

        User teacher = userRepository.findByUsername("delete-teacher")
                .orElseGet(() -> User.builder()
                        .username("delete-teacher")
                        .role(User.Role.ADMIN)
                        .fullName("Deletion Teacher")
                        .build());
        teacher.setPassword(passwordEncoder.encode("teacher-password"));
        teacher.setRole(User.Role.ADMIN);
        teacher.setStatus(User.Status.ACTIVE);
        teacher.setInstitutionId(collegeId);
        userRepository.save(teacher);
        teacherId = teacher.getId();

        seedTenantData();
    }

    /** Removes anything this test seeded, so each run starts from the same state. */
    private void clearTenantData() {
        Long id = institutionRepository.findBySlug("deletable-college")
                .map(Institution::getId).orElse(null);
        if (id == null) {
            return;
        }
        allocationRepository.deleteAllByInstitutionId(id);
        List<Long> roomIds = examRoomRepository.findAll().stream()
                .filter(r -> id.equals(r.getInstitutionId()))
                .map(ExamRoom::getId).toList();
        if (!roomIds.isEmpty()) {
            benchRepository.deleteAllByRoomIdIn(roomIds);
        }
        examRoomRepository.deleteAllByInstitutionId(id);
        studentRepository.deleteAllByInstitutionId(id);
        roundRepository.deleteAllByInstitutionId(id);
        storedFileRepository.deleteAllByInstitutionId(id);
        subscriptionRequestRepository.deleteAllByInstitutionId(id);
        generationUsageRepository.deleteAllByInstitutionId(id);
        authTokenRepository.deleteAllByInstitutionId(id);
        userRepository.deleteAllByInstitutionId(id);
    }

    /** Gives the college a full footprint: students, a hall, a seat, an upload, a request. */
    private void seedTenantData() {
        ExamRoom room = examRoomRepository.save(ExamRoom.builder()
                .institutionId(collegeId)
                .hallName("Deletion Hall")
                .rows(2)
                .columns(2)
                .benchType(ExamRoom.BenchType.SINGLE_SEATER)
                .build());
        Bench bench = benchRepository.save(Bench.builder()
                .room(room).rowIndex(1).colIndex(1).build());

        Student student = studentRepository.save(Student.builder()
                .institutionId(collegeId)
                .name("Deletion Student")
                .identityKey("DEL-1")
                .attributes(java.util.Map.of("candidateid", "DEL-1", "stream", "cse", "batch", "1"))
                .build());

        allocationRepository.save(SeatingAllocation.builder()
                .institutionId(collegeId)
                .student(student)
                .bench(bench)
                .slotNumber(1)
                .scopeKey("PAPER1")
                .build());

        roundRepository.save(SeatingRound.builder()
                .institutionId(collegeId)
                .scopeKey("PAPER1")
                .roundName("Deletion Round")
                .strictness("NORMAL")
                .horizontalGap(1)
                .groupMatchMode(com.examseating.engine.SeatingArrangementEngine.GroupMatchMode.ALL)
                .build());

        storedFileRepository.save(StoredFile.builder()
                .institutionId(collegeId)
                .originalName("roster.xlsx")
                .storedName("roster.xlsx")
                .contentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
                .sizeBytes(11)
                .uploadedAt(Instant.now())
                .content("hello world".getBytes(StandardCharsets.UTF_8))
                .build());

        subscriptionRequestRepository.save(SubscriptionRequest.builder()
                .institutionId(collegeId)
                .institutionName("Deletable College")
                .requestedByUserId(teacherId)
                .requestedByUsername("delete-teacher")
                .requestType(SubscriptionRequest.RequestType.PLAN_UPGRADE)
                .currentPlanCode("FREE")
                .requestedPlanCode("PRO")
                .status(SubscriptionRequest.Status.PENDING)
                .transactionReference("TX-DEL-1")
                .paymentAmount(java.math.BigDecimal.valueOf(79))
                .paymentDate(LocalDate())
                .build());

        generationUsageRepository.save(GenerationUsage.builder()
                .institutionId(collegeId)
                .scopeKey("PAPER1")
                .seats(1)
                .planCode("FREE")
                .createdAt(Instant.now())
                .build());
    }

    private static java.time.LocalDate LocalDate() {
        return java.time.LocalDate.now();
    }

    private String operatorToken() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"delete-operator\",\"password\":\"operator-password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    private String teacherToken() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"delete-teacher\",\"password\":\"teacher-password\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return body.replaceAll(".*\"token\":\"([^\"]+)\".*", "$1");
    }

    // ------------------------------------------------------------------
    //  The preview
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The preview counts every kind of record the college owns")
    void thePreviewCountsEveryKindOfRecord() throws Exception {
        mockMvc.perform(get("/api/v1/admin/deletions/institutions/{id}/preview", collegeId)
                        .header("Authorization", "Bearer " + operatorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetType").value("institution"))
                .andExpect(jsonPath("$.targetName").value("Deletable College"))
                .andExpect(jsonPath("$.canDelete").value(true))
                .andExpect(jsonPath("$.totalRows").isNumber())
                .andExpect(jsonPath("$.categories").isArray());
    }

    @Test
    @DisplayName("The preview removes nothing on its own")
    void thePreviewRemovesNothing() throws Exception {
        long studentsBefore = studentRepository.countByInstitutionId(collegeId);
        long filesBefore = storedFileRepository.findByInstitutionIdOrderByUploadedAtDesc(collegeId).size();

        mockMvc.perform(get("/api/v1/admin/deletions/institutions/{id}/preview", collegeId)
                        .header("Authorization", "Bearer " + operatorToken()))
                .andExpect(status().isOk());

        assertEquals(studentsBefore, studentRepository.countByInstitutionId(collegeId));
        assertEquals(filesBefore, storedFileRepository.findByInstitutionIdOrderByUploadedAtDesc(collegeId).size());
        assertTrue(institutionRepository.findById(collegeId).isPresent());
    }

    @Test
    @DisplayName("The preview of an account shows what it would take")
    void thePreviewOfAnAccountShowsWhatItWouldTake() throws Exception {
        mockMvc.perform(get("/api/v1/admin/deletions/users/{id}/preview", teacherId)
                        .header("Authorization", "Bearer " + operatorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetType").value("user"))
                .andExpect(jsonPath("$.targetName").value("delete-teacher"))
                .andExpect(jsonPath("$.canDelete").value(true));
    }

    @Test
    @DisplayName("The preview of a college that does not exist is a 404")
    void thePreviewOfAMissingCollegeIs404() throws Exception {
        mockMvc.perform(get("/api/v1/admin/deletions/institutions/{id}/preview", 999999)
                        .header("Authorization", "Bearer " + operatorToken()))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------------
    //  The confirmation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A removal without a confirmation changes nothing")
    void aRemovalWithoutAConfirmationChangesNothing() throws Exception {
        mockMvc.perform(post("/api/v1/admin/deletions/institutions")
                        .header("Authorization", "Bearer " + operatorToken())
                        .contentType("application/json")
                        .content("{\"institutionId\":" + collegeId + "}"))
                .andExpect(status().is4xxClientError());

        assertTrue(institutionRepository.findById(collegeId).isPresent());
        assertTrue(studentRepository.countByInstitutionId(collegeId) > 0);
    }

    @Test
    @DisplayName("A removal with confirm=false changes nothing")
    void aRemovalWithConfirmFalseChangesNothing() throws Exception {
        mockMvc.perform(post("/api/v1/admin/deletions/institutions")
                        .header("Authorization", "Bearer " + operatorToken())
                        .contentType("application/json")
                        .content("{\"institutionId\":" + collegeId + ",\"confirm\":false}"))
                .andExpect(status().is4xxClientError());

        assertTrue(institutionRepository.findById(collegeId).isPresent());
    }

    // ------------------------------------------------------------------
    //  Removing a college
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Removing a college takes its whole footprint with it")
    void removingACollegeTakesItsWholeFootprintWithIt() throws Exception {
        mockMvc.perform(post("/api/v1/admin/deletions/institutions")
                        .header("Authorization", "Bearer " + operatorToken())
                        .contentType("application/json")
                        .content("{\"institutionId\":" + collegeId + ",\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetType").value("institution"))
                .andExpect(jsonPath("$.deletedRows").isNumber())
                .andExpect(jsonPath("$.message").isNotEmpty());

        // The college itself.
        assertFalse(institutionRepository.findById(collegeId).isPresent());
        // Its students, halls, allocations, rounds, uploads, requests, ledger, accounts.
        assertEquals(0, studentRepository.countByInstitutionId(collegeId));
        assertEquals(0, examRoomRepository.countByInstitutionId(collegeId));
        assertEquals(0, allocationRepository.countByInstitutionId(collegeId));
        assertEquals(0, roundRepository.countByInstitutionId(collegeId));
        assertEquals(0, storedFileRepository.findByInstitutionIdOrderByUploadedAtDesc(collegeId).size());
        assertEquals(0, subscriptionRequestRepository.findByInstitutionIdOrderByCreatedAtDesc(collegeId).size());
        assertEquals(0, userRepository.countByInstitutionId(collegeId));
        assertFalse(subscriptionRepository.findByInstitutionId(collegeId).isPresent());
    }

    @Test
    @DisplayName("Removing a college releases the bytes of its uploaded files")
    void removingACollegeReleasesTheBytesOfItsUploadedFiles() throws Exception {
        long bytesBefore = storedFileRepository.sumSizeByInstitutionId(collegeId);
        assertTrue(bytesBefore > 0);

        mockMvc.perform(post("/api/v1/admin/deletions/institutions")
                        .header("Authorization", "Bearer " + operatorToken())
                        .contentType("application/json")
                        .content("{\"institutionId\":" + collegeId + ",\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.freedBytes").value(bytesBefore));

        assertEquals(0, storedFileRepository.sumSizeByInstitutionId(collegeId));
    }

    @Test
    @DisplayName("Removing a college keeps the activity log that describes it")
    void removingACollegeKeepsTheActivityLogThatDescribesIt() throws Exception {
        long logBefore = activityLogRepository.countByInstitutionId(collegeId);

        mockMvc.perform(post("/api/v1/admin/deletions/institutions")
                        .header("Authorization", "Bearer " + operatorToken())
                        .contentType("application/json")
                        .content("{\"institutionId\":" + collegeId + ",\"confirm\":true}"))
                .andExpect(status().isOk());

        // The log outlives its subject.
        assertTrue(activityLogRepository.countByInstitutionId(collegeId) >= logBefore);
    }

    @Test
    @DisplayName("The removal itself is written to the activity log")
    void theRemovalItselfIsWrittenToTheActivityLog() throws Exception {
        mockMvc.perform(post("/api/v1/admin/deletions/institutions")
                        .header("Authorization", "Bearer " + operatorToken())
                        .contentType("application/json")
                        .content("{\"institutionId\":" + collegeId + ",\"confirm\":true}"))
                .andExpect(status().isOk());

        List<ActivityLog> entries = activityLogRepository.findTop200ByOrderByOccurredAtDesc();
        assertTrue(entries.stream().anyMatch(e ->
                        e.getType() == ActivityLog.Type.INSTITUTION_DELETED
                                && e.getDetail() != null
                                && e.getDetail().contains("Deletable College")),
                "the removal must be recorded in the activity log");
    }

    @Test
    @DisplayName("A removed college's users can no longer sign in")
    void aRemovedCollegesUsersCanNoLongerSignIn() throws Exception {
        mockMvc.perform(post("/api/v1/admin/deletions/institutions")
                        .header("Authorization", "Bearer " + operatorToken())
                        .contentType("application/json")
                        .content("{\"institutionId\":" + collegeId + ",\"confirm\":true}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType("application/json")
                        .content("{\"username\":\"delete-teacher\",\"password\":\"teacher-password\"}"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    //  Removing one account
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Removing one account leaves the college's data alone")
    void removingOneAccountLeavesTheCollegesDataAlone() throws Exception {
        mockMvc.perform(post("/api/v1/admin/deletions/users")
                        .header("Authorization", "Bearer " + operatorToken())
                        .contentType("application/json")
                        .content("{\"userId\":" + teacherId + ",\"confirm\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetType").value("user"))
                .andExpect(jsonPath("$.targetName").value("delete-teacher"));

        assertFalse(userRepository.findById(teacherId).isPresent());
        // Everything else survives.
        assertTrue(studentRepository.countByInstitutionId(collegeId) > 0);
        assertTrue(examRoomRepository.countByInstitutionId(collegeId) > 0);
        assertTrue(institutionRepository.findById(collegeId).isPresent());
        assertTrue(userRepository.findById(operatorId).isPresent());
    }

    @Test
    @DisplayName("Removing one account ends that account's sessions")
    void removingOneAccountEndsThatAccountsSessions() throws Exception {
        // Sign in as the teacher so there is a session to end.
        teacherToken();
        assertTrue(authTokenRepository.countByUserId(teacherId) > 0);

        mockMvc.perform(post("/api/v1/admin/deletions/users")
                        .header("Authorization", "Bearer " + operatorToken())
                        .contentType("application/json")
                        .content("{\"userId\":" + teacherId + ",\"confirm\":true}"))
                .andExpect(status().isOk());

        assertEquals(0, authTokenRepository.countByUserId(teacherId));
    }

    @Test
    @DisplayName("An account removal without a confirmation changes nothing")
    void anAccountRemovalWithoutAConfirmationChangesNothing() throws Exception {
        mockMvc.perform(post("/api/v1/admin/deletions/users")
                        .header("Authorization", "Bearer " + operatorToken())
                        .contentType("application/json")
                        .content("{\"userId\":" + teacherId + "}"))
                .andExpect(status().is4xxClientError());

        assertTrue(userRepository.findById(teacherId).isPresent());
    }

    // ------------------------------------------------------------------
    //  The guards
    // ------------------------------------------------------------------

    @Test
    @DisplayName("The platform institution cannot be removed")
    void thePlatformInstitutionCannotBeRemoved() throws Exception {
        Institution platform = institutionRepository.findBySlug("platform")
                .orElseGet(() -> institutionRepository.save(Institution.builder()
                        .name("Platform")
                        .slug("platform")
                        .status(Institution.Status.ACTIVE)
                        .platform(true)
                        .build()));

        mockMvc.perform(get("/api/v1/admin/deletions/institutions/{id}/preview", platform.getId())
                        .header("Authorization", "Bearer " + operatorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canDelete").value(false))
                .andExpect(jsonPath("$.blockedReason").isNotEmpty());

        mockMvc.perform(post("/api/v1/admin/deletions/institutions")
                        .header("Authorization", "Bearer " + operatorToken())
                        .contentType("application/json")
                        .content("{\"institutionId\":" + platform.getId() + ",\"confirm\":true}"))
                .andExpect(status().isForbidden());

        assertTrue(institutionRepository.findById(platform.getId()).isPresent());
    }

    @Test
    @DisplayName("The last platform administrator cannot be removed")
    void theLastPlatformAdministratorCannotBeRemoved() throws Exception {
        long admins = userRepository.countByRole(User.Role.PLATFORM_ADMIN);
        // Make the operator the only platform administrator.
        userRepository.findAll().stream()
                .filter(u -> u.getRole() == User.Role.PLATFORM_ADMIN && !u.getId().equals(operatorId))
                .forEach(u -> {
                    u.setRole(User.Role.ADMIN);
                    userRepository.save(u);
                });

        mockMvc.perform(get("/api/v1/admin/deletions/users/{id}/preview", operatorId)
                        .header("Authorization", "Bearer " + operatorToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.canDelete").value(false))
                .andExpect(jsonPath("$.blockedReason").isNotEmpty());

        mockMvc.perform(post("/api/v1/admin/deletions/users")
                        .header("Authorization", "Bearer " + operatorToken())
                        .contentType("application/json")
                        .content("{\"userId\":" + operatorId + ",\"confirm\":true}"))
                .andExpect(status().isForbidden());

        assertTrue(userRepository.findById(operatorId).isPresent());
        assertTrue(admins >= 1);
    }

    @Test
    @DisplayName("An ordinary account cannot use the deletion endpoints")
    void anOrdinaryAccountCannotUseTheDeletionEndpoints() throws Exception {
        String token = teacherToken();

        mockMvc.perform(get("/api/v1/admin/deletions/institutions/{id}/preview", collegeId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/deletions/institutions")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"institutionId\":" + collegeId + ",\"confirm\":true}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/deletions/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content("{\"userId\":" + teacherId + ",\"confirm\":true}"))
                .andExpect(status().isForbidden());

        // Nothing was removed.
        assertTrue(institutionRepository.findById(collegeId).isPresent());
        assertTrue(userRepository.findById(teacherId).isPresent());
    }

    @Test
    @DisplayName("An unauthenticated caller cannot use the deletion endpoints")
    void anUnauthenticatedCallerCannotUseTheDeletionEndpoints() throws Exception {
        mockMvc.perform(get("/api/v1/admin/deletions/institutions/{id}/preview", collegeId))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/admin/deletions/institutions")
                        .contentType("application/json")
                        .content("{\"institutionId\":" + collegeId + ",\"confirm\":true}"))
                .andExpect(status().isForbidden());

        assertTrue(institutionRepository.findById(collegeId).isPresent());
    }
}
