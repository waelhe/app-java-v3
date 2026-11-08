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
            log.error("Failed to send email: to={}, subject={}, template={}",
                    mask(to), subject, template, ex);
            // The exception MESSAGE carries the masked form too — the
            // failure surfaces in the caller's logs and error handlers,
            // which are log stores under the same discipline.
            throw new EmailSendException("Failed to send email to " + mask(to), ex);
        }
    }

    /** The logging-safe rendering: the first character plus the domain. */
    private static String mask(String address) {
        if (address == null) {
            return null;
        }
        int at = address.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return address.charAt(0) + "***" + address.substring(at);
    }
}
