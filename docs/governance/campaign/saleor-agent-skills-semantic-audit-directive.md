# Saleor Agent Skills & Semantic Audit Directive
**Status:** Active campaign supplement  
**Applies to:** integration/saleor-full-commerce-20261021 and its approved candidate descendants  
**Governing decision:** D-SALEOR-FULL-001  
**Governing plan:** docs/governance/plans/saleor-dashboard-full-commerce-canonical-plan.md  
**Execution protocol:** docs/governance/campaign/saleor-multi-agent-execution-playbook.md  
**Role and root assignments:** docs/governance/campaign/A0-EXECUTION-DIRECTIVE-20261028.md

> This directive supplements the existing execution plan and playbook. It does not replace the canonical plan, change A0–A7 ownership, create a second root inventory, or authorize a new Saleor runtime. If this document conflicts with the canonical plan, frozen contracts, approved divergence records, or current measured repository state, stop the conflicting action and ask A0 to reconcile the evidence.

## 1. Mission

Use the official saleor/agent-skills repository as specialized domain knowledge to:

1. Audit previously INTEGRATED Saleor roots for real contract, behavioral, authorization, persistence, transaction, event, and Dashboard correctness.
2. Find and repair defects that name/signature checks or ordinary CRUD tests cannot detect.
3. Convert verified Saleor behavior into repeatable JUnit, integration, security, and Dashboard E2E tests.
4. Continue implementing unblocked roots under the existing A1–A7 workstream ownership.
5. Make the audit evidence durable and prevent regressions through the campaign ledger and CI.

The goal is **verified Saleor-compatible commerce behavior implemented by the existing Spring platform**, not 458 resolver methods or a copied Saleor backend.

## 2. Non-negotiable authority and architecture

The authority order for intended behavior is:

1. docs/governance/plans/saleor-source-pins.json — immutable Saleor schema and Dashboard source pins.
2. docs/governance/plans/saleor-dashboard-full-commerce-canonical-plan.md — sole execution and product-scope plan.
3. docs/governance/campaign/frozen-contracts.md — accepted shared implementation contracts.
4. docs/governance/plans/saleor-contract-divergences.csv — approved wire-level exceptions and their decision records.
5. Official source and tests for the pinned upstream versions — evidence used to establish expected behavior and validate claims.
6. Current platform code and tests — evidence of what the platform actually does, not permission to silently redefine the intended contract.

A vendored skill is a **reference, never an authority**. It cannot override the pinned SDL, canonical plan, frozen contracts, approved divergence decisions, or measured platform ownership.

Keep the existing architecture:

- Java 25, Spring Boot, Spring Modulith, Maven, PostgreSQL, and Netflix DGS.
- Existing domain modules and their declared SPIs own their data and business rules.
- No Saleor Backend, Saleor database, second commerce engine, duplicate source of truth, fake/no-op resolver, fabricated record, or success-shaped unsupported response.
- Saleor-compatible public GraphQL is a boundary contract; internal models remain platform-native.
- Do not edit the canonical plan, source pins, shared SDL, generated DGS code, Flyway numbering, cross-module POMs, Modulith policy, CI workflows, central ledger, or divergence registry unless A0 explicitly assigns the shared-file change.

## 3. Live baseline — re-established 2026-10-31 (Phase A of the promotion plan)

**Current measured baseline (2026-10-31T03:37Z, post-reconciliation):**

- Commerce branch: `integration/saleor-full-commerce-20261021`, HEAD **`6608a869b99e137130346879f54f82791abc5a30`** — this is the published **`CANDIDATE_BASE_SHA`**.
- `main` HEAD at reconciliation: `f2349deca955cee435e6be3ca56be8a4c251d00a` (the PR #771 merge).
- Pre-reconciliation freeze (measured 2026-10-31): commerce **130 commits ahead, 15 commits behind**, merge base `f2702ff4617825d01880cd19fd7a5f66918cbf20`; 592 commerce-side changed files, 200 main-side, 179 both-sides — 171 byte-identical, 6 auto-merged, 4 conflicts resolved in the merge commit (measured union/ownership decisions recorded in its message).
- PR #771 **is merged into main** (`f2349dec`); its full payload — `docs/governance/agent-skills-policy.md`, `docs/governance/plans/agent-skills-pins.json`, vendored `saleor-core` + 9 neon skills with LICENSE attribution, and the fail-closed `PlatformGovernanceFilesTest` — **is now present on the commerce branch** via the reconciliation merge; all 49 pinned files sha256-verified against the registry in the merged tree.
- Root inventory blob at `CANDIDATE_BASE_SHA`: `ec689c643b9c906779e09f3fdaee73885052282a` — 458 roots: 163 INTEGRATED, 238 NOT_STARTED, 57 BLOCKED_BY_DECISION (re-measured 2026-10-31; unchanged).
- Open PRs at re-establishment: **none** (the review-only draft superseded by the promotion plan is closed; the final release PR is created fresh per plan §7 Phase C).
- Migration head on the candidate: `V295` (214 versioned migrations, measured in the merged tree; matches the `DocumentationNumbersGuardTest` derivations together with 188 IT test files).

**Historical snapshot (2026-10-30, superseded by the above — kept as evidence, not current):**

- Branch HEAD at that measurement: `c58e2e9b3b8f0369bc2885544c6e01df3209f237`; main at `28d9947960364565bef4f9fe72eab750c6db4e8f`; compare: diverged, 124 ahead / 14 behind; PR #771 still open (head `58d88c50f3752fcbdc58d514791fdf80d5573fb8`); the skill/registry files not yet present on the commerce branch. Root inventory blob `ec689c64…` and the 163/238/57 split were already as measured above.

Action items 1–5 below are **completed by the reconciliation merge `6608a869`** (facts frozen, divergence resolved file-by-file with recorded decisions, PR #771 payload brought in whole, vendored files verified). Workspaces may now open implementation work against the published `CANDIDATE_BASE_SHA` under the playbook's isolated-branch and shared-contract rules. A0 still re-checks live heads before any candidate assembly action, because `main` may move again.

The original Phase A action items and their completion record (2026-10-30 directive → 2026-10-31 execution):

1. Re-read the live commerce and main heads, all relevant open PRs, check runs, unresolved review threads, and the root inventory blob — **done** (freeze in the baseline above; zero open PRs, zero unresolved threads at the merge).
2. Resolve the 15-commit divergence safely, file-by-file, never blindly — **done**: merge `6608a869`, 4 conflicts resolved with recorded measured decisions (SYSTEM.md merged-tree numbers; findings-doc union; main's approved #766 formatting; UserServiceTest union).
3. Bring the merged PR #771 payload in whole through the normal main-to-commerce reconciliation — **done**: present and 49/49 hash-verified (§3 current baseline).
4. Read-only upstream research at the exact pinned commit remains the rule until a reviewed provenance migration (promotion plan §3) replaces the pin; never run an installer or copy an unpinned head.
5. Implementation work branches now open against the published `CANDIDATE_BASE_SHA` (`6608a869b99e137130346879f54f82791abc5a30`), under the existing playbook's isolated-branch and shared-contract rules.

## 4. Skill source pins and use

The adopted Saleor reference for this campaign is:

- Repository: saleor/agent-skills
- Pinned commit: fb1a57255f58950962f6e474ccba1cade40f5163
- Skill: skills/saleor-core/
- Official pinned source: https://github.com/saleor/agent-skills/tree/fb1a57255f58950962f6e474ccba1cade40f5163/skills/saleor-core

Use the skill selectively:

| Reference | Domain knowledge | Existing workstream that consumes it |
|---|---|---|
| saleor-core/rules/discount-precedence.md | Manual/voucher/promotion precedence, suppression, removal and reevaluation | A3 / P3 |
| saleor-core/rules/order-discount-allocation.md | Order-level discount calculation, proportional line allocation, rounding and API exposure limits | A3 / P3 and A4 / P4 at their ownership boundary |
| saleor-core/rules/stock-availability-modes.md | Legacy/direct availability modes, warehouses, channel linkage, shipping-zone effects, click-and-collect and event conditions | A2 / P2 and A4 / P4 at their ownership boundary |

The skill is not a complete Saleor implementation guide. For taxes, channels, gift cards, payment/transaction APIs, shipping, checkout, orders, apps, webhooks, subscriptions, and other unmentioned areas, inspect the pinned Saleor source, tests, schema, and pinned Dashboard directly.

Other skills are conditional references only:

- saleor-app: only for a real task involving Saleor app protocol, app permissions, appbridge, or webhook protocol semantics. It does not imply that this platform should create a Saleor app registry.
- saleor-storefront: only for a real storefront consumer. It is not the authority for the administration Dashboard.
- saleor-configurator: not applicable to provisioning a second Saleor shop or backend; the platform does not run one.
- storefront-builder: do not use its Saleor-connected storefront bootstrap as an instruction to rebuild the platform Dashboard.

Never run npx skills add or another skill installer against this repository. A skill update is allowed only through a reviewed PR that updates vendored files, source commit, license/attribution metadata, and per-file integrity records together.

### 4.1 Verify every skill claim against upstream

For each rule consumed by an implementation or test:

1. Locate the exact source file and function in Saleor 3.23.43.
2. Locate the upstream unit/integration tests that establish the behavior.
3. Inspect the relevant Dashboard 3.23.39 usage when UI behavior is involved.
4. Record the pinned source URL, commit, path, symbol, and relevant lines/test.
5. Check knownUpstreamErrata in the skill registry when available.
6. If skill prose conflicts with source or tests, treat it as a documented upstream erratum. Do not patch the vendored copy; report the evidence to A0 for the registry/policy process.
7. Never silently convert a discovered upstream implementation detail into a platform-specific requirement. A0 must distinguish wire compatibility, semantic compatibility, and an approved platform adaptation.

Known issues already identified in this skill include an uninitialized variable in a Python snippet, an incorrect source-file path for StockQuerySet, currency-precision-blind rounding language, and nuanced stock/click-and-collect and subscription-delivery claims. Re-verify each against the pinned source before using it; do not treat this list as exhaustive.

The saleor-core frontmatter declares MIT while the upstream repository root states BSD-3-Clause AND CC-BY-4.0. Preserve the observed license statements and attribution records; do not invent a legal reconciliation or edit upstream content locally. A0 must keep the legal status explicit and request owner/legal resolution if distribution or modification depends on it.

## 5. Semantic audit of previously integrated roots

The audit is mandatory for every root currently marked INTEGRATED, not only those directly covered by saleor-core.

At the measured baseline there are 163 such roots. Their assigned domain agent must audit them while continuing its own implementation work; this is not a separate campaign that blocks all coding until every audit finishes.

Use the current central ledger as the only root inventory:

docs/governance/campaign/saleor-458-root-inventory.csv

Do not create a second 458-row inventory or change the ledger's status vocabulary. A0 alone updates the central ledger. Agents produce concise audit reports under:

docs/governance/campaign/reports/semantic-audit/WORKSTREAM.md (for example, A3-P3.md)

Each finding must identify the exact root, source file and symbol, platform implementation location, test evidence, defect severity, user/business consequence, proposed correction, and affected Dashboard route where applicable.

### 5.1 Required audit dimensions for each integrated root

- **Wire contract:** exact root signature, arguments, defaults, nullability, scalars/enums, input/output types, connections, pagination, error shape, and complete referenced type closure.
- **Resolver and ownership:** resolver is actually bound and delegates to the correct existing domain SPI/use case; no duplicate domain authority or direct cross-module persistence.
- **Business semantics:** operation produces the expected Saleor behavior or an explicitly approved platform adaptation.
- **Persistence:** real state is stored by the owning domain; read-back proves it; constraints and relationships are intact.
- **Authorization and scope:** anonymous/authenticated/staff/provider/customer paths, role and field permissions, ownership, provider/tenant isolation, guessed or malformed IDs, and denial behavior.
- **Transactions and concurrency:** atomicity, ordering, idempotency, replay, race conditions, retry behavior, compensation, and unknown outcomes where relevant.
- **Money and inventory invariants:** currency exponent, rounding, immutable snapshots, stock reservation/availability, multi-seller allocation, and ledger reconciliation where relevant.
- **Events and integrations:** actual publisher/consumer ownership, payload, delivery semantics, retry/replay, duplicate safety, signature validation, and revocation behavior where relevant.
- **Dashboard journey:** actual pinned Dashboard operation and route, permission-aware visibility, loading/error states, mutation, persistence, and browser read-back.

A root name, SDL signature, resolver class, mock-only test, or successful compilation does not prove semantic completion.

### 5.2 Existing ledger status transitions

Use only the existing playbook vocabulary:

- Keep INTEGRATED when code exists but the audit has not yet established the full required evidence.
- Move to TESTED only when required contract, semantic, and security tests have passed and their references are recorded.
- Use REVIEW_FINDING when a defect or missing proof invalidates the current completion claim.
- Use FIXED_RETEST_PENDING after a fix but before all affected tests pass.
- Use RELEASE_READY only after A0 verifies the evidence and required owner/scope behavior.
- Retain BLOCKED_BY_DECISION unless its required approved ADR exists.
- Use EXPLICITLY_EXCLUDED only with a reviewed, owner-approved divergence and Dashboard route disposition.

Do not introduce a parallel PASS/FAIL status field into the canonical ledger. Detailed per-dimension results belong in the linked audit report and test reports.

## 6. Required semantic test suites

Before adding tests, search for existing equivalent tests and extend them rather than duplicating coverage. Use the project's pinned JUnit 5/Spring test conventions and existing Testcontainers/Flyway rules. Recommended suite boundaries:

### 6.1 Discount precedence — A3 / P3

Test, as supported by the pinned source:

- Manual order discount suppresses lower-priority order discounts, including the shipping-voucher exception.
- Voucher and order promotion interactions.
- Manual line discount versus catalogue/voucher line discounts.
- Removing a manual discount re-evaluates eligible suppressed discounts.
- Same-level stacking restrictions.
- Gift-line behavior and discount object types.
- Denormalized unit discount fields and reasons.
- Currency and channel boundaries, date windows, and invalid combinations.

Suggested suite name: SaleorDiscountPrecedenceCompatibilityTest.

### 6.2 Order-level discount allocation — A3 / A4

Test:

- One-line and multi-line orders.
- Proportional distribution using the correct discounted base.
- Currency-specific quantization and last-line remainder reconciliation.
- Multiple contributing order discounts and the limits of per-line API attribution.
- Unit discount fields versus per-record order discount values.
- Gift lines and order/line discount type edge cases.
- Frozen order price breakdown and read-back.

Suggested suite name: SaleorOrderDiscountAllocationCompatibilityTest.

### 6.3 Stock availability — A2 / A4

Test:

- useLegacyShippingZoneStockAvailability=true and false.
- Warehouse/channel linkage and stock quantity.
- Shipping-zone/destination effects in each mode.
- Click-and-collect warehouse behavior and empty-country/include-CC cases where present upstream.
- Purchasability versus shippability as distinct outcomes.
- Mode-dependent stock event conditions and delivery (not merely subscription registration).
- Settings transition and backwards-compatible defaults/migration behavior.

Suggested suite name: SaleorStockAvailabilityCompatibilityTest.

### 6.4 Other P3/P4 semantics

Use official Saleor source and tests—not assumptions from saleor-core—to cover:

- Tax inclusivity/exclusivity, region/country and currency boundaries.
- Gift-card liability, issuance, redemption, expiry, refund/cancellation, concurrent double redemption, and ledger posting.
- Channel-specific availability, price, currency, tax and order access.
- Checkout lifecycle, frozen terms, duplicate commands and failure recovery.
- Payment authorization/capture/void/refund, duplicate webhooks, unknown provider outcomes, replay and reconciliation.
- Shipping eligibility, quote calculation, frozen fees, seller split and destination restrictions.
- Partial fulfillment, partial refund, return and cancellation rules.

Financial tests must use currency-aware precision. Never hard-code two decimal places as a universal rule.

### 6.5 Test quality

- Prefer behavior-level assertions against real domain services and persisted state.
- Use real PostgreSQL with the repository's Flyway/Testcontainers integration profile when schema, transaction, constraint, concurrency or migration behavior matters.
- Add negative authorization and cross-tenant tests.
- Add regression tests that fail against the defective pre-fix behavior and pass after the fix.
- Avoid testing only private helpers or reproducing upstream implementation line-for-line in Java.
- Do not add an upstream Saleor runtime as a test dependency.

## 7. Existing A1–A7 ownership — no role remapping

This directive does not rename or reassign the established workstreams:

- **A1 / P1 Identity and permissions:** audit all existing integrated identity/auth roots against the pinned schema, upstream semantics, platform security decisions, global-ID rules, and negative authorization tests. No saleor-core discount rule applies here.
- **A2 / P2 Catalog and attributes:** audit integrated catalog/variant/media roots. Consume stock-availability guidance for warehouse, variant availability, and catalog-to-stock semantics only where relevant. Coordinate P4 checkout consequences with A4; the ledger owner remains authoritative.
- **A3 / P3 Commercial context:** audit all 8 currently integrated P3 roots first. Apply discount-precedence rules to vouchers/promotions, and audit all remaining taxes, channels, gift cards, shop settings, and sales directly against pinned Saleor source/tests.
- **A4 / P4 Checkout, orders, payments, shipping:** audit all 10 currently integrated P4 roots. Apply order-discount allocation rules to order/checkout financial snapshots and stock-availability rules to shipping/checkout eligibility. Audit payment, transaction, refund, shipping, fulfillment, idempotency and recovery directly against their pinned sources/tests.
- **A5 / P5 Management surfaces:** audit all integrated pages, menus, page types and management operations. Consult saleor-app only for an actual app/webhook protocol requirement; do not invent a Saleor app registry or pretend an outbound webhook capability exists without an owner/decision.
- **A6 / P6 Subscriptions:** implement and verify domain-owned events, authorization, payloads, ordering, reconnect, replay and duplicate delivery. A Saleor app/webhook skill is not a substitute for subscription source and delivery tests.
- **A7 / Dashboard integration:** map retained pinned Dashboard routes to exact backend operations; run browser journeys against the candidate backend. Dashboard—not storefront-builder or storefront API patterns—is the UI reference for administration.
- **A0 / Integration Lead:** freezes the candidate base, protects shared files, coordinates A1–A7, reviews audit evidence, maintains the central ledger and divergence registry, integrates commits, and assembles final release evidence. A0 does not unilaterally approve owner decisions or merge/release to main.

No agent may edit another workstream's files, shared SDL, central ledger, divergence registry, source pins, workflow, cross-module POM, Modulith configuration, or migration allocation without A0's explicit work packet.

## 8. Work mode: audit and implementation proceed without per-slice stalls

Follow the existing playbook's continuous build model:

1. A0 establishes the reconciled CANDIDATE_BASE_SHA, frozen contracts, and exact root/file ownership.
2. At the start of each workstream, its agent performs a bounded baseline audit of the existing INTEGRATED roots in that workstream and records findings.
3. The same agent continues implementing its assigned NOT_STARTED roots that do not depend on unresolved defects or decisions. A critical defect in a shared invariant takes priority over new work that depends on it.
4. Agents work in isolated branches/worktrees from the same candidate baseline. No agent creates a wave PR, merges main, merges another agent's branch, deploys, or changes release protections.
5. A0 assembles completed commits continuously on the candidate branch in dependency order. Shared contract changes are versioned and broadcast once.
6. Decision-blocked roots remain blocked; the agent supplies a decision packet while unrelated work continues.
7. After the Implementation Complete gate in the playbook passes, independent R1–R4 review one frozen candidate SHA. Findings are consolidated, fixed, and retested before one final release PR.
8. The owner alone authorizes the final merge to main. No auto-merge. A merge to main is a production deployment trigger.

## 9. Required evidence and handoff

Each domain agent returns:

~~~text
WORKSTREAM:
CANDIDATE_BASE_SHA:
HEAD_SHA:
ROOTS_AUDITED (exact names):
ROOTS_AUDIT_PASS_WITH_EVIDENCE:
ROOTS_REVIEW_FINDING:
ROOTS_FIXED_RETEST_PENDING:
ROOTS_NOT_TESTED + reason:
ROOTS_IMPLEMENTED (exact names):
UPSTREAM_SOURCE_AND_TEST_REFERENCES:
SKILL_RULES_CONSUMED + verified upstream symbols:
PLATFORM_IMPLEMENTATION_LOCATIONS:
DASHBOARD_ROUTE_REFERENCES:
TEST_COMMANDS + exact PASS/FAIL/SKIPPED counts:
SECURITY / OWNERSHIP NEGATIVES:
MONEY / INVENTORY / CONCURRENCY EVIDENCE:
FILES_CHANGED:
COMMITS_FOR_A0_TO_ASSEMBLE:
OPEN_DECISIONS / DIVERGENCES:
KNOWN_LIMITS:
~~~

A0 verifies the reported commit and file paths, re-runs or inspects the exact test results, checks the corresponding current root rows, and only then updates the ledger. Preserve evidence as GitHub links and line references—not unsupported prose.

The audit report must distinguish:
- expected Saleor behavior;
- actual platform behavior;
- difference between them;
- whether the difference is a bug, approved adaptation, or unresolved decision;
- test proving the finding;
- correction and regression proof.

## 10. Coverage reporting

Do not collapse all correctness into one percentage. Report at least:

1. Exact root-signature coverage.
2. Type-closure compatibility.
3. Implemented/resolver proof.
4. Semantic behavior verified.
5. Authorization and tenant-scope verified.
6. Persistence/transaction/invariant verified.
7. Integration/event verified where applicable.
8. Dashboard route/E2E verified.
9. Approved exceptions and blocked decisions.
10. Remaining defects and retest-pending findings.

The central 458-root ledger remains the only source for root disposition and campaign counts. Semantic audit reports add evidence; they do not inflate root coverage or create a competing inventory.

## 11. Completion gates

The campaign cannot claim completion because the skill was installed, all agents committed, SDL compiled, or a root count increased.

Required outcomes:

- Every currently INTEGRATED root receives an evidence-based audit disposition.
- Every defect that invalidates a prior completion claim is recorded, fixed or explicitly held, and regression-tested.
- All unblocked assigned roots are implemented according to the canonical plan and owner/SPIs.
- All blocked roots have explicit owner decision packets and approved ADR/divergence outcomes before release.
- Root signatures and full type closure are compatible or approved as documented.
- Semantic, authorization, persistence, transaction, event, integration and Dashboard tests pass as required by each root.
- The final candidate has no fake/no-op success paths or unresolved blocking divergence.
- Independent R1–R4 findings are closed or explicitly accepted under the owner's process.
- Required CI, security, compatibility, architecture and Dashboard gates are green on the exact final candidate SHA.
- Final evidence is linked from the central ledger and campaign handoff.

## 12. First executable sequence for A0

1. Re-fetch the live branch/main/PR #771/root-inventory state; do not reuse the 2026-10-30 snapshot without verification.
2. Reconcile the 14-commit main divergence safely and publish one CANDIDATE_BASE_SHA.
3. Verify PR #771 and the actual presence/integrity of saleor-core and its registry on the candidate. If not yet available, read the pinned upstream skill only as read-only research.
4. Regenerate/validate the campaign root inventory and confirm all 458 rows still match the pinned schema and domain matrix.
5. Dispatch the existing A1–A7 assignments with this directive, the playbook, frozen contracts, exact root lists and file boundaries.
6. Require A3 and A4 to start with P3/P4 semantic audits; A2 audits stock/catalog availability; all other agents audit their own existing INTEGRATED roots while progressing unblocked work.
7. Start candidate assembly as commits and evidence arrive. Do not wait for every independent workstream to finish before assembling safe, dependency-ready work.
8. Report baseline counts, candidate SHA, active workstreams, audit findings, commits assembled, gates and blockers to the owner.

**Execution rule:** begin from live GitHub evidence, preserve the existing plan and ownership, use skills to ask better questions, use pinned Saleor source/tests to answer them, and use platform tests plus Dashboard journeys to prove the implementation.
