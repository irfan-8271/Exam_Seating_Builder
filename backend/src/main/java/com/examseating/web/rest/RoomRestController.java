package com.examseating.web.rest;

import com.examseating.dto.ImportResult;
import com.examseating.dto.PageResponse;
import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.service.ExcelImportService;
import com.examseating.service.QuotaService;
import com.examseating.tenancy.TenantSupport;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Primary v1 REST resource for exam halls and their bench grids.
 *
 * <p>Changing a hall's layout invalidates its saved seats, so PUT/PATCH/DELETE
 * explicitly remove those allocations before rebuilding the grid.</p>
 */
@RestController
@RequestMapping("/api/v1/rooms")
@RequiredArgsConstructor
public class RoomRestController {

    private static final Set<String> SORTABLE = Set.of("id", "hallName", "rows", "columns");
    private static final Set<String> PATCHABLE = Set.of("hallName", "rows", "columns", "benchType");
    private static final int MAX_DIMENSION = 500;

    private final ExamRoomRepository examRoomRepository;
    private final SeatingAllocationRepository allocationRepository;
    private final ExcelImportService excelImportService;
    private final TenantSupport tenant;
    private final QuotaService quotas;

    /** GET /api/v1/rooms — paged, filtered, sortable collection. */
    @GetMapping
    public PageResponse<ExamRoom> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            @RequestParam(required = false) String hallName,
            @RequestParam(required = false) String benchType,
            @RequestParam(required = false) String sortBy,
            @RequestParam(defaultValue = "asc") String direction) {
        List<ExamRoom> rooms = tenant.visible(examRoomRepository.findAllByOrderByHallNameAsc());
        if (!isBlank(hallName)) {
            String needle = hallName.trim().toLowerCase();
            rooms = rooms.stream()
                    .filter(r -> r.getHallName() != null && r.getHallName().toLowerCase().contains(needle))
                    .toList();
        }
        if (!isBlank(benchType)) {
            String wanted = benchType.trim().toUpperCase().replace('-', '_').replace(' ', '_');
            rooms = rooms.stream()
                    .filter(r -> r.getBenchType() != null && r.getBenchType().name().equals(wanted))
                    .toList();
        }
        String sort = (sortBy == null || sortBy.isBlank()) ? "hallName" : sortBy.trim();
        if (!SORTABLE.contains(sort)) {
            throw new IllegalArgumentException("sortBy must be one of " + SORTABLE);
        }
        if (!"asc".equalsIgnoreCase(direction) && !"desc".equalsIgnoreCase(direction)) {
            throw new IllegalArgumentException("direction must be 'asc' or 'desc'");
        }
        Comparator<ExamRoom> comparator = roomComparator(sort);
        if ("desc".equalsIgnoreCase(direction)) {
            comparator = comparator.reversed();
        }
        return PageResponse.of(rooms.stream().sorted(comparator).toList(), page, size);
    }

    /** GET /api/v1/rooms/{id} — one hall with its bench grid. */
    @GetMapping("/{id}")
    public ExamRoom get(@PathVariable Long id) {
        ExamRoom room = examRoomRepository.findWithBenchesById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Room " + id + " not found"));
        tenant.require(room, "Room " + id);
        return room;
    }

    /** GET /api/v1/rooms/{id}/benches — paged bench grid (row-major). */
    @GetMapping("/{id}/benches")
    public PageResponse<Bench> benches(@PathVariable Long id,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size) {
        List<Bench> grid = get(id).getBenches().stream()
                .sorted(Comparator.comparingInt(Bench::getRowIndex).thenComparingInt(Bench::getColIndex))
                .toList();
        return PageResponse.of(grid, page, size);
    }

    /** POST /api/v1/rooms — 201 Created with a Location header. */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public ResponseEntity<ExamRoom> create(@Valid @RequestBody ExamRoom room) {
        quotas.checkRooms(1);
        tenant.stamp(room);
        fillBenchGrid(room);
        ExamRoom saved = examRoomRepository.save(room);
        return ResponseEntity.created(URI.create("/api/v1/rooms/" + saved.getId())).body(saved);
    }

    /** PUT /api/v1/rooms/{id} — full replacement, rebuilding the bench grid. */
    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    @Transactional
    public ExamRoom replace(@PathVariable Long id, @Valid @RequestBody ExamRoom updates) {
        ExamRoom existing = get(id);
        validateLayout(updates.getRows(), updates.getColumns());
        existing.setHallName(updates.getHallName());
        existing.setRows(updates.getRows());
        existing.setColumns(updates.getColumns());
        existing.setBenchType(updates.getBenchType());
        rebuildGrid(existing, id);
        return examRoomRepository.save(existing);
    }

    /** PATCH /api/v1/rooms/{id} — partial update of whitelisted fields. */
    @PatchMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    @Transactional
    public ExamRoom patch(@PathVariable Long id, @RequestBody Map<String, Object> changes) {
        if (changes == null || changes.isEmpty()) {
            throw new IllegalArgumentException("PATCH body must contain at least one field");
        }
        for (String key : changes.keySet()) {
            if (!PATCHABLE.contains(key)) {
                throw new IllegalArgumentException("Unknown or immutable field '" + key
                        + "'; allowed: " + PATCHABLE);
            }
        }
        ExamRoom existing = get(id);
        boolean layoutChanged = false;
        if (changes.containsKey("hallName")) {
            existing.setHallName(stringValue(changes, "hallName"));
        }
        if (changes.containsKey("rows")) {
            existing.setRows(intValue(changes, "rows"));
            layoutChanged = true;
        }
        if (changes.containsKey("columns")) {
            existing.setColumns(intValue(changes, "columns"));
            layoutChanged = true;
        }
        if (changes.containsKey("benchType")) {
            Object raw = changes.get("benchType");
            if (!(raw instanceof String s)) {
                throw new IllegalArgumentException("benchType must be a string");
            }
            try {
                existing.setBenchType(ExamRoom.BenchType.valueOf(s.trim().toUpperCase().replace('-', '_')));
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException("benchType must be SINGLE_SEATER or DUAL_SEATER");
            }
            layoutChanged = true;
        }
        validateLayout(existing.getRows(), existing.getColumns());
        if (existing.getHallName() == null || existing.getHallName().isBlank()) {
            throw new IllegalArgumentException("Hall name must not be blank");
        }
        if (layoutChanged) {
            rebuildGrid(existing, id);
        }
        return examRoomRepository.save(existing);
    }

    /** DELETE /api/v1/rooms/{id} — 204 No Content. */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public ResponseEntity<Void> delete(@PathVariable Long id) {
        get(id);
        allocationRepository.deleteByBenchRoomId(id);
        examRoomRepository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    /** POST /api/v1/rooms/imports — Excel hall upload (multipart). */
    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public ImportResult importExcel(@RequestParam("file") MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A non-empty 'file' part is required");
        }
        return excelImportService.importRooms(file.getInputStream());
    }

    private void rebuildGrid(ExamRoom room, Long id) {
        allocationRepository.deleteByBenchRoomId(id);
        room.getBenches().clear();
        examRoomRepository.flush(); // execute the DELETEs before inserting the new grid
        fillBenchGrid(room);
    }

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

    private static void validateLayout(int rows, int columns) {
        if (rows < 1 || rows > MAX_DIMENSION || columns < 1 || columns > MAX_DIMENSION) {
            throw new IllegalArgumentException("Rows and columns must be between 1 and " + MAX_DIMENSION);
        }
    }

    private static Comparator<ExamRoom> roomComparator(String field) {
        return switch (field) {
            case "id" -> Comparator.comparingLong((ExamRoom r) -> r.getId() == null ? 0L : r.getId());
            case "rows" -> Comparator.comparingInt(ExamRoom::getRows);
            case "columns" -> Comparator.comparingInt(ExamRoom::getColumns);
            default -> Comparator.comparing((ExamRoom r) -> r.getHallName() == null ? "" : r.getHallName());
        };
    }

    private static String stringValue(Map<String, Object> changes, String key) {
        Object value = changes.get(key);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String s)) {
            throw new IllegalArgumentException("Field '" + key + "' must be a string or null");
        }
        return s.isBlank() ? null : s.trim();
    }

    private static int intValue(Map<String, Object> changes, String key) {
        Object value = changes.get(key);
        if (!(value instanceof Number n)) {
            throw new IllegalArgumentException("Field '" + key + "' must be a whole number");
        }
        double d = n.doubleValue();
        if (d != Math.floor(d)) {
            throw new IllegalArgumentException("Field '" + key + "' must be a whole number");
        }
        return n.intValue();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
