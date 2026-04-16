package com.marketplace.shared.resilience;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Configuration for external service communication using RestClient.
 * Resilience support (CircuitBreaker, RateLimiter) is provided by Resilience4j.
 * Retry support is provided by Resilience4j via @Retry.
 */
@Configuration
public class ExternalServiceConfig {

    @Bean
    public RestClient restClient(RestClient.Builder builder) {
        return builder
                .requestFactory(clientHttpRequestFactory())
                .build();
    }

    private ClientHttpRequestFactory clientHttpRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5_000);
        factory.setReadTimeout(10_000);
        return factory;
    }
}
