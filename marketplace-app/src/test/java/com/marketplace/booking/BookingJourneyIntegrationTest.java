package com.marketplace.booking;

import test.config.IntegrationContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import test.config.AuthorizationServerFixture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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

import static org.assertj.core.api.Assertions.assertThat;
import static test.config.AuthorizationServerFixture.AUTHORIZE_PATH;
import static test.config.AuthorizationServerFixture.CLIENT_ID;
import static test.config.AuthorizationServerFixture.CLIENT_SECRET;
import static test.config.AuthorizationServerFixture.REDIRECT_URI;
import static test.config.AuthorizationServerFixture.TOKEN_PATH;

/**
 * J20 (the unified end-to-end plan §3, the journeys register row J20 —
 * «حجز خدمة/إقامة»: the composite booking→payment→confirmation→eligible
 * review journey) — the machine measured end-to-end on the REAL chain:
 * real PostgreSQL + real Redis + the real Flyway schema + the real login
 * gate's Bearer + the REAL money engine (the BOOKING origin's one
 * collectible intent per booking) + the REAL review eligibility gate.
 *
 * <p>The journey: the consumer books the seller's listing over HTTP (the
 * exact-slot gate + the server-derived flat total — no calendar rows, the
 * L26 backward-compatible number) → the money gate answers its honest 409
 * while the booking is still PENDING (the intent requires CONFIRMED —
 * {@code BookingInfo.requireStatus} rides the A5 IllegalStateException→409
 * taxonomy) → the seller confirms (the slot window booked in the booking's
 * own name — the R2 ownership claim) → the money loop rides the REAL
 * payments engine (the duplicate intent's 409, the idempotent replay's
 * same row, process, the administrative capture) → the COMPLETED payment
 * event reaches the booking listener whose already-CONFIRMED no-op is the
 * honest measured behavior → the seller completes → the consumer's review
 * lands eligible (PUBLISHED, booking-origin) with its branches: the
 * pre-completion 400, the duplicate 409, the stranger's 403, and the
 * provider's own reverse review.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The production-shaped schema: real Flyway, no ddl-auto.
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The production cache type (the test profile defaults to 'simple').
        "spring.cache.type=redis",
        // CI connection budget (the I5/L14 harness rationale).
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",
        // The login gate's client fixtures (the L23 gate properties).
        "marketplace.security.oauth2.client.client-id=" + CLIENT_ID,
        "marketplace.security.oauth2.client.secret=" + CLIENT_SECRET,
        "marketplace.security.oauth2.client.redirect-uris=" + REDIRECT_URI,
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class BookingJourneyIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static org.testcontainers.postgresql.PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String PASSWORD = "it-booking-journey-password";
    private static final String LOGIN_PATH = "/login";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    /** The stay windows — far-future so the expiration sweep never races the journey. */
    private static final String SLOT_START = "2027-06-01T14:00:00Z";
    private static final String SLOT_END = "2027-06-04T10:00:00Z";
    private static final String CANCEL_SLOT_START = "2027-07-01T14:00:00Z";
    private static final String CANCEL_SLOT_END = "2027-07-03T10:00:00Z";

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Value("${local.server.port}")
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void seedTheStoreDictionary() {
        // The journey's category arrives as DATA (the plan's own wording —
        // never a migration), seeded by the class itself: the register path
        // validates the code against the live dictionary, and relying on
        // another test class to have seeded it first was a run-order
        // dependency the added waves unmasked (the RMA journey's own
        // measured CI lesson, adopted verbatim).
        jdbcTemplate.update("INSERT INTO store_categories (id, code, name_en, name_ar, position) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (code) DO NOTHING",
                UUID.randomUUID(), "journey-appliances", "Home appliances", "أجهزة منزلية", 0);
    }

    @Test
    void theFullBookingJourneyFromRequestToEligibleReview() throws Exception {
        // The seller provisions FIRST: the listing (the catalog row the
        // booking's amounts resolve through) and the availability window
        // (the exact slot the booking will claim) are the provider's own
        // records before any book lands.
        String sellerName = "it-booking-seller-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(sellerName, "USER", "PROVIDER");
        GateResult sellerGate = loginGate(sellerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", sellerGate.accessToken()).statusCode())
                .as("the seller's /me provisioning sync").isEqualTo(200);
        UUID sellerId = java.util.UUID.fromString(
                objectMapper.readTree(getWithBearer("/api/v1/users/me", sellerGate.accessToken()).body())
                        .path("id").asString());
        // The seller registers their business FIRST — the provider_profiles row
        // the availability gate's ownsProvider resolves through (the user-owned
        // "me" seam: every cross-module provider_id carries the USER id, so the
        // slot publish 403'd without the row; the RMA and delegation journeys'
        // own registration step).
        HttpResponse<String> providerReg = postJson("/api/v1/providers", sellerGate.accessToken(),
                "{\"displayName\":\"مكتب الحجوزات — الرحلة الكاملة\",\"bio\":\"J20\"}");
        assertThat(providerReg.statusCode()).as("provider register: %s", body(providerReg)).isEqualTo(200);
        UUID listing = registerProduct(sellerGate.accessToken(), 1500L);
        createSlot(sellerGate.accessToken(), sellerId, SLOT_START, SLOT_END);

        String consumerName = "it-booking-buyer-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(consumerName, "CONSUMER");
        GateResult gate = loginGate(consumerName, PASSWORD);
        // The /me family's provisioning step (the measured CI lesson,
        // 2026-10-09): the strict CurrentUserProvider resolves the caller
        // only after the first /users/me call syncs the projection row.
        assertThat(getWithBearer("/api/v1/users/me", gate.accessToken()).statusCode())
                .as("the consumer's /me provisioning sync").isEqualTo(200);

        // (1) The booking request: the exact-slot gate passes, the total is
        // the server-derived flat base price (no calendar rows — the L26
        // backward-compatible number), and the client never supplied money.
        HttpResponse<String> bookingResponse = postJson("/api/v1/bookings", gate.accessToken(), """
                {"listingId":"%s","startsAt":"%s","endsAt":"%s","notes":"J20 journey"}"""
                .formatted(listing, SLOT_START, SLOT_END));
        assertThat(bookingResponse.statusCode()).as("booking create: %s", body(bookingResponse)).isEqualTo(201);
        JsonNode booking = objectMapper.readTree(bookingResponse.body());
        UUID bookingId = UUID.fromString(booking.path("id").asString());
        assertThat(booking.path("status").asString()).isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT price_cents FROM bookings WHERE id = ?", Long.class, bookingId))
                .as("the server-derived flat total (no calendar rows)").isEqualTo(1500L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT currency FROM bookings WHERE id = ?", String.class, bookingId)).isEqualTo("SAR");

        // (2) The money gate's honest 409 while the booking is PENDING: the
        // intent requires CONFIRMED (requireStatus rides the A5
        // IllegalStateException→409 taxonomy — a domain conflict, never a 500).
        HttpResponse<String> prematureIntent = postJson("/api/v1/payments/intents", gate.accessToken(), """
                {"bookingId":"%s","idempotencyKey":"j20-premature-%s"}"""
                .formatted(bookingId, bookingId));
        assertThat(prematureIntent.statusCode())
                .as("intent on a PENDING booking: %s", body(prematureIntent)).isEqualTo(409);

        // (3) The seller confirms: the slot window is booked in the
        // booking's own name (the R2 ownership claim).
        HttpResponse<String> confirm = postJson(
                "/api/v1/bookings/" + bookingId + "/confirm", sellerGate.accessToken(), "");
        assertThat(confirm.statusCode()).as("seller confirm: %s", body(confirm)).isEqualTo(200);
        assertThat(objectMapper.readTree(confirm.body()).path("status").asString())
                .as("the confirm's read-back").isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT booked FROM availability_slots WHERE provider_id = ? AND starts_at = ?",
                Boolean.class, sellerId, java.time.Instant.parse(SLOT_START)))
                .as("the window claimed at confirm").isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT held_by_booking_id FROM availability_slots WHERE provider_id = ? AND starts_at = ?",
                String.class, sellerId, java.time.Instant.parse(SLOT_START)))
                .as("the R2 ownership claim names THIS booking").isEqualTo(bookingId.toString());

        // (4) The events DELIVER: BOOKING_CREATED rows land for BOTH
        // participants (the L22 in-app channel is always on) — the typed
        // wait is the only honest synchronization for a typed assertion.
        awaitTypedNotificationRows(bookingId, "BOOKING_CREATED", 2);

        // (5) The money loop opens: the consumer's intent carries the
        // booking's own amount and ISO 4217 currency.
        HttpResponse<String> intentResponse = postJson("/api/v1/payments/intents", gate.accessToken(), """
                {"bookingId":"%s","idempotencyKey":"j20-intent-%s"}"""
                .formatted(bookingId, bookingId));
        assertThat(intentResponse.statusCode()).as("intent: %s", body(intentResponse)).isEqualTo(201);
        JsonNode intent = objectMapper.readTree(intentResponse.body());
        UUID intentId = UUID.fromString(intent.path("id").asString());
        assertThat(intent.path("amountCents").asLong()).as("the booking's own amount").isEqualTo(1500L);
        assertThat(intent.path("currency").asString()).isEqualTo("SAR");

        // (6) The duplicate's 409 (R4 — one collectible attempt per
        // booking: the guard is the friendly failure, the V81 partial
        // unique index is the race backstop) and the idempotent replay's
        // SAME row (the caller's deduplication surface).
        HttpResponse<String> duplicateIntent = postJson("/api/v1/payments/intents", gate.accessToken(), """
                {"bookingId":"%s","idempotencyKey":"j20-duplicate-%s"}"""
                .formatted(bookingId, bookingId));
        assertThat(duplicateIntent.statusCode())
                .as("duplicate live intent: %s", body(duplicateIntent)).isEqualTo(409);
        HttpResponse<String> replayIntent = postJson("/api/v1/payments/intents", gate.accessToken(), """
                {"bookingId":"%s","idempotencyKey":"j20-intent-%s"}"""
                .formatted(bookingId, bookingId));
        assertThat(replayIntent.statusCode()).as("idempotent replay: %s", body(replayIntent)).isEqualTo(201);
        assertThat(UUID.fromString(objectMapper.readTree(replayIntent.body()).path("id").asString()))
                .as("the replay answers the SAME intent row").isEqualTo(intentId);

        // (7) The consumer drives the process step (the local-only channel:
        // PROCESSING + a Payment row, no remote charge).
        HttpResponse<String> process = postJson(
                "/api/v1/payments/intents/" + intentId + "/process", gate.accessToken(), "");
        assertThat(process.statusCode()).as("process: %s", body(process)).isEqualTo(200);
        assertThat(objectMapper.readTree(process.body()).path("status").asString()).isEqualTo("PROCESSING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payments WHERE payment_intent_id = ?", Integer.class, intentId))
                .as("the process step's Payment row").isEqualTo(1);

        // (8) The administrative capture closes the money loop: the intent
        // SUCCEEDED, the payment row COMPLETED, the shared COMPLETED event
        // fires (the ledger credit + the booking listener both listen).
        String adminName = "it-booking-admin-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(adminName, "ADMIN");
        GateResult adminGate = loginGate(adminName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", adminGate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> capture = postJson(
                "/api/v1/payments/intents/" + intentId + "/confirm", adminGate.accessToken(),
                "{\"externalId\":\"j20-capture-" + intentId + "\"}");
        assertThat(capture.statusCode()).as("admin capture: %s", body(capture)).isEqualTo(200);
        assertThat(objectMapper.readTree(capture.body()).path("status").asString()).isEqualTo("SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE payment_intent_id = ?", String.class, intentId))
                .isEqualTo("COMPLETED");

        // (9) The booking listener's honest no-op: the payment completion
        // reaches autoConfirm, but a booking the provider already confirmed
        // stays CONFIRMED — measured behavior, not a second transition.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId))
                .as("the already-CONFIRMED no-op (the listener's own guard)").isEqualTo("CONFIRMED");

        // (10) The consumer's notification row for the confirmation (the
        // CONSUMER alone — the recipient contract of onBookingConfirmed).
        awaitTypedNotificationRows(bookingId, "BOOKING_CONFIRMED", 1);
        Integer confirmedRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE type = 'BOOKING_CONFIRMED'"
                        + " AND message LIKE '%' || ? || '%'",
                Integer.class, bookingId);
        assertThat(confirmedRows).as("BOOKING_CONFIRMED notification row").isEqualTo(1);

        // (11) The eligibility gate's honest 400 BEFORE completion: a
        // well-formed review on a non-COMPLETED booking is a validation
        // answer, never a silent acceptance.
        HttpResponse<String> earlyReview = postJson("/api/v1/reviews", gate.accessToken(), """
                {"bookingId":"%s","rating":5,"comment":"too early"}""".formatted(bookingId));
        assertThat(earlyReview.statusCode())
                .as("review before completion: %s", body(earlyReview)).isEqualTo(400);

        // (12) The seller completes the stay — the state the review
        // eligibility reads.
        HttpResponse<String> complete = postJson(
                "/api/v1/bookings/" + bookingId + "/complete", sellerGate.accessToken(), "");
        assertThat(complete.statusCode()).as("seller complete: %s", body(complete)).isEqualTo(200);
        assertThat(objectMapper.readTree(complete.body()).path("status").asString()).isEqualTo("COMPLETED");

        // (13) The eligible review lands: PUBLISHED (booking-origin reviews
        // never await moderation — the verified-purchase trust), the
        // reviewer is the booking's own consumer, the provider is the
        // booking's own provider.
        HttpResponse<String> review = postJson("/api/v1/reviews", gate.accessToken(), """
                {"bookingId":"%s","rating":5,"comment":"spotless"}""".formatted(bookingId));
        assertThat(review.statusCode()).as("the eligible review: %s", body(review)).isEqualTo(201);
        JsonNode reviewBody = objectMapper.readTree(review.body());
        assertThat(reviewBody.path("rating").asInt()).isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT origin FROM reviews WHERE booking_id = ?", String.class, bookingId))
                .as("the review's origin is the booking itself").isEqualTo("BOOKING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT moderation_status FROM reviews WHERE booking_id = ?", String.class, bookingId))
                .as("booking-origin reviews publish without moderation").isEqualTo("PUBLISHED");

        // (14) The duplicate's 409 (I8 per-direction uniqueness — one
        // consumer review per booking).
        HttpResponse<String> duplicateReview = postJson("/api/v1/reviews", gate.accessToken(), """
                {"bookingId":"%s","rating":4,"comment":"again"}""".formatted(bookingId));
        assertThat(duplicateReview.statusCode())
                .as("duplicate review: %s", body(duplicateReview)).isEqualTo(409);

        // (15) The stranger's 403: an authenticated outsider on the review
        // surface is rejected by the ownership gate (a documented write
        // path — the honest 403, not the read surface's 404).
        String strangerName = "it-booking-stranger-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(strangerName, "CONSUMER");
        GateResult strangerGate = loginGate(strangerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", strangerGate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> strangerReview = postJson("/api/v1/reviews", strangerGate.accessToken(), """
                {"bookingId":"%s","rating":1,"comment":"not my stay"}""".formatted(bookingId));
        assertThat(strangerReview.statusCode())
                .as("stranger review: only the booking consumer").isEqualTo(403);

        // (16) The provider's reverse review — the booking's provider rates
        // its consumer (the second direction the per-direction uniqueness
        // admits).
        HttpResponse<String> reverseReview = postJson("/api/v1/reviews/reverse", sellerGate.accessToken(), """
                {"bookingId":"%s","rating":5,"comment":"great guest"}""".formatted(bookingId));
        assertThat(reverseReview.statusCode()).as("reverse review: %s", body(reverseReview)).isEqualTo(201);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM reviews WHERE booking_id = ?", Integer.class, bookingId))
                .as("both directions on one booking").isEqualTo(2);

        // (17) The terminal guards: COMPLETED is terminal — the second
        // complete and the late cancel both answer the honest 409.
        HttpResponse<String> secondComplete = postJson(
                "/api/v1/bookings/" + bookingId + "/complete", sellerGate.accessToken(), "");
        assertThat(secondComplete.statusCode()).as("second complete").isEqualTo(409);
        HttpResponse<String> lateCancel = postJson(
                "/api/v1/bookings/" + bookingId + "/cancel", gate.accessToken(), "");
        assertThat(lateCancel.statusCode()).as("cancel after completion").isEqualTo(409);
    }

    /**
     * The cancellation branch (L19 — the booking's money exit): a
     * CONFIRMED booking with a CAPTURED payment cancels through the
     * participant surface, the BookingCancelledEvent drives the payments
     * module's auto-refund (the ONE refund contract: the collected intent
     * refunded in full, the ledger's refund debit mirroring the
     * booking-origin credit), the slot window is released (the R2 claim
     * freed), and the terminal guard keeps the closed state closed. The
     * review eligibility law holds on the exit too: a CANCELLED booking is
     * not reviewable.
     */
    @Test
    void theCancellationBranchRefundsAndReleasesTheWindow() throws Exception {
        String sellerName = "it-booking-cancel-seller-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(sellerName, "USER", "PROVIDER");
        GateResult sellerGate = loginGate(sellerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", sellerGate.accessToken()).statusCode()).isEqualTo(200);
        UUID sellerId = java.util.UUID.fromString(
                objectMapper.readTree(getWithBearer("/api/v1/users/me", sellerGate.accessToken()).body())
                        .path("id").asString());
        // The same registration-first law: the provider_profiles row the
        // availability gate's ownsProvider resolves through.
        HttpResponse<String> providerReg = postJson("/api/v1/providers", sellerGate.accessToken(),
                "{\"displayName\":\"مكتب الحجوزات — فرع الإلغاء\",\"bio\":\"J20 cancel\"}");
        assertThat(providerReg.statusCode()).as("provider register: %s", body(providerReg)).isEqualTo(200);
        UUID listing = registerProduct(sellerGate.accessToken(), 2000L);
        createSlot(sellerGate.accessToken(), sellerId, CANCEL_SLOT_START, CANCEL_SLOT_END);

        String consumerName = "it-booking-cancel-buyer-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(consumerName, "CONSUMER");
        GateResult gate = loginGate(consumerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", gate.accessToken()).statusCode()).isEqualTo(200);

        // The journey to the captured state: book → confirm → intent →
        // process → capture.
        HttpResponse<String> bookingResponse = postJson("/api/v1/bookings", gate.accessToken(), """
                {"listingId":"%s","startsAt":"%s","endsAt":"%s","notes":"cancel branch"}"""
                .formatted(listing, CANCEL_SLOT_START, CANCEL_SLOT_END));
        assertThat(bookingResponse.statusCode()).as("booking create: %s", body(bookingResponse)).isEqualTo(201);
        UUID bookingId = UUID.fromString(objectMapper.readTree(bookingResponse.body()).path("id").asString());

        HttpResponse<String> confirm = postJson(
                "/api/v1/bookings/" + bookingId + "/confirm", sellerGate.accessToken(), "");
        assertThat(confirm.statusCode()).as("seller confirm: %s", body(confirm)).isEqualTo(200);

        HttpResponse<String> intentResponse = postJson("/api/v1/payments/intents", gate.accessToken(), """
                {"bookingId":"%s","idempotencyKey":"j20-cancel-%s"}"""
                .formatted(bookingId, bookingId));
        assertThat(intentResponse.statusCode()).as("intent: %s", body(intentResponse)).isEqualTo(201);
        UUID intentId = UUID.fromString(objectMapper.readTree(intentResponse.body()).path("id").asString());

        assertThat(postJson("/api/v1/payments/intents/" + intentId + "/process",
                gate.accessToken(), "").statusCode()).isEqualTo(200);

        String adminName = "it-booking-cancel-admin-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(adminName, "ADMIN");
        GateResult adminGate = loginGate(adminName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", adminGate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> capture = postJson(
                "/api/v1/payments/intents/" + intentId + "/confirm", adminGate.accessToken(),
                "{\"externalId\":\"j20-cancel-capture-" + intentId + "\"}");
        assertThat(capture.statusCode()).as("admin capture: %s", body(capture)).isEqualTo(200);
        awaitIntentStatus(intentId, "SUCCEEDED");

        // The cancellation: the consumer cancels the confirmed stay — the
        // participant surface's own write.
        HttpResponse<String> cancel = postJson(
                "/api/v1/bookings/" + bookingId + "/cancel", gate.accessToken(), "");
        assertThat(cancel.statusCode()).as("consumer cancel: %s", body(cancel)).isEqualTo(200);
        assertThat(objectMapper.readTree(cancel.body()).path("status").asString()).isEqualTo("CANCELLED");

        // The ONE refund contract rides the event: the collected intent is
        // refunded IN FULL (the R4 scoped search's own target), the payment
        // row flips REFUNDED, and the ledger's refund debit mirrors the
        // booking-origin credit under the refund's own nameUUID source key.
        awaitIntentStatus(intentId, "REFUNDED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE payment_intent_id = ?", String.class, intentId))
                .as("the payment row follows the refund").isEqualTo("REFUNDED");
        UUID refundSource = UUID.nameUUIDFromBytes(
                ("refund-" + intentId.toString()).getBytes(StandardCharsets.UTF_8));
        Integer refundDebits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE entry_type = 'REFUND_DEBIT' AND source_id = ?",
                Integer.class, refundSource);
        assertThat(refundDebits).as("the refund debit mirrors the credit (the ONE refund contract)").isEqualTo(1);
        Integer credits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE entry_type = 'PAYMENT_CREDIT' AND source_id = ?",
                Integer.class, intentId);
        assertThat(credits).as("the capture's credit rode the intent's own source").isEqualTo(1);

        // The slot window is released: the R2 claim freed for the next booker.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT booked FROM availability_slots WHERE provider_id = ? AND starts_at = ?",
                Boolean.class, sellerId, java.time.Instant.parse(CANCEL_SLOT_START)))
                .as("the window released at cancel").isFalse();

        // The terminal guard: the closed state stays closed.
        HttpResponse<String> secondCancel = postJson(
                "/api/v1/bookings/" + bookingId + "/cancel", gate.accessToken(), "");
        assertThat(secondCancel.statusCode()).as("second cancel").isEqualTo(409);

        // The eligibility law holds on the exit: a CANCELLED booking is
        // not reviewable — the honest 400, never a silent acceptance.
        HttpResponse<String> cancelledReview = postJson("/api/v1/reviews", gate.accessToken(), """
                {"bookingId":"%s","rating":5,"comment":"cancelled stay"}""".formatted(bookingId));
        assertThat(cancelledReview.statusCode())
                .as("review on a cancelled booking: %s", body(cancelledReview)).isEqualTo(400);
    }

    // ------------------------------------------------------------------
    // The login gate machinery (the L23 five-step sequence, house verbatim)
    // ------------------------------------------------------------------

    private void registerUser(String username, String... roles) {
        if (userDetailsManager.userExists(username)) {
            return;
        }
        userDetailsManager.createUser(org.springframework.security.core.userdetails.User
                .withUsername(username)
                .password("{noop}" + PASSWORD)
                .roles(roles.length == 0 ? new String[]{"USER"} : roles)
                .build());
    }

    private UUID registerProduct(String sellerToken, long priceMinor) throws Exception {
        HttpResponse<String> created = postJson("/api/v1/store/products", sellerToken, """
                {"storeCategoryCode":"journey-appliances","title":"Journey stay listing",
                 "description":"J20 journey listing","priceMinor":%d,"currency":"SAR"}"""
                .formatted(priceMinor));
        assertThat(created.statusCode()).as("product register: %s", body(created)).isEqualTo(201);
        return UUID.fromString(objectMapper.readTree(created.body()).path("id").asString());
    }

    /**
     * The availability window: the provider's own bookable slot, the exact
     * window the booking will claim (the create gate's
     * hasExactAvailableSlot reads the same row bookSlot claims).
     */
    private void createSlot(String sellerToken, UUID providerId, String startsAt, String endsAt)
            throws Exception {
        HttpResponse<String> slot = postJson(
                "/api/v1/providers/" + providerId + "/availability/slots?startsAt=" + encode(startsAt)
                        + "&endsAt=" + encode(endsAt), sellerToken, "");
        assertThat(slot.statusCode()).as("slot publish: %s", body(slot)).isEqualTo(200);
    }

    private GateResult loginGate(String username, String password) throws Exception {
        AuthorizationServerFixture.registerLoginGateClient(registeredClientRepository,
                "Booking Journey Gate Integration Test Client");
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
        assertThat(authorizeFirst.statusCode()).isEqualTo(302);
        String sessionCookie = sessionCookie(authorizeFirst);

        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, sessionCookie);
        assertThat(loginPage.statusCode()).isEqualTo(200);
        String csrfToken = csrfTokenFrom(loginPage.body());
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);

        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + password + "&_csrf=" + encode(csrfToken), sessionCookie);
        assertThat(loginPost.statusCode()).isEqualTo(302);
        String savedRequest = loginPost.headers().firstValue("Location").orElse("");
        assertThat(savedRequest).contains(AUTHORIZE_PATH);
        sessionCookie = latestSessionCookie(loginPost, sessionCookie);

        HttpResponse<String> authorizeSecond = get(absolute(savedRequest), sessionCookie);
        assertThat(authorizeSecond.statusCode()).isEqualTo(302);
        String redirect = authorizeSecond.headers().firstValue("Location").orElse("");
        assertThat(redirect).startsWith(REDIRECT_URI);
        String authorizationCode = queryParam(redirect, "code");

        HttpResponse<String> tokenResponse = postFormWithBasicAuth(TOKEN_PATH,
                "grant_type=authorization_code"
                        + "&code=" + encode(authorizationCode)
                        + "&redirect_uri=" + encode(REDIRECT_URI)
                        + "&code_verifier=" + codeVerifier);
        assertThat(tokenResponse.statusCode()).isEqualTo(200);

        JsonNode tokens = objectMapper.readTree(tokenResponse.body());
        return new GateResult(tokens.path("access_token").asString(), sessionCookie);
    }

    private record GateResult(String accessToken, String sessionCookie) {
    }

    /**
     * The AFTER_COMMIT listener hop (the booking's notification rows and
     * the refund's ledger movement ride it): the bounded, TYPED wait polls
     * the very rows the assertion below reads — the house polling shape
     * (200ms steps, 10s ceiling).
     */
    private void awaitTypedNotificationRows(UUID bookingId, String type, int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM notifications WHERE type = ? AND message LIKE '%' || ? || '%'",
                    Integer.class, type, bookingId);
            if (rows != null && rows >= expected) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("notification rows of type " + type + " for booking " + bookingId
                + " never reached " + expected + " within 10s");
    }

    /** The bounded poll for the intent's own status (the refund listener's hop). */
    private void awaitIntentStatus(UUID intentId, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM payment_intents WHERE id = ?", String.class, intentId);
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("payment intent " + intentId + " did not reach " + expected
                + " within 10s");
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private HttpResponse<String> get(String url, String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30)).GET();
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getWithBearer(String path, String accessToken) throws Exception {
        return get(baseUrl() + path, null, accessToken);
    }

    private HttpResponse<String> get(String url, String sessionCookie, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(30)).GET();
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postJson(String path, String accessToken, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(String path, String form, String sessionCookie) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Cookie", sessionCookie)
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postFormWithBasicAuth(String path, String form) throws Exception {
        String credentials = Base64.getEncoder()
                .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Authorization", "Basic " + credentials)
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : baseUrl() + location;
    }

    private String sessionCookie(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .map(c -> SESSION_COOKIE.matcher(c))
                .filter(Matcher::find)
                .map(m -> m.group(1) + "=" + m.group(2))
                .findFirst().orElse("");
    }

    private String latestSessionCookie(HttpResponse<String> response, String fallback) {
        String cookie = sessionCookie(response);
        return cookie.isBlank() ? fallback : cookie;
    }

    private String csrfTokenFrom(String html) {
        Matcher direct = CSRF_INPUT.matcher(html);
        if (direct.find()) {
            return direct.group(1);
        }
        Matcher reversed = CSRF_INPUT_REVERSED.matcher(html);
        return reversed.find() ? reversed.group(1) : "";
    }

    private String queryParam(String url, String param) {
        Matcher matcher = Pattern.compile("[?&]" + Pattern.quote(param) + "=([^&]+)").matcher(url);
        assertThat(matcher.find()).as("%s must be present in %s", param, url).isTrue();
        return matcher.group(1);
    }

    private String randomCodeVerifier() {
        return base64Url(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    }

    private byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String body(HttpResponse<String> response) {
        return response.body() == null ? "" : response.body().substring(0, Math.min(2000, response.body().length()));
    }
}
