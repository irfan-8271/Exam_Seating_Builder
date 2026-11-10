package com.examseating.tenancy;

import com.examseating.entity.User;

/**
 * Per-request institution id and account.
 *
 * <p>Filled by {@link TenantFilter} before the request reaches a controller
 * and always cleared in a finally block.</p>
 *
 * <p>The account is published as well as the institution, because the limits
 * that apply to a request are resolved at two levels — the institution's and
 * the account's — and the account's is the more specific one. The filter has
 * already loaded the account to authenticate it, so publishing it costs
 * nothing.</p>
 */
public final class TenantContext {

    private static final ThreadLocal<Long> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<User> CURRENT_ACCOUNT = new ThreadLocal<>();

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
        CURRENT_ACCOUNT.remove();
    }

    /** The account making the request, or null when the request carries no token. */
    public static void setAccount(User account) {
        CURRENT_ACCOUNT.set(account);
    }

    /** The account making the request, or null when the request carries no token. */
    public static User account() {
        return CURRENT_ACCOUNT.get();
    }

    /** The id of the account making the request, or null. */
    public static Long userId() {
        User account = CURRENT_ACCOUNT.get();
        return account == null ? null : account.getId();
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
