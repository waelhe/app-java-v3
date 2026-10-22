package com.marketplace.catalog.spi;

import org.springframework.modulith.NamedInterface;

import java.util.List;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor {@code categoryCreate},
 * {@code categoryUpdate}, {@code categoryDelete} and
 * {@code categoryBulkDelete} mutation roots): the catalog owner's write
 * seam for the listing-category registry. The registry is reference data
 * (the controlled vocabulary the listing {@code category} column
 * references — the {@code V70}-seeded open vocabulary), and these are
 * Saleor MANAGEMENT roots ("Requires one of the following permissions:
 * MANAGE_PRODUCTS"), mapped through the platform's ADMIN capability and
 * enforced HERE at the owning use-case boundary by Spring Security's
 * method security — the same {@code @PreAuthorize} mechanism the pricing
 * module's discount reads and the identity module's staff-user reads
 * established — never only in a resolver.
 *
 * <p><b>The result contract is the Saleor payload-error shape's own domain
 * mapping</b> (the registered "Mutation payload errors" ADAPT divergence):
 * domain-level validation outcomes answer as {@link CategoryMutationError}
 * entries on the {@link CategoryMutationResult} — never as thrown
 * exceptions (a thrown exception is the transport-level classified-error
 * family: malformed typed global IDs, denied authorization). An empty
 * error list IS the success verdict; {@link CategoryMutationResult#category()}
 * carries the row's own bounded projection (the created/updated row, or
 * the soft-deleted row's last-known projection for the delete roots —
 * Saleor's own payload semantics).</p>
 *
 * <p><b>Soft delete is the registry's own discipline</b> — the
 * {@code BaseEntity} {@code @SoftDelete} filter the repository's derived
 * queries already ride: the single delete converts the row's
 * {@code is_deleted} flag through Hibernate's own soft-delete SQL, and a
 * soft-deleted row releases its code for reuse (the
 * {@code uq_categories_code_active} partial unique index, the V47 slug
 * precedent). The bulk delete answers the honestly-affected count —
 * unknown ids are skipped, never fabricated into the count.</p>
 */
@NamedInterface("catalog-spi")
public interface CategoryWriteSpi {

    /**
     * Creates one registry row — the Saleor {@code categoryCreate} root's
     * owning use case.
     *
     * @param command the validated-again create command (name, slug)
     * @return the result with the created row's projection, or the
     *         domain validation errors (REQUIRED name, INVALID slug
     *         shape, ALREADY_EXISTS active code)
     */
    CategoryMutationResult createCategory(CategoryCreateCommand command);

    /**
     * Amends one registry row — the Saleor {@code categoryUpdate} root's
     * owning use case. {@code null} command fields preserve the column
     * (partial update — the Saleor input's omit-to-preserve semantics).
     *
     * @param id      the row's native UUID (the typed global ID is decoded at
     *                the transport boundary)
     * @param command the amendment command (name, slug — either may be
     *                null to preserve)
     * @return the result with the amended row's projection, or the
     *         domain errors (NOT_FOUND, INVALID, ALREADY_EXISTS)
     */
    CategoryMutationResult updateCategory(UUID id, CategoryUpdateCommand command);

    /**
     * Soft-deletes one registry row — the Saleor {@code categoryDelete}
     * root's owning use case.
     *
     * @param id the row's native UUID
     * @return the result with the deleted row's last-known projection,
     *         or the NOT_FOUND domain error
     */
    CategoryMutationResult deleteCategory(UUID id);

    /**
     * Soft-deletes the given registry rows — the Saleor
     * {@code categoryBulkDelete} root's owning use case.
     *
     * @param ids the rows' native UUIDs (unknown ids are skipped — the
     *            honest affected count answers what the registry itself
     *            deleted)
     * @return the count of rows actually soft-deleted
     */
    int deleteCategories(List<UUID> ids);
}
