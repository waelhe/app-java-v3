# Engineering Rulebook — permanent rules derived from the verified history

> Format per rule: invariant · applies when · failure mechanism prevented · inspect before · must satisfy · required evidence · historical justification · enforcement · false-positive handling · verification status.
> Categories: **A** universal (nearly every change) · **B** conditional (triggered by change type) · **C** domain-specific (Saleor/marketplace) · **D** automated enforcement bindings.
> Rules are deliberately few and risk-proportionate: not every incident became a universal rule — incidents became *categories* in `pattern-catalog.md` and only the rules that pay for themselves are below.

## A · Universal change rules

### A1 — Authority anchor before edit
- **Invariant:** every change is traceable to a governing requirement and, for framework-touched behavior, to the official doc of the pinned version.
- **Applies:** every edit, without exception (mirrors AGENTS.md §0.1–0.2).
- **Prevents:** framework-semantics violations (E-06/P2) and off-plan drift.
- **Inspect before:** declare `[governing file]`, `[architectural core]`, `[§14.2 item]`, `[boundary impact]`, `[debt: no/yes]` per AGENTS.md §0.2; for framework behavior, cite the official doc section (Spring Boot/Maven/Modulith/DGS/Flyway for the versions in the root POM).
- **Must satisfy:** the change is either required by the governing file or explicitly registered as a governed divergence.
- **Evidence:** the declaration travels in the PR description.
- **History:** E-06, E-15, E-20. **Enforcement:** procedural (AGENTS.md §0 is already binding); CodeRabbit reviews flag unsourced fixes.
- **False positives:** pure typo/format fixes still declare the governing file (cheap).
- **Status:** verified-procedural.

### A2 — Baseline before change
- **Invariant:** the current behavior and its tests are known before modifying them.
- **Prevents:** plausibly-local rewrites that destroy verified behavior (E-07/P2 family).
- **Inspect before:** read the target and its callers' tests; run the module's tests when feasible (`mvn clean verify -pl <module>` per AGENTS.md).
- **Must satisfy:** you can state what the code does *today* in one sentence before stating what it will do.
- **History:** E-07 (a globally-applied "improvement" reverted wholesale). **Enforcement:** procedural.
- **Status:** verified-procedural.

### A3 — Blast-radius enumeration (the P5 question)
- **Invariant:** the set of affected paths is enumerated before the edit, not discovered after.
- **Prevents:** one-rule-many-doors defects (E-09/E-17/P5 — the highest-recurrence pattern).
- **Inspect before:** list callers, alternate entry points (other modules' access, admin surfaces, GraphQL+REST both, mirrors/indexes/caches, event listeners), data stores, permissions, external effects.
- **Must satisfy:** for each business rule the change touches: either a single choke point enforces it, or every enumerated door has an explicit disposition.
- **Evidence:** the enumeration table rides the PR description.
- **History:** E-09 (11 adapters), CodeRabbit rows 5/13/20 (read rails vs write guards). **Enforcement:** review-protocol pass 3 (mandatory); CodeRabbit as detector.
- **Status:** verified-procedural.

### A4 — Diff-review against intent
- **Invariant:** what was *intended* is what the diff *says*, line by line.
- **Prevents:** plausible-but-different implementations (P8 umbrella).
- **Must satisfy:** after implementing, re-read the full diff against the stated intent before pushing; every hunk maps to a purpose.
- **History:** E-15, E-10. **Enforcement:** procedural + CodeRabbit line review.
- **Status:** verified-procedural.

### A5 — Atomic commit, bound evidence
- **Invariant:** a pushed commit compiles and passes its own tests; reported status cites the exact SHA it was measured on.
- **Prevents:** interrupted-session partial state (E-10/P7) and unbound claims (E-20).
- **Must satisfy:** tests and their implementation land together; "works" statements name the SHA; a test proves it runs (and fails first when written to catch a bug).
- **History:** E-10 (tests-without-implementation outage), E-20. **Enforcement:** CI compile/test gates; atomicity itself procedural.
- **Status:** verified-procedural + CI partial.

### A6 — Numbers are derived or dated
- **Invariant:** every load-bearing number in any document is derived by a guard, or carries a last-verified date + re-derivation command.
- **Prevents:** derived-truth drift (E-12/P6).
- **Must satisfy:** new docs with counts follow the `DocumentationNumbersGuardTest` pattern (add the derivation to the guard) or the R1 dated-command form; PR-body inventories cite the measured date and command.
- **History:** E-12 (four instances). **Enforcement:** D2 for guarded files; procedural elsewhere (gap recorded).
- **Status:** verified — systematic where guarded.

## B · Conditional engineering rules (trigger by change type)

### B1 — Migrations: immutable applied history, forward-only, shared number space *(trigger: any `db/migration` change)*
- **Invariant:** applied `V__` files never change after merge; schema change = new `V{N}`; rollback of code never rolls back migrations.
- **Prevents:** checksum outages (E-01), history rewrite under revert pressure (E-14).
- **Must satisfy:** never edit a merged `V__` file (byte-identical restore is the remedy playbook, per `0d39e818`); new migrations take numbers above the current max; if a number was consumed by an unmerged branch, skip it — gaps are benign, renumbering is not.
- **Evidence:** `MigrationChecksumGuardTest` green; `migration-checksums.properties` regenerated only by adding entries.
- **History:** E-01, E-14, P8. **Enforcement:** D1 (deterministic). **False positives:** none known; `R__` repeatables exempt by design.
- **Status:** verified — the strongest guard in the repository.

### B2 — Transactions, retries, idempotency, publication timing *(trigger: external effects, retry paths, listeners, cache/invalidation)*
- **Invariant:** external effects and their idempotency records survive partial failure; operation identity is stable across retries; publication timing matches read-path consistency needs.
- **Prevents:** rollback-only poisoning (E-04), double-payment windows (E-08), global-timing regressions (E-07).
- **Must satisfy:** retryable boundaries never join the carrier `REQUIRED` transaction; idempotency keys derive from *operation identity* (frozen at first execution), never from mutable state; Modulith events publish AFTER_COMMIT via `@ApplicationModuleListener`; invalidation/deferral decisions are per-read-path, never global; concurrent-write guards (`version = version + 1`) live inside the mutating statement.
- **Evidence:** integration test replaying the rollback→retry (or purge-race) scenario on real PostgreSQL (the `DisputesModuleIntegrationTest` precedent).
- **History:** E-04, E-07, E-08. **Enforcement:** procedural + integration tests per change; no blanket static check (correctly — semantics matter).
- **Status:** verified-procedural + per-change tests.

### B3 — Authorization, ownership, and the door count *(trigger: permission checks, ownership logic, shared rows)*
- **Invariant:** every door that reaches a protected resource enforces the same rule; each writer owns its column set.
- **Prevents:** P5's door-by-door findings (E-09, E-17).
- **Must satisfy:** prefer a shared service guard as single choke point; enumerate doors (A3) with dispositions; column-level ownership on read-modify-write of shared rows (write what you own — E-17's surgical-column lesson); scope checks bind *all* identity dimensions (e.g. author AND location — CodeRabbit row 43).
- **Evidence:** tests per enumerated door (the "membership in *another* neighborhood ⇒ deny" precedent).
- **History:** E-09, E-17, rows 43/46. **Enforcement:** review-protocol pass; CodeRabbit repository scans.
- **Status:** verified-procedural.

### B4 — Contracts and consumer shapes *(trigger: OpenAPI/GraphQL surface, wrappers, pagination, SDL)*
- **Invariant:** consumers depend on shape, not names; contract gates must cover the shape level.
- **Prevents:** E-05 (wrapper broke inbox behind compatible diff), E-11 (name parity masking argument-shape divergence), E-19.
- **Must satisfy:** response-shape changes = frontend notice + battery replay before merge (CONTRIBUTING §4 binding); SDL alignment includes argument containers and nullability against the pinned reference; divergences registered in `saleor-contract-divergences.csv` with owner/tests/approval; cursor changes ride `KeysetCursorGate`.
- **Evidence:** OpenAPI/GraphQL gates green + the registered divergence row (when applicable) + frontend battery result for shape changes.
- **History:** E-05, E-11, E-19. **Enforcement:** D3/D5/D7 (deterministic) + procedural notice step.
- **Status:** verified — systematic on known surfaces.

### B5 — Derived data and content lifecycle *(trigger: mirrors, indexes, caches, vector stores, purge/withdraw, audit mirrors)*
- **Invariant:** derived copies and mirrors follow their source through every lifecycle transition — including withdrawal/purge and late republication.
- **Prevents:** the E-09 family (tombstone filters absent, mirrors unmaintained, withdrawn content re-indexed by a late publish).
- **Must satisfy:** purge/withdraw covers table + mirrors + search/index/vector stores; tombstone filters applied (dead text purged as dead); withdrawal events block late republication; ownership resolved from stable identity (live users by `subject`; anonymized via the Envers mirror with unique decomposition); `version = version + 1` in the purge statement.
- **Evidence:** adapter-family checklist in review-protocol completed; integration test for the race shape.
- **History:** E-09 (14 closures), row 36/31/18/29/3/12. **Enforcement:** CodeRabbit deep scans (D6) as detector; tests per change.
- **Status:** verified — detector systematic, preventer procedural.

### B6 — Configuration, deployment, and verdict points *(trigger: profiles, env placeholders, health contributors, deploy changes, new gates)*
- **Invariant:** every placeholder resolves on every deployment target; every health contributor is an explicit decision; every aggregator/verdict/gate names its automated consumer; deployment triage distinguishes provider refusals from code failures.
- **Prevents:** E-02 (boot collapse), E-03 (blind aggregate), E-13 (gate that cannot run), E-16 (quota walls).
- **Must satisfy:** new `${...}` in prod profiles ⇒ value-or-fail-fast verified against the target env matrix; new health contributors ⇒ explicit `management.health.<key>.enabled` decision; new gates/aggregators ⇒ their own success/failure is watched (S10's lesson applied to gates: E-13); provider-limit failures documented in the deploy log with the distinction.
- **Evidence:** deploy smoke includes aggregate health; placeholder matrix note in the PR.
- **History:** E-02, E-03, E-13, E-16. **Enforcement:** watchdog probes for health (systematic); gate-health monitoring now systematic too — `gate-health.yml` (round 21, gap G5 closed): per-gate health over a 7-day window, CodeRabbit Gate failures classified provider-refusal vs content, RED at ≥3-of-5 provider refusals, review freshness per open PR — first real CI run RED on the live provider outage with the "NOT a code failure" remedy; the placeholder-vs-target matrix stays procedural (gap G2).
- **Status:** verified-procedural + systematic (health probes and gate-health monitor).

### B7 — Numeric representation bounds *(trigger: decimal endpoints, coordinates, multipliers, exponents)*
- **Invariant:** representation cost is bounded before computation.
- **Prevents:** E-18 (exponent-driven exhaustion behind valid `BigDecimal`).
- **Must satisfy:** bounded-scale gates precede arithmetic (precedents: `MAX_COORDINATE_SCALE=32`, `MAX_MULTIPLIER_SCALE=16`); graceful trailing zeros remain legal.
- **History:** rows 1/39. **Enforcement:** per-endpoint tests; review checklist numeric item.
- **Status:** verified-procedural.

### B8 — Feature-flag enforcement seams *(trigger: kill-switches, enabled flags)*
- **Invariant:** a disabled feature refuses new writes honestly at every door while owner/data-management seams stay live by design.
- **Prevents:** rows 13/5 (write guards and discovery rails initially divergent).
- **Must satisfy:** one shared service guard (the polls kill-switch consolidation precedent); 409/empty-page honest responses before any resolution or write; deliberate seam list documented (owner delete, un-RSVP, GDPR purge).
- **History:** rows 13/5. **Enforcement:** guard tests per seam.
- **Status:** verified-procedural.

## C · Domain-specific rules

### C1 — Saleor contract fidelity *(trigger: any Dashboard/commerce SDL or DataFetcher work)*
- **Invariant:** the published contract matches pinned Saleor 3.23.43 in root names, argument containers, and nullability; every divergence is registered, owned, tested, and approved (D-SALEOR-FULL-001; `saleor-contract-divergences.csv`).
- **Must satisfy:** SDL fragments aligned from source (not hand-recalled); DataFetchers read from the input object; nullable semantics match; the Root Contract Gate green or a registered divergence present.
- **History:** E-11. **Enforcement:** D3. **Status:** verified-systematic.

### C2 — Commerce money safety *(trigger: payments, refunds, settlements, captures)*
- **Invariant:** money operations are idempotent under retry, scoped stably, and reconciled against the provider.
- **Must satisfy:** B2's stable-key clause (E-08's `StableRefundScope` form); payable-key binding and ceiling checks before the channel; settlement reconciliation on webhook sync; V230-era key formats preserved byte-for-byte unless a governed change.
- **History:** E-08, row 35, #458 line. **Enforcement:** integration tests + review pass.
- **Status:** verified-procedural.

### C3 — Content lifecycle honesty *(trigger: listings, Q&A, community content, feeds)*
- **Invariant:** visibility rules are enforced at the query gate (active-only, ownership), with honest 404/409 before any feed or write.
- **History:** rows 20/13/5. **Enforcement:** per-door tests. **Status:** verified-procedural.

### C4 — Modulith boundaries *(trigger: any cross-module intent)*
- **Invariant:** cross-module = SPI ports + events only; no entities at the HTTP boundary; no framework types in shared ports.
- **History:** #427/#429 boundary-erosion class (regression list); `ModulithVerificationTest` stands guard.
- **Enforcement:** deterministic (D4). **Status:** verified-systematic.

## D · Automated enforcement bindings (summary — details in `enforcement-map.md`)

- **D1** Migration checksums — `MigrationChecksumGuardTest` (closes E-01's class).
- **D2** Documentation numbers — `DocumentationNumbersGuardTest` (closes E-12 where scoped).
- **D3** Saleor root contract — Root Contract Gate workflow (closes E-11's class).
- **D4** Modulith boundaries — `ModulithVerificationTest`.
- **D5** API surface — OpenAPI diff + GraphQL schema gates (E-05/E-19 family).
- **D6** Independent review — CodeRabbit (repository scans + PR reviews, drafts:true — E-13's fix).
- **D7** Cursor contracts — `KeysetCursorGate` (E-19).
- **D8** Guard-message fidelity — *documented; repaired once by governed code*: a guard's remedy message must describe a real mechanism. E-21's false promise was closed at `0edf0f6` (PR #723): the root-signatures guard now implements the registry its message promised; the rule stays documented for the remaining guards (see enforcement-map §5).
