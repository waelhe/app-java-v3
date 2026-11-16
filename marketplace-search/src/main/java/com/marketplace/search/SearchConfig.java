package com.marketplace.search;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * L35 (realestate systems plan §5 — saved searches): registers the search
 * module's {@link SearchProperties} (the {@code MessagingConfig} house
 * pattern for module-owned configuration).
 */
@Configuration
@EnableConfigurationProperties(SearchProperties.class)
class SearchConfig {
}
