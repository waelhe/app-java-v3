-- V39: repair the provider_profiles.user_id backfill for soft-deleted users.
--
-- Codex review fix C3 (docs/codex-review-fixes-plan.md §3-C3), shaped by the
-- CodeRabbit security finding (CWE-639): V23
-- (V23__backfill_provider_user_id.sql) linked provider_profiles to users by
-- display_name + role without filtering on is_deleted. This migration closes
-- the repairable half of that defect WITHOUT rewriting V23 (a migration that
-- has already shipped — the C4 rule: never rewrite an applied migration;
-- ship a new V):
--
--  (1) A profile could be linked to a user that is soft-deleted. Hibernate
--      ORM @SoftDelete (BaseEntity.java, column is_deleted) makes deleted
--      users invisible to every entity query, so a profile bound to a deleted
--      user is an ownerless orphan the ORM can never resolve — and the
--      soft-deleted user alone poisons the match (the V23 COUNT subquery saw
--      the deleted row and suppressed a valid single match). This migration
--      severs every such binding (user_id -> NULL) so no profile stays
--      linked to a tombstone. No guessing is involved: a tombstone cannot
--      own anything, so the sever is always safe.
--
--  (2) The suppressed-match half is deliberately NOT repaired by re-linking:
--      users.display_name is not ownership evidence. It is mutable
--      identity-provider data (UserService.syncFromOidc rewrites
--      users.display_name from the OIDC "name" claim on every login), and
--      the schema never made it unique (V1 declares no UNIQUE on
--      users.display_name). Re-assigning by name could therefore bind a
--      profile to the WRONG active provider, and ProviderRepository
--      .findByUserId — the ownership "me" seam (L20) — would treat that
--      assignment as true ownership: an authorization bypass through a
--      user-controlled key (CWE-639). Per the CodeRabbit finding, rows
--      without immutable ownership evidence stay NULL for manual repair;
--      this schema holds no immutable evidence for a profile (no
--      created_by/subject/email column on provider_profiles to match
--      against), so the honest migration assigns nothing and every unlinked
--      row goes to manual repair.
--
-- DML only — no schema change: user_id already exists on provider_profiles
-- (V22) and its _aud mirror (V24). Like V23/V29 before it, this data repair
-- does not touch the _aud tables: Hibernate Envers records revisions for
-- entity writes at runtime, not for migration-time DML, so the audit trail of
-- the repair itself is intentionally not written (the table was empty at
-- migration time and the next entity write snapshot is authoritative).

-- Sever every profile bound to a soft-deleted user. This is the whole
-- migration: single statement, no re-assignment (see (2) above).
UPDATE provider_profiles pp
   SET user_id = NULL
  FROM users u
 WHERE pp.user_id = u.id
   AND u.is_deleted = true;
