package com.marketplace.shared.api;

/**
 * Thrown when a requested resource is not found.
 * Maps to HTTP 404 via {@link GlobalExceptionHandler}.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String resourceType, Object id) {
        super(resourceType + " not found: " + id);
    }
}