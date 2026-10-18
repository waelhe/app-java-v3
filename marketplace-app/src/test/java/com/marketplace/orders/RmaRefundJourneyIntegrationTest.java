package com.marketplace.orders;

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
 * A-11 (compliance plan wave C: C.1) — the declared unit gate
 * "اختبار رحلة طلب" (the order journey): the machine measured end-to-end on
 * the real chain — real PostgreSQL + real Redis + the real Flyway schema
 * (V113's tables, V114/V115's widened type CHECK) + the real login gate's
 * Bearer + the real Modulith event machinery (the registry row and the
 * notifications late-lander listener).
 *
 * <p>The journey: the buyer fills the cart over HTTP → places the order
 * (the placement transaction: frozen lines + derived total + the cart
 * tombstone) → the merchant transitions run through the service (the
 * machine's guarded edges, the surfaces the store's merchant console will
 * ride) → the events DELIVER (the AFTER_COMMIT listener writes the buyer's
 * notification rows) → the terminal guard answers 409 over HTTP → the
 * privacy contract answers an honest 404 to a stranger.
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
class RmaRefundJourneyIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static org.testcontainers.postgresql.PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String PASSWORD = "it-order-journey-password";
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
    void seedTheStoreDictionary() {
        // The journey's category arrives as DATA (the plan's own wording —
        // never a migration), seeded by the class itself: the register path
        // validates the code against the live dictionary, and relying on
        // another test class to have seeded it first was a run-order
        // dependency the added waves unmasked (CI 2026-10-12: the 404
        // "StoreCategory not found: journey-appliances" cluster).
        jdbcTemplate.update("INSERT INTO store_categories (id, code, name_en, name_ar, position) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (code) DO NOTHING",
                UUID.randomUUID(), "journey-appliances", "Home appliances", "أجهزة منزلية", 0);
    }

    @Test
    void theFullOrderJourneyFromCartToTerminalState() throws Exception {
        // Stage 6 (ADR-0002): the seller provisions FIRST — the cart's
        // amounts are the store's own records now, so the products must
        // exist with their stock before any add lands.
        String sellerName = "it-order-seller-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(sellerName, "USER", "PROVIDER");
        GateResult sellerGate = loginGate(sellerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", sellerGate.accessToken()).statusCode())
                .as("the seller's /me provisioning sync").isEqualTo(200);
        UUID product = registerProduct(sellerGate.accessToken(), 1500L);
        UUID product2 = registerProduct(sellerGate.accessToken(), 9900L);
        setStock(sellerGate.accessToken(), product, 10);
        setStock(sellerGate.accessToken(), product2, 10);

        String username = "it-order-buyer-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(username);
        GateResult gate = loginGate(username, PASSWORD);
        assertThat(gate.accessToken()).isNotBlank();

        // The /me family's provisioning step (the measured CI lesson,
        // 2026-10-09): the user PROJECTION row is created by syncFromOidc on
        // the first /users/me call — the strict CurrentUserProvider that
        // every /me/** surface resolves through answers
        // "User not found for subject" (mapped to VAL-001 by the
        // IllegalArgumentException advice) until that first call lands.
        // The journey's cart adds were 400-ing exactly here; the /me sync
        // first is the established pattern (AccountSelfDeletion's own
        // syncProjectionIdViaMe).
        HttpResponse<String> me = getWithBearer("/api/v1/users/me", gate.accessToken());
        assertThat(me.statusCode()).as("the /me provisioning sync: %s", body(me)).isEqualTo(200);

        // (1) The cart fills over HTTP — two lines, the union semantics on
        // the third add (the duplicate product raises the quantity).
        postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","quantity":2}"""
                .formatted(product));
        postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","quantity":1}"""
                .formatted(product2));
        HttpResponse<String> unionAdd = postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","quantity":3}"""
                .formatted(product));
        assertThat(unionAdd.statusCode()).as("duplicate add: %s", body(unionAdd)).isEqualTo(201);
        JsonNode unionLine = objectMapper.readTree(unionAdd.body());
        assertThat(unionLine.path("quantity").asInt())
                .as("the duplicate add collapses into the quantity bump").isEqualTo(5);

        HttpResponse<String> cartRead = getWithBearer("/api/v1/me/cart", gate.accessToken());
        assertThat(cartRead.statusCode()).isEqualTo(200);
        assertThat(objectMapper.readTree(cartRead.body()).size()).isEqualTo(2);

        // (2) The placement — the frozen snapshot and the derived total.
        HttpResponse<String> placement = postJson("/api/v1/orders", gate.accessToken(), "");
        assertThat(placement.statusCode()).as("placement: %s", body(placement)).isEqualTo(201);
        JsonNode order = objectMapper.readTree(placement.body());
        UUID orderId = UUID.fromString(order.path("id").asString());
        assertThat(order.path("status").asString()).isEqualTo("PLACED");
        assertThat(order.path("totalAmountMinor").asLong())
                .as("the derived total: 5 x 1500 + 1 x 9900").isEqualTo(17400L);
        assertThat(order.path("items").size()).isEqualTo(2);

        // The cart is tombstoned: the next read is empty and a second
        // placement answers the 409 contract.
        HttpResponse<String> cartAfter = getWithBearer("/api/v1/me/cart", gate.accessToken());
        assertThat(objectMapper.readTree(cartAfter.body()).size()).isZero();
        HttpResponse<String> secondPlacement = postJson("/api/v1/orders", gate.accessToken(), "");
        assertThat(secondPlacement.statusCode()).isEqualTo(409);

        // (2b) The commerce legs (ADR-0002): the reservation landed (the
        // seller's shelf shows the reserved set) and the buyer's intent
        // exists through the REAL payments engine — one intent per order.
        Integer reservedAfterPlace = jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM products WHERE id = ?", Integer.class, product);
        assertThat(reservedAfterPlace).as("the placement's reservation").isEqualTo(5);
        HttpResponse<String> intentResponse = postJson(
                "/api/v1/orders/" + orderId + "/payment-intent", gate.accessToken(), "");
        assertThat(intentResponse.statusCode()).as("intent: %s", body(intentResponse)).isEqualTo(201);
        assertThat(objectMapper.readTree(intentResponse.body()).path("origin").asString()).isEqualTo("ORDER");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT payment_intent_id FROM orders WHERE id = ?", String.class, orderId))
                .as("the order's intent link").isNotNull();

        // (3) The machine's merchant transitions through the MERCHANT
        // SURFACE (A-17's HTTP arrival — the Saleor study's Phase-1
        // slice): the seller's own guarded edges, the post-command
        // read-back over the wire, the same single machine underneath.
        HttpResponse<String> sellerConfirm = postJson(
                "/api/v1/orders/seller/" + orderId + "/confirm", sellerGate.accessToken(), "");
        assertThat(sellerConfirm.statusCode()).as("seller confirm: %s", body(sellerConfirm)).isEqualTo(200);
        assertThat(objectMapper.readTree(sellerConfirm.body()).path("status").asString())
                .as("the confirm's read-back").isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("CONFIRMED");
        HttpResponse<String> sellerFulfill = postJson(
                "/api/v1/orders/seller/" + orderId + "/fulfill", sellerGate.accessToken(), "");
        assertThat(sellerFulfill.statusCode()).as("seller fulfill: %s", body(sellerFulfill)).isEqualTo(200);
        assertThat(objectMapper.readTree(sellerFulfill.body()).path("status").asString())
                .as("the fulfill's read-back").isEqualTo("FULFILLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM orders WHERE id = ?", String.class, orderId))
                .isEqualTo("FULFILLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?", Integer.class, product))
                .as("the fulfillment's deduction: 10 - 5").isEqualTo(5);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM products WHERE id = ?", Integer.class, product))
                .as("the reservation released by the commit").isZero();

        // (4) The events DELIVER — the AFTER_COMMIT listener wrote the
        // buyer's notification rows on the real chain (the registry + the
        // late-lander crossing, measured as rows). The wait is TYPED to
        // the rows the assertions below read (the helper's own record).
        awaitTypedNotificationRows(orderId, "ORDER_CONFIRMED", 1);
        awaitTypedNotificationRows(orderId, "ORDER_FULFILLED", 1);
        Integer confirmedRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ("
                        + "SELECT consumer_id FROM orders WHERE id = ?) AND type = 'ORDER_CONFIRMED'"
                        + " AND message LIKE '%' || ? || '%'",
                Integer.class, orderId, orderId);
        Integer fulfilledRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ("
                        + "SELECT consumer_id FROM orders WHERE id = ?) AND type = 'ORDER_FULFILLED'"
                        + " AND message LIKE '%' || ? || '%'",
                Integer.class, orderId, orderId);
        assertThat(confirmedRows).as("ORDER_CONFIRMED notification row").isEqualTo(1);
        assertThat(fulfilledRows).as("ORDER_FULFILLED notification row").isEqualTo(1);

        // (5) The terminal guard over HTTP: a fulfilled order does not
        // reopen — the honest 409.
        HttpResponse<String> lateCancel = deleteWithBearer(
                "/api/v1/orders/" + orderId, gate.accessToken(), "{\"reason\":\"too late\"}");
        assertThat(lateCancel.statusCode()).as("cancel after fulfillment: %s", body(lateCancel)).isEqualTo(409);

        // (6) The privacy contract: a stranger gets an honest 404.
        String stranger = "it-order-stranger-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(stranger);
        GateResult strangerGate = loginGate(stranger, PASSWORD);
        // The stranger's own /me provisioning sync — the same strict-provider
        // step the journey's opening documents (the measured 400-instead-of-404:
        // the stranger's projection row did not exist, so the orders read
        // died at the caller resolution before the ownership gate could
        // answer the honest 404).
        assertThat(getWithBearer("/api/v1/users/me", strangerGate.accessToken()).statusCode())
                .as("the stranger's /me provisioning sync").isEqualTo(200);
        HttpResponse<String> strangerRead = getWithBearer(
                "/api/v1/orders/" + orderId, strangerGate.accessToken());
        assertThat(strangerRead.statusCode())
                .as("stranger read: existence itself is private").isEqualTo(404);

        // (7) The merchant surface's own scope contract: the BUYER cannot
        // pull the merchant levers (the seller surface is not theirs — the
        // honest 404), the stranger cannot either, and the seller's
        // sold-orders page carries the order they sold.
        HttpResponse<String> buyerConfirm = postJson(
                "/api/v1/orders/seller/" + orderId + "/confirm", gate.accessToken(), "");
        assertThat(buyerConfirm.statusCode())
                .as("the buyer on the merchant surface: not the seller").isEqualTo(404);
        HttpResponse<String> strangerSellerRead = getWithBearer(
                "/api/v1/orders/seller/" + orderId, strangerGate.accessToken());
        assertThat(strangerSellerRead.statusCode())
                .as("stranger on the merchant surface").isEqualTo(404);
        HttpResponse<String> soldList = getWithBearer(
                "/api/v1/orders/seller/me", sellerGate.accessToken());
        assertThat(soldList.statusCode()).as("sold list: %s", body(soldList)).isEqualTo(200);
        java.util.List<UUID> soldIds = new java.util.ArrayList<>();
        for (JsonNode sold : objectMapper.readTree(soldList.body()).path("content")) {
            soldIds.add(UUID.fromString(sold.path("id").asString()));
        }
        assertThat(soldIds).as("the seller's sold page carries the order")
                .contains(orderId);
    }

    /**
     * ADR-0010 (the ADR-0002 deferral opened): the mixed-seller cart SPLITS
     * into one order per seller over the REAL chain — the placement answers
     * 201 with the first group's order as the body and the sibling riding
     * the ADDITIVE {@code additionalOrders} (no wire break), each order
     * carries its own seller and derived total, the cart tombstones once,
     * and each order opens its OWN payment intent independently (the
     * per-seller settlement the ledger rides).
     */
    @Test
    void theMixedSellerCartSplitsIntoOneOrderPerSeller() throws Exception {
        // Two sellers provision first — the store's records precede any add.
        String sellerAName = "it-split-seller-a-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(sellerAName, "USER", "PROVIDER");
        GateResult sellerAGate = loginGate(sellerAName, PASSWORD);
        // The /me provisioning sync first — the measured CI lesson (2026-10-09)
        // the full and cancellation journeys apply: the domain projection row
        // is created by syncFromOidc on the first /users/me call; without it
        // the strict CurrentUserProvider answers "User not found for subject"
        // (VAL-001) on the product register — the mixed-seller journey was
        // added by ADR-0010 missing that step (the CI-measured 2026-10-12
        // 400s across three runs).
        assertThat(getWithBearer("/api/v1/users/me", sellerAGate.accessToken()).statusCode())
                .as("seller A's /me provisioning sync").isEqualTo(200);
        UUID productA = registerProduct(sellerAGate.accessToken(), 1500L);
        setStock(sellerAGate.accessToken(), productA, 10);

        String sellerBName = "it-split-seller-b-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(sellerBName, "USER", "PROVIDER");
        GateResult sellerBGate = loginGate(sellerBName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", sellerBGate.accessToken()).statusCode())
                .as("seller B's /me provisioning sync").isEqualTo(200);
        UUID productB = registerProduct(sellerBGate.accessToken(), 9900L);
        setStock(sellerBGate.accessToken(), productB, 10);

        String username = "it-split-buyer-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(username);
        GateResult gate = loginGate(username, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", gate.accessToken()).statusCode())
                .as("the buyer's /me provisioning sync").isEqualTo(200);

        postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","quantity":2}""".formatted(productA));
        postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","quantity":1}""".formatted(productB));

        HttpResponse<String> placement = postJson("/api/v1/orders", gate.accessToken(), "");
        assertThat(placement.statusCode()).as("place: %s", body(placement)).isEqualTo(201);
        JsonNode placementBody = objectMapper.readTree(placement.body());
        UUID primaryId = UUID.fromString(placementBody.path("id").asString());
        assertThat(placementBody.path("status").asString()).isEqualTo("PLACED");
        assertThat(placementBody.path("totalAmountMinor").asInt()).as("the first group's own total").isEqualTo(3000);
        assertThat(placementBody.path("additionalOrders").size())
                .as("the sibling order rides the additive field").isEqualTo(1);
        JsonNode sibling = placementBody.path("additionalOrders").get(0);
        UUID siblingId = UUID.fromString(sibling.path("id").asString());
        assertThat(sibling.path("totalAmountMinor").asInt()).isEqualTo(9900);
        assertThat(sibling.path("status").asString()).isEqualTo("PLACED");

        // The rows: two orders, each single-seller, the cart tombstoned once.
        Integer orderCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE consumer_id = ("
                        + "SELECT id FROM users WHERE subject = ?)", Integer.class, username);
        assertThat(orderCount).as("one order per seller").isEqualTo(2);
        Integer sellerless = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM orders WHERE consumer_id = ("
                        + "SELECT id FROM users WHERE subject = ?) AND seller_id IS NULL",
                Integer.class, username);
        assertThat(sellerless).as("every new placement sets its seller").isZero();
        Integer reserved = jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM products WHERE id = ?", Integer.class, productA);
        assertThat(reserved).as("the whole-cart reserve covered group A too").isEqualTo(2);

        // Each order settles its own money: two intents, one per order.
        HttpResponse<String> primaryIntent = postJson(
                "/api/v1/orders/" + primaryId + "/payment-intent", gate.accessToken(), "");
        assertThat(primaryIntent.statusCode()).as("primary intent: %s", body(primaryIntent)).isEqualTo(201);
        HttpResponse<String> siblingIntent = postJson(
                "/api/v1/orders/" + siblingId + "/payment-intent", gate.accessToken(), "");
        assertThat(siblingIntent.statusCode()).as("sibling intent: %s", body(siblingIntent)).isEqualTo(201);
        // The W5 read model's own component name — PaymentIntentDetails
        // serializes paymentIntentId (the canonical vocabulary every other
        // consumer reads); the journey's first actual arrival at this line
        // (every earlier round died before it) caught the wrong key.
        assertThat(objectMapper.readTree(primaryIntent.body()).path("paymentIntentId").asString())
                .as("each order's intent carries its own id")
                .isNotEqualTo(objectMapper.readTree(siblingIntent.body()).path("paymentIntentId").asString());

        // The merchant surface preserves ADR-0010's seller isolation: each
        // seller commands their OWN order through the HTTP surface, and the
        // sibling's order reads as the honest 404 (existence itself is
        // private — one order, one seller, one lever).
        HttpResponse<String> aConfirms = postJson(
                "/api/v1/orders/seller/" + primaryId + "/confirm", sellerAGate.accessToken(), "");
        assertThat(aConfirms.statusCode())
                .as("seller A confirms the primary: %s", body(aConfirms)).isEqualTo(200);
        assertThat(objectMapper.readTree(aConfirms.body()).path("status").asString())
                .isEqualTo("CONFIRMED");
        HttpResponse<String> aOnSibling = postJson(
                "/api/v1/orders/seller/" + siblingId + "/confirm", sellerAGate.accessToken(), "");
        assertThat(aOnSibling.statusCode())
                .as("seller A on the sibling order: not their sale").isEqualTo(404);
        HttpResponse<String> bConfirms = postJson(
                "/api/v1/orders/seller/" + siblingId + "/confirm", sellerBGate.accessToken(), "");
        assertThat(bConfirms.statusCode())
                .as("seller B confirms the sibling: %s", body(bConfirms)).isEqualTo(200);
        assertThat(objectMapper.readTree(bConfirms.body()).path("status").asString())
                .isEqualTo("CONFIRMED");
    }

    @Test
    void theCancellationBranchPublishesAndNotifies() throws Exception {
        String sellerName = "it-order-cancel-seller-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(sellerName, "USER", "PROVIDER");
        GateResult sellerGate = loginGate(sellerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", sellerGate.accessToken()).statusCode()).isEqualTo(200);
        UUID product = registerProduct(sellerGate.accessToken(), 4200L);
        setStock(sellerGate.accessToken(), product, 7);

        String username = "it-order-cancel-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(username);
        GateResult gate = loginGate(username, PASSWORD);

        // The /me provisioning sync first — the same strict-provider step the
        // full journey documents (the projection row must exist before any
        // /me/** write).
        assertThat(getWithBearer("/api/v1/users/me", gate.accessToken()).statusCode())
                .as("the /me provisioning sync").isEqualTo(200);

        postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","quantity":1}"""
                .formatted(product));
        HttpResponse<String> placement = postJson("/api/v1/orders", gate.accessToken(), "");
        assertThat(placement.statusCode()).isEqualTo(201);
        UUID orderId = UUID.fromString(objectMapper.readTree(placement.body()).path("id").asString());

        HttpResponse<String> cancellation = deleteWithBearer(
                "/api/v1/orders/" + orderId, gate.accessToken(),
                "{\"reason\":\"buyer changed mind\"}");
        assertThat(cancellation.statusCode()).as("cancel: %s", body(cancellation)).isEqualTo(200);
        assertThat(objectMapper.readTree(cancellation.body()).path("status").asString())
                .isEqualTo("CANCELLED");

        awaitTypedNotificationRows(orderId, "ORDER_CANCELLED", 1);
        Integer cancelledRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ("
                        + "SELECT consumer_id FROM orders WHERE id = ?) AND type = 'ORDER_CANCELLED'"
                        + " AND message LIKE '%' || ? || '%'",
                Integer.class, orderId, orderId);
        assertThat(cancelledRows).as("ORDER_CANCELLED notification row").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?", Integer.class, product))
                .as("the cancellation released the reservation — the shelf is whole").isEqualTo(7);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT reserved_quantity FROM products WHERE id = ?", Integer.class, product)).isZero();

        // A cancelled order is terminal too — the second cancel answers 409.
        HttpResponse<String> secondCancel = deleteWithBearer(
                "/api/v1/orders/" + orderId, gate.accessToken(),
                "{\"reason\":\"twice\"}");
        assertThat(secondCancel.statusCode()).isEqualTo(409);
    }

    
    /**
     * W6 / J19 (the unified plan §3, the register's J19 row) — the
     * return/exchange/refund composite journey on the REAL chain: the
     * fulfilled order → the buyer's RETURN request (line-scoped, one unit
     * of two) → the admin APPROVE that FREEZES the refund share → the
     * RECEIVE that restocks the shelf → the COMPLETE that executes the
     * frozen partial refund through the ONE refund contract — measured on
     * the payment, the ledger, and the shelves — plus the honest guards:
     * a return against a not-yet-fulfilled order answers 409, and a
     * completed return stays closed.
     */
    @Test
    void theRmaJourneyFromRequestThroughApprovalToTheExecutedRefund() throws Exception {
        // The seller provisions FIRST (ADR-0002): the store's own records.
        String sellerName = "it-rma-seller-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(sellerName, "USER", "PROVIDER");
        GateResult sellerGate = loginGate(sellerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", sellerGate.accessToken()).statusCode()).isEqualTo(200);
        UUID product = registerProduct(sellerGate.accessToken(), 1500L);
        setStock(sellerGate.accessToken(), product, 10);

        // The buyer places the order: 2 units, total 3000.
        String username = "it-rma-buyer-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(username);
        GateResult gate = loginGate(username, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", gate.accessToken()).statusCode()).isEqualTo(200);
        postJson("/api/v1/me/cart/items", gate.accessToken(),
                "{\"productId\":\"" + product + "\",\"quantity\":2}");
        HttpResponse<String> placement = postJson("/api/v1/orders", gate.accessToken(), "");
        assertThat(placement.statusCode()).as("placement: %s", body(placement)).isEqualTo(201);
        JsonNode order = objectMapper.readTree(placement.body());
        UUID orderId = UUID.fromString(order.path("id").asString());
        UUID orderItemId = UUID.fromString(order.path("items").path(0).path("id").asString());

        // (1) The honest guard FIRST: a return against a PLACED order
        // answers 409 — the RMA cycle is a post-fulfillment machine.
        HttpResponse<String> prematureReturn = postJson("/api/v1/orders/returns", gate.accessToken(), """
                {"orderId":"%s","type":"RETURN","reason":"too early","lines":[{"orderItemId":"%s","quantity":1}]}"""
                .formatted(orderId, orderItemId));
        assertThat(prematureReturn.statusCode()).as("premature return: %s", body(prematureReturn)).isEqualTo(409);

        // (2) The money loop: the intent, then the administrative confirm —
        // the order auto-transitions PLACED -> CONFIRMED through the real
        // PaymentStateChangedEvent listener.
        HttpResponse<String> intentResponse = postJson(
                "/api/v1/orders/" + orderId + "/payment-intent", gate.accessToken(), "");
        assertThat(intentResponse.statusCode()).as("intent: %s", body(intentResponse)).isEqualTo(201);
        // The intent response's canonical vocabulary is paymentIntentId (the
        // record's own field name — the sibling journeys' parse and the
        // wire contract); the earlier path("id") read a missing node and
        // answered "Invalid UUID string: " (the measured CI failure).
        UUID intentId = UUID.fromString(objectMapper.readTree(intentResponse.body()).path("paymentIntentId").asString());
        String adminName = "it-rma-admin-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(adminName, "USER", "ADMIN");
        GateResult adminGate = loginGate(adminName, PASSWORD);
        // The confirm body's JSON string stays OPEN across the concatenation
        // (the earlier \"it-rma-ext-\" closed the value before the UUID and
        // the raw id landed outside the string literal — malformed JSON,
        // the honest 400 "Failed to read request" of the measured CI run).
        HttpResponse<String> confirm = postJson(
                "/api/v1/payments/intents/" + intentId + "/confirm", adminGate.accessToken(),
                "{\"externalId\":\"it-rma-ext-" + UUID.randomUUID() + "\"}");
        assertThat(confirm.statusCode()).as("admin confirm: %s", body(confirm)).isEqualTo(200);
        awaitOrderStatus(orderId, "CONFIRMED");

        // (3) The seller fulfills: the shelf deducts (10 - 2 = 8).
        HttpResponse<String> sellerFulfill = postJson(
                "/api/v1/orders/seller/" + orderId + "/fulfill", sellerGate.accessToken(), "");
        assertThat(sellerFulfill.statusCode()).as("seller fulfill: %s", body(sellerFulfill)).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?", Integer.class, product)).isEqualTo(8);

        // (4) The buyer's RETURN request: ONE unit of the two-unit line.
        HttpResponse<String> returnRequest = postJson("/api/v1/orders/returns", gate.accessToken(), """
                {"orderId":"%s","type":"RETURN","reason":"one unit arrived damaged","lines":[{"orderItemId":"%s","quantity":1}]}"""
                .formatted(orderId, orderItemId));
        assertThat(returnRequest.statusCode()).as("return request: %s", body(returnRequest)).isEqualTo(201);
        JsonNode returnView = objectMapper.readTree(returnRequest.body());
        UUID returnId = UUID.fromString(returnView.path("id").asString());
        assertThat(returnView.path("status").asString()).isEqualTo("REQUESTED");

        // (5) The admin APPROVE — the refund share FREEZES (1 x 1500).
        HttpResponse<String> approve = postJson(
                "/api/v1/orders/returns/admin/" + returnId + "/approve", adminGate.accessToken(),
                "{\"note\":\"damage confirmed by photos\"}");
        assertThat(approve.statusCode()).as("approve: %s", body(approve)).isEqualTo(200);
        assertThat(objectMapper.readTree(approve.body()).path("status").asString()).isEqualTo("APPROVED");
        assertThat(objectMapper.readTree(approve.body()).path("refundAmountMinor").asLong())
                .as("the frozen refund share: 1 unit x 1500").isEqualTo(1500L);

        // (6) The RECEIVE — the goods arrive, the shelf restocks (8 + 1 = 9).
        HttpResponse<String> receive = postJson(
                "/api/v1/orders/returns/admin/" + returnId + "/receive", adminGate.accessToken(), "");
        assertThat(receive.statusCode()).as("receive: %s", body(receive)).isEqualTo(200);
        assertThat(objectMapper.readTree(receive.body()).path("status").asString()).isEqualTo("RECEIVED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?", Integer.class, product)).isEqualTo(9);

        // (7) The COMPLETE — the frozen partial refund executes through the
        // ONE refund contract: the intent answers PARTIALLY_REFUNDED.
        HttpResponse<String> complete = postJson(
                "/api/v1/orders/returns/admin/" + returnId + "/complete", adminGate.accessToken(), "");
        assertThat(complete.statusCode()).as("complete: %s", body(complete)).isEqualTo(200);
        assertThat(objectMapper.readTree(complete.body()).path("status").asString()).isEqualTo("COMPLETED");
        String intentStatus = jdbcTemplate.queryForObject(
                "SELECT status FROM payment_intents WHERE id = ?", String.class, intentId);
        assertThat(intentStatus)
                .as("the executed refund: 1500 of 3000 — PARTIALLY_REFUNDED, never a full mirror")
                .isEqualTo("PARTIALLY_REFUNDED");

        // (8) The ledger's honest no-op (ADR-0013 D3, the origin-state
        // matrix's own measured law): the partial refund creates NO
        // general-ledger movement — the entries are exactly the confirm's
        // own (the credit keyed by the intent, the commission by its
        // nameUUID key), and NO refund debit source exists.
        Integer entryCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE source_id = ?", Integer.class, intentId);
        assertThat(entryCount).as("the confirm's credit rides the intent's own source").isEqualTo(1);
        // The refund debit's own source key (nameUUID("refund-" + intentId)) —
        // the FULL-refund mirror's key. A partial refund must leave it absent.
        UUID refundSource = java.util.UUID.nameUUIDFromBytes(
                ("refund-" + intentId.toString()).getBytes());
        Integer refundDebits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE entry_type = 'REFUND_DEBIT' "
                        + "AND source_id = ?",
                Integer.class, refundSource);
        assertThat(refundDebits).as("no refund debit for the intent (the partial no-op — ADR-0013 D3)").isZero();

        // (9) The closed-stays-closed branch: the second complete answers 409.
        HttpResponse<String> secondComplete = postJson(
                "/api/v1/orders/returns/admin/" + returnId + "/complete", adminGate.accessToken(), "");
        assertThat(secondComplete.statusCode()).isEqualTo(409);
    }

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
    // product rows the authoritative-pricing source resolves (the journey
    // no longer invents product ids: the cart's amount IS the store's own
    // record now, so the products must exist with their stock).
    // ------------------------------------------------------------------

    private UUID registerProduct(String sellerToken, long priceMinor) throws Exception {
        HttpResponse<String> created = postJson("/api/v1/store/products", sellerToken, """
                {"storeCategoryCode":"journey-appliances","title":"Journey product",
                 "description":"Stage-6 journey stock","priceMinor":%d,"currency":"SAR"}"""
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
                "Order Journey Gate Integration Test Client");
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
     * The AFTER_COMMIT listener runs in its own REQUIRES_NEW unit after the
     * publishing transaction commits — on a loaded CI runner that hop is
     * not instantaneous, so the row assertion waits for it (bounded, the
     * house polling shape: 200ms steps, 10s ceiling).
     *
     * <p><b>The wait is EXACT and TYPED</b>: it polls the very count the
     * assertion will read — recipient-scoped, type-filtered,
     * order-named. The previous minimum-based shape ("any N rows mentioning
     * the order") was a satisfiable-by-proxy race, CI-measured under the
     * delivery vertical's added payment-event listener load: this journey
     * creates a payment intent, and the intent's own PAYMENT_STATE row
     * satisfied the minimum while the ORDER_FULFILLED row was still in its
     * asynchronous hop — the immediate count then read zero against a
     * journey that had, in fact, delivered. The typed wait is the only
     * honest synchronization for a typed assertion, under any present or
     * future event-listener load.
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

    private HttpResponse<String> deleteWithBearer(String path, String accessToken, String json) throws Exception {
        // JDK HttpClient's DELETE() takes no body — the official form for a
        // body-carrying delete is a method override string on the builder.
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
        // 2000 (was 300): a ProblemDetail's fieldErrors — the piece that
        // names the violated constraint — live past the traceId; the 300-char
        // window cut them off in the measured CI failures of 2026-10-09,
        // leaving a "Validation failed" with no subject.
        return response.body() == null ? "" : response.body().substring(0, Math.min(2000, response.body().length()));
    }
}
