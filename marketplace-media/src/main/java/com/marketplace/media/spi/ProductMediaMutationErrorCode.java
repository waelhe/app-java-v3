package com.marketplace.media.spi;

/**
 * N-slice: the bounded error code vocabulary the product-media family's
 * write outcomes carry — the members the pinned {@code ProductErrorCode}
 * (the payloads' own error family — the product family's established
 * full-membership enum) already declares, so every domain outcome maps
 * onto its Saleor member verbatim.
 */
public enum ProductMediaMutationErrorCode {

    /** A duplicated entry in the addressed batch (the pinned member). */
    DUPLICATED_INPUT_ITEM,

    /** The outcome is a well-formed but unusable input shape. */
    INVALID,

    /** The addressed row/target answers no readable/owned fact. */
    NOT_FOUND,

    /** A required argument is absent. */
    REQUIRED
}
