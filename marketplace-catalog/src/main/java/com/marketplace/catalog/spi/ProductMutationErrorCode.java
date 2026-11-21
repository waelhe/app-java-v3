package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor product mutation
 * roots, the agent-2 packet): the domain error classification the
 * owning write use case answers. The members map one-to-one onto the
 * Saleor {@code ProductErrorCode} vocabulary's own members at the
 * transport boundary — the Products-domain payload error vocabulary
 * (MANAGE_PRODUCTS roots). The NOT_FOUND member carries the ownership
 * law's own safe absence: a stranger's row is answered as absence,
 * never as an existence disclosure (the R5 shape the read side
 * established).
 */
public enum ProductMutationErrorCode {
    /** A required input was absent or blank. */
    REQUIRED,
    /** An input's shape violates the row's own contract. */
    INVALID,
    /** The addressed row does not exist, is already soft-deleted, or is another provider's row (the safe absence). */
    NOT_FOUND
}
