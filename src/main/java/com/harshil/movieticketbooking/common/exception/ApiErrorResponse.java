package com.harshil.movieticketbooking.common.exception;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * The one and only error body shape returned by this API.
 * <p>
 * Emitted by {@link GlobalExceptionHandler} for controller failures and by the
 * Spring Security entry point / access denied handler, so a 401 produced inside
 * the filter chain looks exactly like a 403 produced inside a service.
 *
 * @param timestamp   when the failure was rendered, always UTC
 * @param status      HTTP status code
 * @param error       HTTP reason phrase
 * @param code        stable machine-readable {@link ErrorCode} name
 * @param message     human-readable description, never a stack trace
 * @param path        the request path that failed
 * @param fieldErrors per-field problems, present only for validation failures
 * @param details     machine-readable context, for example conflicting seat ids
 */
public record ApiErrorResponse(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        List<FieldValidationError> fieldErrors,
        Map<String, Object> details) {

    /** A single bean-validation failure. */
    public record FieldValidationError(String field, Object rejectedValue, String message) {
    }
}
