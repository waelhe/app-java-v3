package com.marketplace.notifications;

import com.marketplace.shared.api.BookingCreatedEvent;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ReviewCreatedEvent;
import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@Transactional
public class NotificationService {
    private final NotificationRepository notificationRepository;
    private final CurrentUserProvider currentUserProvider;

    public NotificationService(NotificationRepository notificationRepository, CurrentUserProvider currentUserProvider) {
        this.notificationRepository = notificationRepository;
        this.currentUserProvider = currentUserProvider;
    }

    public Notification create(UUID recipientId, NotificationChannel channel, String subject, String body) {
        return notificationRepository.save(Notification.create(recipientId, channel, subject, body));
    }

    @Transactional(readOnly = true)
    public Page<Notification> listMine(Pageable pageable, Authentication authentication) {
        return notificationRepository.findByRecipientId(currentUserProvider.getCurrentUserId(authentication), pageable);
    }

    public Notification markRead(UUID id, Authentication authentication) {
        Notification notification = notificationRepository.findById(id).orElseThrow(() -> new ResourceNotFoundException("Notification", id));
        UUID currentUser = currentUserProvider.getCurrentUserId(authentication);
        if (!notification.getRecipientId().equals(currentUser) && !currentUserProvider.isAdmin(authentication)) {
            throw new AccessDeniedException("Cannot read another user's notification");
        }
        notification.markRead();
        return notification;
    }

    @EventListener
    public void onBookingCreated(BookingCreatedEvent event) {
        create(event.bookingId(), NotificationChannel.IN_APP, "Booking created", "Booking " + event.bookingId() + " has been created.");
    }

    @EventListener
    public void onPaymentChanged(PaymentStateChangedEvent event) {
        create(event.paymentIntentId(), NotificationChannel.IN_APP, "Payment state changed", "Payment is now " + event.state() + ".");
    }

    @EventListener
    public void onReviewCreated(ReviewCreatedEvent event) {
        create(event.reviewId(), NotificationChannel.IN_APP, "Review created", "Review " + event.reviewId() + " has been created.");
    }
}
