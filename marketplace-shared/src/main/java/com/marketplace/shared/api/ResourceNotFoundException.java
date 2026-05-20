package com.marketplace.shared.api;

import org.springframework.http.HttpStatus;

/**
 * Thrown when a requested resource is not found.
 */
public class ResourceNotFoundException extends ApiProblemDetailException {

    private static final String ERROR_TYPE = "https://marketplace.com/errors/not-found";

    public ResourceNotFoundException(String message) {
        super(HttpStatus.NOT_FOUND, ERROR_TYPE, "Not Found", message);
    }

    public ResourceNotFoundException(String resourceType, Object id) {
        this(resourceType + " not found: " + id);
    }
}
