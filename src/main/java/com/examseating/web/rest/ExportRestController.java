package com.examseating.web.rest;

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
import java.util.Map;

/**
 * Primary v1 REST resource for downloadable representations of the current
 * seating plan. Each endpoint returns the binary file with an accurate
 * Content-Type and Content-Disposition header.
 */
@RestController
@RequestMapping("/api/v1/exports")
@RequiredArgsConstructor
public class ExportRestController {

    private static final String XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    private final ExcelExportService excelExportService;
    private final PdfExportService pdfExportService;

    /** GET /api/v1/exports — discoverable list of the available representations. */
    @GetMapping
    public Map<String, String> index() {
        return Map.of(
                "excel", "/api/v1/exports/excel",
                "doorNotices", "/api/v1/exports/pdf/door-notices",
                "invigilatorSheets", "/api/v1/exports/pdf/invigilator");
    }

    /** GET /api/v1/exports/excel — master list + per-hall layout sheets (.xlsx). */
    @GetMapping(value = "/excel", produces = XLSX)
    public ResponseEntity<byte[]> excel(@RequestParam(required = false) String subjectCode) throws IOException {
        return file(excelExportService.export(subjectCode), "seating-plan.xlsx",
                MediaType.parseMediaType(XLSX));
    }

    /** GET /api/v1/exports/pdf/door-notices — printable door notices (.pdf). */
    @GetMapping(value = "/pdf/door-notices", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> doorNotices(@RequestParam(required = false) String subjectCode) {
        return file(pdfExportService.doorNotices(subjectCode), "door-notices.pdf", MediaType.APPLICATION_PDF);
    }

    /** GET /api/v1/exports/pdf/invigilator — attendance sheets (.pdf). */
    @GetMapping(value = "/pdf/invigilator", produces = MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<byte[]> invigilator(@RequestParam(required = false) String subjectCode) {
        return file(pdfExportService.invigilatorSheets(subjectCode), "invigilator-sheets.pdf", MediaType.APPLICATION_PDF);
    }

    private static ResponseEntity<byte[]> file(byte[] bytes, String filename, MediaType mediaType) {
        return ResponseEntity.ok()
                .contentType(mediaType)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .body(bytes);
    }
}
