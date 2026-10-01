package com.marketplace.config;

import test.config.IntegrationContainers;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R7 (comprehensive-review-ar fix plan §4, Wave 5 — the unified WebSocket
 * identity): the end-to-end guard for the identity translation at the
 * WebSocket boundary, over real PostgreSQL + real Redis + the real
 * authorization server — the login gate (the L23 pattern, verbatim) mints
 * a real Bearer whose {@code sub} is the login handle, the identity
 * projection is synced through the real {@code /me} surface (the same
 * REST seam that heals the row), and the STOMP session then proves the
 * three alignment facts:
 * <ol>
 *   <li><b>The translation:</b> the CONNECT's token principal is
 *       re-minted with the stable user id as its name (the official
 *       name-carrying {@code JwtAuthenticationToken} constructor) —
 *       proven by the SUBSCRIBE to {@code /topic/notifications/{uuid}}
 *       being GRANTED (the SpEL guard {@code #userId == authentication.name}
 *       could never match a subject-named principal — the R7 defect) and
 *       by the server-side push to the UUID topic actually ARRIVING.</li>
 *   <li><b>The scoping negative:</b> the translated principal may not
 *       subscribe a foreign user's UUID topic — the guard accepts exactly
 *       one identity, not a name-shaped wildcard.</li>
 *   <li><b>The legacy shape is closed:</b> the subject-named topic — the
 *       exact workaround shape the pre-fix architecture could grant — is
 *       DENIED for the translated principal (the boundary speaks one
 *       identity: the stable id, never the login handle).</li>
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
        "marketplace.security.oauth2.client.client-id=marketplace-web-client",
        "marketplace.security.oauth2.client.secret=it-app-secret",
        "marketplace.security.oauth2.client.redirect-uris=http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class WebSocketIdentityTranslationIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The server-side push channel (the same template NotificationService
     * uses) — the MESSAGE-delivery proof's sender, publishing to the
     * production shape: the UUID topic.
     */
    @Autowired
    private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    @Value("${local.server.port}")
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final String PASSWORD = "it-r7-password";
    private static final String USER = "it-r7-user";
    private static final String LOGIN_PATH = "/login";
    private static final String AUTHORIZE_PATH = "/oauth2/authorize";
    private static final String TOKEN_PATH = "/oauth2/token";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    // -- The R7 core: the translated identity grants the UUID topic ----------------

    @Test
    void theTranslatedPrincipalGrantsItsOwnUuidTopicAndReceivesThePush() throws Exception {
        registerUser();
        String accessToken = loginGateAccessToken();
        UUID userId = syncIdentityViaMe(accessToken);
        WebSocketStompClient stompClient = stompClient();

        java.util.List<String> trace =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        CompletableFuture<String> pushed = new CompletableFuture<>();
        CompletableFuture<Void> subscribed = new CompletableFuture<>();

        StompSessionHandlerAdapter sessionHandler = new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession s, StompHeaders connectedHeaders) {
                trace.add("CONNECTED");
            }

            @Override
            public void handleException(StompSession s, StompCommand command, StompHeaders headers,
                                        byte[] payload, Throwable ex) {
                trace.add("EXCEPTION " + command + ": " + ex);
            }

            @Override
            public void handleTransportError(StompSession s, Throwable ex) {
                trace.add("TRANSPORT-ERROR: " + ex);
            }
        };

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + accessToken);

        StompSession session = stompClient
                .connectAsync("ws://127.0.0.1:" + port + "/ws", handshake(), connectHeaders, sessionHandler)
                .get(15, TimeUnit.SECONDS);
        trace.add("SESSION " + session.getSessionId());

        // The production shape: the STABLE USER ID topic — the guard's
        // SpEL (#userId == authentication.name) matches ONLY the translated
        // principal; a subject-named principal (the R7 defect) was always
        // denied here.
        StompHeaders subscribe = new StompHeaders();
        subscribe.setDestination("/topic/notifications/" + userId);
        session.subscribe(subscribe, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                trace.add("MESSAGE-PAYLOAD " + payload);
                pushed.complete(String.valueOf(payload));
            }
        });
        subscribed.complete(null);
        trace.add("SUBSCRIBED destination=/topic/notifications/" + userId);

        // The server-side push through the production channel shape — the
        // UUID topic NotificationService itself addresses.
        for (int attempt = 1; attempt <= 12 && !pushed.isDone(); attempt++) {
            Thread.sleep(400);
            messagingTemplate.convertAndSend("/topic/notifications/" + userId, "r7-identity-alive");
            trace.add("SENT attempt=" + attempt + " connected=" + session.isConnected());
        }

        String delivered;
        try {
            delivered = pushed.get(15, TimeUnit.SECONDS);
        } catch (TimeoutException ex) {
            throw new AssertionError("the translation proof — the push to the authenticated "
                    + "principal's own UUID topic never ARRIVED at the subscribed session (the "
                    + "subscription was denied or the identity never translated). "
                    + "Client-side trace:\n  " + String.join("\n  ", trace), ex);
        }
        assertThat(delivered)
                .as("the delivered payload — the translated identity owns the UUID topic. "
                        + "Client-side trace:\n  %s", String.join("\n  ", trace))
                .isEqualTo("r7-identity-alive");

        session.disconnect();
    }

    // -- The scoping negative: exactly one identity, never a wildcard ----------------

    @Test
    void theTranslatedPrincipalMayNotSubscribeAForeignUuidTopic() throws Exception {
        registerUser();
        String accessToken = loginGateAccessToken();
        syncIdentityViaMe(accessToken);
        WebSocketStompClient stompClient = stompClient();

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + accessToken);

        // A foreign user's UUID topic: the translated name matches exactly
        // one identity — the subscription is denied and the message layer
        // answers with the ERROR frame that closes the session (the same
        // rejection contract the tokenless CONNECT rides).
        CompletableFuture<Object> failure = new CompletableFuture<>();
        StompSession session = stompClient
                .connectAsync("ws://127.0.0.1:" + port + "/ws", handshake(), connectHeaders,
                        failureCapturingHandler(failure))
                .get(15, TimeUnit.SECONDS);

        StompHeaders foreign = new StompHeaders();
        foreign.setDestination("/topic/notifications/" + UUID.randomUUID());
        session.subscribe(foreign, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                failure.complete("unexpected MESSAGE frame: " + payload);
            }
        });
        assertThat(failure.get(15, TimeUnit.SECONDS))
                .as("a foreign UUID topic subscription must be denied for the translated principal")
                .isNotNull();
    }

    // -- The legacy shape is closed: the subject-named topic is denied ----------------

    @Test
    void theSubjectNamedTopicIsDeniedForTheTranslatedPrincipal() throws Exception {
        registerUser();
        String accessToken = loginGateAccessToken();
        syncIdentityViaMe(accessToken);
        WebSocketStompClient stompClient = stompClient();

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer " + accessToken);

        // The pre-fix workaround shape: the topic named by the token
        // SUBJECT. Before the translation this was the ONLY grantable
        // shape (and nothing was ever published there — the defect's
        // silent half). After the boundary speaks the UUID identity, the
        // guard denies it exactly like any other foreign topic.
        CompletableFuture<Object> failure = new CompletableFuture<>();
        StompSession session = stompClient
                .connectAsync("ws://127.0.0.1:" + port + "/ws", handshake(), connectHeaders,
                        failureCapturingHandler(failure))
                .get(15, TimeUnit.SECONDS);

        StompHeaders subjectNamed = new StompHeaders();
        subjectNamed.setDestination("/topic/notifications/" + USER);
        session.subscribe(subjectNamed, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                failure.complete("unexpected MESSAGE frame: " + payload);
            }
        });
        assertThat(failure.get(15, TimeUnit.SECONDS))
                .as("the subject-named topic (the pre-fix workaround shape) must be denied — "
                        + "the boundary speaks the stable id, never the login handle")
                .isNotNull();
    }

    /**
     * The multi-signal failure capture (the established rejection-contract
     * shape): the denied SUBSCRIBE surfaces as the ERROR frame on the
     * SESSION handler, a payload-conversion exception, or a transport
     * error — whichever the broker's async machinery answers with first
     * (the adapter carries no afterConnectionClosed hook in
     * spring-messaging 7.0.9 — verified).
     */
    private static StompSessionHandlerAdapter failureCapturingHandler(CompletableFuture<Object> failure) {
        return new StompSessionHandlerAdapter() {
            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                // An ERROR frame may carry a null body — the frame ITSELF
                // is the rejection fact.
                failure.complete("ERROR frame: " + headers.getDestination());
            }

            @Override
            public void handleException(StompSession s, StompCommand command, StompHeaders headers,
                                        byte[] payload, Throwable ex) {
                failure.complete(ex);
            }

            @Override
            public void handleTransportError(StompSession s, Throwable ex) {
                failure.complete(ex);
            }
        };
    }

    // -- fixtures & helpers (the login-gate patterns, verbatim) ----------------

    private WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        // Receipt tracking needs a scheduler (Spring Framework Reference ›
        // STOMP Client › Receipts) — the class-level scheduler, shut down
        // after the class.
        client.setTaskScheduler(STOMP_RECEIPT_SCHEDULER);
        // The explicit StringMessageConverter — the honest wiring for a
        // text-payload channel (byte[] <-> String under text/plain; the
        // default SimpleMessageConverter answers "No suitable converter"
        // for a String-declaring handler — measured in the S4 rounds).
        client.setMessageConverter(new org.springframework.messaging.converter.StringMessageConverter());
        return client;
    }

    /** The class-level receipt scheduler — one thread, shut down in {@link #shutDownReceiptScheduler()}. */
    private static final org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler STOMP_RECEIPT_SCHEDULER =
            createReceiptScheduler();

    private static org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler createReceiptScheduler() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("r7-stomp-receipt-");
        scheduler.initialize();
        return scheduler;
    }

    @AfterAll
    static void shutDownReceiptScheduler() {
        STOMP_RECEIPT_SCHEDULER.shutdown();
    }

    /** Explicit empty handshake headers — the documented four-arg connect, unambiguous. */
    private static org.springframework.web.socket.WebSocketHttpHeaders handshake() {
        return new org.springframework.web.socket.WebSocketHttpHeaders();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private void registerUser() {
        if (userDetailsManager.userExists(USER)) {
            return;
        }
        userDetailsManager.createUser(org.springframework.security.core.userdetails.User
                .withUsername(USER)
                .password("{noop}" + PASSWORD)
                .roles("USER")
                .build());
    }

    /**
     * The identity projection sync — the same REST seam that heals the
     * row in production: {@code /me} with the freshly minted Bearer
     * creates the users row whose {@code subject} is the token's
     * {@code sub}, and answers the stable id the WebSocket boundary will
     * translate the principal to.
     */
    private UUID syncIdentityViaMe(String accessToken) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/users/me"))
                .header("Authorization", "Bearer " + accessToken)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("/me sync: %s", response.body()).isEqualTo(200);
        JsonNode user = objectMapper.readTree(response.body());
        assertThat(user.path("id").asString()).as("the /me response must carry the stable id").isNotBlank();
        return UUID.fromString(user.path("id").asString());
    }

    /**
     * The real browser-less login gate — the L23 gate's proven five-step
     * sequence, verbatim: (1) unauthenticated authorize &rarr; 302 /login
     * with the session; (2) the login form's CSRF token (bound to the
     * session); (3) credentials POST &rarr; 302 to the SAVED authorization
     * request; (4) re-issuing the authorize request as the authenticated
     * principal &rarr; 302 to the client with the code; (5) the token
     * exchange (client_secret_basic + the PKCE verifier). The
     * marketplace-web-client is bootstrapped by the standing initializer
     * (env-bound fixtures).
     */
    private String loginGateAccessToken() throws Exception {
        String clientId = "marketplace-web-client";
        String clientSecret = "it-app-secret";
        String redirectUri = "http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client";
        String state = UUID.randomUUID().toString();
        String codeVerifier = UUID.randomUUID().toString().replace("-", "");
        String codeChallenge = base64Url(sha256(codeVerifier));
        String authorizeUrl = baseUrl() + AUTHORIZE_PATH
                + "?response_type=code"
                + "&client_id=" + clientId
                + "&scope=" + OidcScopes.OPENID
                + "&state=" + state
                + "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256";

        HttpResponse<String> authorizeFirst = get(authorizeUrl, null);
        assertThat(authorizeFirst.statusCode()).as("authorize should redirect to login: %s", body(authorizeFirst)).isEqualTo(302);
        assertThat(authorizeFirst.headers().firstValue("Location").orElse("")).contains(LOGIN_PATH);
        String sessionCookie = sessionCookie(authorizeFirst);
        assertThat(sessionCookie).as("spring-session cookie expected").isNotBlank();

        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, sessionCookie);
        assertThat(loginPage.statusCode()).as("login page: %s", body(loginPage)).isEqualTo(200);
        String csrfToken = csrfTokenFrom(loginPage.body());
        assertThat(csrfToken).as("CSRF token must be rendered by the default login page").isNotBlank();
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);

        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + USER + "&password=" + PASSWORD + "&_csrf=" + java.net.URLEncoder.encode(csrfToken, StandardCharsets.UTF_8), sessionCookie);
        assertThat(loginPost.statusCode()).as("login should succeed: %s", body(loginPost)).isEqualTo(302);
        String savedRequest = loginPost.headers().firstValue("Location").orElse("");
        assertThat(savedRequest).as("login must resume the saved authorization request, not /login?error")
                .contains(AUTHORIZE_PATH);
        sessionCookie = latestSessionCookie(loginPost, sessionCookie);

        HttpResponse<String> authorizeSecond = get(absolute(savedRequest), sessionCookie);
        assertThat(authorizeSecond.statusCode())
                .as("authorize should redirect back to the client: %s", body(authorizeSecond)).isEqualTo(302);
        String redirect = authorizeSecond.headers().firstValue("Location").orElse("");
        assertThat(redirect).startsWith(redirectUri);
        String code = param(redirect, "code");

        HttpRequest tokenRequest = HttpRequest.newBuilder(URI.create(baseUrl() + TOKEN_PATH))
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8)))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "grant_type=authorization_code"
                                + "&code=" + code
                                + "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                                + "&code_verifier=" + codeVerifier))
                .build();
        HttpResponse<String> tokenResponse = httpClient.send(tokenRequest, HttpResponse.BodyHandlers.ofString());
        assertThat(tokenResponse.statusCode()).as("token exchange: %s", body(tokenResponse)).isEqualTo(200);

        JsonNode token = objectMapper.readTree(tokenResponse.body());
        return token.path("access_token").asString();
    }

    private HttpResponse<String> get(String url, String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(String path, String form, String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : baseUrl() + location;
    }

    private String sessionCookie(HttpResponse<String> response) {
        return response.headers().allValues("Set-Cookie").stream()
                .map(SESSION_COOKIE::matcher)
                .filter(Matcher::find)
                .map(m -> m.group(1) + "=" + m.group(2))
                .findFirst()
                .orElse("");
    }

    private String latestSessionCookie(HttpResponse<String> response, String fallback) {
        String latest = sessionCookie(response);
        return latest.isBlank() ? fallback : latest;
    }

    private String csrfTokenFrom(String loginPage) {
        Matcher matcher = CSRF_INPUT.matcher(loginPage);
        if (matcher.find()) {
            return matcher.group(1);
        }
        Matcher reversed = CSRF_INPUT_REVERSED.matcher(loginPage);
        return reversed.find() ? reversed.group(1) : "";
    }

    private String param(String uri, String name) {
        Matcher matcher = Pattern.compile("[?&]" + Pattern.quote(name) + "=([^&]+)").matcher(uri);
        return matcher.find() ? matcher.group(1) : "";
    }

    private String body(HttpResponse<String> response) {
        return response.body() == null ? "" : response.body().substring(0, Math.min(500, response.body().length()));
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
