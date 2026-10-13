package com.marketplace.catalog;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * B-16 (compliance plan C.8 — the M2 store wave): the storefront's read
 * root over the M1 {@code Product} — a NEW repository interface beside
 * A-17's {@code ProductRepository} (the wave's boundary: ملفات جديدة
 * فقط — the M1 root's own files stay untouched; Spring Data supports
 * multiple repository interfaces over one aggregate root, each proxy
 * owning exactly the methods its surface needs).
 *
 * <p>The three reads are the summary's own aggregate queries —
 * aggregates are not derivable (query-methods-details' honest limit),
 * so they carry {@code @Query} with CLOSED projections as their return
 * types (projections.html — the plan's «الإسقاط المغلق للملخص»). Every
 * one of them rides the M1 index {@code idx_products_provider} (the
 * partial index on {@code provider_id} where not deleted — V116's own
 * "the provider's own roster read" line, which the M2 summary now
 * exercises from the public side).
 */
public interface StorefrontProductRepository extends JpaRepository<Product, UUID> {

    /**
     * The summary's scalar half — one closed projection row per live
     * seller: the product count and the last activity timestamp. An
     * empty Optional is the honest zero-product answer (the service
     * synthesizes the zero summary from it — existence of the USER is
     * not catalog's truth to tell; the identity line owns that).
     */
    @Query("""
            select p.providerId as sellerId,
                   count(p) as productCount,
                   max(p.updatedAt) as lastActivityAt
            from Product p
            where p.providerId = :sellerId
              and p.status = com.marketplace.catalog.ProductStatus.ACTIVE
            group by p.providerId
            """)
    Optional<SellerSummaryView> summarizeSeller(@Param("sellerId") UUID sellerId);

    /**
     * The summary's currency bands — min/max price ranges grouped per
     * ISO-4217 currency (a range is only honest within one currency;
     * a two-currency seller gets two bands, never one merged fiction).
     */
    @Query("""
            select p.currency as currency,
                   count(p) as productCount,
                   min(p.priceMinor) as minPriceMinor,
                   max(p.priceMinor) as maxPriceMinor
            from Product p
            where p.providerId = :sellerId
              and p.status = com.marketplace.catalog.ProductStatus.ACTIVE
            group by p.currency
            """)
    List<SellerCurrencyBand> sellerCurrencyBands(@Param("sellerId") UUID sellerId);

    /**
     * The summary's category lines — the seller's live product count
     * per store-category code (the storefront shelf layout).
     */
    @Query("""
            select p.storeCategoryCode as categoryCode,
                   count(p) as productCount
            from Product p
            where p.providerId = :sellerId
              and p.status = com.marketplace.catalog.ProductStatus.ACTIVE
            group by p.storeCategoryCode
            """)
    List<SellerCategoryCount> sellerCategoryCounts(@Param("sellerId") UUID sellerId);

    /**
     * Stage 6 (ADR-0002): the buyer's product detail read — ACTIVE only.
     * The suspended/archived/deleted product answers the honest 404: the
     * gate rule («المخفي/المعلق يختفي من الأسطح العامة») at the query
     * level, and the purchase path's own gate (the cart's add, the
     * placement's freshness check) re-checks the state at write time.
     */
    @Query("""
            select p from Product p
            where p.id = :productId
              and p.status = com.marketplace.catalog.ProductStatus.ACTIVE
            """)
    Optional<Product> findActiveProduct(@Param("productId") UUID productId);
}
