package com.marketplace.shared.api;

import java.time.Instant;
import java.util.UUID;

/**
 * L25 (feature-expansion roadmap §5): read-only port for the provider-stats
 * windowed revenue — the ledger module owns the entry types and their signs,
 * so the net computation lives behind this boundary. Implemented by
 * {@code LedgerStatsAdapter} (marketplace-ledger), consumed by the provider
 * module's stats aggregation. Same pattern as {@code ReviewStatsPort} (L21).
 */
public interface LedgerStatsPort {

    /**
     * The provider's net ledger movement inside the window:
     * {@code PAYMENT_CREDIT} minus {@code COMMISSION_DEBIT} minus
     * {@code REFUND_DEBIT}, summed over entries whose {@code created_at}
     * lies in {@code [from, to)} (the house exclusive-end convention — the
     * statement's own ordering key, {@code createdAt}, is the window key).
     * Zero when no entry falls inside the window.
     */
    long findNetCentsForProviderBetween(UUID providerId, Instant from, Instant to);
}
