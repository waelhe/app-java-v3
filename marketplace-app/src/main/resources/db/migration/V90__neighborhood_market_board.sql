-- L50 (the Nextdoor-2026 completeness wave — gap #5, the market board):
-- the neighborhood's own classifieds surface «سوق الحي والحراج». The
-- community module's seventh state migration, on the V60/V61/V64/V73/
-- V83 membership+posts+reports+reactions+events base — a market item is
-- one member's offered possession in exactly one neighborhood, priced
-- or gifted, until it sells or its author withdraws it.
--
-- The plan's own §7 gate («سوق الحي والحراج — طلب منتج فعلي») opened
-- with the owner's standing Nextdoor-2026 directive; the gap analysis's
-- #5 contract (docs/nextdoor-gap-analysis.md): the market board read +
-- the member publish + the author withdraw. The category and condition
-- vocabularies are the PRODUCT's own (the frontend contract
-- src/lib/neighborhood-design.ts — MARKET_CATEGORIES and the condition
-- chips measured verbatim), so the DB enumerations and the Java enums
-- share one membership, D-N7's two-sided discipline.
--
-- NUMBERING (measured 2026-10-02): V88/V89 are allocated to the OPEN
-- W2 branch (PR #489 — provider business page). This wave takes V90 so
-- the two chains stay collision-free whichever merges first; the house
-- renumber-at-merge precedent (the yelp W0/W1 waves moved V71..V74 to
-- V84..V87 at the #484 merge — content never applied to production is
-- free to renumber) applies to whichever chain lands second.
--
-- Cross-module references stay plain UUID columns without FK constraints
-- (the V32/V48/V52/V54/V60/V61/V73/V83 discipline): author_id lives in
-- the users.id space and arrives through the identity seams, location_id
-- in the geo_locations.id space resolved and LEVEL-CHECKED through
-- GeoLookupPort before any write (D-N2). There is no second aggregate
-- here — the RSVP-style owned sub-table has no market twin (a market
-- item is one row's lifecycle), so no internal FK is declared either.
--
-- The free/price pair is ONE integrity rule, not two (the events
-- registration/capacity shape): the product's own model is binary —
-- «مجاني ⇔ بلا سعر» — an item is EITHER a gift (the FREE category,
-- the design's green gift band, «الإعلانات المجانية بلا مقابل») OR a
-- priced possession (one of the four sale categories, a strictly
-- positive integer-cents amount in ISO 4217 — the V2 money shape).
-- The service validates the same rule BEFORE any write (the friendly
-- 400); this CHECK is the backstop (D-N7's shape).
--
-- CHECKs in the V44 locking shape: NOT VALID (metadata-only, enforced
-- for new rows immediately) + VALIDATE under SHARE UPDATE EXCLUSIVE —
-- the table is BORN EMPTY in this transaction (zero rows to scan, the
-- V61/V73/V83 same-transaction freedom), so the validation steps cost
-- nothing while keeping the production deploy lock-honest.
--
-- Every BaseEntity column present from day one (V25/V32 lesson); the
-- Envers mirror follows the V24 convention (V33 lesson: base-table
-- columns without the _aud twin break audit INSERTs silently).
--
-- The board index is the query's own shape: location-scoped, live-only,
-- on the complete sort key (created_at DESC, id DESC — D-N5: no shaky
-- pages; the market board is newest-first where the feed's created_at
-- led). The author index is NON-partial on purpose (the V61/V73/V83
-- reasoning verbatim): the b-2 export and the b-3 purge both scan by
-- author THROUGH Hibernate's soft-delete filter (native SQL), so a
-- partial index would hide exactly the rows those seams exist to see.
--
-- status carries the product's own two-state vocabulary (ACTIVE/SOLD —
-- «متاح»/«تم البيع»): a SOLD row STAYS on the board (the card renders
-- its state — social proof the design's grid shows), the author's
-- withdraw is the house soft delete. A mark-sold write is a documented
-- product decision that rides a future wave; the column exists from
-- day one so the read contract is complete (the V25/V32 lesson, the
-- events' featured flag's own stance).
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

CREATE TABLE neighborhood_market_items (
    id              UUID PRIMARY KEY,
    author_id       UUID NOT NULL,
    location_id     UUID NOT NULL,
    category        VARCHAR(20) NOT NULL,
    title           VARCHAR(200) NOT NULL,
    item_condition  VARCHAR(20) NOT NULL,
    price_cents     INTEGER,
    price_currency  CHAR(3),
    status          VARCHAR(20) NOT NULL,
    location_label  VARCHAR(200) NOT NULL,
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    version         BIGINT NOT NULL DEFAULT 0,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_neighborhood_market_items_category
        CHECK (category IN ('FREE', 'FURNITURE', 'ELECTRONICS', 'TOOLS', 'OTHER')) NOT VALID,
    CONSTRAINT chk_neighborhood_market_items_condition
        CHECK (item_condition IN ('LIKE_NEW', 'GOOD')) NOT VALID,
    CONSTRAINT chk_neighborhood_market_items_status
        CHECK (status IN ('ACTIVE', 'SOLD')) NOT VALID,
    CONSTRAINT chk_neighborhood_market_items_pricing
        CHECK ((category = 'FREE' AND price_cents IS NULL AND price_currency IS NULL)
            OR (category IN ('FURNITURE', 'ELECTRONICS', 'TOOLS', 'OTHER')
                AND price_cents IS NOT NULL AND price_cents > 0
                AND price_currency IS NOT NULL AND price_currency ~ '^[A-Z]{3}$')) NOT VALID
);

ALTER TABLE neighborhood_market_items VALIDATE CONSTRAINT chk_neighborhood_market_items_category;
ALTER TABLE neighborhood_market_items VALIDATE CONSTRAINT chk_neighborhood_market_items_condition;
ALTER TABLE neighborhood_market_items VALIDATE CONSTRAINT chk_neighborhood_market_items_status;
ALTER TABLE neighborhood_market_items VALIDATE CONSTRAINT chk_neighborhood_market_items_pricing;

-- The board query's own index (D-N5): location-scoped, live-only, on
-- the complete sort key — created_at DESC, id DESC — so a page boundary
-- is stable even when two items land in the same second.
CREATE INDEX idx_neighborhood_market_items_board
    ON neighborhood_market_items (location_id, created_at DESC, id DESC)
    WHERE is_deleted = FALSE;

-- The author seam (b-2 export / b-3 purge) rides a NON-partial index:
-- native SQL scans past Hibernate's soft-delete filter by design (the
-- V61 idx_neighborhood_posts_author / V83 idx_neighborhood_events_author
-- reasoning verbatim — a member's items are their personal data until
-- the retention window closes).
CREATE INDEX idx_neighborhood_market_items_author
    ON neighborhood_market_items (author_id, created_at, id);

-- Envers audit history (V24 convention) — every item's lifecycle (the
-- publish, the withdraw) is a revision; the export surface reads it.
CREATE TABLE neighborhood_market_items_aud (
    id              UUID NOT NULL,
    rev             INTEGER NOT NULL,
    revtype         SMALLINT,
    author_id       UUID,
    location_id     UUID,
    category        VARCHAR(20),
    title           VARCHAR(200),
    item_condition  VARCHAR(20),
    price_cents     INTEGER,
    price_currency  CHAR(3),
    status          VARCHAR(20),
    location_label  VARCHAR(200),
    is_deleted      BOOLEAN,
    version         BIGINT,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ,
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
