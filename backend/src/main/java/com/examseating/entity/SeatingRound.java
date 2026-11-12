package com.examseating.entity;

import com.examseating.engine.SeatingArrangementEngine;
import com.examseating.tenancy.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Persisted parameters of one generated seating round, enabling exact rule
 * reconstruction for manual seat moves, UI inspection, and exports.
 *
 * <p>No university-specific conflict presets are stored: every round records
 * its exact geometric separation gaps ({@code dH, dV, dD}), canonical
 * {@link #strictness}, dynamic {@link #groupAttributes}, {@link #groupMatchMode},
 * and optional {@link #scopeKey} / {@link #scopeAttribute}.</p>
 */
@Entity
@Table(name = "seating_rounds",
       uniqueConstraints = @UniqueConstraint(name = "uk_seating_rounds_institution_scope",
                                             columnNames = {"institution_id", "scope_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SeatingRound implements TenantScoped {

    /** Scope key used when a round arranges the entire roster. */
    public static final String WHOLE_ROSTER_KEY = "";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "institution_id")
    private Long institutionId;

    /** Scope value for this round (e.g. exam date or paper code), or {@code ""} for whole roster. */
    @Column(name = "scope_key", nullable = false, length = 60)
    private String scopeKey;

    /** Dynamic attribute key that {@link #scopeKey} filtered on (e.g. {@code "examdate"} or {@code "paper"}). */
    @Column(name = "scope_attribute", length = 64)
    private String scopeAttribute;

    /** Canonical difficulty label ({@code NORMAL}, {@code INTERMEDIATE}, {@code HARD}, or {@code CUSTOM}). */
    @Column(name = "strictness", length = 32)
    private String strictness;

    @Column(name = "horizontal_gap", nullable = false)
    private int horizontalGap;

    @Column(name = "vertical_gap", nullable = false)
    private int verticalGap;

    @Column(name = "diagonal_gap", nullable = false)
    private int diagonalGap;

    /** Comma-separated normalized classmate attribute keys separated in this round. */
    @Column(name = "group_attributes", length = 512)
    private String groupAttributes;

    /** {@code ALL} = classmates share all group attributes; {@code ANY} = share at least one. */
    @Enumerated(EnumType.STRING)
    @Column(name = "group_match_mode", length = 20)
    private SeatingArrangementEngine.GroupMatchMode groupMatchMode;

    @Column(name = "seated_count", nullable = false)
    private int seatedCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (scopeKey == null) {
            scopeKey = WHOLE_ROSTER_KEY;
        }
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    void onUpdate() {
        if (scopeKey == null) {
            scopeKey = WHOLE_ROSTER_KEY;
        }
        updatedAt = Instant.now();
    }

    /** Reconstructs the exact {@link SeatingArrangementEngine.SeatingConfig} used to solve this round. */
    public SeatingArrangementEngine.SeatingConfig toSeatingConfig() {
        return new SeatingArrangementEngine.SeatingConfig(
                horizontalGap,
                verticalGap,
                diagonalGap,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                parseGroupAttributes(),
                groupMatchMode);
    }

    public List<SeatingArrangementEngine.StudentAttribute> parseGroupAttributes() {
        if (groupAttributes == null || groupAttributes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(groupAttributes.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .map(SeatingArrangementEngine.StudentAttribute::of)
                .toList();
    }

    public static String formatGroupAttributes(List<SeatingArrangementEngine.StudentAttribute> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        return attributes.stream()
                .map(SeatingArrangementEngine.StudentAttribute::key)
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
    }

    public static String scopeKeyOf(String rawScope) {
        return (rawScope == null || rawScope.isBlank()) ? WHOLE_ROSTER_KEY : rawScope.trim();
    }
}
