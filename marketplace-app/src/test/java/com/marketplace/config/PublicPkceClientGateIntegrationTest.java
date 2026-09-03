package com.marketplace.config;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.security.provisioning.UserDetailsManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Real end-to-end gate for the <b>public (secret-less) client</b> bootstrapped by
 * {@code OAuth2PublicClientInitializer} — gate B, pattern (3) "native application" of
 * the client hosting strategy plan: authorization request (code + PKCE, custom-scheme
 * redirect URI) &rarr; form login &rarr; consent &rarr; authorization code &rarr;
 * <b>token exchange without client authentication</b> (client_id form parameter, no
 * secret — the client is registered with {@code client_authentication_method: none})
 * &rarr; Bearer access token &rarr; protected API.
 *
 * <p>Proves the official public-client constraints live (not by doc-trust):
 * <ul>
 *   <li>the flow works with a custom-scheme redirect URI (RFC 8252 native app);</li>
 *   <li><b>no refresh token is issued</b> — "Spring Authorization Server will not issue
 *       refresh tokens for a public client" (SAS how-to how-to-pkce, gh-297);</li>
 *   <li>an authorization request <b>without code_challenge is rejected</b> — "Public
 *       clients MUST use PKCE" (RFC 9700 §2.1.1) and "The requireProofKey setting is
 *       important to prevent the PKCE Downgrade Attack" (SAS how-to);</li>
 *   <li>client-secret authentication is <b>rejected</b> for a client registered with
 *       method {@code none};</li>
 *   <li>the issued access token (roles + aud customization, fixed resource-server
 *       audience) passes the real decoder and role gate exactly like the confidential
 *       client's tokens — the second client needs zero changes in the security
 *       chains (plan §5).</li>
 * </ul>
 *
 * <p>Fixtures follow the official wiring of {@code AuthorizationServerLoginGateIntegrationTest}:
 * the schema is the application's own {@code V13__authorization_security.sql} applied
 * through {@code spring.sql.init} (mirroring production, where Flyway migrates before
 * the beans are constructed), and the client row comes exclusively from the real
 * {@code OAuth2PublicClientInitializer} ApplicationRunner driven by
 * {@code @TestPropertySource}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.sql.init.mode=always",
        "spring.sql.init.schema-locations=classpath:db/migration/V13__authorization_security.sql",
        "marketplace.security.oauth2.public-client.client-id=marketplace-public-client",
        "marketplace.security.oauth2.public-client.redirect-uris=com.marketplace.test:/oauth2/callback"
})
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PublicPkceClientGateIntegrationTest {

    private static final String ADMIN_USERNAME = "it-public-gate-admin";
    private static final String USER_USERNAME = "it-public-gate-user";
    private static final String PASSWORD = "it-public-gate-password";

    /** The bootstrapped public client — the whole point of this gate. */
    private static final String APP_CLIENT_ID = "marketplace-public-client";
    private static final String APP_REDIRECT_URI = "com.marketplace.test:/oauth2/callback";

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

    @Autowired
    private OAuth2AuthorizationConsentService authorizationConsentService;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    @BeforeAll
    void setUpFixtures() {
        registerUser(ADMIN_USERNAME, "ADMIN");
        registerUser(USER_USERNAME, "USER");
    }

    @BeforeEach
    void resetConsentFixtures() {
        RegisteredClient publicClient = registeredClientRepository.findByClientId(APP_CLIENT_ID);
        if (publicClient != null) {
            removeConsentIfPresent(publicClient.getId(), ADMIN_USERNAME);
            removeConsentIfPresent(publicClient.getId(), USER_USERNAME);
        }
    }

    private void removeConsentIfPresent(String clientId, String principalName) {
        OAuth2AuthorizationConsent consent = authorizationConsentService.findById(clientId, principalName);
        if (consent != null) {
            authorizationConsentService.remove(consent);
        }
    }

    @Test
    void bootstrappedPublicClientKeepsOfficialDefinition() {
        RegisteredClient publicClient = registeredClientRepository.findByClientId(APP_CLIENT_ID);
        assertThat(publicClient)
                .as("the OAuth2PublicClientInitializer must have bootstrapped the public client")
                .isNotNull();

        assertThat(publicClient.getClientSecret())
                .as("a public client stores no secret")
                .isNull();
        assertThat(publicClient.getClientAuthenticationMethods())
                .containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(publicClient.getAuthorizationGrantTypes())
                .as("authorization_code only — public clients get no refresh grant (gh-297)")
                .containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(publicClient.getScopes()).containsExactlyInAnyOrder("openid", "profile");
        assertThat(publicClient.getRedirectUris())
                .containsExactly(APP_REDIRECT_URI);
        assertThat(publicClient.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(publicClient.getClientSettings().isRequireAuthorizationConsent()).isTrue();

        TokenSettings tokenSettings = publicClient.getTokenSettings();
        assertThat(tokenSettings.getSettings().get("settings.token.access-token-time-to-live"))
                .isEqualTo(Duration.ofSeconds(900));
        assertThat(tokenSettings.getSettings().get("settings.token.authorization-code-time-to-live"))
                .isEqualTo(Duration.ofSeconds(300));
        assertThat(tokenSettings.getIdTokenSignatureAlgorithm()).isNotNull();
        assertThat(tokenSettings.getAccessTokenFormat()).isNotNull();
    }

    /**
     * The full public-client gate: PKCE + custom-scheme redirect + consent, then a token
     * exchange that carries the client_id as a form parameter and <b>no</b> client
     * authentication. Asserts the gh-297 behavior live: no refresh_token in the response.
     */
    @Test
    void publicClientExchangesCodeWithoutAuthenticationAndMintsNoRefreshToken() throws Exception {
        GateResult gate = consentGate(ADMIN_USERNAME, PASSWORD);

        assertThat(gate.accessToken()).isNotBlank();
        assertThat(gate.tokenType()).isEqualToIgnoringCase("Bearer");
        assertThat(gate.refreshToken())
                .as("SAS will not issue refresh tokens for a public client (how-to-pkce, gh-297)")
                .isBlank();
        assertThat(gate.idToken())
                .as("the openid scope must yield an id_token")
                .isNotBlank();
        assertThat(gate.idToken().split("\\.")).hasSize(3);

        JsonNode claims = objectMapper.readTree(jwtClaimsAsJson(gate.accessToken()));
        assertThat(claims.path("iss").asString()).isEqualTo("http://localhost:8080");
        assertThat(claims.path("aud").toString()).contains("marketplace-api");
        assertThat(claims.path("roles").toString()).contains("ADMIN");

        JsonNode idTokenClaims = objectMapper.readTree(jwtClaimsAsJson(gate.idToken()));
        assertThat(idTokenClaims.path("iss").asString()).isEqualTo("http://localhost:8080");
        assertThat(idTokenClaims.path("aud").toString()).contains(APP_CLIENT_ID);
        assertThat(idTokenClaims.path("sub").asString()).isEqualTo(ADMIN_USERNAME);

        // The public client's access token passes the same real decoder + role gate the
        // confidential client's tokens pass — no security-chain change for client #2.
        HttpResponse<String> apiResponse = getWithBearer(PROTECTED_ADMIN_PATH, gate.accessToken());
        assertThat(apiResponse.statusCode()).isNotEqualTo(401);
        assertThat(apiResponse.statusCode()).isNotEqualTo(403);
    }

    /** Non-admin principal: same negative contract as the confidential client. */
    @Test
    void publicClientTokenForNonAdminIsRejectedWithProblemDetail403() throws Exception {
        GateResult gate = consentGate(USER_USERNAME, PASSWORD);

        HttpResponse<String> apiResponse = getWithBearer(PROTECTED_ADMIN_PATH, gate.accessToken());
        assertThat(apiResponse.statusCode()).isEqualTo(403);
        assertThat(apiResponse.headers().firstValue("Content-Type").orElse(""))
                .contains("application/problem+json");
        assertThat(apiResponse.body()).contains("Access denied");
    }

    /**
     * PKCE downgrade protection (RFC 9700 §2.1.1 / SAS how-to): an authenticated
     * authorization request without code_challenge for a requireProofKey client is
     * rejected with an error redirect to the client instead of a code.
     */
    @Test
    void authorizeWithoutCodeChallengeIsRejected() throws Exception {
        String sessionCookie = loginSession(ADMIN_USERNAME, PASSWORD);

        String authorizeUrl = baseUrl() + AUTHORIZE_PATH
                + "?response_type=code"
                + "&client_id=" + APP_CLIENT_ID
                + "&scope=openid"
                + "&state=" + UUID.randomUUID()
                + "&redirect_uri=" + encode(APP_REDIRECT_URI);

        HttpResponse<String> authorize = get(authorizeUrl, sessionCookie);
        assertThat(authorize.statusCode())
                .as("missing code_challenge must be rejected, got: %s", body(authorize))
                .isEqualTo(302);
        String location = authorize.headers().firstValue("Location").orElse("");
        assertThat(location)
                .as("the rejection must be an error redirect to the client redirect URI")
                .startsWith(APP_REDIRECT_URI)
                .contains("error=");
    }

    /** A public client cannot authenticate with a secret: Basic auth is rejected (401). */
    @Test
    void tokenEndpointRejectsClientSecretAuthenticationForPublicClient() throws Exception {
        HttpResponse<String> response = postFormWithBasicAuth(TOKEN_PATH,
                APP_CLIENT_ID, "not-a-secret", "grant_type=client_credentials");

        assertThat(response.statusCode())
                .as("client-secret authentication must fail for method none: %s", body(response))
                .isEqualTo(401);
        assertThat(response.body()).contains("invalid_client");
    }

    /**
     * No refresh grant exists for the public client — SAS 7.1.1 rejects the request
     * <em>before</em> the grant is even evaluated, through the official client
     * authentication path: {@code PublicClientAuthenticationConverter} only matches a
     * PKCE token request ({@code grant_type=authorization_code} + {@code code} +
     * {@code code_verifier} — {@code OAuth2EndpointUtils#matchesPkceTokenRequest}), so a
     * {@code refresh_token} grant request — which carries no code_verifier by protocol —
     * leaves the client unauthenticated. The endpoint filters run after the chain's
     * {@code anyRequest().authenticated()} {@code AuthorizationFilter}, which denies the
     * anonymous request, and the authorization-server entry point registered for the
     * OAuth2 endpoints ({@code HttpStatusEntryPoint(UNAUTHORIZED)}, per
     * {@code OAuth2AuthorizationServerConfigurer}) answers with a bare {@code 401} and an
     * empty body. That is the framework enforcing "will not issue refresh tokens for a
     * public client" (SAS how-to how-to-pkce, gh-297) at the authentication layer.
     */
    @Test
    void refreshTokenGrantIsUnavailableForPublicClient() throws Exception {
        HttpResponse<String> response = postFormNoAuth(TOKEN_PATH,
                "grant_type=refresh_token&refresh_token=fake&client_id=" + APP_CLIENT_ID);

        assertThat(response.statusCode())
                .as("refresh_token grant must be unavailable: %s", body(response))
                .isEqualTo(401);
        assertThat(response.body())
                .as("HttpStatusEntryPoint writes no body for the OAuth2 endpoints")
                .isEmpty();
    }

    // ---------- the live gate (mirrors AuthorizationServerLoginGateIntegrationTest) ----------

    /**
     * Runs the browser-less gate against the bootstrapped public client: authorize
     * (code + PKCE) &rarr; login &rarr; consent (required) &rarr; code &rarr; token
     * exchange with client_id as a form parameter and no client authentication.
     */
    private GateResult consentGate(String username, String password) throws Exception {
        RegisteredClient publicClient = registeredClientRepository.findByClientId(APP_CLIENT_ID);
        assertThat(publicClient)
                .as("the OAuth2PublicClientInitializer must have bootstrapped the public client")
                .isNotNull();
        assertThat(publicClient.getClientSettings().isRequireAuthorizationConsent())
                .as("public client must require consent (requireAuthorizationConsent=true)")
                .isTrue();

        String state = UUID.randomUUID().toString();
        String codeVerifier = randomCodeVerifier();
        String codeChallenge = base64Url(sha256(codeVerifier));

        String authorizeUrl = baseUrl() + AUTHORIZE_PATH
                + "?response_type=code"
                + "&client_id=" + APP_CLIENT_ID
                + "&scope=openid%20profile"
                + "&state=" + state
                + "&redirect_uri=" + encode(APP_REDIRECT_URI)
                + "&code_challenge=" + codeChallenge
                + "&code_challenge_method=S256";

        HttpResponse<String> authorizeFirst = get(authorizeUrl, null);
        assertThat(authorizeFirst.statusCode()).as("authorize -> login: %s", body(authorizeFirst)).isEqualTo(302);
        String sessionCookie = sessionCookie(authorizeFirst);

        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, sessionCookie);
        String csrfToken = csrfTokenFrom(loginPage.body());
        assertThat(csrfToken).as("login CSRF").isNotBlank();
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);

        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + password + "&_csrf=" + encode(csrfToken), sessionCookie);
        assertThat(loginPost.statusCode()).as("login: %s", body(loginPost)).isEqualTo(302);
        String savedRequest = loginPost.headers().firstValue("Location").orElse("");
        sessionCookie = latestSessionCookie(loginPost, sessionCookie);

        HttpResponse<String> authorizeSecond = get(absolute(savedRequest), sessionCookie);
        String authorizationCode;
        if (authorizeSecond.statusCode() == 302 && authorizeSecond.headers().firstValue("Location").orElse("")
                .contains("code=")) {
            authorizationCode = queryParam(authorizeSecond.headers().firstValue("Location").orElse(""), "code");
        } else {
            assertThat(authorizeSecond.statusCode())
                    .as("authorize authenticated must render the consent page, got: %s", body(authorizeSecond))
                    .isEqualTo(200);
            assertThat(authorizeSecond.body())
                    .as("consent page body: %s", body(authorizeSecond))
                    .contains("Consent required");

            String consentState = attributesFromConsentPage(authorizeSecond.body()).get("state");
            String consentClientId = attributesFromConsentPage(authorizeSecond.body()).get("client_id");
            assertThat(consentState).as("consent page hidden state").isNotBlank();
            assertThat(consentClientId).as("consent page hidden client_id").isEqualTo(APP_CLIENT_ID);

            HttpResponse<String> consentPost = postForm(AUTHORIZE_PATH,
                    "client_id=" + encode(consentClientId)
                            + "&state=" + encode(consentState)
                            + "&scope=openid"
                            + "&scope=profile",
                    sessionCookie);
            assertThat(consentPost.statusCode()).as("consent submit: %s", body(consentPost)).isEqualTo(302);
            String redirect = consentPost.headers().firstValue("Location").orElse("");
            assertThat(redirect).startsWith(APP_REDIRECT_URI).contains("code=");
            authorizationCode = queryParam(redirect, "code");
        }

        // Public client: no Authorization header — client_id travels as a form parameter.
        HttpResponse<String> tokenResponse = postFormNoAuth(TOKEN_PATH,
                "grant_type=authorization_code"
                        + "&code=" + encode(authorizationCode)
                        + "&redirect_uri=" + encode(APP_REDIRECT_URI)
                        + "&code_verifier=" + codeVerifier
                        + "&client_id=" + APP_CLIENT_ID);
        assertThat(tokenResponse.statusCode()).as("token endpoint: %s", body(tokenResponse)).isEqualTo(200);

        JsonNode tokens = objectMapper.readTree(tokenResponse.body());
        return new GateResult(
                tokens.path("access_token").asString(),
                tokens.path("refresh_token").asString(),
                tokens.path("token_type").asString(),
                tokens.path("id_token").asString());
    }

    /** Login-only helper (no authorize round-trip) for the negative authorize test. */
    private String loginSession(String username, String password) throws Exception {
        HttpResponse<String> loginPage = get(baseUrl() + LOGIN_PATH, null);
        String csrfToken = csrfTokenFrom(loginPage.body());
        assertThat(csrfToken).as("login CSRF").isNotBlank();
        String sessionCookie = latestSessionCookie(loginPage, null);

        HttpResponse<String> loginPost = postForm(LOGIN_PATH,
                "username=" + username + "&password=" + password + "&_csrf=" + encode(csrfToken), sessionCookie);
        assertThat(loginPost.statusCode()).as("login: %s", body(loginPost)).isEqualTo(302);
        return latestSessionCookie(loginPost, sessionCookie);
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

    private Map<String, String> attributesFromConsentPage(String pageBody) {
        Map<String, String> values = new java.util.HashMap<>();
        Matcher matcher = Pattern.compile("<input[^>]*type=\"hidden\"[^>]*name=\"([^\"]+)\"[^>]*value=\"([^\"]+)\"")
                .matcher(pageBody);
        while (matcher.find()) {
            values.put(matcher.group(1), matcher.group(2));
        }
        return values;
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

    /** Token request without any client authentication (public client path). */
    private HttpResponse<String> postFormNoAuth(String path, String form) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl() + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postFormWithBasicAuth(String path, String clientId, String clientSecret,
                                                       String form) throws Exception {
        String credentials = Base64.getEncoder()
                .encodeToString((clientId + ":" + clientSecret).getBytes(StandardCharsets.UTF_8));
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

    private record GateResult(String accessToken, String refreshToken, String tokenType, String idToken) {
    }
}
