package com.examseating.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * One thing that happened, for the platform administrator's activity feed.
 *
 * <p>The point of this table is that routine administration no longer needs
 * the database: the operator can see who registered, who signed in, what was
 * generated and which limits were changed, without opening a SQL client.</p>
 *
 * <p>It is a plain append-only log — no foreign keys, so an account that is
 * removed leaves its history behind.</p>
 */
@Entity
@Table(name = "activity_log")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ActivityLog {

    /** What happened. */
    public enum Type {
        REGISTRATION,
        SIGN_IN,
        SIGN_OUT,
        GENERATION,
        PLAN_CHANGE,
        LIMIT_CHANGE,
        USER_CHANGE,
        ACCOUNT_SUSPENDED,
        ACCOUNT_RESTORED,
        UPGRADE_REQUESTED,
        UPGRADE_APPROVED,
        UPGRADE_REJECTED,
        PAYMENT_CONFIG_UPDATED,
        PLAN_CATALOG_UPDATED,
        /**
         * An administrator removed stored files or request screenshots to
         * reclaim database space.
         */
        STORAGE_CLEANUP
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "user_id")
    private Long userId;

    @Column(length = 50)
    private String username;

    @Column(name = "institution_id")
    private Long institutionId;

    @Column(name = "institution_name", length = 120)
    private String institutionName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private Type type;

    @Column(length = 400)
    private String detail;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @PrePersist
    void onCreate() {
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
    }
}
