package com.examseating.service;

import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.StudentAttributes;
import com.examseating.entity.StudentIdentityScheme;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.tenancy.TenantSupport;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Schema-agnostic Excel export service (Apache POI):
 * <ul>
 *   <li><b>Master List</b> — dynamic columns reflecting the institution's exact
 *       uploaded attributes, student identity, cohort, round scope, and seat coordinates.</li>
 *   <li><b>Per-Hall 2D Visual Map</b> — bench matrix colored by cohort with student IDs.</li>
 *   <li><b>Legend</b> — cohort color mapping.</li>
 * </ul>
 */
@Service
public class ExcelExportService {

    private final SeatingAllocationRepository allocationRepository;
    private final TenantSupport tenant;
    private final StudentIdentityService identityService;

    public ExcelExportService(SeatingAllocationRepository allocationRepository, TenantSupport tenant) {
        this(allocationRepository, tenant, null);
    }

    @Autowired
    public ExcelExportService(SeatingAllocationRepository allocationRepository,
                              TenantSupport tenant,
                              StudentIdentityService identityService) {
        this.allocationRepository = allocationRepository;
        this.tenant = tenant;
        this.identityService = identityService;
    }

    private static final IndexedColors[] PALETTE = {
            IndexedColors.LIGHT_BLUE, IndexedColors.LIGHT_GREEN, IndexedColors.LAVENDER,
            IndexedColors.LIGHT_ORANGE, IndexedColors.LEMON_CHIFFON, IndexedColors.ROSE,
            IndexedColors.AQUA, IndexedColors.LIGHT_TURQUOISE, IndexedColors.PALE_BLUE,
            IndexedColors.LIGHT_YELLOW, IndexedColors.PLUM, IndexedColors.TAN,
    };

    @Transactional(readOnly = true)
    public byte[] export(String scopeKey) throws IOException {
        List<SeatingAllocation> all = allocationRepository.findAllWithDetails(
                scopeKey == null || scopeKey.isBlank() ? null : scopeKey.trim());
        List<SeatingAllocation> allocations = tenant != null ? tenant.visible(all) : all;

        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Map<String, CellStyle> cohortStyles = new LinkedHashMap<>();
            CellStyle emptyStyle = emptyStyle(workbook);

            writeMasterSheet(workbook, allocations);
            writeHallSheets(workbook, allocations, cohortStyles, emptyStyle);
            writeLegendSheet(workbook, cohortStyles);

            workbook.write(out);
            return out.toByteArray();
        }
    }

    private void writeMasterSheet(Workbook workbook, List<SeatingAllocation> allocations) {
        Sheet sheet = workbook.createSheet("Master List");
        StudentIdentityScheme scheme = identityService == null ? null : identityService.currentScheme().orElse(null);
        List<String> classmateKeys = scheme == null ? List.of() : scheme.classmateAttributeList();
        Map<String, String> labels = identityService == null ? Map.of() : identityService.effectiveColumnLabels();

        Set<String> dynamicAttrKeys = new LinkedHashSet<>();
        for (SeatingAllocation a : allocations) {
            dynamicAttrKeys.addAll(StudentAttributes.allPopulatedAttributes(a.getStudent()).keySet());
        }
        List<String> attrColumns = new ArrayList<>(dynamicAttrKeys);

        List<String> headers = new ArrayList<>();
        headers.add("Student ID");
        headers.add("Student Name");
        headers.add("Cohort");
        for (String key : attrColumns) {
            headers.add(labels.getOrDefault(key, StudentAttributes.defaultTitle(key)));
        }
        headers.add("Exam Round");
        headers.add("Hall");
        headers.add("Bench Row");
        headers.add("Bench Col");
        headers.add("Slot");

        Row headerRow = sheet.createRow(0);
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

        for (int i = 0; i < headers.size(); i++) {
            Cell cell = headerRow.createCell(i);
            cell.setCellValue(headers.get(i));
            cell.setCellStyle(headerStyle);
        }

        int r = 1;
        for (SeatingAllocation a : allocations) {
            Row row = sheet.createRow(r++);
            int col = 0;
            row.createCell(col++).setCellValue(StudentAttributes.displayLabel(a.getStudent(), scheme));
            row.createCell(col++).setCellValue(a.getStudent().getName());
            row.createCell(col++).setCellValue(StudentAttributes.cohortLabel(a.getStudent(), classmateKeys));
            for (String key : attrColumns) {
                String v = StudentAttributes.value(a.getStudent(), key);
                row.createCell(col++).setCellValue(v == null ? "" : v);
            }
            row.createCell(col++).setCellValue(a.getScopeKey() == null ? "All" : a.getScopeKey());
            row.createCell(col++).setCellValue(a.getBench().getRoom().getHallName());
            row.createCell(col++).setCellValue(a.getBench().getRowIndex());
            row.createCell(col++).setCellValue(a.getBench().getColIndex());
            row.createCell(col).setCellValue(a.getSlotNumber());
        }
        for (int i = 0; i < headers.size(); i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.createFreezePane(0, 1);
    }

    private void writeHallSheets(Workbook workbook, List<SeatingAllocation> allocations,
                                 Map<String, CellStyle> cohortStyles, CellStyle emptyStyle) {
        Map<ExamRoom, List<SeatingAllocation>> byRoom = new LinkedHashMap<>();
        Set<String> usedSheetNames = new HashSet<>();
        usedSheetNames.add("Master List");
        usedSheetNames.add("Legend");
        for (SeatingAllocation a : allocations) {
            byRoom.computeIfAbsent(a.getBench().getRoom(), k -> new ArrayList<>()).add(a);
        }

        StudentIdentityScheme scheme = identityService == null ? null : identityService.currentScheme().orElse(null);
        List<String> classmateKeys = scheme == null ? List.of() : scheme.classmateAttributeList();

        for (Map.Entry<ExamRoom, List<SeatingAllocation>> entry : byRoom.entrySet()) {
            ExamRoom room = entry.getKey();
            Sheet sheet = workbook.createSheet(uniqueSheetName(sanitize(room.getHallName()), usedSheetNames));

            Row title = sheet.createRow(0);
            Cell titleCell = title.createCell(0);
            titleCell.setCellValue(safe(room.getHallName()) + "  |  " + room.getRows() + " x " + room.getColumns()
                    + "  |  " + room.getBenchType() + "  |  Round: " + scopeLabel(entry.getValue()));
            CellStyle titleStyle = workbook.createCellStyle();
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleStyle.setFont(titleFont);
            titleCell.setCellStyle(titleStyle);

            Map<String, String> text = new LinkedHashMap<>();
            Map<String, String> cohortByBench = new LinkedHashMap<>();
            for (SeatingAllocation a : entry.getValue()) {
                String key = a.getBench().getRowIndex() + "," + a.getBench().getColIndex();
                String idLabel = StudentAttributes.displayLabel(a.getStudent(), scheme);
                text.merge(key, idLabel, (oldV, newV) -> oldV + "\n" + newV);
                cohortByBench.putIfAbsent(key, StudentAttributes.cohortLabel(a.getStudent(), classmateKeys));
            }
            for (int r = 1; r <= room.getRows(); r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 1; c <= room.getColumns(); c++) {
                    Cell cell = row.createCell(c - 1);
                    String key = r + "," + c;
                    String value = text.get(key);
                    if (value != null) {
                        cell.setCellValue(value);
                        cell.setCellStyle(styleForCohort(workbook, cohortStyles, cohortByBench.get(key)));
                    } else {
                        cell.setCellStyle(emptyStyle);
                    }
                }
            }
            for (int c = 1; c <= room.getColumns(); c++) {
                sheet.setColumnWidth(c - 1, 16 * 256);
            }
            sheet.setColumnWidth(room.getColumns(), 40 * 256);
        }
    }

    private void writeLegendSheet(Workbook workbook, Map<String, CellStyle> cohortStyles) {
        Sheet sheet = workbook.createSheet("Legend");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Cohort / Group");
        header.createCell(1).setCellValue("Colour");
        int r = 1;
        for (Map.Entry<String, CellStyle> e : cohortStyles.entrySet()) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(e.getKey());
            row.createCell(1).setCellStyle(e.getValue());
        }
        sheet.autoSizeColumn(0);
        sheet.setColumnWidth(1, 20 * 256);
    }

    private CellStyle styleForCohort(Workbook workbook, Map<String, CellStyle> cache, String cohort) {
        String key = (cohort == null || cohort.isBlank()) ? "(unassigned)" : cohort;
        return cache.computeIfAbsent(key, k -> {
            CellStyle style = workbook.createCellStyle();
            int colorIndex = Math.floorMod(k.hashCode(), PALETTE.length);
            style.setFillForegroundColor(PALETTE[colorIndex].getIndex());
            style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            style.setBorderTop(BorderStyle.THIN);
            style.setBorderBottom(BorderStyle.THIN);
            style.setBorderLeft(BorderStyle.THIN);
            style.setBorderRight(BorderStyle.THIN);
            style.setAlignment(HorizontalAlignment.CENTER);
            style.setVerticalAlignment(VerticalAlignment.CENTER);
            style.setWrapText(true);
            return style;
        });
    }

    private CellStyle emptyStyle(Workbook workbook) {
        CellStyle style = workbook.createCellStyle();
        style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
        style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        style.setBorderTop(BorderStyle.THIN);
        style.setBorderBottom(BorderStyle.THIN);
        style.setBorderLeft(BorderStyle.THIN);
        style.setBorderRight(BorderStyle.THIN);
        return style;
    }

    private static String sanitize(String name) {
        String forbidden = "[]*?/" + (char) 92 + ":";
        String source = safe(name).trim();
        StringBuilder sb = new StringBuilder();
        for (char c : source.toCharArray()) {
            if (c < 32) {
                continue;
            }
            sb.append(forbidden.indexOf(c) >= 0 ? '-' : c);
        }
        String s = sb.toString();
        if (s.isEmpty()) {
            s = "Hall";
        }
        return s.length() > 31 ? s.substring(0, 31) : s;
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "(unnamed hall)" : value;
    }

    private static String uniqueSheetName(String base, Set<String> used) {
        String candidate = base;
        int suffix = 2;
        while (!used.add(candidate)) {
            String tag = " (" + suffix++ + ")";
            candidate = base.substring(0, Math.min(base.length(), 31 - tag.length())) + tag;
        }
        return candidate;
    }

    private static String scopeLabel(List<SeatingAllocation> allocations) {
        Set<String> scopes = new LinkedHashSet<>();
        for (SeatingAllocation a : allocations) {
            if (a.getScopeKey() != null && !a.getScopeKey().isBlank()) {
                scopes.add(a.getScopeKey());
            }
        }
        return scopes.isEmpty() ? "Whole Roster" : String.join(", ", scopes);
    }
}
