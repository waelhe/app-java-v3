package com.marketplace.catalog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * L40 (realestate systems plan §5 — view analytics): the provider's
 * windowed analytics at the SERVICE level, on the real modules and the
 * real Flyway schema — the {@code ProviderStatsIntegrationTest} pattern
 * verbatim (L25): @WithMockUser + the mocked principal seam + the REAL
 * {@code @authHelper.ownsProvider} guard resolving the REAL profile row,
 * and the REAL {@code ListingViewsStatsAdapter} join over seeded buckets.
 *
 * <p><b>Why this class exists separately from
 * {@code ListingViewsIntegrationTest}</b> (the CI-measured lesson of this
 * PR's first round): that class mixes MockMvc request flows with
 * service-level {@code @WithMockUser} calls — and the MockMvc security
 * chain clears the thread's SecurityContext after each request, so a
 * service call that FOLLOWS MockMvc reads finds no Authentication (the
 * SpEL evaluation fails with
 * {@code AuthenticationCredentialsNotFoundException}). The house pattern
 * was always one-auth-style-per-class: ProviderStatsIntegrationTest
 * (service only) vs ListingCompletenessIntegrationTest (HTTP only). The
 * counting path itself — MockMvc through the real counter, Redis and
 * Envers — lives in ListingViewsIntegrationTest; the window arithmetic
 * over seeded buckets lives HERE.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ListingViewsWindowIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private com.marketplace.provider.ProviderListingViewsService viewsService;

    // The service path is guarded by the REAL @authHelper.ownsProvider;
    // only the principal resolution is the mocked seam (the L20/L25
    // integration precedent).
    @MockitoBean
    private com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    private static final UUID OWNER_USER_ID = UUID.randomUUID();
    private static final UUID FOREIGN_USER_ID = UUID.randomUUID();
    private static final UUID LISTING_ID = UUID.randomUUID();
    private static final UUID OTHER_LISTING_ID = UUID.randomUUID();

    private static final LocalDate TODAY = LocalDate.now(ZoneOffset.UTC);

    @BeforeEach
    void seedTheKnownDataset() {
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
                    userId, "l40w-" + userId, "l40w-" + userId + "@example.com", "L40 Window User " + userId);
        }
        jdbcTemplate.update(
                """
                INSERT INTO provider_profiles (id, display_name, bio, status, user_id, created_at, updated_at, version, is_deleted)
                VALUES (?, 'L40 Window Provider', 'bio', 'VERIFIED', ?, now(), now(), 0, false)
                """,
                UUID.randomUUID(), OWNER_USER_ID);
        jdbcTemplate.update(
                """
                INSERT INTO provider_profiles (id, display_name, bio, status, user_id, created_at, updated_at, version, is_deleted)
                VALUES (?, 'L40 Foreign Provider', 'bio', 'VERIFIED', ?, now(), now(), 0, false)
                """,
                UUID.randomUUID(), FOREIGN_USER_ID);

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
        jdbcTemplate.update("DELETE FROM listing_views_daily_aud");
        jdbcTemplate.update("DELETE FROM listing_views_daily");
        jdbcTemplate.update("DELETE FROM provider_listings WHERE id IN (?, ?)",
                LISTING_ID, OTHER_LISTING_ID);
        jdbcTemplate.update("DELETE FROM provider_profiles WHERE user_id IN (?, ?)",
                OWNER_USER_ID, FOREIGN_USER_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", OWNER_USER_ID, FOREIGN_USER_ID);
    }

    /**
     * The provider window reads the DEDUPLICATED total from the daily
     * bucket: the dedup arithmetic itself (four reads, two visitors ⇒
     * count 2) is proven end-to-end by
     * {@code ListingViewsIntegrationTest.threeReadsFromOneVisitor_countOne_secondVisitorCountsTwo};
     * this guard pins the READ side — the window, the title join and the
     * entry shape over the bucket that arithmetic produced.
     */
    @Test
    @WithMockUser
    void providerWindow_readsTheDeduplicatedTotalFromTheDailyBucket() {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(OWNER_USER_ID);
        seedBucket(LISTING_ID, TODAY, 2L);

        var response = viewsService.getViews(OWNER_USER_ID, new com.marketplace.provider.ListingViewsWindow(7));

        assertThat(response.days()).isEqualTo(7);
        assertThat(response.sinceInclusive()).isEqualTo(TODAY.minusDays(6));
        assertThat(response.listings()).hasSize(1);
        assertThat(response.listings().get(0).listingId()).isEqualTo(LISTING_ID);
        assertThat(response.listings().get(0).views()).isEqualTo(2L);
        assertThat(response.listings().get(0).title()).isEqualTo("L40 Main Listing");
    }

    /**
     * Acceptance criterion 2: windows correct across date boundaries —
     * each seeded row sits ON a boundary or exactly one day past it:
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
    @WithMockUser
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
    @WithMockUser
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
     * space, A1) and the REAL guard resolves the caller's own profile.
     */
    @Test
    @WithMockUser
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
    @WithMockUser
    void softDeletedListing_carriesNoAnalytics() {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(OWNER_USER_ID);

        jdbcTemplate.update("UPDATE provider_listings SET is_deleted = true WHERE id = ?", LISTING_ID);
        seedBucket(LISTING_ID, TODAY, 5L);
        seedBucket(OTHER_LISTING_ID, TODAY, 2L); // the live partner stays

        var response = viewsService.getViews(OWNER_USER_ID, new com.marketplace.provider.ListingViewsWindow(30));

        assertThat(response.listings()).hasSize(1);
        assertThat(response.listings().get(0).listingId()).isEqualTo(OTHER_LISTING_ID);
    }

    // ------------------------------------------------------------------
    // helpers

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
}
