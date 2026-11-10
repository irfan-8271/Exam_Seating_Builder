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
 * The parameters a seating round was generated with.
 *
 * <p>Until now the gap values, conflict scope and grouping key of a round were
 * handed to the engine and then dropped: only the resulting seats were
 * persisted. That made it impossible to tell <i>why</i> a plan looks the way it
 * does, and impossible to check a hand-made change against the rules the plan
 * was actually built with. One row is kept per institution and subject scope,
 * and is overwritten by the next round of the same scope.</p>
 *
 * <p>A round over the whole roster (no subject filter) uses the empty string as
 * its {@code subjectKey}; a subject-scoped round uses the subject code. The
 * empty string keeps the unique key free of {@code NULL}s, which SQL would
 * otherwise treat as "never equal to anything".</p>
 */
@Entity
@Table(name = "seating_rounds",
       uniqueConstraints = @UniqueConstraint(name = "uk_seating_rounds_institution_subject",
                                             columnNames = {"institution_id", "subject_key"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SeatingRound implements TenantScoped {

    /** Subject key used by a round that arranges the whole roster. */
    public static final String WHOLE_ROSTER_KEY = "";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /** Owning institution; null on rows written before multi-tenancy (default institution). */
    @Column(name = "institution_id")
    private Long institutionId;

    /** The subject this round was arranged for; {@code ""} for the whole roster. */
    @Column(name = "subject_key", nullable = false, length = 20)
    private String subjectKey;

    /** Horizontal gap dH used by the round. */
    @Column(name = "horizontal_gap", nullable = false)
    private int horizontalGap;

    /** Vertical gap dV used by the round. */
    @Column(name = "vertical_gap", nullable = false)
    private int verticalGap;

    /** Diagonal gap dD used by the round. */
    @Column(name = "diagonal_gap", nullable = false)
    private int diagonalGap;

    /** Preset that fills the conflict group key when no explicit key was given. */
    @Enumerated(EnumType.STRING)
    @Column(name = "conflict_scope", nullable = false, length = 40)
    private SeatingArrangementEngine.ConflictScope conflictScope;

    /** Rule applied to the two seats of a dual-seater bench. */
    @Enumerated(EnumType.STRING)
    @Column(name = "bench_pairing_rule", nullable = false, length = 40)
    private SeatingArrangementEngine.BenchPairingRule benchPairingRule;

    /** Whether horizontally adjacent seats were forbidden to share a department. */
    @Column(name = "horizontal_dept_isolation", nullable = false)
    private boolean horizontalDeptIsolation;

    /**
     * The requested conflict group key, comma separated
     * ({@code DEPARTMENT,YEAR_SESSION}); {@code null} when the key was derived
     * from {@link #conflictScope}.
     */
    @Column(name = "group_attributes", length = 120)
    private String groupAttributes;

    /** {@code ALL} = share every key attribute; {@code ANY} = share at least one. */
    @Enumerated(EnumType.STRING)
    @Column(name = "group_match_mode", length = 20)
    private SeatingArrangementEngine.GroupMatchMode groupMatchMode;

    /** Number of students seated by the round. */
    @Column(name = "seated_count", nullable = false)
    private int seatedCount;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (updatedAt == null) {
            updatedAt = createdAt;
        }
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }

    /**
     * Rebuilds the engine configuration this round was solved with, so a later
     * change can be checked against exactly the same rules.
     */
    public SeatingArrangementEngine.SeatingConfig toSeatingConfig() {
        return new SeatingArrangementEngine.SeatingConfig(
                horizontalGap,
                verticalGap,
                diagonalGap,
                conflictScope,
                SeatingArrangementEngine.DEFAULT_MAX_BACKTRACK_STEPS,
                benchPairingRule,
                horizontalDeptIsolation,
                parseGroupAttributes(),
                groupMatchMode);
    }

    /** The requested conflict group key as a list (null when it was derived from the scope). */
    public List<SeatingArrangementEngine.StudentAttribute> parseGroupAttributes() {
        if (groupAttributes == null || groupAttributes.isBlank()) {
            return null;
        }
        List<SeatingArrangementEngine.StudentAttribute> attributes =
                Arrays.stream(groupAttributes.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(SeatingArrangementEngine.StudentAttribute::of)
                        .toList();
        return attributes.isEmpty() ? null : attributes;
    }

    /** Serialises a conflict group key for storage ({@code null} stays {@code null}). */
    public static String formatGroupAttributes(List<SeatingArrangementEngine.StudentAttribute> attributes) {
        if (attributes == null || attributes.isEmpty()) {
            return null;
        }
        return attributes.stream().map(SeatingArrangementEngine.StudentAttribute::key)
                .reduce((a, b) -> a + "," + b).orElse(null);
    }

    /** The subject key of a round for the given subject ({@code null} = whole roster). */
    public static String subjectKeyOf(String subjectCode) {
        return (subjectCode == null || subjectCode.isBlank()) ? WHOLE_ROSTER_KEY : subjectCode.trim();
    }
}
