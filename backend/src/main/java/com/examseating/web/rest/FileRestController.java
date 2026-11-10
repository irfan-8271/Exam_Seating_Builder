package com.examseating.web.rest;

import com.examseating.dto.AccountDtos.StoredFileView;
import com.examseating.service.FileStorageService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/** Tenant-scoped file storage, counted against the plan's storage quota. */
@RestController
@RequestMapping("/api/v1/files")
@RequiredArgsConstructor
public class FileRestController {

    private final FileStorageService fileStorageService;

    /** GET /api/v1/files — files owned by the current institution. */
    @GetMapping
    public List<StoredFileView> list() {
        return fileStorageService.list();
    }

    /** POST /api/v1/files — upload one file (multipart, 201 + Location). */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public ResponseEntity<StoredFileView> upload(@RequestParam("file") MultipartFile file) throws IOException {
        StoredFileView view = fileStorageService.store(file);
        return ResponseEntity.created(URI.create("/api/v1/files/" + view.id())).body(view);
    }

    /** GET /api/v1/files/{id} — binary download. */
    @GetMapping("/{id}")
    public ResponseEntity<byte[]> download(@PathVariable Long id) throws IOException {
        FileStorageService.Download download = fileStorageService.load(id);
        String type = download.file().getContentType();
        MediaType mediaType = type == null || type.isBlank()
                ? MediaType.APPLICATION_OCTET_STREAM
                : MediaType.parseMediaType(type);
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + safeHeader(download.file().getOriginalName()) + "\"")
                .body(download.content());
    }

    /** DELETE /api/v1/files/{id} — remove metadata and bytes (204). */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN', 'EXAM_CELL')")
    public ResponseEntity<Void> delete(@PathVariable Long id) throws IOException {
        fileStorageService.delete(id);
        return ResponseEntity.noContent().build();
    }

    private static String safeHeader(String value) {
        String clean = value == null ? "download.bin" : value.replaceAll("[\"\r\n]", "_");
        return clean.isEmpty() ? "download.bin" : clean;
    }
}
