package com.marketplace.shared.email;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

class EmailServiceTest {

    private JavaMailSender mailSender;
    private TemplateEngine templateEngine;
    private EmailService emailService;

    @BeforeEach
    void setUp() {
        mailSender = mock();
        templateEngine = mock();
        // Incident #682's root fix: the sender identity is a required,
        // validated part of the channel — the unit seam carries a fixed
        // one, mirroring the GreenMail proof test's binding.
        emailService = new EmailService(mailSender, templateEngine,
                new MailChannelProperties("Marketplace <no-reply@marketplace.test>"));
    }

    @Test
    void send_sendsEmailSuccessfully() {
        MimeMessage mimeMessage = mock();
        when(mailSender.createMimeMessage()).thenReturn(mimeMessage);
        when(templateEngine.process(eq("email/welcome"), any(Context.class))).thenReturn("<html></html>");

        emailService.send("test@example.com", "Welcome", "email/welcome", Map.of("name", "Test"));

        verify(mailSender).send(mimeMessage);
    }

    @Test
    void send_throwsEmailSendExceptionOnFailure() {
        when(mailSender.createMimeMessage()).thenThrow(new RuntimeException("Mail server down"));

        assertThatThrownBy(() -> emailService.send("test@example.com", "Welcome", "email/welcome", Map.of()))
                .isInstanceOf(EmailSendException.class)
                .hasMessageContaining("Failed to send email");
    }

    /**
     * The 2026-11-09 #807 round (Minor · Security & Privacy): the SMTP
     * client's failure message commonly embeds the full recipient
     * address — the ERROR log and the thrown exception carry only the
     * masked and scrubbed forms, never the raw one.
     */
    @Test
    void send_theFailurePathNeverCarriesTheRawRecipient() {
        when(mailSender.createMimeMessage())
                .thenThrow(new RuntimeException("550 <victim@example.com>... user unknown"));

        assertThatThrownBy(() -> emailService.send("victim@example.com", "Welcome", "email/welcome", Map.of()))
                .isInstanceOf(EmailSendException.class)
                .hasMessageContaining("v***@example.com")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("victim@example.com"));
    }

    /**
     * The 2026-11-09 #807 round (Minor · Security & Privacy): a display
     * name containing {@code '@'} used to defeat the single-index masking
     * — the "masked" value echoed everything from the name's {@code '@'}
     * onward, including the full address. The RFC 5322 parse masks the
     * angle-bracketed addr-spec alone, and the display name (caller
     * text, itself PII-adjacent) is never echoed.
     */
    @Test
    void send_aDisplayFormRecipientWithAnAtSignInTheNameIsMaskedWhole() {
        when(mailSender.createMimeMessage()).thenThrow(new RuntimeException("Mail server down"));

        assertThatThrownBy(() -> emailService.send("Sales @ Team <victim@example.com>",
                "Welcome", "email/welcome", Map.of()))
                .isInstanceOf(EmailSendException.class)
                .hasMessageContaining("v***@example.com")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("victim@example.com"))
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("Sales @ Team"));
    }

    /**
     * The 2026-11-09 #807 round-2 review (Minor · Security & Privacy):
     * the RFC 5322 QUOTED local form ({@code "private
     * mailbox"@example.com}) escaped the scrub token's atom class — the
     * {@code "} ahead of the {@code @} broke the character run, the
     * address never matched, and the full quoted address rode the
     * failure log and the exception verbatim. The quoted alternative
     * joins the atom form: both shapes reach the mask, and the masked
     * rendering hides the quoted local part whole.
     */
    @Test
    void send_theFailurePathNeverCarriesTheQuotedLocalRecipient() {
        when(mailSender.createMimeMessage())
                .thenThrow(new RuntimeException("550 <\"private mailbox\"@example.com>... user unknown"));

        assertThatThrownBy(() -> emailService.send("\"private mailbox\"@example.com",
                "Welcome", "email/welcome", Map.of()))
                .isInstanceOf(EmailSendException.class)
                .hasMessageContaining("\"***@example.com")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("private mailbox"))
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("\"private mailbox\"@example.com"));
    }

    /**
     * The 2026-11-09 #807 round-3 review (Minor · Security &amp; Privacy):
     * the SMTP failure text's parenthesized display-name tail rode the log
     * verbatim while its address masked — the tail now rides the SAME
     * replacement (the name never crosses).
     */
    @Test
    void send_theFailurePathNeverCarriesTheParenthesizedDisplayName() {
        when(mailSender.createMimeMessage())
                .thenThrow(new RuntimeException("550 victim@example.com (Jane Smith)... user unknown"));

        assertThatThrownBy(() -> emailService.send("victim@example.com", "Welcome", "email/welcome", Map.of()))
                .isInstanceOf(EmailSendException.class)
                .hasMessageContaining("v***@example.com (***)")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("Jane Smith"))
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("victim@example.com"));
    }

    /**
     * The same round: the atom and domain classes were ASCII-only — an
     * internationalized address matched nothing and rode the diagnostic
     * whole. The Unicode runs (bounded by whitespace and the address
     * delimiters) reach the mask like every other shape.
     */
    @Test
    void send_theFailurePathMasksTheInternationalizedAddress() {
        when(mailSender.createMimeMessage())
                .thenThrow(new RuntimeException("550 <مستخدم@مثال.شبكة>... user unknown"));

        assertThatThrownBy(() -> emailService.send("مستخدم@مثال.شبكة", "Welcome", "email/welcome", Map.of()))
                .isInstanceOf(EmailSendException.class)
                .satisfies(ex -> assertThat(ex.getMessage()).contains("***@مثال.شبكة"))
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("مستخدم@"));
    }

    /**
     * The 2026-11-09 #807 round-4 review (Minor · Security &amp; Privacy):
     * the ANGLE-BRACKET twin — {@code <victim@example.com> (Jane Smith)}
     * masked the address inside the brackets but the parenthesized name
     * AFTER the closing bracket rode the log verbatim. The name group now
     * skips the bracket and the name never crosses.
     */
    @Test
    void send_theFailurePathNeverCarriesTheNameAfterTheAngleBracketedAddress() {
        when(mailSender.createMimeMessage())
                .thenThrow(new RuntimeException("550 <victim@example.com> (Jane Smith)... user unknown"));

        assertThatThrownBy(() -> emailService.send("victim@example.com", "Welcome", "email/welcome", Map.of()))
                .isInstanceOf(EmailSendException.class)
                .hasMessageContaining("v***@example.com> (***)")
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("Jane Smith"))
                .satisfies(ex -> assertThat(ex.getMessage()).doesNotContain("victim@example.com"));
    }
}
