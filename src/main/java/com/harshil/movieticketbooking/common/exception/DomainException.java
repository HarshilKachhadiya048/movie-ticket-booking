package com.harshil.movieticketbooking.common.exception;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The single exception type thrown by the service layer for expected business
 * failures.
 * <p>
 * The HTTP status lives on the {@link ErrorCode}, so there is no parallel
 * exception hierarchy to keep in sync. {@code details} carries machine-readable
 * context that the client can act on - for example exactly which seat ids were
 * already held - without embedding it in prose.
 */
public class DomainException extends RuntimeException {

    private final ErrorCode errorCode;
    private final transient Map<String, Object> details;

    public DomainException(ErrorCode errorCode) {
        this(errorCode, errorCode.defaultMessage(), Map.of());
    }

    public DomainException(ErrorCode errorCode, String message) {
        this(errorCode, message, Map.of());
    }

    public DomainException(ErrorCode errorCode, String messageFormat, Object... args) {
        this(errorCode, messageFormat.formatted(args), Map.of());
    }

    private DomainException(ErrorCode errorCode, String message, Map<String, Object> details) {
        super(message);
        this.errorCode = errorCode;
        this.details = details;
    }

    /** Returns a copy of this exception carrying one additional detail entry. */
    public DomainException withDetail(String key, Object value) {
        Map<String, Object> merged = new LinkedHashMap<>(this.details);
        merged.put(key, value);
        return new DomainException(this.errorCode, getMessage(), Collections.unmodifiableMap(merged));
    }

    public ErrorCode errorCode() {
        return errorCode;
    }

    public Map<String, Object> details() {
        return details;
    }

    /**
     * Business exceptions are control flow, not programming errors. Skipping
     * stack trace capture keeps the seat-contention path cheap under load,
     * where losing a race is the common case rather than the exception.
     */
    @Override
    public synchronized Throwable fillInStackTrace() {
        return this;
    }
}
