-- L36 (realestate systems plan §5 — agent/office pages): the provider
-- persona extension. Three columns on provider_profiles:
--
--   actor_type      — the actor classification (فرد / وسيط مستقل / مكتب):
--                     INDIVIDUAL, INDEPENDENT_BROKER, AGENCY. NOT NULL with
--                     DEFAULT 'INDIVIDUAL': every pre-L36 profile IS an
--                     individual (the brokerage concept did not exist), so
--                     the backfill is the honest classification, not an
--                     assumption. Display-only — no legal verification is
--                     implied (KYC sits behind its own documented gate,
--                     realestate plan §7).
--   agency_name     — optional public office name (the AGENCY persona's
--                     display field; length class of display_name, 200).
--   license_number  — optional public brokerage license text where one is
--                     legally required (display only — the plan's wording:
--                     "رقم ترخيص الوساطة حيث يُطلب قانونيًا، عرض فقط بلا
--                     تحقق قانوني"; 100 admits every national format).
--
-- The Envers mirror gains the three columns in the V37 style (the aud table
-- is ALTERed alongside its audited table; nullable there — a DEL revision
-- row carries the id alone, the V24/V54 precedent).
--
-- The CHECK is added NOT VALID and validated in the FOLLOW-UP migration
-- V57 (CodeRabbit round 1, adopted from the root — the Squawk
-- constraint-missing-not-valid rule): PostgreSQL retains the ACCESS
-- EXCLUSIVE lock taken by ADD CONSTRAINT until the owning TRANSACTION
-- commits, so validating inside this same script would run the validation
-- scan under ACCESS EXCLUSIVE and block all reads of provider_profiles.
-- Splitting the VALIDATE into its own migration makes the V44 locking
-- shape's documented intent actually real: V56 commits the catalog-only
-- ADD (enforced for every new row immediately), then V57's single
-- VALIDATE runs under its own SHARE UPDATE EXCLUSIVE lock — concurrent
-- reads keep flowing during the scan. V57 is trivially re-runnable after
-- repair (validating an already-valid constraint is a no-op).

ALTER TABLE provider_profiles
    ADD COLUMN IF NOT EXISTS actor_type VARCHAR(30) NOT NULL DEFAULT 'INDIVIDUAL',
    ADD COLUMN IF NOT EXISTS agency_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS license_number VARCHAR(100);

ALTER TABLE provider_profiles_aud
    ADD COLUMN IF NOT EXISTS actor_type VARCHAR(30),
    ADD COLUMN IF NOT EXISTS agency_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS license_number VARCHAR(100);

ALTER TABLE provider_profiles
    ADD CONSTRAINT chk_provider_profiles_actor_type
    CHECK (actor_type IN ('INDIVIDUAL', 'INDEPENDENT_BROKER', 'AGENCY')) NOT VALID;
