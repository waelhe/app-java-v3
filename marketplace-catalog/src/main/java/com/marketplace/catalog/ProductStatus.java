package com.marketplace.catalog;

/**
 * Stage 6 (plan D-08, ADR-0002): the product's display lifecycle. Only
 * {@code ACTIVE} products may enter a cart or a storefront read — the
 * suspended and archived states are the buyer-invisible surfaces of that
 * one rule. {@code ARCHIVED} is terminal (the machine's guard answers for
 * any transition out); {@code ACTIVE ↔ SUSPENDED} is the provider's own
 * toggle. Persisted as the V172 CHECK's string membership set
 * ({@code @Enumerated(STRING)} — the ListingStatus discipline verbatim).
 */
public enum ProductStatus {
    ACTIVE,
    SUSPENDED,
    ARCHIVED
}
