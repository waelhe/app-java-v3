package com.marketplace.config;

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
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real end-to-end login gate for the framework-managed authorization server:
 * authorization request (code + PKCE) &rarr; form login &rarr; authorization code
 * &rarr; token endpoint &rarr; Bearer access token &rarr; protected API.
 *
 * <p>Closes the E5 gap: {@code jwt()} test post-processors bypass the real
 * {@link org.springframework.security.oauth2.jwt.JwtDecoder} and
 * {@link org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter};
 * this gate exercises the full mint&ndash;validate loop over real HTTP, proving
 * that tokens issued by the authorization server (customized with
 * {@code roles} + {@code aud} by the {@code OAuth2TokenCustomizer}) pass the
 * resource server decoder (issuer + audience validators) and the authorities
 * converter on a protected endpoint.
 *
 * <p>Fixtures follow the official wiring: the schema is the application's own
 * {@code V13__authorization_security.sql} migration (the PostgreSQL adaptation of
 * the Spring Authorization Server schema), the client is registered through the
 * {@link RegisteredClientRepository} bean, and the user through the
 * {@link UserDetailsManager} bean.
 *
 * <p>The schema is applied through {@code spring.sql.init} (not in a
 * {@code @BeforeAll}) on purpose: {@code JdbcOAuth2AuthorizationService} resolves
 * the LOB-ish column types from live database metadata while the bean is being
 * constructed (JdbcOAuth2AuthorizationService.java:400-460). If the tables do not
 * exist yet, every {@code *_value}/{@code *_metadata}/{@code attributes} column
 * falls back to the BLOB default and token values are bound as {@code bytea}
 * ({@code operator does not exist: text = bytea} on PostgreSQL). Boot orders the
 * SQL initializer before the {@code JdbcTemplate} bean (and therefore before this
 * test context's {@code JdbcOAuth2AuthorizationService}), which mirrors production,
 * where Flyway migrates before the beans are constructed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/migration/V13__authorization_security.sql"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AuthorizationServerLoginGateIntegrationTest {

    private static final String ADMIN_USERNAME = "it-login-gate-admin";
    private static final String USER_USERNAME = "it-login-gate-user";
    private static final String PASSWORD = "it-login-gate-password";

    private static final String CLIENT_ID = "it-login-gate-client";
    private static final String CLIENT_SECRET = "it-login-gate-secret";
    private static final String REDIRECT_URI = "https://login-gate.test.example/callback";

    private static final String LOGIN_PATH = "/login";
    private static final String AUTHORIZE_PATH = "/oauth2/authorize";
    private static final String TOKEN_PATH = "/oauth2/token";
    private static final String PROTECTED_ADMIN_PATH = "/api/v1/admin/system";

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");
    private static final Pattern CSRF_INPUT = Pattern.compile("<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"");
    private static final Pattern CSRF_INPUT_REVERSED = Pattern.compile("<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"");

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

    private GateResult adminGate;

    @BeforeAll
    void setUpFixtures() {
        registerLoginGateClient();
        registerUser(ADMIN_USERNAME, "ADMIN");
        registerUser(USER_USERNAME, "USER");
    }

    @Test
    void authorizationCodeWithPkceMintsTokenAcceptedByRealDecoderAndRoleGate() throws Exception {
        GateResult gate = adminGate();

        assertThat(gate.accessToken()).isNotBlank();
        assertThat(gate.tokenType()).isEqualToIgnoringCase("Bearer");
        assertThat(gate.refreshToken()).isNotBlank();

        // Mint side: the OAuth2TokenCustomizer issues roles + aud, the issuer comes from
        // spring.security.oauth2.authorizationserver.issuer (AuthorizationServerSettings bean).
        // aud is asserted through the parsed JSON: RFC 7519 4.1.3 allows the single-value
        // (string) and the multi-value (array) serialization, so the raw-contains form
        // would depend on the serializer's shape choice.
        JsonNode claims = objectMapper.readTree(jwtClaimsAsJson(gate.accessToken()));
        assertThat(claims.path("iss").asString()).isEqualTo("http://localhost:8080");
        assertThat(claims.path("aud").toString()).contains("marketplace-api");
        assertThat(claims.path("roles").toString()).contains("ADMIN");

        // Validate side: the real decoder (issuer + audience + signature) and the
        // JwtAuthenticationConverter (roles claim -> ROLE_ authorities) gate the API.
        // 401 would mean the token failed validation; 403 would mean the ROLE_ADMIN
        // authority was not mapped; 404 means authentication and authorization passed
        // and no handler is mapped at this path (there is none - only the security rule).
        HttpResponse<String> apiResponse = getWithBearer(PROTECTED_ADMIN_PATH, gate.accessToken());
        assertThat(apiResponse.statusCode()).isNotEqualTo(401);
        assertThat(apiResponse.statusCode()).isNotEqualTo(403);
    }

    @Test
    void mintedTokenForNonAdminPrincipalIsRejectedWithProblemDetail403() throws Exception {
        GateResult gate = loginGate(USER_USERNAME, PASSWORD);

        HttpResponse<String> apiResponse = getWithBearer(PROTECTED_ADMIN_PATH, gate.accessToken());

        assertThat(apiResponse.statusCode()).isEqualTo(403);
        assertThat(apiResponse.headers().firstValue("Content-Type").orElse(""))
                .contains("application/problem+json");
        assertThat(apiResponse.body()).contains("Access denied");
    }

    @Test
    void refreshTokenGrantRotatesAccessTokenStillAcceptedByTheGate() throws Exception {
        GateResult gate = adminGate();

        HttpResponse<String> refreshResponse = postFormWithBasicAuth(TOKEN_PATH,
                "grant_type=refresh_token&refresh_token=" + gate.refreshToken());
        assertThat(refreshResponse.statusCode()).isEqualTo(200);

        JsonNode tokens = objectMapper.readTree(refreshResponse.body());
        String rotatedAccessToken = tokens.path("access_token").asString();
        assertThat(rotatedAccessToken).isNotBlank().isNotEqualTo(gate.accessToken());

        HttpResponse<String> apiResponse = getWithBearer(PROTECTED_ADMIN_PATH, rotatedAccessToken);
        assertThat(apiResponse.statusCode()).isNotEqualTo(401);
        assertThat(apiResponse.statusCode()).isNotEqualTo(403);
    }

    /**
     * Executes the real browser-less login gate:
     * authorize (code + PKCE) -&gt; 302 /login -&gt; credentials POST -&gt; saved request
     * -&gt; authorization code -&gt; token exchange (client_secret_basic + code_verifier).
     */
    private GateResult loginGate(String username, String password) throws Exception {
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

        // 1) Unauthenticated authorization request -> redirect to the login page
        //    (LoginUrlAuthenticationEntryPoint negotiated via Accept: text/html).
        HttpResponse<String> authorizeFirst = get(authorizeUrl, null);
        assertThat(authorizeFirst.statusCode()).as("authorize should redirect to login: %s", body(authorizeFirst))
                .isEqualTo(302);
        assertThat(authorizeFirst.headers().firstValue("Location").orElse("")).contains(LOGIN_PATH);
        String sessionCookie = sessionCookie(authorizeFirst);
        assertThat(sessionCookie).as("spring-session cookie expected").isNotBlank();

        // 2) Fetch the login form; the CSRF token is bound to the session.
        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, sessionCookie);
        assertThat(loginPage.statusCode()).as("login page: %s", body(loginPage)).isEqualTo(200);
        String csrfToken = csrfTokenFrom(loginPage.body());
        assertThat(csrfToken).as("CSRF token must be rendered by the default login page").isNotBlank();
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);

        // 3) Submit credentials -> redirect back to the saved authorization request.
        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + password + "&_csrf=" + encode(csrfToken), sessionCookie);
        assertThat(loginPost.statusCode()).as("login should succeed: %s", body(loginPost)).isEqualTo(302);
        String savedRequest = loginPost.headers().firstValue("Location").orElse("");
        assertThat(savedRequest).contains(AUTHORIZE_PATH);
        sessionCookie = latestSessionCookie(loginPost, sessionCookie);

        // 4) Re-issue the authorization request as an authenticated principal -> code.
        HttpResponse<String> authorizeSecond = get(absolute(savedRequest), sessionCookie);
        assertThat(authorizeSecond.statusCode())
                .as("authorize should redirect back to the client: %s", body(authorizeSecond))
                .isEqualTo(302);
        String redirect = authorizeSecond.headers().firstValue("Location").orElse("");
        assertThat(redirect).startsWith(REDIRECT_URI);
        assertThat(redirect).contains("code=");
        assertThat(redirect).contains("state=" + state);
        String authorizationCode = queryParam(redirect, "code");

        // 5) Exchange the code for tokens (client_secret_basic + PKCE verifier).
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
                tokens.path("token_type").asString());
    }

    private synchronized GateResult adminGate() throws Exception {
        if (adminGate == null) {
            adminGate = loginGate(ADMIN_USERNAME, PASSWORD);
        }
        return adminGate;
    }

    private void registerLoginGateClient() {
        if (registeredClientRepository.findByClientId(CLIENT_ID) != null) {
            return;
        }
        RegisteredClient loginGateClient = RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(CLIENT_ID)
                .clientSecret("{noop}" + CLIENT_SECRET)
                .clientName("Login Gate Integration Test Client")
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(REDIRECT_URI)
                .scope("openid")
                .clientSettings(ClientSettings.builder()
                        .requireProofKey(true)
                        .requireAuthorizationConsent(false)
                        .build())
                .build();
        registeredClientRepository.save(loginGateClient);
    }

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
        return "http://127.0.0.1:" + port;
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

    private String jwtClaimsAsJson(String accessToken) {
        String[] parts = accessToken.split("\\.");
        assertThat(parts).hasSize(3);
        return new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
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
        return UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
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

    private record GateResult(String accessToken, String refreshToken, String tokenType) {
    }
}
