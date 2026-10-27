package com.examseating.dto;

import java.util.List;

/**
 * Result of an Excel import: how many rows were imported and what went wrong.
 * Excel files contain human error (blank cells, wrong bench types, duplicate roll
 * numbers) — we never fail the whole file, we import the good rows and report the rest.
 */
public record ImportResult(int totalRows, int imported, List<String> errors) {

    public static ImportResult of(int totalRows, int imported, List<String> errors) {
        return new ImportResult(totalRows, imported, List.copyOf(errors));
    }
}
