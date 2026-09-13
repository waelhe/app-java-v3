package com.marketplace.catalog;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link CatalogProperties} (the MediaConfig pattern for
 * module-owned configuration).
 */
@Configuration
@EnableConfigurationProperties(CatalogProperties.class)
class CatalogConfig {
}
