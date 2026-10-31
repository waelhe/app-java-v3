package com.marketplace.catalog.spi;

import org.springframework.modulith.NamedInterface;

import java.util.List;
import java.util.UUID;

/**
 * L-slice (the canonical Saleor plan §5-P2 — the Saleor
 * {@code collectionCreate}, {@code collectionUpdate},
 * {@code collectionDelete}, {@code collectionBulkDelete},
 * {@code collectionAddProducts}, {@code collectionRemoveProducts} and
 * {@code collectionReorderProducts} mutation roots, packet agent-2): the
 * catalog owner's write seam for the storefront collection registry and
 * its product membership. These are Saleor MANAGEMENT roots ("Requires
 * one of the following permissions: MANAGE_PRODUCTS"), mapped through
 * the platform's ADMIN capability (the SaleorPermissionCatalog contract)
 * and enforced HERE at the owning use-case boundary by Spring Security's
 * method security — the same {@code @PreAuthorize} mechanism every write
 * family in this module established — never only in a resolver.
 *
 * <p><b>The result contract is the Saleor payload-error shape's own
 * domain mapping</b> (the registered "Mutation payload errors" ADAPT
 * divergence): domain-level validation outcomes answer as
 * {@link CollectionMutationError} entries on the
 * {@link CollectionMutationResult} — never as thrown exceptions (a
 * thrown exception is the transport-level classified-error family:
 * malformed typed global IDs, denied authorization). An empty error
 * list IS the success verdict.</p>
 *
 * <p><b>Soft delete is the registry's own discipline</b> — the
 * {@code BaseEntity} {@code @SoftDelete} filter the repository's derived
 * queries already ride: the single delete converts the row's
 * {@code is_deleted} flag through Hibernate's own soft-delete SQL, and a
 * soft-deleted row releases its slug for reuse (the
 * {@code uq_collections_slug_live} partial unique index, the V47 slug
 * precedent — presentation identity, never referential). The bulk delete
 * answers the honestly-affected count — unknown ids are skipped, never
 * fabricated into the count. The membership write surfaces (add/remove/
 * reorder) ride the {@code collection_products} registry's own laws: the
 * live-pair partial unique index as the duplicate-add backstop, the
 * position column as the reorder's own ordering fact.</p>
 */
@NamedInterface("catalog-spi")
public interface CollectionWriteSpi {

    /**
     * Creates one registry row (with the optional initial membership) —
     * the Saleor {@code collectionCreate} root's owning use case.
     *
     * @param command the validated-again create command (name, slug,
     *                publication fact, optional member product ids)
     * @return the result with the created row's projection, or the
     *         domain validation errors (REQUIRED name, INVALID slug
     *         shape, UNIQUE live slug, NOT_FOUND/DUPLICATED_INPUT_ITEM
     *         member products)
     */
    CollectionMutationResult createCollection(CollectionCreateCommand command);

    /**
     * Amends one registry row — the Saleor {@code collectionUpdate}
     * root's owning use case. {@code null} command fields preserve the
     * column (partial update — the Saleor input's omit-to-preserve
     * semantics).
     *
     * @param id      the row's native UUID (the typed global ID is
     *                decoded at the transport boundary)
     * @param command the amendment command (name, slug, publication
     *                fact — any may be null to preserve)
     * @return the result with the amended row's projection, or the
     *         domain errors (NOT_FOUND, INVALID, UNIQUE)
     */
    CollectionMutationResult updateCollection(UUID id, CollectionUpdateCommand command);

    /**
     * Soft-deletes one registry row — the Saleor {@code collectionDelete}
     * root's owning use case. The row's live membership rides the same
     * transaction (the membership rows reference the collection — the
     * registry's own referential law).
     *
     * @param id the row's native UUID
     * @return the result with the deleted row's last-known projection,
     *         or the NOT_FOUND domain error
     */
    CollectionMutationResult deleteCollection(UUID id);

    /**
     * Soft-deletes the given registry rows — the Saleor
     * {@code collectionBulkDelete} root's owning use case.
     *
     * @param ids the rows' native UUIDs (unknown ids are skipped — the
     *            honest affected count answers what the registry itself
     *            deleted)
     * @return the count of rows actually soft-deleted
     */
    int deleteCollections(List<UUID> ids);

    /**
     * Adds member products to one collection — the Saleor
     * {@code collectionAddProducts} root's owning use case. The
     * membership's append position is the owner's own max+1 derivation;
     * a duplicate pair (already-live in the request, or already-member)
     * answers the DUPLICATED_INPUT_ITEM domain error with the offending
     * product ids; an unknown collection or product answers NOT_FOUND
     * with the same ids field.
     *
     * @param collectionId the collection's native UUID
     * @param productIds   the member products' native UUIDs (never null
     *                     or empty — the transport boundary's own guard)
     * @return the result with the amended collection's projection, or
     *         the domain errors
     */
    CollectionMutationResult addProducts(UUID collectionId, List<UUID> productIds);

    /**
     * Removes member products from one collection — the Saleor
     * {@code collectionRemoveProducts} root's owning use case. A
     * non-member product is the honest no-op skip (the remove's own
     * idempotent law — never an error, never a fabricated absence);
     * an unknown collection answers NOT_FOUND.
     *
     * @param collectionId the collection's native UUID
     * @param productIds   the member products' native UUIDs (never null
     *                     or empty)
     * @return the result with the amended collection's projection, or
     *         the domain errors
     */
    CollectionMutationResult removeProducts(UUID collectionId, List<UUID> productIds);

    /**
     * Reorders the collection's member products — the Saleor
     * {@code collectionReorderProducts} root's owning use case. The
     * pinned relative moves resolve against the membership's current
     * order with the compacted 1..n re-derivation (the position column's
     * own deterministic law); an out-of-range shift answers the INVALID
     * domain error; an unknown collection answers NOT_FOUND; a move
     * addressing a non-member product answers NOT_FOUND with the ids
     * field.
     *
     * @param collectionId the collection's native UUID
     * @param moves        the relative moves (productId + sortOrder;
     *                     never null or empty)
     * @return the result with the amended collection's projection, or
     *         the domain errors
     */
    CollectionMutationResult reorderProducts(UUID collectionId, List<CollectionProductMove> moves);
}
