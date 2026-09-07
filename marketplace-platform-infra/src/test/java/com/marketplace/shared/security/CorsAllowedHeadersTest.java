package com.marketplace.shared.security;

import tools.jackson.databind.ObjectMapper;
import com.marketplace.shared.config.MarketplaceProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A2: the X-API-Version request header (consumed by
 * {@code ApiVersioningConfig#useRequestHeader}) must be an allowed header in
 * the CORS configuration, otherwise versioned browser clients on a configured
 * origin cannot send it in a cross-origin request.
 */
class CorsAllowedHeadersTest {

    /**
     * A2: X-API-Version must be an allowed CORS request header and the
     * origin policy is unchanged by the header addition.
     */
    @Test
    void allowsXApiVersionRequestHeaderAcrossConfiguredOrigin() {
        SecurityConfig securityConfig = new SecurityConfig(properties(), new ObjectMapper());

        UrlBasedCorsConfigurationSource source =
                (UrlBasedCorsConfigurationSource) securityConfig.corsConfigurationSource();
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/listings");
        CorsConfiguration configuration = source.getCorsConfiguration(request);

        assertThat(configuration).isNotNull();
        assertThat(configuration.getAllowedHeaders())
                .as("X-API-Version must be forwarded for versioned clients")
                .contains("X-API-Version");
        assertThat(configuration.checkOrigin("http://localhost:3000"))
                .as("configured origin remains allowed after the header change")
                .isEqualTo("http://localhost:3000");
    }

    /**
     * Minimal properties record for the {@code SecurityConfig} beans
     * under test.
     */
    private static MarketplaceProperties properties() {
        return new MarketplaceProperties(
                new MarketplaceProperties.Cors(List.of("http://localhost:3000")),
                new MarketplaceProperties.Security(
                        new MarketplaceProperties.Security.Jwt(
                                new MarketplaceProperties.Security.Jwt.KeyStore("", "", "", "", ""),
                                "marketplace-api"
                        ),
                        new MarketplaceProperties.Security.Session(2),
                        new MarketplaceProperties.Security.OAuth2(
                                new MarketplaceProperties.Security.OAuth2.Client("", "", ""),
                                new MarketplaceProperties.Security.OAuth2.PublicClient("", ""))
                )
        );
    }
}
