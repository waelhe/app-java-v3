-- W3 (yelp-level-plan §5 — the discovery & ranking wave, G18): the
-- composite ranking column — «ترتيب مركّب (تقييم×log كمية×اكتمال×حداثة)
-- عمودًا محسوبًا بجدول يومي قائم». The score is a STORED aggregate the
-- daily job refreshes (the ListingExpiryJob/@Scheduled pattern — never a
-- per-request composition in the read path); NULL is the honest
-- «not yet ranked» state a listing carries until the job's first pass.
--
-- The plan's own criterion: «الترتيب المركّب يفضّل مكتملًا موثقًا نشطًا
-- على مكتمل ناقص صامت عند تساوي النجوم» — the four factors multiply:
-- the provider's cached rating pair, log(count), the listing's own
-- completeness, and a recency decay; the job owns the exact formula in
-- ONE place (the service), the column carries only the result.
--
-- Attribution rules (the governing plan's §1.4 + the house shapes):
--   * The provider_profiles column-addition follows the V56 form (ALTER
--     the audited table and its _aud mirror together; nullable in the
--     mirror — the V24 rule).
--   * CHECKs NOT VALID + inline VALIDATE: provider_listings carries live
--     rows, but the scan is a range-check over a freshly-added all-NULL
--     column — the V78 scale-class decision (the same class W2's V89
--     applied to provider_profiles; the V56/V66 split is the documented
--     escalation for scan-heavy shapes).
--   * No speculative secondary index: the ranking sort rides the partial
--     index below (live rows only); the id tiebreak keeps every ranking
--     page deterministic (D-N5 — the L32 total-order rule).
--
-- The score's domain: non-negative (the factors are non-negative by
-- construction); bounded by nothing the schema needs to know — the job's
-- formula is the authority, the CHECK only keeps the floor honest.

ALTER TABLE provider_listings
    ADD COLUMN IF NOT EXISTS ranking_score DOUBLE PRECISION;

ALTER TABLE provider_listings_aud
    ADD COLUMN IF NOT EXISTS ranking_score DOUBLE PRECISION;

ALTER TABLE provider_listings
    ADD CONSTRAINT chk_provider_listings_ranking_score
        CHECK (ranking_score IS NULL OR ranking_score >= 0) NOT VALID;

ALTER TABLE provider_listings
    VALIDATE CONSTRAINT chk_provider_listings_ranking_score;

-- The ranking read: the composite ordering over the live ACTIVE set the
-- search flow serves (score DESC, id DESC — the deterministic tiebreak).
-- Partial on the clean-ACTIVE set (status + expiry, the sitemap's own
-- clean-set law: never advertise what is about to answer 404).
CREATE INDEX idx_provider_listings_ranking
    ON provider_listings (ranking_score DESC, id DESC)
    WHERE is_deleted = FALSE AND status = 'ACTIVE';
