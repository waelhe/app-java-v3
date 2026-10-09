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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
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
 * A-05 (official-compliance plan §6 wave A — A.3, the app-store requirement;
 * execution plan §6 unit A-05 — the unit's declared gate, "IT حذف"): the
 * self-service deletion's FULL journey on real PostgreSQL + real Redis,
 * through real HTTP — the double-verified {@code DELETE /api/v1/users/me}
 * &rarr; 204 &rarr; the standing I7 erasure's every measured consequence
 * (identifier replacement, login-identity death, authorization death, the
 * tombstone) &rarr; the official logout leg's observable (the live
 * form-login session dies — Security
 * {@code servlet/authentication/logout.html}'s own warning: "Failing to
 * call SecurityContextLogoutHandler means that the SecurityContext could
 * still be available on subsequent requests, meaning that the user is not
 * actually logged out") &rarr; the honest negatives (a wrong password
 * answers 401 AUTHN-001 and mutates NOTHING — the session, the login row,
 * and the authorizations all survive for the retry; a token whose account
 * is already deleted answers 404).
 *
 * <p><b>Why real Flyway (the V33 lesson, the I7 guard's own harness):</b>
 * the schema under test is the production schema — {@code spring.flyway.
 * enabled=true} + {@code ddl-auto=none} — so V46's {@code pseudonymized_at},
 * the Envers {@code users_aud} mirror, and V13's login/authorization stores
 * are the real ones.
 *
 * <p><b>Why the PKCE login gate (the L23 pattern):</b> the only honest way
 * to prove "the login identity dies" and "the refresh grant dies" is the
 * real authorization-code flow on the real authorization server — form
 * login against {@code auth_users}, token exchange against
 * {@code /oauth2/token}, refresh grant with the real
 * {@code oauth2_authorization} rows. Fixtures follow the official wiring
 * ({@code RegisteredClientRepository.save}, {@code UserDetailsManager}).
 *
 * <p><b>Why the session cookie rides the DELETE (the official logout
 * prescription's measurable):</b> the deletion endpoint must invoke
 * {@code SecurityContextLogoutHandler} — the framework's complete-logout
 * component the reference prescribes for a custom Spring MVC logout
 * endpoint (bytecode-verified: {@code getSession(false)} invalidation +
 * holder clear + repository clear). The login gate's session cookie
 * attached to the DELETE request carries that proof end-to-end: after the
 * 204, the same cookie no longer authenticates an authorize request
 * (spring-session deleted the Redis session) — the exact observable that
 * would stay green if the prescription were skipped.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        // The production-shaped schema: real Flyway, no ddl-auto.
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The production cache type (the I5 lesson: force the shared Redis).
        "spring.cache.type=redis",
        "spring.cache.redis.time-to-live=1h",
        // CI connection budget (the I5/L14 harness rationale).
        "spring.datasource.hikari.maximum-pool-size=5",
        "spring.datasource.hikari.minimum-idle=1",
        // The login gate's client fixtures (the L23 gate properties).
        "marketplace.security.oauth2.client.client-id=marketplace-web-client",
        "marketplace.security.oauth2.client.secret=it-app-secret",
        "marketplace.security.oauth2.client.redirect-uris=http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client",
        // The b-2(b) secret channel for THIS guard — a test value, never a
        // production secret (secrets-policy §1).
        "marketplace.security.pseudonymization.subject-hmac-key=it-self-deletion-key",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AccountSelfDeletionIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The session-repository probe (the RememberMe test's own pattern): the
     * wrong-password leg's diagnostic — the authorize probe alone cannot
     * tell session-deleted-from-Redis apart from session-present-but-
     * unauthenticated; reading the row by its raw id can.
     */
    @Autowired
    private org.springframework.session.FindByIndexNameSessionRepository<? extends org.springframework.session.Session> sessionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserDetailsManager userDetailsManager;

    @Autowired
    private RegisteredClientRepository registeredClientRepository;

    @Value("${local.server.port}")
    private int portA;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final String PASSWORD = "it-self-deletion-password";
    private static final String LOGIN_PATH = "/login";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    /**
     * The acceptance sequence (the unit's declared gate, one journey — the
     * phases share the state the journey produces): the double-verified
     * deletion &rarr; every erasure consequence &rarr; the complete logout
     * &rarr; the honest 404 of the second call.
     */
    @Test
    void fullJourney_doubleVerifiedDeletionErasesTheIdentityAndCompletesTheLogout() throws Exception {
        String target = "it-self-delete-user";
        registerUser(target, "USER");
        GateResult gate = loginGate(target, PASSWORD);
        assertThat(gate.accessToken()).isNotBlank();
        UUID targetId = syncProjectionIdViaMe(gate.accessToken());

        // The action — real HTTP through the self-service surface, the login
        // gate's session cookie riding the request (the official logout
        // prescription's measurable).
        HttpResponse<String> deletion = deleteMyAccount(
                gate.accessToken(), gate.sessionCookie(), PASSWORD);
        assertThat(deletion.statusCode())
                .as("self-deletion call: %s", body(deletion)).isEqualTo(204);

        // The standing I7 erasure's every consequence (§5-أ): the row's
        // direct identifiers are gone, the status column is stamped, the
        // UUID is untouched.
        var row = jdbcTemplate.queryForMap(
                "SELECT subject, email, display_name, pseudonymized_at FROM users WHERE id = ?", targetId);
        String derivedSubject = (String) row.get("subject");
        assertThat(derivedSubject).startsWith("anon-").hasSize(69);
        assertThat(row.get("email")).isNull();
        assertThat(row.get("display_name")).isNull();
        assertThat(row.get("pseudonymized_at")).isNotNull();

        // The login identity rows are gone (both tables).
        assertThat(userDetailsManager.userExists(target)).isFalse();
        Integer authRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM auth_users WHERE username = ?", Integer.class, target);
        assertThat(authRows).isZero();
        Integer authorityRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM auth_authorities WHERE username = ?", Integer.class, target);
        assertThat(authorityRows).isZero();

        // The refresh grant dies with the authorization rows (invalid_grant).
        Integer authorizationRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM oauth2_authorization WHERE principal_name = ?",
                Integer.class, target);
        assertThat(authorizationRows).isZero();
        HttpResponse<String> refreshAfter = postFormWithBasicAuth(TOKEN_PATH,
                "grant_type=refresh_token&refresh_token=" + gate.refreshToken());
        assertThat(refreshAfter.statusCode())
                .as("refresh after self-deletion: %s", body(refreshAfter)).isEqualTo(400);

        // The form login dies with the auth_users row.
        assertThat(loginIsRejected(target, PASSWORD))
                .as("the next login attempt must be rejected").isTrue();

        // The tombstone: the still-valid access token (its 900s TTL window)
        // cannot resurrect the identity through /me provisioning.
        HttpResponse<String> meAfter = getWithBearer("/api/v1/users/me", gate.accessToken());
        assertThat(meAfter.statusCode())
                .as("/me after self-deletion (tombstone): %s", body(meAfter)).isEqualTo(409);

        // The official logout leg's observable: the login gate's session
        // cookie no longer authenticates an authorize request — the Redis
        // session died with the deletion ("the user is not actually logged
        // out" would be the failure mode the prescription closes).
        assertThat(sessionNoLongerAuthenticates(gate.sessionCookie()))
                .as("the login session must die with the account").isTrue();

        // The honest 404 of the second call: the pseudonymized row carries
        // the derived subject — the old token's subject resolves to nothing.
        HttpResponse<String> again = deleteMyAccount(
                gate.accessToken(), null, PASSWORD);
        assertThat(again.statusCode())
                .as("second self-deletion (already deleted): %s", body(again)).isEqualTo(404);
    }

    /**
     * The security guard the double verification exists for: a wrong
     * password answers the standing 401 AUTHN-001 problem contract and
     * mutates NOTHING — the login row, the identifier row, the
     * authorizations, AND the live session all survive for the retry.
     */
    @Test
    void wrongPasswordAnswers401AndMutatesNothing() throws Exception {
        String target = "it-self-delete-wrongpw-user";
        registerUser(target, "USER");
        GateResult gate = loginGate(target, PASSWORD);
        UUID targetId = syncProjectionIdViaMe(gate.accessToken());

        HttpResponse<String> rejected = deleteMyAccount(
                gate.accessToken(), gate.sessionCookie(), "the-wrong-password");
        assertThat(rejected.statusCode())
                .as("wrong-password deletion: %s", body(rejected)).isEqualTo(401);
        assertThat(rejected.body()).contains("AUTHN-001");

        // Nothing mutated: the identifier row is untouched, the login row
        // still exists, the authorizations still live.
        var row = jdbcTemplate.queryForMap(
                "SELECT subject, email, pseudonymized_at FROM users WHERE id = ?", targetId);
        assertThat(row.get("subject")).isEqualTo(target);
        assertThat(row.get("email")).isEqualTo(target);
        assertThat(row.get("pseudonymized_at")).isNull();
        assertThat(userDetailsManager.userExists(target)).isTrue();
        Integer authorizationRows = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM oauth2_authorization WHERE principal_name = ?",
                Integer.class, target);
        assertThat(authorizationRows).isPositive();

        // The session did NOT die: a failed verification (401) must not
        // terminate the caller's authenticated state — the retry keeps its
        // session (the logout runs only after the deletion succeeds).
        // (The 2026-10-09 diagnostic: the authorize probe alone cannot
        // discriminate session-deleted-from-Redis vs session-present-but-
        // unauthenticated — the repository probe answers that, the
        // RememberMe test's own pattern.)
        assertThat(sessionRepository.findById(rawSessionId(gate.sessionCookie())))
                .as("the session ROW survives the failed verification")
                .isNotNull();
        assertThat(sessionNoLongerAuthenticates(gate.sessionCookie()))
                .as("a failed verification must not terminate the session").isFalse();
    }

    /**
     * The inherited guard at the HTTP surface: the account's own holder
     * cannot delete the LAST active ADMIN — the lockout hole the L23
     * counting constraint closes, inherited unchanged because the
     * self-service deletion IS the same operation.
     */
    @Test
    void lastActiveAdminCannotDeleteTheirOwnAccount() throws Exception {
        UserDetails seeded = silenceSeededAdminIfPresent();
        try {
            String target = "it-self-delete-last-admin";
            registerUser(target, "ADMIN");
            GateResult gate = loginGate(target, PASSWORD);
            UUID targetId = syncProjectionIdViaMe(gate.accessToken());

            HttpResponse<String> conflict = deleteMyAccount(
                    gate.accessToken(), gate.sessionCookie(), PASSWORD);
            assertThat(conflict.statusCode())
                    .as("last-admin self-deletion: %s", body(conflict)).isEqualTo(409);

            // The guard ran BEFORE any mutation: the account is intact.
            assertThat(userDetailsManager.userExists(target)).isTrue();
            var row = jdbcTemplate.queryForMap(
                    "SELECT pseudonymized_at FROM users WHERE id = ?", targetId);
            assertThat(row.get("pseudonymized_at")).isNull();
        } finally {
            restoreSeededAdmin(seeded);
        }
    }

    // -- fixtures & helpers (the L23 gate shapes, the I7 guard's harness) --

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
        AuthorizationServerFixture.registerLoginGateClient(registeredClientRepository,
                "Self-Deletion Gate Integration Test Client");
    }

    /**
     * The real browser-less login gate — the L23 gate's proven five-step
     * sequence, verbatim, returning the live session cookie alongside the
     * tokens (the official logout prescription's measurable rides on it).
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
        return new GateResult(
                tokens.path("access_token").asString(),
                tokens.path("refresh_token").asString(),
                tokens.path("token_type").asString(),
                tokens.path("id_token").asString(),
                sessionCookie);
    }

    /** The A-05 action: DELETE /me with the double-verification body. */
    private HttpResponse<String> deleteMyAccount(String accessToken, String sessionCookie, String password)
            throws Exception {
        // JDK HttpClient's DELETE() takes no body — the official form for a
        // body-carrying DELETE is builder.method("DELETE", publisher).
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/v1/users/me"))
                .header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .method("DELETE", HttpRequest.BodyPublishers.ofString(
                        "{\"password\":\"" + password + "\"}", StandardCharsets.UTF_8));
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The repository keys sessions by the RAW id; the pair arrives in the
     * house's cookie-header form "SESSION=<value>" — the VALUE (after the
     * first '=') is the Base64 the serializer wrote (the measured probe
     * lesson: decoding the whole pair dies at the '=' — "incorrect ending
     * byte at 8").
     */
    private static String rawSessionId(String sessionCookiePair) {
        String value = sessionCookiePair.substring(sessionCookiePair.indexOf('=') + 1);
        return new String(java.util.Base64.getDecoder().decode(value));
    }

    /**
     * The official logout leg's measurable: an authorize request made with
     * the session cookie either still authenticates (302 straight to the
     * client's redirect URI — the session is alive) or bounces to the login
     * page (302 to /login — the session is dead).
     */
    private boolean sessionNoLongerAuthenticates(String sessionCookie) throws Exception {
        String authorizeUrl = baseUrl() + AUTHORIZE_PATH
                + "?response_type=code"
                + "&client_id=" + CLIENT_ID
                + "&scope=openid"
                + "&state=" + UUID.randomUUID()
                + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&code_challenge=" + base64Url(sha256(randomCodeVerifier()))
                + "&code_challenge_method=S256";
        HttpResponse<String> authorize = get(authorizeUrl, sessionCookie);
        assertThat(authorize.statusCode())
                .as("authorize with the login session: %s", body(authorize)).isEqualTo(302);
        String location = authorize.headers().firstValue("Location").orElse("");
        return location.contains(LOGIN_PATH);
    }

    private UUID syncProjectionIdViaMe(String accessToken) throws Exception {
        HttpResponse<String> me = getWithBearer("/api/v1/users/me", accessToken);
        assertThat(me.statusCode()).as("/me sync: %s", body(me)).isEqualTo(200);
        JsonNode user = objectMapper.readTree(me.body());
        assertThat(user.path("id").asString()).as("the /me response must carry the id").isNotBlank();
        return UUID.fromString(user.path("id").asString());
    }

    private UserDetails silenceSeededAdminIfPresent() {
        UserDetails seeded;
        try {
            seeded = userDetailsManager.loadUserByUsername("admin");
        } catch (UsernameNotFoundException noSeed) {
            return null;
        }
        if (seeded.isEnabled()) {
            userDetailsManager.updateUser(withEnabled(seeded, false));
        }
        return seeded;
    }

    private void restoreSeededAdmin(UserDetails seeded) {
        if (seeded != null && seeded.isEnabled()) {
            userDetailsManager.updateUser(withEnabled(seeded, true));
        }
    }

    private static UserDetails withEnabled(UserDetails source, boolean enabled) {
        return org.springframework.security.core.userdetails.User.withUsername(source.getUsername())
                .password(source.getPassword())
                .authorities(source.getAuthorities())
                .accountExpired(!source.isAccountNonExpired())
                .accountLocked(!source.isAccountNonLocked())
                .credentialsExpired(!source.isCredentialsNonExpired())
                .disabled(!enabled)
                .build();
    }

    private boolean loginIsRejected(String username, String password) throws Exception {
        String authorizeUrl = baseUrl() + AUTHORIZE_PATH
                + "?response_type=code"
                + "&client_id=" + CLIENT_ID
                + "&scope=openid"
                + "&state=" + UUID.randomUUID()
                + "&redirect_uri=" + encode(REDIRECT_URI)
                + "&code_challenge=" + base64Url(sha256(randomCodeVerifier()))
                + "&code_challenge_method=S256";
        HttpResponse<String> authorizeFirst = get(authorizeUrl, null);
        assertThat(authorizeFirst.statusCode()).isEqualTo(302);
        String sessionCookie = sessionCookie(authorizeFirst);
        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, sessionCookie);
        String csrfToken = csrfTokenFrom(loginPage.body());
        assertThat(csrfToken).isNotBlank();
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);
        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + password + "&_csrf=" + encode(csrfToken), sessionCookie);
        assertThat(loginPost.statusCode())
                .as("login POST must answer the 302 redirect contract: %s", body(loginPost))
                .isEqualTo(302);
        String location = loginPost.headers().firstValue("Location").orElse("");
        return location.contains("/login?error");
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
        return "http://127.0.0.1:" + portA;
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : baseUrl() + location;
    }

    /** PKCE verifier per the official RFC 7636 flow shape (login-gate test). */
    private static String randomCodeVerifier() {
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
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

    /** The gate's tokens plus the live session cookie (the logout leg's measurable). */
    private record GateResult(String accessToken, String refreshToken, String tokenType,
                              String idToken, String sessionCookie) {
    }
}
