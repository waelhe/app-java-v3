package com.marketplace.shared.api;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;

/**
 * Canonical taxonomy for REST API problem details.
 */
public enum ApiErrorTaxonomy {
    VALIDATION("VAL-001", "validation", HttpStatus.BAD_REQUEST, "Bad Request", "https://marketplace.com/errors/validation"),
    AUTHZ("AUTHZ-001", "authz", HttpStatus.FORBIDDEN, "Forbidden", "https://marketplace.com/errors/access-denied"),
    AUTHN("AUTHN-001", "authz", HttpStatus.UNAUTHORIZED, "Unauthorized", "https://marketplace.com/errors/unauthorized"),
    NOT_FOUND("NF-001", "not-found", HttpStatus.NOT_FOUND, "Not Found", "https://marketplace.com/errors/not-found"),
    CONFLICT("CONFLICT-001", "conflict", HttpStatus.CONFLICT, "Conflict", "https://marketplace.com/errors/conflict"),
    RATE_LIMIT("RL-001", "rate-limit", HttpStatus.TOO_MANY_REQUESTS, "Too Many Requests", "https://marketplace.com/errors/rate-limited"),
    SERVICE_UNAVAILABLE("SU-001", "availability", HttpStatus.SERVICE_UNAVAILABLE, "Service Unavailable", "https://marketplace.com/errors/service-unavailable"),
    INTERNAL("INT-001", "internal", HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error", "https://marketplace.com/errors/internal-error");

    private final String errorCode;
    private final String category;
    private final HttpStatusCode statusCode;
    private final String title;
    private final String typeUri;

    ApiErrorTaxonomy(String errorCode, String category, HttpStatusCode statusCode, String title, String typeUri) {
        this.errorCode = errorCode;
        this.category = category;
        this.statusCode = statusCode;
        this.title = title;
        this.typeUri = typeUri;
    }

    public String errorCode() { return errorCode; }
    public String category() { return category; }
    public HttpStatusCode statusCode() { return statusCode; }
    public String title() { return title; }
    public String typeUri() { return typeUri; }
}
