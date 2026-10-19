package com.marketplace.knowledge;

import test.config.IntegrationContainers;
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
import java.time.Instant;
import java.util.Base64;
import java.util.List;
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
 * W9 / J08 + J33 (the unified plan §3, the register's rows) — the news
 * correction/withdrawal cycle and the urgent-alert delivery tracking, both
 * measured end-to-end on the REAL chain: real PostgreSQL + real Redis + the
 * real Flyway schema (V178's news tables, V231's dedup index, V199's
 * delivery ledger) + the real login gate's Bearer + the real Modulith event
 * machinery (the AFTER_COMMIT urgent-alert fan-out through the routing
 * engine).
 *
 * <p><b>The honestly-measured laws this class freezes:</b></p>
 * <ol>
 *   <li><b>J08 — the correction/withdrawal cycle:</b> a publisher is born
 *       UNVERIFIED (the honest registry), the verdict's only mover is the
 *       admin APPROVE, and only a VERIFIED outlet publishes. The published
 *       item is born displayed with its REQUIRED attribution pair (the
 *       original link + the original date). The CORRECTION re-submits the
 *       complete content with the REQUIRED note in the same write — the
 *       corrected headline reaches the PUBLIC board measured on the wire,
 *       and the original publication date NEVER moves. The WITHDRAWAL flips
 *       the domain flag in one transaction (204): the public board and the
 *       detail stop returning the item immediately, a withdrawn item reads
 *       as the same 404 as an unknown one, and the row + its Envers trail
 *       stay (the flag, not the infrastructure soft delete).</li>
 *   <li><b>J33 — the delivery tracking:</b> the urgent alert from a VERIFIED
 *       delegated source fans out on the real AFTER_COMMIT chain to the
 *       scope's ACTIVE members: the unconditional INBOX row (the V180
 *       ledger key — the alert's own id as the source event id) plus the
 *       V199 channel ledger's EMAIL and WS rows, awaited with typed bounded
 *       polls. The ledger's real shape: rows are BORN COMPLETE — the table
 *       carries no state column at all (no queued/sent/failed vocabulary,
 *       measured through information_schema), so there is no retry/failure
 *       state machinery to track; the retry-safety is structural (the
 *       (event, recipient, channel) unique key + ON CONFLICT DO NOTHING).
 *       PUSH stays absent behind the D-10 seam (no PushNotificationChannel
 *       bean exists in any bootable profile — the W9 device journey's own
 *       measured finding, frozen here for the router path), and live FCM
 *       delivery remains BLOCKED_EXTERNAL (production credentials).</li>
 *   <li><b>The duplicate guard on the wire (#641):</b> the same source's
 *       live, non-withdrawn alert for the same neighborhood carrying the
 *       SAME title with an overlapping window answers the honest 409 BEFORE
 *       any write — no second row, no second event, no second fan-out —
 *       while a different title from the same source and scope is a
 *       legitimate new alert (its own fan-out lands).</li>
 *   <li><b>The public reads' honest security law:</b> the news board and
 *       detail ride the authenticated() blanket today (the NewsController
 *       javadoc's own documented state — no permitAll carve-out has landed
 *       for /api/v1/news), and the admin surface answers 403 to a consumer
 *       and 401 to the anonymous caller.</li>
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
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class NewsAndAlertDeliveryJourneyIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static org.testcontainers.postgresql.PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String PASSWORD = "it-news-alert-journey-password";
    private static final String LOGIN_PATH = "/login";

    // The fixed geo seed ids (R__seed_geo_qudsaya): the REAL administrative
    // tree the news geo gate and the urgent-alert scope gate resolve through.
    private static final String QUDSAYYA_CITY_LEVEL_2 = "11111111-1111-4111-8111-111111111103";
    private static final String AL_HAMAH_LEVEL_3 = "11111111-1111-4111-8111-111111111106";

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

    /**
     * J08 — the news correction/withdrawal cycle on the real chain: the
     * outlet's registration (born UNVERIFIED) → the verification APPROVE (the
     * trust mark) → the publication (born displayed, the attribution pair
     * riding every read) → the CORRECTION (the complete content re-submits
     * with the REQUIRED note; the public board carries the corrected
     * headline; the original publication date never moves) → the WITHDRAWAL
     * (the public reads stop immediately; the withdrawn item reads as the
     * same 404 as an unknown one) → the honest guards.
     */
    @Test
    void theNewsCorrectionAndWithdrawalCycle() throws Exception {
        String adminName = "it-news-admin-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(adminName, "CONSUMER", "ADMIN");
        GateResult adminGate = loginGate(adminName, PASSWORD);
        assertThat(adminGate.accessToken()).isNotBlank();

        // The public reader — an ordinary consumer. The news reads are not
        // /me-scoped, but the /me provisioning sync after each login is the
        // house step before any domain surface touches the caller's row.
        String readerName = "it-news-reader-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(readerName, "CONSUMER");
        GateResult readerGate = loginGate(readerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", readerGate.accessToken()).statusCode())
                .as("the reader's /me provisioning sync").isEqualTo(200);

        // (1) The outlet registers — born UNVERIFIED (the honest registry:
        // the trust mark arrives only through the review).
        HttpResponse<String> publisherResponse = postJson("/api/v1/admin/news/publishers",
                adminGate.accessToken(), """
                        {"name":"وكالة قدسيا للأنباء — دورة الاختبار",
                         "websiteUrl":"https://example.test/qudsayya-news"}""");
        assertThat(publisherResponse.statusCode()).as("publisher register: %s", body(publisherResponse)).isEqualTo(201);
        JsonNode publisher = objectMapper.readTree(publisherResponse.body());
        UUID publisherId = UUID.fromString(publisher.path("id").asString());
        assertThat(publisher.path("verificationState").asString())
                .as("a publisher is born UNVERIFIED — the honest registry").isEqualTo("UNVERIFIED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT verification_state FROM news_publishers WHERE id = ?", String.class, publisherId))
                .isEqualTo("UNVERIFIED");

        // (2) The verdict's only mover: APPROVE lands the trust mark.
        HttpResponse<String> approve = postJson(
                "/api/v1/admin/news/publishers/" + publisherId + "/verification?decision=APPROVE",
                adminGate.accessToken(), "");
        assertThat(approve.statusCode()).as("publisher approve: %s", body(approve)).isEqualTo(200);
        assertThat(objectMapper.readTree(approve.body()).path("verificationState").asString())
                .isEqualTo("VERIFIED");

        // (3) The publication: born displayed, the REQUIRED attribution pair
        // (the original link + the original date) riding the answer, and the
        // geo scope omitted (the whole board — the axis is optional).
        // Micro-aligned on purpose: the Postgres timestamptz column stores
        // MICROSECONDS — a nanosecond-carrying requested instant round-trips
        // ROUNDED (the create echo shows the in-memory nanos; the correction's
        // re-loaded answer the stored truth), and the immutability comparison
        // below would flake on the storage rounding. An exactly-microsecond
        // instant round-trips losslessly — the assertion stays exact.
        Instant originalPublishedAt = Instant.now().minusSeconds(3600)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        HttpResponse<String> published = postJson("/api/v1/admin/news/items", adminGate.accessToken(), """
                {"publisherId":"%s","title":"افتتاح الطريق الدائري الجديد لحي القضية",
                 "summary":"الترحيل الأول للخبر كما قيس على السلك",
                 "sourceUrl":"https://example.test/article/original",
                 "publishedAt":"%s"}"""
                .formatted(publisherId, originalPublishedAt.toString()));
        assertThat(published.statusCode()).as("news publish: %s", body(published)).isEqualTo(201);
        JsonNode item = objectMapper.readTree(published.body());
        UUID itemId = UUID.fromString(item.path("id").asString());
        assertThat(item.path("title").asString())
                .isEqualTo("افتتاح الطريق الدائري الجديد لحي القضية");
        assertThat(item.path("publisherName").asString())
                .as("the attribution rides the read (AC-20-09)").isEqualTo("وكالة قدسيا للأنباء — دورة الاختبار");
        assertThat(item.path("publisherVerified").asBoolean()).isTrue();
        assertThat(item.path("sourceUrl").asString()).isEqualTo("https://example.test/article/original");
        assertThat(item.path("publishedAt").asString()).isEqualTo(originalPublishedAt.toString());
        assertThat(item.path("corrected").asBoolean()).as("born uncorrected").isFalse();
        assertThat(item.path("correctionNote").asString()).as("no marker before a correction").isEmpty();

        // The geo gate's honest boundary (applied only when the scope is
        // present): the level-2 CITY node answers 400 BEFORE any write — no
        // row may exist for the refused request.
        HttpResponse<String> nonNeighborhoodScope = postJson("/api/v1/admin/news/items", adminGate.accessToken(), """
                {"publisherId":"%s","title":"نطاق غير حي","sourceUrl":"https://example.test/scope",
                 "publishedAt":"%s","locationId":"%s"}"""
                .formatted(publisherId, Instant.now().toString(), QUDSAYYA_CITY_LEVEL_2));
        assertThat(nonNeighborhoodScope.statusCode())
                .as("a level-2 scope: 400 before any write (the level-3 requirement)").isEqualTo(400);

        // The public board carries the item, and the detail read works —
        // measured on the wire with the reader's Bearer.
        assertThat(boardCarries(readerGate.accessToken(), itemId)).isTrue();
        HttpResponse<String> detail = getWithBearer("/api/v1/news/" + itemId, readerGate.accessToken());
        assertThat(detail.statusCode()).as("the detail read: %s", body(detail)).isEqualTo(200);
        assertThat(objectMapper.readTree(detail.body()).path("title").asString())
                .isEqualTo("افتتاح الطريق الدائري الجديد لحي القضية");

        // (4) The CORRECTION: the complete new content re-submits with the
        // REQUIRED note in the same write — the corrected headline reaches
        // the PUBLIC board, the marker rides the read, and the original
        // publication date NEVER moves (the honest history).
        HttpResponse<String> correction = patchJson("/api/v1/admin/news/items/" + itemId,
                adminGate.accessToken(), """
                        {"title":"افتتاح الطريق الدائري الجديد لحي القضية (صُحّح المسار)",
                         "summary":"المحتوى المصحّح كما قيس على السلك",
                         "sourceUrl":"https://example.test/article/corrected",
                         "correctionNote":"صُحّح رقم المساحة المذكور في الخبر"}""");
        assertThat(correction.statusCode()).as("correction: %s", body(correction)).isEqualTo(200);
        JsonNode corrected = objectMapper.readTree(correction.body());
        assertThat(corrected.path("title").asString())
                .isEqualTo("افتتاح الطريق الدائري الجديد لحي القضية (صُحّح المسار)");
        assertThat(corrected.path("corrected").asBoolean())
                .as("the correction is displayed, never the muting (AC-20-10)").isTrue();
        assertThat(corrected.path("correctionNote").asString()).isEqualTo("صُحّح رقم المساحة المذكور في الخبر");
        assertThat(corrected.path("correctedAt").asString())
                .as("the correction's timestamp rides the read").isNotEmpty();
        assertThat(corrected.path("publishedAt").asString())
                .as("the ORIGINAL publication date is immutable on correction").isEqualTo(originalPublishedAt.toString());

        // The public reads now carry the corrected headline AND the marker —
        // measured on the wire (the board row and the detail row).
        JsonNode boardRow = boardRow(readerGate.accessToken(), itemId);
        assertThat(boardRow).as("the corrected item stays on the board").isNotNull();
        assertThat(boardRow.path("title").asString())
                .as("the PUBLIC board carries the corrected headline")
                .isEqualTo("افتتاح الطريق الدائري الجديد لحي القضية (صُحّح المسار)");
        assertThat(boardRow.path("corrected").asBoolean()).isTrue();
        HttpResponse<String> correctedDetail = getWithBearer("/api/v1/news/" + itemId, readerGate.accessToken());
        assertThat(objectMapper.readTree(correctedDetail.body()).path("correctionNote").asString())
                .isEqualTo("صُحّح رقم المساحة المذكور في الخبر");

        // (5) The WITHDRAWAL: the domain flag lands in one transaction — the
        // public board stops returning the item and the detail answers the
        // honest 404 (a withdrawn item is indistinguishable from an unknown
        // one), while the row and its audit trail stay.
        HttpResponse<String> withdrawal = deleteWithBearer(
                "/api/v1/admin/news/items/" + itemId, adminGate.accessToken(), "");
        assertThat(withdrawal.statusCode()).as("withdrawal: %s", body(withdrawal)).isEqualTo(204);
        assertThat(boardCarries(readerGate.accessToken(), itemId)).isFalse();
        HttpResponse<String> withdrawnDetail = getWithBearer("/api/v1/news/" + itemId, readerGate.accessToken());
        assertThat(withdrawnDetail.statusCode())
                .as("a withdrawn item reads as the same 404 as an unknown one").isEqualTo(404);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_withdrawn FROM news_items WHERE id = ?", Boolean.class, itemId))
                .as("the withdrawal is the domain flag — the row stays as its own audit trail").isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT withdrawn_at FROM news_items WHERE id = ?", java.sql.Timestamp.class, itemId))
                .as("the withdrawal timestamp landed in the same write").isNotNull();

        // (CodeRabbit quick-win — J08's own promised measurement) The Envers
        // audit trail survives the withdrawal: birth, correction, and the
        // withdrawal itself each left a revision (revtype 0=ADD, 1=MOD — the
        // withdrawal is the domain-flag UPDATE, never a DEL).
        Integer newsAdds = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM news_items_aud WHERE id = ? AND revtype = 0", Integer.class, itemId);
        Integer newsMods = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM news_items_aud WHERE id = ? AND revtype = 1", Integer.class, itemId);
        assertThat(newsAdds)
                .as("Envers kept the birth revision through the withdrawal").isEqualTo(1);
        assertThat(newsMods)
                .as("Envers kept the correction and withdrawal revisions — the audit trail is the journey's own claim")
                .isGreaterThanOrEqualTo(2);

        // (6) The honest guards: correcting a withdrawn item is a conflict
        // (there is no un-withdraw lever in this wave), a second withdrawal
        // answers 409 (idempotence by refusal), and a non-admin never
        // reaches the admin surface.
        HttpResponse<String> correctWithdrawn = patchJson("/api/v1/admin/news/items/" + itemId,
                adminGate.accessToken(), """
                        {"title":"محاولة","sourceUrl":"https://example.test/x",
                         "correctionNote":"لن يمر"}""");
        assertThat(correctWithdrawn.statusCode())
                .as("correcting a withdrawn item: the honest 409").isEqualTo(409);
        HttpResponse<String> secondWithdrawal = deleteWithBearer(
                "/api/v1/admin/news/items/" + itemId, adminGate.accessToken(), "");
        assertThat(secondWithdrawal.statusCode())
                .as("a second withdrawal: idempotence by refusal").isEqualTo(409);

        HttpResponse<String> consumerOnAdmin = postJson("/api/v1/admin/news/items", readerGate.accessToken(), """
                {"publisherId":"%s","title":"ليس لي","sourceUrl":"https://example.test/no",
                 "publishedAt":"%s"}""".formatted(publisherId, Instant.now().toString()));
        assertThat(consumerOnAdmin.statusCode())
                .as("a consumer on the admin surface: the honest 403").isEqualTo(403);
        HttpResponse<String> anonymousOnAdmin = postJson("/api/v1/admin/news/publishers", null, """
                {"name":"مجهول"}""");
        assertThat(anonymousOnAdmin.statusCode())
                .as("the anonymous caller on the admin surface: the honest 401").isEqualTo(401);

        // The public reads' own honest security law (the NewsController
        // javadoc's documented state): the board rides the authenticated()
        // blanket — no permitAll carve-out has landed for /api/v1/news.
        HttpResponse<String> anonymousBoard = get(baseUrl() + "/api/v1/news", null, null);
        assertThat(anonymousBoard.statusCode())
                .as("the anonymous board read: authenticated-only today (the documented law)").isEqualTo(401);
    }

    /**
     * J33 — the urgent-alert delivery tracking on the real chain: the
     * delegated source's verification ladder (born UNVERIFIED → the write
     * gate's honest 409 → PENDING → VERIFIED), the alert's publication to a
     * level-3 scope, the AFTER_COMMIT fan-out to the scope's ACTIVE member
     * (the inbox row + the V199 channel ledger's EMAIL/WS rows, awaited with
     * typed bounded polls), the ledger's born-complete shape (no state
     * column at all — measured through information_schema), the #641
     * duplicate guard on the wire (409 before any write — no second row, no
     * second fan-out), and the different-title allowance (a legitimate new
     * alert whose own fan-out lands).
     */
    @Test
    void theUrgentAlertDeliveryTracking() throws Exception {
        String adminName = "it-alert-admin-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(adminName, "CONSUMER", "ADMIN");
        GateResult adminGate = loginGate(adminName, PASSWORD);

        // The scope's ACTIVE member: an ordinary consumer who joins the
        // alert's own level-3 neighborhood (the fan-out's two data owners
        // are the ACTIVE members + the opt-in subscribers; this journey
        // rides the membership leg).
        String memberName = "it-alert-member-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(memberName, "CONSUMER");
        GateResult memberGate = loginGate(memberName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", memberGate.accessToken()).statusCode())
                .as("the member's /me provisioning sync").isEqualTo(200);
        UUID memberId = jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE subject = ?", UUID.class, memberName);
        HttpResponse<String> joined = putJson("/api/v1/me/neighborhood", memberGate.accessToken(),
                "{\"locationId\":\"" + AL_HAMAH_LEVEL_3 + "\"}");
        assertThat(joined.statusCode()).as("the member joins the alert's scope: %s", body(joined)).isEqualTo(201);
        assertThat(objectMapper.readTree(joined.body()).path("locationId").asString())
                .isEqualTo(AL_HAMAH_LEVEL_3);

        // (1) The delegation registers — born UNVERIFIED (an official body
        // cannot self-declare its own authority).
        HttpResponse<String> sourceResponse = postJson("/api/v1/admin/urgent-alert-sources",
                adminGate.accessToken(), """
                        {"name":"دفاع المدني — ريف دمشق","sourceType":"CIVIL_DEFENSE"}""");
        assertThat(sourceResponse.statusCode()).as("source register: %s", body(sourceResponse)).isEqualTo(201);
        UUID sourceId = UUID.fromString(objectMapper.readTree(sourceResponse.body()).path("id").asString());
        assertThat(objectMapper.readTree(sourceResponse.body()).path("verificationState").asString())
                .isEqualTo("UNVERIFIED");

        // (2) The delegated-source write gate FIRST (AC-20-01's deterministic
        // eligibility): an UNVERIFIED source cannot publish — the honest 409
        // before any write.
        HttpResponse<String> prematurePublish = postJson("/api/v1/admin/urgent-alerts",
                adminGate.accessToken(), alertBody(sourceId, "انقطاع المياه صباح الخميس",
                        Instant.now().minusSeconds(60), Instant.now().plus(Duration.ofHours(2))));
        assertThat(prematurePublish.statusCode())
                .as("publish from an UNVERIFIED source: the honest 409").isEqualTo(409);

        // (3) The verification ladder: the review request moves UNVERIFIED →
        // PENDING, the confirm lands VERIFIED.
        HttpResponse<String> reviewRequest = postJson(
                "/api/v1/admin/urgent-alert-sources/" + sourceId + "/verification/request",
                adminGate.accessToken(), "");
        assertThat(reviewRequest.statusCode()).as("review request: %s", body(reviewRequest)).isEqualTo(200);
        assertThat(objectMapper.readTree(reviewRequest.body()).path("verificationState").asString())
                .isEqualTo("PENDING");
        HttpResponse<String> confirm = postJson(
                "/api/v1/admin/urgent-alert-sources/" + sourceId + "/verification/confirm",
                adminGate.accessToken(), "");
        assertThat(confirm.statusCode()).as("confirm: %s", body(confirm)).isEqualTo(200);
        assertThat(objectMapper.readTree(confirm.body()).path("verificationState").asString())
                .isEqualTo("VERIFIED");

        // (4) The publication: a CRITICAL alert to the member's own scope,
        // window covering now.
        String alertTitle = "انقطاع المياه في الهامّة صباح الخميس "
                + UUID.randomUUID().toString().substring(0, 6);
        HttpResponse<String> published = postJson("/api/v1/admin/urgent-alerts", adminGate.accessToken(),
                alertBody(sourceId, alertTitle, Instant.now().minusSeconds(60), Instant.now().plus(Duration.ofHours(2))));
        assertThat(published.statusCode()).as("alert publish: %s", body(published)).isEqualTo(201);
        JsonNode alert = objectMapper.readTree(published.body());
        UUID alertId = UUID.fromString(alert.path("alertId").asString());
        assertThat(alert.path("level").asString()).isEqualTo("CRITICAL");
        assertThat(alert.path("locationId").asString()).isEqualTo(AL_HAMAH_LEVEL_3);
        assertThat(alert.path("withdrawn").asBoolean()).isFalse();
        assertThat(alert.path("sourceName").asString())
                .as("the honest attribution is the SOURCE's own name (AC-20-06)").isEqualTo("دفاع المدني — ريف دمشق");

        // (5) The fan-out, measured as rows with typed bounded polls (the
        // AFTER_COMMIT listener's own REQUIRES_NEW hop): the unconditional
        // INBOX row (the V180 ledger key — the alert's own id as the source
        // event id) and the V199 channel ledger's rows.
        awaitTypedInboxRows(memberId, alertId, 1);
        Integer inboxRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'URGENT_ALERT'"
                        + " AND source_event_id = ? AND is_deleted = false",
                Integer.class, memberId, alertId);
        assertThat(inboxRows).as("the member's URGENT_ALERT inbox row (the V180 ledger leg)").isEqualTo(1);

        awaitChannelLedgerRows(alertId, 2);
        Integer emailRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE event_id = ? AND channel = 'EMAIL'",
                Integer.class, alertId);
        Integer wsRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE event_id = ? AND channel = 'WS'",
                Integer.class, alertId);
        Integer pushRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE event_id = ? AND channel = 'PUSH'",
                Integer.class, alertId);
        assertThat(emailRows).as("the EMAIL leg's V199 ledger row (the hierarchical default admits it)").isEqualTo(1);
        assertThat(wsRows).as("the WS leg's V199 ledger row (the hierarchical default admits it)").isEqualTo(1);
        assertThat(pushRows)
                .as("the PUSH leg stays absent behind the D-10 seam — no PushNotificationChannel bean exists "
                        + "in any bootable profile (the W9 device journey's own measured finding)")
                .isZero();

        // The ledger's real delivery "states": rows are BORN COMPLETE — the
        // table carries no state column at all (no queued/sent/failed
        // vocabulary, no retry/failure machinery to track; the retry-safety
        // is structural — the (event, recipient, channel) unique key with
        // ON CONFLICT DO NOTHING). Frozen through information_schema itself.
        List<String> ledgerColumns = jdbcTemplate.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_name = 'notification_deliveries'"
                        + " ORDER BY column_name",
                String.class);
        assertThat(ledgerColumns)
                .as("the V199 ledger's own columns — the row IS the audit trail, born complete")
                .containsExactlyInAnyOrder("id", "event_id", "recipient_id", "channel", "delivered_at");
        Integer undatedRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE event_id = ? AND delivered_at IS NULL",
                Integer.class, alertId);
        assertThat(undatedRows).as("every row carries its delivery moment (born complete)").isZero();

        // The member's own read surface closes the loop: the feed carries
        // the arrival over HTTP.
        HttpResponse<String> feed = getWithBearer("/api/v1/notifications", memberGate.accessToken());
        assertThat(feed.statusCode()).as("the inbox feed: %s", body(feed)).isEqualTo(200);
        boolean feedCarriesAlert = false;
        for (JsonNode row : objectMapper.readTree(feed.body()).path("content")) {
            if ("URGENT_ALERT".equals(row.path("type").asString())
                    && row.path("message").asString().contains(alertTitle)) {
                feedCarriesAlert = true;
            }
        }
        assertThat(feedCarriesAlert).as("the member's feed carries the urgent alert").isTrue();

        // (6) The #641 duplicate guard on the wire: the same source, the same
        // neighborhood, the SAME title, an overlapping window — the honest
        // 409 BEFORE any write (no second row, no second event, no second
        // fan-out).
        Integer inboxBeforeDuplicate = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'URGENT_ALERT'",
                Integer.class, memberId);
        HttpResponse<String> duplicate = postJson("/api/v1/admin/urgent-alerts", adminGate.accessToken(),
                alertBody(sourceId, alertTitle, Instant.now().minusSeconds(30), Instant.now().plus(Duration.ofHours(3))));
        assertThat(duplicate.statusCode())
                .as("the duplicate live window with the same title: the honest 409 (#641)").isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM urgent_alerts WHERE source_id = ? AND title = ?",
                Integer.class, sourceId, alertTitle))
                .as("the duplicate wrote no second row").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'URGENT_ALERT'",
                Integer.class, memberId))
                .as("the duplicate fired no second fan-out").isEqualTo(inboxBeforeDuplicate);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE event_id = ?", Integer.class, alertId))
                .as("the duplicate wrote no second channel delivery").isEqualTo(2);

        // (7) The honest allowance: a DIFFERENT title from the same source
        // and scope is a legitimate new alert — and its OWN fan-out lands.
        String secondTitle = "تعميم ثانٍ: أعمال صيانة الكهرباء "
                + UUID.randomUUID().toString().substring(0, 6);
        HttpResponse<String> secondAlert = postJson("/api/v1/admin/urgent-alerts", adminGate.accessToken(),
                alertBody(sourceId, secondTitle, Instant.now().minusSeconds(30), Instant.now().plus(Duration.ofHours(2))));
        assertThat(secondAlert.statusCode())
                .as("a different title: a legitimate new alert (#641's own allowance)").isEqualTo(201);
        UUID secondAlertId = UUID.fromString(objectMapper.readTree(secondAlert.body()).path("alertId").asString());
        awaitTypedInboxRows(memberId, secondAlertId, 1);
        awaitChannelLedgerRows(secondAlertId, 2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE event_id = ? AND channel = 'EMAIL'",
                Integer.class, secondAlertId))
                .as("the second alert's own EMAIL delivery (one row per event id — the ledger's key)").isEqualTo(1);

        // (8) The withdrawal — JT-10's honesty leg: the flags move once, the
        // surfaces stop serving the alert, and the notifications ledger
        // keeps the delivery facts.
        HttpResponse<String> withdrawn = postJson(
                "/api/v1/admin/urgent-alerts/" + alertId + "/withdraw", adminGate.accessToken(), "");
        assertThat(withdrawn.statusCode()).as("alert withdraw: %s", body(withdrawn)).isEqualTo(200);
        assertThat(objectMapper.readTree(withdrawn.body()).path("withdrawn").asBoolean()).isTrue();
        assertThat(objectMapper.readTree(withdrawn.body()).path("withdrawnAt").asString()).isNotEmpty();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE event_id = ?", Integer.class, alertId))
                .as("the ledger keeps the delivery facts after the withdrawal (JT-10)").isEqualTo(2);
    }

    /**
     * J08's verification gate, both directions on the real chain: an
     * UNVERIFIED outlet cannot publish, a REJECTED one cannot either (the
     * registry row stays — the honest refusal), and the APPROVE is the
     * recovery lever that finally opens the publication gate. A VERIFIED
     * outlet cannot be re-approved (409 with the machine's own words) and
     * cannot be rejected either (revocation is not this wave's lever — the
     * per-item withdrawal is).
     */
    @Test
    void theNewsPublisherVerificationGate() throws Exception {
        String adminName = "it-news-gate-admin-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(adminName, "CONSUMER", "ADMIN");
        GateResult adminGate = loginGate(adminName, PASSWORD);

        // (1) The UNVERIFIED outlet: the delegated-source gate answers 409
        // before any write.
        HttpResponse<String> unverified = postJson("/api/v1/admin/news/publishers",
                adminGate.accessToken(), "{\"name\":\"بوابة غير موثقة\"}");
        assertThat(unverified.statusCode()).isEqualTo(201);
        UUID unverifiedId = UUID.fromString(objectMapper.readTree(unverified.body()).path("id").asString());
        HttpResponse<String> publishFromUnverified = postJson("/api/v1/admin/news/items", adminGate.accessToken(), """
                {"publisherId":"%s","title":"خبر من وكالة غير موثقة",
                 "sourceUrl":"https://example.test/unverified","publishedAt":"%s"}"""
                .formatted(unverifiedId, Instant.now().toString()));
        assertThat(publishFromUnverified.statusCode())
                .as("an UNVERIFIED publisher cannot publish — the delegated-source gate").isEqualTo(409);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM news_items WHERE publisher_id = ?", Integer.class, unverifiedId))
                .as("the refused publication wrote no row").isZero();

        // (2) The REJECT: the claim is refused, the row stays (the honest
        // registry) — and the gate still answers 409.
        HttpResponse<String> reject = postJson(
                "/api/v1/admin/news/publishers/" + unverifiedId + "/verification?decision=REJECT",
                adminGate.accessToken(), "");
        assertThat(reject.statusCode()).as("reject: %s", body(reject)).isEqualTo(200);
        assertThat(objectMapper.readTree(reject.body()).path("verificationState").asString()).isEqualTo("REJECTED");
        HttpResponse<String> publishFromRejected = postJson("/api/v1/admin/news/items", adminGate.accessToken(), """
                {"publisherId":"%s","title":"خبر من وكالة مرفوضة",
                 "sourceUrl":"https://example.test/rejected","publishedAt":"%s"}"""
                .formatted(unverifiedId, Instant.now().toString()));
        assertThat(publishFromRejected.statusCode())
                .as("a REJECTED publisher cannot publish either").isEqualTo(409);

        // (3) The APPROVE is the recovery lever: a REJECTED outlet moves to
        // VERIFIED and the publication gate finally opens.
        HttpResponse<String> recover = postJson(
                "/api/v1/admin/news/publishers/" + unverifiedId + "/verification?decision=APPROVE",
                adminGate.accessToken(), "");
        assertThat(recover.statusCode()).as("the recovery approve: %s", body(recover)).isEqualTo(200);
        assertThat(objectMapper.readTree(recover.body()).path("verificationState").asString()).isEqualTo("VERIFIED");
        HttpResponse<String> publishAfterRecovery = postJson("/api/v1/admin/news/items", adminGate.accessToken(), """
                {"publisherId":"%s","title":"خبر بعد استعادة التوثيق",
                 "sourceUrl":"https://example.test/recovered","publishedAt":"%s"}"""
                .formatted(unverifiedId, Instant.now().toString()));
        assertThat(publishAfterRecovery.statusCode())
                .as("the recovered outlet publishes — the gate follows the registry's truth").isEqualTo(201);

        // (4) The machine's own transition words: a VERIFIED outlet is not
        // re-approvable and not rejectable (revocation is not this wave's
        // lever — the per-item withdrawal is).
        HttpResponse<String> reApprove = postJson(
                "/api/v1/admin/news/publishers/" + unverifiedId + "/verification?decision=APPROVE",
                adminGate.accessToken(), "");
        assertThat(reApprove.statusCode()).as("a second approve: 409 with the machine's own words").isEqualTo(409);
        HttpResponse<String> rejectVerified = postJson(
                "/api/v1/admin/news/publishers/" + unverifiedId + "/verification?decision=REJECT",
                adminGate.accessToken(), "");
        assertThat(rejectVerified.statusCode())
                .as("a VERIFIED outlet is not rejectable — the per-item withdrawal is the lever").isEqualTo(409);

        // The unknown-outlet branch: the house 404.
        HttpResponse<String> unknownVerdict = postJson(
                "/api/v1/admin/news/publishers/" + UUID.randomUUID() + "/verification?decision=APPROVE",
                adminGate.accessToken(), "");
        assertThat(unknownVerdict.statusCode()).as("an unknown publisher: the honest 404").isEqualTo(404);
    }

    // ------------------------------------------------------------------
    // The journey's own helpers
    // ------------------------------------------------------------------

    /** The publish body for one urgent alert (the level-3 scope rides it). */
    private String alertBody(UUID sourceId, String title, Instant validFrom, Instant validUntil) {
        return """
                {"sourceId":"%s","locationId":"%s","level":"CRITICAL","title":"%s",
                 "body":"توقف ضخ المياه في الحي لأعمال صيانة مجدولة — التفاصيل في الصفحة الرسمية.",
                 "validFrom":"%s","validUntil":"%s"}"""
                .formatted(sourceId, AL_HAMAH_LEVEL_3, title, validFrom.toString(), validUntil.toString());
    }

    /** The board row for one item id — null when the board does not carry it. */
    private JsonNode boardRow(String accessToken, UUID itemId) throws Exception {
        HttpResponse<String> board = getWithBearer("/api/v1/news?size=50", accessToken);
        assertThat(board.statusCode()).as("the board read: %s", body(board)).isEqualTo(200);
        for (JsonNode row : objectMapper.readTree(board.body()).path("content")) {
            if (itemId.toString().equals(row.path("id").asString())) {
                return row;
            }
        }
        return null;
    }

    private boolean boardCarries(String accessToken, UUID itemId) throws Exception {
        return boardRow(accessToken, itemId) != null;
    }

    /**
     * The bounded poll for the alert's inbox row (the AFTER_COMMIT
     * listener's REQUIRES_NEW hop) — typed to the exact count the assertion
     * reads: the recipient's URGENT_ALERT rows whose source event id IS the
     * alert's own id (the V180 ledger key; the house polling shape: 200ms
     * steps, 10s ceiling).
     */
    private void awaitTypedInboxRows(UUID recipientId, UUID sourceEventId, int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM notifications WHERE recipient_id = ? AND type = 'URGENT_ALERT'"
                            + " AND source_event_id = ? AND is_deleted = false",
                    Integer.class, recipientId, sourceEventId);
            if (rows != null && rows >= expected) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("urgent-alert rows for event " + sourceEventId
                + " never reached " + expected + " within 10s");
    }

    /**
     * The bounded poll for the alert's channel-ledger rows — typed to the
     * exact event id the assertions read (one row per admitted channel).
     */
    private void awaitChannelLedgerRows(UUID eventId, int expected) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM notification_deliveries WHERE event_id = ?",
                    Integer.class, eventId);
            if (rows != null && rows >= expected) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("channel ledger rows for event " + eventId
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
                .roles(roles.length == 0 ? new String[]{"CONSUMER"} : roles)
                .build());
    }

    private GateResult loginGate(String username, String password) throws Exception {
        AuthorizationServerFixture.registerLoginGateClient(registeredClientRepository,
                "News and Alert Journey Gate Integration Test Client");
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

    private HttpRequest.Builder jsonRequest(String path, String accessToken) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "application/json");
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
        }
        return builder;
    }

    private HttpResponse<String> postJson(String path, String accessToken, String json) throws Exception {
        HttpRequest request = jsonRequest(path, accessToken)
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putJson(String path, String accessToken, String json) throws Exception {
        HttpRequest request = jsonRequest(path, accessToken)
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> patchJson(String path, String accessToken, String json) throws Exception {
        // The correction's verb — the same builder form the house's
        // postJsonOrPut uses for the method-override shapes.
        HttpRequest request = jsonRequest(path, accessToken)
                .method("PATCH", HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> deleteWithBearer(String path, String accessToken, String json) throws Exception {
        // JDK HttpClient's DELETE() takes no body — the official form for a
        // body-carrying delete is a method override string on the builder.
        HttpRequest request = jsonRequest(path, accessToken)
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
        // 2000 (the house window): a ProblemDetail's fieldErrors — the piece
        // that names the violated constraint — live past the traceId.
        return response.body() == null ? "" : response.body().substring(0, Math.min(2000, response.body().length()));
    }
}
