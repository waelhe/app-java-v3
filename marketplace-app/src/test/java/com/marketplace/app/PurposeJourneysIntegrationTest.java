package com.marketplace.app;

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
 * W10 / J30 (the unified plan §4 W10, the register's J30 row) — the
 * PURPOSE composite journeys: «يبنى لكل غرض مسار قابل للتنقل مع تعيين
 * المالك لكل قرار، ويعاد استخدام المصادر الأصلية». The plan's own law:
 * a purpose journey is accepted ONLY when it reaches an ACTUAL outcome
 * with failure handling, and the SOURCE STAYS ONE across the screens
 * («لا يكفي تجميع روابط أو صفوف في شاشة… ويُثبت أن المصدر بقي واحدًا
 * عبر الشاشات») — measured here as stable ids across every read of the
 * same source, on the REAL chain (real PostgreSQL + real Redis + the
 * real Flyway schema + the real login gate's Bearer + the real Modulith
 * event machinery).
 *
 * <p>Three of the plan's five purposes are represented as three full
 * journeys (each one the plan's own wording, each riding surfaces the
 * earlier waves already proved — nothing invented, ZERO production
 * changes):
 *
 * <ul>
 *   <li><b>الاحتياج اليومي المحلي (the daily need)</b> — سؤال/توصية ←
 *   العثور على ملف مصدر صالح ← تواصل/شراء/حجز ← إتمام ← مراجعة مؤهلة:
 *   the verified member's RECOMMENDATION ask in the neighborhood feed →
 *   the buyer finds the store's ONE product record (the storefront
 *   public read — the same id on the Q&amp;A board read and the frozen
 *   order line) → the contact leg (the product's public Q&amp;A: the
 *   buyer asks, the seller answers) → the purchase (cart → placement
 *   with the frozen total → the ORDER money loop process+capture → the
 *   real listener's CONFIRMED → the seller's FULFILLED) → the service
 *   visit (the booking of the same listing's window — شراء/حجز is the
 *   plan's own one leg) → the eligible review. Failure legs: the honest
 *   400 of the premature review before completion, and the measured
 *   absence of an ORDER-origin review (the review surface is
 *   booking-scoped — the attempt naming the order answers the honest
 *   404).</li>
 *   <li><b>السياحة/الإقامة (the tourism stay)</b> — بحث بالتواريخ
 *   والتوفر ← قراءة الشروط ← حجز/دفع ← إكمال المزود ← تغيير/إلغاء
 *   باسترداد: the traveler's dated availability read (the public slot
 *   window source) → the stay's authoritative record read → the booking
 *   with the server-derived total → the money gate's honest 409 while
 *   PENDING → confirm → process+capture → the provider's completion →
 *   and the cancellation branch on a SECOND booking: the ONE refund
 *   contract (the intent REFUNDED, the ledger's refund debit mirror)
 *   with the window FREED — proven by a rebooking that succeeds.</li>
 *   <li><b>العودة/الاغتراب (the returnee)</b> — استعادة سياق الحي ←
 *   أسئلة المجتمع ← العثور على أعمال موثوقة: the membershipless
 *   returnee's question answers the plan's own failure case (403 —
 *   «مستخدم لا يملك عضوية حي») → the join + the admin's verification
 *   APPROVE restores the neighborhood context → their GENERAL question
 *   lands in the feed and the community ANSWERS it (the comment) → the
 *   trusted business is found through the recommendation's source
 *   bridge (the feed card's provider id IS the public page's id — one
 *   id, one truth) with the VERIFIED badge and the declared service
 *   fact riding the page → the trust signal in their own scope (the
 *   market board) → the unknown business's honest 404.</li>
 * </ul>
 *
 * <p>The two remaining purposes (الأسرة والمرأة والسلامة، السكن والعقار)
 * ride the same proven sub-journeys (community writes with the
 * moderation/report cycle J04/J05/J10, the realestate surfaces J21) —
 * their specific composites remain owner scope per the wave plan; this
 * class freezes what exists, honestly.
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
class PurposeJourneysIntegrationTest {

    // The fixed geo seed ids (R__seed_geo_qudsaya): levels 0..3 of the
    // REAL administrative tree the membership gates resolve.
    private static final String QUDSAYYA_OLD_TOWN = "11111111-1111-4111-8111-111111111104";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static org.testcontainers.postgresql.PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String PASSWORD = "it-purpose-journey-password";
    private static final String LOGIN_PATH = "/login";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    // The service-visit window of the daily-need journey (the booking leg).
    private static final String SERVICE_SLOT_START = "2027-08-03T09:00:00Z";
    private static final String SERVICE_SLOT_END = "2027-08-03T12:00:00Z";

    // The stay windows of the tourism journey: the completed stay and the
    // cancelled one (whose freed window the rebooking claims).
    private static final String STAY_WINDOW_START = "2027-09-01T14:00:00Z";
    private static final String STAY_WINDOW_END = "2027-09-05T10:00:00Z";
    private static final String CANCEL_WINDOW_START = "2027-09-10T14:00:00Z";
    private static final String CANCEL_WINDOW_END = "2027-09-14T10:00:00Z";
    private static final String AVAILABILITY_FROM = "2027-09-01T00:00:00Z";
    private static final String AVAILABILITY_TO = "2027-09-30T23:59:59Z";

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
        // validates the code against the live dictionary (the RMA journey's
        // own measured CI lesson, adopted verbatim).
        jdbcTemplate.update("INSERT INTO store_categories (id, code, name_en, name_ar, position) "
                + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (code) DO NOTHING",
                UUID.randomUUID(), "journey-appliances", "Home appliances", "أجهزة منزلية", 0);
    }

    /**
     * J30 — <b>الاحتياج اليومي المحلي</b> (the daily-need purpose): the
     * plan's own chain «سؤال/توصية ← العثور على ملف مصدر صالح ←
     * تواصل/شراء/حجز ← تسليم/إتمام ← مراجعة مؤهلة أو بلاغ» measured on
     * the REAL surfaces, with the source-stays-one law as the thread: the
     * ONE product id is the same on the seller's registration echo, the
     * buyer's storefront public read, the Q&amp;A board read, the frozen
     * order line, and the booking's listing — and the seller id is the
     * same on the storefront read and the seller's public summary.
     */
    @Test
    void theDailyNeedPurposeJourneyFromTheAskToTheEligibleReview() throws Exception {
        // (A) The provider provisions the service FIRST (ADR-0002): the
        // store's own records — the shelf row (1500 SAR, stock 10) and the
        // service-visit window the booking leg will claim.
        String sellerName = "it-purpose-seller-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(sellerName, "USER", "PROVIDER");
        GateResult sellerGate = loginGate(sellerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", sellerGate.accessToken()).statusCode())
                .as("the seller's /me provisioning sync").isEqualTo(200);
        UUID sellerId = UUID.fromString(objectMapper.readTree(
                getWithBearer("/api/v1/users/me", sellerGate.accessToken()).body())
                        .path("id").asString());
        UUID product = registerProduct(sellerGate.accessToken(), 1500L);
        setStock(sellerGate.accessToken(), product, 10);
        createSlot(sellerGate.accessToken(), sellerId, SERVICE_SLOT_START, SERVICE_SLOT_END);

        // (B) The ask: a VERIFIED member of the old town posts the
        // recommendation-category question about the service — the
        // membership flow of the market journey verbatim (join →
        // verification request → the admin's APPROVE).
        String askerName = "it-purpose-asker-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(askerName, "CONSUMER");
        GateResult askerGate = loginGate(askerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", askerGate.accessToken()).statusCode())
                .as("the asker's /me provisioning sync").isEqualTo(200);
        HttpResponse<String> askerJoin = putJson("/api/v1/me/neighborhood", askerGate.accessToken(),
                "{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}");
        assertThat(askerJoin.statusCode()).as("the asker's join: %s", body(askerJoin)).isEqualTo(201);
        UUID askerMembershipId = UUID.fromString(
                objectMapper.readTree(askerJoin.body()).path("id").asString());
        assertThat(postJson("/api/v1/me/neighborhood/verification-requests",
                askerGate.accessToken(), "").statusCode()).isEqualTo(200);
        String adminName = "it-purpose-admin-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(adminName, "USER", "ADMIN");
        GateResult adminGate = loginGate(adminName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", adminGate.accessToken()).statusCode())
                .as("the admin's /me provisioning sync").isEqualTo(200);
        HttpResponse<String> askerApprove = postJson(
                "/api/v1/admin/neighborhood-memberships/" + askerMembershipId + "/verification?decision=APPROVE",
                adminGate.accessToken(), "");
        assertThat(askerApprove.statusCode()).as("the asker's verification approve: %s", body(askerApprove))
                .isEqualTo(200);
        assertThat(objectMapper.readTree(askerApprove.body()).path("verificationState").asString())
                .isEqualTo("VERIFIED");
        HttpResponse<String> ask = postJson("/api/v1/neighborhood/posts", askerGate.accessToken(), """
                {"locationId":"%s","category":"RECOMMENDATION",
                 "title":"أبحث عن فني صيانة موثوق داخل الحي",
                 "body":"من جرب فنيًا للصيانة المنزلية وينصح به؟"}"""
                .formatted(QUDSAYYA_OLD_TOWN));
        assertThat(ask.statusCode()).as("the recommendation ask: %s", body(ask)).isEqualTo(201);
        UUID askPostId = UUID.fromString(objectMapper.readTree(ask.body()).path("id").asString());

        // (C) The buyer — a fellow neighbor — restores the context and
        // finds the ask, then the SOURCE: the store's ONE product record.
        String buyerName = "it-purpose-buyer-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(buyerName, "CONSUMER");
        GateResult gate = loginGate(buyerName, PASSWORD);
        // The /me family's provisioning step (the measured CI lesson,
        // 2026-10-09): the strict CurrentUserProvider resolves the caller
        // only after the first /users/me call syncs the projection row.
        assertThat(getWithBearer("/api/v1/users/me", gate.accessToken()).statusCode())
                .as("the buyer's /me provisioning sync").isEqualTo(200);
        assertThat(putJson("/api/v1/me/neighborhood", gate.accessToken(),
                "{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}").statusCode())
                .as("the buyer joins the neighborhood — the feed's scope").isEqualTo(201);
        HttpResponse<String> feed = getWithBearer("/api/v1/neighborhood/posts", gate.accessToken());
        assertThat(feed.statusCode()).as("the neighborhood feed: %s", body(feed)).isEqualTo(200);
        assertThat(feedRowCarrying(feed, askPostId))
                .as("the ask is visible in the buyer's neighborhood feed").isNotNull();

        // The ONE source's detail read — the buyer's public product face.
        HttpResponse<String> storefront = getWithBearer(
                "/api/v1/storefront/products/" + product, gate.accessToken());
        assertThat(storefront.statusCode()).as("the storefront read: %s", body(storefront)).isEqualTo(200);
        JsonNode storefrontView = objectMapper.readTree(storefront.body());
        assertThat(storefrontView.path("id").asString())
                .as("the source stays one: the storefront read carries the registered id")
                .isEqualTo(product.toString());
        assertThat(storefrontView.path("priceMinor").asLong())
                .as("the authoritative price of the source").isEqualTo(1500L);
        assertThat(storefrontView.path("currency").asString()).isEqualTo("SAR");
        assertThat(storefrontView.path("sellerId").asString())
                .as("the source's seller is the provisioning provider").isEqualTo(sellerId.toString());
        assertThat(storefrontView.path("inStock").asBoolean()).isTrue();

        // The seller's public shelf face — the board-level read of the
        // same source's owner (the count is the store's own data).
        HttpResponse<String> summary = getWithBearer(
                "/api/v1/store/sellers/" + sellerId + "/summary", gate.accessToken());
        assertThat(summary.statusCode()).as("the seller summary: %s", body(summary)).isEqualTo(200);
        JsonNode summaryView = objectMapper.readTree(summary.body());
        assertThat(summaryView.path("sellerId").asString())
                .as("the summary names the same seller as the storefront read").isEqualTo(sellerId.toString());
        assertThat(summaryView.path("productCount").asLong()).isEqualTo(1L);

        // The CONTACT leg (تواصل): the product's public Q&A — the buyer
        // asks, the seller answers, and the Q&A board read carries the
        // SAME product id (the source stays one across the screens).
        HttpResponse<String> question = postJson(
                "/api/v1/store/products/" + product + "/questions", gate.accessToken(),
                "{\"body\":\"هل تشمل الخدمة فحص المعدات قبل الشراء؟\"}");
        assertThat(question.statusCode()).as("the buyer's question: %s", body(question)).isEqualTo(201);
        UUID questionId = UUID.fromString(
                objectMapper.readTree(question.body()).path("questionId").asString());
        HttpResponse<String> answer = postJson(
                "/api/v1/store/questions/" + questionId + "/answers", sellerGate.accessToken(),
                "{\"body\":\"نعم — الفحص داخل الخدمة والمعاينة قبل الدفع.\"}");
        assertThat(answer.statusCode()).as("the seller's answer: %s", body(answer)).isEqualTo(201);
        HttpResponse<String> qaBoard = getWithBearer(
                "/api/v1/store/products/" + product + "/questions", gate.accessToken());
        assertThat(qaBoard.statusCode()).as("the Q&A board read: %s", body(qaBoard)).isEqualTo(200);
        JsonNode qaRow = objectMapper.readTree(qaBoard.body()).path("content").path(0);
        assertThat(qaRow.path("productId").asString())
                .as("the Q&A board read carries the SAME product id").isEqualTo(product.toString());
        assertThat(qaRow.path("answer").path("body").asString())
                .as("the seller's official answer rides the public read").contains("الفحص");

        // (D) The PURCHASE (شراء): the cart → the placement with the
        // frozen total → the ORDER money loop (process + the
        // administrative capture) → the real listener's CONFIRMED → the
        // seller's FULFILLED.
        HttpResponse<String> cartAdd = postJson("/api/v1/me/cart/items", gate.accessToken(), """
                {"productId":"%s","quantity":1}"""
                .formatted(product));
        assertThat(cartAdd.statusCode()).as("the cart add: %s", body(cartAdd)).isEqualTo(201);
        HttpResponse<String> placement = postJson("/api/v1/orders", gate.accessToken(), "");
        assertThat(placement.statusCode()).as("the placement: %s", body(placement)).isEqualTo(201);
        JsonNode order = objectMapper.readTree(placement.body());
        UUID orderId = UUID.fromString(order.path("id").asString());
        assertThat(order.path("status").asString()).isEqualTo("PLACED");
        assertThat(order.path("totalAmountMinor").asLong())
                .as("the frozen total: 1 x 1500").isEqualTo(1500L);
        assertThat(order.path("items").path(0).path("productId").asString())
                .as("the frozen line carries the SAME product id — the source stays one into the transaction")
                .isEqualTo(product.toString());

        HttpResponse<String> intentResponse = postJson(
                "/api/v1/orders/" + orderId + "/payment-intent", gate.accessToken(), "");
        assertThat(intentResponse.statusCode()).as("the order intent: %s", body(intentResponse)).isEqualTo(201);
        JsonNode orderIntent = objectMapper.readTree(intentResponse.body());
        UUID orderIntentId = UUID.fromString(orderIntent.path("paymentIntentId").asString());
        assertThat(orderIntent.path("origin").asString())
                .as("the ORDER origin — the domain owner prices its own money").isEqualTo("ORDER");
        HttpResponse<String> orderProcess = postJson(
                "/api/v1/payments/intents/" + orderIntentId + "/process", gate.accessToken(), "");
        assertThat(orderProcess.statusCode()).as("the process step: %s", body(orderProcess)).isEqualTo(200);
        assertThat(objectMapper.readTree(orderProcess.body()).path("status").asString())
                .isEqualTo("PROCESSING");
        HttpResponse<String> orderCapture = postJson(
                "/api/v1/payments/intents/" + orderIntentId + "/confirm", adminGate.accessToken(),
                "{\"externalId\":\"j30-daily-capture-" + orderIntentId + "\"}");
        assertThat(orderCapture.statusCode()).as("the administrative capture: %s", body(orderCapture))
                .isEqualTo(200);
        assertThat(objectMapper.readTree(orderCapture.body()).path("status").asString())
                .isEqualTo("SUCCEEDED");
        // The real listener: the settlement's COMPLETED event auto-confirms
        // the PLACED order — the bounded poll is the honest synchronization.
        awaitOrderStatus(orderId, "CONFIRMED");
        HttpResponse<String> sellerFulfill = postJson(
                "/api/v1/orders/seller/" + orderId + "/fulfill", sellerGate.accessToken(), "");
        assertThat(sellerFulfill.statusCode()).as("the seller's fulfill: %s", body(sellerFulfill)).isEqualTo(200);
        assertThat(objectMapper.readTree(sellerFulfill.body()).path("status").asString())
                .isEqualTo("FULFILLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT stock_quantity FROM products WHERE id = ?", Integer.class, product))
                .as("the fulfillment's deduction: 10 - 1").isEqualTo(9);
        // The events DELIVER — the AFTER_COMMIT listener wrote the buyer's
        // notification rows (the typed wait, the house polling shape).
        awaitTypedNotificationRows("ORDER_CONFIRMED", orderId, 1);
        awaitTypedNotificationRows("ORDER_FULFILLED", orderId, 1);
        // The source after the transaction: the SAME id, still the ONE record.
        HttpResponse<String> storefrontAfter = getWithBearer(
                "/api/v1/storefront/products/" + product, gate.accessToken());
        assertThat(objectMapper.readTree(storefrontAfter.body()).path("id").asString())
                .as("the source stays one after the transaction").isEqualTo(product.toString());

        // (E) The honest boundary of the outcome: an ORDER-origin review
        // does not exist — the review surface is booking-scoped (the
        // reviews module's own law), so the attempt naming the ORDER
        // answers the honest 404. This is the measured absence the
        // journey documents (J30's own «شرح واضح لما تأكد وما لم يتأكد»).
        HttpResponse<String> orderReview = postJson("/api/v1/reviews", gate.accessToken(), """
                {"bookingId":"%s","rating":5,"comment":"the order itself"}"""
                .formatted(orderId));
        assertThat(orderReview.statusCode())
                .as("a review on an order: the surface is booking-scoped — %s", body(orderReview))
                .isEqualTo(404);

        // (F) The SERVICE VISIT (حجز — the plan's own «شراء/حجز» leg): the
        // buyer books the SAME listing's window (the listing id IS the
        // product id — the source stays one).
        HttpResponse<String> bookingResponse = postJson("/api/v1/bookings", gate.accessToken(), """
                {"listingId":"%s","startsAt":"%s","endsAt":"%s","notes":"J30 daily-need service visit"}"""
                .formatted(product, SERVICE_SLOT_START, SERVICE_SLOT_END));
        assertThat(bookingResponse.statusCode()).as("the service-visit booking: %s", body(bookingResponse))
                .isEqualTo(201);
        UUID bookingId = UUID.fromString(
                objectMapper.readTree(bookingResponse.body()).path("id").asString());
        assertThat(objectMapper.readTree(bookingResponse.body()).path("status").asString())
                .isEqualTo("PENDING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT price_cents FROM bookings WHERE id = ?", Long.class, bookingId))
                .as("the server-derived total — the client never supplied money").isEqualTo(1500L);
        HttpResponse<String> bookingConfirm = postJson(
                "/api/v1/bookings/" + bookingId + "/confirm", sellerGate.accessToken(), "");
        assertThat(bookingConfirm.statusCode()).as("the provider's confirm: %s", body(bookingConfirm))
                .isEqualTo(200);
        assertThat(objectMapper.readTree(bookingConfirm.body()).path("status").asString())
                .isEqualTo("CONFIRMED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT booked FROM availability_slots WHERE provider_id = ? AND starts_at = ?",
                Boolean.class, sellerId, java.time.Instant.parse(SERVICE_SLOT_START)))
                .as("the window claimed in the booking's own name").isTrue();

        // The money loop of the visit: the BOOKING intent (the domain's
        // own origin) → process → the administrative capture.
        HttpResponse<String> visitIntent = postJson("/api/v1/payments/intents", gate.accessToken(), """
                {"bookingId":"%s","idempotencyKey":"j30-daily-visit-%s"}"""
                .formatted(bookingId, bookingId));
        assertThat(visitIntent.statusCode()).as("the visit intent: %s", body(visitIntent)).isEqualTo(201);
        UUID visitIntentId = UUID.fromString(
                objectMapper.readTree(visitIntent.body()).path("id").asString());
        assertThat(objectMapper.readTree(visitIntent.body()).path("amountCents").asLong())
                .as("the booking's own amount").isEqualTo(1500L);
        assertThat(postJson("/api/v1/payments/intents/" + visitIntentId + "/process",
                gate.accessToken(), "").statusCode()).isEqualTo(200);
        HttpResponse<String> visitCapture = postJson(
                "/api/v1/payments/intents/" + visitIntentId + "/confirm", adminGate.accessToken(),
                "{\"externalId\":\"j30-daily-visit-capture-" + visitIntentId + "\"}");
        assertThat(visitCapture.statusCode()).as("the visit capture: %s", body(visitCapture)).isEqualTo(200);
        awaitIntentStatus(visitIntentId, "SUCCEEDED");

        // (G) The failure leg of the outcome: the PREMATURE review — the
        // booking is CONFIRMED and paid, but the service is not completed
        // yet. The eligibility gate answers the honest 400 (a validation
        // answer, never a silent acceptance).
        HttpResponse<String> prematureReview = postJson("/api/v1/reviews", gate.accessToken(), """
                {"bookingId":"%s","rating":5,"comment":"too early — the visit has not completed"}"""
                .formatted(bookingId));
        assertThat(prematureReview.statusCode())
                .as("the premature review before completion: %s", body(prematureReview)).isEqualTo(400);

        // The completion (إتمام): the provider completes the visit.
        HttpResponse<String> bookingComplete = postJson(
                "/api/v1/bookings/" + bookingId + "/complete", sellerGate.accessToken(), "");
        assertThat(bookingComplete.statusCode()).as("the provider's completion: %s", body(bookingComplete))
                .isEqualTo(200);
        assertThat(objectMapper.readTree(bookingComplete.body()).path("status").asString())
                .isEqualTo("COMPLETED");

        // (H) The ACTUAL outcome: the eligible review lands — PUBLISHED
        // (the verified-purchase trust), BOOKING-origin (the real law),
        // reviewed by the buyer, about the seller's own provider id.
        HttpResponse<String> review = postJson("/api/v1/reviews", gate.accessToken(), """
                {"bookingId":"%s","rating":5,"comment":"خدمة ممتازة ووصول في الموعد"}"""
                .formatted(bookingId));
        assertThat(review.statusCode()).as("the eligible review: %s", body(review)).isEqualTo(201);
        JsonNode reviewBody = objectMapper.readTree(review.body());
        assertThat(reviewBody.path("rating").asInt()).isEqualTo(5);
        assertThat(reviewBody.path("origin").asString())
                .as("the review's origin is the booking itself (the real surface's law)")
                .isEqualTo("BOOKING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT moderation_status FROM reviews WHERE booking_id = ?", String.class, bookingId))
                .as("booking-origin reviews publish without moderation").isEqualTo("PUBLISHED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT provider_id FROM reviews WHERE booking_id = ?", String.class, bookingId))
                .as("the review's provider is the source's own seller (A1: the user id space)")
                .isEqualTo(sellerId.toString());
        // The one-review-per-booking law: the duplicate answers 409.
        HttpResponse<String> duplicateReview = postJson("/api/v1/reviews", gate.accessToken(), """
                {"bookingId":"%s","rating":4,"comment":"again"}""".formatted(bookingId));
        assertThat(duplicateReview.statusCode())
                .as("the duplicate review: one per booking").isEqualTo(409);
    }

    /**
     * J30 — <b>السياحة/الإقامة</b> (the tourism/stay purpose): the plan's
     * own chain «بحث بالتواريخ والتوفر ← قراءة الشروط ← حجز/دفع ← إكمال
     * المزود ← تغيير/إلغاء واسترداد بحسب شروط المصدر» — the traveler
     * searches the dated availability (the public slot source), reads the
     * stay's conditions (the authoritative record), books with the
     * server-derived total, pays through the real engine, the provider
     * completes — and the cancellation branch on a SECOND booking proves
     * the source's own conditions: the ONE refund contract AND the freed
     * window (a rebooking succeeds).
     */
    @Test
    void theTourismStayPurposeJourneyFromDateSearchToTheRefundedCancellation() throws Exception {
        // (A) The host provisions: the stay listing + TWO windows (the
        // completed stay and the cancelled one).
        String hostName = "it-purpose-host-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(hostName, "USER", "PROVIDER");
        GateResult hostGate = loginGate(hostName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", hostGate.accessToken()).statusCode())
                .as("the host's /me provisioning sync").isEqualTo(200);
        UUID hostId = UUID.fromString(objectMapper.readTree(
                getWithBearer("/api/v1/users/me", hostGate.accessToken()).body())
                        .path("id").asString());
        UUID stay = registerProduct(hostGate.accessToken(), 2000L);
        setStock(hostGate.accessToken(), stay, 1);
        createSlot(hostGate.accessToken(), hostId, STAY_WINDOW_START, STAY_WINDOW_END);
        createSlot(hostGate.accessToken(), hostId, CANCEL_WINDOW_START, CANCEL_WINDOW_END);

        // (B) The traveler searches by dates: the public availability
        // source carries both free windows.
        String travelerName = "it-purpose-traveler-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(travelerName, "CONSUMER");
        GateResult travelerGate = loginGate(travelerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", travelerGate.accessToken()).statusCode())
                .as("the traveler's /me provisioning sync").isEqualTo(200);
        HttpResponse<String> availability = getWithBearer(
                "/api/v1/providers/" + hostId + "/availability?from=" + encode(AVAILABILITY_FROM)
                        + "&to=" + encode(AVAILABILITY_TO), travelerGate.accessToken());
        assertThat(availability.statusCode()).as("the dated availability read: %s", body(availability))
                .isEqualTo(200);
        assertThat(slotStartingAt(availability, STAY_WINDOW_START))
                .as("the stay window rides the availability source").isNotNull();
        assertThat(slotStartingAt(availability, CANCEL_WINDOW_START))
                .as("the second window rides the availability source").isNotNull();
        assertThat(slotStartingAt(availability, STAY_WINDOW_START).path("booked").asBoolean())
                .as("the stay window is free at the search").isFalse();

        // (C) The conditions read: the stay's authoritative record — the
        // ONE source of the price the booking will derive.
        HttpResponse<String> conditions = getWithBearer(
                "/api/v1/storefront/products/" + stay, travelerGate.accessToken());
        assertThat(conditions.statusCode()).as("the conditions read: %s", body(conditions)).isEqualTo(200);
        JsonNode conditionsView = objectMapper.readTree(conditions.body());
        assertThat(conditionsView.path("id").asString())
                .as("the source stays one: the conditions read carries the registered id")
                .isEqualTo(stay.toString());
        assertThat(conditionsView.path("priceMinor").asLong()).isEqualTo(2000L);
        assertThat(conditionsView.path("currency").asString()).isEqualTo("SAR");
        assertThat(conditionsView.path("sellerId").asString())
                .as("the stay's host is the provisioning provider").isEqualTo(hostId.toString());

        // (D) The booking: the exact-slot gate passes, the total is the
        // server-derived flat price (the client never supplied money).
        HttpResponse<String> bookingResponse = postJson("/api/v1/bookings", travelerGate.accessToken(), """
                {"listingId":"%s","startsAt":"%s","endsAt":"%s","notes":"J30 tourism stay"}"""
                .formatted(stay, STAY_WINDOW_START, STAY_WINDOW_END));
        assertThat(bookingResponse.statusCode()).as("the stay booking: %s", body(bookingResponse))
                .isEqualTo(201);
        UUID bookingId = UUID.fromString(
                objectMapper.readTree(bookingResponse.body()).path("id").asString());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT price_cents FROM bookings WHERE id = ?", Long.class, bookingId))
                .as("the server-derived total").isEqualTo(2000L);

        // (E) The money gate's honest 409 while the booking is PENDING
        // (the failure leg — the intent requires CONFIRMED).
        HttpResponse<String> prematureIntent = postJson("/api/v1/payments/intents", travelerGate.accessToken(), """
                {"bookingId":"%s","idempotencyKey":"j30-stay-premature-%s"}"""
                .formatted(bookingId, bookingId));
        assertThat(prematureIntent.statusCode())
                .as("the intent on a PENDING booking: %s", body(prematureIntent)).isEqualTo(409);

        // (F) The host confirms (the window claimed in the booking's own
        // name) → the money loop → the provider's completion.
        HttpResponse<String> confirm = postJson(
                "/api/v1/bookings/" + bookingId + "/confirm", hostGate.accessToken(), "");
        assertThat(confirm.statusCode()).as("the host's confirm: %s", body(confirm)).isEqualTo(200);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT booked FROM availability_slots WHERE provider_id = ? AND starts_at = ?",
                Boolean.class, hostId, java.time.Instant.parse(STAY_WINDOW_START)))
                .as("the stay window claimed at confirm").isTrue();
        HttpResponse<String> intentResponse = postJson("/api/v1/payments/intents", travelerGate.accessToken(), """
                {"bookingId":"%s","idempotencyKey":"j30-stay-%s"}"""
                .formatted(bookingId, bookingId));
        assertThat(intentResponse.statusCode()).as("the intent: %s", body(intentResponse)).isEqualTo(201);
        UUID intentId = UUID.fromString(
                objectMapper.readTree(intentResponse.body()).path("id").asString());
        assertThat(objectMapper.readTree(intentResponse.body()).path("amountCents").asLong())
                .as("the booking's own amount").isEqualTo(2000L);
        HttpResponse<String> process = postJson(
                "/api/v1/payments/intents/" + intentId + "/process", travelerGate.accessToken(), "");
        assertThat(process.statusCode()).as("the process step: %s", body(process)).isEqualTo(200);
        String adminName = "it-purpose-stay-admin-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(adminName, "ADMIN");
        GateResult adminGate = loginGate(adminName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", adminGate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> capture = postJson(
                "/api/v1/payments/intents/" + intentId + "/confirm", adminGate.accessToken(),
                "{\"externalId\":\"j30-stay-capture-" + intentId + "\"}");
        assertThat(capture.statusCode()).as("the administrative capture: %s", body(capture)).isEqualTo(200);
        awaitIntentStatus(intentId, "SUCCEEDED");
        // The booking listener's honest no-op: the already-CONFIRMED
        // booking stays CONFIRMED (measured, not a second transition).
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM bookings WHERE id = ?", String.class, bookingId))
                .isEqualTo("CONFIRMED");
        awaitTypedNotificationRows("BOOKING_CONFIRMED", bookingId, 1);
        HttpResponse<String> complete = postJson(
                "/api/v1/bookings/" + bookingId + "/complete", hostGate.accessToken(), "");
        assertThat(complete.statusCode()).as("the provider's completion: %s", body(complete)).isEqualTo(200);
        assertThat(objectMapper.readTree(complete.body()).path("status").asString())
                .isEqualTo("COMPLETED");

        // (G) The cancellation branch on a SECOND booking (تغيير/إلغاء
        // باسترداد): a second traveler books the other window, pays, then
        // cancels — the ONE refund contract rides the event.
        String traveler2Name = "it-purpose-traveler2-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(traveler2Name, "CONSUMER");
        GateResult traveler2Gate = loginGate(traveler2Name, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", traveler2Gate.accessToken()).statusCode())
                .as("the second traveler's /me provisioning sync").isEqualTo(200);
        HttpResponse<String> booking2 = postJson("/api/v1/bookings", traveler2Gate.accessToken(), """
                {"listingId":"%s","startsAt":"%s","endsAt":"%s","notes":"J30 cancellation branch"}"""
                .formatted(stay, CANCEL_WINDOW_START, CANCEL_WINDOW_END));
        assertThat(booking2.statusCode()).as("the second booking: %s", body(booking2)).isEqualTo(201);
        UUID booking2Id = UUID.fromString(
                objectMapper.readTree(booking2.body()).path("id").asString());
        assertThat(postJson("/api/v1/bookings/" + booking2Id + "/confirm",
                hostGate.accessToken(), "").statusCode()).isEqualTo(200);
        HttpResponse<String> intent2 = postJson("/api/v1/payments/intents", traveler2Gate.accessToken(), """
                {"bookingId":"%s","idempotencyKey":"j30-stay-cancel-%s"}"""
                .formatted(booking2Id, booking2Id));
        assertThat(intent2.statusCode()).as("the second intent: %s", body(intent2)).isEqualTo(201);
        UUID intent2Id = UUID.fromString(
                objectMapper.readTree(intent2.body()).path("id").asString());
        assertThat(postJson("/api/v1/payments/intents/" + intent2Id + "/process",
                traveler2Gate.accessToken(), "").statusCode()).isEqualTo(200);
        HttpResponse<String> capture2 = postJson(
                "/api/v1/payments/intents/" + intent2Id + "/confirm", adminGate.accessToken(),
                "{\"externalId\":\"j30-stay-cancel-capture-" + intent2Id + "\"}");
        assertThat(capture2.statusCode()).as("the second capture: %s", body(capture2)).isEqualTo(200);
        awaitIntentStatus(intent2Id, "SUCCEEDED");

        // The cancellation: the participant's own write.
        HttpResponse<String> cancel = postJson(
                "/api/v1/bookings/" + booking2Id + "/cancel", traveler2Gate.accessToken(), "");
        assertThat(cancel.statusCode()).as("the consumer's cancel: %s", body(cancel)).isEqualTo(200);
        assertThat(objectMapper.readTree(cancel.body()).path("status").asString())
                .isEqualTo("CANCELLED");

        // The ONE refund contract: the collected intent refunded IN FULL,
        // the payment row follows, the ledger's refund debit mirrors the
        // booking-origin credit under the refund's own nameUUID key.
        awaitIntentStatus(intent2Id, "REFUNDED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM payments WHERE payment_intent_id = ?", String.class, intent2Id))
                .as("the payment row follows the refund").isEqualTo("REFUNDED");
        UUID refundSource = UUID.nameUUIDFromBytes(
                ("refund-" + intent2Id.toString()).getBytes(StandardCharsets.UTF_8));
        Integer refundDebits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE entry_type = 'REFUND_DEBIT' AND source_id = ?",
                Integer.class, refundSource);
        assertThat(refundDebits).as("the refund debit mirrors the credit (the ONE refund contract)")
                .isEqualTo(1);
        Integer credits = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM ledger_entries WHERE entry_type = 'PAYMENT_CREDIT' AND source_id = ?",
                Integer.class, intent2Id);
        assertThat(credits).as("the capture's credit rode the intent's own source").isEqualTo(1);

        // The source's own conditions enforced: the window is FREED —
        // measured on the same availability read the search used, and
        // proven by a REBOOKING that succeeds.
        HttpResponse<String> availabilityAfter = getWithBearer(
                "/api/v1/providers/" + hostId + "/availability?from=" + encode(AVAILABILITY_FROM)
                        + "&to=" + encode(AVAILABILITY_TO), travelerGate.accessToken());
        assertThat(slotStartingAt(availabilityAfter, CANCEL_WINDOW_START).path("booked").asBoolean())
                .as("the cancelled booking's window is freed on the availability source").isFalse();
        String traveler3Name = "it-purpose-traveler3-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(traveler3Name, "CONSUMER");
        GateResult traveler3Gate = loginGate(traveler3Name, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", traveler3Gate.accessToken()).statusCode())
                .as("the rebooker's /me provisioning sync").isEqualTo(200);
        HttpResponse<String> rebooking = postJson("/api/v1/bookings", traveler3Gate.accessToken(), """
                {"listingId":"%s","startsAt":"%s","endsAt":"%s","notes":"J30 the freed window rebooked"}"""
                .formatted(stay, CANCEL_WINDOW_START, CANCEL_WINDOW_END));
        assertThat(rebooking.statusCode()).as("the rebooking on the freed window: %s", body(rebooking))
                .isEqualTo(201);

        // The terminal guard: the closed state stays closed.
        HttpResponse<String> secondCancel = postJson(
                "/api/v1/bookings/" + booking2Id + "/cancel", traveler2Gate.accessToken(), "");
        assertThat(secondCancel.statusCode()).as("the second cancel").isEqualTo(409);
    }

    /**
     * J30 — <b>العودة/الاغتراب</b> (the returnee purpose): the plan's own
     * chain «استعادة سياق الحي ← أسئلة المجتمع ← العثور على أعمال
     * موثوقة» — the membershipless returnee's question answers the plan's
     * own failure case (403), the join + the verification APPROVE restores
     * the context, their GENERAL question lands and the community answers
     * it, and the trusted business is found through the recommendation's
     * source bridge: the feed card's provider id IS the public page's id
     * (one id, one truth), the VERIFIED badge and the declared service
     * fact ride the page, and the market board reads in their own scope.
     */
    @Test
    void theReturneePurposeJourneyFromContextRecoveryToTheTrustedBusiness() throws Exception {
        // (A) The trusted business: the owner creates the profile, the
        // administrative verify lights the badge, and the declared service
        // is the page's own fact. (Two role gates on the way: the profile
        // creation is the CONSUMER surface, the services write the
        // PROVIDER one — the owner carries both.)
        String ownerName = "it-purpose-owner-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(ownerName, "CONSUMER", "PROVIDER");
        GateResult ownerGate = loginGate(ownerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", ownerGate.accessToken()).statusCode())
                .as("the owner's /me provisioning sync").isEqualTo(200);
        HttpResponse<String> profile = postJson("/api/v1/providers", ownerGate.accessToken(), """
                {"displayName":"مكتب الواحة للخدمات","bio":"أعمال موثوقة داخل البلدة القديمة منذ 2019"}""");
        assertThat(profile.statusCode()).as("the business profile: %s", body(profile)).isEqualTo(200);
        UUID businessId = UUID.fromString(objectMapper.readTree(profile.body()).path("id").asString());
        String adminName = "it-purpose-returnee-admin-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(adminName, "USER", "ADMIN");
        GateResult adminGate = loginGate(adminName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", adminGate.accessToken()).statusCode())
                .as("the admin's /me provisioning sync").isEqualTo(200);
        HttpResponse<String> verify = postJson(
                "/api/v1/admin/providers/" + businessId + "/verify", adminGate.accessToken(), "");
        assertThat(verify.statusCode()).as("the administrative verify: %s", body(verify)).isEqualTo(200);
        assertThat(objectMapper.readTree(verify.body()).path("status").asString())
                .isEqualTo("VERIFIED");
        HttpResponse<String> declaredService = postJson(
                "/api/v1/providers/" + businessId + "/services", ownerGate.accessToken(), """
                {"title":"استشارة وصيانة","description":"زيارة وتقييم داخل الحي",
                 "durationMinutes":45,"priceCents":15000,"currency":"SAR"}""");
        assertThat(declaredService.statusCode()).as("the declared service: %s", body(declaredService))
                .isEqualTo(200);

        // (B) The neighbor: a member of the old town recommends the
        // business (the W4 source bridge) and publishes one market row.
        String neighborName = "it-purpose-neighbor-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(neighborName, "CONSUMER");
        GateResult neighborGate = loginGate(neighborName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", neighborGate.accessToken()).statusCode())
                .as("the neighbor's /me provisioning sync").isEqualTo(200);
        assertThat(putJson("/api/v1/me/neighborhood", neighborGate.accessToken(),
                "{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}").statusCode())
                .as("the neighbor's join").isEqualTo(201);
        HttpResponse<String> recommendation = postJson("/api/v1/neighborhood/posts", neighborGate.accessToken(), """
                {"locationId":"%s","category":"RECOMMENDATION",
                 "title":"أنصح بمكتب الواحة للخدمات",
                 "body":"تعاملت معهم مرتين — التزام ومصداقية.",
                 "recommendedProviderId":"%s"}"""
                .formatted(QUDSAYYA_OLD_TOWN, businessId));
        assertThat(recommendation.statusCode()).as("the recommendation with its real source: %s",
                body(recommendation)).isEqualTo(201);
        UUID recommendationId = UUID.fromString(
                objectMapper.readTree(recommendation.body()).path("id").asString());
        HttpResponse<String> marketRow = postJson("/api/v1/neighborhood/market", neighborGate.accessToken(), """
                {"locationId":"%s","category":"ELECTRONICS","title":"مثقاب شحن للبطاريات — سليم",
                 "condition":"GOOD","priceCents":12000,"priceCurrency":"SAR",
                 "locationLabel":"شارع المسجد - مربع 2"}"""
                .formatted(QUDSAYYA_OLD_TOWN));
        assertThat(marketRow.statusCode()).as("the neighbor's market row: %s", body(marketRow))
                .isEqualTo(201);
        UUID marketItemId = UUID.fromString(
                objectMapper.readTree(marketRow.body()).path("id").asString());

        // (C) The returnee arrives with NO membership: their question
        // answers the plan's own J30 failure case — the explicit 403
        // («مستخدم لا يملك عضوية حي»), never a widened scope.
        String returneeName = "it-purpose-returnee-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(returneeName, "CONSUMER");
        GateResult returneeGate = loginGate(returneeName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", returneeGate.accessToken()).statusCode())
                .as("the returnee's /me provisioning sync").isEqualTo(200);
        HttpResponse<String> membershiplessQuestion = postJson("/api/v1/neighborhood/posts",
                returneeGate.accessToken(), """
                        {"locationId":"%s","category":"GENERAL",
                         "title":"عدت بعد سنوات للسكن في الحي",
                         "body":"ما أفضل الطرق لاستعادة سياق الحي والتعرف على الأعمال الموثوقة؟"}"""
                        .formatted(QUDSAYYA_OLD_TOWN));
        assertThat(membershiplessQuestion.statusCode())
                .as("the membershipless question: the honest 403 — %s", body(membershiplessQuestion))
                .isEqualTo(403);

        // (D) The context recovery: the join + the verification request +
        // the admin's APPROVE.
        HttpResponse<String> join = putJson("/api/v1/me/neighborhood", returneeGate.accessToken(),
                "{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}");
        assertThat(join.statusCode()).as("the returnee's join: %s", body(join)).isEqualTo(201);
        UUID membershipId = UUID.fromString(objectMapper.readTree(join.body()).path("id").asString());
        assertThat(postJson("/api/v1/me/neighborhood/verification-requests",
                returneeGate.accessToken(), "").statusCode()).isEqualTo(200);
        HttpResponse<String> approve = postJson(
                "/api/v1/admin/neighborhood-memberships/" + membershipId + "/verification?decision=APPROVE",
                adminGate.accessToken(), "");
        assertThat(approve.statusCode()).as("the returnee's verification approve: %s", body(approve))
                .isEqualTo(200);
        assertThat(objectMapper.readTree(approve.body()).path("verificationState").asString())
                .isEqualTo("VERIFIED");

        // (E) The question lands and the community ANSWERS it: the
        // returnee's GENERAL post is visible in their feed, and the
        // neighbor's comment carries the reply.
        HttpResponse<String> question = postJson("/api/v1/neighborhood/posts", returneeGate.accessToken(), """
                {"locationId":"%s","category":"GENERAL",
                 "title":"عدت بعد سنوات للسكن في الحي",
                 "body":"ما أفضل الطرق لاستعادة سياق الحي والتعرف على الأعمال الموثوقة؟"}"""
                .formatted(QUDSAYYA_OLD_TOWN));
        assertThat(question.statusCode()).as("the returnee's question: %s", body(question)).isEqualTo(201);
        UUID questionId = UUID.fromString(objectMapper.readTree(question.body()).path("id").asString());
        HttpResponse<String> feed = getWithBearer("/api/v1/neighborhood/posts", returneeGate.accessToken());
        assertThat(feed.statusCode()).as("the feed after the join: %s", body(feed)).isEqualTo(200);
        assertThat(feedRowCarrying(feed, questionId))
                .as("the returnee's own question is visible in their feed").isNotNull();
        HttpResponse<String> reply = postJson(
                "/api/v1/posts/" + questionId + "/comments", neighborGate.accessToken(),
                "{\"body\":\"أهلًا بعودتك — ابدأ بسوق الحي وتوصيات الجيران، وكل عمل موثق له صفحة عامة تحمل شارة التوثيق.\"}");
        assertThat(reply.statusCode()).as("the neighbor's answer: %s", body(reply)).isEqualTo(201);
        HttpResponse<String> comments = getWithBearer(
                "/api/v1/posts/" + questionId + "/comments", returneeGate.accessToken());
        assertThat(comments.statusCode()).as("the comments read: %s", body(comments)).isEqualTo(200);
        assertThat(objectMapper.readTree(comments.body()).path("content").path(0).path("body").asString())
                .as("the community's answer rides the read").contains("صفحة عامة");

        // (F) The trusted business: the feed card's source resolves the
        // provider's CURRENT facts, and the page opens with the SAME id —
        // one id, one truth (the source stays one across the screens).
        HttpResponse<String> feedWithSource = getWithBearer(
                "/api/v1/neighborhood/posts", returneeGate.accessToken());
        JsonNode recommendationRow = feedRowCarrying(feedWithSource, recommendationId);
        assertThat(recommendationRow).as("the recommendation rides the returnee's feed").isNotNull();
        assertThat(recommendationRow.path("recommendedProvider").path("providerId").asString())
                .as("the feed card's source id").isEqualTo(businessId.toString());
        assertThat(recommendationRow.path("recommendedProvider").path("verificationStatus").asString())
                .as("the feed card carries the earned trust badge").isEqualTo("VERIFIED");
        HttpResponse<String> publicPage = getWithBearer(
                "/api/v1/providers/" + businessId + "/public", returneeGate.accessToken());
        assertThat(publicPage.statusCode()).as("the business's public page: %s", body(publicPage))
                .isEqualTo(200);
        JsonNode pageView = objectMapper.readTree(publicPage.body());
        assertThat(pageView.path("id").asString())
                .as("the page's id IS the feed card's source id — the source stays one")
                .isEqualTo(businessId.toString());
        assertThat(pageView.path("displayName").asString()).isEqualTo("مكتب الواحة للخدمات");
        assertThat(pageView.path("status").asString())
                .as("the administrative trust decision rides the page").isEqualTo("VERIFIED");
        assertThat(pageView.path("services").path(0).path("title").asString())
                .as("the declared service fact rides the page").isEqualTo("استشارة وصيانة");
        assertThat(pageView.path("reviews").path("totalElements").asLong())
                .as("the honest empty reviews block — a business with no reviews yet")
                .isZero();

        // (G) The trust signal in their own scope: the market board reads
        // the membership's board — the neighbor's row rides it.
        HttpResponse<String> board = getWithBearer(
                "/api/v1/neighborhood/market", returneeGate.accessToken());
        assertThat(board.statusCode()).as("the market board in the returnee's scope: %s", body(board))
                .isEqualTo(200);
        assertThat(boardCarries(board, marketItemId))
                .as("the neighborhood's market row rides the returnee's board").isTrue();

        // (H) The honest boundary: a business that does not exist has no
        // page — the 404, never an invented placeholder.
        HttpResponse<String> unknownBusiness = getWithBearer(
                "/api/v1/providers/" + UUID.randomUUID() + "/public", returneeGate.accessToken());
        assertThat(unknownBusiness.statusCode())
                .as("the unknown business's page: the honest 404").isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // The journey's read helpers (the source-stays-one measurements)
    // ------------------------------------------------------------------

    /** The feed row carrying the given post id — null when absent. */
    private JsonNode feedRowCarrying(HttpResponse<String> feed, UUID postId) throws Exception {
        for (JsonNode row : objectMapper.readTree(feed.body()).path("content")) {
            if (row.path("id").asString().equals(postId.toString())) {
                return row;
            }
        }
        return null;
    }

    /** The board row carrying the given market item id. */
    private boolean boardCarries(HttpResponse<String> board, UUID itemId) throws Exception {
        for (JsonNode row : objectMapper.readTree(board.body()).path("content")) {
            if (row.path("id").asString().equals(itemId.toString())) {
                return true;
            }
        }
        return false;
    }

    /** The availability slot row starting exactly at the given instant. */
    private JsonNode slotStartingAt(HttpResponse<String> availability, String startsAt) throws Exception {
        for (JsonNode slot : objectMapper.readTree(availability.body())) {
            if (slot.path("startsAt").asString().equals(startsAt)) {
                return slot;
            }
        }
        return null;
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
    // The seller's commerce provisioning — the REAL product rows the
    // authoritative-pricing source resolves (the RMA journey's own shape).
    // ------------------------------------------------------------------

    private UUID registerProduct(String sellerToken, long priceMinor) throws Exception {
        HttpResponse<String> created = postJson("/api/v1/store/products", sellerToken, """
                {"storeCategoryCode":"journey-appliances","title":"Journey purpose service",
                 "description":"J30 journey stock","priceMinor":%d,"currency":"SAR"}"""
                .formatted(priceMinor));
        assertThat(created.statusCode()).as("product register: %s", body(created)).isEqualTo(201);
        return java.util.UUID.fromString(objectMapper.readTree(created.body()).path("id").asString());
    }

    private void setStock(String sellerToken, UUID productId, int quantity) throws Exception {
        HttpResponse<String> stocked = postJsonOrPut("/api/v1/store/products/" + productId + "/inventory",
                sellerToken, "{\"stockQuantity\":" + quantity + "}", "PUT");
        assertThat(stocked.statusCode()).as("stock write: %s", body(stocked)).isEqualTo(200);
    }

    /**
     * The availability window: the provider's own bookable slot, the exact
     * window the booking will claim (the booking journey's own shape).
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
                "Purpose Journey Gate Integration Test Client");
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
     * house polling shape: 200ms steps, 10s ceiling). The wait is EXACT
     * and TYPED: it polls the very count the assertion below reads.
     */
    private void awaitTypedNotificationRows(String type, UUID refId, int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM notifications WHERE type = ? AND message LIKE '%' || ? || '%'",
                    Integer.class, type, refId);
            if (rows != null && rows >= expected) {
                return;
            }
            Thread.sleep(200);
        }
    }

    /** The bounded poll for the order's own status (the payment listener's hop). */
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

    /** The bounded poll for the intent's own status (the settlement/refund listener's hop). */
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

    private HttpResponse<String> putJson(String path, String accessToken, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
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
