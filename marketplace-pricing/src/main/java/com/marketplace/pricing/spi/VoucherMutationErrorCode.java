package com.marketplace.pricing.spi;

/**
 * P3 (the canonical Saleor plan §5-P3 — the voucher mutation roots): the
 * domain error codes the voucher policy's own write rules answer — the
 * vocabulary the transport boundary maps onto the Saleor
 * {@code DiscountErrorCode} enum's own members.
 */
public enum VoucherMutationErrorCode {

    /** A required input was absent. */
    REQUIRED,

    /** An input's shape or bound violates the policy row's own contract. */
    INVALID,

    /** The addressed row does not exist (or is already soft-deleted). */
    NOT_FOUND,

    /** An ACTIVE row already carries the code (the unique key's own membership). */
    ALREADY_EXISTS,

    /** The voucher has redemptions — the code-stability discipline bars the amendment. */
    VOUCHER_ALREADY_USED
}
