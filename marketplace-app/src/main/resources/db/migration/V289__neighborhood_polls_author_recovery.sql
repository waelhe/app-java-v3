-- Row 9 (CodeRabbit agent findings, 2026-10-26 14:33 export — the V286
-- recovery's pseudonymized-account gap + the audit mirror's unpopulated
-- historical revisions): the CORRECTIVE migration.
--
-- The measured gap: V286's two recovery arms both match users.subject —
-- (a) the live accounts, (b) the Envers first revision of the POLL row
-- resolved through users.subject. An account ALREADY pseudonymized when
-- V286 ran carries its REPLACEMENT pseudonym in users.subject, so neither
-- arm resolved the ORIGINAL subject the poll's created_by holds — those
-- authored polls stayed unattributed and the content purge (keyed by the
-- stable author_id) silently left their text in place.
--
-- (c) the third arm — the original subject recovered from the users
--     Envers history itself (the declared b-4 residual: the old→new
--     mapping pseudonymization deliberately keeps). The FIRST revision of
--     each users row carries the subject the account was born with —
--     written once, at creation — so matching the poll's created_by
--     against that first-revision subject resolves exactly the rows the
--     first two arms missed (a pseudonymization after the poll's creation
--     moves users.subject but never touches the creation revision).
--
-- (d) the audit twin's backfill — neighborhood_polls_aud.author_id was
--     added (V286) but never populated for PRE-V286 revisions, so the
--     purge's _aud statements (keyed by author_id) missed every
--     historical revision of every attributed poll: a purged author's
--     question text survived in the audit history. The mirror's rows key
--     by the base row's id (Envers), so the base table's recovered
--     author_id backfills every revision of every attributed poll in one
--     statement.
--
-- Rows still unattributed after this migration keep NULL — the seed's
-- demo rows (created_by = 'seed') resolve through the repeatable seed's
-- own re-apply (R__ files re-run after every versioned migration whenever
-- their checksum changes — Flyway's documented repeatable semantics — and
-- the seed now carries author_id); the write path itself is NOT NULL by
-- construction (the entity factory requires the id).
--
-- Checksum registered in migration-checksums.properties in this same
-- commit (MigrationChecksumGuardTest — the 2026-09-14 incident class).

-- (c) the already-pseudonymized: the ORIGINAL subject from the users
-- creation revision (the b-4 residual the erasure deliberately keeps).
-- Determinism (review round, CodeRabbit on the 4a split, 2026-10-28):
-- a pseudonymized account frees its subject, so a LATER account may be
-- born carrying the same subject — PostgreSQL's UPDATE ... FROM picks
-- ARBITRARILY among matching rows, so the bare join could attribute the
-- poll to the reuser. The original owner is picked deterministically:
-- per subject, the EARLIEST-created account born with it (tie-break by
-- id) — the polls this arm targets were authored BEFORE the original
-- owner's pseudonymization, and any later same-subject account is a
-- reuser by construction; V286's live-arm already attributed the
-- reuser's own polls through users.subject.
WITH first_poll_revision AS (
    SELECT DISTINCT ON (a.id) a.id, a.created_by
    FROM neighborhood_polls_aud a
    WHERE a.created_by IS NOT NULL
    ORDER BY a.id, a.rev
),
first_user_revision AS (
    SELECT DISTINCT ON (id) id, subject
    FROM users_aud
    ORDER BY id, rev
),
born_subjects AS (
    -- every account paired with the subject it was born with
    SELECT fur.subject AS born_subject, u.id AS user_id, u.created_at
    FROM first_user_revision fur
    JOIN users u ON u.id = fur.id
),
original_owner AS (
    -- per subject: the earliest-born account — the deterministic owner
    SELECT DISTINCT ON (born_subject) born_subject, user_id
    FROM born_subjects
    ORDER BY born_subject, created_at, user_id
)
UPDATE neighborhood_polls p
SET author_id = original_owner.user_id
FROM first_poll_revision, original_owner
WHERE p.author_id IS NULL
  AND first_poll_revision.id = p.id
  AND first_poll_revision.created_by = original_owner.born_subject;

-- (d) the audit mirror's backfill through the base row's recovered author.
UPDATE neighborhood_polls_aud a
SET author_id = p.author_id
FROM neighborhood_polls p
WHERE a.author_id IS NULL
  AND p.id = a.id
  AND p.author_id IS NOT NULL;
