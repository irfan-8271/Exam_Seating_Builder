package com.examseating.dto;

import java.util.List;

/**
 * Result of an Excel import: how many rows were imported, what went wrong,
 * and — for a student import — how the students in the file were identified.
 *
 * <p>Excel files contain human error (blank cells, wrong bench types, duplicate
 * identifiers) — we never fail the whole file, we import the good rows and
 * report the rest.</p>
 *
 * @param totalRows the number of data rows in the file
 * @param imported  the number of rows that were imported
 * @param errors    one message per rejected row
 * @param notes     what the importer decided along the way (for example
 *                  which attributes identified the students)
 */
public record ImportResult(int totalRows, int imported, List<String> errors, List<String> notes) {

    public static ImportResult of(int totalRows, int imported, List<String> errors) {
        return new ImportResult(totalRows, imported, List.copyOf(errors), List.of());
    }

    public static ImportResult of(int totalRows, int imported, List<String> errors, List<String> notes) {
        return new ImportResult(totalRows, imported, List.copyOf(errors), List.copyOf(notes));
    }
}
