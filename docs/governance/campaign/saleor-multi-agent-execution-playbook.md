# Multi-Agent Execution Playbook — Full Saleor Dashboard + Commerce Contract

**Purpose:** Complete the implementation campaign in one continuous build phase. Implementation agents must not stop after each slice to wait for PR review, approval, or merge. Integration happens continuously on a dedicated candidate branch; formal review and the production merge happen only after the whole candidate is built and assembled.

**This is an execution protocol, not a replacement plan.** The sole governing plan remains `docs/governance/plans/saleor-dashboard-full-commerce-canonical-plan.md`. Older Saleor implementation plans remain historical evidence only.

## 1. Starting point and non-negotiable decisions

At the last verified repository snapshot on 2026-10-21:

- Backend `waelhe/app-java-v3` `main`: `ea0031a97db4452748a47582d3fed2c4d3ec1d52` (merged Users read slice PR #694).
- Admin `waelhe/marketplace-admin` `main`: `982ecf3667d2242cab941737cb6a0bceaee7ffcb` (weekly contract-drift guard PR #2 merged).
- Spring Boot capability audit PR #692 is merged at `b122c90b…`.
- Typed global-ID codec PR #693 is merged at `f3a31788…`.
- Users read roots `me`, `user`, and `staffUsers` PR #694 is merged at `ea0031a…`.
- Saleor reference schema: Saleor `3.23.43`, `saleor/graphql/schema.graphql`, Git blob SHA-1 `b8fa0dd809081f76dc2834bb5c286561e5783276`, containing 90 Query + 342 Mutation + 26 Subscription roots = 458 roots.
- Dashboard base: `saleor/saleor-dashboard` `3.23.39`, commit `bc75e988da0fa4fbc3d47378a888d859d070746f`.

**Re-fetch and freeze the actual `main` SHA and source pins at campaign start.** The SHAs above are a reference snapshot, not permission to ignore subsequent commits.

Fixed decisions:

1. Modular monolith; Spring Boot owns runtime and Maven owns builds.
2. Netflix DGS is the GraphQL resolver/code-generation model, per the canonical plan.
3. Reuse the existing platform identity, catalog, pricing, order, payment, delivery, media and other owning domains through their declared SPIs. Do not create duplicate authorities.
4. Do not deploy or run Saleor backend, Saleor database, or a second commerce engine.
5. Keep native platform identifiers/storage authoritative behind the Saleor-compatible wire contract.
6. Preserve legacy platform roots as separately inventoried extensions; never count their names as Saleor compatibility.
7. No placeholders, fake records, silent no-op resolvers, or “success-shaped” responses for unsupported work. Each operation must be implemented honestly or carry its explicit approved disposition/ADR.
8. A merge into `main` triggers production deployment in this repository. No implementation agent may merge to `main`, release, deploy, or change deployment protections. Final merge is an explicit release-owner action after all gates.

## 2. The operating model: one build campaign, a separate release gate

Use four phases. Do not turn each implementation slice into a stop-and-review cycle.

### Phase A — Freeze the shared contract and create the candidate lane

The Integration Lead must:

1. Read `AGENTS.md`, `SYSTEM.md`, `PROJECT_MAP.md`, `docs/agent-protocol.md`, `docs/agent-skill.md`, the governing Saleor plan, the domain matrix, the divergence registry and source pins. Resolve their current paths from the actual `main`; do not act on snippets alone.
2. Capture the base SHAs of both repositories, the Saleor schema blob, the Dashboard commit, current migration head, current generated-contract inventory and all current open PRs/CI status.
3. Create a single backend candidate branch from the frozen backend `main`, e.g. `integration/saleor-full-commerce-20261021`. Create a corresponding Dashboard candidate branch only if Dashboard code changes are required.
4. Publish a contract pack before assigning parallel implementation. It must contain: 458-root inventory (one row per exact root), per-root owner, root signature, type dependencies, source category, intended disposition, owning SPI/use case, expected permission/scope, test owner, and current status. Root counts alone are not completion evidence.
5. Freeze shared API decisions: SDL ownership and operation signatures, module/SPI names, global-ID rules, error taxonomy, pagination and cursor formats, decimal/money rules, idempotency rules, event/outbox rules, authorization requirements, and migration ownership. Changes after freeze go through the Integration Lead as explicit contract revisions.

Do not begin bulk domain coding before this contract pack exists. This is the only up-front synchronization gate; after it is frozen, domain work proceeds without waiting for review of each slice.

### Phase B — Parallel implementation in isolated workspaces

Each agent gets a separate branch/worktree and a bounded file/operation ownership list. Agents implement, test and commit their slice without opening PRs or waiting for external review. They deliver commits and evidence to the Integration Lead; they do not merge to `main`.

All agent branches start from the same frozen candidate baseline. They must not use one shared writable branch concurrently. This avoids lost updates and nondeterministic commits. Every agent declares its exact branch base SHA and rechecks it before handoff.

Agents may work against the frozen shared interfaces and use mocks at domain seams for their isolated tests. If an interface is missing, submit a contract-change request to the Integration Lead; do not independently redefine a public SPI, GraphQL type, auth rule or database owner.

### Phase C — Continuous assembly on the candidate branch

The Integration Lead integrates completed agent commits into the candidate branch continuously, in dependency order. This is **assembly**, not formal review or production merge. Resolve integration errors immediately and return a precise fix request to the owning agent, or make the smallest integration-only fix with a recorded rationale.

- Keep one coherent commit sequence per workstream/wave; preserve agent attribution and avoid squash/cherry-pick ambiguity.
- Integrate cross-cutting contracts and their owners first, then P1, P2/P3, P4, P5 and P6 in dependency order. Work may be developed in parallel where interfaces are frozen; assembly remains topological.
- Run fast targeted checks as code lands. Do not wait for reviewers or require a complete CI cycle after every isolated component.
- Run focused integration checks at each wave boundary, and the full release-candidate suite after all work is assembled.
- If a shared API changes, the Integration Lead broadcasts one versioned contract revision and identifies every affected agent. Only affected branches are rebased/reconciled; unrelated agents continue.

### Phase D — Independent review, remediation and one release merge

Formal review starts only after the Implementation Complete gate in §8 passes. Reviewers work in parallel on the same frozen candidate SHA, but they do not edit it directly. Findings are severity-ranked and returned as one consolidated list. A dedicated Fix/Integration owner addresses findings on the candidate branch; relevant checks are rerun; reviewers verify the corrected candidate SHA.

Open **one final release PR** from the fully assembled candidate branch to `main`, with a review index linking commits, domains, exact root inventory rows and evidence. Do not merge wave PRs separately: the repository deploys on `main` merge, so a chain of partial merges could publish an incomplete commerce surface. Do not enable auto-merge. The release owner explicitly authorizes the one final merge only after all release gates are green.

## 3. Agent topology and strict ownership

Use one Integration Lead (orchestrator/contract steward), domain implementers, and independent post-build reviewers. Do not let the same agent be the only implementer and approver of a security-sensitive slice.

| Workstream | Inventory boundary | Primary responsibility | File/area ownership rule |
|---|---|---|---|
| A0 — Integration Lead / Contract Steward | Cross-cutting; all 458 inventory rows | Freeze contracts, assign ownership, maintain candidate branch, assemble changes, manage release evidence | Sole owner of shared SDL aggregation, generated-contract inventory, `pom.xml` cross-module changes, Modulith allow-list, governance matrix/divergence registry/source pins, CI workflow changes, migration-number allocation and `SYSTEM.md`/`PROJECT_MAP.md` updates. Domain agents submit deltas; they do not race-edit these files. |
| A1 — P1 Identity, Users, Auth, Permissions | P1: 62 roots total (Users 53 + Authentication 9) | Complete user/customer/staff/permission/auth routes required by the pinned Dashboard; existing identity owner; OIDC/PKCE; server-side RBAC | Identity and auth-owned source files only. The already-merged `me`, `user`, `staffUsers` roots are partial progress, not evidence that all 62 roots or the Dashboard login/session gate are complete. Check the exact root inventory before selecting tasks. |
| A2 — P2 Catalog, Product Types, Attributes, Variants, Media | P2: 95 roots (Products 78 + Attributes 17) | Saleor wire types and operations delegated to existing catalog/media owners; persisted mutations and owner-scope checks | Catalog/media-owned Java/SPIs/tests; submit SDL additions and migration requests to A0. Never rewrite product engine or edit existing migrations. |
| A3 — P3 Commercial Context, Pricing, Promotions, Tax, Gift Cards, Shop | P3: 85 roots (Discounts 33 + Gift cards 21 + Taxes 14 + Channels 8 + Shop 9) | Exact-money and pricing delegation; genuine channel model/decision; gift-card liability owner; configuration-backed settings | Pricing/config and assigned new-domain files only. Channel and gift-card ownership gaps require explicit ADR/owner decision before fake implementations are even considered. |
| A4 — P4 Checkout, Orders, Payments, Shipping | P4: 111 roots (Orders 52 + Checkout 23 + Payments 21 + Shipping 15) | Existing cart/order/payment/ledger/delivery use cases; frozen terms; replay/idempotency; multi-seller splits and refunds | Assigned order/payment/delivery source files only; shared transaction semantics and migration numbering remain steward-controlled. |
| A5 — P5 Dashboard management surfaces | P5: 79 roots (Apps 21 + Pages 18 + Menu 15 + Webhooks 9 + Uncategorized 16) | Real platform-owned extension/page/menu/webhook/job/i18n/configuration operations and explicit exceptions for federation | Assigned surface modules only. Any missing owner must be resolved by ADR; no fake App/Plugin/remote records and no unreviewed federation metadata. |
| A6 — P6 Subscriptions | P6: 26 subscription roots | Domain-owned real events, secured transport, ordering/reconnect/duplicate semantics | Subscription adapters/tests and publisher integrations through declared events/outbox. Do not invent events or bypass owning modules. Coordinate event names and payloads with A0 and the owning domain agents. |
| A7 — Dashboard/session integration | P1/P7 cross-repo gate | Wire the pinned Dashboard to platform OIDC/PKCE/session behavior and the contract actually provided by the candidate; test core routes | Own only `marketplace-admin` integration/config/test areas assigned at kickoff. Coordinate exact operation dependencies with A1–A5; do not modify backend SDL directly. |

**Recommended concurrency:** one owner per workstream above. Split a workstream into multiple agents only when the exact roots and files are disjoint and the shared contract is already frozen. Avoid “generalist agents” editing the same SDL, SPI, migration directory or governance file.

## 4. Cross-cutting collision-prevention rules

These rules are mandatory for every agent:

1. **Exact ownership list:** the work packet names exact root names, mutations/queries/subscriptions, Java packages, test files and allowed files. “Work on products” is not a sufficient boundary.
2. **One SDL steward:** because the current app has a shared runtime SDL, domain agents must not concurrently edit `marketplace-app/src/main/resources/graphql/schema.graphqls`. Submit a schema fragment/patch proposal to A0. A0 integrates the SDL in one ordered change and runs DGS code generation. Do not hand-edit generated DGS classes.
3. **One migration allocator:** never invent the next Flyway version independently. A0 allocates unique versions from the current repository head. Each agent authors its assigned new migration only, and existing migrations are immutable.
4. **One owner per public SPI:** the owning-domain agent owns the implementation and its SPI changes; consumers request changes. No duplicate repositories, alternate identity stores, second pricing/order/payment engines, or direct cross-module entity access.
5. **No shared-file races:** `pom.xml`, Modulith boundary definitions, contract-publication scripts, OpenAPI inventory, root counts, CI workflows, `SYSTEM.md`, `PROJECT_MAP.md`, source pins and governance CSVs are steward-owned. Provide proposed diffs/data, not parallel edits.
6. **No uncoordinated renames:** package, root, enum, error code, cursor payload or event-payload changes require an explicit contract revision and a list of affected workstreams.
7. **No force pushes or hidden history rewrites** after handoff. Use commits with descriptive messages and preserve the exact candidate base. The Integration Lead uses explicit cherry-pick/merge operations and records the included commit SHAs.
8. **Conflict protocol:** stop only the conflicting file/contract change, not unrelated coding. Report path, conflicting SHAs, intended semantics and a minimal resolution proposal to A0. A0 resolves the shared file once and publishes the new candidate SHA.
9. **No premature “done”:** a root is not complete merely because an SDL field or resolver name exists. It needs real owner delegation, input validation, authorization/scope behavior, persistence/read-back where applicable, classified errors, and its required tests.
10. **Debt transparency:** every deferred field/root/route/ADR is logged with exact owner, Dashboard impact, safe behavior and release impact in the divergence registry. A release-blocking item cannot be waved through as “future work.”

## 5. Mandatory agent work packet and completion report

The Integration Lead gives every agent one message containing all of the following:

- Workstream ID and exact root inventory rows/names included.
- In-scope and out-of-scope operations; expected disposition for each root.
- Owning module and allowed file paths; shared files that must not be changed.
- Frozen candidate/base SHA and the relevant SPI/SDL/error/pagination/security contracts.
- Required unit, DGS/runtime SDL, persistence/integration, authorization and Dashboard/E2E tests.
- Explicit contract gaps/ADRs already known for that slice.
- Required commit format and branch name.

Agent branch name: `agent/saleor-<wave>-<domain>-<short-topic>`. Keep commits workstream-local; do not create a PR, merge, deploy, wait for review or change another agent's branch.

An agent returns this completion format:

```text
WORKSTREAM: A<n> / P<n>
BASE SHA:
HEAD SHA:
ROOTS CLAIMED:
ROOTS IMPLEMENTED + EVIDENCE:
ROOTS ADAPTED/EXCEPTED + REGISTRY ROW:
FILES CHANGED:
SPI/CONTRACT CHANGES REQUESTED:
DATABASE MIGRATIONS ADDED (allocator-assigned versions):
TESTS RUN (exact commands and observed result):
TESTS NOT RUN + REASON:
SECURITY/AUTHZ/OWNERSHIP NEGATIVES:
KNOWN GAPS / BLOCKERS:
COMMITS TO INTEGRATE:
```

“Tests pass” without the command and observed result is not evidence. If a test cannot run locally, state why; do not claim success based on an assumed CI outcome.

## 6. Required implementation sequence and parallelism

Work can be coded in parallel once the contract pack is frozen, but integrate in this order:

1. **P0 / foundation:** Verify source pins and drift guards. This is substantially present already; audit rather than recreate it.
2. **P1:** Identity/global IDs, user/customer/staff and permission surfaces, OIDC/PKCE and session integration. Existing `me`/`user`/`staffUsers` code is a starting point; inventory the remaining roots and type closure before marking P1 complete.
3. **P2 + P3:** These can be implemented in parallel after P1-compatible identity/permission and global-ID contracts freeze. P2 owns catalog semantics; P3 owns monetary/pricing/channel/configuration semantics. Do not invent channel semantics: an explicit owner/model decision is needed.
4. **P4:** Checkout/order/payment/shipping may be coded against frozen P2/P3 contracts, but integrate after their required type/SPI interfaces exist. Assert no duplicate order state machine and no financial side effects without idempotency and ledger evidence.
5. **P5:** Management surfaces and remaining operations. Can be coded in parallel against the contract pack, but any missing owner or extension registry needs its own accepted ADR.
6. **P6:** Subscription transport and domain event publishers. Payloads must match actual owning-domain events. Integrate after publisher and authorization semantics are confirmed.
7. **Dashboard and P7:** Verify the real pinned Dashboard's actual operations/routes against the candidate, the real OIDC/PKCE/login/session path, protected page access, refresh/expiry behavior and the 458-root inventory.

Parallel coding does not mean relaxing dependency gates. Agents may implement against frozen interfaces before providers land; A0 integrates providers before consumers in the candidate branch.

## 7. Test strategy — continuous feedback without per-slice review stalls

### During coding

- Each agent runs formatting/compile and targeted unit tests for their own slice.
- GraphQL changes require DGS codegen plus real-SDL execution tests; do not rely on text search or schema signature checks alone.
- Security work requires allow and deny paths: anonymous, wrong role, cross-provider/owner, guessed ID, malformed/mistyped global ID and unauthorized field/operation access as applicable.
- Persistence work requires Flyway-migrated real PostgreSQL integration tests for constraints, transactions, idempotency, concurrency and read-back where applicable. Never substitute `ddl-auto` schema for the real migrated schema when migration behavior matters.
- Dashboard operations require route-level journeys, not only backend tests.
- Run short targeted checks continuously, but do not block other agents on a full suite or external PR review.

### At wave assembly boundaries

Run the narrow cross-module and GraphQL checks that catch interface and SDL drift. Fix issues on the candidate lane while the agents continue unrelated bounded work.

### At final candidate freeze

On one frozen candidate SHA, run the repository-mandated full build and checks, including as applicable:

- `mvn clean verify` at the repository/root level and the documented `mvn clean verify -pl <module>` checks for affected modules.
- Complete unit and integration test suites using the required test profile and real Flyway migrations/Testcontainers rules.
- DGS code generation, runtime SDL validation, root signature inventory, schema compatibility and generated contract publication.
- REST/OpenAPI compatibility, Modulith/module-boundary checks, migration checks and coverage gates.
- Pinned Dashboard build and browser/E2E journeys for critical retained routes.
- Anonymous rejection, OIDC/PKCE/session expiry, CSRF/CORS as relevant, role matrix and cross-tenant/provider scope denial.
- Idempotency/replay/concurrency/ledger checks for financial operations; event authorization/reconnect/replay tests for subscriptions.
- CI, integration, Android/consumer compatibility, CodeQL, container scan/Trivy, Snyk and governance/drift gates required by the repository.

Capture exact URLs, run IDs, conclusions, test totals and candidate SHA. Do not claim a gate is green if its run is pending, absent, or for another SHA. A Production Watchdog failure is a deployment/health signal and must be triaged separately from PR unit-test success; do not ignore it or mislabel it as a code-test failure.

## 8. Implementation Complete gate — required before formal review opens

The build phase is complete only when all items below have evidence:

- Every one of the 458 exact Saleor roots has a disposition and named owner; every Dashboard-used root has an implemented or explicitly disabled/removed route with a documented reason.
- Contract-generation and schema-inventory automation show exact pinned-source identity and explain every intentional difference. Name parity alone is not semantic parity.
- Each implemented operation has argument/return/nullability compatibility, owner-backed behavior, validation, authorization/data-scope behavior, and a test appropriate to its side effects.
- SDL/type closure, global IDs, cursor semantics, error mapping, enums and money/scalar semantics are coherent across workstreams.
- No fake/no-op roots, unresolved conflicting owner decisions, undocumented migration collisions, raw UUID leakage where global IDs are required, or direct cross-module persistence access remain.
- Divergence registry rows are updated from actual evidence. All rows still marked `IMPLEMENTATION_REQUIRED` or `OWNER_ADR_REQUIRED` have either been closed with proof or are explicitly release-blocking and excluded from the release scope through a recorded decision. P7 does not pass with unresolved blocking differences.
- Pinned Dashboard login/session and critical retained routes pass against the integrated candidate. If the chosen release scope truly cannot complete a route, remove/disable it honestly and document the exact disposition; do not pretend full-dashboard readiness.
- Candidate source SHA is frozen and all required test/security/contract gates are green against that SHA.

## 9. Separate review phase and merge protocol

After §8 passes, assign independent reviewers. Reviewers do not need to re-implement; they verify evidence and challenge assumptions.

- **R1 — Contract reviewer:** Compare pinned SDL and Dashboard operations with generated root inventory; inspect signatures, nullability, enums, scalar semantics, connection/cursor behavior and all divergence dispositions.
- **R2 — Security reviewer:** Review authentication/session, permission mapping, service-level authorization, IDOR/guessed IDs, provider/tenant scope, sensitive fields, rate limiting and denial/error leakage.
- **R3 — Domain/data reviewer:** Review owner/SPIs, transactions, migrations, money, inventory/order/payment invariants, idempotency, replay, audit/outbox/events and real database tests.
- **R4 — Release reviewer:** Review module boundaries, build reproducibility, Dashboard critical journeys, CI/security scans, rollback/recovery, monitoring and deployment readiness.

All reviewers must cite file/line or test evidence and classify findings as blocker / high / normal / informational. R1–R4 send findings to one triage list; they must not make independent edits to the candidate. The Fix/Integration owner resolves findings, updates tests/evidence and produces a new candidate SHA. Only impacted reviewers recheck the changed areas; all final gates are rerun for the final candidate SHA.

Then open one release PR from the complete candidate branch to `main`. Keep it unmerged until:

1. No blocker/high findings remain; normal findings are fixed or accepted in writing with an owner and date.
2. Required CI and external security checks are conclusively green on the exact PR head.
3. The final root inventory and divergence registry match the candidate contract.
4. Production impact, migration ordering, rollback/recovery and monitoring are explicitly reviewed.
5. The owner explicitly authorizes release.

No agent merges, auto-merges, deploys or releases. Because `main` merge deploys production, avoid partial PR merges that could expose incomplete waves. After merge, record the exact main/merge SHA, deployment result and post-deploy smoke tests before treating the release as shipped.

## 10. Non-blocking decision protocol

A dependency question must not suspend the entire implementation campaign. The affected agent submits a short request:

```text
DECISION ID:
AFFECTED ROOTS / WORKSTREAMS:
OPTIONS (at least 2 where possible):
CONTRACT / SECURITY / DATA CONSEQUENCE:
RECOMMENDATION:
WHAT CAN CONTINUE WITHOUT THE DECISION:
RELEASE GATE AFFECTED:
```

A0 resolves it in the contract ledger. Unaffected agents continue. If an owner/ADR decision is necessary to avoid inventing a business capability, mark that exact dependency as blocked while continuing unrelated work; do not implement a fake fallback to keep green builds.

## 11. Copyable top-level instruction to launch the agent team

> You are participating in one implementation campaign for the canonical Saleor Dashboard/full-commerce contract. Read the repository's mandatory agent protocol and the sole governing plan before touching code. Freeze the live base SHA and source pins. Do not create implementation PRs, wait for external review, merge to `main`, deploy, or change release protections. Implement the exact assigned roots in your isolated worktree, against the frozen contract pack; run targeted tests and commit your work. Do not edit shared SDL, generated files, migration version allocation, POM/Modulith boundaries, CI, source pins or governance registries unless your work packet explicitly assigns the file. Submit proposed shared-file deltas to the Integration Lead. Report exact SHAs, changed files, roots implemented, tests/commands/results, remaining gaps and commits to integrate. Never claim a root is complete by name/signature alone. Continue unrelated work if another workstream is blocked. The Integration Lead alone assembles the candidate in dependency order. Formal independent review starts only after the whole implementation candidate passes the Implementation Complete gate. The release owner alone authorizes the single final merge to `main` after all gates; that merge triggers production deployment.

## 12. Owner dashboard for the campaign

Maintain one shared campaign ledger with these columns:

`root_name | root_kind | source_category | wave | dashboard_operation(s) | disposition | owner_agent | owning_module/SPI | permission/scope | status | unit_test | integration_test | dashboard_E2E | divergence_id | candidate_commit | evidence_url`

Allowed statuses: `NOT_STARTED`, `IN_PROGRESS`, `INTEGRATED`, `TESTED`, `REVIEW_FINDING`, `FIXED_RETEST_PENDING`, `RELEASE_READY`, `BLOCKED_BY_DECISION`, `EXPLICITLY_EXCLUDED`. A root reaches `RELEASE_READY` only when its tests and owner/scope behavior have evidence. Track the overall state from the ledger, not optimistic prose.

**Campaign success is not “all agents committed.” It is one integrated, tested, independently reviewed release candidate with auditable evidence and no unresolved blocking compatibility/security decisions.**
