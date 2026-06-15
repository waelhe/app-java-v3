package com.marketplace.notifications;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.UserLookupPort;
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

    public NotificationService(NotificationRepository repository,
                               BookingParticipantProvider bookingParticipantProvider,
                               PaymentIntentLookupPort paymentIntentLookupPort,
                               CurrentUserProvider currentUserProvider,
                               UserLookupPort userLookupPort,
                               Optional<SimpMessagingTemplate> messagingTemplate,
                               Optional<EmailService> emailService) {
        this.repository = repository;
        this.bookingParticipantProvider = bookingParticipantProvider;
        this.paymentIntentLookupPort = paymentIntentLookupPort;
        this.currentUserProvider = currentUserProvider;
        this.userLookupPort = userLookupPort;
        this.messagingTemplate = messagingTemplate;
        this.emailService = emailService;
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

    private void sendEmail(UUID userId, String subject, String template, Map<String, Object> variables) {
        emailService.ifPresent(es ->
            userLookupPort.findById(userId).ifPresent(user -> {
                try {
                    es.send(user.email(), subject, template, variables);
                } catch (Exception e) {
                    log.error("Failed to send email to user {}: {}", userId, e.getMessage());
                }
            })
        );
    }

    private void sendWebSocket(UUID userId, String type, String message) {
        messagingTemplate.ifPresent(template ->
            template.convertAndSend("/topic/notifications/" + userId,
                    new WebSocketNotification(type, message))
        );
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
