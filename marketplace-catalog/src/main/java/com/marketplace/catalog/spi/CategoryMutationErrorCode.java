package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the category mutation roots): the
 * domain error codes the registry's own write rules answer — the
 * vocabulary the transport boundary maps onto the Saleor
 * {@code ProductErrorCode} enum's own members.
 */
public enum CategoryMutationErrorCode {

    /** A required input was absent or blank. */
    REQUIRED,

    /** An input's shape violates the registry's own contract. */
    INVALID,

    /** The addressed row does not exist (or is already soft-deleted). */
    NOT_FOUND,

    /** An ACTIVE row already carries the code (the partial unique index's own membership). */
    ALREADY_EXISTS
}
