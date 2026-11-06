package com.examseating.tenancy;

/**
 * Per-request institution id. Filled by {@link TenantFilter} before the request
 * reaches a controller and always cleared in a finally block.
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();

    private TenantContext() {
    }

    public static void set(Long institutionId) {
        CURRENT.set(institutionId);
    }

    public static Long get() {
        return CURRENT.get();
    }

    public static void clear() {
        CURRENT.remove();
    }

    /** Resolved tenant, or a clear failure when no filter ran (e.g. a background job). */
    public static Long require() {
        Long id = CURRENT.get();
        if (id == null) {
            throw new IllegalStateException("No institution resolved for this request");
        }
        return id;
    }
}
