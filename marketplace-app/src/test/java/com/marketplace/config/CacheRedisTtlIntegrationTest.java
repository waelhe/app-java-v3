package com.marketplace.config;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end guard for the cache-entry TTL layer, on the <b>real binding
 * path</b>: yml {@code spring.cache.redis.time-to-live} →
 * {@code CacheProperties$Redis.timeToLive} →
 * {@code org.springframework.boot.cache.autoconfigure.RedisCacheConfiguration}
 * → {@code RedisCacheManager} default cache config →
 * {@code DefaultRedisCacheWriter} writing each entry with a Redis
 * {@code Expiration} (bytecode-verified constants: entryTtl / getTimeToLive
 * / "::" key prefix / Expiration).
 *
 * <p>Why this exists: the application ran 13 named Redis caches with the
 * framework default expiration — "By default the entries never expire"
 * ({@code spring-configuration-metadata.json}, spring-boot-cache 4.1.1).
 * Invalidation is AFTER_COMMIT relay only (fires when an entity changes —
 * dead rows never do), and the caches grow by construction (query-text keys
 * on catalog-search/search-results, entity-id keys on
 * bookings/conversations/paymentIntents). This test pins that a written
 * cache entry carries a positive live TTL, so no cache entry is immortal
 * anymore.
 *
 * <p>Setup follows {@code EventPublicationResubmissionIntegrationTest}:
 * full application context on real PostgreSQL (Flyway enabled,
 * {@code ddl-auto=none}, {@code postgres:18-alpine} — same image as CI
 * services and docker-compose). Redis is provided by an isolated
 * {@code redis:8-alpine} container bound via the official
 * {@code RedisContainerConnectionDetailsFactory}
 * (spring-boot-data-redis 4.1.1, {@code @ServiceConnection} on a
 * GenericContainer with a "redis" image — same image as CI services).
 * The cache type is forced to {@code redis} (the test profile otherwise
 * overrides it to {@code simple}) with the exact TTL key/value the base
 * {@code application.yml} declares — the key pinned by
 * {@code CacheTtlConfigTest}.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        // The test profile overrides the cache type to 'simple' — force the
        // production type so this guard exercises the real Redis path.
        "spring.cache.type=redis",
        // The exact key and value the base application.yml declares (pinned
        // by CacheTtlConfigTest). Bound by CacheProperties$Redis.timeToLive.
        "spring.cache.redis.time-to-live=1h",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class CacheRedisTtlIntegrationTest {

    private static final long EXPECTED_TTL_SECONDS = 3600; // 1h

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static org.testcontainers.postgresql.PostgreSQLContainer postgres =
            new org.testcontainers.postgresql.PostgreSQLContainer(
                    DockerImageName.parse("postgres:18-alpine"))
            .withDatabaseName("marketplace");

    @Container
    @ServiceConnection
    @SuppressWarnings("resource") // Lifecycle managed by @Testcontainers extension; connection details via RedisContainerConnectionDetailsFactory
    static GenericContainer<?> redis = new GenericContainer<>(
            DockerImageName.parse("redis:8-alpine"))
            .withExposedPorts(6379);

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private StringRedisTemplate redisTemplate;

    private String probeRedisKey; // "users::ttl-probe:<uuid>" — cleaned per test

    @AfterEach
    void evictProbeEntry() {
        if (probeRedisKey != null) {
            // Delete the exact key the framework wrote (discovered via SCAN
            // in the test body) — no assumption about the key layout.
            redisTemplate.delete(probeRedisKey);
            probeRedisKey = null;
        }
    }

    @Test
    void cacheManagerIsTheOfficialRedisImplementation() {
        // The auto-configured manager the TTL binds into (spring-boot-cache
        // 4.1.1: CacheAutoConfiguration → CacheConfigurations(redis) →
        // RedisCacheConfiguration). A different manager here would mean the
        // redis properties silently bind to nothing.
        assertThat(cacheManager).isInstanceOf(RedisCacheManager.class);
        // The declared cache set is created eagerly by the framework from
        // spring.cache.cache-names (pinned in CacheTtlConfigTest).
        assertThat(cacheManager.getCacheNames()).contains("users");
    }

    @Test
    void writtenCacheEntriesCarryAFrameworkTtl() throws InterruptedException {
        Cache cache = cacheManager.getCache("users");
        assertThat(cache).as("the 'users' cache must exist").isNotNull();

        String key = "ttl-probe:" + UUID.randomUUID();
        cache.put(key, "probe-value");

        // Discover the exact key the framework wrote — no assumption about
        // the prefix layout (the first CI round proved the literal
        // "users::" guess wrong: the probe key was absent, TTL -2). SCAN for
        // the unique probe uuid; on the isolated container this is cheap and
        // exact. The discovered key doubles as the cleanup target.
        java.util.Set<String> written = discoverWrittenKeys(key);
        assertThat(written)
                .as("the framework must have written a cache entry holding key %s (found keys are the evidence)", key)
                .isNotEmpty();
        String actualKey = written.iterator().next();
        probeRedisKey = actualKey;

        // Read the TTL Redis itself carries for the entry the framework
        // just wrote. Without the time-to-live key this is -1 (no expiry —
        // immortal by the framework default); a value in (0, 3600] proves
        // the binding reached the writer and every entry now self-expires.
        Long ttl = redisTemplate.getExpire(actualKey);
        assertThat(ttl)
                .as("cache entry %s must carry a positive Redis TTL (default: never expires)", actualKey)
                .isNotNull()
                .isPositive()
                .isLessThanOrEqualTo(EXPECTED_TTL_SECONDS);

        // The entry is readable through the same cache path it was written
        // (same key conversion, same serialization) — TTL does not interfere
        // with normal cache operations.
        Cache.ValueWrapper wrapper = cache.get(key);
        assertThat(wrapper).isNotNull();
        assertThat(wrapper.get()).isEqualTo("probe-value");
    }

    @Test
    void providerStatsCacheCarriesTheShortOverrideTtl() throws InterruptedException {
        // L25 (feature-expansion roadmap §5): the roadmap's "مخبأة قصيرة TTL"
        // — the provider-stats cache alone overrides the global 1h with 5m
        // through the official RedisCacheManagerBuilderCustomizer
        // (ProviderStatsCacheConfig). Same probe pattern as the global TTL
        // test: write through the cache manager, read the TTL Redis itself
        // carries. Bound: (0, 300] seconds.
        Cache cache = cacheManager.getCache("provider-stats");
        assertThat(cache).as("the 'provider-stats' cache must exist").isNotNull();

        String key = "stats-ttl-probe:" + UUID.randomUUID();
        cache.put(key, "probe-value");

        java.util.Set<String> written = discoverWrittenKeys(key);
        assertThat(written)
                .as("the framework must have written the provider-stats probe entry")
                .isNotEmpty();
        String actualKey = written.iterator().next();
        probeRedisKey = actualKey;

        Long ttl = redisTemplate.getExpire(actualKey);
        assertThat(ttl)
                .as("provider-stats entries carry the 5m override, not the global 1h")
                .isNotNull()
                .isPositive()
                .isLessThanOrEqualTo(300L);
    }

    /**
     * Async-aware discovery of the raw key the framework wrote for a probe
     * (root cause, PR #257 CI rounds — bytecode evidence from the
     * spring-data-redis 4.1.1 jar itself):
     * {@code DefaultRedisCacheWriter.create(...)} passes
     * {@code !configurer.immediateWrites} into the writer's
     * {@code asynchronousWrites} flag, and {@code immediateWrites} defaults
     * to {@code false} — so <b>cache writes are asynchronous by
     * default</b>: {@code cache.put(key, value)} returns when the
     * CompletableFuture is dispatched, not when Redis has persisted the
     * entry. A SCAN issued immediately after the put races that future —
     * under CI load (cold JIT right after context boot) the scan can lose
     * and the probe appears absent (the same symptom this test's own
     * history comment documents from its first CI round). The bounded
     * retry waits for the framework's eventual write — production keeps
     * the framework's async default deliberately (throughput; convergence
     * is bounded by the TTL and the AFTER_COMMIT relay).
     */
    private java.util.Set<String> discoverWrittenKeys(String key) throws InterruptedException {
        java.util.Set<String> written = scanFor(key);
        long deadline = System.nanoTime() + 5_000_000_000L; // 5s bound
        while ((written == null || written.isEmpty()) && System.nanoTime() < deadline) {
            Thread.sleep(25);
            written = scanFor(key);
        }
        return written == null ? java.util.Set.of() : written;
    }

    private java.util.Set<String> scanFor(String key) {
        java.util.Set<String> keys = redisTemplate.keys("*" + key + "*");
        return keys == null ? java.util.Set.of() : keys;
    }
}
