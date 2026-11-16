package com.marketplace.shared.api;

import java.io.Serializable;

/**
 * R9 (comprehensive-review-ar-fix plan §4/R9 — the ledger's currency): the
 * per-currency money carrier for cross-module reads. Aggregations that mix
 * currencies under one number are the R9 defect itself, so every aggregate
 * surface carries one {@code CurrencyAmount} per ISO 4217 code instead —
 * the ledger's balance reads and the provider-stats net revenue use it.
 *
 * <p><b>Serializable (the Redis cache value family):</b> the provider-stats
 * response caches this record inside its cached value (short TTL) and the
 * production cache type is Redis — the same serialization seam
 * {@code ProviderStatsResponse} documents. A non-Serializable carrier would
 * fail every cold-cache PUT with {@code IllegalStateException} (the
 * STATS-409 defect class, closed by the same rule).
 */
public record CurrencyAmount(String currency, long amountCents) implements Serializable {

    @java.io.Serial
    private static final long serialVersionUID = 1L;

    public CurrencyAmount {
        currency = Currencies.normalizeOrDefault(currency, Currencies.DEFAULT_CODE);
    }
}
