package com.examseating;

import com.examseating.dto.ArrangeRequest;
import com.examseating.dto.MoveAllocationRequest;
import com.examseating.dto.SeatingResponse;
import com.examseating.engine.SeatingArrangementEngine.GroupMatchMode;
import com.examseating.engine.SeatingArrangementEngine.StudentAttribute;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.SeatingRound;
import com.examseating.entity.Student;
import com.examseating.entity.Subscription;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.exception.SeatingConflictException;
import com.examseating.repository.BenchRepository;
import com.examseating.repository.CohortRuleRepository;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.repository.SeatingRoundRepository;
import com.examseating.repository.StudentIdentitySchemeRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.repository.SubscriptionRepository;
import com.examseating.service.SeatingService;
import com.examseating.tenancy.TenantSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for manual seat moves ({@code PATCH /api/v1/seating-plans/current/allocations/{id}})
 * and persisted dynamic {@link SeatingRound} metadata.
 */
@SpringBootTest
@AutoConfigureMockMvc
public class SeatingPlanMoveIntegrationTest {

    @Autowired private SeatingService seatingService;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ExamRoomRepository examRoomRepository;
    @Autowired private BenchRepository benchRepository;
    @Autowired private SeatingAllocationRepository allocationRepository;
    @Autowired private SeatingRoundRepository roundRepository;
    @Autowired private StudentIdentitySchemeRepository schemeRepository;
    @Autowired private CohortRuleRepository cohortRuleRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private TenantSupport tenantSupport;
    @Autowired private MockMvc mockMvc;

    @BeforeEach
    void cleanDatabase() {
        allocationRepository.deleteAllInBatch();
        roundRepository.deleteAllInBatch();
        benchRepository.deleteAllInBatch();
        examRoomRepository.deleteAllInBatch();
        studentRepository.deleteAllInBatch();
        cohortRuleRepository.deleteAllInBatch();
        schemeRepository.deleteAllInBatch();

        Long defaultTenantId = tenantSupport.currentId();
        Subscription sub = subscriptionRepository.findByInstitutionId(defaultTenantId)
                .orElseGet(() -> Subscription.builder().institutionId(defaultTenantId).build());
        sub.setPlanCode("ENTERPRISE");
        sub.setStatus(Subscription.Status.ACTIVE);
        sub.setPeriodStart(Instant.now().minus(1, ChronoUnit.DAYS));
        sub.setPeriodEnd(Instant.now().plus(30, ChronoUnit.DAYS));
        subscriptionRepository.save(sub);
    }

    private ExamRoom createHall(String name, int rows, int columns) {
        return examRoomRepository.save(ExamRoom.builder()
                .hallName(name)
                .rows(rows)
                .columns(columns)
                .benchType(ExamRoom.BenchType.SINGLE_SEATER)
                .build());
    }

    private void createStudents(String paperCode, String... specs) {
        List<Student> students = new ArrayList<>();
        for (String spec : specs) {
            String[] parts = spec.split(":"); // id:stream:batch
            Map<String, String> attrs = new LinkedHashMap<>();
            attrs.put("candidateid", parts[0]);
            attrs.put("stream", parts[1]);
            attrs.put("batch", parts[2]);
            if (paperCode != null) {
                attrs.put("papercode", paperCode);
            }
            students.add(Student.builder()
                    .name("S" + parts[0])
                    .identityKey(parts[0])
                    .attributes(attrs)
                    .build());
        }
        studentRepository.saveAll(students);
    }

    private SeatingResponse arrange(Integer dH, Integer dV, Integer dD,
                                    String scopeValue, List<Long> roomIds) {
        ArrangeRequest request = new ArrangeRequest(
                null,
                dH, dV, dD,
                List.of(StudentAttribute.of("stream"), StudentAttribute.of("batch")),
                GroupMatchMode.ALL,
                scopeValue != null ? "papercode" : null,
                scopeValue,
                roomIds,
                true);
        return seatingService.arrange(request);
    }

    private SeatingAllocation allocationOf(String identity) {
        return allocationRepository.findAllWithDetails(null).stream()
                .filter(a -> identity.equals(a.getStudent().getIdentityKey()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no allocation for " + identity));
    }

    private String seatOf(String identity) {
        SeatingAllocation a = allocationOf(identity);
        return a.getBench().getRowIndex() + ":" + a.getBench().getColIndex() + ":" + a.getSlotNumber();
    }

    private int[] firstFreeSeat(ExamRoom hall) {
        for (int row = 1; row <= hall.getRows(); row++) {
            for (int col = 1; col <= hall.getColumns(); col++) {
                if (!isTaken(hall, row, col)) {
                    return new int[]{row, col};
                }
            }
        }
        throw new AssertionError("no free seat in " + hall.getHallName());
    }

    private boolean isTaken(ExamRoom hall, int row, int col) {
        return allocationRepository.findAllWithDetails(null).stream()
                .anyMatch(a -> a.getBench().getRoom() != null
                        && a.getBench().getRoom().getId().equals(hall.getId())
                        && a.getBench().getRowIndex() == row
                        && a.getBench().getColIndex() == col);
    }

    private int[] freeSeatNextTo(ExamRoom hall, int row, int col) {
        for (int candidate : new int[]{col - 1, col + 1}) {
            if (candidate >= 1 && candidate <= hall.getColumns() && !isTaken(hall, row, candidate)) {
                return new int[]{row, candidate};
            }
        }
        throw new AssertionError("no free seat next to " + row + "," + col);
    }

    @Test
    @DisplayName("A seating round stores the dynamic parameters it was solved with")
    void testRoundParametersAreRecorded() {
        createHall("Round Hall", 2, 4);
        createStudents("MATH101", "101:CSE:2024", "102:CSE:2024", "103:ECE:2024", "104:ECE:2024");

        arrange(null, null, null, "MATH101", null);

        SeatingRound round = roundRepository
                .findByInstitutionIdAndScopeKey(tenantSupport.currentId(), "MATH101")
                .orElseThrow(() -> new AssertionError("the round was not recorded"));

        assertEquals(1, round.getHorizontalGap());
        assertEquals(0, round.getVerticalGap());
        assertEquals(0, round.getDiagonalGap());
        assertEquals("papercode", round.getScopeAttribute());
        assertEquals(4, round.getSeatedCount());
        assertNotNull(round.getCreatedAt());

        assertEquals(1, round.toSeatingConfig().horizontalGap());
        assertEquals(0, round.toSeatingConfig().verticalGap());
        assertEquals(0, round.toSeatingConfig().diagonalGap());
        assertEquals(List.of(StudentAttribute.of("stream"), StudentAttribute.of("batch")),
                round.parseGroupAttributes());
    }

    @Test
    @DisplayName("The recorded round keeps explicit gap values")
    void testExplicitGapsAreRecorded() {
        createHall("Gap Hall", 3, 4);
        createStudents("PHY101", "201:CSE:2024", "202:CSE:2024", "203:ECE:2024", "204:ECE:2024");

        arrange(1, 1, 1, "PHY101", null);

        SeatingRound round = roundRepository
                .findByInstitutionIdAndScopeKey(tenantSupport.currentId(), "PHY101")
                .orElseThrow();

        assertEquals(1, round.getHorizontalGap());
        assertEquals(1, round.getVerticalGap());
        assertEquals(1, round.getDiagonalGap());
    }

    @Test
    @DisplayName("A round over the whole roster is stored under the empty scope key")
    void testWholeRosterRoundIsRecorded() {
        createHall("Whole Hall", 2, 4);
        createStudents(null, "301:CSE:2024", "302:CSE:2024");

        arrange(null, null, null, null, null);

        SeatingRound round = roundRepository
                .findByInstitutionIdAndScopeKey(tenantSupport.currentId(), SeatingRound.WHOLE_ROSTER_KEY)
                .orElseThrow();

        assertEquals(1, round.getHorizontalGap());
        assertEquals(2, round.getSeatedCount());
    }

    @Test
    @DisplayName("A student can be moved to a free seat that breaks no rule")
    void testLegalMoveSucceeds() {
        ExamRoom hall = createHall("Move Hall", 1, 6);
        createStudents(null, "401:CSE:2024", "402:ECE:2024");
        arrange(0, 0, 0, null, null);

        SeatingAllocation c1 = allocationOf("401");
        int[] free = firstFreeSeat(hall);

        SeatingResponse.AllocationDto moved = seatingService.moveAllocation(c1.getId(),
                new MoveAllocationRequest(hall.getId(), free[0], free[1], 1));

        assertEquals("401", moved.identity());
        assertEquals(free[0], moved.benchRow());
        assertEquals(free[1], moved.benchCol());
        assertEquals(1, moved.slot());

        SeatingAllocation reloaded = allocationRepository.findByIdWithDetails(c1.getId()).orElseThrow();
        assertEquals(free[0], reloaded.getBench().getRowIndex());
        assertEquals(free[1], reloaded.getBench().getColIndex());
    }

    @Test
    @DisplayName("A student can be moved into another hall")
    void testMoveToAnotherHallSucceeds() {
        ExamRoom hallA = createHall("Hall A", 1, 4);
        ExamRoom hallB = createHall("Hall B", 1, 4);
        createStudents(null, "501:CSE:2024", "502:ECE:2024");
        arrange(0, 0, 0, null, List.of(hallA.getId()));

        SeatingAllocation c1 = allocationOf("501");
        SeatingResponse.AllocationDto moved = seatingService.moveAllocation(c1.getId(),
                new MoveAllocationRequest(hallB.getId(), 1, 1, 1));

        assertEquals("Hall B", moved.hallName());
        assertEquals(1, moved.benchRow());
        assertEquals(1, moved.benchCol());
    }

    @Test
    @DisplayName("A move onto an occupied seat is refused")
    void testMoveOntoOccupiedSeatIsRefused() {
        ExamRoom hall = createHall("Busy Hall", 1, 6);
        createStudents(null, "601:CSE:2024", "602:ECE:2024");
        arrange(0, 0, 0, null, null);

        SeatingAllocation c1 = allocationOf("601");
        SeatingAllocation e1 = allocationOf("602");

        SeatingConflictException ex = assertThrows(SeatingConflictException.class,
                () -> seatingService.moveAllocation(c1.getId(),
                        new MoveAllocationRequest(hall.getId(),
                                e1.getBench().getRowIndex(), e1.getBench().getColIndex(),
                                e1.getSlotNumber())));
        assertTrue(ex.getMessage().contains("already taken"));
    }

    @Test
    @DisplayName("A move that breaks cohort separation rules is refused")
    void testIllegalMoveIsRefused() {
        ExamRoom hall = createHall("Strict Hall", 1, 6);
        createStudents(null, "701:CSE:2024", "702:CSE:2024");
        arrange(null, null, null, null, null);

        SeatingAllocation c1 = allocationOf("701");
        SeatingAllocation c2 = allocationOf("702");
        int[] neighbour = freeSeatNextTo(hall, c2.getBench().getRowIndex(), c2.getBench().getColIndex());

        SeatingConflictException ex = assertThrows(SeatingConflictException.class,
                () -> seatingService.moveAllocation(c1.getId(),
                        new MoveAllocationRequest(hall.getId(), neighbour[0], neighbour[1], 1)));
        assertTrue(ex.getMessage().contains("Cannot seat"));
    }

    @Test
    @DisplayName("Unknown bench, unknown allocation, and invalid slot are rejected")
    void testInvalidMoveTargetsRejected() {
        ExamRoom hall = createHall("Slot Hall", 1, 4);
        createStudents(null, "801:CSE:2024", "802:ECE:2024");
        arrange(0, 0, 0, null, null);

        SeatingAllocation c1 = allocationOf("801");

        assertThrows(ResourceNotFoundException.class,
                () -> seatingService.moveAllocation(c1.getId(),
                        new MoveAllocationRequest(hall.getId(), 9, 9, 1)));
        assertThrows(ResourceNotFoundException.class,
                () -> seatingService.moveAllocation(999999L,
                        new MoveAllocationRequest(hall.getId(), 1, 1, 1)));
        assertThrows(IllegalArgumentException.class,
                () -> seatingService.moveAllocation(c1.getId(),
                        new MoveAllocationRequest(hall.getId(), 1, 1, 2)));
    }

    @Test
    @DisplayName("PATCH /api/v1/seating-plans/current/allocations/{id} moves a seat")
    void testMoveEndpointReturnsTheAllocation() throws Exception {
        ExamRoom hall = createHall("Http Hall", 1, 6);
        createStudents(null, "B01:CSE:2024", "B02:ECE:2024");
        arrange(0, 0, 0, null, null);

        SeatingAllocation c1 = allocationOf("B01");
        int[] free = firstFreeSeat(hall);

        mockMvc.perform(patch("/api/v1/seating-plans/current/allocations/{id}", c1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId": %d, "benchRow": %d, "benchCol": %d, "slot": 1}
                                """.formatted(hall.getId(), free[0], free[1])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.identity").value("B01"))
                .andExpect(jsonPath("$.benchRow").value(free[0]))
                .andExpect(jsonPath("$.benchCol").value(free[1]))
                .andExpect(jsonPath("$.slot").value(1));
    }

    @Test
    @DisplayName("A refused move answers 409 CONFLICT and changes nothing")
    void testMoveEndpointAnswersConflict() throws Exception {
        ExamRoom hall = createHall("Http Strict Hall", 1, 6);
        createStudents(null, "B11:CSE:2024", "B12:CSE:2024");
        arrange(null, null, null, null, null);

        SeatingAllocation c1 = allocationOf("B11");
        SeatingAllocation c2 = allocationOf("B12");
        int[] neighbour = freeSeatNextTo(hall, c2.getBench().getRowIndex(), c2.getBench().getColIndex());
        String seatBefore = seatOf("B11");

        mockMvc.perform(patch("/api/v1/seating-plans/current/allocations/{id}", c1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId": %d, "benchRow": %d, "benchCol": %d, "slot": 1}
                                """.formatted(hall.getId(), neighbour[0], neighbour[1])))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.status").value(409));

        assertEquals(seatBefore, seatOf("B11"));
    }

    @Test
    @DisplayName("A move with an incomplete body answers 400 VALIDATION_FAILED")
    void testMoveEndpointValidatesTheBody() throws Exception {
        ExamRoom hall = createHall("Http Invalid Hall", 1, 4);
        createStudents(null, "B21:CSE:2024", "B22:ECE:2024");
        arrange(0, 0, 0, null, null);

        SeatingAllocation c1 = allocationOf("B21");

        mockMvc.perform(patch("/api/v1/seating-plans/current/allocations/{id}", c1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\": %d}".formatted(hall.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
    }

    @Test
    @DisplayName("A move of an unknown allocation answers 404 NOT_FOUND")
    void testMoveEndpointAnswersNotFound() throws Exception {
        ExamRoom hall = createHall("Http Missing Hall", 1, 4);
        createStudents(null, "B31:CSE:2024", "B32:ECE:2024");
        arrange(0, 0, 0, null, null);

        mockMvc.perform(patch("/api/v1/seating-plans/current/allocations/{id}", 999999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId": %d, "benchRow": 1, "benchCol": 1, "slot": 1}
                                """.formatted(hall.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
