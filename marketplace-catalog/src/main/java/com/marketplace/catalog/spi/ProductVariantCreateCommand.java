package com.marketplace.catalog.spi;

import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productVariantCreate} mutation root, the agent-2 packet): the
 * create command the GraphQL boundary hands the catalog owner's
 * variant write use case. The bounded field set is the row's own
 * direct facts: the owning product (the parent the variant registers
 * under — the ADR-0012 ownership gate), the seller's SKU, the option's
 * display name and the authoritative unit price (the platform
 * extension carrier the row's NOT NULL {@code price_minor} column
 * requires — the voucher slice's own "platform-only fields as
 * extensions" policy). The provider and the currency are the SERVICE's
 * own copy from the owning product (never client-supplied — the
 * ADR's decision 3), and the stock starts at zero and arrives through
 * the inventory surface — neither travels in this command.
 *
 * @param productId  the owning product's native UUID (the typed global
 *                   ID is decoded at the transport boundary)
 * @param sku        the seller's own SKU (unique within the seller's
 *                   namespace — the V219 unique index)
 * @param name       the option's optional display name
 * @param priceMinor the variant's authoritative unit price in minor
 *                   units (non-negative)
 */
public record ProductVariantCreateCommand(UUID productId, String sku, String name, Long priceMinor) {
}
