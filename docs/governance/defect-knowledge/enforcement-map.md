# Enforcement Map & Audit Coverage — rule bindings, validation, gaps

> §1 maps every rule to its working enforcement point. §2 lists the existing guard infrastructure this system reuses. §3 validates the system against representative historical cases. §4 records proportionality against successful changes. §5 states coverage, limitations, and open gaps — explicitly, per the mission's completion criteria.

## §1 · Rule → enforcement binding

| Rule | Mechanism (type) | Working enforcement point | Status |
|---|---|---|---|
| A1 authority anchor | procedural | AGENTS.md §0.2 declaration (binding); CodeRabbit flags unsourced fixes | enforced-procedural |
| A2 baseline | procedural | change-protocol Phase 0, step 2; module verify per AGENTS.md | enforced-procedural |
| A3 blast radius | procedural | PR enumeration table; review-protocol pass 3 | enforced-procedural |
| A4 diff-vs-intent | procedural + tool | change-protocol Phase 2, step 1; CodeRabbit line review | enforced-procedural |
| A5 atomic commit / bound evidence | CI + procedural | CI compile/test gates (partial); SHA-binding procedural | partially enforced |
| A6 derived-or-dated numbers | deterministic test | `DocumentationNumbersGuardTest` for SYSTEM/ARCHITECTURE/README + `DefectKnowledgeDocsGuardTest` for this system's own enumerations (added with this system); procedural elsewhere — GAP G1 (narrowed to files outside guard scope) | enforced where scoped |
| B1 migrations | deterministic test | `MigrationChecksumGuardTest` + `migration-checksums.properties` | **enforced (strongest)** |
| B2 transactions/retries/idempotency | per-change integration tests | replay scenarios on real PostgreSQL (DisputesModuleIntegrationTest precedent); CODING_STANDARDS §3 | enforced per change |
| B3 ownership/doors | procedural + scans | review-protocol pass 3; CodeRabbit repository scans | enforced-procedural |
| B4 contract shapes | deterministic CI gates | OpenAPI diff gate, GraphQL schema gate, Root Contract Gate, `KeysetCursorGate`; notice+battery procedural | **enforced (deterministic)** |
| B5 derived data | scans + per-change tests | CodeRabbit deep scans (detector); adapter-family checklist (preventer) | detector enforced, preventer procedural |
| B6 config/deploy/verdicts | partial systematic | watchdog probes (health); **placeholder-vs-target matrix and gate-health monitoring procedural — GAP G2** | partially enforced |
| B7 numeric bounds | per-endpoint tests | bounded-scale gates precedent | enforced per change |
| B8 flag seams | per-door tests | shared-guard consolidation precedent | enforced per change |
| C1 Saleor contract | deterministic CI gate | Saleor Root Contract Gate + `saleor-contract-divergences.csv` + pins (`saleor-source-pins.json`, `saleor-canonical-governance.yml`) | **enforced** |
| C2 money safety | integration tests | stable-key + settlement reconciliation tests | enforced per change |
| C3 content visibility | per-door tests | active-only gates; honest 404/409 tests | enforced per change |
| C4 Modulith boundaries | deterministic test | `ModulithVerificationTest` | **enforced** |
| D1 migration checksums | deterministic test | `MigrationChecksumGuardTest` + `migration-checksums.properties` | **enforced (strongest)** |
| D2 documentation numbers | deterministic test | `DocumentationNumbersGuardTest`; `DefectKnowledgeDocsGuardTest` guards this system's own counts/references/tags | enforced where scoped — GAP G1 outside scoped files |
| D3 Saleor root contract | deterministic CI gate | Root Contract Gate + `saleor-contract-divergences.csv` + pins (`saleor-source-pins.json`, `saleor-canonical-governance.yml`) | **enforced** |
| D4 Modulith boundaries | deterministic test | `ModulithVerificationTest` (binding for C4) | **enforced** |
| D5 API surface | deterministic CI gates | OpenAPI diff gate + GraphQL contract publication (binding for B4) | **enforced** |
| D6 independent review | CI workflow | CodeRabbit Gate with `drafts:true` (E-13's fix) | enforced |
| D7 cursor contracts | deterministic test | `KeysetCursorGate` (binding for B4-cursor) | **enforced** |
| D8 guard-message fidelity | **documented only** | no mechanism — **GAP G3 (E-21)** | documented-not-enforced |

## §2 · Existing guard inventory reused (not duplicated) by this system

`MigrationChecksumGuardTest` · `DocumentationNumbersGuardTest` · `ModulithVerificationTest` · `PlatformGovernanceFilesTest` · Saleor Root Contract Gate · Saleor Canonical Plan and Schema Inventory gate · OpenAPI diff gate · GraphQL contract publication · `KeysetCursorGate` · CodeRabbit Gate · CodeQL · Container Scan · Production Watchdog (6 probes post-S10) · Retired Saleor Plan and PR Guard (supersession markers) · three merge gates of SYSTEM.md §14.3.

## §3 · Validation against representative historical cases

Per the mission: cases selected across causal families; for each, the claim is that the pattern is identified, the rule covers the mechanism, the protocol would have prompted the analysis, and — where mechanically checkable — the guard detects the original failure. Evidence cited at the exact commits.

| Case (family) | Pattern/Rule | Would the protocol have caught it? | Mechanical detection proof |
|---|---|---|---|
| **E-01** V51 checksum (migration) | P8 → B1/D1 | Yes — Phase 0 trigger table routes any `db/migration` touch to B1, whose first line forbids editing applied files | **Proven historically**: `MigrationChecksumGuardTest` (born in the fix commit `0d39e818`) freezes all checksums; the class never recurred after Sep 14 (revert-scan) |
| **E-03** S10 blind aggregate (verdict point) | P9 → B6 | Yes — B6's "new aggregator ⇒ name its automated consumer" is the exact missing decision; P1's environment question covers the Railway-only SMTP reachability | Watchdog probe #6 + `AggregateHealthGauge` + alert rule shipped in the fix (#406) — the guard exists and runs in production |
| **E-08** refund key (idempotency) | P4 → B2 | Yes — B2's stable-key clause names this exact shape; Phase 0, step 5 forces the "rollback-after-external-effect" scenario into the acceptance evidence | Integration proof shipped with the fix (`ffbdcbd7` + DisputesModuleIntegrationTest race proof on real PostgreSQL) |
| **E-11** root-contract deviations (contract) | P3 → C1/D3 | Yes — C1 requires source-aligned SDL including argument containers; the gate would have failed the pre-fix state — as it actually did | **Proven historically**: the Root Contract Gate itself detected both deviations on the live head (round-13 audit); fixes `fdf55b2`→`1c8b848` turned it green |
| **E-12** numbers drift (derived truth) | P6 → A6/D2 | Yes — A6 forces derivation-or-dating at doc-write time | **Proven historically twice**: guard failed the build at 183≠184 and was closed by `2a13d192` (round-14 audit); campaign-report counts remain procedural (G1) |
| **E-02** env placeholder (environment blindness) | P1 → B6 | Yes — B6's placeholder-resolution matrix is the missing step; Phase 0, step 4 loads B6 for profile changes | No deterministic guard (G2) — honestly unproven mechanically; procedural prevention only |
| **E-10** session-interruption partial commit (agent ops) | P7 → A5 | Yes — A5's atomicity + test-proven-to-run; Phase 2, step 7 pushes atomically | Not mechanically enforceable as stated — CI caught it at `testCompile` (the detection worked; the prevention is procedural) |
| **E-09** adapter family (many doors) | P5 → B3/B5 | Yes — A3's enumeration table + review pass 3; sibling search (Phase 2, step 4) is the anti-door lesson | Detector proven: CodeRabbit repository scans found the family repeatedly; preventer procedural |

**Neighboring-behavior check:** none of the validated rules blocks legitimate neighbors — B1 allows new migrations (gaps benign by Flyway semantics, documented in P8); D2 permits retired numbers in range phrasing (its own javadoc); B2 does not forbid AFTER_COMMIT (E-07's revert teaches *per-path analysis*, not a ban); D3 permits registered divergences through the CSV route.

## §4 · Proportionality against successful changes

Sampled successful classes: routine module feature waves (PROJECT_MAP L48–L52, W3–W5 era; squash-merged with three gates, zero rollback), the keyset pagination P0 (#677→#678 — CodeRabbit root notes adopted, gate generalized), the DGS adoption phases 0–5 (each merged → deployed → smoked → truth-synced). For these, the protocol's cost is the declaration + enumeration table + existing gates — the repo's own recorded workflow already pays these costs; the rulebook adds the *selective* B-rules only on trigger, keeping the burden proportional. The regression list's warning against defensive bloat is honored: 9 patterns and 18 A/B/C rules plus 8 automated D bindings — 26 enumerated items, counts derived from the enumerations themselves and enforced by `DefectKnowledgeDocsGuardTest` — not a catalog of 100.

## §5 · Audit coverage, limitations, open gaps

**Actually inspected (this mission, 2026-10-27):** 715 PRs inventoried via API (state/merge identity, dates); 14,575 workflow runs fetched and classified (2,569 failures by workflow/month); full git history of main (706 commits: timeline, authors, churn, reverts, migration tree) and integration line (785 commits); the five governance documents + CODING_STANDARDS + agent-protocol read at the pinned heads; in-repo incident records (S10 postmortem, ADR-0001..0013 indices, CodeRabbit findings registries 2026-10-25/26 with their 35+46 fingerprint reconciliations); the V51/#419/#174/`8715cb2b`/`7bc2f5e5`/`91094558`-family commits read directly; PR #723 round-13/14 audit records (20 CodeRabbit findings classified); migration gap analysis (V1–V291, 86 absent, zero deletions).

**Not inspected / limitations (explicit):**
- Workflow-run *logs* were not re-fetched per failure (14,575 conclusions classified; log-level failure causes relied on the repository's own documented incident records, which this repo maintains diligently).
- CodeRabbit finding *exports* for historical PRs other than the two registries were read as summarized in-repo, not re-exported from the service.
- The campaign SDL fragment files (`docs/governance/campaign/sdl/**`) were not individually re-validated against upstream this mission — their gate status is cited from CI records.
- Pre-September history (715 PRs before the governance era) was inventoried statistically; individual early PRs were sampled, not exhaustively re-read.
- Production database state was not directly queried; production facts are cited from SYSTEM.md §15's measured log at its last-verified date.

**Open gaps (registered, not concealed):**
- **G1** — campaign reports / PR bodies with load-bearing numbers have no deterministic guard; the defect-knowledge system's own structural enumerations (rule counts, case tags, pattern range, phase/section references) are now guarded by `DefectKnowledgeDocsGuardTest`; other new docs remain procedural (A6).
- **G2** — no deterministic placeholder-vs-deployment-target resolution guard (E-02 class prevention is procedural).
- **G3** — E-21: the failing guard's message promises a decisions mechanism the script does not implement. Fix requires code (registry or message correction) — **deliberately left to a governed code change, not silently patched by this docs-only mission**.
- **G4** — PR #723's 20 CodeRabbit documentation findings and the main-reconciliation (Maven 3.10 + OpenAPI JSON gate) remain open on the integration branch, pending owner decision — recorded in the round-13/14 reports on the PR.
- **G5** — gate-health monitoring (E-13's generalization: watch the watchers) is a stated rule (B6) without a dedicated implementation.

**Highest-value next action supported by the findings:** close G3 with a small governed code change (implement the exception registry the guard's message promises, or correct the message) — it is the only gap where the enforcement layer itself carries a known false promise; everything else in the map either works or is honestly procedural.
