-- A-04 (official-compliance plan §6, wave A — A.1 the password-reset
-- journey + A.2 email verification): the identity module's single-use,
-- time-limited AUTH ACTION TOKENS. One table serves both flows because the
-- mechanism is ONE (measured against the official references the plan names
-- for A.1/A.2 — Boot reference/io/email.html for the mail leg, Spring
-- Security features/authentication/password-storage.html for the re-encoded
-- secret at redemption): a cryptographically random token travels out of
-- band (the reset/verification email), is stored ONLY as its SHA-256 hex
-- digest, and is redeemed exactly once inside its TTL. The OWASP Forgot
-- Password Cheat Sheet (the declared trusted community source, plan §5.3)
-- is the measured authority for the token hygiene this shape implements:
-- "Randomly generated using a cryptographically safe algorithm", "Single
-- use and expire after an appropriate period".
--
-- WHY HASH-AT-REST (not the raw token): the same cheat sheet's storage
-- guidance — a leaked database snapshot must not carry usable reset
-- credentials. The raw token exists exactly twice in the system's whole
-- life: in the outbound email (the mail leg Boot's JavaMailSender delivers)
-- and in the transient event payload that carries the deep link across the
-- module boundary (the Modulith registry row — see the PasswordReset-
-- RequestedEvent javadoc for the measured lifecycle bound on that copy).
--
-- WHY username REFERENCES auth_users (the V13 login store) with a real FK:
-- the auth_authorities precedent — the token's subject IS a login account,
-- and a token whose account no longer exists is referential garbage. The
-- pseudonymize surface deletes auth_users rows (JdbcUserDetailsManager
-- deleteUser order, 7.1.1 bytecode), so every such surface consumes
-- outstanding tokens FIRST inside its own transaction (the FK-safe order
-- and the auditable one: Envers records the consumption as a revision, an
-- ON DELETE CASCADE would erase the trail silently).
--
-- WHY the partial unique index (username, purpose) WHERE consumed_at IS
-- NULL AND is_deleted = FALSE: one LIVE token per account per purpose —
-- the DB-level single-flight rule. A re-request replaces the previous
-- outstanding token (the service marks it consumed, then inserts), so the
-- newest email always carries the only redeemable token — the V64/V73/V83/
-- V91/V100 partial-unique tool applied to the security domain.
--
-- WHY the purpose CHECK (the D-N7 discipline): every enumerated column
-- carries its DB-level membership guard — the Java enum (AuthActionToken
-- Purpose) is the application's single source of truth, this constraint is
-- the store's. Fresh table, zero rows: the membership check validates
-- inline (the V78/V99/V100 fresh-table scale-class decision — NOT VALID +
-- a separate VALIDATE migration is the populated-table pattern only).
--
-- BaseEntity house columns (version/created_by/created_at/updated_by/
-- updated_at/is_deleted — the V100 column set verbatim): @Version is not
-- decoration here — optimistic locking is the second single-use wall: two
-- concurrent redemptions of the same token both load consumed_at IS NULL,
-- the first UPDATE wins the version, the second fails with the optimistic
-- lock exception and maps to the honest 400 — exactly once, enforced at
-- the persistence layer, not by check-then-act luck.
--
-- Track A's Flyway range V110-V149 (parallel execution plan §5.3 over the
-- measured V105 state; V110/V111 consumed by A-03) — this is the range's
-- second consumption.

CREATE TABLE auth_action_tokens (
    id          UUID PRIMARY KEY,
    username    VARCHAR(50) NOT NULL,
    purpose     VARCHAR(30) NOT NULL,
    token_hash  VARCHAR(64) NOT NULL,
    expires_at  TIMESTAMPTZ NOT NULL,
    consumed_at TIMESTAMPTZ,
    is_deleted  BOOLEAN NOT NULL DEFAULT FALSE,
    version     BIGINT NOT NULL DEFAULT 0,
    created_by  VARCHAR(200),
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_by  VARCHAR(200),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_auth_action_tokens_auth_users
        FOREIGN KEY (username) REFERENCES auth_users (username),
    CONSTRAINT auth_action_tokens_purpose_check
        CHECK (purpose IN ('PASSWORD_RESET', 'EMAIL_VERIFICATION'))
);

-- The redemption lookup: hash (+purpose) finds the one row the presented
-- token maps to. Unique, live-only-aware (a consumed row still occupies
-- its hash forever — redemption history — so the index is plain, the
-- service filters the state).
CREATE UNIQUE INDEX ix_auth_action_tokens_hash
    ON auth_action_tokens (token_hash);

-- One live token per (account, purpose) — the partial-unique single-flight
-- rule above. Index-only reads for the re-request/replace and the
-- outstanding-invalidation paths.
CREATE UNIQUE INDEX ix_auth_action_tokens_live
    ON auth_action_tokens (username, purpose)
    WHERE consumed_at IS NULL AND is_deleted = FALSE;

-- The admin surfaces (updateUserStatus DISABLED, pseudonymizeAccount) scan
-- for live rows to consume — the ban-vs-verification invariant's own read.
CREATE INDEX idx_auth_action_tokens_username_live
    ON auth_action_tokens (username)
    WHERE consumed_at IS NULL AND is_deleted = FALSE;
