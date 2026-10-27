package com.examseating.entity;

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
 * Links one student to one seat on a {@link Bench} in an {@link ExamRoom}.
 *
 * <ul>
 *   <li>{@code slotNumber = 1} on a SINGLE_SEATER bench — the only seat.</li>
 *   <li>{@code slotNumber = 1 | 2} on a DUAL_SEATER bench.</li>
 * </ul>
 *
 * <p>The engine returns transient (unsaved) instances; the service layer
 * (Step 2) persists them. The unique key is {@code (bench_id, slot_number)}.</p>
 */
@Entity
@Table(name = "seating_allocations",
       uniqueConstraints = @UniqueConstraint(name = "uk_seating_allocations_bench_slot",
                                             columnNames = {"bench_id", "slot_number"}),
       indexes = @Index(name = "idx_seating_allocations_student", columnList = "student_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class SeatingAllocation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "student_id", nullable = false)
    private Student student;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bench_id", nullable = false)
    private Bench bench;

    /** Seat slot: 1 for single-seater benches; 1 or 2 for dual-seater benches. */
    @Column(name = "slot_number", nullable = false)
    private int slotNumber;

    /** Denormalized snapshot of {@link Student#getSubjectCode()} for export queries. */
    @Column(name = "subject_code", length = 20)
    private String subjectCode;

    @Column(name = "allocated_at", nullable = false, updatable = false)
    private Instant allocatedAt;

    @PrePersist
    void onCreate() {
        if (allocatedAt == null) {
            allocatedAt = Instant.now();
        }
    }
}
