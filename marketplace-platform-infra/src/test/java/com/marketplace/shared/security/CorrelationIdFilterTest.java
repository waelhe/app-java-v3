package com.marketplace.shared.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void propagatesExistingCorrelationId() throws Exception {
        HttpServletRequest request = mock();
        HttpServletResponse response = mock();
        FilterChain chain = mock();
        when(request.getHeader("X-Correlation-ID")).thenReturn("existing-id");

        filter.doFilterInternal(request, response, chain);

        verify(response).setHeader("X-Correlation-ID", "existing-id");
    }

    @Test
    void generatesCorrelationIdWhenMissing() throws Exception {
        HttpServletRequest request = mock();
        HttpServletResponse response = mock();
        FilterChain chain = mock();
        when(request.getHeader("X-Correlation-ID")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        verify(response).setHeader(eq("X-Correlation-ID"), anyString());
    }
}
