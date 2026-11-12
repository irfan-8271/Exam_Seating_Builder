package com.examseating.service;

import com.examseating.dto.ImportPreview;
import com.examseating.dto.ImportResult;
import com.examseating.dto.ImportPreview.ColumnPreview;
import com.examseating.dto.ImportPreview.IdentityOption;
import com.examseating.dto.IdentitySchemeView;
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
 * Parses the Excel input files with Apache POI.
 *
 * <p><b>The student sheet may look like anything.</b> A roster is no longer
 * required to have a "roll number" column, and columns the importer does not
 * recognise are no longer thrown away:</p>
 * <pre>
 *   STUDENTS.xlsx : any headers you like — at least one column must identify a student
 *   ROOMS.xlsx    : Hall Name | Rows | Columns | Bench Type   (Bench Type = SINGLE_SEATER | DUAL_SEATER)
 * </pre>
 *
 * <p>Every column is read. The six the engine reasons about ({@code name},
 * {@code rollNo}, {@code department}, {@code course}, {@code yearSession},
 * {@code subjectCode}) are recognised by a table of accepted spellings and
 * kept in their typed columns; <b>every other column is kept as a named
 * attribute</b> of the student (batch number, section, registration number,
 * house, …). Nothing is discarded.</p>
 *
 * <p><b>Identification is the institution's decision.</b> The importer works
 * out which attributes could identify a student — see
 * {@link #previewStudents} — and the caller either accepts a suggestion or
 * names the attributes explicitly. A single attribute (a registration
 * number) or a combination (roll number + batch number) is equally
 * acceptable. The chosen values are folded into a canonical identity key that
 * is unique per institution, which is what stops the same student being
 * imported twice under two different column layouts.</p>
 *
 * <p>Import policy: per-row try/catch — good rows are saved, bad rows are
 * collected into {@link ImportResult#errors()} instead of aborting the whole
 * upload. Each row is saved in its own short database transaction
 * ({@link TransactionTemplate}), so a constraint violation on one row rolls
 * back only that row. Values are validated against the same limits as the JPA
 * entities <em>before</em> the insert, so an overlong value is reported as a
 * clear per-row error rather than an opaque database failure.</p>
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

    /** Column limits mirrored from the JPA entities (see Student / ExamRoom). */
    private static final int MAX_NAME = 100;
    private static final int MAX_ROLL_NO = 30;
    private static final int MAX_TEXT = 60;
    private static final int MAX_YEAR = 30;
    private static final int MAX_SUBJECT = 20;
    private static final int MAX_ATTRIBUTE_VALUE = 255;
    private static final int MAX_HALL_NAME = 100;
    private static final int MAX_DIMENSION = 500;

    /** How many example values the preview shows per column. */
    private static final int SAMPLES_PER_COLUMN = 3;

    /** How many single-attribute and two-attribute identity options the preview offers. */
    private static final int MAX_IDENTITY_SUGGESTIONS = 12;

    /**
     * Typed attribute → accepted header spellings (lowercased, punctuation
     * removed). Extend this table instead of hardcoding new formats.
     */
    private static final Map<String, String[]> TYPED_ALIASES = new LinkedHashMap<>();

    static {
        TYPED_ALIASES.put(StudentAttributes.NAME, new String[]{"name", "student name", "full name",
                "student", "candidate name"});
        TYPED_ALIASES.put(StudentAttributes.ROLL_NO, new String[]{"rollno", "rollnumber", "roll",
                "regno", "regnumber", "registrationno", "registrationnumber",
                "admissionno", "admissionnumber", "enrollmentno", "enrolmentno",
                "enrollmentnumber", "enrolmentnumber", "studentid", "usn", "indexno", "candidatecode"});
        TYPED_ALIASES.put(StudentAttributes.DEPARTMENT, new String[]{"department", "dept", "stream",
                "branch", "class", "school", "faculty"});
        TYPED_ALIASES.put(StudentAttributes.COURSE, new String[]{"course", "programme", "program",
                "degree", "programofstudy"});
        TYPED_ALIASES.put(StudentAttributes.YEAR_SESSION, new String[]{"yearsession", "year",
                "batch", "semester", "sem", "semsession", "section", "session", "classname"});
        TYPED_ALIASES.put(StudentAttributes.SUBJECT_CODE, new String[]{"subjectcode", "subject",
                "papercode", "paper", "coursecode", "subjectpapercode"});
        TYPED_ALIASES.put("hallname", new String[]{"hallname", "hall", "room", "roomname", "examhall"});
        TYPED_ALIASES.put("rows", new String[]{"rows", "benchrows", "numrows"});
        TYPED_ALIASES.put("columns", new String[]{"columns", "cols", "benchcolumns", "numcolumns"});
        TYPED_ALIASES.put("benchtype", new String[]{"benchtype", "desktype", "seatingtype", "bench"});
    }

    // ------------------------------------------------------------------
    //  Students — preview
    // ------------------------------------------------------------------

    /**
     * Analyses a roster file without saving anything: which columns it has,
     * which of them could identify a student, and what the institution's
     * current identity scheme is.
     *
     * <p>This is what a client shows the user to ask "which attribute should
     * identify a student?" — a single attribute or a combination.</p>
     */
    public ImportPreview previewStudents(InputStream input) throws IOException {
        ParsedSheet sheet = parseStudentSheet(input);
        List<ColumnPreview> columns = new ArrayList<>();
        for (SheetColumn column : sheet.columns()) {
            columns.add(new ColumnPreview(
                    column.key(),
                    column.title(),
                    column.typedField() == null ? "attribute" : "typed",
                    isComplete(sheet, column),
                    isUnique(sheet, column),
                    distinctValues(sheet, column),
                    samples(sheet, column)));
        }
        List<IdentityOption> suggestions = suggestIdentities(sheet);
        List<IdentityOption> classmateSuggestions = suggestClassmateGroups(sheet);
        IdentitySchemeView scheme = identityService.currentScheme()
                .map(s -> new IdentitySchemeView(s.getId(), s.describe(), s.attributeList(),
                        s.effectiveDisplayAttribute(), s.classmateAttributeList()))
                .orElse(null);
        return new ImportPreview(columns, suggestions, classmateSuggestions, scheme, sheet.rows().size());
    }

    // ------------------------------------------------------------------
    //  Students — import
    // ------------------------------------------------------------------

    /**
     * Imports a roster file, letting the institution's identity scheme (or the
     * importer's own detection) decide how students are identified.
     */
    public ImportResult importStudents(InputStream input) throws IOException {
        return importStudents(input, List.of());
    }

    /**
     * Imports a roster file.
     *
     * @param input             the workbook
     * @param identityAttributes the attributes that identify a student — one or
     *                          several. When empty, the institution's stored
     *                          scheme is used, and failing that the importer
     *                          detects a usable column itself.
     */
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
                            "duplicate student identity '" + key + "' — the same student appears twice in the file");
                }
                transactionTemplate.executeWithoutResult(status -> studentRepository.save(student));
                imported++;
            } catch (Exception ex) {
                errors.add("Row " + (i + 2) + ": " + friendlyMessage(ex));
            }
        }
        return ImportResult.of(total, imported, errors, notes);
    }

    /**
     * Refuses a row whose identifier Excel stored as a number it cannot
     * represent exactly (a fractional value, or an integer above 2^53). Such a
     * cell would silently identify the wrong student, so the row is reported
     * instead of imported.
     */
    private static void rejectUnreliableNumbers(ParsedSheet sheet, int rowIndex, List<String> identity) {
        for (String attribute : identity) {
            Set<Integer> rows = sheet.unreliableRows().get(attribute);
            if (rows != null && rows.contains(rowIndex)) {
                throw new IllegalArgumentException("the identifying column '" + attribute
                        + "' holds a number Excel cannot store exactly — format that column as text");
            }
        }
    }

    /** Builds one student from one data row. */
    private Student buildStudent(ParsedSheet sheet, RowValues row, List<String> identity) {
        Student student = new Student();
        student.setName(row.value(StudentAttributes.NAME));
        student.setRollNo(row.value(StudentAttributes.ROLL_NO));
        student.setDepartment(row.value(StudentAttributes.DEPARTMENT));
        student.setCourse(row.value(StudentAttributes.COURSE));
        student.setYearSession(row.value(StudentAttributes.YEAR_SESSION));
        student.setSubjectCode(row.value(StudentAttributes.SUBJECT_CODE));

        // Every column the engine does not model becomes a named attribute.
        Map<String, String> attributes = new LinkedHashMap<>();
        for (SheetColumn column : sheet.columns()) {
            if (column.typedField() != null) {
                continue;
            }
            String value = row.value(column.key());
            if (value != null && !value.isBlank()) {
                attributes.put(column.key(), value);
            }
        }
        student.setAttributes(attributes);

        // A roster without a name column is still usable: the identifier is
        // the best label we have.
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

    /**
     * Decides which attributes identify the students of this file.
     *
     * <ol>
     *   <li>the caller named them — use those;</li>
     *   <li>the institution configured a scheme — use the sheet columns that
     *       match it;</li>
     *   <li>otherwise detect: a known identifier column, else the single
     *       column that is unique across the file.</li>
     * </ol>
     *
     * <p>When the file simply does not contain a usable identifier, the
     * importer refuses and names the columns it found — the caller then asks
     * the institution which one to use (see {@link #previewStudents}).</p>
     */
    private IdentityChoice resolveIdentity(ParsedSheet sheet, List<String> requested) {
        Set<String> available = new LinkedHashSet<>();
        for (SheetColumn column : sheet.columns()) {
            available.add(column.key());
            available.addAll(column.aliases());
        }

        // 1. Explicitly requested by the caller. The name may be the
        //    attribute key or the header exactly as it appears in the file.
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
            requireIdentifying(sheet, keys, raw(requested));
            return new IdentityChoice(keys,
                    List.of("Students are identified by " + describe(sheet, keys) + "."));
        }

        // 2. The institution's stored scheme.
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

        // 3. Auto-detection.
        List<SheetColumn> candidates = new ArrayList<>();
        for (SheetColumn column : sheet.columns()) {
            // A display name is never auto-detected as an identifier: names
            // are not unique in any real roster, and silently identifying
            // students by name would be wrong.
            if (StudentAttributes.NAME.equals(column.key())) {
                continue;
            }
            if (isComplete(sheet, column) && isUnique(sheet, column)) {
                candidates.add(column);
            }
        }
        // A column that is known to carry an identifier wins outright.
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
            // Several columns would do the job. Take the first and say so —
            // refusing the whole file would be worse than importing it with a
            // note, and the institution can pin the choice down in its
            // identity scheme afterwards.
            SheetColumn chosen = candidates.get(0);
            return new IdentityChoice(List.of(chosen.key()),
                    List.of("Students are identified by " + describe(sheet, List.of(chosen.key()))
                            + " (detected automatically — several columns were unique; "
                            + "set an identity scheme or pass 'identityAttributes' to choose another)."));
        }

        // No single column is unique. Two columns together may still be.
        List<SheetColumn> others = sheet.columns().stream()
                .filter(column -> !StudentAttributes.NAME.equals(column.key()))
                .toList();
        for (int i = 0; i < others.size(); i++) {
            for (int j = i + 1; j < others.size(); j++) {
                List<String> pair = List.of(others.get(i).key(), others.get(j).key());
                if (isUniqueTogether(sheet, pair)) {
                    return new IdentityChoice(pair,
                            List.of("Students are identified by " + describe(sheet, pair)
                                    + " (detected automatically — no single column is unique)."));
                }
            }
        }
        if (others.isEmpty()) {
            throw new IllegalArgumentException("Cannot work out how the students in this file are "
                    + "identified — the file has no column that could identify a student. "
                    + "Available columns: " + String.join(", ", available));
        }
        // Nothing is unique: use the first column that could carry an
        // identifier and let the duplicate check report the rows that clash.
        SheetColumn fallback = others.get(0);
        return new IdentityChoice(List.of(fallback.key()),
                List.of("Students are identified by " + describe(sheet, List.of(fallback.key()))
                        + " (no column of this file is unique — rows that share a value are "
                        + "reported as duplicates)."));
    }

    /**
     * Refuses an identifier that does not identify.
     *
     * <p>Two rows that share the same value in the identifying column are two
     * students the file cannot tell apart — importing them would merge two
     * people into one identity. The file is refused as a whole, because the
     * remedy is to choose a different attribute (or a combination), not to
     * patch individual rows.</p>
     */
    private static void requireIdentifying(ParsedSheet sheet, List<String> keys, String requested) {
        Set<String> seen = new HashSet<>();
        for (RowValues row : sheet.rows()) {
            StringBuilder builder = new StringBuilder();
            for (String key : keys) {
                String value = row.value(key);
                if (value == null || value.isBlank()) {
                    // A blank is a row-level problem, reported for that row.
                    continue;
                }
                builder.append(value.trim().toLowerCase(Locale.ROOT)).append('\u0001');
            }
            if (!seen.add(builder.toString())) {
                throw new IllegalArgumentException("'" + requested + "' does not identify the students "
                        + "of this file — two rows share the same value. Choose an attribute (or a "
                        + "combination of attributes) that is unique; call "
                        + "POST /api/v1/students/imports/preview to see the candidates.");
            }
        }
    }

    /** The names the caller asked for, as one readable string. */
    private static String raw(List<String> requested) {
        return String.join(" + ", requested);
    }

    /** A human-readable description of the attributes that identify students. */
    private static String describe(ParsedSheet sheet, List<String> keys) {
        List<String> names = new ArrayList<>();
        for (String key : keys) {
            SheetColumn column = null;
            for (SheetColumn candidate : sheet.columns()) {
                if (candidate.key().equals(key)) {
                    column = candidate;
                    break;
                }
            }
            names.add(column == null ? key : "'" + column.title() + "'");
        }
        return String.join(" + ", names);
    }

    /** The column a name addresses, whether it is the storage key or the header. */
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

    /**
     * Suggests identity options for the preview: every unique column on its
     * own, and every pair of columns that is unique together.
     */
    private List<IdentityOption> suggestIdentities(ParsedSheet sheet) {
        List<IdentityOption> options = new ArrayList<>();
        List<SheetColumn> columns = sheet.columns();
        for (SheetColumn column : columns) {
            // A name is never an identifier: two students can share one.
            if (StudentAttributes.NAME.equals(column.key())) {
                continue;
            }
            if (isComplete(sheet, column) && isUnique(sheet, column)) {
                options.add(new IdentityOption(List.of(column.key()),
                        column.title(), true));
            }
        }
        for (int i = 0; i < columns.size(); i++) {
            for (int j = i + 1; j < columns.size(); j++) {
                if (options.size() >= MAX_IDENTITY_SUGGESTIONS) {
                    return options;
                }
                SheetColumn first = columns.get(i);
                SheetColumn second = columns.get(j);
                if (StudentAttributes.NAME.equals(first.key())
                        || StudentAttributes.NAME.equals(second.key())) {
                    continue;
                }
                if (!isComplete(sheet, first) || !isComplete(sheet, second)) {
                    continue;
                }
                boolean unique = isUniqueTogether(sheet, first, second);
                options.add(new IdentityOption(List.of(first.key(), second.key()),
                        first.title() + " + " + second.title(), unique));
            }
        }
        return options;
    }

    /**
     * Suggests candidate classmate / cohort grouping combinations: non-unique columns
     * that partition students into manageable academic cohorts.
     */
    private List<IdentityOption> suggestClassmateGroups(ParsedSheet sheet) {
        List<IdentityOption> options = new ArrayList<>();
        List<SheetColumn> candidates = new ArrayList<>();
        for (SheetColumn column : sheet.columns()) {
            if (StudentAttributes.NAME.equals(column.key()) || StudentAttributes.ROLL_NO.equals(column.key())) {
                continue;
            }
            int distinct = distinctValues(sheet, column);
            if (distinct > 1 && distinct < sheet.rows().size()) {
                candidates.add(column);
            }
        }
        // Single grouping columns
        for (SheetColumn col : candidates) {
            options.add(new IdentityOption(List.of(col.key()), col.title(), false));
        }
        // Pair combinations (e.g. Course + Batch, Class + Section, Dept + Year)
        for (int i = 0; i < candidates.size() && options.size() < 12; i++) {
            for (int j = i + 1; j < candidates.size() && options.size() < 12; j++) {
                SheetColumn c1 = candidates.get(i);
                SheetColumn c2 = candidates.get(j);
                options.add(new IdentityOption(List.of(c1.key(), c2.key()), c1.title() + " + " + c2.title(), false));
            }
        }
        return options;
    }

    // ------------------------------------------------------------------
    //  Rooms (unchanged format)
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
                    // Build the M×N bench grid so the engine finds persisted benches.
                    for (int r = 1; r <= room.getRows(); r++) {
                        for (int c = 1; c <= room.getColumns(); c++) {
                            room.getBenches().add(Bench.builder()
                                    .room(room)
                                    .rowIndex(r)
                                    .colIndex(c)
                                    .benchType(room.getBenchType())
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

    /** Number of non-empty data rows; used for a single up-front quota check. */
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
    //  Parsing the student sheet
    // ------------------------------------------------------------------

    /**
     * One column of the sheet: where it is, what it is called, and — when the
     * importer recognises it — which typed field it feeds.
     */
    /**
     * One column of an uploaded sheet.
     *
     * @param index     the column number in the sheet
     * @param title     the header exactly as it appears in the file
     * @param key       the attribute key the value is stored under
     * @param typedField the {@code students} column it feeds, or null for a
     *                   dynamic attribute
     * @param aliases   every name this column can be addressed by — its
     *                   storage key and its normalized header, so both
     *                   {@code "rollno"} and {@code "Registration Number"}
     *                   reach the same column
     */
    /**
     * The attributes that identify the students of a sheet, plus the reasons
     * behind that choice — the notes are returned to the caller so the
     * institution can see how its file was read.
     */
    private record IdentityChoice(List<String> attributes, List<String> notes) {
    }

    private record SheetColumn(int index, String title, String key, String typedField,
                               Set<String> aliases) {

        /** Whether this column can be addressed by the given name. */
        boolean matches(String name) {
            return aliases.contains(StudentAttributes.normalize(name));
        }
    }

    /** One data row: the values of every column, keyed by attribute key. */
    private record RowValues(Map<String, String> values) {
        String value(String key) {
            return values.get(key);
        }
    }

    /** The parsed sheet: its columns, its rows and the rows that hold unreliable numbers. */
    private record ParsedSheet(List<SheetColumn> columns, List<RowValues> rows,
                               Map<String, Set<Integer>> unreliableRows) {
    }

    /**
     * Reads the whole student sheet: every column, every row.
     *
     * <p>Unlike the room format, nothing is required here — a sheet with two
     * unknown columns is still a valid roster. Only the presence of at least
     * one column and one row is required.</p>
     */
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
            for (Cell cell : headerRow) {
                String title = formatter.formatCellValue(cell).trim().replaceAll("\\s+", " ");
                if (title.isEmpty()) {
                    continue;
                }
                String normalizedTitle = StudentAttributes.normalize(title);
                String typedField = typedFieldFor(normalizedTitle);
                // A recognised column is addressed by its canonical attribute
                // key ("Reg No" and "Registration Number" both mean "rollno"),
                // so the value can be found under that name later on.
                String key = typedField != null ? typedField : normalizedTitle;
                if (key.isEmpty()) {
                    key = "column" + (cell.getColumnIndex() + 1);
                }
                // Two headers that mean the same thing ("Roll No" and
                // "Roll_No") must not collide: the second one becomes its own
                // dynamic attribute.
                if (usedKeys.contains(key)) {
                    String fallback = normalizedTitle.isEmpty() ? key : normalizedTitle;
                    key = fallback;
                    int suffix = 2;
                    while (usedKeys.contains(key)) {
                        key = fallback + suffix++;
                    }
                }
                usedKeys.add(key);
                Set<String> aliases = new LinkedHashSet<>();
                aliases.add(key);
                aliases.add(normalizedTitle);
                columns.add(new SheetColumn(cell.getColumnIndex(), title, key, typedField, aliases));
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

    /**
     * The typed field a normalized header feeds, or null for a custom
     * attribute.
     *
     * <p>Both sides are normalized, so the alias {@code "student name"} and
     * the header {@code "Student Name"} meet in the middle.</p>
     */
    private static String typedFieldFor(String key) {
        String normalized = StudentAttributes.normalize(key);
        if (normalized.isEmpty()) {
            return null;
        }
        for (Map.Entry<String, String[]> entry : TYPED_ALIASES.entrySet()) {
            for (String alias : entry.getValue()) {
                if (normalized.equals(StudentAttributes.normalize(alias))) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /** The value of one cell, plus whether it is a number Excel cannot represent exactly. */
    private record CellValue(String text, boolean unreliable) {
    }

    /**
     * Renders one cell to text. Numeric identifiers that Excel stored as a
     * fractional or an imprecise 64-bit value are flagged: those cells cannot
     * be trusted as identifiers and the importer refuses them once it knows
     * the column is part of the identity.
     */
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

    // ------------------------------------------------------------------
    //  Column analysis
    // ------------------------------------------------------------------

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

    /** Whether a combination of attribute keys separates every row of the sheet. */
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

    private static boolean isUniqueTogether(ParsedSheet sheet, SheetColumn first, SheetColumn second) {
        Set<String> values = new HashSet<>();
        for (RowValues row : sheet.rows()) {
            String a = row.value(first.key());
            String b = row.value(second.key());
            if (a == null || a.isBlank() || b == null || b.isBlank()) {
                return false;
            }
            values.add(a.trim().toLowerCase(Locale.ROOT) + "\u0001" + b.trim().toLowerCase(Locale.ROOT));
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

    // ------------------------------------------------------------------
    //  Validation (mirrors the Jakarta constraints on the entities)
    // ------------------------------------------------------------------

    private static void validateStudent(Student s) {
        requireText(s.getName(), "Name", MAX_NAME, true);
        requireText(s.getRollNo(), "Roll / registration number", MAX_ROLL_NO, false);
        requireText(s.getDepartment(), "Department", MAX_TEXT, false);
        requireText(s.getCourse(), "Course", MAX_TEXT, false);
        requireText(s.getYearSession(), "Year/Session", MAX_YEAR, false);
        requireText(s.getSubjectCode(), "Subject Code", MAX_SUBJECT, false);
        for (Map.Entry<String, String> attribute : s.getAttributes().entrySet()) {
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

    /** Parses a room dimension, enforcing the entity's @Min(1)/@Max(500) limits. */
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

    /**
     * Strict bench-type parsing. Previously a substring match accepted values
     * such as "DUAL-SINGLE" as dual-seater; only the documented spellings are valid.
     */
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

    /**
     * Turns low-level exceptions into actionable, per-row messages. A DB length
     * error is NOT a duplicate-key error — mislabelling it made imports impossible
     * to debug.
     */
    private static String friendlyMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        String lower = message.toLowerCase(Locale.ROOT);
        // Inspect the message FIRST: a DataIntegrityViolationException may be a
        // length/truncation error, not a duplicate key. Mislabelling it as a
        // duplicate made failed imports impossible to diagnose.
        if (lower.contains("data too long") || lower.contains("value too long")
                || lower.contains("data truncation") || lower.contains("column length")) {
            return "a value is too long for its database column — shorten it and re-import — row skipped";
        }
        if (lower.contains("uk_students_identity") || lower.contains("identity_key")) {
            return "this student is already on file (same identity) — row skipped";
        }
        if (ex instanceof org.springframework.dao.DataIntegrityViolationException
                || lower.contains("duplicate") || lower.contains("unique constraint")) {
            return "duplicate value for a unique field (student identity or hall name already exists) — row skipped";
        }
        return message;
    }

    // ------------------------------------------------------------------
    //  Header mapping (room format)
    // ------------------------------------------------------------------

    /**
     * Maps the header row to canonical column indexes: {"hallname" -> 0, ...},
     * resolving aliases ("Hall", "Room Name" → "hallname", ...).
     */
    private Map<String, Integer> headerIndexes(Row headerRow) {
        Map<String, Integer> map = new HashMap<>();
        if (headerRow == null) {
            return map;
        }
        for (Cell cell : headerRow) {
            String title = normalizeHeader(formatter.formatCellValue(cell));
            if (title.isEmpty()) {
                continue;
            }
            String canonical = null;
            for (Map.Entry<String, String[]> entry : TYPED_ALIASES.entrySet()) {
                for (String alias : entry.getValue()) {
                    if (title.equals(alias)) {
                        canonical = entry.getKey();
                        break;
                    }
                }
                if (canonical != null) {
                    break;
                }
            }
            if (canonical != null && !map.containsKey(canonical)) {
                map.put(canonical, cell.getColumnIndex());
            }
        }
        return map;
    }

    /**
     * The header form used by the room importer: the same normalization as
     * the student importer, so both speak one language — "Hall Name",
     * "hall_name" and "HALLNAME" all address the same column.
     */
    private static String normalizeHeader(String title) {
        return StudentAttributes.normalize(title);
    }

    private static void requireHeaders(Map<String, Integer> header, String... required) {
        for (String name : required) {
            if (!header.containsKey(name)) {
                throw new IllegalArgumentException("Missing column in Excel header: '" + name
                        + "' (accepted spellings include: " + String.join(", ", TYPED_ALIASES.get(name)) + ")");
            }
        }
    }

    // ------------------------------------------------------------------
    //  Cell rendering (room format)
    // ------------------------------------------------------------------

    /** Renders one cell to text. */
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
            if (numeric != Math.floor(numeric)) {
                return BigDecimal.valueOf(numeric).stripTrailingZeros().toPlainString();
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

    /** Convenience for tests and diagnostics: the ordered attribute keys of a sheet. */
    static List<String> attributeKeys(ParsedSheet sheet) {
        List<String> keys = new ArrayList<>();
        for (SheetColumn column : sheet.columns()) {
            keys.add(column.key());
        }
        return Collections.unmodifiableList(keys);
    }
}
