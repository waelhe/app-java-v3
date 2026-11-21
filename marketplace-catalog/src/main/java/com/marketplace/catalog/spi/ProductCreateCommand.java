package com.marketplace.catalog.spi;

import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code productCreate}
 * mutation root, the agent-2 packet): the create command the GraphQL
 * boundary hands the catalog owner's product write use case. The
 * bounded field set is the row's own closure: the display {@code name}
 * (the entity's title), the optional {@code description}, the row's
 * NOT NULL store-category dictionary reference ({@code storeCategoryCode}
 * — the platform extension carrier for the category fact; the Saleor
 * {@code category: ID} argument's categories registry is a DIFFERENT
 * taxonomy this row does not reference), the NOT NULL authoritative
 * price facts ({@code priceMinor} + {@code currency} — platform
 * extension carriers, the voucher slice's own precedent), and the
 * optional {@code productTypeId} (the reusable goods schema — a typed
 * global ID at the boundary, the native UUID here). Unowned Saleor
 * {@code ProductCreateInput} members (the attributes family, the JSON
 * rich-text description, SEO/metadata, collections, chargeTaxes,
 * externalReference, publication state, tax class, weight, rating,
 * slug, media) have neither owning columns nor owning capabilities on
 * this surface and never reach this seam.
 *
 * @param name              the display title (the entity's title column)
 * @param description       the free-form description (optional)
 * @param storeCategoryCode the store-categories dictionary's stable code
 * @param priceMinor        the authoritative unit price in minor units
 * @param currency          the price's ISO-4217 currency code
 * @param productTypeId     the optional reusable goods schema reference
 */
public record ProductCreateCommand(String name, String description, String storeCategoryCode,
                                    Long priceMinor, String currency, UUID productTypeId) {
}
