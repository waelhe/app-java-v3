package com.marketplace.delivery;

import test.config.IntegrationContainers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
import static org.mockito.BDDMockito.given;
import static test.config.AuthorizationServerFixture.AUTHORIZE_PATH;
import static test.config.AuthorizationServerFixture.CLIENT_ID;
import static test.config.AuthorizationServerFixture.CLIENT_SECRET;
import static test.config.AuthorizationServerFixture.REDIRECT_URI;
import static test.config.AuthorizationServerFixture.TOKEN_PATH;

/**
 * J25 (the unified plan §3's journey register, the W8 wave) — the
 * store-order delivery leg's composite journey: «مركّب الرجل من الطابور
 * إلى الإثبات والإتمام» — from the checkout's FROZEN delivery choice,
 * through the money loop, the confirmation edge that creates the leg, the
 * dispatch queue, the courier's proof, to the once-only order completion
 * — measured end-to-end on the real chain: real PostgreSQL + real Redis +
 * the real Flyway schema (V215's delivery tables, V218's order handoff,
 * V224-V228's frozen selection and legs indexes) + the real login gate's
 * Bearer + the real Modulith event machinery (the OrdersPaymentListener's
 * auto-confirm, the OrderFulfillmentListener's leg creation, the
 * OrdersDeliveryEventListener's completion edge) + the REAL dispatch
 * scheduler sweeping at a 500ms cadence.
 *
 * <p><b>The one external boundary this journey stubs:</b> the OSRM
 * road-routing engine — exactly the class of external service the payments
 * journeys' PSP channel belongs to. {@link DeliveryRouteProvider} is
 * answered deterministically (a fixed 10&nbsp;km route; a longitude ≥ 50
 * dropoff marker answers an out-of-area 500&nbsp;km route for the
 * unserviceable-refusal guard). Everything else is real.
 *
 * <p><b>The delivery policy the journey configures</b> (the production
 * default is <i>disabled-by-default</i> — {@code DeliveryProperties}
 * {@code @DefaultValue("false")}; no profile enables it, so the test
 * properties carry the full policy the vertical requires):
 * base&nbsp;2000 + 200/km and courier compensation 500 + 50/km over the
 * stubbed 10&nbsp;km route freeze a 4000-minor shipping fee with a
 * 1000-minor courier compensation — the margin the split arithmetic
 * carves.
 *
 * <p><b>Two measured laws this journey is SHAPED by (documented honestly,
 * never invented around):</b>
 * <ol>
 *   <li><b>The checkout leg's proof code is unreachable</b>
 *       (G-J25-a): {@code DeliveryService.createOrderLegFromFulfillment}
 *       generates the proof code and returns it in its response — but its
 *       only caller, the {@code OrderFulfillmentListener}, discards the
 *       return value, and no HTTP read, admin surface, or notification
 *       ever exposes the code afterward (every read passes
 *       {@code DeliveryRequestResponse.from(request, null)}). Over HTTP
 *       the courier can therefore never present a valid proof for the
 *       checkout-created BASE leg. The operator's split command
 *       ({@code POST /admin/orders/{orderId}/legs}) DOES return the new
 *       leg's proof code — so the composite proves the completion law
 *       through the split legs, and the base leg takes the buyer's
 *       cancellation exit (legal from READY_FOR_DISPATCH).</li>
 *   <li><b>A checkout leg's completion is never journaled</b>
 *       (G-J25-b): the delivery journal's recognition
 *       ({@code LedgerJournalService.recognize}) requires the leg's own
 *       captured DELIVERY-origin payment intent — the #604 direct-delivery
 *       money shape. A checkout leg's fee rode the ORDER's single intent
 *       (its {@code payment_intent_id} is NULL by design — the
 *       no-second-charge law), so the recognition listener rejects the
 *       completion event ({@code IllegalArgumentException} on the null
 *       payment id) in its own AFTER_COMMIT unit. The ORDER completion —
 *       a separate listener — is unaffected; the courier compensation is
 *       simply never booked for checkout legs.</li>
 * </ol>
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
        // The delivery vertical's policy — the journey's own subject. The
        // production default is disabled-by-default; a leg cannot exist
        // without the flag, the fare policy and the compensation margin
        // (CheckoutDeliveryAdapter refuses the same misconfiguration the
        // leg creation refuses — the parity law).
        "marketplace.delivery.enabled=true",
        "marketplace.delivery.currency=SAR",
        "marketplace.delivery.base-fee-minor=2000",
        "marketplace.delivery.per-kilometer-minor=200",
        "marketplace.delivery.max-distance-meters=30000",
        // Inert by intent: the routing BEAN is the journey's one stub; the
        // base URL only has to be non-blank for ensureConfigured's gate.
        "marketplace.delivery.routing-base-url=http://127.0.0.1:9/osrm-stubbed-for-j25",
        "marketplace.delivery.courier-base-compensation-minor=500",
        "marketplace.delivery.courier-compensation-per-kilometer-minor=50",
        // The whole journey must fit inside the courier's location
        // freshness window (the re-declared availability renews it anyway).
        "marketplace.delivery.courier-location-max-age-seconds=600",
        // The REAL dispatch scheduler's sweep cadence — fast enough that
        // the assignment awaits below resolve in bounded time.
        "marketplace.delivery.dispatch-interval-ms=500",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class OrderLegDeliveryJourneyIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static org.testcontainers.postgresql.PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    /**
     * The road-routing engine — the journey's one stubbed external boundary
     * (the OSRM HTTP service, the same class of external dependency the
     * payments journeys' PSP channel occupies). Deterministic answers keep
     * the frozen fee arithmetic exact: every route inside the service area
     * is 10 km / 900 s; a dropoff at longitude ≥ 50 is the out-of-area
     * marker (500 km) the unserviceable-refusal guard rides.
     */
    @MockitoBean
    DeliveryRouteProvider routeProvider;

    private static final long ROUTE_DISTANCE_METERS = 10_000L;
    private static final long ROUTE_DURATION_SECONDS = 900L;
    /** The stub's fixed route freezes: fee = 2000 + 200 × 10 km = 4000. */
    private static final long FROZEN_SHIPPING_FEE_MINOR = 4_000L;
    /** The out-of-area marker: dropoffs at this longitude answer 500 km. */
    private static final double FAR_DESTINATION_LONGITUDE = 50.0;
    private static final double SELLER_LATITUDE = 24.7000;
    private static final double SELLER_LONGITUDE = 46.6800;
    private static final double BUYER_LATITUDE = 24.7136;
    private static final double BUYER_LONGITUDE = 46.6753;

    private static final String PASSWORD = "it-order-leg-journey-password";
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

    @BeforeEach
    void seedTheStoreDictionaryAndTheRoadRouter() {
        // The journey's category arrives as DATA (the plan's own wording —
        // never a migration), seeded by the class itself: the register path
        // validates the code against the live dictionary.
        jdbcTemplate.update("INSERT INTO store_categories (id, code, name_en, name_ar, position) "
                        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (code) DO NOTHING",
                UUID.randomUUID(), "journey-parcel", "Parcel goods", "سلع طرود", 0);
        // The deterministic road router (see the field's javadoc). The
        // MockitoBean reset between tests makes the @BeforeEach re-apply
        // the law each time.
        given(routeProvider.route(ArgumentMatchers.anyDouble(), ArgumentMatchers.anyDouble(),
                        ArgumentMatchers.anyDouble(), ArgumentMatchers.anyDouble()))
                .willAnswer(invocation -> {
                    double dropoffLongitude = invocation.getArgument(3);
                    if (dropoffLongitude >= FAR_DESTINATION_LONGITUDE) {
                        return new DeliveryRouteMetrics(500_000L, 36_000L);
                    }
                    return new DeliveryRouteMetrics(ROUTE_DISTANCE_METERS, ROUTE_DURATION_SECONDS);
                });
    }

    /**
     * J25's main composite: the frozen checkout choice → the money loop →
     * the base leg born dispatch-ready with NO payment of its own (the
     * no-second-charge law) → the operator's splits carving the frozen fee
     * → the REAL scheduler's assignment → the courier's proof-guarded
     * transitions → the multi-leg completion WAIT (the first delivered leg
     * does not complete an order with a sibling still in flight) → the
     * once-only completion when the LAST leg delivers → the terminal
     * guards. Plus the honest refusals along the way: the unserviceable
     * destination refuses the whole placement with the stock untouched,
     * the greedy split refuses beyond the frozen ceiling, the seller's
     * manual fulfill refuses while the order rides an active delivery, and
     * a delivered leg without the proof answers 403.
     */
    @Test
    void theOrderLegJourneyFromFrozenChoiceThroughProofToOnceOnlyCompletion() throws Exception {
        // ---- The seller's provisioning: the store records AND the delivery
        // pickup. The checkout resolves the platform-delivery fare from the
        // VERIFIED seller's private fulfillment location — the profile, the
        // administrative verification and the pickup write all precede the
        // buyer's cart.
        String sellerName = "it-leg-seller-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(sellerName, "USER", "PROVIDER", "CONSUMER");
        GateResult sellerGate = loginGate(sellerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", sellerGate.accessToken()).statusCode())
                .as("the seller's /me provisioning sync").isEqualTo(200);
        UUID sellerProductId = registerProduct(sellerGate.accessToken(), 1500L);
        setStock(sellerGate.accessToken(), sellerProductId, 10);
        UUID sellerProviderId = becomeVerifiedProvider(sellerGate.accessToken(), "Journey Seller Depot");

        String adminName = "it-leg-admin-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(adminName, "USER", "ADMIN");
        GateResult adminGate = loginGate(adminName, PASSWORD);
        assertThat(postJson("/api/v1/admin/providers/" + sellerProviderId + "/verify",
                adminGate.accessToken(), "").statusCode())
                .as("the seller's administrative verification (the delivery pickup's gate)").isEqualTo(200);
        assertThat(putJson("/api/v1/providers/me/fulfillment-location", sellerGate.accessToken(), """
                {"address":"Journey Seller Depot","latitude":%s,"longitude":%s}"""
                .formatted(SELLER_LATITUDE, SELLER_LONGITUDE)).statusCode())
                .as("the seller's private pickup point").isEqualTo(200);

        // ---- The buyer: two units of the 1500-minor product → a 3000
        // subtotal the frozen 4000 fee joins into a 7000 agreement.
        String buyerName = "it-leg-buyer-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(buyerName, "USER", "CONSUMER");
        GateResult gate = loginGate(buyerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", gate.accessToken()).statusCode())
                .as("the buyer's /me provisioning sync").isEqualTo(200);
        postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","quantity":2}"""
                .formatted(sellerProductId));

        // (1) The honest unserviceable refusal FIRST: a destination outside
        // the configured area refuses the WHOLE placement — the fee is a
        // synchronous prerequisite of the agreement, exactly like stock,
        // so nothing moves: no order row, no reservation, the cart lives.
        HttpResponse<String> farPlacement = postJson("/api/v1/orders", gate.accessToken(), """
                {"fulfillment":{"method":"PLATFORM_DELIVERY","destinationAddress":"Far away",
                 "destinationLatitude":24.0,"destinationLongitude":%s}}"""
                .formatted(FAR_DESTINATION_LONGITUDE));
        assertThat(farPlacement.statusCode())
                .as("the out-of-area destination refuses the placement: %s", body(farPlacement)).isEqualTo(409);
        assertThat(farPlacement.body()).contains("no platform delivery");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM products WHERE id = ?", Integer.class, sellerProductId))
                .as("the refused placement reserved nothing").isZero();
        assertThat(objectMapper.readTree(
                        getWithBearer("/api/v1/me/cart", gate.accessToken()).body()).size())
                .as("the refused placement kept the cart alive").isEqualTo(1);

        // (2) The placement WITH the delivery choice: the fare freezes into
        // the order — the fee rides the total, the destination snapshot
        // rides the row, the method rides the selection.
        HttpResponse<String> placement = postJson("/api/v1/orders", gate.accessToken(), """
                {"fulfillment":{"method":"PLATFORM_DELIVERY","destinationAddress":"Riyadh downtown",
                 "destinationLatitude":%s,"destinationLongitude":%s}}"""
                .formatted(BUYER_LATITUDE, BUYER_LONGITUDE));
        assertThat(placement.statusCode()).as("placement: %s", body(placement)).isEqualTo(201);
        JsonNode order = objectMapper.readTree(placement.body());
        UUID orderId = UUID.fromString(order.path("id").asString());
        assertThat(order.path("status").asString()).isEqualTo("PLACED");
        assertThat(order.path("fulfillmentMethod").asString()).isEqualTo("PLATFORM_DELIVERY");
        assertThat(order.path("shippingAmountMinor").asLong())
                .as("the frozen platform-delivery fee: 2000 + 200 × 10 km").isEqualTo(FROZEN_SHIPPING_FEE_MINOR);
        assertThat(order.path("totalAmountMinor").asLong())
                .as("the fee joins the agreement: 2 × 1500 + 4000").isEqualTo(7_000L);
        assertThat(order.path("shipToAddress").asString()).isEqualTo("Riyadh downtown");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM products WHERE id = ?", Integer.class, sellerProductId))
                .as("the placement's reservation").isEqualTo(2);

        // (3) The money loop — the ORDER's ONE intent carries the whole
        // agreement INCLUDING the shipping (the no-second-charge law): the
        // buyer processes, the administrator confirms, and the settlement's
        // COMPLETED event auto-confirms the machine through the real
        // OrdersPaymentListener.
        HttpResponse<String> intentResponse = postJson(
                "/api/v1/orders/" + orderId + "/payment-intent", gate.accessToken(), "");
        assertThat(intentResponse.statusCode()).as("intent: %s", body(intentResponse)).isEqualTo(201);
        JsonNode intent = objectMapper.readTree(intentResponse.body());
        UUID intentId = UUID.fromString(intent.path("paymentIntentId").asString());
        assertThat(intent.path("origin").asString()).isEqualTo("ORDER");
        assertThat(intent.path("amountCents").asLong())
                .as("the single intent carries the subtotal AND the frozen fee").isEqualTo(7_000L);
        HttpResponse<String> process = postJson(
                "/api/v1/payments/intents/" + intentId + "/process", gate.accessToken(), "");
        assertThat(process.statusCode()).as("the buyer processes the intent: %s", body(process)).isEqualTo(200);
        HttpResponse<String> confirm = postJson(
                "/api/v1/payments/intents/" + intentId + "/confirm", adminGate.accessToken(),
                "{\"externalId\":\"it-leg-journey-ext-" + UUID.randomUUID() + "\"}");
        assertThat(confirm.statusCode()).as("the administrative confirm: %s", body(confirm)).isEqualTo(200);
        awaitOrderStatus(orderId, "CONFIRMED");

        // (4) The confirmation edge creates the leg from the FROZEN
        // selection — the real OrderFulfillmentListener's AFTER_COMMIT
        // write, awaited by its own row. The leg is born
        // READY_FOR_DISPATCH (the fee already rode the order's intent —
        // no AWAITING_PAYMENT hop, no delivery intent of its own), and it
        // becomes the order's primary link.
        awaitLegCount(orderId, 1);
        HttpResponse<String> legsRead = getWithBearer(
                "/api/v1/deliveries/admin/orders/" + orderId + "/legs", adminGate.accessToken());
        assertThat(legsRead.statusCode()).as("the operator's legs read: %s", body(legsRead)).isEqualTo(200);
        JsonNode legs = objectMapper.readTree(legsRead.body());
        assertThat(legs.size()).isEqualTo(1);
        UUID baseLegId = UUID.fromString(legs.get(0).path("id").asString());
        assertThat(legs.get(0).path("status").asString())
                .as("the checkout leg is born dispatch-ready — the payment already rode the order")
                .isEqualTo("READY_FOR_DISPATCH");
        assertThat(legs.get(0).path("paymentIntentId").isNull())
                .as("the leg owns NO payment intent of its own (the frozen fee never charges twice)").isTrue();
        assertThat(legs.get(0).path("amountMinor").asLong())
                .as("the leg carries the frozen shipping fee").isEqualTo(FROZEN_SHIPPING_FEE_MINOR);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_request_id FROM orders WHERE id = ?", String.class, orderId))
                .as("the first leg is the order's primary link (V218)").isEqualTo(baseLegId.toString());
        // The single collection, measured: exactly one intent holds this
        // order's money, and no DELIVERY-origin intent exists for the leg.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_intents WHERE order_id = ?", Integer.class, orderId))
                .as("exactly one intent for the order's money").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_intents WHERE delivery_request_id = ?",
                Integer.class, baseLegId))
                .as("no delivery-origin intent for the checkout leg").isZero();
        // The ORDER settlement's ledger footprint: the seller's credit for
        // the WHOLE agreement (items + shipping — the single collection's
        // other face) and the announced commission's nameUUID key.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE source_id = ?", Integer.class, intentId))
                .as("the confirm's seller credit rides the intent's own source").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE source_id = ?", Integer.class,
                UUID.nameUUIDFromBytes(("commission-" + intentId).getBytes(StandardCharsets.UTF_8))))
                .as("the frozen commission's nameUUID key").isEqualTo(1);

        // (5) The operator's multi-leg splits: each share is CARVED out of
        // the primary leg — the legs' sum stays the buyer's frozen fee BY
        // CONSTRUCTION, and every split carries its own proof code (the
        // one surface that answers a leg's proof over HTTP).
        HttpResponse<String> splitOne = postJson(
                "/api/v1/deliveries/admin/orders/" + orderId + "/legs", adminGate.accessToken(), """
                        {"packageDescription":"split package one","amountMinor":1500}""");
        assertThat(splitOne.statusCode()).as("the first split: %s", body(splitOne)).isEqualTo(201);
        JsonNode splitOneView = objectMapper.readTree(splitOne.body());
        UUID splitOneId = UUID.fromString(splitOneView.path("id").asString());
        String splitOneProof = splitOneView.path("proofCode").asString();
        assertThat(splitOneProof).as("the split command answers the leg's proof code").isNotBlank();
        HttpResponse<String> splitTwo = postJson(
                "/api/v1/deliveries/admin/orders/" + orderId + "/legs", adminGate.accessToken(), """
                        {"packageDescription":"split package two","amountMinor":1400}""");
        assertThat(splitTwo.statusCode()).as("the second split: %s", body(splitTwo)).isEqualTo(201);
        JsonNode splitTwoView = objectMapper.readTree(splitTwo.body());
        UUID splitTwoId = UUID.fromString(splitTwoView.path("id").asString());
        String splitTwoProof = splitTwoView.path("proofCode").asString();
        assertThat(splitTwoProof).isNotBlank();

        // The carve law, measured: 1100 + 1500 + 1400 = the frozen 4000.
        JsonNode afterSplits = objectMapper.readTree(getWithBearer(
                "/api/v1/deliveries/admin/orders/" + orderId + "/legs", adminGate.accessToken()).body());
        assertThat(afterSplits.size()).isEqualTo(3);
        assertThat(afterSplits.get(0).path("amountMinor").asLong())
                .as("the primary keeps the frozen fee minus the carves").isEqualTo(1_100L);
        assertThat(afterSplits.get(1).path("amountMinor").asLong()).isEqualTo(1_500L);
        assertThat(afterSplits.get(2).path("amountMinor").asLong()).isEqualTo(1_400L);
        assertThat(afterSplits.get(0).path("amountMinor").asLong()
                        + afterSplits.get(1).path("amountMinor").asLong()
                        + afterSplits.get(2).path("amountMinor").asLong())
                .as("the legs' sum is exactly the buyer's frozen fee — the split ceiling")
                .isEqualTo(FROZEN_SHIPPING_FEE_MINOR);

        // The greedy split's honest 409: after the carves the primary's
        // margin (1100 − its 1000 compensation) carries no further share.
        HttpResponse<String> greedySplit = postJson(
                "/api/v1/deliveries/admin/orders/" + orderId + "/legs", adminGate.accessToken(), """
                        {"packageDescription":"greedy split","amountMinor":1050}""");
        assertThat(greedySplit.statusCode())
                .as("a share beyond the primary's margin answers the frozen-fee ceiling: %s", body(greedySplit))
                .isEqualTo(409);

        // (6) The seller's manual fulfill guard: while the order rides an
        // active delivery link, the merchant surface refuses — the
        // delivery lifecycle owns the completion.
        HttpResponse<String> sellerFulfill = postJson(
                "/api/v1/orders/seller/" + orderId + "/fulfill", sellerGate.accessToken(), "");
        assertThat(sellerFulfill.statusCode())
                .as("the seller cannot fulfill around the delivery lifecycle: %s", body(sellerFulfill)).isEqualTo(409);

        // (7) The base leg's honest exit. Its proof code is UNREACHABLE
        // (G-J25-a: the listener discards the creation response and no
        // surface answers the code afterward) — the courier can never
        // honestly deliver it over HTTP. The buyer's cancellation (legal
        // from READY_FOR_DISPATCH, no courier in the pool yet — the
        // deterministic window before the courier onboards below) is the
        // honest terminal exit, and the measured law: the cancelled leg's
        // frozen share triggers NO refund — the fee rode the order's one
        // intent and stays there (no delivery intent exists to cancel or
        // refund; the buyer's recourse is the whole-order cancellation).
        HttpResponse<String> baseCancel = postJson(
                "/api/v1/deliveries/requests/" + baseLegId + "/cancel", gate.accessToken(),
                "{\"reason\":\"the base package joined another run\"}");
        assertThat(baseCancel.statusCode()).as("the base leg's cancel: %s", body(baseCancel)).isEqualTo(200);
        assertThat(objectMapper.readTree(baseCancel.body()).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_request_id FROM orders WHERE id = ?", String.class, orderId))
                .as("the cancelled primary clears the order's link").isNull();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_intents WHERE order_id = ?", Integer.class, orderId))
                .as("the leg's cancel opened no second intent").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM payment_intents WHERE id = ?", String.class, intentId))
                .as("the leg's cancel never touched the order's collected money").isEqualTo("SUCCEEDED");

        // (8) The courier's onboarding: a verified provider opting into
        // the dispatch pool with a fresh location.
        String courierName = "it-leg-courier-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(courierName, "USER", "PROVIDER", "CONSUMER");
        GateResult courierGate = loginGate(courierName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", courierGate.accessToken()).statusCode())
                .as("the courier's /me provisioning sync").isEqualTo(200);
        UUID courierProviderId = becomeVerifiedProvider(courierGate.accessToken(), "Journey Courier Fleet");
        assertThat(postJson("/api/v1/admin/providers/" + courierProviderId + "/verify",
                adminGate.accessToken(), "").statusCode())
                .as("the courier's administrative verification").isEqualTo(200);
        HttpResponse<String> availability = putJson(
                "/api/v1/deliveries/courier/availability", courierGate.accessToken(), """
                        {"latitude":24.7100,"longitude":46.6700,"available":true}""");
        assertThat(availability.statusCode()).as("the courier's availability: %s", body(availability)).isEqualTo(200);
        UUID courierUserId = UUID.fromString(objectMapper.readTree(availability.body()).path("userId").asString());
        assertThat(objectMapper.readTree(availability.body()).path("available").asBoolean()).isTrue();

        // (9) The dispatch: the REAL scheduler's sweep assigns the oldest
        // ready leg to the fresh courier — awaited by the row itself.
        UUID assignedOne = awaitLegAssigned(splitOneId);
        assertThat(assignedOne).as("the scheduler assigned the first split to the onboarded courier")
                .isEqualTo(courierUserId);

        // (10) The proof guard: the DELIVERED edge without the code is the
        // honest 403 — delivery demands physical proof.
        HttpResponse<String> wrongProof = postJson(
                "/api/v1/deliveries/requests/" + splitOneId + "/transition", courierGate.accessToken(),
                "{\"targetStatus\":\"DELIVERED\",\"proofCode\":\"not-the-code\"}");
        assertThat(wrongProof.statusCode())
                .as("DELIVERED without the matching proof: %s", body(wrongProof)).isEqualTo(403);

        // (11) The courier's walk: PICKED_UP → IN_TRANSIT → DELIVERED with
        // the proof. The completion event fires — but the order WAITS:
        // the second split is still an active leg (the multi-leg law).
        assertThat(postJson("/api/v1/deliveries/requests/" + splitOneId + "/transition",
                courierGate.accessToken(), "{\"targetStatus\":\"PICKED_UP\"}").statusCode()).isEqualTo(200);
        assertThat(postJson("/api/v1/deliveries/requests/" + splitOneId + "/transition",
                courierGate.accessToken(), "{\"targetStatus\":\"IN_TRANSIT\"}").statusCode()).isEqualTo(200);
        HttpResponse<String> deliveredOne = postJson(
                "/api/v1/deliveries/requests/" + splitOneId + "/transition", courierGate.accessToken(), """
                        {"targetStatus":"DELIVERED","proofCode":"%s"}"""
                        .formatted(splitOneProof));
        assertThat(deliveredOne.statusCode()).as("the first split delivers: %s", body(deliveredOne)).isEqualTo(200);
        JsonNode deliveredOneView = objectMapper.readTree(deliveredOne.body());
        assertThat(deliveredOneView.path("status").asString()).isEqualTo("DELIVERED");
        assertThat(deliveredOneView.path("deliveredAt").isNull())
                .as("the delivered leg carries its proof timestamp").isFalse();

        // (12) The completion WAIT, measured across a real dispatch sweep:
        // the courier re-declares availability, the scheduler takes the
        // SECOND leg (a ≥ 500 ms real window after the first completion
        // event), and the order is STILL CONFIRMED — a completing
        // non-final leg is the multi-leg law's no-op (the service-level
        // idempotence OrdersDeliveryPortTest pins; here the wait holds
        // across the sweep that proves time passed).
        assertThat(putJson("/api/v1/deliveries/courier/availability", courierGate.accessToken(), """
                {"latitude":24.7100,"longitude":46.6700,"available":true}""").statusCode()).isEqualTo(200);
        UUID assignedTwo = awaitLegAssigned(splitTwoId);
        assertThat(assignedTwo).as("the freed courier takes the second split").isEqualTo(courierUserId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .as("the first leg's completion did NOT fulfill the order — a sibling is still in flight")
                .isEqualTo("CONFIRMED");

        // (13) The once-only completion: the LAST leg's proof completes
        // the ORDER — FULFILLED, the reservation committed into the real
        // deduction, the buyer's notification row delivered by the real
        // AFTER_COMMIT chain.
        assertThat(postJson("/api/v1/deliveries/requests/" + splitTwoId + "/transition",
                courierGate.accessToken(), "{\"targetStatus\":\"PICKED_UP\"}").statusCode()).isEqualTo(200);
        assertThat(postJson("/api/v1/deliveries/requests/" + splitTwoId + "/transition",
                courierGate.accessToken(), "{\"targetStatus\":\"IN_TRANSIT\"}").statusCode()).isEqualTo(200);
        HttpResponse<String> deliveredTwo = postJson(
                "/api/v1/deliveries/requests/" + splitTwoId + "/transition", courierGate.accessToken(), """
                        {"targetStatus":"DELIVERED","proofCode":"%s"}"""
                        .formatted(splitTwoProof));
        assertThat(deliveredTwo.statusCode()).as("the last leg delivers: %s", body(deliveredTwo)).isEqualTo(200);
        awaitOrderStatus(orderId, "FULFILLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?", Integer.class, sellerProductId))
                .as("the completion committed the reservation: 10 - 2").isEqualTo(8);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM products WHERE id = ?", Integer.class, sellerProductId))
                .as("the reservation released by the commit").isZero();
        awaitTypedNotificationRows(orderId, "ORDER_FULFILLED", 1);

        // (14) The terminal guards: the delivered leg does not reopen (the
        // once-only law enforced at the source — no second completion
        // event can exist), the fulfilled order does not cancel, and the
        // split command refuses a completed order.
        HttpResponse<String> reopenLeg = postJson(
                "/api/v1/deliveries/requests/" + splitTwoId + "/transition", courierGate.accessToken(),
                "{\"targetStatus\":\"PICKED_UP\"}");
        assertThat(reopenLeg.statusCode())
                .as("a delivered leg never reopens: %s", body(reopenLeg)).isEqualTo(409);
        HttpResponse<String> lateCancel = deleteWithBearer(
                "/api/v1/orders/" + orderId, gate.accessToken(), "{\"reason\":\"too late\"}");
        assertThat(lateCancel.statusCode())
                .as("the fulfilled order is terminal: %s", body(lateCancel)).isEqualTo(409);
        HttpResponse<String> lateSplit = postJson(
                "/api/v1/deliveries/admin/orders/" + orderId + "/legs", adminGate.accessToken(), """
                        {"packageDescription":"too late","amountMinor":100}""");
        assertThat(lateSplit.statusCode())
                .as("the split command refuses a completed order: %s", body(lateSplit)).isEqualTo(409);

        // (15) The single collection, held to the end: still exactly one
        // intent for the order's money, still no delivery-origin intent
        // for any leg, still collected.
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_intents WHERE order_id = ?", Integer.class, orderId))
                .as("the whole journey collected the order's money exactly once").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_intents WHERE delivery_request_id IN (?, ?, ?)",
                Integer.class, baseLegId, splitOneId, splitTwoId))
                .as("no leg ever opened a second charge").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM payment_intents WHERE id = ?", String.class, intentId))
                .isEqualTo("SUCCEEDED");
    }

    /**
     * J25's partial-cancel branch (the #628 wave's partial-cancellation
     * law, measured on the real chain): a checkout order's delivery SPLITS,
     * the buyer cancels ONE leg — the order stays CONFIRMED (its other leg
     * still in flight), the cancelled leg's frozen share moves NO money
     * (no delivery intent exists behind it — the measured absence), and the
     * primary link survives a non-primary cancel. The branch then takes its
     * honest end state: with every leg terminal the buyer's whole-order
     * cancellation is legal again, and the EXISTING engine fully refunds
     * the collected intent — the whole agreement including the frozen
     * shipping — with the ledger's refund mirror and the shelf restored.
     */
    @Test
    void thePartialCancelBranch() throws Exception {
        // The provisioning (each journey provisions its own actors).
        String sellerName = "it-leg-cancel-seller-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(sellerName, "USER", "PROVIDER", "CONSUMER");
        GateResult sellerGate = loginGate(sellerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", sellerGate.accessToken()).statusCode()).isEqualTo(200);
        UUID sellerProductId = registerProduct(sellerGate.accessToken(), 1500L);
        setStock(sellerGate.accessToken(), sellerProductId, 10);
        UUID sellerProviderId = becomeVerifiedProvider(sellerGate.accessToken(), "Cancel Branch Depot");

        String adminName = "it-leg-cancel-admin-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(adminName, "USER", "ADMIN");
        GateResult adminGate = loginGate(adminName, PASSWORD);
        assertThat(postJson("/api/v1/admin/providers/" + sellerProviderId + "/verify",
                adminGate.accessToken(), "").statusCode()).isEqualTo(200);
        assertThat(putJson("/api/v1/providers/me/fulfillment-location", sellerGate.accessToken(), """
                {"address":"Cancel Branch Depot","latitude":%s,"longitude":%s}"""
                .formatted(SELLER_LATITUDE, SELLER_LONGITUDE)).statusCode()).isEqualTo(200);

        String buyerName = "it-leg-cancel-buyer-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(buyerName, "USER", "CONSUMER");
        GateResult gate = loginGate(buyerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", gate.accessToken()).statusCode()).isEqualTo(200);
        postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","quantity":1}"""
                .formatted(sellerProductId));

        // The placement with the frozen delivery choice: 1500 + 4000.
        HttpResponse<String> placement = postJson("/api/v1/orders", gate.accessToken(), """
                {"fulfillment":{"method":"PLATFORM_DELIVERY","destinationAddress":"Riyadh downtown",
                 "destinationLatitude":%s,"destinationLongitude":%s}}"""
                .formatted(BUYER_LATITUDE, BUYER_LONGITUDE));
        assertThat(placement.statusCode()).as("placement: %s", body(placement)).isEqualTo(201);
        UUID orderId = UUID.fromString(objectMapper.readTree(placement.body()).path("id").asString());
        assertThat(objectMapper.readTree(placement.body()).path("totalAmountMinor").asLong())
                .as("1 × 1500 + the frozen 4000").isEqualTo(5_500L);

        // The money loop → CONFIRMED → the base leg.
        HttpResponse<String> intentResponse = postJson(
                "/api/v1/orders/" + orderId + "/payment-intent", gate.accessToken(), "");
        assertThat(intentResponse.statusCode()).as("intent: %s", body(intentResponse)).isEqualTo(201);
        UUID intentId = UUID.fromString(
                objectMapper.readTree(intentResponse.body()).path("paymentIntentId").asString());
        assertThat(postJson("/api/v1/payments/intents/" + intentId + "/process",
                gate.accessToken(), "").statusCode()).isEqualTo(200);
        assertThat(postJson("/api/v1/payments/intents/" + intentId + "/confirm",
                adminGate.accessToken(),
                "{\"externalId\":\"it-leg-cancel-ext-" + UUID.randomUUID() + "\"}").statusCode()).isEqualTo(200);
        awaitOrderStatus(orderId, "CONFIRMED");
        awaitLegCount(orderId, 1);
        JsonNode legs = objectMapper.readTree(getWithBearer(
                "/api/v1/deliveries/admin/orders/" + orderId + "/legs", adminGate.accessToken()).body());
        UUID baseLegId = UUID.fromString(legs.get(0).path("id").asString());
        assertThat(legs.get(0).path("amountMinor").asLong()).isEqualTo(FROZEN_SHIPPING_FEE_MINOR);

        // The split: the frozen fee becomes two legs (2500 + 1500).
        HttpResponse<String> split = postJson(
                "/api/v1/deliveries/admin/orders/" + orderId + "/legs", adminGate.accessToken(), """
                        {"packageDescription":"the partial package","amountMinor":1500}""");
        assertThat(split.statusCode()).as("the split: %s", body(split)).isEqualTo(201);
        UUID splitLegId = UUID.fromString(objectMapper.readTree(split.body()).path("id").asString());

        // (1) The PARTIAL cancel: the buyer cancels the SPLIT leg. The
        // measured law: the order does not move (its primary leg still
        // flies), the primary link survives (the cancel's clear only
        // matches the order's OWN link), and the cancelled share moves NO
        // money — no refund intent, no refund event, the order's collected
        // intent untouched. The frozen share has no dedicated refund path
        // (the measured absence G-J25-c): the buyer's recourse is the
        // whole-order cancellation below.
        HttpResponse<String> splitCancel = postJson(
                "/api/v1/deliveries/requests/" + splitLegId + "/cancel", gate.accessToken(),
                "{\"reason\":\"the partial package is not needed\"}");
        assertThat(splitCancel.statusCode()).as("the split leg's cancel: %s", body(splitCancel)).isEqualTo(200);
        assertThat(objectMapper.readTree(splitCancel.body()).path("status").asString()).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .as("a partially-cancelled delivery leaves the order CONFIRMED").isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT delivery_request_id FROM orders WHERE id = ?", String.class, orderId))
                .as("a non-primary cancel never clears the order's primary link")
                .isEqualTo(baseLegId.toString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM delivery_requests WHERE id = ?", String.class, baseLegId))
                .as("the primary leg still flies").isEqualTo("READY_FOR_DISPATCH");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_intents WHERE order_id = ?", Integer.class, orderId))
                .as("the cancelled share opened no money movement").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM payment_intents WHERE id = ?", String.class, intentId))
                .as("the collected intent is untouched by the partial cancel").isEqualTo("SUCCEEDED");

        // (2) The whole-order escape: the buyer cancels the remaining leg
        // (clearing the primary link), then the ORDER — legal again with
        // every leg terminal — and the EXISTING engine settles: the
        // collected intent fully refunds (the whole agreement INCLUDING
        // the frozen shipping), the ledger mirrors the refund, and the
        // shelf is restored.
        HttpResponse<String> baseCancel = postJson(
                "/api/v1/deliveries/requests/" + baseLegId + "/cancel", gate.accessToken(),
                "{\"reason\":\"the whole order is not needed\"}");
        assertThat(baseCancel.statusCode()).as("the base leg's cancel: %s", body(baseCancel)).isEqualTo(200);
        HttpResponse<String> orderCancel = deleteWithBearer(
                "/api/v1/orders/" + orderId, gate.accessToken(), "{\"reason\":\"buyer changed mind\"}");
        assertThat(orderCancel.statusCode()).as("the whole-order cancel: %s", body(orderCancel)).isEqualTo(200);
        assertThat(objectMapper.readTree(orderCancel.body()).path("status").asString()).isEqualTo("CANCELLED");

        awaitIntentStatus(intentId, "REFUNDED");
        UUID refundSource = UUID.nameUUIDFromBytes(
                ("refund-" + intentId.toString()).getBytes(StandardCharsets.UTF_8));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT amount_cents FROM ledger_entries WHERE source_id = ?", Long.class, refundSource))
                .as("the refund mirror returns the WHOLE agreement — items and the frozen shipping")
                .isEqualTo(5_500L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?", Integer.class, sellerProductId))
                .as("the cancellation released the reservation — the shelf is whole").isEqualTo(10);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM products WHERE id = ?", Integer.class, sellerProductId)).isZero();
        awaitTypedNotificationRows(orderId, "ORDER_CANCELLED", 1);

        // A cancelled order is terminal too — the second cancel answers 409.
        HttpResponse<String> secondCancel = deleteWithBearer(
                "/api/v1/orders/" + orderId, gate.accessToken(), "{\"reason\":\"twice\"}");
        assertThat(secondCancel.statusCode()).isEqualTo(409);
    }

    // ------------------------------------------------------------------
    // The seller's delivery provisioning — the provider profile the
    // administrative verification and the private pickup point (the W5b
    // owner-only write surface the checkout's fare resolution reads
    // through ProviderFulfillmentPort) ride.
    // ------------------------------------------------------------------

    /** Creates the caller's provider profile; the verification follows. */
    private UUID becomeVerifiedProvider(String token, String displayName) throws Exception {
        HttpResponse<String> created = postJson("/api/v1/providers", token, """
                {"displayName":"%s"}""".formatted(displayName));
        assertThat(created.statusCode()).as("provider profile: %s", body(created)).isEqualTo(200);
        return UUID.fromString(objectMapper.readTree(created.body()).path("id").asString());
    }

    // ------------------------------------------------------------------
    // The delivery journey's bounded typed polls (the house shape: 200 ms
    // steps, 10 s ceiling — each poll reads the very row the assertion
    // below will read).
    // ------------------------------------------------------------------

    /** The bounded poll for the order's own status (the AFTER_COMMIT listener hop). */
    private void awaitOrderStatus(UUID orderId, String expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            String status = jdbcTemplate.queryForObject(
                    "SELECT status FROM orders WHERE id = ?", String.class, orderId);
            if (expected.equals(status)) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("order " + orderId + " did not reach " + expected + " within 10s");
    }

    /**
     * The bounded poll for the confirmation edge's leg write — the
     * OrderFulfillmentListener's AFTER_COMMIT unit.
     */
    private void awaitLegCount(UUID orderId, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer legs = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM delivery_requests WHERE order_id = ?", Integer.class, orderId);
            if (legs != null && legs >= expected) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("delivery legs for order " + orderId + " never reached " + expected
                + " within 10s");
    }

    /**
     * The bounded poll for the REAL dispatch scheduler's assignment — the
     * sweep runs at the journey's 500 ms cadence and hands the leg to the
     * fresh courier; the answer is the assigning courier's user id.
     */
    private UUID awaitLegAssigned(UUID legId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            java.util.List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT status, courier_user_id FROM delivery_requests WHERE id = ?", legId);
            if (!rows.isEmpty() && "ASSIGNED".equals(rows.get(0).get("status"))
                    && rows.get(0).get("courier_user_id") != null) {
                return (UUID) rows.get(0).get("courier_user_id");
            }
            Thread.sleep(200);
        }
        throw new AssertionError("leg " + legId + " was never ASSIGNED to a courier within 20s");
    }

    /** The bounded poll for the refund engine's terminal state. */
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

    /**
     * The AFTER_COMMIT listener runs in its own REQUIRES_NEW unit after the
     * publishing transaction commits — on a loaded CI runner that hop is
     * not instantaneous, so the row assertion waits for it (bounded, the
     * house polling shape: 200ms steps, 10s ceiling). The wait is EXACT
     * and TYPED: it polls the very count the assertion will read.
     */
    private void awaitTypedNotificationRows(UUID orderId, String type, int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM notifications WHERE recipient_id = ("
                            + "SELECT consumer_id FROM orders WHERE id = ?) AND type = ?"
                            + " AND message LIKE '%' || ? || '%'",
                    Integer.class, orderId, type, orderId);
            if (rows != null && rows >= expected) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("notification rows of type " + type + " for order " + orderId
                + " never reached " + expected + " within 10s");
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

    // ------------------------------------------------------------------
    // Stage 6 (ADR-0002): the seller's commerce provisioning — the REAL
    // product rows the authoritative-pricing source resolves.
    // ------------------------------------------------------------------

    private UUID registerProduct(String sellerToken, long priceMinor) throws Exception {
        HttpResponse<String> created = postJson("/api/v1/store/products", sellerToken, """
                {"storeCategoryCode":"journey-parcel","title":"Journey parcel product",
                 "description":"J25 journey stock","priceMinor":%d,"currency":"SAR"}"""
                .formatted(priceMinor));
        assertThat(created.statusCode()).as("product register: %s", body(created)).isEqualTo(201);
        return java.util.UUID.fromString(objectMapper.readTree(created.body()).path("id").asString());
    }

    private void setStock(String sellerToken, UUID productId, int quantity) throws Exception {
        HttpResponse<String> stocked = postJsonOrPut("/api/v1/store/products/" + productId + "/inventory",
                sellerToken, "{\"stockQuantity\":" + quantity + "}", "PUT");
        assertThat(stocked.statusCode()).as("stock write: %s", body(stocked)).isEqualTo(200);
    }

    private GateResult loginGate(String username, String password) throws Exception {
        AuthorizationServerFixture.registerLoginGateClient(registeredClientRepository,
                "Order Leg Journey Gate Integration Test Client");
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

    private HttpResponse<String> putJson(String path, String accessToken, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
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

    private HttpResponse<String> deleteWithBearer(String path, String accessToken, String json) throws Exception {
        // JDK HttpClient's DELETE() takes no body — the official form for a
        // body-carrying delete is a method-override string on the builder.
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .method("DELETE", HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
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
        // 2000 (the house window): a ProblemDetail's fieldErrors live past
        // the traceId; the window must name the violated constraint.
        return response.body() == null ? "" : response.body().substring(0, Math.min(2000, response.body().length()));
    }
}
