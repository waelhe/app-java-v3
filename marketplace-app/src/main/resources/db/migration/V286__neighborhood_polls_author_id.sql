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
-- BEFORE the b-4 audit purge scrubs it). Review round 1 (the pseudonymized
-- gap): the original arms BOTH matched users.subject — but
-- pseudonymizeAccount REPLACES users.subject with the derived pseudonym
-- while created_by keeps the original string, so every already-
-- pseudonymized account's polls resolved to nothing. The linkage now
-- reads the user's own Envers history as-of the poll's creation moment:
--   (a) audit-visible accounts — the subject on the account's latest
--       revision timestamped at/before the poll's creation
--       (revinfo.revtstmp, epoch millis) must equal created_by. This
--       covers live accounts (their history still ends at the same
--       subject) AND pseudonymized ones (the original subject lives in
--       their audit history — the declared home of the old->new mapping,
--       b-4 — not in the replaced users.subject), and it settles
--       identifier release-and-reuse deterministically: the releasing
--       account's later revisions carry the replacement subject, so it
--       stops matching polls born after the release, while a later
--       account that took the freed string has no revision at/before
--       those older polls — each poll binds to whoever held the
--       identifier at its creation, which is exactly the authorship
--       question;
--   (b) the live fallback for pre-Envers rows — accounts with no audit
--       revision at/before the poll: the exact current-subject match,
--       restricted to never-pseudonymized accounts (a released
--       identifier is resolved by (a) through the history, never by its
--       current holder).
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

-- (a) the as-of recovery: the account whose audit-current subject AT the
-- poll's creation moment IS the created_by string — covers live and
-- pseudonymized accounts alike and settles identifier reuse (the scalar
-- subquery is the account's latest revision at/before that moment;
-- revinfo.revtstmp is epoch millis, EXTRACT(EPOCH ...) * 1000 matches
-- its unit; an account with no revision yet answers NULL, i.e. did not
-- exist). Runs FIRST so the exact match below cannot poison reuse rows.
UPDATE neighborhood_polls p
SET author_id = u.id
FROM users u
WHERE p.author_id IS NULL
  AND p.created_by IS NOT NULL
  AND (
        SELECT a.subject
        FROM users_aud a
        JOIN revinfo ri ON ri.rev = a.rev
        WHERE a.id = u.id
          AND ri.revtstmp <= (EXTRACT(EPOCH FROM p.created_at) * 1000)::bigint
        ORDER BY a.rev DESC
        LIMIT 1
      ) = p.created_by;

-- (b) the live fallback: accounts with no audit revision at/before the
-- poll (pre-Envers rows) — the exact current-subject match, never for a
-- pseudonymized account (its identifier was released; (a) owns those
-- rows through the history).
UPDATE neighborhood_polls p
SET author_id = u.id
FROM users u
WHERE p.author_id IS NULL
  AND p.created_by IS NOT NULL
  AND u.subject = p.created_by
  AND u.pseudonymized_at IS NULL;

-- The purge seam (b-3) + the export order (b-2): the V61/V90 NON-partial
-- author index shape.
CREATE INDEX idx_neighborhood_polls_author
    ON neighborhood_polls (author_id, created_at, id);

-- The Envers twin (the V33 lesson: a base-table column without its _aud
-- twin breaks audit INSERTs silently) — nullable like the base column.
ALTER TABLE neighborhood_polls_aud ADD COLUMN author_id UUID;
