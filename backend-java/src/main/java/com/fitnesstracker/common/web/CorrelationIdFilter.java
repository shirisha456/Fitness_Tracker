package com.fitnesstracker.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Propagates {@code X-Correlation-ID}, mirroring Python's {@code CorrelationIdMiddleware}.
 *
 * <p>nginx forwards the inbound header, so a correlation id set by the caller survives
 * the whole hop. The id is echoed on the response and placed in the error envelope.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Correlation-ID";
    public static final String REQUEST_ATTRIBUTE = "correlationId";
    private static final String MDC_KEY = "correlation_id";

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }

        request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
        response.setHeader(HEADER, correlationId);
        MDC.put(MDC_KEY, correlationId);
        try {
            chain.doFilter(request, response);
        } finally {
            // Virtual threads are not pooled, but the filter may run on a platform thread
            // during startup probes; clearing keeps ids from leaking between requests.
            MDC.remove(MDC_KEY);
        }
    }

    /** The id for the current request, or {@code "unknown"} — never null in an envelope. */
    public static String current(HttpServletRequest request) {
        Object value = request == null ? null : request.getAttribute(REQUEST_ATTRIBUTE);
        return value instanceof String id ? id : "unknown";
    }
}
