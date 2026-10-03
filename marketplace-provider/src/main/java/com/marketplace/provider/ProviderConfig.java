package com.marketplace.provider;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * W2 (yelp-level plan §5 — the business page): registers
 * {@link ProviderProperties} — the MediaConfig/CatalogConfig pattern for
 * module-owned configuration (the L33 module-local record precedent).
 *
 * <p>No prod fail-fast here, deliberately: the SEO origin is a
 * capability gate, not a security key — blank means the JSON-LD block
 * omits {@code url} (the L39 honesty rule), which is a legal deployment
 * state, not a degraded one. Contrast {@code CatalogConfig}'s
 * ip-hash-key: THAT one exists to make a stored marker non-enumerable,
 * so an unset prod value is a broken promise and fails startup.
 */
@Configuration
@EnableConfigurationProperties(ProviderProperties.class)
class ProviderConfig {
}
