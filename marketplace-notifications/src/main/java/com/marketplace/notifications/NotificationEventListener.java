package com.marketplace.notifications;

import com.marketplace.shared.api.BookingCreatedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationEventListener {

    private final NotificationService notificationService;

    public NotificationEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @EventListener
    public void onBookingCreated(BookingCreatedEvent event) {
        notificationService.onBookingCreated(event.bookingId());
    }

}
