package com.marketplace.media;

public enum MediaOwnerKind {

    /** The V32 target: a provider listing's photo (provider-scoped). */
    LISTING,

    /** The L48 target: a neighborhood post's photo (member-scoped). */
    POST,

    /**
     * The A-17 target (compliance plan C.7 — the M1 store root): a store
     * product's photo (provider-scoped — the product's owning provider,
     * resolved through the {@code ProductLookupPort} seam).
     */
    PRODUCT
}
