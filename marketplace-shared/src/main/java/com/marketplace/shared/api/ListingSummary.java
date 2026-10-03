package com.marketplace.shared.api;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Serializable for the Redis cache value path: the catalog's four
 * {@code @Cacheable} sites return {@code PagedResponse<ListingSummary>}
 * (catalog-active, catalog-by-category, catalog-search, search-results)
 * and Spring Boot's default Redis value serializer is
 * {@code JdkSerializationRedisSerializer} — a non-Serializable record
 * component holder makes every cold-cache PUT throw
 * {@code NotSerializableException} → HTTP 500 (live-proven defect;
 * guard: {@code ColdCacheRedisSerializationIntegrationTest}).
 * {@code PagedResponse} itself implements Serializable for the same
 * contract (the pre-neutral type was Spring's PageImpl, Serializable by
 * spring-data-commons). For record classes the Object Serialization
 * Specification declares serialVersionUID as 0L unless explicitly declared
 * and waives the match requirement — the canonical-constructor form is
 * the serialization contract.
 *
 * <p>W3 (yelp-level plan §5 — the discovery & ranking wave, G20): the row
 * gains the provider's stars — {@code providerRating} +
 * {@code providerReviewCount}, the RECOMPUTED verified pair resolved in
 * ONE batch per page through the exposed stats port (the names' own
 * toSummaryPage discipline; a page costs a bounded number of queries,
 * never per-row). {@code providerRating} is {@code null} for a provider
 * with no verified reviews — the honest not-yet-rated row (never a
 * fabricated zero); the count is {@code 0} in that case. The cached form
 * rides the existing bounded-staleness discipline (the provider-name
 * twin: review writes do not evict search pages; the 1h TTL bounds the
 * drift) — the cache-name suffix bumps with this component change (the
 * D-R6 rule pinned by {@code ListingSummaryCacheContractFilesTest}).
 */
public record ListingSummary(
        UUID id,
        String title,
        String category,
        BigDecimal price,
        String currency,
        String providerName,
        Double providerRating,
        long providerReviewCount
) implements Serializable {
}
