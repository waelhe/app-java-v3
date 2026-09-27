# Code-quality remediation & development plan — 2026-09-27

**Status:** proposed — queued for execution, one PR per work unit.
**Source of truth:** `code-quality-audit-2026-09-25.md` (repo root, merged via #423 with its
review-adoption corrections) and the forensic verification posted on #423 (2026-09-27).
**Companion documents:** `docs/comprehensive-reverification-repair-plan-2026-09-26.md`
(platform-readiness closure — separate track, already executed) and `docs/proposals/`
(#422 open-source extraction wave — separate queue).

This plan converts the audit's **Top 5 priorities** and its verified backlog into executable
tracks. It follows two rules the audit itself set: every number is re-measured rather than
inherited, and every fix installs the gate that makes regression **fail the build instead of
waiting for review**.

---

## 0. Method

1. **Measure-first.** Every figure in this plan was re-measured on `main @ 70638fe`
   (2026-09-27). The audit's baseline was `24e1e998` (2026-09-25); drift between the two
   trees is documented below, not silently inherited.
2. **Official-docs-first.** Every design decision cites its official basis — Maven, Spring
   Framework, Spring Modulith, Spring Data JPA, Spring Authorization Server, ArchUnit,
   Testcontainers, JaCoCo. Where the audit proposed a direction, this plan validates it
   against the official documentation before adopting it; where the two disagree, the
   official document wins and the deviation is recorded here.
3. **No patches, no debt.** Each track repairs the root cause and installs its guard in the
   same PR. Items deliberately deferred are recorded as deferrals with rationale in §6 —
   never dropped silently.
4. **Repo conventions.** One PR per work unit; `./mvnw clean verify -pl <module>` before
   push (AGENTS.md); squash merge after CI 6/6 and CodeRabbit threads resolved (§14.3).

---

## 1. Live baseline (main @ 70638fe, re-measured 2026-09-27)

| Figure | Live value | Documented today as | Status |
|---|---|---|---|
| Maven modules | **22** = 18 domain + shared + platform-infra + app + **edge** | `README.md:4` "21" · `SYSTEM.md:24` "21" · `docs/architecture/ARCHITECTURE.md:25` "16" | three stale claims; every breakdown forgets `marketplace-edge` |
| Flyway migrations | **69 versioned (V1..V70, V35 retired) + 2 repeatable = 71 files** | `SYSTEM.md:142` "70 files / V69 latest" | stale after V70 |
| Named caches | **15** (`spring.cache.cache-names` CSV, `application.yml:186`) | `SYSTEM.md:145` "13" · `application.yml` comments "all 13" (×2) and "the 14th" (×1) | stale |
| `ModuleIntegrationTest` classes | **22** | `SYSTEM.md:166` "14" | stale |
| Integration-test selection | **79 files selected by Failsafe** (`**/*IT.java` + `**/*IntegrationTest.java` includes; 2 carry the `*IT` suffix, 77 end in `IntegrationTest`; 250 unit files run under Surefire) | `SYSTEM.md:55` "34" | stale; needs one canonical phrasing |
| `permitAll` rules | **15** (`SecurityConfig.java:174-274`) | 12-at-baseline, drift documented in #423 | already handled by #423 |
| Framework versions | **Spring Boot 4.1.1 / Spring Modulith 2.1.1** (`pom.xml` parent + BOM) | `ARCHITECTURE.md:25` "4.1.0 / 2.1.0" | stale |
| Catalog module-local tests | **11 files / 118 tests; BUNDLE coverage 0.718 ≥ 0.70** (#408) | — | audit priority 2 largely closed; verify (Track 2) |
| ArchUnit rules live | **11** (`ArchitectureRulesTest` 9 `@ArchTest` + 2 `@Test`; `ModulithVerificationTest`) | — | base for Tracks 3/5 |

The audit's warning that "the wrong interpretation would merely install a new wrong
number" already materialized once: the drift-resistant README introduced by #405 says
"21" while the reactor holds 22 — the module it forgets is `marketplace-edge`. Track 1
exists to end this class of failure, not to fix these eight instances by hand only.

---

## Track 1 — Documentation truth guards [priority 1 · effort S]

**Problem.** Ten stale numeric claims plus one version drift (the Boot and Modulith
versions — table above). The project's own map misinforms every reader and every tooling
session — the audit rates this **high**, and it is confirmed live on `main` today.

**Design — official basis.**
- JUnit 5 tests as governance guards, exactly the established pattern in this repo:
  `PlatformGovernanceFilesTest.containerScanIgnoresAreBoundedEntries` asserts a bounded
  list against the real file content. The guard **derives** every figure from its source
  of truth at test time and asserts the documented phrasing matches.
- Maven: the reactor is the single authority for module count — "Guide to Working with
  Multiple Modules" (the reactor computes its build order from the `<modules>` of the
  root POM). The guard parses `pom.xml` `<module>` entries; no hand-maintained constant.
- Flyway: the migration folder is the authority (`db/migration/`, V/R naming, retired
  numbers allowed — V35 is retired by design and the count must not "fix" that).
- Spring Boot: the `spring.cache.cache-names` CSV in `application.yml` is the authority
  for cache count; the POM parent/`<properties>` for framework versions.

**Steps.**
1. New `DocumentationNumbersGuardTest` in `marketplace-app/src/test/java/com/marketplace/config/`
   (beside `PlatformGovernanceFilesTest`), one `@Test` per figure, each with the canonical
   phrasing constant and a `because` citing the derivation source.
2. Fix the stale claims in the same PR: `README.md:4`, `SYSTEM.md:24/:55/:142/:145/:166`,
   `docs/architecture/ARCHITECTURE.md:25`, and the three `application.yml` comments
   ("all 13" ×2, "the 14th" ×1). The guard must go red→green inside the PR — red first
   proves it detects the drift, green proves the docs were fixed.
3. Canonical phrasings (exactly one per figure, everywhere):
   - modules: "**22 Maven modules** (18 domain + shared contracts + platform infrastructure
     + app assembly + edge)"
   - migrations: "**69 versioned migrations** (V1..V70, V35 retired) + **2 repeatable
     seeds** = **71 files**"
   - caches: "**15 named caches**"
   - module integration: "**22 `ModuleIntegrationTest` classes**"
   - integration selection: "**79 integration files selected by Failsafe** (2 `*IT` + 77 `*IntegrationTest`)"
   - versions: derived from the POM (never hand-typed in prose)

**Gate.** `./mvnw clean verify -pl marketplace-app` green with the guard active; the guard
fails the build whenever any guarded figure drifts from its derived value.
**Risk.** Low — test + documentation only. No production code touched.

---

## Track 2 — Coverage gate over `marketplace-catalog` [priority 2 · effort S (verify)]

**Problem (audit).** At audit time the module had 27 main files and 0 module-local test
files, so the module-local BUNDLE gate was effectively absent — the largest domain module
was outside the quality gate.

**Live state (re-measured).** #408 added **11 test files / 118 tests** and reports the gate
met at **0.718**. The audit's gap appears largely closed — but "appears" is not a gate.

**Design — official basis.** The root POM binds `jacoco-maven-plugin` (0.8.15) in
`<build><plugins>` with a `check` execution: element `BUNDLE`, counter `INSTRUCTION`,
`COVEREDRATIO` minimum `0.70` (`pom.xml:62` property). Maven plugin executions declared in
the parent POM are inherited by every module of the reactor ("POM Introduction — Inheritance
& Interpolation"); nothing new needs designing — the gate exists and inherits.

**Steps.**
1. **Verify, don't assume:** run `./mvnw clean verify -pl marketplace-catalog -am` and
   capture the JaCoCo `check` execution line for the module — proving the gate binds, the
   exclusions apply, and the measured BUNDLE ratio ≥ 0.70.
2. Record the verified state in `SYSTEM.md` (quality row) with the canonical phrasing
   "the catalog module's BUNDLE gate is bound and met (0.718 at #408)". Coverage ratios
   drift legitimately with code growth — they are **not** guard material (the guard
   protects documented figures, not living metrics).
3. Contingency: if (1) shows the gate does not actually bind for the module, fix the
   binding per the JaCoCo "check goal" documentation — that would be the root-cause repair,
   and the fix lands with the proof line in the same PR.

**Gate.** The captured CI/log line showing the JaCoCo check executed for
`marketplace-catalog` with ≥ 0.70.
**Risk.** None (verification); low (contingency binding fix).

---

## Track 3 — Entity-free HTTP boundary [priority 3 · effort M]

**Problem (audit).** `MessagingWebSocketController` imports the messaging JPA entity and
`MessagingService.sendMessage` returns it (`MessagingService.java:177-182`); four
controllers expose JPA entities in 9 endpoint signatures (`AvailabilityController`,
`NotificationController`, `LedgerController`, `ProviderLedgerController`). The audit itself
labels the controller-signature claims as agent-measured "not re-read line by line" — so
the first step here is re-measurement, then repair.

**Design — official basis.**
- Spring Modulith reference ("Structuring the Application", module API): a module's
  exposed API consists of the interfaces and DTOs it chooses to publish; entities are
  internal by default. Crossing the boundary with an entity couples every consumer to the
  persistence schema.
- Spring Framework / Spring Data JPA: controller return values are the wire contract;
  returning entities couples response shape to schema evolution and to lazy-initialization
  semantics. DTO projection is the documented recommendation for API responses.
- ArchUnit: rules-as-tests so regressions fail the build (the repo already runs 11 such
  rules — this adds the missing one).

**Steps.**
1. Re-measure: inventory the exact entity types crossing the 9 REST signatures and the
   WebSocket path on `main` (grep + compile-level check). The fix list is the measured
   list, not the audit's list.
2. Introduce record DTOs in the owning modules; map at the service boundary. Mapping
   tooling: **MapStruct 1.6.3 is already the repo's mapping framework** (root
   `dependencyManagement` + annotation processor in the root POM; `marketplace-catalog`
   already depends on it) — use it where the module's conventions do, plain records +
   manual mapping where simpler.
3. WebSocket: `MessagingService.sendMessage` returns a DTO record; the controller imports
   only the DTO. Keep the entity strictly inside the messaging module.
4. New ArchUnit rule in `ArchitectureRulesTest`:
   `controllersMustNotDependOnJpaEntities` — no class annotated `@RestController`/`@Controller`
   (and no class in a `..websocket..` package) may depend on a class annotated `@Entity`,
   with `because(...)` citing the Modulith module-API boundary. Red before the fix, green
   after — in the same PR.
5. Wire-contract safety: `.ci/check-openapi-compat.sh` compares the live spec against the
   latest release tag — run it in the PR and document any deliberate schema-name changes.
   Breaking changes, if any, are explicit in the PR description, never accidental.

**Gate.** New rule red→green; CI 6/6 including the OpenAPI compatibility step; per-module
integration suites green.
**Risk.** Medium — wire-contract changes on up to 9 REST endpoints + the WS payload.
Mitigated by the OpenAPI gate, module integration tests, and the DTO records being
1:1 snapshots of the current response shapes unless a change is deliberate.

---

## Track 4 — Test duplication that compounds [priority 4 · effort M]

**Problem (audit).** The same ~10-line PostgreSQL/Redis container pair repeated in 35 of
74 integration-test classes; 5 classes redeclare the same authorization-server fixture
(`it-login-gate-client` constants); 44 unused imports across 37 files; 39 Java + 10 SQL
files without a final newline.

**Design — official basis, with one deliberate repo invariant.**
- Testcontainers' official guides describe **singleton/shared containers** — and that
  pattern is **rejected here by design**: `PlatformGovernanceFilesTest.integrationTestsOwnTheirDatabaseContainer`
  enforces that every integration test class owns its own `@Container @ServiceConnection`
  PostgreSQLContainer field (the #382 structural fix: every IT gets a dedicated database;
  no cross-class state bleed). The audit itself concludes: "Extract a shared factory while
  keeping the per-class `@Container` fields the isolation guard requires."
- The root-cause dedup therefore extracts **configuration, not ownership**: a single
  factory (e.g. `IntegrationContainers.postgres()` / `.redis()`) returning fully
  configured container instances; each class still declares its own `@Container` field
  initialized from the factory. Boilerplate dies; isolation survives. JUnit 5 inheritance
  may carry shared assertion plumbing, never container fields.
- The OAuth fixture: one test fixture class owning the `it-login-gate-client` registration
  constants (client id, secret, callback URI, authorize/token paths); the 5 classes
  reference it. Spring Authorization Server's testing guidance keeps client-registration
  constants in one place — the drift risk of five copies is exactly what the audit
  measured ("authorize path 7, token path 8" mentions across 5 files).
- Mechanical sweep: 44 unused imports + 49 missing final newlines in one zero-behavior
  commit.

**Steps.**
1. Re-measure the 35/74 + 5 fixture classes on `main` at execution time (the audit's
   counts are baseline-time).
2. Extract the container factory; migrate the classes keeping their own fields.
3. Extract `AuthorizationServerFixture`; migrate the 5 classes.
4. Mechanical import/newline sweep.
5. The isolation guard (`integrationTestsOwnTheirDatabaseContainer`) must stay green
   throughout — it is the invariant this track protects.

**Gate.** Isolation guard green; full CI 6/6; diff review confirms zero behavioral change
(tests only).
**Risk.** Medium — touches ~40 test files. Mitigated by the invariant guard and the
per-module IT suites.

---

## Track 5 — Architecture rules that exist on paper only [priority 5 · effort S (rules) + M (ports)]

**Problem (audit).** Transaction placement is documented-only (`docs/CODING_STANDARDS.md:115-163`);
framework-neutral ports are unenforced — re-measured 2026-09-27: **2 of the 24 ports** in
`marketplace-shared` import Spring `Page`/`Pageable` (`CatalogSearchPort`,
`RealestatePropertyFilterPort`); package layering is unenforced (flat packages in all 18
domains).

**Design — official basis.**
- Transactions: Spring Framework reference, "Declarative Transaction Management" —
  proxy-based AOP makes `@Transactional` effective only on public methods invoked through
  the proxy; self-invocation and non-public methods are the documented pitfalls. The repo
  standard's hard prohibitions (CODING_STANDARDS.md §3.2: not on controllers, not on
  private methods) mirror the official semantics. Live re-measurement (2026-09-27,
  adoption of the CodeRabbit finding on this plan): ~162 production `@Transactional`
  occurrences — ~125 on `*Service` classes and ~37 on SPI adapters implementing shared
  ports (`*Adapter`, e.g. `AvailabilityLookupAdapter`) plus scheduled jobs
  (`LeadsFingerprintCleanupJob`); **0 on controllers and 0 on private methods — the two
  documented prohibitions are green today.** Adapters and jobs are business-boundary
  beans in the standard's intent, so "services only" would be a rule the code legitimately
  violates 37 times; the rules below encode the prohibitions the standard actually states.
- Two new ArchUnit rules in `ArchitectureRulesTest`:
  1. `transactionsMustNotLiveOnControllers` — no class annotated `@RestController`/
     `@Controller` (or named `*Controller`) may use `@Transactional`, `because(...)`
     citing the proxy semantics + the standard's controller prohibition — **green today
     (measured 0) and made permanent**.
     `transactionsMustNotBePrivate` — no private method carries `@Transactional`, same
     official basis — **green today (measured 0) and made permanent**.
  2. `sharedPortsAreFrameworkNeutral` — classes in the shared-contracts package named
     `*Port` must not depend on `org.springframework..` (this rule is red today — by
     design, it drives the fix below).
- Ports repair: introduce a neutral pagination record in `marketplace-shared` (e.g.
  `PageRequest(int page, int size)`), change the two ports' signatures to it, and map to
  Spring `Pageable` inside the catalog/realestate adapters — the port signatures become
  100% framework-neutral while the runtime keeps Spring Data pagination intact. Spring
  Modulith's boundary model: the shared contract module is the last place framework types
  should leak.
- **Package layering (`api`/`internal`/`persistence` per domain): deliberately deferred**
  — a structural migration across 18 domains done "partially" would be exactly the
  patch-work the repo forbids. It gets its own plan when scheduled (§6). Recorded here so
  it is not silent debt.

**Steps.**
1. Add the two transaction rules — both green today by measurement (0 controller usages,
   0 private usages); they make the documented prohibitions permanent instead of waiting
   for review.
2. Add the ports rule (red), decouple the two ports with the neutral record, adapters map
   to `Pageable` (green) — same PR.
3. Record the layering deferral in `SYSTEM.md` governance section with a pointer to §6.

**Gate.** Rules red→green as specified; CI 6/6.
**Risk.** Low for the rules; medium for the two ports (interface change ripples into the
catalog and realestate call sites — bounded, both measured).

---

## 6. Deferred backlog (documented, sequenced after the tracks)

Each item below ships as its own PR when scheduled; none is silent debt.

| Item | Audit anchor | Approach |
|---|---|---|
| `CatalogService` 794 lines; 6 classes > 400 lines | §3 finding 5 | module-internal split along cohesive seams; Modulith events remain the contract; coverage gate + module tests hold the behavior |
| 17/18 domains depend directly on `marketplace-platform-infra` while owning concrete adapters (`S3MediaStorage`, `StripePspChannel`) | §3 finding 4 | hexagonal completion — port in the domain, adapter remains in infra (Modulith's suggested arrangement) |
| 601 YAML keys / 31 duplicated path-value pairs / 6 files | §5 | consolidation pass with the documented property-source order |
| Comments at 37% of main lines; some restate code | §5 | prune only code-restating comments; keep measured WHY with citations |
| 7 switch-on-type dispatch sites | §3 finding 7 | strategy objects where dispatch is genuinely polymorphic |
| 3 beans with > 7 constructor collaborators | §3 finding 5 | split along cohesive seams |
| `getMessage()` 31 sites untraced | §1 gaps | one tracing pass, documented |
| Cross-layer literals ("status", "listing_id", "COMPLETED") | §4 | centralize where the coupling is intended |
| Flat packages in 18 domains | §3 finding 3 | dedicated structural plan (deliberate deferral, Track 5) |
| 2 unbounded `findAll()` (geo reference data, pricing rules) | §2 | bounded only if measured growth demands; both are small reference sets today — verify size first |

## 7. Explicitly out of scope (owner decisions, tracked elsewhere)

MFA (absent, recorded) · GraphQL depth/complexity limits · per-endpoint IDOR sweep ·
production image contents (all "not verified" by the audit) · LICENSE decision (#422 §6) ·
JWT r3 rotation before 2026-12-08 · BE-02a `callbackURL` on signOut (frontend team) · the
open-source extraction tracks (#422).

## 8. Sequencing & delivery

| Order | Track | Effort | Why this order |
|---|---|---|---|
| 1 | Track 1 — documentation truth guards | S | truth first: every later number lands on honest docs, and the guard protects all of them |
| 2 | Track 2 — catalog gate verification | S | close the audit's #2 with a measured proof line |
| 3 | Track 5 (rules) + Track 3 | S + M | boundary rules land while the tree is green; entities leave the HTTP boundary with the rule enforcing it forever |
| 4 | Track 4 — test fixture hygiene | M | with the invariant guard already in place |
| 5 | Track 5 (ports) | M | neutral pagination record + two port migrations |
| 6 | Backlog | M+ | by owner prioritization from §6 |

Every work unit: branch → `./mvnw clean verify` → PR → CodeRabbit review → CI 6/6 →
squash merge (§14.3). The audit's numbers become the guard's expected values; the guard's
failure list is the remediation checklist — the build, not a review, keeps the system
honest from here on.
