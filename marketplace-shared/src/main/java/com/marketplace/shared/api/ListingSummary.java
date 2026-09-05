package com.marketplace.shared.api;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Serializable for the Redis cache value path: four @Cacheable sites return
 * {@code Page<ListingSummary>} (catalog-active, catalog-by-category,
 * catalog-search, search-results) and Spring Boot's default Redis value
 * serializer is {@code JdkSerializationRedisSerializer} — a non-Serializable
 * record component holder makes every cold-cache PUT throw
 * {@code NotSerializableException} → HTTP 500 (live-proven defect;
 * guard: {@code ColdCacheRedisSerializationIntegrationTest}).
 * {@code PageImpl} itself is already Serializable (spring-data-commons
 * {@code Chunk implements Serializable}, bytecode-verified). For record
 * classes the Object Serialization Specification declares serialVersionUID
 * as 0L unless explicitly declared and waives the match requirement — the
 * canonical-constructor form is the serialization contract.
 */
public record ListingSummary(
        UUID id,
        String title,
        String category,
        BigDecimal price,
        String currency,
        String providerName
) implements Serializable {
}
