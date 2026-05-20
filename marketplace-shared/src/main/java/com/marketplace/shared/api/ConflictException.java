package com.marketplace.shared.api;

/**
 * Thrown when the requested operation violates current resource state.
 */
public class ConflictException extends ApiProblemDetailException {

    public ConflictException(String detail) {
        super(ApiErrorTaxonomy.CONFLICT, detail);
    }
}
