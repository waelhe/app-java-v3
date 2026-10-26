-- Row 18 (CodeRabbit agent findings, export 2026-10-26 — the polls' stable
-- author id): the board's polls were born (V100) with the display label
-- alone — author_label, a committee string, never a person — so the
-- authored-text purge (b-3) had no reliable ownership key for them:
-- purging by the label string would miss every relabeled row and falsely
-- hit every identically-labeled stranger. This migration gives the
-- aggregate the same stable ownership every authored community surface
-- already carries (V61 posts / V83 events / V90 market items: author_id
-- UUID in the users.id space, a plain column, no FK across module
-- boundaries — the V32/V48/V52/V54/V60/V61/V73/V83/V90/V91 discipline).
--
-- The ownership recovery (measured, two arms — the attribution available
-- BEFORE the b-4 audit purge scrubs it):
--   (a) live accounts: users.subject = created_by exactly (subject is
--       UNIQUE since V1 — the match resolves to one user by construction);
--   (b) already-pseudonymized rows: the Envers mirror's FIRST revision
--       still carries the original created_by (the V24 twin — created_by
--       is written once, at the creation revision), resolved the same
--       exact way.
-- Rows neither arm resolves keep NULL — the column stays NULLABLE on
-- purpose, and that is the measured forward-only shape, not an omission:
-- the seed's three demo rows (created_by = 'seed') resolve through the
-- repeatable seed's own re-apply (R__ files re-run after every versioned
-- migration whenever their checksum changes — Flyway's documented
-- repeatable semantics — and the seed now carries author_id), while the
-- write path itself is NOT NULL by construction: the entity maps
-- nullable = false and its factory requires the id, so Hibernate's
-- flush-time check enforces it on every new row.
--
-- The purge seam's index follows the V61/V90 shape verbatim: NON-partial
-- on (author_id, created_at, id) — the b-3 purge scan reads through it
-- with native SQL (Hibernate's soft-delete filter never sees native
-- statements), so a partial index would hide exactly the rows the seam
-- exists to see.
--
-- Checksum registered in migration-checksums.properties in this same
-- commit (MigrationChecksumGuardTest — the 2026-09-14 incident class).

ALTER TABLE neighborhood_polls ADD COLUMN author_id UUID;

-- (a) the live accounts: the exact subject match.
UPDATE neighborhood_polls p
SET author_id = u.id
FROM users u
WHERE p.author_id IS NULL
  AND p.created_by IS NOT NULL
  AND u.subject = p.created_by;

-- (b) the already-pseudonymized: the Envers mirror's first revision
-- carries the original creator (created_by is written once, at the
-- creation revision).
UPDATE neighborhood_polls p
SET author_id = u.id
FROM (SELECT DISTINCT ON (a.id) a.id, a.created_by
      FROM neighborhood_polls_aud a
      WHERE a.created_by IS NOT NULL
      ORDER BY a.id, a.rev) first_revision, users u
WHERE p.author_id IS NULL
  AND first_revision.id = p.id
  AND u.subject = first_revision.created_by;

-- The purge seam (b-3) + the export order (b-2): the V61/V90 NON-partial
-- author index shape.
CREATE INDEX idx_neighborhood_polls_author
    ON neighborhood_polls (author_id, created_at, id);

-- The Envers twin (the V33 lesson: a base-table column without its _aud
-- twin breaks audit INSERTs silently) — nullable like the base column.
ALTER TABLE neighborhood_polls_aud ADD COLUMN author_id UUID;
