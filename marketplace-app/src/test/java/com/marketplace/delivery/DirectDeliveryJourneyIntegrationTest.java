package com.marketplace.delivery;

import test.config.IntegrationContainers;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import test.config.AuthorizationServerFixture;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static test.config.AuthorizationServerFixture.AUTHORIZE_PATH;
import static test.config.AuthorizationServerFixture.CLIENT_ID;
import static test.config.AuthorizationServerFixture.CLIENT_SECRET;
import static test.config.AuthorizationServerFixture.REDIRECT_URI;
import static test.config.AuthorizationServerFixture.TOKEN_PATH;

/**
 * W8 / J24 + J26 (the unified plan §4 W8, the journeys register's J24 and
 * J26 rows) — the direct (independent) delivery composite journey and the
 * fleet-operations acceptance, measured end-to-end on the REAL chain:
 * real PostgreSQL + real Redis + the real Flyway schema (V215's quotes/
 * requests/journal tables, V217's couriers and widened journal accounts,
 * V227's one-request-per-quote partial unique, V229's operational columns)
 * + the real login gate's Bearer + the real Modulith event machinery (the
 * delivery payment listener, the refund listener, the ledger's balanced
 * delivery journal CAPTURE→RECOGNIZE→COURIER_PAYABLE chain) + the REAL
 * production road-routing adapter ({@code OsrmDeliveryRouteProvider} in
 * marketplace-delivery, speaking OSRM HTTP to a configured endpoint — the
 * journey boots the module's ENABLED deployment shape and points the
 * configured routing base URL at a local OSRM-shaped endpoint, exactly
 * the role the external routing service plays in production; nothing
 * inside the chain is mocked).
 *
 * <p><b>J24 (the measured laws):</b> a CONSUMER-born member with PROVIDER
 * standing (the platform's own role model — {@code UserService.register}
 * births every member CONSUMER, the provider upgrade adds the standing)
 * becomes a VERIFIED courier and opts into the automatic dispatch pool →
 * the consumer's quote is priced by the SERVER (the road route's distance
 * drives the fare and the courier's compensation — the module-test numbers
 * 12.5 km ⇒ 750 fare / 375 compensation reproduced over HTTP) → the
 * request is born AWAITING_PAYMENT with its OWN DELIVERY-origin intent on
 * the deterministic {@code delivery-{id}} key → the money loop (CONSUMER
 * process + ADMIN confirm) moves it to READY_FOR_DISPATCH through the real
 * listener and books the journal's CAPTURE → the automatic dispatch
 * scheduler assigns the available courier → the courier's guarded
 * transitions (PICKED_UP → IN_TRANSIT → DELIVERED, proof-gated) → the
 * journal recognizes the revenue and the courier's payable → the terminal
 * guards (409) and both parties' boards carry the delivered leg.
 *
 * <p><b>J24's cancellation branch:</b> a collected payment cancels into the
 * ONE refund contract (the intent REFUNDED through the delivery refund
 * listener's auto-refund, the journal's {@code REFUND:{cumulative}}
 * posting — the DELIVERY origin's dedicated mirror, never the generic
 * ledger's REFUND_DEBIT, which the delivery origin explicitly skips); an
 * uncollected payment cancels the intent CANCELLED with zero money moved
 * and zero journal rows; a second cancel is the honest idempotent 200.
 *
 * <p><b>J26 (the fleet-operations acceptance):</b> the suspended courier
 * cannot re-declare availability (the operator's verdict beats the
 * courier's own live signal — 409), reactivation never auto-restores
 * availability (the courier re-declares it), the carrier registry
 * declares ONLY the implemented capabilities (the platform fleet: ON_DEMAND
 * + PROOF_OF_DELIVERY — never a wish list), and the settlement
 * reconciliation reports per currency (quoted / collected / courier
 * payables / order-borne kept apart) over the ADMIN read.
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
        // The journey exercises the ENABLED deployment shape of the delivery
        // module: DeliveryProperties defaults enabled=false (the fail-closed
        // launch law — #604), and ensureConfigured() refuses every quote
        // unless the fare/compensation/service-area policy is complete.
        // The values are the module test's own measured policy (12.5 km ⇒
        // fare 100 + 13x50 = 750, compensation 50 + 13x25 = 375), and the
        // dispatch sweep is tightened from the 10s default so the automatic
        // assignment is measured within the journey's bounded wait.
        "marketplace.delivery.enabled=true",
        "marketplace.delivery.currency=SAR",
        "marketplace.delivery.base-fee-minor=100",
        "marketplace.delivery.per-kilometer-minor=50",
        "marketplace.delivery.max-distance-meters=20000",
        "marketplace.delivery.courier-base-compensation-minor=50",
        "marketplace.delivery.courier-compensation-per-kilometer-minor=25",
        "marketplace.delivery.dispatch-interval-ms=1000",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class DirectDeliveryJourneyIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static org.testcontainers.postgresql.PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    // ------------------------------------------------------------------
    // The configured road-routing endpoint (the journey's one external
    // dependency): the REAL OsrmDeliveryRouteProvider speaks OSRM HTTP to
    // whatever URL the routing-base-url property names — in production a
    // self-hosted OSRM instance, here a local endpoint answering the same
    // contract with the deterministic 12,500 m / 1,020 s route. Nothing in
    // the chain is mocked: the adapter's URI building, its JDK HttpClient
    // timeouts, its response validation and its metric rounding all run
    // for real — the local endpoint is the environment, the same role the
    // PostgreSQL container plays for the schema.
    // ------------------------------------------------------------------
    private static final HttpServer ROUTING_ENDPOINT;
    private static final int ROUTING_PORT;
    /** Every path the production adapter really crossed HTTP for (the coordinate-law measurement). */
    private static final java.util.List<String> ROUTE_PATHS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    static {
        try {
            ROUTING_ENDPOINT = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ROUTING_ENDPOINT.setExecutor(Executors.newFixedThreadPool(2));
            ROUTING_ENDPOINT.createContext("/", exchange -> {
                ROUTE_PATHS.add(exchange.getRequestURI().getPath());
                // The OSRM route contract the adapter validates: code "Ok",
                // at least one route with finite positive distance/duration.
                byte[] response = ("{\"code\":\"Ok\",\"routes\":[{\"distance\":12500.0,\"duration\":1020.0}]}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(response);
                }
            });
            ROUTING_ENDPOINT.start();
            ROUTING_PORT = ROUTING_ENDPOINT.getAddress().getPort();
        } catch (Exception ex) {
            throw new IllegalStateException("The journey's routing endpoint failed to boot", ex);
        }
    }

    @DynamicPropertySource
    static void routingEndpointProperties(DynamicPropertyRegistry registry) {
        // The configured routing base URL — read lazily by the real adapter
        // at every route() call, exactly like the production property.
        registry.add("marketplace.delivery.routing-base-url",
                () -> "http://127.0.0.1:" + ROUTING_PORT);
    }

    @AfterAll
    static void stopTheRoutingEndpoint() {
        // The cached application context's dispatch sweep only asks the
        // router when a READY_FOR_DISPATCH leg exists — after this class
        // no new legs arrive, so the stopped endpoint stays unreached.
        ROUTING_ENDPOINT.stop(0);
    }

    private static final String PASSWORD = "it-delivery-journey-password";
    private static final String LOGIN_PATH = "/login";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

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

    // ------------------------------------------------------------------
    // The journey's measured fare constants — derived once, from the
    // routing endpoint's 12,500 m (which the fare walk rounds UP to 13
    // kilometres): fare = 100 + 13x50 = 750 and compensation = 50 + 13x25
    // = 375 — the module test's own arithmetic, reproduced over HTTP.
    // ------------------------------------------------------------------

    private static final long EXPECTED_DISTANCE_METERS = 12_500L;
    private static final long EXPECTED_FARE_MINOR = 750L;
    private static final long EXPECTED_COMPENSATION_MINOR = 375L;

    /**
     * J24 (1) — the courier's full onboarding over the real surfaces: a
     * CONSUMER-born member with PROVIDER standing (both roles ride the
     * gate's token — the platform's own model), the provider profile, the
     * administrative VERIFIED trust decision, and the availability opt-in
     * that joins the automatic dispatch pool. Returns the courier's user
     * id (the dispatch pool's own key). The caller places the courier:
     * the journey's own courier stands beside the pickup point, the
     * fleet-acceptance courier stands far away — the sweep's nearest-first
     * choice (great-circle shortlist, then the router's ETA) stays
     * deterministic whichever method order JUnit picks.
     */
    private UUID onboardVerifiedCourier(GateResult courierGate, double latitude, double longitude)
            throws Exception {
        UUID courierUserId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE subject = ?", UUID.class, courierGate.username());
        assertThat(courierUserId).as("the courier's projection row").isNotNull();

        // The provider profile — born PENDING.
        HttpResponse<String> profile = postJson("/api/v1/providers", courierGate.accessToken(), """
                {"displayName":"Journey Fleet Courier","bio":"The journey's own courier"}""");
        assertThat(profile.statusCode()).as("courier provider profile: %s", body(profile)).isEqualTo(200);
        JsonNode profileView = objectMapper.readTree(profile.body());
        UUID providerId = UUID.fromString(profileView.path("id").asString());
        assertThat(profileView.path("status").asString()).isEqualTo("PENDING");

        // The administrative trust decision — the courier surfaces all gate
        // on VERIFIED (availability, assignment, transitions, the board).
        String adminName = "it-delivery-admin-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(adminName, "ADMIN");
        GateResult adminGate = loginGate(adminName, PASSWORD);
        HttpResponse<String> verify = postJson(
                "/api/v1/admin/providers/" + providerId + "/verify", adminGate.accessToken(), "");
        assertThat(verify.statusCode()).as("admin verify: %s", body(verify)).isEqualTo(200);
        assertThat(objectMapper.readTree(verify.body()).path("status").asString()).isEqualTo("VERIFIED");

        // The availability opt-in: coordinates + the live signal.
        HttpResponse<String> availability = postJsonOrPut(
                "/api/v1/deliveries/courier/availability", courierGate.accessToken(), """
                {"latitude":%s,"longitude":%s,"available":true}"""
                        .formatted(latitude, longitude), "PUT");
        assertThat(availability.statusCode()).as("courier availability: %s", body(availability)).isEqualTo(200);
        JsonNode availabilityView = objectMapper.readTree(availability.body());
        assertThat(availabilityView.path("available").asBoolean())
                .as("the live signal the dispatch pool reads").isTrue();
        assertThat(availabilityView.path("userId").asString()).isEqualTo(courierUserId.toString());
        return courierUserId;
    }

    /**
     * J24 (2) — the consumer's quote over HTTP: the server computes the
     * fare from the road route (the client's coordinates reach the REAL
     * routing adapter; the distance drives every money number).
     */
    private UUID quoteTheDirectLeg(GateResult consumerGate) throws Exception {
        HttpResponse<String> quote = postJson("/api/v1/deliveries/quotes", consumerGate.accessToken(), """
                {"pickupAddress":"Journey depot, Riyadh","pickupLatitude":24.7,"pickupLongitude":46.67,
                 "dropoffAddress":"Journey dropoff, Riyadh","dropoffLatitude":24.78,"dropoffLongitude":46.72,
                 "packageDescription":"The journey's parcel"}""");
        assertThat(quote.statusCode()).as("quote: %s", body(quote)).isEqualTo(201);
        JsonNode quoteView = objectMapper.readTree(quote.body());
        assertThat(quoteView.path("distanceMeters").asLong())
                .as("the road route's own distance (never the client's)").isEqualTo(EXPECTED_DISTANCE_METERS);
        assertThat(quoteView.path("estimatedDurationSeconds").asLong()).isEqualTo(1020L);
        assertThat(quoteView.path("amountMinor").asLong())
                .as("the server-computed fare: 100 + 13 km x 50").isEqualTo(EXPECTED_FARE_MINOR);
        assertThat(quoteView.path("courierCompensationMinor").asLong())
                .as("the server-computed compensation: 50 + 13 km x 25").isEqualTo(EXPECTED_COMPENSATION_MINOR);
        assertThat(quoteView.path("currency").asString()).isEqualTo("SAR");
        assertThat(quoteView.path("expiresAt").asString()).isNotBlank();
        // The production adapter really crossed HTTP with the quote's own
        // stops — OSRM's path carries longitude first (the adapter's own
        // contract), both stops at %.6f precision. The recorded-paths list
        // (never a last-path snapshot) keeps the measurement immune to any
        // concurrent sweep route call.
        assertThat(ROUTE_PATHS)
                .as("the real routing adapter asked for the quote's own stops")
                .anySatisfy(path -> assertThat(path)
                        .contains("46.670000,24.700000")
                        .contains("46.720000,24.780000"));
        return UUID.fromString(quoteView.path("id").asString());
    }

    /**
     * J24 (3) — the request from the quote: born AWAITING_PAYMENT with its
     * own DELIVERY-origin intent on the deterministic key, and the proof
     * code delivered exactly once (the create response is its only wire
     * appearance — the stored column carries the hash).
     */
    private JsonNode createTheDirectRequest(GateResult consumerGate, UUID quoteId) throws Exception {
        HttpResponse<String> created = postJson("/api/v1/deliveries/requests", consumerGate.accessToken(),
                "{\"quoteId\":\"" + quoteId + "\"}");
        assertThat(created.statusCode()).as("request: %s", body(created)).isEqualTo(201);
        JsonNode requestView = objectMapper.readTree(created.body());
        assertThat(requestView.path("status").asString()).isEqualTo("AWAITING_PAYMENT");
        assertThat(requestView.path("amountMinor").asLong()).isEqualTo(EXPECTED_FARE_MINOR);
        assertThat(requestView.path("currency").asString()).isEqualTo("SAR");
        assertThat(requestView.path("paymentIntentId").asString())
                .as("the request's own DELIVERY intent").isNotBlank();
        assertThat(requestView.path("proofCode").asString())
                .as("the proof code's ONLY wire appearance").isNotBlank();
        return requestView;
    }

    /**
     * J24 (4) — the money loop through the real payments engine: the
     * CONSUMER drives the intent to PROCESSING (the collect attempt — a
     * Payment row exists from here), the ADMIN captures it with an
     * external reference, and the SUCCEEDED read-back is the input the
     * delivery payment listener consumes asynchronously.
     */
    private void payTheDirectLeg(GateResult consumerGate, GateResult adminGate, UUID paymentIntentId)
            throws Exception {
        HttpResponse<String> process = postJson(
                "/api/v1/payments/intents/" + paymentIntentId + "/process", consumerGate.accessToken(), "");
        assertThat(process.statusCode()).as("process: %s", body(process)).isEqualTo(200);
        HttpResponse<String> confirm = postJson(
                "/api/v1/payments/intents/" + paymentIntentId + "/confirm", adminGate.accessToken(),
                "{\"externalId\":\"it-delivery-ext-" + UUID.randomUUID() + "\"}");
        assertThat(confirm.statusCode()).as("admin confirm: %s", body(confirm)).isEqualTo(200);
        assertThat(objectMapper.readTree(confirm.body()).path("status").asString()).isEqualTo("SUCCEEDED");
    }

    @Test
    void theFullDirectDeliveryJourneyFromQuoteToSettledProof() throws Exception {
        // (1) The courier: onboarding through the real trust chain.
        String courierName = "it-delivery-courier-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(courierName, "USER", "CONSUMER", "PROVIDER");
        GateResult courierGate = loginGate(courierName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", courierGate.accessToken()).statusCode())
                .as("the courier's /me provisioning sync").isEqualTo(200);
        UUID courierUserId = onboardVerifiedCourier(courierGate, 24.7136, 46.6753);

        // (2) The consumer: quote → request.
        String consumerName = "it-delivery-buyer-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(consumerName, "CONSUMER");
        GateResult consumerGate = loginGate(consumerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", consumerGate.accessToken()).statusCode())
                .as("the consumer's /me provisioning sync").isEqualTo(200);
        UUID quoteId = quoteTheDirectLeg(consumerGate);
        JsonNode requestView = createTheDirectRequest(consumerGate, quoteId);
        UUID requestId = UUID.fromString(requestView.path("id").asString());
        UUID paymentIntentId = UUID.fromString(requestView.path("paymentIntentId").asString());
        String proofCode = requestView.path("proofCode").asString();

        // (2b) The deterministic-key law: the intent's idempotency key IS
        // the request's own identity — the four siblings' pattern, the
        // engine's structural deduplication (a replay of the creation
        // can never open a second charge).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT idempotency_key FROM payment_intents WHERE id = ?", String.class, paymentIntentId))
                .as("the deterministic DELIVERY key").isEqualTo("delivery-" + requestId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT origin FROM payment_intents WHERE id = ?", String.class, paymentIntentId))
                .isEqualTo("DELIVERY");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_request_id FROM payment_intents WHERE id = ?", UUID.class, paymentIntentId))
                .isEqualTo(requestId);
        // The proof is STORED as its hash — the sha256 hex the transition
        // gate re-derives from the courier's proof code at delivery time.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT proof_code_hash FROM delivery_requests WHERE id = ?", String.class, requestId))
                .as("the stored proof: sha256(proofCode) hex")
                .isEqualTo(sha256Hex(proofCode));

        // (2c) The one-request-per-quote law (V227's partial unique is the
        // race backstop; the service's own read is the friendly edge): the
        // duplicate creation returns the SAME request — and the second
        // response carries NO proof code (the secret never re-crosses the
        // wire; the first response was its only appearance).
        HttpResponse<String> duplicate = postJson("/api/v1/deliveries/requests", consumerGate.accessToken(),
                "{\"quoteId\":\"" + quoteId + "\"}");
        assertThat(duplicate.statusCode()).as("duplicate request: %s", body(duplicate)).isEqualTo(201);
        JsonNode duplicateView = objectMapper.readTree(duplicate.body());
        assertThat(duplicateView.path("id").asString())
                .as("the quote's request is one — the duplicate is the same row").isEqualTo(requestId.toString());
        assertThat(duplicateView.path("proofCode").isNull())
                .as("the proof code never re-crosses the wire").isTrue();

        // (3) The money loop.
        String adminName = "it-delivery-pay-admin-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(adminName, "ADMIN");
        GateResult adminGate = loginGate(adminName, PASSWORD);
        payTheDirectLeg(consumerGate, adminGate, paymentIntentId);

        // (4) The real listener moves the request into the dispatch queue
        // (the AFTER_COMMIT hop is awaited — the typed poll below reads
        // the very status the assertions assert), and the ledger's
        // balanced delivery journal CAPTURES the collected fare.
        awaitDeliveryStatusIn(requestId, Set.of("READY_FOR_DISPATCH", "ASSIGNED"));
        awaitJournalOperation(requestId, "CAPTURE");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT amount_minor, currency, debit_account, credit_account FROM ledger_journal_postings "
                        + "WHERE source_type = 'DELIVERY' AND source_id = ? AND operation_key = 'CAPTURE'",
                requestId))
                .as("the CAPTURE posting: PSP_CLEARING -> CUSTOMER_ADVANCES, the collected 750 SAR")
                .containsEntry("amount_minor", EXPECTED_FARE_MINOR)
                .containsEntry("currency", "SAR")
                .containsEntry("debit_account", "PSP_CLEARING")
                .containsEntry("credit_account", "DELIVERY_CUSTOMER_ADVANCES");

        // (5) The automatic dispatch: the scheduler's sweep assigns the
        // available courier to the ready leg (the platform fleet's own
        // ON_DEMAND mode — the same sweep production runs).
        awaitAssignment(requestId, courierUserId);
        HttpResponse<String> assignedRead = getWithBearer(
                "/api/v1/deliveries/requests/" + requestId, consumerGate.accessToken());
        assertThat(assignedRead.statusCode()).as("assigned read: %s", body(assignedRead)).isEqualTo(200);
        JsonNode assignedView = objectMapper.readTree(assignedRead.body());
        assertThat(assignedView.path("status").asString()).isEqualTo("ASSIGNED");
        assertThat(assignedView.path("courierUserId").asString())
                .as("the assigned courier's identity on the request").isEqualTo(courierUserId.toString());

        // (6) The courier's guarded transitions — the machine's own edges
        // over the courier surface.
        HttpResponse<String> pickedUp = postJson("/api/v1/deliveries/requests/" + requestId + "/transition",
                courierGate.accessToken(), "{\"targetStatus\":\"PICKED_UP\"}");
        assertThat(pickedUp.statusCode()).as("picked up: %s", body(pickedUp)).isEqualTo(200);
        assertThat(objectMapper.readTree(pickedUp.body()).path("status").asString()).isEqualTo("PICKED_UP");
        HttpResponse<String> inTransit = postJson("/api/v1/deliveries/requests/" + requestId + "/transition",
                courierGate.accessToken(), "{\"targetStatus\":\"IN_TRANSIT\"}");
        assertThat(inTransit.statusCode()).as("in transit: %s", body(inTransit)).isEqualTo(200);
        assertThat(objectMapper.readTree(inTransit.body()).path("status").asString()).isEqualTo("IN_TRANSIT");

        // The proof gate FIRST: DELIVERED without the proof code is the
        // honest 403 — no delivery completes on a bare claim.
        HttpResponse<String> noProof = postJson("/api/v1/deliveries/requests/" + requestId + "/transition",
                courierGate.accessToken(), "{\"targetStatus\":\"DELIVERED\"}");
        assertThat(noProof.statusCode()).as("delivered without proof: %s", body(noProof)).isEqualTo(403);

        // DELIVERED with the proof: the delivery's terminal success, the
        // timestamp written by the machine.
        HttpResponse<String> delivered = postJson("/api/v1/deliveries/requests/" + requestId + "/transition",
                courierGate.accessToken(),
                "{\"targetStatus\":\"DELIVERED\",\"proofCode\":\"" + proofCode + "\"}");
        assertThat(delivered.statusCode()).as("delivered: %s", body(delivered)).isEqualTo(200);
        JsonNode deliveredView = objectMapper.readTree(delivered.body());
        assertThat(deliveredView.path("status").asString()).isEqualTo("DELIVERED");
        assertThat(deliveredView.path("deliveredAt").asString())
                .as("the delivery's own timestamp (V215's evidence CHECK)").isNotBlank();

        // (7) The settlement: the balanced delivery journal recognizes the
        // earned revenue and books the courier's payable — the two
        // AFTER_COMMIT listener hops are awaited (typed to the very rows
        // the assertions read).
        awaitJournalOperation(requestId, "RECOGNIZE");
        awaitJournalOperation(requestId, "COURIER_PAYABLE");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT amount_minor, currency, debit_account, credit_account FROM ledger_journal_postings "
                        + "WHERE source_type = 'DELIVERY' AND source_id = ? AND operation_key = 'RECOGNIZE'",
                requestId))
                .as("the RECOGNIZE posting: ADVANCES -> REVENUE, the whole collected fare")
                .containsEntry("amount_minor", EXPECTED_FARE_MINOR)
                .containsEntry("currency", "SAR")
                .containsEntry("debit_account", "DELIVERY_CUSTOMER_ADVANCES")
                .containsEntry("credit_account", "DELIVERY_REVENUE");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT amount_minor, currency, debit_account, credit_account, recipient_id "
                        + "FROM ledger_journal_postings WHERE source_type = 'DELIVERY' AND source_id = ? "
                        + "AND operation_key = 'COURIER_PAYABLE'",
                requestId))
                .as("the COURIER_PAYABLE posting: the earned compensation, its owner named")
                .containsEntry("amount_minor", EXPECTED_COMPENSATION_MINOR)
                .containsEntry("currency", "SAR")
                .containsEntry("debit_account", "DELIVERY_REVENUE")
                .containsEntry("credit_account", "DELIVERY_COURIER_PAYABLE")
                .containsEntry("recipient_id", courierUserId);
        // The generic provider ledger stays OUT of the delivery origin (the
        // dedicated journal owns it — LedgerPaymentEventListener's own
        // measured skip): no credit, no commission, no refund debit will
        // ever ride ledger_entries for this intent.
        Integer genericEntries = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE source_id = ?", Integer.class, paymentIntentId);
        assertThat(genericEntries)
                .as("the generic ledger never double-posts the delivery origin").isZero();

        // (8) The terminal guards: a delivered leg does not reopen — the
        // honest 409 on both surfaces.
        HttpResponse<String> lateTransition = postJson(
                "/api/v1/deliveries/requests/" + requestId + "/transition", courierGate.accessToken(),
                "{\"targetStatus\":\"IN_TRANSIT\"}");
        assertThat(lateTransition.statusCode()).as("transition after delivery: %s", body(lateTransition)).isEqualTo(409);
        HttpResponse<String> lateCancel = postJson(
                "/api/v1/deliveries/requests/" + requestId + "/cancel", consumerGate.accessToken(),
                "{\"reason\":\"too late\"}");
        assertThat(lateCancel.statusCode()).as("cancel after delivery: %s", body(lateCancel)).isEqualTo(409);

        // Both parties' boards carry the delivered leg: the courier's own
        // work list and the consumer's request list.
        HttpResponse<String> courierBoard = getWithBearer(
                "/api/v1/deliveries/courier/me", courierGate.accessToken());
        assertThat(courierBoard.statusCode()).as("courier board: %s", body(courierBoard)).isEqualTo(200);
        assertThat(objectMapper.readTree(courierBoard.body()).path("content").toString())
                .as("the courier's board carries the delivered leg").contains(requestId.toString());
        HttpResponse<String> consumerBoard = getWithBearer(
                "/api/v1/deliveries/me", consumerGate.accessToken());
        assertThat(consumerBoard.statusCode()).as("consumer board: %s", body(consumerBoard)).isEqualTo(200);
        assertThat(objectMapper.readTree(consumerBoard.body()).path("content").toString())
                .as("the consumer's board carries the delivered leg").contains(requestId.toString());
    }

    /**
     * J24's cancellation branch — the two money shapes a cancellation can
     * meet, each measured on the real chain:
     *
     * <ul>
     *   <li><b>the collected-payment cancel:</b> the ONE refund contract
     *       executes through the delivery refund listener (the intent
     *       REFUNDED with the full cumulative amount), and the DELIVERY
     *       journal mirrors the refund with its own
     *       {@code REFUND:{cumulative}} posting (ADVANCES back to
     *       CLEARING — the money was captured but never recognized);
     *       the GENERIC ledger's REFUND_DEBIT mirror stays absent for
     *       this origin (the dedicated journal owns delivery refunds —
     *       the origin-state matrix's own measured dispatch).</li>
     *   <li><b>the uncollected-payment cancel:</b> the intent resolves
     *       CANCELLED — no money ever moved, no journal row exists, and
     *       the delivery row is honestly CANCELLED.</li>
     * </ul>
     *
     * <p>The second cancel on an already-cancelled leg is the service's
     * own idempotent 200 (the state machine's early return) — measured
     * here honestly rather than assumed.
     */
    @Test
    void theCancellationBranchCollectedAndUncollected() throws Exception {
        String adminName = "it-delivery-cancel-admin-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(adminName, "ADMIN");
        GateResult adminGate = loginGate(adminName, PASSWORD);

        // (A) The collected-payment cancel.
        String consumerName = "it-delivery-cancel-buyer-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(consumerName, "CONSUMER");
        GateResult consumerGate = loginGate(consumerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", consumerGate.accessToken()).statusCode())
                .as("the consumer's /me provisioning sync").isEqualTo(200);
        UUID quoteId = quoteTheDirectLeg(consumerGate);
        JsonNode requestView = createTheDirectRequest(consumerGate, quoteId);
        UUID requestId = UUID.fromString(requestView.path("id").asString());
        UUID paymentIntentId = UUID.fromString(requestView.path("paymentIntentId").asString());

        payTheDirectLeg(consumerGate, adminGate, paymentIntentId);
        // The payment listener's hop: the leg is cancellable from
        // READY_FOR_DISPATCH (and from ASSIGNED — both are pre-pickup
        // states the machine admits; whichever the sweep reached first).
        awaitDeliveryStatusIn(requestId, Set.of("READY_FOR_DISPATCH", "ASSIGNED"));

        HttpResponse<String> cancellation = postJson(
                "/api/v1/deliveries/requests/" + requestId + "/cancel", consumerGate.accessToken(),
                "{\"reason\":\"buyer changed mind\"}");
        assertThat(cancellation.statusCode()).as("cancel: %s", body(cancellation)).isEqualTo(200);
        assertThat(objectMapper.readTree(cancellation.body()).path("status").asString()).isEqualTo("CANCELLED");

        // The ONE refund contract executes (the delivery refund listener's
        // auto-refund — two AFTER_COMMIT hops awaited: the intent's REFUNDED
        // mark, then the journal's mirror of it).
        awaitIntentStatus(paymentIntentId, "REFUNDED");
        assertThat(jdbcTemplate.queryForMap(
                "SELECT status, refunded_amount_cents, amount_cents FROM payment_intents WHERE id = ?",
                paymentIntentId))
                .as("the collected intent: fully refunded, the cumulative actual equals the fare")
                .containsEntry("status", "REFUNDED")
                .containsEntry("refunded_amount_cents", EXPECTED_FARE_MINOR)
                .containsEntry("amount_cents", EXPECTED_FARE_MINOR);

        // The DELIVERY journal's own refund mirror: REFUND:{cumulative},
        // the advances giving the money back to clearing (this leg was
        // never recognized — it never delivered).
        awaitJournalOperation(requestId, "REFUND:" + EXPECTED_FARE_MINOR);
        assertThat(jdbcTemplate.queryForMap(
                "SELECT amount_minor, currency, debit_account, credit_account, cumulative_refund_minor "
                        + "FROM ledger_journal_postings WHERE source_type = 'DELIVERY' AND source_id = ? "
                        + "AND operation_key = ?",
                requestId, "REFUND:" + EXPECTED_FARE_MINOR))
                .as("the refund posting: ADVANCES -> CLEARING, the full cumulative refund")
                .containsEntry("amount_minor", EXPECTED_FARE_MINOR)
                .containsEntry("currency", "SAR")
                .containsEntry("debit_account", "DELIVERY_CUSTOMER_ADVANCES")
                .containsEntry("credit_account", "PSP_CLEARING")
                .containsEntry("cumulative_refund_minor", EXPECTED_FARE_MINOR);

        // The GENERIC ledger's refund debit stays ABSENT for the delivery
        // origin — the dedicated journal is the one mirror this origin
        // owns (LedgerPaymentEventListener's own dispatch skips DELIVERY
        // on both the credit and the refund-debit halves). The
        // nameUUID("refund-" + intentId) source is the generic mirror's
        // own key: measured absent, exactly like the RMA journey measured
        // it PRESENT for its origin.
        UUID genericRefundSource = UUID.nameUUIDFromBytes(
                ("refund-" + paymentIntentId.toString()).getBytes(StandardCharsets.UTF_8));
        Integer genericRefundDebits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE entry_type = 'REFUND_DEBIT' AND source_id = ?",
                Integer.class, genericRefundSource);
        assertThat(genericRefundDebits)
                .as("no generic REFUND_DEBIT for the delivery origin (the dedicated journal owns it)").isZero();
        Integer genericEntries = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE source_id = ?", Integer.class, paymentIntentId);
        assertThat(genericEntries).as("no generic ledger movement at all for the delivery intent").isZero();

        // The second cancel: the service's own idempotent early return —
        // the honest measured law (a cancelled leg answers its own state,
        // not a second state change).
        HttpResponse<String> secondCancel = postJson(
                "/api/v1/deliveries/requests/" + requestId + "/cancel", consumerGate.accessToken(),
                "{\"reason\":\"twice\"}");
        assertThat(secondCancel.statusCode()).as("second cancel: %s", body(secondCancel)).isEqualTo(200);
        assertThat(objectMapper.readTree(secondCancel.body()).path("status").asString()).isEqualTo("CANCELLED");

        // (B) The uncollected-payment cancel: the intent resolves CANCELLED
        // — no money moved (no journal row exists for the leg).
        UUID unpaidQuoteId = quoteTheDirectLeg(consumerGate);
        JsonNode unpaidView = createTheDirectRequest(consumerGate, unpaidQuoteId);
        UUID unpaidRequestId = UUID.fromString(unpaidView.path("id").asString());
        UUID unpaidIntentId = UUID.fromString(unpaidView.path("paymentIntentId").asString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM delivery_requests WHERE id = ?", String.class, unpaidRequestId))
                .isEqualTo("AWAITING_PAYMENT");

        HttpResponse<String> unpaidCancel = postJson(
                "/api/v1/deliveries/requests/" + unpaidRequestId + "/cancel", consumerGate.accessToken(),
                "{\"reason\":\"never paid, cancelling\"}");
        assertThat(unpaidCancel.statusCode()).as("unpaid cancel: %s", body(unpaidCancel)).isEqualTo(200);
        assertThat(objectMapper.readTree(unpaidCancel.body()).path("status").asString()).isEqualTo("CANCELLED");

        awaitIntentStatus(unpaidIntentId, "CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT refunded_amount_cents FROM payment_intents WHERE id = ?",
                Long.class, unpaidIntentId))
                .as("the uncollected intent: nothing was ever refunded").isEqualTo(0L);
        Integer unpaidJournalRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_journal_postings WHERE source_type = 'DELIVERY' "
                        + "AND source_id = ?", Integer.class, unpaidRequestId);
        assertThat(unpaidJournalRows)
                .as("no money moved, no journal row: the delivery journal only books captured money").isZero();
    }

    /**
     * J26 — the fleet-operations acceptance (the plan §4 W8's operational
     * criteria, measured over the ADMIN surface):
     *
     * <ul>
     *   <li><b>the suspended courier</b> cannot re-activate their own
     *       availability — the operator's standing verdict beats the
     *       courier's live signal (409), reactivation never auto-restores
     *       availability (the courier re-declares it themselves), and the
     *       roster read carries the verdict's own words;</li>
     *   <li><b>the carrier registry</b> declares ONLY implemented
     *       capabilities — the platform fleet answers ON_DEMAND +
     *       PROOF_OF_DELIVERY and nothing else (no scheduling, no live
     *       tracking, no returns — the honest board the routing decisions
     *       read);</li>
     *   <li><b>the settlement reconciliation</b> reports per currency:
     *       the quoted charges, the collected amounts (quote-borne legs
     *       whose own intent completed), the courier payables, and the
     *       order-borne totals kept apart — measured on a driven scenario
     *       (one paid direct leg: 750 quoted, 750 collected, 375 payable)
     *       plus the window's own shape law (an inverted window answers
     *       the honest 400).</li>
     * </ul>
     */
    @Test
    void theFleetOperationsAcceptance() throws Exception {
        // (1) A dedicated courier for the operational verdicts.
        String courierName = "it-fleet-courier-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(courierName, "USER", "CONSUMER", "PROVIDER");
        GateResult courierGate = loginGate(courierName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", courierGate.accessToken()).statusCode())
                .as("the courier's /me provisioning sync").isEqualTo(200);
        UUID courierUserId = onboardVerifiedCourier(courierGate, 24.7136, 46.6753);

        String adminName = "it-fleet-admin-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(adminName, "ADMIN");
        GateResult adminGate = loginGate(adminName, PASSWORD);

        // The suspension: the operator's standing verdict.
        HttpResponse<String> suspension = postJson(
                "/api/v1/deliveries/admin/couriers/" + courierUserId + "/suspend", adminGate.accessToken(),
                "{\"reason\":\"safety incident on the last leg\"}");
        assertThat(suspension.statusCode()).as("suspend: %s", body(suspension)).isEqualTo(200);
        JsonNode suspendedView = objectMapper.readTree(suspension.body());
        assertThat(suspendedView.path("status").asString()).isEqualTo("SUSPENDED");
        assertThat(suspendedView.path("available").asBoolean())
                .as("the verdict clears the live signal").isFalse();
        assertThat(suspendedView.path("statusReason").asString())
                .as("the verdict's own words ride the row").isEqualTo("safety incident on the last leg");

        // The measured law the plan names explicitly («منع المندوب الموقوف
        // من إعادة تفعيل التوفر آليًا»): the suspended courier's OWN
        // availability write is refused — the courier cannot walk the
        // verdict back by re-declaring the signal themselves.
        HttpResponse<String> suspendedReavail = postJsonOrPut(
                "/api/v1/deliveries/courier/availability", courierGate.accessToken(), """
                {"latitude":24.0,"longitude":46.0,"available":true}""", "PUT");
        assertThat(suspendedReavail.statusCode())
                .as("the suspended courier re-declaring availability: %s", body(suspendedReavail)).isEqualTo(409);
        // An availability WITHDRAWAL under suspension is still the
        // courier's own live signal to manage (available=false carries no
        // dispatch eligibility — the guard refuses only the re-activation).
        HttpResponse<String> suspendedWithdraw = postJsonOrPut(
                "/api/v1/deliveries/courier/availability", courierGate.accessToken(), """
                {"latitude":24.0,"longitude":46.0,"available":false}""", "PUT");
        assertThat(suspendedWithdraw.statusCode())
                .as("the suspended courier withdrawing their own signal: %s", body(suspendedWithdraw)).isEqualTo(200);

        // The roster read carries the verdict and its words.
        HttpResponse<String> roster = getWithBearer(
                "/api/v1/deliveries/admin/couriers", adminGate.accessToken());
        assertThat(roster.statusCode()).as("roster: %s", body(roster)).isEqualTo(200);
        JsonNode rosterRow = null;
        for (JsonNode row : objectMapper.readTree(roster.body()).path("content")) {
            if (courierUserId.toString().equals(row.path("userId").asString())) {
                rosterRow = row;
            }
        }
        assertThat(rosterRow).as("the roster carries the suspended courier").isNotNull();
        assertThat(rosterRow.path("status").asString()).isEqualTo("SUSPENDED");
        assertThat(rosterRow.path("statusReason").asString()).isEqualTo("safety incident on the last leg");

        // The reactivation: the operator's reversal — availability is
        // NEVER auto-restored (the courier re-declares it themselves).
        HttpResponse<String> reactivation = postJson(
                "/api/v1/deliveries/admin/couriers/" + courierUserId + "/reactivate",
                adminGate.accessToken(), "");
        assertThat(reactivation.statusCode()).as("reactivate: %s", body(reactivation)).isEqualTo(200);
        JsonNode reactivatedView = objectMapper.readTree(reactivation.body());
        assertThat(reactivatedView.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(reactivatedView.path("available").asBoolean())
                .as("reactivation never auto-restores the live signal").isFalse();
        assertThat(reactivatedView.path("statusReason").isNull())
                .as("the verdict's words are cleared with the verdict").isTrue();

        // The full recovery arc: the reactivated courier re-declares.
        HttpResponse<String> redeclared = postJsonOrPut(
                "/api/v1/deliveries/courier/availability", courierGate.accessToken(), """
                {"latitude":24.0,"longitude":46.0,"available":true}""", "PUT");
        assertThat(redeclared.statusCode()).as("the re-declared signal: %s", body(redeclared)).isEqualTo(200);
        assertThat(objectMapper.readTree(redeclared.body()).path("available").asBoolean()).isTrue();

        // (2) The carrier registry: the honest declared-capabilities board.
        HttpResponse<String> carriers = getWithBearer(
                "/api/v1/deliveries/admin/carriers", adminGate.accessToken());
        assertThat(carriers.statusCode()).as("carriers: %s", body(carriers)).isEqualTo(200);
        JsonNode carrierList = objectMapper.readTree(carriers.body());
        assertThat(carrierList.size()).as("the platform fleet is the launch carrier").isEqualTo(1);
        JsonNode platform = carrierList.path(0);
        assertThat(platform.path("code").asString()).isEqualTo("PLATFORM");
        assertThat(platform.path("capabilities").toString())
                .as("ONLY the implemented capabilities are declared — ON_DEMAND and PROOF_OF_DELIVERY; "
                        + "SCHEDULED, LIVE_TRACKING and RETURNS stay off the board until their adapters exist")
                .isEqualTo("[\"ON_DEMAND\",\"PROOF_OF_DELIVERY\"]");

        // (3) The settlement reconciliation on a driven scenario: one paid
        // direct leg inside a fresh window — quoted, collected and the
        // courier payable per currency, the order-borne family empty (the
        // direct path's legs always carry their own intents).
        Instant windowFrom = Instant.now();
        String consumerName = "it-fleet-buyer-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(consumerName, "CONSUMER");
        GateResult consumerGate = loginGate(consumerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", consumerGate.accessToken()).statusCode())
                .as("the consumer's /me provisioning sync").isEqualTo(200);
        UUID quoteId = quoteTheDirectLeg(consumerGate);
        JsonNode requestView = createTheDirectRequest(consumerGate, quoteId);
        UUID paidRequestId = UUID.fromString(requestView.path("id").asString());
        UUID paymentIntentId = UUID.fromString(requestView.path("paymentIntentId").asString());
        payTheDirectLeg(consumerGate, adminGate, paymentIntentId);
        awaitDeliveryStatusIn(paidRequestId, Set.of("READY_FOR_DISPATCH", "ASSIGNED"));
        Instant windowTo = Instant.now();

        HttpResponse<String> report = getWithBearer(
                "/api/v1/deliveries/admin/reconciliation?from=" + encode(windowFrom.toString())
                        + "&to=" + encode(windowTo.toString()),
                adminGate.accessToken());
        assertThat(report.statusCode()).as("reconciliation: %s", body(report)).isEqualTo(200);
        JsonNode reportView = objectMapper.readTree(report.body());
        assertThat(reportView.path("legs").asInt())
                .as("the window carries exactly the driven leg").isEqualTo(1);
        assertThat(reportView.path("quoted").toString())
                .as("the quoted charge per currency: the leg's fare")
                .isEqualTo("[{\"currency\":\"SAR\",\"amountMinor\":" + EXPECTED_FARE_MINOR + "}]");
        assertThat(reportView.path("collected").toString())
                .as("the collected amount per currency: the quote-borne leg's own SUCCEEDED intent")
                .isEqualTo("[{\"currency\":\"SAR\",\"amountMinor\":" + EXPECTED_FARE_MINOR + "}]");
        assertThat(reportView.path("courierPayables").toString())
                .as("the courier payable per currency: the leg's compensation")
                .isEqualTo("[{\"currency\":\"SAR\",\"amountMinor\":" + EXPECTED_COMPENSATION_MINOR + "}]");
        assertThat(reportView.path("orderBorneQuoted").isArray()).isTrue();
        assertThat(reportView.path("orderBorneQuoted").size())
                .as("the direct path's legs never ride their orders' intents").isZero();

        // The window's own shape law: an inverted window answers the
        // honest 400 (never a silently empty report).
        HttpResponse<String> invertedWindow = getWithBearer(
                "/api/v1/deliveries/admin/reconciliation?from=" + encode(windowTo.toString())
                        + "&to=" + encode(windowFrom.toString()),
                adminGate.accessToken());
        assertThat(invertedWindow.statusCode())
                .as("the inverted window: %s", body(invertedWindow)).isEqualTo(400);

        // The scenario's own close (the fleet's hygiene — and one more
        // measured cancel): the consumer cancels the window's leg BEFORE
        // any courier picks it up, so the shared class database never
        // carries a dispatchable leg into the next journey method
        // (whichever order JUnit picks) and no courier is left holding a
        // hostage assignment.
        HttpResponse<String> scenarioCancel = postJson(
                "/api/v1/deliveries/requests/" + paidRequestId + "/cancel", consumerGate.accessToken(),
                "{\"reason\":\"scenario complete\"}");
        assertThat(scenarioCancel.statusCode()).as("scenario cancel: %s", body(scenarioCancel)).isEqualTo(200);
        assertThat(objectMapper.readTree(scenarioCancel.body()).path("status").asString())
                .isEqualTo("CANCELLED");
    }

    // ------------------------------------------------------------------
    // The bounded typed polls (the house shape: 200 ms steps, a generous
    // but bounded ceiling — every listener-driven effect is awaited on the
    // very row the assertion below reads, never assumed).
    // ------------------------------------------------------------------

    /** Awaits the delivery request's status joining one of the expected states. */
    private void awaitDeliveryStatusIn(UUID requestId, Set<String> expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM delivery_requests WHERE id = ?", String.class, requestId);
            if (expected.contains(status)) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("delivery request " + requestId + " never reached " + expected
                + " within 20s");
    }

    /**
     * Awaits the automatic dispatch's assignment: the sweep (1 s interval
     * in this journey's deployment shape) claims the ready leg for the
     * available courier — the platform fleet's own ON_DEMAND mode.
     */
    private void awaitAssignment(UUID requestId, UUID courierUserId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            var row = jdbcTemplate.queryForMap(
                    "SELECT status, courier_user_id FROM delivery_requests WHERE id = ?", requestId);
            if ("ASSIGNED".equals(row.get("status")) && courierUserId.equals(row.get("courier_user_id"))) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("delivery request " + requestId
                + " was never assigned to courier " + courierUserId + " within 20s");
    }

    /** Awaits a payment intent's status (the money loop's listener hops). */
    private void awaitIntentStatus(UUID intentId, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM payment_intents WHERE id = ?", String.class, intentId);
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("payment intent " + intentId + " did not reach " + expected
                + " within 15s");
    }

    /**
     * Awaits a balanced delivery journal posting (the CAPTURE/RECOGNIZE/
     * COURIER_PAYABLE/REFUND operations — each written by its own
     * AFTER_COMMIT listener hop on the real Modulith chain).
     */
    private void awaitJournalOperation(UUID sourceId, String operationKey) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM ledger_journal_postings WHERE source_type = 'DELIVERY' "
                            + "AND source_id = ? AND operation_key = ?",
                    Integer.class, sourceId, operationKey);
            if (rows != null && rows >= 1) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("journal operation " + operationKey + " for delivery " + sourceId
                + " never appeared within 15s");
    }

    /** The proof hash the transition gate re-derives (sha256 hex, lowercase). */
    private static String sha256Hex(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(64);
        for (byte b : bytes) {
            result.append(String.format("%02x", b));
        }
        return result.toString();
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

    private record GateResult(String accessToken, String sessionCookie, String username) {
    }

    private GateResult loginGate(String username, String password) throws Exception {
        AuthorizationServerFixture.registerLoginGateClient(registeredClientRepository,
                "Delivery Journey Gate Integration Test Client");
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
        return new GateResult(tokens.path("access_token").asString(), sessionCookie, username);
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

    private HttpResponse<String> postJsonOrPut(String path, String accessToken, String json,
                                                String method) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
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

    private byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private String randomCodeVerifier() {
        return base64Url(UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8));
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String body(HttpResponse<String> response) {
        // 2000 (the house window): a ProblemDetail's fieldErrors — the piece
        // that names the violated constraint — live past the traceId.
        return response.body() == null ? "" : response.body().substring(0, Math.min(2000, response.body().length()));
    }
}
