-- CodeRabbit review round on #741's part 4 (2026-10-28, Major — Security
-- & Privacy): the texts the hide-time purge could not see.
--
-- The measured gap: V289's arm (c) recovered author_id for polls whose
-- author was ALREADY pseudonymized when V286/V289 ran — but the content
-- purge (CommunityContentPurgeAdapter, keyed by the STABLE author_id)
-- runs at pseudonymization time, and for exactly those polls the
-- author_id was NULL when the account was hidden: the adapter's poll
-- statements matched nothing, the recovery then attributed the rows, and
-- the authored text (question, author label, options — base tables AND
-- Envers mirrors) stayed readable behind a hidden identity.
--
-- The corrective shape: run the adapter's OWN purge statements (verbatim
-- statement shapes — the [purged] tombstone marker, the <> marker
-- idempotence filters, the through-the-base-table joins for the mirrors)
-- restricted to the polls whose author is CURRENTLY pseudonymized
-- (users.pseudonymized_at IS NOT NULL — the same live/hidden predicate
-- V286's arms used). Idempotent by construction: polls the hide-time
-- purge already reached carry the marker and match nothing; the seed's
-- demo rows carry no author_id and join nothing; a re-run is a no-op.
--
-- Never modify existing migrations (AGENTS.md / MigrationChecksumGuardTest
-- — the 2026-09-14 incident class): this is a NEW versioned migration.
-- Checksum registered in migration-checksums.properties in this same
-- commit.

-- The base table: the poll's own two authored columns.
UPDATE neighborhood_polls p
SET question = '[purged]', author_label = '[purged]'
FROM users u
WHERE u.id = p.author_id
  AND u.pseudonymized_at IS NOT NULL
  AND (p.question <> '[purged]' OR p.author_label <> '[purged]');

-- The audit mirror — bound through the base table's ownership (the
-- adapter's own join shape: pre-V286 revisions carry NULL in the _aud
-- twin's author_id, so a direct author filter would miss them).
UPDATE neighborhood_polls_aud a
SET question = '[purged]', author_label = '[purged]'
FROM neighborhood_polls p, users u
WHERE p.id = a.id
  AND u.id = p.author_id
  AND u.pseudonymized_at IS NOT NULL
  AND (a.question <> '[purged]' OR a.author_label <> '[purged]');

-- The options die with their poll (the authored-unit rule): an option's
-- label is purged exactly when its poll's author is the hidden subject.
UPDATE neighborhood_poll_options o
SET label = '[purged]'
FROM neighborhood_polls p, users u
WHERE p.id = o.poll_id
  AND u.id = p.author_id
  AND u.pseudonymized_at IS NOT NULL
  AND o.label <> '[purged]';

UPDATE neighborhood_poll_options_aud o
SET label = '[purged]'
FROM neighborhood_polls p, users u
WHERE p.id = o.poll_id
  AND u.id = p.author_id
  AND u.pseudonymized_at IS NOT NULL
  AND o.label <> '[purged]';
