package com.marketplace.shared.api;

/**
 * The requested capability is unavailable in the current configuration —
 * answers 503 SU-001. The canonical case is an optional external provider
 * that has not been bound yet (e.g. media storage): the capability is OFF,
 * not broken, and this exception lets callers and monitors tell the two
 * apart.
 */
public class ServiceUnavailableException extends ApiProblemDetailException {

    public ServiceUnavailableException(String detail) {
        super(ApiErrorTaxonomy.SERVICE_UNAVAILABLE, detail);
    }
}
