package com.marketplace.reviews;

import com.marketplace.community.ContentReportView;
import com.marketplace.community.ModerationAction;
import com.marketplace.community.ReportReason;
import com.marketplace.community.ReportTargetType;
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
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * W1 (§4.5 — إبلاغ المراجعة): the REVIEW report target through the REAL
 * modules over a REAL Flyway schema (the V86+V87 widening applied by the
 * boot — 'REVIEW' must ride the DB guard's widened list).
 *
 * <p><b>The gate order for REVIEW:</b> VISIBLE-target resolve first (an
 * unknown or pending/hidden review is the honest 404) → the reviewer's
 * own report on his own review is the 409 → a valid report inserts OPEN.
 * The resolution path (admin-gated service call, the real
 * {@code ReviewLookupPort} adapter) flips the published review to
 * HIDDEN_BY_MODERATOR in the resolver's transaction and closes the
 * report RESOLVED.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ReviewReportIntegrationTest {

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
    private com.marketplace.community.ContentReportService reportService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private com.marketplace.provider.ProviderRepository providerProfiles;

    @Autowired
    private JdbcTemplate jdbc;

    private final String tag = "revrep" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    private UUID reporterId;
    private UUID authorId;
    private UUID providerUserId;

    @BeforeEach
    void seedUsers() {
        reporterId = userRepository.save(User.create(
                tag + "-reporter-subject", tag + "-reporter@t.com", "Reporter", UserRole.CONSUMER)).getId();
        authorId = userRepository.save(User.create(
                tag + "-author-subject", tag + "-author@t.com", "Author", UserRole.CONSUMER)).getId();
        providerUserId = userRepository.save(User.create(
                tag + "-provider-subject", tag + "-provider@t.com", "Reported Provider", UserRole.PROVIDER)).getId();
        providerProfiles.save(
                com.marketplace.provider.ProviderProfile.create("Reported Business", "bio", providerUserId));
    }

    @AfterEach
    void cleanUp() {
        jdbc.update("DELETE FROM content_reports WHERE reporter_id IN (?, ?, ?)",
                reporterId, authorId, providerUserId);
        jdbc.update("DELETE FROM reviews WHERE reviewer_id IN (?, ?)", reporterId, authorId);
        jdbc.update("DELETE FROM provider_profiles WHERE user_id = ?", providerUserId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?, ?)", reporterId, authorId, providerUserId);
    }

    /** The V86/V87 pair applies cleanly: the widened target list holds the new value. */
    @Test
    void widenedTargetCheck_holdsReview() {
        UUID reviewId = plantReview(5, "PUBLISHED");

        ContentReportView created = reportService.createReport(
                reporterId, ReportTargetType.REVIEW, reviewId, ReportReason.INAPPROPRIATE);

        assertThat(jdbc.queryForObject(
                        "select target_type from content_reports where id = ?", String.class, created.id()))
                .isEqualTo("REVIEW");
        assertThat(created.status()).isEqualTo("OPEN");
    }

    /** An unknown review id is the honest 404 (the visibility gate resolves first). */
    @Test
    void unknownReview_answersNotFound() {
        assertThrows(com.marketplace.shared.api.ResourceNotFoundException.class,
                () -> reportService.createReport(reporterId, ReportTargetType.REVIEW,
                        UUID.randomUUID(), ReportReason.SPAM));
    }

    /** A PENDING (not yet public) review is not a public target — the same 404. */
    @Test
    void pendingReview_answersNotFound() {
        UUID pendingId = plantReview(5, "PENDING_REVIEW");

        assertThrows(com.marketplace.shared.api.ResourceNotFoundException.class,
                () -> reportService.createReport(reporterId, ReportTargetType.REVIEW,
                        pendingId, ReportReason.SPAM));
    }

    /** The author cannot report his own review (the own-content 409). */
    @Test
    void authorReportingOwnReview_answersConflict() {
        UUID reviewId = plantReview(5, "PUBLISHED");

        assertThrows(com.marketplace.shared.api.ConflictException.class,
                () -> reportService.createReport(authorId, ReportTargetType.REVIEW,
                        reviewId, ReportReason.OTHER));
    }

    /**
     * The resolution command on a REVIEW report: the admin resolve flips
     * the published review to HIDDEN_BY_MODERATOR inside the resolver's
     * transaction and closes the report RESOLVED — the real
     * {@code ReviewLookupPort} adapter, not a stub.
     */
    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
    void resolveHide_hidesTheReviewAndClosesResolved() {
        UUID reviewId = plantReview(5, "PUBLISHED");
        ContentReportView report = reportService.createReport(
                reporterId, ReportTargetType.REVIEW, reviewId, ReportReason.HARASSMENT);
        UUID adminId = userRepository.save(User.create(
                tag + "-admin-subject", tag + "-admin@t.com", "Mod", UserRole.ADMIN)).getId();
        try {
            ContentReportView resolved = reportService.resolveReport(
                    adminId, report.id(), ModerationAction.HIDE_CONTENT, "w1 hide");

            assertThat(resolved.status()).isEqualTo("RESOLVED");
            assertThat(jdbc.queryForObject(
                            "select moderation_status from reviews where id = ?", String.class, reviewId))
                    .isEqualTo("HIDDEN_BY_MODERATOR");
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?", adminId);
        }
    }

    /** A second resolve on the closed report is a 409 — closed history stays closed. */
    @Test
    @org.springframework.security.test.context.support.WithMockUser(roles = "ADMIN")
    void resolveTwice_answersConflictOnTheSecond() {
        UUID reviewId = plantReview(4, "PUBLISHED");
        ContentReportView report = reportService.createReport(
                reporterId, ReportTargetType.REVIEW, reviewId, ReportReason.SPAM);
        UUID adminId = userRepository.save(User.create(
                tag + "-admin2-subject", tag + "-admin2@t.com", "Mod2", UserRole.ADMIN)).getId();
        try {
            reportService.resolveReport(adminId, report.id(), ModerationAction.HIDE_CONTENT, "first");

            assertThrows(com.marketplace.shared.api.ConflictException.class,
                    () -> reportService.resolveReport(
                            adminId, report.id(), ModerationAction.DISMISS, "second"));
        } finally {
            jdbc.update("DELETE FROM users WHERE id = ?", adminId);
        }
    }

    /** A raw review row in the given moderation status (organic — no booking needed). */
    private UUID plantReview(int rating, String moderationStatus) {
        UUID reviewId = UUID.randomUUID();
        jdbc.update("INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, rating, "
                        + "origin, moderation_status) VALUES (?, NULL, ?, ?, ?, 'ORGANIC', ?)",
                reviewId, authorId, providerUserId, rating, moderationStatus);
        return reviewId;
    }
}