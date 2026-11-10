package com.examseating.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;

/**
 * A single bench in an {@link ExamRoom} grid, identified by its 1-based
 * {@code (rowIndex, colIndex)} position.
 *
 * <p>Seating capacity depends on the bench type:
 * {@code SINGLE_SEATER} → slot 1 only; {@code DUAL_SEATER} → slots 1 and 2.</p>
 */
@Entity
@Table(name = "benches",
       uniqueConstraints = @UniqueConstraint(name = "uk_benches_position",
                                             columnNames = {"room_id", "bench_row", "bench_col"}))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Bench {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    /** @JsonIgnore: inverse side of the room→benches cycle — stops Jackson recursion. */
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private ExamRoom room;

    /** 1-based bench row within the hall grid. */
    @Column(name = "bench_row", nullable = false)
    private int rowIndex;

    /** 1-based bench column within the hall grid. */
    @Column(name = "bench_col", nullable = false)
    private int colIndex;

    @Enumerated(EnumType.STRING)
    @Column(name = "bench_type", nullable = false, length = 20)
    private ExamRoom.BenchType benchType;

    /**
     * Seat allocations on this bench (1 per single-seater, up to 2 for dual-seater).
     * @JsonIgnore: lazy collection — allocations are exposed via DTOs, never nested.
     */
    @JsonIgnore
    @Builder.Default
    @OneToMany(mappedBy = "bench", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SeatingAllocation> allocations = new ArrayList<>();
}
