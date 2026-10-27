package com.examseating.web;

import com.examseating.dto.ImportResult;
import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.service.ExcelImportService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

/**
 * Exam hall configuration REST endpoints.
 *
 * <p>The controller accepts an {@link ExamRoom} JSON body. Note how persistence is
 * handled: {@code ExamRoom.benches} is {@code cascade = ALL}, so saving the room
 * automatically persists its M×N bench grid — one {@code save()} call, no manual
 * bench bookkeeping.</p>
 */
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class ExamRoomController {

    private final ExamRoomRepository examRoomRepository;
    private final SeatingAllocationRepository allocationRepository;
    private final ExcelImportService excelImportService;

    @GetMapping
    public List<ExamRoom> list() {
        return examRoomRepository.findAllByOrderByHallNameAsc();
    }

    @GetMapping("/{id}")
    public ExamRoom get(@PathVariable Long id) {
        // findWithBenchesById fetches benches in the same query, so Jackson can
        // serialize the room (including its bench grid) after the transaction closes.
        return examRoomRepository.findWithBenchesById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Room " + id + " not found"));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public ExamRoom create(@Valid @RequestBody ExamRoom room) {
        fillBenchGrid(room); // build the M×N grid so the engine always finds benches
        return examRoomRepository.save(room);
    }

    /**
     * @Transactional: clearing/rebuilding the room's bench grid touches the lazy
     * benches collection, which requires an open persistence context (one
     * transaction for the whole read-modify-write).
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    @org.springframework.transaction.annotation.Transactional
    public ExamRoom update(@PathVariable Long id, @Valid @RequestBody ExamRoom updates) {
        ExamRoom existing = examRoomRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Room " + id + " not found"));
        existing.setHallName(updates.getHallName());
        existing.setRows(updates.getRows());
        existing.setColumns(updates.getColumns());
        existing.setBenchType(updates.getBenchType());
        // A changed layout invalidates the old benches: rebuild the grid.
        // JPA pitfall: if we clear() and add new benches with the SAME unique
        // (room_id, bench_row, bench_col) keys in one flush, Hibernate can INSERT the
        // new rows before DELETEing the old ones -> unique-constraint violation.
        // So: clear, flush (execute the deletes NOW), then add the new grid.
        existing.getBenches().clear();
        examRoomRepository.flush();
        fillBenchGrid(existing);
        return examRoomRepository.save(existing);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public void delete(@PathVariable Long id) {
        // Free the foreign keys: seats reference benches, benches reference the room.
        allocationRepository.deleteByBenchRoomId(id);
        examRoomRepository.deleteById(id);
    }

    /** Bulk upload of a room configuration Excel file. */
    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public ImportResult importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        return excelImportService.importRooms(file.getInputStream());
    }

    /** Row-major 1-based bench grid. */
    private static void fillBenchGrid(ExamRoom room) {
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
    }
}
