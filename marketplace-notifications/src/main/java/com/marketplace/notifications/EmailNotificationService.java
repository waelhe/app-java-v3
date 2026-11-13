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

    /**
     * Sends an email notification to an EXPLICIT address — the recipient
     * the mail's own semantics name, not the user record's current one.
     *
     * <p>B11 (ORD-03 — the 2026-11-03 adversarial review): the
     * email-change confirmation's redemption right belongs to the NEW
     * address's mailbox (the pinned Saleor behavior: the change
     * confirmation mails the new address, so possession of the one-time
     * token is the proof the new address works — the whole point of the
     * confirmation). The user-record lookup would resolve the OLD
     * address, which is why this seam takes the recipient verbatim from
     * the event's own frozen contract.</p>
     *
     * @param to        the recipient address the event's contract names
     * @param subject   email subject
     * @param template  Thymeleaf template path
     * @param variables template model variables
     */
    public void sendEmailTo(String to, String subject, String template, Map<String, Object> variables) {
        emailService.ifPresent(es -> {
            es.send(to, subject, template, variables);
            // The 2026-11-06 #810 round review (Minor · Security &
            // Privacy): the full recipient address never rides the logs
            // (the sibling send() logs the userId alone) — the security
            // legs (deletion, email change) carry the MOST sensitive
            // addresses; a masked local-part answers the operator's
            // "which mailbox" question without the PII.
            log.info("Email notification sent: to={}, subject={}", mask(to), subject);
        });
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
