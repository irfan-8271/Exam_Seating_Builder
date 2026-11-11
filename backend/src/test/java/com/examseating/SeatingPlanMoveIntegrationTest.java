package com.examseating;

import com.examseating.dto.ArrangeRequest;
import com.examseating.dto.MoveAllocationRequest;
import com.examseating.dto.SeatingResponse;
import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.SeatingRound;
import com.examseating.entity.Student;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.exception.SeatingConflictException;
import com.examseating.repository.BenchRepository;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.repository.SeatingRoundRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.repository.SubscriptionRepository;
import com.examseating.service.SeatingService;
import com.examseating.tenancy.TenantSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Integration tests for manual seat moves and for the seating round metadata
 * that makes them checkable.
 *
 * <p>A seating round now stores the parameters it was solved with, so a seat
 * that is changed by hand can be checked against exactly the rules the plan was
 * built with — and a change that breaks one of those rules is refused.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
public class SeatingPlanMoveIntegrationTest {

    @Autowired
    private SeatingService seatingService;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private ExamRoomRepository examRoomRepository;

    @Autowired
    private BenchRepository benchRepository;

    @Autowired
    private SeatingAllocationRepository allocationRepository;

    @Autowired
    private SeatingRoundRepository roundRepository;

    @Autowired
    private SubscriptionRepository subscriptionRepository;

    @Autowired
    private TenantSupport tenantSupport;

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void cleanDatabase() {
        allocationRepository.deleteAllInBatch();
        roundRepository.deleteAllInBatch();
        benchRepository.deleteAllInBatch();
        examRoomRepository.deleteAllInBatch();
        studentRepository.deleteAllInBatch();

        Long defaultTenantId = tenantSupport.currentId();
        com.examseating.entity.Subscription sub = subscriptionRepository.findByInstitutionId(defaultTenantId)
                .orElseGet(() -> com.examseating.entity.Subscription.builder().institutionId(defaultTenantId).build());
        sub.setPlanCode("ENTERPRISE");
        sub.setStatus(com.examseating.entity.Subscription.Status.ACTIVE);
        sub.setPeriodStart(java.time.Instant.now().minus(1, java.time.temporal.ChronoUnit.DAYS));
        sub.setPeriodEnd(java.time.Instant.now().plus(30, java.time.temporal.ChronoUnit.DAYS));
        subscriptionRepository.save(sub);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** A hall of single-seater benches. */
    private ExamRoom createHall(String name, int rows, int columns) {
        return examRoomRepository.save(ExamRoom.builder()
                .hallName(name)
                .rows(rows)
                .columns(columns)
                .benchType(ExamRoom.BenchType.SINGLE_SEATER)
                .build());
    }

    private void createStudents(String subjectCode, String... specs) {
        List<Student> students = new ArrayList<>();
        for (String spec : specs) {
            String[] parts = spec.split(":"); // roll:department:year
            students.add(Student.builder()
                    .name("S" + parts[0])
                    .rollNo(parts[0])
                    .department(parts[1])
                    .yearSession(parts[2])
                    .subjectCode(subjectCode)
                    .build());
        }
        studentRepository.saveAll(students);
    }

    /**
     * Arranges the given halls.
     *
     * @param gaps the explicit gap values; {@code null} uses the BASIC preset
     */
    private SeatingResponse arrange(List<ExamRoom> halls, Integer dH, Integer dV, Integer dD,
                                     String subjectCode, List<Long> roomIds) {
        ArrangeRequest request = new ArrangeRequest(
                null,
                dH, dV, dD,
                null, null, null, null, null,
                subjectCode, roomIds, true);
        return seatingService.arrange(request);
    }

    /** The allocation of a student, looked up by roll number. */
    private SeatingAllocation allocationOf(String rollNo) {
        return allocationRepository.findAllWithDetails(null).stream()
                .filter(a -> rollNo.equals(a.getStudent().getRollNo()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no allocation for " + rollNo));
    }

    /** The seat a student occupies, as "row:col:slot". */
    private String seatOf(String rollNo) {
        SeatingAllocation a = allocationOf(rollNo);
        return a.getBench().getRowIndex() + ":" + a.getBench().getColIndex() + ":" + a.getSlotNumber();
    }

    /** The first free seat of a hall, in reading order. */
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

    // ------------------------------------------------------------------
    // The round metadata
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A seating round stores the parameters it was solved with")
    void testRoundParametersAreRecorded() {
        ExamRoom hall = createHall("Round Hall", 2, 4);
        createStudents("MATH101", "101:CSE:2024", "102:CSE:2024", "103:ECE:2024", "104:ECE:2024");

        arrange(List.of(hall), null, null, null, "MATH101", null);

        SeatingRound round = roundRepository
                .findByInstitutionIdAndSubjectKey(tenantSupport.currentId(), "MATH101")
                .orElseThrow(() -> new AssertionError("the round was not recorded"));

        // BASIC is the default preset: dH = 1, dV = 0, dD = 0.
        assertEquals(1, round.getHorizontalGap());
        assertEquals(0, round.getVerticalGap());
        assertEquals(0, round.getDiagonalGap());
        assertEquals(4, round.getSeatedCount());
        assertNotNull(round.getCreatedAt());

        // The stored parameters must rebuild the configuration the solver used.
        assertEquals(1, round.toSeatingConfig().horizontalGap());
        assertEquals(0, round.toSeatingConfig().verticalGap());
        assertEquals(0, round.toSeatingConfig().diagonalGap());
        assertTrue(round.toSeatingConfig().horizontalDeptIsolation());
    }

    @Test
    @DisplayName("The recorded round keeps the explicit gap values of the request")
    void testExplicitGapsAreRecorded() {
        ExamRoom hall = createHall("Gap Hall", 3, 4);
        createStudents("PHY101", "201:CSE:2024", "202:CSE:2024", "203:ECE:2024", "204:ECE:2024");

        arrange(List.of(hall), 1, 1, 1, "PHY101", null);

        SeatingRound round = roundRepository
                .findByInstitutionIdAndSubjectKey(tenantSupport.currentId(), "PHY101")
                .orElseThrow(() -> new AssertionError("the round was not recorded"));

        assertEquals(1, round.getHorizontalGap());
        assertEquals(1, round.getVerticalGap());
        assertEquals(1, round.getDiagonalGap());
    }

    @Test
    @DisplayName("A round over the whole roster is stored under the empty subject key")
    void testWholeRosterRoundIsRecorded() {
        ExamRoom hall = createHall("Whole Hall", 2, 4);
        createStudents(null, "301:CSE:2024", "302:CSE:2024");

        arrange(List.of(hall), null, null, null, null, null);

        SeatingRound round = roundRepository
                .findByInstitutionIdAndSubjectKey(tenantSupport.currentId(), SeatingRound.WHOLE_ROSTER_KEY)
                .orElseThrow(() -> new AssertionError("the round was not recorded"));

        assertEquals(1, round.getHorizontalGap());
        assertEquals(2, round.getSeatedCount());
    }

    // ------------------------------------------------------------------
    // Moving a seat
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A student can be moved to a free seat that breaks no rule")
    void testLegalMoveSucceeds() {
        ExamRoom hall = createHall("Move Hall", 1, 6);
        createStudents(null, "401:CSE:2024", "402:ECE:2024");
        // No separation axis is switched on, so every free seat is legal.
        arrange(List.of(hall), 0, 0, 0, null, null);

        SeatingAllocation c1 = allocationOf("401");
        int[] free = firstFreeSeat(hall);

        SeatingResponse.AllocationDto moved = seatingService.moveAllocation(c1.getId(),
                new MoveAllocationRequest(hall.getId(), free[0], free[1], 1));

        assertEquals("401", moved.rollNo());
        assertEquals(free[0], moved.benchRow());
        assertEquals(free[1], moved.benchCol());
        assertEquals(1, moved.slot());

        // The change is persisted.
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
        // Only hall A takes part in the round, so hall B stays empty.
        arrange(List.of(hallA), 0, 0, 0, null, List.of(hallA.getId()));

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
        arrange(List.of(hall), 0, 0, 0, null, null);

        SeatingAllocation c1 = allocationOf("601");
        SeatingAllocation e1 = allocationOf("602");

        SeatingConflictException ex = assertThrows(SeatingConflictException.class,
                () -> seatingService.moveAllocation(c1.getId(),
                        new MoveAllocationRequest(hall.getId(),
                                e1.getBench().getRowIndex(), e1.getBench().getColIndex(),
                                e1.getSlotNumber())));

        assertTrue(ex.getMessage().contains("already taken"),
                "the message must say why: " + ex.getMessage());
    }

    @Test
    @DisplayName("A move that breaks the separation rules is refused")
    void testIllegalMoveIsRefused() {
        ExamRoom hall = createHall("Strict Hall", 1, 6);
        // Two classmates: they must stay more than one column apart.
        createStudents(null, "701:CSE:2024", "702:CSE:2024");
        arrange(List.of(hall), null, null, null, null, null);

        SeatingAllocation c1 = allocationOf("701");
        SeatingAllocation c2 = allocationOf("702");

        // The seat right next to the second classmate: free (the first one is
        // more than one column away) but forbidden by the horizontal gap.
        int[] neighbour = freeSeatNextTo(hall, c2.getBench().getRowIndex(), c2.getBench().getColIndex());

        SeatingConflictException ex = assertThrows(SeatingConflictException.class,
                () -> seatingService.moveAllocation(c1.getId(),
                        new MoveAllocationRequest(hall.getId(), neighbour[0], neighbour[1], 1)));

        assertTrue(ex.getMessage().contains("Cannot seat"),
                "the message must explain the refusal: " + ex.getMessage());
    }

    @Test
    @DisplayName("A move to a bench that does not exist is a 404")
    void testMoveToUnknownBenchIsNotFound() {
        ExamRoom hall = createHall("Unknown Hall", 1, 4);
        createStudents(null, "801:CSE:2024", "802:ECE:2024");
        arrange(List.of(hall), 0, 0, 0, null, null);

        SeatingAllocation c1 = allocationOf("801");

        assertThrows(ResourceNotFoundException.class,
                () -> seatingService.moveAllocation(c1.getId(),
                        new MoveAllocationRequest(hall.getId(), 9, 9, 1)));
    }

    @Test
    @DisplayName("A move of an unknown allocation is a 404")
    void testMoveOfUnknownAllocationIsNotFound() {
        ExamRoom hall = createHall("Missing Hall", 1, 4);
        createStudents(null, "901:CSE:2024", "902:ECE:2024");
        arrange(List.of(hall), 0, 0, 0, null, null);

        assertThrows(ResourceNotFoundException.class,
                () -> seatingService.moveAllocation(999999L,
                        new MoveAllocationRequest(hall.getId(), 1, 1, 1)));
    }

    @Test
    @DisplayName("A move to a slot the bench does not have is rejected")
    void testMoveToNonExistingSlotIsRejected() {
        ExamRoom hall = createHall("Slot Hall", 1, 4);
        createStudents(null, "A01:CSE:2024", "A02:ECE:2024");
        arrange(List.of(hall), 0, 0, 0, null, null);

        SeatingAllocation c1 = allocationOf("A01");

        // The hall has single-seater benches only, so there is no slot 2.
        assertThrows(IllegalArgumentException.class,
                () -> seatingService.moveAllocation(c1.getId(),
                        new MoveAllocationRequest(hall.getId(), 1, 1, 2)));
    }

    /**
     * A free seat next to a given bench in the same row. The seat on the other
     * side of a bench at the edge of the row does not exist, so only the
     * in-range candidates are considered.
     */
    private int[] freeSeatNextTo(ExamRoom hall, int row, int col) {
        for (int candidate : new int[]{col - 1, col + 1}) {
            if (candidate >= 1 && candidate <= hall.getColumns() && !isTaken(hall, row, candidate)) {
                return new int[]{row, candidate};
            }
        }
        throw new AssertionError("no free seat next to " + row + "," + col);
    }

    // ------------------------------------------------------------------
    // The HTTP layer
    // ------------------------------------------------------------------

    @Test
    @DisplayName("PATCH /api/v1/seating-plans/allocations/{id} moves a seat")
    void testMoveEndpointReturnsTheAllocation() throws Exception {
        ExamRoom hall = createHall("Http Hall", 1, 6);
        createStudents(null, "B01:CSE:2024", "B02:ECE:2024");
        arrange(List.of(hall), 0, 0, 0, null, null);

        SeatingAllocation c1 = allocationOf("B01");
        int[] free = firstFreeSeat(hall);

        mockMvc.perform(patch("/api/v1/seating-plans/allocations/{id}", c1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId": %d, "benchRow": %d, "benchCol": %d, "slot": 1}
                                """.formatted(hall.getId(), free[0], free[1])))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rollNo").value("B01"))
                .andExpect(jsonPath("$.benchRow").value(free[0]))
                .andExpect(jsonPath("$.benchCol").value(free[1]))
                .andExpect(jsonPath("$.slot").value(1));
    }

    @Test
    @DisplayName("A refused move answers 409 CONFLICT and changes nothing")
    void testMoveEndpointAnswersConflict() throws Exception {
        ExamRoom hall = createHall("Http Strict Hall", 1, 6);
        createStudents(null, "B11:CSE:2024", "B12:CSE:2024");
        arrange(List.of(hall), null, null, null, null, null);

        SeatingAllocation c1 = allocationOf("B11");
        SeatingAllocation c2 = allocationOf("B12");
        int[] neighbour = freeSeatNextTo(hall, c2.getBench().getRowIndex(), c2.getBench().getColIndex());
        String seatBefore = seatOf("B11");

        mockMvc.perform(patch("/api/v1/seating-plans/allocations/{id}", c1.getId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId": %d, "benchRow": %d, "benchCol": %d, "slot": 1}
                                """.formatted(hall.getId(), neighbour[0], neighbour[1])))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"))
                .andExpect(jsonPath("$.status").value(409));

        // Nothing was moved.
        assertEquals(seatBefore, seatOf("B11"), "a refused move must not change the plan");
    }

    @Test
    @DisplayName("A move with an incomplete body answers 400 VALIDATION_FAILED")
    void testMoveEndpointValidatesTheBody() throws Exception {
        ExamRoom hall = createHall("Http Invalid Hall", 1, 4);
        createStudents(null, "B21:CSE:2024", "B22:ECE:2024");
        arrange(List.of(hall), 0, 0, 0, null, null);

        SeatingAllocation c1 = allocationOf("B21");

        mockMvc.perform(patch("/api/v1/seating-plans/allocations/{id}", c1.getId())
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
        arrange(List.of(hall), 0, 0, 0, null, null);

        mockMvc.perform(patch("/api/v1/seating-plans/allocations/{id}", 999999L)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"roomId": %d, "benchRow": 1, "benchCol": 1, "slot": 1}
                                """.formatted(hall.getId())))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }
}
