package com.marketplace.catalog;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

/**
 * Registers {@link CatalogProperties} (the MediaConfig pattern for
 * module-owned configuration).
 *
 * <p>L40: the prod fail-fast for the view visitor-fingerprint key — the
 * {@code MessagingConfig} pattern (CodeRabbit #242 round 2): the
 * fingerprint exists to make the stored marker non-enumerable (CWE-759);
 * an unset key in production would silently degrade it to an empty-key
 * HMAC — functional, but weaker than the deployment promised. Fail the
 * startup instead. (The L34 operational lesson that comes with this
 * pattern: the Railway variable {@code MARKETPLACE_CATALOG_VIEWS_IP_HASH_KEY}
 * must be set BEFORE the deploy carrying this code reaches production —
 * set with {@code skipDeploys}, ahead of the merge.)
 */
@Configuration
@EnableConfigurationProperties(CatalogProperties.class)
class CatalogConfig {

    CatalogConfig(CatalogProperties properties, Environment environment) {
        if (properties.views().ipHashKey().isBlank()
                && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException(
                    "marketplace.catalog.views.ip-hash-key must be set in the prod profile —"
                            + " the view visitor fingerprint is a keyed HMAC and an unset key"
                            + " silently weakens it (CWE-759)");
        }
    }
}
