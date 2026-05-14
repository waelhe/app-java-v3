package com.marketplace.shared.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void generatesCorrelationIdWhenHeaderMissing() throws ServletException, IOException {
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var capturedId = new AtomicReference<String>();
        var chain = (FilterChain) (req, res) -> {
            capturedId.set(MDC.get(CorrelationIdFilter.MDC_KEY));
        };

        when(request.getHeader(CorrelationIdFilter.HEADER_NAME)).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        assertNotNull(capturedId.get());
        verify(response).setHeader(CorrelationIdFilter.HEADER_NAME, capturedId.get());
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void propagatesCorrelationIdFromRequest() throws ServletException, IOException {
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var capturedId = new AtomicReference<String>();
        var chain = (FilterChain) (req, res) -> {
            capturedId.set(MDC.get(CorrelationIdFilter.MDC_KEY));
        };

        when(request.getHeader(CorrelationIdFilter.HEADER_NAME)).thenReturn("abc-123");

        filter.doFilterInternal(request, response, chain);

        assertEquals("abc-123", capturedId.get());
        verify(response).setHeader(CorrelationIdFilter.HEADER_NAME, "abc-123");
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void handlesBlankCorrelationId() throws ServletException, IOException {
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var capturedId = new AtomicReference<String>();
        var chain = (FilterChain) (req, res) -> {
            capturedId.set(MDC.get(CorrelationIdFilter.MDC_KEY));
        };

        when(request.getHeader(CorrelationIdFilter.HEADER_NAME)).thenReturn("   ");

        filter.doFilterInternal(request, response, chain);

        assertNotNull(capturedId.get());
        verify(response).setHeader(CorrelationIdFilter.HEADER_NAME, capturedId.get());
        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }

    @Test
    void cleansUpMdcAfterChainException() throws ServletException, IOException {
        var request = mock(HttpServletRequest.class);
        var response = mock(HttpServletResponse.class);
        var chain = mock(FilterChain.class);

        when(request.getHeader(CorrelationIdFilter.HEADER_NAME)).thenReturn(null);
        doThrow(new ServletException("chain error")).when(chain).doFilter(request, response);

        try {
            filter.doFilterInternal(request, response, chain);
        } catch (ServletException e) {
            // expected
        }

        assertNull(MDC.get(CorrelationIdFilter.MDC_KEY));
    }
}
