package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor attribute mutation
 * roots, the agent-2 packet): one domain-level validation outcome of
 * the catalog owner's attribute write use case. The registered
 * "Mutation payload errors" ADAPT divergence carries it onto the
 * Saleor payload's own {@code errors} field (mapped onto the
 * {@code AttributeError} vocabulary at the transport boundary).
 *
 * @param field   the input field that caused the error (the payload's
 *                own {@code field} rendering — never null)
 * @param code    the domain error code (mapped onto the Saleor enum's
 *                own member at the boundary)
 * @param message the human-readable refusal reason (the payload's own
 *                {@code message} rendering)
 */
public record AttributeMutationError(String field, AttributeMutationErrorCode code, String message) {
}
