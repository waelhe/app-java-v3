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
 * <p>Nested sections that must be safe to dereference even when no property key exists
 * are primed with an empty {@link DefaultValue}, so constructor binding always produces a
 * non-null instance: "If you want to always bind a non-null instance of {@code Security},
 * even when properties are missing, you can use an empty {@code @DefaultValue} annotation"
 * (constructor-binding section of the Spring Boot reference).
 *
 * @see <a href="https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.constructor-binding">
 *      Spring Boot — Type-safe Configuration Properties — Constructor binding</a>
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
        Session session,
        @DefaultValue OAuth2 oauth2
    ) {
        public record Jwt(
            KeyStore keystore,
            @DefaultValue("marketplace-api") String audience
        ) {
            public record KeyStore(
                @DefaultValue("") String path,
                @DefaultValue("") String password,
                @DefaultValue("") String alias,
                @DefaultValue("") String keyPassword
            ) {}
        }
        public record Session(
            @DefaultValue("2") int maxSessions
        ) {}
        public record OAuth2(
            @DefaultValue Client client
        ) {
            public record Client(
                @DefaultValue("") String clientId,
                @DefaultValue("") String secret
            ) {}
        }
    }
}
