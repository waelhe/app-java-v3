package com.marketplace.notifications;

import com.marketplace.shared.api.BookingCreatedEvent;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

@Component
public class NotificationEventListener {

    private static final Logger log = LoggerFactory.getLogger(NotificationEventListener.class);

    private final NotificationService notificationService;

    public NotificationEventListener(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @ApplicationModuleListener
    public void onBookingCreated(BookingCreatedEvent event) {
        try {
            notificationService.onBookingCreated(event.bookingId());
            log.info("Notification sent for booking created: {}", event.bookingId());
        } catch (Exception e) {
            log.error("Failed to send notification for booking created: {}", event.bookingId(), e);
        }
    }

    @ApplicationModuleListener
    public void onPaymentStateChanged(PaymentStateChangedEvent event) {
        try {
            notificationService.onPaymentStateChanged(event.paymentIntentId(), event.state());
            log.info("Notification sent for payment state change: intentId={}, state={}",
                    event.paymentIntentId(), event.state());
        } catch (Exception e) {
            log.error("Failed to send notification for payment state change: intentId={}, state={}",
                    event.paymentIntentId(), event.state(), e);
        }
    }

}
