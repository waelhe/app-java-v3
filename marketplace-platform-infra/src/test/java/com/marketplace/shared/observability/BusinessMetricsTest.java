package com.marketplace.shared.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class BusinessMetricsTest {

    private MeterRegistry registry;
    private BusinessMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new BusinessMetrics(registry);
    }

    @Test
    void registersCountersOnConstruction() {
        assertThat(registry.get("marketplace.bookings.created").counter()).isNotNull();
        assertThat(registry.get("marketplace.bookings.completed").counter()).isNotNull();
        assertThat(registry.get("marketplace.bookings.cancelled").counter()).isNotNull();
        assertThat(registry.get("marketplace.payments.initiated").counter()).isNotNull();
        assertThat(registry.get("marketplace.payments.completed").counter()).isNotNull();
        assertThat(registry.get("marketplace.payments.failed").counter()).isNotNull();
        assertThat(registry.get("marketplace.listings.created").counter()).isNotNull();
        assertThat(registry.get("marketplace.reviews.created").counter()).isNotNull();
    }

    @Test
    void incrementIncreasesCounters() {
        metrics.bookingCreated();
        metrics.bookingCompleted();
        metrics.bookingCancelled();
        metrics.paymentInitiated();
        metrics.paymentCompleted();
        metrics.paymentFailed();
        metrics.listingCreated();
        metrics.reviewCreated();

        assertThat(registry.get("marketplace.bookings.created").counter().count()).isEqualTo(1);
        assertThat(registry.get("marketplace.bookings.completed").counter().count()).isEqualTo(1);
        assertThat(registry.get("marketplace.bookings.cancelled").counter().count()).isEqualTo(1);
        assertThat(registry.get("marketplace.payments.initiated").counter().count()).isEqualTo(1);
        assertThat(registry.get("marketplace.payments.completed").counter().count()).isEqualTo(1);
        assertThat(registry.get("marketplace.payments.failed").counter().count()).isEqualTo(1);
        assertThat(registry.get("marketplace.listings.created").counter().count()).isEqualTo(1);
        assertThat(registry.get("marketplace.reviews.created").counter().count()).isEqualTo(1);
    }
}
