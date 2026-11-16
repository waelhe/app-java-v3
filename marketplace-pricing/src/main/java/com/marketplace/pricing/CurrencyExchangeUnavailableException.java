package com.marketplace.pricing;

import com.marketplace.shared.api.BadRequestException;

/**
 * Thrown when the bound exchange implementation cannot serve a conversion
 * pair — the caller answers the documented 400 VAL-001 (an unknown rate is
 * a request problem: ask for a currency the deployment actually quotes).
 * Distinguishes itself from {@code ServiceUnavailableException}, which is
 * reserved for the unbound-channel state (503 SU-001).
 */
public class CurrencyExchangeUnavailableException extends BadRequestException {

    public CurrencyExchangeUnavailableException(String message) {
        super(message);
    }

    static CurrencyExchangeUnavailableException missingRate(String base, String code) {
        return new CurrencyExchangeUnavailableException(
                "No exchange rate is configured for " + code + " against the base " + base
                        + ". Add marketplace.pricing.currency.exchange.rates." + code
                        + " (units of " + base + " per 1 " + code + ").");
    }
}
