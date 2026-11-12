package com.examseating.service;

import com.examseating.entity.ExamRoom;
import com.examseating.entity.SeatingAllocation;
import com.examseating.entity.Student;
import com.examseating.entity.StudentAttributes;
import com.examseating.entity.StudentIdentityScheme;
import com.examseating.repository.SeatingAllocationRepository;
import com.examseating.tenancy.TenantSupport;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * Schema-agnostic OpenPDF export service for printable Door Notices (Landscape A4
 * 2D hall maps) and Invigilator Attendance Sheets (Portrait A4 rosters).
 */
@Service
public class PdfExportService {

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
    private final TenantSupport tenant;
    private final StudentIdentityService identityService;

    public PdfExportService(SeatingAllocationRepository allocationRepository, TenantSupport tenant) {
        this(allocationRepository, tenant, null);
    }

    @Autowired
    public PdfExportService(SeatingAllocationRepository allocationRepository,
                            TenantSupport tenant,
                            StudentIdentityService identityService) {
        this.allocationRepository = allocationRepository;
        this.tenant = tenant;
        this.identityService = identityService;
    }

    private static final class CohortTheme {
        final Color bg;
        final Color border;
        final Color text;
        final Color dot;

        CohortTheme(Color bg, Color border, Color text, Color dot) {
            this.bg = bg;
            this.border = border;
            this.text = text;
            this.dot = dot;
        }
    }

    private static final CohortTheme[] PALETTE = {
            new CohortTheme(new Color(238, 242, 255), new Color(165, 180, 252), new Color(49, 46, 129), new Color(99, 102, 241)),
            new CohortTheme(new Color(236, 254, 255), new Color(103, 232, 249), new Color(22, 78, 99), new Color(6, 182, 212)),
            new CohortTheme(new Color(254, 243, 199), new Color(252, 211, 77), new Color(120, 53, 15), new Color(245, 158, 11)),
            new CohortTheme(new Color(236, 253, 245), new Color(110, 231, 183), new Color(6, 78, 59), new Color(16, 185, 129)),
            new CohortTheme(new Color(253, 242, 248), new Color(249, 168, 212), new Color(131, 24, 67), new Color(236, 72, 153)),
            new CohortTheme(new Color(245, 243, 255), new Color(196, 181, 253), new Color(76, 29, 149), new Color(139, 92, 246)),
            new CohortTheme(new Color(255, 247, 237), new Color(253, 186, 116), new Color(124, 45, 18), new Color(249, 115, 22))
    };

    private static final CohortTheme VACANT_THEME = new CohortTheme(
            new Color(248, 250, 252), new Color(226, 232, 240), new Color(148, 163, 184), new Color(203, 213, 225));

    @Transactional(readOnly = true)
    public byte[] doorNotices(String scopeKey) {
        List<SeatingAllocation> allocations = load(scopeKey);
        Map<ExamRoom, List<SeatingAllocation>> roomGroups = groupByRoom(allocations);

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Document document = new Document(PageSize.A4.rotate(), 22, 22, 18, 18);
            PdfWriter.getInstance(document, out);
            document.open();

            if (roomGroups.isEmpty()) {
                document.add(new Paragraph("EXAMINATION NOTICE BOARD", FONT_DOC_TITLE));
                document.add(new Paragraph("No seating arrangements generated yet.", FONT_DOC_SUBTITLE));
                document.close();
                return out.toByteArray();
            }

            StudentIdentityScheme scheme = identityService == null ? null : identityService.currentScheme().orElse(null);
            List<String> classmateKeys = scheme == null ? List.of() : scheme.classmateAttributeList();

            int pageIndex = 0;
            for (Map.Entry<ExamRoom, List<SeatingAllocation>> entry : roomGroups.entrySet()) {
                if (pageIndex++ > 0) {
                    document.newPage();
                }
                ExamRoom room = entry.getKey();
                List<SeatingAllocation> roomAllocs = entry.getValue();

                Map<String, SeatingAllocation> seatMap = new HashMap<>();
                Set<String> cohorts = new LinkedHashSet<>();
                Set<String> scopes = new LinkedHashSet<>();
                for (SeatingAllocation a : roomAllocs) {
                    seatMap.put(a.getBench().getRowIndex() + "_" + a.getBench().getColIndex() + "_" + a.getSlotNumber(), a);
                    if (a.getStudent() != null) {
                        String cohort = StudentAttributes.cohortLabel(a.getStudent(), classmateKeys);
                        if (!cohort.isBlank()) {
                            cohorts.add(cohort);
                        }
                    }
                    if (a.getScopeKey() != null && !a.getScopeKey().isBlank()) {
                        scopes.add(a.getScopeKey());
                    }
                }

                document.add(buildNoticeHeader());
                document.add(buildHallBanner(room, roomAllocs.size(), scopes));
                document.add(buildFrontOfHallBar());
                document.add(buildBenchGrid(room, seatMap, scheme, classmateKeys));
                document.add(buildNoticeFooter(cohorts));
            }

            document.close();
            return out.toByteArray();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Failed to generate door-notice PDF", e);
        }
    }

    @Transactional(readOnly = true)
    public byte[] invigilatorSheets(String scopeKey) {
        List<SeatingAllocation> allocations = load(scopeKey);
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

            StudentIdentityScheme scheme = identityService == null ? null : identityService.currentScheme().orElse(null);
            List<String> classmateKeys = scheme == null ? List.of() : scheme.classmateAttributeList();

            int pageIndex = 0;
            for (Map.Entry<ExamRoom, List<SeatingAllocation>> entry : roomGroups.entrySet()) {
                if (pageIndex++ > 0) {
                    document.newPage();
                }
                ExamRoom room = entry.getKey();
                List<SeatingAllocation> roomAllocs = entry.getValue();

                PdfPTable topBar = new PdfPTable(new float[]{3f, 1.5f});
                topBar.setWidthPercentage(100);
                topBar.setSpacingAfter(10f);

                PdfPCell titleCell = new PdfPCell();
                titleCell.setBorder(Rectangle.NO_BORDER);
                titleCell.addElement(new Paragraph("EXAMINATION ATTENDANCE & INVIGILATOR SHEET", FONT_INVIG_TITLE));
                String roundStr = (roomAllocs.isEmpty() || roomAllocs.get(0).getScopeKey() == null)
                        ? "Whole Roster" : roomAllocs.get(0).getScopeKey();
                titleCell.addElement(new Paragraph("Hall: " + safe(room.getHallName()) + "  |  Round: " + roundStr
                        + "  |  Total Candidates: " + roomAllocs.size(), FONT_INVIG_SUB));
                topBar.addCell(titleCell);

                PdfPCell dateCell = new PdfPCell();
                dateCell.setBorder(Rectangle.NO_BORDER);
                dateCell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                Paragraph datePara = new Paragraph("Date: " + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
                        + "\nSession: Official Exam", FONT_BODY);
                datePara.setAlignment(Element.ALIGN_RIGHT);
                dateCell.addElement(datePara);
                topBar.addCell(dateCell);
                document.add(topBar);

                PdfPTable table = new PdfPTable(new float[]{1.8f, 2.8f, 2.2f, 2.4f, 2.2f});
                table.setWidthPercentage(100);

                addInvigilatorHeader(table, "Student ID");
                addInvigilatorHeader(table, "Student Name");
                addInvigilatorHeader(table, "Cohort / Group");
                addInvigilatorHeader(table, "Bench / Seat");
                addInvigilatorHeader(table, "Candidate Signature");

                int rowNum = 0;
                for (SeatingAllocation a : roomAllocs) {
                    Color rowBg = (rowNum++ % 2 == 0) ? Color.WHITE : new Color(248, 250, 252);
                    String slotDesc = a.getSlotNumber() == 1 ? "Slot 1 (Left)" : "Slot 2 (Right)";
                    if (room.getBenchType() == ExamRoom.BenchType.SINGLE_SEATER) {
                        slotDesc = "Center Seat";
                    }
                    String benchPos = "Desk R" + a.getBench().getRowIndex() + "-C"
                            + a.getBench().getColIndex() + " [" + slotDesc + "]";
                    String cohort = StudentAttributes.cohortLabel(a.getStudent(), classmateKeys);

                    table.addCell(createInvigCell(
                            StudentAttributes.displayLabel(a.getStudent(), scheme), FONT_BODY_BOLD, rowBg, Element.ALIGN_CENTER));
                    table.addCell(createInvigCell(a.getStudent().getName(), FONT_BODY, rowBg, Element.ALIGN_LEFT));
                    table.addCell(createInvigCell(cohort, FONT_BODY, rowBg, Element.ALIGN_CENTER));
                    table.addCell(createInvigCell(benchPos, FONT_BODY, rowBg, Element.ALIGN_CENTER));
                    table.addCell(createInvigCell("", FONT_BODY, rowBg, Element.ALIGN_CENTER));
                }
                document.add(table);

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
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Failed to generate invigilator PDF", e);
        }
    }

    private PdfPTable buildNoticeHeader() {
        PdfPTable header = new PdfPTable(new float[]{2.5f, 1.5f});
        header.setWidthPercentage(100);
        header.setSpacingAfter(4f);

        PdfPCell left = new PdfPCell();
        left.setBorder(Rectangle.NO_BORDER);
        left.setPadding(0);
        left.addElement(new Paragraph("EXAMINATION SEATING ARRANGEMENT — NOTICE BOARD", FONT_DOC_TITLE));
        left.addElement(new Paragraph("OFFICIAL HALL ENTRY SEATING CHART • CHECK YOUR STUDENT ID & ASSIGNED SEAT", FONT_DOC_SUBTITLE));
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

    private PdfPTable buildHallBanner(ExamRoom room, int seatedCount, Set<String> scopes) {
        PdfPTable banner = new PdfPTable(new float[]{1.8f, 1.2f, 1.3f, 1.1f, 1.6f});
        banner.setWidthPercentage(100);
        banner.setSpacingAfter(4f);

        int capacity = room.getRows() * room.getColumns() * room.getBenchType().getSeatsPerBench();
        String typeLabel = room.getBenchType() == ExamRoom.BenchType.DUAL_SEATER
                ? "Dual-Seater (2/Bench)" : "Single-Seater (1/Bench)";
        String roundStr = scopes.isEmpty() ? "Whole Roster" : String.join(", ", scopes);

        banner.addCell(createBannerCell("HALL NAME", safe(room.getHallName()).toUpperCase(), true));
        banner.addCell(createBannerCell("GRID SIZE", room.getRows() + " Rows × " + room.getColumns() + " Cols", false));
        banner.addCell(createBannerCell("BENCH TYPE", typeLabel, false));
        banner.addCell(createBannerCell("SEATED", seatedCount + " / " + capacity + " Seats", false));
        banner.addCell(createBannerCell("EXAM ROUND", roundStr, false));
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
        cell.setBackgroundColor(new Color(241, 245, 249));
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(new Color(203, 213, 225));
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

    private PdfPTable buildBenchGrid(ExamRoom room,
                                     Map<String, SeatingAllocation> seatMap,
                                     StudentIdentityScheme scheme,
                                     List<String> classmateKeys) {
        int cols = room.getColumns();
        PdfPTable grid = new PdfPTable(cols);
        grid.setWidthPercentage(100);
        boolean isDual = room.getBenchType() == ExamRoom.BenchType.DUAL_SEATER;

        for (int r = 1; r <= room.getRows(); r++) {
            for (int c = 1; c <= cols; c++) {
                SeatingAllocation a1 = seatMap.get(r + "_" + c + "_1");
                SeatingAllocation a2 = seatMap.get(r + "_" + c + "_2");
                PdfPTable benchTable = isDual
                        ? createDualBenchTable(r, c, a1, a2, cols, scheme, classmateKeys)
                        : createSingleBenchTable(r, c, a1, cols, scheme, classmateKeys);
                PdfPCell gridCell = new PdfPCell(benchTable);
                gridCell.setBorder(Rectangle.NO_BORDER);
                gridCell.setPadding(2.5f);
                grid.addCell(gridCell);
            }
        }
        return grid;
    }

    private PdfPTable createDualBenchTable(int row, int col, SeatingAllocation a1, SeatingAllocation a2,
                                           int totalCols, StudentIdentityScheme scheme, List<String> classmateKeys) {
        PdfPTable bench = new PdfPTable(new float[]{1f, 1f});
        bench.setWidthPercentage(100);

        PdfPCell header = new PdfPCell();
        header.setColspan(2);
        header.setBackgroundColor(new Color(51, 65, 85));
        header.setBorder(Rectangle.BOX);
        header.setBorderColor(new Color(30, 41, 59));
        header.setPadding(2.5f);
        header.setHorizontalAlignment(Element.ALIGN_CENTER);
        Paragraph hPara = new Paragraph("DESK (R" + row + ", C" + col + ")  •  DUAL-SEATER", FONT_BENCH_HEADER);
        hPara.setAlignment(Element.ALIGN_CENTER);
        header.addElement(hPara);
        bench.addCell(header);

        bench.addCell(createSeatCell(a1, "SLOT 1 [LEFT]", totalCols, scheme, classmateKeys));
        bench.addCell(createSeatCell(a2, "SLOT 2 [RIGHT]", totalCols, scheme, classmateKeys));
        return bench;
    }

    private PdfPTable createSingleBenchTable(int row, int col, SeatingAllocation a,
                                             int totalCols, StudentIdentityScheme scheme, List<String> classmateKeys) {
        PdfPTable bench = new PdfPTable(1);
        bench.setWidthPercentage(100);

        PdfPCell header = new PdfPCell();
        header.setBackgroundColor(new Color(51, 65, 85));
        header.setBorder(Rectangle.BOX);
        header.setBorderColor(new Color(30, 41, 59));
        header.setPadding(2.5f);
        header.setHorizontalAlignment(Element.ALIGN_CENTER);
        Paragraph hPara = new Paragraph("DESK (R" + row + ", C" + col + ")  •  SINGLE-SEATER", FONT_BENCH_HEADER);
        hPara.setAlignment(Element.ALIGN_CENTER);
        header.addElement(hPara);
        bench.addCell(header);

        bench.addCell(createSeatCell(a, "CENTER SEAT (SLOT 1)", totalCols, scheme, classmateKeys));
        return bench;
    }

    private PdfPCell createSeatCell(SeatingAllocation alloc, String slotLabel, int totalCols,
                                    StudentIdentityScheme scheme, List<String> classmateKeys) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(new Color(148, 163, 184));
        cell.setPadding(3f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);

        float idSize = totalCols <= 3 ? 9.5f : (totalCols <= 4 ? 8.5f : (totalCols <= 5 ? 7.5f : 6.8f));
        float labelSize = totalCols <= 4 ? 6.2f : 5.5f;
        float cohortSize = totalCols <= 4 ? 6.8f : 6.0f;
        float nameSize = totalCols <= 4 ? 6.5f : 5.8f;

        if (alloc == null || alloc.getStudent() == null) {
            cell.setBackgroundColor(VACANT_THEME.bg);
            cell.setBorderColor(VACANT_THEME.border);
            Paragraph p = new Paragraph();
            p.setAlignment(Element.ALIGN_CENTER);
            p.add(new Phrase(slotLabel + "\n", new Font(Font.HELVETICA, labelSize, Font.BOLD, VACANT_THEME.text)));
            p.add(new Phrase("— VACANT —\n", new Font(Font.HELVETICA, idSize, Font.ITALIC, VACANT_THEME.text)));
            p.add(new Phrase("No Student", new Font(Font.HELVETICA, nameSize, Font.NORMAL, VACANT_THEME.text)));
            cell.addElement(p);
            return cell;
        }

        Student s = alloc.getStudent();
        String cohort = StudentAttributes.cohortLabel(s, classmateKeys);
        String cohortDisplay = !cohort.isBlank() ? cohort : "Cohort";
        CohortTheme theme = getTheme(cohortDisplay);

        cell.setBackgroundColor(theme.bg);
        cell.setBorderColor(theme.border);

        Paragraph p = new Paragraph();
        p.setAlignment(Element.ALIGN_CENTER);
        p.add(new Phrase(slotLabel + "\n", new Font(Font.HELVETICA, labelSize, Font.BOLD, new Color(71, 85, 105))));
        p.add(new Phrase(StudentAttributes.displayLabel(s, scheme) + "\n",
                new Font(Font.HELVETICA, idSize, Font.BOLD, new Color(15, 23, 42))));
        p.add(new Phrase(cohortDisplay + "\n", new Font(Font.HELVETICA, cohortSize, Font.BOLD, theme.text)));
        p.add(new Phrase(truncate(s.getName(), 16), new Font(Font.HELVETICA, nameSize, Font.NORMAL, new Color(51, 65, 85))));

        cell.addElement(p);
        return cell;
    }

    private PdfPTable buildNoticeFooter(Set<String> cohorts) {
        PdfPTable footer = new PdfPTable(1);
        footer.setWidthPercentage(100);
        footer.setSpacingBefore(5f);

        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.BOX);
        cell.setBorderColor(new Color(226, 232, 240));
        cell.setBackgroundColor(new Color(248, 250, 252));
        cell.setPadding(4f);

        Paragraph legend = new Paragraph();
        legend.setAlignment(Element.ALIGN_CENTER);
        legend.add(new Phrase("COHORT LEGEND:  ", new Font(Font.HELVETICA, 7.5f, Font.BOLD, new Color(15, 23, 42))));

        for (String cohort : cohorts) {
            CohortTheme t = getTheme(cohort);
            legend.add(new Phrase("■ ", new Font(Font.HELVETICA, 8.5f, Font.BOLD, t.dot)));
            legend.add(new Phrase(cohort + "    ", new Font(Font.HELVETICA, 7.2f, Font.BOLD, t.text)));
        }
        if (cohorts.isEmpty()) {
            legend.add(new Phrase("No students seated.", new Font(Font.HELVETICA, 7.2f, Font.ITALIC, Color.GRAY)));
        }
        cell.addElement(legend);

        Paragraph note = new Paragraph(
                "NOTICE TO CANDIDATES: Locate your desk by Row and Column. On dual-seater benches, strictly sit on your designated side (Slot 1 on Left, Slot 2 on Right).",
                new Font(Font.HELVETICA, 6.5f, Font.ITALIC, new Color(100, 116, 139)));
        note.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(note);

        footer.addCell(cell);
        return footer;
    }

    private void addInvigilatorHeader(PdfPTable table, String text) {
        PdfPCell cell = new PdfPCell(new Phrase(text, FONT_TABLE_HEADER));
        cell.setBackgroundColor(new Color(15, 23, 42));
        cell.setBorderColor(new Color(51, 65, 85));
        cell.setPadding(4.5f);
        cell.setHorizontalAlignment(Element.ALIGN_CENTER);
        cell.setVerticalAlignment(Element.ALIGN_MIDDLE);
        table.addCell(cell);
    }

    private PdfPCell createInvigCell(String text, Font font, Color bg, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
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

    private List<SeatingAllocation> load(String scopeKey) {
        List<SeatingAllocation> all = allocationRepository.findAllWithDetails(
                scopeKey == null || scopeKey.isBlank() ? null : scopeKey.trim());
        return tenant != null ? tenant.visible(all) : all;
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

    private static CohortTheme getTheme(String cohortName) {
        if (cohortName == null || cohortName.isBlank()) {
            return PALETTE[0];
        }
        return PALETTE[Math.floorMod(cohortName.hashCode(), PALETTE.length)];
    }

    private static String truncate(String text, int maxLen) {
        if (text == null) {
            return "";
        }
        return text.length() <= maxLen ? text : text.substring(0, maxLen - 1) + "…";
    }

    private static String safe(String text) {
        return text == null || text.isBlank() ? "(unnamed hall)" : text;
    }
}
