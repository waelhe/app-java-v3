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
 * A-06 (official-compliance plan §6 wave A — A.4) — the overflow policy's
 * other documented half, over real HTTP and the real Redis session store:
 * {@code maxSessionsPreventsLogin=true} («It is also common that you would
 * prefer to prevent a second login, in which case you can use:
 * …maxSessionsPreventsLogin(true)… The second login will then be rejected.
 * By "rejected", we mean that the user will be sent to the
 * authentication-failure-url if form-based login is being used»).
 *
 * <p><b>The leg this closes.</b> The default-policy class
 * ({@code ConcurrentSessionsIntegrationTest}) measures the documented
 * evict-least-recent behavior; this class binds the A.4 property to
 * {@code true} through {@code @TestPropertySource} and measures the
 * alternative the reference documents for the same maximum: at the limit
 * (max-sessions 2), the third device's form login is REJECTED at the
 * authentication-failure URL ({@code /login?error}), and — the defining
 * difference of the policy — <b>nothing is expired</b>: both live sessions
 * keep minting authorization codes after the rejected attempt (the
 * reference's own test shape: «second login is prevented… first session is
 * still valid»).
 *
 * <p><b>Fixtures follow the official wiring</b> (the login-gate/R8
 * precedents): the client through {@link RegisteredClientRepository}, the
 * user through {@link UserDetailsManager}, and the V13 authorization schema
 * through {@code spring.sql.init} ordered before the JDBC beans.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/migration/V13__authorization_security.sql",
        "marketplace.security.oauth2.client.client-id=marketplace-web-client",
        "marketplace.security.oauth2.client.secret=it-app-secret",
        "marketplace.security.oauth2.client.redirect-uris=http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client",
        // A.4's overflow policy, bound to the documented alternative for this
        // context: true = the new login is rejected, the live sessions stay.
        "marketplace.security.session.max-sessions-prevents-login=true"
})
@Testcontainers(disabledWithoutDocker = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ConcurrentSessionsPreventLoginIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    private static final String SATURATED_USERNAME = "it-a06-prevent-login-user";

    private static final String PASSWORD = "it-a06-prevent-login-password";

    private static final String LOGIN_PATH = "/login";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

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
                "A-06 Concurrent Sessions Prevent-Login Integration Test Client");
        if (!userDetailsManager.userExists(SATURATED_USERNAME)) {
            UserDetails user = User.withUsername(SATURATED_USERNAME)
                    .password("{noop}" + PASSWORD)
                    .roles("USER")
                    .build();
            userDetailsManager.createUser(user);
        }
    }

    /**
     * The A.4 alternative policy, end to end: two devices log in (the
     * maximum), the third device's login is rejected at the form-login
     * failure URL — the documented «authentication-failure-url» — and both
     * live sessions still mint authorization codes afterwards: the
     * prevent-login policy expires nothing.
     */
    @Test
    void thirdLoginIsRejectedWhileBothLiveSessionsSurvive() throws Exception {
        String firstSession = loginForSession(SATURATED_USERNAME);
        assertThat(authorizeWithSession(firstSession))
                .as("the first device's session must mint a code (the policy's premise)")
                .contains("code=");

        String secondSession = loginForSession(SATURATED_USERNAME);
        assertThat(authorizeWithSession(secondSession))
                .as("the second device's session must mint a code at the limit")
                .contains("code=");

        // The documented rejection shape for form-based login: the failure
        // URL, not the evicted oldest session.
        assertThat(loginIsRejected(SATURATED_USERNAME))
                .as("the third device's login must be rejected (maxSessionsPreventsLogin=true)").isTrue();

        assertThat(authorizeWithSession(firstSession))
                .as("the first device's session must still mint after the rejected attempt (nothing was expired)")
                .contains("code=");
        assertThat(authorizeWithSession(secondSession))
                .as("the second device's session must still mint after the rejected attempt (nothing was expired)")
                .contains("code=");
    }

    // -- fixtures and login helpers (the login-gate/R8 pattern) ------------

    /**
     * Runs the login part of the gate and keeps the post-login session
     * cookie — the R8 precedent's shape.
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
     * Runs the login part of the gate and reports whether it FAILED: a
     * rejected credential POST redirects to {@code /login?error} instead of
     * the saved authorization request — the R8 precedent's helper, carrying
     * the documented meaning of «rejected» for form-based login.
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

    private String authorizeWithSession(String sessionCookie) throws Exception {
        HttpResponse<String> response = get(authorizeUrl(UUID.randomUUID().toString(), randomCodeVerifier()),
                sessionCookie);
        return response.headers().firstValue("Location").orElse("");
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
