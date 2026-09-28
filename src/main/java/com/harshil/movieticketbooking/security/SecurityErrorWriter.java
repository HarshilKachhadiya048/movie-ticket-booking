package com.harshil.movieticketbooking.security;

import com.harshil.movieticketbooking.common.exception.ApiErrorResponse;
import com.harshil.movieticketbooking.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/**
 * Renders authentication and authorization failures using the same
 * {@link ApiErrorResponse} body the controller advice produces.
 * <p>
 * These failures are raised inside the Spring Security filter chain, before
 * the dispatcher servlet ever runs, so {@code @RestControllerAdvice} cannot see
 * them. Without this writer a 401 would come back as an empty body or a
 * container error page while every other failure came back as JSON.
 */
@Component
@RequiredArgsConstructor
public class SecurityErrorWriter {

    private final ObjectMapper objectMapper;
    private final Clock clock;

    public void write(HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode)
            throws IOException {
        ApiErrorResponse body = new ApiErrorResponse(
                Instant.now(clock),
                errorCode.status().value(),
                errorCode.status().getReasonPhrase(),
                errorCode.name(),
                errorCode.defaultMessage(),
                request.getRequestURI(),
                null,
                null);

        response.setStatus(errorCode.status().value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), body);
    }
}
