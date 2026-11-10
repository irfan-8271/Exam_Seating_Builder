package com.examseating.repository;

import com.examseating.entity.Bench;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for {@link Bench}.
 *
 * <p>Benches are usually reached through their room ({@code room.getBenches()}); this
 * repository exists for bulk operations on them.</p>
 */
public interface BenchRepository extends JpaRepository<Bench, Long> {

}
