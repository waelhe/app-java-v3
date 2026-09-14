package com.marketplace.shared.api;

/**
 * L34 (realestate systems plan §5 — lead capture): a request rejected by
 * an application-level rate policy — the G-R6 per-sender daily cap. Carries
 * the house RATE_LIMIT taxonomy entry (RL-001, 429) so the client sees the
 * exact same problem shape the Resilience4j {@code RequestNotPermitted}
 * channel produces; it exists because the daily cap is a counting policy
 * in the service, not a Resilience4j limiter instance (whose exception
 * factory requires one).
 */
public class TooManyRequestsException extends ApiProblemDetailException {

    public TooManyRequestsException(String detail) {
        super(ApiErrorTaxonomy.RATE_LIMIT, detail);
    }
}
