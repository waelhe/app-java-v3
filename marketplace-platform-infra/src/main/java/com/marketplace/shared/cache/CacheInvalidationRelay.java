package com.marketplace.shared.cache;

import com.marketplace.shared.api.CacheInvalidationRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Listens for {@link CacheInvalidationRequested} events and evicts the
 * corresponding cache entries <em>only after</em> the publishing transaction
 * has committed successfully.
 *
 * <p>This closes a correctness window that exists when
 * {@link org.springframework.cache.annotation.CacheEvict} is used directly on a
 * {@code @Transactional} method: the eviction runs before the transaction
 * commits, so a concurrent reader can repopulate the cache with the
 * pre-commit value, leaving stale data cached after the commit succeeds.
 *
 * <p><b>Official source</b> —
 * <a href="https://docs.spring.io/spring-modulith/reference/events.html">
 * Spring Modulith Reference — Events</a>:
 * <pre>
 * "...transaction handling at transaction commit and treat secondary functionality
 * exactly as that: An async, transactional event listener
 * {@code @TransactionalEventListener}"
 * </pre>
 *
 * <p>Eviction failures are recorded via {@link CacheInvalidationMetrics} for
 * alerting. They are best-effort: the underlying transaction has already
 * committed by the time the listener runs, so a failure to evict cannot be
 * rolled back. Operators seeing a sustained non-zero evict-failure counter
 * should manually flush the affected caches.
 */
@Component
public class CacheInvalidationRelay {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationRelay.class);

    private final CacheManager cacheManager;
    private final CacheInvalidationMetrics metrics;

    public CacheInvalidationRelay(CacheManager cacheManager, CacheInvalidationMetrics metrics) {
        this.cacheManager = cacheManager;
        this.metrics = metrics;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCacheInvalidationRequested(CacheInvalidationRequested event) {
        try {
            for (String cacheName : event.cacheNames()) {
                Cache cache = cacheManager.getCache(cacheName);
                if (cache == null) {
                    continue;
                }
                if (event.clearAll()) {
                    cache.clear();
                } else {
                    cache.evict(event.key());
                }
            }
        } catch (Exception e) {
            metrics.recordEvictFailure();
            log.warn("Failed to invalidate caches {} after commit: {}",
                    event.cacheNames(), e.getMessage());
        }
    }
}
