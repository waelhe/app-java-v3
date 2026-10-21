package com.marketplace.catalog.spi;

import org.springframework.modulith.NamedInterface;
import org.springframework.security.core.Authentication;

import java.util.List;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productCreate}, {@code productUpdate}, {@code productDelete}
 * and {@code productBulkDelete} mutation roots, the agent-2 packet):
 * the catalog owner's write seam for the store's product rows.
 * Authorization lives in the owning use case
 * ({@code ProductWriteService} enforces the platform's PROVIDER
 * capability — the two-sided marketplace's own ownership law: the
 * owning provider IS the caller, the row's providerId resolves from
 * the authentication, and a stranger's row answers the safe absence
 * — the same boundary {@code ProductService} established), never in a
 * resolver and never duplicated at the GraphQL boundary.
 */
@NamedInterface("catalog-spi")
public interface ProductWriteSpi {

    /**
     * Registers one product row. The store-category dictionary
     * membership and the optional goods-schema reference are checked
     * BEFORE the write; domain validation refusals answer as payload
     * error entries.
     *
     * @param command         the bounded create command
     * @param authentication  the caller — the owning provider
     * @return the created projection, or the domain error entries
     */
    ProductMutationResult createProduct(ProductCreateCommand command, Authentication authentication);

    /**
     * Amends one product's display title, description and price.
     * {@code null} command fields preserve the column (the Saleor
     * omit-to-preserve semantics); an ARCHIVED row is terminal and
     * answers the INVALID domain error. A stranger's row answers the
     * safe absence (NOT_FOUND).
     *
     * @param id             the native UUID (decoded by the boundary's codec)
     * @param command        the partial update command
     * @param authentication the caller — the owning provider
     * @return the updated projection, or the domain error entries
     */
    ProductMutationResult updateProduct(UUID id, ProductUpdateCommand command, Authentication authentication);

    /**
     * Soft-deletes one product row — the {@code BaseEntity}
     * {@code @SoftDelete} discipline; the row's last-known projection
     * answers in the payload (Saleor's own delete-payload semantics).
     *
     * @param id             the native UUID (decoded by the boundary's codec)
     * @param authentication the caller — the owning provider
     * @return the last-known projection, or the NOT_FOUND error entry
     */
    ProductMutationResult deleteProduct(UUID id, Authentication authentication);

    /**
     * Soft-deletes a batch of product rows. Unknown or stranger ids are
     * the owner's own honest skip; the count answers only what the
     * caller owns and the registry itself deleted.
     *
     * @param ids            the native UUIDs (decoded by the boundary's codec)
     * @param authentication the caller — the owning provider
     * @return the number of the caller's rows the registry deleted
     */
    int deleteProducts(List<UUID> ids, Authentication authentication);
}
