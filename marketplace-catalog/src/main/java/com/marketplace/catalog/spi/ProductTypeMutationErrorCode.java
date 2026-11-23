package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor product-type mutation
 * roots, the agent-2 packet): the domain error classification the owning
 * write use case answers. The members map one-to-one onto the Saleor
 * {@code ProductErrorCode} vocabulary's own members at the transport
 * boundary — the Products-domain payload error vocabulary
 * (MANAGE_PRODUCT_TYPES_AND_ATTRIBUTES roots).
 */
public enum ProductTypeMutationErrorCode {
    /** A required input was absent or blank. */
    REQUIRED,
    /** An input's shape violates the registry's own contract. */
    INVALID,
    /** The addressed row does not exist (or is already soft-deleted). */
    NOT_FOUND,
    /** An ACTIVE row already carries the code (the code's own uniqueness). */
    ALREADY_EXISTS
}
