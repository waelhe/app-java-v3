package com.marketplace.identity;

import test.config.IntegrationContainers;
import test.config.AuthorizationServerFixture;
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
import org.testcontainers.postgresql.PostgreSQLContainer;
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
 * W2 / J01 (unified end-to-end plan §3, the journeys register row J01) — the
 * session lifecycle journey measured end-to-end on the REAL chain: the real
 * login gate (form login + CSRF + Spring Session in Redis) → the real
 * authorization server (authorization-code + PKCE + client_secret_basic) →
 * the real resource-server chain (Bearer JWT) → the real identity projection
 * (the /me provisioning sync).
 *
 * <p>The journey's acceptance shape (the register's J01 row): (1) a supported
 * account logs in and reaches its own /me; (2) the token can be renewed
 * through the documented refresh grant; (3) account switching carries no
 * context bleed — two concurrent gates answer two distinct identities; (4)
 * the explicit server-side termination: the RFC 7009 revocation kills the
 * refresh grant AND the form-session logout kills the server session — the
 * next authorization attempt redirects to the login page, not to a code;
 * (5) the failure branches: wrong password redirects to /login?error (no
 * code, no token), an anonymous /me answers 401, and a garbage Bearer
 * answers 401.
 *
 * <p>Android's native legs (W12) consume exactly these contracts; this class
 * proves the backend side of the journey — the login method set is the
 * integration's real one (form + authorization code), nothing is invented.
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
class SessionLifecycleJourneyIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static org.testcontainers.postgresql.PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String PASSWORD = "it-session-journey-password";
    private static final String LOGIN_PATH = "/login";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Value("${local.server.port}")
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * The journey's linking test (plan §5 — the final binding pass over the
     * most important path): register → login gate → /me sync → refresh →
     * account switch (two identities, no bleed) → explicit server-side
     * termination (revocation + session logout) → the terminated state is
     * measured on the server, not assumed.
     */
    @Test
    void theSessionJourneyFromLoginThroughSwitchToTermination() throws Exception {
        // (1) The account exists and the gate opens: the full documented
        // chain answers an access token bound to THIS user's subject.
        String username = "it-session-user-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(username);
        GateResult gate = loginGate(username, PASSWORD);
        assertThat(gate.accessToken()).isNotBlank();
        assertThat(gate.refreshToken()).as("the refresh grant is part of the registered client").isNotBlank();

        HttpResponse<String> me = getWithBearer("/api/v1/users/me", gate.accessToken());
        assertThat(me.statusCode()).as("the /me provisioning sync: %s", body(me)).isEqualTo(200);
        JsonNode user = objectMapper.readTree(me.body());
        // The /me view's own identity fields (UserResponse): the projection
        // row's UUID is the stable identity the account-switch contract
        // rides (the view carries no subject echo — the caller IS the
        // subject by construction of the strict provider).
        String userId = user.path("id").asString();
        assertThat(userId).as("the projection carries the account's id").isNotBlank();
        UUID identity = UUID.fromString(userId);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT id FROM users WHERE subject = ?", UUID.class, username))
                .as("the /me projection is the login subject's own row")
                .isEqualTo(identity);

        // (2) The documented renewal: the refresh grant answers a fresh
        // access token for the same subject (the app's session-restore leg
        // rides exactly this).
        HttpResponse<String> refresh = postFormWithBasicAuth(TOKEN_PATH,
                "grant_type=refresh_token&refresh_token=" + encode(gate.refreshToken()));
        assertThat(refresh.statusCode()).as("refresh grant: %s", body(refresh)).isEqualTo(200);
        String renewedToken = objectMapper.readTree(refresh.body()).path("access_token").asString();
        assertThat(renewedToken).isNotBlank();
        HttpResponse<String> meRenewed = getWithBearer("/api/v1/users/me", renewedToken);
        assertThat(meRenewed.statusCode()).as("the renewed token carries the identity").isEqualTo(200);
        assertThat(objectMapper.readTree(meRenewed.body()).path("id").asString())
                .as("the renewed token is the SAME identity — no drift")
                .isEqualTo(identity.toString());

        // (3) Account switching: a SECOND account's gate coexists with the
        // first — each /me answers its own subject, no context bleed (the
        // Android account switcher's whole contract).
        String otherName = "it-session-other-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(otherName);
        GateResult otherGate = loginGate(otherName, PASSWORD);
        HttpResponse<String> otherMe = getWithBearer("/api/v1/users/me", otherGate.accessToken());
        assertThat(otherMe.statusCode()).isEqualTo(200);
        String otherIdentity = objectMapper.readTree(otherMe.body()).path("id").asString();
        assertThat(otherIdentity).as("the switched account's identity").isNotBlank();
        assertThat(otherIdentity).as("two accounts, two distinct identities").isNotEqualTo(identity.toString());
        HttpResponse<String> firstMeAgain = getWithBearer("/api/v1/users/me", gate.accessToken());
        assertThat(objectMapper.readTree(firstMeAgain.body()).path("id").asString())
                .as("the first account's token still answers its OWN identity — switching never mutates the other session")
                .isEqualTo(identity.toString());

        // (4) The explicit server-side termination, leg one — the RFC 7009
        // revocation of the refresh token: the documented termination
        // surface. The endpoint answers 200 and the grant is dead.
        HttpResponse<String> revoke = postFormWithBasicAuth("/oauth2/revoke",
                "token=" + encode(gate.refreshToken()) + "&token_type_hint=refresh_token");
        assertThat(revoke.statusCode()).as("revocation: %s", body(revoke)).isEqualTo(200);
        HttpResponse<String> refreshAfterRevoke = postFormWithBasicAuth(TOKEN_PATH,
                "grant_type=refresh_token&refresh_token=" + encode(gate.refreshToken()));
        assertThat(refreshAfterRevoke.statusCode())
                .as("the revoked refresh grant is dead on the server: %s", body(refreshAfterRevoke))
                .isEqualTo(400);

        // (5) The explicit server-side termination, leg two — the form
        // session logout (the C-8 documented cleanup chain): the session
        // row dies in Redis and the NEXT authorization attempt redirects
        // to the login page instead of issuing a code.
        HttpResponse<String> logoutCsrfPage = get(baseUrl() + LOGIN_PATH, gate.sessionCookie());
        assertThat(logoutCsrfPage.statusCode()).isEqualTo(200);
        String logoutCsrf = csrfTokenFrom(logoutCsrfPage.body());
        assertThat(logoutCsrf).as("the login page still renders a CSRF token for the logout POST").isNotBlank();
        HttpResponse<String> logout = postForm("/logout", "_csrf=" + encode(logoutCsrf), gate.sessionCookie());
        assertThat(logout.statusCode()).as("the logout POST: %s", body(logout)).isEqualTo(302);
        assertThat(logout.headers().firstValue("Location").orElse(""))
                .as("the documented post-logout landing").contains("/login?logout");

        // The terminated session cannot silently resume the authorization
        // flow: the authorize endpoint answers a redirect to the LOGIN page,
        // never to the client with a code.
        HttpResponse<String> authorizeAfterLogout = get(
                authorizeUrl(UUID.randomUUID().toString(), base64Url(sha256(randomCodeVerifier()))),
                gate.sessionCookie());
        assertThat(authorizeAfterLogout.statusCode()).isEqualTo(302);
        assertThat(authorizeAfterLogout.headers().firstValue("Location").orElse(""))
                .as("a terminated session re-authenticates — no code without login")
                .contains(LOGIN_PATH);
    }

    /** The wrong-password branch: the gate answers the documented failure redirect, and NO code is issued. */
    @Test
    void wrongPasswordAnswersTheFailureRedirectAndNoCode() throws Exception {
        String username = "it-session-wrong-" + UUID.randomUUID().toString().substring(0, 8);
        registerUser(username);
        AuthorizationServerFixture.registerLoginGateClient(registeredClientRepository,
                "Session Journey Gate Integration Test Client");
        String state = UUID.randomUUID().toString();
        HttpResponse<String> authorizeFirst = get(authorizeUrl(state, base64Url(sha256(randomCodeVerifier()))), null);
        assertThat(authorizeFirst.statusCode()).isEqualTo(302);
        String sessionCookie = sessionCookie(authorizeFirst);
        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, sessionCookie);
        String csrfToken = csrfTokenFrom(loginPage.body());
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);

        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=not-the-password&_csrf=" + encode(csrfToken), sessionCookie);
        assertThat(loginPost.statusCode()).isEqualTo(302);
        assertThat(loginPost.headers().firstValue("Location").orElse(""))
                .as("the documented authentication-failure redirect")
                .contains("/login?error");

        // The failure branch never reaches a code: re-issuing the authorize
        // request with the same (unauthenticated) session still bounces to
        // the login page.
        HttpResponse<String> authorizeRetry = get(authorizeUrl(state, base64Url(sha256(randomCodeVerifier()))), sessionCookie);
        assertThat(authorizeRetry.statusCode()).isEqualTo(302);
        assertThat(authorizeRetry.headers().firstValue("Location").orElse("")).contains(LOGIN_PATH);
    }

    /** The unauthenticated and garbage-token branches: the resource-server chain answers the honest 401. */
    @Test
    void anonymousAndGarbageTokensAnswer401() throws Exception {
        HttpResponse<String> anonymous = get(baseUrl() + "/api/v1/users/me", null);
        assertThat(anonymous.statusCode()).as("no Bearer, no /me").isEqualTo(401);

        HttpResponse<String> garbage = getWithBearer("/api/v1/users/me", "not-a-jwt");
        assertThat(garbage.statusCode()).as("a garbage Bearer is rejected").isEqualTo(401);
    }

    // ------------------------------------------------------------------
    // The gate helpers (the house shape, OrderJourneyIntegrationTest's own
    // — the full documented chain: PKCE + client_secret_basic + the form
    // login's CSRF dance).
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
                "Session Journey Gate Integration Test Client");
        String codeVerifier = randomCodeVerifier();
        String codeChallenge = base64Url(sha256(codeVerifier));

        HttpResponse<String> authorizeFirst = get(authorizeUrl(UUID.randomUUID().toString(), codeChallenge), null);
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
        assertThat(tokenResponse.statusCode()).as("token endpoint: %s", body(tokenResponse)).isEqualTo(200);

        JsonNode tokens = objectMapper.readTree(tokenResponse.body());
        return new GateResult(tokens.path("access_token").asString(),
                tokens.path("refresh_token").asString(), sessionCookie);
    }

    private record GateResult(String accessToken, String refreshToken, String sessionCookie) {
    }

    private String authorizeUrl(String state, String codeChallenge) {
        return baseUrl() + AUTHORIZE_PATH
                + "?response_type=code"
                + "&client_id=" + CLIENT_ID
                + "&scope=openid"
                + "&state=" + state
                + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256";
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
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + accessToken)
                .GET().build();
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
                .header("Accept", "application/json")
                .header("Authorization", "Basic " + credentials)
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : baseUrl() + location;
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

    private static String randomCodeVerifier() {
        return base64Url(UUID.randomUUID().toString().getBytes(StandardCharsets.US_ASCII));
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
}
