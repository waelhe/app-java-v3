# ADR-0007: the Arabic text-search configuration is verified against the real engine, not assumed (Plan D-07)

- **Status:** Accepted (owner-ordered execution of the unified plan — the reserved decision opened per the owner's standing order, governed by the official documentation exclusively)
- **Decision owners:** search execution (the plan's D-07 row: "تحقق pg_catalog في CI")
- **Governing references (official documentation exclusively):**
  - PostgreSQL documentation — Text Search: [`pg_ts_config`](https://www.postgresql.org/docs/current/catalog-pg-ts-config.html), [`pg_ts_config_map`](https://www.postgresql.org/docs/current/catalog-pg-ts-config-map.html), [`pg_ts_dict`](https://www.postgresql.org/docs/current/catalog-pg-ts-dict.html) — the system catalogs that define *which named configurations exist* and *which dictionaries they map token types to*; Chapter 12 ("Full Text Search") — `to_tsvector(config, text)` resolves `config` against `pg_ts_config` at runtime, so a query naming a configuration that is absent fails at parse time
  - PostgreSQL documentation — `EXPLAIN` (the plan text of a functional GIN index shows the index's expression verbatim, including the configuration name inside `to_tsvector(...)`)
  - Spring Boot reference — Testing (Testcontainers integration testing with `@ServiceConnection`)
  - The measured repository: V106's own header already states the fact being verified ("Every standard initdb creates the 'arabic' text search configuration… no migration-side DDL") — D-07 turns that *prose claim* into a *measured gate*

## The decision

1. **The named configuration is verified, never assumed.** The plan's D-07 row is precise: "وجود قاموس arabic_stem لا يثبت configuration مسماة" — the presence of the `arabic_stem` dictionary (in `pg_ts_dict`) does NOT prove that a *named configuration* (`pg_ts_config.cfgname = 'arabic'`) exists or that the shipped indexes and queries actually resolve through it. The gate is an integration test that runs against the REAL Flyway-migrated PostgreSQL 18 database (the CI's own engine, `postgis/postgis:18-3.6-alpine`) and asserts, via `pg_catalog`:
   - the configuration `arabic` exists in `pg_ts_config` and its token-type mappings (`pg_ts_config_map`) resolve through the `arabic_stem` snowball dictionary (`pg_ts_dict`) — the named configuration, proven;
   - the shipped Arabic FTS indexes (`idx_listing_search_title` from V106, `idx_neighborhood_posts_search_fts` from V168) carry `to_tsvector('arabic', …)` in their `pg_indexes.indexdef` — the indexes the queries must serve are built on the SAME named configuration, verified post-migration;
   - the production predicate shape (`to_tsvector('arabic', …) @@ websearch_to_tsquery('arabic', …)`) executes against this database without error and self-matches — the configuration the code names is the configuration this engine resolves.
2. **No new DDL.** The verification is a measured gate, not a migration: the `arabic` configuration is engine-provided (initdb), and the V106 decision ("no migration-side DDL") stands — a migration-side `CREATE TEXT SEARCH CONFIGURATION …` would be a manual parallel of the official mechanism (a customization the official mechanism does not need), exactly the class the standing order removes.
3. **The gate rides the existing CI machinery.** The verification is an ordinary Testcontainers integration test (`@Testcontainers(disabledWithoutDocker = true)`, `@ServiceConnection`, Flyway on, `ddl-auto=none`, an OWNED container — the house isolation rule), so the plan's "في CI" is satisfied by the integration suite the CI already runs (`integration-test.yml` executes `mvnw clean verify` against the real PG 18 service). No new workflow, no manual step.

## Consequences

- A regression that drops the named configuration (an image change, a catalog drift, a hand-written migration touching text-search objects) fails the CI gate with a message naming the exact catalog fact that moved — the D-07 reservation is closed and stays closed.
- The remaining search reservation (D-06, the engine decision PG ↔ OpenSearch) stays behind its own measured gateway (ADR-0008) — this ADR does not touch the engine choice.
- The plan's P1 row ("العربية تحتاج تحقق قاعدة حقيقية — استعلام pg_catalog في CI") is answered at its own wording: a real query against a real base, in the suite the CI runs.
