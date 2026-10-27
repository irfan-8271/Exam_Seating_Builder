package com.examseating.repository;

import com.examseating.entity.SeatingAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Spring Data JPA repository for {@link SeatingAllocation}.
 *
 * <p>This repository shows the two ways to write non-trivial queries:</p>
 * <ul>
 *   <li><b>Derived query</b> — method name is parsed into JPQL:
 *       {@code deleteBySubjectCode} → {@code delete from SeatingAllocation where subjectCode = ?}.</li>
 *   <li><b>Explicit {@code @Query}</b> — you write JPQL yourself (Hibernate then compiles
 *       it to SQL like always). Needed for {@code JOIN FETCH} and bulk
 *       {@code @Modifying} statements.</li>
 * </ul>
 *
 * <p><b>Why {@code JOIN FETCH} here:</b> every allocation references a Student, a Bench
 * and a Room (all lazy). Export code would otherwise trigger N+1 SELECTs. Fetching all
 * three in one query also lets us safely map to DTOs outside a transaction.</p>
 */
public interface SeatingAllocationRepository extends JpaRepository<SeatingAllocation, Long> {

    /**
     * All allocations (optionally for one subject), with student/bench/room loaded
     * eagerly, deterministically ordered for exports.
     *
     * <p>{@code :subjectCode is null or a.subjectCode = :subjectCode} is a neat JPQL trick
     * for an optional filter: pass {@code null} to get everything.</p>
     */
    @Query("""
            select a from SeatingAllocation a
            join fetch a.student
            join fetch a.bench b
            join fetch b.room
            where (:subjectCode is null or a.subjectCode = :subjectCode)
            order by b.room.hallName, b.rowIndex, b.colIndex, a.slotNumber
            """)
    List<SeatingAllocation> findAllWithDetails(@Param("subjectCode") String subjectCode);

    /**
     * Bulk deletes. {@code @Transactional} on the interface method makes Spring Data
     * open a transaction around the call, so these can be invoked from anywhere
     * (services and controllers) — without it Hibernate throws
     * TransactionRequiredException for update/delete queries.
     */
    @Modifying
    @Transactional
    @Query("delete from SeatingAllocation a where a.subjectCode = :subjectCode")
    int deleteBySubjectCode(@Param("subjectCode") String subjectCode);

    @Modifying
    @Transactional
    @Query("delete from SeatingAllocation a where a.bench.id in (select b.id from Bench b where b.room.id = :roomId)")
    int deleteByBenchRoomId(@Param("roomId") Long roomId);

    List<SeatingAllocation> findBySubjectCode(String subjectCode);

    long countBySubjectCode(String subjectCode);
}
