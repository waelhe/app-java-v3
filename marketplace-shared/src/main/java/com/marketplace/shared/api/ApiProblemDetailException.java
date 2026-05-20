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

    protected ApiProblemDetailException(ApiErrorTaxonomy taxonomy, String detail) {
        super(detail);
        this.statusCode = taxonomy.statusCode();
        this.body = ProblemDetail.forStatusAndDetail(statusCode, detail);
        this.body.setType(URI.create(taxonomy.typeUri()));
        this.body.setTitle(taxonomy.title());
        this.body.setProperty("errorCode", taxonomy.errorCode());
        this.body.setProperty("category", taxonomy.category());
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
