package com.marketplace.shared.cache;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Cache configuration separated from the main application class,
 * following Spring Boot best practices.
 *
 * <p>{@code @EnableScheduling} activates the
 * {@code EventPublicationCleanup} scheduled task that purges old
 * completed event publications per Spring Modulith docs.
 */
@Configuration
@EnableCaching
@EnableScheduling
public class CacheConfig {
}
