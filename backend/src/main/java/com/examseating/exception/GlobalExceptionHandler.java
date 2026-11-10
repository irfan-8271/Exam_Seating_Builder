package com.examseating.exception;

import com.examseating.dto.ArrangeRequest;
import com.examseating.engine.SeatingArrangementEngine;
import com.examseating.engine.SeatingArrangementEngine.SeatingEngineException;
import com.examseating.exception.ForbiddenException;
import com.examseating.exception.QuotaExceededException;
import com.examseating.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Converts exceptions thrown anywhere in the controllers/services into one
 * uniform JSON error envelope with correct HTTP status codes.
 *
 * <p>Every response has the same shape: {@code timestamp, status, code, error,
 * message, path, details}. Machine clients can switch on {@code code}; humans
 * read {@code message}. This applies to both the {@code /api/v1} resources and
 * the legacy {@code /api} aliases.</p>
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 400 — bean validation failed (@NotNull/@Min/... on a request body). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex,
                                                          HttpServletRequest request) {
        List<String> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .toList();
        String message = details.isEmpty() ? "validation failed" : String.join("; ", details);
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation error", message,
                details, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex,
                                                                   HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "Validation error",
                ex.getMessage(), List.of(), request);
    }

    /**
     * 400 — unparseable JSON / unknown enum value / null in a non-nullable field.
     *
     * <p>When the body failed because of an unknown enum constant, the message
     * names the accepted values — "check the enum spellings" on its own sends
     * the caller hunting through the source.</p>
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleUnreadable(HttpMessageNotReadableException ex,
                                                           HttpServletRequest request) {
        String detail = ex.getMostSpecificCause().getMessage();
        List<String> details = List.of();
        String message = "The JSON body could not be read. Check field names, value types and enum spellings.";
        if (detail != null) {
            if (detail.contains("StrictnessLevel")) {
                message = "Unknown strictness level. Accepted values: "
                        + Arrays.stream(ArrangeRequest.StrictnessLevel.values())
                        .map(Enum::name).collect(Collectors.joining(", "));
                details = List.of("strictness");
            } else if (detail.contains("ConflictScope")) {
                message = "Unknown conflict scope. Accepted values: "
                        + Arrays.stream(SeatingArrangementEngine.ConflictScope.values())
                        .map(Enum::name).collect(Collectors.joining(", "));
                details = List.of("conflictScope");
            } else if (detail.contains("BenchPairingRule")) {
                message = "Unknown bench pairing rule. Accepted values: "
                        + Arrays.stream(SeatingArrangementEngine.BenchPairingRule.values())
                        .map(Enum::name).collect(Collectors.joining(", "));
                details = List.of("benchPairingRule");
            } else if (detail.contains("GroupMatchMode")) {
                message = "Unknown group match mode. Accepted values: "
                        + Arrays.stream(SeatingArrangementEngine.GroupMatchMode.values())
                        .map(Enum::name).collect(Collectors.joining(", "));
                details = List.of("groupMatchMode");
            }
        }
        return error(HttpStatus.BAD_REQUEST, "MALFORMED_JSON", "Malformed request body",
                message, details, request);
    }

    /** 400 — a query/path parameter could not be converted (e.g. page=abc). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex,
                                                             HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "INVALID_PARAMETER", "Invalid parameter",
                "Parameter '" + ex.getName() + "' has an invalid value.", List.of(), request);
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ErrorResponse> handleMissingParameter(MissingServletRequestParameterException ex,
                                                                 HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "MISSING_PARAMETER", "Missing parameter",
                "Required parameter '" + ex.getParameterName() + "' is missing.", List.of(), request);
    }

    /** 405 — wrong HTTP verb on an existing resource. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex,
                                                                 HttpServletRequest request) {
        return error(HttpStatus.METHOD_NOT_ALLOWED, "METHOD_NOT_ALLOWED", "Method not allowed",
                "HTTP " + ex.getMethod() + " is not supported on this resource.", List.of(), request);
    }

    /** 415 — unsupported Content-Type. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ErrorResponse> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex,
                                                                      HttpServletRequest request) {
        return error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_MEDIA_TYPE",
                "Unsupported media type", "Content-Type '" + ex.getContentType() + "' is not supported.",
                List.of(), request);
    }

    /** 404 — no handler matched the URL. */
    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException ex,
                                                           HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found",
                "No resource exists at this path.", List.of(), request);
    }

    /** 404 — requested entity does not exist. */
    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(ResourceNotFoundException ex,
                                                        HttpServletRequest request) {
        return error(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", ex.getMessage(), List.of(), request);
    }

    /** 422 — the seating engine rejected the input (capacity, gaps, unsatisfiable...). */
    @ExceptionHandler(SeatingEngineException.class)
    public ResponseEntity<ErrorResponse> handleEngine(SeatingEngineException ex,
                                                      HttpServletRequest request) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "SEATING_ERROR", "Seating engine error",
                ex.getMessage(), List.of(), request);
    }

    /** 409 — a database constraint was violated (e.g. duplicate roll number). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ErrorResponse> handleConflict(DataIntegrityViolationException ex,
                                                        HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "CONFLICT", "Data conflict",
                "A record with the same unique value already exists.", List.of(), request);
    }

    /** 409 — a change to a seating plan would break one of the plan's rules. */
    @ExceptionHandler(SeatingConflictException.class)
    public ResponseEntity<ErrorResponse> handleSeatingConflict(SeatingConflictException ex,
                                                                HttpServletRequest request) {
        return error(HttpStatus.CONFLICT, "CONFLICT", "Data conflict", ex.getMessage(),
                List.of(), request);
    }

    /** 413 — uploaded file too large. */
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ErrorResponse> handleTooLarge(MaxUploadSizeExceededException ex,
                                                        HttpServletRequest request) {
        return error(HttpStatus.PAYLOAD_TOO_LARGE, "PAYLOAD_TOO_LARGE", "File too large",
                ex.getMessage(), List.of(), request);
    }

    /** 400 — a bad Excel file or bad request content (import header errors etc.). */
    @ExceptionHandler({IllegalArgumentException.class, java.io.IOException.class})
    public ResponseEntity<ErrorResponse> handleBadInput(Exception ex, HttpServletRequest request) {
        return error(HttpStatus.BAD_REQUEST, "BAD_REQUEST", "Bad request", ex.getMessage(),
                List.of(), request);
    }

    /** 403 — @PreAuthorize denial inside a controller. */
    @ExceptionHandler(org.springframework.security.access.AccessDeniedException.class)
    public ResponseEntity<ErrorResponse> handleAccessDenied(
            org.springframework.security.access.AccessDeniedException ex, HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied",
                "Your role is not allowed to perform this action.", List.of(), request);
    }

    /**
     * 403 — the caller is authenticated but may not do this, and the reason
     * is worth reading ("this is an administrator account — use the other
     * sign-in"). The message is shown as it was written.
     */
    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ErrorResponse> handleForbidden(ForbiddenException ex,
                                                         HttpServletRequest request) {
        return error(HttpStatus.FORBIDDEN, "FORBIDDEN", "Access denied",
                ex.getMessage(), List.of(), request);
    }

    /** 429 — a subscription plan limit was reached. */
    @ExceptionHandler(QuotaExceededException.class)
    public ResponseEntity<ErrorResponse> handleQuota(QuotaExceededException ex,
                                                     HttpServletRequest request) {
        return error(HttpStatus.TOO_MANY_REQUESTS, "QUOTA_EXCEEDED", "Plan limit reached",
                ex.getMessage(), List.of(ex.getQuota()), request);
    }

    /** 401 — missing, expired or invalid bearer token. */
    @ExceptionHandler(UnauthorizedException.class)
    public ResponseEntity<ErrorResponse> handleUnauthorized(UnauthorizedException ex,
                                                            HttpServletRequest request) {
        return error(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "Authentication required",
                ex.getMessage(), List.of(), request);
    }

    /** 500 — anything else. The message is deliberately generic; the stack goes to the log. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneric(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return error(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "Internal error",
                "An unexpected error occurred. See the server log for details.", List.of(), request);
    }

    private ResponseEntity<ErrorResponse> error(HttpStatus status, String code, String error, String message,
                                                List<String> details, HttpServletRequest request) {
        return ResponseEntity.status(status).body(new ErrorResponse(
                Instant.now(), status.value(), code, error, message,
                request.getRequestURI(), details == null ? List.of() : List.copyOf(details)));
    }

    /** Uniform error envelope returned by every failure. */
    public record ErrorResponse(Instant timestamp, int status, String code, String error,
                                String message, String path, List<String> details) {
    }
}
