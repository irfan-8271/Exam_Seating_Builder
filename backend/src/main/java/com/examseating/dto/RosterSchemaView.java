package com.examseating.dto;

import com.fasterxml.jackson.annotation.JsonGetter;

import java.util.List;
import java.util.Map;

/**
 * Live schema inspection of the current institution's student dataset across
 * both Excel-uploaded and manually added student records.
 */
public record RosterSchemaView(
        List<ImportPreview.ColumnPreview> columns,
        List<ImportPreview.IdentityOption> suggestedIdentities,
        List<ImportPreview.IdentityOption> suggestedClassmateGroups,
        IdentitySchemeView currentScheme,
        Map<String, List<String>> distinctValuesByAttribute,
        int totalStudents) {

    public RosterSchemaView(List<ImportPreview.ColumnPreview> columns,
                            List<ImportPreview.IdentityOption> suggestedIdentities,
                            List<ImportPreview.IdentityOption> suggestedClassmateGroups,
                            IdentitySchemeView currentScheme,
                            int totalStudents) {
        this(columns, suggestedIdentities, suggestedClassmateGroups, currentScheme, Map.of(), totalStudents);
    }

    @JsonGetter("currentIdentity")
    public List<String> currentIdentity() {
        return currentScheme == null || currentScheme.attributes() == null
                ? List.of()
                : currentScheme.attributes();
    }

    @JsonGetter("currentClassmateAttributes")
    public List<String> currentClassmateAttributes() {
        return currentScheme == null || currentScheme.classmateAttributes() == null
                ? List.of()
                : currentScheme.classmateAttributes();
    }

    @JsonGetter("currentScopeAttribute")
    public String currentScopeAttribute() {
        return currentScheme == null ? null : currentScheme.scopeAttribute();
    }
}
