package com.examseating.service;

import com.examseating.dto.ArrangeRequest;
import com.examseating.dto.SeatingResponse;
import com.examseating.engine.SeatingArrangementEngine;
import com.examseating.engine.SeatingArrangementEngine.ConflictScope;
import com.examseating.engine.SeatingArrangementEngine.SeatingConfig;
import com.examseating.engine.SeatingArrangementEngine.SeatingPlan;
import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Student;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Orchestrates a seating round: load data from the DB → run the Step 1 engine →
 * persist the result → map it to DTOs.
 *
 * <p><b>Spring/JPA concepts used here (since you know Hibernate without Spring):</b></p>
 * <ul>
 *   <li><b>{@code @Service}</b> — a {@code @Component} stereotype; Spring creates one
 *       singleton bean of this class and injects the repositories into it (via the
 *       {@code @RequiredArgsConstructor} + final fields — that is Spring's "constructor
 *       injection", the recommended style).</li>
 *   <li><b>{@code @Transactional}</b> — without this annotation, each repository call
 *       would run in its own short transaction. Here the whole method (read rooms,
 *       run engine, delete old allocations, insert new ones) must be ONE atomic unit:
 *       either all of it succeeds or all of it rolls back. Spring implements this with
 *       a proxy: it opens a Hibernate {@code Session}/{@code Transaction} before the
 *       method and commits/rolls back afterwards. The same transaction also keeps lazy
 *       collections loadable while the method runs.</li>
 *   <li><b>Managed vs detached entities</b> — rooms/students returned by repository
 *       methods are <i>managed</i> (attached to the open persistence context), so
 *       changes are flushed automatically and lazy loading works.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class SeatingService {

    private final StudentRepository studentRepository;
    private final ExamRoomRepository examRoomRepository;
    private final SeatingAllocationRepository allocationRepository;

    /** The Step 1 engine is stateless — one shared instance is fine. */
    private final SeatingArrangementEngine engine = new SeatingArrangementEngine();

    /**
     * Runs a full seating round and persists it.
     *
     * @return response DTO (built inside the transaction on purpose — see below)
     */
    @Transactional
    public SeatingResponse arrange(ArrangeRequest request) {
        // 1) Load halls (all, or only the requested ones).
        //    findAllByOrderByHallNameAsc uses @EntityGraph so benches come loaded in
        //    the same query.
        List<ExamRoom> rooms = (request.roomIds() == null || request.roomIds().isEmpty())
                ? examRoomRepository.findAllByOrderByHallNameAsc()
                : examRoomRepository.findAllByIdIn(request.roomIds());
        if (rooms.isEmpty()) {
            throw new ResourceNotFoundException("No exam halls found — create a room first.");
        }
        // The engine needs every hall's bench grid present and persisted, otherwise it
        // would attach transient benches that cannot be referenced by saved allocations.
        rooms.forEach(this::ensureBenchGridPersisted);

        // 2) Load students — all, or only those sitting the requested paper.
        String subjectCode = blankToNull(request.subjectCode());
        List<Student> students = (subjectCode == null)
                ? studentRepository.findAll()
                : studentRepository.findBySubjectCode(subjectCode);

        // 3) Build the engine configuration from the request (inferred from strictness or explicit gaps).
        int dH = (request.horizontalGap() != null) ? request.horizontalGap() : 1;
        int dV = (request.verticalGap() != null) ? request.verticalGap() :
                (request.strictness() == ArrangeRequest.StrictnessLevel.EXTREME ? 1 : 0);
        int dD = (request.diagonalGap() != null) ? request.diagonalGap() :
                (request.strictness() == ArrangeRequest.StrictnessLevel.EXTREME ? 1 : 0);
        ConflictScope scope = (request.conflictScope() != null) ? request.conflictScope() :
                ConflictScope.DEPARTMENT_AND_YEAR;
        com.examseating.engine.SeatingArrangementEngine.BenchPairingRule pairingRule =
                (request.benchPairingRule() != null)
                        ? request.benchPairingRule()
                        : com.examseating.engine.SeatingArrangementEngine.BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR;
        boolean horizontalDeptIsolation = request.horizontalDeptIsolation() == null || request.horizontalDeptIsolation();

        SeatingConfig config = new SeatingConfig(
                dH,
                dV,
                dD,
                scope,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                pairingRule,
                horizontalDeptIsolation);

        // 4) Solve. arrangeForSubject is a thin wrapper that filters by subject code.
        SeatingPlan plan = (subjectCode == null)
                ? engine.arrange(rooms, students, config)
                : engine.arrangeForSubject(rooms, students, subjectCode, config);

        // 5) Replace the previous round (subject-scoped, or the whole table).
        //    Bulk SQL deletes execute immediately; the new inserts then cannot collide
        //    with the (bench_id, slot_number) unique key.
        if (subjectCode == null) {
            allocationRepository.deleteAllInBatch();
        } else {
            allocationRepository.deleteBySubjectCode(subjectCode);
        }

        // 6) Persist every seat. save() on a new entity = Hibernate persist; ids are
        //    assigned by MySQL AUTO_INCREMENT and populated immediately.
        List<SeatingAllocation> saved = new ArrayList<>(plan.allocations().size());
        for (SeatingAllocation seat : plan.allocations()) {
            SeatingAllocation entity = SeatingAllocation.builder()
                    .student(seat.getStudent())   // managed — loaded by the repository
                    .bench(seat.getBench())       // managed — loaded with its room
                    .slotNumber(seat.getSlotNumber())
                    .subjectCode(seat.getStudent().getSubjectCode())
                    .build();
            saved.add(allocationRepository.save(entity));
        }

        // 7) Map to DTOs. This is done HERE, inside the transaction: reading
        //    seat.getBench().getRoom() outside would throw LazyInitializationException
        //    (we keep spring.jpa.open-in-view=false).
        List<SeatingResponse.AllocationDto> dtos = saved.stream()
                .map(SeatingResponse.AllocationDto::from)
                .toList();

        return SeatingResponse.of(dtos.size(), plan.unplacedStudents().size(), plan.totalSlots(),
                plan.totalBacktrackSteps(), plan.solved(), dtos);
    }

    /** Read-only view of the persisted allocations (used by the GET endpoint). */
    @Transactional(readOnly = true)
    public SeatingResponse view(String subjectCode) {
        List<SeatingAllocation> allocations =
                allocationRepository.findAllWithDetails(blankToNull(subjectCode));
        long totalSlots = allocations.size();
        List<SeatingResponse.AllocationDto> dtos = allocations.stream()
                .map(SeatingResponse.AllocationDto::from)
                .toList();
        return SeatingResponse.of(dtos.size(), 0, (int) totalSlots, 0, true, dtos);
    }

    /** Clears persisted allocations for one subject (or all when subject is null). */
    @Transactional
    public void clear(String subjectCode) {
        if (blankToNull(subjectCode) == null) {
            allocationRepository.deleteAllInBatch();
        } else {
            allocationRepository.deleteBySubjectCode(subjectCode);
        }
    }

    /**
     * If a hall was created without its bench grid (defensive — the REST create
     * endpoint already builds it), persist an M×N grid of benches so the engine
     * can reference persisted Bench rows.
     */
    private void ensureBenchGridPersisted(ExamRoom room) {
        if (!room.getBenches().isEmpty()) {
            return;
        }
        for (int r = 1; r <= room.getRows(); r++) {
            for (int c = 1; c <= room.getColumns(); c++) {
                room.getBenches().add(Bench.builder()
                        .room(room)
                        .rowIndex(r)
                        .colIndex(c)
                        .benchType(room.getBenchType())
                        .build());
            }
        }
        // CascadeType.ALL on ExamRoom.benches persists the whole grid with the room.
        examRoomRepository.save(room);
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
