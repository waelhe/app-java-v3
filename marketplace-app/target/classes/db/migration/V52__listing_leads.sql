-- L34 (realestate systems plan §5 — lead capture): the listing leads table.
-- A lead is a guest's contact message to a listing's provider — the
-- messaging module's second domain shape (the plan's chosen home: "البيت
-- الأنسب: التواصل بين طرفين رسالته الأصلية").
--
-- Cross-module references are plain UUID columns without FK constraints
-- (the V32/media_assets, V20/disputes and V48/property_details discipline):
-- the module resolves the listing's liveness and provider through the
-- shared ports and the catalog-spi named interface, never through a
-- database-level dependency.
--
-- sender_user_id is NULL for anonymous (guest) submissions and the user id
-- when a valid JWT accompanied the request (the optional-identity seam).
-- sender_ip_hash is the SHA-256 hex of the client IP — never the raw IP
-- (privacy by design): it exists solely to bound one sender's daily lead
-- volume (the G-R6 conservative initial limit; the raw value is never
-- resolvable back to an address).
--
-- CHECKs in the V44 locking shape: NOT VALID (metadata-only, enforced for
-- new rows immediately) + VALIDATE under SHARE UPDATE EXCLUSIVE so the
-- shared production database keeps serving traffic during the deploy.
-- Every BaseEntity column present from day one (V25/V32 lesson); Envers
-- mirror follows the V24 convention (V33 lesson).

CREATE TABLE listing_leads (
    id              UUID PRIMARY KEY,
    listing_id      UUID NOT NULL,
    provider_id     UUID NOT NULL,
    sender_user_id  UUID,
    sender_ip_hash  VARCHAR(64),
    contact_name    VARCHAR(120) NOT NULL,
    contact_phone   VARCHAR(32) NOT NULL,
    message         VARCHAR(2000) NOT NULL,
    status          VARCHAR(12) NOT NULL,
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    version         BIGINT NOT NULL DEFAULT 0,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_listing_leads_status CHECK (status IN ('NEW', 'READ', 'ARCHIVED')) NOT VALID,
    CONSTRAINT chk_listing_leads_ip_hash CHECK (sender_ip_hash IS NULL OR length(sender_ip_hash) = 64) NOT VALID
) ;

ALTER TABLE listing_leads VALIDATE CONSTRAINT chk_listing_leads_status;
ALTER TABLE listing_leads VALIDATE CONSTRAINT chk_listing_leads_ip_hash;

-- The provider inbox: newest first, the id is the deterministic tiebreak
-- (the L32 stable-order lesson).
CREATE INDEX idx_listing_leads_provider ON listing_leads (provider_id, created_at DESC, id DESC) WHERE is_deleted = FALSE;
-- Per-listing audit view.
CREATE INDEX idx_listing_leads_listing ON listing_leads (listing_id) WHERE is_deleted = FALSE;
-- The daily-cap count path (G-R6): one fingerprint's trailing window.
CREATE INDEX idx_listing_leads_sender_window ON listing_leads (sender_ip_hash, created_at) WHERE is_deleted = FALSE AND sender_ip_hash IS NOT NULL;

-- Envers audit history (V24 convention).
CREATE TABLE listing_leads_aud (
    id              UUID NOT NULL,
    rev             INTEGER NOT NULL,
    revtype         SMALLINT,
    listing_id      UUID,
    provider_id     UUID,
    sender_user_id  UUID,
    sender_ip_hash  VARCHAR(64),
    contact_name    VARCHAR(120),
    contact_phone   VARCHAR(32),
    message         VARCHAR(2000),
    status          VARCHAR(12),
    is_deleted      BOOLEAN,
    version         BIGINT,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ,
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
