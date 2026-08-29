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
import java.util.Set;

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
        Method m = CacheInvalidationRelay.class.getMethod(
                "onCacheInvalidation", CacheInvalidationRequested.class);
        TransactionalEventListener ann = m.getAnnotation(TransactionalEventListener.class);
        assertThat(ann).isNotNull();
        assertThat(ann.phase()).isEqualTo(TransactionPhase.AFTER_COMMIT);
    }

    @Test
    void evictsByKeyForTargetedRequest() {
        Object key = "abc-123";
        relay.onCacheInvalidation(
                new CacheInvalidationRequested(Set.of("bookings"), key));

        verify(bookingsCache, times(1)).evict(key);
        verify(bookingsCache, never()).clear();
        verify(metrics, never()).incrementEvictFailure("bookings");
    }

    @Test
    void clearsAllWhenClearAllIsTrue() {
        relay.onCacheInvalidation(
                new CacheInvalidationRequested(Set.of("bookings"), null, true));

        verify(bookingsCache, times(1)).clear();
        verify(bookingsCache, never()).evict(any());
        verify(metrics, never()).incrementEvictFailure("bookings");
    }

    @Test
    void handlesMultipleCachesInOneRequest() {
        relay.onCacheInvalidation(
                new CacheInvalidationRequested(Set.of("users", "bookings"), null, true));

        verify(usersCache, times(1)).clear();
        verify(bookingsCache, times(1)).clear();
    }

    @Test
    void skipsMissingCacheGracefully() {
        when(cacheManager.getCache("bookings")).thenReturn(null);

        assertThatCode(() -> relay.onCacheInvalidation(
                new CacheInvalidationRequested(Set.of("bookings", "users"), null, true)))
                .doesNotThrowAnyException();

        verify(usersCache, times(1)).clear();
        verify(metrics, never()).incrementEvictFailure("bookings");
    }

    @Test
    void recordsFailureOnExceptionAndDoesNotRethrow() {
        RuntimeException boom = new RuntimeException("Redis is down");
        org.mockito.Mockito.doThrow(boom).when(bookingsCache).clear();

        assertThatCode(() -> relay.onCacheInvalidation(
                new CacheInvalidationRequested(Set.of("bookings"), null, true)))
                .doesNotThrowAnyException();

        verify(metrics, times(1)).incrementEvictFailure("bookings");
    }
}
