package com.examseating.dto;

import java.util.List;

/**
 * Uniform paginated collection envelope used by every {@code GET} collection
 * resource in the v1 API.
 *
 * @param content       the current page of items (never null)
 * @param page          0-based page number
 * @param size          requested page size
 * @param totalElements total items after filtering
 * @param totalPages    total pages (0 when empty)
 * @param first         true for page 0
 * @param last          true when this is the final page
 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements,
                              int totalPages, boolean first, boolean last) {

    public static final int MAX_SIZE = 200;

    /** Slices an already-filtered, already-sorted list into one page. */
    public static <T> PageResponse<T> of(List<T> source, int page, int size) {
        if (page < 0) {
            throw new IllegalArgumentException("page must be >= 0");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new IllegalArgumentException("size must be between 1 and " + MAX_SIZE);
        }
        long total = source.size();
        int totalPages = (int) ((total + size - 1) / size);
        long offset = (long) page * size;
        int from = offset >= total ? (int) total : (int) offset;
        int to = (int) Math.min(offset + size, total);
        List<T> content = List.copyOf(source.subList(from, to));
        boolean last = totalPages == 0 || page >= totalPages - 1;
        return new PageResponse<>(content, page, size, total, totalPages, page == 0, last);
    }
}
