package com.marketplace.pricing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Row 11 (agent findings 2026-10-25 — CWE-834): registers the pricing
 * module's {@link PricingProperties} — the {@code SearchConfig} house
 * pattern for module-owned configuration (the MessagingConfig lineage).
 */
@Configuration
@EnableConfigurationProperties(PricingProperties.class)
class PricingConfig {
}
