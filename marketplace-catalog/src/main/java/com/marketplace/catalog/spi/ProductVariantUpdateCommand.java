package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productVariantUpdate} mutation root, the agent-2 packet): the
 * partial-update command the GraphQL boundary hands the catalog
 * owner's variant write use case. {@code null} fields preserve the
 * column (the Saleor input's omit-to-preserve semantics). The bounded
 * movable set is exactly the row's own amendable facts (the entity's
 * own {@code reviseDetails} seam): the display name, the SKU and the
 * authoritative unit price. The row's ownership facts (productId,
 * providerId, currency — the denormalized copies from the owning
 * product), the reserved counter and the availability lifecycle stay
 * immutable through this root — each is its own owning capability,
 * never a silent side effect of an update.
 *
 * @param sku        the seller's own SKU (null preserves; a new value
 *                   must stay free in the seller's namespace)
 * @param name       the option's display name (null preserves)
 * @param priceMinor the authoritative unit price in minor units (null
 *                   preserves)
 */
public record ProductVariantUpdateCommand(String sku, String name, Long priceMinor) {

    /** The omit-everything no-op — answered by the current projection. */
    public boolean isEmpty() {
        return sku == null && name == null && priceMinor == null;
    }
}
