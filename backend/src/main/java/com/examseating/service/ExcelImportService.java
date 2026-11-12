package com.examseating.service;

import com.examseating.dto.IdentitySchemeView;
import com.examseating.dto.ImportPreview;
import com.examseating.dto.ImportPreview.ColumnPreview;
import com.examseating.dto.ImportPreview.IdentityOption;
import com.examseating.dto.ImportResult;
import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.Student;
import com.examseating.entity.StudentAttributes;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.StudentRepository;
import com.examseating.tenancy.TenantSupport;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Schema-agnostic Apache POI importer for arbitrary institutional student
 * rosters and exam-hall spreadsheets.
 *
 * <p><b>Zero hardcoded student aliases:</b> only display-name headers
 * ({@code Name}, {@code Student Name}, {@code Candidate Name}) populate
 * {@link Student#getName()}. Every other column in the uploaded spreadsheet
 * is stored directly under its exact normalized header key inside
 * {@link Student#getAttributes()} — nothing is silently remapped or merged.</p>
 */
@Service
@RequiredArgsConstructor
public class ExcelImportService {

    private final StudentRepository studentRepository;
    private final ExamRoomRepository examRoomRepository;
    private final TransactionTemplate transactionTemplate;
    private final TenantSupport tenant;
    private final QuotaService quotas;
    private final StudentIdentityService identityService;

    private final DataFormatter formatter = new DataFormatter();

    private static final int MAX_NAME = 100;
    private static final int MAX_ATTRIBUTE_VALUE = 255;
    private static final int MAX_HALL_NAME = 100;
    private static final int MAX_DIMENSION = 500;
    private static final int SAMPLES_PER_COLUMN = 3;
    private static final int MAX_IDENTITY_SUGGESTIONS = 12;

    private static final Map<String, String[]> ROOM_HEADER_ALIASES = Map.of(
            "hallname", new String[]{"hallname", "hall", "room", "roomname", "examhall"},
            "rows", new String[]{"rows", "benchrows", "numrows"},
            "columns", new String[]{"columns", "cols", "benchcolumns", "numcolumns"},
            "benchtype", new String[]{"benchtype", "desktype", "seatingtype", "bench"}
    );

    // ------------------------------------------------------------------
    //  Students — preview & import
    // ------------------------------------------------------------------

    public ImportPreview previewStudents(InputStream input) throws IOException {
        ParsedSheet sheet = parseStudentSheet(input);
        List<ColumnPreview> columns = new ArrayList<>();
        for (SheetColumn column : sheet.columns()) {
            columns.add(new ColumnPreview(
                    column.key(),
                    column.title(),
                    StudentAttributes.NAME.equals(column.key()) ? "name" : "attribute",
                    isComplete(sheet, column),
                    isUnique(sheet, column),
                    distinctValues(sheet, column),
                    samples(sheet, column)));
        }
        List<IdentityOption> suggestions = suggestIdentities(sheet);
        List<IdentityOption> classmateSuggestions = suggestClassmateGroups(sheet);
        IdentitySchemeView scheme = identityService.currentScheme()
                .map(s -> new IdentitySchemeView(
                        s.getId(),
                        s.describe(),
                        s.attributeList(),
                        s.effectiveDisplayAttribute(),
                        s.classmateAttributeList(),
                        s.getScopeAttribute()))
                .orElse(null);
        return new ImportPreview(columns, suggestions, classmateSuggestions, scheme, sheet.rows().size());
    }

    public ImportResult importStudents(InputStream input) throws IOException {
        return importStudents(input, List.of());
    }

    public ImportResult importStudents(InputStream input, List<String> identityAttributes) throws IOException {
        ParsedSheet sheet = parseStudentSheet(input);
        IdentityChoice choice = resolveIdentity(sheet, identityAttributes);

        quotas.checkStudents(sheet.rows().size());
        List<String> errors = new ArrayList<>();
        List<String> notes = new ArrayList<>(choice.notes());
        Set<String> seen = new HashSet<>();
        int imported = 0;
        int total = 0;
        for (int i = 0; i < sheet.rows().size(); i++) {
            RowValues row = sheet.rows().get(i);
            total++;
            try {
                rejectUnreliableNumbers(sheet, i, choice.attributes());
                Student student = buildStudent(sheet, row, choice.attributes());
                validateStudent(student);
                tenant.stamp(student);
                String key = identityService.identityKey(student, choice.attributes());
                if (key == null) {
                    throw new IllegalArgumentException("this row cannot be identified — "
                            + describe(sheet, choice.attributes()) + " is empty");
                }
                if (!seen.add(key)) {
                    throw new IllegalArgumentException(
                            "duplicate student identity '" + key.replace("|", " · ")
                                    + "' — the same student appears twice in the file");
                }
                transactionTemplate.executeWithoutResult(status -> studentRepository.save(student));
                imported++;
            } catch (Exception ex) {
                errors.add("Row " + (i + 2) + ": " + friendlyMessage(ex));
            }
        }
        Map<String, String> columnLabels = new LinkedHashMap<>();
        for (SheetColumn col : sheet.columns()) {
            if (!StudentAttributes.NAME.equals(col.key())) {
                columnLabels.put(col.key(), col.title());
            }
        }
        identityService.recordColumnLabels(columnLabels);
        return ImportResult.of(total, imported, errors, notes);
    }

    private static void rejectUnreliableNumbers(ParsedSheet sheet, int rowIndex, List<String> identity) {
        for (String attribute : identity) {
            Set<Integer> rows = sheet.unreliableRows().get(attribute);
            if (rows != null && rows.contains(rowIndex)) {
                throw new IllegalArgumentException("the identifying column '" + attribute
                        + "' holds a number Excel cannot store exactly — format that column as text");
            }
        }
    }

    private Student buildStudent(ParsedSheet sheet, RowValues row, List<String> identity) {
        Student student = new Student();
        student.setName(row.value(StudentAttributes.NAME));

        Map<String, String> attributes = new LinkedHashMap<>();
        for (SheetColumn column : sheet.columns()) {
            if (StudentAttributes.NAME.equals(column.key())) {
                continue;
            }
            String value = row.value(column.key());
            if (value != null && !value.isBlank()) {
                attributes.put(column.key(), value.trim());
            }
        }
        student.setAttributes(attributes);

        if (student.getName() == null || student.getName().isBlank()) {
            String fallback = identity.stream()
                    .map(attribute -> StudentAttributes.value(student, attribute))
                    .filter(value -> value != null && !value.isBlank())
                    .findFirst()
                    .orElse(null);
            if (fallback != null) {
                student.setName(fallback);
            }
        }
        student.setIdentityKey(identityService.identityKey(student, identity));
        return student;
    }

    private IdentityChoice resolveIdentity(ParsedSheet sheet, List<String> requested) {
        Set<String> available = new LinkedHashSet<>();
        for (SheetColumn column : sheet.columns()) {
            available.add(column.key());
        }

        if (requested != null && !requested.isEmpty()) {
            List<String> keys = new ArrayList<>();
            for (String raw : requested) {
                SheetColumn column = columnFor(sheet, raw);
                if (column == null) {
                    throw new IllegalArgumentException("The file has no column '" + raw
                            + "'; available columns: " + String.join(", ", available));
                }
                if (!keys.contains(column.key())) {
                    keys.add(column.key());
                }
            }
            if (keys.isEmpty()) {
                throw new IllegalArgumentException("No identifying attribute was given");
            }
            requireIdentifying(sheet, keys, String.join(" + ", requested));
            return new IdentityChoice(keys,
                    List.of("Students are identified by " + describe(sheet, keys) + "."));
        }

        List<String> scheme = identityService.currentAttributes();
        if (!scheme.isEmpty()) {
            List<String> keys = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String key : scheme) {
                SheetColumn column = columnFor(sheet, key);
                if (column != null) {
                    keys.add(column.key());
                } else {
                    missing.add(key);
                }
            }
            if (missing.isEmpty()) {
                requireIdentifying(sheet, keys, String.join(" + ", scheme));
                return new IdentityChoice(keys,
                        List.of("Students are identified by " + describe(sheet, keys)
                                + " (the identity configured for this institution)."));
            }
            throw new IllegalArgumentException("The institution identifies students by "
                    + String.join(" + ", scheme) + ", but the file has no column for "
                    + String.join(", ", missing) + "; available columns: "
                    + String.join(", ", available));
        }

        List<SheetColumn> candidates = new ArrayList<>();
        for (SheetColumn column : sheet.columns()) {
            if (StudentAttributes.NAME.equals(column.key())) {
                continue;
            }
            if (isComplete(sheet, column) && isUnique(sheet, column)) {
                candidates.add(column);
            }
        }
        for (SheetColumn column : candidates) {
            if (StudentAttributes.looksLikeIdentifier(column.key())
                    || StudentAttributes.looksLikeIdentifier(column.title())) {
                return new IdentityChoice(List.of(column.key()),
                        List.of("Students are identified by " + describe(sheet, List.of(column.key()))
                                + " (detected automatically)."));
            }
        }
        if (candidates.size() == 1) {
            return new IdentityChoice(List.of(candidates.get(0).key()),
                    List.of("Students are identified by " + describe(sheet, List.of(candidates.get(0).key()))
                            + " (detected automatically)."));
        }
        if (candidates.size() > 1) {
            SheetColumn chosen = candidates.get(0);
            return new IdentityChoice(List.of(chosen.key()),
                    List.of("Students are identified by " + describe(sheet, List.of(chosen.key()))
                            + " (detected automatically — several columns were unique)."));
        }

        List<SheetColumn> others = sheet.columns().stream()
                .filter(column -> !StudentAttributes.NAME.equals(column.key()))
                .toList();
        for (int i = 0; i < others.size(); i++) {
            for (int j = i + 1; j < others.size(); j++) {
                List<String> pair = List.of(others.get(i).key(), others.get(j).key());
                if (isUniqueTogether(sheet, pair)) {
                    return new IdentityChoice(pair,
                            List.of("Students are identified by " + describe(sheet, pair)
                                    + " (detected automatically — two-attribute composite identity)."));
                }
            }
        }
        for (int i = 0; i < others.size(); i++) {
            for (int j = i + 1; j < others.size(); j++) {
                for (int k = j + 1; k < others.size(); k++) {
                    List<String> triple = List.of(
                            others.get(i).key(), others.get(j).key(), others.get(k).key());
                    if (isUniqueTogether(sheet, triple)) {
                        return new IdentityChoice(triple,
                                List.of("Students are identified by " + describe(sheet, triple)
                                        + " (detected automatically — three-attribute composite identity)."));
                    }
                }
            }
        }
        if (others.isEmpty()) {
            throw new IllegalArgumentException("Cannot work out how the students in this file are "
                    + "identified — the file has no column that could identify a student.");
        }
        SheetColumn fallback = others.get(0);
        return new IdentityChoice(List.of(fallback.key()),
                List.of("Students are identified by " + describe(sheet, List.of(fallback.key()))
                        + " (no column is unique — duplicate rows are reported individually)."));
    }

    private static void requireIdentifying(ParsedSheet sheet, List<String> keys, String requested) {
        Set<String> seen = new HashSet<>();
        for (RowValues row : sheet.rows()) {
            StringBuilder builder = new StringBuilder();
            for (String key : keys) {
                String value = row.value(key);
                if (value == null || value.isBlank()) {
                    continue;
                }
                builder.append(value.trim().toLowerCase(Locale.ROOT)).append('\u0001');
            }
            if (!builder.isEmpty() && !seen.add(builder.toString())) {
                throw new IllegalArgumentException("'" + requested + "' does not uniquely identify the students "
                        + "of this file — two rows share the same value. Choose an attribute or combination "
                        + "that is unique via POST /api/v1/students/imports/preview.");
            }
        }
    }

    private static String describe(ParsedSheet sheet, List<String> keys) {
        List<String> names = new ArrayList<>();
        for (String key : keys) {
            SheetColumn match = null;
            for (SheetColumn candidate : sheet.columns()) {
                if (candidate.key().equals(key)) {
                    match = candidate;
                    break;
                }
            }
            names.add(match == null ? key : "'" + match.title() + "'");
        }
        return String.join(" + ", names);
    }

    private static SheetColumn columnFor(ParsedSheet sheet, String name) {
        if (name == null) {
            return null;
        }
        for (SheetColumn column : sheet.columns()) {
            if (column.matches(name)) {
                return column;
            }
        }
        return null;
    }

    private List<IdentityOption> suggestIdentities(ParsedSheet sheet) {
        List<IdentityOption> options = new ArrayList<>();
        List<SheetColumn> columns = sheet.columns();
        for (SheetColumn column : columns) {
            if (StudentAttributes.NAME.equals(column.key())) {
                continue;
            }
            if (isComplete(sheet, column) && isUnique(sheet, column)) {
                options.add(new IdentityOption(List.of(column.key()), column.title(), true));
            }
        }
        for (int i = 0; i < columns.size() && options.size() < MAX_IDENTITY_SUGGESTIONS; i++) {
            for (int j = i + 1; j < columns.size() && options.size() < MAX_IDENTITY_SUGGESTIONS; j++) {
                SheetColumn first = columns.get(i);
                SheetColumn second = columns.get(j);
                if (StudentAttributes.NAME.equals(first.key())
                        || StudentAttributes.NAME.equals(second.key())
                        || !isComplete(sheet, first)
                        || !isComplete(sheet, second)) {
                    continue;
                }
                List<String> pair = List.of(first.key(), second.key());
                if (isUniqueTogether(sheet, pair)) {
                    options.add(new IdentityOption(pair, first.title() + " + " + second.title(), true));
                }
            }
        }
        for (int i = 0; i < columns.size() && options.size() < MAX_IDENTITY_SUGGESTIONS; i++) {
            for (int j = i + 1; j < columns.size() && options.size() < MAX_IDENTITY_SUGGESTIONS; j++) {
                for (int k = j + 1; k < columns.size() && options.size() < MAX_IDENTITY_SUGGESTIONS; k++) {
                    SheetColumn c1 = columns.get(i);
                    SheetColumn c2 = columns.get(j);
                    SheetColumn c3 = columns.get(k);
                    if (StudentAttributes.NAME.equals(c1.key())
                            || StudentAttributes.NAME.equals(c2.key())
                            || StudentAttributes.NAME.equals(c3.key())
                            || !isComplete(sheet, c1)
                            || !isComplete(sheet, c2)
                            || !isComplete(sheet, c3)
                            || isUnique(sheet, c1)
                            || isUnique(sheet, c2)
                            || isUnique(sheet, c3)) {
                        continue;
                    }
                    List<String> triple = List.of(c1.key(), c2.key(), c3.key());
                    if (isUniqueTogether(sheet, triple)) {
                        options.add(new IdentityOption(triple,
                                c1.title() + " + " + c2.title() + " + " + c3.title(), true));
                    }
                }
            }
        }
        return options;
    }

    private List<IdentityOption> suggestClassmateGroups(ParsedSheet sheet) {
        List<IdentityOption> options = new ArrayList<>();
        List<SheetColumn> candidates = new ArrayList<>();
        for (SheetColumn column : sheet.columns()) {
            if (StudentAttributes.NAME.equals(column.key())
                    || StudentAttributes.looksLikeIdentifier(column.key())
                    || StudentAttributes.looksLikeScopeField(column.key())
                    || isUnique(sheet, column)) {
                continue;
            }
            int distinct = distinctValues(sheet, column);
            if (distinct > 1 && distinct < sheet.rows().size()) {
                candidates.add(column);
            }
        }
        for (int i = 0; i < candidates.size() && options.size() < MAX_IDENTITY_SUGGESTIONS; i++) {
            for (int j = i + 1; j < candidates.size() && options.size() < MAX_IDENTITY_SUGGESTIONS; j++) {
                SheetColumn c1 = candidates.get(i);
                SheetColumn c2 = candidates.get(j);
                List<String> pair = List.of(c1.key(), c2.key());
                if (!isUniqueTogether(sheet, pair)) {
                    options.add(new IdentityOption(pair, c1.title() + " + " + c2.title(), false));
                }
            }
        }
        for (int i = 0; i < candidates.size() && options.size() < MAX_IDENTITY_SUGGESTIONS; i++) {
            for (int j = i + 1; j < candidates.size() && options.size() < MAX_IDENTITY_SUGGESTIONS; j++) {
                for (int k = j + 1; k < candidates.size() && options.size() < MAX_IDENTITY_SUGGESTIONS; k++) {
                    SheetColumn c1 = candidates.get(i);
                    SheetColumn c2 = candidates.get(j);
                    SheetColumn c3 = candidates.get(k);
                    List<String> triple = List.of(c1.key(), c2.key(), c3.key());
                    if (!isUniqueTogether(sheet, triple)) {
                        options.add(new IdentityOption(triple,
                                c1.title() + " + " + c2.title() + " + " + c3.title(), false));
                    }
                }
            }
        }
        for (SheetColumn col : candidates) {
            if (options.size() >= MAX_IDENTITY_SUGGESTIONS) {
                break;
            }
            options.add(new IdentityOption(List.of(col.key()), col.title(), false));
        }
        return options;
    }

    // ------------------------------------------------------------------
    //  Rooms — import
    // ------------------------------------------------------------------

    public ImportResult importRooms(InputStream input) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new IllegalArgumentException("The workbook contains no sheets");
            }
            Map<String, Integer> header = headerIndexes(sheet.getRow(0));
            requireHeaders(header, "hallname", "rows", "columns", "benchtype");

            quotas.checkRooms(countDataRows(sheet, header));
            List<String> errors = new ArrayList<>();
            int imported = 0;
            int total = 0;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isBlank(row, header)) {
                    continue;
                }
                total++;
                try {
                    ExamRoom room = ExamRoom.builder()
                            .hallName(cell(row, header, "hallname"))
                            .rows(parseDimension(cell(row, header, "rows"), "Rows"))
                            .columns(parseDimension(cell(row, header, "columns"), "Columns"))
                            .benchType(parseBenchType(cell(row, header, "benchtype")))
                            .build();
                    validateRoom(room);
                    for (int r = 1; r <= room.getRows(); r++) {
                        for (int c = 1; c <= room.getColumns(); c++) {
                            room.getBenches().add(Bench.builder()
                                    .room(room)
                                    .rowIndex(r)
                                    .colIndex(c)
                                    .build());
                        }
                    }
                    tenant.stamp(room);
                    transactionTemplate.executeWithoutResult(status -> examRoomRepository.save(room));
                    imported++;
                } catch (Exception ex) {
                    errors.add("Row " + (i + 1) + ": " + friendlyMessage(ex));
                }
            }
            return ImportResult.of(total, imported, errors);
        }
    }

    private int countDataRows(Sheet sheet, Map<String, Integer> header) {
        int count = 0;
        for (int i = 1; i <= sheet.getLastRowNum(); i++) {
            Row row = sheet.getRow(i);
            if (row != null && !isBlank(row, header)) {
                count++;
            }
        }
        return count;
    }

    // ------------------------------------------------------------------
    //  Sheet parsing & validation internals
    // ------------------------------------------------------------------

    private record IdentityChoice(List<String> attributes, List<String> notes) {
    }

    private record SheetColumn(int index, String title, String key, Set<String> aliases) {
        boolean matches(String name) {
            return aliases.contains(StudentAttributes.normalize(name));
        }
    }

    private record RowValues(Map<String, String> values) {
        String value(String key) {
            return values.get(key);
        }
    }

    private record ParsedSheet(List<SheetColumn> columns, List<RowValues> rows,
                               Map<String, Set<Integer>> unreliableRows) {
    }

    private ParsedSheet parseStudentSheet(InputStream input) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new IllegalArgumentException("The workbook contains no sheets");
            }
            Row headerRow = sheet.getRow(0);
            if (headerRow == null) {
                throw new IllegalArgumentException("The sheet has no header row");
            }

            List<SheetColumn> columns = new ArrayList<>();
            Set<String> usedKeys = new LinkedHashSet<>();
            boolean nameMapped = false;

            for (Cell cell : headerRow) {
                String title = formatter.formatCellValue(cell).trim().replaceAll("\\s+", " ");
                if (title.isEmpty()) {
                    continue;
                }
                String normalizedTitle = StudentAttributes.normalize(title);
                String key;
                if (!nameMapped && StudentAttributes.looksLikeNameHeader(title)) {
                    key = StudentAttributes.NAME;
                    nameMapped = true;
                } else {
                    key = normalizedTitle.isEmpty() ? "column" + (cell.getColumnIndex() + 1) : normalizedTitle;
                }
                if (usedKeys.contains(key)) {
                    String base = normalizedTitle.isEmpty() ? key : normalizedTitle;
                    int suffix = 2;
                    key = base + suffix;
                    while (usedKeys.contains(key)) {
                        key = base + (++suffix);
                    }
                }
                usedKeys.add(key);
                Set<String> aliases = new LinkedHashSet<>();
                aliases.add(key);
                if (!normalizedTitle.isEmpty()) {
                    aliases.add(normalizedTitle);
                }
                columns.add(new SheetColumn(cell.getColumnIndex(), title, key, aliases));
            }
            if (columns.isEmpty()) {
                throw new IllegalArgumentException("The sheet has no usable header row");
            }

            List<RowValues> rows = new ArrayList<>();
            Map<String, Set<Integer>> unreliable = new HashMap<>();
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null) {
                    continue;
                }
                Map<String, String> values = new LinkedHashMap<>();
                boolean empty = true;
                for (SheetColumn column : columns) {
                    CellValue cell = readCell(row, column.index());
                    if (cell.text() != null && !cell.text().isBlank()) {
                        empty = false;
                    }
                    values.put(column.key(), cell.text());
                    if (cell.unreliable()) {
                        unreliable.computeIfAbsent(column.key(), k -> new LinkedHashSet<>()).add(rows.size());
                    }
                }
                if (!empty) {
                    rows.add(new RowValues(values));
                }
            }
            if (rows.isEmpty()) {
                throw new IllegalArgumentException("The sheet contains no data rows");
            }
            return new ParsedSheet(columns, rows, unreliable);
        }
    }

    private record CellValue(String text, boolean unreliable) {
    }

    private CellValue readCell(Row row, int index) {
        Cell cell = row.getCell(index);
        if (cell == null) {
            return new CellValue("", false);
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double numeric = cell.getNumericCellValue();
            if (Double.isNaN(numeric) || Double.isInfinite(numeric)) {
                return new CellValue("", true);
            }
            boolean whole = numeric == Math.floor(numeric);
            boolean exact = Math.abs(numeric) <= 9_007_199_254_740_992d;
            String text = BigDecimal.valueOf(numeric).stripTrailingZeros().toPlainString();
            return new CellValue(text, !whole || !exact);
        }
        if (cell.getCellType() == CellType.BOOLEAN) {
            return new CellValue(Boolean.toString(cell.getBooleanCellValue()), false);
        }
        return new CellValue(formatter.formatCellValue(cell).trim().replaceAll("\\s+", " "), false);
    }

    private static boolean isComplete(ParsedSheet sheet, SheetColumn column) {
        for (RowValues row : sheet.rows()) {
            String value = row.value(column.key());
            if (value == null || value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private static boolean isUnique(ParsedSheet sheet, SheetColumn column) {
        return distinctValues(sheet, column) == sheet.rows().size();
    }

    private static int distinctValues(ParsedSheet sheet, SheetColumn column) {
        Set<String> values = new HashSet<>();
        for (RowValues row : sheet.rows()) {
            String value = row.value(column.key());
            values.add(value == null ? "" : value.trim().toLowerCase(Locale.ROOT));
        }
        return values.size();
    }

    private static boolean isUniqueTogether(ParsedSheet sheet, List<String> keys) {
        Set<String> values = new HashSet<>();
        for (RowValues row : sheet.rows()) {
            StringBuilder builder = new StringBuilder();
            for (String key : keys) {
                String value = row.value(key);
                if (value == null || value.isBlank()) {
                    return false;
                }
                builder.append(value.trim().toLowerCase(Locale.ROOT)).append('\u0001');
            }
            values.add(builder.toString());
        }
        return values.size() == sheet.rows().size();
    }

    private static List<String> samples(ParsedSheet sheet, SheetColumn column) {
        List<String> samples = new ArrayList<>();
        for (RowValues row : sheet.rows()) {
            String value = row.value(column.key());
            if (value != null && !value.isBlank() && !samples.contains(value)) {
                samples.add(value);
            }
            if (samples.size() >= SAMPLES_PER_COLUMN) {
                break;
            }
        }
        return samples;
    }

    private static void validateStudent(Student s) {
        requireText(s.getName(), "Name", MAX_NAME, true);
        for (Map.Entry<String, String> attribute : s.ensureAttributes().entrySet()) {
            requireText(attribute.getValue(), "Attribute '" + attribute.getKey() + "'",
                    MAX_ATTRIBUTE_VALUE, false);
        }
    }

    private static void validateRoom(ExamRoom r) {
        requireText(r.getHallName(), "Hall Name", MAX_HALL_NAME, true);
        if (r.getRows() < 1 || r.getRows() > MAX_DIMENSION) {
            throw new IllegalArgumentException("Rows must be between 1 and " + MAX_DIMENSION);
        }
        if (r.getColumns() < 1 || r.getColumns() > MAX_DIMENSION) {
            throw new IllegalArgumentException("Columns must be between 1 and " + MAX_DIMENSION);
        }
        if (r.getBenchType() == null) {
            throw new IllegalArgumentException("Bench Type is required");
        }
    }

    private static void requireText(String value, String label, int max, boolean required) {
        if (value == null || value.isBlank()) {
            if (required) {
                throw new IllegalArgumentException(label + " is required");
            }
            return;
        }
        if (value.length() > max) {
            throw new IllegalArgumentException(label + " exceeds the maximum length of " + max
                    + " characters (got " + value.length() + ")");
        }
    }

    private static int parseDimension(String value, String label) {
        final int parsed;
        try {
            parsed = Integer.parseInt(value == null ? "" : value.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a whole number (got '" + value + "')");
        }
        if (parsed < 1 || parsed > MAX_DIMENSION) {
            throw new IllegalArgumentException(label + " must be between 1 and " + MAX_DIMENSION
                    + " (got " + parsed + ")");
        }
        return parsed;
    }

    private static ExamRoom.BenchType parseBenchType(String value) {
        String v = value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                .replace(' ', '_').replace('-', '_').replace('.', '_');
        return switch (v) {
            case "SINGLE_SEATER", "SINGLE", "ONE_SEATER" -> ExamRoom.BenchType.SINGLE_SEATER;
            case "DUAL_SEATER", "DUAL", "TWO_SEATER", "DOUBLE_SEATER" -> ExamRoom.BenchType.DUAL_SEATER;
            default -> throw new IllegalArgumentException(
                    "Bench Type must be SINGLE_SEATER or DUAL_SEATER (got '" + value + "')");
        };
    }

    private static String friendlyMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("data too long") || lower.contains("value too long")
                || lower.contains("data truncation") || lower.contains("column length")) {
            return "a value is too long for its database column — shorten it and re-import — row skipped";
        }
        if (lower.contains("uk_students_identity") || lower.contains("identity_key")) {
            return "this student is already on file (same identity) — row skipped";
        }
        if (ex instanceof DataIntegrityViolationException
                || lower.contains("duplicate") || lower.contains("unique constraint")) {
            return "duplicate value for a unique field (student identity or hall name already exists) — row skipped";
        }
        return message;
    }

    private Map<String, Integer> headerIndexes(Row headerRow) {
        Map<String, Integer> map = new HashMap<>();
        if (headerRow == null) {
            return map;
        }
        for (Cell cell : headerRow) {
            String title = StudentAttributes.normalize(formatter.formatCellValue(cell));
            if (title.isEmpty()) {
                continue;
            }
            for (Map.Entry<String, String[]> entry : ROOM_HEADER_ALIASES.entrySet()) {
                for (String alias : entry.getValue()) {
                    if (title.equals(alias) && !map.containsKey(entry.getKey())) {
                        map.put(entry.getKey(), cell.getColumnIndex());
                        break;
                    }
                }
            }
        }
        return map;
    }

    private static void requireHeaders(Map<String, Integer> header, String... required) {
        for (String name : required) {
            if (!header.containsKey(name)) {
                throw new IllegalArgumentException("Missing column in Excel header: '" + name
                        + "' (accepted spellings include: "
                        + String.join(", ", ROOM_HEADER_ALIASES.get(name)) + ")");
            }
        }
    }

    private String cell(Row row, Map<String, Integer> header, String column) {
        Integer idx = header.get(column);
        if (idx == null) {
            return "";
        }
        Cell cell = row.getCell(idx);
        if (cell == null) {
            return "";
        }
        if (cell.getCellType() == CellType.NUMERIC) {
            double numeric = cell.getNumericCellValue();
            if (Double.isNaN(numeric) || Double.isInfinite(numeric)) {
                throw new IllegalArgumentException(column + " contains an invalid numeric value");
            }
            return BigDecimal.valueOf(numeric).stripTrailingZeros().toPlainString();
        }
        return formatter.formatCellValue(cell).trim().replaceAll("\\s+", " ");
    }

    private boolean isBlank(Row row, Map<String, Integer> header) {
        for (Integer idx : header.values()) {
            Cell cell = row.getCell(idx);
            if (cell != null && !formatter.formatCellValue(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    static List<String> attributeKeys(ParsedSheet sheet) {
        List<String> keys = new ArrayList<>();
        for (SheetColumn column : sheet.columns()) {
            keys.add(column.key());
        }
        return Collections.unmodifiableList(keys);
    }
}
