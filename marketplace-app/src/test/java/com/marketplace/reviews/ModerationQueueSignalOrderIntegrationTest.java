package com.marketplace.reviews;

import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import com.marketplace.identity.UserRole;
import com.marketplace.provider.ProviderProfile;
import com.marketplace.provider.ProviderRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * W5 (yelp-level plan §5 — G25): the §7 risk section's own law — «الإشراف
 * البشري يقرر، والإشارة ترتّب الطابور» — as a living guard: the
 * moderation queue drains SIGNAL-FIRST (the aggregate fraud-signal count
 * the W1 wave records), with the W1 FIFO order (createdAt, id) as the
 * deterministic tiebreak. Three pending reviews with different signal
 * counts prove the ordering; the flags ride the REAL entity factories and
 * the REAL Flyway schema (V87).
 *
 * <p><b>Every pending review carries its OWN reviewer</b> — the W1 law
 * (V85's {@code uq_review_organic_once}: one ORGANIC review per
 * reviewer x provider, forever) makes a same-pair queue structurally
 * impossible, and a real moderation queue holds reviews from DIFFERENT
 * reviewers on the SAME provider. The CI round on {@code 1204c9e} caught
 * the shared-reviewer seed violating the constraint on the second insert
 * (the Docker-less local runs had skipped this class —
 * {@code disabledWithoutDocker = true}); this is the domain-honest shape,
 * not a workaround.</p>
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ModerationQueueSignalOrderIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"})
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private ReviewsService reviewsService;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private ReviewFlagRepository reviewFlagRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProviderRepository providerProfiles;

    @Autowired
    private JdbcTemplate jdbc;

    private final String tag = "w5-queue-" + UUID.randomUUID();
    /** One reviewer per pending review — the W1 uq_review_organic_once law. */
    private final List<UUID> reviewerIds = new java.util.ArrayList<>();
    private UUID providerId;
    private UUID profileId;

    @BeforeEach
    void seedWorld() {
        providerId = userRepository.save(User.create(
                tag + "-provider-subject", tag + "-provider@t.com", "W5 Provider", UserRole.PROVIDER)).getId();
        profileId = providerProfiles.save(ProviderProfile.create("W5 Business", "bio", providerId)).getId();
    }

    @AfterEach
    void cleanUp() {
        // Flags before reviews (V87 has no ON DELETE CASCADE — the W1 r3 lesson),
        // then each reviewer's own rows — one reviewer per pending review.
        for (UUID reviewerId : reviewerIds) {
            jdbc.update("DELETE FROM review_flags WHERE review_id IN "
                    + "(SELECT id FROM reviews WHERE reviewer_id = ?)", reviewerId);
            jdbc.update("DELETE FROM reviews WHERE reviewer_id = ?", reviewerId);
            jdbc.update("DELETE FROM users WHERE id = ?", reviewerId);
        }
        jdbc.update("DELETE FROM provider_profiles WHERE id = ?", profileId);
        jdbc.update("DELETE FROM users WHERE id = ?", providerId);
    }

    /**
     * Three pending reviews: the FIRST-created carries one signal, the
     * second carries none, the third (newest) carries THREE — the queue
     * must answer the newest first (signal count DESC), then the oldest
     * FIFO pair in creation order.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void theQueueDrainsSignalFirst_thenFIFO() {
        // Deterministic creation order: the entity's createdAt is
        // @CreatedDate (millisecond resolution on the same clock) — pin the
        // timestamps explicitly so the FIFO tiebreak is asserted, not hoped.
        Review oneSignal = pendingReview("one signal, oldest", 3);
        jdbc.update("UPDATE reviews SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(java.time.Instant.parse("2026-10-03T08:00:00Z")), oneSignal.getId());
        Review noSignal = pendingReview("no signal at all", 4);
        jdbc.update("UPDATE reviews SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(java.time.Instant.parse("2026-10-03T08:00:01Z")), noSignal.getId());
        Review threeSignals = pendingReview("three signals, newest", 1);
        jdbc.update("UPDATE reviews SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(java.time.Instant.parse("2026-10-03T08:00:02Z")), threeSignals.getId());

        reviewFlagRepository.save(ReviewFlag.create(oneSignal.getId(),
                ReviewFlag.FlagType.NEW_ACCOUNT_ACTIVITY, "seeded"));
        reviewFlagRepository.save(ReviewFlag.create(threeSignals.getId(),
                ReviewFlag.FlagType.BURST_ON_PROVIDER, "seeded"));
        reviewFlagRepository.save(ReviewFlag.create(threeSignals.getId(),
                ReviewFlag.FlagType.NEW_ACCOUNT_ACTIVITY, "seeded"));
        reviewFlagRepository.save(ReviewFlag.create(threeSignals.getId(),
                ReviewFlag.FlagType.TEXT_SIMILARITY, "seeded"));

        var page = reviewsService.moderationQueue(
                ReviewModerationStatus.PENDING_REVIEW, Pageable.unpaged());

        List<UUID> order = page.getContent().stream()
                .map(ReviewsService.ModerationQueueItem::id).toList();
        assertThat(order).containsExactly(
                threeSignals.getId(), oneSignal.getId(), noSignal.getId());

        // The item carries the flag vocabulary — the moderator sees WHY the
        // review ranks where it does (the transparency the plan demanded:
        // «لا حجب تلقائي في الإصدار الأول؛ الإشراف البشري يقرر»).
        var topItem = page.getContent().get(0);
        assertThat(topItem.flags()).hasSize(3);
    }

    /**
     * The pagination composes with the signal order: page 1 (size 2) answers
     * the two most-flagged reviews, page 2 the FIFO remainder — the GROUP BY
     * ordering is total, no row is skipped or duplicated across pages.
     */
    @Test
    @WithMockUser(roles = "ADMIN")
    void theSignalOrderPaginatesWithoutSkippingOrDuplicating() {
        Review a = pendingReview("a", 3);
        Review b = pendingReview("b", 3);
        Review c = pendingReview("c", 3);
        jdbc.update("UPDATE reviews SET created_at = ? WHERE id IN (?, ?, ?)",
                java.sql.Timestamp.from(java.time.Instant.parse("2026-10-03T08:10:00Z")),
                a.getId(), b.getId(), c.getId());
        // Same created_at second: the id tiebreak owns the total order.
        // Two flags on a, one on b, none on c.
        reviewFlagRepository.save(ReviewFlag.create(a.getId(),
                ReviewFlag.FlagType.BURST_ON_PROVIDER, "seeded"));
        reviewFlagRepository.save(ReviewFlag.create(a.getId(),
                ReviewFlag.FlagType.TEXT_SIMILARITY, "seeded"));
        reviewFlagRepository.save(ReviewFlag.create(b.getId(),
                ReviewFlag.FlagType.NEW_ACCOUNT_ACTIVITY, "seeded"));

        var page1 = reviewsService.moderationQueue(
                ReviewModerationStatus.PENDING_REVIEW, PageRequest.of(0, 2));
        var page2 = reviewsService.moderationQueue(
                ReviewModerationStatus.PENDING_REVIEW, PageRequest.of(1, 2));

        assertThat(page1.getContent()).extracting(ReviewsService.ModerationQueueItem::id)
                .containsExactly(a.getId(), b.getId());
        assertThat(page2.getContent()).extracting(ReviewsService.ModerationQueueItem::id)
                .containsExactly(c.getId());
        assertThat(page1.getContent()).extracting(ReviewsService.ModerationQueueItem::id)
                .doesNotContainAnyElementsOf(
                        page2.getContent().stream().map(ReviewsService.ModerationQueueItem::id).toList());
    }

    /**
     * One PENDING_REVIEW organic review from its OWN reviewer — the W1
     * law's own shape (a real queue: different reviewers, one provider).
     */
    private Review pendingReview(String comment, int rating) {
        UUID reviewerId = userRepository.save(User.create(
                tag + "-reviewer-" + reviewerIds.size() + "-subject",
                tag + "-reviewer-" + reviewerIds.size() + "@t.com",
                "W5 Reviewer", UserRole.CONSUMER)).getId();
        reviewerIds.add(reviewerId);
        Review review = Review.createOrganic(reviewerId, providerId, null, rating, comment);
        review.queueForReview();
        return reviewRepository.save(review);
    }
}
