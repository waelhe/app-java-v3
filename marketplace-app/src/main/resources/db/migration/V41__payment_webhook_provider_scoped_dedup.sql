-- B5 (docs/codex-review-fixes-plan.md §4): webhook dedup must be scoped to the
-- event's provider (channel), not global. The UNIQUE(event_id) gate was Stripe
-- by accident of being the only channel; a future channel may emit event_ids
-- that collide with Stripe's space. The composite unique key replaces it — the
-- existing idx_payment_webhook_events_provider index still serves provider
-- lookups. No data backfill needed: with UNIQUE(event_id) it is impossible for
-- a (provider, event_id) duplicate to already exist.
ALTER TABLE payment_webhook_events
    DROP CONSTRAINT payment_webhook_events_event_id_key;

ALTER TABLE payment_webhook_events
    ADD CONSTRAINT payment_webhook_events_provider_event_id_key UNIQUE (provider, event_id);