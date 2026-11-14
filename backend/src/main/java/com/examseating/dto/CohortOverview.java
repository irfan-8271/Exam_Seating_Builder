package com.examseating.dto;

import com.fasterxml.jackson.annotation.JsonGetter;

import java.util.List;

/**
 * Full cohort overview for the roster page.
 *
 * @param attributes the base classmate attribute keys used to derive the buckets
 * @param buckets    the flat derived buckets
 * @param candidates attribute keys available for configuring a cohort inside a bucket
 */
public record CohortOverview(
        List<String> attributes,
        List<CohortView> buckets,
        List<String> candidates) {

    @JsonGetter("miscellaneousCount")
    public int miscellaneousCount() {
        return (int) buckets.stream()
                .filter(b -> "Miscellaneous".equalsIgnoreCase(b.label()))
                .mapToInt(CohortView::count)
                .sum();
    }

    @JsonGetter("totalStudents")
    public int totalStudents() {
        return buckets.stream().mapToInt(CohortView::count).sum();
    }
}
