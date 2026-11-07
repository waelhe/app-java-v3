package com.marketplace.shared.api;

/**
 * Thrown when a requested resource is not found.
 */
public class ResourceNotFoundException extends ApiProblemDetailException {

    private final String resourceType;

    public ResourceNotFoundException(String message) {
        this(null, message);
    }

    public ResourceNotFoundException(String resourceType, Object id) {
        this(resourceType, resourceType + " not found: " + id);
    }

    private ResourceNotFoundException(String resourceType, String message) {
        super(ApiErrorTaxonomy.NOT_FOUND, message);
        this.resourceType = resourceType;
    }

    /**
     * The missed resource's own type vocabulary (the domain type name the
     * throwing owner carries, e.g. {@code "Checkout"} or
     * {@code "CheckoutLine"}), or {@code null} when the exception was
     * raised with a free-form message. Transport adapters classify the
     * missed RESOURCE through this accessor — never by parsing the
     * message (the owner's texts are internal and may change; the
     * safeMessage law keeps them off the wire contract).
     */
    public String resourceType() {
        return resourceType;
    }
}
