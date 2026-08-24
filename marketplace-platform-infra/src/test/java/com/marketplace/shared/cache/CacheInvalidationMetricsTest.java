package com.marketplace.shared.cache;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link CacheInvalidationMetrics}.
 *
 * <p>Verifies that failure counters are registered with the expected names
 * and cache-name tags, and that they accumulate one increment per reported
 * failure, so alerting rules can be built on them.
 *
 * <p>Reference: Spring Boot — Metrics (Micrometer):
 * "The MeterRegistry is primarily intended to be used at injection points [...]
 * Spring Boot auto-configures a composite MeterRegistry."
 * https://docs.spring.io/spring-boot/reference/actuator/metrics.html
 */
class CacheInvalidationMetricsTest {

    @Test
    void publishFailureIncrementsTaggedCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheInvalidationMetrics metrics = new CacheInvalidationMetrics(registry);

        metrics.publishFailure("catalog-active");
        metrics.publishFailure("catalog-active");

        assertThat(registry.get(CacheInvalidationMetrics.PUBLISH_FAILURE)
                .tag("cache", "catalog-active")
                .counter()
                .count()).isEqualTo(2.0);
    }

    @Test
    void evictFailureIncrementsTaggedCounter() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheInvalidationMetrics metrics = new CacheInvalidationMetrics(registry);

        metrics.evictFailure("catalog-search");

        assertThat(registry.get(CacheInvalidationMetrics.EVICT_FAILURE)
                .tag("cache", "catalog-search")
                .counter()
                .count()).isEqualTo(1.0);
    }

    @Test
    void failuresForDifferentCachesAreCountedSeparately() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CacheInvalidationMetrics metrics = new CacheInvalidationMetrics(registry);

        metrics.publishFailure("catalog-active");
        metrics.publishFailure("catalog-search");

        assertThat(registry.get(CacheInvalidationMetrics.PUBLISH_FAILURE)
                .tag("cache", "catalog-active").counter().count()).isEqualTo(1.0);
        assertThat(registry.get(CacheInvalidationMetrics.PUBLISH_FAILURE)
                .tag("cache", "catalog-search").counter().count()).isEqualTo(1.0);
    }
}
