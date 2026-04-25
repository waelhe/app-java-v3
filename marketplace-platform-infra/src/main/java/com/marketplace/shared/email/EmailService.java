package com.marketplace.shared.email;

import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
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
 * (i.e., when {@code spring.mail.host} is configured).
 */
@Service
@ConditionalOnBean(JavaMailSender.class)
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
