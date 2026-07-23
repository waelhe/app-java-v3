@org.springframework.modulith.NamedInterface("shared-cache")
/**
 * Shared cache infrastructure for distributed cache invalidation via
 * Redis Pub/Sub.
 *
 * <p>Uses Spring Data Redis 4.1's {@code @RedisListener} for
 * annotation-driven Pub/Sub listener endpoints.
 */
package com.marketplace.shared.cache;

import org.springframework.modulith.NamedInterface;
