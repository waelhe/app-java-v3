package com.marketplace.identity;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

/**
 * Distributed rate limiter backed by Redis.
 *
 * <p>Uses the <strong>fixed-window counter</strong> pattern documented by Redis:
 * a Lua script atomically INCRs a counter and sets the TTL on the first request.
 * This is fully distributed — works across all application instances sharing the
 * same Redis — replacing the prior in-process Resilience4j {@code @RateLimiter}
 * which was per-instance (N instances = N× the effective limit for an attacker,
 * but still 1× for a legitimate user hitting a single instance).
 *
 * <p>The Lua script ensures atomicity: INCR + conditional EXPIRE happen in a
 * single Redis round-trip with no race window.
 *
 * <p>Per-key bucketing: the caller provides a bucket key (e.g. {@code "ip:1.2.3.4"}
 * or {@code "user:admin"}) so limits are enforced per-identity, not globally.
 *
 * <p><b>References</b>
 * <ul>
 *   <li><a href="https://redis.io/docs/latest/develop/use/patterns/distributed-locks/">Redis — Distributed Patterns</a></li>
 *   <li><a href="https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html#rate-limiting">OWASP Authentication Cheat Sheet — Rate Limiting</a></li>
 *   <li><a href="https://docs.spring.io/spring-data/redis/reference/">Spring Data Redis Reference</a></li>
 * </ul>
 */
@Service
public class DistributedRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(DistributedRateLimiter.class);

    /**
     * Lua script: atomically increment the counter and set TTL on first increment.
     * Returns the new counter value. The caller compares it against the limit.
     *
     * <p>Uses ARGV[1] for TTL (no dummy ARGV[2]). Reference: Redis Lua scripting —
     * "KEYS[] and ARGV[] are accessed by index" (1-based).
     * https://redis.io/docs/latest/develop/use/patterns/distributed-locks/
     *
     * <pre>
     * local current = redis.call('INCR', KEYS[1])
     * if current == 1 then
     *   redis.call('EXPIRE', KEYS[1], ARGV[1])
     * end
     * return current
     * </pre>
     */
    private static final String RATE_LIMIT_SCRIPT =
            "local current = redis.call('INCR', KEYS[1]) " +
            "if current == 1 then " +
            "  redis.call('EXPIRE', KEYS[1], ARGV[1]) " +
            "end " +
            "return current";

    private static final DefaultRedisScript<Long> SCRIPT = new DefaultRedisScript<>(RATE_LIMIT_SCRIPT, Long.class);

    private final StringRedisTemplate redisTemplate;

    private final int authLimit;
    private final Duration authWindow;

    public DistributedRateLimiter(
            StringRedisTemplate redisTemplate,
            @Value("${marketplace.security.rate-limit.auth.limit:5}") int authLimit,
            @Value("${marketplace.security.rate-limit.auth.window-seconds:60}") int authWindowSeconds) {
        this.redisTemplate = redisTemplate;
        this.authLimit = authLimit;
        this.authWindow = Duration.ofSeconds(authWindowSeconds);
    }

    /**
     * Checks if a request for the given bucket is allowed under the auth rate limit.
     *
     * <p>The bucket key should incorporate the per-identity dimension (IP or username)
     * to enforce limits per-attacker, not globally (OWASP recommendation).
     *
     * @param bucketKey e.g. {@code "auth:ip:1.2.3.4"} or {@code "auth:user:admin"}
     * @return true if the request is allowed, false if the limit has been exceeded
     */
    public boolean tryAcquire(String bucketKey) {
        String redisKey = "marketplace:ratelimit:" + bucketKey;
        try {
            Long current = redisTemplate.execute(
                    SCRIPT,
                    List.of(redisKey),
                    String.valueOf(authWindow.getSeconds())); // ARGV[1] = TTL in seconds
            if (current == null) {
                // Redis returned null — fail open (allow) to avoid blocking legitimate users
                // when Redis is temporarily unavailable. Log the degradation.
                log.warn("Rate limiter returned null for key={} — failing open (Redis degraded?)", bucketKey);
                return true;
            }
            return current <= authLimit;
        } catch (org.springframework.data.redis.RedisConnectionFailureException
                | org.springframework.data.redis.RedisSystemException e) {
            // Fail open ONLY on Redis infrastructure errors (connection refused, timeout,
            // Redis system error) — never block legitimate users when Redis is temporarily
            // unavailable. Other RuntimeExceptions (ClassCastException, NPE, etc.) propagate
            // to fail closed per OWASP "Fail Securely" principle.
            // Reference: OWASP Secure Coding Practices — "Fail Securely: handle exceptions
            // in a way that an attacker can't exploit."
            // Redis exception hierarchy:
            //   RedisConnectionFailureException extends DataAccessResourceFailureException
            //   RedisSystemException extends UncategorizedDataAccessException
            log.warn("Rate limiter Redis error for key={} — failing open", bucketKey, e);
            return true;
        }
    }

    /** Returns the configured auth rate limit (for diagnostics). */
    public int getAuthLimit() {
        return authLimit;
    }

    /** Returns the configured auth window (for diagnostics). */
    public Duration getAuthWindow() {
        return authWindow;
    }
}
