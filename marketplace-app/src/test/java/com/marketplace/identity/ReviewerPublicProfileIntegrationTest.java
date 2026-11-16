package com.marketplace.identity;

import test.config.IntegrationContainers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W4 (yelp-level plan §5 — G28/G29, the reviewer identity) — the public
 * reviewer page's acceptance criteria over the REAL chain: the anonymous
 * GET (the SecurityConfig precise-wildcard line, the L36 mirror) → the
 * identity module's page → the {@code ReviewerStatsPort} seam → the
 * reviews module's origin-split published counters and the cumulative
 * helpful-vote total (V85's provenance + V87's votes) → the derived
 * badges (G29 — recomputed facts, never stored).
 *
 * <p>The click chain the plan names ("نقرة من مراجعة إلى صفحة المراجع
 * تكشف نشاطه"): the SAME users.id key carries from the review row's
 * {@code reviewerId} block (asserted here through the public reviewer
 * activity surface {@code GET /api/v1/reviews/reviewer/{id}}) to the
 * page's own key — no id-space seam.
 *
 * <p>Seeding follows the W1 reviews integration conventions (raw SQL +
 * ON CONFLICT DO NOTHING; the BOOKING-origin row rides a real COMPLETED
 * booking because {@code ck_review_origin_booking} pins it there, the
 * ORGANIC row rides the NULL booking the same constraint pins it to).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ReviewerPublicProfileIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches SavedSearchIntegrationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private UUID reviewerId;
    private UUID providerUserId;
    private UUID bookingReviewId;
    private UUID organicReviewId;

    @BeforeEach
    void seed() {
        // The pristine reviewer world (the SavedSearchIntegrationTest root
        // fix): one shared Spring context across this class's tests, and
        // the page counts THIS reviewer's rows only — but every seeded
        // fact is per-test random anyway, so a wipe merely keeps the
        // shared tables bounded. Raw SQL writes no Envers revisions, so
        // the deletes are order-free and audit-silent.
        jdbc.update("DELETE FROM review_votes");
        jdbc.update("DELETE FROM reviews");

        reviewerId = UUID.randomUUID();
        providerUserId = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, 'Sara the Reviewer', 'CONSUMER')
                ON CONFLICT (id) DO NOTHING
                """, reviewerId, "w4-reviewer-" + reviewerId, "w4-reviewer-" + reviewerId + "@example.com");
        jdbc.update("""
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, 'W4 Provider', 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """, providerUserId, "w4-provider-" + providerUserId, "w4-provider-" + providerUserId + "@example.com");
    }

    /** The W1 seeding shape: a COMPLETED booking + the two-origin pair. */
    private void seedPublishedReviews(long helpfulVotes) {
        UUID listingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status)
                VALUES (?, ?, 'W4 listing', 'w4 reviewer page test', 'APARTMENT', 10000, 'SAR', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """, listingId, providerUserId);

        // The verified review rides a real booking (ck_review_origin_booking)
        UUID bookingId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, price_cents, currency, notes)
                VALUES (?, ?, ?, ?, 'COMPLETED', 100_00, 'SAR', NULL)
                ON CONFLICT (id) DO NOTHING
                """, bookingId, reviewerId, providerUserId, listingId);
        bookingReviewId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, rating, origin, moderation_status)
                VALUES (?, ?, ?, ?, 5, 'BOOKING', 'PUBLISHED')
                ON CONFLICT (id) DO NOTHING
                """, bookingReviewId, bookingId, reviewerId, providerUserId);

        // The organic review rides the NULL booking the same CHECK pins it to
        organicReviewId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, rating, origin, moderation_status)
                VALUES (?, NULL, ?, ?, 4, 'ORGANIC', 'PUBLISHED')
                ON CONFLICT (id) DO NOTHING
                """, organicReviewId, reviewerId, providerUserId);

        // The cumulative helpful signal — W1's review_votes (voter ids are
        // plain UUIDs in the users.id space, no FK by design; distinct
        // voters for the partial-unique pair)
        for (int i = 0; i < helpfulVotes; i++) {
            jdbc.update("""
                    INSERT INTO review_votes (id, review_id, voter_id)
                    VALUES (?, ?, ?)
                    ON CONFLICT DO NOTHING
                    """, UUID.randomUUID(), bookingReviewId, UUID.randomUUID());
        }
    }

    private static MockHttpServletRequestBuilder anonymousGet(String url, Object... vars) {
        return get(url, vars);
    }

    @Test
    void theAnonymousPageCarriesThePlanFieldListWithBothBadges() throws Exception {
        // G28 (the plan's own field list: "اسم، طابع انضمام، عدّاد مراجعات
        // موثقة/عاملة، أوسمته") + G29 (both badge families over the two
        // measured facts) — through the REAL security chain, anonymous.
        seedPublishedReviews(10L);

        mockMvc.perform(anonymousGet("/api/v1/users/{id}/public", reviewerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewerId").value(reviewerId.toString()))
                .andExpect(jsonPath("$.displayName").value("Sara the Reviewer"))
                .andExpect(jsonPath("$.joinedAt").isNotEmpty())
                .andExpect(jsonPath("$.verifiedReviewCount").value(1))
                .andExpect(jsonPath("$.organicReviewCount").value(1))
                .andExpect(jsonPath("$.helpfulVoteCount").value(10))
                .andExpect(jsonPath("$.badges.length()").value(2))
                .andExpect(jsonPath("$.badges[0]").value("VERIFIED_REVIEWER"))
                .andExpect(jsonPath("$.badges[1]").value("HELPFUL_REVIEWER"));
    }

    @Test
    void theClickTargetTravelsWithTheReviewRowsAndRevealsTheActivity() throws Exception {
        // The plan's acceptance: "نقرة من مراجعة إلى صفحة المراجع تكشف
        // نشاطه" — the public reviewer ACTIVITY surface (W1's
        // GET /api/v1/reviews/reviewer/{id}) now carries the row's own
        // click target (reviewerId), and the page answers under the SAME
        // users.id key.
        seedPublishedReviews(2L);

        String activity = mockMvc.perform(anonymousGet("/api/v1/reviews/reviewer/{reviewerId}", reviewerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2))
                .andReturn().getResponse().getContentAsString();
        var rows = tools.jackson.databind.json.JsonMapper.builder().build()
                .readTree(activity).get("content");
        int carryingTheTarget = 0;
        for (var row : rows) {
            if (reviewerId.toString().equals(row.get("reviewerId").asText())) {
                carryingTheTarget++;
            }
        }
        org.assertj.core.api.Assertions.assertThat(carryingTheTarget).isEqualTo(2);
        // the composed blocks the rows already carried (W1) survive W4's
        // field addition untouched
        org.assertj.core.api.Assertions.assertThat(rows.get(0).get("reviewerName").asText())
                .isEqualTo("Sara the Reviewer");
        org.assertj.core.api.Assertions.assertThat(rows.get(0).get("reviewerReviewCount").asLong())
                .isEqualTo(2L);

        // and the page itself answers under the same key
        mockMvc.perform(anonymousGet("/api/v1/users/{id}/public", reviewerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reviewerId").value(reviewerId.toString()));
    }

    @Test
    void aNeverReviewerIsTheHonestZeroProfileAndTheUnknownIdIs404() throws Exception {
        // A live account with no published reviews is the "not yet a
        // reviewer" profile — 200 with all-zero counters and no badges;
        // an unknown id is the honest 404 (the users row is the page's
        // existence authority).
        mockMvc.perform(anonymousGet("/api/v1/users/{id}/public", reviewerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Sara the Reviewer"))
                .andExpect(jsonPath("$.verifiedReviewCount").value(0))
                .andExpect(jsonPath("$.organicReviewCount").value(0))
                .andExpect(jsonPath("$.helpfulVoteCount").value(0))
                .andExpect(jsonPath("$.badges.length()").value(0));

        mockMvc.perform(anonymousGet("/api/v1/users/{id}/public", UUID.randomUUID()))
                .andExpect(status().isNotFound());
    }

    @Test
    void aClosedAccountAnswersTheFormerMemberLabel() throws Exception {
        // I7/W1: the page's name rule honours pseudonymized_at — the same
        // word every public surface answers for the same account, never
        // the stored profile columns.
        seedPublishedReviews(0L);
        jdbc.update("UPDATE users SET pseudonymized_at = now() WHERE id = ?", reviewerId);

        mockMvc.perform(anonymousGet("/api/v1/users/{id}/public", reviewerId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Former member"))
                // the measured facts stay measurable — the community's
                // endorsement is history, not an erasure (b-5)
                .andExpect(jsonPath("$.verifiedReviewCount").value(1));
    }

    @Test
    void thePlainMeOwnerSurfaceStaysAuthenticated() throws Exception {
        // The permitAll line is PRECISE (the L36 mirror): only the
        // composite /users/{id}/public page opened — the plain /users/me
        // owner surface keeps its authenticated contract, anonymous 401
        // through the real resource-server chain.
        mockMvc.perform(anonymousGet("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }
}
