package com.marketplace.catalog.spi;

import org.springframework.modulith.NamedInterface;

import java.util.List;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code attributeValueCreate}, {@code attributeValueUpdate},
 * {@code attributeValueDelete} and {@code attributeValueBulkDelete}
 * mutation roots, the agent-2 packet): the catalog owner's write seam
 * for the CHOICE attribute's registered option values. Authorization
 * lives in the owning use case ({@code AttributeValueWriteService}
 * enforces the platform's ADMIN capability — the same RBAC divergence
 * row the attribute definition write seam established), never in a
 * resolver and never duplicated at the GraphQL boundary.
 */
@NamedInterface("catalog-spi")
public interface AttributeValueWriteSpi {

    /**
     * Registers one option value under its owning CHOICE attribute over
     * the {@code catalog_attribute_options} row. The owning attribute
     * must exist and be CHOICE-typed (the module's own
     * {@code CatalogAttributeService.registerOption} law); the code is
     * unique within the attribute; the append position is the owner's
     * own max+1 derivation. Domain validation refusals answer as
     * payload error entries.
     *
     * @param attributeId the owning attribute's native UUID (decoded by
     *                    the boundary's codec)
     * @param command     the bounded create command (name, slug)
     * @return the value and owning-attribute projections, or the domain
     *         error entries
     */
    AttributeValueMutationResult createAttributeValue(UUID attributeId, AttributeValueCreateCommand command);

    /**
     * Amends one option value's display metadata. {@code null} command
     * fields preserve the column (the Saleor omit-to-preserve
     * semantics); the row's stable public code is immutable (the
     * entity's own identity law): a slug equal to the row's own code is
     * the idempotent re-send, and any DIFFERENT slug answers the
     * classified INVALID domain error — a sent field is never a silent
     * no-op.
     *
     * @param id      the option's native UUID (decoded by the boundary's
     *                codec)
     * @param command the partial update command
     * @return the value and owning-attribute projections, or the domain
     *         error entries
     */
    AttributeValueMutationResult updateAttributeValue(UUID id, AttributeValueUpdateCommand command);

    /**
     * Soft-deletes one option value — the {@code BaseEntity}
     * {@code @SoftDelete} discipline; the row's last-known projection
     * (with the owning attribute's own projection) answers in the
     * payload.
     *
     * @param id the option's native UUID (decoded by the boundary's codec)
     * @return the last-known projections, or the NOT_FOUND error entry
     */
    AttributeValueMutationResult deleteAttributeValue(UUID id);

    /**
     * Soft-deletes a batch of option values. Unknown-but-well-formed ids
     * are the owner's own honest skip; the count answers only what the
     * registry itself deleted.
     *
     * @param ids the options' native UUIDs (decoded by the boundary's codec)
     * @return the number of rows the registry itself deleted
     */
    int deleteAttributeValues(List<UUID> ids);
}
