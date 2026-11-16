package com.marketplace.shared.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer counters for cache invalidation failures.
 *
 * <p>Official source — Spring Boot 3.x Reference:
 * <a href="https://docs.spring.io/spring-boot/reference/actuator/metrics.html">
 * Metrics</a>:
 * "Spring Boot auto-configures a composite {@code MeterRegistry} and adds a
 * registry to the composite for each of the supported implementations that
 * it finds on the classpath."
 *
 * <p>Counters exposed:
 * <ul>
 *   <li>{@code marketplace.cache.invalidation.evict.failure} — incremented
 *       when {@code Cache.clear()} or {@code Cache.evict()} throws</li>
 * </ul>
 */
@Component
public class CacheInvalidationMetrics {

    private final MeterRegistry meterRegistry;

    public CacheInvalidationMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    /**
     * Increments the eviction-failure counter for the given cache name.
     *
     * @param cacheName the cache that failed to evict
     */
    public void incrementEvictFailure(String cacheName) {
        Counter.builder("marketplace.cache.invalidation.evict.failure")
                .description("Cache eviction failures after transaction commit")
                .tag("cache", cacheName)
                .register(meterRegistry)
                .increment();
    }
}
