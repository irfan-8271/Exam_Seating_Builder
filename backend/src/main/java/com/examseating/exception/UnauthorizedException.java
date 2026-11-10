package com.examseating.exception;

/** Missing / expired / invalid bearer token; mapped to HTTP 401. */
public class UnauthorizedException extends RuntimeException {
    public UnauthorizedException(String message) {
        super(message);
    }
}
