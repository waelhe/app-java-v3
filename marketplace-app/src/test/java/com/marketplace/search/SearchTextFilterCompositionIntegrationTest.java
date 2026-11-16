package com.marketplace.search;

import test.config.IntegrationContainers;

import com.marketplace.catalog.ProviderListing;
import com.marketplace.catalog.ProviderListingRepository;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R6 (comprehensive-review-ar fix plan §4, Wave 5 — the composed
 * text+filter search) over the REAL modules: the criteria path through
 * the real {@code SearchService} into the catalog native queries — no
 * mocks between the criteria and PostgreSQL.
 *
 * <p>Boot pattern follows {@code SearchGuestsFilterIntegrationTest} (the
 * I6 guard): full application context on an isolated
 * {@code postgis/postgis:18-3.6-alpine} container via
 * {@code @ServiceConnection}, Flyway enabled, {@code ddl-auto=none} —
 * the composed predicate blocks (text GIN + category / price bounds /
 * guests) run against exactly the schema migrations produce.
 *
 * <p><b>The defect this closes (the review's R6 matrix):</b> a request
 * carrying {@code q + category + maxPrice + guests} TOGETHER used to
 * answer the UNFILTERED text match set — the dispatch's text branch
 * passed the query text alone and dropped every riding filter. The
 * acceptance criteria:
 * <ol>
 *   <li>the composed query answers the INTERSECTION — a listing that
 *       matches the text but violates any riding filter is excluded;</li>
 *   <li>the pagination count applies the same composed restriction (no
 *       deceptive pages — the count query carries the same predicate
 *       blocks);</li>
 *   <li>the pure-text form (no filters) keeps the legacy behavior
 *       byte-identically (the whole seeded text-match set);</li>
 *   <li>the typo-tolerance fallback composes too — when lexical FTS finds
 *       no match, the pg_trgm fallback respects the riding filters
 *       (a fallback that dropped them would answer the unfiltered
 *       similarity set).</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SearchTextFilterCompositionIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private SearchService searchService;

    @Autowired
    private ProviderListingRepository listingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectProvider<CacheManager> cacheManagerProvider;

    // FK parents (V2: provider_listings.provider_id references users(id)) —
    // fixed ids keep the seed idempotent across @BeforeEach invocations.
    private static final UUID USER_A = UUID.fromString("00000000-0000-0000-0000-0000000006a1");
    private static final UUID USER_B = UUID.fromString("00000000-0000-0000-0000-0000000006b2");
    private static final UUID USER_C = UUID.fromString("00000000-0000-0000-0000-0000000006c3");
    private static final UUID USER_D = UUID.fromString("00000000-0000-0000-0000-0000000006d4");

    // Every listing title carries the shared text token "garden" — the
    // text predicate alone matches ALL four; each row then violates
    // exactly ONE dimension of the composed filters.
    private static final String A = "Garden Alpha Suite";   // category home, cheap, capacity 4 — survives EVERYTHING
    private static final String B = "Garden Bravo Flat";    // category STAY (category filter drops it)
    private static final String C = "Garden Charlie Cottage"; // category home, EXPENSIVE (maxPrice drops it)
    private static final String D = "Garden Delta House";   // category home, cheap, capacity 1 (guests drop it)

    private static final long PRICE_CENTS = 10_000L;
    private static final long EXPENSIVE_CENTS = 100_000L;

    @BeforeEach
    void seedTheKnownDataset() {
        // Cache entries from a previous test method would serve stale pages
        // against the re-seeded rows — clear when present (the house pattern).
        cacheManagerProvider.ifAvailable(cm ->
                cm.getCacheNames().forEach(name -> {
                    var cache = cm.getCache(name);
                    if (cache != null) {
                        cache.clear();
                    }
                }));

        listingRepository.deleteAll();
        jdbcTemplate.update("""
                DELETE FROM provider_listings WHERE provider_id IN (?, ?, ?, ?)
                """, USER_A, USER_B, USER_C, USER_D);

        for (UUID userId : List.of(USER_A, USER_B, USER_C, USER_D)) {
            jdbcTemplate.update(
                    """
                    INSERT INTO users (id, subject, email, display_name, role)
                    VALUES (?, ?, ?, ?, 'PROVIDER')
                    ON CONFLICT (id) DO NOTHING
                    """,
                    userId, "r6-" + userId + "@example.com",
                    "r6-" + userId + "@example.com", "R6 Provider " + userId);
        }

        // The full creation form — the entity path itself is under test
        // (the V44 column round-trip + the Envers audit INSERT on save).
        listingRepository.saveAll(List.of(
                composed(USER_A, A, "home", PRICE_CENTS, 4),
                composed(USER_B, B, "stay", PRICE_CENTS, 4),
                composed(USER_C, C, "home", EXPENSIVE_CENTS, 4),
                composed(USER_D, D, "home", PRICE_CENTS, 1)));
    }

    @Test
    void theReviewMatrix_qCategoryMaxPriceGuests_composesInOneQuery() {
        // The review's R6 matrix, verbatim: q + category + maxPrice +
        // guests TOGETHER. Before the wave, this answered all four
        // (the unfiltered text match set); after it, exactly the one row
        // that satisfies EVERY dimension.
        Page<ListingSummary> page = searchService.search(
                new SearchCriteria("garden", "home", null, BigDecimal.valueOf(PRICE_CENTS, 2), null, null, 4),
                Pageable.ofSize(10));

        assertThat(page.map(ListingSummary::title))
                .as("the composed query answers the INTERSECTION: B drops on category, "
                        + "C on maxPrice, D on guests — only A survives every dimension")
                .containsExactly(A);
    }

    @Test
    void theComposedCountAppliesTheSameRestriction_noDeceptivePages() {
        // Page size 1 over the same composed query: the count query
        // carries the same predicate blocks — total 1 (not 4), one page.
        Page<ListingSummary> first = searchService.search(
                new SearchCriteria("garden", "home", null, BigDecimal.valueOf(PRICE_CENTS, 2), null, null, 4),
                PageRequest.of(0, 1));

        assertThat(first.getTotalElements())
                .as("the count must describe the filtered set, never the raw text match set")
                .isEqualTo(1);
        assertThat(first.getTotalPages()).isEqualTo(1);
        assertThat(first.getContent()).hasSize(1);

        // An out-of-range page over the same filtered reality stays
        // honestly empty (the fallback must not replace it — the same
        // PR #256 contract, now on the composed form).
        Page<ListingSummary> past = searchService.search(
                new SearchCriteria("garden", "home", null, BigDecimal.valueOf(PRICE_CENTS, 2), null, null, 4),
                PageRequest.of(1, 1));
        assertThat(past.isEmpty()).isTrue();
        assertThat(past.getTotalElements()).isEqualTo(1);
    }

    @Test
    void pureTextForm_keepsTheLegacyBehavior_byteIdentically() {
        // The text alone (no filters): the whole seeded match set — the
        // optional predicate blocks deactivate on NULL parameters, the
        // legacy result is unchanged.
        Page<ListingSummary> page = searchService.search(
                new SearchCriteria("garden", null, null, null),
                Pageable.ofSize(10));

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.map(ListingSummary::title))
                .containsExactlyInAnyOrder(A, B, C, D);
    }

    @Test
    void theTypoToleranceFallback_composesWithTheRidingFilters() {
        // "gardn" — a one-edit typo: lexical FTS finds no stem match, so
        // the pg_trgm fallback runs. The fallback must respect the riding
        // filters: with the composed criteria (category home + maxPrice +
        // guests) it answers the SAME single row as the exact-spelling
        // composed query — never the unfiltered similarity set.
        Page<ListingSummary> exact = searchService.search(
                new SearchCriteria("garden", "home", null, BigDecimal.valueOf(PRICE_CENTS, 2), null, null, 4),
                Pageable.ofSize(10));
        Page<ListingSummary> typo = searchService.search(
                new SearchCriteria("gardn", "home", null, BigDecimal.valueOf(PRICE_CENTS, 2), null, null, 4),
                Pageable.ofSize(10));

        assertThat(exact.getTotalElements()).isEqualTo(1);
        assertThat(typo.map(ListingSummary::title))
                .as("the fallback carries the SAME optional predicates — the typo-tolerated "
                        + "answer is the filtered one")
                .containsExactly(A);
    }

    /** An ACTIVE listing with the full creation form (category/price/capacity). */
    private static ProviderListing composed(UUID providerId, String title, String category,
                                            long priceCents, Integer maxGuests) {
        ProviderListing listing = ProviderListing.create(providerId, title,
                "R6 seed listing " + title, category, priceCents, null, maxGuests);
        listing.activate();
        return listing;
    }
}
