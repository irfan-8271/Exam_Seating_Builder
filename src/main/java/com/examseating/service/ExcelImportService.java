package com.examseating.service;

import com.examseating.dto.ImportResult;
import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.Student;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.tenancy.TenantSupport;
import com.examseating.repository.StudentRepository;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the Excel input files with Apache POI.
 *
 * <p>Accepted format (first row = headers, matched by name, order-insensitive;
 * common spellings like "Roll No." / "Roll Number" / "Reg No" are aliased):</p>
 * <pre>
 *   STUDENTS.xlsx : Name | Roll No | Department | Course | Year/Session | Subject Code
 *   ROOMS.xlsx    : Hall Name | Rows | Columns | Bench Type   (Bench Type = SINGLE_SEATER | DUAL_SEATER)
 * </pre>
 *
 * <p>Only <b>Name</b> and <b>Roll No</b> are required for students — every
 * classification attribute (Department, Course, Year/Session) and the optional
 * Subject Code may be omitted, so schools with just class + roll number and
 * universities with registration numbers + departments + semesters can both
 * import their own files. Institutions with different header spellings are
 * served by {@link #HEADER_ALIASES}.</p>
 *
 * <p>Import policy: per-row try/catch — good rows are saved, bad rows are collected into
 * {@link ImportResult#errors()} instead of aborting the whole upload. Each row is saved in
 * its own short database transaction ({@link TransactionTemplate}), so a constraint
 * violation on one row rolls back only that row. Values are validated against the
 * same limits as the JPA entities <em>before</em> the insert, so an overlong value is
 * reported as a clear per-row error rather than an opaque database failure.</p>
 */
@Service
@RequiredArgsConstructor
public class ExcelImportService {

    private final StudentRepository studentRepository;
    private final ExamRoomRepository examRoomRepository;
    private final TransactionTemplate transactionTemplate;
    private final TenantSupport tenant;
    private final QuotaService quotas;

    private final DataFormatter formatter = new DataFormatter();

    /** Column limits mirrored from the JPA entities (see Student / ExamRoom). */
    private static final int MAX_NAME = 100;
    private static final int MAX_ROLL_NO = 30;
    private static final int MAX_TEXT = 60;
    private static final int MAX_YEAR = 30;
    private static final int MAX_SUBJECT = 20;
    private static final int MAX_HALL_NAME = 100;
    private static final int MAX_DIMENSION = 500;

    /**
     * Canonical header → accepted spellings (lowercased, '.'/'-'/'_' treated as
     * spaces). Extend this table instead of hardcoding new formats.
     */
    private static final Map<String, String[]> HEADER_ALIASES = new HashMap<>();

    static {
        HEADER_ALIASES.put("name", new String[]{"name", "student name", "full name"});
        HEADER_ALIASES.put("roll no", new String[]{"roll no", "roll number", "rollno", "reg no", "reg number",
                "registration no", "registration number", "admission no", "admission number", "student id", "id"});
        HEADER_ALIASES.put("department", new String[]{"department", "dept", "stream", "branch", "class", "school"});
        HEADER_ALIASES.put("course", new String[]{"course", "programme", "program", "degree"});
        HEADER_ALIASES.put("year/session", new String[]{"year/session", "year session", "year", "batch", "semester",
                "sem", "sem/session", "section", "session"});
        HEADER_ALIASES.put("subject code", new String[]{"subject code", "subject", "paper code", "paper", "course code"});
        HEADER_ALIASES.put("hall name", new String[]{"hall name", "hall", "room", "room name", "exam hall"});
        HEADER_ALIASES.put("rows", new String[]{"rows", "bench rows", "num rows"});
        HEADER_ALIASES.put("columns", new String[]{"columns", "cols", "bench columns", "num columns"});
        HEADER_ALIASES.put("bench type", new String[]{"bench type", "desk type", "seating type", "bench"});
    }

    // ------------------------------------------------------------------
    //  Students
    // ------------------------------------------------------------------

    public ImportResult importStudents(InputStream input) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new IllegalArgumentException("The workbook contains no sheets");
            }
            Map<String, Integer> header = headerIndexes(sheet.getRow(0));
            requireHeaders(header, "name", "roll no");

            quotas.checkStudents(countDataRows(sheet, header));
            List<String> errors = new ArrayList<>();
            int imported = 0;
            int total = 0;
            for (int i = 1; i <= sheet.getLastRowNum(); i++) {
                Row row = sheet.getRow(i);
                if (row == null || isBlank(row, header)) {
                    continue; // skip empty rows
                }
                total++;
                try {
                    Student student = Student.builder()
                            .name(cell(row, header, "name"))
                            .rollNo(cell(row, header, "roll no"))
                            .department(cellOrNull(row, header, "department"))
                            .course(cellOrNull(row, header, "course"))
                            .yearSession(cellOrNull(row, header, "year/session"))
                            .subjectCode(cellOrNull(row, header, "subject code"))
                            .build();
                    validateStudent(student);
                    tenant.stamp(student);
                    transactionTemplate.executeWithoutResult(status -> studentRepository.save(student));
                    imported++;
                } catch (Exception ex) {
                    errors.add("Row " + (i + 1) + ": " + friendlyMessage(ex));
                }
            }
            return ImportResult.of(total, imported, errors);
        }
    }

    // ------------------------------------------------------------------
    //  Rooms
    // ------------------------------------------------------------------

    public ImportResult importRooms(InputStream input) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) {
                throw new IllegalArgumentException("The workbook contains no sheets");
            }
            Map<String, Integer> header = headerIndexes(sheet.getRow(0));
            requireHeaders(header, "hall name", "rows", "columns", "bench type");

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
                            .hallName(cell(row, header, "hall name"))
                            .rows(parseDimension(cell(row, header, "rows"), "Rows"))
                            .columns(parseDimension(cell(row, header, "columns"), "Columns"))
                            .benchType(parseBenchType(cell(row, header, "bench type")))
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
    //  Validation (mirrors the Jakarta constraints on the entities)
    // ------------------------------------------------------------------

    private static void validateStudent(Student s) {
        requireText(s.getName(), "Name", MAX_NAME, true);
        requireText(s.getRollNo(), "Roll / registration number", MAX_ROLL_NO, true);
        requireText(s.getDepartment(), "Department", MAX_TEXT, false);
        requireText(s.getCourse(), "Course", MAX_TEXT, false);
        requireText(s.getYearSession(), "Year/Session", MAX_YEAR, false);
        requireText(s.getSubjectCode(), "Subject Code", MAX_SUBJECT, false);
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
        String v = value == null ? "" : value.trim().toUpperCase()
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
        String lower = message.toLowerCase();
        // Inspect the message FIRST: a DataIntegrityViolationException may be a
        // length/truncation error, not a duplicate key. Mislabelling it as a
        // duplicate made failed imports impossible to diagnose.
        if (lower.contains("data too long") || lower.contains("value too long")
                || lower.contains("data truncation") || lower.contains("column length")) {
            return "a value is too long for its database column — shorten it and re-import — row skipped";
        }
        if (ex instanceof org.springframework.dao.DataIntegrityViolationException
                || lower.contains("duplicate") || lower.contains("unique constraint")) {
            return "duplicate value for a unique field (roll number or hall name already exists) — row skipped";
        }
        return message;
    }

    // ------------------------------------------------------------------
    //  Header mapping
    // ------------------------------------------------------------------

    /**
     * Maps the header row to canonical column indexes: {"name" -> 0, "roll no" -> 1, ...},
     * resolving aliases ("Roll No.", "Reg No", "Registration Number" → "roll no", ...).
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
            for (Map.Entry<String, String[]> entry : HEADER_ALIASES.entrySet()) {
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

    /** Lowercased, '.'/'-'/'_' collapsed to spaces, then trimmed at both ends. */
    private static String normalizeHeader(String title) {
        return title == null ? "" : title.trim().toLowerCase()
                .replaceAll("[._\\-]+", " ")
                .replaceAll("\\s+", " ")
                .trim();
    }

    private static void requireHeaders(Map<String, Integer> header, String... required) {
        for (String name : required) {
            if (!header.containsKey(name)) {
                throw new IllegalArgumentException("Missing column in Excel header: '" + name
                        + "' (accepted spellings include: " + String.join(", ", HEADER_ALIASES.get(name)) + ")");
            }
        }
    }

    // ------------------------------------------------------------------
    //  Cell rendering
    // ------------------------------------------------------------------

    /**
     * Renders one cell to text. Numeric ID cells (roll numbers, paper codes) are
     * rendered without decimal noise and are rejected when Excel stored a
     * fractional or an imprecise 64-bit value — those identifiers must be text.
     */
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
            boolean identifier = "roll no".equals(column) || "subject code".equals(column);
            if (Double.isNaN(numeric) || Double.isInfinite(numeric)) {
                throw new IllegalArgumentException(column + " contains an invalid numeric value");
            }
            if (numeric != Math.floor(numeric)) {
                if (identifier) {
                    throw new IllegalArgumentException(column
                            + " must be a whole number (got " + numeric + ") — format the column as text");
                }
                return BigDecimal.valueOf(numeric).stripTrailingZeros().toPlainString();
            }
            // IEEE-754 doubles cannot represent every integer above 2^53 exactly.
            if (identifier && Math.abs(numeric) > 9_007_199_254_740_992d) {
                throw new IllegalArgumentException(column
                        + " exceeds the exact numeric range — format the column as text");
            }
            return BigDecimal.valueOf(numeric).stripTrailingZeros().toPlainString();
        }
        return formatter.formatCellValue(cell).trim().replaceAll("\\s+", " ");
    }

    private String cellOrNull(Row row, Map<String, Integer> header, String column) {
        String val = cell(row, header, column);
        return val.isBlank() ? null : val;
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
}
