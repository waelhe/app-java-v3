-- L43 (neighborhood community plan §5 — the recommendations category):
-- the validation step of V68's widened category CHECK, in its OWN
-- migration — the V66 precedent verbatim (V57 was the same shape for
-- V56's constraint): Flyway runs each versioned migration in its own
-- transaction, and a transaction RETAINS every lock it acquired until it
-- commits, so a VALIDATE sharing V68's transaction would scan under the
-- DROP/ADD statements' still-held ACCESS EXCLUSIVE, blocking ordinary
-- reads and writes for the scan. Alone here, the VALIDATE statement's
-- own lock is SHARE UPDATE EXCLUSIVE — concurrent reads and writes keep
-- flowing (the PostgreSQL ALTER TABLE lock table; the V44 locking
-- shape's own documented intent).
--
-- Idempotence note: exactly the shape V66 already ran in production for
-- the widened type CHECK, and V57 before it for V56's — sub-second on
-- this young table.

ALTER TABLE neighborhood_posts VALIDATE CONSTRAINT chk_neighborhood_posts_category;
