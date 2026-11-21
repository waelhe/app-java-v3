package com.marketplace.catalog.spi;

/**
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor collection
 * mutation roots, packet agent-2): the owning use case's domain error
 * code vocabulary — the bounded owned members of the pinned
 * {@code CollectionErrorCode} enum (the members the platform's own
 * registry laws can honestly produce). The deprecated
 * {@code CANNOT_MANAGE_PRODUCT_WITHOUT_VARIANT} member (upstream's own
 * deprecation note: "This error will never be returned") and the
 * Upload-owned {@code FILE_SIZE_LIMIT_EXCEEDED} member stay undeclared —
 * no owning capability can produce them on this surface.
 */
public enum CollectionMutationErrorCode {

    /** The same product id appears more than once in one request. */
    DUPLICATED_INPUT_ITEM,

    /** A graph-level failure outside the domain vocabulary's own reach. */
    GRAPHQL_ERROR,

    /** A validation outcome over the owned field set. */
    INVALID,

    /** The addressed collection (or member product) does not exist. */
    NOT_FOUND,

    /** A required owned field is missing. */
    REQUIRED,

    /** The slug is already taken by another live row. */
    UNIQUE
}
