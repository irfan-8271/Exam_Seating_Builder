package com.examseating.exception;

import com.examseating.engine.SeatingArrangementEngine.SeatingEngineException;
import jakarta.validation.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import java.time.Instant;

@Slf4j

/**
 * Converts exceptions thrown anywhere in the controllers/services into clean JSON
 * responses with proper HTTP status codes.
 *
 * <p>{@code @RestControllerAdvice} = a global "catch block" for every controller.
 * When any handler method throws, Spring looks for an {@code @ExceptionHandler}
 * method whose parameter type matches and calls it instead of letting the exception
 * bubble up to Tomcat's HTML error page.</p>
 *
 * <p>This is why controllers can stay thin: they don't wrap everything in try/catch —
 * they just throw, and this class formats the error.</p>
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 400 — bean validation failed (@NotNull/@Min/... on a request body). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse("validation failed");
        return error(HttpStatus.BAD_REQUEST, "Validation error", message, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Validation error", ex.getMessage(), request);
    }

    /** 404 — requested entity does not exist. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex,
                                                        HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "Not found", ex.getMessage(), request);
    }

    /** 422 — the seating engine rejected the input (capacity, gaps, unsatisfiable...). */
    @ExceptionHandler(SeatingEngineException.class)
    public ResponseEntity<ErrorResponse> handleEngine(SeatingEngineException ex,
                                                      HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "Seating engine error", ex.getMessage(), request);
    }

    /** 409 — a database constraint was violated (e.g. duplicate roll number). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleConflict(DataIntegrityViolationException ex,
                                                        HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "Data conflict", "A record with the same unique value already exists.", request);
    }

    /** 413 — uploaded file too large. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException ex,
                                                        HttpServletRequest request) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "File too large", ex.getMessage(), request);
    }

    /** 400 — a bad Excel file or bad request content (import header errors etc.). */
    @ExceptionHandler({IllegalArgumentException.class, java.io.IOException.class})
    public ResponseEntity<ErrorResponse> handleBadInput(Exception ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Bad request", ex.getMessage(), request);
    }

    /**
     * 403 — @PreAuthorize denial. URL-level denials are answered by the security
     * filter chain's accessDeniedHandler; method-level denials throw
     * AccessDeniedException inside the controller, which lands here.
     */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(org.springframework.security.access.AccessDeniedException ex,
                                                            HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "Access denied",
                "Your role is not allowed to perform this action.", request);
    }

    /** 500 — anything else. The message is deliberately generic; the full stack goes to the log. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error",
                "An unexpected error occurred. See the server log for details.", request);
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String error, String message,
                                                HttpServletRequest request) {
        return ResponseEntity.status(status).body(
                new ErrorResponse(Instant.now(), status.value(), error, message, request.getRequestURI()));
    }

    /** Uniform error body. */
    public record ErrorResponse(Instant timestamp, int status, String error, String message, String path) {
    }
}
