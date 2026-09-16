package com.marketplace.catalog;

import com.marketplace.shared.api.ListingSummary;
import com.marketplace.shared.api.SearchCriteria;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * L37 (realestate systems plan §5 — the featured boost): the four
 * acceptance criteria as living guards, on the REAL migration schema and
 * the REAL ordering machinery (the boost specification through the
 * official two-specification {@code findAll} overload, the native
 * queries' baked ORDER BY flag, and the injected Clock).
 *
 * <ul>
 *   <li><b>Criterion 1</b> — "لوحتان بنفس الفرز، إحداهما مظللة ⇒
 *       المظللة أولًا؛ انتهاء التظليل يعيد الترتيب الطبيعي (اختبار بحاقن
 *       ساعة)": the {@link MutableClock} advances the window past its end
 *       and the natural order returns — through BOTH the cached
 *       Specification surface and the uncached native criteria path.</li>
 *   <li><b>Criterion 2</b> — "التظليل لا يغيّر الفلترة أبدًا (العدّ الكلّي
 *       لا يكذب)": a boosted listing outside the filter never appears, and
 *       every total stays the pre-shading number. The count query rides the
 *       countSpec (predicates verbatim) — the structural half of the
 *       two-specification design.</li>
 *   <li><b>Criterion 3</b> — "مظللة منتهية في صفحة 2 لا تقفز": after the
 *       window passes, the listing sits at its natural position and STAYS
 *       there across reads (the CASE flag is query-time evaluated — no
 *       cleanup job exists).</li>
 *   <li><b>Criterion 4</b> — "كل الترقية عبر نقطة إدارية موثقة Envers":
 *       the shading lands through the ADMIN service point and the revision
 *       trail + the {@code _aud} mirror carry it.</li>
 * </ul>
 *
 * <p>Fixed-prefix UUIDs (the L40 CodeRabbit round-2 lesson): the ids
 * 00000000-0000-4000-8000-…01/…02/…03 fix the id-ASC total order
 * deterministically — the tiebreak contract of the L32 rule is asserted,
 * not hoped for.
 *
 * <p>Boot pattern: {@code CatalogSearchFullTextIntegrationTest} verbatim —
 * full application context on an ISOLATED {@code postgis/postgis:18-3.6-alpine}
 * container via {@code @ServiceConnection}, Flyway enabled,
 * {@code ddl-auto=none} (V59's column + mirror exist only on the migration
 * schema). The FK parent user row is seeded before the listings (V2 DDL).
 *
 * <p>Cache honesty: the cached surfaces reflect the boost state at fill
 * time — a shading evicts through the AFTER_COMMIT relay (the production
 * mechanism, exercised by these tests), and a pure window expiry is
 * bounded by the 1h TTL in production; the test compresses that bound to
 * zero (an explicit clear) exactly where the clock advances a CACHED
 * surface, and documents it inline. The native criteria path is uncached
 * — the clock semantics are asserted there with no cache noise at all.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@WithMockUser(roles = "ADMIN")
class BoostOrderingIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches CatalogSearchFullTextIntegrationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    /**
     * The injected-clock seam the plan's criterion 1 demands: the
     * production {@code ClockConfig} bean stays in the context, and this
     * @Primary test bean takes precedence for every injection point (the
     * standard Boot override mechanism) — one winning Clock per context,
     * and it is mutable.
     */
    @TestConfiguration
    static class MutableClockConfig {
        static final MutableClock CLOCK = new MutableClock(Instant.parse("2026-06-01T12:00:00Z"));

        @Bean
        @Primary
        Clock mutableClock() {
            return CLOCK;
        }
    }

    /** The test's own clock — advances, never restarts. */
    static final class MutableClock extends Clock {
        private volatile Instant current;

        MutableClock(Instant start) {
            this.current = start;
        }

        void advanceTo(Instant next) {
            this.current = next;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return current;
        }
    }

    /** The fixed id-ASC order: 01 < 02 < 03 (PostgreSQL memcmp). */
    private static final UUID ID_01 = UUID.fromString("00000000-0000-4000-8000-000000000001");
    private static final UUID ID_02 = UUID.fromString("00000000-0000-4000-8000-000000000002");
    private static final UUID ID_03 = UUID.fromString("00000000-0000-4000-8000-000000000003");

    /** FK parent (V2: provider_listings.provider_id references users(id)). */
    private static final UUID PROVIDER_USER_ID = UUID.fromString("00000000-0000-4000-8000-0000000000aa");

    /** The test's clock anchor. */
    private static final Instant T0 = Instant.parse("2026-06-01T12:00:00Z");

    @Autowired
    private CatalogService catalogService;

    @Autowired
    private ProviderListingRepository listingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectProvider<CacheManager> cacheManagerProvider;

    @BeforeEach
    void resetWorld() {
        // Cache entries from a previous test method would serve stale pages
        // against the re-seeded rows — clear when present (the FTS test's
        // own discipline).
        cacheManagerProvider.ifAvailable(cm ->
                cm.getCacheNames().forEach(name -> {
                    var cache = cm.getCache(name);
                    if (cache != null) {
                        cache.clear();
                    }
                }));
        // FIXED ids + the entity's @SoftDelete do not compose across test
        // methods (a soft-deleted row shadows the re-inserted same id), so
        // the reset is a HARD delete of both the table and its Envers
        // mirror — the criterion-4 test then counts revisions from zero.
        // Raw SQL is the house pattern for schema-level test setup.
        jdbcTemplate.update("DELETE FROM provider_listings_aud");
        jdbcTemplate.update("DELETE FROM provider_listings");
        jdbcTemplate.update(
                """
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """,
                PROVIDER_USER_ID, "boost-it@example.com", "boost-it@example.com",
                "Boost IT Provider");
        MutableClockConfig.CLOCK.advanceTo(T0);
    }

    private ProviderListing active(UUID id, String title, long priceCents, String category) {
        ProviderListing listing = new ProviderListing(
                id, PROVIDER_USER_ID, title, "description of " + title, category, priceCents);
        listing.activate();
        return listing;
    }

    private void seedThreeHomeListings() {
        listingRepository.saveAll(List.of(
                active(ID_01, "Alpha Home", 100_00L, "home"),
                active(ID_02, "Beta Home", 100_00L, "home"),
                active(ID_03, "Gamma Home", 100_00L, "home")));
    }

    private List<UUID> activeIds() {
        return catalogService.listActive(Pageable.ofSize(10))
                .map(ListingSummary::id).getContent();
    }

    private void clearCaches() {
        cacheManagerProvider.ifAvailable(cm ->
                cm.getCacheNames().forEach(name -> {
                    var cache = cm.getCache(name);
                    if (cache != null) {
                        cache.clear();
                    }
                }));
    }

    // ---- Criterion 1: boosted first within the same base sort ----------
    // ---- and expiry restores the natural order (injected clock) --------

    @Test
    void boostedListingRanksFirst_andExpiryRestoresTheNaturalOrder_onTheCachedSurface() {
        seedThreeHomeListings();
        // The unsorted total order (L32): id ASC — the conversion of the
        // derived surface to the boost specification made it structural.
        assertThat(activeIds()).containsExactly(ID_01, ID_02, ID_03);

        // Shade the MIDDLE listing — future window at the injected clock.
        catalogService.setListingPromotion(ID_02, T0.plus(Duration.ofHours(1)));

        // "لوحتان بنفس الفرز، إحداهما مظللة ⇒ المظللة أولًا" — the shading
        // evicted the cached pages through the AFTER_COMMIT relay, so this
        // read is fresh by the production mechanism itself.
        assertThat(activeIds()).containsExactly(ID_02, ID_01, ID_03);

        // Advance the clock past the window. The boost state is QUERY-TIME
        // evaluated, so the natural order returns on the next read — the
        // 1h TTL bound compressed to zero here (the documented staleness
        // edge; the uncached path below proves the same flip with no cache
        // involvement at all).
        MutableClockConfig.CLOCK.advanceTo(T0.plus(Duration.ofHours(2)));
        clearCaches();
        assertThat(activeIds()).containsExactly(ID_01, ID_02, ID_03);
    }

    @Test
    void boostedListingRanksFirst_andExpiryRestoresTheNaturalOrder_onTheNativeCriteriaPath() {
        seedThreeHomeListings();
        catalogService.setListingPromotion(ID_02, T0.plus(Duration.ofHours(1)));
        Pageable pageable = Pageable.ofSize(10);
        SearchCriteria criteria = new SearchCriteria(null, "home", null, null);

        // The native path is uncached: the clock's advance flips the
        // ordering directly — the pure criterion-1 semantics.
        Page<ListingSummary> boosted = catalogService.searchByCriteria(criteria, pageable);
        assertThat(boosted.map(ListingSummary::id).getContent())
                .containsExactly(ID_02, ID_01, ID_03);

        MutableClockConfig.CLOCK.advanceTo(T0.plus(Duration.ofHours(2)));
        Page<ListingSummary> natural = catalogService.searchByCriteria(criteria, pageable);
        assertThat(natural.map(ListingSummary::id).getContent())
                .containsExactly(ID_01, ID_02, ID_03);
    }

    /** Criterion 1's composition half: the boost rides the requested sort too. */
    @Test
    void boostOutranksTheRequestedSort_withinTheFacetedSpecificationPath() {
        // Prices chosen so the requested sort and the boost DISAGREE:
        // 01 = 300, 02 = 100, 03 = 200 — price ASC is [02, 03, 01].
        listingRepository.saveAll(List.of(
                active(ID_01, "Alpha Home", 300_00L, "home"),
                active(ID_02, "Beta Home", 100_00L, "home"),
                active(ID_03, "Gamma Home", 200_00L, "home")));
        catalogService.setListingPromotion(ID_03, T0.plus(Duration.ofDays(7)));

        // "المعزّز أولًا داخل نفس الفرز الأساسي": the boosted 03 first,
        // THEN the base sort among the unboosted ([02, 01] by price ASC),
        // id ASC as the tiebreak. The count (3) also proves the count
        // specification ran without the ordering — the measured PostgreSQL
        // aggregate/ORDER-BY rejection cannot happen on this path.
        var page = catalogService.searchByCriteriaFaceted(
                new SearchCriteria(null, "home", null, null),
                PageRequest.of(0, 10, Sort.by(Sort.Direction.ASC, "priceCents")));
        assertThat(page.map(ListingSummary::id).getContent())
                .containsExactly(ID_03, ID_02, ID_01);
        assertThat(page.getTotalElements()).isEqualTo(3L);
    }

    /** Q1's resolved rule: the boost outranks FTS relevance. */
    @Test
    void boostOutranksRelevance_onTheFullTextPath() {
        // Both listings match "garden"; the ranking between them is the
        // pre-L37 contract and stays untouched — the assertion pins ONLY
        // the boost's position (first) and the match set (filtering
        // unchanged), rank-order-agnostic by design.
        listingRepository.saveAll(List.of(
                active(ID_01, "Garden View", 100_00L, "home"),
                active(ID_02, "Cozy House with a garden", 100_00L, "home")));
        catalogService.setListingPromotion(ID_02, T0.plus(Duration.ofDays(7)));

        Page<ListingSummary> page = catalogService.searchFullText("garden", Pageable.ofSize(10));

        assertThat(page.getContent()).hasSize(2);
        assertThat(page.getContent().getFirst().id()).isEqualTo(ID_02);
    }

    // ---- Criterion 2: the boost never changes filtering -----------------

    @Test
    void boostNeverWidensTheFilter_theTotalsDoNotLie() {
        listingRepository.saveAll(List.of(
                active(ID_01, "Alpha Home", 100_00L, "home"),
                active(ID_02, "Beta Home", 100_00L, "home"),
                active(ID_03, "Gamma Car", 100_00L, "cars")));
        // The shaded listing lives OUTSIDE the home category.
        catalogService.setListingPromotion(ID_03, T0.plus(Duration.ofDays(7)));

        var homes = catalogService.listByCategory("home", Pageable.ofSize(10));
        assertThat(homes.map(ListingSummary::id).getContent())
                .containsExactly(ID_01, ID_02);
        assertThat(homes.getTotalElements()).isEqualTo(2L);

        var cars = catalogService.listByCategory("cars", Pageable.ofSize(10));
        assertThat(cars.map(ListingSummary::id).getContent())
                .containsExactly(ID_03);
        assertThat(cars.getTotalElements()).isEqualTo(1L);

        // The price filter too: a boosted listing beyond the ceiling never
        // appears (the native WHERE is untouched — the boost only reorders
        // what already matched).
        var cheap = catalogService.searchByCriteria(
                new SearchCriteria(null, "home", null, java.math.BigDecimal.valueOf(200)),
                Pageable.ofSize(10));
        assertThat(cheap.map(ListingSummary::id).getContent())
                .containsExactly(ID_01, ID_02);
        assertThat(cheap.getTotalElements()).isEqualTo(2L);
    }

    // ---- Criterion 3: an expired boost on page 2 does not jump ----------

    @Test
    void expiredBoostOnPageTwo_staysAtItsNaturalPosition_acrossReads() {
        // Five listings; the LAST id is shaded. Page size 2.
        listingRepository.saveAll(List.of(
                active(ID_01, "One", 100_00L, "home"),
                active(ID_02, "Two", 100_00L, "home"),
                active(ID_03, "Three", 100_00L, "home"),
                active(UUID.fromString("00000000-0000-4000-8000-000000000004"), "Four", 100_00L, "home"),
                active(UUID.fromString("00000000-0000-4000-8000-000000000005"), "Five", 100_00L, "home")));
        UUID id04 = UUID.fromString("00000000-0000-4000-8000-000000000004");
        UUID id05 = UUID.fromString("00000000-0000-4000-8000-000000000005");
        catalogService.setListingPromotion(id05, T0.plus(Duration.ofHours(1)));
        Pageable page0 = PageRequest.of(0, 2);
        Pageable page1 = PageRequest.of(1, 2);
        Pageable page2 = PageRequest.of(2, 2);

        // While the window is live: the boosted listing leads page 0.
        assertThat(catalogService.listActive(page0).map(ListingSummary::id).getContent())
                .containsExactly(id05, ID_01);

        // The window passes; the cached surface's staleness bound is
        // compressed to zero (the production bound is the 1h TTL).
        MutableClockConfig.CLOCK.advanceTo(T0.plus(Duration.ofHours(2)));
        clearCaches();

        // The expired listing sits at its NATURAL position — page 2, last.
        assertThat(catalogService.listActive(page0).map(ListingSummary::id).getContent())
                .containsExactly(ID_01, ID_02);
        assertThat(catalogService.listActive(page1).map(ListingSummary::id).getContent())
                .containsExactly(ID_03, id04);
        assertThat(catalogService.listActive(page2).map(ListingSummary::id).getContent())
                .containsExactly(id05);

        // Time moves on — the position is STABLE (no further jump, no
        // cleanup job: the CASE flag is evaluated per read).
        MutableClockConfig.CLOCK.advanceTo(T0.plus(Duration.ofDays(8)));
        clearCaches();
        assertThat(catalogService.listActive(page2).map(ListingSummary::id).getContent())
                .containsExactly(id05);
        assertThat(catalogService.listActive(Pageable.ofSize(10)).getTotalElements())
                .isEqualTo(5L);
    }

    // ---- Criterion 4: the shading is Envers-documented -------------------

    @Test
    void shadingIsDocumentedByTheEnversRevisionTrail_andTheMirrorColumn() {
        seedThreeHomeListings();
        Instant until = T0.plus(Duration.ofDays(7));
        catalogService.setListingPromotion(ID_02, until);

        // The revision trail the admin revisions surface reads: the ADD at
        // seed time, then the UPDATE carrying the window.
        var revisions = listingRepository.findRevisions(ID_02);
        assertThat(revisions.getContent()).hasSize(2);
        var latest = revisions.getContent().getLast();
        assertThat(latest.getEntity().getPromotedUntil()).isEqualTo(until);

        // The mirror column is live (the V59 _aud ALTER): the UPDATE row
        // carries the window — the "تجميعي يُدقَّق كالكيانات" discipline.
        // OffsetDateTime binds TZ-explicitly (pgjdbc renders the offset —
        // a plain Timestamp would ride the session timezone).
        var mirrored = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM provider_listings_aud WHERE id = ? AND promoted_until = ?",
                Long.class, ID_02, until.atOffset(java.time.ZoneOffset.UTC));
        assertThat(mirrored).isEqualTo(1L);
    }

    // ---- The provider page surface: the same rule, provider-scoped -------

    @Test
    void theProviderPageReordersUnderTheSameRule() {
        seedThreeHomeListings();
        catalogService.setListingPromotion(ID_03, T0.plus(Duration.ofDays(7)));

        var page = catalogService.listByProvider(PROVIDER_USER_ID, Pageable.ofSize(10));
        assertThat(page.map(ProviderListing::getId).getContent())
                .containsExactly(ID_03, ID_01, ID_02);
        assertThat(page.getTotalElements()).isEqualTo(3L);
    }

    // ---- The window-restricted native path carries the same ordering ----

    @Test
    void theWindowRestrictedNativePathCarriesTheBoostFlag() {
        seedThreeHomeListings();
        catalogService.setListingPromotion(ID_03, T0.plus(Duration.ofDays(7)));

        var page = catalogService.searchByCriteriaRestricted(
                new SearchCriteria(null, "home", null, null),
                java.util.Set.of(PROVIDER_USER_ID),
                Pageable.ofSize(10));
        assertThat(page.map(ListingSummary::id).getContent())
                .containsExactly(ID_03, ID_01, ID_02);
        assertThat(page.getTotalElements()).isEqualTo(3L);
    }
}
