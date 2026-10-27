package com.examseating.service;

import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.repository.SeatingAllocationRepository;
import lombok.RequiredArgsConstructor;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Excel export (Apache POI) — the Step 1 "outputs" requirement.
 *
 * <p>The workbook contains:</p>
 * <ul>
 *   <li><b>Master List</b> — one row per allocated seat (roll no, name, dept, year,
 *       subject, hall, bench row/col, slot).</li>
 *   <li><b>One sheet per hall</b> — the 2D visual layout map: bench (row, col) is
 *       drawn at Excel cell (row, col), cell background color = department of the
 *       student(s) sitting there, text = roll number(s).</li>
 *   <li><b>Legend</b> — department → color mapping.</li>
 * </ul>
 *
 * <p>POI modelling notes: {@code XSSFWorkbook} = the .xlsx file; {@code Sheet},
 * {@code Row}, {@code Cell} are the spreadsheet objects; {@code CellStyle} bundles
 * font, fill color, borders and alignment and is reused across cells (styles are
 * workbook-global resources). Colors use Excel's indexed palette so the output opens
 * in any spreadsheet app.</p>
 */
@Service
@RequiredArgsConstructor
public class ExcelExportService {

    private final SeatingAllocationRepository allocationRepository;

    /** Deterministic department → color palette (cycled in encounter order). */
    private static final IndexedColors[] PALETTE = {
            IndexedColors.LIGHT_BLUE, IndexedColors.LIGHT_GREEN, IndexedColors.LAVENDER,
            IndexedColors.LIGHT_ORANGE, IndexedColors.LEMON_CHIFFON, IndexedColors.ROSE,
            IndexedColors.AQUA, IndexedColors.LIGHT_TURQUOISE, IndexedColors.PALE_BLUE,
            IndexedColors.LIGHT_YELLOW, IndexedColors.PLUM, IndexedColors.TAN,
    };

    @Transactional(readOnly = true)
    public byte[] export(String subjectCode) throws IOException {
        List<SeatingAllocation> allocations =
                allocationRepository.findAllWithDetails(subjectCode == null || subjectCode.isBlank() ? null : subjectCode);

        try (Workbook workbook = new XSSFWorkbook();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {

            Map<String, CellStyle> deptStyles = new LinkedHashMap<>();

            writeMasterSheet(workbook, allocations, deptStyles);
            writeHallSheets(workbook, allocations, deptStyles);
            writeLegendSheet(workbook, deptStyles);

            workbook.write(out);
            return out.toByteArray();
        }
    }

    // ------------------------------------------------------------------
    //  Master list
    // ------------------------------------------------------------------

    private void writeMasterSheet(Workbook workbook, List<SeatingAllocation> allocations,
                                  Map<String, CellStyle> deptStyles) {
        Sheet sheet = workbook.createSheet("Master List");
        String[] headers = {"Roll No", "Name", "Department", "Year/Session", "Subject",
                "Hall", "Bench Row", "Bench Col", "Slot"};
        Row headerRow = sheet.createRow(0);
        CellStyle headerStyle = workbook.createCellStyle();
        Font headerFont = workbook.createFont();
        headerFont.setBold(true);
        headerFont.setColor(IndexedColors.WHITE.getIndex());
        headerStyle.setFont(headerFont);
        headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
        headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
        for (int i = 0; i < headers.length; i++) {
            headerRow.createCell(i).setCellValue(headers[i]);
            headerRow.getCell(i).setCellStyle(headerStyle);
        }
        int r = 1;
        for (SeatingAllocation a : allocations) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(a.getStudent().getRollNo());
            row.createCell(1).setCellValue(a.getStudent().getName());
            row.createCell(2).setCellValue(a.getStudent().getDepartment());
            row.createCell(3).setCellValue(a.getStudent().getYearSession());
            row.createCell(4).setCellValue(a.getSubjectCode());
            row.createCell(5).setCellValue(a.getBench().getRoom().getHallName());
            row.createCell(6).setCellValue(a.getBench().getRowIndex());
            row.createCell(7).setCellValue(a.getBench().getColIndex());
            row.createCell(8).setCellValue(a.getSlotNumber());
        }
        for (int i = 0; i < headers.length; i++) {
            sheet.autoSizeColumn(i);
        }
        sheet.createFreezePane(0, 1);
    }

    // ------------------------------------------------------------------
    //  One 2D layout sheet per hall
    // ------------------------------------------------------------------

    private void writeHallSheets(Workbook workbook, List<SeatingAllocation> allocations,
                                 Map<String, CellStyle> deptStyles) {
        // Group allocations by hall (insertion order = first appearance = deterministic).
        Map<ExamRoom, List<SeatingAllocation>> byRoom = new LinkedHashMap<>();
        for (SeatingAllocation a : allocations) {
            byRoom.computeIfAbsent(a.getBench().getRoom(), k -> new java.util.ArrayList<>()).add(a);
        }

        for (Map.Entry<ExamRoom, List<SeatingAllocation>> entry : byRoom.entrySet()) {
            ExamRoom room = entry.getKey();
            Sheet sheet = workbook.createSheet(sanitize(room.getHallName()));

            // Title row
            Row title = sheet.createRow(0);
            Cell titleCell = title.createCell(0);
            titleCell.setCellValue(room.getHallName() + "  |  " + room.getRows() + " x " + room.getColumns()
                    + "  |  " + room.getBenchType() + "  |  Subject: "
                    + entry.getValue().get(0).getSubjectCode());
            CellStyle titleStyle = workbook.createCellStyle();
            Font titleFont = workbook.createFont();
            titleFont.setBold(true);
            titleFont.setFontHeightInPoints((short) 14);
            titleStyle.setFont(titleFont);
            titleCell.setCellStyle(titleStyle);

            // Grid: bench (r,c) → Excel cell (r + 2, c - 1); 1-based → 0-based offset.
            Map<String, String> text = new LinkedHashMap<>();
            Map<String, String> dept = new LinkedHashMap<>();
            for (SeatingAllocation a : entry.getValue()) {
                String key = a.getBench().getRowIndex() + "," + a.getBench().getColIndex();
                String roll = a.getStudent().getRollNo();
                text.merge(key, roll, (oldV, newV) -> oldV + "\n" + newV); // slot1 / slot2 lines
                dept.put(key, a.getStudent().getDepartment());
            }
            for (int r = 1; r <= room.getRows(); r++) {
                Row row = sheet.createRow(r + 1);
                for (int c = 1; c <= room.getColumns(); c++) {
                    Cell cell = row.createCell(c - 1);
                    String key = r + "," + c;
                    String value = text.get(key);
                    if (value != null) {
                        cell.setCellValue(value);
                        cell.setCellStyle(styleForDept(workbook, deptStyles, dept.get(key)));
                    } else {
                        cell.setCellStyle(emptyStyle(workbook));
                    }
                }
            }
            for (int c = 1; c <= room.getColumns(); c++) {
                sheet.setColumnWidth(c - 1, 14 * 256); // ~14 chars
            }
            sheet.setColumnWidth(room.getColumns(), 40 * 256); // wide title column
        }
    }

    // ------------------------------------------------------------------
    //  Legend
    // ------------------------------------------------------------------

    private void writeLegendSheet(Workbook workbook, Map<String, CellStyle> deptStyles) {
        Sheet sheet = workbook.createSheet("Legend");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Department");
        header.createCell(1).setCellValue("Colour");
        int r = 1;
        for (Map.Entry<String, CellStyle> e : deptStyles.entrySet()) {
            Row row = sheet.createRow(r++);
            row.createCell(0).setCellValue(e.getKey());
            row.createCell(1).setCellStyle(e.getValue());
        }
        sheet.autoSizeColumn(0);
        sheet.setColumnWidth(1, 20 * 256);
    }

    // ------------------------------------------------------------------
    //  Styles
    // ------------------------------------------------------------------

    /** Returns (and caches) the style for a department — color-coded per requirement. */
    private CellStyle styleForDept(Workbook workbook, Map<String, CellStyle> cache, String dept) {
        return cache.computeIfAbsent(dept, d -> {
            CellStyle style = workbook.createCellStyle();
            int colorIndex = Math.floorMod(d.hashCode(), PALETTE.length);
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

    /** Excel sheet names cannot contain some characters — strip them. */
    private static String sanitize(String name) {
        return name.replaceAll("[\\[\\]\\*\\?/\\\\:]", "-");
    }
}
