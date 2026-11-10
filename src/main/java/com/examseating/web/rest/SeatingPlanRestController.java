package com.examseating.web.rest;

import com.examseating.dto.ArrangeRequest;
import com.examseating.dto.MoveAllocationRequest;
import com.examseating.dto.PageResponse;
import com.examseating.dto.SeatingResponse;
import com.examseating.service.SeatingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.List;

/**
 * Primary v1 REST resource for the current seating plan.
 *
 * <p>The plan is a singleton resource addressed by the collection root: POST
 * generates (201 Created), GET reads (paged allocation list embedded in the
 * summary), DELETE clears (204 No Content). A pure paged allocation collection
 * is available under {@code /allocations}.</p>
 */
@RestController
@RequestMapping("/api/v1/seating-plans")
@RequiredArgsConstructor
public class SeatingPlanRestController {

    private final SeatingService seatingService;

    /**
     * GET /api/v1/seating-plans — current plan with a paged allocation list.
     *
     * <p>The summary fields ({@code seated}, {@code totalSlots}, ...) describe
     * the whole plan; {@code allocations} holds the requested page. The
     * pagination metadata tells the client how many pages there are.</p>
     */
    @GetMapping
    public SeatingResponse current(
            @RequestParam(required = false) String subjectCode,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        SeatingResponse full = seatingService.view(subjectCode);
        PageResponse<SeatingResponse.AllocationDto> paged =
                PageResponse.of(full.allocations(), page, size);
        return SeatingResponse.paged(full.seated(), full.unplaced(), full.totalSlots(),
                full.backtracks(), full.solved(), paged.content(),
                paged.page(), paged.size(), paged.totalElements(), paged.totalPages());
    }

    /** GET /api/v1/seating-plans/allocations — paged, filterable seat collection. */
    @GetMapping("/allocations")
    public PageResponse<SeatingResponse.AllocationDto> allocations(
            @RequestParam(required = false) String subjectCode,
            @RequestParam(required = false) String hallName,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        List<SeatingResponse.AllocationDto> allocations = seatingService.view(subjectCode).allocations();
        if (hallName != null && !hallName.isBlank()) {
            String needle = hallName.trim();
            allocations = allocations.stream()
                    .filter(a -> a.hallName() != null && a.hallName().equalsIgnoreCase(needle))
                    .toList();
        }
        return PageResponse.of(allocations, page, size);
    }

    /** POST /api/v1/seating-plans — generate and persist a new round (201 Created). */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public ResponseEntity<SeatingResponse> generate(@Valid @RequestBody ArrangeRequest request) {
        SeatingResponse response = seatingService.arrange(request);
        return ResponseEntity.created(URI.create("/api/v1/seating-plans")).body(response);
    }

    /**
     * PATCH /api/v1/seating-plans/allocations/{allocationId} — move a seated
     * student to another seat.
     *
     * <p>The new seat is checked against the anti-cheating rules of the round
     * the seat belongs to: a move onto a taken seat, or onto a seat the
     * separation rules forbid, is refused with <b>409 CONFLICT</b>.</p>
     */
    @PatchMapping("/allocations/{allocationId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public ResponseEntity<SeatingResponse.AllocationDto> move(
            @PathVariable Long allocationId,
            @Valid @RequestBody MoveAllocationRequest request) {
        return ResponseEntity.ok(seatingService.moveAllocation(allocationId, request));
    }

    /** DELETE /api/v1/seating-plans — clear one subject or the whole plan (204). */
    @DeleteMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public ResponseEntity<Void> clear(@RequestParam(required = false) String subjectCode) {
        seatingService.clear(subjectCode);
        return ResponseEntity.noContent().build();
    }
}
