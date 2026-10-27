package com.examseating.web;

import com.examseating.dto.ArrangeRequest;
import com.examseating.dto.SeatingResponse;
import com.examseating.service.SeatingService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Seating round endpoints — the heart of the API.
 *
 * <ul>
 *   <li>{@code POST /api/seating/arrange} — run the Step 1 engine and persist the plan
 *       (ADMIN / EXAM_CELL only).</li>
 *   <li>{@code GET /api/seating/allocations} — view the current plan from the DB
 *       (any authenticated user — invigilators need this).</li>
 *   <li>{@code DELETE /api/seating/allocations} — clear a round before re-running.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/seating")
@RequiredArgsConstructor
public class SeatingController {

    private final SeatingService seatingService;

    @PostMapping("/arrange")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public SeatingResponse arrange(@Valid @RequestBody ArrangeRequest request) {
        return seatingService.arrange(request);
    }

    @GetMapping("/allocations")
    public SeatingResponse view(@RequestParam(required = false) String subjectCode) {
        return seatingService.view(subjectCode);
    }

    @DeleteMapping("/allocations")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public void clear(@RequestParam(required = false) String subjectCode) {
        seatingService.clear(subjectCode);
    }
}
