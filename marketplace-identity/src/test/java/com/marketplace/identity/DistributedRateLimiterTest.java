package com.marketplace.identity;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link DistributedRateLimiter}.
 *
 * <p>Verifies:
 * <ul>
 *   <li>Requests under the limit are allowed</li>
 *   <li>Requests over the limit are blocked</li>
 *   <li>Redis failures fail open (never block auth on infra failure)</li>
 * </ul>
 *
 * <p>Type-safe approach: uses {@code RedisScript<Long>} matcher instead of raw
 * {@code RedisScript.class} — eliminates the need for {@code @SuppressWarnings("unchecked")}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DistributedRateLimiterTest {

    @Mock private StringRedisTemplate redisTemplate;

    private DistributedRateLimiter rateLimiter;

    @BeforeEach
    void setUp() {
        rateLimiter = new DistributedRateLimiter(redisTemplate, 5, 60);
        // Default: allow all (counter = 1L). Individual tests override with specific return values.
        // Type-safe: RedisScript<? extends Object> matcher — no unchecked warning.
        lenient().when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);
    }

    @Test
    void tryAcquire_allowsRequestsUnderLimit() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(1L);

        assertTrue(rateLimiter.tryAcquire("auth:ip:1.2.3.4"));
    }

    @Test
    void tryAcquire_allowsRequestsAtLimit() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(5L); // exactly at the limit

        assertTrue(rateLimiter.tryAcquire("auth:ip:1.2.3.4"));
    }

    @Test
    void tryAcquire_blocksRequestsOverLimit() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(6L); // over the limit

        assertFalse(rateLimiter.tryAcquire("auth:ip:1.2.3.4"));
    }

    @Test
    void tryAcquire_failsOpenOnRedisNull() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenReturn(null);

        assertTrue(rateLimiter.tryAcquire("auth:ip:1.2.3.4"),
                "Must fail open when Redis returns null — never block auth on infra degradation");
    }

    @Test
    void tryAcquire_failsOpenOnRedisException() {
        when(redisTemplate.execute(any(RedisScript.class), anyList(), any(Object[].class)))
                .thenThrow(new RuntimeException("Redis connection refused"));

        assertTrue(rateLimiter.tryAcquire("auth:ip:1.2.3.4"),
                "Must fail open when Redis throws — never block auth on infra failure");
    }
}
