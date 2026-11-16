package com.marketplace.shared.api;

/**
 * Thrown when client input is invalid for a specific business operation.
 */
public class BadRequestException extends ApiProblemDetailException {

    public BadRequestException(String detail) {
        super(ApiErrorTaxonomy.VALIDATION, detail);
    }
}
