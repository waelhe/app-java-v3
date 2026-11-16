-- L37 (realestate systems plan §5 — the featured boost): the promoted
-- window column.
--
--   promoted_until — when the listing's boost window ends. NULL = not
--                    boosted (every pre-L37 row: the backfill is the
--                    honest absence, not an assumption). TIMESTAMPTZ in
--                    the V49 expires_at/renewed_at family: the house
--                    timestamps are UTC instants and the query-time
--                    comparison rides the injected Clock (the expiry
--                    acceptance test's own seam).
--
-- The ordering that CONSUMES this column is evaluated at QUERY TIME
-- (the CASE flag below rides every ordered public read — never a WHERE
-- clause: the boost window reorders, it never filters; the count
-- queries of every surface stay byte-identical). That is also why no
-- cleanup task exists: an expired boost is self-correcting at the next
-- read — "مظللة منتهية لا تقفز" (the plan's criterion 3) with zero
-- jobs. The plan's "idempotent المهمة إن طُلب تنظيف" is the option we
-- do not need.
--
-- The Envers mirror gains the column in the V37/V49 style (the aud
-- table is ALTERed alongside its audited table; nullable there — a DEL
-- revision row carries the id alone, the V24/V54 precedent). Every
-- boost shading is an entity UPDATE on the @Audited ProviderListing,
-- so the revision trail IS the plan's criterion 4 record — the admin
-- revisions surface (/api/v1/admin/revisions/...) reads it directly.
--
-- Plain nullable column: NO CHECK and therefore NO NOT VALID/VALIDATE
-- split (the V56/V57 discipline exists for constraint-bearing changes
-- on live tables; V49's plain column adds are the exact precedent).
--
-- Checksum registered in migration-checksums.properties in this same
-- PR (MigrationChecksumGuardTest — the 2026-09-14 incident class).

ALTER TABLE provider_listings
    ADD COLUMN IF NOT EXISTS promoted_until TIMESTAMPTZ;

ALTER TABLE provider_listings_aud
    ADD COLUMN IF NOT EXISTS promoted_until TIMESTAMPTZ;
