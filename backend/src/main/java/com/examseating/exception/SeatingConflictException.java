package com.examseating.exception;

/**
 * Thrown when a change to a seating plan is refused because it would break a
 * rule of the plan — for example moving a student onto an occupied seat or
 * onto a seat that the anti-cheating rules of the round forbid.
 *
 * <p>Surfaced as <b>409 CONFLICT</b> by the
 * {@link GlobalExceptionHandler}.</p>
 */
public class SeatingConflictException extends RuntimeException {

    public SeatingConflictException(String message) {
        super(message);
    }
}
