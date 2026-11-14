package com.examseating.dto;

import com.fasterxml.jackson.annotation.JsonGetter;

import java.util.List;

/**
 * One flat classmate bucket derived from the institution's base classmate
 * attributes plus any bucket-level {@code Configure cohort} refinements.
 *
 * @param label          human-readable bucket label, e.g. "B.Tech · First Semester"
 * @param values         the bucket's attribute values, in attribute order
 * @param attributes     the attribute keys that produced this bucket
 * @param count          number of students in the bucket
 * @param refined        true when a cohort refinement has been configured for this bucket
 * @param groupAttribute the extra attribute configured inside this bucket (null when none)
 * @param missing        attribute keys this bucket's students have no value for
 */
public record CohortView(
        String label,
        List<String> values,
        List<String> attributes,
        int count,
        boolean refined,
        String groupAttribute,
        List<String> missing) {

    @JsonGetter("isMiscellaneous")
    public boolean isMiscellaneous() {
        return "Miscellaneous".equalsIgnoreCase(label);
    }
}
