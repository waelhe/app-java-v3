@NamedInterface("shared-email")
/**
 * Email infrastructure backed by Spring Boot auto-configured
 * {@link org.springframework.mail.javamail.JavaMailSender} and
 * Thymeleaf {@link org.thymeleaf.TemplateEngine}.
 *
 * <p>Activated only when {@code spring.mail.host} is set
 * (via {@link org.springframework.boot.autoconfigure.condition.ConditionalOnBean}).
 * An activated channel REQUIRES a sender identity
 * ({@code marketplace.mail.from}, env {@code MAIL_FROM} — see
 * {@link MailChannelProperties}): a missing or invalid sender fails
 * startup at binding time (incident #682's root fix, INV-2/D6).
 */
package com.marketplace.shared.email;

import org.springframework.modulith.NamedInterface;
