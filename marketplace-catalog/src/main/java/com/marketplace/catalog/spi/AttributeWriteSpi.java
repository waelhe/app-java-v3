package com.marketplace.catalog.spi;

import org.springframework.modulith.NamedInterface;

import java.util.List;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code attributeCreate}, {@code attributeUpdate},
 * {@code attributeDelete} and {@code attributeBulkDelete} mutation
 * roots, the agent-2 packet): the catalog owner's write seam for the
 * reusable typed attribute reference collection. Authorization lives
 * in the owning use case ({@code AttributeWriteService} enforces the
 * platform's ADMIN capability — the server-side mapping of Saleor's
 * staff MANAGE_PRODUCT_TYPES_AND_ATTRIBUTES-class requirement at the
 * use-case boundary, the same RBAC divergence row the attribute read
 * seam and the product-type write seam established), never in a
 * resolver and never duplicated at the GraphQL boundary.
 */
@NamedInterface("catalog-spi")
public interface AttributeWriteSpi {

    /**
     * Registers one typed attribute definition over the
     * {@code catalog_attribute_definitions} row. Domain validation
     * refusals answer as payload error entries; the duplicate code
     * answers ALREADY_EXISTS (the registry's own honest precheck over
     * the derived {@code findByCode} lookup).
     *
     * @param command the bounded create command (name, slug, value shape)
     * @return the created projection, or the domain error entries
     */
    AttributeMutationResult createAttribute(AttributeCreateCommand command);

    /**
     * Amends one attribute definition's display metadata. {@code null}
     * command fields preserve the column (the Saleor omit-to-preserve
     * semantics); the row's stable public code and value shape are
     * immutable (the entity's own law — no mutating method exists): a
     * slug equal to the row's own code is the idempotent re-send, and
     * any DIFFERENT slug answers the classified INVALID domain error —
     * a sent field is never a silent no-op.
     *
     * @param id      the native UUID (decoded by the boundary's codec)
     * @param command the partial update command
     * @return the updated projection, or the domain error entries
     */
    AttributeMutationResult updateAttribute(UUID id, AttributeUpdateCommand command);

    /**
     * Soft-deletes one attribute definition — the {@code BaseEntity}
     * {@code @SoftDelete} discipline; the row's last-known projection
     * answers in the payload (Saleor's own delete-payload semantics).
     *
     * @param id the native UUID (decoded by the boundary's codec)
     * @return the last-known projection, or the NOT_FOUND error entry
     */
    AttributeMutationResult deleteAttribute(UUID id);

    /**
     * Soft-deletes a batch of attribute definitions. Unknown-but-
     * well-formed ids are the owner's own honest skip; the count
     * answers only what the registry itself deleted.
     *
     * @param ids the native UUIDs (decoded by the boundary's codec)
     * @return the number of rows the registry itself deleted
     */
    int deleteAttributes(List<UUID> ids);
}
