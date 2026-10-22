package com.marketplace.catalog.spi;

import org.springframework.modulith.NamedInterface;

import java.util.List;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productVariantCreate}, {@code productVariantUpdate},
 * {@code productVariantDelete} and {@code productVariantBulkDelete}
 * mutation roots, the agent-2 packet): the catalog owner's write seam
 * for the merchandise model's purchasable unit (the ADR-0012 variant
 * registry). These are Saleor MANAGEMENT roots ("Requires one of the
 * following permissions: MANAGE_PRODUCTS") executed over the
 * platform's own ownership law for provider-owned rows — the same law
 * the {@code ProductWriteSpi} slice established: the owning provider
 * IS the caller (the row's denormalized {@code providerId} — the
 * V219 {@code orders.seller_id} precedent — resolves through the
 * caller's authentication), and a stranger's row answers the safe
 * absence. Authorization is enforced at the owning use-case boundary
 * by Spring Security's method security, never only in a resolver.
 *
 * <p><b>The result contract is the Saleor payload-error shape's own
 * domain mapping</b> (the registered "Mutation payload errors" ADAPT
 * divergence): domain-level validation outcomes answer as
 * {@link ProductVariantMutationError} entries on the
 * {@link ProductVariantMutationResult} — never as thrown exceptions.
 * An empty error list IS the success verdict;
 * {@link ProductVariantMutationResult#variant()} carries the row's
 * own bounded projection (the created/updated row, or the soft-deleted
 * row's last-known projection for the delete roots — Saleor's own
 * payload semantics).</p>
 *
 * <p><b>Soft delete is the registry's own discipline</b> — the
 * {@code BaseEntity} {@code @SoftDelete} filter the repository's
 * derived queries already ride, the same discipline the product slice
 * rides. The bulk delete answers the honestly-affected count —
 * unknown or stranger ids are skipped, never fabricated into the
 * count (no existence disclosure).</p>
 */
@NamedInterface("catalog-spi")
public interface ProductVariantWriteSpi {

    /**
     * Registers one variant under an owned product — the Saleor
     * {@code productVariantCreate} root's owning use case. The
     * provider and the currency are the service's own copy from the
     * owning product (never client-supplied — the ADR's decision 3);
     * the stock starts at zero and arrives through the inventory
     * surface.
     *
     * @param command       the validated-again create command
     *                      (product, sku, name, price)
     * @param authentication the caller's authentication (the owning
     *                      provider resolution)
     * @return the result with the created row's projection, or the
     *         domain validation errors (REQUIRED product/price,
     *         NOT_FOUND product, INVALID shapes, ALREADY_EXISTS sku)
     */
    ProductVariantMutationResult createVariant(ProductVariantCreateCommand command,
                                               org.springframework.security.core.Authentication authentication);

    /**
     * Amends one variant row — the Saleor {@code productVariantUpdate}
     * root's owning use case. {@code null} command fields preserve the
     * column (partial update — the Saleor input's omit-to-preserve
     * semantics). An ARCHIVED row is terminal and answers the INVALID
     * domain error (the entity's own documented law, classified here
     * instead of thrown).
     *
     * @param id            the row's native UUID (the typed global ID
     *                      is decoded at the transport boundary)
     * @param command       the amendment command (sku, name, price —
     *                      each may be null to preserve)
     * @param authentication the caller's authentication
     * @return the result with the amended row's projection, or the
     *         domain errors (NOT_FOUND, INVALID, ALREADY_EXISTS)
     */
    ProductVariantMutationResult updateVariant(UUID id, ProductVariantUpdateCommand command,
                                               org.springframework.security.core.Authentication authentication);

    /**
     * Soft-deletes one variant row — the Saleor
     * {@code productVariantDelete} root's owning use case.
     *
     * @param id            the row's native UUID
     * @param authentication the caller's authentication
     * @return the result with the deleted row's last-known projection,
     *         or the NOT_FOUND domain error
     */
    ProductVariantMutationResult deleteVariant(UUID id,
                                               org.springframework.security.core.Authentication authentication);

    /**
     * Soft-deletes the given variant rows — the Saleor
     * {@code productVariantBulkDelete} root's owning use case.
     *
     * @param ids           the rows' native UUIDs (unknown or stranger
     *                      ids are skipped — the honest affected count
     *                      answers what the caller owns and the
     *                      registry itself deleted)
     * @param authentication the caller's authentication
     * @return the count of rows actually soft-deleted
     */
    int deleteVariants(List<UUID> ids, org.springframework.security.core.Authentication authentication);

    /**
     * Registers a batch of variants under one owned product — the
     * Saleor {@code productVariantBulkCreate} root's owning use case.
     * The whole batch rides ONE transaction; the pinned error policy's
     * own semantics answer ({@code REJECT_EVERYTHING} — the default —
     * rolls the whole batch back when any item is refused,
     * {@code IGNORE_FAILED}/{@code REJECT_FAILED_ROWS} persist the
     * passing items).
     *
     * @param productId      the owning product's native UUID (every
     *                       item registers under it — the pinned root's
     *                       own product argument)
     * @param commands       the bounded create commands (one per item)
     * @param authentication the caller's authentication
     * @param errorPolicy    the pinned policy member name (null = the
     *                       REJECT_EVERYTHING default)
     * @return the per-item outcomes (the single-item ladder's own
     *         answers at their bulk positions)
     */
    List<ProductVariantBulkOutcome> bulkCreateVariants(UUID productId,
                                                       List<ProductVariantCreateCommand> commands,
                                                       org.springframework.security.core.Authentication authentication,
                                                       String errorPolicy);

    /**
     * Amends a batch of variant rows — the Saleor
     * {@code productVariantBulkUpdate} root's owning use case (the
     * pinned {@code id + fields} items over the single-item amendment
     * ladder; the same one-transaction/policy semantics).
     *
     * @param items          the update items (row id + the partial fields)
     * @param authentication the caller's authentication
     * @param errorPolicy    the pinned policy member name (null = the
     *                       REJECT_EVERYTHING default)
     * @return the per-item outcomes
     */
    List<ProductVariantBulkOutcome> bulkUpdateVariants(List<ProductVariantBulkUpdateItem> items,
                                                       org.springframework.security.core.Authentication authentication,
                                                       String errorPolicy);
}
