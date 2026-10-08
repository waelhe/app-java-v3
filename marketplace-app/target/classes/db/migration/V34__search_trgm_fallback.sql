-- Typo-tolerant search fallback (roadmap B2 / gap G-PROD-2): pg_trgm word
-- similarity, the official PostgreSQL extension for fuzzy text matching.
--
-- Why: full-text search (websearch_to_tsquery, idx_listing_search_title)
-- requires lexical stem equality — a user typing "gardn" or "keybaord" gets
-- zero results because the misspelled term matches no tsvector lexeme. pg_trgm
-- closes exactly this defect class at the word level: word_similarity()
-- compares the query's trigram set against every continuous extent of the
-- indexed text, so a one-edit typo still scores high similarity.
--
-- Availability evidence (this session, live queries on real PostgreSQL):
--   * pg_available_extensions lists pg_trgm default 1.6 on the local server.
--   * CREATE EXTENSION pg_trgm succeeds as the application user. pg_trgm is
--     a *trusted* extension since PostgreSQL 13 (official docs: Fuzzy String
--     Matching / extensions table) — a database owner can install it without
--     superuser rights, and the Railway template user owns the database.
--   * PostGIS is NOT in pg_available_extensions on the standard postgres
--     distribution — it requires a postgis-specific image, which is a
--     platform decision outside the official template (documented gate,
--     SYSTEM.md §15). Geographic search is therefore NOT attempted here.
--
-- Index mirrors the FTS index exactly (V9 line 18): same expression
-- coalesce(title,'') || ' ' || coalesce(description,''), same partial
-- predicate is_deleted = false AND status = 'ACTIVE' — so the similarity
-- query and the index agree on the searched text, and inactive/soft-deleted
-- rows stay unindexed for both paths alike. gin_trgm_ops supports the
-- <% (word similarity) operator directly (official docs: Fuzzy String
-- Matching, "Index Support" table).

create extension if not exists pg_trgm;

create index idx_listing_search_trgm
    on provider_listings using gin ((coalesce(title,'') || ' ' || coalesce(description,'')) gin_trgm_ops)
    where is_deleted = false and status = 'ACTIVE';
