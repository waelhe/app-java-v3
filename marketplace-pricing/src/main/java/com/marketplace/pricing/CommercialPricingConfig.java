package com.marketplace.pricing;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link CommercialPricingProperties} — the commercial-policy
 * configuration (the default tax jurisdiction). Spring owns the bean
 * lifecycle; {@link CommercialPricingService} consumes it (framework-
 * managed, no manual surface — the {@code CurrencyExchangeConfig} twin).
 */
@Configuration
@EnableConfigurationProperties(CommercialPricingProperties.class)
class CommercialPricingConfig {
}
