package com.marketplace.pricing;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/**
 * Registers {@link CurrencyExchangeProperties} and — only when at least one
 * static rate is bound — the single static-rates {@link CurrencyExchangePort}
 * bean. Spring owns the bean lifecycle; the implementation is stateless and
 * fully driven by configuration (framework-managed, no manual surface).
 */
@Configuration
@EnableConfigurationProperties(CurrencyExchangeProperties.class)
class CurrencyExchangeConfig {

    @Bean
    @Conditional(StaticRatesConfiguredCondition.class)
    CurrencyExchangePort staticRatesCurrencyExchange(CurrencyExchangeProperties properties) {
        return new StaticRatesCurrencyExchange(properties);
    }
}
