package com.marketplace.shared.api;

import java.net.URI;

import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;

/**
 * Base exception for API errors represented as RFC 7807 ProblemDetail.
 */
public abstract class ApiProblemDetailException extends RuntimeException implements ErrorResponse {

    private final HttpStatusCode statusCode;
    private final ProblemDetail body;

    protected ApiProblemDetailException(HttpStatusCode statusCode, String typeUri, String title, String detail) {
        super(detail);
        this.statusCode = statusCode;
        this.body = ProblemDetail.forStatusAndDetail(statusCode, detail);
        this.body.setType(URI.create(typeUri));
        this.body.setTitle(title);
    }

    @Override
    public HttpStatusCode getStatusCode() {
        return statusCode;
    }

    @Override
    public ProblemDetail getBody() {
        return body;
    }
}
