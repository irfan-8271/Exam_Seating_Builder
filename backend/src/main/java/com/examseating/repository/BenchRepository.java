package com.examseating.repository;

import com.examseating.entity.Bench;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

/**
 * Spring Data JPA repository for {@link Bench}.
 *
 * <p>Benches are usually reached through their room ({@code room.getBenches()}); this
 * repository exists for bulk operations on them.</p>
 */
public interface BenchRepository extends JpaRepository<Bench, Long> {

    /**
     * Removes every bench belonging to the given halls.
     *
     * <p>Benches also cascade from their room, but a bulk removal names them
     * explicitly so the order of the operation is visible in the code rather
     * than implied by a cascade.</p>
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM Bench b WHERE b.room.id IN :roomIds")
    int deleteAllByRoomIdIn(@Param("roomIds") java.util.Collection<Long> roomIds);
}
