package com.examseating.entity;

import com.examseating.tenancy.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
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
 * Links one {@link Student} to one seat slot on a {@link Bench}.
 *
 * <ul>
 *   <li>{@code slotNumber = 1} on a {@code SINGLE_SEATER} bench.</li>
 *   <li>{@code slotNumber = 1 | 2} on a {@code DUAL_SEATER} bench.</li>
 * </ul>
 */
@Entity
@Table(name = "seating_allocations",
       uniqueConstraints = @UniqueConstraint(name = "uk_seating_allocations_bench_slot",
                                             columnNames = {"bench_id", "slot_number"}),
       indexes = {
               @Index(name = "idx_seating_allocations_student", columnList = "student_id"),
               @Index(name = "idx_seating_allocations_scope", columnList = "institution_id, scope_key")
       })
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SeatingAllocation implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "institution_id")
    private Long institutionId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bench_id", nullable = false)
    private Bench bench;

    /** Seat slot: 1 for single-seater benches; 1 or 2 for dual-seater benches. */
    @Column(name = "slot_number", nullable = false)
    private int slotNumber;

    /**
     * Optional exam round scope value (e.g. an exam date {@code "2026-11-15"}
     * or paper code {@code "MATH101"}) when multiple rounds share physical halls;
     * {@code null} when the round covers the whole roster.
     */
    @Column(name = "scope_key", length = 60)
    private String scopeKey;

    @Column(name = "allocated_at", nullable = false, updatable = false)
    private Instant allocatedAt;

    @PrePersist
    void onCreate() {
        if (allocatedAt == null) {
            allocatedAt = Instant.now();
        }
    }
}
