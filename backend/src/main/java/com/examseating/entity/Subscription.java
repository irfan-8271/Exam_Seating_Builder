package com.examseating.entity;

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

/** Current plan for one institution, including the active billing period. */
@Entity
@Table(name = "subscriptions",
       uniqueConstraints = @UniqueConstraint(name = "uk_subscriptions_institution", columnNames = "institution_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Subscription implements TenantScoped {

    public enum Status {
        ACTIVE,
        PAST_DUE,
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "institution_id", nullable = false)
    private Long institutionId;

    @Column(name = "plan_code", nullable = false, length = 30)
    private String planCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    @Column(name = "period_start", nullable = false)
    private Instant periodStart;

    @Column(name = "period_end", nullable = false)
    private Instant periodEnd;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    // ------------------------------------------------------------------
    // Limit overrides
    //
    // A plan says what an institution may do. An operator may grant more
    // (or less) for one institution without inventing a new plan: an
    // override replaces the plan's value for that one limit. A null
    // override means "whatever the plan says".
    // ------------------------------------------------------------------

    /** Overrides {@link Plan#getMaxStudents()} when set. */
    @Column(name = "max_students_override")
    private Integer maxStudentsOverride;

    /** Overrides {@link Plan#getMaxRooms()} when set. */
    @Column(name = "max_rooms_override")
    private Integer maxRoomsOverride;

    /** Overrides {@link Plan#getMaxGenerationsPerMonth()} when set. */
    @Column(name = "max_generations_override")
    private Integer maxGenerationsOverride;

    /** Overrides {@link Plan#getStorageLimitMb()} when set. */
    @Column(name = "storage_limit_mb_override")
    private Long storageLimitMbOverride;

    /** Overrides {@link Plan#getMaxFileSizeMb()} when set. */
    @Column(name = "max_file_size_mb_override")
    private Long maxFileSizeMbOverride;

    /** Overrides {@link Plan#getSeatsPerGeneration()} when set. */
    @Column(name = "seats_per_generation_override")
    private Integer seatsPerGenerationOverride;

    @PrePersist
    void onCreate() {
        Instant now = Instant.now();
        if (startedAt == null) startedAt = now;
        if (periodStart == null) periodStart = now;
        if (periodEnd == null) periodEnd = now.plusSeconds(30L * 24 * 60 * 60);
        if (status == null) status = Status.ACTIVE;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
