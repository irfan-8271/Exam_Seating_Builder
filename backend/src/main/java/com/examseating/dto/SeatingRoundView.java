package com.examseating.dto;

import com.examseating.engine.SeatingArrangementEngine;
import com.examseating.entity.SeatingRound;

import java.time.Instant;
import java.util.List;

/**
 * Read-only view of the parameters a seating round was solved with.
 */
public record SeatingRoundView(
        String scopeKey,
        String scopeAttribute,
        String strictness,
        int horizontalGap,
        int verticalGap,
        int diagonalGap,
        List<String> groupAttributes,
        String groupMatchMode,
        int seatedCount,
        Integer targetCount,
        String filterAttribute,
        List<String> filterValues,
        Instant createdAt) {

    public static SeatingRoundView from(SeatingRound round) {
        if (round == null) {
            return null;
        }
        List<String> attributes = round.parseGroupAttributes().stream()
                .map(SeatingArrangementEngine.StudentAttribute::key)
                .toList();
        String key = round.getScopeKey();
        boolean wholeRoster = key == null || key.isBlank() || SeatingRound.WHOLE_ROSTER_KEY.equals(key);
        String strictness = round.getStrictness();
        if (strictness == null || strictness.isBlank()) {
            strictness = round.getDiagonalGap() > 0 ? "HARD"
                    : (round.getVerticalGap() > 0 ? "INTERMEDIATE" : "NORMAL");
        }
        List<String> parsedFilterVals = (round.getFilterValuesSummary() == null || round.getFilterValuesSummary().isBlank())
                ? List.of()
                : java.util.Arrays.stream(round.getFilterValuesSummary().split("\\|\\|"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .toList();
        return new SeatingRoundView(
                wholeRoster ? null : key,
                round.getScopeAttribute(),
                strictness,
                round.getHorizontalGap(),
                round.getVerticalGap(),
                round.getDiagonalGap(),
                attributes,
                round.getGroupMatchMode() == null ? "ALL" : round.getGroupMatchMode().name(),
                round.getSeatedCount(),
                round.getTargetCount(),
                round.getFilterAttribute(),
                parsedFilterVals,
                round.getCreatedAt());
    }
}
