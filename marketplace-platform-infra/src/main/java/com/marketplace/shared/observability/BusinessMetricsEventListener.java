package com.marketplace.shared.observability;

import com.marketplace.shared.api.BookingCreatedEvent;
import com.marketplace.shared.api.ListingCreatedEvent;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.api.ReviewCreatedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class BusinessMetricsEventListener {

    private final BusinessMetrics businessMetrics;

    public BusinessMetricsEventListener(BusinessMetrics businessMetrics) {
        this.businessMetrics = businessMetrics;
    }

    @EventListener
    public void onBookingCreated(BookingCreatedEvent event) {
        businessMetrics.bookingCreated();
    }

    @EventListener
    public void onListingCreated(ListingCreatedEvent event) {
        businessMetrics.listingCreated();
    }

    @EventListener
    public void onReviewCreated(ReviewCreatedEvent event) {
        businessMetrics.reviewCreated();
    }

    @EventListener
    public void onPaymentStateChanged(PaymentStateChangedEvent event) {
        if ("INITIATED".equals(event.state())) {
            businessMetrics.paymentInitiated();
        } else if ("COMPLETED".equals(event.state())) {
            businessMetrics.paymentCompleted();
        } else if ("FAILED".equals(event.state())) {
            businessMetrics.paymentFailed();
        }
    }
}
