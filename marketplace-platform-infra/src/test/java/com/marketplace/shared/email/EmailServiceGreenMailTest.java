package com.marketplace.shared.email;

import com.icegreen.greenmail.store.FolderException;
import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exception #16 proof test (community adoption wave, owner's word
 * 2026-10-07): the REAL {@link EmailService} chain against a REAL SMTP
 * server — GreenMail in-JVM (official JUnit 5 extension recipe,
 * greenmail-mail-test.github.io) — instead of the mock-based unit test
 * that only proves wiring.
 *
 * <p>Officially grounded on both sides:
 * <ul>
 *   <li>Spring Boot: the exact auto-configurations the service relies on —
 *       {@code MailSenderAutoConfiguration} (docs.spring.io/spring-boot
 *       reference/io/email.html — the JavaMailSender bean, activated by
 *       spring.mail.host) and {@code ThymeleafAutoConfiguration} (the
 *       TemplateEngine bean, reference/io/email.html#templating) — imported
 *       into a minimal context: no database, no web, no containers.</li>
 *   <li>GreenMail: manual {@code new GreenMail(ServerSetupTest.SMTP.dynamicPort())}
 *       started in a static initializer — the documented race-free manual
 *       setup (the JUnit 5 extension starts the server per-test, AFTER the
 *       Spring context resolves {@code @DynamicPropertySource} suppliers —
 *       measured on greenmail 2.1.14) — with {@code @DynamicPropertySource}
 *       pointing spring.mail at the started server.</li>
 * </ul>
 *
 * <p>The chain exercised is end-to-end for the mail slice:
 * JavaMailSender auto-config → Thymeleaf processes the REAL
 * {@code templates/email/password-reset.html} (the A-04 journey's
 * template) → {@code MimeMessageHelper} → SMTP wire → GreenMail's
 * received-messages assert. Arabic content (the recipient's name) rides
 * the real UTF-8 MIME path.
 */
@SpringBootTest(
        classes = {
                MailSenderAutoConfiguration.class,
                ThymeleafAutoConfiguration.class,
                EmailService.class
        },
        webEnvironment = SpringBootTest.WebEnvironment.NONE)
class EmailServiceGreenMailTest {

    /** Started at class-load: BEFORE any JUnit/ Spring lifecycle — race-free. */
    private static final GreenMail greenMail =
            new GreenMail(ServerSetupTest.SMTP.dynamicPort());

    static {
        greenMail.start();
    }

    @AfterAll
    static void stopMailServer() {
        greenMail.stop();
    }

    /**
     * The static server spans all tests — purge the mailboxes between them
     * WITHOUT restarting the server ({@code reset()} reboots it, measured:
     * the cached Spring context keeps pointing at the original port while
     * the rebooted server moves — every send then fails). Purging keeps
     * the server bound; only the stored messages are dropped.
     */
    @BeforeEach
    void cleanMailboxes() throws FolderException {
        greenMail.purgeEmailFromAllMailboxes();
    }

    @DynamicPropertySource
    static void mailProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.mail.host", () -> ServerSetupTest.SMTP.getBindAddress());
        registry.add("spring.mail.port", () -> greenMail.getSmtp().getPort());
    }

    @Autowired
    private EmailService emailService;

    @Test
    void sendsTheRealPasswordResetTemplateThroughRealSmtp() throws Exception {
        String recipient = "user@example.com";
        String resetLink = "https://app.example.com/reset?token=proof-token-1";

        emailService.send(recipient, "إعادة تعيين كلمة المرور",
                "email/password-reset",
                Map.of("name", "أحمد", "resetLink", resetLink,
                       "expirationMinutes", 30));

        MimeMessage[] received = greenMail.getReceivedMessages();
        assertThat(received).hasSize(1);
        assertThat(received[0].getAllRecipients()).hasSize(1);
        assertThat(received[0].getAllRecipients()[0].toString())
                .isEqualTo(recipient);
        assertThat(received[0].getSubject())
                .isEqualTo("إعادة تعيين كلمة المرور");
        String body = received[0].getContent().toString();
        assertThat(body).contains(resetLink);
        assertThat(body).contains("أحمد");
    }

    @Test
    void twoSendsArriveAsTwoMessages() {
        emailService.send("a@example.com", "Subject A", "email/password-reset",
                Map.of("name", "A", "resetLink", "https://x/a",
                       "expirationMinutes", 30));
        emailService.send("b@example.com", "Subject B", "email/password-reset",
                Map.of("name", "B", "resetLink", "https://x/b",
                       "expirationMinutes", 30));

        assertThat(greenMail.getReceivedMessages()).hasSize(2);
    }
}
