package com.marketplace.shared.api;

import org.springframework.http.HttpStatus;

/**
 * Thrown when client input is invalid for a specific business operation.
 */
public class BadRequestException extends ApiProblemDetailException {

    private static final String ERROR_TYPE = "https://marketplace.com/errors/bad-request";

    public BadRequestException(String detail) {
        super(HttpStatus.BAD_REQUEST, ERROR_TYPE, "Bad Request", detail);
    }
}
