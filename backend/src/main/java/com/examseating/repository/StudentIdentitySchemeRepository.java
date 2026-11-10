package com.examseating.repository;

import com.examseating.entity.StudentIdentityScheme;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/**
 * Spring Data JPA repository for {@link StudentIdentityScheme}.
 *
 * <p>There is exactly one identity scheme per institution, so the natural
 * lookup is by institution id.</p>
 */
public interface StudentIdentitySchemeRepository extends JpaRepository<StudentIdentityScheme, Long> {

    /** The scheme configured for one institution, if any. */
    Optional<StudentIdentityScheme> findByInstitutionId(Long institutionId);

    /** Removes the scheme of one institution (used when resetting to auto-detection). */
    void deleteByInstitutionId(Long institutionId);
}
