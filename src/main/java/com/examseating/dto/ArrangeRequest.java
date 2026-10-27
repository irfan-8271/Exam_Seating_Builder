package com.examseating.dto;

import com.examseating.engine.SeatingArrangementEngine.BenchPairingRule;
import com.examseating.engine.SeatingArrangementEngine.ConflictScope;
import com.examseating.engine.SeatingArrangementEngine.GroupMatchMode;
import com.examseating.engine.SeatingArrangementEngine.StudentAttribute;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;

/**
 * JSON body of {@code POST /api/seating/arrange}.
 *
 * <p>Subject code is optional: when present, only that paper's students are
 * arranged and only that round is replaced; when absent, the whole roster is
 * arranged. Grouping/separation criteria are configurable via
 * {@code groupAttributes} (+ {@code groupMatchMode}); when omitted, the
 * {@code conflictScope} preset decides.</p>
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

        /** Which attributes form the conflict group key (null → derived from conflictScope). */
        List<StudentAttribute> groupAttributes,

        /** ALL = share every key attribute; ANY = share at least one (null → ALL). */
        GroupMatchMode groupMatchMode,

        String subjectCode,
        List<Long> roomIds,
        Boolean clearExisting) {

    /**
     * Difficulty presets. {@code NORMAL} and {@code EXTREME} are kept as
     * aliases of {@code BASIC} and {@code ADVANCED} for backward compatibility.
     */
    public enum StrictnessLevel {
        /** dH=1, dV=0, dD=0 — bench-mate + horizontal separation. */
        BASIC,
        /** dH=1, dV=1, dD=0 — adds row separation (staggered packing). */
        INTERMEDIATE,
        /** dH=1, dV=1, dD=1 — full multi-directional (Moore neighborhood) isolation. */
        ADVANCED,
        /** @deprecated alias of {@link #BASIC}. */
        @Deprecated
        NORMAL,
        /** @deprecated alias of {@link #ADVANCED}. */
        @Deprecated
        EXTREME
    }

    public ArrangeRequest(Integer horizontalGap, Integer verticalGap, Integer diagonalGap,
                          ConflictScope conflictScope, String subjectCode, List<Long> roomIds,
                          Boolean clearExisting) {
        this(null, horizontalGap, verticalGap, diagonalGap, conflictScope, null, null, null, null,
                subjectCode, roomIds, clearExisting);
    }

    /** Sensible defaults when the client omits fields. */
    public ArrangeRequest {
        if (strictness == null && horizontalGap == null && verticalGap == null && diagonalGap == null) {
            strictness = StrictnessLevel.BASIC;
        }
        if (benchPairingRule == null) {
            benchPairingRule = BenchPairingRule.DIFFERENT_DEPARTMENT_AND_YEAR;
        }
        if (horizontalDeptIsolation == null) {
            horizontalDeptIsolation = true;
        }
        if (groupAttributes != null && groupAttributes.isEmpty()) {
            groupAttributes = null;
        }
        if (groupAttributes != null && groupMatchMode == null) {
            groupMatchMode = GroupMatchMode.ALL;
        }
        if (clearExisting == null) clearExisting = true;
    }
}
