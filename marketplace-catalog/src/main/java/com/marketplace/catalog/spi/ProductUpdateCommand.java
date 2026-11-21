package com.marketplace.catalog.spi;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productUpdate} mutation root, the agent-2 packet): the
 * partial-update command the GraphQL boundary hands the catalog
 * owner's product write use case. {@code null} fields preserve the
 * column (the Saleor input's omit-to-preserve semantics). The bounded
 * movable set is exactly the platform's own product-update law (the
 * {@code ProductService.updateOwnedProduct} contract): the display
 * title, the description and the price. The row's ownership facts
 * (providerId, storeCategoryCode, currency, productTypeId) and the
 * lifecycle state stay immutable through this root — each is its own
 * owning capability (the lifecycle transitions, the category move),
 * never a silent side effect of an update.
 *
 * @param name        the display title (null preserves)
 * @param description the free-form description (null preserves)
 * @param priceMinor  the authoritative unit price in minor units (null preserves)
 */
public record ProductUpdateCommand(String name, String description, Long priceMinor) {

    /** The omit-everything no-op — answered by the current projection. */
    public boolean isEmpty() {
        return name == null && description == null && priceMinor == null;
    }
}
