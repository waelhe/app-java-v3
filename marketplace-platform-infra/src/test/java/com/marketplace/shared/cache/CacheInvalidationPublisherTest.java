package com.marketplace.shared.cache;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link CacheInvalidationPublisher}.
 *
 * <p>Verifies that:
 * <ul>
 *   <li>Valid cache names are published to the Redis channel</li>
 *   <li>Null or blank cache names are silently ignored</li>
 *   <li>Redis failures are logged but do not propagate</li>
 * </ul>
 *
 * <p>Reference: Spring Data Redis 4.1 — {@code @RedisListener}:
 * "Annotation-driven Pub/Sub Listener Endpoints built on Spring Messaging
 * using @RedisListener."
 * https://spring.io/projects/release-highlights (Spring Data 2026.0.0)
 */
@ExtendWith(MockitoExtension.class)
class CacheInvalidationPublisherTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @InjectMocks
    private CacheInvalidationPublisher publisher;

    @Test
    void publishInvalidation_sendsMessageToRedisChannel() {
        String cacheName = "catalog-active";

        publisher.publishInvalidation(cacheName);

        verify(redisTemplate).convertAndSend(eq(CacheInvalidationPublisher.CHANNEL), eq(cacheName));
    }

    @Test
    void publishInvalidation_nullCacheNameDoesNothing() {
        publisher.publishInvalidation(null);

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void publishInvalidation_blankCacheNameDoesNothing() {
        publisher.publishInvalidation("   ");

        verifyNoInteractions(redisTemplate);
    }

    @Test
    void publishInvalidation_redisFailureDoesNotPropagate() {
        String cacheName = "catalog-active";
        doThrow(new RuntimeException("Redis connection refused"))
                .when(redisTemplate).convertAndSend(any(), any());

        publisher.publishInvalidation(cacheName);

        verify(redisTemplate).convertAndSend(eq(CacheInvalidationPublisher.CHANNEL), eq(cacheName));
    }
}
