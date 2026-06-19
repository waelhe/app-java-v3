package com.marketplace.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Generates or propagates a correlation ID (X-Correlation-ID) for every request
 * and places it in the MDC for structured logging.
 *
 * <p><b>Input sanitization</b>: the correlation ID is validated against a strict
 * charset (alphanumeric + dash, max 128 chars) before being placed in MDC or
 * reflected in the response header. This prevents log injection / log forging
 * via CRLF-injected X-Correlation-ID values (OWASP Logging Cheat Sheet -- Log
 * Injection).
 *
 * @see <a href="https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html#log-injection">OWASP Logging Cheat Sheet -- Log Injection</a>
 */
@Component
public class CorrelationIdFilter extends OncePerRequestFilter {

    public static final String HEADER_NAME = "X-Correlation-ID";
    public static final String MDC_KEY = "correlationId";

    /** Maximum length for an accepted correlation ID. */
    private static final int MAX_LENGTH = 128;

    /** Allowed charset: alphanumeric, dash, underscore. */
    private static final Pattern VALID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1," + MAX_LENGTH + "}$");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String correlationId = request.getHeader(HEADER_NAME);
        if (correlationId == null || !VALID_PATTERN.matcher(correlationId).matches()) {
            // Reject invalid/missing correlation IDs -- generate a fresh UUID.
            correlationId = UUID.randomUUID().toString();
        }
        MDC.put(MDC_KEY, correlationId);
        response.setHeader(HEADER_NAME, correlationId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }
}
