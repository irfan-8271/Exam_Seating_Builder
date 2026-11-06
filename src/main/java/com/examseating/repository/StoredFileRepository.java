package com.examseating.repository;

import com.examseating.entity.StoredFile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface StoredFileRepository extends JpaRepository<StoredFile, Long> {
    List<StoredFile> findByInstitutionIdOrderByUploadedAtDesc(Long institutionId);

    @Query("select coalesce(sum(f.sizeBytes), 0) from StoredFile f where f.institutionId = :institutionId")
    long sumSizeByInstitutionId(@Param("institutionId") Long institutionId);

    @Query("select coalesce(sum(f.sizeBytes), 0) from StoredFile f where f.institutionId is null")
    long sumSizeByInstitutionIdIsNull();
}
