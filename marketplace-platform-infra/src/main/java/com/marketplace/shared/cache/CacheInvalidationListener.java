package com.marketplace.shared.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.annotation.EnableRedisListeners;
import org.springframework.data.redis.annotation.RedisListener;
import org.springframework.stereotype.Component;

/**
 * Listens for cache invalidation messages on a Redis Pub/Sub channel and
 * evicts the corresponding cache entries in the local {@link CacheManager}.
 *
 * <p>When a service instance evicts a cache entry, it publishes a message
 * via {@link CacheInvalidationPublisher}. This listener receives the
 * message and evicts the same cache in the local {@link CacheManager},
 * ensuring cache consistency across horizontally-scaled instances.
 *
 * <p>Uses Spring Data Redis 4.1's {@code @RedisListener} annotation —
 * a new feature in Spring Data 2026.0.0 (Spring Boot 4.1 release train):
 * "Annotation-driven Pub/Sub Listener Endpoints built on Spring Messaging
 * using @RedisListener."
 *
 * <p>Reference: Spring Data Redis 4.1 — {@code @RedisListener}:
 * https://spring.io/projects/release-highlights (Spring Data 2026.0.0)
 *
 * <p>Reference: Spring Data Redis — Pub/Sub Messaging:
 * https://docs.spring.io/spring-data/redis/reference/pubsub.html
 */
@Component
@EnableRedisListeners
public class CacheInvalidationListener {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationListener.class);

    private final CacheManager cacheManager;
    private final CacheInvalidationMetrics metrics;

    public CacheInvalidationListener(CacheManager cacheManager,
                                     CacheInvalidationMetrics metrics) {
        this.cacheManager = cacheManager;
        this.metrics = metrics;
    }

    /**
     * Handles a cache invalidation message by evicting all entries for
     * the specified cache name in the local {@link CacheManager}.
     *
     * @param cacheName the name of the cache to invalidate (message payload)
     */
    @RedisListener(topic = CacheInvalidationPublisher.CHANNEL)
    public void onCacheInvalidation(String cacheName) {
        if (cacheName == null || cacheName.isBlank()) {
            return;
        }
        try {
            org.springframework.cache.Cache cache = cacheManager.getCache(cacheName);
            if (cache != null) {
                cache.clear();
                log.debug("Evicted local cache: {}", cacheName);
            } else {
                log.debug("Cache {} not found in local CacheManager — skipping", cacheName);
            }
        } catch (RuntimeException ex) {
            log.warn("Failed to evict local cache {}: {}", cacheName, ex.getMessage());
            metrics.evictFailure(cacheName);
        }
    }
}
