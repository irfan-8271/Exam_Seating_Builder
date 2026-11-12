package com.examseating.dto;

import com.examseating.engine.SeatingArrangementEngine.GroupMatchMode;
import com.examseating.engine.SeatingArrangementEngine.StudentAttribute;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.List;
import java.util.Objects;

/**
 * JSON body of {@code POST /api/v1/seating-plans}.
 *
 * <p>Pure dynamic contract: classmate separation is driven by the institution's
 * configured {@code classmateAttributes} or explicit {@code groupAttributes},
 * and optional multi-round hall sharing is driven by {@code (scopeAttribute, scopeValue)}
 * (for example {@code ("examdate", "2026-11-15")} or {@code ("paper", "MATH101")}).</p>
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

        /** Dynamic attributes defining classmates/cohorts to separate; defaults to institution scheme. */
        List<StudentAttribute> groupAttributes,

        /** ALL = share every attribute (classmates); ANY = share at least one attribute. */
        GroupMatchMode groupMatchMode,

        /** Optional attribute key used to filter students for this round (e.g. "examdate", "paper"). */
        String scopeAttribute,

        /** Optional attribute value for this round (e.g. "2026-11-15", "MATH101"). */
        String scopeValue,

        /** Optional subset of hall IDs to seat into; null/empty uses all halls. */
        List<Long> roomIds,

        /** Whether to clear existing allocations before solving (default true). */
        Boolean clearExisting) {

    /** Canonical difficulty levels. */
    public enum StrictnessLevel {
        /** dH=1, dV=0, dD=0 — horizontal separation + front-to-back column continuity. */
        NORMAL,
        /** dH=1, dV=1, dD=0 — horizontal + front-to-back row separation. */
        INTERMEDIATE,
        /** dH=1, dV=1, dD=1 — full 8-way (horizontal + vertical + diagonal) separation. */
        HARD
    }

    public ArrangeRequest {
        if (strictness == null && horizontalGap == null && verticalGap == null && diagonalGap == null) {
            strictness = StrictnessLevel.NORMAL;
        }
        if (groupAttributes != null) {
            if (groupAttributes.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("groupAttributes must not contain null entries");
            }
            if (groupAttributes.isEmpty()) {
                groupAttributes = null;
            }
        }
        if (groupMatchMode == null) {
            groupMatchMode = GroupMatchMode.ALL;
        }
        if (clearExisting == null) {
            clearExisting = true;
        }
    }
}
