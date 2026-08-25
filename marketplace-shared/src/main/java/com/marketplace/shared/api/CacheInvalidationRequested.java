package com.marketplace.shared.api;

import java.util.List;

/**
 * Internal application event requesting cache invalidation after the current
 * transaction commits successfully.
 *
 * <p>Publishers ({@code ReviewsService}, {@code BookingService},
 * {@code UserService}) publish this event from within a {@code @Transactional}
 * method instead of relying on {@link org.springframework.cache.annotation.CacheEvict}.
 * Doing so defers the cache eviction to a point after the transaction has
 * committed (see {@code CacheInvalidationRelay} in module {@code shared}),
 * eliminating the window where a concurrent reader could repopulate the
 * cache with the pre-commit value.
 *
 * <p><b>Official source</b> —
 * <a href="https://docs.spring.io/spring-modulith/reference/events.html">
 * Spring Modulith Reference — Events</a>:
 * <pre>
 * "...transaction handling at transaction commit and treat secondary functionality
 * exactly as that: An async, transactional event listener
 * {@code @TransactionalEventListener}
 * By default, all event listeners (meta-)annotated with
 * {@code @TransactionalEventListener} are considered."
 * </pre>
 *
 * @param cacheNames the names of the caches to invalidate
 * @param key        the specific cache key to evict, or {@code null} to clear
 *                   all entries from every named cache
 */
public record CacheInvalidationRequested(List<String> cacheNames, Object key) {

    /**
     * Returns {@code true} when the request targets all entries of the named
     * caches (i.e. no specific key was supplied).
     */
    public boolean clearAll() {
        return key == null;
    }
}
