package com.marketplace.community;

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
 * W6 / J06 (the unified plan §3, the register's J06 row) — the
 * neighborhood's used-goods/giveaway/lost-and-found composite journey on
 * the REAL chain (real PostgreSQL + real Redis + the real Flyway schema +
 * the real login gate's Bearer): the verified member publishes a priced
 * used possession and a FREE gift to their own neighborhood's market
 * board (the ONE pricing law: «مجاني ⇔ بلا سعر»), the buyer reads the
 * board under its filters, the seller closes the priced listing with the
 * mark-sold edge (the face-to-face settlement — the SOLD row STAYS as
 * the buyer-side social proof), the retried close is idempotent, the
 * gift's withdraw is the house soft delete — and the lost-and-found leg
 * carries the same journey's other close (the owner's own RESOLVED
 * flip). Every guard measured on the wire: the pricing law's 400s, the
 * non-seller's 403, the board's membership scope (a different
 * neighborhood's member sees nothing), and the non-LOST_FOUND flip's
 * honest 409.
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
class MarketUsedGoodsJourneyIntegrationTest {

    // The fixed geo seed ids (R__seed_geo_qudsaya): levels 0..3 of the
    // REAL administrative tree the membership gates resolve.
    private static final String QUDSAYYA_OLD_TOWN = "11111111-1111-4111-8111-111111111104";
    private static final String QUDSAYYA_SUBURB = "11111111-1111-4111-8111-111111111105";

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static org.testcontainers.postgresql.PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String PASSWORD = "it-market-journey-password";
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

    /**
     * The used-goods/giveaway journey: the verified seller's two listings
     * ride the board, the pricing law refuses both of its violations, the
     * buyer's board read is the membership's scope, the mark-sold closes
     * the priced listing (the SOLD row STAYS — the social proof), the
     * retried close writes nothing, and the gift's withdraw is the house
     * soft delete. A different neighborhood's member sees none of it.
     */
    @Test
    void theUsedGoodsAndGiveawayJourneyFromPublishToTheSoldSocialProof() throws Exception {
        // The seller joins the old town and earns the verification mark
        // (the trust badge the card carries).
        String sellerName = "it-market-seller-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(sellerName, "CONSUMER");
        GateResult sellerGate = loginGate(sellerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", sellerGate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> join = putJson("/api/v1/me/neighborhood", sellerGate.accessToken(),
                "{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}");
        assertThat(join.statusCode()).as("the seller's join: %s", body(join)).isEqualTo(201);
        UUID membershipId = UUID.fromString(objectMapper.readTree(join.body()).path("id").asString());
        assertThat(postJson("/api/v1/me/neighborhood/verification-requests",
                sellerGate.accessToken(), "").statusCode()).isEqualTo(200);
        String adminName = "it-market-admin-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(adminName, "ADMIN");
        GateResult adminGate = loginGate(adminName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", adminGate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> approve = postJson(
                "/api/v1/admin/neighborhood-memberships/" + membershipId + "/verification?decision=APPROVE",
                adminGate.accessToken(), "");
        assertThat(approve.statusCode()).as("the verification's approve: %s", body(approve)).isEqualTo(200);
        assertThat(objectMapper.readTree(approve.body()).path("verificationState").asString())
                .isEqualTo("VERIFIED");

        // (1) The priced used possession rides the board — the honest
        // echo carries the earned badge.
        HttpResponse<String> priced = postJson("/api/v1/neighborhood/market", sellerGate.accessToken(), """
                {"locationId":"%s","category":"ELECTRONICS","title":"مكيف سبليت 24000 وحدة — مستعمل بحالة جيدة",
                 "condition":"GOOD","priceCents":25000,"priceCurrency":"SAR",
                 "locationLabel":"شارع المسجد - مربع 2"}"""
                .formatted(QUDSAYYA_OLD_TOWN));
        assertThat(priced.statusCode()).as("the priced listing: %s", body(priced)).isEqualTo(201);
        JsonNode pricedView = objectMapper.readTree(priced.body());
        UUID pricedId = UUID.fromString(pricedView.path("id").asString());
        assertThat(pricedView.path("status").asString()).isEqualTo("ACTIVE");
        assertThat(pricedView.path("sellerVerified").asBoolean())
                .as("the earned trust badge rides the echo").isTrue();

        // (2) The gift rides the board — FREE carries NO price at all
        // (the ONE pricing law's own happy side).
        HttpResponse<String> gift = postJson("/api/v1/neighborhood/market", sellerGate.accessToken(), """
                {"locationId":"%s","category":"FREE","title":"كراسي مكتبية للإهداء — سليمتان",
                 "condition":"LIKE_NEW","locationLabel":"مدخل الحي الشرقي"}"""
                .formatted(QUDSAYYA_OLD_TOWN));
        assertThat(gift.statusCode()).as("the gift listing: %s", body(gift)).isEqualTo(201);
        UUID giftId = UUID.fromString(objectMapper.readTree(gift.body()).path("id").asString());
        assertThat(objectMapper.readTree(gift.body()).path("priceCents").isNull()).isTrue();

        // (3) The pricing law's both violations answer the friendly 400
        // BEFORE any write (the V90 CHECK's twin).
        HttpResponse<String> giftWithPrice = postJson("/api/v1/neighborhood/market",
                sellerGate.accessToken(), """
                        {"locationId":"%s","category":"FREE","title":"هدية بثمن","condition":"GOOD",
                         "priceCents":100,"priceCurrency":"SAR","locationLabel":"مدخل الحي الشرقي"}"""
                        .formatted(QUDSAYYA_OLD_TOWN));
        assertThat(giftWithPrice.statusCode())
                .as("a gift carries no price at all: %s", body(giftWithPrice)).isEqualTo(400);
        HttpResponse<String> saleWithoutPrice = postJson("/api/v1/neighborhood/market",
                sellerGate.accessToken(), """
                        {"locationId":"%s","category":"ELECTRONICS","title":"مكيف بلا ثمن","condition":"GOOD",
                         "locationLabel":"شارع المسجد"}"""
                        .formatted(QUDSAYYA_OLD_TOWN));
        assertThat(saleWithoutPrice.statusCode())
                .as("a sale category carries a strictly positive price: %s", body(saleWithoutPrice))
                .isEqualTo(400);

        // (4) The buyer — a fellow member of the same neighborhood —
        // reads the board: both listings ride it, the badge rides them.
        String buyerName = "it-market-buyer-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(buyerName, "CONSUMER");
        GateResult buyerGate = loginGate(buyerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", buyerGate.accessToken()).statusCode()).isEqualTo(200);
        assertThat(putJson("/api/v1/me/neighborhood", buyerGate.accessToken(),
                "{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}").statusCode()).isEqualTo(201);
        HttpResponse<String> board = getWithBearer("/api/v1/neighborhood/market", buyerGate.accessToken());
        assertThat(board.statusCode()).as("the board: %s", body(board)).isEqualTo(200);
        assertThat(boardCarries(board, pricedId)).as("the board carries the priced listing").isTrue();
        assertThat(boardCarries(board, giftId)).as("the board carries the gift").isTrue();

        // The category filter narrows to the gift alone.
        HttpResponse<String> freeBoard = getWithBearer(
                "/api/v1/neighborhood/market?category=FREE", buyerGate.accessToken());
        assertThat(freeBoard.statusCode()).isEqualTo(200);
        assertThat(boardCarries(freeBoard, giftId)).as("the FREE filter carries the gift").isTrue();
        assertThat(boardCarries(freeBoard, pricedId)).as("the FREE filter drops the priced listing").isFalse();

        // (5) The buyer cannot close the seller's listing — the 403.
        HttpResponse<String> buyerClose = postJson(
                "/api/v1/neighborhood/market/" + pricedId + "/sold", buyerGate.accessToken(), "");
        assertThat(buyerClose.statusCode()).as("the non-seller's close: %s", body(buyerClose)).isEqualTo(403);

        // (6) The seller closes the priced listing after the face-to-face
        // deal — the community market's own settlement.
        HttpResponse<String> markSold = postJson(
                "/api/v1/neighborhood/market/" + pricedId + "/sold", sellerGate.accessToken(), "");
        assertThat(markSold.statusCode()).as("mark sold: %s", body(markSold)).isEqualTo(200);
        assertThat(objectMapper.readTree(markSold.body()).path("status").asString()).isEqualTo("SOLD");

        // (7) The SOLD row STAYS on the board — the buyer-side social
        // proof, measured on the buyer's own read.
        HttpResponse<String> boardAfter = getWithBearer(
                "/api/v1/neighborhood/market", buyerGate.accessToken());
        assertThat(boardCarries(boardAfter, pricedId))
                .as("the SOLD row stays on the board (the social proof)").isTrue();
        for (JsonNode row : objectMapper.readTree(boardAfter.body()).path("content")) {
            if (row.path("id").asString().equals(pricedId.toString())) {
                assertThat(row.path("status").asString()).as("the card's display state").isEqualTo("SOLD");
            }
        }

        // (8) The retried close is idempotent — the honest 200, no
        // re-stamp (the updated_at fact rides untouched).
        String updatedAtBefore = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM neighborhood_market_items WHERE id = ?",
                String.class, pricedId);
        HttpResponse<String> retriedClose = postJson(
                "/api/v1/neighborhood/market/" + pricedId + "/sold", sellerGate.accessToken(), "");
        assertThat(retriedClose.statusCode()).as("the retried close: %s", body(retriedClose)).isEqualTo(200);
        assertThat(objectMapper.readTree(retriedClose.body()).path("status").asString()).isEqualTo("SOLD");
        String updatedAtAfter = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM neighborhood_market_items WHERE id = ?",
                String.class, pricedId);
        assertThat(updatedAtAfter).as("the idempotent close re-stamps nothing").isEqualTo(updatedAtBefore);

        // (9) The gift's withdraw is the house soft delete — the board
        // stops returning it.
        HttpResponse<String> withdraw = deleteWithBearer(
                "/api/v1/neighborhood/market/" + giftId, sellerGate.accessToken(), null);
        assertThat(withdraw.statusCode()).as("withdraw: %s", body(withdraw)).isEqualTo(204);
        HttpResponse<String> boardFinal = getWithBearer(
                "/api/v1/neighborhood/market", buyerGate.accessToken());
        assertThat(boardCarries(boardFinal, giftId))
                .as("the withdrawn gift leaves the board").isFalse();
        assertThat(boardCarries(boardFinal, pricedId))
                .as("the SOLD listing stays — only the withdraw takes a row off").isTrue();

        // (10) The board's scope is the membership's own: a member of the
        // suburb sees none of the old town's board (G-N1's one-membership
        // scope — the board you read is the board you're in).
        String suburbName = "it-market-suburb-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(suburbName, "CONSUMER");
        GateResult suburbGate = loginGate(suburbName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", suburbGate.accessToken()).statusCode()).isEqualTo(200);
        assertThat(putJson("/api/v1/me/neighborhood", suburbGate.accessToken(),
                "{\"locationId\":\"" + QUDSAYYA_SUBURB + "\"}").statusCode()).isEqualTo(201);
        HttpResponse<String> suburbBoard = getWithBearer(
                "/api/v1/neighborhood/market", suburbGate.accessToken());
        assertThat(suburbBoard.statusCode()).isEqualTo(200);
        assertThat(boardCarries(suburbBoard, pricedId))
                .as("a different neighborhood's board carries nothing of the old town").isFalse();
    }

    /**
     * The journey's lost-and-found leg: the member's report is born ACTIVE
     * (the discovery rail's own eligibility state), the owner's own flip
     * closes it (RESOLVED), a non-owner's flip answers 403, and a
     * non-LOST_FOUND post answers the honest 409 (it carries no
     * lost-and-found state to move).
     */
    @Test
    void theLostFoundReportJourneyAndItsOwnClose() throws Exception {
        String memberName = "it-lost-member-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(memberName, "CONSUMER");
        GateResult memberGate = loginGate(memberName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", memberGate.accessToken()).statusCode()).isEqualTo(200);
        assertThat(putJson("/api/v1/me/neighborhood", memberGate.accessToken(),
                "{\"locationId\":\"" + QUDSAYYA_OLD_TOWN + "\"}").statusCode()).isEqualTo(201);

        // The report is born ACTIVE.
        HttpResponse<String> report = postJson("/api/v1/neighborhood/posts", memberGate.accessToken(), """
                {"locationId":"%s","category":"LOST_FOUND",
                 "title":"قطت رمادية قرب مدرسة الحي","body":"ضاعت مساء أمس"}"""
                .formatted(QUDSAYYA_OLD_TOWN));
        assertThat(report.statusCode()).as("the report: %s", body(report)).isEqualTo(201);
        JsonNode reportView = objectMapper.readTree(report.body());
        UUID postId = UUID.fromString(reportView.path("id").asString());
        assertThat(reportView.path("lostFoundState").asString())
                .as("a fresh report is ACTIVE — the search is on").isEqualTo("ACTIVE");

        // A non-owner's flip answers 403.
        String otherName = "it-lost-other-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(otherName, "CONSUMER");
        GateResult otherGate = loginGate(otherName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", otherGate.accessToken()).statusCode()).isEqualTo(200);
        HttpResponse<String> foreignFlip = postJson("/api/v1/posts/" + postId + "/lost-found-state",
                otherGate.accessToken(), "{\"state\":\"RESOLVED\"}");
        assertThat(foreignFlip.statusCode()).as("the non-owner's flip: %s", body(foreignFlip)).isEqualTo(403);

        // The owner's own flip closes the report.
        HttpResponse<String> resolved = postJson("/api/v1/posts/" + postId + "/lost-found-state",
                memberGate.accessToken(), "{\"state\":\"RESOLVED\"}");
        assertThat(resolved.statusCode()).as("the owner's close: %s", body(resolved)).isEqualTo(200);
        assertThat(objectMapper.readTree(resolved.body()).path("lostFoundState").asString())
                .isEqualTo("RESOLVED");

        // A non-LOST_FOUND post answers the honest 409.
        HttpResponse<String> general = postJson("/api/v1/neighborhood/posts", memberGate.accessToken(), """
                {"locationId":"%s","category":"GENERAL","title":"شكرًا جيران",
                 "body":"منشور عام"}"""
                .formatted(QUDSAYYA_OLD_TOWN));
        assertThat(general.statusCode()).isEqualTo(201);
        UUID generalId = UUID.fromString(objectMapper.readTree(general.body()).path("id").asString());
        HttpResponse<String> wrongCategory = postJson("/api/v1/posts/" + generalId + "/lost-found-state",
                memberGate.accessToken(), "{\"state\":\"RESOLVED\"}");
        assertThat(wrongCategory.statusCode())
                .as("a non-LOST_FOUND post carries no state to move: %s", body(wrongCategory)).isEqualTo(409);
    }

    private boolean boardCarries(HttpResponse<String> board, UUID itemId) throws Exception {
        for (JsonNode row : objectMapper.readTree(board.body()).path("content")) {
            if (row.path("id").asString().equals(itemId.toString())) {
                return true;
            }
        }
        return false;
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

    private GateResult loginGate(String username, String password) throws Exception {
        AuthorizationServerFixture.registerLoginGateClient(registeredClientRepository,
                "Market Journey Gate Integration Test Client");
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30)).GET();
        if (accessToken != null) {
            builder.header("Authorization", "Bearer " + accessToken);
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

    private HttpResponse<String> deleteWithBearer(String path, String accessToken, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken);
        if (json != null) {
            builder.header("Content-Type", "application/json")
                    .method("DELETE", HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8));
        } else {
            builder.DELETE();
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
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
