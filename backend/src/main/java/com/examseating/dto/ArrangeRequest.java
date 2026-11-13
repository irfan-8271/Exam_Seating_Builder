package com.examseating.dto;

import com.examseating.engine.SeatingArrangementEngine.GroupMatchMode;
import com.examseating.engine.SeatingArrangementEngine.StudentAttribute;
import com.examseating.entity.StudentAttributes;
import com.fasterxml.jackson.annotation.JsonCreator;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * JSON body of {@code POST /api/v1/seating-plans} and {@code POST /api/v1/seating-plans/analyze}.
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
        Boolean clearExisting,

        /**
         * Optional dynamic attribute key (or '+'-joined composite key such as
         * {@code "department+semester"}) used to include only selected student
         * categories/groups in pre-flight analysis and seating generation.
         */
        String filterAttribute,

        /**
         * Selected category values for {@code filterAttribute}. When non-empty,
         * only students matching one of these values are included; null/empty
         * includes all students.
         */
        List<String> filterValues) {

    /** Backward-compatible 10-argument constructor for existing callers and tests. */
    public ArrangeRequest(
            StrictnessLevel strictness,
            Integer horizontalGap,
            Integer verticalGap,
            Integer diagonalGap,
            List<StudentAttribute> groupAttributes,
            GroupMatchMode groupMatchMode,
            String scopeAttribute,
            String scopeValue,
            List<Long> roomIds,
            Boolean clearExisting) {
        this(strictness, horizontalGap, verticalGap, diagonalGap,
                groupAttributes, groupMatchMode, scopeAttribute, scopeValue,
                roomIds, clearExisting, null, null);
    }

    /**
     * Canonical seating difficulty / restriction levels across the application:
     * <ul>
     *   <li>{@link #NORMAL} — Horizontal adjacent separation ({@code dH=1, dV=0, dD=0}).</li>
     *   <li>{@link #INTERMEDIATE} — Horizontal + front/back vertical separation ({@code dH=1, dV=1, dD=0}).</li>
     *   <li>{@link #EXTREME} — Extreme / Rigid full 8-way surrounding separation ({@code dH=1, dV=1, dD=1});
     *       accepts {@code HARD} and {@code RIGID} as JSON aliases.</li>
     * </ul>
     */
    public enum StrictnessLevel {
        /** dH=1, dV=0, dD=0 — horizontal separation + front-to-back column continuity. */
        NORMAL,
        /** dH=1, dV=1, dD=0 — horizontal + front-to-back row separation. */
        INTERMEDIATE,
        /** dH=1, dV=1, dD=1 — full 8-way (horizontal + vertical + diagonal) separation. */
        EXTREME;

        @JsonCreator
        public static StrictnessLevel fromJson(String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String normalized = raw.trim().toUpperCase(Locale.ROOT);
            return switch (normalized) {
                case "NORMAL", "BASIC" -> NORMAL;
                case "INTERMEDIATE", "MEDIUM" -> INTERMEDIATE;
                case "EXTREME", "HARD", "RIGID", "ADVANCED" -> EXTREME;
                default -> throw new IllegalArgumentException(
                        "Invalid strictness '" + raw + "' — accepted values: NORMAL, INTERMEDIATE, EXTREME (aliases: HARD, RIGID)");
            };
        }
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
        if (filterAttribute != null) {
            String normalizedFilter = Arrays.stream(filterAttribute.split("\\+"))
                    .map(StudentAttributes::normalize)
                    .filter(k -> !k.isEmpty())
                    .collect(Collectors.joining("+"));
            filterAttribute = normalizedFilter.isEmpty() ? null : normalizedFilter;
        }
        if (filterValues != null) {
            filterValues = filterValues.stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(s -> !s.isEmpty())
                    .distinct()
                    .toList();
            if (filterValues.isEmpty()) {
                filterValues = null;
            }
        }
    }
}
