package com.marketplace.shared.cache;

import com.marketplace.shared.api.CacheInvalidationRequested;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Set;

/**
 * Listens for {@link CacheInvalidationRequested} events and evicts cache
 * entries <em>after</em> the publishing transaction commits.
 *
 * <p>This replaces {@code @CacheEvict} on {@code @Transactional} methods.
 * {@code @CacheEvict} fires as part of the AOP interceptor chain before the
 * transaction commits, creating a window where a concurrent reader can
 * repopulate the cache with stale data. By deferring eviction to
 * {@code AFTER_COMMIT}, the cache is cleared only when the DB change is
 * durable.
 *
 * <p>Official source — Spring Framework 7.0 Reference:
 * <a href="https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html">
 * Transaction-bound Events</a>:
 * "If you need to bind it to the transaction, use
 * {@code @TransactionalEventListener}. When you do so, the listener is bound
 * to the commit phase of the transaction by default."
 *
 * <p>Official source — Spring Framework 7.0 Reference:
 * <a href="https://docs.spring.io/spring-framework/reference/integration/cache/strategies.html">
 * Understanding the Cache Abstraction</a>:
 * "The caching abstraction has no special handling for multi-threaded and
 * multi-process environments, as such features are handled by the cache
 * implementation."
 *
 * <p>Best-effort: exceptions are caught and logged (the transaction has
 * already committed, so there is nothing to roll back).
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

    /**
     * Evicts cache entries after the publishing transaction commits.
     * Uses the default {@code AFTER_COMMIT} phase per official Spring docs.
     */
    @TransactionalEventListener
    public void onCacheInvalidation(CacheInvalidationRequested event) {
        Set<String> cacheNames = event.cacheNames();
        if (cacheNames == null || cacheNames.isEmpty()) {
            return;
        }

        for (String cacheName : cacheNames) {
            Cache cache = cacheManager.getCache(cacheName);
            if (cache == null) {
                log.warn("Cache '{}' not found in CacheManager — skipping eviction", cacheName);
                continue;
            }
            try {
                if (event.clearAll()) {
                    cache.clear();
                    log.debug("Cleared all entries from cache '{}'", cacheName);
                } else {
                    cache.evict(event.key());
                    log.debug("Evicted key '{}' from cache '{}'", event.key(), cacheName);
                }
            } catch (Exception ex) {
                log.error("Failed to evict cache '{}' key '{}' — best-effort, ignoring",
                        cacheName, event.key(), ex);
                metrics.incrementEvictFailure(cacheName);
            }
        }
    }
}
