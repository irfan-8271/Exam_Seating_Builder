package com.examseating.repository;

import com.examseating.entity.Bench;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link Bench}.
 *
 * <p>Benches are usually reached through their room ({@code room.getBenches()}), but a
 * dedicated repository is convenient for bulk operations and for lookups like the one
 * below (used when rebuilding a hall layout).</p>
 */
public interface BenchRepository extends JpaRepository<Bench, Long> {

    /** All benches of one hall, in row-major order. */
    List<Bench> findByRoomIdOrderByRowIndexAscColIndexAsc(Long roomId);
}
