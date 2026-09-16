package com.marketplace.catalog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L40 (realestate systems plan §5 — view analytics): the full chain on the
 * REAL modules — the public detail read (MockMvc → the real controller →
 * the real counter → the real Redis SETNX → the real locked +1 → the real
 * Envers revisions) and the provider's windowed totals (the real
 * {@code ListingViewsStatsAdapter} join). Only the principal resolution is
 * the mocked seam (the L20/L25 integration precedent).
 *
 * <p>Boot pattern follows {@code CacheRedisTtlIntegrationTest}: isolated
 * {@code postgis/postgis:18-3.6-alpine} (Flyway enabled, ddl-auto=none)
 * PLUS an isolated {@code redis:8-alpine} via the official
 * {@code RedisContainerConnectionDetailsFactory} — the view counter is the
 * codebase's first direct Redis consumer, so its integration guard needs
 * the real TTL semantics, not the cache abstraction's.
 *
 * <p>The plan's acceptance criteria, in order:
 * <ol>
 *   <li>three consecutive reads from one visitor in one day ⇒ counter 1
 *       (a second visitor ⇒ 2) — the dedup contract;</li>
 *   <li>the provider windows return correct numbers ACROSS date
 *       boundaries (seeded rows at today-0/1/7/8/30/31/90/91 — each
 *       window claims exactly its own days);</li>
 *   <li>the anonymous visitor leaves no permanent identifier: the dedup
 *       key is gone once the TTL lapses (this test overrides the window
 *       to 200ms — the property exists precisely for this criterion) and
 *       the only stored trace is the anonymous daily count.</li>
 * </ol>
 *
 * <p>Plus two house guards beyond the plan text: the Envers mirror is
 * LIVE (each ADD/MOD leaves a revision — the D-R8 "تجميعي يُدقَّق كالكيانات"
 * requirement, which is exactly why the increment is NOT a native
 * upsert), and soft-deleted buckets are excluded from the aggregate (the
 * Hibernate 7 {@code @SoftDelete} predicate on the JPQL join).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
class ListingViewsIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers extension; connection details via RedisContainerConnectionDetailsFactory
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private com.marketplace.provider.ProviderListingViewsService viewsService;

    // The provider surface is guarded by the REAL @authHelper.ownsProvider;
    // only the principal resolution is the mocked seam (the L20 precedent).
    @MockitoBean
    private com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    private static final UUID OWNER_USER_ID = UUID.randomUUID();
    private static final UUID FOREIGN_USER_ID = UUID.randomUUID();
    private static final UUID LISTING_ID = UUID.randomUUID();
    private static final UUID OTHER_LISTING_ID = UUID.randomUUID();

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @BeforeEach
    void seedTheKnownDataset() {
        clearDedupMarkers();
        jdbcTemplate.update("DELETE FROM listing_views_daily_aud");
        jdbcTemplate.update("DELETE FROM listing_views_daily");
        jdbcTemplate.update("DELETE FROM provider_listings WHERE id IN (?, ?)",
                LISTING_ID, OTHER_LISTING_ID);
        jdbcTemplate.update("DELETE FROM provider_profiles WHERE user_id IN (?, ?)",
                OWNER_USER_ID, FOREIGN_USER_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", OWNER_USER_ID, FOREIGN_USER_ID);

        for (UUID userId : List.of(OWNER_USER_ID, FOREIGN_USER_ID)) {
            jdbcTemplate.update(
                    """
                    INSERT INTO users (id, subject, email, display_name, role)
                    VALUES (?, ?, ?, ?, 'PROVIDER')
                    """,
                    userId, "l40-" + userId, "l40-" + userId + "@example.com", "L40 User " + userId);
        }
        jdbcTemplate.update(
                """
                INSERT INTO provider_profiles (id, display_name, bio, status, user_id, created_at, updated_at, version, is_deleted)
                VALUES (?, 'L40 Views Provider', 'bio', 'VERIFIED', ?, now(), now(), 0, false)
                """,
                UUID.randomUUID(), OWNER_USER_ID);

        // Both listings of the owner: the seeded-view listing and the
        // deterministic-order tiebreak partner.
        for (UUID listingId : List.of(LISTING_ID, OTHER_LISTING_ID)) {
            jdbcTemplate.update(
                    """
                    INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status, created_at, updated_at, version, is_deleted)
                    VALUES (?, ?, ?, 'seed', 'home', 1000, 'SAR', 'ACTIVE', now(), now(), 0, false)
                    """,
                    listingId, OWNER_USER_ID,
                    LISTING_ID.equals(listingId) ? "L40 Main Listing" : "L40 Tiebreak Listing");
        }
    }

    @AfterEach
    void cleanUp() {
        clearDedupMarkers();
        jdbcTemplate.update("DELETE FROM listing_views_daily_aud");
        jdbcTemplate.update("DELETE FROM listing_views_daily");
        jdbcTemplate.update("DELETE FROM provider_listings WHERE id IN (?, ?)",
                LISTING_ID, OTHER_LISTING_ID);
        jdbcTemplate.update("DELETE FROM provider_profiles WHERE user_id IN (?, ?)",
                OWNER_USER_ID, FOREIGN_USER_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", OWNER_USER_ID, FOREIGN_USER_ID);
    }

    /**
     * Acceptance criterion 1: three consecutive reads from ONE visitor in
     * one day count 1; a SECOND visitor counts 2 — the dedup contract on
     * the real Redis SETNX. The reads ride the real public endpoint
     * (anonymous — the permitAll surface).
     */
    @Test
    void threeReadsFromOneVisitor_countOne_secondVisitorCountsTwo() throws Exception {
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(get("/api/v1/listings/{id}", LISTING_ID)
                            .with(request -> {
                                request.setRemoteAddr("203.0.113.7");
                                return request;
                            }))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/v1/listings/{id}", LISTING_ID)
                        .with(request -> {
                            request.setRemoteAddr("198.51.100.9");
                            return request;
                        }))
                .andExpect(status().isOk());

        Long counted = jdbcTemplate.queryForObject(
                "SELECT view_count FROM listing_views_daily WHERE listing_id = ? AND view_date = ?",
                Long.class, LISTING_ID, java.sql.Date.valueOf(TODAY));
        assertThat(counted).isEqualTo(2L);

        // exactly ONE bucket row exists — the daily aggregate shape itself
        Integer buckets = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM listing_views_daily WHERE listing_id = ?", Integer.class, LISTING_ID);
        assertThat(buckets).isEqualTo(1);
    }

    /**
     * Acceptance criterion 1's mirror at the provider surface: the
     * provider's own 7-day window carries the deduplicated total (2), not
     * the raw read count (4).
     */
    @Test
    @org.springframework.security.test.context.support.WithMockUser
    void providerWindow_carriesTheDeduplicatedTotal() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(OWNER_USER_ID);

        for (int i = 0; i < 3; i++) {
            readAs("203.0.113.7");
        }
        readAs("198.51.100.9");

        var response = viewsService.getViews(OWNER_USER_ID, new com.marketplace.provider.ListingViewsWindow(7));

        assertThat(response.days()).isEqualTo(7);
        assertThat(response.sinceInclusive()).isEqualTo(TODAY.minusDays(6));
        assertThat(response.listings()).hasSize(1);
        assertThat(response.listings().get(0).listingId()).isEqualTo(LISTING_ID);
        assertThat(response.listings().get(0).views()).isEqualTo(2L);
        assertThat(response.listings().get(0).title()).isEqualTo("L40 Main Listing");
    }

    /**
     * Acceptance criterion 2: windows correct across date boundaries. The
     * seeded rows claim exact dates relative to TODAY (so the test is
     * deterministic every day it runs) — each row sits ON a boundary or
     * exactly one day past it:
     * <pre>
     *   today      : 5 views   — the still-accumulating bucket, in 7/30/90
     *   today-6    : 3 views   — the 7-day window's INCLUSIVE first day
     *   today-7    : 4 views   — one day past 7 (in 30/90)
     *   today-29   : 6 views   — the 30-day window's INCLUSIVE first day
     *   today-30   : 9 views   — one day past 30 (in 90)
     *   today-89   : 2 views   — the 90-day window's INCLUSIVE first day
     *   today-90   : 7 views   — one day past 90 (in NOTHING)
     *   today-2, soft-deleted bucket: 7 views — in NOTHING (the @SoftDelete guard)
     * </pre>
     * Expected sums: 7-day = 5+3; 30-day = 5+3+4+6; 90-day = 5+3+4+6+9+2.
     */
    @Test
    @org.springframework.security.test.context.support.WithMockUser
    void windows_claimExactlyTheirOwnDays_acrossDateBoundaries() {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(OWNER_USER_ID);

        seedBucket(LISTING_ID, TODAY, 5L);
        seedBucket(LISTING_ID, TODAY.minusDays(6), 3L);
        seedBucket(LISTING_ID, TODAY.minusDays(7), 4L);
        seedBucket(LISTING_ID, TODAY.minusDays(29), 6L);
        seedBucket(LISTING_ID, TODAY.minusDays(30), 9L);
        seedBucket(LISTING_ID, TODAY.minusDays(89), 2L);
        seedBucket(LISTING_ID, TODAY.minusDays(90), 7L);
        seedSoftDeletedBucket(LISTING_ID, TODAY.minusDays(2), 7L);

        assertThat(totalFor(7)).isEqualTo(5L + 3L);
        assertThat(totalFor(30)).isEqualTo(5L + 3L + 4L + 6L);
        assertThat(totalFor(90)).isEqualTo(5L + 3L + 4L + 6L + 9L + 2L);
    }

    /**
     * The deterministic order (L32): equal totals break by listing id ASC.
     */
    @Test
    @org.springframework.security.test.context.support.WithMockUser
    void equalTotals_breakByListingIdAscending() {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(OWNER_USER_ID);

        seedBucket(LISTING_ID, TODAY, 5L);
        seedBucket(OTHER_LISTING_ID, TODAY, 5L);
        seedBucket(OTHER_LISTING_ID, TODAY.minusDays(3), 1L);

        var response = viewsService.getViews(OWNER_USER_ID, new com.marketplace.provider.ListingViewsWindow(7));

        assertThat(response.listings()).hasSize(2);
        // OTHER has 6 (5+1) > MAIN's 5 → OTHER first despite the name
        assertThat(response.listings().get(0).listingId()).isEqualTo(OTHER_LISTING_ID);
        assertThat(response.listings().get(0).views()).isEqualTo(6L);
        assertThat(response.listings().get(1).listingId()).isEqualTo(LISTING_ID);
        assertThat(response.listings().get(1).views()).isEqualTo(5L);
    }

    /**
     * Another provider's views never leak into the caller's window — the
     * ownership join rides provider_listings.provider_id (the users.id
     * space, A1).
     */
    @Test
    @org.springframework.security.test.context.support.WithMockUser
    void foreignListings_neverLeakIntoTheCallerWindow() {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(FOREIGN_USER_ID);

        // the OWNER's listing carries views; the FOREIGN caller owns an
        // empty listing space
        seedBucket(LISTING_ID, TODAY, 5L);

        var response = viewsService.getViews(FOREIGN_USER_ID, new com.marketplace.provider.ListingViewsWindow(30));

        assertThat(response.listings()).isEmpty();
    }

    /**
     * The join's OTHER soft-delete side (the repository javadoc's promise):
     * a soft-deleted LISTING carries no analytics — its views are views of
     * a tombstone (the purge flow's own contract: the purged listing is
     * not browsable, not searchable, and not an analytics row either).
     */
    @Test
    @org.springframework.security.test.context.support.WithMockUser
    void softDeletedListing_carriesNoAnalytics() {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(OWNER_USER_ID);

        jdbcTemplate.update("UPDATE provider_listings SET is_deleted = true WHERE id = ?", LISTING_ID);
        seedBucket(LISTING_ID, TODAY, 5L);
        seedBucket(OTHER_LISTING_ID, TODAY, 2L); // the live partner stays

        var response = viewsService.getViews(OWNER_USER_ID, new com.marketplace.provider.ListingViewsWindow(30));

        assertThat(response.listings()).hasSize(1);
        assertThat(response.listings().get(0).listingId()).isEqualTo(OTHER_LISTING_ID);
    }

    /**
     * The house guard for D-R8 ("تجميعي يُدقَّق كالكيانات"): the Envers
     * mirror is LIVE — one ADD revision for the first view and one MOD
     * revision per further +1. This is precisely the measured fact that
     * forced the locked read-modify-write over the plan's literal native
     * upsert (a native statement bypasses the listeners and would leave
     * the mirror empty).
     */
    @Test
    void enversMirror_recordsEveryIncrement() throws Exception {
        readAs("203.0.113.7"); // ADD (bucket born)
        readAs("198.51.100.9"); // MOD (+1)
        readAs("198.51.100.77"); // MOD (+1)

        Integer adds = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM listing_views_daily_aud WHERE revtype = 0", Integer.class);
        Integer mods = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM listing_views_daily_aud WHERE revtype = 1", Integer.class);

        assertThat(adds).isEqualTo(1); // the bucket's birth
        assertThat(mods).isEqualTo(2); // each further +1
    }

    /**
     * The HTTP seam of the provider surface — the full chain through the
     * real resource-server filter chain and the real JSON line: the days
     * whitelist, the window bounds, and the entries in the documented
     * order. The role-carrying JWT (the SavedSearchIntegrationTest
     * pattern — a bare jwt() carries no authorities).
     */
    @Test
    void providerSurface_httpLine_carriesWindowAndEntries() throws Exception {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(OWNER_USER_ID);

        seedBucket(LISTING_ID, TODAY, 5L);
        seedBucket(OTHER_LISTING_ID, TODAY, 2L);

        mockMvc.perform(get("/api/v1/providers/me/listings/views")
                        .param("days", "7")
                        .with(providerJwt("l40-owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.days").value(7))
                .andExpect(jsonPath("$.sinceInclusive").value(TODAY.minusDays(6).toString()))
                .andExpect(jsonPath("$.listings[0].listingId").value(LISTING_ID.toString()))
                .andExpect(jsonPath("$.listings[0].views").value(5))
                .andExpect(jsonPath("$.listings[1].listingId").value(OTHER_LISTING_ID.toString()))
                .andExpect(jsonPath("$.listings[1].views").value(2));
    }

    /**
     * The surface stays authenticated: no new security line was added (the
     * family default {@code anyRequest().authenticated()} owns the path —
     * the same line every other /providers/me/** surface rides).
     */
    @Test
    void providerSurface_anonymous_isA401() throws Exception {
        mockMvc.perform(get("/api/v1/providers/me/listings/views"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------
    // helpers

    /** The role-carrying JWT (the SavedSearchIntegrationTest pattern). */
    private static org.springframework.test.web.servlet.request.RequestPostProcessor providerJwt(
            String subject) {
        return jwt().jwt(j -> j.subject(subject))
                .authorities(new org.springframework.security.core.authority
                        .SimpleGrantedAuthority("ROLE_PROVIDER"));
    }

    private void readAs(String remoteAddr) throws Exception {
        mockMvc.perform(get("/api/v1/listings/{id}", LISTING_ID)
                        .with(request -> {
                            request.setRemoteAddr(remoteAddr);
                            return request;
                        }))
                .andExpect(status().isOk());
    }

    private long totalFor(int days) {
        return viewsService.getViews(OWNER_USER_ID, new com.marketplace.provider.ListingViewsWindow(days))
                .listings().stream().mapToLong(com.marketplace.shared.api.ListingViewStats::views).sum();
    }

    private void seedBucket(UUID listingId, LocalDate viewDate, long views) {
        jdbcTemplate.update(
                """
                INSERT INTO listing_views_daily (id, listing_id, view_date, view_count, is_deleted, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, false, 0, now(), now())
                """,
                UUID.randomUUID(), listingId, java.sql.Date.valueOf(viewDate), views);
    }

    private void seedSoftDeletedBucket(UUID listingId, LocalDate viewDate, long views) {
        jdbcTemplate.update(
                """
                INSERT INTO listing_views_daily (id, listing_id, view_date, view_count, is_deleted, version, created_at, updated_at)
                VALUES (?, ?, ?, ?, true, 0, now(), now())
                """,
                UUID.randomUUID(), listingId, java.sql.Date.valueOf(viewDate), views);
    }

    /** The dedup markers are per-test state (the Redis container is shared by the context). */
    private void clearDedupMarkers() {
        Set<String> keys = redisTemplate.keys(ListingViewCounter.DEDUP_KEY_PREFIX + "*");
        if (keys != null && !keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }
}
