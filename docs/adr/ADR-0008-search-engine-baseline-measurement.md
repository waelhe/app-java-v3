# ADR-0008: the search engine stays PostgreSQL behind a measured baseline — the D-06 gateway is the harness, the report, and the gate (Plan D-06)

- **Status:** Accepted (owner-ordered execution of the unified plan — the reserved decision opened per the owner's standing order, governed by the official documentation exclusively)
- **Decision owners:** the plan's D-06 row names the deliverable — "تقرير قياس + مالك/تشغيل" (a measurement report + the owner/ops acceptance); this ADR builds the measuring instrument and fixes the methodology
- **Governing references (official documentation exclusively):**
  - PostgreSQL documentation — `EXPLAIN ANALYZE` (Chapter 14 "Performance Tips": actual run times, the cost of measuring, repeating measurements to control noise); Chapter 12 "Full Text Search" (the GIN index paths the production queries take); `pg_trgm` (the official trigram extension, `word_similarity`, `%`/`<%` operators)
  - The measured repository: the production predicates live in `ProviderListingRepository.searchFullText` / `searchSimilar` (V106/V168's named-configuration indexes) — the harness measures THE production SQL, never a stand-in
  - Spring Boot reference — Testing (Testcontainers integration testing)
  - The plan's own D-06 wording (§3.2 / §5.2): "PG خط أساس؛ OpenSearch خلف قياس" — PostgreSQL IS the baseline; OpenSearch enters only behind a proven measurement; "لا فهران متداخلان لنفس البحث دون سبب واضح وقياس يبرره"

## The decision

1. **The instrument is the deliverable's engine.** A search-baseline harness (an owned-container integration test on the real Flyway-migrated PostgreSQL 18) seeds a representative bilingual dataset, then measures — with the EXACT production SQL — the plan's three lexical behaviors and their composition:
   - **full-text**: `to_tsvector('arabic', …) @@ websearch_to_tsquery('arabic', …)` ordered by `ts_rank` (the `ProviderListingRepository.searchFullText` query verbatim);
   - **trigram**: the `word_similarity` / `<%` fallback (`searchSimilar` verbatim);
   - **substring**: the naive `ILIKE '%…%'` scan — the baseline row that shows WHY the two GIN indexes exist (its plan is a sequential scan by construction);
   - **filters + ranking**: the production composition (category + price range on top of FTS) and the `ts_rank_cd` community-ordering variant.
2. **The methodology is fixed and written down.** Every measured path runs `EXPLAIN (ANALYZE, BUFFERS)` — six executions, the first discarded (the cache-warm run — the noise control Chapter 14's own discussion implies), the minimum of the remainder reported as the steady state. The plans are captured verbatim into the report so the index usage is auditable, and the test ASSERTS the structural facts (the FTS path serves from `idx_listing_search_title`'s GIN bitmap scan, the trigram path from `idx_listing_search_trgm`'s, the substring path sequential) — structural assertions only, never absolute latencies (a latency threshold in a test is a flake generator, not a gate).
3. **The report is the D-06 artifact.** The harness writes `search-baseline-report.md` (the CI artifact) containing the methodology, the measured table (path × query × execution time), and the plan excerpts. The engine decision stays WHERE the plan put it: **PostgreSQL remains the only search engine**; an OpenSearch adoption is refused until a report produced by THIS instrument on a representative benchmark demonstrates the measured need, and the owner/ops accept it — the harness is the standing instrument that produces that evidence on demand. No parallel engine, no speculative index, no UBI/behavioral pipeline until that measured gate (the plan's own §5.2 wording).
4. **No production code moves.** The harness is measurement-only: no query is rewritten, no index added, no threshold tuned (the framework-default 0.6 similarity threshold stays untouched — the measured-repository rule). The D-06 reservation closes as the plan defines it: the measuring instrument exists, the baseline is measurable at any time, the engine decision is a report-and-acceptance away — never an assumption.

## Consequences

- The CI gains a standing, repeatable baseline instrument (owned container, seeded data, structural index assertions) — an index/config regression that flips a production query off its GIN index fails the gate structurally.
- The OpenSearch question is now ANSWERABLE rather than open: run the instrument on the representative benchmark, compare the report against the operational costs, decide. Until that acceptance, PostgreSQL is the engine — by measurement, not by default.
- The D-13 ordering gateway (ADR-0006's eval contract) rides the same surface: the learned-ordering question stays behind its own measured gate; this ADR does not touch it.
