package com.marketplace.config;

import java.time.Duration;

import org.springframework.boot.cache.autoconfigure.RedisCacheManagerBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;

/**
 * L25 (feature-expansion roadmap §5): the short TTL of the
 * {@code provider-stats} cache — the roadmap's "مخبأة قصيرة TTL بمفتاح
 * المزوّد+النافذة".
 *
 * <p>Mechanism: the official Boot extension point
 * {@link RedisCacheManagerBuilderCustomizer} (spring-boot-autoconfigure —
 * the customizer is applied to the auto-configured RedisCacheManager).
 * The global entry TTL stays 1h (application.yml, pinned by
 * {@code CacheTtlConfigTest}); this cache alone overrides it.
 *
 * <p>Value derivation (not taste): the stats response is a dashboard read
 * over three modules' aggregates — write-driven invalidation would require
 * the availability/ledger/booking modules to evict a fourth module's cache,
 * so the roadmap explicitly chose TTL-bounded freshness instead. 5 minutes
 * bounds the staleness of a number whose slowest-moving input (bookings)
 * changes on the order of hours; anything shorter rebuilds three aggregate
 * queries per page refresh. The test profile's simple cache has no TTL —
 * the customizer is inert there by design (only RedisCacheManager applies
 * it).
 */
@Configuration
public class ProviderStatsCacheConfig {

    static final Duration STATS_TTL = Duration.ofMinutes(5);

    @Bean
    RedisCacheManagerBuilderCustomizer providerStatsShortTtl() {
        return builder -> builder.withCacheConfiguration("provider-stats",
                RedisCacheConfiguration.defaultCacheConfig().entryTtl(STATS_TTL));
    }
}
