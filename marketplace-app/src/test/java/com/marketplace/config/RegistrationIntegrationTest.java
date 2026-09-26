package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
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

/**
 * S1/B1 (platform-readiness audit §6 gate B1 — the registration surface): the
 * end-to-end guard for the account's whole birth-to-first-call loop, through
 * the REAL chains on the REAL Flyway schema:
 * <ol>
 *   <li><b>The S1 core:</b> an anonymous caller registers — 201, the CONSUMER
 *       profile in the response, both stores written in one transaction.</li>
 *   <li><b>The loop closes (the audit's real test):</b> the SAME credentials
 *       walk the full L23 login gate (the five-step PKCE browser-less flow,
 *       verbatim) and mint a REAL access token — the registered account is
 *       LOGINABLE, which no seeding path ever proved.</li>
 *   <li><b>The token is a first-class caller:</b> GET /users/me with that
 *       Bearer answers the registered profile (syncFromOidc resolves the
 *       row registration created — the subject IS the email).</li>
 *   <li><b>The honest rejections:</b> a duplicate address answers 409; a
 *       too-short password answers the clean 400 (the policy is the request
 *       contract — 8..72, the bcrypt byte ceiling).</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@TestPropertySource(properties = {
        // The login gate's client fixtures (the L23 gate pattern, verbatim —
        // the standing initializer bootstraps the client from env).
        "marketplace.security.oauth2.client.client-id=marketplace-web-client",
        "marketplace.security.oauth2.client.secret=it-app-secret",
        "marketplace.security.oauth2.client.redirect-uris=http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class RegistrationIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${local.server.port}")
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

    /** One address per test method — the uniqueness guard is per-address. */
    private String uniqueEmail() {
        return "s1-" + UUID.randomUUID() + "@example.com";
    }

    @Test
    void theRegisteredAccountWalksTheFullLoop_registerLoginGateTokenThenMe() throws Exception {
        String email = uniqueEmail();
        String password = "s1-valid-password";

        // (1) The anonymous registration — 201 with the CONSUMER profile.
        HttpResponse<String> registered = postJson("/api/v1/auth/register", """
                {"email": "%s", "password": "%s", "displayName": "S1 Member"}
                """.formatted(email, password));
        assertThat(registered.statusCode()).as("registration: %s", body(registered)).isEqualTo(201);
        JsonNode profile = objectMapper.readTree(registered.body());
        assertThat(profile.path("subject").asString()).isEqualTo(email);
        assertThat(profile.path("email").asString()).isEqualTo(email);
        assertThat(profile.path("displayName").asString()).isEqualTo("S1 Member");
        assertThat(profile.path("role").asString()).isEqualTo("CONSUMER");

        // (2) The full L23 login gate with THOSE credentials — a real token.
        String accessToken = loginGateAccessToken(email, password);
        assertThat(accessToken).as("the registered account is loginable — the gate mints a real token").isNotBlank();

        // (3) The token is a first-class API caller: /users/me answers the
        // registered profile (syncFromOidc resolves the row by subject).
        HttpResponse<String> me = getJson("/api/v1/users/me", accessToken);
        assertThat(me.statusCode()).as("the minted token calls the API: %s", body(me)).isEqualTo(200);
        JsonNode meProfile = objectMapper.readTree(me.body());
        assertThat(meProfile.path("subject").asString()).isEqualTo(email);
        assertThat(meProfile.path("role").asString()).isEqualTo("CONSUMER");
    }

    @Test
    void aDuplicateAddressAnswersConflict() throws Exception {
        String email = uniqueEmail();

        HttpResponse<String> first = postJson("/api/v1/auth/register", """
                {"email": "%s", "password": "s1-valid-password", "displayName": "First"}
                """.formatted(email));
        assertThat(first.statusCode()).isEqualTo(201);

        HttpResponse<String> second = postJson("/api/v1/auth/register", """
                {"email": "%s", "password": "s1-other-password", "displayName": "Second"}
                """.formatted(email));
        assertThat(second.statusCode()).as("duplicate: %s", body(second)).isEqualTo(409);
        assertThat(second.body()).contains("already exists");
    }

    @Test
    void aTooShortPasswordAnswersTheCleanValidation400() throws Exception {
        HttpResponse<String> response = postJson("/api/v1/auth/register", """
                {"email": "%s", "password": "short", "displayName": "S1 Member"}
                """.formatted(uniqueEmail()));
        assertThat(response.statusCode()).as("weak password: %s", body(response)).isEqualTo(400);
    }

    // -- HTTP helpers + the L23 login gate (browser-less five-step PKCE) -------

    private HttpResponse<String> postJson(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> getJson(String path, String bearer) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).GET();
        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String loginGateAccessToken(String username, String password) throws Exception {
        String clientId = "marketplace-web-client";
        String clientSecret = "it-app-secret";
        String redirectUri = "http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client";
        String state = UUID.randomUUID().toString();
        String codeVerifier = UUID.randomUUID().toString().replace("-", "");
        String codeChallenge = base64Url(sha256(codeVerifier));
        String authorizeUrl = "http://127.0.0.1:" + port + "/oauth2/authorize"
                + "?response_type=code"
                + "&client_id=" + clientId
                + "&scope=openid"
                + "&state=" + state
                + "&redirect_uri=" + java.net.URLEncoder.encode(redirectUri, StandardCharsets.UTF_8)
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256";

        HttpResponse<String> authorizeFirst = get(authorizeUrl, null);
        assertThat(authorizeFirst.statusCode()).as("authorize should redirect to login: %s", body(authorizeFirst)).isEqualTo(302);
        String sessionCookie = sessionCookie(authorizeFirst);
        assertThat(sessionCookie).isNotBlank();

        HttpResponse<String> loginPage = get("http://127.0.0.1:" + port + "/login", sessionCookie);
        assertThat(loginPage.statusCode()).as("login page: %s", body(loginPage)).isEqualTo(200);
        String csrfToken = csrfTokenFrom(loginPage.body());
        assertThat(csrfToken).isNotBlank();
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);

        HttpResponse<String> loginPost = postForm("/login",
                "username=" + java.net.URLEncoder.encode(username, StandardCharsets.UTF_8)
                        + "&password=" + java.net.URLEncoder.encode(password, StandardCharsets.UTF_8)
                        + "&_csrf=" + java.net.URLEncoder.encode(csrfToken, StandardCharsets.UTF_8), sessionCookie);
        assertThat(loginPost.statusCode()).as("login should succeed: %s", body(loginPost)).isEqualTo(302);
        String savedRequest = loginPost.headers().firstValue("Location").orElse("");
        assertThat(savedRequest).as("login must resume the saved authorization request, not /login?error")
                .contains("/oauth2/authorize");
        sessionCookie = latestSessionCookie(loginPost, sessionCookie);

        HttpResponse<String> authorizeSecond = get(absolute(savedRequest), sessionCookie);
        assertThat(authorizeSecond.statusCode())
                .as("authorize should redirect back to the client: %s", body(authorizeSecond)).isEqualTo(302);
        String redirect = authorizeSecond.headers().firstValue("Location").orElse("");
        assertThat(redirect).startsWith(redirectUri);
        String code = param(redirect, "code");

        HttpRequest tokenRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/oauth2/token"))
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
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (sessionCookie != null) {
            builder.header("Cookie", sessionCookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : "http://127.0.0.1:" + port + location;
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
