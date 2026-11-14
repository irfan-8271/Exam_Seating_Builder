package com.examseating.dto;

import java.util.List;

/**
 * Requests for the flat classmate bucket API.
 */
public final class CohortRequests {

    private CohortRequests() {
    }

    /**
     * Configures a cohort inside one bucket, splitting it into newly derived flat
     * buckets by one additional attribute.
     *
     * @param attributes  the base classmate attribute keys identifying the bucket
     * @param values      the bucket's values, in the same order as {@code attributes}
     * @param groupAttribute the extra attribute chosen inside the bucket
     */
    public record ConfigureCohortRequest(
            List<String> attributes,
            List<String> values,
            String groupAttribute) {
    }

    /**
     * Preview of the buckets that would result from configuring a cohort.
     */
    public record PreviewCohortRequest(
            List<String> attributes,
            List<String> values,
            String groupAttribute) {
    }

    /**
     * Deletes every student belonging to one bucket.
     *
     * @param attributes the base classmate attribute keys identifying the bucket
     * @param values     the bucket's values, in the same order as {@code attributes}
     */
    public record DeleteCohortRequest(
            List<String> attributes,
            List<String> values) {
    }
}
