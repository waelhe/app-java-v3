package com.marketplace.pricing;

import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.type.AnnotatedTypeMetadata;

import java.util.Arrays;

/**
 * Registers the static-rates exchange bean only when at least one rate is
 * bound (any {@code marketplace.pricing.currency.exchange.rates.<CODE>}
 * property source entry — YAML map or relaxed-binding env var). This is the
 * framework's own conditional SPI — the map-valued equivalent of
 * {@code @ConditionalOnProperty}, the same shape as the payments module's
 * {@code StripeChannelConfiguredCondition}.
 *
 * <p>The no-rates state is the documented inert default: no exchange beans
 * exist, {@code PricingService.convert} keeps the 503 SU-001 dormant answer,
 * and nothing else changes.</p>
 */
class StaticRatesConfiguredCondition implements Condition {

    private static final String RATES_KEY_PREFIX = "marketplace.pricing.currency.exchange.rates";

    @Override
    public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
        if (!(context.getEnvironment() instanceof ConfigurableEnvironment environment)) {
            return false;
        }
        return environment.getPropertySources().stream()
                .filter(EnumerablePropertySource.class::isInstance)
                .map(EnumerablePropertySource.class::cast)
                .flatMap(source -> Arrays.stream(source.getPropertyNames()))
                .anyMatch(name -> name.toLowerCase(java.util.Locale.ROOT)
                        .startsWith(RATES_KEY_PREFIX + ".")
                        || name.toLowerCase(java.util.Locale.ROOT)
                        .startsWith(RATES_KEY_PREFIX + "["));
    }
}
