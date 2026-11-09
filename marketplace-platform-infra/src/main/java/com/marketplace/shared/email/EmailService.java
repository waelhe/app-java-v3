package com.marketplace.shared.email;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import jakarta.mail.internet.MimeMessage;
import io.micrometer.observation.annotation.Observed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.resilience.annotation.ConcurrencyLimit;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.util.Map;

/**
 * Email sending service using Spring Boot auto-configured {@link JavaMailSender}
 * and Thymeleaf auto-configured {@link TemplateEngine}.
 *
 * <p>Official references:
 * <ul>
 *   <li>Boot auto-configuration: docs.spring.io/spring-boot/reference/io/email.html</li>
 *   <li>MimeMessageHelper: docs.spring.io/spring-framework/reference/integration/email.html</li>
 *   <li>Thymeleaf + Spring Mail: thymeleaf.org/doc/articles/springmail.html</li>
 * </ul>
 *
 * <p>Conditionally activated only when a {@link JavaMailSender} bean exists
 * (i.e., when {@code spring.mail.host} is configured) — the condition lives
 * on {@link EmailServiceAutoConfiguration}, the officially documented place
 * for {@code @ConditionalOnBean} (the Boot reference's own warning: a
 * condition on a component-scanned class races the auto-configuration order
 * and is therefore not guaranteed — measured on the MailChannelIsolation
 * context, where the scanned condition evaluated before
 * {@code MailSenderAutoConfiguration} registered the sender and the service
 * silently vanished from the context).
 *
 * <p><b>The sender identity (incident #682's root fix):</b> every message now
 * carries an explicit {@code From} — {@code helper.setFrom(from)} with the
 * validated {@link MailChannelProperties#from} — exactly the shape the
 * Framework reference documents. The channel previously shipped with no
 * From at all: JavaMail derived the envelope from
 * {@code user.name@hostname}, the provider rejected every submission at the
 * MAIL FROM stage ({@code 550 Invalid `from` field} — measured by a raw-SMTP
 * dialogue replay against the live credentials), and the mail leg of every
 * {@code @ApplicationModuleListener} stayed incomplete, which the
 * event-bus health indicator honestly reported as platform DOWN. The
 * identity itself has no default: an activated channel without a sender
 * fails at binding time (INV-2/D6) instead of silently mailing from a
 * derived address.
 *
 * <p><b>D.4 (compliance plan wave D — channel resilience):</b> the send is
 * the SMTP channel's single crossing, so it carries the same official
 * Resilience4j guards the payments PSP channel already wears (the
 * {@code paymentProcessing} house pattern): {@code @Retry(name = "mailSend")}
 * absorbs transient SMTP blips (an async AFTER_COMMIT listener calls this —
 * a blip would otherwise leave the registry publication incomplete for
 * nothing), and {@code @CircuitBreaker(name = "mailSend")} isolates a
 * sustained outage: the mail leg fails FAST with
 * {@code CallNotPermittedException} instead of hanging the listener thread
 * on SMTP connect timeouts, the publication stays incomplete for the
 * registry's resubmission (the A-04/A-13 contract), and the system keeps
 * serving. No fallback method — the house rule (the payments precedent):
 * honest degradation, never a silent one.
 */
public class EmailService {

    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final JavaMailSender mailSender;
    private final TemplateEngine templateEngine;
    private final String from;

    public EmailService(JavaMailSender mailSender, TemplateEngine templateEngine,
            MailChannelProperties mailProperties) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
        this.from = mailProperties.from();
    }

    /**
     * Sends an HTML email using a Thymeleaf template.
     *
     * @param to       recipient email address
     * @param subject  email subject
     * @param template Thymeleaf template path (e.g., "email/welcome")
     * @param variables template model variables
     */
    @Retry(name = "mailSend")
    @CircuitBreaker(name = "mailSend")
    @ConcurrencyLimit(3)
    @Observed(name = "email.send")
    public void send(String to, String subject, String template, Map<String, Object> variables) {
        try {
            Context ctx = new Context();
            ctx.setVariables(variables);

            String htmlContent = templateEngine.process(template, ctx);

            MimeMessage mimeMessage = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(mimeMessage, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            mailSender.send(mimeMessage);

            // The 2026-11-08 #810 round-5 review (Minor · Security &
            // Privacy): the SENDER's own logs never carry the full
            // recipient address — the security legs (deletion, email
            // change) ride this seam with the platform's most sensitive
            // mailboxes, and the caller-side masked line
            // (EmailNotificationService) was answered by an UNMASKED line
            // here one frame earlier. The masked local-part answers the
            // operator's "which mailbox" question without the PII.
            log.info("Email sent: to={}, subject={}, template={}", mask(to), subject, template);
        } catch (Exception ex) {
            // The 2026-11-09 #807 round (Minor · Security & Privacy, two
            // findings): the FAILURE path never carries the raw recipient
            // either. The SMTP client's exception message commonly embeds
            // the full address ("550 <user@example.com>... user unknown"),
            // so (a) the ERROR log prints the failure CLASS plus the
            // SCRUBBED message — never the raw throwable, whose logged
            // header would echo the address verbatim — and (b) the
            // EmailSendException carries the masked recipient plus the
            // same scrubbed diagnostic summary in its own message, with no
            // raw cause riding the chain into a caller's log store (the
            // exception's own stack still identifies the failure site;
            // the resilience annotations key on the exception TYPE, which
            // is unchanged).
            log.error("Failed to send email: to={}, subject={}, template={}, failure={}: {}",
                    mask(to), subject, template, ex.getClass().getName(), scrub(ex.getMessage()));
            throw new EmailSendException("Failed to send email to " + mask(to)
                    + " — " + ex.getClass().getName() + ": " + scrub(ex.getMessage()));
        }
    }

    /**
     * Every address-shaped token in a diagnostic message — the scrub
     * target. The 2026-11-09 #807 round-2 review added the RFC 5322
     * QUOTED local form; the round-3 review closed the parenthesized
     * display-name tail and the ASCII-only classes (Unicode runs bounded
     * by whitespace and the address delimiters now); the round-4 review
     * (Minor · Security &amp; Privacy) closed the ANGLE-BRACKET twin: the
     * SMTP text's {@code <victim@example.com> (Jane Smith)} renders the
     * address inside angle brackets with the parenthesized name AFTER
     * the closing bracket — the name group now skips the bracket so the
     * name never crosses into the log or the exception. The masked
     * rendering keeps the first character and the domain (the operator's
     * forensic pair), and is idempotent over its own output.
     */
    private static final java.util.regex.Pattern EMAIL_TOKEN =
            java.util.regex.Pattern.compile(
                    "((\"[^\"]*\"|[^\\s@\"'<(]+)@[^\\s@,;<>\\)]+)(\\s*>\\s*)?(\\s*\\([^)]*\\))?");

    /**
     * The logging-safe rendering of one recipient value. The RFC 5322
     * display form ({@code "Display Name <local@domain>"}) is parsed
     * FIRST — the addr-spec between the angle brackets is the address;
     * the display name is caller-controlled text that may itself contain
     * {@code '@'} (the 2026-11-09 #807 round: the single-index masking
     * then returned everything from the display name's {@code '@'}
     * onward, echoing the FULL address into the success log). The
     * extracted addr-spec must carry exactly one {@code '@'}, neither
     * first nor last; anything else is not a parseable address and
     * answers the fully-masked form — the input is never echoed.
     */
    private static String mask(String recipient) {
        if (recipient == null) {
            return null;
        }
        String address = recipient;
        int open = recipient.lastIndexOf('<');
        int close = recipient.lastIndexOf('>');
        if (open >= 0 && close > open) {
            address = recipient.substring(open + 1, close).trim();
        }
        int at = address.indexOf('@');
        if (at <= 0 || at != address.lastIndexOf('@') || at == address.length() - 1) {
            return "***";
        }
        return address.charAt(0) + "***" + address.substring(at);
    }

    /**
     * Masks every address-shaped token in an arbitrary diagnostic
     * message (the 2026-11-09 #807 round): the SMTP failure text carries
     * the recipient inline, and the log store's discipline applies to the
     * whole line, not just the formatted {@code to} field. Already-masked
     * tokens ({@code u***@x.com}) carry no matching local-part characters
     * before the {@code @} and pass through unchanged.
     *
     * <p>The 2026-11-09 #807 rounds 3 and 4: a matched token's optional
     * closing angle bracket and display-name tail ({@code
     * <victim@example.com> (Jane Smith)} or {@code victim@example.com
     * (Jane Smith)}) are replaced together with its address — the
     * bracket survives, the name never crosses.</p>
     */
    private static String scrub(String message) {
        if (message == null) {
            return null;
        }
        java.util.regex.Matcher matcher = EMAIL_TOKEN.matcher(message);
        StringBuilder scrubbed = new StringBuilder();
        while (matcher.find()) {
            String replacement = mask(matcher.group(1));
            if (matcher.group(3) != null) {
                replacement = replacement + ">";
            }
            if (matcher.group(4) != null) {
                replacement = replacement + " (***)";
            }
            matcher.appendReplacement(scrubbed,
                    java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(scrubbed);
        return scrubbed.toString();
    }
}
