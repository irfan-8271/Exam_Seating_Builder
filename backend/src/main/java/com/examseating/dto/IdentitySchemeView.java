package com.examseating.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * The institution's dynamic student identity, classmate cohort, and optional
 * exam-round scope configuration exchanged over {@code /api/v1/students/identity}.
 */
public record IdentitySchemeView(
        Long id,
        String label,
        List<String> attributes,
        String displayAttribute,
        List<String> classmateAttributes,
        String scopeAttribute) {

    public IdentitySchemeView(Long id, String label, List<String> attributes,
                              String displayAttribute, List<String> classmateAttributes) {
        this(id, label, attributes, displayAttribute, classmateAttributes, null);
    }

    public record IdentitySchemeRequest(
            String label,

            @NotEmpty(message = "attributes must contain at least one identifying attribute")
            List<String> attributes,

            String displayAttribute,

            List<String> classmateAttributes,

            String scopeAttribute) {

        public IdentitySchemeRequest(String label, List<String> attributes,
                                     String displayAttribute, List<String> classmateAttributes) {
            this(label, attributes, displayAttribute, classmateAttributes, null);
        }
    }
}
