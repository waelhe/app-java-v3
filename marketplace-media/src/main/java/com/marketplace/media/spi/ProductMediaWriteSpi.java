package com.marketplace.media.spi;

import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

/**
 * N-slice (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productMediaDelete}, {@code productMediaBulkDelete} and
 * {@code productMediaReorder} mutation roots, packet agent-2): the media
 * owner's write seam for the product-media family — the three roots whose
 * pinned signatures ride facts the module ALREADY owns (the
 * {@code MediaService.delete} seam for the deletions, the
 * {@code media_assets.position} column for the reorder). The family's
 * remaining roots stay behind the recorded owner decisions (the create's
 * Upload-scalar input needs the module's own input design; the alt-based
 * update/translate need alt storage the one-language row law does not
 * carry) — never a partial shell.
 *
 * <p>These are Saleor MANAGEMENT roots ("Requires one of the following
 * permissions: MANAGE_PRODUCTS") executed over the platform's own
 * ownership law for provider-owned rows — the module's own A-17/C.7 law
 * the upload and read surfaces already carry: the row's
 * {@code providerId} resolves through the {@code ProviderLookupPort}
 * seam (the admin bypass included, {@code MediaService}'s own
 * {@code verifyOwnership} law), and a stranger's row answers the same
 * safe absence as a missing one (the R5 posture — never an existence
 * disclosure). Authorization is enforced at the owning use-case boundary
 * by Spring Security's method security, never only in a resolver.</p>
 *
 * <p><b>The result contract is the Saleor payload-error shape's own
 * domain mapping</b> (the registered "Mutation payload errors" ADAPT
 * divergence): domain-level validation outcomes answer as
 * {@link ProductMediaMutationError} entries on the results — never as
 * thrown exceptions. An empty error list IS the success verdict. The
 * bulk delete answers the honestly-affected count — unknown or stranger
 * ids are skipped, never fabricated into the count (the registry's own
 * bulk-delete law every packet family carries).</p>
 */
@NamedInterface("media-spi")
public interface ProductMediaWriteSpi {

    /**
     * Deletes one product-media row — the Saleor {@code productMediaDelete}
     * root's owning use case. The deletion itself rides the module's own
     * {@code MediaService.delete} seam (the soft delete + the after-commit
     * storage-object cleanup, unchanged); this use case adds only the
     * product-media family's own typed boundary and the classified
     * outcome.
     *
     * @param mediaId        the row's native UUID (the typed global ID is
     *                       decoded at the transport boundary)
     * @param authentication the caller's authentication (the ownership
     *                       resolution)
     * @return the result with the deleted row's last-known projection (the
     *         Saleor delete-payload semantics), or the NOT_FOUND domain
     *         error — a missing row, a foreign-target row (a listing's or
     *         a post's media addressed through the product-media root) and
     *         a stranger's row all answer the same safe absence
     */
    ProductMediaMutationResult deleteProductMedia(UUID mediaId, Authentication authentication);

    /**
     * Deletes a batch of product-media rows — the Saleor
     * {@code productMediaBulkDelete} root's owning use case. Every row
     * rides the same single-delete ladder (the module's own seam per row,
     * one transaction); unknown or stranger ids are the honest skip and
     * the count answers only what the registry itself deleted.
     *
     * @param mediaIds       the rows' native UUIDs (decoded at the
     *                       transport boundary)
     * @param authentication the caller's authentication
     * @return the honestly-affected count (never fabricated, never an
     *         existence disclosure for another provider's row)
     */
    ProductMediaBulkResult bulkDeleteProductMedia(List<UUID> mediaIds, Authentication authentication);

    /**
     * Rewrites the product's media display order — the Saleor
     * {@code productMediaReorder} root's owning use case. The given
     * ordered list must cover EXACTLY the product's live displayable
     * (UPLOADED) rows; the positions re-derive 1..n in the given order
     * (the compacted re-derivation — never a client-supplied absolute
     * position), and any pending-upload intents re-park after the
     * confirmed block so the per-product positions stay unique. The
     * whole rewrite runs under the module's own advisory position lock
     * (the {@code lockProductPositionAllocation} discipline the upload
     * allocation established).
     *
     * @param productId       the owning product's native UUID (resolved
     *                        through the {@code ProductLookupPort} seam —
     *                        the product's owning provider IS the reorder's
     *                        ownership gate)
     * @param orderedMediaIds the displayable rows' native UUIDs in the
     *                        desired order
     * @param authentication  the caller's authentication
     * @return the result with the new-order projections, or the domain
     *         errors (REQUIRED/NOT_FOUND product, REQUIRED/
     *         DUPLICATED_INPUT_ITEM/NOT_FOUND/INVALID mediaIds)
     */
    ProductMediaReorderResult reorderProductMedia(UUID productId, List<UUID> orderedMediaIds,
                                                  Authentication authentication);
}
