-- V232 — W4 (the unified end-to-end plan §3, J09 — «توصية بعمل أو خدمة:
-- الربط بملف العمل/الخدمة الحقيقي»): the recommendation post's OPTIONAL
-- bridge to the real provider row. Plain UUID reference, no FK across the
-- module boundary (the community module's own D-N1 discipline verbatim —
-- the same shape every cross-module reference in the codebase carries);
-- the EXISTENCE gate is the service's own (through the shared-api
-- ProviderLookupPort, 404 before any write), and the CURRENT information
-- is resolved at READ time through the same port (never frozen into the
-- post — the provider's truth stays the provider's own).
ALTER TABLE neighborhood_posts
    ADD COLUMN recommended_provider_id UUID;

-- The Envers mirror (the AGENTS.md law: every domain entity audited).
ALTER TABLE neighborhood_posts_aud
    ADD COLUMN recommended_provider_id UUID;
