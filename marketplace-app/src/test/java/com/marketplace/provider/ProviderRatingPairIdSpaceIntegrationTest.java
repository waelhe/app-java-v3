package com.marketplace.provider;

import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import com.marketplace.identity.UserRole;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W1 (§4.4 — the id-space correction): the FIX for the measured defect, at
 * the production shape. The old
 * {@code ProviderService.refreshRatingAverage} looked a review's
 * {@code provider_id} (a {@code users.id} — V6's enforced FK space) up in
 * the {@code provider_profiles.id} space and then queried the aggregate by
 * {@code profile.getId()} AGAIN — a double mismatch that made the whole
 * path silently skip on every production-shaped pair of id spaces. The
 * pre-W1 unit test hid it by coinciding the two spaces.
 *
 * <p><b>This test's fixture is deliberately the production shape:</b> the
 * profile row ({@code provider_profiles.id}) and the provider's user id
 * are two DIFFERENT UUIDs. A booking carries the user id; the reviews
 * carry the user id; the profile links by {@code user_id}. The stored
 * VERIFIED column AND the general pair must land from real user-space
 * data — the old code would have skipped every assertion below.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ProviderRatingPairIdSpaceIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"})
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"})
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private ProviderService providerService;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * In production the refresh runs on the async AFTER_COMMIT dispatch
     * ({@code @ApplicationModuleListener} = its own REQUIRES_NEW transaction).
     * Called bare from the test thread, only the repository call would carry
     * the read-only transaction Spring Data supplies, the profile entity
     * would go detached, and the mutation would never persist — the assertion
     * would test nothing. This wrapper supplies the transaction the listener
     * owes, so what the test reads back is the COMMITTED row.
     */
    @Autowired
    private TransactionTemplate tx;

    private final String tag = "idspace" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    private UUID providerUserId;
    private UUID consumerUserId;
    private UUID profileId;

    @BeforeEach
    void seedUsersAndProfileWithDifferentIdSpaces() {
        providerUserId = userRepository.save(User.create(
                tag + "-provider-subject", tag + "-provider@t.com", "Id Provider", UserRole.PROVIDER)).getId();
        consumerUserId = userRepository.save(User.create(
                tag + "-consumer-subject", tag + "-consumer@t.com", "Id Consumer", UserRole.CONSUMER)).getId();
        profileId = providerRepository.save(
                ProviderProfile.create("IdSpace Business", "bio", providerUserId)).getId();

        assertThat(profileId)
                .as("the fixture really separates the two id spaces")
                .isNotEqualTo(providerUserId);

        jdbc.update("INSERT INTO provider_listings (id, provider_id, title, description, category, "
                        + "price_cents, currency, status) VALUES (?, ?, ?, ?, ?, ?, 'SAR', 'ACTIVE') "
                        + "ON CONFLICT (id) DO NOTHING",
                UUID.randomUUID(), providerUserId, "Id Loft", "id fixture", "home", 150_00L);
        jdbc.update("INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, "
                        + "price_cents, currency, notes) VALUES (?, ?, ?, "
                        + "(SELECT id FROM provider_listings WHERE provider_id = ? LIMIT 1), "
                        + "'COMPLETED', 150_00, 'SAR', NULL) ON CONFLICT (id) DO NOTHING",
                UUID.randomUUID(), consumerUserId, providerUserId, providerUserId);
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM reviews WHERE reviewer_id = ?", consumerUserId);
        jdbc.update("DELETE FROM bookings WHERE consumer_id = ?", consumerUserId);
        jdbc.update("DELETE FROM provider_listings WHERE provider_id = ?", providerUserId);
        jdbc.update("DELETE FROM provider_profiles WHERE user_id = ?", providerUserId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", providerUserId, consumerUserId);
    }

    /**
     * One BOOKING review (4) in the production id space: the synchronous
     * refresh lands the stored VERIFIED column at the true 4.0 — the old
     * flow skipped (the users.id never matched a profile row) and the
     * column would have stayed null.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void refresh_landsTheStoredVerifiedColumnAcrossTheRealIdSpaces() {
        UUID reviewId = plantVerifiedReview(4);

        refresh(reviewId);

        assertThat(stored("rating_average")).isEqualTo(4.0);
    }

    /**
     * The general pair lands across the same spaces: a PUBLISHED organic
     * averages 5.0 × 1 into {@code rating_general_average}/
     * {@code rating_general_count}, while the VERIFIED column stays
     * untouched.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void refresh_landsTheGeneralPairWithoutTouchingTheVerifiedColumn() {
        UUID organicId = plantOrganicReview(5);

        refresh(organicId);

        assertThat(stored("rating_general_average")).isEqualTo(5.0);
        assertThat(storedCount()).isEqualTo(1L);
        assertThat(stored("rating_average"))
                .as("no verified review exists yet")
                .isNull();
    }

    /**
     * The recompute-is-truth rule (§4.4): once every published review has
     * left the surface (the organic flips to HIDDEN_BY_MODERATOR — the
     * report-resolve outcome), the next refresh CLEARS the stored pair
     * instead of keeping a stale number.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void refresh_clearsStaleStoredValuesWhenNothingPublishedRemains() {
        UUID organicId = plantOrganicReview(5);
        refresh(organicId);
        assertThat(stored("rating_general_average")).isEqualTo(5.0);

        jdbc.update("UPDATE reviews SET moderation_status = 'HIDDEN_BY_MODERATOR' WHERE id = ?", organicId);
        refresh(organicId);

        assertThat(stored("rating_general_average")).isNull();
        assertThat(storedCount()).isZero();
    }

    /** The refresh exactly as the event dispatch runs it — inside its transaction. */
    private void refresh(UUID reviewId) {
        tx.executeWithoutResult(status -> providerService.refreshRatingAverage(reviewId));
    }

    /** The COMMITTED value of one stored-pair column, read straight from the badge's source. */
    private Double stored(String column) {
        return jdbc.queryForObject(
                "SELECT " + column + " FROM provider_profiles WHERE id = ?", Double.class, profileId);
    }

    private long storedCount() {
        Long count = jdbc.queryForObject(
                "SELECT rating_general_count FROM provider_profiles WHERE id = ?", Long.class, profileId);
        return count == null ? -1L : count;
    }

    private UUID plantVerifiedReview(int rating) {
        UUID reviewId = UUID.randomUUID();
        jdbc.update("INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, rating, "
                        + "origin, moderation_status) VALUES (?, "
                        + "(SELECT id FROM bookings WHERE consumer_id = ? LIMIT 1), ?, ?, ?, "
                        + "'BOOKING', 'PUBLISHED')",
                reviewId, consumerUserId, consumerUserId, providerUserId, rating);
        return reviewId;
    }

    private UUID plantOrganicReview(int rating) {
        UUID reviewId = UUID.randomUUID();
        jdbc.update("INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, rating, "
                        + "origin, moderation_status) VALUES (?, NULL, ?, ?, ?, 'ORGANIC', 'PUBLISHED')",
                reviewId, consumerUserId, providerUserId, rating);
        return reviewId;
    }
}