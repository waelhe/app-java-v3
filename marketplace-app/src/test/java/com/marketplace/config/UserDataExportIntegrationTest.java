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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.marketplace.MarketplaceApplication;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * I7 Phase 2 (account-pseudonymization-plan §5-ج — the Art. 20 export
 * contract's integration guard): the data-subject export on real
 * PostgreSQL + real Redis, through real HTTP — the PKCE login gate (the
 * L23 pattern, verbatim) mints a real Bearer, and
 * {@code GET /api/v1/users/me/export} answers the plan's contract:
 * <ol>
 *   <li><b>Provenance:</b> exactly what he provided — his profile, his
 *       first-party booking (status/dates/amounts), the review HE authored,
 *       the messages HE sent, his media metadata, his notifications.</li>
 *   <li><b>Counterparty minimality:</b> shared records carry the
 *       counterparty as an opaque UUID; the counterparty's review, message,
 *       and notification never appear; his booking entry carries no
 *       free-text notes.</li>
 *   <li><b>Exclusions:</b> no audit/system column keys anywhere in the
 *       document (recursive key scan) and no reply field on his review.</li>
 *   <li><b>The boundary notice:</b> the response's own export header
 *       documents scope + date (the auditable Art. 20 execution record).</li>
 *   <li><b>The security contract:</b> 401 without a token.</li>
 *   <li><b>Symmetric isolation:</b> the counterparty's own export contains
 *       HIS authored content and never the requester's — the provenance
 *       rule cuts both ways.</li>
 * </ol>
 *
 * <p><b>Why real Flyway (the V33 lesson):</b> the schema under test is the
 * production schema ({@code spring.flyway.enabled=true} +
 * {@code ddl-auto=none}) — a create-drop profile would rebuild the schema
 * from the entities and hide exactly the FK/id-space facts the export
 * contract is measured against (V6's {@code reviews.provider_id
 * REFERENCES users(id)}).
 *
 * <p><b>Why the PKCE login gate (the L23 pattern):</b> the only honest way
 * to exercise the self-service surface is the real authorization-code flow
 * on the real authorization server — the export must answer a real Bearer
 * minted by the real chain (fixtures follow the official wiring,
 * {@code RegisteredClientRepository.save} + {@code UserDetailsManager}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The production-shaped schema: real Flyway (V1..V46), no ddl-auto.
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The production cache type (the test profile defaults to 'simple').
        "spring.cache.type=redis",
        "spring.cache.redis.time-to-live=1h",
        // CI connection budget (the I5/L14 harness rationale).
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",
        // The login gate's client fixtures (the L23 gate properties).
        "marketplace.security.oauth2.client.client-id=marketplace-web-client",
        "marketplace.security.oauth2.client.secret=it-app-secret",
        "marketplace.security.oauth2.client.redirect-uris=http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class UserDataExportIntegrationTest {

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
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final String PASSWORD = "it-export-password";
    private static final String REQUESTER = "it-export-requester";
    private static final String COUNTERPARTY = "it-export-counterparty";
    private static final String CLIENT_ID = "it-login-gate-client";
    private static final String CLIENT_SECRET = "it-login-gate-secret";
    private static final String REDIRECT_URI = "https://login-gate.test.example/callback";
    private static final String LOGIN_PATH = "/login";
    private static final String AUTHORIZE_PATH = "/oauth2/authorize";
    private static final String TOKEN_PATH = "/oauth2/token";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    /** The counterparty's authored content — words that must never ride the requester's export. */
    private static final String COUNTERPARTY_MESSAGE = "counterparty-only secret words";
    private static final String COUNTERPARTY_REVIEW_COMMENT = "the requester was late (counterparty words)";
    private static final String REQUESTER_REVIEW_COMMENT = "punctual and thorough (requester words)";
    private static final String COUNTERPARTY_NOTIFICATION = "New booking request (counterparty's notification)";

    @Test
    void fullContract_provenanceMinimalityExclusionsNoticeSecurityIsolation() throws Exception {
        // The two live identities, through the real gate + the real sync.
        registerUser(REQUESTER, "USER");
        registerUser(COUNTERPARTY, "USER");
        GateResult requesterGate = loginGate(REQUESTER, PASSWORD);
        GateResult counterpartyGate = loginGate(COUNTERPARTY, PASSWORD);
        UUID requesterId = syncProjectionIdViaMe(requesterGate.accessToken());
        UUID counterpartyId = syncProjectionIdViaMe(counterpartyGate.accessToken());

        // The shared records, seeded on the real Flyway schema (FK parents
        // exist: both users rows came from the real sync).
        UUID bookingId = seedSharedHistory(requesterId, counterpartyId);

        // -- The requester's export (the surface under test) ----------------

        HttpResponse<String> exportResponse = getWithBearer(
                "/api/v1/users/me/export", requesterGate.accessToken());
        assertThat(exportResponse.statusCode())
                .as("export call: %s", body(exportResponse)).isEqualTo(200);
        JsonNode export = objectMapper.readTree(exportResponse.body());

        // The boundary notice (§5-ج): scope + date inside the response.
        assertThat(export.path("export").path("generatedAt").asString()).isNotBlank();
        assertThat(export.path("export").path("scopeNotice").asString())
                .contains("Art. 20").contains("opaque identifier");

        // The profile section: his account row.
        JsonNode profile = export.path("profile");
        assertThat(profile.path("id").asString()).isEqualTo(requesterId.toString());
        assertThat(profile.path("subject").asString()).isEqualTo(REQUESTER);
        assertThat(profile.path("role").asString()).isEqualTo("CONSUMER");
        assertThat(profile.path("createdAt").asString()).isNotBlank();

        // Bookings: exactly his first-party booking, the plan's enumeration,
        // the counterparty as an opaque UUID, and NO notes key.
        JsonNode bookings = export.path("bookings");
        assertThat(bookings.isArray()).isTrue();
        assertThat(bookings.size()).isEqualTo(1);
        JsonNode booking = bookings.get(0);
        assertThat(booking.path("id").asString()).isEqualTo(bookingId.toString());
        assertThat(booking.path("role").asString()).isEqualTo("CONSUMER");
        assertThat(booking.path("counterpartyId").asString()).isEqualTo(counterpartyId.toString());
        assertThat(booking.path("status").asString()).isEqualTo("PENDING");
        assertThat(booking.path("priceCents").asLong()).isEqualTo(100_00L);
        assertThat(booking.path("currency").asString()).isEqualTo("SAR");
        assertThat(booking.path("createdAt").asString()).isNotBlank();
        assertThat(booking.has("notes"))
                .as("free texts are gate b-3's residual — never exported").isFalse();

        // Reviews: only the review HE authored, counterparty as opaque UUID.
        JsonNode reviews = export.path("reviews");
        assertThat(reviews.isArray()).isTrue();
        assertThat(reviews.size()).isEqualTo(1);
        JsonNode review = reviews.get(0);
        assertThat(review.path("direction").asString()).isEqualTo("CONSUMER_TO_PROVIDER");
        assertThat(review.path("rating").asInt()).isEqualTo(5);
        assertThat(review.path("comment").asString()).isEqualTo(REQUESTER_REVIEW_COMMENT);
        assertThat(review.path("reviewedProviderUserId").asString())
                .isEqualTo(counterpartyId.toString());
        assertThat(review.path("reviewedConsumerUserId").isNull()).isTrue();
        assertThat(review.has("reply"))
                .as("the counterparty's reply is his counterpart's content").isFalse();
        assertThat(exportResponse.body()).doesNotContain(COUNTERPARTY_REVIEW_COMMENT);

        // Conversations: the shared record with the counterparty as an
        // opaque UUID only.
        JsonNode conversations = export.path("conversations");
        assertThat(conversations.isArray()).isTrue();
        assertThat(conversations.size()).isEqualTo(1);
        assertThat(conversations.get(0).path("counterpartyUserId").asString())
                .isEqualTo(counterpartyId.toString());

        // Messages: ONLY what he sent.
        JsonNode messages = export.path("messages");
        assertThat(messages.isArray()).isTrue();
        assertThat(messages.size()).isEqualTo(1);
        assertThat(messages.get(0).path("content").asString())
                .isEqualTo("requester's own words");
        assertThat(exportResponse.body()).doesNotContain(COUNTERPARTY_MESSAGE);

        // Media: descriptive metadata only, never the thumbnail key.
        JsonNode media = export.path("media");
        assertThat(media.isArray()).isTrue();
        assertThat(media.size()).isEqualTo(1);
        JsonNode asset = media.get(0);
        assertThat(asset.path("contentType").asString()).isEqualTo("image/jpeg");
        assertThat(asset.path("sizeBytes").asLong()).isEqualTo(2048L);
        assertThat(asset.path("status").asString()).isEqualTo("PENDING_UPLOAD");
        assertThat(asset.path("objectKey").asString()).contains("listings/");
        assertThat(asset.has("thumbObjectKey")).isFalse();

        // Notifications: recipient-scoped only.
        JsonNode notifications = export.path("notifications");
        assertThat(notifications.isArray()).isTrue();
        assertThat(notifications.size()).isEqualTo(1);
        assertThat(notifications.get(0).path("message").asString())
                .isEqualTo("Your booking was created");
        assertThat(exportResponse.body()).doesNotContain(COUNTERPARTY_NOTIFICATION);

        // The exclusion rule (recursive): no audit/system column key anywhere
        // in the machine-readable document.
        assertNoForbiddenKeys(export);

        // The counterparty's PII never rides the requester's document.
        assertThat(exportResponse.body())
                .doesNotContain("counterparty@example.com")
                .doesNotContain("The Counterparty");

        // -- The security contract ------------------------------------------

        HttpResponse<String> anonymous = get("/api/v1/users/me/export", null);
        assertThat(anonymous.statusCode())
                .as("401 without a token — the standing /api/v1 contract").isEqualTo(401);

        // -- Symmetric isolation: the counterparty's own export -------------

        HttpResponse<String> counterpartyResponse = getWithBearer(
                "/api/v1/users/me/export", counterpartyGate.accessToken());
        assertThat(counterpartyResponse.statusCode()).isEqualTo(200);
        JsonNode counterpartyExport = objectMapper.readTree(counterpartyResponse.body());

        // HIS authored content IS there: the reverse review, his message,
        // his notification — and the same booking from the PROVIDER side.
        assertThat(counterpartyResponse.body()).contains(COUNTERPARTY_REVIEW_COMMENT);
        assertThat(counterpartyResponse.body()).contains(COUNTERPARTY_MESSAGE);
        assertThat(counterpartyResponse.body()).contains(COUNTERPARTY_NOTIFICATION);
        JsonNode theirBooking = counterpartyExport.path("bookings");
        assertThat(theirBooking.isArray()).isTrue();
        assertThat(theirBooking.size()).isEqualTo(1);
        assertThat(theirBooking.get(0).path("role").asString()).isEqualTo("PROVIDER");
        assertThat(theirBooking.get(0).path("counterpartyId").asString())
                .isEqualTo(requesterId.toString());
        assertThat(counterpartyExport.path("reviews").size()).isEqualTo(1);
        assertThat(counterpartyExport.path("reviews").get(0).path("direction").asString())
                .isEqualTo("PROVIDER_TO_CONSUMER");
        assertThat(counterpartyExport.path("reviews").get(0).path("reviewedConsumerUserId").asString())
                .isEqualTo(requesterId.toString());

        // The requester's authored content is NOT there.
        assertThat(counterpartyResponse.body())
                .doesNotContain(REQUESTER_REVIEW_COMMENT)
                .doesNotContain("requester's own words");
        assertNoForbiddenKeys(counterpartyExport);
    }

    /**
     * Seeds the shared history on the real schema (the Phase 1 seeding
     * convention — direct JDBC against the Flyway-owned tables): one
     * booking (requester = consumer), one forward review by the requester
     * and one reverse review by the counterparty (per-direction uniqueness,
     * V45), one conversation with one message from each side, one media
     * asset owned by the requester (provider_id is a user id — A1), and one
     * notification each.
     */
    private UUID seedSharedHistory(UUID requesterId, UUID counterpartyId) {
        UUID listingId = UUID.randomUUID();
        UUID bookingId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status)
                VALUES (?, ?, 'Export Fixture Listing', 'Reference-integrity listing', 'home', 100_00, 'SAR', 'ACTIVE')
                ON CONFLICT (id) DO NOTHING
                """,
                listingId, counterpartyId);
        jdbcTemplate.update(
                """
                INSERT INTO bookings (id, consumer_id, provider_id, listing_id, status, price_cents, currency, notes)
                VALUES (?, ?, ?, ?, 'PENDING', 100_00, 'SAR', 'notes stay out of the export')
                ON CONFLICT (id) DO NOTHING
                """,
                bookingId, requesterId, counterpartyId, listingId);
        // His forward review (reviewer = requester).
        jdbcTemplate.update(
                """
                INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, rating, comment, direction)
                VALUES (?, ?, ?, ?, 5, ?, 'CONSUMER_TO_PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """,
                UUID.randomUUID(), bookingId, requesterId, counterpartyId, REQUESTER_REVIEW_COMMENT);
        // The counterparty's reverse review about the requester — his
        // authored content, never the requester's export.
        jdbcTemplate.update(
                """
                INSERT INTO reviews (id, booking_id, reviewer_id, provider_id, reviewee_id, rating, comment, direction)
                VALUES (?, ?, ?, ?, ?, 2, ?, 'PROVIDER_TO_CONSUMER')
                ON CONFLICT (id) DO NOTHING
                """,
                UUID.randomUUID(), bookingId, counterpartyId, counterpartyId, requesterId,
                COUNTERPARTY_REVIEW_COMMENT);
        UUID conversationId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO conversations (id, booking_id, participant_a, participant_b)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                conversationId, bookingId, requesterId, counterpartyId);
        jdbcTemplate.update(
                """
                INSERT INTO messages (id, conversation_id, sender_id, content)
                VALUES (?, ?, ?, 'requester''s own words')
                ON CONFLICT (id) DO NOTHING
                """,
                UUID.randomUUID(), conversationId, requesterId);
        jdbcTemplate.update(
                """
                INSERT INTO messages (id, conversation_id, sender_id, content)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (id) DO NOTHING
                """,
                UUID.randomUUID(), conversationId, counterpartyId, COUNTERPARTY_MESSAGE);
        jdbcTemplate.update(
                """
                INSERT INTO media_assets (id, listing_id, provider_id, object_key, content_type, size_bytes, status, position)
                VALUES (?, ?, ?, ?, 'image/jpeg', 2048, 'PENDING_UPLOAD', 1)
                ON CONFLICT (id) DO NOTHING
                """,
                UUID.randomUUID(), listingId, requesterId,
                "listings/" + listingId + "/export-fixture.jpg");
        jdbcTemplate.update(
                """
                INSERT INTO notifications (id, recipient_id, type, message)
                VALUES (?, ?, 'BOOKING_CREATED', 'Your booking was created')
                ON CONFLICT (id) DO NOTHING
                """,
                UUID.randomUUID(), requesterId);
        jdbcTemplate.update(
                """
                INSERT INTO notifications (id, recipient_id, type, message)
                VALUES (?, ?, 'BOOKING_CREATED', ?)
                ON CONFLICT (id) DO NOTHING
                """,
                UUID.randomUUID(), counterpartyId, COUNTERPARTY_NOTIFICATION);
        return bookingId;
    }

    /** The plan's exclusion rule, enforced on the document itself (recursive). */
    private void assertNoForbiddenKeys(JsonNode node) {
        if (node.isObject()) {
            node.propertyNames().forEach(field ->
                    assertThat(field)
                            .as("internal system column must never ride the export: %s", field)
                            .isNotIn("createdBy", "updatedBy", "version", "isDeleted",
                                    "created_by", "updated_by"));
            node.propertyNames().forEach(field -> assertNoForbiddenKeys(node.get(field)));
        } else if (node.isArray()) {
            node.forEach(this::assertNoForbiddenKeys);
        }
    }

    // -- fixtures & helpers (the L23 gate shapes, verbatim) ----------------

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
                .clientName("Data-Subject Export Gate Integration Test Client")
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
     * sequence, verbatim: (1) unauthenticated authorize &rarr; 302 /login with
     * the session; (2) the login form's CSRF token (bound to the session);
     * (3) credentials POST &rarr; 302 to the SAVED authorization request;
     * (4) re-issuing the authorize request as the authenticated principal
     * &rarr; 302 to the client with the code; (5) the token exchange
     * (client_secret_basic + the PKCE verifier).
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

        // (1) Unauthenticated authorization request -> redirect to the login page.
        HttpResponse<String> authorizeFirst = get(authorizeUrl, null);
        assertThat(authorizeFirst.statusCode()).as("authorize should redirect to login: %s", body(authorizeFirst)).isEqualTo(302);
        assertThat(authorizeFirst.headers().firstValue("Location").orElse("")).contains(LOGIN_PATH);
        String sessionCookie = sessionCookie(authorizeFirst);
        assertThat(sessionCookie).as("spring-session cookie expected").isNotBlank();

        // (2) Fetch the login form; the CSRF token is bound to the session.
        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, sessionCookie);
        assertThat(loginPage.statusCode()).as("login page: %s", body(loginPage)).isEqualTo(200);
        String csrfToken = csrfTokenFrom(loginPage.body());
        assertThat(csrfToken).as("CSRF token must be rendered by the default login page").isNotBlank();
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);

        // (3) Submit credentials -> redirect back to the saved authorization request.
        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + password + "&_csrf=" + encode(csrfToken), sessionCookie);
        assertThat(loginPost.statusCode()).as("login should succeed: %s", body(loginPost)).isEqualTo(302);
        String savedRequest = loginPost.headers().firstValue("Location").orElse("");
        assertThat(savedRequest).as("login must resume the saved authorization request, not /login?error")
                .contains(AUTHORIZE_PATH);
        sessionCookie = latestSessionCookie(loginPost, sessionCookie);

        // (4) Re-issue the authorization request as an authenticated principal -> code.
        HttpResponse<String> authorizeSecond = get(absolute(savedRequest), sessionCookie);
        assertThat(authorizeSecond.statusCode())
                .as("authorize should redirect back to the client: %s", body(authorizeSecond)).isEqualTo(302);
        String redirect = authorizeSecond.headers().firstValue("Location").orElse("");
        assertThat(redirect).startsWith(REDIRECT_URI);
        assertThat(redirect).contains("code=");
        String authorizationCode = queryParam(redirect, "code");

        // (5) Exchange the code for tokens (client_secret_basic + PKCE verifier).
        HttpResponse<String> tokenResponse = postFormWithBasicAuth(TOKEN_PATH,
                "grant_type=authorization_code"
                        + "&code=" + encode(authorizationCode)
                        + "&redirect_uri=" + encode(REDIRECT_URI)
                        + "&code_verifier=" + codeVerifier);
        assertThat(tokenResponse.statusCode()).as("token endpoint: %s", body(tokenResponse)).isEqualTo(200);

        JsonNode tokens = objectMapper.readTree(tokenResponse.body());
        return new GateResult(tokens.path("access_token").asString());
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : baseUrl() + location;
    }

    /** PKCE verifier per the official RFC 7636 flow shape (login-gate test). */
    private static String randomCodeVerifier() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
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
        return "http://127.0.0.1:" + port;
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

    private record GateResult(String accessToken) {
    }
}
