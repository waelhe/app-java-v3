package com.marketplace.notifications;

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
import test.config.IntegrationContainers;
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
 * W9 / J11 (the unified plan §3, the register's J11 row) — the DEVICE CYCLE
 * composite journey on the REAL chain: the consumer's push token is
 * registered through the /me surface (ADR-0003 / plan D-10: V185's
 * {@code push_tokens} registry, UNIQUE token = the upsert key), the
 * delivery preference surface is set (the L22 type matrix V40 — widened to
 * PUSH by V185/V210 — and the §8.1 topic matrix V198), a REAL conversation
 * message fans out through the real Modulith event machinery
 * (MessageReceivedEvent → NotificationService.onMessageReceived → the
 * always-on in-app row + the preference-gated push dispatch through
 * PushTokenService), and the token is unregistered (Hibernate 7
 * {@code @SoftDelete} — the row stays as the audit trail, the live
 * registry answers empty).
 *
 * <p><b>The honestly-measured laws this class freezes:</b></p>
 * <ol>
 *   <li><b>The register upsert:</b> the same token re-registers as ONE row
 *       (the UNIQUE key collapses it; the platform rebinds — the reinstall
 *       path), and another user's re-register REBINDS the row to them (the
 *       documented "one hardware token, one row" law — the device's new
 *       owner wins).</li>
 *   <li><b>The preference gate's storage:</b> the type-level EMAIL opt-out
 *       and the explicit PUSH opt-in land as rows (V40's sparse-override
 *       matrix); the topic-level EMAIL switch lands in V198. The delivery
 *       path consults the TYPE-level row (the sendPush gate); the topic
 *       level governs the routing-engine consumers (§8.1's hierarchy).</li>
 *   <li><b>The delivery, as the code really records it:</b> the in-app row
 *       is unconditional (L22: "inside the app always" — the V180 ledger
 *       row the composite awaits); the push dispatch addresses the user's
 *       LIVE tokens through the real preference gate; and the V199
 *       {@code notification_deliveries} channel ledger carries NO row on
 *       this path — that ledger guards the dispute/urgent routers, whose
 *       PUSH leg stays behind the D-10 {@code PushNotificationChannel} seam
 *       (no bean exists in ANY bootable profile — measured). The typed
 *       path's idempotency rides the messaging replay key + the V180 inbox
 *       row, not the channel ledger.</li>
 *   <li><b>The fail-safe provider law:</b> the test profile's real bean is
 *       {@code NoopPushAdapter} (no FCM credentials bound — live FCM stays
 *       {@code BLOCKED_EXTERNAL}, production credentials): every send
 *       reports failure-WITHOUT-pruning, so the registry keeps its tokens
 *       — the measured no-op-provider contract. The real provider's
 *       UNREGISTERED-verdict pruning is proven at the module level
 *       ({@code PushTokenServiceTest} + {@code FcmHttpV1PushAdapterTest}).</li>
 *   <li><b>The unregister:</b> the owner's DELETE soft-deletes the row
 *       (is_deleted=true; the live count answers zero) and a subsequent
 *       notification delivers the in-app row but addresses NO device. A
 *       DIFFERENT caller's DELETE of another user's token is the scoped
 *       no-op (204, the row survives — the /me family never 403/404s: the
 *       delete is scoped to the caller's own row by construction).</li>
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
class DeviceCycleJourneyIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static org.testcontainers.postgresql.PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String PASSWORD = "it-device-journey-password";
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
     * The device cycle: register → preference-gated delivery through a REAL
     * conversation message → rebind (the reinstall path) → the ownership
     * guard → the owner's unregister → a subsequent notification with NO
     * device addressed. Every step rides the real HTTP surfaces, the real
     * PKCE gate, and the real AFTER_COMMIT event machinery.
     */
    @Test
    void theDeviceCycleFromRegistrationThroughPreferenceGatedDelivery() throws Exception {
        // The two consumers: A owns the device (the recipient), B sends the
        // message. The /me provisioning sync first — the strict
        // CurrentUserProvider every /me/** surface resolves through answers
        // "User not found for subject" until the first /users/me call lands
        // (the measured CI lesson every journey suite applies).
        String ownerName = "it-device-owner-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(ownerName);
        GateResult ownerGate = loginGate(ownerName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", ownerGate.accessToken()).statusCode())
                .as("the device owner's /me provisioning sync").isEqualTo(200);
        UUID ownerId = userIdBySubject(ownerName);

        String senderName = "it-device-sender-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(senderName);
        GateResult senderGate = loginGate(senderName, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", senderGate.accessToken()).statusCode())
                .as("the sender's /me provisioning sync").isEqualTo(200);

        // (1) The preference surface FIRST — the delivery's gates exist
        // before the event fires. The type-level matrix (V40, widened to
        // PUSH by V185/V210): EMAIL opts OUT for MESSAGE_RECEIVED, PUSH
        // opts IN explicitly (the sparse-override default is enabled — the
        // explicit row measures the switch is storable, not just implied).
        HttpResponse<String> typePrefs = putJson("/api/v1/notifications/preferences", ownerGate.accessToken(), """
                {"preferences":[{"type":"MESSAGE_RECEIVED","channel":"EMAIL","enabled":false},
                                {"type":"MESSAGE_RECEIVED","channel":"PUSH","enabled":true}]}""");
        assertThat(typePrefs.statusCode()).as("type preferences PUT: %s", body(typePrefs)).isEqualTo(200);
        JsonNode matrix = objectMapper.readTree(typePrefs.body());
        assertThat(preferenceInMatrix(matrix, "MESSAGE_RECEIVED", "EMAIL"))
                .as("the type-level EMAIL opt-out rides the effective matrix").isEqualTo(false);
        assertThat(preferenceInMatrix(matrix, "MESSAGE_RECEIVED", "PUSH"))
                .as("the explicit PUSH opt-in rides the effective matrix").isEqualTo(true);
        assertThat(preferenceInMatrix(matrix, "MESSAGE_RECEIVED", "DB"))
                .as("the in-app channel stays always-on in the matrix").isEqualTo(true);
        Boolean emailSwitch = jdbcTemplate.queryForObject(
                "SELECT enabled FROM notification_preferences WHERE user_id = ? "
                        + "AND type = 'MESSAGE_RECEIVED' AND channel = 'EMAIL'",
                Boolean.class, ownerId);
        assertThat(emailSwitch).as("the stored type-level EMAIL switch (V40)").isEqualTo(false);
        Boolean pushSwitch = jdbcTemplate.queryForObject(
                "SELECT enabled FROM notification_preferences WHERE user_id = ? "
                        + "AND type = 'MESSAGE_RECEIVED' AND channel = 'PUSH'",
                Boolean.class, ownerId);
        assertThat(pushSwitch).as("the stored type-level PUSH switch (the V185/V210-widened vocabulary)")
                .isEqualTo(true);

        // The topic-level matrix (V198, the §8.1 hierarchy's second layer):
        // EMAIL opts OUT for the MESSAGING family — the surface the
        // routing-engine consumers resolve (type level > topic level >
        // enabled default). The topic surface governs EMAIL/WS only (the
        // D-10 boundary the second test freezes).
        HttpResponse<String> topicPrefs = putJson(
                "/api/v1/notifications/preferences/topics", ownerGate.accessToken(), """
                {"preferences":[{"topic":"MESSAGING","channel":"EMAIL","enabled":false}]}""");
        assertThat(topicPrefs.statusCode()).as("topic preferences PUT: %s", body(topicPrefs)).isEqualTo(200);
        Boolean topicSwitch = jdbcTemplate.queryForObject(
                "SELECT enabled FROM notification_topic_preferences WHERE user_id = ? "
                        + "AND topic = 'MESSAGING' AND channel = 'EMAIL'",
                Boolean.class, ownerId);
        assertThat(topicSwitch).as("the stored topic-level EMAIL switch (V198)").isEqualTo(false);

        // (2) The device registers through the /me surface (D-10's
        // addressing record): 201, and the row exists with the caller's
        // identity and the platform.
        String deviceToken = "it-device-token-" + UUID.randomUUID();
        HttpResponse<String> registered = postJson("/api/v1/me/push-tokens", ownerGate.accessToken(), """
                {"token":"%s","platform":"ANDROID"}""".formatted(deviceToken));
        assertThat(registered.statusCode()).as("device register: %s", body(registered)).isEqualTo(201);
        JsonNode registerView = objectMapper.readTree(registered.body());
        assertThat(UUID.fromString(registerView.path("userId").asString()))
                .as("the register answer carries the caller's own identity").isEqualTo(ownerId);
        assertThat(registerView.path("platform").asString()).isEqualTo("ANDROID");
        Integer liveRowsAfterRegister = liveTokenRows(deviceToken);
        assertThat(liveRowsAfterRegister).as("the registry row exists (V185)").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT platform FROM push_tokens WHERE token = ?", String.class, deviceToken))
                .isEqualTo("ANDROID");

        // (3) The idempotent re-register — the UNIQUE token key collapses
        // the repeat into the ONE row (the upsert), and the platform
        // rebinds with it (the same device's platform change).
        HttpResponse<String> reRegistered = postJson("/api/v1/me/push-tokens", ownerGate.accessToken(), """
                {"token":"%s","platform":"IOS"}""".formatted(deviceToken));
        assertThat(reRegistered.statusCode()).as("re-register: %s", body(reRegistered)).isEqualTo(201);
        assertThat(liveTokenRows(deviceToken)).as("still ONE row — the token is the upsert key").isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT platform FROM push_tokens WHERE token = ?", String.class, deviceToken))
                .as("the re-register rebound the platform").isEqualTo("IOS");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id FROM push_tokens WHERE token = ?", java.util.UUID.class, deviceToken))
                .as("the row stays the owner's").isEqualTo(ownerId);

        // (4) The REAL notification event that carries push: the direct
        // conversation (L44) and the message — B-08's arrival chain.
        HttpResponse<String> conversation = postJson("/api/v1/messages/conversations/direct",
                senderGate.accessToken(), "{\"recipientId\":\"%s\"}".formatted(ownerId));
        assertThat(conversation.statusCode()).as("direct conversation open: %s", body(conversation))
                .isEqualTo(201);
        UUID conversationId = UUID.fromString(
                objectMapper.readTree(conversation.body()).path("id").asString());

        HttpResponse<String> message = postJson(
                "/api/v1/messages/conversations/" + conversationId + "/messages",
                senderGate.accessToken(), "{\"content\":\"is the device cycle proven?\"}");
        assertThat(message.statusCode()).as("message send: %s", body(message)).isEqualTo(201);

        // (5) The delivery, as the code really records it. The in-app row
        // is the unconditional leg (L22) — the V180 ledger row the real
        // AFTER_COMMIT listener writes for the message's other participant.
        awaitTypedInboxRows(ownerName, conversationId, 1);
        Integer inboxRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notifications WHERE recipient_id = ("
                        + "SELECT id FROM users WHERE subject = ?) AND type = 'MESSAGE_RECEIVED'"
                        + " AND message LIKE '%' || ? || '%' AND is_deleted = false",
                Integer.class, ownerName, conversationId);
        assertThat(inboxRows).as("the MESSAGE_RECEIVED in-app row (the V180 ledger leg)").isEqualTo(1);

        // The push leg addressed the LIVE token through the real preference
        // gate (the type-level PUSH switch admitted it) — and the test
        // profile's real provider bean is the honest NoopPushAdapter (no
        // FCM credentials): every send reports failure-WITHOUT-pruning, so
        // the registry keeps the token. The measured fail-safe contract —
        // a disabled provider never mutilates the deterministic registry.
        assertThat(liveTokenRows(deviceToken))
                .as("the token survives the inert provider's verdict (no pruning on failure)")
                .isEqualTo(1);

        // The V199 channel ledger's honest shape on this path: NO row. The
        // delivery ledger guards the dispute/urgent routers (its PUSH leg
        // waits on the D-10 PushNotificationChannel bean — no
        // implementation exists in any bootable profile, measured); the
        // typed-notification path's idempotency rides the messaging replay
        // key + the V180 inbox row. Asserting the absence freezes the real
        // recording contract instead of an invented one.
        Integer channelLedgerRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_deliveries WHERE recipient_id = ?",
                Integer.class, ownerId);
        assertThat(channelLedgerRows)
                .as("the typed path writes no V199 channel row (the ledger guards the routers)")
                .isZero();

        // The consumer's own read surface closes the loop: the inbox feed
        // carries the arrival over HTTP.
        HttpResponse<String> feed = getWithBearer("/api/v1/notifications", ownerGate.accessToken());
        assertThat(feed.statusCode()).as("the inbox feed: %s", body(feed)).isEqualTo(200);
        boolean feedCarriesArrival = false;
        for (JsonNode row : objectMapper.readTree(feed.body()).path("content")) {
            if ("MESSAGE_RECEIVED".equals(row.path("type").asString())
                    && row.path("message").asString().contains(conversationId.toString())) {
                feedCarriesArrival = true;
            }
        }
        assertThat(feedCarriesArrival).as("the feed carries the message arrival").isTrue();

        // (6) The rebind — the documented reinstall path: ANOTHER user
        // registering the same hardware token REBINDS the row to them (one
        // hardware token, one row, the register call is the upsert — the
        // device changed accounts, not the platform's truth).
        HttpResponse<String> rebound = postJson("/api/v1/me/push-tokens", senderGate.accessToken(), """
                {"token":"%s","platform":"ANDROID"}""".formatted(deviceToken));
        assertThat(rebound.statusCode()).as("the reinstall rebind: %s", body(rebound)).isEqualTo(201);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT user_id FROM push_tokens WHERE token = ?", java.util.UUID.class, deviceToken))
                .as("the row rebound to the new caller (the reinstall path)").isEqualTo(userIdBySubject(senderName));
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM push_tokens WHERE user_id = ? AND is_deleted = false",
                Integer.class, ownerId))
                .as("the previous owner now addresses no device").isZero();

        // (7) The ownership guard: the previous owner's DELETE of the
        // (now sender's) token is the scoped no-op — the /me family never
        // answers 403/404; the delete is scoped to the caller's own row by
        // construction, so the stranger's call succeeds AND changes nothing.
        HttpResponse<String> foreignDelete = deleteWithBearer(
                "/api/v1/me/push-tokens?token=" + deviceToken, ownerGate.accessToken(), "");
        assertThat(foreignDelete.statusCode())
                .as("another user's unregister: the scoped no-op (never a 403/404)").isEqualTo(204);
        assertThat(liveTokenRows(deviceToken))
                .as("the row survives the foreign unregister — a token is only ever removed by its owner")
                .isEqualTo(1);

        // (8) The owner's own unregister: 204, and the row soft-deletes
        // (Hibernate 7 @SoftDelete — the row IS the audit trail; the live
        // registry answers empty).
        HttpResponse<String> unregister = deleteWithBearer(
                "/api/v1/me/push-tokens?token=" + deviceToken, senderGate.accessToken(), "");
        assertThat(unregister.statusCode()).as("the owner's unregister: %s", body(unregister)).isEqualTo(204);
        assertThat(liveTokenRows(deviceToken)).as("no live row remains for the token").isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT is_deleted FROM push_tokens WHERE token = ?", Boolean.class, deviceToken))
                .as("the unregister soft-deleted the row (the audit trail stays)").isTrue();

        // (9) The subsequent notification: the in-app row still lands (the
        // channel that is always on), but the push dispatch addresses NO
        // device — the user's live registry is empty and nothing
        // resurrects the soft-deleted row.
        HttpResponse<String> secondMessage = postJson(
                "/api/v1/messages/conversations/" + conversationId + "/messages",
                senderGate.accessToken(), "{\"content\":\"and after the unregister?\"}");
        assertThat(secondMessage.statusCode()).as("second message: %s", body(secondMessage)).isEqualTo(201);
        awaitTypedInboxRows(ownerName, conversationId, 2);
        assertThat(liveTokenRows(deviceToken))
                .as("the unregistered device stays unregistered — no delivery, no resurrection")
                .isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT count(*) FROM push_tokens WHERE user_id = ? AND is_deleted = false",
                Integer.class, ownerId))
                .as("the owner addresses no device after the cycle").isZero();
    }

    /**
     * The preference surface's own boundaries — the push channel's place in
     * the two matrices, measured as the API really answers: the in-app (DB)
     * opt-out is rejected at the binding layer (the three-layer invariant:
     * binding check, service check, the V40 schema CHECK), the TOPIC matrix
     * rejects a PUSH switch (topics govern EMAIL/WS only — push awaits the
     * provider decision D-10, so its preference surface is the TYPE level
     * alone), and the type-level matrix carries the full PUSH vocabulary
     * (the V185/V210 widening) with the enabled default.
     */
    @Test
    void thePreferenceSurfaceBoundariesForThePushChannel() throws Exception {
        String username = "it-device-prefs-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(username);
        GateResult gate = loginGate(username, PASSWORD);
        assertThat(getWithBearer("/api/v1/users/me", gate.accessToken()).statusCode())
                .as("the /me provisioning sync").isEqualTo(200);

        // The in-app (DB) channel is always on: an opt-out is rejected by
        // the framework's binding validation — a 400 before any controller
        // code runs (the API never reports a state the delivery path does
        // not honor).
        HttpResponse<String> dbOptOut = putJson("/api/v1/notifications/preferences", gate.accessToken(), """
                {"preferences":[{"type":"MESSAGE_RECEIVED","channel":"DB","enabled":false}]}""");
        assertThat(dbOptOut.statusCode())
                .as("the in-app opt-out: rejected at the binding layer").isEqualTo(400);

        // The topic matrix governs EMAIL/WS only: a PUSH switch is
        // rejected — the push channel's preference surface is the TYPE
        // level alone until the D-10 provider decision lands its own
        // machinery (the V198 schema CHECK makes it absolute).
        HttpResponse<String> pushTopicSwitch = putJson(
                "/api/v1/notifications/preferences/topics", gate.accessToken(), """
                {"preferences":[{"topic":"MESSAGING","channel":"PUSH","enabled":true}]}""");
        assertThat(pushTopicSwitch.statusCode())
                .as("a topic-level PUSH switch: rejected (topics govern EMAIL/WS only)").isEqualTo(400);
        Integer storedTopicPush = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM notification_topic_preferences WHERE user_id = ("
                        + "SELECT id FROM users WHERE subject = ?) AND channel = 'PUSH'",
                Integer.class, username);
        assertThat(storedTopicPush).as("no topic-level PUSH row was stored").isZero();

        // The type-level matrix carries the full PUSH vocabulary — every
        // type × PUSH answers enabled by the sparse-override default (the
        // V185/V210-widened channel CHECK; the vocabulary the device
        // cycle's delivery gate reads).
        HttpResponse<String> matrixRead = getWithBearer("/api/v1/notifications/preferences", gate.accessToken());
        assertThat(matrixRead.statusCode()).as("the matrix read: %s", body(matrixRead)).isEqualTo(200);
        JsonNode matrix = objectMapper.readTree(matrixRead.body());
        int pushRows = 0;
        for (JsonNode row : matrix) {
            if ("PUSH".equals(row.path("channel").asString())) {
                pushRows++;
                assertThat(row.path("enabled").asBoolean())
                        .as("the PUSH default is enabled (the sparse-override model)").isTrue();
            }
        }
        assertThat(pushRows)
                .as("the matrix carries every type's PUSH row (the V185/V210 vocabulary)")
                .isEqualTo(NotificationType.values().length);
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
                "Device Cycle Journey Gate Integration Test Client");
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

    // ------------------------------------------------------------------
    // The journey's own helpers
    // ------------------------------------------------------------------

    /** The domain projection row's id for a login-gate subject. */
    private UUID userIdBySubject(String username) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE subject = ?", java.util.UUID.class, username);
    }

    /** The live (non-soft-deleted) registry rows for one token value. */
    private Integer liveTokenRows(String token) {
        return jdbcTemplate.queryForObject(
                "SELECT count(*) FROM push_tokens WHERE token = ? AND is_deleted = false",
                Integer.class, token);
    }

    /** One (type, channel) cell of the effective matrix the PUT returns. */
    private Boolean preferenceInMatrix(JsonNode matrix, String type, String channel) {
        for (JsonNode row : matrix) {
            if (type.equals(row.path("type").asString()) && channel.equals(row.path("channel").asString())) {
                return row.path("enabled").asBoolean();
            }
        }
        return null;
    }

    /**
     * The bounded poll for the message's arrival row (the AFTER_COMMIT
     * listener's REQUIRES_NEW hop) — typed to the exact count the
     * assertion reads: the recipient's MESSAGE_RECEIVED rows naming THIS
     * conversation (the house polling shape: 200ms steps, 10s ceiling).
     */
    private void awaitTypedInboxRows(String recipientSubject, UUID conversationId, int expected)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            Integer rows = jdbcTemplate.queryForObject(
                    "SELECT count(*) FROM notifications WHERE recipient_id = ("
                            + "SELECT id FROM users WHERE subject = ?) AND type = 'MESSAGE_RECEIVED'"
                            + " AND message LIKE '%' || ? || '%' AND is_deleted = false",
                    Integer.class, recipientSubject, conversationId);
            if (rows != null && rows >= expected) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("typed inbox rows for conversation " + conversationId
                + " never reached " + expected + " within 10s");
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
        // 2000 (the house window): a ProblemDetail's fieldErrors — the piece
        // that names the violated constraint — live past the traceId.
        return response.body() == null ? "" : response.body().substring(0, Math.min(2000, response.body().length()));
    }
}
