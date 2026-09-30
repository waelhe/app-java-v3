package com.marketplace.config;

import test.config.AuthorizationServerFixture;
import test.config.IntegrationContainers;

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

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static test.config.AuthorizationServerFixture.AUTHORIZE_PATH;
import static test.config.AuthorizationServerFixture.CLIENT_ID;
import static test.config.AuthorizationServerFixture.CLIENT_SECRET;
import static test.config.AuthorizationServerFixture.REDIRECT_URI;
import static test.config.AuthorizationServerFixture.TOKEN_PATH;

/**
 * R8 (comprehensive-review-ar fix plan §4, Wave 1) — the end-to-end guard for
 * the surviving-session gap, over real HTTP, the real Redis session store and
 * the real administrative surface.
 *
 * <p><b>The gap this closes.</b> The L23 login-gate test proved the two
 * post-disable legs it owns (the refresh grant dies with the removed
 * authorization rows; the next form login throws {@code DisabledException}).
 * What stayed open — the comprehensive review's R8 — is the session that is
 * already alive when the disable lands: it kept authenticating on the
 * authorization server chain and could mint fresh authorization codes for
 * the disabled account. This gate exercises exactly that surface: a live
 * session proves it can mint a code, the account is disabled through the
 * real administrative endpoint, and the very next authorization request with
 * the same session cookie is intercepted by the framework's
 * {@code ConcurrentSessionFilter} (now present on the authorization server
 * chain — the R8 wave's {@code SecurityConfig} change) with its official
 * expired-session response, no code minted.
 *
 * <p><b>The negative legs.</b> A bystander's session must survive another
 * account's disable (the invalidation is scoped to the published principal)
 * — and, riding the same idempotent enable reset each test starts from, the
 * enable no-op (CodeRabbit round 1 on the fix plan, adopted from the root):
 * activation publishes the domain fact but expires nothing. The role-change
 * leg rides the same listener through the {@code UserRoleChanged} payload
 * that now carries the username: the live session's cached authorities are
 * stale the moment the projection is replaced, so the session expires and
 * the next authorization request re-authenticates against the new
 * {@code roles} claim source.
 *
 * <p><b>Fixtures follow the official wiring</b> (the login-gate precedent):
 * the client is registered through {@link RegisteredClientRepository}, the
 * users through {@link UserDetailsManager}, and the V13 authorization schema
 * through {@code spring.sql.init} ordered before the JDBC beans — mirroring
 * production, where Flyway migrates before bean construction. Test methods
 * are order-independent: each starts from an idempotent enable of the target
 * account (whose projection id is synced once, while the account is enabled
 * in {@code @BeforeAll}).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/migration/V13__authorization_security.sql",
        "marketplace.security.oauth2.client.client-id=marketplace-web-client",
        "marketplace.security.oauth2.client.secret=it-app-secret",
        "marketplace.security.oauth2.client.redirect-uris=http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client"
})
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountStatusSessionInvalidationIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String ADMIN_USERNAME = "it-r8-gate-admin";
    private static final String TARGET_USERNAME = "it-r8-target-user";
    private static final String BYSTANDER_USERNAME = "it-r8-bystander-user";
    private static final String PASSWORD = "it-r8-gate-password";

    private static final String LOGIN_PATH = "/login";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    /** The framework's official expired-session marker (ConcurrentSessionFilter's default strategy body). */
    private static final String EXPIRED_MARKER = "This session has been expired";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private String adminAccessToken;
    private UUID targetId;

    @BeforeAll
    void setUpFixtures() throws Exception {
        AuthorizationServerFixture.registerLoginGateClient(registeredClientRepository,
                "R8 Session Invalidation Integration Test Client");
        registerUser(ADMIN_USERNAME, "ADMIN");
        registerUser(TARGET_USERNAME, "USER");
        registerUser(BYSTANDER_USERNAME, "USER");
        adminAccessToken = loginForTokens(ADMIN_USERNAME).accessToken();
        // The projection id is synced while the account is enabled; every
        // test then resets the account to enabled by this id before acting.
        targetId = syncProjectionIdViaMe(loginForTokens(TARGET_USERNAME).accessToken());
    }

    /**
     * The comprehensive review's R8 scenario, end to end: a live session mints
     * an authorization code; the account is disabled through the real
     * administrative endpoint; the same session can no longer mint — the
     * framework's ConcurrentSessionFilter answers its official expired marker
     * and no code exists; and the account cannot log back in.
     */
    @Test
    void r8_disableExpiresTheLiveSessionOnTheMintingSurface() throws Exception {
        ensureTargetEnabled();
        String targetSession = loginForSession(TARGET_USERNAME);

        // The premise, measured: the live session authenticates the
        // authorization request and a code is minted for the still-enabled
        // account.
        assertThat(authorizeWithSession(targetSession))
                .as("the live session must mint a code before the disable (the gap's premise)")
                .contains("code=");

        // The real administrative disable (real HTTP, real UserDetailsManager
        // flip, real authorization removal, real AccountStatusChanged event).
        HttpResponse<String> disable = putJsonWithBearer("/api/v1/admin/users/" + targetId + "/status",
                adminAccessToken, "{\"status\":\"DISABLED\",\"reason\":\"r8 gate test\"}");
        assertThat(disable.statusCode()).as("disable call: %s", body(disable)).isEqualTo(200);

        // The fix, measured on the same cookie: the authorization server chain
        // no longer authenticates the session — ConcurrentSessionFilter's
        // official expired-session response, and no code minted. The bounded
        // await is pure robustness: the invalidator's plain
        // @TransactionalEventListener(AFTER_COMMIT) + REQUIRES_NEW runs in
        // the committing thread (bytecode-verified against spring-tx 7.0.1 —
        // TransactionalApplicationListenerSynchronization.afterCommit invokes
        // processEvent synchronously, and with no @Async on the method there
        // is no executor hop — see the invalidator's javadoc for why
        // @ApplicationModuleListener's composed @Async was rejected:
        // Modulith's own AsyncEnablingConfiguration activates @EnableAsync
        // here), so the marker is already in Redis before the disable PUT's
        // 200 response and the loop degenerates to a single attempt;
        // CodeRabbit round 1's bounded-wait shape is adopted as zero-cost
        // insurance against scheduling hiccups in CI runners.
        AuthorizeAttempt afterDisable = awaitExpired(targetSession, 10);
        assertThat(afterDisable.body())
                .as("the expired-session marker must be the framework's own: %s", afterDisable.body())
                .contains(EXPIRED_MARKER);
        assertThat(afterDisable.codeMinted())
                .as("no authorization code may be minted from the disabled account's session")
                .isFalse();

        // The L23 leg rides along: the next form login is rejected.
        assertThat(loginIsRejected(TARGET_USERNAME))
                .as("the disabled account must not be able to log back in").isTrue();
    }

    /**
     * The scoping negative: a bystander's session must survive another
     * account's disable (and the idempotent enable this test starts from) —
     * the invalidation touches exactly the published principal's sessions.
     */
    @Test
    void r8_bystanderSessionSurvivesAnotherAccountsDisable() throws Exception {
        ensureTargetEnabled();
        String bystanderSession = loginForSession(BYSTANDER_USERNAME);

        HttpResponse<String> disable = putJsonWithBearer("/api/v1/admin/users/" + targetId + "/status",
                adminAccessToken, "{\"status\":\"DISABLED\",\"reason\":\"r8 bystander test\"}");
        assertThat(disable.statusCode()).as("disable call: %s", body(disable)).isEqualTo(200);

        assertThat(authorizeWithSession(bystanderSession))
                .as("the bystander's session must keep minting after the target's disable")
                .contains("code=");
    }

    /**
     * The role-change leg: the account stays enabled, only the projection is
     * replaced — the live session's cached authorities are stale, so the
     * session expires and the next authorization request re-authenticates.
     */
    @Test
    void r8_roleChangeExpiresTheStaleAuthoritySession() throws Exception {
        ensureTargetEnabled();
        String targetSession = loginForSession(TARGET_USERNAME);
        assertThat(authorizeWithSession(targetSession))
                .as("the live session must mint a code before the role change").contains("code=");

        HttpResponse<String> roleChange = putJsonWithBearer("/api/v1/admin/users/" + targetId + "/role",
                adminAccessToken, "{\"role\":\"PROVIDER\"}");
        assertThat(roleChange.statusCode()).as("role change call: %s", body(roleChange)).isEqualTo(200);

        AuthorizeAttempt afterRoleChange = awaitExpired(targetSession, 10);
        assertThat(afterRoleChange.body())
                .as("the expired-session marker must be the framework's own: %s", afterRoleChange.body())
                .contains(EXPIRED_MARKER);
        assertThat(afterRoleChange.codeMinted())
                .as("no authorization code may be minted from the stale-authority session")
                .isFalse();
    }

    // -- fixtures and reset --------------------------------------------------

    private void registerUser(String username, String role) {
        if (userDetailsManager.userExists(username)) {
            return;
        }
        UserDetails user = User.withUsername(username)
                .password("{noop}" + PASSWORD)
                .roles(role)
                .build();
        userDetailsManager.createUser(user);
    }

    /**
     * Idempotent state reset — the enable leg doubles as the enable no-op
     * guard: the call publishes {@code AccountStatusChanged(enabled=true)}
     * and expires nothing.
     */
    private void ensureTargetEnabled() throws Exception {
        HttpResponse<String> enable = putJsonWithBearer("/api/v1/admin/users/" + targetId + "/status",
                adminAccessToken, "{\"status\":\"ENABLED\",\"reason\":\"test reset\"}");
        assertThat(enable.statusCode()).as("enable reset: %s", body(enable)).isEqualTo(200);
    }

    // -- session login helpers (the login-gate pattern, session-scoped) -----

    /**
     * Runs the login part of the gate and keeps the post-login session
     * cookie — the artifact the surviving-session gap is about. The CSRF and
     * saved-request mechanics are the login-gate test's own (the token is
     * bound to the session; the POST redirects back to the saved
     * authorization request).
     */
    private String loginForSession(String username) throws Exception {
        String authorizeUrl = authorizeUrl(UUID.randomUUID().toString(), randomCodeVerifier());

        HttpResponse<String> authorizeFirst = get(authorizeUrl, null);
        assertThat(authorizeFirst.statusCode()).as("authorize should redirect to login: %s", body(authorizeFirst))
                .isEqualTo(302);
        String sessionCookie = sessionCookie(authorizeFirst);
        assertThat(sessionCookie).as("spring-session cookie expected").isNotBlank();

        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, sessionCookie);
        assertThat(loginPage.statusCode()).as("login page: %s", body(loginPage)).isEqualTo(200);
        String csrfToken = csrfTokenFrom(loginPage.body());
        assertThat(csrfToken).as("CSRF token must be rendered by the default login page").isNotBlank();
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);

        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + PASSWORD + "&_csrf=" + encode(csrfToken), sessionCookie);
        assertThat(loginPost.statusCode()).as("login should succeed: %s", body(loginPost)).isEqualTo(302);
        assertThat(loginPost.headers().firstValue("Location").orElse(""))
                .as("the login POST must return to the saved authorization request")
                .contains(AUTHORIZE_PATH);
        return latestSessionCookie(loginPost, sessionCookie);
    }

    /**
     * The full token-minting gate (the login-gate test's shape) — used to
     * obtain a Bearer token for the administrative endpoints and to sync the
     * JWT subject into the identity projection via {@code /me}.
     */
    private GateResult loginForTokens(String username) throws Exception {
        String state = UUID.randomUUID().toString();
        String codeVerifier = randomCodeVerifier();

        HttpResponse<String> authorizeFirst = get(authorizeUrl(state, codeVerifier), null);
        assertThat(authorizeFirst.statusCode()).as("authorize should redirect to login: %s", body(authorizeFirst))
                .isEqualTo(302);
        String sessionCookie = sessionCookie(authorizeFirst);

        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, sessionCookie);
        String csrfToken = csrfTokenFrom(loginPage.body());
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);

        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + PASSWORD + "&_csrf=" + encode(csrfToken), sessionCookie);
        assertThat(loginPost.statusCode()).as("login should succeed: %s", body(loginPost)).isEqualTo(302);
        String savedRequest = loginPost.headers().firstValue("Location").orElse("");
        sessionCookie = latestSessionCookie(loginPost, sessionCookie);

        HttpResponse<String> authorizeSecond = get(absolute(savedRequest), sessionCookie);
        assertThat(authorizeSecond.statusCode())
                .as("authorize should redirect back to the client: %s", body(authorizeSecond))
                .isEqualTo(302);
        String redirect = authorizeSecond.headers().firstValue("Location").orElse("");
        assertThat(redirect).startsWith(REDIRECT_URI);
        String authorizationCode = queryParam(redirect, "code");

        HttpResponse<String> tokenResponse = postFormWithBasicAuth(TOKEN_PATH,
                "grant_type=authorization_code"
                        + "&code=" + encode(authorizationCode)
                        + "&redirect_uri=" + encode(REDIRECT_URI)
                        + "&code_verifier=" + codeVerifier);
        assertThat(tokenResponse.statusCode()).as("token endpoint: %s", body(tokenResponse)).isEqualTo(200);

        JsonNode tokens = objectMapper.readTree(tokenResponse.body());
        return new GateResult(tokens.path("access_token").asString());
    }

    private record GateResult(String accessToken) {
    }

    /** The raw outcome of an authorization request carried by a session cookie. */
    private record AuthorizeAttempt(String location, String body) {
        boolean codeMinted() {
            return location != null && location.startsWith(REDIRECT_URI) && location.contains("code=");
        }
    }

    private AuthorizeAttempt authorizeRaw(String sessionCookie) throws Exception {
        HttpResponse<String> response = get(authorizeUrl(UUID.randomUUID().toString(), randomCodeVerifier()),
                sessionCookie);
        return new AuthorizeAttempt(response.headers().firstValue("Location").orElse(""), response.body());
    }

    /**
     * Bounded wait for the expired-session marker — the CodeRabbit round-1
     * robustness shape. With the synchronous AFTER_COMMIT listener the first
     * attempt already observes the marker and the loop never iterates; the
     * bound only guards against scheduling hiccups in CI runners. A probe
     * that mints a code does not change the session, so retrying is safe.
     */
    private AuthorizeAttempt awaitExpired(String sessionCookie, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(timeoutSeconds).toNanos();
        AuthorizeAttempt attempt = authorizeRaw(sessionCookie);
        while (!attempt.body().contains(EXPIRED_MARKER) && System.nanoTime() < deadline) {
            Thread.sleep(100);
            attempt = authorizeRaw(sessionCookie);
        }
        return attempt;
    }

    private String authorizeWithSession(String sessionCookie) throws Exception {
        return authorizeRaw(sessionCookie).location();
    }

    private String authorizeUrl(String state, String codeVerifier) throws Exception {
        return baseUrl() + AUTHORIZE_PATH
                + "?response_type=code"
                + "&client_id=" + CLIENT_ID
                + "&scope=openid"
                + "&state=" + state
                + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&code_challenge=" + base64Url(sha256(codeVerifier))
                + "&code_challenge_method=S256";
    }

    // -- shared helpers (login-gate conventions) ---------------------------

    /** JSON PUT with a Bearer token — the administrative status/role surface. */
    private HttpResponse<String> putJsonWithBearer(String path, String accessToken, String json)
            throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .PUT(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
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

    /**
     * Syncs the JWT subject into the identity projection through the real
     * {@code /api/v1/users/me} endpoint (the projection row is what the admin
     * status endpoint addresses by id) and returns the id.
     */
    private UUID syncProjectionIdViaMe(String accessToken) throws Exception {
        HttpResponse<String> me = getWithBearer("/api/v1/users/me", accessToken);
        assertThat(me.statusCode()).as("/me sync: %s", body(me)).isEqualTo(200);
        JsonNode user = objectMapper.readTree(me.body());
        assertThat(user.path("id").asString()).as("the /me response must carry the id").isNotBlank();
        return UUID.fromString(user.path("id").asString());
    }

    /**
     * Runs the login part of the gate and reports whether it FAILED: a
     * rejected credential POST redirects to {@code /login?error} instead of
     * the saved authorization request.
     */
    private boolean loginIsRejected(String username) throws Exception {
        HttpResponse<String> authorizeFirst = get(authorizeUrl(UUID.randomUUID().toString(), randomCodeVerifier()),
                null);
        assertThat(authorizeFirst.statusCode()).isEqualTo(302);
        String sessionCookie = sessionCookie(authorizeFirst);
        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, sessionCookie);
        String csrfToken = csrfTokenFrom(loginPage.body());
        assertThat(csrfToken).isNotBlank();
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);
        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + PASSWORD + "&_csrf=" + encode(csrfToken), sessionCookie);
        assertThat(loginPost.statusCode())
                .as("login POST must answer the 302 redirect contract: %s", body(loginPost))
                .isEqualTo(302);
        return loginPost.headers().firstValue("Location").orElse("").contains("/login?error");
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + Base64.getEncoder()
                        .encodeToString((CLIENT_ID + ":" + CLIENT_SECRET).getBytes(StandardCharsets.UTF_8)))
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : baseUrl() + location;
    }

    private String body(HttpResponse<String> response) {
        return response.body() == null ? "" : response.body().replaceAll("\\s+", " ");
    }

    private String sessionCookie(HttpResponse<String> response) {
        return headerCookie(response.headers().firstValue("Set-Cookie").orElse(null));
    }

    private String latestSessionCookie(HttpResponse<String> response, String fallback) {
        return headerCookie(response.headers().firstValue("Set-Cookie").orElse(fallback));
    }

    private String headerCookie(String raw) {
        if (raw == null) {
            return null;
        }
        Matcher matcher = SESSION_COOKIE.matcher(raw);
        return matcher.find() ? matcher.group(1) + "=" + matcher.group(2) : null;
    }

    private String csrfTokenFrom(String loginPageBody) {
        Matcher matcher = CSRF_INPUT.matcher(loginPageBody);
        if (matcher.find()) {
            return matcher.group(1);
        }
        Matcher reversed = CSRF_INPUT_REVERSED.matcher(loginPageBody);
        return reversed.find() ? reversed.group(1) : null;
    }

    private String queryParam(String redirect, String param) {
        Matcher matcher = Pattern.compile("[?&]" + Pattern.quote(param) + "=([^&]+)").matcher(redirect);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String encode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String randomCodeVerifier() {
        return UUID.randomUUID().toString() + UUID.randomUUID();
    }

    private byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
