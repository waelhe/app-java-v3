-- Wave D-1 (plan #536 §1.4 / JT-20 — the home discovery rails, "صفوف
-- اكتشاف موضوعية فوق سجل موحد"): discovery_impressions — the display
-- impression ledger of the home rails. One row per (user, row, source,
-- day) impression EVER rendered, guarded by a UNIQUE index; an OPERATIONAL
-- DISPLAY LEDGER, not a domain aggregate (the V93/provider_follow_alerts
-- precedent verbatim, itself the V54/saved_search_matches one — the
-- event_publication family: framework-managed operational tables with no
-- mutable domain state): rows are born complete and never mutated, so
-- there is no mutable state for an Envers mirror to track (its audit
-- trail is the row itself — no _aud mirror, no @Version, no soft delete),
-- and the bridge writes it with a native INSERT ... ON CONFLICT DO
-- NOTHING — the skip is a returned 0, never a transaction-aborting 23505.
-- The key is exactly the quadruple AC-20-03's display contract names: the
-- same card may reach the user through two rails/contexts in one day and
-- is recorded once per day — the cross-context display dedup lives HERE,
-- never in content copies (the rail itself never owns a copy of anything;
-- every card resolves to its source record, AC-20-10).
--
-- Cross-module references are plain UUID columns without FK constraints
-- (the V32/media_assets, V52/listing_leads, V54/saved_searches, V93/
-- provider_follows discipline): user_id lives in the users.id space,
-- source_id in the source record's OWN id space (NEIGHBORHOOD_POST/
-- NEIGHBORHOOD_EVENT → community, JOB → jobs, URGENT_ALERT → institutions,
-- PROVIDER_LISTING → catalog), and row_type/source_type carry the closed
-- vocabularies the discovery module's type gates enforce on the Java side
-- (the DiscoveryRowType enum and the card's sourceType set are the single
-- source of truth — the D-N7 division; no DB CHECK duplicates them).
--
-- Every column present from day one (the V25/V32 lesson); created_at is
-- database-owned (the born-complete birth moment, DEFAULT now()).

CREATE TABLE discovery_impressions (
    id             UUID PRIMARY KEY,
    user_id        UUID NOT NULL,
    row_type       VARCHAR(40) NOT NULL,
    source_type    VARCHAR(60) NOT NULL,
    source_id      UUID NOT NULL,
    impression_day DATE NOT NULL,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One impression per (user, rail, source pair, display day) ever — the
-- skip key (AC-20-03's display-contract dedup, the
-- uq_provider_follow_alerts_once twin).
CREATE UNIQUE INDEX uq_discovery_impressions_once
    ON discovery_impressions (user_id, row_type, source_type, source_id, impression_day);

-- The viewer's day scan (the ledger's future read: "what did I already
-- see today") — the newest display day first, the L32 complete-order
-- discipline (no wobbly boundaries).
CREATE INDEX idx_discovery_impressions_user_day
    ON discovery_impressions (user_id, impression_day DESC);
