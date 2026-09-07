-- V39: repair the provider_profiles.user_id backfill for soft-deleted users.
--
-- Codex review fix C3 (docs/codex-review-fixes-plan.md §3-C3): V23
-- (V23__backfill_provider_user_id.sql) linked provider_profiles to users by
-- display_name + role without filtering on is_deleted. Two defects, both
-- closed here WITHOUT rewriting V23 (a migration that has already shipped —
-- the C4 rule: never rewrite an applied migration; ship a new V):
--
--  (1) A profile could be linked to a user that is soft-deleted. Hibernate
--      ORM @SoftDelete (BaseEntity.java:40, column is_deleted) makes deleted
--      users invisible to every entity query, so a profile bound to a deleted
--      user is an ownerless orphan the ORM can never resolve — and the
--      soft-deleted user alone poisons the match (the V23 COUNT subquery saw
--      the deleted row and suppressed a valid single match). This migration
--      first severs every such binding (user_id -> NULL) so no profile stays
--      linked to a tombstone.
--
--  (2) The corrected backfill then re-links the now-NULL profiles to the
--      single ACTIVE (is_deleted = false) PROVIDER user whose display_name
--      matches — the exact V23 criteria plus is_deleted=false on BOTH the
--      join user and the uniqueness COUNT subquery. Rows with no active match
--      or multiple active matches stay NULL (linked manually), mirroring V23.
--
-- DML only — no schema change: user_id already exists on provider_profiles
-- (V22) and its _aud mirror (V24). Like V23/V29 before it, this data repair
-- does not touch the _aud tables: Hibernate Envers records revisions for
-- entity writes at runtime, not for migration-time DML, so the audit trail of
-- the repair itself is intentionally not written (the table was empty at
-- migration time and the next entity write snapshot is authoritative).

-- (1) First sever every profile bound to a soft-deleted user. The set-first
-- ORDER for the two statements matters: this runs the repair, then the
-- backfill below sees the repaired (NULL) rows and may re-link them to a
-- valid active user.
UPDATE provider_profiles pp
   SET user_id = NULL
  FROM users u
 WHERE pp.user_id = u.id
   AND u.is_deleted = true;

-- (2) Corrected backfill — V23's match with is_deleted=false on the join and
-- on the uniqueness COUNT (so a deleted name-twin can no longer suppress the
-- single valid active match). Only rows still unlinked are considered.
UPDATE provider_profiles pp
   SET user_id = u.id
  FROM users u
 WHERE pp.user_id IS NULL
   AND u.display_name = pp.display_name
   AND u.role = 'PROVIDER'
   AND u.is_deleted = false
   AND (SELECT COUNT(*)
          FROM users u2
         WHERE u2.display_name = pp.display_name
           AND u2.role = 'PROVIDER'
           AND u2.is_deleted = false) = 1;
