package com.harshil.movieticketbooking.security;

import com.harshil.movieticketbooking.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Answers unauthenticated requests with 401 and a JSON body.
 * <p>
 * The {@code WWW-Authenticate} header is deliberately omitted: browsers react
 * to it by popping a native Basic-auth dialog, which is unhelpful for an API
 * that is consumed programmatically.
 */
@Component
@RequiredArgsConstructor
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final SecurityErrorWriter errorWriter;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException) throws IOException {
        errorWriter.write(request, response, ErrorCode.AUTHENTICATION_REQUIRED);
    }
}
