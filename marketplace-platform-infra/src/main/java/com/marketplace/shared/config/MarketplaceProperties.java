package com.marketplace.shared.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.List;

/**
 * Type-safe configuration properties for the marketplace application.
 *
 * <p>Replaces scattered {@code @Value} annotations with a single
 * configuration properties class, following Spring Boot best practices.
 *
 * @see <a href="https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties">
 *      Spring Boot — Type-safe Configuration Properties</a>
 */
@ConfigurationProperties(prefix = "marketplace")
public record MarketplaceProperties(
    Cors cors,
    Security security
) {
    public record Cors(
        @DefaultValue("https://marketplace.com") List<String> allowedOrigins
    ) {}

    public record Security(
        Jwt jwt,
        AuthServer authServer
    ) {
        public record Jwt(KeyStore keystore) {
            public record KeyStore(
                @DefaultValue("") String path,
                @DefaultValue("") String password,
                @DefaultValue("") String alias,
                @DefaultValue("") String keyPassword
            ) {}
        }
        public record AuthServer(
            @DefaultValue("http://localhost:8080") String issuer
        ) {}
    }
}
