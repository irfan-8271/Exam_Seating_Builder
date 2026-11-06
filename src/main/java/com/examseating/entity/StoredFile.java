package com.examseating.entity;

import com.examseating.tenancy.TenantScoped;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/** Metadata for an uploaded roster / document retained under the institution's storage quota. */
@Entity
@Table(name = "stored_files")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class StoredFile implements TenantScoped {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(name = "institution_id", nullable = false)
    private Long institutionId;

    @Column(name = "original_name", nullable = false, length = 255)
    private String originalName;

    /** Random on-disk name; never derived from user input (path traversal safe). */
    @Column(name = "stored_name", nullable = false, length = 100)
    private String storedName;

    @Column(name = "content_type", length = 120)
    private String contentType;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "uploaded_by", length = 50)
    private String uploadedBy;

    @Column(name = "uploaded_at", nullable = false, updatable = false)
    private Instant uploadedAt;

    @jakarta.persistence.PrePersist
    void onCreate() {
        if (uploadedAt == null) {
            uploadedAt = Instant.now();
        }
    }
}
