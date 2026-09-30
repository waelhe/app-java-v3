-- R9 (comprehensive-review-ar-fix plan §4/R9 — the ledger's currency):
-- every money row learns its ISO 4217 currency, and the provider balance
-- key widens from (provider_id) to (provider_id, currency). The defect:
-- the balance aggregated DIFFERENT currencies under one provider_id key —
-- a 100 SAR credit and a 100 USD credit read as one 200-unit balance —
-- while the booking side carried the currency all along (BookingInfo's
-- own validated field; the listener measured to ignore it). The decided
-- model (the plan's own wording): ledger entries carry their currency,
-- balances are keyed (provider_id, currency), every aggregation groups by
-- the pair.
--
-- Column addition first, data convergence second, key third — the same
-- ordering discipline as V72/V73.
--
-- (1) ENTRY CURRENCY — derived from the PAYMENT INTENT the runtime path
--     itself derives from ("as the path itself reads"): every ledger
--     entry's source id IS a function of a payment intent id —
--       PAYMENT_CREDIT.source_id   = the intent id itself,
--       COMMISSION_DEBIT.source_id = UUIDv3("commission-" || intent id),
--       REFUND_DEBIT.source_id     = UUIDv3("refund-"   || intent id)
--     (LedgerService's own derivation, JDK UUID.nameUUIDFromBytes — MD5
--     with version 3 / IETF variant nibbles). The migration reconstructs
--     the SAME mapping inside PostgreSQL with md5() and the identical
--     nibble surgery:
--       substr(h,1,12) || '3' || substr(h,14,3)
--         || substr('89ab', ((('x' || substr(h,17,1))::bit(4)::int & 3) + 1), 1)
--         || substr(h,18,3) || substr(h,21,12)
--     where h = md5(prefix || intent_id::text) — validated character-for-
--     character against the JDK derivation (8000 random cases, both
--     prefixes, zero mismatches). An entry whose intent cannot be found
--     (pre-V33 residue or a manually seeded row) falls back to the house
--     default 'SAR' — exactly what the runtime path's own fallback
--     (Currencies.normalizeOrDefault) produces for a blank currency.
--     ALL rows converge (live and soft-deleted alike — the column becomes
--     NOT NULL); the mapping join is by source id only.
--
-- (2) BALANCE RECOMPUTATION — the provider balance is a materialized
--     aggregate of the entry ledger (the invariant every money path
--     maintains: an entry is written first, the same amount moves the
--     balance). The pre-fix single-currency rows are the defect's own
--     artifact (mixed sums), so they are REPLACED by the honest
--     recomputation: one row per (provider_id, currency), available_cents
--     = SUM(signed amount) over the provider's LIVE entries of that
--     currency — credits positive, commission and refund debits negative
--     (the statement's own sign convention). A provider with no entries
--     materializes no row (the read path's empty projection answers him);
--     a provider whose entries sum to zero keeps an honest zero row.
--     created_at/updated_at are stamped now() — the per-currency rows are
--     born at migration time; the Envers runtime trail of the retired
--     mixed rows stays queryable in provider_balances_aud (the migration
--     itself writes no revisions — the V73 precedent).
--
-- (3) THE COMPOSITE KEY — provider_balances' PK widens to
--     (provider_id, currency) AFTER the recomputation guarantees every
--     row carries its currency. The Envers mirror provider_balances_aud
--     gains the currency column (nullable there — a DEL revision row
--     carries the id alone, the V24/V54 precedent) and its PK widens to
--     (provider_id, currency, rev): two currency rows of one provider
--     mutated in the same revision must not collide on (provider_id, rev).
--     ledger_entries_aud gains the currency column the same way (the
--     V56/V37 mirror style).
--
-- Composite id mapping (JPA §2.4.1, Spring Data JPA "Composite keys"):
-- @EmbeddedId ProviderBalanceId(providerId, currency) — a Java record
-- embeddable, mapping-verified on the reactor's own Hibernate 7.4.5.
--
-- Plain transactional migration (no CONCURRENTLY build here — the V56/
-- V72 ALTER shape): all statements run inside Flyway's transaction; the
-- tables are small (the ledger's own content) and the deploy-overlap
-- window holds no writer that can race the recomputation into a wrong
-- sum (the old deployment writes LIVE-entry-currency-agnostic amounts to
-- the single balance row — a write that lands mid-migration simply rolls
-- the transaction back with the migration and retries on the redeploy;
-- the entries the write would have produced are the recomputation's own
-- input, so the converged state stays entry-derived either way).
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

-- ---------- (1) entry currency: the column, the convergence, the guard --

ALTER TABLE ledger_entries
    ADD COLUMN IF NOT EXISTS currency varchar(3);

ALTER TABLE ledger_entries_aud
    ADD COLUMN IF NOT EXISTS currency varchar(3);

UPDATE ledger_entries le
SET currency = mapped.currency
FROM (
    -- the three source-id shapes the runtime path derives from each intent
    SELECT pi.id AS source_id, pi.currency AS currency
    FROM payment_intents pi
    UNION ALL
    SELECT (substr(md5('commission-' || pi.id::text), 1, 12) || '3'
                || substr(md5('commission-' || pi.id::text), 14, 3)
                || substr('89ab', ((('x' || substr(md5('commission-' || pi.id::text), 17, 1))::bit(4)::int & 3) + 1), 1)
                || substr(md5('commission-' || pi.id::text), 18, 3)
                || substr(md5('commission-' || pi.id::text), 21, 12))::uuid AS source_id,
           pi.currency AS currency
    FROM payment_intents pi
    UNION ALL
    SELECT (substr(md5('refund-' || pi.id::text), 1, 12) || '3'
                || substr(md5('refund-' || pi.id::text), 14, 3)
                || substr('89ab', ((('x' || substr(md5('refund-' || pi.id::text), 17, 1))::bit(4)::int & 3) + 1), 1)
                || substr(md5('refund-' || pi.id::text), 18, 3)
                || substr(md5('refund-' || pi.id::text), 21, 12))::uuid AS source_id,
           pi.currency AS currency
    FROM payment_intents pi
) mapped
WHERE le.source_id = mapped.source_id;

-- entries with no resolvable intent keep the house default — the same
-- fallback the runtime path's Currencies.normalizeOrDefault produces.
UPDATE ledger_entries
SET currency = 'SAR'
WHERE currency IS NULL;

ALTER TABLE ledger_entries
    ALTER COLUMN currency SET NOT NULL;

-- ---------- (2) balance recomputation: the column, the honest sums ------

ALTER TABLE provider_balances
    ADD COLUMN IF NOT EXISTS currency varchar(3);

ALTER TABLE provider_balances_aud
    ADD COLUMN IF NOT EXISTS currency varchar(3);

-- the mixed-currency aggregates are the defect's own artifact — replaced
-- by the entry-derived truth, one row per (provider_id, currency).
DELETE FROM provider_balances;

INSERT INTO provider_balances (provider_id, currency, available_cents,
                               created_at, updated_at, version)
SELECT e.provider_id,
       e.currency,
       SUM(CASE WHEN e.entry_type = 'PAYMENT_CREDIT' THEN e.amount_cents
                ELSE -e.amount_cents END),
       now(),
       now(),
       0
FROM ledger_entries e
WHERE e.is_deleted = false
GROUP BY e.provider_id, e.currency;

ALTER TABLE provider_balances
    ALTER COLUMN currency SET NOT NULL;

-- ---------- (3) the composite keys ---------------------------------------

ALTER TABLE provider_balances
    DROP CONSTRAINT provider_balances_pkey;

ALTER TABLE provider_balances
    ADD PRIMARY KEY (provider_id, currency);

ALTER TABLE provider_balances_aud
    DROP CONSTRAINT provider_balances_aud_pkey;

ALTER TABLE provider_balances_aud
    ADD PRIMARY KEY (provider_id, currency, rev);
