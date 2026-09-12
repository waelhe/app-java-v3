package com.marketplace.shared.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The injectable system clock — the single time source for schedulable
 * business logic (L33's expiry job and the activation/renewal windows).
 * Tests override the bean (a fixed or mutable clock) to assert boundary
 * behavior deterministically; production always gets UTC.
 */
@Configuration
public class ClockConfig {

    @Bean
    Clock systemClock() {
        return Clock.systemUTC();
    }
}
