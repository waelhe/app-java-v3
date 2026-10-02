package com.marketplace.reviews;

import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import com.marketplace.identity.UserRole;
import com.marketplace.provider.ProviderProfile;
import com.marketplace.provider.ProviderRepository;
import com.marketplace.provider.ProviderService;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.ReviewStats;
import com.marketplace.shared.api.SystemSettingKeys;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * W1 (§4.4/§4.5 — the moderation and the dual badges): the pending
 * review's whole life through the REAL modules over a REAL Flyway schema.
 *
 * <p><b>Visibility is data, not intent (§4.5's own words):</b> a pending
 * organic review is absent from the provider's public list AND from both
 * live aggregates — and a real approval flips it into both (the stored
 * averages recomputed through the EXISTING review events — the plan's
 * AFTER_COMMIT requirement).
 *
 * <p><b>The hybrid badges:</b> one VERIFIED (booking) and one approved
 * organic on the same provider — the public page shows the verified
 * aggregate in the main fields and the general aggregate in the second
 * badge, counted and averaged separately (§4.4's «موثّق 4.8 (23) · عام
 * 4.2 (156)» at data scale).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ReviewModerationIntegrationTest {

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
    private ReviewsService reviewsService;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProviderRepository providerProfiles;

    @Autowired
    private ProviderService providerService;

    @Autowired
    private com.marketplace.provider.ProviderPublicPageService publicPageService;

    /**
     * The stored-pair recompute is a listener method: in production it runs
     * on the async AFTER_COMMIT dispatch, inside its own transaction. Called
     * bare from the test thread the profile entity goes detached and the
     * mutation never persists — this wrapper supplies that transaction so the
     * assertions below read committed state.
     */
    @Autowired
    private org.springframework.transaction.support.TransactionTemplate tx;

    private final String tag = "modrev" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    private UUID reviewerId;
    private UUID providerUserId;
    private ProviderProfile profile;
    private com.marketplace.admin.SystemSettingsService settings;
    private JdbcTemplate jdbc;

    @Autowired
    void wiring(com.marketplace.admin.SystemSettingsService settings, JdbcTemplate jdbc) {
        this.settings = settings;
        this.jdbc = jdbc;
    }

    @BeforeEach
    void seedUsers() {
        reviewerId = userRepository.save(User.create(
                tag + "-reviewer-subject", tag + "-reviewer@t.com", "Mod Reviewer", UserRole.CONSUMER)).getId();
        providerUserId = userRepository.save(User.create(
                tag + "-provider-subject", tag + "-provider@t.com", "Mod Provider", UserRole.PROVIDER)).getId();
        profile = providerProfiles.save(ProviderProfile.create("Mod Business", "bio", providerUserId));
        settings.update(SystemSettingKeys.REVIEWS_MODE,
                JsonNodeFactory.instance.textNode("HYBRID"), null, "w1-test");
        jdbc.update("UPDATE users SET created_at = ? WHERE id IN (?, ?)",
                java.sql.Timestamp.from(Instant.now().minus(java.time.Duration.ofDays(9))),
                reviewerId, providerUserId);
    }

    @AfterEach
    void restoreSeedAndCleanUp() {
        settings.update(SystemSettingKeys.REVIEWS_MODE,
                JsonNodeFactory.instance.textNode("VERIFIED_ONLY"), null, "w1-test");
        // CodeRabbit W1 r3 (adopted from the root): flags before reviews — V87
        // has no ON DELETE CASCADE and createOrganic records the young
        // account's NEW_ACCOUNT_ACTIVITY flag; the reverse order dies on the
        // FK and leaks the fixtures into the shared database.
        jdbc.update("DELETE FROM review_flags WHERE review_id IN "
                + "(SELECT id FROM reviews WHERE reviewer_id = ?)", reviewerId);
        jdbc.update("DELETE FROM reviews WHERE reviewer_id = ?", reviewerId);
        // hybrid_ownTwoBadgesSeparately inserts a provider_listings row for
        // the organic listing target (same CodeRabbit r3 note): the users
        // delete below would otherwise die on the listing's provider FK.
        jdbc.update("DELETE FROM provider_listings WHERE provider_id = ?", providerUserId);
        jdbc.update("DELETE FROM provider_profiles WHERE id = ?", profile.getId());
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", reviewerId, providerUserId);
    }

    /**
     * The moderation life at data scale: a fresh account's first organic
     * review is PENDING_REVIEW — absent from the provider's public list,
     * absent from both live aggregates, present in the FIFO queue. A real
     * approval publishes it — the EXISTING creation event fires, the async
     * listener recomputes, and the stored general pair lands.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void pendingReview_invisibleUntilRealApproval_thenCountedByTheExistingEvent() {
        JwtAuthenticationToken reviewer = jwtAuthentication(tag + "-reviewer-subject", "ROLE_CONSUMER");

        Review queued = reviewsService.createOrganic(
                profile.getId(), null, 5, "awaiting moderation", reviewer);
        assertThat(queued.getModerationStatus()).isEqualTo(ReviewModerationStatus.PENDING_REVIEW);

        // The public read paths serve nothing pending (visibility = data).
        assertThat(reviewsService.listByProvider(providerUserId, Pageable.ofSize(10)))
                .isEmpty();
        assertThat(reviewsService.listByReviewer(reviewerId, Pageable.ofSize(10), null))
                .isEmpty();
        assertThat(reviewRepository.getStatsByProviderId(providerUserId)).isEmpty();
        assertThat(reviewRepository.getGeneralStatsByProviderId(providerUserId)).isEmpty();

        // The author's own list owes him the badge (§4.5's owner branch).
        assertThat(reviewsService.listByReviewer(reviewerId, Pageable.ofSize(10), reviewer)
                .map(Review::getModerationStatus))
                .containsExactly(ReviewModerationStatus.PENDING_REVIEW);

        // The queue drains FIFO with the fraud flags visible.
        assertThat(reviewsService.moderationQueue(ReviewModerationStatus.PENDING_REVIEW, Pageable.ofSize(10))
                .map(ReviewsService.ModerationQueueItem::id))
                .contains(queued.getId());

        // A real approval: the row publishes, the EXISTING creation event
        // fires, the async listener recomputes the general aggregate.
        reviewsService.approve(queued.getId());
        awaitGeneralStats(providerUserId, 5.0, 1L);
        // The CI-measured first-run race: the aggregate await above passes the
        // instant approve() commits (the direct query sees the published row),
        // while the async listener's STORED-pair write may still be in flight —
        // awaiting the profile's stored pair is awaiting the listener itself
        // (the only writer of those columns).
        awaitStoredGeneralPair(profile.getId(), 5.0, 1L);

        assertThat(reviewsService.listByProvider(providerUserId, Pageable.ofSize(10))
                .map(Review::getId))
                .contains(queued.getId());

        // The STORED pair — written only by the listener path, never
        // inline — carries the approved review; the verified column stays
        // null (no verified review exists yet).
        ProviderProfile stored = providerProfiles.findById(profile.getId()).orElseThrow();
        assertThat(stored.getRatingGeneralAverage()).isEqualTo(5.0);
        assertThat(stored.getRatingGeneralCount()).isEqualTo(1L);
        assertThat(stored.getRatingAverage()).isNull();
    }

    /**
     * The hybrid badges at data scale: one VERIFIED booking review and
     * one published organic on the same provider — the verified aggregate
     * feeds the stored column (and the page's main fields), the organic
     * one the general pair. The stored recompute runs synchronously (the
     * async listener is the same method — pinned race-free above).
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void hybrid_ownTwoBadgesSeparately() {
        UUID bookingId = UUID.randomUUID();
        jdbc.update("INSERT INTO provider_listings (id, provider_id, title, description, category, "
                        + "price_cents, currency, status) VALUES (?, ?, ?, ?, ?, ?, 'SAR', 'ACTIVE') "
                        + "ON CONFLICT (id) DO NOTHING",
                UUID.randomUUID(), providerUserId, "Hybrid Loft", "hybrid fixture", "home", 200_00L);
        jdbc.update("INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, "
                        + "price_cents, currency, notes) VALUES (?, ?, ?, "
                        + "(SELECT id FROM provider_listings WHERE provider_id = ? LIMIT 1), "
                        + "'COMPLETED', 200_00, 'SAR', NULL) ON CONFLICT (id) DO NOTHING",
                bookingId, reviewerId, providerUserId, providerUserId);

        Review verifiedReview = jdbcVerifiedReview(bookingId, 4);
        Review organicReview = reviewsService.createOrganic(
                profile.getId(), null, 5, "general words",
                jwtAuthentication(tag + "-reviewer-subject", "ROLE_CONSUMER"));
        // The FIRST organic of the fixture account queues — published
        // through the real approval, like the moderation queue would.
        reviewsService.approve(organicReview.getId());

        // The stored pair, recomputed the way the listener runs it (inside its
        // own transaction). The async dispatch of the approval above computes
        // the SAME pair — the profile row's pessimistic lock serializes the
        // two, and both carry exactly these values.
        tx.executeWithoutResult(
                status -> providerService.refreshRatingAverage(verifiedReview.getId()));

        ProviderProfile read = providerProfiles.findById(profile.getId()).orElseThrow();
        assertThat(read.getRatingAverage()).isEqualTo(4.0);
        assertThat(read.getRatingGeneralAverage()).isEqualTo(5.0);
        assertThat(read.getRatingGeneralCount()).isEqualTo(1L);

        // CodeRabbit W1 r3 (adopted from the root): flags before reviews — the
        // organic review of this nine-day-old account carries its
        // NEW_ACCOUNT_ACTIVITY flag, and V87 has no ON DELETE CASCADE.
        jdbc.update("DELETE FROM review_flags WHERE review_id IN (?, ?)",
                verifiedReview.getId(), organicReview.getId());
        jdbc.update("DELETE FROM reviews WHERE id IN (?, ?)", verifiedReview.getId(), organicReview.getId());
        jdbc.update("DELETE FROM bookings WHERE id = ?", bookingId);
    }

    /** A raw VERIFIED row in the production shape (booking-bound, users.id space, PUBLISHED). */
    private Review jdbcVerifiedReview(UUID bookingId, int rating) {
        UUID id = UUID.randomUUID();
        jdbc.update("INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, rating, "
                        + "origin, moderation_status) VALUES (?, ?, ?, ?, ?, 'BOOKING', 'PUBLISHED')",
                id, bookingId, reviewerId, providerUserId, rating);
        return reviewRepository.findById(id).orElseThrow();
    }

    /** Plain poll loop (30s / 200ms) — the ReviewsTwoWayIntegrationTest house shape for async listeners. */
    /**
     * Awaits the listener's OWN artifact — the stored pair on the profile row
     * (its only writer) — the race-free contract awaitGeneralStats alone
     * cannot express (the aggregate becomes visible at approve-commit time,
     * before the async listener runs).
     */
    private void awaitStoredGeneralPair(UUID profileId, double expectedAverage, long expectedCount) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            ProviderProfile stored = providerProfiles.findById(profileId).orElse(null);
            if (stored != null
                    && stored.getRatingGeneralAverage() != null
                    && Math.abs(stored.getRatingGeneralAverage() - expectedAverage) < 1e-9
                    && stored.getRatingGeneralCount() == expectedCount) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        org.junit.jupiter.api.Assertions.fail(
                "the stored general pair never reached " + expectedAverage + "/" + expectedCount);
    }

    private void awaitGeneralStats(UUID providerId, double expectedAverage, long expectedCount) {
        long deadline = System.nanoTime() + 30_000_000_000L;
        while (System.nanoTime() < deadline) {
            java.util.Optional<ReviewStats> stats =
                    reviewRepository.getGeneralStatsByProviderId(providerId);
            if (stats.isPresent()
                    && Math.abs(stats.get().averageRating() - expectedAverage) < 1e-9
                    && stats.get().reviewCount() == expectedCount) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        org.junit.jupiter.api.Assertions.fail(
                "the general aggregate never reached " + expectedAverage + "/" + expectedCount);
    }

    /**
     * The method-parameter principal: a JwtAuthenticationToken whose subject
     * is a real users row (the ReviewsTwoWayIntegrationTest house shape).
     */
    private static JwtAuthenticationToken jwtAuthentication(String subject, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .issuedAt(Instant.now())
                .expiresAt(Instant.now().plusSeconds(60))
                .build();
        return new JwtAuthenticationToken(jwt, List.of(new SimpleGrantedAuthority(role)));
    }
}