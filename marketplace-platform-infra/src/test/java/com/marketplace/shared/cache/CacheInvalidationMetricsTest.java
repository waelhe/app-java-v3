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
    void registersCountersOnConstruction() {
        assertThat(registry.get("marketplace.cache.invalidation.evict.failure").counter()).isNotNull();
        assertThat(registry.get("marketplace.cache.invalidation.publish.failure").counter()).isNotNull();
    }

    @Test
    void recordEvictFailureIncrementsCounter() {
        assertThat(registry.get("marketplace.cache.invalidation.evict.failure").counter().count()).isZero();
        metrics.recordEvictFailure();
        metrics.recordEvictFailure();
        assertThat(registry.get("marketplace.cache.invalidation.evict.failure").counter().count()).isEqualTo(2);
    }

    @Test
    void recordPublishFailureIncrementsCounter() {
        assertThat(registry.get("marketplace.cache.invalidation.publish.failure").counter().count()).isZero();
        metrics.recordPublishFailure();
        assertThat(registry.get("marketplace.cache.invalidation.publish.failure").counter().count()).isEqualTo(1);
    }

    @Test
    void countersAreIndependent() {
        metrics.recordEvictFailure();
        metrics.recordEvictFailure();
        metrics.recordPublishFailure();
        assertThat(registry.get("marketplace.cache.invalidation.evict.failure").counter().count()).isEqualTo(2);
        assertThat(registry.get("marketplace.cache.invalidation.publish.failure").counter().count()).isEqualTo(1);
    }
}
