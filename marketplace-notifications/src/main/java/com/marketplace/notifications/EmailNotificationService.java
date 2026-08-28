package com.marketplace.notifications;

import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.email.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends notification emails as part of the transactional event listener for notifications.
 *
 * <p>The {@link NotificationEventListener} methods are annotated with
 * {@link org.springframework.modulith.events.ApplicationModuleListener}, which runs each
 * listener in its own {@code REQUIRES_NEW} transaction. The emails sent here participate in
 * that transaction: if sending fails, the exception propagates out of the listener, the
 * listener transaction rolls back, and the {@link org.springframework.modulith.events.EventPublicationRegistry}
 * keeps the publication entry uncompleted so that retry mechanisms can be deployed.
 *
 * <p>Official reference — Spring Modulith Events:
 * <a href="https://docs.spring.io/spring-modulith/reference/events.html">
 * "Each transactional event listener is wrapped into an aspect that marks that log entry as
 * completed if the execution of the listener succeeds. In case the listener fails, the log entry
 * stays untouched so that retry mechanisms can be deployed."</a>
 */
@Service
public class EmailNotificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailNotificationService.class);

    private final Optional<EmailService> emailService;
    private final UserLookupPort userLookupPort;

    public EmailNotificationService(Optional<EmailService> emailService,
                                     UserLookupPort userLookupPort) {
        this.emailService = emailService;
        this.userLookupPort = userLookupPort;
    }

    /**
     * Sends an email notification to a user.
     *
     * <p>If no {@link EmailService} is configured (e.g., in development),
     * this method is a no-op. If sending fails, the exception propagates so
     * that the listener's transaction rolls back and the event publication
     * registry keeps the publication uncompleted for retry.
     *
     * @param userId    the recipient user ID
     * @param subject   email subject
     * @param template  Thymeleaf template path
     * @param variables template model variables
     */
    public void sendEmail(UUID userId, String subject, String template, Map<String, Object> variables) {
        emailService.ifPresent(es ->
                userLookupPort.findById(userId).ifPresent(user -> {
                    es.send(user.email(), subject, template, variables);
                    log.info("Email notification sent: userId={}, subject={}", userId, subject);
                })
        );
    }
}
