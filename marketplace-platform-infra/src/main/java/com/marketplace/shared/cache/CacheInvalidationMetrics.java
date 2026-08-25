package com.marketplace.shared.cache;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Micrometer counters for cache-invalidation reliability monitoring.
 *
 * <p>Two failure paths are tracked because they imply different remediation:
 * <ul>
 *   <li><b>publish failure</b> — the in-transaction call to
 *       {@link org.springframework.context.ApplicationEventPublisher#publishEvent}
 *       threw an exception. This is recorded by the publisher so the
 *       transaction can still commit while alerting on the missed invalidation.</li>
 *   <li><b>evict failure</b> — the AFTER_COMMIT listener could not evict the
 *       named caches (e.g. Redis connectivity loss). The transaction has already
 *       committed; the alert gives the operator a chance to flush the affected
 *       caches manually before stale reads spread.</li>
 * </ul>
 *
 * <p><b>Official source</b> —
 * <a href="https://docs.spring.io/spring-boot/reference/actuator/metrics.html">
 * Spring Boot Reference — Metrics</a>:
 * <pre>
 * "Spring Boot auto-configures a composite MeterRegistry and adds a registry
 * to the composite for each of the supported implementations that it finds on
 * the classpath."
 * </pre>
 *
 * <p>Alert on {@code marketplace.cache.invalidation.evict.failure} &gt; 0 — a
 * sustained non-zero value means cache entries are not being invalidated after
 * commit, indicating stale reads may be served.
 */
@Component
public class CacheInvalidationMetrics {

    private static final String TAG_CACHE = "cache";

    private final Counter evictFailure;
    private final Counter publishFailure;

    public CacheInvalidationMetrics(MeterRegistry registry) {
        this.evictFailure = Counter.builder("marketplace.cache.invalidation.evict.failure")
                .description("Cache evictions that failed after transaction commit")
                .tag(TAG_CACHE, "all")
                .register(registry);
        this.publishFailure = Counter.builder("marketplace.cache.invalidation.publish.failure")
                .description("Cache invalidation publish failures within transaction")
                .tag(TAG_CACHE, "all")
                .register(registry);
    }

    /**
     * Records a failure when the AFTER_COMMIT listener could not evict the
     * requested caches. Called from {@link CacheInvalidationRelay}.
     */
    public void recordEvictFailure() {
        evictFailure.increment();
    }

    /**
     * Records a failure when the in-transaction event publish itself threw.
     * Called from publisher services when they swallow the publish exception
     * to avoid breaking the surrounding business transaction.
     */
    public void recordPublishFailure() {
        publishFailure.increment();
    }
}
