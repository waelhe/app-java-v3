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

    /**
     * Reorders the attribute's registered option values — the Saleor
     * {@code attributeReorderValues} root's owning use case (the
     * pinned relative moves resolved against the option registry's
     * current order with the compacted 1..n re-derivation; the
     * attribute's own projection answers in the payload).
     *
     * @param attributeId the owning attribute definition's native UUID
     * @param moves       the relative moves (option value, sortOrder)
     * @return the owning attribute's projection with its reordered
     *         values, or the domain error entries (NOT_FOUND
     *         attribute/value, INVALID out-of-range move)
     */
    AttributeMutationResult reorderValues(UUID attributeId, List<AttributeReorderMove> moves);

    /**
     * Registers a batch of typed attribute definitions — the Saleor
     * {@code attributeBulkCreate} root's owning use case. The whole
     * batch rides ONE transaction; the pinned error policy's own
     * semantics answer: {@code REJECT_EVERYTHING} (the default)
     * rolls the whole batch back when any item is refused,
     * {@code IGNORE_FAILED}/{@code REJECT_FAILED_ROWS} persist the
     * passing items (the per-row granularity the flat create carries).
     *
     * @param commands     the bounded create commands (one per item)
     * @param errorPolicy  the pinned policy member name (null = the
     *                     REJECT_EVERYTHING default)
     * @return the per-item outcomes (the single-item ladder's own
     *         answers at their bulk positions)
     */
    List<AttributeBulkOutcome> bulkCreateAttributes(List<AttributeCreateCommand> commands, String errorPolicy);

    /**
     * Amends a batch of attribute definitions — the Saleor
     * {@code attributeBulkUpdate} root's owning use case (the pinned
     * {@code id + fields} items over the single-item amendment ladder;
     * the same one-transaction/policy semantics the bulk create rides).
     *
     * @param items       the update items (row id + the partial fields)
     * @param errorPolicy the pinned policy member name (null = the
     *                    REJECT_EVERYTHING default)
     * @return the per-item outcomes
     */
    List<AttributeBulkOutcome> bulkUpdateAttributes(List<AttributeBulkUpdateItem> items, String errorPolicy);
}
