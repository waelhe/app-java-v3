package com.marketplace.notifications;

import com.marketplace.shared.api.BookingInfo;
import com.marketplace.shared.api.BookingParticipantProvider;
import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.security.CurrentUserProvider;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class NotificationService {
    private final NotificationRepository repository;
    private final BookingParticipantProvider bookingParticipantProvider;
    private final CurrentUserProvider currentUserProvider;

    public NotificationService(NotificationRepository repository,
                               BookingParticipantProvider bookingParticipantProvider,
                               CurrentUserProvider currentUserProvider) {
        this.repository = repository;
        this.bookingParticipantProvider = bookingParticipantProvider;
        this.currentUserProvider = currentUserProvider;
    }

    public void onBookingCreated(UUID bookingId) {
        BookingInfo info = bookingParticipantProvider.getBookingInfo(bookingId);
        repository.save(Notification.create(info.consumerId(), "BOOKING_CREATED", "Booking created: " + bookingId));
        repository.save(Notification.create(info.providerId(), "BOOKING_CREATED", "New booking request: " + bookingId));
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
