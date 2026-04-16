package com.marketplace.shared.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Business-level metrics for the marketplace platform.
 */
@Component
public class BusinessMetrics {

    private final Counter bookingsCreatedCounter;
    private final Counter bookingsCompletedCounter;
    private final Counter bookingsCancelledCounter;
    private final Counter paymentsInitiatedCounter;
    private final Counter paymentsCompletedCounter;
    private final Counter paymentsFailedCounter;
    private final Counter listingsCreatedCounter;
    private final Counter reviewsCreatedCounter;

    public BusinessMetrics(MeterRegistry registry) {
        this.bookingsCreatedCounter = Counter.builder("marketplace.bookings.created")
                .description("Number of bookings created").register(registry);
        this.bookingsCompletedCounter = Counter.builder("marketplace.bookings.completed")
                .description("Number of bookings completed").register(registry);
        this.bookingsCancelledCounter = Counter.builder("marketplace.bookings.cancelled")
                .description("Number of bookings cancelled").register(registry);
        this.paymentsInitiatedCounter = Counter.builder("marketplace.payments.initiated")
                .description("Number of payment intents initiated").register(registry);
        this.paymentsCompletedCounter = Counter.builder("marketplace.payments.completed")
                .description("Number of payments completed").register(registry);
        this.paymentsFailedCounter = Counter.builder("marketplace.payments.failed")
                .description("Number of payments failed").register(registry);
        this.listingsCreatedCounter = Counter.builder("marketplace.listings.created")
                .description("Number of provider listings created").register(registry);
        this.reviewsCreatedCounter = Counter.builder("marketplace.reviews.created")
                .description("Number of reviews created").register(registry);
    }

    public void bookingCreated() { bookingsCreatedCounter.increment(); }
    public void bookingCompleted() { bookingsCompletedCounter.increment(); }
    public void bookingCancelled() { bookingsCancelledCounter.increment(); }
    public void paymentInitiated() { paymentsInitiatedCounter.increment(); }
    public void paymentCompleted() { paymentsCompletedCounter.increment(); }
    public void paymentFailed() { paymentsFailedCounter.increment(); }
    public void listingCreated() { listingsCreatedCounter.increment(); }
    public void reviewCreated() { reviewsCreatedCounter.increment(); }
}