package com.examseating.dto;

import java.util.List;

/**
 * What the importer found in an uploaded roster file.
 *
 * <p>This is the answer to "the spreadsheet may look like anything". The
 * importer reads every column of the sheet, keeps the ones it recognises
 * ({@code name}, {@code rollNo}, {@code department}, {@code course},
 * {@code yearSession}, {@code subjectCode}) in their typed columns and keeps
 * <b>every other column</b> as a named attribute of the student. The preview
 * then tells the caller what it found and which attributes could serve as the
 * student identifier — one attribute, or a combination of several.</p>
 *
 * <p>A client uses this to ask the institution: "your sheet has Registration
 * Number, Batch Number and Department — which one (or which pair) identifies
 * a student?"</p>
 *
 * @param columns            every column of the sheet, with how it was interpreted
 * @param suggestedIdentities attribute combinations that uniquely identify a student
 * @param currentScheme      the identity scheme already configured for the institution
 * @param totalRows          number of data rows the sheet contains
 */
public record ImportPreview(
        List<ColumnPreview> columns,
        List<IdentityOption> suggestedIdentities,
        IdentitySchemeView currentScheme,
        int totalRows) {

    /**
     * One column of the uploaded sheet.
     *
     * @param key            normalized attribute key ("rollno", "batchnumber", ...)
     * @param title          the header exactly as it appears in the file
     * @param kind           "typed" for a recognised column, "attribute" for a custom one
     * @param complete       true when every data row has a value in this column
     * @param unique         true when no two rows share a value (a candidate identifier)
     * @param distinctValues number of different values in this column
     * @param samples        a few example values
     */
    public record ColumnPreview(
            String key,
            String title,
            String kind,
            boolean complete,
            boolean unique,
            int distinctValues,
            List<String> samples) {
    }

    /**
     * A candidate identity: one attribute, or a combination of several.
     *
     * @param attributes the attribute keys, in the order they are joined
     * @param label      human-readable description
     * @param unique     true when the combination separates every row
     */
    public record IdentityOption(
            List<String> attributes,
            String label,
            boolean unique) {
    }
}
