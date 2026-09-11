package com.marketplace.reviews;

import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import com.marketplace.identity.UserRole;
import com.marketplace.shared.api.ReviewStats;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I8/V45 — the rating aggregate's direction filter, pinned at the query
 * level with production-space data: {@code ReviewRepository.getStatsByProviderId}
 * must count FORWARD reviews only («a provider-authored rating of the
 * consumer never pollutes the provider's own average», V45's letter).
 *
 * <p><b>Why this test exists now:</b> the §9 surgical gate fix re-seeded
 * {@code ReviewsTwoWayIntegrationTest.reverseReview_neverPollutes...} to
 * the production id space (the reverse review stores the AUTHORING
 * provider's user id — A1), which moved that test's pollution-proof behind
 * the id-space separation (the stats recompute cannot even resolve a
 * profile from a users.id). The direction filter itself — the defense that
 * holds when both directions share one provider key — still deserves a
 * direct pin: this test plants BOTH directions on ONE booking with the
 * SAME provider id (the only differing attribute), and asserts the
 * aggregate counts the forward row alone.
 *
 * <p><b>Seeding (the Phase 3 b-3 JDBC pattern, one real FK chain):</b> the
 * provider's listing (V2), ONE COMPLETED booking whose provider_id is the
 * provider's user id (V3 — the production shape), and the two-way review
 * pair V45's per-direction uniqueness admits on a single booking: the
 * consumer's forward review (rating 5) and the provider's reverse review
 * (rating 1) — both carrying the provider's user id, both live rows.
 */
@SpringBootTest(properties = {
        // The production-shaped schema (the CatalogSearchFullText / b-3 house
        // pattern): real Flyway V1..V46, no ddl-auto — the default test
        // profile's create-drop schema lacks the migration DEFAULTS the
        // JDBC seeds rely on (V2's is_deleted default false) and V30's
        // revinfo sequence alignment; the §7 "test schema != production
        // schema" class, measured in CI round 1.
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ReviewsAggregateDirectionIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void aggregateCountsForwardReviewsOnly_bothDirectionsSameProvider() {
        String tag = "aggdir";
        UUID providerUserId = userRepository.save(User.create(
                tag + "-provider-subject", tag + "-provider@t.com", "Provider", UserRole.PROVIDER)).getId();
        UUID consumerId = userRepository.save(User.create(
                tag + "-consumer-subject", tag + "-consumer@t.com", "Consumer", UserRole.CONSUMER)).getId();

        UUID listingId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status)
                VALUES (?, ?, ?, ?, ?, ?, 'SAR', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """,
                listingId, providerUserId, "Aggregate Direction Listing " + tag,
                "direction filter fixture", "home", 100_00L);

        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, price_cents, currency, notes)
                VALUES (?, ?, ?, ?, 'COMPLETED', 100_00, 'SAR', NULL)
                ON CONFLICT (id) DO NOTHING
                """,
                bookingId, consumerId, providerUserId, listingId);

        // The two-way pair on ONE booking (V45's per-direction uniqueness):
        // the consumer's forward review...
        jdbcTemplate.update(
                """
                INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, rating, comment, direction, reviewee_id)
                VALUES (?, ?, ?, ?, 5, 'great stay', 'CONSUMER_TO_PROVIDER', NULL)
                ON CONFLICT (id) DO NOTHING
                """,
                UUID.randomUUID(), bookingId, consumerId, providerUserId);
        // ...and the provider's reverse review — SAME provider id, the only
        // differing attribute is the direction (rating 1: an unfiltered
        // aggregate would land (5+1)/2 = 3.0).
        jdbcTemplate.update(
                """
                INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, rating, comment, direction, reviewee_id)
                VALUES (?, ?, ?, ?, 1, 'difficult guest', 'PROVIDER_TO_CONSUMER', ?)
                ON CONFLICT (id) DO NOTHING
                """,
                UUID.randomUUID(), bookingId, providerUserId, providerUserId, consumerId);

        Optional<ReviewStats> stats = reviewRepository.getStatsByProviderId(providerUserId);

        assertThat(stats).as("the aggregate resolves (forward rows exist)").isPresent();
        assertThat(stats.orElseThrow().averageRating())
                .as("the direction filter excludes the reverse row (unfiltered: 3.0)")
                .isEqualTo(5.0);
        assertThat(stats.orElseThrow().reviewCount()).isEqualTo(1L);
    }
}
