package com.examseating.entity;

import com.examseating.tenancy.TenantScoped;
import jakarta.persistence.Basic;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Lob;
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

    /**
     * Random identifier for the stored blob. It is never derived from user
     * input, so it cannot be used to escape anything.
     *
     * <p>It is no longer used as a file name on disk — the bytes live in
     * {@link #content} — but it is kept so existing rows stay valid and
     * downloads remain addressable.
     */
    @Column(name = "stored_name", nullable = false, length = 100)
    private String storedName;

    /**
     * The uploaded bytes, stored in the database.
     *
     * <p>The content used to be written to a folder on the server's disk and
     * only the metadata was kept here. That made every deployment lossy: on a
     * host that recreates the container between deployments — Render, Fly.io,
     * anything running from an image rather than a persistent volume — the
     * files vanished while their metadata rows survived. Downloads then failed
     * with "Stored content for file N is missing", and the storage quota kept
     * counting bytes that no longer existed.
     *
     * <p>Keeping the bytes in the database makes the application stateless:
     * the container can be thrown away and rebuilt at any time without losing
     * anything. It also means no writable volume is required, which matters
     * because free container platforms either charge for persistent disks or
     * do not offer them at all.
     *
     * <p>Per-plan file limits are small (3 MB on the Free plan, 25 MB on
     * Enterprise), so a BLOB column is comfortably large enough.
     */
    @Lob
    @Basic(fetch = FetchType.LAZY)
    @Column(name = "content", columnDefinition = "LONGBLOB")
    private byte[] content;

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
