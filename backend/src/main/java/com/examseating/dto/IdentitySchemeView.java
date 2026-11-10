package com.examseating.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

/**
 * The institution's student-identification rule, as exchanged over the API.
 *
 * @param id               the stored scheme's id (null when auto-detected)
 * @param label            human-readable name, e.g. "Roll Number + Batch Number"
 * @param attributes       the identifying attributes, in the order they are joined
 * @param displayAttribute the attribute shown to humans (may be null)
 */
public record IdentitySchemeView(
        Long id,
        String label,
        List<String> attributes,
        String displayAttribute) {

    /**
     * Request body for setting the identity scheme.
     *
     * @param label            optional human-readable name
     * @param attributes       the identifying attributes — one, or a combination
     * @param displayAttribute optional: which of them to show to humans
     */
    public record IdentitySchemeRequest(
            String label,

            @NotEmpty(message = "attributes must contain at least one identifying attribute")
            List<String> attributes,

            String displayAttribute) {
    }
}
