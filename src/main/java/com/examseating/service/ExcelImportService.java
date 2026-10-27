package com.examseating.service;

import com.examseating.dto.ImportResult;
import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.Student;
import com.examseating.repository.ExamRoomRepository;
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
 * served by {@link #HEADER_ALIASES}; a future mapping UI will build on the same
 * table.</p>
 *
 * <p>POI fundamentals: a workbook → sheets → rows (0-based) → cells (0-based). Each cell
 * holds a typed value (string, number, formula, ...). {@link DataFormatter} renders any
 * cell to the string the user would see in Excel — the most forgiving way to read input
 * files. {@link WorkbookFactory#create} sniffs whether the file is .xlsx or .xls.</p>
 *
 * <p>Import policy: per-row try/catch — good rows are saved, bad rows are collected into
 * {@link ImportResult#errors()} instead of aborting the whole upload. Each row is saved in
 * its own short database transaction ({@link TransactionTemplate}), so a constraint
 * violation on one row (e.g. a duplicate roll number) rolls back only that row and never
 * poisons the good ones.</p>
 */
@Service
@RequiredArgsConstructor
public class ExcelImportService {

    private final StudentRepository studentRepository;
    private final ExamRoomRepository examRoomRepository;
    private final TransactionTemplate transactionTemplate;

    private final DataFormatter formatter = new DataFormatter();

    /**
     * Canonical header → accepted spellings (lowercased, '.' and '-' treated as
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
            Map<String, Integer> header = headerIndexes(sheet.getRow(0));
            requireHeaders(header, "name", "roll no");

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
                            .department(cell(row, header, "department"))
                            .course(cell(row, header, "course"))
                            .yearSession(cell(row, header, "year/session"))
                            .subjectCode(cell(row, header, "subject code"))
                            .build();
                    validateStudent(student);
                    // Each row gets its own transaction: a failing row rolls back
                    // alone and never poisons the rest of the file.
                    transactionTemplate.executeWithoutResult(status -> studentRepository.save(student));
                    imported++;
                } catch (Exception ex) {
                    // One bad row must not kill the whole file.
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
            Map<String, Integer> header = headerIndexes(sheet.getRow(0));
            requireHeaders(header, "hall name", "rows", "columns", "bench type");

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
                            .rows(parsePositiveInt(cell(row, header, "rows"), "Rows"))
                            .columns(parsePositiveInt(cell(row, header, "columns"), "Columns"))
                            .benchType(parseBenchType(cell(row, header, "bench type")))
                            .build();
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
                    transactionTemplate.executeWithoutResult(status -> examRoomRepository.save(room));
                    imported++;
                } catch (Exception ex) {
                    errors.add("Row " + (i + 1) + ": " + friendlyMessage(ex));
                }
            }
            return ImportResult.of(total, imported, errors);
        }
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private static void validateStudent(Student s) {
        if (s.getRollNo() == null || s.getRollNo().isBlank()) {
            throw new IllegalArgumentException("Roll / registration number is required");
        }
        if (s.getName() == null || s.getName().isBlank()) {
            throw new IllegalArgumentException("Name is required for roll " + s.getRollNo());
        }
    }

    private static int parsePositiveInt(String value, String label) {
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed <= 0) {
                throw new IllegalArgumentException(label + " must be positive (got '" + value + "')");
            }
            return parsed;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(label + " must be a whole number (got '" + value + "')");
        }
    }

    private static ExamRoom.BenchType parseBenchType(String value) {
        String v = value == null ? "" : value.trim().toUpperCase().replace(' ', '_').replace('-', '_');
        if (v.contains("DUAL")) {
            return ExamRoom.BenchType.DUAL_SEATER;
        }
        if (v.contains("SINGLE")) {
            return ExamRoom.BenchType.SINGLE_SEATER;
        }
        throw new IllegalArgumentException(
                "Bench Type must be SINGLE_SEATER or DUAL_SEATER (got '" + value + "')");
    }

    private static String friendlyMessage(Exception ex) {
        String message = ex.getMessage();
        if (message == null || message.isBlank()) {
            return ex.getClass().getSimpleName();
        }
        // Raw DB constraint messages are unreadable — map the common one.
        if (ex instanceof org.springframework.dao.DataIntegrityViolationException
                || message.toLowerCase().contains("duplicate")) {
            return "duplicate value for a unique field (roll number or hall name already exists) — row skipped";
        }
        return message;
    }

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

    /** Lowercased, '.'/'-'/'_' collapsed to spaces, trimmed. */
    private static String normalizeHeader(String title) {
        return title == null ? "" : title.trim().toLowerCase()
                .replaceAll("[._\\-]+", " ")
                .replaceAll("\\s+", " ");
    }

    private static void requireHeaders(Map<String, Integer> header, String... required) {
        for (String name : required) {
            if (!header.containsKey(name)) {
                throw new IllegalArgumentException("Missing column in Excel header: '" + name
                        + "' (accepted spellings include: " + String.join(", ", HEADER_ALIASES.get(name)) + ")");
            }
        }
    }

    /**
     * Renders one cell to text. Numeric ID cells (e.g. roll number 101001) are
     * rendered without decimal noise ("101001.0" → "101001").
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
        if (cell.getCellType() == CellType.NUMERIC
                && ("roll no".equals(column) || "subject code".equals(column))) {
            double numeric = cell.getNumericCellValue();
            if (numeric == Math.floor(numeric) && !Double.isInfinite(numeric)) {
                return String.valueOf((long) numeric);
            }
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
}
