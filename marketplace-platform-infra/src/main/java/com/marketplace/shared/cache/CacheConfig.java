package com.marketplace.shared.cache;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;

/**
 * Cache configuration separated from the main application class,
 * following Spring Boot best practices.
 */
@Configuration
@EnableCaching
public class CacheConfig {
}
