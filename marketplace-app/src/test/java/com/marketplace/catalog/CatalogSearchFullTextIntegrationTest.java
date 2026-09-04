package com.marketplace.catalog;

import com.marketplace.shared.api.ListingSummary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.data.domain.Page;
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
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Full-text search semantics on real PostgreSQL — guards the official
 * {@code websearch_to_tsquery} native query in
 * {@link ProviderListingRepository#searchFullText} end-to-end:
 *
 * <ul>
 *   <li>Multi-word input is AND semantics (PostgreSQL reference:
 *       {@code websearch_to_tsquery('english', 'The fat rats')} →
 *       {@code 'fat' & 'rat'}) — preserves the previous behavior for plain
 *       queries.</li>
 *   <li>Arbitrary special characters never raise a tsquery syntax error
 *       (reference example: {@code '""" )( dummy \ query <->'} parses to
 *       {@code 'dummi' & 'queri'}) — the previous {@code to_tsquery} form
 *       raised SQL exceptions (HTTP 500) on quotes/parens/dashes. The defect
 *       class is closed here, not just the single case.</li>
 *   <li>Quoted phrases, OR and -exclusion — officially supported web-search
 *       operators.</li>
 * </ul>
 *
 * <p>Boot pattern follows {@code QuartzJdbcJobStoreConfigTest} /
 * {@code EventPublicationArchiveIntegrationTest}: full application context on
 * an ISOLATED {@code postgres:18-alpine} container via {@code @ServiceConnection},
 * with Flyway enabled and {@code ddl-auto=none} — so the native query runs
 * against exactly the schema migrations produce (V1..V30, including V29's
 * matview drop and V30's Envers revision sequence) and V30 (Envers revision sequence). Isolation is deliberate: this test seeds rows, and the shared
 * CI service database is asserted-empty by other integration tests
 * ({@code CatalogModuleIntegrationTest.listActiveSummary_returnsEmptyPage} —
 * the eff5966 CI lesson: module-slice tests share the service database and
 * its cached context).
 *
 * <p>Real-schema FK (the 0ba779d CI round): {@code provider_listings.provider_id
 * references users(id)} (V2 DDL) — the entity model has no relation, so a
 * create-drop slice schema masks the constraint; on the migration-produced
 * schema the seed MUST insert the parent user row first.
 *
 * <p>Real-schema Envers revision source (the 4fd46a7 CI round): V24 hand-wrote
 * {@code revinfo.rev} as an identity column, but Hibernate Envers' generator
 * uses the sequence {@code revinfo_seq} (which Hibernate's create-drop schema
 * creates automatically) — on the migration-produced schema the first
 * @Audited write failed with 'relation "revinfo_seq" does not exist'. Fixed
 * by V30 (sequence + continuity setval). Running on the Flyway schema rather
 * than the entity schema is exactly what exposes such drift — the §7 lesson,
 * now validated three times.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CatalogSearchFullTextIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private ProviderListingRepository listingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectProvider<CacheManager> cacheManagerProvider;

    /** FK parent (V2: provider_listings.provider_id references users(id)). */
    private static final UUID PROVIDER_USER_ID = UUID.randomUUID();

    private UUID gardenViewId;

    @BeforeEach
    void seedListings() {
        // Cache entries from a previous test method would serve stale pages
        // against the re-seeded rows (new UUIDs) — clear when present.
        cacheManagerProvider.ifAvailable(cm ->
                cm.getCacheNames().forEach(name -> {
                    var cache = cm.getCache(name);
                    if (cache != null) {
                        cache.clear();
                    }
                }));

        listingRepository.deleteAll();

        // The migration schema (unlike the entity-only create-drop schema)
        // enforces provider_listings.provider_id -> users(id): insert the
        // parent row first (idempotent across @BeforeEach invocations).
        jdbcTemplate.update(
                """
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """,
                PROVIDER_USER_ID, "search-it@example.com", "search-it@example.com",
                "Search IT Provider");

        ProviderListing gardenView = active("Garden View", "Apartment overlooking a garden");
        ProviderListing cityView = active("City View Loft", "Modern loft, skyline panorama");
        ProviderListing house = active("Cozy House", "A house with a large garden and a nice view");
        ProviderListing keyboard = active("Mechanical Keyboard", "Clicky switches, tenkeyless");
        listingRepository.saveAll(List.of(gardenView, cityView, house, keyboard));

        gardenViewId = gardenView.getId();
    }

    @Test
    void multiWordQueryKeepsAndSemantics() {
        Page<ListingSummary> page = catalogService.searchFullText("garden view", Pageable.ofSize(10));
        // "garden view" AND-matches: Garden View (title) and Cozy House (description
        // has both words) — City View Loft lacks "garden", keyboard lacks both.
        assertThat(page.map(ListingSummary::title)).containsExactlyInAnyOrder(
                "Garden View", "Cozy House");
    }

    @Test
    void specialCharactersNeverThrow() {
        // The former defect: to_tsquery syntax error -> SQL exception -> HTTP 500.
        // websearch_to_tsquery parses any input leniently (official example:
        // '""" )( dummy \ query <->' -> 'dummi' & 'queri').
        assertThatCode(() ->
                catalogService.searchFullText("\"unbalanced (quote -minus", Pageable.ofSize(10)))
                .as("raw user input with quotes/parens/dashes must never raise a tsquery syntax error")
                .doesNotThrowAnyException();
    }

    @Test
    void quotedPhraseMatchesAdjacentWordsOnly() {
        Page<ListingSummary> page = catalogService.searchFullText("\"garden view\"", Pageable.ofSize(10));
        // 'garden' <-> 'view' adjacency: Garden View title matches; Cozy House
        // has "garden" and "view" separated by other words — not a phrase match.
        assertThat(page.map(ListingSummary::id)).containsExactly(gardenViewId);
    }

    @Test
    void exclusionOperatorFiltersOutTerm() {
        Page<ListingSummary> page = catalogService.searchFullText("view -city", Pageable.ofSize(10));
        // 'view' AND NOT 'city': Garden View + Cozy House match, City View Loft excluded.
        assertThat(page.map(ListingSummary::title)).containsExactlyInAnyOrder("Garden View", "Cozy House");
    }

    @Test
    void orOperatorMatchesEitherPhrase() {
        Page<ListingSummary> page = catalogService.searchFullText("keyboard or loft", Pageable.ofSize(10));
        assertThat(page.map(ListingSummary::title)).containsExactlyInAnyOrder(
                "Mechanical Keyboard", "City View Loft");
    }

    @Test
    void singleTermQueryMatchesAllOccurrences() {
        // 'garden' alone: plain single-term query — no operator involved.
        Page<ListingSummary> page = catalogService.searchFullText("garden", Pageable.ofSize(10));
        assertThat(page.map(ListingSummary::title)).containsExactlyInAnyOrder("Garden View", "Cozy House");
    }

    private ProviderListing active(String title, String description) {
        ProviderListing listing = ProviderListing.create(
                PROVIDER_USER_ID, title, description, "home", 100_00L);
        listing.activate();
        return listing;
    }
}
