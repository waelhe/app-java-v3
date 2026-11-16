package com.marketplace.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.mock.web.MockHttpServletRequest;

class StubHttpServletRequest extends HttpServletRequestWrapper {

    StubHttpServletRequest(String requestUri) {
        super(build(requestUri, null));
    }

    StubHttpServletRequest(String requestUri, String correlationId) {
        super(build(requestUri, correlationId));
    }

    private static HttpServletRequest build(String requestUri, String correlationId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(requestUri);
        if (correlationId != null && !correlationId.isBlank()) {
            request.addHeader("X-Correlation-ID", correlationId);
        }
        return request;
    }
}
