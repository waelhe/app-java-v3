package com.marketplace.shared.email;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.mail.autoconfigure.MailSenderAutoConfiguration;
import org.springframework.boot.thymeleaf.autoconfigure.ThymeleafAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.mail.javamail.JavaMailSender;
import org.thymeleaf.TemplateEngine;

/**
 * The official home of the {@link EmailService} registration — an
 * auto-configuration class, exactly where the Boot reference requires
 * {@code @ConditionalOnBean} to live.
 *
 * <p><b>Why this class exists (the measured defect it closes):</b> the
 * service used to carry {@code @Service @ConditionalOnBean(JavaMailSender)}
 * directly — a condition on a COMPONENT-SCANNED class. The Boot reference's
 * own warning for exactly this shape: "@ConditionalOnBean and
 * @ConditionalOnMissingBean ... do not operate on the level of
 * component-scanned classes ... The condition(s) are evaluated in the order
 * the beans are registered" — and component-scanned beans register BEFORE
 * the auto-configurations, so the condition raced
 * {@code MailSenderAutoConfiguration} and was not guaranteed. Measured on
 * the MailChannelIsolation context: the scanned condition evaluated before
 * the sender bean definition existed, the service silently vanished from
 * the context, and every mail-dependent surface failed. On an
 * auto-configuration class the ordering is deterministic:
 * {@code @AutoConfiguration(after = ...)} guarantees the sender (and the
 * template engine) are registered first, so the condition matches in every
 * context — the app, every module slice, every test context.</p>
 *
 * <p><b>The registration shape</b> follows the library-bean pattern the
 * same reference documents: {@code @ConditionalOnMissingBean} on the
 * {@code @Bean} method lets any application override the service by
 * declaring its own.</p>
 */
@AutoConfiguration(after = {MailSenderAutoConfiguration.class, ThymeleafAutoConfiguration.class})
@ConditionalOnBean(JavaMailSender.class)
public class EmailServiceAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(EmailService.class)
    EmailService emailService(JavaMailSender mailSender, TemplateEngine templateEngine) {
        return new EmailService(mailSender, templateEngine);
    }
}
