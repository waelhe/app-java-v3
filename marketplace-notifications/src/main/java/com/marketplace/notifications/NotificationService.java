package com.marketplace.notifications;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;

import com.marketplace.shared.api.PasswordResetRequestedEvent;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.UserLookupPort;
import com.marketplace.shared.api.UserRegisteredEvent;
import com.marketplace.shared.api.UserVerifiedEvent;
import com.marketplace.shared.email.EmailService;
import com.marketplace.shared.security.CurrentUserProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Transactional
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final NotificationRepository repository;
    private final BookingParticipantProvider bookingParticipantProvider;
    private final PaymentIntentLookupPort paymentIntentLookupPort;
    private final CurrentUserProvider currentUserProvider;
    private final UserLookupPort userLookupPort;
    private final Optional<SimpMessagingTemplate> messagingTemplate;
    private final Optional<EmailService> emailService;

    private final String authServerIssuer;

    public NotificationService(NotificationRepository repository,
                               BookingParticipantProvider bookingParticipantProvider,
                               PaymentIntentLookupPort paymentIntentLookupPort,
                               CurrentUserProvider currentUserProvider,
                               UserLookupPort userLookupPort,
                               Optional<SimpMessagingTemplate> messagingTemplate,
                               Optional<EmailService> emailService,
                               @org.springframework.beans.factory.annotation.Value("${marketplace.security.auth-server.issuer:http://localhost:8080}") String authServerIssuer) {
        this.repository = repository;
        this.bookingParticipantProvider = bookingParticipantProvider;
        this.paymentIntentLookupPort = paymentIntentLookupPort;
        this.currentUserProvider = currentUserProvider;
        this.userLookupPort = userLookupPort;
        this.messagingTemplate = messagingTemplate;
        this.emailService = emailService;
        this.authServerIssuer = authServerIssuer;
    }

    public void onBookingCreated(UUID bookingId) {
        BookingInfo info = bookingParticipantProvider.getBookingInfo(bookingId);
        repository.save(Notification.create(info.consumerId(), "BOOKING_CREATED", "Booking created: " + bookingId));
        repository.save(Notification.create(info.providerId(), "BOOKING_CREATED", "New booking request: " + bookingId));
        sendEmail(info.consumerId(), "Booking Created", "email/notification", Map.of("message", "Your booking " + bookingId + " has been created."));
        sendEmail(info.providerId(), "New Booking Request", "email/notification", Map.of("message", "New booking request " + bookingId + " for your service."));
        sendWebSocket(info.consumerId(), "BOOKING_CREATED", "Booking created: " + bookingId);
        sendWebSocket(info.providerId(), "BOOKING_CREATED", "New booking request: " + bookingId);
    }

    public void onPaymentStateChanged(UUID paymentIntentId, String state) {
        paymentIntentLookupPort.findById(paymentIntentId).ifPresent(intent -> {
            BookingInfo bookingInfo = bookingParticipantProvider.getBookingInfo(intent.bookingId());
            repository.save(Notification.create(intent.consumerId(), "PAYMENT_STATE", "Payment " + state + " for booking " + intent.bookingId()));
            repository.save(Notification.create(bookingInfo.providerId(), "PAYMENT_STATE", "Payment " + state + " for booking " + intent.bookingId()));
            String message = "Payment " + state + " for booking " + intent.bookingId();
            sendEmail(intent.consumerId(), "Payment " + state, "email/notification", Map.of("message", message));
            sendEmail(bookingInfo.providerId(), "Payment " + state, "email/notification", Map.of("message", message));
            sendWebSocket(intent.consumerId(), "PAYMENT_STATE", message);
            sendWebSocket(bookingInfo.providerId(), "PAYMENT_STATE", message);
        });
    }

    public void onUserRegistered(UserRegisteredEvent event) {
        String verificationLink = authServerIssuer + "/api/v1/auth/verify?token=" + event.verificationToken();
        emailService.ifPresent(es -> {
            try {
                es.send(event.email(), "Welcome to Marketplace -- Verify Your Email",
                        "email/verify-email",
                        Map.of("name", event.displayName(), "verificationLink", verificationLink));
            } catch (Exception e) {
                // Pass 'e' as last arg so SLF4J prints the full stack trace for diagnostics.
                log.error("Failed to send verification email to {}", event.email(), e);
            }
        });
    }

    public void onUserVerified(UserVerifiedEvent event) {
        emailService.ifPresent(es -> {
            try {
                es.send(event.email(), "Email Verified -- Welcome to Marketplace",
                        "email/email-verified",
                        Map.of("name", event.email()));
            } catch (Exception e) {
                log.error("Failed to send verified email to {}", event.email(), e);
            }
        });
    }

    public void onPasswordResetRequested(PasswordResetRequestedEvent event) {
        String resetLink = authServerIssuer + "/reset-password?token=" + event.resetToken();
        emailService.ifPresent(es -> {
            try {
                es.send(event.email(), "Reset Your Password",
                        "email/password-reset",
                        Map.of("name", event.email(), "resetLink", resetLink, "expirationMinutes", 30));
            } catch (Exception e) {
                log.error("Failed to send password reset email to {}", event.email(), e);
            }
        });
    }

    private void sendEmail(UUID userId, String subject, String template, Map<String, Object> variables) {
        emailService.ifPresent(es ->
            userLookupPort.findById(userId).ifPresent(user -> {
                try {
                    es.send(user.email(), subject, template, variables);
                } catch (Exception e) {
                    log.error("Failed to send email to user {}", userId, e);
                }
            })
        );
    }

    private void sendWebSocket(UUID userId, String type, String message) {
        messagingTemplate.ifPresent(template -> {
            try {
                template.convertAndSend("/topic/notifications/" + userId,
                        new WebSocketNotification(type, message));
            } catch (Exception e) {
                // WebSocket is best-effort -- notification persistence must not roll back
                // if the STOMP broker is unreachable. Same pattern as sendEmail above.
                log.error("Failed to send WebSocket notification to user {}", userId, e);
            }
        });
    }

    @Transactional(readOnly = true)
    public List<Notification> getMyNotifications(Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return repository.findByRecipientIdOrderByCreatedAtDesc(userId);
    }

    public Notification markAsRead(UUID id, Authentication authentication) {
        Notification notification = repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Notification not found: " + id));
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        if (!notification.getRecipientId().equals(userId) && !currentUserProvider.isAdmin(authentication)) {
            throw new AccessDeniedException("Not allowed to access this notification");
        }
        notification.markRead();
        return notification;
    }
}
