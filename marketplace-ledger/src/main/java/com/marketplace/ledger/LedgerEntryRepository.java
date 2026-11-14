package com.marketplace.ledger;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.history.RevisionRepository;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, UUID>, RevisionRepository<LedgerEntry, UUID, Integer> {
    Optional<LedgerEntry> findBySourceId(UUID sourceId);

    /** Provider statement (L20): newest-first movement page for one provider.
     * Secondary id-DESC keeps ties (the credit + commission-debit pair land
     * in one listener transaction — identical createdAt) in a stable order,
     * so pagination across a tie boundary is deterministic. */
    Page<LedgerEntry> findByProviderIdOrderByCreatedAtDescIdDesc(UUID providerId, Pageable pageable);

    /**
     * W11 (the origin-state matrix's G5 closure — ADR-0013 D3): the
     * provider's {@code PAYMENT_CREDIT} entries — the settled credits the
     * settlement-eligibility read derives over (one per collected intent,
     * the credit's idempotency key being the intent id itself). Same
     * deterministic ordering law as the provider statement: newest first,
     * secondary id-DESC — the eligibility page over them is stable across
     * tie boundaries. Read-only; the derivation itself lives in the
     * payments module behind {@code LedgerReadPort}.
     */
    List<LedgerEntry> findByProviderIdAndEntryTypeOrderByCreatedAtDescIdDesc(UUID providerId,
                                                                            LedgerEntryType entryType);

    /**
     * R9 (comprehensive-review-ar-fix plan §4/R9 — the ledger's currency):
     * the provider's windowed net movement PER CURRENCY — credits (the payment
     * credit AND the full refund's commission REVERSAL credit, A2 round 6;
     * AND the delivery-fee REVERSAL credit, F-832-1) minus debits
     * (commission, delivery fee, refund, ad bill — the L25 sign convention),
     * grouped by the entry's ISO 4217 currency (the plan's own wording:
     * aggregations group by the {@code (provider, currency)} pair). The
     * pre-fix single-currency sum mixed different currencies into one
     * number and is gone with the port it served; the pre-A2 CASE subtracted
     * the reversal credit like a debit, so a window covering a fully
     * refunded settlement netted MINUS twice the commission instead of the
     * honest zero — the round-6 full-review's third sign finding. Rows come
     * back as {@code [currency (String), netCents (Long)]} tuples ordered by
     * currency; the COALESCE per group keeps an all-debit window at its
     * honest negative, and an empty window returns no rows at all.
     */
    @Query("""
            SELECT e.currency AS currency,
                   COALESCE(SUM(CASE WHEN e.entryType = com.marketplace.ledger.LedgerEntryType.PAYMENT_CREDIT
                                        OR e.entryType = com.marketplace.ledger.LedgerEntryType.COMMISSION_REVERSAL_CREDIT
                                        OR e.entryType = com.marketplace.ledger.LedgerEntryType.DELIVERY_FEE_REVERSAL_CREDIT
                                     THEN e.amountCents ELSE -e.amountCents END), 0) AS netCents
            FROM LedgerEntry e
            WHERE e.providerId = :providerId
              AND e.createdAt >= :from
              AND e.createdAt < :to
            GROUP BY e.currency
            ORDER BY e.currency
            """)
    List<Object[]> sumNetCentsByCurrencyForProviderBetween(@Param("providerId") UUID providerId,
                                                           @Param("from") Instant from,
                                                           @Param("to") Instant to);

    /**
     * A2 + CodeRabbit round 8 (the deploy-overlap closure): the runtime twin
     * of {@code V298__ledger_entries_commission_reversal_backfill.sql} — the
     * SAME one-statement convergence, verbatim semantics (the deterministic
     * {@code commission-reversal-<intentId>} UUIDv3 source key reconstructed
     * with the V82-validated md5 nibble surgery; the POSTED commission row's
     * own amount/currency/provider; the {@code missing} join that includes
     * only intents holding the settlement's COMMISSION_DEBIT and the
     * refund's REFUND_DEBIT but not the pair's mirror; the per-balance-key
     * aggregation so one provider moves once by the summed reversals; the
     * UPSERT limited by RETURNING to rows this run actually inserted).
     *
     * <p>Why a runtime twin at all: Railway keeps the PREVIOUS deployment
     * serving while the new one boots and runs Flyway — during that window
     * the OLD deployment's refund listener still writes the PRE-A2 shape
     * (REFUND_DEBIT with no mirror) and its publications COMPLETE (the
     * pre-fix code does not fail), so the registry never replays them; the
     * migration's snapshot misses exactly those. The exposure is
     * minutes-bounded (the V82 documented window), and this sweep — the
     * {@link CommissionReversalReconciliation} component's own schedule —
     * converges every straggler the snapshot missed, idempotently, until
     * the queue is empty forever. Both halves carry the same statement by
     * design: the migration is the deploy-time pass, this method the
     * post-stop pass (the reviewer's requested later reconciliation).</p>
     *
     * @return the number of missing reversal entries this run created (zero
     *         once the world has converged — the sweep's own idle signal)
     */
    @Modifying
    @Query(value = """
            WITH per_intent AS (
                -- CodeRabbit round 9: the eligibility cut — only REFUNDED
                -- intents can hold a deserving refund (the refund debit lands
                -- in the very payments transaction that flips the status, so
                -- every intent still needing a reversal is REFUNDED; the cut
                -- spares the derivation work over the rest of the table
                -- without excluding a single deserving row).
                SELECT pi.id AS intent_id,
                       (substr(md5('commission-' || pi.id::text), 1, 12) || '3'
                           || substr(md5('commission-' || pi.id::text), 14, 3)
                           || substr('89ab', ((('x' || substr(md5('commission-' || pi.id::text), 17, 1))::bit(4)::int & 3) + 1), 1)
                           || substr(md5('commission-' || pi.id::text), 18, 3)
                           || substr(md5('commission-' || pi.id::text), 21, 12))::uuid AS commission_source,
                       (substr(md5('refund-' || pi.id::text), 1, 12) || '3'
                           || substr(md5('refund-' || pi.id::text), 14, 3)
                           || substr('89ab', ((('x' || substr(md5('refund-' || pi.id::text), 17, 1))::bit(4)::int & 3) + 1), 1)
                           || substr(md5('refund-' || pi.id::text), 18, 3)
                           || substr(md5('refund-' || pi.id::text), 21, 12))::uuid AS refund_source,
                       (substr(md5('commission-reversal-' || pi.id::text), 1, 12) || '3'
                           || substr(md5('commission-reversal-' || pi.id::text), 14, 3)
                           || substr('89ab', ((('x' || substr(md5('commission-reversal-' || pi.id::text), 17, 1))::bit(4)::int & 3) + 1), 1)
                           || substr(md5('commission-reversal-' || pi.id::text), 18, 3)
                           || substr(md5('commission-reversal-' || pi.id::text), 21, 12))::uuid AS reversal_source
                FROM payment_intents pi
                WHERE pi.status = 'REFUNDED'
            ),
            missing AS (
                SELECT cd.provider_id, cd.currency, cd.amount_cents, ps.reversal_source
                FROM per_intent ps
                JOIN ledger_entries cd ON cd.source_id = ps.commission_source
                                       AND cd.entry_type = 'COMMISSION_DEBIT'
                JOIN ledger_entries rd ON rd.source_id = ps.refund_source
                                       AND rd.entry_type = 'REFUND_DEBIT'
                LEFT JOIN ledger_entries rv ON rv.source_id = ps.reversal_source
                WHERE rv.id IS NULL
            ),
            inserted AS (
                INSERT INTO ledger_entries (id, provider_id, source_id, entry_type, amount_cents,
                                            currency, created_at, updated_at)
                SELECT gen_random_uuid(), m.provider_id, m.reversal_source,
                       'COMMISSION_REVERSAL_CREDIT', m.amount_cents, m.currency, now(), now()
                FROM missing m
                ON CONFLICT (source_id) DO NOTHING
                RETURNING provider_id, currency, amount_cents
            ),
            inserted_by_balance AS (
                SELECT i.provider_id, i.currency, SUM(i.amount_cents) AS amount_cents
                FROM inserted i
                GROUP BY i.provider_id, i.currency
            )
            INSERT INTO provider_balances (provider_id, currency, available_cents, created_at, updated_at,
                                           version)
            SELECT b.provider_id, b.currency, b.amount_cents, now(), now(),
                   0
            FROM inserted_by_balance b
            ON CONFLICT (provider_id, currency)
                DO UPDATE SET available_cents = provider_balances.available_cents + EXCLUDED.available_cents,
                              version = provider_balances.version + 1,
                              updated_at = now()
            """, nativeQuery = true)
    int reconcileMissingCommissionReversals();

    /**
     * F-832-1 + CodeRabbit round 2 (the fee mirror's own deploy-overlap
     * closure): the fee twin of {@link #reconcileMissingCommissionReversals}
     * — the SAME one-statement convergence shape, verbatim semantics, on the
     * fee pair's own deterministic keys ({@code delivery-fee-<intentId>} /
     * {@code delivery-fee-reversal-<intentId>}, the V82-validated md5 nibble
     * surgery the runtime's {@code nameUUIDFromBytes} reconstructs): every
     * REFUNDED intent holding the settlement's DELIVERY_FEE_DEBIT and the
     * refund's REFUND_DEBIT but not the pair's mirror gains the missing
     * DELIVERY_FEE_REVERSAL_CREDIT — the POSTED fee row's own amount,
     * currency and provider (the postedCommissionCents discipline: never a
     * recomputation from live order facts), the per-balance-key aggregation
     * so one provider moves once by the summed reversals, the UPSERT limited
     * by RETURNING to rows this run actually inserted and the version bump
     * the round-9 stale-save interleave demanded.
     *
     * <p>Why the twin: Railway keeps the PREVIOUS deployment serving while
     * the new one boots — during that window the OLD deployment's refund
     * listener (pre-F-832-1) still writes the refund leg without the fee
     * mirror and its publications COMPLETE, so the registry never replays
     * them. No one-time migration exists for the fee pair (the feature
     * ships with this branch — every historical row the OLD code wrote is
     * exactly the pre-mirror shape this sweep converges), so this runtime
     * pass is the fee mirror's whole convergence story: hourly, idempotent,
     * silent once converged (zero = the sweep's own idle signal).</p>
     *
     * @return the number of missing fee-reversal entries this run created
     *         (zero once the world has converged)
     */
    @Modifying
    @Query(value = """
            WITH per_intent AS (
                -- the commission sweep's own eligibility cut (reviewed
                -- rounds 1-9): only REFUNDED intents can hold a deserving
                -- refund; the cut spares the derivation work over the rest
                -- of the table without excluding a single deserving row.
                SELECT pi.id AS intent_id,
                       (substr(md5('delivery-fee-' || pi.id::text), 1, 12) || '3'
                           || substr(md5('delivery-fee-' || pi.id::text), 14, 3)
                           || substr('89ab', ((('x' || substr(md5('delivery-fee-' || pi.id::text), 17, 1))::bit(4)::int & 3) + 1), 1)
                           || substr(md5('delivery-fee-' || pi.id::text), 18, 3)
                           || substr(md5('delivery-fee-' || pi.id::text), 21, 12))::uuid AS fee_source,
                       (substr(md5('refund-' || pi.id::text), 1, 12) || '3'
                           || substr(md5('refund-' || pi.id::text), 14, 3)
                           || substr('89ab', ((('x' || substr(md5('refund-' || pi.id::text), 17, 1))::bit(4)::int & 3) + 1), 1)
                           || substr(md5('refund-' || pi.id::text), 18, 3)
                           || substr(md5('refund-' || pi.id::text), 21, 12))::uuid AS refund_source,
                       (substr(md5('delivery-fee-reversal-' || pi.id::text), 1, 12) || '3'
                           || substr(md5('delivery-fee-reversal-' || pi.id::text), 14, 3)
                           || substr('89ab', ((('x' || substr(md5('delivery-fee-reversal-' || pi.id::text), 17, 1))::bit(4)::int & 3) + 1), 1)
                           || substr(md5('delivery-fee-reversal-' || pi.id::text), 18, 3)
                           || substr(md5('delivery-fee-reversal-' || pi.id::text), 21, 12))::uuid AS reversal_source
                FROM payment_intents pi
                WHERE pi.status = 'REFUNDED'
            ),
            missing AS (
                SELECT fd.provider_id, fd.currency, fd.amount_cents, ps.reversal_source
                FROM per_intent ps
                JOIN ledger_entries fd ON fd.source_id = ps.fee_source
                                       AND fd.entry_type = 'DELIVERY_FEE_DEBIT'
                JOIN ledger_entries rd ON rd.source_id = ps.refund_source
                                       AND rd.entry_type = 'REFUND_DEBIT'
                LEFT JOIN ledger_entries rv ON rv.source_id = ps.reversal_source
                WHERE rv.id IS NULL
            ),
            inserted AS (
                INSERT INTO ledger_entries (id, provider_id, source_id, entry_type, amount_cents,
                                            currency, created_at, updated_at)
                SELECT gen_random_uuid(), m.provider_id, m.reversal_source,
                       'DELIVERY_FEE_REVERSAL_CREDIT', m.amount_cents, m.currency, now(), now()
                FROM missing m
                ON CONFLICT (source_id) DO NOTHING
                RETURNING provider_id, currency, amount_cents
            ),
            inserted_by_balance AS (
                SELECT i.provider_id, i.currency, SUM(i.amount_cents) AS amount_cents
                FROM inserted i
                GROUP BY i.provider_id, i.currency
            )
            INSERT INTO provider_balances (provider_id, currency, available_cents, created_at, updated_at,
                                           version)
            SELECT b.provider_id, b.currency, b.amount_cents, now(), now(),
                   0
            FROM inserted_by_balance b
            ON CONFLICT (provider_id, currency)
                DO UPDATE SET available_cents = provider_balances.available_cents + EXCLUDED.available_cents,
                              version = provider_balances.version + 1,
                              updated_at = now()
            """, nativeQuery = true)
    int reconcileMissingDeliveryFeeReversals();
}
