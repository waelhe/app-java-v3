/**
 * Email infrastructure backed by Spring Boot auto-configured
 * {@link org.springframework.mail.javamail.JavaMailSender} and
 * Thymeleaf {@link org.thymeleaf.TemplateEngine}.
 *
 * <p>Activated only when {@code spring.mail.host} is set
 * (via {@link org.springframework.boot.autoconfigure.condition.ConditionalOnBean}).
 */
package com.marketplace.shared.email;
