package com.examseating.service;

import com.examseating.dto.ImportResult;
import com.examseating.entity.Bench;
import com.examseating.entity.ExamRoom;
import com.examseating.entity.Student;
import com.examseating.repository.ExamRoomRepository;
import com.examseating.repository.StudentRepository;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the Excel input files with Apache POI.
 *
 * <p>Two accepted formats (first row = headers, matched by name, order-insensitive):</p>
 * <pre>
 *   STUDENTS.xlsx : Name | Roll No | Department | Course | Year/Session | Subject Code
 *   ROOMS.xlsx    : Hall Name | Rows | Columns | Bench Type   (Bench Type = SINGLE_SEATER | DUAL_SEATER)
 * </pre>
 *
 * <p>POI fundamentals: a workbook → sheets → rows (0-based) → cells (0-based). Each cell
 * holds a typed value (string, number, formula, ...). {@link DataFormatter} renders any
 * cell to the string the user would see in Excel — the most forgiving way to read input
 * files. {@link WorkbookFactory#create} sniffs whether the file is .xlsx or .xls.</p>
 *
 * <p>Import policy: per-row try/catch — good rows are saved, bad rows are collected into
 * {@link ImportResult#errors()} instead of aborting the whole upload.</p>
 */
@Service
@RequiredArgsConstructor
public class ExcelImportService {

    private final StudentRepository studentRepository;
    private final ExamRoomRepository examRoomRepository;
    private final DataFormatter formatter = new DataFormatter();

    // ------------------------------------------------------------------
    //  Students
    // ------------------------------------------------------------------

    @Transactional
    public ImportResult importStudents(InputStream input) throws IOException {
        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            Map<String, Integer> header = headerIndexes(sheet.getRow(0));
            requireHeaders(header, "name", "roll no", "department", "course", "year/session", "subject code");

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
                    studentRepository.save(student);
                    imported++;
                } catch (Exception ex) {
                    // One bad row must not kill the whole file.
                    errors.add("Row " + (i + 1) + ": " + ex.getMessage());
                }
            }
            return ImportResult.of(total, imported, errors);
        }
    }

    // ------------------------------------------------------------------
    //  Rooms
    // ------------------------------------------------------------------

    @Transactional
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
                            .rows(Integer.parseInt(cell(row, header, "rows").trim()))
                            .columns(Integer.parseInt(cell(row, header, "columns").trim()))
                            .benchType(ExamRoom.BenchType.valueOf(cell(row, header, "bench type").trim().toUpperCase()))
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
                    if (room.getRows() <= 0 || room.getColumns() <= 0) {
                        throw new IllegalArgumentException("rows and columns must be positive");
                    }
                    examRoomRepository.save(room); // cascade persists benches
                    imported++;
                } catch (Exception ex) {
                    errors.add("Row " + (i + 1) + ": " + ex.getMessage());
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
            throw new IllegalArgumentException("Roll No is required");
        }
        if (s.getName() == null || s.getName().isBlank()) {
            throw new IllegalArgumentException("Name is required for roll " + s.getRollNo());
        }
    }

    /** Maps the header row to column indexes: {"name" -> 0, "roll no" -> 1, ...}. */
    private static Map<String, Integer> headerIndexes(Row headerRow) {
        Map<String, Integer> map = new HashMap<>();
        for (Cell cell : headerRow) {
            String title = new DataFormatter().formatCellValue(cell).trim().toLowerCase();
            if (!title.isEmpty()) {
                map.put(title, cell.getColumnIndex());
            }
        }
        return map;
    }

    private static void requireHeaders(Map<String, Integer> header, String... required) {
        for (String name : required) {
            if (!header.containsKey(name)) {
                throw new IllegalArgumentException("Missing column in Excel header: '" + name + "'");
            }
        }
    }

    private static String cell(Row row, Map<String, Integer> header, String column) {
        Integer idx = header.get(column);
        if (idx == null) {
            return "";
        }
        Cell cell = row.getCell(idx);
        return cell == null ? "" : new DataFormatter().formatCellValue(cell).trim();
    }

    private static boolean isBlank(Row row, Map<String, Integer> header) {
        for (Integer idx : header.values()) {
            Cell cell = row.getCell(idx);
            if (cell != null && !new DataFormatter().formatCellValue(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }
}
