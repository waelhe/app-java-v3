package com.marketplace.shared.api;

import java.util.UUID;

/**
 * Stage 6 (plan D-08, ADR-0002): the store's authoritative pricing seam —
 * the source the cart line's amount snapshot comes from.
 *
 * <p>The orders module never imports the catalog module (the V32/V48/V52
 * discipline verbatim); the price leaves the catalog module through this
 * shared contract and its catalog-side adapter. The boundary the orders
 * unit itself documented — «the store's authoritative product pricing
 * replaces the SOURCE, never the snapshot path» — is THIS port: the cart
 * line freezes what this port answers, and no caller-supplied amount is
 * consulted anywhere on the write path.
 */
public interface ProductPricingPort {

    /**
     * Resolves the product's authoritative price and storefront state.
     *
     * @param productId the catalog module's product id
     * @return the pricing carrier (minor units, ISO-4217 currency, state)
     * @throws com.marketplace.shared.api.ResourceNotFoundException when the
     *         product does not exist
     */
    ProductPrice priceOf(UUID productId);

    /**
     * The pricing carrier — the minimal facts the cart's add path reads.
     *
     * @param productId     the product's own id
     * @param providerId    the product's owning seller (the single-seller
     *                      cart invariant's source fact)
     * @param priceMinor    the authoritative unit price in minor units
     * @param currency      the ISO-4217 code the price is denominated in
     * @param storefront    the product's display lifecycle state
     */
    record ProductPrice(UUID productId, UUID providerId, long priceMinor, String currency,
                        StorefrontState storefront) {
    }

    /**
     * The product's display lifecycle (the V172 CHECK's membership set).
     * Only {@code ACTIVE} products may enter a cart — the suspended and
     * archived states are the buyer-invisible surfaces of the same rule.
     */
    enum StorefrontState {
        ACTIVE,
        SUSPENDED,
        ARCHIVED
    }
}
