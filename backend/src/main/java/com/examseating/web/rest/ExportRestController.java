package com.examseating.web.rest;

import com.examseating.service.ExcelExportService;
import com.examseating.service.PdfExportService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;

/**
 * Primary v1 REST resource for downloadable Excel and PDF seating deliverables.
 */
@RestController
@RequestMapping("/api/v1/seating-plans/current")
@RequiredArgsConstructor
public class ExportRestController {

    private static final MediaType XLSX = MediaType.parseMediaType(
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final ExcelExportService excelExportService;
    private final PdfExportService pdfExportService;

    @GetMapping("/workbook")
    public ResponseEntity<byte[]> workbook(@RequestParam(required = false) String scopeKey)
            throws IOException {
        return download(excelExportService.export(scopeKey), XLSX,
                filename("seating-plan", scopeKey, "xlsx"));
    }

    @GetMapping("/door-notices")
    public ResponseEntity<byte[]> doorNotices(@RequestParam(required = false) String scopeKey) {
        return download(pdfExportService.doorNotices(scopeKey), MediaType.APPLICATION_PDF,
                filename("door-notices", scopeKey, "pdf"));
    }

    @GetMapping("/invigilator-sheets")
    public ResponseEntity<byte[]> invigilatorSheets(@RequestParam(required = false) String scopeKey) {
        return download(pdfExportService.invigilatorSheets(scopeKey), MediaType.APPLICATION_PDF,
                filename("invigilator-sheets", scopeKey, "pdf"));
    }

    private static ResponseEntity<byte[]> download(byte[] bytes, MediaType type, String name) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(type);
        headers.setContentDisposition(ContentDisposition.attachment().filename(name).build());
        return ResponseEntity.ok().headers(headers).body(bytes);
    }

    private static String filename(String stem, String scopeKey, String ext) {
        if (scopeKey == null || scopeKey.isBlank()) {
            return stem + "." + ext;
        }
        String safe = scopeKey.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        return stem + "-" + safe + "." + ext;
    }
}
