package com.marketplace.search;

import com.marketplace.availability.AvailabilitySlotRepository;
import com.marketplace.shared.api.AvailabilityLookupPort;
import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.SearchCriteria;
import com.marketplace.catalog.ProviderListing;
import com.marketplace.catalog.ProviderListingRepository;
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

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L27 (feature-expansion roadmap §5) — the stay-window search filter over
 * the REAL modules: the search service resolves the availability whitelist
 * through the real {@code AvailabilityLookupAdapter} (the shared-api
 * {@link AvailabilityLookupPort}) and runs the restricted catalog queries —
 * no mocks between the criteria and PostgreSQL.
 *
 * <p>Boot pattern follows {@code CatalogSearchFullTextIntegrationTest}:
 * full application context on an ISOLATED {@code postgres:18-alpine}
 * container via {@code @ServiceConnection}, Flyway enabled,
 * {@code ddl-auto=none} — the restricted native queries (with their
 * {@code provider_id IN (...)}) and the bulk availability JPQL predicate run
 * against exactly the schema migrations produce.
 *
 * <p>Acceptance criteria (§5-L27), all on one known dataset:
 * <ol>
 *   <li>the availability predicate facts — a provider with a free slot
 *       covering the window appears; the booked one and the time-off
 *       conflicted one do not; the adjacent-acceptance boundary (a time-off
 *       ending exactly at {@code checkIn} is not a conflict) and the
 *       exclusive-end boundaries (a free slot ending exactly at
 *       {@code checkIn}, or starting exactly at {@code checkOut}, does not
 *       qualify) are all asserted in one test;</li>
 *   <li>without date criteria the results are the legacy set unchanged;</li>
 *   <li>the pagination count applies the same restriction (no deceptive
 *       pages);</li>
 *   <li>the full-text branch is restricted too.</li>
 * </ol>
 * Input validation (criterion 0) is pinned by {@code SearchCriteriaTest}
 * and the controller WebMvc slice.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SearchWindowFilterIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @Autowired
    private SearchService searchService;

    @Autowired
    private AvailabilityLookupPort availabilityLookupPort;

    @Autowired
    private AvailabilitySlotRepository slotRepository;

    @Autowired
    private ProviderListingRepository listingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectProvider<CacheManager> cacheManagerProvider;

    /** The stay window under test: [CHECK_IN, CHECK_OUT) — exclusive end. */
    private static final Instant CHECK_IN = Instant.parse("2026-10-05T10:00:00Z");
    private static final Instant CHECK_OUT = Instant.parse("2026-10-08T10:00:00Z");

    // FK parents (V2: provider_listings.provider_id references users(id)) —
    // fixed ids keep the seed idempotent across @BeforeEach invocations.
    private static final UUID USER_A = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID USER_B = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
    private static final UUID USER_C = UUID.fromString("00000000-0000-0000-0000-0000000000c3");
    private static final UUID USER_D = UUID.fromString("00000000-0000-0000-0000-0000000000d4");
    private static final UUID USER_E = UUID.fromString("00000000-0000-0000-0000-0000000000e5");
    private static final UUID USER_F = UUID.fromString("00000000-0000-0000-0000-0000000000f6");
    private static final UUID USER_G = UUID.fromString("00000000-0000-0000-0000-000000000007");

    private static final String A = "Alpha Suite";      // free slot covering the window -> appears
    private static final String B = "Booked Flat";      // covering slot, booked             -> absent
    private static final String C = "Cliff Cottage";    // covering slot + overlapping vacation -> absent
    private static final String D = "Dawn House";       // covering slot + time-off ending exactly at checkIn -> appears
    private static final String E = "Edge Villa";       // free slot ending exactly at checkIn (only)    -> absent
    private static final String F = "Far Cabin";        // no slots at all                   -> absent
    private static final String G = "Gate Loft";        // free slot starting exactly at checkOut (only) -> absent

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

        slotRepository.deleteAll();
        jdbcTemplate.update("DELETE FROM provider_time_off");
        listingRepository.deleteAll();

        for (UUID userId : List.of(USER_A, USER_B, USER_C, USER_D, USER_E, USER_F, USER_G)) {
            jdbcTemplate.update(
                    """
                    INSERT INTO users (id, subject, email, display_name, role)
                    VALUES (?, ?, ?, ?, 'PROVIDER')
                    ON CONFLICT (id) DO NOTHING
                    """,
                    userId, "l27-" + userId + "@example.com",
                    "l27-" + userId + "@example.com", "L27 Provider " + userId);
        }

        listingRepository.saveAll(List.of(
                active(USER_A, A), active(USER_B, B), active(USER_C, C),
                active(USER_D, D), active(USER_E, E), active(USER_F, F),
                active(USER_G, G)));

        // The covering free slot: strictly overlapping [CHECK_IN, CHECK_OUT).
        slot(USER_A, CHECK_IN.minusSeconds(3600), CHECK_OUT.plusSeconds(3600), false);
        // The booked twin — booked = false is part of the predicate.
        slot(USER_B, CHECK_IN.minusSeconds(3600), CHECK_OUT.plusSeconds(3600), true);
        // Covering free slot, but a time-off strictly inside the window.
        slot(USER_C, CHECK_IN.minusSeconds(3600), CHECK_OUT.plusSeconds(3600), false);
        timeOff(USER_C, CHECK_IN.plusSeconds(3600), CHECK_OUT.minusSeconds(3600));
        // Covering free slot + a time-off ending EXACTLY at checkIn:
        // t.ends_at > checkIn is false -> not a conflict (adjacent acceptance).
        slot(USER_D, CHECK_IN.minusSeconds(3600), CHECK_OUT.plusSeconds(3600), false);
        timeOff(USER_D, CHECK_IN.minusSeconds(2 * 3600), CHECK_IN);
        // A free slot ending EXACTLY at checkIn and nothing else:
        // s.ends_at > checkIn is false -> does not qualify (strict overlap).
        slot(USER_E, CHECK_IN.minusSeconds(2 * 3600), CHECK_IN, false);
        // (USER_F: no slots at all.)
        // A free slot starting EXACTLY at checkOut and nothing else:
        // s.starts_at < checkOut is false -> does not qualify.
        slot(USER_G, CHECK_OUT, CHECK_OUT.plusSeconds(2 * 3600), false);
    }

    @Test
    void windowFilter_appliesTheAvailabilityPredicate_allFactsInOneTest() {
        // The predicate itself, at the port seam: A (covering free slot) and
        // D (adjacent time-off tolerated) qualify; B/C/E/F/G do not.
        assertThat(availabilityLookupPort.findAvailableProviderIds(CHECK_IN, CHECK_OUT))
                .as("the bulk isAvailable predicate — strict overlap, booked=false, no time-off conflict")
                .containsExactlyInAnyOrder(USER_A, USER_D);

        Page<ListingSummary> page = searchService.search(
                new SearchCriteria(null, null, null, null, CHECK_IN, CHECK_OUT),
                Pageable.ofSize(10));

        assertThat(page.map(ListingSummary::title))
                .as("acceptance 1: the covering free slot appears; the booked, the time-off "
                        + "conflicted, the boundary-adjacent-only and the slot-less providers do not")
                .containsExactlyInAnyOrder(A, D);
    }

    @Test
    void noWindow_returnsTheFullLegacySet() {
        // Acceptance 2 (backward compatibility): without date criteria the
        // dispatch is the pre-L27 listActive — every ACTIVE listing of the
        // same dataset, availability irrelevant.
        Page<ListingSummary> page = searchService.search(
                new SearchCriteria(null, null, null, null),
                Pageable.ofSize(10));

        assertThat(page.getTotalElements()).isEqualTo(7);
        assertThat(page.map(ListingSummary::title))
                .containsExactlyInAnyOrder(A, B, C, D, E, F, G);
    }

    @Test
    void windowPagination_countAppliesTheSameRestriction() {
        // Acceptance 3: page size 1 over the two qualifying listings — the
        // count query carries the same provider restriction (total 2, not 7),
        // and the deterministic ORDER BY id keeps both pages disjoint and
        // complete (no repeated or skipped rows).
        Page<ListingSummary> firstPage = searchService.search(
                new SearchCriteria(null, null, null, null, CHECK_IN, CHECK_OUT),
                PageRequest.of(0, 1));

        assertThat(firstPage.getTotalElements()).as("total reflects the restriction").isEqualTo(2);
        assertThat(firstPage.getTotalPages()).isEqualTo(2);
        assertThat(firstPage.getContent()).hasSize(1);

        Page<ListingSummary> secondPage = searchService.search(
                new SearchCriteria(null, null, null, null, CHECK_IN, CHECK_OUT),
                PageRequest.of(1, 1));

        assertThat(secondPage.getContent()).hasSize(1);
        assertThat(secondPage.getNumber()).isEqualTo(1);

        Set<String> union = firstPage.getContent().stream().map(ListingSummary::title)
                .collect(Collectors.toSet());
        union.addAll(secondPage.getContent().stream().map(ListingSummary::title)
                .collect(Collectors.toSet()));
        assertThat(union).containsExactlyInAnyOrder(A, D);
    }

    @Test
    void fullTextWithWindow_restrictsTheRankedBranch() {
        // The query matches A and B lexically; the window keeps only A (B's
        // covering slot is booked) — the FTS branch is restricted, not bypassed.
        Page<ListingSummary> page = searchService.search(
                new SearchCriteria("suite or flat", null, null, null, CHECK_IN, CHECK_OUT),
                Pageable.ofSize(10));

        assertThat(page.map(ListingSummary::title)).containsExactly(A);
    }

    private ProviderListing active(UUID providerId, String title) {
        ProviderListing listing = ProviderListing.create(
                providerId, title, "L27 seed listing " + title, "home", 100_00L);
        listing.activate();
        return listing;
    }

    private void slot(UUID providerId, Instant startsAt, Instant endsAt, boolean booked) {
        jdbcTemplate.update(
                """
                INSERT INTO availability_slots (id, provider_id, starts_at, ends_at, booked, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, now(), now())
                """,
                UUID.randomUUID(), providerId, Timestamp.from(startsAt), Timestamp.from(endsAt), booked);
    }

    private void timeOff(UUID providerId, Instant startsAt, Instant endsAt) {
        jdbcTemplate.update(
                """
                INSERT INTO provider_time_off (id, provider_id, starts_at, ends_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, now(), now())
                """,
                UUID.randomUUID(), providerId, Timestamp.from(startsAt), Timestamp.from(endsAt));
    }
}
