package com.examseating.service;

import com.examseating.dto.AccountDtos.StoredFileView;
import com.examseating.entity.StoredFile;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.repository.StoredFileRepository;
import com.examseating.tenancy.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * Retains uploaded documents under the institution's storage quota.
 *
 * <p><strong>The bytes are kept in the database</strong>, not on the server's
 * disk. Earlier versions wrote the upload to a folder on disk and stored only
 * the metadata in the database. That made deployments lossy: any host that
 * recreates the container between deployments (Render, Fly.io, anything
 * running from an image rather than a persistent volume) lost the files while
 * keeping the metadata rows. Downloads then failed with "Stored content for
 * file N is missing", and the storage quota kept counting bytes that were no
 * longer there.
 *
 * <p>Storing the content in the database removes that whole class of problem:
 * the application keeps no local state, so the container can be destroyed and
 * rebuilt at any moment without losing anything. It also means the app no
 * longer needs a writable volume, which matters because free container
 * platforms either charge for persistent disks or do not offer them at all.
 *
 * <p>Because the storage quota is measured against the database anyway
 * ({@link QuotaService#storageUsedBytes()} sums the metadata rows), keeping
 * the bytes and the quota in the same place keeps the two consistent.
 */
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final StoredFileRepository fileRepository;
    private final TenantSupport tenant;
    private final QuotaService quotas;

    /** Binary download paired with its metadata, so the controller can set headers. */
    public record Download(StoredFile file, byte[] content) {
    }

    @Transactional
    public StoredFileView store(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A non-empty 'file' part is required");
        }
        byte[] bytes = file.getBytes();
        long size = bytes.length;
        quotas.checkStorage(size, size);
        String original = safeName(file.getOriginalFilename());
        // A random id keeps each upload addressable; it is never derived from
        // user input, so it cannot be used to escape anything.
        String stored = UUID.randomUUID().toString().replace("-", "");
        Long institutionId = tenant.currentId();
        StoredFile entity = fileRepository.save(StoredFile.builder()
                .institutionId(institutionId)
                .originalName(original)
                .storedName(stored)
                .content(bytes)
                .contentType(file.getContentType())
                .sizeBytes(size)
                .build());
        return view(entity);
    }

    public List<StoredFileView> list() {
        return tenant.visible(fileRepository.findByInstitutionIdOrderByUploadedAtDesc(tenant.currentId()))
                .stream().map(FileStorageService::view).toList();
    }

    public Download load(Long id) throws IOException {
        StoredFile file = fileRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("File " + id + " not found"));
        tenant.require(file, "File " + id);
        byte[] content = file.getContent();
        if (content == null || content.length == 0) {
            throw new ResourceNotFoundException("Stored content for file " + id + " is missing");
        }
        return new Download(file, content);
    }

    @Transactional
    public void delete(Long id) throws IOException {
        StoredFile file = fileRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("File " + id + " not found"));
        tenant.require(file, "File " + id);
        fileRepository.delete(file);
    }

    private static StoredFileView view(StoredFile f) {
        return new StoredFileView(f.getId(), f.getOriginalName(), f.getContentType(),
                f.getSizeBytes(), f.getUploadedAt());
    }

    /** Strip any directory component and control characters from a client filename. */
    private static String safeName(String raw) {
        String name = raw == null ? "upload.bin" : raw.replace('\\', '/');
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        name = name.replaceAll("[\\x00-\\x1f\\x7f]", "_").trim();
        if (name.isEmpty() || ".".equals(name) || "..".equals(name)) {
            name = "upload.bin";
        }
        return name.length() > 255 ? name.substring(0, 255) : name;
    }
}
