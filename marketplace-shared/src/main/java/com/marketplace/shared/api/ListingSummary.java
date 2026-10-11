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
 *
 * <p>ADR-0011 (D-15 — DSA (EU) 2022/2065 Art. 26(1)(a), the EUR-Lex
 * text): "Providers of online platforms that present advertisements on
 * their online interfaces shall ensure that, for each specific
 * advertisement presented to each individual recipient, the recipients
 * of the service are able to identify, in a clear, concise and
 * unambiguous manner and in real time" that the information is an
 * advertisement. The platform's advertisement is the paid promotion the
 * ordering already speaks (a live per-impression/per-click campaign with
 * remaining budget, or an admin featured window — the
 * {@code ProviderListingRepository} ORDER BY's own first tier). The row
 * now carries that truth: {@code promoted} is the SAME boolean the
 * ordering's first tier evaluated when the page was read (resolved in
 * ONE batch per page beside the names/stats batch — never per-row), so
 * the recipient identifies the promoted rows in real time on the same
 * response the reordering presents. The flag rides the caches' bounded
 * staleness exactly like the ordering itself (the namespace bump keeps
 * the pair consistent: the flag never disagrees with the order of the
 * page it rides).
 */
public record ListingSummary(
        UUID id,
        String title,
        String category,
        BigDecimal price,
        String currency,
        String providerName,
        Double providerRating,
        long providerReviewCount,
        boolean promoted
) implements Serializable {
}
