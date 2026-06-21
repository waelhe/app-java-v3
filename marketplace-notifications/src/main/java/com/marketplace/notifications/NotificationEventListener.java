package com.marketplace.notifications;

import com.marketplace.shared.api.BookingCreatedEvent;
import com.marketplace.shared.api.PasswordResetRequestedEvent;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.api.UserRegisteredEvent;
import com.marketplace.shared.api.UserVerifiedEvent;
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
        notificationService.onBookingCreated(event.bookingId());
        log.info("Notification sent for booking created: {}", event.bookingId());
    }

    @ApplicationModuleListener
    public void onPaymentStateChanged(PaymentStateChangedEvent event) {
        notificationService.onPaymentStateChanged(event.paymentIntentId(), event.state());
        log.info("Notification sent for payment state change: intentId={}, state={}",
                event.paymentIntentId(), event.state());
    }

    @ApplicationModuleListener
    public void onUserRegistered(UserRegisteredEvent event) {
        notificationService.onUserRegistered(event);
        log.info("Welcome email sent for user: {}", event.email());
    }

    @ApplicationModuleListener
    public void onUserVerified(UserVerifiedEvent event) {
        notificationService.onUserVerified(event);
        log.info("Verification confirmation email sent for user: {}", event.email());
    }

    @ApplicationModuleListener
    public void onPasswordResetRequested(PasswordResetRequestedEvent event) {
        notificationService.onPasswordResetRequested(event);
        log.info("Password reset email sent for user: {}", event.email());
    }
}
