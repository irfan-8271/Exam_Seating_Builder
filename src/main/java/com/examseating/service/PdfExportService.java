package com.examseating.service;

import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Student;
import com.examseating.repository.SeatingAllocationRepository;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * PDF export service (OpenPDF) for high-impact exam seating outputs.
 *
 * <ul>
 *   <li><b>Door Notices / Notice Board:</b> Landscape visual seating map per hall.
 *       Renders each bench desk visually with Left/Right seat boxes for dual-seater benches,
 *       centered seat boxes for single-seater benches, distinct stream color coding,
 *       prominent roll numbers, blackboard front-of-hall indicator, and stream legend.</li>
 *   <li><b>Invigilator Sheets:</b> Professional hall-wise attendance rosters with
 *       desk coordinates, slot indicators, student credentials, and signature columns.</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class PdfExportService {

    // Document Typography Tokens
    private static final Font FONT_DOC_TITLE = new Font(Font.HELVETICA, 14, Font.BOLD, new Color(15, 23, 42));
    private static final Font FONT_DOC_SUBTITLE = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, new Color(100, 116, 139));
    private static final Font FONT_DOC_META = new Font(Font.HELVETICA, 8, Font.BOLD, new Color(30, 41, 59));

    private static final Font FONT_BANNER_ROOM = new Font(Font.HELVETICA, 10.5f, Font.BOLD, Color.WHITE);
    private static final Font FONT_BANNER_ITEM = new Font(Font.HELVETICA, 7.5f, Font.NORMAL, new Color(226, 232, 240));
    private static final Font FONT_BANNER_BOLD = new Font(Font.HELVETICA, 7.5f, Font.BOLD, Color.WHITE);

    private static final Font FONT_FRONT_BAR = new Font(Font.HELVETICA, 7.5f, Font.BOLD, new Color(51, 65, 85));
    private static final Font FONT_BENCH_HEADER = new Font(Font.HELVETICA, 6.8f, Font.BOLD, Color.WHITE);

    private static final Font FONT_INVIG_TITLE = new Font(Font.HELVETICA, 16, Font.BOLD, new Color(15, 23, 42));
    private static final Font FONT_INVIG_SUB = new Font(Font.HELVETICA, 9.5f, Font.NORMAL, new Color(71, 85, 105));
    private static final Font FONT_TABLE_HEADER = new Font(Font.HELVETICA, 8.5f, Font.BOLD, Color.WHITE);
    private static final Font FONT_BODY = new Font(Font.HELVETICA, 8.5f, Font.NORMAL, new Color(30, 41, 59));
    private static final Font FONT_BODY_BOLD = new Font(Font.HELVETICA, 8.5f, Font.BOLD, new Color(15, 23, 42));

    private final SeatingAllocationRepository allocationRepository;

    /**
     * Department color theme definition for high-contrast visual display.
     */
    private static final class DeptTheme {
        final Color bg;        // Seat cell background
        final Color border;    // Seat cell border
        final Color text;      // Badge/Dept text
        final Color dot;       // Legend swatch

        DeptTheme(Color bg, Color border, Color text, Color dot) {
            this.bg = bg;
            this.border = border;
            this.text = text;
            this.dot = dot;
        }
    }

    private static final DeptTheme[] PALETTE = {
            new DeptTheme(new Color(238, 242, 255), new Color(165, 180, 252), new Color(49, 46, 129), new Color(99, 102, 241)),  // Indigo (CSE)
            new DeptTheme(new Color(236, 254, 255), new Color(103, 232, 249), new Color(22, 78, 99), new Color(6, 182, 212)),   // Cyan (ECE)
            new DeptTheme(new Color(254, 243, 199), new Color(252, 211, 77), new Color(120, 53, 15), new Color(245, 158, 11)),  // Amber (MECH)
            new DeptTheme(new Color(236, 253, 245), new Color(110, 231, 183), new Color(6, 78, 59), new Color(16, 185, 129)),   // Emerald (Civil)
            new DeptTheme(new Color(253, 242, 248), new Color(249, 168, 212), new Color(131, 24, 67), new Color(236, 72, 153)),  // Pink (IT)
            new DeptTheme(new Color(245, 243, 255), new Color(196, 181, 253), new Color(76, 29, 149), new Color(139, 92, 246)),  // Purple (EE)
            new DeptTheme(new Color(255, 247, 237), new Color(253, 186, 116), new Color(124, 45, 18), new Color(249, 115, 22))   // Orange (Chem)
    };

    private static final DeptTheme VACANT_THEME = new DeptTheme(
            new Color(248, 250, 252), new Color(226, 232, 240), new Color(148, 163, 184), new Color(203, 213, 225));

    // ------------------------------------------------------------------
    //  Door Notices (Seating Notice Board)
    // ------------------------------------------------------------------

    /**
     * Printable Door Notices: Beautiful, visual 2D seating matrix rendered in Landscape A4.
     * Demonstrates bench desks with clear Left/Right slots for dual-seaters, centered
     * positions for single-seaters, prominent roll numbers, stream badges, and blackboard indicator.
     */
    @Transactional(readOnly = true)
    public byte[] doorNotices(String subjectCode) {
        List<SeatingAllocation> allocations = load(subjectCode);
        Map<ExamRoom, List<SeatingAllocation>> roomGroups = groupByRoom(allocations);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            // A4 Landscape: 842 pt width x 595 pt height
            Document document = new Document(PageSize.A4.rotate(), 22, 22, 18, 18);
            PdfWriter.getInstance(document, out);
            document.open();

            if (roomGroups.isEmpty()) {
                document.add(new Paragraph("EXAMINATION NOTICE BOARD", FONT_DOC_TITLE));
                document.add(new Paragraph("No seating arrangements generated yet. Please run the seating engine first.", FONT_DOC_SUBTITLE));
                document.close();
                return out.toByteArray();
            }

            int pageIndex = 0;
            for (Map.Entry<ExamRoom, List<SeatingAllocation>> entry : roomGroups.entrySet()) {
                if (pageIndex++ > 0) {
                    document.newPage();
                }
                ExamRoom room = entry.getKey();
                List<SeatingAllocation> roomAllocs = entry.getValue();

                // Build lookup map for O(1) seat access: "row_col_slot" -> SeatingAllocation
                Map<String, SeatingAllocation> seatMap = new HashMap<>();
                Set<String> departments = new LinkedHashSet<>();
                Set<String> subjects = new LinkedHashSet<>();
                for (SeatingAllocation a : roomAllocs) {
                    seatMap.put(a.getBench().getRowIndex() + "_" + a.getBench().getColIndex() + "_" + a.getSlotNumber(), a);
                    if (a.getStudent() != null && a.getStudent().getDepartment() != null) {
                        departments.add(a.getStudent().getDepartment());
                    }
                    if (a.getSubjectCode() != null && !a.getSubjectCode().isBlank()) {
                        subjects.add(a.getSubjectCode());
                    }
                }

                // 1) Header Top Bar (Title & Meta)
                document.add(buildNoticeHeader());

                // 2) Hall Info Summary Banner
                document.add(buildHallBanner(room, roomAllocs.size(), subjects));

                // 3) Front of Hall / Blackboard Visual Indicator
                document.add(buildFrontOfHallBar());

                // 4) 2D Interactive Bench Matrix
                document.add(buildBenchGrid(room, seatMap));

                // 5) Department Color Legend & Instructions
                document.add(buildNoticeFooter(departments));
            }

            document.close();
            return out.toByteArray();
        } catch (DocumentException | java.io.IOException e) {
            throw new IllegalStateException("Failed to generate door-notice PDF", e);
        }
    }

    // ------------------------------------------------------------------
    //  Invigilator Sheets
    // ------------------------------------------------------------------

    /**
     * Printable Invigilator Sheets: Per-hall student attendance rosters with signature columns.
     */
    @Transactional(readOnly = true)
    public byte[] invigilatorSheets(String subjectCode) {
        List<SeatingAllocation> allocations = load(subjectCode);
        Map<ExamRoom, List<SeatingAllocation>> roomGroups = groupByRoom(allocations);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4, 28, 28, 36, 36);
            PdfWriter.getInstance(document, out);
            document.open();

            if (roomGroups.isEmpty()) {
                document.add(new Paragraph("INVIGILATOR ATTENDANCE SHEET", FONT_INVIG_TITLE));
                document.add(new Paragraph("No student seating allocations available.", FONT_INVIG_SUB));
                document.close();
                return out.toByteArray();
            }

            int pageIndex = 0;
            for (Map.Entry<ExamRoom, List<SeatingAllocation>> entry : roomGroups.entrySet()) {
                if (pageIndex++ > 0) {
                    document.newPage();
                }
                ExamRoom room = entry.getKey();
                List<SeatingAllocation> roomAllocs = entry.getValue();

                // Header
                PdfPTable topBar = new PdfPTable(new float[]{3f, 1.5f});
                topBar.setWidthPercentage(100);
                topBar.setSpacingAfter(10f);

                PdfPCell titleCell = new PdfPCell();
                titleCell.setBorder(Rectangle.NO_BORDER);
                titleCell.addElement(new Paragraph("EXAMINATION ATTENDANCE & INVIGILATOR SHEET", FONT_INVIG_TITLE));
                String subjStr = roomAllocs.isEmpty() ? "N/A" : roomAllocs.get(0).getSubjectCode();
                titleCell.addElement(new Paragraph("Hall: " + room.getHallName() + "  |  Subject: " + subjStr
                        + "  |  Total Candidates: " + roomAllocs.size(), FONT_INVIG_SUB));
                topBar.addCell(titleCell);

                PdfPCell dateCell = new PdfPCell();
                dateCell.setBorder(Rectangle.NO_BORDER);
                dateCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                Paragraph datePara = new Paragraph("Date: " + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + "\nSession: Official Exam", FONT_BODY);
                datePara.setAlignment(Element.ALIGN_RIGHT);
                dateCell.addElement(datePara);
                topBar.addCell(dateCell);
                document.add(topBar);

                // Student Table
                PdfPTable table = new PdfPTable(new float[]{1.4f, 2.8f, 1.8f, 1.2f, 2.2f, 2.2f});
                table.setWidthPercentage(100);

                addInvigilatorHeader(table, "Roll No");
                addInvigilatorHeader(table, "Student Name");
                addInvigilatorHeader(table, "Department");
                addInvigilatorHeader(table, "Year");
                addInvigilatorHeader(table, "Bench / Seat");
                addInvigilatorHeader(table, "Candidate Signature");

                int rowNum = 0;
                for (SeatingAllocation a : roomAllocs) {
                    Color rowBg = (rowNum++ % 2 == 0) ? Color.WHITE : new Color(248, 250, 252);
                    String slotDesc = a.getSlotNumber() == 1 ? "Slot 1 (Left)" : "Slot 2 (Right)";
                    if (room.getBenchType() == ExamRoom.BenchType.SINGLE_SEATER) {
                        slotDesc = "Center Seat";
                    }
                    String benchPos = "Desk R" + a.getBench().getRowIndex() + "-C" + a.getBench().getColIndex() + " [" + slotDesc + "]";

                    table.addCell(createInvigCell(a.getStudent().getRollNo(), FONT_BODY_BOLD, rowBg, Element.ALIGN_CENTER));
                    table.addCell(createInvigCell(a.getStudent().getName(), FONT_BODY, rowBg, Element.ALIGN_LEFT));
                    table.addCell(createInvigCell(a.getStudent().getDepartment(), FONT_BODY, rowBg, Element.ALIGN_CENTER));
                    table.addCell(createInvigCell(a.getStudent().getYearSession(), FONT_BODY, rowBg, Element.ALIGN_CENTER));
                    table.addCell(createInvigCell(benchPos, FONT_BODY, rowBg, Element.ALIGN_CENTER));
                    table.addCell(createInvigCell("", FONT_BODY, rowBg, Element.ALIGN_CENTER));
                }
                document.add(table);

                // Invigilator Signature Footer
                PdfPTable signTable = new PdfPTable(new float[]{1f, 1f, 1f});
                signTable.setWidthPercentage(100);
                signTable.setSpacingBefore(18f);

                signTable.addCell(createSignCell("Invigilator 1 Signature: __________________\nName:"));
                signTable.addCell(createSignCell("Invigilator 2 Signature: __________________\nName:"));
                signTable.addCell(createSignCell("Total Present: ____   Total Absent: ____\nChief Superintendent: ________________"));
                document.add(signTable);
            }

            document.close();
            return out.toByteArray();
        } catch (DocumentException | java.io.IOException e) {
            throw new IllegalStateException("Failed to generate invigilator PDF", e);
        }
    }

    // ------------------------------------------------------------------
    //  Notice Board Component Builders
    // ------------------------------------------------------------------

    private PdfPTable buildNoticeHeader() {
        PdfPTable header = new PdfPTable(new float[]{2.5f, 1.5f});
        header.setWidthPercentage(100);
        header.setSpacingAfter(4f);

        PdfPCell left = new PdfPCell();
        left.setBorder(Rectangle.NO_BORDER);
        left.setPadding(0);
        left.addElement(new Paragraph("EXAMINATION SEATING ARRANGEMENT — NOTICE BOARD", FONT_DOC_TITLE));
        left.addElement(new Paragraph("OFFICIAL HALL ENTRY SEATING CHART • CHECK YOUR ROLL NUMBER & ASSIGNED SEAT", FONT_DOC_SUBTITLE));
        header.addCell(left);

        PdfPCell right = new PdfPCell();
        right.setBorder(Rectangle.NO_BORDER);
        right.setPadding(0);
        right.setHorizontalAlignment(Element.ALIGN_RIGHT);
        Paragraph meta = new Paragraph("DATE: " + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                + "  |  EXAM SESSION: OFFICIAL", FONT_DOC_META);
        meta.setAlignment(Element.ALIGN_RIGHT);
        right.addElement(meta);
        header.addCell(right);

        return header;
    }

    private PdfPTable buildHallBanner(ExamRoom room, int seatedCount, Set<String> subjects) {
        PdfPTable banner = new PdfPTable(new float[]{1.8f, 1.2f, 1.3f, 1.1f, 1.6f});
        banner.setWidthPercentage(100);
        banner.setSpacingAfter(4f);

        int capacity = room.getRows() * room.getColumns() * room.getBenchType().getSeatsPerBench();
        String typeLabel = room.getBenchType() == ExamRoom.BenchType.DUAL_SEATER ? "Dual-Seater (2/Bench)" : "Single-Seater (1/Bench)";
        String subjStr = subjects.isEmpty() ? "All Papers" : String.join(", ", subjects);

        banner.addCell(createBannerCell("HALL NAME", room.getHallName().toUpperCase(), true));
        banner.addCell(createBannerCell("GRID SIZE", room.getRows() + " Rows × " + room.getColumns() + " Cols", false));
        banner.addCell(createBannerCell("BENCH TYPE", typeLabel, false));
        banner.addCell(createBannerCell("SEATED", seatedCount + " / " + capacity + " Seats", false));
        banner.addCell(createBannerCell("SUBJECT(S)", subjStr, false));

        return banner;
    }

    private PdfPCell createBannerCell(String label, String value, boolean isPrimary) {
        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(isPrimary ? new Color(15, 23, 42) : new Color(30, 41, 59));
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(new Color(51, 65, 85));
        cell.setPadding(3.5f);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        Paragraph p = new Paragraph();
        p.add(new Phrase(label + "\n", FONT_BANNER_ITEM));
        p.add(new Phrase(value, isPrimary ? FONT_BANNER_ROOM : FONT_BANNER_BOLD));
        cell.addElement(p);
        return cell;
    }

    private PdfPTable buildFrontOfHallBar() {
        PdfPTable table = new PdfPTable(1);
        table.setWidthPercentage(100);
        table.setSpacingAfter(4f);

        PdfPCell cell = new PdfPCell();
        cell.setBackgroundColor(new Color(241, 245, 249)); // Slate 100
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(new Color(203, 213, 225));     // Slate 300
        cell.setBorderWidth(1f);
        cell.setPadding(3f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);

        Paragraph p = new Paragraph("▲  ▲  ▲   FRONT OF EXAMINATION HALL  /  BLACKBOARD  /  PODIUM  (ROW 1)   ▲  ▲  ▲", FONT_FRONT_BAR);
        p.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(p);

        table.addCell(cell);
        return table;
    }

    private PdfPTable buildBenchGrid(ExamRoom room, Map<String, SeatingAllocation> seatMap) {
        int cols = room.getColumns();
        PdfPTable grid = new PdfPTable(cols);
        grid.setWidthPercentage(100);

        boolean isDual = room.getBenchType() == ExamRoom.BenchType.DUAL_SEATER;

        for (int r = 1; r <= room.getRows(); r++) {
            for (int c = 1; c <= cols; c++) {
                SeatingAllocation a1 = seatMap.get(r + "_" + c + "_1");
                SeatingAllocation a2 = seatMap.get(r + "_" + c + "_2");

                PdfPTable benchTable = isDual
                        ? createDualBenchTable(r, c, a1, a2, cols)
                        : createSingleBenchTable(r, c, a1, cols);

                PdfPCell gridCell = new PdfPCell(benchTable);
                gridCell.setBorder(Rectangle.NO_BORDER);
                gridCell.setPadding(2.5f);
                grid.addCell(gridCell);
            }
        }

        return grid;
    }

    /**
     * Creates a Dual-Seater Bench Card:
     * Shows a dark header bar with desk coordinates, then two visually distinct seat boxes:
     * Slot 1 on the Left and Slot 2 on the Right side of the bench.
     */
    private PdfPTable createDualBenchTable(int row, int col, SeatingAllocation a1, SeatingAllocation a2, int totalCols) {
        PdfPTable bench = new PdfPTable(new float[]{1f, 1f});
        bench.setWidthPercentage(100);

        // Header: Desk Coordinates & Bench Type
        PdfPCell header = new PdfPCell();
        header.setColspan(2);
        header.setBackgroundColor(new Color(51, 65, 85)); // Slate 700
        header.setBorder(Rectangle.BOX);
        header.setBorderColor(new Color(30, 41, 59));
        header.setPadding(2.5f);
        header.setHorizontalAlignment(Element.ALIGN_CENTER);
        Paragraph hPara = new Paragraph("DESK (R" + row + ", C" + col + ")  •  DUAL-SEATER", FONT_BENCH_HEADER);
        hPara.setAlignment(Element.ALIGN_CENTER);
        header.addElement(hPara);
        bench.addCell(header);

        // Seat 1 (Left Seat)
        bench.addCell(createSeatCell(a1, "SLOT 1 [LEFT]", true, totalCols));

        // Seat 2 (Right Seat)
        bench.addCell(createSeatCell(a2, "SLOT 2 [RIGHT]", false, totalCols));

        return bench;
    }

    /**
     * Creates a Single-Seater Bench Card:
     * Shows a dark header bar with desk coordinates, and a single centered seat position.
     */
    private PdfPTable createSingleBenchTable(int row, int col, SeatingAllocation a, int totalCols) {
        PdfPTable bench = new PdfPTable(1);
        bench.setWidthPercentage(100);

        // Header
        PdfPCell header = new PdfPCell();
        header.setBackgroundColor(new Color(51, 65, 85)); // Slate 700
        header.setBorder(Rectangle.BOX);
        header.setBorderColor(new Color(30, 41, 59));
        header.setPadding(2.5f);
        header.setHorizontalAlignment(Element.ALIGN_CENTER);
        Paragraph hPara = new Paragraph("DESK (R" + row + ", C" + col + ")  •  SINGLE-SEATER", FONT_BENCH_HEADER);
        hPara.setAlignment(Element.ALIGN_CENTER);
        header.addElement(hPara);
        bench.addCell(header);

        // Single Centered Seat
        bench.addCell(createSeatCell(a, "CENTER SEAT (SLOT 1)", true, totalCols));

        return bench;
    }

    /**
     * Renders an individual seat cell on the bench.
     * When occupied: Displays Roll Number in prominent bold font, Stream/Dept badge in distinct color, and student name.
     * When vacant: Displays a clean muted indicator.
     */
    private PdfPCell createSeatCell(SeatingAllocation alloc, String slotLabel, boolean isLeftOrSingle, int totalCols) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(new Color(148, 163, 184)); // Slate 400
        cell.setPadding(3f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);

        // Dynamic font scaling for dense hall layouts (4, 5, 6+ columns)
        float rollSize = totalCols <= 3 ? 9.5f : (totalCols <= 4 ? 8.5f : (totalCols <= 5 ? 7.5f : 6.8f));
        float labelSize = totalCols <= 4 ? 6.2f : 5.5f;
        float deptSize = totalCols <= 4 ? 6.8f : 6.0f;
        float nameSize = totalCols <= 4 ? 6.5f : 5.8f;

        if (alloc == null || alloc.getStudent() == null) {
            // Vacant / Empty Seat
            cell.setBackgroundColor(VACANT_THEME.bg);
            cell.setBorderColor(VACANT_THEME.border);

            Paragraph p = new Paragraph();
            p.setAlignment(Element.ALIGN_CENTER);
            Font fLabel = new Font(Font.HELVETICA, labelSize, Font.BOLD, VACANT_THEME.text);
            Font fVacant = new Font(Font.HELVETICA, rollSize, Font.ITALIC, VACANT_THEME.text);

            p.add(new Phrase(slotLabel + "\n", fLabel));
            p.add(new Phrase("— VACANT —\n", fVacant));
            p.add(new Phrase("No Student", new Font(Font.HELVETICA, nameSize, Font.NORMAL, VACANT_THEME.text)));
            cell.addElement(p);
            return cell;
        }

        Student s = alloc.getStudent();
        String dept = (s.getDepartment() != null && !s.getDepartment().isBlank()) ? s.getDepartment() : "General";
        DeptTheme theme = getTheme(dept);

        cell.setBackgroundColor(theme.bg);
        cell.setBorderColor(theme.border);

        Paragraph p = new Paragraph();
        p.setAlignment(Element.ALIGN_CENTER);

        // 1) Slot badge (e.g. "SLOT 1 [LEFT]")
        Font fSlot = new Font(Font.HELVETICA, labelSize, Font.BOLD, new Color(71, 85, 105));
        p.add(new Phrase(slotLabel + "\n", fSlot));

        // 2) Roll Number in prominent bold font
        Font fRoll = new Font(Font.HELVETICA, rollSize, Font.BOLD, new Color(15, 23, 42));
        p.add(new Phrase(s.getRollNo() + "\n", fRoll));

        // 3) Department / Stream Badge
        String yr = (s.getYearSession() != null && !s.getYearSession().isBlank()) ? " • " + s.getYearSession() : "";
        Font fDept = new Font(Font.HELVETICA, deptSize, Font.BOLD, theme.text);
        p.add(new Phrase(dept + yr + "\n", fDept));

        // 4) Student Name (truncated to fit gracefully)
        String name = truncate(s.getName() != null ? s.getName() : "", 16);
        Font fName = new Font(Font.HELVETICA, nameSize, Font.NORMAL, new Color(51, 65, 85));
        p.add(new Phrase(name, fName));

        cell.addElement(p);
        return cell;
    }

    private PdfPTable buildNoticeFooter(Set<String> departments) {
        PdfPTable footer = new PdfPTable(1);
        footer.setWidthPercentage(100);
        footer.setSpacingBefore(5f);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(new Color(226, 232, 240));
        cell.setBackgroundColor(new Color(248, 250, 252));
        cell.setPadding(4f);

        // Department Legend Swatches
        Paragraph legend = new Paragraph();
        legend.setAlignment(Element.ALIGN_CENTER);
        legend.add(new Phrase("STREAM LEGEND:  ", new Font(Font.HELVETICA, 7.5f, Font.BOLD, new Color(15, 23, 42))));

        for (String dept : departments) {
            DeptTheme t = getTheme(dept);
            Font fSwatch = new Font(Font.HELVETICA, 8.5f, Font.BOLD, t.dot);
            Font fText = new Font(Font.HELVETICA, 7.2f, Font.BOLD, t.text);

            legend.add(new Phrase("■ ", fSwatch));
            legend.add(new Phrase(dept + "    ", fText));
        }

        if (departments.isEmpty()) {
            legend.add(new Phrase("No students seated.", new Font(Font.HELVETICA, 7.2f, Font.ITALIC, Color.GRAY)));
        }

        cell.addElement(legend);

        // Instruction caption
        Paragraph note = new Paragraph(
                "NOTICE TO CANDIDATES: Locate your desk by Row and Column. On dual-seater benches, strictly sit on your designated side (Slot 1 on Left, Slot 2 on Right). Do not exchange seats.",
                new Font(Font.HELVETICA, 6.5f, Font.ITALIC, new Color(100, 116, 139)));
        note.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(note);

        footer.addCell(cell);
        return footer;
    }

    // ------------------------------------------------------------------
    //  Invigilator Helpers
    // ------------------------------------------------------------------

    private void addInvigilatorHeader(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, FONT_TABLE_HEADER));
        cell.setBackgroundColor(new Color(15, 23, 42)); // Slate 900
        cell.setBorderColor(new Color(51, 65, 85));
        cell.setPadding(4.5f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        table.addCell(cell);
    }

    private PdfPCell createInvigCell(String text, Font font, Color bg, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(bg);
        cell.setBorderColor(new Color(226, 232, 240));
        cell.setPadding(4f);
        cell.setHorizontalAlignment(align);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return cell;
    }

    private PdfPCell createSignCell(String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, new Font(Font.HELVETICA, 8, Font.NORMAL, new Color(30, 41, 59))));
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(new Color(203, 213, 225));
        cell.setBackgroundColor(new Color(248, 250, 252));
        cell.setPadding(6f);
        return cell;
    }

    // ------------------------------------------------------------------
    //  General Helpers
    // ------------------------------------------------------------------

    private List<SeatingAllocation> load(String subjectCode) {
        return allocationRepository.findAllWithDetails(
                subjectCode == null || subjectCode.isBlank() ? null : subjectCode);
    }

    private Map<ExamRoom, List<SeatingAllocation>> groupByRoom(List<SeatingAllocation> allocations) {
        Map<ExamRoom, List<SeatingAllocation>> map = new LinkedHashMap<>();
        for (SeatingAllocation a : allocations) {
            map.computeIfAbsent(a.getBench().getRoom(), k -> new ArrayList<>()).add(a);
        }
        for (List<SeatingAllocation> list : map.values()) {
            list.sort(Comparator.comparingInt((SeatingAllocation a) -> a.getBench().getRowIndex())
                    .thenComparingInt(a -> a.getBench().getColIndex())
                    .thenComparingInt(SeatingAllocation::getSlotNumber));
        }
        return map;
    }

    private static DeptTheme getTheme(String deptName) {
        if (deptName == null || deptName.isBlank()) {
            return PALETTE[0];
        }
        String lower = deptName.toLowerCase();
        if (lower.contains("comp") || lower.contains("cse")) return PALETTE[0];
        if (lower.contains("elec") || lower.contains("ece")) return PALETTE[1];
        if (lower.contains("mech")) return PALETTE[2];
        if (lower.contains("civil") || lower.contains("ce")) return PALETTE[3];
        if (lower.contains("info") || lower.contains("it")) return PALETTE[4];
        if (lower.contains("power") || lower.contains("ee")) return PALETTE[5];

        int hash = Math.abs(deptName.hashCode());
        return PALETTE[hash % PALETTE.length];
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) return "";
        return text.length() <= maxLen ? text : text.substring(0, maxLen - 1) + "…";
    }
}
