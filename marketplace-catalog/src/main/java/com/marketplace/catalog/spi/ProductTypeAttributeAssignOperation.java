package com.marketplace.catalog.spi;

import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productAttributeAssign} mutation root, the agent-2 follow-on
 * packet): one assign operation of the bulk command the GraphQL
 * boundary hands the catalog owner's product-type attribute schema
 * write use case. The operation carries the pinned input's own facts
 * verbatim — the attribute reference, the Saleor attribute type
 * (PRODUCT or VARIANT) and the variant-selection flag — and the
 * owning use case derives the platform's own scope model (PRODUCT /
 * VARIANT / VARIANT_SELECTION) with the combination's own validation
 * (a PRODUCT-typed attribute cannot simultaneously be a variant
 * selection).
 *
 * @param attributeId      the attribute definition's native UUID (the
 *                         typed global ID is decoded at the transport
 *                         boundary)
 * @param attributeType    the pinned input's type member (PRODUCT or
 *                         VARIANT — never null)
 * @param variantSelection whether the attribute is allowed in variant
 *                         selection (the VARIANT_SELECTION scope's own
 *                         carrier; null = false)
 */
public record ProductTypeAttributeAssignOperation(UUID attributeId, String attributeType,
                                                  Boolean variantSelection) {
}
