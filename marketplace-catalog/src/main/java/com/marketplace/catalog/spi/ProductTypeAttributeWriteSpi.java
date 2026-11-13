package com.marketplace.catalog.spi;

import org.springframework.modulith.NamedInterface;

import java.util.List;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productAttributeAssign},
 * {@code productAttributeAssignmentUpdate},
 * {@code productAttributeUnassign} and
 * {@code productTypeReorderAttributes} mutation roots, the agent-2
 * follow-on packet): the catalog owner's write seam for the
 * product-type attribute schema (the assignment registry over the
 * EXISTING {@code product_type_attribute_assignments} table — no new
 * migration). These are Saleor MANAGEMENT roots ("Requires one of the
 * following permissions: MANAGE_PRODUCT_TYPES_AND_ATTRIBUTES" — the
 * attribute family's own permission class) mapped through the
 * platform's ADMIN capability and enforced HERE at the owning
 * use-case boundary by Spring Security's method security — the same
 * {@code ProductTypeAttributeService.assign}/remove precedent the
 * module itself established — never only in a resolver.
 *
 * <p><b>The scope model mapping is the platform's own:</b> the pinned
 * input's {@code type} (PRODUCT/VARIANT) plus the
 * {@code variantSelection} flag address the platform's three-scope
 * model (PRODUCT / VARIANT / VARIANT_SELECTION — a
 * VARIANT_SELECTION assignment is required for every variant and
 * rides the CHOICE value shape with the two-option floor, the
 * platform's own invariants). The append position is the OWNER's own
 * max+1 derivation within the (product type, scope) bucket — never a
 * client-supplied fact. The reorder resolves the pinned relative
 * moves against the bucket's current order with the compacted 1..n
 * re-derivation.</p>
 *
 * <p><b>The result contract is the owning family's own payload
 * shape</b> (the registered "Mutation payload errors" ADAPT
 * divergence): domain validation outcomes answer as
 * {@link ProductTypeMutationError} entries on the
 * {@link ProductTypeMutationResult}; an empty error list IS the
 * success verdict and the productType projection answers the amended
 * schema's owning row.</p>
 */
@NamedInterface("catalog-spi")
public interface ProductTypeAttributeWriteSpi {

    /**
     * Assigns attributes to a product type's schema — the Saleor
     * {@code productAttributeAssign} root's owning use case (the bulk
     * operations the pinned signature carries).
     *
     * @param productTypeId the owning product type's native UUID
     * @param operations    the assign operations (attribute, type,
     *                      variantSelection)
     * @return the amended schema's owning row projection, or the
     *         domain errors (NOT_FOUND type/attribute, INVALID
     *         combination/invariants, ALREADY_EXISTS assignment)
     */
    ProductTypeMutationResult assignAttributes(UUID productTypeId,
                                               List<ProductTypeAttributeAssignOperation> operations);

    /**
     * Amends assignments' variant-selection membership — the Saleor
     * {@code productAttributeAssignmentUpdate} root's owning use case
     * (the scope move between VARIANT and VARIANT_SELECTION).
     *
     * @param productTypeId the owning product type's native UUID
     * @param operations    the update operations (attribute, new
     *                      variantSelection flag)
     * @return the amended schema's owning row projection, or the
     *         domain errors (NOT_FOUND, INVALID invariants)
     */
    ProductTypeMutationResult updateAssignments(UUID productTypeId,
                                                List<ProductTypeAttributeAssignmentUpdateOperation> operations);

    /**
     * Unassigns attributes from a product type's schema — the Saleor
     * {@code productAttributeUnassign} root's owning use case.
     *
     * @param productTypeId the owning product type's native UUID
     * @param attributeIds  the attribute definitions' native UUIDs
     *                      (unknown ids are the owner's own honest
     *                      skip)
     * @return the amended schema's owning row projection, or the
     *         domain errors (NOT_FOUND type)
     */
    ProductTypeMutationResult unassignAttributes(UUID productTypeId, List<UUID> attributeIds);

    /**
     * Reorders one attribute-type bucket of a product type's schema —
     * the Saleor {@code productTypeReorderAttributes} root's owning
     * use case.
     *
     * @param productTypeId the owning product type's native UUID
     * @param attributeType the pinned input's type member (PRODUCT or
     *                      VARIANT — the scope bucket to reorder)
     * @param moves         the relative moves (attribute, sortOrder)
     * @return the amended schema's owning row projection, or the
     *         domain errors (NOT_FOUND, INVALID)
     */
    ProductTypeMutationResult reorderAttributes(UUID productTypeId, String attributeType,
                                                List<AttributeReorderMove> moves);
}
