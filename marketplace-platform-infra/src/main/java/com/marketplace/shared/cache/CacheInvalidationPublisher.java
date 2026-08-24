package com.marketplace.shared.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes cache invalidation messages to a Redis Pub/Sub channel.
 *
 * <p>When a service instance evicts a cache entry (e.g., catalog listings
 * are updated), it publishes a message so that other instances in a
 * horizontally-scaled deployment also evict their local cache entries.
 *
 * <p>This ensures cache consistency across instances without relying on
 * cache expiration timeouts, which would cause stale data between
 * expiration cycles.
 *
 * <p>Reference: Spring Data Redis 4.1 — {@code @RedisListener}:
 * "Annotation-driven Pub/Sub Listener Endpoints built on Spring Messaging
 * using @RedisListener."
 * https://spring.io/projects/release-highlights (Spring Data 2026.0.0)
 *
 * <p>Reference: Spring Data Redis — Pub/Sub Messaging:
 * https://docs.spring.io/spring-data/redis/reference/pubsub.html
 */
@Component
public class CacheInvalidationPublisher {

    private static final Logger log = LoggerFactory.getLogger(CacheInvalidationPublisher.class);

    /**
     * The Redis channel name for cache invalidation messages.
     */
    public static final String CHANNEL = "marketplace:cache:invalidation";

    private final StringRedisTemplate redisTemplate;
    private final CacheInvalidationMetrics metrics;

    public CacheInvalidationPublisher(StringRedisTemplate redisTemplate,
                                       CacheInvalidationMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.metrics = metrics;
    }

    /**
     * Publishes a cache invalidation message for the given cache name.
     *
     * <p>The message format is the cache name (e.g., "catalog-active").
     * Subscribers use this to evict all entries for that cache name
     * in their local cache manager.
     *
     * @param cacheName the name of the cache to invalidate
     */
    public void publishInvalidation(String cacheName) {
        if (cacheName == null || cacheName.isBlank()) {
            return;
        }
        try {
            redisTemplate.convertAndSend(CHANNEL, cacheName);
            log.debug("Published cache invalidation for: {}", cacheName);
        } catch (RuntimeException ex) {
            log.warn("Failed to publish cache invalidation for {}: {}", cacheName, ex.getMessage());
            metrics.publishFailure(cacheName);
        }
    }
}
