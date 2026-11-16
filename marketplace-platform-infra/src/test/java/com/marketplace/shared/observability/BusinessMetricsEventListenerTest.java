package com.marketplace.shared.observability;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.UUID;

import com.marketplace.shared.api.BookingCreatedEvent;
import com.marketplace.shared.api.ListingCreatedEvent;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.api.ReviewCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BusinessMetricsEventListenerTest {

    private BusinessMetrics businessMetrics;
    private BusinessMetricsEventListener listener;

    @BeforeEach
    void setUp() {
        businessMetrics = mock();
        listener = new BusinessMetricsEventListener(businessMetrics);
    }

    @Test
    void onBookingCreated_incrementsBookingCreated() {
        listener.onBookingCreated(new BookingCreatedEvent(UUID.randomUUID()));
        verify(businessMetrics).bookingCreated();
    }

    @Test
    void onListingCreated_incrementsListingCreated() {
        listener.onListingCreated(new ListingCreatedEvent(UUID.randomUUID()));
        verify(businessMetrics).listingCreated();
    }

    @Test
    void onReviewCreated_incrementsReviewCreated() {
        listener.onReviewCreated(new ReviewCreatedEvent(UUID.randomUUID()));
        verify(businessMetrics).reviewCreated();
    }

    @Test
    void onPaymentStateChanged_whenInitiated_incrementsPaymentInitiated() {
        listener.onPaymentStateChanged(new PaymentStateChangedEvent(UUID.randomUUID(), "INITIATED"));
        verify(businessMetrics).paymentInitiated();
        verifyNoMoreInteractions(businessMetrics);
    }

    @Test
    void onPaymentStateChanged_whenCompleted_incrementsPaymentCompleted() {
        listener.onPaymentStateChanged(new PaymentStateChangedEvent(UUID.randomUUID(), "COMPLETED"));
        verify(businessMetrics).paymentCompleted();
        verifyNoMoreInteractions(businessMetrics);
    }

    @Test
    void onPaymentStateChanged_whenFailed_incrementsPaymentFailed() {
        listener.onPaymentStateChanged(new PaymentStateChangedEvent(UUID.randomUUID(), "FAILED"));
        verify(businessMetrics).paymentFailed();
        verifyNoMoreInteractions(businessMetrics);
    }
}
