package com.marketplace.shared.api;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import org.springframework.mock.web.MockHttpServletRequest;

class StubHttpServletRequest extends HttpServletRequestWrapper {

    StubHttpServletRequest(String requestUri) {
        super(build(requestUri));
    }

    private static HttpServletRequest build(String requestUri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(requestUri);
        return request;
    }
}
