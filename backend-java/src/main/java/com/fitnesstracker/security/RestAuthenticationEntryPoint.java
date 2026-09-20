package com.fitnesstracker.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fitnesstracker.common.api.ErrorCode;
import com.fitnesstracker.common.api.ErrorResponse;
import com.fitnesstracker.common.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

/**
 * Renders an unauthenticated request as the application's error envelope.
 *
 * <p>Spring Security's default is an empty 403 for anonymous access; the Python backend
 * returns <b>401</b> with {@code {"error": {...}}}, and the Next.js BFF keys its silent
 * refresh off exactly that status. Getting this wrong breaks every logged-in page.
 */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    public RestAuthenticationEntryPoint(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            AuthenticationException authException)
            throws IOException {
        Object failure = request.getAttribute(JwtAuthenticationFilter.FAILURE_ATTRIBUTE);
        String code = ErrorCode.UNAUTHORIZED;
        String message = "Not authenticated";
        if (failure instanceof TokenException tokenException) {
            code = tokenException.getCode();
            message = tokenException.getMessage();
        }

        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(
                response.getOutputStream(),
                ErrorResponse.of(code, message, List.of(), CorrelationIdFilter.current(request)));
    }
}
