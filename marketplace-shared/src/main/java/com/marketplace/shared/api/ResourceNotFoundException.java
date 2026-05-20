package com.marketplace.shared.api;

/**
 * Thrown when a requested resource is not found.
 */
public class ResourceNotFoundException extends ApiProblemDetailException {

    public ResourceNotFoundException(String message) {
        super(ApiErrorTaxonomy.NOT_FOUND, message);
    }

    public ResourceNotFoundException(String resourceType, Object id) {
        this(resourceType + " not found: " + id);
    }
}
