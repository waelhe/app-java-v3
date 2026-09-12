-- Repeatable seed for the administrative geography (realestate systems plan
-- L30; the G-R1 gate owns the AUTHORITATIVE source of the division data).
--
-- Content status: the administrative chain (Syria / Rif Dimashq / Qudsayya
-- city) is the verified public division skeleton; the neighborhood level
-- carries an initial minimal set and is trial-grade until G-R1 decides the
-- content owner (content team vs. reference source) — the admin surface
-- (POST/PATCH /api/v1/admin/geo) is the live correction path, and this file
-- converges with it: R__ re-applies on checksum change, rows are upserted
-- by FIXED id (stable references for consumers), and admin-created rows are
-- never removed here (no slug/id collisions by convention: the seed's slug
-- set is closed).
--
-- Fixed UUIDs keep the seed idempotent across environments (deterministic
-- references for the Qudsayya pilot — the business-model document's target
-- market سوريا ← ريف دمشق ← قدسيا).

INSERT INTO geo_locations (id, parent_id, level, name_ar, name_en, slug)
VALUES
    ('11111111-1111-4111-8111-111111111101', NULL, 0, 'سوريا', 'Syria', 'syria'),
    ('11111111-1111-4111-8111-111111111102', '11111111-1111-4111-8111-111111111101', 1, 'ريف دمشق', 'Rif Dimashq', 'rif-dimashq'),
    ('11111111-1111-4111-8111-111111111103', '11111111-1111-4111-8111-111111111102', 2, 'قدسيا', 'Qudsayya', 'qudsayya'),
    ('11111111-1111-4111-8111-111111111104', '11111111-1111-4111-8111-111111111103', 3, 'قدسيا البلد', 'Qudsayya Old Town', 'qudsayya-old-town'),
    ('11111111-1111-4111-8111-111111111105', '11111111-1111-4111-8111-111111111103', 3, 'ضاحية قدسيا', 'Qudsayya Suburb', 'qudsayya-suburb'),
    ('11111111-1111-4111-8111-111111111106', '11111111-1111-4111-8111-111111111103', 3, 'الهامة', 'Al-Hamah', 'al-hamah')
ON CONFLICT (id) DO UPDATE SET
    parent_id = EXCLUDED.parent_id,
    level     = EXCLUDED.level,
    name_ar   = EXCLUDED.name_ar,
    name_en   = EXCLUDED.name_en,
    slug      = EXCLUDED.slug;
