package com.marketplace.shared.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BusinessMetricsTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private BusinessMetrics metrics;

    @BeforeEach
    void setUp() {
        metrics = new BusinessMetrics(registry);
    }

    @Test
    void bookingCreatedIncrementsCounter() {
        metrics.bookingCreated();
        assertEquals(1, registry.counter("marketplace.bookings.created").count());
    }

    @Test
    void bookingCompletedIncrementsCounter() {
        metrics.bookingCompleted();
        assertEquals(1, registry.counter("marketplace.bookings.completed").count());
    }

    @Test
    void bookingCancelledIncrementsCounter() {
        metrics.bookingCancelled();
        assertEquals(1, registry.counter("marketplace.bookings.cancelled").count());
    }

    @Test
    void paymentInitiatedIncrementsCounter() {
        metrics.paymentInitiated();
        assertEquals(1, registry.counter("marketplace.payments.initiated").count());
    }

    @Test
    void paymentCompletedIncrementsCounter() {
        metrics.paymentCompleted();
        assertEquals(1, registry.counter("marketplace.payments.completed").count());
    }

    @Test
    void paymentFailedIncrementsCounter() {
        metrics.paymentFailed();
        assertEquals(1, registry.counter("marketplace.payments.failed").count());
    }

    @Test
    void listingCreatedIncrementsCounter() {
        metrics.listingCreated();
        assertEquals(1, registry.counter("marketplace.listings.created").count());
    }

    @Test
    void reviewCreatedIncrementsCounter() {
        metrics.reviewCreated();
        assertEquals(1, registry.counter("marketplace.reviews.created").count());
    }
}
