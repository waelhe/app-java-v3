package com.marketplace.shared.cache;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.marketplace.shared.api.CacheInvalidationRequested;

import java.lang.reflect.Method;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

class CacheInvalidationRelayTest {

    private CacheManager cacheManager;
    private CacheInvalidationMetrics metrics;
    private CacheInvalidationRelay relay;
    private Cache bookingsCache;
    private Cache usersCache;

    @BeforeEach
    void setUp() {
        cacheManager = mock(CacheManager.class);
        metrics = mock(CacheInvalidationMetrics.class);
        bookingsCache = mock(Cache.class);
        usersCache = mock(Cache.class);
        when(cacheManager.getCache("bookings")).thenReturn(bookingsCache);
        when(cacheManager.getCache("users")).thenReturn(usersCache);
        relay = new CacheInvalidationRelay(cacheManager, metrics);
    }

    @Test
    void listenerIsBoundToAfterCommitPhase() throws NoSuchMethodException {
        // Critical invariant: the listener MUST run after the transaction commits.
        // If this annotation is removed, the cache-eviction-on-rollback bug returns.
        Method m = CacheInvalidationRelay.class.getMethod(
                "onCacheInvalidationRequested", CacheInvalidationRequested.class);
        TransactionalEventListener ann = m.getAnnotation(TransactionalEventListener.class);
        assertThat(ann).isNotNull();
        assertThat(ann.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void evictsByKeyForTargetedRequest() {
        Object key = "abc-123";
        relay.onCacheInvalidationRequested(
                new CacheInvalidationRequested(List.of("bookings"), key));

        verify(bookingsCache, times(1)).evict(key);
        verify(bookingsCache, never()).clear();
        verify(metrics, never()).recordEvictFailure();
    }

    @Test
    void clearsAllWhenKeyIsNull() {
        relay.onCacheInvalidationRequested(
                new CacheInvalidationRequested(List.of("bookings"), null));

        verify(bookingsCache, times(1)).clear();
        verify(bookingsCache, never()).evict(any());
        verify(metrics, never()).recordEvictFailure();
    }

    @Test
    void handlesMultipleCachesInOneRequest() {
        relay.onCacheInvalidationRequested(
                new CacheInvalidationRequested(List.of("users", "bookings"), null));

        verify(usersCache, times(1)).clear();
        verify(bookingsCache, times(1)).clear();
    }

    @Test
    void skipsMissingCacheGracefully() {
        // CacheManager may return null for a cache name that no longer exists
        // (e.g. cache-name removed from spring.cache.cache-names). The relay
        // must continue with the remaining caches instead of NPE-ing.
        when(cacheManager.getCache("bookings")).thenReturn(null);

        assertThatCode(() -> relay.onCacheInvalidationRequested(
                new CacheInvalidationRequested(List.of("bookings", "users"), null)))
                .doesNotThrowAnyException();

        verify(usersCache, times(1)).clear();
        verify(metrics, never()).recordEvictFailure();
    }

    @Test
    void recordsFailureOnExceptionAndDoesNotRethrow() {
        // The transaction has already committed by the time the listener runs.
        // An exception here must not propagate — operators see the failure
        // via the metric counter and can flush the cache manually.
        RuntimeException boom = new RuntimeException("Redis is down");
        org.mockito.Mockito.doThrow(boom).when(bookingsCache).clear();

        assertThatCode(() -> relay.onCacheInvalidationRequested(
                new CacheInvalidationRequested(List.of("bookings"), null)))
                .doesNotThrowAnyException();

        verify(metrics, times(1)).recordEvictFailure();
    }
}
