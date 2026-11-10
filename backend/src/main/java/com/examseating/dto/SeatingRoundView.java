package com.examseating.dto;

import com.examseating.entity.SeatingRound;

import java.time.Instant;
import java.util.List;

/**
 * The parameters a seating round was solved with.
 *
 * <p>A round records the rules it was built under, so a seat that is changed
 * by hand afterwards can be checked against exactly those rules — and so the
 * institution can see how a plan came about.</p>
 *
 * @param subjectKey            the subject scope; {@code ""} for a whole-roster round
 * @param subjectCode           the subject code, or null for a whole-roster round
 * @param horizontalGap         dH used by the round
 * @param verticalGap           dV used by the round
 * @param diagonalGap           dD used by the round
 * @param conflictScope         the scope preset
 * @param benchPairingRule      the bench-mate rule
 * @param horizontalDeptIsolation whether horizontal department isolation was on
 * @param groupAttributes       the conflict group key, as attribute names
 * @param groupMatchMode        ALL or ANY
 * @param seatedCount           how many students the round seated
 * @param createdAt             when the round was recorded
 */
public record SeatingRoundView(
        String subjectKey,
        String subjectCode,
        int horizontalGap,
        int verticalGap,
        int diagonalGap,
        String conflictScope,
        String benchPairingRule,
        boolean horizontalDeptIsolation,
        List<String> groupAttributes,
        String groupMatchMode,
        int seatedCount,
        Instant createdAt) {

    /** Maps a stored round to its view. */
    public static SeatingRoundView from(SeatingRound round) {
        if (round == null) {
            return null;
        }
        List<String> attributes = round.parseGroupAttributes() == null
                ? List.of()
                : round.parseGroupAttributes().stream()
                        .map(com.examseating.engine.SeatingArrangementEngine.StudentAttribute::key)
                        .toList();
        String subjectKey = round.getSubjectKey();
        boolean wholeRoster = subjectKey == null || subjectKey.isBlank()
                || SeatingRound.WHOLE_ROSTER_KEY.equals(subjectKey);
        return new SeatingRoundView(
                subjectKey,
                wholeRoster ? null : subjectKey,
                round.getHorizontalGap(),
                round.getVerticalGap(),
                round.getDiagonalGap(),
                round.getConflictScope() == null ? null : round.getConflictScope().name(),
                round.getBenchPairingRule() == null ? null : round.getBenchPairingRule().name(),
                round.isHorizontalDeptIsolation(),
                attributes,
                round.getGroupMatchMode() == null ? null : round.getGroupMatchMode().name(),
                round.getSeatedCount(),
                round.getCreatedAt());
    }
}
