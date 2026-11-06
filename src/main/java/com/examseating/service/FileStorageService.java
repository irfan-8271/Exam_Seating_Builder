package com.examseating.service;

import com.examseating.dto.AccountDtos.StoredFileView;
import com.examseating.entity.StoredFile;
import com.examseating.exception.ResourceNotFoundException;
import com.examseating.repository.StoredFileRepository;
import com.examseating.tenancy.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

/** Persists uploaded documents under the institution's storage quota. */
@Service
@RequiredArgsConstructor
public class FileStorageService {

    private final StoredFileRepository fileRepository;
    private final TenantSupport tenant;
    private final QuotaService quotas;

    @Value("${app.storage.dir:./data/storage}")
    private String storageDir;

    /** Binary download paired with its metadata, so the controller can set headers. */
    public record Download(StoredFile file, byte[] content) {
    }

    @Transactional
    public StoredFileView store(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("A non-empty 'file' part is required");
        }
        long size = file.getSize();
        quotas.checkStorage(size, size);
        String original = safeName(file.getOriginalFilename());
        String stored = UUID.randomUUID().toString().replace("-", "");
        Long institutionId = tenant.currentId();
        Path directory = Paths.get(storageDir).resolve(String.valueOf(institutionId));
        Files.createDirectories(directory);
        Files.write(directory.resolve(stored), file.getBytes());
        StoredFile entity = fileRepository.save(StoredFile.builder()
                .institutionId(institutionId)
                .originalName(original)
                .storedName(stored)
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
        Path path = pathOf(file);
        if (!Files.isRegularFile(path)) {
            throw new ResourceNotFoundException("Stored content for file " + id + " is missing");
        }
        return new Download(file, Files.readAllBytes(path));
    }

    @Transactional
    public void delete(Long id) throws IOException {
        StoredFile file = fileRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("File " + id + " not found"));
        tenant.require(file, "File " + id);
        Files.deleteIfExists(pathOf(file));
        fileRepository.delete(file);
    }

    private Path pathOf(StoredFile file) {
        return Paths.get(storageDir)
                .resolve(String.valueOf(file.getInstitutionId() == null
                        ? tenant.defaultId() : file.getInstitutionId()))
                .resolve(file.getStoredName());
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
