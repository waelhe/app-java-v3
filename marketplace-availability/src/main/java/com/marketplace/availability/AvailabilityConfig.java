package com.marketplace.availability;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Row 11 (agent findings 2026-10-25 — CWE-834): registers the
 * availability module's {@link AvailabilityProperties} — the
 * {@code SearchConfig} house pattern for module-owned configuration
 * (the MessagingConfig lineage).
 */
@Configuration
@EnableConfigurationProperties(AvailabilityProperties.class)
class AvailabilityConfig {
}
