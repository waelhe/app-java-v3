package com.marketplace.shared.api;

import java.net.URI;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.ErrorResponse;

/**
 * Thrown when a requested resource is not found.
 * Produces HTTP 404 as RFC 7807 {@link ProblemDetail}.
 */
public class ResourceNotFoundException extends RuntimeException implements ErrorResponse {

    private static final String ERROR_TYPE = "https://marketplace.com/errors/not-found";
    private final ProblemDetail body;

    public ResourceNotFoundException(String message) {
        super(message);
        this.body = createBody(message);
    }

    public ResourceNotFoundException(String resourceType, Object id) {
        this(resourceType + " not found: " + id);
    }

    @Override
    public HttpStatusCode getStatusCode() {
        return HttpStatus.NOT_FOUND;
    }

    @Override
    public ProblemDetail getBody() {
        return body;
    }

    private static ProblemDetail createBody(String detail) {
        ProblemDetail problemDetail = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, detail);
        problemDetail.setType(URI.create(ERROR_TYPE));
        problemDetail.setTitle("Not Found");
        return problemDetail;
    }
}
