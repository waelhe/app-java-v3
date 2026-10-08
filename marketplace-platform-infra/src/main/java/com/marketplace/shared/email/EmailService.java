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

    public EmailService(JavaMailSender mailSender, TemplateEngine templateEngine) {
        this.mailSender = mailSender;
        this.templateEngine = templateEngine;
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
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlContent, true);

            mailSender.send(mimeMessage);

            log.info("Email sent: to={}, subject={}, template={}", to, subject, template);
        } catch (Exception ex) {
            log.error("Failed to send email: to={}, subject={}, template={}", to, subject, template, ex);
            throw new EmailSendException("Failed to send email to " + to, ex);
        }
    }
}
