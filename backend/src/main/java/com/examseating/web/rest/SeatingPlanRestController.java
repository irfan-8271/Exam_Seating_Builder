package com.examseating.web.rest;

import com.examseating.dto.ArrangeRequest;
import com.examseating.dto.MoveAllocationRequest;
import com.examseating.dto.PageResponse;
import com.examseating.dto.SeatingResponse;
import com.examseating.engine.SeatingArrangementEngine.SeatingAdvisory;
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

/**
 * Primary v1 REST resource for seating plans ({@code /api/v1/seating-plans}).
 */
@RestController
@RequestMapping("/api/v1/seating-plans")
@RequiredArgsConstructor
public class SeatingPlanRestController {

    private final SeatingService seatingService;

    /** POST /api/v1/seating-plans/analyze — read-only pre-flight feasibility & empty-seat calculation. */
    @PostMapping("/analyze")
    public SeatingAdvisory analyze(@Valid @RequestBody(required = false) ArrangeRequest request) {
        return seatingService.analyze(request);
    }

    /** POST /api/v1/seating-plans — computes and persists a seating plan (201 Created). */
    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public ResponseEntity<SeatingResponse> create(@Valid @RequestBody ArrangeRequest request) {
        SeatingResponse plan = seatingService.arrange(request);
        return ResponseEntity.created(URI.create("/api/v1/seating-plans/current")).body(plan);
    }

    /** GET /api/v1/seating-plans/current — returns the current persisted plan. */
    @GetMapping("/current")
    public SeatingResponse current(
            @RequestParam(required = false) String scopeKey,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "200") int size) {
        SeatingResponse full = seatingService.view(scopeKey);
        PageResponse<SeatingResponse.AllocationDto> slice =
                PageResponse.of(full.allocations(), page, size);
        return SeatingResponse.paged(full.seated(), full.unplaced(), full.totalSlots(),
                full.backtracks(), full.solved(), slice.content(),
                slice.page(), slice.size(), slice.totalElements(), slice.totalPages(),
                full.advisory());
    }

    /** PATCH /api/v1/seating-plans/current/allocations/{allocationId} — validated manual seat move. */
    @PatchMapping("/current/allocations/{allocationId}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public SeatingResponse.AllocationDto moveAllocation(
            @PathVariable Long allocationId,
            @Valid @RequestBody MoveAllocationRequest request) {
        return seatingService.moveAllocation(allocationId, request);
    }

    /** DELETE /api/v1/seating-plans/current — clears allocations for one scope (or all). */
    @DeleteMapping("/current")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public ResponseEntity<Void> deleteCurrent(@RequestParam(required = false) String scopeKey) {
        seatingService.clear(scopeKey);
        return ResponseEntity.noContent().build();
    }
}
