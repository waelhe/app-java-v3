-- W5 (yelp-level plan §5 — the ads & billing wave): the ad intent's own
-- lookup index, built the LIVE-TABLE way — the V51/V67/V80/V81 law
-- verbatim. payment_intents carries every payment creation and settlement
-- in production; a plain CREATE INDEX inside V100's transaction would hold
-- the write lock against all of them for the build's whole duration.
-- CREATE INDEX CONCURRENTLY builds without blocking writes; the sibling
-- .conf file (executeInTransaction=false) is the Flyway-documented form
-- for non-transactional migrations, and the project's
-- spring.flyway.postgresql.transactional-lock: false (set since V51)
-- lets the pair apply. The documented recovery path (PostgreSQL "Building
-- Indexes Concurrently"): a failed build leaves an INVALID index — drop
-- and re-run; the migration fails loudly, never silently.
--
-- The index itself: the billing listener resolves intents by the
-- deterministic idempotency key (the column's own UNIQUE), and the
-- provider's campaign statement resolves a campaign's intents — partial
-- on the ad rows only (every pre-W5 row carries NULL ad_campaign_id and
-- stays out of the index entirely).

CREATE INDEX CONCURRENTLY idx_payment_intents_ad_campaign
    ON payment_intents (ad_campaign_id)
    WHERE ad_campaign_id IS NOT NULL AND is_deleted = FALSE;
