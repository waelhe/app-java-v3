package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code attributeCreate} mutation root, the agent-2 packet): the
 * domain error code vocabulary of the catalog owner's attribute write
 * use case. The closed member set is the platform's own domain
 * outcomes; the GraphQL boundary maps each member onto the Saleor
 * {@code AttributeErrorCode} vocabulary's own member (the registered
 * "Mutation payload errors" ADAPT divergence — the codes are the
 * shared enum members).
 */
public enum AttributeMutationErrorCode {

    /** A required input field was absent or blank. */
    REQUIRED,

    /** An input field failed the row's own shape or domain law. */
    INVALID,

    /** The addressed attribute row does not exist. */
    NOT_FOUND,

    /** The slug is already owned by another attribute row. */
    ALREADY_EXISTS
}
