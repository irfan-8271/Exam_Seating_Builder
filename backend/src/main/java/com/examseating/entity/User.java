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
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * An account of the service.
 *
 * <p>Every account belongs to exactly one {@link Institution} — a workspace
 * that owns the students, the halls and the seating plans. The institution is
 * the unit of data; the account is the unit of access.</p>
 *
 * <p>Roles:</p>
 * <ul>
 *   <li>{@code ADMIN} — runs the workspace: manages its members, its roster,
 *       its halls and its plan;</li>
 *   <li>{@code EXAM_CELL} — prepares the exam: roster and seating plans;</li>
 *   <li>{@code INVIGILATOR} — reads the plan and the exports;</li>
 *   <li>{@code PLATFORM_ADMIN} — the operator of the service as a whole. Not a
 *       customer account: it signs in through the administration endpoint and
 *       never appears in an institution's member list.</li>
 * </ul>
 *
 * <p>The password column stores a BCrypt hash. It is never read into a view,
 * a DTO or a log — the operator's screens show accounts, never credentials.</p>
 */
@Entity
@Table(name = "users",
       uniqueConstraints = @UniqueConstraint(name = "uk_users_username", columnNames = "username"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class User implements TenantScoped {

    public enum Role {
        ADMIN,
        EXAM_CELL,
        INVIGILATOR,
        /**
         * The platform administrator — the operator of the service as a
         * whole, not of one institution. May see every institution and user
         * and change any institution's subscription and limits.
         */
        PLATFORM_ADMIN
    }

    /** Whether the account may sign in. */
    public enum Status {
        ACTIVE,
        SUSPENDED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /** Owning institution; null for legacy single-tenant accounts. */
    @Column(name = "institution_id")
    private Long institutionId;

    @Column(nullable = false, length = 50)
    private String username;

    /** BCrypt hash — never a plain-text password. */
    @Column(nullable = false, length = 100)
    private String password;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Role role;

    /**
     * Whether the account may sign in. A suspended account keeps its data
     * and its place in the member list; it simply cannot get in.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20)
    @Builder.Default
    private Status status = Status.ACTIVE;

    @Column(name = "full_name", length = 100)
    private String fullName;

    @Column(length = 120)
    private String email;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** When this account last signed in — the operator's "who is active" view. */
    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    /** When the password was last set or reset by an administrator. */
    @Column(name = "password_changed_at")
    private Instant passwordChangedAt;

    // ------------------------------------------------------------------
    // The limits that apply to this account alone.
    //
    // The limits in force are resolved in three steps: the plan says what an
    // institution may do, the institution may override any single limit, and
    // an account may override it again for itself alone. The most specific
    // value wins; a null field means "whatever the level above says".
    //
    // These columns mirror the institution's own overrides on Subscription.
    // ------------------------------------------------------------------

    /** Overrides the student limit for this account. */
    @Column(name = "max_students_override")
    private Integer maxStudentsOverride;

    /** Overrides the hall limit for this account. */
    @Column(name = "max_rooms_override")
    private Integer maxRoomsOverride;

    /** Overrides the monthly generation limit for this account. */
    @Column(name = "max_generations_override")
    private Integer maxGenerationsOverride;

    /** Overrides the storage limit (MB) for this account. */
    @Column(name = "storage_limit_mb_override")
    private Long storageLimitMbOverride;

    /** Overrides the largest accepted upload (MB) for this account. */
    @Column(name = "max_file_size_mb_override")
    private Long maxFileSizeMbOverride;

    /** Overrides the seats-per-generation limit for this account. */
    @Column(name = "seats_per_generation_override")
    private Integer seatsPerGenerationOverride;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
        if (status == null) {
            status = Status.ACTIVE;
        }
        if (passwordChangedAt == null) {
            passwordChangedAt = createdAt;
        }
    }

    /** The status as it should be read — a row written before the column existed counts as active. */
    public Status effectiveStatus() {
        return status == null ? Status.ACTIVE : status;
    }

    /** Whether this account has an override of its own. */
    public boolean hasLimitOverrides() {
        return maxStudentsOverride != null || maxRoomsOverride != null
                || maxGenerationsOverride != null || storageLimitMbOverride != null
                || maxFileSizeMbOverride != null || seatsPerGenerationOverride != null;
    }
}
