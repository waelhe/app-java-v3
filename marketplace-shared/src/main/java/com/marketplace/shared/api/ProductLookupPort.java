package com.marketplace.shared.api;

import java.util.UUID;

/**
 * A-17 (compliance plan C.7 — the M1 store root): resolves one store
 * {@code Product} as a media attachment target — the third target of the
 * generalized media line, the {@code PostLookupPort} twin (L48's measured
 * seam shape verbatim).
 *
 * <p>The port lives in the shared contracts so the media module never
 * depends on the catalog module (the V32/V48/V52/V54/V60/V61/V64
 * discipline): existence and ownership are resolved at write time through
 * this seam, never a JPA relation across module boundaries. The catalog
 * module owns the implementation ({@code ProductLookupAdapter} in its spi
 * package — the {@code PostLookupAdapter} pattern).
 *
 * <p>{@code providerId} is the product's owning provider — a plain user id
 * in the identity seams' space. The media line's write path gates on the
 * PROVIDER role plus this ownership (the listing flow's exact gate shape).
 */
public interface ProductLookupPort {

    /**
     * Resolves one product as a media attachment target.
     *
     * @param productId the catalog module's product id
     * @return the product's owner carrier
     * @throws com.marketplace.shared.api.ResourceNotFoundException if the
     *         product does not exist or is soft-deleted
     */
    ProductInfo getProductInfo(UUID productId);

    /**
     * The media line's ownership carrier — the minimal facts the upload
     * gate reads (the {@code PostLookupPort.PostInfo} twin).
     *
     * @param productId the product's own id
     * @param providerId the product's owning provider (the upload gate's
     *        ownership check)
     */
    record ProductInfo(UUID productId, UUID providerId) {
    }
}
