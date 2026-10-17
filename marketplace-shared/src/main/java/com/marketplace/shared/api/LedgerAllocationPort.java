package com.marketplace.shared.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * ADR-0013 + the payments plan's Phase-1 unified finance-operator read
 * model: the ledger module's SOURCE-OWNED allocation read for one payment
 * intent — the three deterministic postings the ledger's own writer paths
 * key ({@code PAYMENT_CREDIT} on the intent id itself,
 * {@code COMMISSION_DEBIT} on {@code nameUUID("commission-" + intentId)},
 * {@code REFUND_DEBIT} on {@code nameUUID("refund-" + intentId)}) plus the
 * provider balances those postings moved. The workbench composes this
 * port with the payments module's own intent read — no module ever reads
 * another module's tables (the Modulith boundary the whole tree enforces
 * through these shared-api seams).
 *
 * <p><b>Why entries AND balances:</b> the entries are the intent's own
 * allocation history (what posted, when, for how much); the balances are
 * the provider's CURRENT position the operator needs before any correction
 * — the acceptance journey's "verify provider/payment/ledger/source
 * state" names both.
 *
 * <p><b>AD_DEBIT is deliberately absent:</b> ad debits are keyed by
 * {@code (campaign, window)} — they are the BILL the ad intent settles,
 * not an allocation OF that intent. An intent's allocation is exactly the
 * three intent-keyed postings above.
 */
public interface LedgerAllocationPort {

    /**
     * The allocation facts for one payment intent: the intent-keyed ledger
     * entries (any of the three that exist — an uncollected intent has
     * none) and the current balances, per currency, of every provider the
     * returned entries moved.
     */
    LedgerAllocation findByPaymentIntentId(UUID paymentIntentId);

    /** One intent-keyed posting, typed by the ledger's own vocabulary. */
    record LedgerAllocationEntry(
            String entryType,
            long amountCents,
            String currency,
            UUID providerId,
            Instant postedAt
    ) {
    }

    /** One provider's current balance in one currency. */
    record ProviderBalanceFact(
            UUID providerId,
            String currency,
            long balanceCents
    ) {
    }

    /**
     * The composed answer. {@code netAllocationCents} is derived per
     * currency: {@code PAYMENT_CREDIT − COMMISSION_DEBIT − REFUND_DEBIT}
     * (the provider's earned-so-far from THIS intent; the ledger's own
     * sign convention, surfaced read-only — the ledger module owns the
     * arithmetic, the operator surface never re-derives it).
     */
    record LedgerAllocation(
            List<LedgerAllocationEntry> entries,
            List<ProviderBalanceFact> providerBalances,
            List<CurrencyAmount> netAllocation
    ) {

        /** The honest empty answer for an intent with no postings yet. */
        public static LedgerAllocation empty() {
            return new LedgerAllocation(List.of(), List.of(), List.of());
        }
    }
}
