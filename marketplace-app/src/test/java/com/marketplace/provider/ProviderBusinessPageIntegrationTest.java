package com.marketplace.provider;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import com.marketplace.identity.User;
import com.marketplace.identity.UserRepository;
import com.marketplace.identity.UserRole;

import org.junit.jupiter.api.Test;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;
import com.marketplace.shared.security.CurrentUserProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * W2 (yelp-level plan §5 — the business page): the acceptance criteria
 * over the REAL chain — HTTP → the resource-server chain → the
 * ownership gate → V88/V89's real schema (the CHECKs, the unique keys,
 * the Envers mirrors) → the public page's composed blocks.
 *
 * <p>The house convention for the caller identity (the community
 * module's own integration tests): a request-scoped {@code jwt()}
 * processor for the security chain with the {@link CurrentUserProvider}
 * mocked per-test — the subject resolution is another module's concern,
 * not this wave's (the L41/L42 convention; every test uses its OWN
 * random user ids, no test-level transaction).
 *
 * <p>Acceptance criteria (the wave's own row): (1) the provider declares
 * hours/services/areas through the self-service surface and the public
 * page carries them — with the JSON-LD {@code openingHours} in the
 * schema.org canonical form; (2) the raw CHECK floors fire loudly
 * (invalid ISO day, backwards window, the split money pair); (3) the
 * unique keys fire (the duplicate area) and the PUT upsert MOVES an
 * existing day instead of colliding; (4) the ownership-verification
 * lifecycle walks UNVERIFIED → PENDING → VERIFIED and the badge state
 * rides the public page; (5) the empty aggregate is honestly omitted
 * («يُبثان فقط عند وجود مراجعات مقبولة» — never zeroed).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "marketplace.provider.seo.public-site-base-url=https://public.example"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
class ProviderBusinessPageIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProviderRepository providerRepository;

    @Autowired
    private ProviderBusinessPageService businessPageService;

    @Autowired
    private UserRepository userRepository;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    // ---- fixtures -----------------------------------------------------------

    /**
     * A live provider profile owned by the given (random) user id — seeded
     * through the REPOSITORY, the house convention every W1 integration
     * test applies (ReviewsTwoWay/ReviewModeration/ReviewsOrganicGate):
     * the service's {@code create} carries {@code @PreAuthorize}, so a
     * direct call outside the HTTP chain has no Authentication in the
     * SecurityContext and fails {@code AuthenticationCredentialsNotFound}
     * (the CI-measured first-run lesson of this very test). The seed is
     * data setup, not the surface under test.
     */
    private ProviderProfile seedProvider(UUID userId) {
        return providerRepository.save(ProviderProfile.create("شركة الاختبار", "وصف", userId));
    }

    /** The house convention: stub the mocked provider with the caller's id. */
    private void asCaller(UUID userId) {
        when(currentUserProvider.getCurrentUserId(any())).thenReturn(userId);
        when(currentUserProvider.isAdmin(any())).thenReturn(false);
    }

    /** The admin caller: the JWT carries the authority, ownership is not checked. */
    private static RequestPostProcessor asAdmin() {
        return jwt().jwt(jwt -> jwt.subject("w2-admin"))
                .authorities(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    private static RequestPostProcessor asUser(UUID userId) {
        return jwt().jwt(jwt -> jwt.subject(userId.toString()))
                .authorities(new SimpleGrantedAuthority("ROLE_PROVIDER"));
    }

    private org.springframework.security.core.Authentication serviceAuth(UUID userId) {
        return new TestingAuthenticationToken(userId.toString(), "n/a", "ROLE_PROVIDER");
    }

    // ---- (1) the declared blocks ride the public page ------------------------

    @Test
    void declaredBlocks_rideThePublicPage() throws Exception {
        UUID userId = UUID.randomUUID();
        ProviderProfile provider = seedProvider(userId);
        asCaller(userId);

        mockMvc.perform(put("/api/v1/providers/{id}/business-hours", provider.getId())
                        .with(asUser(userId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"hours\": [{\"dayOfWeek\": \"SUNDAY\", "
                                + "\"opensAt\": \"09:00\", \"closesAt\": \"17:00\"}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].dayOfWeek").value("SUNDAY"));

        mockMvc.perform(get("/api/v1/providers/{id}/public", provider.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessHours[0].dayOfWeek").value("SUNDAY"))
                .andExpect(jsonPath("$.services").isArray())
                .andExpect(jsonPath("$.serviceAreas").isArray())
                .andExpect(jsonPath("$.verificationState").value("UNVERIFIED"))
                .andExpect(jsonPath("$.jsonLd.@type").value("LocalBusiness"))
                .andExpect(jsonPath("$.jsonLd.openingHours[0]").value("Su 09:00-17:00"))
                .andExpect(jsonPath("$.jsonLd.url")
                        .value("https://public.example/providers/" + provider.getId()));
    }

    // ---- (2) the CHECK floors fire on raw writers ----------------------------

    @Test
    void rawInvalidWeekday_failsTheDbCheck() {
        ProviderProfile provider = seedProvider(UUID.randomUUID());
        assertRejected("insert into business_hours (id, provider_id, day_of_week, opens_at, closes_at) "
                + "values (?, ?, 0, '09:00', '17:00')", provider.getId());
    }

    @Test
    void rawBackwardsWindow_failsTheDbCheck() {
        ProviderProfile provider = seedProvider(UUID.randomUUID());
        assertRejected("insert into business_hours (id, provider_id, day_of_week, opens_at, closes_at) "
                + "values (?, ?, 1, '17:00', '09:00')", provider.getId());
    }

    @Test
    void rawSplitMoneyPair_failsTheDbCheck() {
        ProviderProfile provider = seedProvider(UUID.randomUUID());
        assertRejected("insert into provider_services (id, provider_id, title, price_cents) "
                + "values (?, ?, 'x', 100)", provider.getId());
    }

    /** The loud floor the schema itself enforces — DataIntegrityViolation, never a silent row. */
    private void assertRejected(String sql, UUID providerId) {
        try {
            jdbc.update(sql, UUID.randomUUID(), providerId);
            throw new AssertionError("the CHECK must reject the row: " + sql);
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            assertThat(expected).isNotNull();
        }
    }

    // ---- (3) the unique keys and the upsert contract -------------------------

    /**
     * The CI-measured root of this round's two failures: these tests call
     * the SECURED service directly (outside the HTTP chain, exactly as the
     * class's own seedProvider lesson documents) — the {@code serviceAuth}
     * PARAMETER carries the owner for the ownership check, but plain
     * {@code @PreAuthorize("hasRole('PROVIDER')")} still evaluates against
     * the SecurityContext, which a direct call leaves empty
     * (AuthenticationCredentialsNotFound). {@code @WithMockUser} seeds the
     * context with the PROVIDER role — the ListingPriceCalendarIntegrationTest
     * house pattern for exactly this direct-call shape.
     */
    @Test
    @WithMockUser(roles = "PROVIDER")
    void putHours_upsertsTheExistingDay_theUniqueKeyHolds() {
        UUID userId = UUID.randomUUID();
        ProviderProfile provider = seedProvider(userId);
        asCaller(userId);

        businessPageService.replaceHours(provider.getId(), List.of(
                new ProviderBusinessPageService.HoursEntry(
                        DayOfWeek.FRIDAY, LocalTime.parse("09:00"), LocalTime.parse("13:00"))),
                serviceAuth(userId));
        // The second declare of the same day MOVES the window (the upsert
        // contract) — the unique key never collides.
        businessPageService.replaceHours(provider.getId(), List.of(
                new ProviderBusinessPageService.HoursEntry(
                        DayOfWeek.FRIDAY, LocalTime.parse("10:00"), LocalTime.parse("14:00"))),
                serviceAuth(userId));

        List<BusinessHour> hours = businessPageService.getHours(provider.getId());
        assertThat(hours).hasSize(1);
        assertThat(hours.get(0).getOpensAt()).isEqualTo(LocalTime.parse("10:00"));
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void duplicateArea_failsLoudly() {
        UUID userId = UUID.randomUUID();
        ProviderProfile provider = seedProvider(userId);
        asCaller(userId);
        UUID locationId = jdbc.queryForObject("select id from geo_locations limit 1", UUID.class);

        businessPageService.addArea(provider.getId(), locationId, serviceAuth(userId));
        try {
            businessPageService.addArea(provider.getId(), locationId, serviceAuth(userId));
            throw new AssertionError("the duplicate area must fail");
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            assertThat(expected).isNotNull();
        }
    }

    // ---- (4) the verification lifecycle ---------------------------------------

    @Test
    void verificationLifecycle_walksTheFourStates() throws Exception {
        UUID userId = UUID.randomUUID();
        ProviderProfile provider = seedProvider(userId);
        asCaller(userId);

        // The owner's submission (the self-service path over HTTP).
        mockMvc.perform(post("/api/v1/providers/{id}/verification", provider.getId())
                        .with(asUser(userId)))
                .andExpect(status().isOk());

        // The administrative confirmation rides the admin chain.
        mockMvc.perform(post("/api/v1/admin/providers/{id}/verification/confirm",
                        provider.getId()).with(asAdmin()))
                .andExpect(status().isOk());

        // The badge state rides the public page.
        mockMvc.perform(get("/api/v1/providers/{id}/public", provider.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verificationState").value("VERIFIED"));
    }

    // ---- (5) the empty aggregate is honestly omitted ---------------------------

    @Test
    void noReviewsYet_jsonLdOmitsTheAggregate() throws Exception {
        ProviderProfile provider = seedProvider(UUID.randomUUID());

        String body = mockMvc.perform(get("/api/v1/providers/{id}/public", provider.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonLd.@type").value("LocalBusiness"))
                .andReturn().getResponse().getContentAsString();

        // The plan's literal rule: the aggregate rides only when published
        // reviews exist — never zeroed (a fresh provider has none).
        assertThat(body).doesNotContain("aggregateRating");
    }

    // ---- (6) the JSON-LD sample follows the aggregate's population -------------

    /**
     * W2 (greptile round 2, adopted from the root): the structured sample
     * rides the population the aggregate beside it describes — never a
     * filter of the caller's requested page. The discriminating fixture:
     * eleven NEWER organic reviews push the ONE older booking review off
     * the default reviews page (size 10), so the visible page 0 carries no
     * booking row at all — while the VERIFIED_ONLY aggregate reports
     * exactly that booking review. The old page-filter shape left the
     * sample EMPTY here (markup disagreeing with its own aggregate); the
     * population read keeps the booking review's evidence in the
     * structured data.
     */
    @Test
    void jsonLdSample_followsTheAggregatesPopulation_notTheRequestedPage() throws Exception {
        String tag = "w2-jsonld-sample-" + UUID.randomUUID();
        UUID providerUserId = userRepository.save(User.create(
                tag + "-provider-subject", tag + "-provider@t.com", "Sample Provider", UserRole.PROVIDER)).getId();
        ProviderProfile provider = seedProvider(providerUserId);

        // The FK-honest booking seed (the organic-gate suite's own shape —
        // this suite boots on REAL Flyway, so V6's reviews_booking_id_fkey
        // is live): one ACTIVE listing, one COMPLETED booking.
        jdbc.update("INSERT INTO provider_listings (id, provider_id, title, description, category, "
                        + "price_cents, currency, status) VALUES (?, ?, ?, ?, 'home', 100_00, 'SAR', 'ACTIVE')",
                UUID.randomUUID(), providerUserId, tag + " fixture", "fk seed");
        UUID bookingId = UUID.randomUUID();
        UUID bookingReviewer = userRepository.save(User.create(
                tag + "-booking-reviewer", tag + "-booking@t.com", "Booking Reviewer", UserRole.CONSUMER)).getId();
        jdbc.update("INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, "
                        + "price_cents, currency, notes) VALUES (?, ?, ?, "
                        + "(SELECT id FROM provider_listings WHERE provider_id = ? LIMIT 1), "
                        + "'COMPLETED', 100_00, 'SAR', NULL)",
                bookingId, bookingReviewer, providerUserId, providerUserId);

        // The aggregate's whole population: ONE booking review, the OLDEST
        // row on the provider (rating 5 — the value the structured
        // aggregate must carry).
        jdbc.update("INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, rating, comment, "
                        + "origin, moderation_status, created_at) "
                        + "VALUES (?, ?, ?, ?, 5, ?, 'BOOKING', 'PUBLISHED', ?)",
                UUID.randomUUID(), bookingId, bookingReviewer, providerUserId,
                "التقييم الموثق للتجربة",
                java.sql.Timestamp.from(Instant.now().minus(java.time.Duration.ofMinutes(90))));

        // Eleven NEWER organic reviews from eleven distinct reviewers (the
        // V85 1x1 uniqueness) — enough to fill the default reviews page
        // (size 10) and push the booking review off it entirely.
        for (int i = 0; i < 11; i++) {
            UUID reviewer = userRepository.save(User.create(
                    tag + "-organic-" + i, tag + "-organic-" + i + "@t.com",
                    "Organic " + i, UserRole.CONSUMER)).getId();
            jdbc.update("INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, rating, "
                            + "origin, moderation_status, created_at) "
                            + "VALUES (?, NULL, ?, ?, 2, 'ORGANIC', 'PUBLISHED', ?)",
                    UUID.randomUUID(), reviewer, providerUserId,
                    java.sql.Timestamp.from(Instant.now().minus(java.time.Duration.ofMinutes(i + 1))));
        }

        // The default page request (reviewsPage 0 / reviewsSize 10).
        mockMvc.perform(get("/api/v1/providers/{id}/public", provider.getId()))
                .andExpect(status().isOk())
                // The bug's precondition, measured: the visible page 0 is
                // organic-only — the booking row sits beyond it.
                .andExpect(jsonPath("$.reviews.totalElements").value(12))
                .andExpect(jsonPath("$.reviews.pageNumber").value(0))
                .andExpect(jsonPath("$.reviews.content.length()").value(10))
                .andExpect(jsonPath("$.reviews.content[0].origin").value("ORGANIC"))
                // The aggregate describes the booking population (the
                // VERIFIED_ONLY law): one review, rating five.
                .andExpect(jsonPath("$.jsonLd.aggregateRating.reviewCount").value(1))
                .andExpect(jsonPath("$.jsonLd.aggregateRating.ratingValue").value(5.0))
                // The root fix's own claim: the sample carries the booking
                // review's evidence even though the requested page holds
                // none of its rows.
                .andExpect(jsonPath("$.jsonLd.review.length()").value(1))
                .andExpect(jsonPath("$.jsonLd.review[0].reviewBody").value("التقييم الموثق للتجربة"))
                .andExpect(jsonPath("$.jsonLd.review[0].reviewRating.ratingValue").value(5));
    }
}
