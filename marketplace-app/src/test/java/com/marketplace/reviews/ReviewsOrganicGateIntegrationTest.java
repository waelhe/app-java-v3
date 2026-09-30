package com.marketplace.reviews;

import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import com.marketplace.identity.UserRole;
import com.marketplace.provider.ProviderProfile;
import com.marketplace.provider.ProviderRepository;
import com.marketplace.shared.api.BadRequestException;
import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ConflictException;
import com.marketplace.shared.api.SystemSettingKeys;
import com.marketplace.shared.api.TooManyRequestsException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * W1 (§4.1/§4.3/§4.5 — the unified creation gate): the base pin plus the
 * per-mode creation tests through the REAL modules over a REAL Flyway
 * schema (V72 applied by the boot — the booking seam stays the one mocked
 * boundary). Modes flip through the REAL admin channel and are restored
 * to the seed after every test.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
// The organic write surface is @PreAuthorize("isAuthenticated()") — the
// method-parameter JwtAuthenticationToken carries OWNERSHIP (who the caller
// is in the users table), while method security reads the context holder.
// A class-level principal supplies the latter for every test here; the
// tests that assert a specific role repeat it at method level.
@WithMockUser(roles = "CONSUMER")
class ReviewsOrganicGateIntegrationTest {

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

    @MockitoBean
    private BookingParticipantProvider bookingParticipantProvider;

    @Autowired
    private ReviewsService reviewsService;

    @Autowired
    private ReviewRepository reviewRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ProviderRepository providerProfiles;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private com.marketplace.admin.SystemSettingsService settingsService;

    private final String tag = "orggate" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

    private UUID reviewerId;
    private UUID providerUserId;
    private ProviderProfile profile;
    private String reviewerSubject;
    private String providerSubject;

    @BeforeEach
    void seedUsers() {
        reviewerSubject = tag + "-reviewer-subject";
        providerSubject = tag + "-provider-subject";
        reviewerId = userRepository.save(User.create(
                reviewerSubject, tag + "-reviewer@t.com", "Org Reviewer", UserRole.CONSUMER)).getId();
        providerUserId = userRepository.save(User.create(
                providerSubject, tag + "-provider@t.com", "Org Provider", UserRole.PROVIDER)).getId();
        profile = providerProfiles.save(ProviderProfile.create("Org Business", "bio", providerUserId));
    }

    private void ageAccount(UUID userId, int days) {
        jdbc.update("UPDATE users SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(Duration.ofDays(days))), userId);
    }

    private void setMode(String mode) {
        settingsService.update(SystemSettingKeys.REVIEWS_MODE,
                JsonNodeFactory.instance.textNode(mode), null, "w1-test");
    }

    @AfterEach
    void restoreSeedAndCleanUp() {
        settingsService.update(SystemSettingKeys.REVIEWS_MODE,
                JsonNodeFactory.instance.textNode("VERIFIED_ONLY"), null, "w1-test");
        jdbc.update("DELETE FROM reviews WHERE reviewer_id IN (?, ?)", reviewerId, providerUserId);
        jdbc.update("DELETE FROM provider_profiles WHERE user_id IN (?, ?)", reviewerId, providerUserId);
        jdbc.update("DELETE FROM users WHERE id IN (?, ?)", reviewerId, providerUserId);
    }

    /**
     * The base pin: the seeded VERIFIED_ONLY survives from V71 onto the
     * full stack and the booking path creates a BOOKING-origin published
     * review — the observable contract every legacy flow depends on.
     */
    @Test
    @WithMockUser(roles = "CONSUMER")
    void verifiedOnly_bookingPathWorksAndModeIsSeededFromW0() {
        assertThat(jdbc.queryForObject(
                        "select setting_value::text from system_settings where setting_key = ?",
                        String.class, SystemSettingKeys.REVIEWS_MODE))
                .isEqualTo("\"VERIFIED_ONLY\"");

        UUID bookingId = UUID.randomUUID();
        when(bookingParticipantProvider.getBookingInfo(any())).thenReturn(new BookingInfo(
                providerUserId, reviewerId, "COMPLETED", 5000L, "SAR", Instant.now(), Instant.now()));

        Review created = reviewsService.create(bookingId, reviewerId, 4, "solid stay");

        assertThat(created.getOrigin()).isEqualTo(Review.ORIGIN_BOOKING);
        assertThat(created.getModerationStatus()).isEqualTo(ReviewModerationStatus.PUBLISHED);
        assertThat(jdbc.queryForObject(
                        "select count(*) from reviews where id = ? and origin = 'BOOKING'"
                                + " and moderation_status = 'PUBLISHED'",
                        Long.class, created.getId()))
                .isEqualTo(1L);
    }

    /**
     * The base pin, negative: under the seed mode an organic attempt is a
     * 400 with the vocabulary message — never a silent booking review.
     */
    @Test
    void verifiedOnly_organicPathIsRefused() {
        ageAccount(reviewerId, 9);

        assertThrows(BadRequestException.class, () -> reviewsService.createOrganic(
                profile.getId(), null, 5, "not enabled here",
                jwtAuthentication(reviewerSubject, "ROLE_CONSUMER")));
        assertThat(reviewRepository.existsByReviewerIdAndProviderIdAndOrigin(
                reviewerId, providerUserId, Review.ORIGIN_ORGANIC)).isFalse();
    }

    /**
     * The method-parameter principal: a JwtAuthenticationToken whose subject
     * is a real users row (the ReviewsTwoWayIntegrationTest house shape —
     * method security reads the @WithMockUser context, ownership this token).
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

    /**
     * OPEN, the owner branch: an aged account's fourth organic review
     * (past the first-three queue) lands PUBLISHED with a null booking —
     * the letter of §4.1's second row plus §4.2's null-booking shape.
     */
    @Test
    void open_agedFourthReviewLandsPublishedWithNullBooking() {
        setMode("OPEN");
        UUID fresh = agedReviewerWithPriorOrganic(3, tag + "-fresh-subject");
        try {
            Review created = reviewsService.createOrganic(
                    profile.getId(), null, 5, "great bakery",
                    jwtAuthentication(tag + "-fresh-subject", "ROLE_CONSUMER"));

            assertThat(created.getOrigin()).isEqualTo(Review.ORIGIN_ORGANIC);
            assertThat(created.getBookingId()).isNull();
            assertThat(created.getModerationStatus()).isEqualTo(ReviewModerationStatus.PUBLISHED);
            assertThat(jdbc.queryForObject(
                            "select count(*) from review_flags where review_id = ? and is_deleted = false",
                            Long.class, created.getId()))
                    .as("an 8-day-old account trips the NEW_ACCOUNT_ACTIVITY signal (recorded, not blocked)")
                    .isEqualTo(1L);
        } finally {
            cleanupOrganicReviewer(fresh);
        }
    }

    /**
     * OPEN, negative: a booking review in OPEN mode is the ruled rejection
     * (the plan's «يُرفض الموثق» choice) — explicit 400, no write.
     */
    @Test
    @WithMockUser(roles = "CONSUMER")
    void open_bookingPathIsRefused_loudlyAndWithoutAWrite() {
        setMode("OPEN");
        UUID bookingId = UUID.randomUUID();
        when(bookingParticipantProvider.getBookingInfo(any())).thenReturn(new BookingInfo(
                providerUserId, reviewerId, "COMPLETED", 5000L, "SAR", Instant.now(), Instant.now()));

        assertThrows(BadRequestException.class,
                () -> reviewsService.create(bookingId, reviewerId, 5, "wrong mode"));
        assertThat(reviewRepository.existsByBookingIdAndDirection(
                bookingId, ReviewDirection.CONSUMER_TO_PROVIDER)).isFalse();
    }

    /**
     * HYBRID: both paths answer 201 (the booking review and an aged
     * account's queued organic — the queue belongs to the moderation
     * test, here only the acceptance is proved).
     */
    @Test
    @WithMockUser(roles = "CONSUMER")
    void hybrid_bothPathsAccepted() {
        setMode("HYBRID");
        ageAccount(reviewerId, 9);
        UUID bookingId = UUID.randomUUID();
        when(bookingParticipantProvider.getBookingInfo(any())).thenReturn(new BookingInfo(
                providerUserId, reviewerId, "COMPLETED", 5000L, "SAR", Instant.now(), Instant.now()));

        Review verified = reviewsService.create(bookingId, reviewerId, 4, "verified stay");
        Review organic = reviewsService.createOrganic(
                profile.getId(), null, 4, "general words",
                jwtAuthentication(reviewerSubject, "ROLE_CONSUMER"));

        assertThat(verified.getOrigin()).isEqualTo(Review.ORIGIN_BOOKING);
        assertThat(organic.getOrigin()).isEqualTo(Review.ORIGIN_ORGANIC);
        assertThat(organic.getModerationStatus()).isEqualTo(ReviewModerationStatus.PENDING_REVIEW);
    }

    /**
     * §4.5 account-age floor by data: a two-day-old account cannot write
     * organic reviews, whatever the mode; the answer is the explicit 400.
     */
    @Test
    void organic_rejectsTheTooYoungAccount() {
        setMode("OPEN");
        ageAccount(reviewerId, 2);

        assertThrows(BadRequestException.class, () -> reviewsService.createOrganic(
                profile.getId(), null, 5, "patience",
                jwtAuthentication(reviewerSubject, "ROLE_CONSUMER")));
    }

    /**
     * §4.5 self-review: the provider's own account cannot review his
     * business (the plan's «ليس المزود نفسه»).
     */
    @Test
    void organic_rejectsTheProviderReviewingHimself() {
        setMode("OPEN");
        ageAccount(providerUserId, 9);

        assertThrows(BadRequestException.class, () -> reviewsService.createOrganic(
                profile.getId(), null, 5, "my own shop",
                jwtAuthentication(providerSubject, "ROLE_PROVIDER")));
    }

    /**
     * §4.5 daily cap by data: five organic reviews exhaust the window —
     * the sixth answers 429 through the house exception.
     */
    @Test
    void organic_dailyCapAnswersTooManyRequests() {
        setMode("OPEN");
        UUID capped = agedReviewerWithPriorOrganic(5, tag + "-capped-subject");
        try {
            assertThrows(TooManyRequestsException.class, () -> reviewsService.createOrganic(
                    profile.getId(), null, 5, "capped",
                    jwtAuthentication(tag + "-capped-subject", "ROLE_CONSUMER")));
        } finally {
            cleanupOrganicReviewer(capped);
        }
    }

    /**
     * G10 by data: one organic review per (reviewer, provider) — forever;
     * the second answers the explicit 409.
     */
    @Test
    void organic_secondReviewOnTheSameProviderAnswersConflict() {
        setMode("OPEN");
        ageAccount(reviewerId, 9);
        reviewsService.createOrganic(profile.getId(), null, 5, "first words",
                jwtAuthentication(reviewerSubject, "ROLE_CONSUMER"));

        assertThrows(ConflictException.class, () -> reviewsService.createOrganic(
                profile.getId(), null, 4, "second words",
                jwtAuthentication(reviewerSubject, "ROLE_CONSUMER")));
    }

    /**
     * The V72 cross-column check by negative INSERT: a BOOKING review
     * without a booking_id is rejected by the database itself (the badge
     * can never print on a booking-less row).
     */
    @Test
    void originBookingCheck_rejectsABookedRowWithoutABooking() {
        org.springframework.dao.DataIntegrityViolationException thrown =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.dao.DataIntegrityViolationException.class,
                        () -> jdbc.update(
                                "INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, "
                                        + "rating, origin, moderation_status) "
                                        + "VALUES (?, NULL, ?, ?, 5, 'BOOKING', 'PUBLISHED')",
                                UUID.randomUUID(), reviewerId, providerUserId));
        assertThat(thrown.getMostSpecificCause().getMessage())
                .contains("ck_review_origin_booking");
    }

    /**
     * The V72 organic uniqueness by negative SQL: the explicit 409 has the
     * index backstop behind it (the concurrent-insert proof).
     */
    @Test
    void organicUniqueness_backstoppedByThePartialIndex() {
        setMode("OPEN");
        ageAccount(reviewerId, 9);
        reviewsService.createOrganic(profile.getId(), null, 5, "first words",
                jwtAuthentication(reviewerSubject, "ROLE_CONSUMER"));

        org.springframework.dao.DataIntegrityViolationException thrown =
                org.junit.jupiter.api.Assertions.assertThrows(
                        org.springframework.dao.DataIntegrityViolationException.class,
                        () -> jdbc.update(
                                "INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, "
                                        + "rating, origin, moderation_status) "
                                        + "VALUES (?, NULL, ?, ?, 4, 'ORGANIC', 'PUBLISHED')",
                                UUID.randomUUID(), reviewerId, providerUserId));
        assertThat(thrown.getMostSpecificCause().getMessage())
                .contains("uq_review_organic_once");
    }

    /**
     * Seeds an aged account carrying exactly {@code prior} PUBLISHED
     * organic reviews — each against its OWN spare provider (the 1x1
     * uniqueness forbids two on one provider) — so the fresh account's
     * first review on the fixture provider behaves like its Nth.
     */
    private UUID agedReviewerWithPriorOrganic(int prior, String subject) {
        UUID userId = userRepository.save(User.create(
                subject, subject + "@t.com", "Prior Reviewer", UserRole.CONSUMER)).getId();
        ageAccount(userId, 8);
        for (int i = 0; i < prior; i++) {
            UUID spareProvider = makeSpareProvider(i);
            jdbc.update("INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, "
                            + "rating, origin, moderation_status) "
                            + "VALUES (?, NULL, ?, ?, 5, 'ORGANIC', 'PUBLISHED')",
                    UUID.randomUUID(), userId, spareProvider);
        }
        return userId;
    }

    private UUID makeSpareProvider(int salt) {
        String subject = tag + "-spare-" + salt + "-subject";
        UUID userId = userRepository.save(User.create(
                subject, tag + "-spare-" + salt + "@t.com", "Spare", UserRole.PROVIDER)).getId();
        providerProfiles.save(ProviderProfile.create("Spare Business " + salt, "bio", userId));
        return userId;
    }

    private void cleanupOrganicReviewer(UUID userId) {
        jdbc.update("DELETE FROM review_flags WHERE review_id IN "
                + "(SELECT id FROM reviews WHERE reviewer_id = ?)", userId);
        jdbc.update("DELETE FROM reviews WHERE reviewer_id = ?", userId);
        jdbc.update("DELETE FROM provider_profiles WHERE user_id IN "
                + "(SELECT id FROM users WHERE subject LIKE ?)", tag + "-spare-%");
        jdbc.update("DELETE FROM users WHERE id = ?", userId);
        jdbc.update("DELETE FROM users WHERE subject LIKE ?", tag + "-spare-%");
    }
}