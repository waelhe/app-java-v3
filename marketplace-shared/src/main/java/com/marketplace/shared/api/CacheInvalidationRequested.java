package com.marketplace.shared.api;

import java.util.Set;

/**
 * Application event published when a cache entry should be evicted
 * <em>after</em> the originating transaction commits.
 *
 * <p>Replaces direct {@code @CacheEvict} on {@code @Transactional} methods.
 * {@code @CacheEvict} fires before the transaction commits, so a concurrent
 * reader can repopulate the cache with the pre-commit value. This event is
 * consumed by {@code CacheInvalidationRelay} via
 * {@code @TransactionalEventListener(phase = AFTER_COMMIT)}, which is the
 * official Spring mechanism for transaction-bound side effects.
 *
 * <p>Official source — Spring Framework 7.0 Reference:
 * <a href="https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html">
 * Transaction-bound Events</a>:
 * "The {@code @TransactionalEventListener} annotation exposes a {@code phase}
 * attribute … The valid phases are {@code BEFORE_COMMIT},
 * {@code AFTER_COMMIT} (default), {@code AFTER_ROLLBACK}, as well as
 * {@code AFTER_COMPLETION}."
 *
 * @param cacheNames the cache names to evict
 * @param key        the cache key to evict, or {@code null} to clear all entries
 * @param clearAll   if {@code true}, evicts all entries in each named cache
 *                   (ignores {@code key})
 */
public record CacheInvalidationRequested(
        Set<String> cacheNames,
        Object key,
        boolean clearAll) {

    /**
     * Evicts <em>all entries</em> in the given caches.
     */
    public CacheInvalidationRequested(Set<String> cacheNames) {
        this(cacheNames, null, true);
    }

    /**
     * Evicts a <em>single key</em> from the given caches.
     */
    public CacheInvalidationRequested(Set<String> cacheNames, Object key) {
        this(cacheNames, key, false);
    }
}
