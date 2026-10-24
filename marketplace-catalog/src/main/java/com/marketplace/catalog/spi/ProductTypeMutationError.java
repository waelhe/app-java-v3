package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor product-type mutation
 * roots, the agent-2 packet): one domain-level validation outcome of the
 * catalog owner's product-type write use case. The registered "Mutation
 * payload errors" ADAPT divergence carries it onto the Saleor payload's
 * own {@code errors} field (mapped onto the {@code ProductError}
 * vocabulary at the transport boundary).
 *
 * @param field   the input field that caused the error (the payload's
 *                own field pointer, never a column name)
 * @param code    the domain error classification
 * @param message the human-readable explanation
 */
public record ProductTypeMutationError(String field, ProductTypeMutationErrorCode code, String message) {
}
