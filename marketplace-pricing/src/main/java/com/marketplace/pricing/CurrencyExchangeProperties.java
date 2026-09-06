package com.marketplace.pricing;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.util.Map;

/**
 * Type-safe configuration for the currency exchange channel (roadmap B4 /
 * gap G-PROD-4), constructor binding primed with defaults per the house
 * rule (AGENTS.md).
 *
 * <p>When no rates are bound the channel is inert by design: no exchange
 * beans are created ({@link StaticRatesConfiguredCondition}) and
 * {@code PricingService.convert} answers 503 SU-001 — the same
 * graceful-provider-gate pattern as MAIL / MEDIA_S3 / PAYMENTS_STRIPE
 * (SYSTEM.md §15 debt item 3). The provider choice (static config today, a
 * live rates API when the deployment decides to bind one) is a deployment
 * decision, not a code decision.</p>
 */
@ConfigurationProperties(prefix = "marketplace.pricing.currency.exchange")
public record CurrencyExchangeProperties(
        @DefaultValue("SAR") String baseCurrency,
        @DefaultValue Map<String, java.math.BigDecimal> rates
) {

    /**
     * Rate semantics: units of {@code baseCurrency} per 1 unit of the
     * currency CODE — e.g. {@code USD=3.75} with base SAR means
     * 1 USD = 3.75 SAR. Keys are ISO 4217 codes; binding is case-insensitive
     * (relaxed binding), normalised to uppercase at read time.
     */
    public CurrencyExchangeProperties {
        baseCurrency = (baseCurrency == null || baseCurrency.isBlank())
                ? com.marketplace.shared.api.Currencies.DEFAULT_CODE
                : baseCurrency.strip().toUpperCase(java.util.Locale.ROOT);
        rates = rates == null ? Map.of() : normalizeKeys(rates);
    }

    private static Map<String, java.math.BigDecimal> normalizeKeys(
            Map<String, java.math.BigDecimal> source) {
        var normalized = new java.util.LinkedHashMap<String, java.math.BigDecimal>();
        source.forEach((code, rate) -> normalized.put(
                code.strip().toUpperCase(java.util.Locale.ROOT), rate));
        return java.util.Collections.unmodifiableMap(normalized);
    }
}
