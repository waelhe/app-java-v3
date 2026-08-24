package com.marketplace.shared.cache;

import java.util.List;

/**
 * Application event signalling that one or more application caches need to
 * be invalidated on every service instance.
 *
 * <p>Business services publish this event from inside their transactional
 * methods. The actual Redis Pub/Sub message is only sent by
 * {@link CacheInvalidationRelay} once the publishing transaction has
 * committed successfully, so a rolled-back transaction never causes cache
 * invalidation (and therefore no stale, premature eviction) on any instance.
 *
 * <p>Reference: Spring Framework — Transactional Events:
 * "If you need to bind it to the transaction, use
 * {@code @TransactionalEventListener}. When you do so, the listener is bound
 * to the commit phase of the transaction by default."
 * https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html
 *
 * @param cacheNames names of the caches to invalidate (never {@code null})
 */
public record CacheInvalidationRequested(List<String> cacheNames) {

    public CacheInvalidationRequested {
        cacheNames = List.copyOf(cacheNames);
    }
}
