package com.examseating.dto;

import java.util.List;

/**
 * Preview of an uploaded student roster spreadsheet before importing.
 *
 * <p>Every column (except the student's display {@code name}) is treated
 * uniformly as a dynamic attribute. No column is forced into hardcoded
 * university fields.</p>
 */
public record ImportPreview(
        List<ColumnPreview> columns,
        List<IdentityOption> suggestedIdentities,
        List<IdentityOption> suggestedClassmateGroups,
        IdentitySchemeView currentScheme,
        int totalRows) {

    public ImportPreview(List<ColumnPreview> columns, List<IdentityOption> suggestedIdentities,
                         IdentitySchemeView currentScheme, int totalRows) {
        this(columns, suggestedIdentities, List.of(), currentScheme, totalRows);
    }

    public record ColumnPreview(
            String key,
            String title,
            String kind,
            boolean complete,
            boolean unique,
            int distinctValues,
            List<String> samples) {
    }

    public record IdentityOption(
            List<String> attributes,
            String label,
            boolean unique) {
    }
}
