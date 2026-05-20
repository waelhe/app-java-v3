package com.marketplace.shared.api;

import org.springframework.http.HttpStatus;

/**
 * Thrown when the requested operation violates current resource state.
 */
public class ConflictException extends ApiProblemDetailException {

    private static final String ERROR_TYPE = "https://marketplace.com/errors/conflict";

    public ConflictException(String detail) {
        super(HttpStatus.CONFLICT, ERROR_TYPE, "Conflict", detail);
    }
}
