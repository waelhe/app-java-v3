package com.marketplace.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I7 Phase 3 (account-pseudonymization-plan §2 gate b-3 + §7 Phase 3 row —
 * the free-text purge): the integration guard on real PostgreSQL + real
 * Redis, through real HTTP. The end-state the plan's purge option defines:
 * every text the (already-pseudonymized) subject authored dies on the base
 * tables AND the Envers mirrors; the counterparty's texts on the very same
 * shared rows survive (Art. 17(3)(b) / 20(4) — the plan's §4); the rows
 * themselves keep their structure and non-text columns; a re-run purges
 * exactly zero rows (idempotence, the port contract).
 *
 * <p><b>Why real Flyway (the V33 lesson, the Phase 1 guard's own
 * rationale):</b> the schema under test is the production schema —
 * {@code spring.flyway.enabled=true} + {@code ddl-auto=none} — so the NOT
 * NULL text columns (V7 {@code messages.content}, V14
 * {@code provider_profiles.display_name}, V18
 * {@code notifications.message}, V20 {@code disputes.reason}) and the
 * Envers mirrors (V24 + V37/V45 alterations) are the real constraints the
 * NULL-vs-marker convention is measured against.
 *
 * <p><b>Why seeded Envers rows:</b> the purge's own statements are the only
 * writers of the mirrors' purged state (no entity maps them), so the guard
 * seeds the mirror rows directly (the Phase 1 raw-SQL seed pattern) and
 * asserts they purge under the same predicates — a purge that left the
 * mirrors intact would be cosmetic (the original text survives in the
 * audit trail, a silent debt).
 *
 * <p><b>Why the PKCE login gate (the L23 pattern):</b> the purge surface is
 * the administrative one — the guard proves the real authorization path:
 * a consumer token is denied (403), a live account is rejected (409 — the
 * purge completes an erasure flow, never operating on a live account's
 * active content), and the happy path runs with a real ADMIN bearer.
 *
 * <p><b>The measured live defect this guard documents (not patched by
 * b-3):</b> {@code ReviewsService.reply}/{@code createReverse} compare the
 * stored {@code users.id} against the resolved
 * {@code provider_profiles.id} (the A1 cross-space mismatch at two sites
 * A1's sweep did not list) — so reply rows cannot be written through the
 * API today. The reply fixtures here are seeded directly (the data-level
 * truth the purge operates on) and the reply predicate encodes the reply
 * gate's own authorship model ({@code provider_id}'s user authors the
 * reply) for when that defect is fixed by its own surgical PR.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The production-shaped schema: real Flyway (V1..V46), no ddl-auto.
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The production cache type (the I5 lesson — force the shared Redis).
        "spring.cache.type=redis",
        "spring.cache.redis.time-to-live=1h",
        // CI connection budget (the I5/L14 harness rationale).
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",
        // The login gate's client fixtures (the L23 gate properties).
        "marketplace.security.oauth2.client.client-id=marketplace-web-client",
        "marketplace.security.oauth2.client.secret=it-app-secret",
        "marketplace.security.oauth2.client.redirect-uris=http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client",
        // The b-2(b) secret channel for THIS guard — a test value, never a
        // production secret (secrets-policy §1: real secrets never touch Git).
        "marketplace.security.pseudonymization.subject-hmac-key=it-pseudonymization-key",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthoredContentPurgeIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Value("${local.server.port}")
    private int portA;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final String PASSWORD = "it-purge-password";
    private static final String ADMIN_USERNAME = "it-purge-admin-user";
    private static final String CLIENT_ID = "it-login-gate-client";
    private static final String CLIENT_SECRET = "it-login-gate-secret";
    private static final String REDIRECT_URI = "https://login-gate.test.example/callback";
    private static final String LOGIN_PATH = "/login";
    private static final String AUTHORIZE_PATH = "/oauth2/authorize";
    private static final String TOKEN_PATH = "/oauth2/token";
    private static final String PURGED_MARKER = "[purged]";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    /**
     * The acceptance sequence (the plan's purge option, one test — the
     * phases share the state the sequence produces): pseudonymize &rarr;
     * purge &rarr; every authored text dies on base + mirror &rarr; the
     * counterparty's texts on the same rows survive &rarr; the rows keep
     * their structure &rarr; the re-run purges zero.
     */
    @Test
    void fullSequence_authoredTextsDieCounterpartyStaysRowsStay() throws Exception {
        String target = "it-purge-target-user";
        registerUser(target, "USER");
        GateResult targetGate = loginGate(target, PASSWORD);
        assertThat(targetGate.accessToken()).isNotBlank();
        UUID subjectId = syncProjectionIdViaMe(targetGate.accessToken());

        PurgeFixture f = seedAllModules(subjectId);

        GateResult admin = adminGate();

        // The purge's own guard: it completes an erasure flow — Phase 1's
        // surface must run first (the plan's §5-أ, the real HTTP path).
        HttpResponse<String> pseudonymize = postJsonWithBearer(
                "/api/v1/admin/users/" + subjectId + "/pseudonymize", admin.accessToken(),
                "{\"reason\":\"data-subject erasure request\"}");
        assertThat(pseudonymize.statusCode())
                .as("pseudonymize call: %s", body(pseudonymize)).isEqualTo(200);

        // The action — real HTTP through the administrative surface.
        HttpResponse<String> purge = postJsonWithBearer(
                "/api/v1/admin/users/" + subjectId + "/purge-content", admin.accessToken(),
                "{\"reason\":\"data-subject erasure request\"}");
        assertThat(purge.statusCode())
                .as("purge call: %s", body(purge)).isEqualTo(200);
        JsonNode purgeBody = objectMapper.readTree(purge.body());
        assertThat(purgeBody.path("purgedRows").asInt())
                .as("the exact fan-out count: 2 bookings + 4 messages + 4 review "
                        + "comments + 4 review replies + 4 profile fields + 2 disputes "
                        + "+ 2 notifications (base + mirror each)")
                .isEqualTo(22);

        // -- bookings (V3: notes is nullable -> NULL) ---------------------
        assertThat(jdbcTemplate.queryForObject(
                "SELECT notes FROM bookings WHERE id = ?", String.class, f.subjectBookingId()))
                .as("the subject's booking notes die").isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT notes FROM bookings WHERE id = ?", String.class, f.counterpartyBookingId()))
                .as("the counterparty's booking notes survive")
                .isEqualTo("counterparty keeps his notes");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, f.subjectBookingId()))
                .as("the shared row keeps its structure").isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT notes FROM bookings_aud WHERE id = ? AND rev = ?", f.subjectBookingId(), f.revBase())
                .get("notes")).as("the mirror dies too").isNull();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT notes FROM bookings_aud WHERE id = ? AND rev = ?", f.counterpartyBookingId(), f.revBase())
                .get("notes")).as("the counterparty's mirror survives")
                .isEqualTo("counterparty keeps his notes");

        // -- messages (V7: content NOT NULL -> the marker) ----------------
        assertThat(jdbcTemplate.queryForObject(
                "SELECT min(content) FROM messages WHERE sender_id = ?", String.class, subjectId))
                .as("every message the subject sent dies (2 rows)")
                .isEqualTo(PURGED_MARKER);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM messages WHERE sender_id = ? AND content <> ?",
                Integer.class, subjectId, PURGED_MARKER)).isZero();
        assertThat(jdbcTemplate.queryForMap(
                "SELECT content FROM messages WHERE id = ?", f.counterpartyMessageId()).get("content"))
                .as("the counterparty's message in the SAME conversation survives")
                .isEqualTo("counterparty keeps his message");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT participant_a, participant_b FROM conversations WHERE id = ?",
                f.conversationId()))
                .as("the conversation row keeps both participants (reference integrity)")
                .containsEntry("participant_a", subjectId)
                .containsEntry("participant_b", f.counterpartyId());
        assertThat(jdbcTemplate.queryForMap(
                "SELECT content FROM messages_aud WHERE id = ? AND rev = ?",
                f.subjectMessageId(), f.revBase()).get("content"))
                .as("the subject's message mirror dies").isEqualTo(PURGED_MARKER);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT content FROM messages_aud WHERE id = ? AND rev = ?",
                f.counterpartyMessageId(), f.revBase()).get("content"))
                .as("the counterparty's message mirror survives")
                .isEqualTo("counterparty keeps his message");

        // -- reviews (V6/V37/V45: comment/reply nullable -> NULL) --------
        // Review A (forward, the subject authored the review): his comment
        // dies; the counterparty's reply on that review survives.
        assertThat(jdbcTemplate.queryForMap(
                "SELECT comment, reply, rating FROM reviews WHERE id = ?", f.subjectReviewId()))
                .as("the subject's review comment dies; the counterparty's reply "
                        + "on the same row survives; the rating stays")
                .containsEntry("comment", null)
                .containsEntry("reply", "counterparty keeps his reply")
                .containsEntry("rating", 5);
        // Review B (forward, the subject is the reviewed provider): the
        // counterparty's review comment survives; the subject's reply dies.
        assertThat(jdbcTemplate.queryForMap(
                "SELECT comment, reply FROM reviews WHERE id = ?", f.subjectAsProviderReviewId()))
                .as("the counterparty's review of the subject survives; the "
                        + "subject's own reply dies")
                .containsEntry("comment", "counterparty keeps his review")
                .containsEntry("reply", null);
        // Review C (reverse, the subject authored review AND reply): both die.
        assertThat(jdbcTemplate.queryForMap(
                "SELECT comment, reply, reviewee_id FROM reviews WHERE id = ?", f.subjectReverseReviewId()))
                .as("the subject's reverse review: his comment and reply both die; "
                        + "the reviewee reference stays")
                .containsEntry("comment", null)
                .containsEntry("reply", null)
                .containsEntry("reviewee_id", f.counterpartyId());
        // Mirrors: subject-authored texts die, counterparty's survive.
        assertThat(jdbcTemplate.queryForMap(
                "SELECT comment, reply FROM reviews_aud WHERE id = ? AND rev = ?",
                f.subjectReviewId(), f.revBase()))
                .as("mirror: the subject's comment dies, the counterparty's reply "
                        + "on the same row survives")
                .containsEntry("comment", null)
                .containsEntry("reply", "counterparty keeps his reply");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT comment, reply FROM reviews_aud WHERE id = ? AND rev = ?",
                f.subjectAsProviderReviewId(), f.revBase()))
                .as("mirror: the counterparty's review survives, the subject's "
                        + "reply dies")
                .containsEntry("comment", "counterparty keeps his review")
                .containsEntry("reply", null);

        // -- provider_profiles (V14/V22: bio nullable -> NULL;
        //    display_name NOT NULL -> the marker) -------------------------
        assertThat(jdbcTemplate.queryForMap(
                "SELECT display_name, bio, status FROM provider_profiles WHERE id = ?",
                f.subjectProfileId()))
                .as("the subject's persona: name -> marker, bio -> NULL, status stays")
                .containsEntry("display_name", PURGED_MARKER)
                .containsEntry("bio", null)
                .containsEntry("status", "ACTIVE");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT display_name, bio FROM provider_profiles WHERE id = ?",
                f.counterpartyProfileId()))
                .as("the counterparty's persona survives untouched")
                .containsEntry("display_name", "Counterparty Stays")
                .containsEntry("bio", "counterparty keeps his bio");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT display_name, bio FROM provider_profiles_aud WHERE id = ? AND rev = ?",
                f.subjectProfileId(), f.revBase()))
                .as("the subject's persona mirror dies")
                .containsEntry("display_name", PURGED_MARKER)
                .containsEntry("bio", null);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT display_name FROM provider_profiles_aud WHERE id = ? AND rev = ?",
                f.counterpartyProfileId(), f.revBase()).get("display_name"))
                .as("the counterparty's persona mirror survives")
                .isEqualTo("Counterparty Stays");

        // -- disputes (V20: reason NOT NULL -> the marker) ----------------
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reason FROM disputes WHERE id = ?", String.class, f.subjectDisputeId()))
                .as("the subject's dispute reason dies").isEqualTo(PURGED_MARKER);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT reason, status FROM disputes WHERE id = ?", f.counterpartyDisputeId()))
                .as("the counterparty's dispute survives")
                .containsEntry("reason", "counterparty keeps his reason")
                .containsEntry("status", "OPEN");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT reason FROM disputes_aud WHERE id = ? AND rev = ?",
                f.subjectDisputeId(), f.revBase()).get("reason"))
                .as("the subject's dispute mirror dies").isEqualTo(PURGED_MARKER);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT reason FROM disputes_aud WHERE id = ? AND rev = ?",
                f.counterpartyDisputeId(), f.revBase()).get("reason"))
                .as("the counterparty's dispute mirror survives")
                .isEqualTo("counterparty keeps his reason");

        // -- notifications (V18: message NOT NULL -> the marker) ----------
        assertThat(jdbcTemplate.queryForObject(
                "SELECT message FROM notifications WHERE id = ?", String.class, f.subjectNotificationId()))
                .as("the subject's notification feed dies").isEqualTo(PURGED_MARKER);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT message, is_read FROM notifications WHERE id = ?",
                f.counterpartyNotificationId()))
                .as("the counterparty's feed survives; the delivery state is "
                        + "operational data and stays")
                .containsEntry("message", "counterparty keeps his notification")
                .containsEntry("is_read", false);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT message FROM notifications_aud WHERE id = ? AND rev = ?",
                f.subjectNotificationId(), f.revBase()).get("message"))
                .as("the subject's notification mirror dies").isEqualTo(PURGED_MARKER);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT message FROM notifications_aud WHERE id = ? AND rev = ?",
                f.counterpartyNotificationId(), f.revBase()).get("message"))
                .as("the counterparty's notification mirror survives")
                .isEqualTo("counterparty keeps his notification");

        // Idempotence (the port contract): the re-run purges exactly zero.
        HttpResponse<String> again = postJsonWithBearer(
                "/api/v1/admin/users/" + subjectId + "/purge-content", admin.accessToken(),
                "{\"reason\":\"re-run\"}");
        assertThat(again.statusCode()).as("idempotent re-run: %s", body(again)).isEqualTo(200);
        assertThat(objectMapper.readTree(again.body()).path("purgedRows").asInt())
                .as("the re-run answers zero — every predicate matches nothing")
                .isZero();
    }

    /**
     * The guard's negative: a LIVE account is rejected with 409 before any
     * statement runs (the purge completes an erasure flow — pseudonymize
     * first). Also the unknown-id mapping (404) on the same surface.
     */
    @Test
    void liveAccountIsRejectedBeforeAnyStatementRuns() throws Exception {
        String live = "it-purge-live-user";
        registerUser(live, "USER");
        GateResult liveGate = loginGate(live, PASSWORD);
        UUID liveId = syncProjectionIdViaMe(liveGate.accessToken());
        GateResult admin = adminGate();

        HttpResponse<String> attempt = postJsonWithBearer(
                "/api/v1/admin/users/" + liveId + "/purge-content", admin.accessToken(),
                "{\"reason\":\"must be rejected\"}");
        assertThat(attempt.statusCode())
                .as("live account attempt: %s", body(attempt)).isEqualTo(409);
        assertThat(attempt.body()).contains("pseudonymized");

        HttpResponse<String> unknown = postJsonWithBearer(
                "/api/v1/admin/users/" + UUID.randomUUID() + "/purge-content",
                admin.accessToken(), "{\"reason\":\"unknown id\"}");
        assertThat(unknown.statusCode())
                .as("unknown id attempt: %s", body(unknown)).isEqualTo(404);
    }

    /**
     * The guard's negative: a non-admin token cannot purge anyone (403).
     */
    @Test
    void consumerTokenCannotPurge() throws Exception {
        String consumer = "it-purge-consumer-user";
        registerUser(consumer, "USER");
        GateResult consumerGate = loginGate(consumer, PASSWORD);
        UUID someId = syncProjectionIdViaMe(consumerGate.accessToken());

        HttpResponse<String> attempt = postJsonWithBearer(
                "/api/v1/admin/users/" + someId + "/purge-content", consumerGate.accessToken(),
                "{\"reason\":\"must be rejected\"}");
        assertThat(attempt.statusCode())
                .as("consumer attempt: %s", body(attempt)).isEqualTo(403);
    }

    // -- fixtures & helpers (the Phase 1 guard's shapes, verbatim) -------

    /**
     * Seeds the subject's authored texts across all six owning modules AND
     * the counterparty's texts on the same shared rows — the isolation
     * proof needs both sides present. Envers mirror rows are seeded at one
     * shared revision {@code revBase} (distinct per test run).
     */
    private PurgeFixture seedAllModules(UUID subjectId) {
        UUID counterpartyId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """,
                counterpartyId, "it-purge-counterparty@example.com",
                "it-purge-counterparty@example.com", "I7 Purge Counterparty");

        int revBase = 9_100_000 + (int) (System.currentTimeMillis() % 100_000);
        seedRevinfo(revBase);

        // Bookings (V3): the subject's requested booking + the
        // counterparty's own booking — both reference one listing owned by
        // the counterparty (V2 FK parents).
        UUID listingId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status)
                VALUES (?, ?, ?, ?, ?, ?, 'SAR', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """,
                listingId, counterpartyId, "I7 Purge Listing",
                "purge guard listing", "home", 100_00L);
        // The subject-as-provider's own listing (V2: provider_id -> users.id)
        // — the FK parent of the booking that carries his forward+reverse
        // review pair below (the faithful production shape: his listing,
        // booked by the counterparty). Provider listings are OUTSIDE the
        // purge's six-adapter scope (the plan's letter — the §9 decision
        // row), so this row contributes zero to every purge count.
        UUID subjectListingId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status)
                VALUES (?, ?, ?, ?, ?, ?, 'SAR', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """,
                subjectListingId, subjectId, "I7 Purge Subject Listing",
                "purge guard subject listing", "home", 100_00L);
        UUID subjectBookingId = UUID.randomUUID();
        UUID counterpartyBookingId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, price_cents, currency, notes)
                VALUES (?, ?, ?, ?, 'PENDING', 100_00, 'SAR', ?)
                ON CONFLICT (id) DO NOTHING
                """,
                subjectBookingId, subjectId, counterpartyId, listingId, "please bring towels");
        jdbcTemplate.update(
                """
                INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, price_cents, currency, notes)
                VALUES (?, ?, ?, ?, 'CONFIRMED', 100_00, 'SAR', ?)
                ON CONFLICT (id) DO NOTHING
                """,
                counterpartyBookingId, counterpartyId, counterpartyId, listingId,
                "counterparty keeps his notes");
        seedBookingsAud(subjectBookingId, revBase, subjectId, "please bring towels");
        seedBookingsAud(counterpartyBookingId, revBase, counterpartyId, "counterparty keeps his notes");

        // Messaging (V7): one conversation, the subject's two messages and
        // the counterparty's one — the isolation proof on the shared thread.
        UUID conversationId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO conversations (id, participant_a, participant_b)
                VALUES (?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                conversationId, subjectId, counterpartyId);
        UUID subjectMessageId = UUID.randomUUID();
        UUID counterpartyMessageId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO messages (id, conversation_id, sender_id, content)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                subjectMessageId, conversationId, subjectId, "hello, is the pool open?");
        jdbcTemplate.update(
                """
                INSERT INTO messages (id, conversation_id, sender_id, content)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                UUID.randomUUID(), conversationId, subjectId, "see you at check-in");
        jdbcTemplate.update(
                """
                INSERT INTO messages (id, conversation_id, sender_id, content)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                counterpartyMessageId, conversationId, counterpartyId,
                "counterparty keeps his message");
        seedMessagesAud(subjectMessageId, revBase, subjectId, "hello, is the pool open?");
        seedMessagesAud(counterpartyMessageId, revBase, counterpartyId,
                "counterparty keeps his message");

        // The review-carrying bookings — V6's measured fact: reviews.booking_id
        // is NOT NULL REFERENCES bookings(id) (an FK the seed must satisfy);
        // the write path (ReviewsService.create/createReverse) reviews
        // COMPLETED bookings only, and V45's uq_reviews_booking_direction_active
        // admits one ACTIVE review per (booking, direction) — so A rides its
        // own booking (the subject as consumer, completed), while the forward
        // B + reverse C legally share one booking on the subject's listing.
        // Both carry NULL notes: they satisfy the FK, contribute no authored
        // text, and match no purge predicate (notes IS NOT NULL) — the exact
        // 22-row count stays exact.
        UUID reviewBookingA = UUID.randomUUID();
        UUID reviewBookingBC = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, price_cents, currency, notes)
                VALUES (?, ?, ?, ?, 'COMPLETED', 100_00, 'SAR', NULL)
                ON CONFLICT (id) DO NOTHING
                """,
                reviewBookingA, subjectId, counterpartyId, listingId);
        jdbcTemplate.update(
                """
                INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, price_cents, currency, notes)
                VALUES (?, ?, ?, ?, 'COMPLETED', 100_00, 'SAR', NULL)
                ON CONFLICT (id) DO NOTHING
                """,
                reviewBookingBC, counterpartyId, subjectId, subjectListingId);

        // Reviews (V6/V37/V45) — three rows pin every predicate:
        UUID subjectReviewId = UUID.randomUUID();          // A: he authored the review
        UUID subjectAsProviderReviewId = UUID.randomUUID(); // B: he is the reviewed provider
        UUID subjectReverseReviewId = UUID.randomUUID();   // C: reverse, he authored both
        jdbcTemplate.update(
                """
                INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, rating, comment, reply)
                VALUES (?, ?, ?, ?, 5, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                subjectReviewId, reviewBookingA, subjectId, counterpartyId,
                "the subject's review comment", "counterparty keeps his reply");
        jdbcTemplate.update(
                """
                INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, rating, comment, reply)
                VALUES (?, ?, ?, ?, 4, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                subjectAsProviderReviewId, reviewBookingBC, counterpartyId, subjectId,
                "counterparty keeps his review", "the subject's own reply");
        jdbcTemplate.update(
                """
                INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, rating, comment, reply, direction, reviewee_id)
                VALUES (?, ?, ?, ?, 3, ?, ?, 'PROVIDER_TO_CONSUMER', ?)
                ON CONFLICT (id) DO NOTHING
                """,
                subjectReverseReviewId, reviewBookingBC, subjectId, subjectId,
                "the subject's reverse comment", "the subject's reverse reply",
                counterpartyId);
        seedReviewsAud(subjectReviewId, revBase, subjectId, counterpartyId,
                "the subject's review comment", "counterparty keeps his reply");
        seedReviewsAud(subjectAsProviderReviewId, revBase, counterpartyId, subjectId,
                "counterparty keeps his review", "the subject's own reply");
        seedReviewsAud(subjectReverseReviewId, revBase, subjectId, subjectId,
                "the subject's reverse comment", "the subject's reverse reply");

        // Provider personas (V14/V22): the subject's + the counterparty's.
        UUID subjectProfileId = UUID.randomUUID();
        UUID counterpartyProfileId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO provider_profiles (id, display_name, bio, status, user_id)
                VALUES (?, ?, ?, 'ACTIVE', ?)
                ON CONFLICT (id) DO NOTHING
                """,
                subjectProfileId, "Subject The Provider", "the subject's bio", subjectId);
        jdbcTemplate.update(
                """
                INSERT INTO provider_profiles (id, display_name, bio, status, user_id)
                VALUES (?, ?, ?, 'ACTIVE', ?)
                ON CONFLICT (id) DO NOTHING
                """,
                counterpartyProfileId, "Counterparty Stays", "counterparty keeps his bio",
                counterpartyId);
        seedProfilesAud(subjectProfileId, revBase, subjectId,
                "Subject The Provider", "the subject's bio");
        seedProfilesAud(counterpartyProfileId, revBase, counterpartyId,
                "Counterparty Stays", "counterparty keeps his bio");

        // Disputes (V20/V38): the subject's + the counterparty's. V20's
        // disputes.booking_id carries no FK — but the production write path
        // opens a dispute ON a real booking, so both anchor to the fixture's
        // real booking rows (no invented identifiers anywhere in the seed).
        UUID subjectDisputeId = UUID.randomUUID();
        UUID counterpartyDisputeId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO disputes (id, booking_id, opened_by, status, reason, created_at, updated_at, version)
                VALUES (?, ?, ?, 'OPEN', ?, now(), now(), 0)
                ON CONFLICT (id) DO NOTHING
                """,
                subjectDisputeId, subjectBookingId, subjectId, "the subject's dispute reason");
        jdbcTemplate.update(
                """
                INSERT INTO disputes (id, booking_id, opened_by, status, reason, created_at, updated_at, version)
                VALUES (?, ?, ?, 'OPEN', ?, now(), now(), 0)
                ON CONFLICT (id) DO NOTHING
                """,
                counterpartyDisputeId, counterpartyBookingId, counterpartyId,
                "counterparty keeps his reason");
        seedDisputesAud(subjectDisputeId, revBase, subjectId, "the subject's dispute reason");
        seedDisputesAud(counterpartyDisputeId, revBase, counterpartyId,
                "counterparty keeps his reason");

        // Notifications (V18): the subject's feed + the counterparty's.
        UUID subjectNotificationId = UUID.randomUUID();
        UUID counterpartyNotificationId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO notifications (id, recipient_id, type, message, is_read)
                VALUES (?, ?, 'BOOKING_CREATED', ?, false)
                ON CONFLICT (id) DO NOTHING
                """,
                subjectNotificationId, subjectId, "the subject's notification body");
        jdbcTemplate.update(
                """
                INSERT INTO notifications (id, recipient_id, type, message, is_read)
                VALUES (?, ?, 'BOOKING_CREATED', ?, false)
                ON CONFLICT (id) DO NOTHING
                """,
                counterpartyNotificationId, counterpartyId,
                "counterparty keeps his notification");
        seedNotificationsAud(subjectNotificationId, revBase, subjectId,
                "the subject's notification body");
        seedNotificationsAud(counterpartyNotificationId, revBase, counterpartyId,
                "counterparty keeps his notification");

        return new PurgeFixture(counterpartyId, revBase, subjectBookingId,
                counterpartyBookingId, conversationId, subjectMessageId,
                counterpartyMessageId, subjectReviewId, subjectAsProviderReviewId,
                subjectReverseReviewId, subjectProfileId, counterpartyProfileId,
                subjectDisputeId, counterpartyDisputeId, subjectNotificationId,
                counterpartyNotificationId);
    }

    private void seedRevinfo(int rev) {
        jdbcTemplate.update("INSERT INTO revinfo (rev, revtstmp) VALUES (?, ?)",
                rev, System.currentTimeMillis());
    }

    private void seedBookingsAud(UUID id, int rev, UUID consumerId, String notes) {
        jdbcTemplate.update(
                "INSERT INTO bookings_aud (id, rev, revtype, consumer_id, notes) VALUES (?, ?, 0, ?, ?)",
                id, rev, consumerId, notes);
    }

    private void seedMessagesAud(UUID id, int rev, UUID senderId, String content) {
        jdbcTemplate.update(
                "INSERT INTO messages_aud (id, rev, revtype, sender_id, content) VALUES (?, ?, 0, ?, ?)",
                id, rev, senderId, content);
    }

    private void seedReviewsAud(UUID id, int rev, UUID reviewerId, UUID providerId,
                                String comment, String reply) {
        jdbcTemplate.update(
                "INSERT INTO reviews_aud (id, rev, revtype, reviewer_id, provider_id, comment, reply) "
                        + "VALUES (?, ?, 0, ?, ?, ?, ?)",
                id, rev, reviewerId, providerId, comment, reply);
    }

    private void seedProfilesAud(UUID id, int rev, UUID userId, String displayName, String bio) {
        jdbcTemplate.update(
                "INSERT INTO provider_profiles_aud (id, rev, revtype, user_id, display_name, bio) "
                        + "VALUES (?, ?, 0, ?, ?, ?)",
                id, rev, userId, displayName, bio);
    }

    private void seedDisputesAud(UUID id, int rev, UUID openedBy, String reason) {
        jdbcTemplate.update(
                "INSERT INTO disputes_aud (id, rev, revtype, opened_by, reason) VALUES (?, ?, 0, ?, ?)",
                id, rev, openedBy, reason);
    }

    private void seedNotificationsAud(UUID id, int rev, UUID recipientId, String message) {
        jdbcTemplate.update(
                "INSERT INTO notifications_aud (id, rev, revtype, recipient_id, message) "
                        + "VALUES (?, ?, 0, ?, ?)",
                id, rev, recipientId, message);
    }

    private void registerUser(String username, String role) {
        if (userDetailsManager.userExists(username)) {
            return;
        }
        userDetailsManager.createUser(org.springframework.security.core.userdetails.User
                .withUsername(username)
                .password("{noop}" + PASSWORD)
                .roles(role)
                .build());
    }

    private void registerLoginGateClient() {
        if (registeredClientRepository.findByClientId(CLIENT_ID) != null) {
            return;
        }
        RegisteredClient loginGateClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(CLIENT_ID)
                .clientSecret("{noop}" + CLIENT_SECRET)
                .clientName("Authored Content Purge Gate Integration Test Client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(REDIRECT_URI)
                .scope("openid")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .build();
        registeredClientRepository.save(loginGateClient);
    }

    /**
     * The real browser-less login gate — the L23 gate's proven five-step
     * sequence, verbatim: (1) unauthenticated authorize &rarr; 302 /login
     * with the session; (2) the login form's CSRF token; (3) credentials
     * POST &rarr; 302 to the saved authorization request; (4) re-issuing
     * the authorize request as the authenticated principal &rarr; 302 to
     * the client with the code; (5) the token exchange.
     */
    private GateResult loginGate(String username, String password) throws Exception {
        registerLoginGateClient();
        String state = UUID.randomUUID().toString();
        String codeVerifier = randomCodeVerifier();
        String codeChallenge = base64Url(sha256(codeVerifier));
        String authorizeUrl = baseUrl() + AUTHORIZE_PATH
                + "?response_type=code"
                + "&client_id=" + CLIENT_ID
                + "&scope=openid"
                + "&state=" + state
                + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256";

        HttpResponse<String> authorizeFirst = get(authorizeUrl, null);
        assertThat(authorizeFirst.statusCode())
                .as("authorize should redirect to login: %s", body(authorizeFirst)).isEqualTo(302);
        assertThat(authorizeFirst.headers().firstValue("Location").orElse("")).contains(LOGIN_PATH);
        String sessionCookie = sessionCookie(authorizeFirst);
        assertThat(sessionCookie).as("spring-session cookie expected").isNotBlank();

        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, sessionCookie);
        assertThat(loginPage.statusCode()).as("login page: %s", body(loginPage)).isEqualTo(200);
        String csrfToken = csrfTokenFrom(loginPage.body());
        assertThat(csrfToken).as("CSRF token must be rendered by the default login page").isNotBlank();
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);

        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + password + "&_csrf=" + encode(csrfToken), sessionCookie);
        assertThat(loginPost.statusCode()).as("login should succeed: %s", body(loginPost)).isEqualTo(302);
        String savedRequest = loginPost.headers().firstValue("Location").orElse("");
        assertThat(savedRequest).as("login must resume the saved authorization request, not /login?error")
                .contains(AUTHORIZE_PATH);
        sessionCookie = latestSessionCookie(loginPost, sessionCookie);

        HttpResponse<String> authorizeSecond = get(absolute(savedRequest), sessionCookie);
        assertThat(authorizeSecond.statusCode())
                .as("authorize should redirect back to the client: %s", body(authorizeSecond)).isEqualTo(302);
        String redirect = authorizeSecond.headers().firstValue("Location").orElse("");
        assertThat(redirect).startsWith(REDIRECT_URI);
        assertThat(redirect).contains("code=");
        String authorizationCode = queryParam(redirect, "code");

        HttpResponse<String> tokenResponse = postFormWithBasicAuth(TOKEN_PATH,
                "grant_type=authorization_code"
                        + "&code=" + encode(authorizationCode)
                        + "&redirect_uri=" + encode(REDIRECT_URI)
                        + "&code_verifier=" + codeVerifier);
        assertThat(tokenResponse.statusCode()).as("token endpoint: %s", body(tokenResponse)).isEqualTo(200);

        JsonNode tokens = objectMapper.readTree(tokenResponse.body());
        return new GateResult(
                tokens.path("access_token").asString(),
                tokens.path("refresh_token").asString(),
                tokens.path("token_type").asString(),
                tokens.path("id_token").asString());
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : baseUrl() + location;
    }

    /** PKCE verifier per the official RFC 7636 flow shape (login-gate test). */
    private static String randomCodeVerifier() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
    }

    private GateResult adminGate() throws Exception {
        registerUser(ADMIN_USERNAME, "ADMIN");
        return loginGate(ADMIN_USERNAME, PASSWORD);
    }

    private UUID syncProjectionIdViaMe(String accessToken) throws Exception {
        HttpResponse<String> me = getWithBearer("/api/v1/users/me", accessToken);
        assertThat(me.statusCode()).as("/me sync: %s", body(me)).isEqualTo(200);
        JsonNode user = objectMapper.readTree(me.body());
        assertThat(user.path("id").asString()).as("the /me response must carry the id").isNotBlank();
        return UUID.fromString(user.path("id").asString());
    }

    private HttpResponse<String> get(String url, String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "text/html,application/xhtml+xml")
                .timeout(Duration.ofSeconds(30))
                .GET();
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithBearer(String path, String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJsonWithBearer(String path, String accessToken, String json)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(String path, String form, String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "text/html,application/xhtml+xml")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8));
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postFormWithBasicAuth(String path, String form) throws Exception {
        String credentials = Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + credentials)
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + portA;
    }

    private String sessionCookie(HttpResponse<?> response) {
        for (String header : response.headers().allValues("Set-Cookie")) {
            Matcher matcher = SESSION_COOKIE.matcher(header);
            if (matcher.find()) {
                return matcher.group(1) + "=" + matcher.group(2);
            }
        }
        return null;
    }

    private String latestSessionCookie(HttpResponse<?> response, String fallback) {
        String cookie = sessionCookie(response);
        return cookie != null ? cookie : fallback;
    }

    private String csrfTokenFrom(String loginHtml) {
        Matcher matcher = CSRF_INPUT.matcher(loginHtml);
        if (matcher.find()) {
            return matcher.group(1);
        }
        matcher = CSRF_INPUT_REVERSED.matcher(loginHtml);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String queryParam(String location, String name) {
        String query = location.substring(location.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0 && name.equals(pair.substring(0, equals))) {
                return pair.substring(equals + 1);
            }
        }
        return null;
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String body(HttpResponse<String> response) {
        return response.body() == null ? "" : response.body().substring(0, Math.min(500, response.body().length()));
    }

    private record GateResult(String accessToken, String refreshToken, String tokenType, String idToken) {
    }

    private record PurgeFixture(
            UUID counterpartyId,
            int revBase,
            UUID subjectBookingId,
            UUID counterpartyBookingId,
            UUID conversationId,
            UUID subjectMessageId,
            UUID counterpartyMessageId,
            UUID subjectReviewId,
            UUID subjectAsProviderReviewId,
            UUID subjectReverseReviewId,
            UUID subjectProfileId,
            UUID counterpartyProfileId,
            UUID subjectDisputeId,
            UUID counterpartyDisputeId,
            UUID subjectNotificationId,
            UUID counterpartyNotificationId) {
    }
}
