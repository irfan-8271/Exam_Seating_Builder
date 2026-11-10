package com.examseating.web.rest;

import com.examseating.dto.SeatingRoundView;
import com.examseating.entity.SeatingRound;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.repository.SeatingRoundRepository;
import com.examseating.tenancy.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * The seating rounds of this institution and the rules they were solved with.
 *
 * <p>Each round is recorded when it is arranged, so the question "which rules
 * produced this plan?" always has an answer — and a seat that is changed by
 * hand afterwards is checked against exactly those rules.</p>
 */
@RestController
@RequestMapping("/api/v1/seating-rounds")
@RequiredArgsConstructor
public class SeatingRoundRestController {

    private final SeatingRoundRepository roundRepository;
    private final TenantSupport tenant;

    /**
     * GET /api/v1/seating-rounds — every recorded round of this institution,
     * one per subject scope.
     */
    @GetMapping
    public List<SeatingRoundView> list() {
        return tenant.visible(roundRepository.findAll()).stream()
                .map(SeatingRoundView::from)
                .toList();
    }

    /**
     * GET /api/v1/seating-rounds/current — the round that produced the current
     * plan.
     *
     * @param subjectCode the subject scope; omit it for the whole-roster round
     */
    @GetMapping("/current")
    public SeatingRoundView current(@RequestParam(required = false) String subjectCode) {
        String key = SeatingRound.subjectKeyOf(subjectCode);
        Optional<SeatingRound> found =
                roundRepository.findByInstitutionIdAndSubjectKey(tenant.currentId(), key);
        if (found.isEmpty() && subjectCode == null) {
            // No whole-roster round: the newest round is what the
            // institution arranged last.
            found = roundRepository
                    .findByInstitutionIdOrderByUpdatedAtDesc(tenant.currentId())
                    .stream()
                    .findFirst();
        }
        SeatingRound round = found.orElseThrow(() -> new ResourceNotFoundException(
                "No seating round has been recorded for "
                        + (subjectCode == null || subjectCode.isBlank()
                        ? "the whole roster" : "subject '" + subjectCode + "'")));
        return SeatingRoundView.from(round);
    }
}
