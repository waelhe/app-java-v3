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

import static org.assertj.core.api.Assertions.assertThat;
import static test.config.AuthorizationServerFixture.AUTHORIZE_PATH;
import static test.config.AuthorizationServerFixture.CLIENT_ID;
import static test.config.AuthorizationServerFixture.REDIRECT_URI;

/**
 * A-06 (official-compliance plan §6 wave A — A.4) — the declared unit gate:
 * «اختبار جلسات متزامنة» (the concurrent-sessions test), over real HTTP, the
 * real Redis session store and the real login gateway.
 *
 * <p><b>What this closes.</b> The R8-era wiring already carried
 * {@code maximumSessions} + {@code SpringSessionBackedSessionRegistry} on both
 * session-bearing chains; A.4's missing half was the documented overflow
 * decision ({@code maxSessionsPreventsLogin}) and a gate that measures the
 * policy end to end. This test class exercises the <b>documented default</b>
 * overflow behavior — the Spring Security Reference's first
 * maximumSessions sample: «This will prevent a user from logging in multiple
 * times - a second login will cause the first to be invalidated» — with the
 * platform's bound default (max-sessions 2, prevents-login false): the third
 * device's login succeeds, the <b>least-recent</b> session is expired by the
 * framework, and the survivors keep minting authorization codes.
 *
 * <p><b>The fixation leg.</b> The same reference documents that «Spring
 * Security protects against this automatically by creating a new session or
 * otherwise changing the session ID when a user logs in», with
 * {@code changeSessionId} as «the default in Servlet 3.1 and newer
 * containers» — no DSL line exists for it in {@code SecurityConfig} (a
 * re-declared default would be redundant configuration), so this gate
 * <b>measures</b> the default instead: the session id carried by the cookie
 * must rotate across the login POST.
 *
 * <p><b>The scoping negative.</b> Another user's sessions must survive a
 * principal's overflow — the eviction touches exactly the overflowing
 * principal's sessions in the registry (the R8 bystander precedent).
 *
 * <p><b>Fixtures follow the official wiring</b> (the login-gate/R8
 * precedents): the client through {@link RegisteredClientRepository}, the
 * users through {@link UserDetailsManager}, and the V13 authorization schema
 * through {@code spring.sql.init} ordered before the JDBC beans. Each test
 * uses its own user so the methods stay order-independent against the shared
 * session store.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/migration/V13__authorization_security.sql,classpath:sql/init/auth_effective_authorities_view.sql",
        "marketplace.security.oauth2.client.client-id=marketplace-web-client",
        "marketplace.security.oauth2.client.secret=it-app-secret",
        "marketplace.security.oauth2.client.redirect-uris=http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client"
})
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrentSessionsIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    /** The overflowing principal of the eviction leg — its own user keeps the tests order-independent. */
    private static final String OVERFLOW_USERNAME = "it-a06-overflow-user";

    /** The fixation leg's own user — its single login pair is untouched by the other legs. */
    private static final String FIXATION_USERNAME = "it-a06-fixation-user";

    /** The scoping negative's bystander — one session, never at the limit. */
    private static final String BYSTANDER_USERNAME = "it-a06-bystander-user";

    /** The scoping negative's overflowing principal. */
    private static final String OVERFLOWING_NEIGHBOR_USERNAME = "it-a06-overflowing-neighbor-user";

    private static final String PASSWORD = "it-a06-gate-password";

    private static final String LOGIN_PATH = "/login";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    /** The framework's official expired-session marker (ConcurrentSessionFilter's default strategy body). */
    private static final String EXPIRED_MARKER = "This session has been expired";

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @BeforeAll
    void setUpFixtures() {
        AuthorizationServerFixture.registerLoginGateClient(registeredClientRepository,
                "A-06 Concurrent Sessions Integration Test Client");
        registerUser(OVERFLOW_USERNAME);
        registerUser(FIXATION_USERNAME);
        registerUser(BYSTANDER_USERNAME);
        registerUser(OVERFLOWING_NEIGHBOR_USERNAME);
    }

    /**
     * The A.4 core, in the documented default shape: with max-sessions 2 and
     * prevents-login false, the third device's login SUCCEEDS and the
     * framework expires the least-recent session — the first device's next
     * authorization request meets ConcurrentSessionFilter's official
     * expired-session response and mints no code, while both survivors (the
     * second and the newest third device) keep minting.
     */
    @Test
    void thirdLoginExpiresTheLeastRecentSessionWhileTheSurvivorsStillMint() throws Exception {
        String firstSession = loginForSession(OVERFLOW_USERNAME);
        assertThat(authorizeWithSession(firstSession))
                .as("the first device's session must mint a code before the overflow (the premise)")
                .contains("code=");

        String secondSession = loginForSession(OVERFLOW_USERNAME);
        assertThat(authorizeWithSession(secondSession))
                .as("the second device's session must mint a code at the limit")
                .contains("code=");

        // The documented default overflow: the login itself is NOT rejected —
        // "a second login will cause the first to be invalidated" — and the
        // helper asserts the successful-login 302 contract on the way back.
        String thirdSession = loginForSession(OVERFLOW_USERNAME);

        // The eviction, measured on the first device's cookie: the bounded
        // await is zero-cost insurance (the strategy expires the least-recent
        // session synchronously inside the third login's request thread, so
        // the first probe already observes the marker).
        AuthorizeAttempt evicted = awaitExpired(firstSession, 10);
        assertThat(evicted.body())
                .as("the expired-session marker must be the framework's own: %s", evicted.body())
                .contains(EXPIRED_MARKER);
        assertThat(evicted.codeMinted())
                .as("no authorization code may be minted from the evicted least-recent session")
                .isFalse();

        assertThat(authorizeWithSession(secondSession))
                .as("the second device's session must survive the overflow (it is not the least recent)")
                .contains("code=");
        assertThat(authorizeWithSession(thirdSession))
                .as("the newest device's session must mint after causing the eviction")
                .contains("code=");
    }

    /**
     * The fixation leg — the documented default measured, not re-declared:
     * {@code changeSessionId} («the default in Servlet 3.1 and newer
     * containers») rotates the session id across the login POST, so the
     * cookie that answers the authenticated redirect carries a different id
     * than the one that carried the pre-login (saved-request) session. A
     * fixation link built on the pre-login id therefore dies at the login it
     * was crafted for.
     */
    @Test
    void loginRotatesTheSessionIdTheDocumentedFixationDefault() throws Exception {
        SessionPair pair = loginForSessionPair(FIXATION_USERNAME);

        assertThat(pair.preLoginCookie()).as("the pre-login session cookie must exist").isNotBlank();
        assertThat(pair.postLoginCookie()).as("the post-login session cookie must exist").isNotBlank();
        assertThat(pair.postLoginCookie())
                .as("the session id must rotate across the login POST (the changeSessionId documented default)")
                .isNotEqualTo(pair.preLoginCookie());

        // The rotated session is a working one: it mints.
        assertThat(authorizeWithSession(pair.postLoginCookie()))
                .as("the rotated session must mint a code after the login")
                .contains("code=");
    }

    /**
     * The scoping negative: a bystander's session must survive another
     * principal's overflow — the eviction touches exactly the overflowing
     * principal's sessions in the shared registry, never a neighbor's.
     */
    @Test
    void bystanderSessionsAreUnaffectedByAnotherUsersOverflow() throws Exception {
        String bystanderSession = loginForSession(BYSTANDER_USERNAME);
        assertThat(authorizeWithSession(bystanderSession))
                .as("the bystander's session must mint before the neighbor's overflow")
                .contains("code=");

        // The neighbor overflows its own limit (three logins at max-sessions 2).
        loginForSession(OVERFLOWING_NEIGHBOR_USERNAME);
        loginForSession(OVERFLOWING_NEIGHBOR_USERNAME);
        loginForSession(OVERFLOWING_NEIGHBOR_USERNAME);

        assertThat(authorizeWithSession(bystanderSession))
                .as("the bystander's session must keep minting after another principal's overflow")
                .contains("code=");
    }

    // -- fixtures and login helpers (the login-gate/R8 pattern) ------------

    private void registerUser(String username) {
        if (userDetailsManager.userExists(username)) {
            return;
        }
        UserDetails user = User.withUsername(username)
                .password("{noop}" + PASSWORD)
                .roles("USER")
                .build();
        userDetailsManager.createUser(user);
    }

    /**
     * Runs the login part of the gate and keeps the post-login session
     * cookie — the R8 precedent's shape (the CSRF token is bound to the
     * session; the POST redirects back to the saved authorization request).
     */
    private String loginForSession(String username) throws Exception {
        return loginForSessionPair(username).postLoginCookie();
    }

    /**
     * The fixation leg's fixture: both cookies of one login — the id that
     * carried the pre-login (saved-request) session and the rotated id that
     * answers the authenticated redirect.
     */
    private SessionPair loginForSessionPair(String username) throws Exception {
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
        String preLoginCookie = latestSessionCookie(loginPage, sessionCookie);

        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + PASSWORD + "&_csrf=" + encode(csrfToken), preLoginCookie);
        assertThat(loginPost.statusCode()).as("login should succeed: %s", body(loginPost)).isEqualTo(302);
        assertThat(loginPost.headers().firstValue("Location").orElse(""))
                .as("the login POST must return to the saved authorization request")
                .contains(AUTHORIZE_PATH);
        String postLoginCookie = latestSessionCookie(loginPost, preLoginCookie);

        return new SessionPair(preLoginCookie, postLoginCookie);
    }

    /** The raw outcome of an authorization request carried by a session cookie. */
    private record AuthorizeAttempt(String location, String body) {
        boolean codeMinted() {
            return location != null && location.startsWith(REDIRECT_URI) && location.contains("code=");
        }
    }

    /** The fixation leg's pair: the pre-login and post-login session cookies of one login. */
    private record SessionPair(String preLoginCookie, String postLoginCookie) {
    }

    private AuthorizeAttempt authorizeRaw(String sessionCookie) throws Exception {
        HttpResponse<String> response = get(authorizeUrl(UUID.randomUUID().toString(), randomCodeVerifier()),
                sessionCookie);
        return new AuthorizeAttempt(response.headers().firstValue("Location").orElse(""), response.body());
    }

    /**
     * Bounded wait for the expired-session marker (the R8 awaitExpired shape:
     * every probe also asserts no code was minted, so an intermediate probe
     * cannot slip through the loop). The concurrent-login eviction is
     * synchronous in the login request thread, so the first probe already
     * observes the marker; the bound only guards CI scheduling hiccups.
     */
    private AuthorizeAttempt awaitExpired(String sessionCookie, int timeoutSeconds) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(timeoutSeconds).toNanos();
        AuthorizeAttempt attempt = authorizeRaw(sessionCookie);
        assertThat(attempt.codeMinted())
                .as("no authorization code may be minted by any probe while awaiting the expired marker")
                .isFalse();
        while (!attempt.body().contains(EXPIRED_MARKER) && System.nanoTime() < deadline) {
            Thread.sleep(100);
            attempt = authorizeRaw(sessionCookie);
            assertThat(attempt.codeMinted())
                    .as("no authorization code may be minted by any probe while awaiting the expired marker")
                    .isFalse();
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

    private String baseUrl() {
        return "http://127.0.0.1:" + port;
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
