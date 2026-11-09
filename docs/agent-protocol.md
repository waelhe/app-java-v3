# The Backend-Agent Protocol — the durable repo version

> Document status: **the agent operating layer of the root file family** —
> the zero-memory entry + acquisition protocol for AI agents working on
> `app-java-v3`. It does NOT replace the master method
> ([`docs/METHODOLOGY.md`](./METHODOLOGY.md) — the 6-stage workflow is the
> stage engine this protocol wraps) nor the enforced rules (`AGENTS.md` §0);
> it binds them together so a fresh agent can rebuild full session state
> from GitHub alone. Truth lives in the repo: if any local artifact (the
> global `~/.config/opencode/AGENTS.md`, the local `protocol-enforcer`
> skill, session-downloaded JDKs) disagrees with the repo, **the repo
> governs** and the local artifact is patched to match.
> Effective: 2026-11-03 (protocol revision; initial bootstrap protocol 2026-09-29). Provenance: built by the frontend platform agent
> (waelhe/web-marketplace) under the owner's one-time role exception
> («قم باستثناء لدورك هذه المرة وقم بتصميم وهندسة و ببناء هذا النظام
> لهم. لانك الاكثر معرفة به. ثم عد لدورك», 2026-09-28 — "make an
> exception to your role this once: design, engineer, and build this
> system for them, because you know it best; then return to your role"),
> transferred from the frontend twin
> (`waelhe/web-marketplace:docs/agent-protocol.md`, PR #10 + #11 — A/B
> validated with six zero-context agents). **The backend team owns this
> document from here**; amendments ride the normal cycle (branch → PR →
> the three merge gates). Maintained with the family: `AGENTS.md` (rules) /
> `SYSTEM.md` (mechanism) / `PROJECT_MAP.md` (state) /
> [`CONTRIBUTING.md`](../CONTRIBUTING.md) (human entry) /
> [`docs/CODING_STANDARDS.md`](./CODING_STANDARDS.md).

---

## 0. Why this protocol exists

Agent environments collapse silently between sessions: clones vanish,
local skills vanish, the global `~/.config/opencode/AGENTS.md` that
`AGENTS.md` header references is machine-local state, and even the JDK
is downloaded fresh per session (Temurin 25.0.4.1, "نُزّل للجلسة" —
PROJECT_MAP 2026-09-25). What survives every collapse: **GitHub**. The
backend team already runs on this truth de facto — every governing file
lives in the repo, every fact carries its evidence `ملف:سطر` (SYSTEM.md
golden rule), every merge is measured. What was missing is the **chain**:
one durable document a zero-memory agent can start from that leads to
all of it. That is this protocol.

> **GitHub is the only truth. No local state is believed before it is
> re-derived from the repo. Nothing precious is stored only locally.**

And you are a professional software engineer — Java 25, Spring Boot
4.1.2, Spring Modulith 2.1.2, Spring Authorization Server 7.1.2, Maven,
Flyway, PostgreSQL/PostGIS, Redis — working to the official docs at the
pinned versions and the repo's own measured facts, never training
memory: **Spring Boot 4.1.2 is not the Spring Boot you know.** The
binding epistemic order (METHODOLOGY.md §1.1, the owner's standing words
recorded in the repo: «لا أريد ما تراه أنت صحيحًا، أريد التصميم الرسمي
حسب الإطار والنظام وحقائق الكود» — "I don't want what you think is
right; I want the official design per the framework, the system, and the
facts of the code"): (1) official docs at the installed versions
(sources listed in METHODOLOGY.md Appendix C) + the repo's evidence
files; (2) the network for gaps, same versions only; (3) when prose and
the running system disagree, **the measured environment governs** —
«الوثيقة لقطة بتاريخ؛ البيئة هي الحقيقة» ("the doc is a dated
snapshot; the environment is the truth" — CONTRIBUTING.md §5).

## 1. Language policy (binding)

- **English is the machine layer**: code, code comments, commit titles,
  `docs/METHODOLOGY.md`, this protocol, and new AI-facing technical
  documents (the frontend A/B test — six zero-context agents — validated
  the English AI-facing format; ~8% token saving, structural validity
  restored).
- **Arabic is the owner layer**: every reply to the owner is Arabic; PR
  bodies are Arabic with measured numbers (the repo's standing practice —
  see any merged PR); the existing Arabic root files (`AGENTS.md`,
  `SYSTEM.md`, `PROJECT_MAP.md`, `CONTRIBUTING.md`) stay Arabic as
  recorded.
- The owner's literal words — the merge word «ادمج», standing rules —
  are quoted verbatim with an English gloss. They are data, not prose.

## 2. Project identity (stable facts, each measured)

| | |
|---|---|
| Owner | GitHub **waelhe** (repo `waelhe/app-java-v3`, public) — day-to-day engineering owned by the **backend team**; the frontend repo's agent treats this repo as read-and-measure (its standing rule), except owner-granted documented exceptions |
| Stack | Java 25 (`--release 25`, `pom.xml:58` — `<java.version>`), Spring Boot 4.1.2 (parent, `pom.xml:7-10`), Spring Modulith 2.1.2 (BOM), Spring Authorization Server 7.1.2 (Boot-BOM-managed), Maven distribution 3.10.0 + wrapper tool 3.3.4 with enforcer floor `[3.10,)` (`pom.xml:1007` — `<requireMavenVersion>`) — all evidenced in SYSTEM.md §1 |
| Modules | **30** Maven modules in the root reactor (measured 2026-10-24 from the root POM's `<modules>` block at the PR #714 head; guarded in README/SYSTEM by `DocumentationNumbersGuardTest`, derived from `<modules>`) |
| Data | PostgreSQL 18 + PostGIS (CI service `postgis/postgis:18-3.6-alpine`, `ci.yml:24`); production DB channel = external Neon (since 2026-09-18, SYSTEM.md §1); Redis 8 (`ci.yml:37`; production 8.2); Flyway with `ddl-auto: none` — latest migration **V70** (measured 2026-09-28) |
| Quality | JaCoCo 0.8.15, BUNDLE ≥ 70% per module (`pom.xml:61-62, 242-244`); `failOnWarning` at compile; unit (`*Test`, surefire) strictly separated from integration (`*IT`/`*IntegrationTest`, failsafe; environmental ones skip without Docker) |
| OpenAPI | `GET /v3/api-docs` — **128 operations across 111 paths** (re-measured live on staging 2026-09-28; matches CONTRIBUTING.md §4 @2026-09-27; its re-verification jq command is the authority) |
| CI | 7 workflows (measured 2026-09-28, `.github/workflows/`): `ci.yml` (gitleaks high-policy with anchored `.gitleaks.toml` allowlist → OpenAPI backward-compatibility gate `.ci/check-openapi-compat.sh` → `./mvnw verify` with services), `codeql.yml`, `container-scan.yml`, `integration-test.yml`, `fork-sync.yml`, `maven-publish.yml`, `watchdog.yml` |
| Production | `app-java-v3-production.up.railway.app` (measured 200 on `/v3/api-docs` 2026-09-28) — Railway, GitHub-connected `waelhe/app-java-v3@main`, **auto-deploy on every merge** (measured repeatedly in PROJECT_MAP: functional builds ~2-3 min, docs-only warm builds ~44-61 s, staging ~68 s) — **so merging to main IS the production deploy trigger** |
| Staging | `app-java-v3-staging-staging.up.railway.app` (measured 200 + 128 ops 2026-09-28) — the frontend repo's dev backend |
| Frontend consumer | `waelhe/web-marketplace` (Next.js BFF) consumes this OpenAPI — contract changes ripple there (see §6, the shape-compat lesson) |
| Secrets | Live in Railway only, rotate (CONTRIBUTING.md §5); `client_id` is an environmental fact, not a doc constant; gitleaks guards the repo |
| Language | Owner: Arabic. Code + METHODOLOGY + this protocol: English. PR bodies: Arabic with measured numbers |

## 3. Session bootstrap ritual (in this order, every session)

1. **GitHub truth and access**: read the repository, current default-branch SHA,
   open PRs, and workflow state through the connected GitHub interface.
   Never assume a stale local clone is current. If GitHub access is unavailable,
   report the blocker rather than substituting unverified local state.
2. **Read the in-flight work**: recent commits through GitHub + open PRs and
   issues via the API, **with full pagination** — a capped page silently
   truncates the list (measured 2026-09-28: `per_page=10` showed 10 of
   28 open PRs — the retro review campaign `review/retro-*` #431-#457
   plus fix branches). Open PRs are forward-contract risk: a merged wave
   can change the OpenAPI the frontend consumes. Note that
   `PROJECT_MAP.md` can LAG behind merged waves (measured 2026-09-28:
   its newest section recorded merge #383 while main sat at #458 — the
   repo's own recorded truth-sync-gap pattern) — the recent commit history + PR
   list are the fresher truth; the map is a dated snapshot.
3. **Load the root family in the AGENTS.md §0.1 mandatory order**:
   `SYSTEM.md` (the mechanism, §1-§14) → `PROJECT_MAP.md` (the state:
   what is merged, open, pending — read the newest wave sections first)
   → the governing file for the task at hand (the §0.1 map: auth
   redesign, client bootstrap, feature expansion, …; default
   `docs/CODING_STANDARDS.md`). `AGENTS.md` itself is read first by
   agent frameworks and re-read here.
4. **Load the defect-knowledge system** (`docs/governance/defect-knowledge/`
   — §9's regression list is its quick view): before editing, load
   `change-protocol.md` and apply the pattern families it triggers
   (`pattern-catalog.md`, P1–P10); before writing a fix, check
   `evidence-ledger.md` for a case matching the symptom (E-01…E-23)
   and the rulebook rules (A/B/C/D) it binds; after implementing, run
   the adversarial pass in `review-protocol.md`; when a guard fails,
   `enforcement-map.md` states the rule → enforcement binding. The
   system is permanent; the quick view only points into it.
5. **Activate task-specific skills and source references**: read
   `docs/governance/agent-skills-policy.md` and
   `docs/governance/plans/agent-skills-pins.json`; load each relevant
   vendored `.agents/skills/<name>/SKILL.md` and its specific rules or
   references. Check that its pin/registry is current. A skill is a
   specialist reference, never a replacement for official docs, the
   canonical domain plan, a pinned schema, or code/tests. Never run an
   unpinned installer (including `npx skills add`) against this repo.
   For a Saleor task, consult https://docs.saleor.io/developer/ai,
   start from its `llms.txt`, use the read-only Documentation MCP when
   available, and use a separate GraphQL MCP/schema source only when
   needed. Use the schema matching the repository's pinned Saleor
   version; never substitute an unreleased `main` schema. Load the
   adopted `saleor-core` skill for the discount/stock/order behaviors
   it covers. Other Saleor skills remain unadopted unless the actual
   task requires them and their adoption is approved and pinned through
   the repository policy. For all other technologies, discover and
   load only relevant, properly versioned skills and official sources.
6. **Optional local toolchain**: when a supported local environment is
   available, use the pinned Temurin JDK/Maven wrapper for supplementary
   feedback. Do not block GitHub-first execution on creating a local
   environment; GitHub Actions remain the required remote verification
   record. Docker-dependent integration tests are judged by the configured
   remote workflow when no supported local Docker environment exists.
7. **Measure before building** (METHODOLOGY.md Stage 2 is not optional):
   live `/v3/api-docs` on staging, the target endpoints' success AND
   failure shapes, the repo's own evidence at `ملف:سطر`. Every parity
   claim carries its measurement date.
8. **Emit the §0.2 declaration before any mutating action** (AGENTS.md —
   enforced): `[الملف الحاكم]` (governing file) / `[النواة المعمارية]`
   (architecture core) / `[البند من المصفوفة §14.2]` / `[أثر على
   الحدود]` (boundary impact) / `[دين]` (debt) / `[معرفة العيوب]`
   (defect knowledge — the §B/§C rulebook rules the change's triggers
   load, or «لا محفزات»). An action without the
   declaration is stopped before it executes.
9. **Remote branch and durable edits on GitHub**: create a task branch from the exact observed base
   SHA and make durable edits/commits on GitHub; open a PR for review. Do not
   push directly to `main`. A local clone/toolchain is optional supplementary
   support, not the primary execution surface or source of truth.
10. **The word gates stand**: gates B and C (AGENTS.md §0.4) open only on
   the owner's explicit word; no assumed answers.

## 4. The five operating rules (mapped to the repo's own governance)

1. **Definition before building** — work starts from a governing file
   and the SYSTEM.md §14.2 consistency matrix + gates A/B/C,
   never from an endpoint that happens to exist. `PROJECT_MAP.md` is the
   register of what is merged/open/pending.
2. **The 6-stage workflow is the method** (METHODOLOGY.md, the master
   protocol): Research First (official docs, verbatim quotes) → Verify
   Reality (read the actual code) → Establish Baseline (record test
   counts — the safety net) → Implement Cleanly (CODING_STANDARDS) →
   Verify No Regression (same tests, delta 0, JaCoCo, Modulith
   verification) → Document & Ship. Skipping stages is a forbidden
   process pattern (§16.2).
3. **Full execution authority — on the branch; the merge is
   word-gated** — branches, implementation, and branch-level checks are the
   agent's, transparently. Merging to `main` happens ONLY on the owner's
   explicit word («ادمج» and its variants — every merge wave in
   PROJECT_MAP records its word), through the three measured merge
   gates: **CI green on the head + CodeRabbit review success + zero open
   threads + mergeable/CLEAN**. The merge IS the production deploy
   trigger (auto-deploy, §2). Exceptional merges are documented with the
   owner's literal command (the #366 precedent).
4. **The gates are sacred** — never bypass or weaken: the CI suite
   (gitleaks → OpenAPI compat gate → build → unit + integration with
   services), JaCoCo ≥ 70% per module, Modulith boundary verification,
   the mandatory PR checklist (`.github/pull_request_template.md`),
   at least one approval, no unresolved review comments. A regression
   pin must FAIL on the previous code (the #458 standard: two pins, each
   proven red before the fix). Never merge red — locally or on CI.
5. **Measurement governs** — every fact carries its evidence `ملف:سطر`
   (SYSTEM.md golden rule) and its last-verified date + re-verification
   command (CONTRIBUTING.md R1); the environment outranks the doc
   snapshot; verified against code AND official semantics before
   adoption (the #458 review-adoption bar). And when live verification
   is impossible — **stop claiming and declare it** instead of guessing.

## 5. The agent cycle (one concern = one branch = one PR)

1. Read the governing file + SYSTEM.md §14.2 matrix and the relevant
   task-specific pinned skills → 2. verify the applicable official docs
   and matching official source repository → 3. measure current code,
   live contracts, and baseline. The baseline is a MEASURED record with
   a named source, never an assumed value: either the latest completed
   GitHub Actions run on the observed base SHA (its run link cited in
   the PR) or the locally recorded Stage-3 output when local execution
   is available — docs/METHODOLOGY.md's BEFORE/AFTER/DELTA table cites
   whichever source produced each number. When NO completed run exists
   on the observed base SHA and local execution is unavailable, the
   BEFORE is obtained by running the SAME test set remotely: dispatch
   the repository's own workflow on that base SHA (a pushed baseline
   branch from the base SHA with the identical suite — GitHub Actions
   as the executor, never a hand-rolled substitute), and when the
   LATEST completed run sits on a DIFFERENT SHA that does not include
   the affected code, a comparable completed run on the SAME base SHA
   is the acceptable BEFORE — never a number transplanted from a
   different code state (the 2026-11-08 #806 round-9 review) → 4. create a remote branch from the
   observed GitHub base SHA → 5. implement on that branch with the
   AGENTS.md §0.2 declaration and the official supported APIs/extension
   points → 6. add regression pins and run relevant tests. Run local
   Maven/JDK checks when available for supplementary feedback, but local
   execution is not a prerequisite for GitHub-first work. Use GitHub
   Actions on the current PR head as the required remote verification
   record → 7. open/update the PR with the mandatory template, exact
   versioned official references, contract/migration/security impact, and
   measured baseline/after/delta → 8. request CodeRabbit review when
   applicable with `@coderabbitai review`; verify it is complete and
   attached to the current PR head. For PRs above the per-review file
   limit, use only the repository's existing fail-closed review-shard
   and coverage-verifier process → 9. while CI/reviews are pending, do
   not idle: continue independent analysis or non-conflicting branches
   and monitor required results periodically. Pending is never success
   → 10. HOLD: merge only on the owner's explicit word and after every
   required gate passes → 11. on authorization, squash merge per policy
   → 12. verify post-merge deployment/smoke where the task requires it
   → 13. truth-sync the governing state documents with measured evidence.

## 6. House patterns (what "looks right" here — each measured)

- **Evidence or it didn't happen**: every claim carries `ملف:سطر` from
  this repo or a fetched official quote. Numbers in docs are guarded by
  derivation tests (`DocumentationNumbersGuardTest` guards README /
  SYSTEM / ARCHITECTURE counts — the #425 Track 1 discipline).
- **The declaration**: the §0.2 six-field block rides every mutating
  action and every PR body.
- **Root-cause fixes with fail-on-previous-code pins**: the #458
  standard — a fix adopted from a review note only after verification
  against code AND official semantics, carrying integration pins that
  demonstrably fail on the pre-fix code.
- **Transaction ownership discipline** (the #458 lesson): a retryable
  boundary (`@Retry`) joining a `REQUIRED` carrier transaction poisons
  it (rollback-only marking); the settlement owns its transaction
  (`REQUIRES_NEW`) so retries commit and compensating cleanup runs.
- **Boundary hygiene**: the HTTP boundary speaks DTO records only,
  entities stay internal (#427); transaction prohibitions +
  framework-neutral shared ports are build gates (#429); test fixture
  hygiene via the container factory + auth fixture (#428).
- **The shape-compat lesson** (CONTRIBUTING.md §4, measured 2026-09-27):
  `openapi-diff` compatibility is contract-level, not runtime-shape
  level — the `GET /notifications` bare-array → `PagedResponse` wrapper
  "compatible" change broke the frontend `/inbox` page. Any response
  SHAPE change (wrapper/pagination/form) = notify the frontend repo
  before merge + re-run its battery. Contract changes are
  forward-contract risk for `waelhe/web-marketplace`.
- **Honest telemetry**: the aggregate health verdict is watched (the S10
  postmortem: 503 DOWN in production while liveness/readiness were UP —
  a mail health contributor, discovered only by manual measurement;
  `docs/observability/postmortems/2026-09-26-s10-aggregate-health-blind.md`).
- **The smoke station + watchdog** close every merge wave (PROJECT_MAP
  records them per wave).

## 7. Environment survival kit

| Thing | The measured reality / fix |
|---|---|
| JDK | Temurin 25 (25.0.4.1 is the team's recorded build) — download per session; CI uses `setup-java` temurin 25 with maven cache (`ci.yml:62-67`) |
| Build | `./mvnw` (Maven distribution 3.10.0, wrapper tool 3.3.4); local full/targeted runs are supplementary feedback when available; GitHub Actions on the current PR head are the required remote verification record |
| No Docker locally | The standing protocol: integration tests judge on CI (`@Testcontainers(disabledWithoutDocker = true)`); the local run covers unit + test-compile |
| Deploy proof | Railway GraphQL tokens measured dead (403) → the **commit-status channel** is the documented alternative (PROJECT_MAP); python HTTP to Railway is blocked by Cloudflare 1010 → curl with a browser UA (frontend-measured 2026-09-28, applies here) |
| Secrets | Railway service variables only (45 app variables measured at migration); rotate; never in tracked files — gitleaks (high/critical policy, anchored allowlist) runs first in CI |
| Security disclosure | GitHub Private Vulnerability Reporting (measured enabled 2026-09-27) — never a public issue |
| Local conveniences | The global `~/.config/opencode/AGENTS.md` and the local `protocol-enforcer` skill are conveniences — the repo twin governs; rebuild them from this document |
| Resting state | Zero open PRs/issues between waves (the current 28 open retro-campaign PRs are an in-flight wave, measured 2026-09-28 with full pagination) |

## 8. The truth map (recovery map — all GitHub-durable)

| Artifact | Location | Role |
|---|---|---|
| The master method (6-stage, PR/review/release) | `docs/METHODOLOGY.md` | The stage engine — final authority for method doubts |
| Enforced rules + load order + declaration | `AGENTS.md` | Governance entry (frameworks auto-read it) |
| Mechanism reference | `SYSTEM.md` (§1-§15) | Every fact with `ملف:سطر` evidence; §14 governance; §15 live measurements |
| State ledger | `PROJECT_MAP.md` | Newest-first wave sections: words, gates, deploy telemetry, user-held items |
| Human entry | `CONTRIBUTING.md` | Channels, bug template with measured fingerprint, contract rules, secrets policy |
| Standards + testing | `docs/CODING_STANDARDS.md` (§13 testing), `docs/dev/testing-conventions.md` | Detailed rules |
| Domain governance | `docs/governance/*`, `docs/security/*`, `docs/api/*`, `docs/observability/*`, `docs/release/*` | Specialized extensions (per METHODOLOGY Appendix B hierarchy) |
| The gates | `.github/workflows/*` + `.ci/*` + `.gitleaks.toml` | CI as the durable public record |
| History + decisions | GitHub commit history + PRs (#N squash) + `docs/governance/decisions/*` | The permanent record |
| This protocol + the skill spec | `docs/agent-protocol.md` + `docs/agent-skill.md` | The zero-memory agent chain |
| The knowledge graph | `.ua/` | Rides PRs (generation snapshots) |

## 9. Regression list (the failures that return — read this first)

> **2026-10-27:** this list is now the quick view of the permanent **defect-knowledge system** (`docs/governance/defect-knowledge/`): the reconstructed evidence ledger (E-01…E-23), the causal pattern catalog (P1–P10), the rulebook (A/B/C/D rules), the mandatory change protocol, the independent review protocol, and the enforcement map. When any item below matches your change, load the corresponding pattern and rules before editing.

- Trusting training memory over the pinned-version official docs
  (Spring Boot 4.1.2 is not the Spring Boot you know; Modulith 2.1.2
  boundary semantics differ from memory).
- A retryable boundary joining a `REQUIRED` carrier transaction —
  rollback-only poisoning, dedup records without settlements (#458).
- Trusting contract-level OpenAPI compatibility while changing the
  response SHAPE — the wrapper/pagination lesson that broke the frontend
  inbox (CONTRIBUTING.md §4).
- The aggregate health verdict going unwatched while individual probes
  are green (S10: 503 DOWN in production, zero traffic impact, zero
  alerts).
- Doc numbers drifting from derived reality — guarded now by
  `DocumentationNumbersGuardTest`; new docs with load-bearing numbers
  should be guarded the same way.
- Boundary erosion: entities at the HTTP boundary, framework types in
  shared ports, cross-module direct dependencies instead of SPI/events
  (#427/#429; METHODOLOGY §16.2 forbidden).
- Editing an existing Flyway migration instead of writing a new `V{N}`
  (forbidden — breaks production DB history).
- Merging red, or merging without the three gates ("just this once" —
  the platform is `bypass=never`, but process bypass is a choice agents
  can make wrongly).
- Numbers in docs without a last-verified date + re-verification
  command (R1) — and stale duplicate numbers that survive beside the
  guarded canonical phrase (measured 2026-09-28: a retired module count
  persists in prose while the reactor moved on).
- Truth-sync debt: PROJECT_MAP/SYSTEM sections lagging the merged waves
  (the repo's own recorded gap pattern) — cross-check recent GitHub commit history + PRs
  before trusting a doc section's recency.
- Guessing when live verification is impossible (backend down, no
  Docker): stop and declare, never fabricate a measurement.
- Secrets in tracked files, or security reports in public issues.

## 10. The session entry prompt (the owner's clipboard)

The owner starts a backend session by pasting ONE fixed prompt. It is
anchored here — durable on GitHub — so neither the owner nor the agent
depends on any local copy surviving:

```text
تابع العمل على باك اند المنصة (app-java-v3). أنت وكيل الباك اند الدائم:
ابدأ بطقس الإقلاع في docs/agent-protocol.md بمستودع waelhe/app-java-v3
(استخدم GitHub مباشرة؛ أنشئ فرعًا بعيدًا من SHA الرأس الحالي وافتح PR؛ لا تجعل العمل المحلي شرطًا)، واتبع
AGENTS.md §0 كحوكمة إجبارية وdocs/METHODOLOGY.md كطريقة العمل الرئيسة.
استند إلى الوثائق والمستودعات الرسمية المطابقة للإصدارات لكل التقنيات،
واستخدم المهارات المثبتة ذات الصلة وفق سياسة المستودع، ولا تخترع عملاً
يدويًا غير موثق. لا تنتظر CI أو CodeRabbit بلا عمل؛ تابع المهام المستقلة
مع بقاء جميع بوابات التحقق والدمج إلزامية. ثم افحص PROJECT_MAP.md وPRs
والفروع المفتوحة وحدد المهمة التالية دون تعارض ثم تابع العمل. ردودك عليّ بالعربية دائمًا،
ولا دمج إلى main إلا بكلمة
صريحة مني — الدمج نفسه نشر إلى الإنتاج.
```

Gloss (owner-facing Arabic, quoted as data per §1): "Continue the
marketplace backend (app-java-v3). You are the permanent backend agent:
start with the bootstrap ritual in `docs/agent-protocol.md` of
`waelhe/app-java-v3`; work GitHub-first on a remote task branch and PR,
with local execution only as supplementary support. Follow `AGENTS.md`
§0 as enforced governance and `docs/METHODOLOGY.md` as the master method.
Use version-matched official documentation and source repositories across
all technologies, and activate relevant pinned skills under the repo's
skill policy. Do not invent undocumented manual workarounds. Do not idle
while CI/reviews run; continue independent work without bypassing gates.
Then inspect `PROJECT_MAP.md`, current PRs, and branches before selecting
non-conflicting work.
Replies to me always in Arabic; no merge to main except on my explicit
word — the merge itself deploys to production."

**Why a zero-memory agent needs nothing else.** The prompt's only local
assumption is GitHub credentials (ask the owner when missing). Everything
else is fetched from GitHub at session start: this protocol
(the complete method chain), `AGENTS.md` (auto-read by agent frameworks
— and re-read by §3.3), the root family (mechanism, state, standards),
the CI workflows, recent commits + PRs (the history). The local global
AGENTS.md, the local `protocol-enforcer` skill, and the session JDK are
conveniences, rebuilt from this document — never dependencies.

## 11. The bootstrap skill (derivable, for any agent system)

The complete copy-paste-able skill spec lives at
[`docs/agent-skill.md`](./agent-skill.md): install it into any agent
system's skills directory (opencode, Claude, or any framework with a
skills mechanism) and that system permanently carries the protocol.
**The sync rule**: the skill is a bootstrap map — this document governs;
on any disagreement the skill is patched to match the twin (the frontend
precedent, validated: structurally-valid frontmatter, description within
the 1024-char limit, English AI-facing format).

## 12. Permanent execution rules — all technologies and every task

### 12.1 Evidence before implementation

The source-of-truth rule applies to every technology, service, SDK,
database, protocol, build tool, deployment platform, and integration —
not only Spring or Maven. For each decision, use the official
documentation for the exact deployed/pinned version, the matching
official source repository, and applicable published standards.
Trusted community practice is secondary evidence only. Record the
source URL, version/commit, and exact relevant section, API, or source
path.

Do not invent unsupported code, configuration, operational steps,
manual workarounds, or integrations. Prefer the technology's documented
automatic configuration, lifecycle, conventions, generators, and
supported plugins where they cover the use case. Custom implementation
is permitted when it uses a documented official extension or
configuration point and is required by the product contract. Do not
remove a manual customization solely because it is manual; verify its
support status and purpose first. If no supported solution can be
established, report the gap instead of improvising.

### 12.2 Skills are task-scoped and pinned

Before work, inspect `docs/governance/agent-skills-policy.md` and
`docs/governance/plans/agent-skills-pins.json`, then read the relevant
vendored skill and its referenced rules. Skills are reference inputs,
not authority over product decisions, canonical plans, pinned schemas,
official documentation, or measured code/tests. Never install or
refresh a skill outside the repository's reviewed pin-and-registry
procedure.

For Saleor work, use
[Saleor's AI developer guidance](https://docs.saleor.io/developer/ai):
start with `llms.txt`, use the Documentation MCP for read-only
documentation search when available, and use GraphQL MCP only where
schema access is needed. The GraphQL schema must match the repository's
pinned Saleor release. Apply the already-adopted `saleor-core` skill
where its discount, order-allocation, or stock rules apply. The other
Saleor skills are not automatically applicable: adopt them only when
the task requires them and after the repository's pinning policy is
satisfied. The canonical Saleor plan, source pins, divergence ledger,
and application code remain controlling.

### 12.3 GitHub-first execution and non-blocking parallelism

GitHub is the durable execution surface. Create a branch from the
observed base SHA, apply changes to that branch, and use PRs and
GitHub Actions as the review and verification record. A local clone or
local test run may supplement this flow, but it is not the primary
source of truth and must not be represented as a remote check.

Do not remain idle while CI, CodeRabbit, or another independent review
is running. Continue a genuinely independent task or a non-conflicting
branch, inspect results periodically, and return to any failed check
when its result is available. Parallel work must not create overlapping
writes, stale reviews, or false claims. A pending check is never a
passing check.

### 12.4 Review and merge integrity

For applicable human-authored PRs, request CodeRabbit review using
`@coderabbitai review` and verify a completed review against the
current PR head. Respect the repository's CodeRabbit gate and its
fail-closed review-coverage process for PRs exceeding the review file
limit. Do not create substitute review shards, bypass the gate, or
assume a review happened from a status label alone.

Implementation and review can proceed on the branch without waiting
for the owner, but merge to `main` remains subject to the owner's
explicit authorization and every required repository gate. Never
weaken branch protection, skip CI, or merge a pending/red result.

### 12.5 Honest completion report

Report the actual branch, PR, commit, source versions, changed paths,
tests, remote workflow results, completed reviews, unresolved threads,
and outstanding blockers. Distinguish completed, pending, failed, and
not run. Never claim deployment, review, or test success without
observable evidence.

---

*The backend team has operated under the root-family governance since
the repo's origin; this protocol wraps it for zero-memory agents since
2026-09-29. Amending it = branch + PR through the normal cycle (a docs
change — no code gates beyond CI, conceptual review mandatory).*
