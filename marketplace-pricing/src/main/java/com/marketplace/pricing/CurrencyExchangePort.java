package com.marketplace.pricing;

import java.math.BigDecimal;
import java.util.Currency;

/**
 * Currency conversion port (roadmap B4 / gap G-PROD-4) — the pricing
 * module's outbound FX channel, shaped after the module's own dormant-goods
 * patterns (PSP, MEDIA_S3, MAIL): implementations are optional beans
 * created only when the deployment binds them. When no implementation is
 * bound, {@code PricingService.convert} answers 503 SU-001 — the capability
 * is OFF, not broken.
 *
 * <p>The model is ISO 4217 via the JDK's {@link Currency}; amounts cross the
 * port in minor units (cents) and the implementation is responsible for the
 * target currency's default fraction digits (e.g. JPY has none).</p>
 */
public interface CurrencyExchangePort {

    /**
     * Converts a minor-unit amount from one ISO 4217 currency to another.
     *
     * @throws CurrencyExchangeUnavailableException when the implementation
     *         cannot serve the pair (missing rate for either leg)
     */
    ExchangeQuote convert(long amountMinorUnits, Currency from, Currency to);

    /**
     * Immutable conversion result: source and target minor units, the
     * effective multiplicative rate applied from {@code source} to
     * {@code target} (major-unit semantics), and a short provenance label
     * for observability (e.g. {@code static-config}).
     */
    record ExchangeQuote(
            long sourceMinorUnits,
            String sourceCurrency,
            long targetMinorUnits,
            String targetCurrency,
            BigDecimal rate,
            String rateSource
    ) {
    }
}
