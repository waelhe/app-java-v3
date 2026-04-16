package com.marketplace.shared.resilience;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * Configuration for external service communication using Spring Framework 7 RestClient.
 * Retry support is provided by Spring Framework 7's built-in retry API
 * (no external spring-retry dependency needed).
 */
@Configuration
public class ExternalServiceConfig {

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        return builder.build();
    }
}