# Pattern Catalog — recurring causal mechanisms behind the defect history

> Grouped by **cause**, not by file/module/PR. Each pattern states: which cases demonstrate it, whether they share one cause or merely symptoms, frequency/severity/recurrence, whether prevention is systematic or was only local, and the strongest practical prevention. Patterns here are the *verified* set; rejected candidate patterns are listed at the end with the reason for rejection.

## P1 · Verification-environment blindness — "green here, broken there"

**Invariant protected:** the verification environment must exercise the exact dimension the change touches *as the deployment target experiences it*.

**Demonstrating cases:** E-01 (fresh-DB CI blind to applied-migration checksum drift; production 502), E-02 (env var present in CI, absent on Railway → boot collapse), E-03 (SMTP unreachable only from the Railway node → aggregate DOWN), E-16 (provider quota walls invisible to build), E-47 (unresolved Spring placeholder retained as a literal), and E-48 (invalid mail origin URI accepted).

**Same cause or similar symptoms?** One cause in four costumes: the check that gates the merge runs in an environment structurally different from production in precisely the failed dimension (database state, environment variables, network reachability, provider capacity). The reverts and outages are symptoms; the environment delta is the cause.

**Frequency/severity/areas:** 4 major production incidents across the whole history; each was critical (boot failure or platform self-verdict failure); areas: migrations, config, health, deployment.

**Systematic vs local prevention:** local so far — each case got its own guard (checksum guard, env discipline, health probe, runbook). The *class* has no single guard because it is a class of environment dimensions; prevention is a mandatory pre-merge question, not one test.

**Strongest prevention:** change-protocol Phase 0, step 5 — the P1 example ("state the environment dimension this change exercises and where it was verified") — + B6 for config-shaped changes + the S10-derived rule that every verdict point gets a consumer *in the target environment*.

## P2 · Framework default ≠ architecture decision; framework semantics ≠ generic judgment

**Invariant protected:** behavior contributed by framework defaults or framework-managed lifecycle is an explicit decision; audit flags on framework-managed code resolve against the pinned version's official docs.

**Demonstrating cases:** E-03 (mail health indicator in the aggregate by Boot default — no one decided SMTP outage should fail platform health), E-06 (`@Transactional` added onto a Modulith `@ApplicationModuleListener` from a generic audit rule — illegal per Modulith 2.x semantics), AGENTS.md's pinned `@ConfigurationProperties` nested-record-null lesson (documented from the official reference).

**Same cause or different?** One mechanism: the framework acted (or was "corrected") without the official-docs-for-the-pinned-version consultation. E-03 is a *default accepted*; E-06 is a *default fought wrongly*. Two directions, one root: framework authority bypassed.

**Frequency/severity:** recurring medium severity; high recurrence risk because every dependency upgrade re-opens the surface.

**Prevention status:** partially systematic — version pins + AGENTS.md rules exist; the adversarial review checklist adds "framework-managed?" to every flagged fix.

**Strongest prevention:** rule A1/P2 question at fix time: *"Is this behavior framework-managed for our pinned version? Cite the official doc section before changing it."*

## P3 · Syntactic parity masking semantic divergence

**Invariant protected:** contract fidelity is measured at the shape/semantics level (argument containers, nullability, response wrapper), not at name level.

**Demonstrating cases:** E-11 (Saleor roots with correct names but arguments outside the input object / wrong nullability), E-05 (OpenAPI-diff "compatible" while the wrapper change broke the frontend inbox), E-19 (cursor shape evolution needing a dedicated gate), E-39/E-40 (ledger reversal sign in net aggregation and response metadata), and E-43–E-46 (GraphQL/REST error mapping, locale and fallback rendering).

**Same cause?** Yes: verification scope narrower than consumer-dependence scope. Names/diff-level pass; shapes fail downstream.

**Frequency/severity:** 3 verified instances; severity high (frontend breakage, contract gate failures); recurrence risk high — every hand-aligned SDL and every wrapper refactor.

**Prevention status:** systematic for known surfaces — Root Contract Gate (D3), OpenAPI/GraphQL gates, `KeysetCursorGate` (D7), CONTRIBUTING §4 notice+battery rule. The *residual* risk is new consumer surfaces without their own gate.

**Strongest prevention:** B4's shape-change rule + "consumer-dependence inventory" in the change protocol (list who consumes this shape before changing it).

## P4 · Transaction, retry, and mutable-state coupling

**Invariant protected:** external effects and idempotency records survive partial failure; identity of an operation is stable regardless of state at retry time.

**Demonstrating cases:** E-04 (retryable boundary joined `REQUIRED` carrier → rollback-only poisoning, dedup-without-settlement), E-08 (refund key derived from dispute state → double-payment window after rollback+webhook), E-07 (AFTER_COMMIT deferral applied globally then reverted — the inverse lesson: the pattern is conditional, not universal), E-06-adjacent (listener transaction semantics), and E-33–E-38 (posted-commission reversal, asynchronous event order, zero commission, historical backfill, unique-key aggregation and optimistic-version preservation), and E-50 (concurrent intake event/sweep issue creation).

**Same cause?** One family, three mechanisms: (a) retryable work inside carrier transactions, (b) idempotency identity derived from mutable state, (c) publication timing chosen without read-path analysis. All are *transactional-boundary design* failures.

**Frequency/severity:** the highest-value family — money-adjacent (refunds), integrity-adjacent (settlements); severity critical when it fires; frequency moderate but each occurrence justifies its own ledger entry.

**Prevention status:** systematic patterns exist in code (`StableRefundScope`, AFTER_COMMIT rails, `version = version + 1` purge statements) and in CODING_STANDARDS §3; review checklist enforces the questions.

**Strongest prevention:** B2 as a conditional rule triggered by *any* external-effect or retry-adjacent change; integration tests replaying the rollback+retry scenario on real PostgreSQL (the `DisputesModuleIntegrationTest` precedent).

## P5 · One rule, many doors — incomplete enforcement across entry points

**Invariant protected:** a business rule is enforced at a single choke point, or its coverage is exhaustively enumerated and guarded.

**Demonstrating cases:** E-09 (the purge-adapter family: eleven adapters, findings kept arriving door by door), E-17 (password column written via full-snapshot update — the "door" was the wide write), CodeRabbit rows 20/13/5 (active-only gate, feature-flag 409 guards, discovery rails — each rule initially enforced on the write path but not the read rails, or vice versa), the polls kill-switch consolidated to one shared service guard, the last-admin guard counting *effective* authorities rather than stale rows.

**Same cause?** Yes — and this is the pattern the history most persistently re-teaches: a rule is implemented where the implementer stood; other doors (other modules' access, other query rails, admin surfaces, mirrors) stay open until a scanner or incident finds them.

**Frequency/severity:** the largest single share of the 2026-10 deep-scan findings; severity medium-to-high (authorization and data-completeness); recurrence risk **highest of all patterns** — it grows with every new module and adapter.

**Prevention status:** detector systematic (CodeRabbit repository scans, D6); *preventer* procedural — hence the review-protocol "enumerate alternate entry points" pass and the B3 rule (choke-point preference).

**Strongest prevention:** B3's mandatory entry-point enumeration table in the change protocol + architectural preference for shared service guards (one door, not N).

## P6 · Derived-truth drift — hand-maintained numbers and stale narrative

**Invariant protected:** every load-bearing number in machine- or owner-facing documents is derived (guarded) or explicitly dated with a re-derivation command.

**Demonstrating cases:** E-12 (guard-caught 183→184; PR-body 107/294/57 vs measured 163/238/57; campaign 98≠90, 57≠56), E-20 (false claim + dead config — narrative-code divergence), E-21 (guard message drifting from guard behavior), E-49 (repair-plan summary/count drift), regression-list item "stale duplicate numbers surviving beside the guarded phrase".

**Same cause?** Yes: truth maintained by hands instead of derivation. Different documents, one mechanism.

**Frequency/severity:** the most *frequent* finding class in CodeRabbit's 2026-10-26 review of the wave (Major findings dominated by counts); severity low-damage/high-mislead (decisions made on wrong inventories).

**Prevention status:** systematic for SYSTEM/ARCHITECTURE/README (D2 guard); **gap**: campaign reports, PR bodies, and new docs are procedural-only.

**Strongest prevention:** D2 discipline extension + A6 (new doc with load-bearing numbers ⇒ add the derivation or the dated-command note in the same PR).

## P7 · Interrupted or partial agent operations

**Invariant protected:** pushed state is atomic (compiles + passes its own tests); reported status binds to the exact pushed SHA.

**Demonstrating cases:** E-10 (tests without implementation after session interruption — module-wide `testCompile` failure; plus a test that never ran), E-41 (shared asynchronous event-test mock depended on call order), E-42 (scheduled reconciliation scanned the full payment-intent table), the standing owner protocol "execute on GitHub immediately" (existing precisely because sessions drop), E-20 (status claimed beyond evidence).

**Same cause?** Yes: agent sessions are interruptible by nature; un-atomic pushes or unbound claims turn interruption into defect.

**Frequency/severity:** low frequency, high blast radius (whole-module build breaks); recurrence risk structural.

**Prevention status:** procedural (A5) — no deterministic guard for "this commit is a complete concern" exists or is practical.

**Strongest prevention:** A5 commit atomicity + the review-protocol "test proven to run" check.

## P8 · Applied-migration immutability and the shared number space

**Invariant protected:** applied `V__` files never change; numbering is a shared concurrent resource under forward-only discipline.

**Demonstrating cases:** E-01 (the checksum outage — the founding incident), E-14 (the wave revert that *kept* migrations forward-only — the discipline holding under pressure), the measured numbering gaps (86 absent numbers in V1–V291 with zero deletions ever — numbers consumed by branches that never merged; **[VERIFIED]** as fact, **[HYPOTHESIS]** as problem: gaps are benign for Flyway but were never a *designed* policy — recorded so future agents neither "fix" the gaps nor assume renumbering is safe).

**Same cause?** One invariant (immutability + forward-only) demonstrated under three conditions: violation (E-01), rollback pressure (E-14), concurrent numbering (gaps).

**Prevention status:** systematic — `MigrationChecksumGuardTest` (D1) freezes every checksum; AGENTS.md rule; the guard's own javadoc encodes the lesson.

**Strongest prevention:** B1 (unchanged, already the strongest form: deterministic guard + byte-identical restore playbook recorded in E-01).

## P9 · Blind aggregation and verdict points

**Invariant protected:** every point that aggregates or concludes (health verdicts, gate conclusions, inventory totals, watchdog state) has an automated consumer that would notice it lying or failing.

**Demonstrating cases:** E-03 (the canonical case — the aggregate itself unwatched), E-13 (CodeRabbit Gate failing 102/264 while appearing "deployed" — nobody watched the gate's own health), E-12-adjacent (inventory totals that no derivation checks), Production Watchdog's own 112 failed runs — **[HYPOTHESIS, explicitly unverified]**: the count alone does not establish whether those failures had an automated consumer; whether any alert consumed the watchdog's own failures was not verified by this audit (it would require an alerting-configuration review; no in-repo record settles it either way). Kept as the open question B6-agg asks of every verdict point — not as a demonstrated defect instance.

**Same cause?** Yes: monitoring/derivation built for *components* but not for the *aggregators of components*. An aggregation point without a consumer is a decision that silently rots.

**Frequency/severity:** 3 verified instances (E-03, E-13, E-12-adjacent) + 1 open hypothesis (the watchdog's own failures); severity high (platform self-verdict, review-gate integrity).

**Prevention status:** systematic for health (S10 fix: gauge+alert+probe); systematic for gate-health (round 21, G5 closed: `gate-health.yml` — per-gate 7-day health, provider-refusal vs content classification, review freshness per open PR; E-13's lesson added to D6-adjacent monitoring rule).

**Strongest prevention:** B6-agg clause: "new aggregator/verdict/gate ⇒ name its automated consumer in the same PR".


## P10 · Automation-hostile source shape — layout that defeats line-based machinery

**Invariant protected:** every Java source's physical layout stays processable by the repo's line-based automated machinery — recipes that patch imports, fail-closed publication guards that verify import-only diffs, and line-anchored review.

**Demonstrating cases:** E-23 (thirteen single-line sources — package, imports, and declarations packed onto one line — carried in with the security carries; the OpenRewrite publication guard refused the batch on three consecutive main pushes while the root persisted).

**Same cause?** Yes: carried/generated code bypassed the layout conventions while no guard owned the format dimension; every line-based consumer inherits the blindness together.

**Frequency/severity:** 13 measured files across three modules (twelve by the round-26 code search, the thirteenth — `DeliveryModule.java` — by the guard's own first CI run); severity medium (red automation on main, unreviewable diffs); recurrence risk high while carriers paste generated sources.

**Systematic vs local prevention:** systematic from E-23's fix — `SourceFormatGuardTest` (D9) rejects packed package/import lines repo-wide at test time; the first twelve files were reformatted in the same commit and the thirteenth (`DeliveryModule.java`, caught by the guard's first CI run) in the follow-up.

**Strongest prevention:** D9 plus the carry rule implicit in it: sources land in standard layout (package line alone, one import per line, declaration on its own lines) — the guard makes the convention mechanical, not aspirational.

---


## P11 · Lock-order inversion and external work inside a transaction

**Invariant protected:** a transaction acquires shared locks in one global order, and database locks/connections are not held across slow network or provider calls.

**Demonstrating cases:** E-24 (order/delivery cancellation and leg-attachment acquire the same rows in opposite orders, creating an ABBA deadlock); E-25 (OSRM route lookup runs while the order row is locked and a database transaction is open).

**Same cause or different?** Related but distinct mechanisms within one transaction-scope family: lock-order inversion creates a deadlock cycle; outbound I/O inside a transaction extends lock and connection lifetime and amplifies contention. They require different corrective controls and therefore retain separate cases.

**Frequency/severity:** two confirmed mechanisms in the delivery/order boundary; severity high for availability and latency under concurrent split/cancel activity.

**Prevention status:** registered, not yet closed in the measured issue #749. The required control is one documented lock order plus a pre-transaction route lookup and real-PostgreSQL concurrency tests.

**Strongest prevention:** B2 + A3: publish a lock-order table for every shared row and prove interleaved operations on PostgreSQL; assert the route port is called before transaction/lock acquisition.

## P12 · Test evidence does not exercise the claimed invariant

**Invariant protected:** a green test must execute the failure trigger and observable state transition described by its name and acceptance claim.

**Demonstrating cases:** E-28 (purge version advancement was present, but the original test did not prove stale-write rejection on PostgreSQL; #764 added the real database proof); E-01/E-02/E-03 (CI's clean or differently configured environment did not exercise the deployed failure dimension); E-10 (test source existed but was not executed by the required pipeline), E-41/E-42 (test fixture determinism and recurring-query bounds).

**Same cause?** Yes: the test's assertion surface or execution environment is weaker than the claim it is used to justify. It can pass while the invariant is still false.

**Frequency/severity:** recurring across integration, concurrency, configuration and CI tests; severity follows the protected invariant and can be critical for money/security.

**Prevention status:** partially systematic: exact test execution and CI sharding exist, but each new acceptance proof still needs a scenario-level adequacy check.

**Strongest prevention:** before fixing, write the test against the unfixed behavior and prove it fails for the intended reason; use real PostgreSQL for database concurrency/constraint claims and verify the test is included in CI.

## P13 · Review-gate protocol disagreement

**Invariant protected:** every gate in a release path consumes the same definition of a valid review artifact and produces one coherent verdict.

**Demonstrating cases:** E-26 (the CodeRabbit review poll accepted an empty-body approval while the exact processed-file coverage verifier rejected that same review; the PR could become stuck after review threads had been resolved).

**Same cause?** Yes: two independently implemented gate steps disagree about the admissible evidence, so satisfying one gate cannot satisfy the composed release gate.

**Frequency/severity:** one newly confirmed governance defect in the November 12 follow-up; severity high because it blocks or misreports release readiness.

**Prevention status:** a documented workaround exists for the affected full review, but a structural unification is still open. This must not be called fixed until both gate stages share and test the same acceptance predicate.

**Strongest prevention:** shared schema/parser or a single authoritative verdict artifact; contract tests must feed empty-body, stale-head, incomplete-file-list and duplicate-review examples through every consumer.

## P14 · Automation toolchain drift

**Invariant protected:** repository automation runs with the exact toolchain declared and pinned by the repository, not an ambient runner installation.

**Demonstrating cases:** E-27 (OpenRewrite invoked system Maven 3.9.15 while the repository's Maven Enforcer required >=3.10; PR #813 moved the workflow to the Maven Wrapper); E-29 (the catalog job ran against Dependabot changes and forced a manual-only recovery path); E-30 (vendored skills existed without a reviewed pin/provenance registry).

**Same cause or different?** The common failure is hidden automation inputs: executable version, event class or downloaded agent resource is not governed by the repository's declared source of truth.

**Frequency/severity:** three verified automation/governance cases across CI and agent supply chain; severity moderate-high due to non-reproducible or unreviewable automation.

**Prevention status:** partly systematic: #813 fixed the Maven wrapper path on main, #770 excluded Dependabot from the incompatible catalog audit, and #771 added skill pins. The gate itself must verify exact tool versions and input provenance.

**Strongest prevention:** B6 plus D11: pin toolchain inputs, test automation branches including Dependabot and workflow-dispatch paths, and record the source SHA/version for downloaded tools and skills.

---

## Candidate patterns examined and rejected (recorded to prevent re-litigation)

- **"Module count growth as defect"** — rejected: 24-module Modulith growth is the designed architecture (SYSTEM.md §5), not a failure mechanism; the churn sentinels (E-22) record the cost honestly.
- **"CI failure volume as defect signal"** — rejected as a *pattern*: 2,569 failures correlate with agent-era iteration speed (Sep–Oct escalation matches the wave cadence); the *actionable* signal is which failure *classes* produced incidents (all captured above), not the count. Kept as a monitoring metric, not a causal pattern.
- **"Arabic/English mixing as defect source"** — rejected: the language policy is binding and measured (AGENTS.md §1); no incident in the ledger traces to it.
