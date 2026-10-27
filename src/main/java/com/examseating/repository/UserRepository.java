package com.examseating.repository;

import com.examseating.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link User}.
 *
 * <p><b>How Spring Data JPA works (you know Hibernate; this is the Spring wrapper):</b></p>
 * <ul>
 *   <li>You only write an <i>interface</i>. At startup, Spring Data JPA generates a
 *       concrete implementation (a JDK dynamic proxy) and registers it as a bean.
 *       You never write the implementation class.</li>
 *   <li>{@link JpaRepository} already brings the classic CRUD methods — {@code findAll()},
 *       {@code findById(id)}, {@code save(entity)}, {@code deleteById(id)},
 *       {@code count()}, {@code existsById(id)} — so a whole DAO layer is inherited for free.</li>
 *   <li>{@code save(entity)} is a "save-or-update": Hibernate decides whether to
 *       {@code persist} (new, no id) or {@code merge} (detached, has id).</li>
 *   <li><b>Derived queries:</b> a method name like {@code findByUsername} is parsed
 *       by the framework into JPQL ({@code WHERE username = ?1}) and a query is
 *       generated — no SQL/JPQL string needed. Parse rules: {@code findBy} + entity
 *       property name, camelCase, and you can chain conditions
 *       ({@code findByRoleAndDepartment}), sort ({@code findAllByOrderByNameAsc}),
 *       or add limits.</li>
 * </ul>
 */
public interface UserRepository extends JpaRepository<User, Long> {

    /** Derived query: SELECT u FROM User u WHERE u.username = :username */
    Optional<User> findByUsername(String username);
}
