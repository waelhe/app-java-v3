package com.marketplace.notifications;

import com.marketplace.shared.api.BookingCreatedEvent;
import com.marketplace.shared.api.PaymentStateChangedEvent;
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

    @EventListener
    public void onPaymentStateChanged(PaymentStateChangedEvent event) {
        notificationService.onPaymentStateChanged(event.paymentIntentId(), event.state());
    }

}
