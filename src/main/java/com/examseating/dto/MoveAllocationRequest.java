package com.examseating.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * JSON body of {@code PATCH /api/v1/seating-plans/allocations/{id}} — the new
 * seat of an already seated student.
 *
 * <p>The seat is addressed by its position in the hall grid, exactly like the
 * rest of the API: {@code roomId} selects the hall, {@code benchRow} and
 * {@code benchCol} the bench inside it, and {@code slot} the seat on that bench
 * (1 on a single-seater, 1 or 2 on a dual-seater).</p>
 *
 * <p>The move is refused with <b>409 CONFLICT</b> when the seat is taken or
 * when the anti-cheating rules of the round forbid the new placement.</p>
 */
public record MoveAllocationRequest(

        @NotNull(message = "roomId is required")
        Long roomId,

        @NotNull(message = "benchRow is required")
        @Min(value = 1, message = "benchRow must be >= 1")
        Integer benchRow,

        @NotNull(message = "benchCol is required")
        @Min(value = 1, message = "benchCol must be >= 1")
        Integer benchCol,

        @NotNull(message = "slot is required")
        @Min(value = 1, message = "slot must be >= 1")
        @Max(value = 2, message = "slot must be 1 or 2")
        Integer slot) {
}
