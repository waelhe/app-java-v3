package com.marketplace.app.graphql;

import com.icegreen.greenmail.util.GreenMail;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.graphql.test.tester.HttpGraphQlTester;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import test.config.IntegrationContainers;

import jakarta.mail.internet.MimeMessage;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * B11 (ORD-03 — the 2026-11-03 adversarial review, review-pr-811): the
 * end-to-end proof of the account-lifecycle mail legs — the two journeys
 * whose events carried NO consumer since their landing
 * ({@code AccountDeletionRequestedEvent} and
 * {@code EmailChangeRequestedEvent}), so the one-time rights never
 * reached a mailbox and both journeys froze at the token issue.
 *
 * <p>The journeys run through the REAL stack end to end: the REST
 * registration/verification legs build the real auth stores (bcrypt +
 * the users projection), the GraphQL request/redemption legs ride the
 * real DGS wiring and the real identity owner, the mail legs ride the
 * real EmailService against GreenMail (the JVM-wide shared test SMTP),
 * and the redemption asserts the durable effect on the account.</p>
 *
 * <p><b>The recipient law the email-change leg pins:</b> the change
 * confirmation mails the NEW address (the pinned Saleor behavior —
 * possession of the one-time token is the proof the new address works),
 * not the account's current record. The deletion confirmation mails the
 * account's own current address (the erasure right belongs to the
 * account holder).</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AccountLifecycleMailLegIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource"}) // Lifecycle managed by @Testcontainers; connection details via RedisContainerConnectionDetailsFactory.
    static GenericContainer<?> redis = IntegrationContainers.redis();

    /**
     * The shared GreenMail (test.config.GreenMailTestAutoConfiguration) — the
     * JVM-wide test SMTP on the test profile's own binding; a fresh mailbox
     * per leg via {@link GreenMail#purgeEmailFromAllMailboxes()} at setup,
     * the established idiom.
     */
    @Autowired
    private GreenMail greenMail;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @LocalServerPort
    private int port;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /** The AuthActionToken wire shape — 32 random bytes, Base64url, unpadded (43 chars). */
    private static final Pattern ONE_TIME_TOKEN = Pattern.compile("[A-Za-z0-9_-]{43}");

    @BeforeEach
    void purgeMailboxes() {
        greenMail.purgeEmailFromAllMailboxes();
    }

    @Test
    void theDeletionJourneyDeliversItsMailLegAndCompletes() throws Exception {
        String email = uniqueEmail("b11-delete");
        registerVerifiedAccount(email);

        HttpGraphQlTester self = testerFor(email, "CONSUMER");
        self.document("""
                        mutation { accountRequestDeletion(channel: null,
                                redirectUrl: "https://app.example.com/gone") {
                            errors { code }
                        } }
                        """)
                .execute()
                .errors().verify()
                .path("accountRequestDeletion.errors").entityList(Object.class).hasSize(0);

        // The mail leg — the previously-dead consumer now delivers the
        // one-time erasure right to the account's own mailbox.
        String token = tokenFromLatestMail("Confirm Your Account Deletion", email);

        self.document("""
                        mutation { accountDelete(token: "%s") {
                            errors { code }
                        } }
                        """.formatted(token))
                .execute()
                .errors().verify()
                .path("accountDelete.errors").entityList(Object.class).hasSize(0);

        // The durable effect: the account's identity row is erased (the
        // self-deletion journey's own end state).
        Integer remaining = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE subject = ? AND email IS NOT NULL",
                Integer.class, email);
        assertThat(remaining).as("the erased account's email is gone").isZero();
    }

    @Test
    void theEmailChangeJourneyDeliversItsMailLegToTheNewAddressAndCompletes() throws Exception {
        String email = uniqueEmail("b11-change");
        String newEmail = uniqueEmail("b11-change-next");
        registerVerifiedAccount(email);

        HttpGraphQlTester self = testerFor(email, "CONSUMER");
        self.document("""
                        mutation { requestEmailChange(newEmail: "%s",
                                password: "%s",
                                redirectUrl: "https://app.example.com/mail") {
                            errors { code }
                        } }
                        """.formatted(newEmail, HOUSE_PASSWORD))
                .execute()
                .errors().verify()
                .path("requestEmailChange.errors").entityList(Object.class).hasSize(0);

        // The recipient law: the confirmation mails the NEW address —
        // possession of the token proves the new address works.
        String token = tokenFromLatestMail("Confirm Your Email Change", newEmail);

        self.document("""
                        mutation { confirmEmailChange(channel: null, token: "%s") {
                            user { email }
                            errors { code }
                        } }
                        """.formatted(token))
                .execute()
                .errors().verify()
                .path("confirmEmailChange.errors").entityList(Object.class).hasSize(0);

        // The durable effect: the login identity moved to the new address.
        Integer moved = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE subject = ?",
                Integer.class, email);
        Integer atNewAddress = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM users WHERE subject = ? AND email = ?",
                Integer.class, email, newEmail);
        assertThat(moved).as("the account row survives the move").isEqualTo(1);
        assertThat(atNewAddress).as("the account's email moved to the new address").isEqualTo(1);
    }

    // ------------------------------------------------------------------
    // the harness
    // ------------------------------------------------------------------

    private static String uniqueEmail(String prefix) {
        return prefix + "-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";
    }

    /** Every leg registers its own account with the same house password. */
    private static final String HOUSE_PASSWORD = "b11-Mail-Journey-Passw0rd!";

    /**
     * The real registration + verification legs over REST — the same
     * setup the AccountMailJourney harness rides: a real bcrypt row in
     * the auth store, a real users projection row, and the welcome mail's
     * one-time verification link lifted before any mutation runs.
     */
    private void registerVerifiedAccount(String email) throws Exception {
        HttpResponse<String> registered = postJson("/api/v1/auth/register", """
                {"email": "%s", "password": "%s", "displayName": "B11 Mail Case"}
                """.formatted(email, HOUSE_PASSWORD));
        assertThat(registered.statusCode()).as("setup registration: %s", registered.body())
                .isEqualTo(201);
        String verificationToken = tokenFromLatestMail(
                "Welcome to Marketplace — verify your email", email);
        HttpResponse<String> verified = postJson("/api/v1/auth/email-verification/complete", """
                {"token": "%s"}
                """.formatted(verificationToken));
        assertThat(verified.statusCode()).as("setup verification: %s", verified.body())
                .isEqualTo(204);
    }

    private HttpResponse<String> postJson(String path, String json) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * The authenticated GraphQL tester — the JWT carries the subject and
     * the roles claim exactly as the production converter reads them.
     */
    private HttpGraphQlTester testerFor(String subject, String role) {
        Jwt jwt = Jwt.withTokenValue("test-token")
                .header("alg", "RS256")
                .claim("sub", subject)
                .claim("aud", List.of("marketplace-api"))
                .claim("roles", List.of(role))
                .build();
        when(jwtDecoder.decode(anyString())).thenReturn(jwt);
        return HttpGraphQlTester.builder(WebTestClient.bindToServer()
                        .baseUrl("http://localhost:" + port + "/graphql")
                        .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-token"))
                .build();
    }

    /**
     * Extracts the one-time token from the LATEST mail with the given
     * (subject, recipient) — the house polling pattern (the measured CI
     * lesson: GreenMail's {@code waitForIncomingEmail} counts the WHOLE
     * mailbox, so the wait must poll for the named mail itself).
     */
    private String tokenFromLatestMail(String subject, String recipient) {
        MimeMessage mail = awaitMail(subject, recipient, Duration.ofSeconds(10));
        assertThat(mail).as("a mail with subject '%s' for %s", subject, recipient).isNotNull();
        try {
            String content = mail.getContent().toString();
            Matcher matcher = ONE_TIME_TOKEN.matcher(content);
            assertThat(matcher.find()).as("the mail body carries the one-time token").isTrue();
            return matcher.group(0);
        } catch (Exception ex) {
            throw new IllegalStateException("mail body read failed", ex);
        }
    }

    private MimeMessage awaitMail(String subject, String recipient, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            for (MimeMessage message : greenMail.getReceivedMessages()) {
                try {
                    if (subject.equals(message.getSubject())
                            && message.getRecipients(jakarta.mail.Message.RecipientType.TO) != null
                            && java.util.Arrays.stream(message.getRecipients(jakarta.mail.Message.RecipientType.TO))
                                    .anyMatch(address -> recipient.equalsIgnoreCase(address.toString()))) {
                        return message;
                    }
                } catch (Exception ignored) {
                    // a partially-formed mail during GreenMail's write — the poll retries
                }
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        return null;
    }
}
