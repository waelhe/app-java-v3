-- Agent 1 (the Saleor 458-root campaign, assignment packet PR #696): widen
-- the auth-action-token purpose vocabulary for the account lifecycle's two
-- new redemption rights (the D-N7 discipline — the enum in code and the
-- CHECK in the store move together, existing migrations immutable, the
-- widening lands as its own migration):
--
--   EMAIL_CHANGE      — the requestEmailChange/confirmEmailChange journey's
--                       one-time right (the new address's confirmation).
--   ACCOUNT_DELETION  — the accountRequestDeletion/accountDelete journey's
--                       one-time right (the caller's own erasure).
--
-- The table's own shape is untouched (the V112 columns carry both purposes
-- verbatim); only the CHECK's vocabulary grows. The column already carries
-- consumed history for the two standing purposes — the ALTER swaps the
-- constraint under its own name, which requires a drop-then-add of the
-- named constraint (a RENAME-free swap PostgreSQL expresses as two
-- statements in one script).

ALTER TABLE auth_action_tokens DROP CONSTRAINT auth_action_tokens_purpose_check;

ALTER TABLE auth_action_tokens ADD CONSTRAINT auth_action_tokens_purpose_check
    CHECK (purpose IN ('PASSWORD_RESET', 'EMAIL_VERIFICATION', 'EMAIL_CHANGE', 'ACCOUNT_DELETION'));

-- The email-change journey's pending pair: the requested NEW address rides
-- the account's own row (one pending pair per account; a re-request
-- overwrites it) so the redemption leg re-derives it from the single
-- source of truth. The Envers mirror grows the same column (the standing
-- users_aud contract).
ALTER TABLE users ADD COLUMN pending_email VARCHAR(320);
ALTER TABLE users_aud ADD COLUMN pending_email VARCHAR(320);
ALTER TABLE users ADD CONSTRAINT users_pending_email_shape_chk
    CHECK (pending_email IS NULL OR pending_email ~ '^[^@[:space:]]+@[^@[:space:]]+\.[^@[:space:]]+$');
