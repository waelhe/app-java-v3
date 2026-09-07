package com.marketplace.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Generates or propagates a correlation ID (X-Correlation-ID) for every request
 * and places it in the MDC for structured logging.
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    @Override
    /**
     * Propagates or generates the correlation id and publishes it to the
     * MDC, the response header and — since A6 — the {@code correlationId}
     * request attribute, so the error path can emit it in the problem
     * body when the client sent no header.
     */
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER_NAME);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        // A6: expose the generated/propagated id as a request attribute so the
        // error path (GlobalExceptionHandler.problem) can emit it in the problem
        // body even when the client sent no header — otherwise the server-side
        // generated traceId stays in the MDC and never reaches the response.
        request.setAttribute(MDC_KEY, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
