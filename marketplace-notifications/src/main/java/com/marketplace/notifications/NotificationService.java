package com.marketplace.notifications;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.PaymentIntentLookupPort;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import io.micrometer.observation.annotation.Observed;
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
    private final EmailNotificationService emailNotificationService;
    private final Optional<SimpMessagingTemplate> messagingTemplate;
    private final NotificationPreferenceService preferences;

    public NotificationService(NotificationRepository repository,
                               BookingParticipantProvider bookingParticipantProvider,
                               PaymentIntentLookupPort paymentIntentLookupPort,
                               CurrentUserProvider currentUserProvider,
                               EmailNotificationService emailNotificationService,
                               Optional<SimpMessagingTemplate> messagingTemplate,
                               NotificationPreferenceService preferences) {
        this.repository = repository;
        this.bookingParticipantProvider = bookingParticipantProvider;
        this.paymentIntentLookupPort = paymentIntentLookupPort;
        this.currentUserProvider = currentUserProvider;
        this.emailNotificationService = emailNotificationService;
        this.messagingTemplate = messagingTemplate;
        this.preferences = preferences;
    }

    public void onBookingCreated(UUID bookingId) {
        BookingInfo info = bookingParticipantProvider.getBookingInfo(bookingId);
        // L22: the in-app channel is always on — the row lands for both
        // participants regardless of preferences (roadmap: "inside the app
        // always").
        repository.save(Notification.create(info.consumerId(), NotificationType.BOOKING_CREATED.name(), "Booking created: " + bookingId));
        repository.save(Notification.create(info.providerId(), NotificationType.BOOKING_CREATED.name(), "New booking request: " + bookingId));
        if (preferences.isChannelEnabled(info.consumerId(), NotificationType.BOOKING_CREATED, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(info.consumerId(), "Booking Created", "email/notification", Map.of("message", "Your booking " + bookingId + " has been created."));
        }
        if (preferences.isChannelEnabled(info.providerId(), NotificationType.BOOKING_CREATED, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(info.providerId(), "New Booking Request", "email/notification", Map.of("message", "New booking request " + bookingId + " for your service."));
        }
        sendWebSocket(info.consumerId(), NotificationType.BOOKING_CREATED, "Booking created: " + bookingId);
        sendWebSocket(info.providerId(), NotificationType.BOOKING_CREATED, "New booking request: " + bookingId);
    }

    public void onPaymentStateChanged(UUID paymentIntentId, String state) {
        paymentIntentLookupPort.findById(paymentIntentId).ifPresent(intent -> {
            BookingInfo bookingInfo = bookingParticipantProvider.getBookingInfo(intent.bookingId());
            // L22: the in-app channel is always on (see onBookingCreated).
            repository.save(Notification.create(intent.consumerId(), NotificationType.PAYMENT_STATE.name(), "Payment " + state + " for booking " + intent.bookingId()));
            repository.save(Notification.create(bookingInfo.providerId(), NotificationType.PAYMENT_STATE.name(), "Payment " + state + " for booking " + intent.bookingId()));
            String message = "Payment " + state + " for booking " + intent.bookingId();
            if (preferences.isChannelEnabled(intent.consumerId(), NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL)) {
                emailNotificationService.sendEmail(intent.consumerId(), "Payment " + state, "email/notification", Map.of("message", message));
            }
            if (preferences.isChannelEnabled(bookingInfo.providerId(), NotificationType.PAYMENT_STATE, NotificationChannel.EMAIL)) {
                emailNotificationService.sendEmail(bookingInfo.providerId(), "Payment " + state, "email/notification", Map.of("message", message));
            }
            sendWebSocket(intent.consumerId(), NotificationType.PAYMENT_STATE, message);
            sendWebSocket(bookingInfo.providerId(), NotificationType.PAYMENT_STATE, message);
        });
    }

    /**
     * L34 (realestate systems plan §5 — lead capture): the provider's
     * LEAD_RECEIVED alert — the same delivery shape as the two event
     * points above (in-app row always lands; WebSocket and email ride
     * their L22 per-type/channel preferences).
     *
     * <p><b>The recipient is the listing's provider id — which lives in
     * the users.id space (the A1/V2 measured fact:
     * {@code provider_listings.provider_id references users(id)}, the
     * same seam {@code onBookingCreated} uses for its BookingInfo
     * provider): the id IS the recipient, no profile resolution, no
     * unlinked-profile edge to skip.
     */
    public void onLeadReceived(UUID leadId, UUID listingId, UUID providerUserId) {
        String message = "New lead for your listing: " + listingId;
        // L22: the in-app channel is always on (see onBookingCreated).
        repository.save(Notification.create(providerUserId,
                NotificationType.LEAD_RECEIVED.name(), message));
        if (preferences.isChannelEnabled(providerUserId,
                NotificationType.LEAD_RECEIVED, NotificationChannel.EMAIL)) {
            emailNotificationService.sendEmail(providerUserId, "New Lead",
                    "email/notification", Map.of("message", message));
        }
        sendWebSocket(providerUserId, NotificationType.LEAD_RECEIVED, message);
    }

    private void sendWebSocket(UUID userId, NotificationType type, String message) {
        // L22: WS sends by default and honors an explicit opt-out — the
        // preference check is the single gate before the push.
        if (!preferences.isChannelEnabled(userId, type, NotificationChannel.WS)) {
            return;
        }
        messagingTemplate.ifPresent(template ->
            template.convertAndSend("/topic/notifications/" + userId,
                    new WebSocketNotification(type.name(), message))
        );
    }

    @Transactional(readOnly = true)
    public List<Notification> getMyNotifications(Authentication authentication) {
        UUID userId = currentUserProvider.getCurrentUserId(authentication);
        return repository.findByRecipientIdOrderByCreatedAtDesc(userId);
    }

    @Observed(name = "notification.mark.read")
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
