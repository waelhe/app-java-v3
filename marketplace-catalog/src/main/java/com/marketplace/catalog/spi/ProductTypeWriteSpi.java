package com.marketplace.catalog.spi;

import org.springframework.modulith.NamedInterface;

import java.util.List;
import java.util.UUID;

/**
 * P2 (the canonical Saleor plan §5-P2 — the Saleor
 * {@code productTypeCreate}, {@code productTypeUpdate},
 * {@code productTypeDelete} and {@code productTypeBulkDelete} mutation
 * roots, the agent-2 packet): the catalog owner's write seam for the
 * reusable goods-schema reference collection. Authorization lives in
 * the owning use case ({@code ProductTypeWriteService} enforces the
 * platform's ADMIN capability — the server-side mapping of Saleor's
 * staff MANAGE_PRODUCT_TYPES_AND_ATTRIBUTES-class requirement at the
 * use-case boundary, the same RBAC divergence row the category registry
 * write established), never in a resolver and never duplicated at the
 * GraphQL boundary.
 */
@NamedInterface("catalog-spi")
public interface ProductTypeWriteSpi {

    /**
     * Registers one goods schema over the {@code product_types} row.
     * Domain validation refusals answer as payload error entries; the
     * duplicate code answers ALREADY_EXISTS (the registry's own honest
     * precheck over the derived {@code findByCode} lookup).
     *
     * @param command the bounded create command (name and slug)
     * @return the created projection, or the domain error entries
     */
    ProductTypeMutationResult createProductType(ProductTypeCreateCommand command);

    /**
     * Amends one goods schema's display metadata. {@code null} command
     * fields preserve the column (the Saleor omit-to-preserve
     * semantics); the row's stable public code moves only through an
     * explicit slug command (checked against the code's own uniqueness).
     *
     * @param id      the native UUID (decoded by the boundary's codec)
     * @param command the partial update command
     * @return the updated projection, or the domain error entries
     */
    ProductTypeMutationResult updateProductType(UUID id, ProductTypeUpdateCommand command);

    /**
     * Soft-deletes one goods schema — the {@code BaseEntity}
     * {@code @SoftDelete} discipline; the row's last-known projection
     * answers in the payload (Saleor's own delete-payload semantics).
     *
     * @param id the native UUID (decoded by the boundary's codec)
     * @return the last-known projection, or the NOT_FOUND error entry
     */
    ProductTypeMutationResult deleteProductType(UUID id);

    /**
     * Soft-deletes a batch of goods schemas. Unknown-but-well-formed ids
     * are the owner's own honest skip; the count answers only what the
     * registry itself deleted.
     *
     * @param ids the native UUIDs (decoded by the boundary's codec)
     * @return the number of rows the registry itself deleted
     */
    int deleteProductTypes(List<UUID> ids);
}
