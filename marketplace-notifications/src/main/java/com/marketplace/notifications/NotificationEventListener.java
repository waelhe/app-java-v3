package com.marketplace.notifications;

import com.marketplace.shared.api.BookingCreatedEvent;
import com.marketplace.shared.api.ListingLeadCreatedEvent;
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
        notificationService.onBookingCreated(event.bookingId());
        log.info("Notification sent for booking created: {}", event.bookingId());
    }

    @ApplicationModuleListener
    public void onPaymentStateChanged(PaymentStateChangedEvent event) {
        notificationService.onPaymentStateChanged(event.paymentIntentId(), event.state());
        log.info("Notification sent for payment state change: intentId={}, state={}",
                event.paymentIntentId(), event.state());
    }

    /**
     * L34 (realestate systems plan §5 — lead capture): the provider's
     * LEAD_RECEIVED alert. Same contract as the two listeners above — after
     * commit, its own transaction, the framework's retry: a failed delivery
     * never loses the lead (the registry entry stays incomplete until the
     * listener succeeds).
     */
    @ApplicationModuleListener
    public void onListingLeadCreated(ListingLeadCreatedEvent event) {
        notificationService.onLeadReceived(event.leadId(), event.listingId(), event.providerId());
        log.info("Notification sent for listing lead: leadId={}, listingId={}",
                event.leadId(), event.listingId());
    }

}
