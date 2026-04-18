package com.marketplace.shared.observability;

import com.marketplace.shared.api.BookingCreatedEvent;
import com.marketplace.shared.api.ListingCreatedEvent;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.api.ReviewCreatedEvent;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class BusinessMetricsEventListener {

    private final BusinessMetrics businessMetrics;

    public BusinessMetricsEventListener(BusinessMetrics businessMetrics) {
        this.businessMetrics = businessMetrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onBookingCreated(BookingCreatedEvent event) {
        businessMetrics.bookingCreated();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onListingCreated(ListingCreatedEvent event) {
        businessMetrics.listingCreated();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onReviewCreated(ReviewCreatedEvent event) {
        businessMetrics.reviewCreated();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
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
