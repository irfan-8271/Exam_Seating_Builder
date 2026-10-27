package com.examseating.entity;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
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
 * An exam hall configured as an {@code M rows × N columns} grid of benches.
 *
 * <p>Every bench in the room follows the room's {@link BenchType}:
 * {@code SINGLE_SEATER} (1 student) or {@code DUAL_SEATER} (2 students,
 * slot 1 / slot 2).</p>
 *
 * <p>Bench coordinates are 1-based: row 1..M, column 1..N.</p>
 */
@Entity
@Table(name = "exam_rooms",
       uniqueConstraints = @UniqueConstraint(name = "uk_exam_rooms_hall_name", columnNames = "hall_name"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class ExamRoom {

    public enum BenchType {
        SINGLE_SEATER(1),
        DUAL_SEATER(2);

        private final int seatsPerBench;

        BenchType(int seatsPerBench) {
            this.seatsPerBench = seatsPerBench;
        }

        public int getSeatsPerBench() {
            return seatsPerBench;
        }
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @jakarta.validation.constraints.NotBlank(message = "Hall name is required")
    @jakarta.validation.constraints.Size(max = 100)
    @Column(name = "hall_name", nullable = false, length = 100)
    private String hallName;

    /** Number of bench rows in the hall grid (M). DB column renamed: "rows" is a reserved word. */
    @jakarta.validation.constraints.Min(value = 1, message = "Rows must be at least 1")
    @jakarta.validation.constraints.Max(value = 500)
    @Column(name = "num_rows", nullable = false)
    private int rows;

    /** Number of bench columns in the hall grid (N). DB column renamed: "columns" is a reserved word. */
    @jakarta.validation.constraints.Min(value = 1, message = "Columns must be at least 1")
    @jakarta.validation.constraints.Max(value = 500)
    @Column(name = "num_cols", nullable = false)
    private int columns;

    @jakarta.validation.constraints.NotNull(message = "Bench type is required")
    @Enumerated(EnumType.STRING)
    @Column(name = "bench_type", nullable = false, length = 20)
    private BenchType benchType;

    /** The M×N bench grid of this hall (row-major, 1-based coordinates). */
    @Builder.Default
    @OneToMany(mappedBy = "room", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("rowIndex ASC, colIndex ASC")
    private List<Bench> benches = new ArrayList<>();

    /** Total number of benches in the hall grid: M × N. */
    public int getTotalBenches() {
        return rows * columns;
    }

    /** Maximum number of students this hall can seat. */
    public int getCapacity() {
        return rows * columns * benchType.getSeatsPerBench();
    }
}
