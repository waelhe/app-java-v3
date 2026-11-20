-- A2 + CodeRabbit round 6 on the full review (the reconciliation the
-- inline finding at V296 lines 34-38 asked for): the pre-fix code
-- executed only the credit reversal on every full refund, so intents
-- settled AND fully refunded BEFORE this wave carry a REFUND_DEBIT with
-- its COMMISSION_DEBIT but NO COMMISSION_REVERSAL_CREDIT — and their
-- provider balances sit at minus the commission (the A2 defect's own
-- residue in data). The runtime fix (LedgerPaymentEventListener) only
-- covers NEW refunds; the registry's completed publications are never
-- re-delivered, so the historical rows need a one-time, deploy-time
-- convergence — the V82 house precedent (the R9 balance recomputation)
-- applied to the A2 mirror.
--
-- The shape: for every payment intent whose ledger already holds the
-- settlement's COMMISSION_DEBIT and the full refund's REFUND_DEBIT but
-- not the pair's mirror member, insert the missing
-- COMMISSION_REVERSAL_CREDIT — the posted commission row's OWN amount,
-- currency, and provider (the round-1 discipline: the mirror reads the
-- posted row, never a recomputation) — and move the provider's balance
-- by exactly that amount. The source id is the runtime path's own
-- deterministic key
-- UUIDv3("commission-reversal-" || intent_id) reconstructed inside
-- PostgreSQL with the V82-validated md5() nibble surgery (verified
-- character-for-character against the JDK derivation there).
--
-- Atomicity and re-run safety: ONE data-modifying CTE statement — the
-- entry INSERT rides the V19 source_id UNIQUE (ON CONFLICT DO NOTHING),
-- and the balance moves as a UPSERT whose DO UPDATE side adds exactly
-- the amounts this run actually inserted (RETURNING restricts it to rows
-- this run created), aggregated to ONE row per (provider_id, currency) —
-- a provider with two deserving pre-fix refunds moves his balance once,
-- by the sum of both reversals (CodeRabbit round 7). A second execution
-- finds every reversal row already present, inserts nothing, returns
-- nothing, and moves nothing — the V57 re-runnable letter. The migration
-- itself writes no Envers revisions (the V73/V82 precedent: SQL-level
-- convergence is not a business revision).
--
-- Zero-commission pre-fix refunds (rate 0.00 or a computed zero) wrote no
-- COMMISSION_DEBIT row at all — the join finds nothing for them and they
-- are correctly left untouched (nothing was ever over-debited), the same
-- separation the runtime guard (paymentCreditPosted) now makes.
--
-- Numbering note (the house renumber-at-rebase precedent, V229/V230):
-- this file takes V298 on THIS branch; the delivery-fee wave in flight
-- renumbers its own pair when it rebases onto the merged main.

WITH per_intent AS (
    -- CodeRabbit round 9: the eligibility cut — only REFUNDED intents can
    -- hold a deserving refund (the refund debit lands in the very payments
    -- transaction that flips the status, so every intent still needing a
    -- reversal is REFUNDED; the cut spares the derivation work over the
    -- rest of the table without excluding a single deserving row).
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
    -- CodeRabbit round 7: ONE row per balance key — a provider with TWO
    -- deserving pre-fix refunds (same (provider, currency)) moves his
    -- balance once, by the SUM of the two reversals, never two separate
    -- upserts racing the same key inside one statement.
    SELECT i.provider_id, i.currency, SUM(i.amount_cents) AS amount_cents
    FROM inserted i
    GROUP BY i.provider_id, i.currency
)
INSERT INTO provider_balances (provider_id, currency, available_cents, created_at, updated_at,
                               version)
SELECT b.provider_id, b.currency, b.amount_cents, now(), now(),
       -- the optimistic-lock column's initial value (the V25/V82 letter:
       -- a born row starts at version 0; is_deleted rides its own DEFAULT
       -- false)
       0
FROM inserted_by_balance b
ON CONFLICT (provider_id, currency)
    DO UPDATE SET available_cents = provider_balances.available_cents + EXCLUDED.available_cents,
                  -- CodeRabbit round 9: the raw UPDATE bumps the optimistic
                  -- version exactly as every JPA save does — a payment
                  -- transaction holding the pre-reconciliation entity then
                  -- FAILS its save (stale version) instead of silently
                  -- overwriting the reconciled balance with its own stale
                  -- number (the lost-update the round asked to close).
                  version = provider_balances.version + 1,
                  updated_at = now();
