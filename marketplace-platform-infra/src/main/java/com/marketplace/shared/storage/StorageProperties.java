package com.marketplace.shared.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Type-safe configuration properties for file storage.
 *
 * <p>Follows the same pattern as {@code MarketplaceProperties} (record +
 * {@code @ConfigurationProperties} + {@code @DefaultValue}).
 *
 * <p>Reference: Spring Boot Reference -- Type-safe Configuration Properties:
 * "Constructor binding can be used with records."
 * https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties
 */
@ConfigurationProperties(prefix = "app.storage")
public record StorageProperties(
    @DefaultValue("uploads") String uploadDir,
    @DefaultValue("10MB") String maxFileSize
) {
}
