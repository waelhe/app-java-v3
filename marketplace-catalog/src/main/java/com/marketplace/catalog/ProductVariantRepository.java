package com.marketplace.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * ADR-0012 (the merchandise model's variant depth): the purchasable
 * unit's repository. The soft-delete filter rides {@code BaseEntity}'s
 * {@code @SoftDelete} — a deleted variant is not a roster member (the
 * {@code ProductRepository} stance verbatim).
 *
 * <p><b>The reservation arithmetic is NOT here yet — deliberately.</b>
 * The {@code reserve/release/commit} conditional updates land with the
 * cart-integration slice that owns their caller (the ADR's decision 4:
 * the orders-side consumption is its own PR with its own journey proof);
 * this repository carries exactly what the seller's write/read surface
 * needs, nothing speculative.
 */
public interface ProductVariantRepository extends JpaRepository<ProductVariant, UUID> {

    /**
     * The SKU namespace probe — the seller's own space only (the V219
     * {@code uq_product_variants_provider_sku} index's read side; the
     * index itself is the race backstop, this read is the friendly
     * pre-check that answers the house 409 with the collision named).
     */
    Optional<ProductVariant> findByProviderIdAndSku(UUID providerId, String sku);

    /** The product's live roster (the owner's list surface — the V219 partial index's read). */
    List<ProductVariant> findByProductIdOrderBySkuAsc(UUID productId);
}
