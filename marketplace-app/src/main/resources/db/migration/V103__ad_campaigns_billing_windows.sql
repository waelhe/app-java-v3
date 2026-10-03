-- W5 (yelp-level plan §5 — the ads & billing wave, G24): the paid
-- promotion engine. The plan's own words: «حملات (ad_campaigns: ميزانية،
-- تسعير نقرة/ظهور، مدة) + أحداث (ظهور من listing_views_daily القائم،
-- ونقرة مسجلة) + هوية نافذة فوترة لكل حملة (مؤشر billed_through + سجل
-- شحن غير قابل للتعديل لكل نافذة يجمد الاستهلاك المفوتر)».
--
-- Attribution rules (the governing plan's §1.4 + the house shapes):
--   * New tables carry every BaseEntity column from day one (the V25/V32
--     lesson) and their Envers mirrors in the V24 convention (all columns
--     nullable in the mirror — a DEL revision row carries the id alone).
--     Charges are INSERT-ONLY by service contract: no UPDATE statement
--     exists on the immutable record («سجل شحن غير قابل للتعديل») — the
--     revision trail therefore shows ADD-only rows, the strongest form of
--     the plan's freeze («يجمد الاستهلاك المفوتر»).
--   * CHECKs land NOT VALID everywhere (the V44 shape — even on new
--     tables, the house convention V58/V84 applied); each VALIDATE runs
--     in V101 under its own statement's SHARE UPDATE EXCLUSIVE alone
--     (the V68/V69 + V87 measured lesson: DROP/ADD transactions already
--     committed; sharing their transactions would scan under ACCESS
--     EXCLUSIVE and block live traffic).
--   * The one-active-campaign-per-listing law rides a partial unique
--     index (the V67/V87 backstop shape: the service's friendly 409
--     first, the index the concurrency race backstop).
--   * No FK from ad tables to users — the house convention for
--     cross-module id spaces (V58's listing_id is the precedent);
--     listing_id DOES carry an FK: provider_listings lives in this
--     module's own schema (the V5 booking_id precedent — same-module
--     integrity is the database's own job).
--
-- (1) ad_campaigns — the plan's «ميزانية، تسعير نقرة/ظهور، مدة»:
--     budget_cents (the hard ceiling — consumption caps at it, the
--     campaign ENDS when it is reached), click_price_cents +
--     impression_price_cents (both bill; a zero price honestly makes that
--     event free — the plan prices «نقرة/ظهور» together), starts_at/ends_at
--     (the duration; ends_at NULL = until the budget runs out),
--     consumed_cents (the frozen billed running total — denormalized in
--     the SAME transaction as each charge insert, so it is always exactly
--     SUM(ad_billing_charges.amount_cents); the V85 stored-rating-pair
--     precedent), billed_through (the window identity: the FIRST
--     unsettled date — everything strictly before it is frozen inside
--     immutable charge rows), currency (the money's own denomination —
--     V82's multi-currency ledger carries it through to the balance).

CREATE TABLE ad_campaigns (
    id                    UUID PRIMARY KEY,
    provider_id           UUID NOT NULL,
    listing_id            UUID NOT NULL REFERENCES provider_listings(id),
    budget_cents          BIGINT NOT NULL,
    click_price_cents     BIGINT NOT NULL DEFAULT 0,
    impression_price_cents BIGINT NOT NULL DEFAULT 0,
    consumed_cents        BIGINT NOT NULL DEFAULT 0,
    currency              VARCHAR(3) NOT NULL DEFAULT 'SAR',
    status                VARCHAR(12) NOT NULL DEFAULT 'ACTIVE',
    starts_at             TIMESTAMPTZ NOT NULL,
    ends_at               TIMESTAMPTZ,
    billed_through        DATE NOT NULL,
    is_deleted            BOOLEAN NOT NULL DEFAULT FALSE,
    version               BIGINT NOT NULL DEFAULT 0,
    created_by            VARCHAR(200),
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by            VARCHAR(200),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_ad_campaigns_budget
        CHECK (budget_cents > 0) NOT VALID,
    CONSTRAINT chk_ad_campaigns_prices
        CHECK (click_price_cents >= 0 AND impression_price_cents >= 0) NOT VALID,
    CONSTRAINT chk_ad_campaigns_consumed
        CHECK (consumed_cents >= 0 AND consumed_cents <= budget_cents) NOT VALID,
    CONSTRAINT chk_ad_campaigns_status
        CHECK (status IN ('ACTIVE', 'PAUSED', 'ENDED')) NOT VALID,
    CONSTRAINT chk_ad_campaigns_duration
        CHECK (ends_at IS NULL OR ends_at > starts_at) NOT VALID
);

-- The boost read resolves «does this listing have a live paid promotion?»
-- through this partial index alone (status + remaining budget are IN the
-- index predicate's sibling columns; the scan is the listing_id equality
-- the UNIQUE partial index below cannot serve because it filters ACTIVE).
CREATE INDEX idx_ad_campaigns_listing_live
    ON ad_campaigns (listing_id)
    WHERE is_deleted = FALSE AND status = 'ACTIVE';

-- ONE live campaign per listing (the plan's single-promotion semantics —
-- the Yelp shape): a second concurrent creation is the service's 409,
-- this index the race backstop (the V67/V87 law).
CREATE UNIQUE INDEX uq_ad_campaigns_one_active_per_listing
    ON ad_campaigns (listing_id)
    WHERE is_deleted = FALSE AND status = 'ACTIVE';

-- The provider's own ledger of campaigns (the /providers/me reads).
CREATE INDEX idx_ad_campaigns_provider
    ON ad_campaigns (provider_id)
    WHERE is_deleted = FALSE;

-- The billing run's candidate scan: unsettled windows only (the job
-- selects on billed_through < the horizon it settles to).
CREATE INDEX idx_ad_campaigns_open_billing
    ON ad_campaigns (billed_through)
    WHERE is_deleted = FALSE AND status IN ('ACTIVE', 'ENDED');

CREATE TABLE ad_campaigns_aud (
    id                    UUID NOT NULL,
    rev                   INTEGER NOT NULL,
    revtype               SMALLINT,
    provider_id           UUID,
    listing_id            UUID,
    budget_cents          BIGINT,
    click_price_cents     BIGINT,
    impression_price_cents BIGINT,
    consumed_cents        BIGINT,
    currency              VARCHAR(3),
    status                VARCHAR(12),
    starts_at             TIMESTAMPTZ,
    ends_at               TIMESTAMPTZ,
    billed_through        DATE,
    is_deleted            BOOLEAN,
    version               BIGINT,
    created_by            VARCHAR(200),
    created_at            TIMESTAMPTZ,
    updated_by            VARCHAR(200),
    updated_at            TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- (2) ad_billing_charges — the plan's «سجل شحن غير قابل للتعديل لكل
--     نافذة يجمد الاستهلاك المفوتر»: one row per (campaign, window) —
--     the window is [window_start, window_end) in UTC days, the impressions
--     and clicks are the FROZEN consumption the charge billed, and
--     amount_cents is what hit the budget (capped at the remaining
--     budget — «حملة بميزانية تنتهي بنفادها»). The partial UNIQUE on
--     (campaign_id, window_start) is the deterministic-identity backstop:
--     a re-run of the debit or two overlapping schedules for one window
--     can produce at most ONE row («إعادة تشغيل الخصم أو تداخل جدولتين
--     لنافذة واحدة تنتج قيدًا واحدًا ومفتاح مصدر واحد») — the losing
--     transaction rolls back entirely (its billed_through advance and
--     consumed_cents move ride the SAME transaction).
CREATE TABLE ad_billing_charges (
    id            UUID PRIMARY KEY,
    campaign_id   UUID NOT NULL REFERENCES ad_campaigns(id),
    window_start  DATE NOT NULL,
    window_end    DATE NOT NULL,
    impressions   BIGINT NOT NULL,
    clicks        BIGINT NOT NULL,
    amount_cents  BIGINT NOT NULL,
    currency      VARCHAR(3) NOT NULL,
    is_deleted    BOOLEAN NOT NULL DEFAULT FALSE,
    version       BIGINT NOT NULL DEFAULT 0,
    created_by    VARCHAR(200),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by    VARCHAR(200),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_ad_billing_charges_campaign_window
        UNIQUE (campaign_id, window_start),
    CONSTRAINT chk_ad_billing_charges_counts
        CHECK (impressions >= 0 AND clicks >= 0) NOT VALID,
    CONSTRAINT chk_ad_billing_charges_amount
        CHECK (amount_cents > 0) NOT VALID,
    CONSTRAINT chk_ad_billing_charges_window
        CHECK (window_end > window_start) NOT VALID
);

CREATE TABLE ad_billing_charges_aud (
    id            UUID NOT NULL,
    rev           INTEGER NOT NULL,
    revtype       SMALLINT,
    campaign_id   UUID,
    window_start  DATE,
    window_end    DATE,
    impressions   BIGINT,
    clicks        BIGINT,
    amount_cents  BIGINT,
    currency      VARCHAR(3),
    is_deleted    BOOLEAN,
    version       BIGINT,
    created_by    VARCHAR(200),
    created_at    TIMESTAMPTZ,
    updated_by    VARCHAR(200),
    updated_at    TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- (3) ad_clicks_daily — the plan's «نقرة مسجلة»: the click twin of
--     listing_views_daily (V58's exact shape — same daily grain, same
--     UNIQUE (campaign, date), same count CHECK, same insert-race retry
--     contract in the service). A click is attributed to the campaign
--     that was LIVE when it happened; the public endpoint resolves the
--     listing's one ACTIVE campaign (the partial unique index above
--     guarantees at most one), so a click on an unpromoted listing is
--     the honest 404 no-op.
CREATE TABLE ad_clicks_daily (
    id           UUID PRIMARY KEY,
    campaign_id  UUID NOT NULL REFERENCES ad_campaigns(id),
    click_date   DATE NOT NULL,
    click_count  BIGINT NOT NULL,
    is_deleted   BOOLEAN NOT NULL DEFAULT FALSE,
    version      BIGINT NOT NULL DEFAULT 0,
    created_by   VARCHAR(200),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by   VARCHAR(200),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uk_ad_clicks_daily_campaign_date
        UNIQUE (campaign_id, click_date),
    CONSTRAINT chk_ad_clicks_daily_count
        CHECK (click_count >= 1) NOT VALID
);

CREATE TABLE ad_clicks_daily_aud (
    id           UUID NOT NULL,
    rev          INTEGER NOT NULL,
    revtype      SMALLINT,
    campaign_id  UUID,
    click_date   DATE,
    click_count  BIGINT,
    is_deleted   BOOLEAN,
    version      BIGINT,
    created_by   VARCHAR(200),
    created_at   TIMESTAMPTZ,
    updated_by   VARCHAR(200),
    updated_at   TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);

-- (4) The ad payment intent's origin — the plan's «وظيفة خصم دورية تُصدر
--     نية دفع»: an ad bill is a payment intent whose payer is the
--     PROVIDER, so the booking coupling lifts exactly the way W1 lifted
--     the review's (V85's own three-part shape): booking_id loses NOT
--     NULL, origin names the path, and the cross-column CHECK pins the
--     pairing. Plain VARCHAR held by the DB check — NOT a Java enum —
--     the plan's §4.2 explicit decision, verbatim.
ALTER TABLE payment_intents ALTER COLUMN booking_id DROP NOT NULL;

ALTER TABLE payment_intents
    ADD COLUMN IF NOT EXISTS origin VARCHAR(12) NOT NULL DEFAULT 'BOOKING';

ALTER TABLE payment_intents
    ADD COLUMN IF NOT EXISTS ad_campaign_id UUID;

ALTER TABLE payment_intents
    ADD CONSTRAINT chk_payment_intents_origin
        CHECK (origin IN ('BOOKING', 'AD')) NOT VALID;

-- The origin<->pairing invariant (V85's ck_review_origin_booking law): a
-- booking exists if and only if the origin is BOOKING; an ad intent
-- carries its campaign and no booking. Every existing row is
-- origin='BOOKING' + booking_id NOT NULL + campaign NULL — the default
-- answers the CHECK for the whole live table.
ALTER TABLE payment_intents
    ADD CONSTRAINT ck_payment_intents_origin_pairing
        CHECK ((origin = 'BOOKING' AND booking_id IS NOT NULL AND ad_campaign_id IS NULL)
            OR (origin = 'AD' AND booking_id IS NULL AND ad_campaign_id IS NOT NULL)) NOT VALID;

-- The ad intent's own lookup index moved to V102: payment_intents is a
-- LIVE table and the house law (V51/V67/V80/V81) builds live-table indexes
-- with CREATE INDEX CONCURRENTLY in a non-transactional migration of its
-- own — a plain CREATE INDEX would hold the write lock against every
-- payment creation and settlement for the build's whole duration.

ALTER TABLE payment_intents_aud ADD COLUMN IF NOT EXISTS origin VARCHAR(12);
ALTER TABLE payment_intents_aud ADD COLUMN IF NOT EXISTS ad_campaign_id UUID;

-- (5) The ledger's closed set, pinned in the database at last — the
--     plan's §7 measured fact: «العمود entry_type في V19 هو VARCHAR(30)
--     بلا فحص قيمة في القاعدة — فزوج W5 يضيف AD_DEBIT إلى التعداد ويضيف
--     فحص CHECK يثبّت المجموعة المغلقة كاملة على العمود». The four
--     values are LedgerEntryType's complete membership; the enum stays
--     the authority, the CHECK the backstop (the same discipline that
--     governed V44 and V56).
ALTER TABLE ledger_entries
    ADD CONSTRAINT chk_ledger_entries_entry_type
        CHECK (entry_type IN ('PAYMENT_CREDIT', 'COMMISSION_DEBIT', 'REFUND_DEBIT', 'AD_DEBIT')) NOT VALID;
