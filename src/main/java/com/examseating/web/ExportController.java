package com.examseating.web;

import com.examseating.service.ExcelExportService;
import com.examseating.service.PdfExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Export endpoints (Step 1 "outputs"):
 * <ul>
 *   <li>{@code GET /api/export/excel} — color-coded 2D hall maps + master list (.xlsx)</li>
 *   <li>{@code GET /api/export/pdf/door-notices} — printable per-hall door notices (.pdf)</li>
 *   <li>{@code GET /api/export/pdf/invigilator} — per-hall invigilator/attendance sheets (.pdf)</li>
 * </ul>
 *
 * <p>Any authenticated role may download (invigilators print the notices); role-based
 * write access is enforced on the arrange/import endpoints.</p>
 *
 * <p>{@code ResponseEntity<byte[]>} is the standard way to return a binary file from a
 * controller: set the {@code Content-Type}, a {@code Content-Disposition} header so the
 * browser offers "Save as", and hand the bytes to Spring.</p>
 */
@RestController
@RequestMapping("/api/export")
@RequiredArgsConstructor
public class ExportController {

    private final ExcelExportService excelExportService;
    private final PdfExportService pdfExportService;

    @GetMapping(value = "/excel", produces = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    public ResponseEntity<byte[]> excel(@RequestParam(required = false) String subjectCode) throws IOException {
        byte[] bytes = excelExportService.export(subjectCode);
        return file(bytes, "seating-plan.xlsx", MediaType.parseMediaType(
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"));
    }

    @GetMapping(value = "/pdf/door-notices", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> doorNotices(@RequestParam(required = false) String subjectCode) {
        byte[] bytes = pdfExportService.doorNotices(subjectCode);
        return file(bytes, "door-notices.pdf", MediaType.APPLICATION_PDF);
    }

    @GetMapping(value = "/pdf/invigilator", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> invigilator(@RequestParam(required = false) String subjectCode) {
        byte[] bytes = pdfExportService.invigilatorSheets(subjectCode);
        return file(bytes, "invigilator-sheets.pdf", MediaType.APPLICATION_PDF);
    }

    private static ResponseEntity<byte[]> file(byte[] bytes, String filename, MediaType mediaType) {
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + filename + "\"")
                .body(bytes);
    }
}
