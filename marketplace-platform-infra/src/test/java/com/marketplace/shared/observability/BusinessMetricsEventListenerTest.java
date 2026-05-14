package com.marketplace.shared.observability;

import com.marketplace.shared.api.BookingCreatedEvent;
import com.marketplace.shared.api.ListingCreatedEvent;
import com.marketplace.shared.api.PaymentStateChangedEvent;
import com.marketplace.shared.api.ReviewCreatedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

class BusinessMetricsEventListenerTest {

    private final BusinessMetrics businessMetrics = mock(BusinessMetrics.class);
    private BusinessMetricsEventListener listener;

    @BeforeEach
    void setUp() {
        listener = new BusinessMetricsEventListener(businessMetrics);
    }

    @Test
    void onBookingCreatedDelegatesToMetrics() {
        var event = new BookingCreatedEvent(UUID.randomUUID());
        listener.onBookingCreated(event);
        verify(businessMetrics).bookingCreated();
    }

    @Test
    void onListingCreatedDelegatesToMetrics() {
        var event = new ListingCreatedEvent(UUID.randomUUID());
        listener.onListingCreated(event);
        verify(businessMetrics).listingCreated();
    }

    @Test
    void onReviewCreatedDelegatesToMetrics() {
        var event = new ReviewCreatedEvent(UUID.randomUUID());
        listener.onReviewCreated(event);
        verify(businessMetrics).reviewCreated();
    }

    @Test
    void onPaymentInitiatedDelegatesToMetrics() {
        var event = new PaymentStateChangedEvent(UUID.randomUUID(), "INITIATED");
        listener.onPaymentStateChanged(event);
        verify(businessMetrics).paymentInitiated();
    }

    @Test
    void onPaymentCompletedDelegatesToMetrics() {
        var event = new PaymentStateChangedEvent(UUID.randomUUID(), "COMPLETED");
        listener.onPaymentStateChanged(event);
        verify(businessMetrics).paymentCompleted();
    }

    @Test
    void onPaymentFailedDelegatesToMetrics() {
        var event = new PaymentStateChangedEvent(UUID.randomUUID(), "FAILED");
        listener.onPaymentStateChanged(event);
        verify(businessMetrics).paymentFailed();
    }

    @Test
    void onUnknownPaymentStateDoesNothing() {
        var event = new PaymentStateChangedEvent(UUID.randomUUID(), "UNKNOWN");
        listener.onPaymentStateChanged(event);
        verifyNoMoreInteractions(businessMetrics);
    }
}
