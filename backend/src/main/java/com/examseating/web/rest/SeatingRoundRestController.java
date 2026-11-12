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
 * Primary v1 REST resource for persisted seating rounds ({@code /api/v1/seating-rounds}).
 */
@RestController
@RequestMapping("/api/v1/seating-rounds")
@RequiredArgsConstructor
public class SeatingRoundRestController {

    private final SeatingRoundRepository roundRepository;
    private final TenantSupport tenant;

    @GetMapping
    public List<SeatingRoundView> list() {
        return tenant.visible(roundRepository.findAll()).stream()
                .map(SeatingRoundView::from)
                .toList();
    }

    @GetMapping("/current")
    public SeatingRoundView current(@RequestParam(required = false) String scopeKey) {
        String key = SeatingRound.scopeKeyOf(scopeKey);
        Optional<SeatingRound> found =
                roundRepository.findByInstitutionIdAndScopeKey(tenant.currentId(), key);
        if (found.isEmpty() && (scopeKey == null || scopeKey.isBlank())) {
            found = roundRepository
                    .findByInstitutionIdOrderByUpdatedAtDesc(tenant.currentId())
                    .stream()
                    .findFirst();
        }
        SeatingRound round = found.orElseThrow(() -> new ResourceNotFoundException(
                "No seating round has been recorded for "
                        + (scopeKey == null || scopeKey.isBlank()
                        ? "the whole roster" : "scope '" + scopeKey + "'")));
        return SeatingRoundView.from(round);
    }
}
