package com.marketplace.shared.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * W11 (the origin-state matrix's G5 closure — ADR-0013 D3): the ledger
 * module's SOURCE-OWNED <b>settlement-eligibility read</b> port — the
 * read-only facts the payments module's eligibility derivation needs from
 * the ledger's own tables. The matrix measured the absence this port
 * closes: «لا بوابة تسوية في الكود لـ BOOKING/AD/ORDER/LOAN — ADR-0013 D3
 * يسمي الأهلية لكنها سطح قراءة تشغيلي لا كود بوابة». This is that
 * operational read surface — <b>READ-ONLY above the existing entries</b>:
 * no state machine, no money movement, no second accounting engine
 * (ADR-0013's own consequences record: "the settlement eligibility and
 * holds are read-model/operational surfaces over the existing entries").
 *
 * <p><b>Why credits and not balances alone:</b> the provider's
 * {@code (provider, currency)} balance is the books' CURRENT position, but
 * eligibility is a PER-CREDIT question — each {@code PAYMENT_CREDIT} entry
 * belongs to one payment intent whose owning domain either reached its
 * proper end (ADR-0013 D3: an order's FULFILLED, a booking's COMPLETED, an
 * ad's charged window, a loan fee's collection) or did not. The credit
 * rows are therefore the derivation's input; the balances are the
 * bookkeeping truth the derived eligible total is read against. The
 * derivation itself lives in the payments module (it owns the intent facts
 * — origin, subject, refund history — and the booking seam); the ledger
 * only answers what its own writer paths already keyed
 * ({@code PAYMENT_CREDIT} on the intent id itself).
 *
 * <p><b>AD_DEBIT is deliberately absent:</b> ad debits are keyed by
 * {@code (campaign, window)} — they are the bill an ad intent settles, not
 * an allocation OF that intent (the {@link LedgerAllocationPort} record's
 * own rule). REFUND_DEBIT and COMMISSION_DEBIT are absent for the same
 * reason they are absent from the balance arithmetic here: the balance
 * already nets them; the per-credit eligibility reads the refund history
 * from the intent itself (the payments module's own truth).
 *
 * <p>House pattern: the {@code BookingParticipantProvider}/
 * {@code PaymentIntentLookupPort} synchronous-query seam (Spring Modulith's
 * documented allowance for synchronous reads the current operation needs),
 * implemented by {@code LedgerReadAdapter} in marketplace-ledger, consumed
 * by marketplace-payments — no module ever reads another module's tables.
 */
public interface LedgerReadPort {

    /**
     * The provider's {@code PAYMENT_CREDIT} entries — one per collected
     * payment intent the ledger's completion listener credited (the credit
     * lands exactly once per intent, idempotent by source id). Ordered
     * newest-first with the secondary id-DESC tiebreak — the provider
     * statement's own deterministic ordering law — so the eligibility
     * page over them is stable across tie boundaries.
     *
     * <p><b>The honest v1 bound:</b> one provider's credits are the
     * provider's own transaction history — the same set the per-origin
     * totals need, so the port answers it whole (the
     * {@code DeliveryReconciliationService} in-memory-accumulation
     * precedent). A platform-wide aggregation would need its own bounded
     * window shape and is deliberately NOT this port's v1.
     */
    List<SettlementCredit> findCreditsByProvider(UUID providerId);

    /**
     * The provider's current balances, one row per currency he holds
     * (ordered by currency for a deterministic response shape) — the
     * bookkeeping truth the eligible total is read against. An empty list
     * is the valid answer for a provider with no ledger history.
     */
    List<LedgerAllocationPort.ProviderBalanceFact> findBalancesByProvider(UUID providerId);

    /**
     * One settled credit — the ledger's own posting of one collected
     * payment intent. {@code paymentIntentId} is the entry's source id
     * (the credit's idempotency key), so the payments module resolves the
     * intent's own facts (origin, subject, refund history) by primary key.
     */
    record SettlementCredit(
            UUID providerId,
            UUID paymentIntentId,
            long amountCents,
            String currency,
            Instant creditedAt
    ) {
    }
}
