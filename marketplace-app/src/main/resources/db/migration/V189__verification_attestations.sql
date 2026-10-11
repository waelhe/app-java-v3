-- Phase 1 (the unified plan §10 — الهوية والأدوار والمحلية والثقة) — the
-- trust vocabulary made persistent: the verification attestation, the
-- record behind §6.5's «الثقة الأربع» and §4.5's trust/provenance
-- dimension (the discovery envelope's fifth axis).
--
-- THE FOUR TYPES ARE THE PLAN'S OWN WORDS (§6.5, verbatim semantics —
-- the shared-api TrustType enum is the Java-side twin, the D-N7
-- two-sided discipline):
--   VERIFIED_LOCAL_MEMBER  — «عضو موثق محليًا — أدلة ارتباط بالحي/المنطقة»
--   VERIFIED_BUSINESS      — «جهة/مزود موثق — تحقق هوية أو بيانات نشاط
--                             بحسب نوع الجهة»
--   COMMUNITY_ENDORSEMENT  — «توصية مجتمعية — تجربة/تزكية منسوبة لشخص
--                             حقيقي»
--   VERIFIED_SOURCE        — «محتوى موثوق المصدر — معلومة من جهة رسمية
--                             أو مصدر قابل للتحقق»
-- §6.5's no-conflation rule is the table's own design constraint
-- («لا خلط بينها»: الإقامة لا تجعل كل معلومة صحيحة، وتوثيق النشاط لا
-- يجعل تقييمه ممتازًا) — four SEPARATE facts with separate evidence,
-- never one mega «موثق» boolean (§4.5's explicit prohibition).
--
-- OWNERSHIP (§4.3): the subject is an ACCOUNT, so the table lives in
-- marketplace-identity («هوية الحساب الأساس → marketplace-identity»).
-- The attestation is the account-level TRUST FACT + its evidence
-- reference; the VERIFICATION WORKFLOWS behind each type keep their own
-- owners (§6.2: «التحقق من الجهة الرسمية منفصل عن مجرد توثيق حساب تجاري
-- أو عضوية مجتمع»; §4.3: documented membership → community+geo, business
-- files → provider, official entities → institutions). evidence_ref
-- points AT those owners' records — it copies nothing (the §4.2
-- one-record rule).
--
-- THE STATE MACHINE (the task's PENDING/GRANTED/REVOKED/REJECTED —
-- the institutions VerificationState's lifecycle vocabulary extended
-- with the two post-grant outcomes the trust lifecycle needs):
--   PENDING → GRANTED (the reviewer's positive decision — granted_at set)
--   PENDING → REJECTED (the reviewer's negative decision)
--   GRANTED → REVOKED  (withdrawal/abuse — revoked_at set; the row and
--             its audit history stay, the V178 withdraw philosophy)
--   anything else is refused by the service (the honest 409).
--
-- granted_by: the administrative reviewer (a users.id) — NULL means the
-- SYSTEM granted it (the documented «نظام/إداري» pair; the backfill-free
-- table is born with no rows, so a NULL here is always a deliberate
-- system act, never a missing audit trail).
--
-- UNIQUENESS (the task's «معرف فريد جزئي للنشط بكل (subject, type)»):
-- uq_verification_attestations_one_active — ONE GRANTED attestation per
-- (subject, trust_type). A REVOKED or REJECTED row frees the pair: a new
-- request after a rejection is legal (the fresh evidence row stays as
-- the pair's own history). PENDING rows are NOT unique-constrained —
-- several pending requests may queue; the service's review transition
-- targets one.
--
-- Indexes (one per measured read, the V93 shape):
-- (a) uq_verification_attestations_one_active — the partial unique above.
-- (b) idx_verification_attestations_subject — the subject's own list
--     (the /me read), state-scoped.
-- (c) idx_verification_attestations_type_state — the type-side scan
--     (who holds VERIFIED_LOCAL_MEMBER today — the discovery/trust joins
--     and administrative queries land here).
--
-- Cross-module discipline: subject_user_id is a plain UUID in the
-- users.id space (identity's OWN table — the existence check rides the
-- module's UserRepository; the FK is the V178 same-unit REFERENCES
-- precedent). users rows are never physically deleted (I7), so the FK
-- never blocks the erasure flows.
--
-- The table is born EMPTY (zero rows, zero live traffic): the NOT VALID
-- → VALIDATE pairs ride the documented empty-table exception (V177's
-- letter — the VALIDATE scans see zero rows and no concurrent session
-- can even see the table before this transaction closes).
--
-- All BaseEntity columns present from day one (the V25/V32 lesson); the
-- Envers mirror follows the V24 convention (the V33 lesson) — all
-- mirror columns NULLABLE (a DEL revision carries only id/rev/revtype).
--
-- Checksum registered in migration-checksums.properties in this same PR
-- (MigrationChecksumGuardTest — the 2026-09-14 incident class).

CREATE TABLE verification_attestations (
    id              UUID PRIMARY KEY,
    subject_user_id UUID NOT NULL REFERENCES users (id),
    trust_type      VARCHAR(40) NOT NULL,
    state           VARCHAR(20) NOT NULL,
    evidence_ref    VARCHAR(500),
    granted_at      TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    granted_by      UUID,
    is_deleted      BOOLEAN NOT NULL DEFAULT FALSE,
    version         BIGINT NOT NULL DEFAULT 0,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- The closed §6.5 vocabulary — the TrustType enum's SQL-side twin.
ALTER TABLE verification_attestations
    ADD CONSTRAINT verification_attestations_trust_type_check
    CHECK (trust_type IN ('VERIFIED_LOCAL_MEMBER', 'VERIFIED_BUSINESS',
                          'COMMUNITY_ENDORSEMENT', 'VERIFIED_SOURCE')) NOT VALID;

-- The closed state machine.
ALTER TABLE verification_attestations
    ADD CONSTRAINT verification_attestations_state_check
    CHECK (state IN ('PENDING', 'GRANTED', 'REVOKED', 'REJECTED')) NOT VALID;

-- The empty-table exception (see the header).
ALTER TABLE verification_attestations
    VALIDATE CONSTRAINT verification_attestations_trust_type_check;

ALTER TABLE verification_attestations
    VALIDATE CONSTRAINT verification_attestations_state_check;

-- One GRANTED attestation per (subject, type) — the service's transition
-- gates come first, this partial unique index the concurrent-insert
-- backstop; a REVOKED or REJECTED row frees the pair for a fresh request.
CREATE UNIQUE INDEX uq_verification_attestations_one_active
    ON verification_attestations (subject_user_id, trust_type)
    WHERE state = 'GRANTED' AND is_deleted = FALSE;

-- The subject's own list (the /me read), newest first.
CREATE INDEX idx_verification_attestations_subject
    ON verification_attestations (subject_user_id, state, created_at DESC, id DESC)
    WHERE is_deleted = FALSE;

-- The type-side scan (who holds which granted type today).
CREATE INDEX idx_verification_attestations_type_state
    ON verification_attestations (trust_type, state)
    WHERE is_deleted = FALSE;

-- Envers mirror (V24 convention — all columns NULLABLE).
CREATE TABLE verification_attestations_aud (
    id              UUID NOT NULL,
    rev             INTEGER NOT NULL,
    revtype         SMALLINT,
    subject_user_id UUID,
    trust_type      VARCHAR(40),
    state           VARCHAR(20),
    evidence_ref    VARCHAR(500),
    granted_at      TIMESTAMPTZ,
    revoked_at      TIMESTAMPTZ,
    granted_by      UUID,
    is_deleted      BOOLEAN,
    version         BIGINT,
    created_by      VARCHAR(200),
    created_at      TIMESTAMPTZ,
    updated_by      VARCHAR(200),
    updated_at      TIMESTAMPTZ,
    PRIMARY KEY (id, rev)
);
