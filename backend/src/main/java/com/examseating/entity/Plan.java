package com.examseating.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

/**
 * A sellable subscription tier. Limits are deliberately plain columns so the
 * quota service can enforce them without a separate billing service.
 */
@Entity
@Table(name = "plans",
       uniqueConstraints = @UniqueConstraint(name = "uk_plans_code", columnNames = "code"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class Plan {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "monthly_price", nullable = false, precision = 10, scale = 2)
    private BigDecimal monthlyPrice;

    @Column(name = "max_students", nullable = false)
    private int maxStudents;

    @Column(name = "max_rooms", nullable = false)
    private int maxRooms;

    @Column(name = "max_generations_per_month", nullable = false)
    private int maxGenerationsPerMonth;

    @Column(name = "storage_limit_mb", nullable = false)
    private long storageLimitMb;

    @Column(name = "max_file_size_mb", nullable = false)
    private long maxFileSizeMb;

    @Column(name = "seats_per_generation", nullable = false)
    private int seatsPerGeneration;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active;
}
