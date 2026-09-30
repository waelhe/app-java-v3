-- W1 (yelp-level plan §4.5 — إبلاغ المراجعة): the content_reports target_type
-- widening by REVIEW — "توسيع CHECK + ثابت enum بنمط V68/V69".
--
-- The V68 shape verbatim: DROP the old constraint + ADD the widened list
-- NOT VALID. NOT VALID is the V44 locking shape — a metadata-only statement,
-- enforced for every NEW row immediately — so live report writes never wait
-- on a scan. The VALIDATE step rides its OWN migration (V74 — the V66/V69
-- measured lesson: Flyway runs each versioned migration in its own
-- transaction, and a transaction retains every lock it acquired until it
-- commits; a VALIDATE sharing this transaction would scan under the DROP/ADD
-- ACCESS EXCLUSIVE and block ordinary reads and writes for the scan's
-- duration. Alone, the VALIDATE's own lock is SHARE UPDATE EXCLUSIVE — the
-- shared production database keeps serving traffic).
--
-- No column change, no _aud change (the mirror carries target_type as
-- VARCHAR(20) — 'REVIEW' fits the existing width), no new index (the queue
-- index is target-agnostic: status-first, FIFO, live rows only).
--
-- Checksum registered in migration-checksums.properties in this same PR
-- (MigrationChecksumGuardTest — the 2026-09-14 incident class).

ALTER TABLE content_reports DROP CONSTRAINT IF EXISTS chk_content_reports_target_type;

ALTER TABLE content_reports
    ADD CONSTRAINT chk_content_reports_target_type
    CHECK (target_type IN ('POST', 'COMMENT', 'REVIEW')) NOT VALID;
