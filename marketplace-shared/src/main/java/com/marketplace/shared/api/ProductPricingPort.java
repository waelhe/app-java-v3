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
     * ADR-0012 (the merchandise model's variant depth — the
     * cart-integration slice): resolves the VARIANT's authoritative price
     * and BOTH shelf states — the variant's own and its owning product's.
     * The parent's state gates the child's purchasability exactly the way
     * Saleor's publication model does: a suspended or archived product
     * hides every variant under it, whatever the variant's own state; a
     * suspended variant is buyer-invisible under an ACTIVE product (the
     * storefront read's own rule, re-checked here at the write boundary).
     *
     * @param variantId the catalog module's variant id
     * @return the variant pricing carrier (both ids, the seller, minor
     *         units, ISO-4217 currency, both shelf states)
     * @throws com.marketplace.shared.api.ResourceNotFoundException when the
     *         variant does not exist
     */
    VariantPrice priceOfVariant(UUID variantId);

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
     * ADR-0012 (the cart-integration slice): the variant's pricing
     * carrier — the minimal facts the cart's add path and the placement's
     * re-pricing read. The purchasability test is BOTH states ACTIVE
     * ({@link #purchasable()}); the two states stay separate fields so
     * the refusing 409 can NAME the shelf that hid the purchase (a
     * suspended product and a suspended variant are different operator
     * facts, and the buyer's remedy differs with them).
     *
     * @param variantId         the variant's own id
     * @param productId         the OWNING product's id (the variant's
     *                          parent — the line's product identity)
     * @param providerId        the owning product's seller (the seller
     *                          grouping's and the SKU namespace's source
     *                          fact)
     * @param priceMinor        the variant's authoritative unit price in
     *                          minor units
     * @param currency          the variant's denomination (the product's
     *                          own, copied at the variant's creation —
     *                          never client-supplied)
     * @param productStorefront the OWNING product's display lifecycle
     * @param variantStorefront the variant's own availability lifecycle
     */
    record VariantPrice(UUID variantId, UUID productId, UUID providerId,
                        long priceMinor, String currency,
                        StorefrontState productStorefront, StorefrontState variantStorefront) {

        /** The Saleor publication rule: the parent must be ACTIVE and so must the variant. */
        public boolean purchasable() {
            return productStorefront == StorefrontState.ACTIVE
                    && variantStorefront == StorefrontState.ACTIVE;
        }
    }

    /**
     * The product's display lifecycle (the V184 CHECK's membership set).
     * Only {@code ACTIVE} products may enter a cart — the suspended and
     * archived states are the buyer-invisible surfaces of the same rule.
     */
    enum StorefrontState {
        ACTIVE,
        SUSPENDED,
        ARCHIVED
    }
}
