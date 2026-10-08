-- Real PSP channel (roadmap B3 / gap G-PROD-3): the psp_intent_id column
-- links a local PaymentIntent to its remote counterpart so a signed webhook
-- can resolve the local intent deterministically (lookup by psp intent id,
-- metadata cross-checked). Flyway owns the schema (SYSTEM.md §7).

-- Cross-cutting debt closed in the same batch (found live, three-way proof):
-- V26 added bookings.starts_at/ends_at and V27 added
-- payment_intents.payments refunded_amount_cents to the BASE tables only —
-- the Envers audit tables were never given the matching columns. Official
-- Envers behavior (hibernate-envers 7.4.5.Final sources,
-- AddWorkUnit:35-41): audit INSERT data is built from
-- entityPersister.getPropertyNames(), i.e. EVERY entity property must exist
-- in the _aud table. Live probe on a real database after V1..V32:
-- payment_intents_aud/payments_aud lacked refunded_amount_cents,
-- bookings_aud lacked starts_at/ends_at, and an Envers-style INSERT failed
-- with 'column "refunded_amount_cents" of relation "payment_intents_aud"
-- does not exist'. Zero CI coverage caught it because no integration test
-- writes audited payments/booking entities — AuditedWritesIntegrationTest
-- (this batch) closes that hole.
alter table payment_intents add column if not exists psp_intent_id varchar(100);
alter table payment_intents_aud add column if not exists psp_intent_id varchar(100);
alter table payment_intents_aud add column if not exists refunded_amount_cents bigint;
alter table payments_aud add column if not exists refunded_amount_cents bigint;
alter table bookings_aud add column if not exists starts_at timestamptz;
alter table bookings_aud add column if not exists ends_at timestamptz;

create index if not exists idx_payment_intents_psp
    on payment_intents (psp_intent_id)
    where psp_intent_id is not null;
