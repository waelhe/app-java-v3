package com.marketplace.shared.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * L25 (feature-expansion roadmap §5): read-only port for the provider-stats
 * windowed revenue — the ledger module owns the entry types and their signs,
 * so the net computation lives behind this boundary. Implemented by
 * {@code LedgerStatsAdapter} (marketplace-ledger), consumed by the provider
 * module's stats aggregation. Same pattern as {@code ReviewStatsPort} (L21).
 *
 * <p><b>R9 (comprehensive-review-ar-fix plan §4/R9 — the ledger's
 * currency):</b> the net movement is PER CURRENCY — one
 * {@link CurrencyAmount} per ISO 4217 code the provider's window touches.
 * The pre-fix single {@code long} summed different currencies into one
 * number (the balance defect's own class); every aggregation now groups by
 * the {@code (provider, currency)} pair, the plan's own wording. A window
 * with no entry answers the empty list.</p>
 */
public interface LedgerStatsPort {

    /**
     * The provider's net ledger movement inside the window, per currency:
     * {@code PAYMENT_CREDIT} minus {@code COMMISSION_DEBIT} minus
     * {@code REFUND_DEBIT}, grouped over entries whose {@code created_at}
     * lies in {@code [from, to)} (the house exclusive-end convention — the
     * statement's own ordering key, {@code createdAt}, is the window key)
     * and ordered by currency for a deterministic response shape. Zero-net
     * currencies appear with their honest zero (the currency moved inside
     * the window and cancelled out).
     */
    List<CurrencyAmount> findNetByCurrencyForProviderBetween(UUID providerId, Instant from, Instant to);
}
