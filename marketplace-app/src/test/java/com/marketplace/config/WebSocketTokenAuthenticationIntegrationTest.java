package com.marketplace.config;

import com.marketplace.MarketplaceApplication;
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
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * S4/N3 root fix (comprehensive repair plan §10/2.1): the end-to-end guard
 * for the WebSocket token authentication architecture on real PostgreSQL +
 * real Redis + the real authorization server — the PKCE login gate (the L23
 * pattern, verbatim) mints a real Bearer and:
 * <ol>
 *   <li><b>The S4 core:</b> the anonymous STOMP handshake completes the
 *       protocol upgrade (101) instead of the form-login 302 redirect —
 *       the handshake now belongs to the stateless resource-server chain
 *       (permitAll at the HTTP layer by design; the message layer is the
 *       authorization boundary).</li>
 *   <li><b>The documented token pattern:</b> a CONNECT frame carrying the
 *       {@code Authorization} Bearer STOMP header establishes the STOMP
 *       session (the {@code JwtChannelAuthenticationInterceptor} lifts the
 *       token through the SAME decoder/converter the REST chain uses —
 *       Spring Framework Reference › STOMP › Token Authentication, saved at
 *       {@code scripts/doc-verify/ws/framework-stomp-token-based.html}).</li>
 *   <li><b>The N3 completion:</b> the authenticated session SUBSCRIBES to
 *       its own notification topic and receives the subscription receipt —
 *       the push channel is reachable by token clients.</li>
 *   <li><b>The rejection contract:</b> an invalid supplied token rejects
 *       the CONNECT; a tokenless CONNECT is rejected by the message layer's
 *       {@code nullDestMatcher().authenticated()}.</li>
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
class WebSocketTokenAuthenticationIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private ObjectMapper objectMapper;

    /**
     * The server-side push channel (the same template NotificationService
     * uses) — the MESSAGE-delivery proof's sender.
     */
    @Autowired
    private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    @Value("${local.server.port}")
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final String PASSWORD = "it-ws-password";
    private static final String USER = "it-ws-user";
    private static final String LOGIN_PATH = "/login";
    private static final String AUTHORIZE_PATH = "/oauth2/authorize";
    private static final String TOKEN_PATH = "/oauth2/token";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    // -- The S4 core: the handshake completes the protocol upgrade -------------------------------

    @Test
    void anonymousHandshakeCompletesTheProtocolUpgradeInsteadOfTheFormLoginRedirect() throws Exception {
        registerUser();

        // The JDK WebSocket client performs the real HTTP upgrade itself: a
        // 302 login redirect (the S4 measurement) fails the handshake with
        // WebSocketHandshakeException — only the 101 protocol switch
        // completes. No token anywhere on this request.
        WebSocket webSocket = httpClient.newWebSocketBuilder()
                .buildAsync(URI.create("ws://127.0.0.1:" + port + "/ws"), new WebSocket.Listener() {
                })
                .get(15, TimeUnit.SECONDS);

        assertThat(webSocket).as("the anonymous handshake must upgrade (101), never the form-login 302 (S4)").isNotNull();
        webSocket.abort();
    }

    // -- The documented token pattern end-to-end --------------------------------------------------

    @Test
    void connectWithBearerTokenOnTheConnectFrameEstablishesTheSession() throws Exception {
        registerUser();
        String accessToken = loginGateAccessToken();
        WebSocketStompClient stompClient = stompClient();

        // CI round 6: the guard is SELF-DIAGNOSING — every client-visible
        // event (frames, transport errors, closures, the CONNECTED
        // negotiation) is recorded, and a failure answers with the whole
        // trace instead of a bare timeout. The rounds so far measured: the
        // CONNECT establishes (round 2+), the session stays open with NO
        // rejection exception in the server log (round 5 — the subscribe is
        // not denied), yet neither a receipt (rounds 3-4 — receipts are the
        // external relay's feature, bytecode-proven) nor a delivered MESSAGE
        // (round 5) arrives. The trace closes the remaining unknowns in ONE
        // round: heartbeat negotiation, late delivery, silent closure.
        java.util.List<String> trace =
                java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        CompletableFuture<String> pushed = new CompletableFuture<>();

        StompSessionHandlerAdapter sessionHandler = new StompSessionHandlerAdapter() {
            @Override
            public void afterConnected(StompSession s, StompHeaders connectedHeaders) {
                trace.add("CONNECTED heartbeat=" + java.util.Arrays.toString(connectedHeaders.getHeartbeat()));
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                trace.add("FRAME destination=" + headers.getDestination()
                        + " receipt-id=" + headers.getReceiptId());
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

        assertThat(session.isConnected()).isTrue();
        assertThat(session.getSessionId()).isNotBlank();

        // The N3 completion — the MESSAGE-delivery proof (bytecode-verified
        // against spring-messaging 7.0.9: SimpleBrokerMessageHandler carries
        // ZERO receipt support — receipts are the EXTERNAL broker relay's
        // feature; the simple broker acknowledges a subscription only by
        // DELIVERING to it). The push channel is alive for token clients when
        // a server-side push to the authenticated principal's own topic
        // actually ARRIVES at the subscribed session — the same delivery path
        // NotificationService drives in production.
        StompHeaders subscribe = new StompHeaders();
        subscribe.setDestination("/topic/notifications/" + USER);
        session.subscribe(subscribe, new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return String.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                // MESSAGE frames for the notification topic — the
                // channel-alive proof itself.
                trace.add("MESSAGE-PAYLOAD " + payload);
                pushed.complete(String.valueOf(payload));
            }
        });
        trace.add("SUBSCRIBED destination=/topic/notifications/" + USER);

        // The server-side push, retried while the broker's async subscription
        // registration settles (each send delivers to whatever is registered
        // by then — one landing completes the proof).
        for (int attempt = 1; attempt <= 12 && !pushed.isDone(); attempt++) {
            Thread.sleep(400);
            messagingTemplate.convertAndSend("/topic/notifications/" + USER, "s4-channel-alive");
            trace.add("SENT attempt=" + attempt + " connected=" + session.isConnected());
        }

        assertThat(pushed.get(15, TimeUnit.SECONDS))
                .as("the channel-alive proof — the push to the authenticated principal's own topic "
                        + "must ARRIVE at the subscribed session. Client-side trace:\n  %s",
                        String.join("\n  ", trace))
                .isEqualTo("s4-channel-alive");

        session.disconnect();
    }

    @Test
    void connectWithInvalidSuppliedTokenIsRejected() throws Exception {
        registerUser();
        WebSocketStompClient stompClient = stompClient();

        StompHeaders connectHeaders = new StompHeaders();
        connectHeaders.add("Authorization", "Bearer not-a-real-token");

        // A SUPPLIED token that fails validation rejects the CONNECT (the
        // resource-server semantics) — the session never establishes.
        CompletableFuture<Object> failure = new CompletableFuture<>();
        stompClient.connectAsync("ws://127.0.0.1:" + port + "/ws", handshake(), connectHeaders,
                        new StompSessionHandlerAdapter() {
                            @Override
                            public void handleTransportError(StompSession session, Throwable ex) {
                                failure.complete(ex);
                            }

                            @Override
                            public void handleFrame(StompHeaders headers, Object payload) {
                                // An ERROR frame may carry a null body — the
                                // frame ITSELF is the rejection fact.
                                failure.complete("ERROR frame: " + headers.getDestination());
                            }
                        })
                .whenComplete((session, ex) -> {
                    if (ex != null) {
                        failure.complete(ex);
                    }
                });

        assertThat(failure.get(15, TimeUnit.SECONDS)).isNotNull();
    }

    @Test
    void tokenlessConnectIsRejectedByTheMessageLayer() throws Exception {
        registerUser();
        WebSocketStompClient stompClient = stompClient();

        // No token anywhere: the CONNECT passes the CSRF leg (stateless, no
        // session token) but the message authorization manager's
        // nullDestMatcher().authenticated() rejects it — nothing anonymous.
        CompletableFuture<Object> failure = new CompletableFuture<>();
        stompClient.connectAsync("ws://127.0.0.1:" + port + "/ws", handshake(), new StompHeaders(),
                        new StompSessionHandlerAdapter() {
                            @Override
                            public void handleTransportError(StompSession session, Throwable ex) {
                                failure.complete(ex);
                            }

                            @Override
                            public void handleFrame(StompHeaders headers, Object payload) {
                                // An ERROR frame may carry a null body — the
                                // frame ITSELF is the rejection fact.
                                failure.complete("ERROR frame: " + headers.getDestination());
                            }
                        })
                .whenComplete((session, ex) -> {
                    if (ex != null) {
                        failure.complete(ex);
                    }
                });

        assertThat(failure.get(15, TimeUnit.SECONDS)).isNotNull();
    }

    // -- fixtures & helpers (the L23 gate shapes, verbatim) ----------------------------------------

    private WebSocketStompClient stompClient() {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        // CI round 3 (the framework's own contract, measured: "To track
        // receipts, a TaskScheduler must be configured"): the SUBSCRIBE's
        // receipt header — the channel-alive proof this guard asserts — needs
        // receipt tracking, and the STOMP client only tracks receipts when a
        // scheduler is configured (Spring Framework Reference › STOMP Client
        // › Receipts). One class-level scheduler, shut down after the class.
        client.setTaskScheduler(STOMP_RECEIPT_SCHEDULER);
        return client;
    }

    /** The class-level receipt scheduler — one thread, shut down in {@link #shutDownReceiptScheduler()}. */
    private static final org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler STOMP_RECEIPT_SCHEDULER =
            createReceiptScheduler();

    private static org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler createReceiptScheduler() {
        org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler scheduler =
                new org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler();
        scheduler.setPoolSize(1);
        scheduler.setThreadNamePrefix("s4-stomp-receipt-");
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
     * The real browser-less login gate — the L23 gate's proven five-step
     * sequence, verbatim: (1) unauthenticated authorize &rarr; 302 /login with
     * the session; (2) the login form's CSRF token (bound to the session);
     * (3) credentials POST &rarr; 302 to the SAVED authorization request;
     * (4) re-issuing the authorize request as the authenticated principal
     * &rarr; 302 to the client with the code; (5) the token exchange
     * (client_secret_basic + the PKCE verifier). The marketplace-web-client
     * is bootstrapped by the standing initializer (env-bound fixtures).
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
