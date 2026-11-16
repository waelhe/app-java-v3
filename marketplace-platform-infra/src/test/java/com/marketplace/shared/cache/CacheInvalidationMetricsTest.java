package com.marketplace.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CacheInvalidationMetricsTest {

    private MeterRegistry registry;
    private CacheInvalidationMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new CacheInvalidationMetrics(registry);
    }

    @Test
    void incrementsEvictFailureCounter() {
        metrics.incrementEvictFailure("bookings");
        metrics.incrementEvictFailure("bookings");

        assertThat(registry.get("marketplace.cache.invalidation.evict.failure")
                .tag("cache", "bookings").counter().count()).isEqualTo(2);
    }

    @Test
    void countersAreIndependentPerCacheName() {
        metrics.incrementEvictFailure("bookings");
        metrics.incrementEvictFailure("bookings");
        metrics.incrementEvictFailure("users");

        assertThat(registry.get("marketplace.cache.invalidation.evict.failure")
                .tag("cache", "bookings").counter().count()).isEqualTo(2);
        assertThat(registry.get("marketplace.cache.invalidation.evict.failure")
                .tag("cache", "users").counter().count()).isEqualTo(1);
    }
}
