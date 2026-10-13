package com.marketplace.config;

import test.config.AuthorizationServerFixture;
import test.config.IntegrationContainers;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.crypto.password.StandardPasswordEncoder;
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

/**
 * A-07 (official-compliance plan §6 wave A — A.6) — the declared unit gate:
 * «اختبار ترميز» (the encoding test), over real HTTP, the real login
 * gateway, and the real {@code auth_users} rows. It measures the official
 * password-upgrade journey the wiring in {@code SecurityConfig} installs:
 *
 * <ul>
 *   <li><b>The upgrade leg.</b> A row stored under a foreign {@code {id}}
 *       ({@code {sha256}} — the reference's own example form) still
 *       authenticates the raw password (id-driven matching), and the
 *       successful login re-encodes the verifier to the preferred
 *       {@code {bcrypt}} form through the framework's own path:
 *       {@code DaoAuthenticationProvider} → {@code upgradeEncoding} →
 *       {@code UserDetailsPasswordService#updatePassword} → the customized
 *       {@code updateUserSql}. The upgraded verifier must itself still
 *       authenticate the SAME raw password on the next login (the journey's
 *       continuity — the user never notices the migration).</li>
 *   <li><b>The churn negative.</b> A row already in the preferred form at
 *       the configured strength is byte-identical after a successful login
 *       — the decision primitive answers false, so the write never happens
 *       (no gratuitous rewrites, no history noise).</li>
 *   <li><b>The failure negative.</b> A failed login (wrong password)
 *       upgrades nothing: the upgrade lives in
 *       {@code createSuccessAuthentication}, which only runs after the
 *       match succeeds — a wrong password leaves the legacy verifier
 *       untouched (zero mutation on failure, the A-04/A-05 rule).</li>
 * </ul>
 *
 * <p><b>Fixtures follow the official wiring</b> (the login-gate/R8/A-06
 * precedents): the client through {@link RegisteredClientRepository}, the
 * users through {@link UserDetailsManager} (the very bean whose concrete
 * type now implements {@code UserDetailsPasswordService}), and the V13
 * authorization schema through {@code spring.sql.init} ordered before the
 * JDBC beans. Each leg uses its own user so the methods stay
 * order-independent.
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
class PasswordEncodingUpgradeIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    /** The legacy-form user of the upgrade leg. */
    private static final String LEGACY_USERNAME = "it-a07-sha256-user";

    /** The healthy-bcrypt user of the churn negative. */
    private static final String CURRENT_USERNAME = "it-a07-bcrypt-user";

    /** The wrong-password user of the failure negative. */
    private static final String FAILURE_USERNAME = "it-a07-failure-user";

    private static final String PASSWORD = "it-a07-gate-password";

    private static final String LOGIN_PATH = "/login";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    @Value("${local.server.port}")
    private int port;

    /** The production encoder — the delegating instance the login chain consults. */
    @Autowired
    private PasswordEncoder passwordEncoder;

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
                "A-07 Password Encoding Upgrade Integration Test Client");
        registerUser(LEGACY_USERNAME, "{sha256}" + new StandardPasswordEncoder("").encode(PASSWORD));
        registerUser(CURRENT_USERNAME, passwordEncoder.encode(PASSWORD));
        registerUser(FAILURE_USERNAME, "{sha256}" + new StandardPasswordEncoder("").encode(PASSWORD));
    }

    /**
     * The upgrade leg: the legacy {@code {sha256}} verifier authenticates,
     * and the successful login rewrites the stored row to the preferred
     * {@code {bcrypt}} form — the official migration path for one-way
     * hashes, measured end to end (HTTP → provider → encoder decision →
     * updatePassword → the customized SQL). The SECOND login proves the
     * journey's continuity: the upgraded verifier serves the same raw
     * password.
     */
    @Test
    void successfulLoginUpgradesTheLegacyVerifierAndKeepsTheJourneyWorking() throws Exception {
        assertThat(storedPasswordOf(LEGACY_USERNAME))
                .as("the fixture must start from the legacy form (the premise)")
                .startsWith("{sha256}");

        LoginOutcome first = loginAttempt(LEGACY_USERNAME, PASSWORD);
        assertThat(first.redirectsToError())
                .as("the legacy verifier must authenticate the raw password (id-driven matching): %s",
                        first.location())
                .isFalse();

        assertThat(storedPasswordOf(LEGACY_USERNAME))
                .as("the successful login must upgrade the stored form to the preferred {bcrypt}")
                .startsWith("{bcrypt}");

        LoginOutcome second = loginAttempt(LEGACY_USERNAME, PASSWORD);
        assertThat(second.redirectsToError())
                .as("the upgraded verifier must serve the SAME raw password on the next login (continuity)")
                .isFalse();
    }

    /**
     * The churn negative: a verifier already in the preferred form at the
     * configured strength is byte-identical after a successful login — the
     * upgrade decision answers false and no write happens.
     */
    @Test
    void healthyVerifierIsRewrittenNever() throws Exception {
        String before = storedPasswordOf(CURRENT_USERNAME);

        LoginOutcome outcome = loginAttempt(CURRENT_USERNAME, PASSWORD);
        assertThat(outcome.redirectsToError())
                .as("the healthy verifier must authenticate: %s", outcome.location())
                .isFalse();

        assertThat(storedPasswordOf(CURRENT_USERNAME))
                .as("a {bcrypt} verifier at the configured strength must be byte-identical after login (no churn)")
                .isEqualTo(before);
    }

    /**
     * The failure negative: a wrong password fails the login and upgrades
     * nothing — the upgrade path lives in the success leg only.
     */
    @Test
    void failedLoginUpgradesNothing() throws Exception {
        String before = storedPasswordOf(FAILURE_USERNAME);

        LoginOutcome outcome = loginAttempt(FAILURE_USERNAME, "not-" + PASSWORD);
        assertThat(outcome.redirectsToError())
                .as("the wrong password must fail the login (the default failure URL)")
                .isTrue();

        assertThat(storedPasswordOf(FAILURE_USERNAME))
                .as("a failed login must leave the legacy verifier untouched (zero mutation)")
                .isEqualTo(before);
    }

    // -- fixtures and login helpers (the login-gate conventions) -----------

    private void registerUser(String username, String storedPassword) {
        if (userDetailsManager.userExists(username)) {
            return;
        }
        UserDetails user = User.withUsername(username)
                .password(storedPassword)
                .roles("USER")
                .build();
        userDetailsManager.createUser(user);
    }

    /** The stored verifier of the login row, read through the manager's own query. */
    private String storedPasswordOf(String username) {
        return userDetailsManager.loadUserByUsername(username).getPassword();
    }

    /** The raw outcome of one standalone login POST against the form-login chain. */
    private record LoginOutcome(int status, String location) {
        boolean redirectsToError() {
            return location != null && location.contains("error");
        }
    }

    /**
     * One full login attempt: fetch the login page (a fresh session renders
     * the CSRF token), then POST the credentials. The A.6 mechanism fires on
     * ANY successful form-login POST — no authorization dance needed.
     */
    private LoginOutcome loginAttempt(String username, String password) throws Exception {
        HttpResponse<String> loginPage = get(LOGIN_PATH, null);
        assertThat(loginPage.statusCode()).as("login page: %s", body(loginPage)).isEqualTo(200);
        String sessionCookie = latestSessionCookie(loginPage, null);
        String csrfToken = csrfTokenFrom(loginPage.body());
        assertThat(csrfToken).as("CSRF token must be rendered by the default login page").isNotBlank();

        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + password + "&_csrf=" + encode(csrfToken), sessionCookie);
        return new LoginOutcome(loginPost.statusCode(),
                loginPost.headers().firstValue("Location").orElse(""));
    }

    // -- shared helpers (login-gate conventions) ---------------------------

    private HttpResponse<String> get(String path, String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl() + path))
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
}
