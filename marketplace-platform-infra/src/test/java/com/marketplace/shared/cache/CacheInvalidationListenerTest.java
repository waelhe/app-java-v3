package com.marketplace.shared.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CacheInvalidationListener}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Valid cache names trigger local cache eviction</li>
 *   <li>Null or blank cache names are silently ignored</li>
 *   <li>Missing cache names do not cause errors</li>
 *   <li>Cache eviction failures are logged, do not propagate, and are counted as metrics</li>
 * </ul>
 *
 * <p>Reference: Spring Data Redis 4.1 — {@code @RedisListener}:
 * "Annotation-driven Pub/Sub Listener Endpoints built on Spring Messaging
 * using @RedisListener."
 * https://spring.io/projects/release-highlights (Spring Data 2026.0.0)
 */
@ExtendWith(MockitoExtension.class)
class CacheInvalidationListenerTest {

    @Mock
    private CacheManager cacheManager;

    @Mock
    private CacheInvalidationMetrics metrics;

    @InjectMocks
    private CacheInvalidationListener listener;

    @Test
    void onCacheInvalidation_clearsLocalCache() {
        String cacheName = "catalog-active";
        Cache cache = mock(Cache.class);
        when(cacheManager.getCache(cacheName)).thenReturn(cache);

        listener.onCacheInvalidation(cacheName);

        verify(cache).clear();
        verifyNoInteractions(metrics);
    }

    @Test
    void onCacheInvalidation_nullCacheNameDoesNothing() {
        listener.onCacheInvalidation(null);

        verifyNoInteractions(cacheManager);
    }

    @Test
    void onCacheInvalidation_blankCacheNameDoesNothing() {
        listener.onCacheInvalidation("   ");

        verifyNoInteractions(cacheManager);
    }

    @Test
    void onCacheInvalidation_missingCacheDoesNotError() {
        String cacheName = "nonexistent-cache";
        when(cacheManager.getCache(cacheName)).thenReturn(null);

        listener.onCacheInvalidation(cacheName);

        verify(cacheManager).getCache(cacheName);
        verifyNoMoreInteractions(cacheManager);
    }

    @Test
    void onCacheInvalidation_cacheFailureDoesNotPropagateAndCountsMetric() {
        String cacheName = "catalog-active";
        when(cacheManager.getCache(cacheName)).thenThrow(new RuntimeException("Cache error"));

        listener.onCacheInvalidation(cacheName);

        verify(cacheManager).getCache(cacheName);
        verify(metrics).evictFailure(cacheName);
    }
}
