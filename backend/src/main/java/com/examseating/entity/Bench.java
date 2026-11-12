package com.examseating.entity;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * <p>Bench seating type ({@code SINGLE_SEATER} vs {@code DUAL_SEATER}) is owned
 * strictly by {@link ExamRoom#getBenchType()} — never duplicated per bench row.</p>
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

    @JsonIgnore
    @Builder.Default
    @OneToMany(mappedBy = "bench", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SeatingAllocation> allocations = new ArrayList<>();

    /** Delegates bench seating capacity type to the owning {@link ExamRoom}. */
    public ExamRoom.BenchType getBenchType() {
        return room == null ? null : room.getBenchType();
    }
}
