package com.examseating.repository;

import com.examseating.entity.ExamRoom;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/**
 * Spring Data JPA repository for {@link ExamRoom}.
 *
 * <p><b>Lazy loading note (this is the classic Spring + JPA pitfall):</b> the
 * {@code benches} collection on a room is {@code FetchType.LAZY}, so reading
 * {@code room.getBenches()} outside a transaction throws
 * {@code LazyInitializationException}. Spring Data JPA closes the transaction as soon
 * as the repository method returns — which is why services should be
 * {@code @Transactional} (keep the persistence context open for the whole operation)
 * and why we build response DTOs <i>inside</i> the service method, never after it.</p>
 */
public interface ExamRoomRepository extends JpaRepository<ExamRoom, Long> {

    /** Hall name is unique — lookups by it are used by importers and tests. */
    java.util.Optional<ExamRoom> findByHallName(String hallName);

    /**
     * Fetches rooms together with their benches in one query.
     *
     * <p>Method names are parsed as derived queries, so this must be a real
     * "find ... by ..." name — {@code findAllWithBenches} would be parsed as a
     * (nonexistent) property {@code withBenches}. {@code findAllByOrderByHallNameAsc}
     * means "find all, ordered by hallName ascending", which is valid.</p>
     *
     * <p>{@link EntityGraph} is a JPA hint: "eagerly load the benches attribute when
     * you load rooms" — implemented by Hibernate as a {@code LEFT JOIN FETCH}. Without
     * it, calling {@code room.getBenches()} for each of N rooms would fire N extra
     * queries (the N+1 problem).</p>
     */
    @EntityGraph(attributePaths = "benches")
    List<ExamRoom> findAllByOrderByHallNameAsc();

    @EntityGraph(attributePaths = "benches")
    List<ExamRoom> findAllByIdIn(java.util.Collection<Long> ids);

    /** Single hall with its bench grid eagerly loaded (explicit JPQL — no name-parsing surprises). */
    @org.springframework.data.jpa.repository.Query(
            "select r from ExamRoom r left join fetch r.benches where r.id = :id")
    java.util.Optional<ExamRoom> findWithBenchesById(@org.springframework.data.repository.query.Param("id") Long id);
}
