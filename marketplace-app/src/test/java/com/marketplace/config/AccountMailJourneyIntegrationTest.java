package com.marketplace.config;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A-04 (official-compliance plan §6, wave A — A.1+A.2): the FULL-JOURNEY IT,
 * the unit's own declared gate ("IT كامل للرحلة"). Every leg runs through
 * the REAL chains on the REAL Flyway schema — no service is mocked:
 * <ol>
 *   <li><b>The A.2 birth journey:</b> registration holds the account, the
 *       welcome mail carries the one-time verification link, redemption
 *       lifts the hold, and the SAME PKCE login gate that refused the held
 *       account now mints a real token.</li>
 *   <li><b>The A.1 reset journey:</b> the enumeration-safe request, the
 *       reset mail with the deep link, redemption through the official
 *       manager — and the secret's rotation proven at the gate itself: the
 *       OLD password stops working, the NEW one walks the full five-step
 *       flow.</li>
 *   <li><b>The honest negatives:</b> an unknown address answers the same
 *       202 with NO mail; a spent token answers the single-use 400; the
 *       wrong-purpose token is refused; a weak replacement answers the
 *       clean validation 400.</li>
 * </ol>
 *
 * <p><b>The mail leg is real end-to-end:</b> the test profile's standing
 * {@code spring.mail.host=localhost, port=3025} binding (which predates this
 * unit and anticipated exactly this adoption) meets GreenMail's JUnit 5
 * extension on {@link ServerSetupTest#SMTP} — the SAME port 3025. Boot's
 * {@code JavaMailSender} auto-configuration activates (the measured
 * {@code reference/io/email.html} stack), the AFTER_COMMIT
 * {@code @ApplicationModuleListener} dispatch renders the Thymeleaf template
 * through the platform-infra {@code EmailService}, and GreenMail's own
 * {@code waitForIncomingEmail} — the official GreenMail wait API — is the
 * asynchronous rendezvous: no sleeps, no flaky margins.</p>
 *
 * <p><b>Test-only cadence:</b> the per-account re-issue floor
 * ({@code marketplace.identity.min-issue-interval}, the OWASP flood wall) is
 * bound to zero HERE so the resend/re-request journeys are exercisable
 * without sleeping past production's 60 seconds — the wall itself is proven
 * at its default cadence by the unit guards (fixed clock, no CI time spent).
 * The token-expiry wall is likewise unit-proven (the same fixed-clock
 * seam); this IT owns the LIVE journey facts.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@TestPropertySource(properties = {
        // The login gate's client fixtures (the L23/RegistrationIT pattern
        // verbatim — the standing initializer bootstraps the client from env).
        "marketplace.security.oauth2.client.client-id=marketplace-web-client",
        "marketplace.security.oauth2.client.secret=it-app-secret",
        "marketplace.security.oauth2.client.redirect-uris=http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client",
        // The per-account re-issue floor at zero — the cadence seam above.
        "marketplace.identity.min-issue-interval=0s",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AccountMailJourneyIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    /** GreenMail's SMTP test setup — port 3025, the test profile's own mail binding. */
    @RegisterExtension
    static GreenMailExtension greenMail = new GreenMailExtension(ServerSetupTest.SMTP);

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${local.server.port}")
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private static final Pattern TOKEN_IN_LINK = Pattern.compile("token=([A-Za-z0-9_-]+)");

    /** One address per test method — within the login store's 50-char domain. */
    private String uniqueEmail() {
        return "a04-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    @BeforeEach
    void purgeMailboxes() throws Exception {
        greenMail.purgeEmailFromAllMailboxes();
    }

    // ---- The A.2 birth journey ------------------------------------------------

    @Test
    void theHeldAccountWaitsForItsMailAndTheVerificationLiftsTheHold() throws Exception {
        String email = uniqueEmail();
        String password = "a04-valid-password";

        // (1) The birth: 201 with the profile — and the account is HELD.
        HttpResponse<String> registered = postJson("/api/v1/auth/register", """
                {"email": "%s", "password": "%s", "displayName": "A04 Member"}
                """.formatted(email, password));
        assertThat(registered.statusCode()).as("registration: %s", body(registered)).isEqualTo(201);

        // (2) The hold is real: the login gate REFUSES the held account
        // (DaoAuthenticationProvider's DisabledException — the framework's
        // own answer, the honest "the account exists but is not usable
        // yet").
        assertThat(loginGateFailsWith(email, password))
                .as("the held account must not pass the login gate").isTrue();

        // (3) The welcome mail arrives through the REAL stack and carries
        // the one-time verification deep link.
        String verificationToken = tokenFromLatestMail(
                "Welcome to Marketplace — verify your email", email);
        assertThat(verificationToken).as("the verification link's token").isNotBlank();

        // (4) The redemption: 204, and ONLY the token — no address the
        // caller could have invented.
        HttpResponse<String> verified = postJson("/api/v1/auth/email-verification/complete", """
                {"token": "%s"}
                """.formatted(verificationToken));
        assertThat(verified.statusCode()).as("verification: %s", body(verified)).isEqualTo(204);

        // (5) The hold is lifted: the SAME credentials now walk the full
        // five-step PKCE gate and mint a real token — and /users/me
        // answers the registered profile.
        String accessToken = loginGateAccessToken(email, password);
        assertThat(accessToken).as("the verified account is loginable").isNotBlank();
        HttpResponse<String> me = getJson("/api/v1/users/me", accessToken);
        assertThat(me.statusCode()).as("the minted token calls the API: %s", body(me)).isEqualTo(200);
        assertThat(objectMapper.readTree(me.body()).path("email").asString()).isEqualTo(email);
    }

    @Test
    void aResendForAPendingAccountDeliversAFreshMailWithAFreshToken() throws Exception {
        String email = uniqueEmail();

        postJson("/api/v1/auth/register", """
                {"email": "%s", "password": "a04-valid-password", "displayName": "Resend Case"}
                """.formatted(email));
        String firstToken = tokenFromLatestMail(
                "Welcome to Marketplace — verify your email", email);

        // The resend surface (the test-only zero floor makes the cadence
        // immediate): a PENDING account gets a fresh right by mail.
        HttpResponse<String> resent = postJson("/api/v1/auth/email-verification/resend", """
                {"email": "%s"}
                """.formatted(email));
        assertThat(resent.statusCode()).as("resend: %s", body(resent)).isEqualTo(202);

        String freshToken = tokenFromLatestMail(
                "Welcome to Marketplace — verify your email", email);
        assertThat(freshToken)
                .as("the resent mail carries a FRESH one-time token (the V112 single-flight)")
                .isNotBlank()
                .isNotEqualTo(firstToken);

        // The fresh right redeems; the superseded one is dead.
        HttpResponse<String> completed = postJson("/api/v1/auth/email-verification/complete", """
                {"token": "%s"}
                """.formatted(freshToken));
        assertThat(completed.statusCode()).isEqualTo(204);
        HttpResponse<String> spentFirst = postJson("/api/v1/auth/email-verification/complete", """
                {"token": "%s"}
                """.formatted(firstToken));
        assertThat(spentFirst.statusCode())
                .as("the replaced token answers the single-use 400").isEqualTo(400);
    }

    // ---- The A.1 reset journey ------------------------------------------------

    @Test
    void theResetJourneyRotatesTheSecretEndToEndAtTheLoginGateItself() throws Exception {
        String email = uniqueEmail();
        String oldPassword = "a04-old-password";
        String newPassword = "a04-new-password";
        registerVerifiedAccount(email, oldPassword);

        // The account starts loginable — the journey's honest baseline.
        assertThat(loginGateAccessToken(email, oldPassword)).isNotBlank();

        // (1) The enumeration-safe request: 202, and the reset mail arrives.
        HttpResponse<String> requested = postJson("/api/v1/auth/password-reset/request", """
                {"email": "%s"}
                """.formatted(email));
        assertThat(requested.statusCode()).as("reset request: %s", body(requested)).isEqualTo(202);
        String resetToken = tokenFromLatestMail("Reset Your Password", email);
        assertThat(resetToken).as("the reset link's token").isNotBlank();

        // (2) The redemption: the NEW secret under the SAME policy.
        HttpResponse<String> completed = postJson("/api/v1/auth/password-reset/complete", """
                {"token": "%s", "newPassword": "%s"}
                """.formatted(resetToken, newPassword));
        assertThat(completed.statusCode()).as("reset completion: %s", body(completed)).isEqualTo(204);

        // (3) The rotation is proven at the gate itself: the OLD password
        // stops working, the NEW one walks the full five-step flow.
        assertThat(loginGateFailsWith(email, oldPassword))
                .as("the old password is dead after the rotation").isTrue();
        assertThat(loginGateAccessToken(email, newPassword))
                .as("the new password is loginable").isNotBlank();

        // (4) Single use: the spent token answers the honest 400.
        HttpResponse<String> spent = postJson("/api/v1/auth/password-reset/complete", """
                {"token": "%s", "newPassword": "a04-another-password"}
                """.formatted(resetToken));
        assertThat(spent.statusCode()).as("the spent token answers 400").isEqualTo(400);
    }

    // ---- The honest negatives --------------------------------------------------

    @Test
    void anUnknownAddressAnswersTheSame202AndMailsNothing() throws Exception {
        HttpResponse<String> requested = postJson("/api/v1/auth/password-reset/request", """
                {"email": "nobody-owns-this@example.com"}
                """);
        // The enumeration wall: the same 202 the real account got — and
        // GreenMail proves the silence (no mail was ever sent).
        assertThat(requested.statusCode()).isEqualTo(202);
        assertThat(greenMail.getReceivedMessages())
                .as("the unknown address triggers no mail").isEmpty();

        HttpResponse<String> resent = postJson("/api/v1/auth/email-verification/resend", """
                {"email": "nobody-owns-this@example.com"}
                """);
        assertThat(resent.statusCode()).isEqualTo(202);
        assertThat(greenMail.getReceivedMessages()).isEmpty();
    }

    @Test
    void theWrongPurposeTokenIsRefused() throws Exception {
        String email = uniqueEmail();
        registerVerifiedAccount(email, "a04-valid-password");
        postJson("/api/v1/auth/password-reset/request", """
                {"email": "%s"}
                """.formatted(email));
        String resetToken = tokenFromLatestMail("Reset Your Password", email);

        // A verification redemption demands a verification right — the
        // reset token's hash has no EMAIL_VERIFICATION row.
        HttpResponse<String> wrongPurpose = postJson("/api/v1/auth/email-verification/complete", """
                {"token": "%s"}
                """.formatted(resetToken));
        assertThat(wrongPurpose.statusCode()).isEqualTo(400);
    }

    @Test
    void aWeakReplacementPasswordAnswersTheCleanValidation400() throws Exception {
        String email = uniqueEmail();
        registerVerifiedAccount(email, "a04-valid-password");
        postJson("/api/v1/auth/password-reset/request", """
                {"email": "%s"}
                """.formatted(email));
        String resetToken = tokenFromLatestMail("Reset Your Password", email);

        HttpResponse<String> weak = postJson("/api/v1/auth/password-reset/complete", """
                {"token": "%s", "newPassword": "short"}
                """.formatted(resetToken));
        // The SAME policy register pins (8..72, the bcrypt byte ceiling) —
        // the request layer's own clean 400, never a storage-time 500.
        assertThat(weak.statusCode()).as("weak replacement: %s", body(weak)).isEqualTo(400);
    }

    // ---- Helpers ---------------------------------------------------------------

    /** Registers AND verifies an account — the reset journey's honest baseline. */
    private void registerVerifiedAccount(String email, String password) throws Exception {
        HttpResponse<String> registered = postJson("/api/v1/auth/register", """
                {"email": "%s", "password": "%s", "displayName": "Reset Case"}
                """.formatted(email, password));
        assertThat(registered.statusCode()).as("setup registration: %s", body(registered))
                .isEqualTo(201);
        String verificationToken = tokenFromLatestMail(
                "Welcome to Marketplace — verify your email", email);
        HttpResponse<String> verified = postJson("/api/v1/auth/email-verification/complete", """
                {"token": "%s"}
                """.formatted(verificationToken));
        assertThat(verified.statusCode()).as("setup verification: %s", body(verified))
                .isEqualTo(204);
    }

    /** Extracts the one-time token from the LATEST mail with the given subject. */
    private String tokenFromLatestMail(String subject, String recipient) {
        assertThat(greenMail.waitForIncomingEmail(10_000, 1))
                .as("the mail must arrive through the real stack").isTrue();
        MimeMessage latest = null;
        for (MimeMessage message : greenMail.getReceivedMessages()) {
            try {
                boolean toRecipient = jakarta.mail.internet.InternetAddress.toString(
                        message.getRecipients(jakarta.mail.Message.RecipientType.TO)).contains(recipient);
                if (toRecipient && message.getSubject().equals(subject)) {
                    latest = message;
                }
            } catch (Exception ex) {
                throw new IllegalStateException("mail introspection failed", ex);
            }
        }
        assertThat(latest).as("a mail with subject '%s' for %s", subject, recipient).isNotNull();
        try {
            String content = latest.getContent().toString();
            Matcher matcher = TOKEN_IN_LINK.matcher(content);
            assertThat(matcher.find()).as("the mail body carries the deep link").isTrue();
            return matcher.group(1);
        } catch (Exception ex) {
            throw new IllegalStateException("mail body read failed", ex);
        }
    }

    /**
     * The login gate's honest FAILURE answer: the form-login POST redirects
     * to {@code /login?error} instead of resuming the saved authorization
     * request (the RegistrationIT helper's own success criterion, inverted).
     */
    private boolean loginGateFailsWith(String username, String password) throws Exception {
        String authorizeUrl = "http://127.0.0.1:" + port + "/oauth2/authorize"
                + "?response_type=code"
                + "&client_id=marketplace-web-client"
                + "&scope=openid"
                + "&state=" + UUID.randomUUID()
                + "&redirect_uri=" + java.net.URLEncoder.encode(
                        "http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client",
                        StandardCharsets.UTF_8)
                + "&code_challenge=" + base64Url(sha256(UUID.randomUUID().toString().replace("-", "")))
                + "&code_challenge_method=S256";
        HttpResponse<String> authorizeFirst = get(authorizeUrl, null);
        if (authorizeFirst.statusCode() != 302) {
            return true;
        }
        String sessionCookie = sessionCookie(authorizeFirst);
        HttpResponse<String> loginPage = get("http://127.0.0.1:" + port + "/login", sessionCookie);
        Matcher csrf = Pattern.compile(
                "<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(loginPage.body());
        if (!csrf.find()) {
            csrf = Pattern.compile(
                    "<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"").matcher(loginPage.body());
        }
        HttpResponse<String> loginPost = postForm("/login",
                "username=" + java.net.URLEncoder.encode(username, StandardCharsets.UTF_8)
                        + "&password=" + java.net.URLEncoder.encode(password, StandardCharsets.UTF_8)
                        + "&_csrf=" + java.net.URLEncoder.encode(csrf.group(1), StandardCharsets.UTF_8),
                sessionCookie);
        String location = loginPost.headers().firstValue("Location").orElse("");
        return location.contains("/login?error") || !location.contains("/oauth2/authorize");
    }

    // -- HTTP + PKCE helpers (the RegistrationIT shapes, verbatim) ---------------

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
        assertThat(authorizeFirst.statusCode()).as("authorize should redirect to login").isEqualTo(302);
        String sessionCookie = sessionCookie(authorizeFirst);

        HttpResponse<String> loginPage = get("http://127.0.0.1:" + port + "/login", sessionCookie);
        assertThat(loginPage.statusCode()).as("login page").isEqualTo(200);
        String csrfToken = csrfTokenFrom(loginPage.body());
        sessionCookie = latestSessionCookie(loginPage, sessionCookie);

        HttpResponse<String> loginPost = postForm("/login",
                "username=" + java.net.URLEncoder.encode(username, StandardCharsets.UTF_8)
                        + "&password=" + java.net.URLEncoder.encode(password, StandardCharsets.UTF_8)
                        + "&_csrf=" + java.net.URLEncoder.encode(csrfToken, StandardCharsets.UTF_8),
                sessionCookie);
        assertThat(loginPost.statusCode()).as("login should succeed").isEqualTo(302);
        String savedRequest = loginPost.headers().firstValue("Location").orElse("");
        assertThat(savedRequest).contains("/oauth2/authorize");
        sessionCookie = latestSessionCookie(loginPost, sessionCookie);

        HttpResponse<String> authorizeSecond = get(absolute(savedRequest), sessionCookie);
        assertThat(authorizeSecond.statusCode()).as("authorize should redirect back").isEqualTo(302);
        String redirect = authorizeSecond.headers().firstValue("Location").orElse("");
        assertThat(redirect).startsWith(redirectUri);
        String code = param(redirect, "code");

        HttpRequest tokenRequest = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/oauth2/token"))
                .header("Authorization", "Basic " + java.util.Base64.getEncoder()
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

    private HttpResponse<String> get(String url, String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).GET();
        if (sessionCookie != null && !sessionCookie.isBlank()) {
            builder.header("Cookie", sessionCookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> postForm(String path, String form, String sessionCookie) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form));
        if (sessionCookie != null && !sessionCookie.isBlank()) {
            builder.header("Cookie", sessionCookie);
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private String body(HttpResponse<String> response) {
        return response.body() == null ? "" : response.body().substring(0,
                Math.min(500, response.body().length()));
    }

    private String absolute(String location) {
        return location.startsWith("http") ? location : "http://127.0.0.1:" + port + location;
    }

    private static final Pattern SESSION_COOKIE = Pattern.compile("(SESSION|JSESSIONID)=([^;]+)");

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

    private String csrfTokenFrom(String loginPageBody) {
        Matcher forward = Pattern.compile(
                "<input[^>]*name=\"_csrf\"[^>]*value=\"([^\"]+)\"").matcher(loginPageBody);
        if (forward.find()) {
            return forward.group(1);
        }
        Matcher reversed = Pattern.compile(
                "<input[^>]*value=\"([^\"]+)\"[^>]*name=\"_csrf\"").matcher(loginPageBody);
        assertThat(reversed.find()).as("the login page must carry a CSRF token").isTrue();
        return reversed.group(1);
    }

    private String param(String redirect, String name) {
        Matcher matcher = Pattern.compile("[?&]" + name + "=([^&]+)").matcher(redirect);
        assertThat(matcher.find()).as("redirect carries %s", name).isTrue();
        return matcher.group(1);
    }

    private byte[] sha256(String value) {
        try {
            return java.security.MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private String base64Url(byte[] bytes) {
        return java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
