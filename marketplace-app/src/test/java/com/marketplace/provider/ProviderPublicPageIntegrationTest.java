package com.marketplace.provider;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * L36 (realestate systems plan §5 — agent/office pages): the public page
 * through the REAL modules and a REAL Flyway schema (V56 applied by the
 * boot) — the acceptance criteria 1 and 2.
 *
 * <p>Criterion 1 — the VERIFIED broker's page shows his persona
 * (actor type / agency name / license), his ACTIVE listings paginated and
 * his rating (the fresh aggregate over his forward reviews).
 *
 * <p>Criterion 2 — the SUSPENDED broker's page hides his listings (the
 * layer's VERIFIED gate; measured: no hiding mechanism pre-existed — the
 * plan's "existing behavior" claim is corrected in this layer's truth
 * batch).
 *
 * <p><b>Id spaces (measured, CI-enforced):</b> ALL three provider_id
 * columns carry {@code users.id} — {@code provider_listings.provider_id}
 * and {@code bookings.provider_id} (A1 — the FKs say so) AND
 * {@code reviews.provider_id} (the V6 FK {@code references users(id)}
 * rejects any other space — CI measured it rejecting a profile id). The
 * production write path writes the user id there ({@code ReviewsService.create}
 * carries {@code bookingInfo.providerId()} = the booking's provider_id =
 * the owner's user id), and the FK-honest house test
 * {@code ReviewsAggregateDirectionIntegrationTest} seeds and aggregates by
 * the user id. The seeded reviews therefore carry the broker's USER id.
 *
 * <p><b>A measured finding OUTSIDE this layer's scope (declared, not
 * patched — scope discipline):</b> the L21 stored-average landing
 * ({@code ProviderService.refreshRatingAverage} →
 * {@code findByIdForUpdate(review.providerId)}) resolves
 * {@code provider_profiles} by the review's provider_id — which is a
 * users.id under the enforced schema — so the profile-row lookup misses
 * and the landing silently skips for FK-honest rows. The seeded-by-profile
 * world of {@code ReviewsTwoWayIntegrationTest} (mocked BookingInfo,
 * Hibernate-generated schema — no V6 FK) is where its forward-path
 * "profiles.id-space deviation" note comes from. This PR declares the
 * finding in its truth batch; its fix (mapping the user id to the profile
 * row, e.g. resolving through {@code findByUserId} — or re-anchoring the
 * aggregate) belongs to the L21/reviews seam in its own surgical cycle,
 * not inside L36.
 *
 * <p>Boot pattern follows {@code ProviderStatsIntegrationTest}: isolated
 * {@code postgis/postgis:18-3.6-alpine} container via
 * {@code @ServiceConnection}, Flyway enabled, {@code ddl-auto=none}.
 * {@code @MockitoBean CurrentUserProvider} is the only mocked seam — the
 * public page itself needs no principal (it is a permitAll surface); the
 * bean is mocked so the context boots without the resource-server
 * identity plumbing, the stats test's own precedent.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ProviderPublicPageIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Autowired
    private ProviderPublicPageService publicPageService;

    @Autowired
    private ProviderService providerService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private com.marketplace.shared.security.CurrentUserProvider currentUserProvider;

    private static final UUID BROKER_USER_ID = UUID.randomUUID();
    private static final UUID CONSUMER_USER_ID = UUID.randomUUID();
    private static final UUID ACTIVE_LISTING_ID = UUID.randomUUID();
    private static final UUID PAUSED_LISTING_ID = UUID.randomUUID();
    private static final UUID BOOKING_A = UUID.randomUUID();
    private static final UUID BOOKING_B = UUID.randomUUID();
    private static final UUID REVIEW_R4 = UUID.randomUUID();
    private static final UUID REVIEW_R5 = UUID.randomUUID();

    private UUID profileId;

    @BeforeEach
    void seedTheBrokerDataset() {
        cleanUp();

        jdbcTemplate.update(
                "INSERT INTO users (id, subject, email, display_name, role) VALUES (?, ?, ?, ?, 'PROVIDER')",
                BROKER_USER_ID, "l36-broker-" + BROKER_USER_ID,
                "l36-broker-" + BROKER_USER_ID + "@example.com", "L36 Broker");
        jdbcTemplate.update(
                "INSERT INTO users (id, subject, email, display_name, role) VALUES (?, ?, ?, ?, 'CONSUMER')",
                CONSUMER_USER_ID, "l36-guest-" + CONSUMER_USER_ID,
                "l36-guest-" + CONSUMER_USER_ID + "@example.com", "L36 Guest");

        // The broker's profile: VERIFIED with the full L36 persona (seeded
        // through the entity-unreachable raw path — the status machine's
        // VERIFIED state is the page's gate input).
        profileId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO provider_profiles (id, display_name, bio, status, user_id, actor_type, agency_name, license_number, created_at, updated_at, version, is_deleted)
                VALUES (?, 'Qudsia Prime Estates', 'broker bio', 'VERIFIED', ?, 'AGENCY', 'Qudsia Prime Estates', 'BR-2026-1149', now(), now(), 0, false)
                """,
                profileId, BROKER_USER_ID);

        // The broker's listings (provider_listings.provider_id = users.id, A1):
        // one ACTIVE (the page's listing) and one PAUSED (must NOT appear —
        // the ACTIVE-only public contract).
        listing(ACTIVE_LISTING_ID, "ACTIVE");
        listing(PAUSED_LISTING_ID, "PAUSED");

        // The FK parents for the two reviews (bookings.provider_id = users.id).
        booking(BOOKING_A);
        booking(BOOKING_B);

        // Two forward reviews (provider_id = the broker's USER id — the
        // V6 FK's enforced space, the production write path's value):
        // ratings 4 and 5 -> AVG 4.5 grouped by the user id.
        review(REVIEW_R4, BOOKING_A, 4);
        review(REVIEW_R5, BOOKING_B, 5);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM reviews WHERE id IN (?, ?)", REVIEW_R4, REVIEW_R5);
        jdbcTemplate.update("DELETE FROM bookings WHERE id IN (?, ?)", BOOKING_A, BOOKING_B);
        jdbcTemplate.update("DELETE FROM provider_listings WHERE id IN (?, ?)",
                ACTIVE_LISTING_ID, PAUSED_LISTING_ID);
        jdbcTemplate.update("DELETE FROM provider_profiles WHERE user_id = ?", BROKER_USER_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id IN (?, ?)", BROKER_USER_ID, CONSUMER_USER_ID);
    }

    /**
     * Acceptance criterion 1 — the VERIFIED broker's page: persona fields,
     * ACTIVE listings only (the PAUSED sibling never appears), and the
     * fresh rating block (AVG 4.5 over 2 forward reviews, aggregated in
     * the reviews' enforced users.id space).
     */
    @Test
    void verifiedBrokerPage_showsPersonaActiveListingsAndRating() {
        var page = publicPageService.getPublicPage(profileId, PageRequest.of(0, 20));

        // The persona block (criterion 1's VERIFIED broker with his page):
        assertThat(page.status()).isEqualTo(ProviderStatus.VERIFIED);
        assertThat(page.displayName()).isEqualTo("Qudsia Prime Estates");
        assertThat(page.actorType()).isEqualTo(ProviderActorType.AGENCY);
        assertThat(page.agencyName()).isEqualTo("Qudsia Prime Estates");
        assertThat(page.licenseNumber()).isEqualTo("BR-2026-1149");

        // The listings block: the ACTIVE listing alone, honestly counted.
        assertThat(page.listings().totalElements()).isEqualTo(1);
        assertThat(page.listings().content()).singleElement()
                .satisfies(summary -> assertThat(summary.id()).isEqualTo(ACTIVE_LISTING_ID));

        // The rating block: the fresh aggregate (AVG 4.5 over 2 reviews).
        assertThat(page.ratingAverage()).isEqualTo(4.5);
        assertThat(page.reviewCount()).isEqualTo(2L);
    }

    /**
     * Acceptance criterion 2 — the SUSPENDED broker's page hides his
     * listings (total 0, honest empty page); the profile itself stays
     * visible with its status (the plain profile surface's own contract).
     * The suspension runs through the REAL service write (the status
     * machine + the AFTER_COMMIT cache invalidation the production path
     * fires) — no synthetic row UPDATE: the cached profile read must see
     * the invalidated, fresh state.
     */
    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
    void suspendedBrokerPage_hidesTheListings() {
        ProviderProfile profile = providerService.getById(profileId);
        assertThat(profile.getStatus()).isEqualTo(ProviderStatus.VERIFIED);
        providerService.suspend(profileId);

        var page = publicPageService.getPublicPage(profileId, PageRequest.of(0, 20));

        assertThat(page.status()).isEqualTo(ProviderStatus.SUSPENDED);
        assertThat(page.listings().totalElements()).isZero();
        assertThat(page.listings().content()).isEmpty();
        // The profile block itself stays visible (the criterion hides the
        // LISTINGS, not the profile).
        assertThat(page.displayName()).isEqualTo("Qudsia Prime Estates");
        assertThat(page.actorType()).isEqualTo(ProviderActorType.AGENCY);
    }

    @Test
    void unknownProvider_answers404() {
        assertThatThrownBy(() -> publicPageService.getPublicPage(UUID.randomUUID(), PageRequest.of(0, 20)))
                .isInstanceOf(com.marketplace.shared.api.ResourceNotFoundException.class);
    }

    private void listing(UUID id, String status) {
        jdbcTemplate.update(
                """
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status, created_at, updated_at, version, is_deleted)
                VALUES (?, ?, ?, 'l36 seed', 'APARTMENT', 1000, 'SAR', ?, now(), now(), 0, false)
                """,
                id, BROKER_USER_ID, "L36 Listing " + status, status);
    }

    private void booking(UUID id) {
        Instant startsAt = Instant.parse("2026-09-01T12:00:00Z");
        jdbcTemplate.update(
                """
                INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, price_cents, currency, starts_at, ends_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'COMPLETED', 1000, 'SAR', ?, ?, now(), now())
                """,
                id, CONSUMER_USER_ID, BROKER_USER_ID, ACTIVE_LISTING_ID,
                Timestamp.from(startsAt), Timestamp.from(startsAt.plusSeconds(3600)));
    }

    private void review(UUID id, UUID bookingId, int rating) {
        // provider_id = the broker's USER id — the V6 FK's enforced space
        // (references users(id)) and the production write path's value; the
        // FK rejects a profile id (measured: CI run on the first push).
        jdbcTemplate.update(
                """
                INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, rating, comment, direction, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, 'l36 seed review', 'CONSUMER_TO_PROVIDER', now(), now())
                """,
                id, bookingId, CONSUMER_USER_ID, BROKER_USER_ID, rating);
    }
}
