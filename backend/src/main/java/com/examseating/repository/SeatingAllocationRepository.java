package com.examseating.repository;

import com.examseating.entity.SeatingAllocation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Spring Data JPA repository for {@link SeatingAllocation}. */
public interface SeatingAllocationRepository extends JpaRepository<SeatingAllocation, Long> {

    @Query("""
            select a from SeatingAllocation a
            join fetch a.student
            join fetch a.bench b
            join fetch b.room
            where (:scopeKey is null or a.scopeKey = :scopeKey)
            order by b.room.hallName, b.rowIndex, b.colIndex, a.slotNumber
            """)
    List<SeatingAllocation> findAllWithDetails(@Param("scopeKey") String scopeKey);

    @Modifying
    @Transactional
    @Query("delete from SeatingAllocation a where a.scopeKey = :scopeKey")
    int deleteByScopeKey(@Param("scopeKey") String scopeKey);

    @Modifying
    @Transactional
    @Query("delete from SeatingAllocation a where a.bench.id in (select b.id from Bench b where b.room.id = :roomId)")
    int deleteByBenchRoomId(@Param("roomId") Long roomId);

    List<SeatingAllocation> findByScopeKey(String scopeKey);

    @Query("""
            select a from SeatingAllocation a
            join fetch a.student
            join fetch a.bench b
            join fetch b.room
            where a.id = :id
            """)
    Optional<SeatingAllocation> findByIdWithDetails(@Param("id") Long id);

    @Query("""
            select a from SeatingAllocation a
            join fetch a.student
            join fetch a.bench b
            join fetch b.room
            where b.room.id = :roomId
            """)
    List<SeatingAllocation> findByBenchRoomIdWithDetails(@Param("roomId") Long roomId);
}
