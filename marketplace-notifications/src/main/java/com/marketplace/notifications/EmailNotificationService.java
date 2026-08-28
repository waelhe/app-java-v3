package com.marketplace.notifications;

import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.email.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sends notification emails in a separate transaction from the business operation.
 *
 * <p>This separation ensures that email delivery failures do not roll back the
 * business transaction (e.g., saving notification records to the database).
 * If email sending fails, the {@link org.springframework.modulith.events.EventPublicationRegistry}
 * marks the event as FAILED and retry mechanisms can be deployed.
 *
 * <p>Official reference — Spring Modulith Events:
 * <a href="https://docs.spring.io/spring-modulith/reference/events.html">
 * "Each transactional event listener is wrapped into an aspect that marks that log entry as
 * completed if the execution of the listener succeeds. In case the listener fails, the log entry
 * stays untouched so that retry mechanisms can be deployed."</a>
 *
 * <p>Official reference — Spring Framework Transaction Propagation:
 * <a href="https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/propagation.html">
 * "REQUIRES_NEW: Run within a separate transaction if an existing transaction exists;
 * create a new transaction if none exists."</a>
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
     * Sends an email notification in a separate transaction.
     *
     * <p>If no {@link EmailService} is configured (e.g., in development),
     * this method is a no-op. If sending fails, the exception propagates
     * so that the event publication registry can mark the event as FAILED.
     *
     * @param userId    the recipient user ID
     * @param subject   email subject
     * @param template  Thymeleaf template path
     * @param variables template model variables
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void sendEmail(UUID userId, String subject, String template, Map<String, Object> variables) {
        emailService.ifPresent(es ->
                userLookupPort.findById(userId).ifPresent(user -> {
                    es.send(user.email(), subject, template, variables);
                    log.info("Email notification sent: userId={}, subject={}", userId, subject);
                })
        );
    }
}
