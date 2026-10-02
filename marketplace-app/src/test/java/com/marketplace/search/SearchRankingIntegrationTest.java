package com.marketplace.search;

import test.config.IntegrationContainers;

import com.marketplace.catalog.ListingRankingBatchExecutor;
import com.marketplace.catalog.ProviderListing;
import com.marketplace.catalog.ProviderListingRepository;
import com.marketplace.shared.api.BadRequestException;
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
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * W3 (yelp-level plan §5 — the discovery & ranking wave): the wave's own
 * acceptance criteria over the REAL modules — the criteria path through
 * the real {@code SearchService} into the catalog's Specification/native
 * flows and the reviews module's real aggregate queries, plus the ranking
 * job's own executor writing the composite column. No mocks between the
 * criteria, the stats and PostgreSQL.
 *
 * <p>Boot pattern follows {@code SearchGuestsFilterIntegrationTest} (the
 * I6 guard): full application context on an isolated PostGIS container
 * via {@code @ServiceConnection}, Flyway enabled, {@code ddl-auto=none}
 * — V91/V92's schema and the reviews CHECKs run against exactly the
 * schema migrations produce.
 *
 * <p>Acceptance criteria (the wave's own row, decomposed):
 * <ol>
 *   <li>«4 نجوم فأعلى» يعمل — the min-stars floor filters to the
 *       providers whose recomputed verified average answers it (a 2-star
 *       provider and an unrated one never match);</li>
 *   <li>the composite ranking prefers the complete, review-rich ACTIVE
 *       listing over the lacking one at equal stars (the job's own
 *       formula — log(count) then completeness then recency — read back
 *       through sort=rating, with the not-yet-ranked NULL row ordering
 *       LAST);</li>
 *   <li>the result rows carry the provider's stars (G20 — the batched
 *       exposed-stats join: rating + count, null for the not-yet-rated
 *       provider);</li>
 *   <li>the floor's type gate: a minRating outside [1, 5] is a 400 at
 *       construction, before any query.</li>
 * </ol>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SearchRankingIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private SearchService searchService;

    @Autowired
    private ProviderListingRepository listingRepository;

    @Autowired
    private ListingRankingBatchExecutor rankingBatchExecutor;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ObjectProvider<CacheManager> cacheManagerProvider;

    // FK parents (V2: provider_listings.provider_id references users(id)) —
    // fixed ids keep the seed idempotent across @BeforeEach invocations.
    private static final UUID STRONG = UUID.fromString("00000000-0000-0000-0000-0000000053a1");
    private static final UUID WEAK = UUID.fromString("00000000-0000-0000-0000-0000000057a2");
    private static final UUID LOW = UUID.fromString("00000000-0000-0000-0000-000000004ca3");
    private static final UUID NONE = UUID.fromString("00000000-0000-0000-0000-000000004ea4");
    private static final UUID REVIEWER_1 = UUID.fromString("00000000-0000-0000-0000-0000000052b1");
    private static final UUID REVIEWER_2 = UUID.fromString("00000000-0000-0000-0000-0000000052b2");
    private static final UUID REVIEWER_3 = UUID.fromString("00000000-0000-0000-0000-0000000052b3");

    private static final String STRONG_TITLE = "Complete Sea Gem";
    private static final String WEAK_TITLE = "Lacking Stub";
    private static final String LOW_TITLE = "Low Star Lodge";
    private static final String NONE_TITLE = "No Star Hut";

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

        jdbc.update("DELETE FROM reviews WHERE provider_id IN (?, ?, ?, ?)",
                STRONG, WEAK, LOW, NONE);
        jdbc.update("DELETE FROM bookings WHERE provider_id IN (?, ?, ?, ?)",
                STRONG, WEAK, LOW, NONE);
        jdbc.update("""
                DELETE FROM provider_listings WHERE provider_id IN (?, ?, ?, ?)
                """, STRONG, WEAK, LOW, NONE);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?, ?, ?, ?, ?)",
                STRONG, WEAK, LOW, NONE, REVIEWER_1, REVIEWER_2, REVIEWER_3);

        for (UUID provider : new UUID[]{STRONG, WEAK, LOW, NONE}) {
            jdbc.update("INSERT INTO users (id, subject, email, display_name, role) "
                            + "VALUES (?, ?, ?, ?, 'PROVIDER')",
                    provider, "w3-" + provider, provider + "@w3.t.com", "Provider " + provider);
        }
        for (UUID reviewer : new UUID[]{REVIEWER_1, REVIEWER_2, REVIEWER_3}) {
            jdbc.update("INSERT INTO users (id, subject, email, display_name, role) "
                            + "VALUES (?, ?, ?, ?, 'CONSUMER')",
                    reviewer, "w3-r-" + reviewer, reviewer + "@w3.t.com", "Reviewer");
        }

        // The listings: every RATED provider's listing carries a description
        // (the completeness quarter's live variable — 25 at this fixture's
        // shape); the unrated provider's carries none (0 — reinforcing its
        // own zero). The equal-stars pair (STRONG/WEAK, both 4.0) is then
        // separated by the composite's log(count) factor: 10 reviews beat 2
        // — the wave's own acceptance, read back through sort=rating.
        listingRepository.save(activeListing(STRONG, STRONG_TITLE, "desc"));
        listingRepository.save(activeListing(WEAK, WEAK_TITLE, "desc"));
        listingRepository.save(activeListing(LOW, LOW_TITLE, "desc"));
        listingRepository.save(activeListing(NONE, NONE_TITLE, null));

        // The verified pairs: STRONG 4.0 over 10, WEAK 4.0 over 2 (the
        // equal-stars pair the composite must separate by volume), LOW
        // 2.0 over 3 (below any 4-star floor), NONE none at all.
        seedVerifiedReviews(STRONG, 10, 4);
        seedVerifiedReviews(WEAK, 2, 4);
        seedVerifiedReviews(LOW, 3, 2);
    }

    /** An ACTIVE listing for the given provider (the entity's own transition). */
    private ProviderListing activeListing(UUID providerId, String title, String description) {
        ProviderListing listing = ProviderListing.create(
                providerId, title, description, "stay", 10_000L, "SAR");
        listing.activate();
        return listing;
    }

    /** FK-honest verified reviews: one COMPLETED booking per review. */
    private void seedVerifiedReviews(UUID providerId, int count, int rating) {
        UUID[] reviewers = {REVIEWER_1, REVIEWER_2, REVIEWER_3};
        UUID listingId = jdbc.queryForObject(
                "SELECT id FROM provider_listings WHERE provider_id = ? LIMIT 1", UUID.class, providerId);
        for (int i = 0; i < count; i++) {
            UUID bookingId = UUID.randomUUID();
            jdbc.update("INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, "
                            + "price_cents, currency, notes) VALUES (?, ?, ?, ?, 'COMPLETED', 100_00, 'SAR', NULL)",
                    bookingId, reviewers[i % 3], providerId, listingId);
            jdbc.update("INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, rating, "
                            + "origin, moderation_status, created_at) "
                            + "VALUES (?, ?, ?, ?, ?, 'BOOKING', 'PUBLISHED', ?)",
                    UUID.randomUUID(), bookingId, reviewers[i % 3], providerId, rating,
                    java.sql.Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(2))));
        }
    }

    private java.util.function.Function<String, ListingSummary> byTitle(Page<ListingSummary> page) {
        return title -> page.getContent().stream()
                .filter(row -> title.equals(row.title()))
                .findFirst().orElseThrow();
    }

    // ---- (1) «4 نجوم فأعلى» يعمل -------------------------------------------

    @Test
    void minRatingFour_filtersToTheProvidersAtOrAboveTheFloor() {
        SearchCriteria criteria = new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, BigDecimal.valueOf(4));

        Page<ListingSummary> page = searchService.search(criteria, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(ListingSummary::title)
                .containsExactlyInAnyOrder(STRONG_TITLE, WEAK_TITLE);
        assertThat(page.getTotalElements()).isEqualTo(2);
    }

    @Test
    void minRating_absent_servesTheLegacySetUnchanged() {
        SearchCriteria criteria = new SearchCriteria(null, null, null, null);

        Page<ListingSummary> page = searchService.search(criteria, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(ListingSummary::title)
                .containsExactlyInAnyOrder(STRONG_TITLE, WEAK_TITLE, LOW_TITLE, NONE_TITLE);
    }

    @Test
    void minRating_outOfBounds_isA400AtConstruction() {
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, BigDecimal.valueOf(6)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("minRating must be within [1, 5]");
        assertThatThrownBy(() -> new SearchCriteria(null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, BigDecimal.ZERO))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("minRating must be within [1, 5]");
    }

    // ---- (2) the composite ordering (G16 reads the G18 column) -------------

    @Test
    void ratingSort_prefersTheCompleteReviewRichListing_atEqualStars_andNullsLast() {
        // The job's own seam: one full pass writes the composite column for
        // every clean-ACTIVE listing (the cron orchestrator is the wrapper;
        // the executor is the transaction boundary under test).
        UUID cursor = rankingBatchExecutor.rankOneBatch(null, 100);
        assertThat(cursor).isNull(); // the whole roster fit one batch

        // A listing born AFTER the pass keeps the honest NULL — the
        // not-yet-ranked state must order LAST, never first.
        listingRepository.save(activeListing(NONE, "Fresh Null", null));
        cacheManagerProvider.ifAvailable(cm -> cm.getCacheNames().forEach(name -> {
            var cache = cm.getCache(name);
            if (cache != null) {
                cache.clear();
            }
        }));

        Page<ListingSummary> page = searchService.search(
                new SearchCriteria(null, null, null, null),
                PageRequest.of(0, 20, Sort.by(Sort.Direction.DESC, "rating")));

        assertThat(page.getContent()).extracting(ListingSummary::title)
                .containsExactly(STRONG_TITLE, WEAK_TITLE, LOW_TITLE, NONE_TITLE, "Fresh Null");
        // The stored scores themselves: positive for the rated, zero for the
        // unrated, NULL for the post-pass listing.
        Double strongScore = jdbc.queryForObject(
                "SELECT ranking_score FROM provider_listings WHERE title = ?", Double.class, STRONG_TITLE);
        Double freshScore = jdbc.queryForObject(
                "SELECT ranking_score FROM provider_listings WHERE title = ?", Double.class, "Fresh Null");
        assertThat(strongScore).isPositive();
        assertThat(freshScore).isNull();
    }

    // ---- (3) the stars ride the result rows (G20) ---------------------------

    @Test
    void resultRowsCarryTheProvidersVerifiedPair() {
        Page<ListingSummary> page = searchService.search(
                new SearchCriteria(null, null, null, null), PageRequest.of(0, 20));

        var row = byTitle(page);
        assertThat(row.apply(STRONG_TITLE).providerRating()).isEqualTo(4.0);
        assertThat(row.apply(STRONG_TITLE).providerReviewCount()).isEqualTo(10);
        assertThat(row.apply(WEAK_TITLE).providerRating()).isEqualTo(4.0);
        assertThat(row.apply(WEAK_TITLE).providerReviewCount()).isEqualTo(2);
        assertThat(row.apply(LOW_TITLE).providerRating()).isEqualTo(2.0);
        assertThat(row.apply(LOW_TITLE).providerReviewCount()).isEqualTo(3);
        // The honest not-yet-rated row — null rating, zero count.
        assertThat(row.apply(NONE_TITLE).providerRating()).isNull();
        assertThat(row.apply(NONE_TITLE).providerReviewCount()).isZero();
    }

    // ---- (4) the floor composes with the text flow ---------------------------

    @Test
    void textSearch_withFloor_returnsOnlyTheFloorProvidersMatchingText() {
        SearchCriteria criteria = new SearchCriteria("Sea", null, null, null,
                null, null, null, null, null, null, null, null, null, null, null,
                null, BigDecimal.valueOf(4));

        Page<ListingSummary> page = searchService.search(criteria, PageRequest.of(0, 20));

        assertThat(page.getContent()).extracting(ListingSummary::title)
                .containsExactly(STRONG_TITLE);
    }
}
