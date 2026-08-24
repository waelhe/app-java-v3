package com.marketplace.shared.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Metrics for the distributed cache invalidation machinery.
 *
 * <p>Counts every failure to publish an invalidation message to Redis and
 * every failure to evict a local cache upon receipt, so that silent cache
 * drift across instances becomes observable and alertable (a rising failure
 * rate on these counters means instances may serve stale data).
 *
 * <p>Reference: Spring Boot — Metrics (Micrometer):
 * "Spring Boot auto-configures a composite MeterRegistry and adds a registry
 * to the composite for each of the supported implementations that it finds
 * on the classpath."
 * https://docs.spring.io/spring-boot/reference/actuator/metrics.html
 */
@Component
public class CacheInvalidationMetrics {

    /** Counter name for Redis publish failures, tagged with the cache name. */
    static final String PUBLISH_FAILURE = "marketplace.cache.invalidation.publish.failure";

    /** Counter name for local eviction failures, tagged with the cache name. */
    static final String EVICT_FAILURE = "marketplace.cache.invalidation.evict.failure";

    private static final String CACHE_TAG = "cache";
    private static final String UNKNOWN_CACHE = "unknown";

    private final MeterRegistry registry;

    public CacheInvalidationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Records a failed attempt to publish an invalidation message to Redis.
     *
     * @param cacheName the cache whose invalidation message could not be published
     */
    public void publishFailure(String cacheName) {
        failureCounter(PUBLISH_FAILURE, cacheName).increment();
    }

    /**
     * Records a failed attempt to evict a local cache after receiving an
     * invalidation message.
     *
     * @param cacheName the cache that could not be evicted locally
     */
    public void evictFailure(String cacheName) {
        failureCounter(EVICT_FAILURE, cacheName).increment();
    }

    private Counter failureCounter(String name, String cacheName) {
        return Counter.builder(name)
                .tag(CACHE_TAG, cacheName == null ? UNKNOWN_CACHE : cacheName)
                .description("Failed distributed cache invalidation operations")
                .register(registry);
    }
}
