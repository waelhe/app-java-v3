-- Row 25 (CodeRabbit agent findings 2026-10-25 — CWE-400): the scan
-- claim ledger — the coalescing and activation budget for the
-- saved-search matcher. Every ListingActivatedEvent used to evaluate
-- the ENTIRE global alert-enabled population before the match ledger's
-- already-reported skip, so a provider pausing and reactivating one
-- owned listing could replay population-wide matching work without
-- creating any new state (the finding's exact shape). The claim row is
-- the per-listing budget anchor: one row per listing that has EVER been
-- scanned, carrying the last ACTUAL scan's instant. The listener's
-- transaction takes a non-blocking transactional advisory lock per
-- listing (pg_try_advisory_xact_lock — the lockPerUserCreate advisory
-- family's TRY variant, so a duplicate never parks a shared event
-- listener thread for a whole population scan), reads the claim, and
-- coalesces the activation when the last scan is still inside the
-- configured cooldown (marketplace.search.savedsearches
-- .alert-scan-cooldown, default 1h; zero disables the time budget,
-- leaving only the in-flight coalescing). A scan that RUNS stamps the
-- claim inside the same transaction — a failed scan rolls the stamp
-- back with it and the registry's retry starts clean (the
-- event_publication at-least-once contract).
--
-- The saved_search_matches/event_publication precedent: an OPERATIONAL
-- ledger with no module entity and no Envers mirror — the row is born
-- by the claim stamp and mutated only by the next stamp; only the
-- LATEST scan instant matters to the coalescing decision, so there is
-- no auditable domain state to track (its trail is the matches ledger
-- and the event publication log).
--
-- Cross-module references are plain UUID columns without FK constraints
-- (the V32/media_assets, V52/listing_leads, V54/saved_searches
-- discipline): listing_id lives in the provider_listings.id space and is
-- written by the search module's listener through the activation event,
-- never through a database-level dependency.

CREATE TABLE saved_search_scan_claims (
    listing_id   UUID PRIMARY KEY,
    last_scan_at TIMESTAMPTZ NOT NULL
);
