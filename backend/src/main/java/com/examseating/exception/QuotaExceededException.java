package com.examseating.exception;

/** A plan limit would be exceeded; mapped to HTTP 429 with code QUOTA_EXCEEDED. */
public class QuotaExceededException extends RuntimeException {

    private final String quota;

    public QuotaExceededException(String quota, String message) {
        super(message);
        this.quota = quota;
    }

    public String getQuota() {
        return quota;
    }
}
