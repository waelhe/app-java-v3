package com.marketplace.catalog.spi;

import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productAttributeAssignmentUpdate} mutation root, the agent-2
 * follow-on packet): one assignment-update operation of the bulk
 * command the GraphQL boundary hands the catalog owner's
 * product-type attribute schema write use case. The pinned input's
 * own ONLY movable fact is the variant-selection flag — flipping it
 * moves the assignment between the VARIANT and VARIANT_SELECTION
 * scopes (the platform's own scope model; the attribute's
 * product/variant type itself never moves through this root).
 *
 * @param attributeId      the assigned attribute definition's native
 *                         UUID (the typed global ID is decoded at the
 *                         transport boundary)
 * @param variantSelection the new variant-selection flag (never null
 *                         — the pinned input's own non-null contract)
 */
public record ProductTypeAttributeAssignmentUpdateOperation(UUID attributeId, boolean variantSelection) {
}
