package com.harshil.movieticketbooking.common.exception;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Turns every failure into the same {@link ApiErrorResponse} body.
 * <p>
 * Nothing here ever exposes a stack trace, a SQL fragment, a constraint name
 * or an entity class name to the caller. Unexpected exceptions are logged in
 * full on the server and reported to the client as a bare
 * {@link ErrorCode#INTERNAL_ERROR}.
 * <p>
 * Security exceptions are deliberately <em>re-thrown</em> rather than handled.
 * Spring Security's {@code ExceptionTranslationFilter} is the component that
 * knows whether an anonymous caller should get 401 (authenticate first) or an
 * authenticated caller should get 403; short-circuiting it here would turn
 * every missing credential into a misleading 403. The filter then renders the
 * body through {@code RestAuthenticationEntryPoint} /
 * {@code RestAccessDeniedHandler}, which emit this same record.
 */
@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler {

    private final Clock clock;

    // -----------------------------------------------------------------------
    // Expected business failures
    // -----------------------------------------------------------------------

    @ExceptionHandler(DomainException.class)
    public ResponseEntity<ApiErrorResponse> handleDomain(DomainException ex, HttpServletRequest request) {
        ErrorCode code = ex.errorCode();
        if (code.status().is5xxServerError()) {
            log.error("Domain failure {} on {}: {}", code, request.getRequestURI(), ex.getMessage());
        } else {
            log.debug("Domain failure {} on {}: {}", code, request.getRequestURI(), ex.getMessage());
        }
        return build(code, ex.getMessage(), request, null, ex.details());
    }

    // -----------------------------------------------------------------------
    // Request validation
    // -----------------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiErrorResponse> handleBodyValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiErrorResponse.FieldValidationError> fieldErrors = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .map(GlobalExceptionHandler::toFieldError)
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(),
                request, fieldErrors, Map.of());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiErrorResponse> handleParameterValidation(
            HandlerMethodValidationException ex, HttpServletRequest request) {
        List<ApiErrorResponse.FieldValidationError> fieldErrors = ex.getParameterValidationResults()
                .stream()
                .flatMap(result -> result.getResolvableErrors()
                        .stream()
                        .map(error -> new ApiErrorResponse.FieldValidationError(
                                result.getMethodParameter().getParameterName(),
                                result.getArgument(),
                                error.getDefaultMessage())))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(),
                request, fieldErrors, Map.of());
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleConstraintViolation(
            ConstraintViolationException ex, HttpServletRequest request) {
        List<ApiErrorResponse.FieldValidationError> fieldErrors = ex.getConstraintViolations()
                .stream()
                .map(violation -> new ApiErrorResponse.FieldValidationError(
                        String.valueOf(violation.getPropertyPath()),
                        violation.getInvalidValue(),
                        violation.getMessage()))
                .toList();
        return build(ErrorCode.VALIDATION_FAILED, ErrorCode.VALIDATION_FAILED.defaultMessage(),
                request, fieldErrors, Map.of());
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiErrorResponse> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {
        log.debug("Unreadable request body on {}", request.getRequestURI(), ex);
        return build(ErrorCode.MALFORMED_REQUEST, ErrorCode.MALFORMED_REQUEST.defaultMessage(),
                request, null, Map.of());
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiErrorResponse> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        String message = "Parameter '%s' has an invalid value".formatted(ex.getName());
        return build(ErrorCode.INVALID_REQUEST, message, request, null, Map.of());
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiErrorResponse> handleMissingParameter(
            MissingServletRequestParameterException ex, HttpServletRequest request) {
        String message = "Required parameter '%s' is missing".formatted(ex.getParameterName());
        return build(ErrorCode.INVALID_REQUEST, message, request, null, Map.of());
    }

    @ExceptionHandler({ NoResourceFoundException.class, HttpRequestMethodNotSupportedException.class })
    public ResponseEntity<ApiErrorResponse> handleUnknownRoute(Exception ex, HttpServletRequest request) {
        return build(ErrorCode.INVALID_REQUEST, "No handler for this request", request, null, Map.of());
    }

    // -----------------------------------------------------------------------
    // Persistence-level conflicts
    // -----------------------------------------------------------------------

    /**
     * A race that slipped past an application-level pre-check and was caught by
     * a unique or check constraint instead. The constraint name is logged, never
     * returned.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiErrorResponse> handleDataIntegrity(
            DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation on {}", request.getRequestURI(), ex);
        return build(ErrorCode.DATA_INTEGRITY_VIOLATION, ErrorCode.DATA_INTEGRITY_VIOLATION.defaultMessage(),
                request, null, Map.of());
    }

    /**
     * Lost optimistic version check, or a row lock that could not be acquired
     * (including a deadlock victim). Both are retryable from the client's point
     * of view, so both report 409.
     */
    @ExceptionHandler({
            OptimisticLockingFailureException.class,
            PessimisticLockingFailureException.class,
            CannotAcquireLockException.class })
    public ResponseEntity<ApiErrorResponse> handleLockFailure(Exception ex, HttpServletRequest request) {
        log.warn("Lock contention on {}: {}", request.getRequestURI(), ex.getMessage());
        return build(ErrorCode.CONCURRENT_MODIFICATION, ErrorCode.CONCURRENT_MODIFICATION.defaultMessage(),
                request, null, Map.of());
    }

    // -----------------------------------------------------------------------
    // Security: hand back to ExceptionTranslationFilter
    // -----------------------------------------------------------------------

    @ExceptionHandler(AccessDeniedException.class)
    public void rethrowAccessDenied(AccessDeniedException ex) {
        throw ex;
    }

    @ExceptionHandler(AuthenticationException.class)
    public void rethrowAuthentication(AuthenticationException ex) {
        throw ex;
    }

    // -----------------------------------------------------------------------
    // Fallback
    // -----------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiErrorResponse> handleUnexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return build(ErrorCode.INTERNAL_ERROR, ErrorCode.INTERNAL_ERROR.defaultMessage(),
                request, null, Map.of());
    }

    // -----------------------------------------------------------------------

    private static ApiErrorResponse.FieldValidationError toFieldError(FieldError error) {
        return new ApiErrorResponse.FieldValidationError(
                error.getField(), error.getRejectedValue(), error.getDefaultMessage());
    }

    private ResponseEntity<ApiErrorResponse> build(
            ErrorCode code,
            String message,
            HttpServletRequest request,
            List<ApiErrorResponse.FieldValidationError> fieldErrors,
            Map<String, Object> details) {
        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(clock),
                code.status().value(),
                code.status().getReasonPhrase(),
                code.name(),
                message,
                request.getRequestURI(),
                fieldErrors == null || fieldErrors.isEmpty() ? null : fieldErrors,
                details == null || details.isEmpty() ? null : details);
        return ResponseEntity.status(code.status()).body(body);
    }
}
