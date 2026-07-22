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
        AuthServer authServer,
        @DefaultValue Session session
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
        public record AuthServer(
            @DefaultValue("http://localhost:8080") String issuer
        ) {}

        /**
         * Concurrent session control configuration.
         *
         * <p>Reference: Spring Security 7.1 — Session Management:
         * "You may want to prevent a user from authenticating to the same
         * application multiple times at the same time."
         * https://docs.spring.io/spring-security/reference/servlet/authentication/session-management.html#concurrent-session-control
         *
         * @param maximumSessions         the maximum number of concurrent sessions per user
         *                                (default: 1 — prevents credential sharing)
         * @param maxSessionsPreventsLogin if {@code true}, blocks the second login;
         *                                if {@code false} (default), invalidates the first session
         */
        public record Session(
            @DefaultValue("1") int maximumSessions,
            @DefaultValue("false") boolean maxSessionsPreventsLogin
        ) {}
    }
}
