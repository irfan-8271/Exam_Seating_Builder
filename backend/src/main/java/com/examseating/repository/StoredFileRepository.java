package com.examseating.repository;

import com.examseating.entity.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {
    List<StoredFile> findByInstitutionIdOrderByUploadedAtDesc(Long institutionId);

    @Query("select coalesce(sum(f.sizeBytes), 0) from StoredFile f where f.institutionId = :institutionId")
    long sumSizeByInstitutionId(@Param("institutionId") Long institutionId);

    @Query("select coalesce(sum(f.sizeBytes), 0) from StoredFile f where f.institutionId is null")
    long sumSizeByInstitutionIdIsNull();

    /**
     * Stored files that may be removed, oldest first.
     *
     * @param cutoff        only files uploaded at or before this instant; null means no cutoff
     * @param institutionId restrict to one institution; null means every institution
     */
    @Query("select f from StoredFile f "
            + "where (:cutoff is null or f.uploadedAt <= :cutoff) "
            + "and (:institutionId is null or f.institutionId = :institutionId) "
            + "order by f.uploadedAt asc")
    List<StoredFile> findRemovable(@Param("cutoff") Instant cutoff,
                                   @Param("institutionId") Long institutionId);

    /** Every stored file, oldest first — used to report the total footprint. */
    @Query("select f from StoredFile f order by f.uploadedAt asc")
    List<StoredFile> findAllOldestFirst();

    /** Total bytes across every stored file, regardless of institution. */
    @Query("select coalesce(sum(f.sizeBytes), 0) from StoredFile f")
    long sumSizeAll();
}
