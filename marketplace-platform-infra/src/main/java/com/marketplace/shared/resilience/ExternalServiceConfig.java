package com.marketplace.shared.resilience;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import io.netty.channel.ChannelOption;
import reactor.netty.http.client.HttpClient;

/**
 * Configuration for external service communication using WebClient.
 * Resilience support (CircuitBreaker, RateLimiter) is provided by Resilience4j.
 * Retry support is provided by Resilience4j via @Retry.
 */
@Configuration
public class ExternalServiceConfig {

    @Value("${marketplace.webclient.connect-timeout:5000}")
    private int connectTimeout;

    @Value("${marketplace.webclient.response-timeout:10s}")
    private Duration responseTimeout;

    @Bean
    public WebClient webClient(WebClient.Builder builder) {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout)
                .responseTimeout(responseTimeout);

        return builder
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
