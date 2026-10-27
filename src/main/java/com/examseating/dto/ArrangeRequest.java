package com.examseating.dto;

import com.examseating.engine.SeatingArrangementEngine.BenchPairingRule;
import com.examseating.engine.SeatingArrangementEngine.ConflictScope;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

/**
 * JSON body of {@code POST /api/seating/arrange}.
 */
public record ArrangeRequest(
        StrictnessLevel strictness,

        @Min(value = 0, message = "horizontalGap must be >= 0")
        @Max(value = 10, message = "horizontalGap too large (max 10)")
        Integer horizontalGap,

        @Min(value = 0, message = "verticalGap must be >= 0")
        @Max(value = 10, message = "verticalGap too large (max 10)")
        Integer verticalGap,

        @Min(value = 0, message = "diagonalGap must be >= 0")
        @Max(value = 10, message = "diagonalGap too large (max 10)")
        Integer diagonalGap,

        ConflictScope conflictScope,
        BenchPairingRule benchPairingRule,
        Boolean horizontalDeptIsolation,
        String subjectCode,
        List<Long> roomIds,
        Boolean clearExisting) {

    public enum StrictnessLevel {
        NORMAL,
        EXTREME
    }

    public ArrangeRequest(Integer horizontalGap, Integer verticalGap, Integer diagonalGap,
                          ConflictScope conflictScope, String subjectCode, List<Long> roomIds,
                          Boolean clearExisting) {
        this(null, horizontalGap, verticalGap, diagonalGap, conflictScope, null, null, subjectCode, roomIds, clearExisting);
    }

    /** Sensible defaults when the client omits fields. */
    public ArrangeRequest {
        if (strictness == null && horizontalGap == null && verticalGap == null && diagonalGap == null) {
            strictness = StrictnessLevel.NORMAL;
        }
        if (benchPairingRule == null) {
            benchPairingRule = BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR;
        }
        if (horizontalDeptIsolation == null) {
            horizontalDeptIsolation = true;
        }
        if (clearExisting == null) clearExisting = true;
    }
}
