package com.marketplace.search;

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
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I6 (internal free plan §6, roadmap D1) — the guest-capacity search filter
 * over the REAL modules: the criteria path through the real
 * {@code SearchService} into the catalog native queries — no mocks between
 * the criteria and PostgreSQL.
 *
 * <p>Boot pattern follows {@code SearchWindowFilterIntegrationTest} (the
 * L27 guard): full application context on an isolated
 * {@code postgres:18-alpine} container via {@code @ServiceConnection},
 * Flyway enabled, {@code ddl-auto=none} — the capacity predicate
 * ({@code :guests IS NULL OR (max_guests IS NOT NULL AND max_guests >= :guests)})
 * and V44's column/constraint run against exactly the schema migrations
 * produce. Seeding through {@code listingRepository.saveAll} also proves
 * the Envers audit mirror writes (V44 added {@code max_guests} to
 * {@code provider_listings_aud} — the V33 lesson: a base column without
 * its {@code _aud} twin breaks audit INSERTs).
 *
 * <p>Acceptance criteria (the L27 protocol, adapted):
 * <ol>
 *   <li>the capacity predicate facts — a listing declaring capacity N
 *       appears for {@code guests <= N}; one declaring less does not; one
 *       with UNDECLARED capacity never matches any guests criterion;
 *       the exact-equality boundary (capacity 3, guests 3) appears — all
 *       in one test;</li>
 *   <li>without the guests criterion the results are the legacy set
 *       unchanged (the NULL-capacity listing included — byte-identical
 *       legacy behavior);</li>
 *   <li>the pagination count applies the same restriction (no deceptive
 *       pages).</li>
 * </ol>
 * Input validation (criterion 0 — non-positive guests is a 400 before any
 * query) is pinned by {@code SearchCriteriaTest}; the write-side gate
 * (Bean Validation + the entity floor) by {@code CatalogServiceTest} and
 * the controller WebMvc slice.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SearchGuestsFilterIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

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
    private static final UUID USER_A = UUID.fromString("00000000-0000-0000-0000-0000000001a1");
    private static final UUID USER_B = UUID.fromString("00000000-0000-0000-0000-0000000002b2");
    private static final UUID USER_C = UUID.fromString("00000000-0000-0000-0000-0000000003c3");
    private static final UUID USER_D = UUID.fromString("00000000-0000-0000-0000-0000000004d4");

    private static final String A = "Alpha Suite";    // max_guests = 2
    private static final String B = "Bravo Flat";     // max_guests = 5
    private static final String C = "Charlie Cottage"; // max_guests = NULL (undeclared)
    private static final String D = "Delta House";    // max_guests = 3 (the exact-equality boundary)

    private static final long PRICE_CENTS = 10_000L;

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
        jdbcTemplate.update("DELETE FROM provider_time_off");
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
                    userId, "i6-" + userId + "@example.com",
                    "i6-" + userId + "@example.com", "I6 Provider " + userId);
        }

        // The full creation form (I6) — the entity path itself is under test
        // (the @Positive floor + the V44 column round-trip + the Envers
        // audit INSERT on save).
        listingRepository.saveAll(List.of(
                activeWithCapacity(USER_A, A, 2),
                activeWithCapacity(USER_B, B, 5),
                activeWithCapacity(USER_C, C, null),
                activeWithCapacity(USER_D, D, 3)));
    }

    @Test
    void guestsFilter_appliesTheCapacityPredicate_allFactsInOneTest() {
        // guests = 2: capacity 2 (exact match), 3 and 5 qualify; undeclared
        // never matches.
        Page<ListingSummary> two = searchService.search(
                new SearchCriteria(null, null, null, null, null, null, 2),
                Pageable.ofSize(10));
        assertThat(two.map(ListingSummary::title))
                .as("guests=2: declared capacity >= 2 appears; undeclared never matches")
                .containsExactlyInAnyOrder(A, B, D);

        // guests = 3: the exact-equality boundary (D, capacity 3) and B
        // qualify; A (capacity 2) drops out.
        Page<ListingSummary> three = searchService.search(
                new SearchCriteria(null, null, null, null, null, null, 3),
                Pageable.ofSize(10));
        assertThat(three.map(ListingSummary::title))
                .as("guests=3: the exact-equality boundary appears")
                .containsExactlyInAnyOrder(B, D);

        // guests = 6: over every declared capacity — an honest empty page.
        Page<ListingSummary> six = searchService.search(
                new SearchCriteria(null, null, null, null, null, null, 6),
                Pageable.ofSize(10));
        assertThat(six.isEmpty()).as("guests=6: nobody declares that much — total 0").isTrue();
        assertThat(six.getTotalElements()).isZero();
    }

    @Test
    void noGuestsCriterion_returnsTheFullLegacySet_includingUndeclared() {
        // Acceptance 2 (backward compatibility): without the guests
        // criterion the dispatch is the legacy criteria query — the
        // undeclared-capacity listing is served exactly like before.
        Page<ListingSummary> page = searchService.search(
                new SearchCriteria(null, null, null, null),
                Pageable.ofSize(10));

        assertThat(page.getTotalElements()).isEqualTo(4);
        assertThat(page.map(ListingSummary::title))
                .containsExactlyInAnyOrder(A, B, C, D);
    }

    @Test
    void guestsPagination_countAppliesTheSameRestriction() {
        // Acceptance 3: page size 2 over the three qualifying listings —
        // the count query carries the same capacity restriction (total 3,
        // not 4), and the pages stay disjoint and complete.
        Page<ListingSummary> firstPage = searchService.search(
                new SearchCriteria(null, null, null, null, null, null, 2),
                PageRequest.of(0, 2));

        assertThat(firstPage.getTotalElements()).as("total reflects the restriction").isEqualTo(3);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent()).hasSize(2);

        Page<ListingSummary> secondPage = searchService.search(
                new SearchCriteria(null, null, null, null, null, null, 2),
                PageRequest.of(1, 2));

        assertThat(secondPage.getContent()).hasSize(1);
        var union = new java.util.LinkedHashSet<String>();
        firstPage.getContent().forEach(s -> union.add(s.title()));
        secondPage.getContent().forEach(s -> union.add(s.title()));
        assertThat(union).containsExactlyInAnyOrder(A, B, D);
    }

    @Test
    void guestsCombineWithPrice_theSameQueryComposes() {
        // The criteria predicates are optional predicates of ONE query —
        // guests composes with price exactly like category does.
        Page<ListingSummary> page = searchService.search(
                new SearchCriteria(null, null,
                        java.math.BigDecimal.valueOf(PRICE_CENTS - 1, 2), null, null, null, 2),
                Pageable.ofSize(10));

        // priceCents - 1 cent excludes every seeded listing (all priced
        // exactly PRICE_CENTS) — an honest empty page, not a bypass of the
        // guests predicate.
        assertThat(page.getTotalElements()).isZero();
    }

    /** An ACTIVE listing priced at {@link #PRICE_CENTS} with the given capacity. */
    private static ProviderListing activeWithCapacity(UUID providerId, String title, Integer maxGuests) {
        ProviderListing listing = ProviderListing.create(providerId, title,
                "I6 seed listing " + title, "stay", PRICE_CENTS, null, maxGuests);
        listing.activate();
        return listing;
    }
}
