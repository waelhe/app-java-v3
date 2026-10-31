# Commerce Promotion to Main & Expanded Saleor Agent-Skills Plan

**Status:** Proposed executable integration plan — owner authorization required for the production merge  
**Applies to:** `integration/saleor-full-commerce-20261021` and its approved candidate descendants  
**Target:** `main`  
**Governing decision:** `D-SALEOR-FULL-001`  
**A0 role:** Integration Lead / Contract Steward

> This plan supplements, and does not replace, the canonical Saleor execution plan, the multi-agent execution playbook, or the Saleor Agent Skills & Semantic Audit Directive. If any instruction conflicts with those authorities, stop and ask A0 to reconcile it. No part of this plan authorizes a Saleor runtime, a second database, a production deployment, or a merge to `main`.

## 1. Governing documents and boundaries

The following remain authoritative:

1. `docs/governance/plans/saleor-dashboard-full-commerce-canonical-plan.md` — the sole scope and execution authority for D-SALEOR-FULL-001.
2. `docs/governance/campaign/saleor-multi-agent-execution-playbook.md` — continuous build, A0–A7 ownership, candidate assembly, independent review, and one final release PR.
3. `docs/governance/campaign/saleor-agent-skills-semantic-audit-directive.md` — skill use, semantic audit of previously integrated roots, evidence, and skill-backed tests.
4. `docs/governance/campaign/A0-EXECUTION-DIRECTIVE-20261028.md` — workstream assignments and file ownership.
5. After branch reconciliation, `docs/governance/agent-skills-policy.md`, `docs/governance/plans/agent-skills-pins.json`, `.coderabbit.yaml`, and `.github/workflows/coderabbit-gate.yml` from `main`.

This plan must not create a second 458-root inventory, reassign A1–A7, bypass A0 ownership of shared files, or change the canonical contract. A skill is specialist reference material, never a wire-contract authority.

## 2. Live baseline — measured 2026-10-31

The GitHub compare of `main...integration/saleor-full-commerce-20261021` returned:

- `main` base SHA: `f2349deca955cee435e6be3ca56be8a4c251d00a`.
- Relationship: `diverged`.
- Commerce branch: **129 commits ahead, 15 commits behind** `main`.
- Compare API returned 300 file entries. This is the response ceiling observed from the comparison endpoint, not a proven complete changed-file count.

The old baseline in §3 of the semantic-audit directive (124 ahead / 14 behind, and PR #771 still open) is a historical 2026-10-30 snapshot. Do not reuse it as the execution baseline. A0 must capture the current branch HEAD, `main` HEAD, merge base, complete commit ranges, complete changed-path inventory, migrations head, root-inventory blob, open PRs, review threads, and checks immediately before reconciliation.

PR #771, `governance(agent-skills): pin the vendored skills surface and adopt saleor-core`, is now merged into `main`. The merged main contains the policy, registry, vendored `saleor-core`, and fail-closed governance test. At this measurement those files are absent from the commerce branch. The first reconciliation must bring the **whole approved PR #771 change** into the commerce candidate through the normal main-to-commerce reconciliation; do not manually copy only selected files, re-install a live skill, or cherry-pick around the divergent history.

### Live baseline refresh — later measurement on 2026-10-31

The initial §2 snapshot above is retained as historical evidence. A later live remeasurement on the same date supersedes its divergence statement:

- Live `main`: `f2349deca955cee435e6be3ca56be8a4c251d00a`.
- Live `integration/saleor-full-commerce-20261021`: `435b895d2c98ada7dc2c9661bb2973c7774f78b4`.
- GitHub compare `main...integration`: `ahead`, 133 commits ahead, 0 behind; merge base equals the current main SHA. The complete main history is already an ancestor of the commerce branch.
- The approved PR #771 payload is present on the commerce branch and byte-identical by Git blob SHA to main:
  - `docs/governance/agent-skills-policy.md`: `9fb1d91a9ad25392fb9e719f122d258866bbd609`
  - `docs/governance/plans/agent-skills-pins.json`: `6ef9280fb535b42ed083b523b5fbeb56e88cc9b1`
  - `.agents/skills/saleor-core/SKILL.md`: `2db2c66276fffbf0ba79c15942b87ca0ea0476c7`
  - `.coderabbit.yaml`: `f998e013b5c8438e9ea4f56a493ef859e9a4eb51`
  - `.github/workflows/coderabbit-gate.yml`: `aa4435733823ba8aba180df2a2eacd3118c489b9`

**A0 conclusion:** Phase A's main-to-commerce reconciliation and inclusion of the approved PR #771 payload are already satisfied at this live snapshot. Do not merge main again, cherry-pick PR #771, or copy its files manually. Keep `435b895d2c98ada7dc2c9661bb2973c7774f78b4` as the current integration baseline until a new candidate is explicitly published. Prepared agent workspaces pinned to this SHA may proceed against this baseline; any new candidate SHA must be broadcast before they rebase or open PRs.

## 3. Saleor skill-source change — required provenance refresh

The previous skills directive and PR #771 pin `saleor-core` to `saleor/agent-skills@fb1a57255f58950962f6e474ccba1cade40f5163`. That is a valid historical pin and must remain byte-verifiable until a reviewed migration replaces it.

Saleor's current official Agents documentation now directs installation/discovery through **`saleor/saleor-docs`**, not `saleor/agent-skills`, and publishes five skills: `saleor-core`, `saleor-storefront`, `saleor-app`, `saleor-configurator`, and `storefront-builder`. Its machine-readable discovery index is:

- `https://docs.saleor.io/developer/ai`
- `https://docs.saleor.io/.well-known/agent-skills/index.json`
- Source repository: `https://github.com/saleor/saleor-docs/tree/main/skills/`

This change is important because the existing main policy says the five-skill inventory is complete under the old `saleor/agent-skills` source. That source/catalog statement needs a reviewed correction. Do not silently rewrite the old pin or treat a mutable `main` URL as a reproducible version.

### 3.1 Required source migration work

A0 opens a dedicated, reviewable provenance update after the branch reconciliation:

1. Capture the exact `saleor/saleor-docs` commit SHA used for the snapshot; do not pin the symbolic `main` branch.
2. Record the official discovery-index response and the advertised digest for every skill bundle.
3. Download/inspect each selected skill bundle at that exact snapshot and verify its contents against the source tree. Record per-file SHA-256, source path, repository, commit, version metadata, and license/attribution.
4. Compare the new `saleor-core` content with the currently vendored `saleor/agent-skills@fb1a5725` files. Produce a behavior-level change report; do not overwrite the existing snapshot until the diff is reviewed.
5. Preserve the existing known-upstream-errata record and the observed license statements. The old `saleor-core` skill frontmatter says MIT while the old upstream repository root says BSD-3-Clause AND CC-BY-4.0. Record the new source's license evidence separately; do not invent a legal reconciliation.
6. For each newly adopted skill, vendor only the reviewed exact snapshot. Update the pin registry, policy/catalog, per-file hashes, license/attribution, and governance test in the same PR.
7. Keep unadopted skills in the official-source applicability catalog, not as fake vendored registry entries. A non-vendored skill must not be presented as installed or hash-verified locally.
8. Never run `npx skills add` in this repository. The installer resolves a moving upstream version and can replace the reviewed snapshot outside the pin workflow.

This is a provenance migration, not a wire-contract migration. It does not change Saleor 3.23.43, Dashboard 3.23.39, the SDL, any root disposition, or an approved divergence.

## 4. Expanded skill utilization matrix

The goal is to extract maximum **relevant, verifiable** value from all five official skills—not to install every skill or execute instructions that assume a separate Saleor server.

| Official skill | Useful workstreams | What agents should extract and verify | Explicit boundary |
|---|---|---|---|
| `saleor-core` | A2/P2, A3/P3, A4/P4, A6 where stock events apply, A7 only for the Dashboard guidance documented inside the published rule files | Exactly the three published rule families of the pinned snapshot `saleor/agent-skills@fb1a57255f58950962f6e474ccba1cade40f5163`: `discount-precedence` (precedence, suppression, denormalized-field semantics), `order-discount-allocation` (two-pass distribution, per-line exposure, gift-line shape), and `stock-availability-modes` (legacy vs direct stock, purchasability vs shippability, click-and-collect, mode-conditional events, and the Dashboard-wording and migration/test guidance those rule files themselves document). | Coverage is those three rule files at that pinned commit and nothing else. Taxes, gift cards, channels, payment, checkout, orders, and shipping details not in those rules require direct pinned Saleor 3.23.43 source/tests. No additional topic may be attributed to this skill until a fixed new snapshot is reviewed and documented through the §3 provenance procedure. |
| `saleor-storefront` | A2/P2, A3/P3, A4/P4; optional consumer work in `waelhe/web-marketplace` | GraphQL data-model and nullability patterns; token/permission behavior; product/variant selection; channel-scoped queries; checkout lifecycle; purchasability and storefront-facing price/availability. | It is a consumer/API usage reference, not authority to change the pinned SDL. It does not replace the backend canonical plan or the administration Dashboard. Cross-repository changes require a separate scoped work packet. |
| `saleor-app` | A4 payment integrations when a real external-app protocol is in scope; A5 integrations/webhooks; A6 only for actual app/webhook boundaries | App manifest and registration protocol, app/user permission scopes, payment-app lifecycle, external webhook signature/authentication, sync vs async delivery, retry, settings and Dashboard AppBridge behavior. | Conditional reference only. Do not create a Saleor app registry, app-token store, AppBridge runtime, or second integration authority unless an owner-approved platform requirement and domain owner exist. It is not a substitute for the platform's own subscription/event-delivery source and tests. |
| `saleor-configurator` | A0 governance/configuration design; A3/A5 only where a real declarative configuration surface is approved | Config schema design, stable entity identity (slug vs name), desired-vs-actual diff, dependency ordering, idempotent deployment plans, and partial-failure reporting. | Read-only design reference. Do not run Configurator against Saleor, provision a Saleor shop, add Saleor credentials, or create a second configuration authority. Any platform config-code proposal needs an owning module and decision. |
| `storefront-builder` | A2/A3/A4 buyer-journey analysis; optional consumer work in `waelhe/web-marketplace` | Product-list and product-detail data needs, variant-selection UX, channel/price/availability correctness, and end-to-end buyer flow expectations. | Do not execute its bootstrap steps: they assume a Saleor instance, Saleor API URL/token, Saleor client and generated client setup. Do not use it to bootstrap the admin Dashboard; the pinned Saleor Dashboard 3.23.39 remains the admin UI baseline. |

### 4.1 Skill-to-workstream use is task-triggered, not blanket

- **A1 / P1:** use storefront permission guidance only as a source of questions about public/customer/staff access; prove actual backend permissions against pinned schema/source and the platform's RBAC/ownership decisions. Do not import Saleor token architecture.
- **A2 / P2:** use core stock rules and storefront product/variant rules. Trace inventory, purchasability and shippability through the existing catalog/inventory owners and their SPIs.
- **A3 / P3:** use core discount-precedence and allocation rules; use storefront channel/price display guidance where a real buyer surface consumes the contract. Verify tax, channel, gift-card, promotion and configuration behavior directly against Saleor 3.23.43 source and tests.
- **A4 / P4:** use core order-discount and stock rules at the checkout/order/shipping boundaries; use storefront checkout lifecycle and app payment rules only for actual implemented external-provider/app protocol paths. Verify all payment, refund, fulfillment, and recovery behavior from pinned source/tests and platform owners.
- **A5 / P5:** use saleor-app for actual app/webhook protocol work and configurator for an approved platform configuration design. No app registry or Saleor configurator runtime is implied.
- **A6 / P6:** distinguish GraphQL subscription registration from actual event publication and delivery. Use saleor-app only for real outbound/inbound webhook protocol semantics; validate subscriptions against the pinned Saleor source and platform outbox/event-delivery owners.
- **A7 / Dashboard:** keep the complete pinned Saleor Dashboard 3.23.39 as the admin UI authority. Use `saleor-core` only for relevant Dashboard behavior/copy. Use storefront/builder references only for buyer-facing journeys, not as Dashboard scaffolding.

No skill changes A0–A7 ownership, central ledger ownership, shared SDL authority, SPI ownership, Flyway numbering, or the decision/ADR process.

## 5. Mandatory skill-consumption protocol

For every implementation or audit claim materially informed by a skill:

1. Select the skill and the narrow rule relevant to the operation; read `SKILL.md` and the rule/reference file, not just a generated summary.
2. Treat the skill as a checklist or hypothesis. Identify the exact behavior it claims.
3. Verify the claim against the exact Saleor 3.23.43 source symbol and its unit/integration tests. For UI behavior, inspect the relevant Dashboard 3.23.39 operation/component.
4. Record source repository, commit, file path, symbol, line range, upstream test, and Dashboard operation/route if applicable.
5. Compare it with the actual platform resolver, owner SPI/use case, persistence, transaction, and authorization implementation.
6. Classify the result as: compatible behavior, defect, approved platform adaptation, or unresolved owner decision. Wire differences go through the existing divergences registry; other system defects go through defect-knowledge evidence.
7. Add/extend a regression test that proves the accepted behavior. Use JUnit/Spring tests and PostgreSQL/Testcontainers where persistence, locking, transactions, constraints, migrations, or concurrency matter.
8. Report the skill rule consumed, upstream proof, platform mapping, test command/result, and candidate SHA in the existing workstream semantic-audit report.

Never copy Saleor's Django/Graphene internals directly into Java. Reimplement behavior using existing platform domain owners, declared SPIs, and platform-native persistence. Do not count a read skill, a prompt, an SDL signature, a resolver stub, or a mock-only test as completion evidence.

## 6. Expanded test and audit coverage

Continue the mandatory audit of **every root currently marked INTEGRATED** under the existing semantic-audit directive. Skill use does not narrow that audit to P2–P4 and does not change the single central root inventory.

In addition to the skill-specific suites already named by the semantic-audit directive, ensure coverage is considered for:

- **Storefront/API semantics:** nullability and data closure; anonymous vs authenticated vs privileged access; token scopes; product and variant selection; variant attributes; channel context; checkout state and failure shapes.
- **Discount/pricing semantics:** precedence, suppression/reevaluation, proportional distribution, gift lines, frozen order breakdown, currency exponent and rounding.
- **Inventory/shipping semantics:** legacy/direct modes, warehouse/channel linkage, destination restrictions, purchasability vs shippability, click-and-collect, stock event emission and delivery.
- **Integration/app semantics, only where an approved capability exists:** manifest/registration, app-vs-user scope, webhook signature validation, replay/idempotency, retry/dead-letter behavior, payment provider unknown outcomes and reconciliation.
- **Configuration semantics, only where a platform owner exists:** identity stability, declarative diff correctness, dependency order, idempotency, safe deletion and partial-failure recovery.
- **Buyer journeys:** PLP → PDP → variant selection → add-to-cart/checkout → payment outcome → order read-back, with browser tests against the platform candidate when the owning frontend scope is explicitly assigned.
- **Administration journeys:** pinned Dashboard route → backend Saleor-shaped operation → actual persistence → browser read-back, including permissions, loading, validation and error states.

Use official Saleor source/tests for behavior not documented by a selected skill. Keep financial precision currency-aware. Do not add the Saleor backend as a test runtime or dependency.

## 7. Branch reconciliation and promotion to main

This repository's existing playbook requires one assembled candidate and one final release PR because a merge to `main` triggers production deployment. Preserve that model.

### Phase A — Re-establish and reconcile

1. A0 freezes live `main`, commerce branch, merge base, open PRs, checks, review threads, migration head, root inventory, and all changed paths/commit ranges.
2. Reconcile **main into commerce first**. No commerce-to-main merge, force push, history rewrite, or blind rebase.
3. Include the complete already-merged PR #771 change: policy, pin registry, vendored files, and governance test. Resolve conflicts with their ownership and test implications recorded.
4. Record `CANDIDATE_BASE_SHA`; validate that the vendored files match registered hashes and `PlatformGovernanceFilesTest` fails closed on drift.
5. Refresh the stale baseline section in the semantic-audit handoff with dated live measurements, retaining historical evidence rather than rewriting old measurements as if they were current.

### Phase B — Candidate build and audit

1. Dispatch A1–A7 using the existing playbook and exact root/file ownership.
2. Agents audit their already-INTEGRATED roots and continue unblocked NOT_STARTED implementation in the same continuous build cycle.
3. A0 assembles completed commits on the candidate branch in dependency order, not by merging partial waves to `main`.
4. Resolve decision-blocked roots only through owner-approved ADR/divergence records. No no-op or success-shaped implementation.
5. The skill source refresh in §3 is a separate provenance work packet; it must not block unrelated commerce implementation, and the existing pinned core snapshot remains usable while the reviewed refresh is pending.

### Phase C — Review and one release PR

1. Freeze one complete candidate SHA after implementation gates.
2. Run independent R1–R4 reviews against that same SHA, remediate and retest.
3. Create one final release PR from the fully assembled candidate to `main`. No wave PR is merged to `main` separately.
4. Do not enable auto-merge. Production release requires the owner's explicit authorization and all gates on the final candidate SHA.

## 8. CodeRabbit: quota, file handling, and elapsed time

The repository already carries `.coderabbit.yaml` and `CodeRabbit Gate` in `main`; the observed copies on the commerce branch were byte-identical at the last check. Bring the main versions through reconciliation and verify them again on the candidate. Record the approved git-blob identity of both files from `main` at reconciliation time — measured 2026-10-31: `.coderabbit.yaml` blob `f998e013b5c8438e9ea4f56a493ef859e9a4eb51`, `.github/workflows/coderabbit-gate.yml` blob `aa4435733823ba8aba180df2a2eacd3118c489b9` — and re-read them from `main` at reconciliation in case `main` moved. The gate workflow does not verify these files itself, so the candidate release check must fail when either file differs from the approved identity, unless the owner approves a documented exception. Do not weaken the gate to work around large diffs.

**Verified operating facts:**

- CodeRabbit's official plan documentation states that the **Open Source (OSS) tier allows 100–300 files per review**, depending on project community/popularity; the exact allowance is repository-scoped. The file count is measured after `path_filters`. Its OSS rate-limit row separately lists **1–10 PR reviews/hour, 1 IDE review/hour, and 3 CLI reviews/hour** per developer. Public repositories with fewer than 10 stars require manual PR-review triggering. See the official [Plans and pricing](https://docs.coderabbit.ai/management/plans) documentation.
- PR #771's actual CodeRabbit review metadata stated one included review per hour and zero remaining after that review. This is the measured project observation for that review, consistent with the lower end of the OSS tier; recheck the live allowance before each campaign.
- The official limits documentation says usage-based large-PR review can be offered when an eligible review exceeds its included file allowance but contains no more than 300 files; **reviews above 300 files are not supported through usage pricing**. Do not plan on paid overage to make a greater-than-300-file review possible. For the current OSS arrangement, do not enable paid usage-based review without owner approval.
- The workflow's **720-second** human-review polling deadline is a local gate setting, not CodeRabbit's service timeout. The workflow's **75-minute** job timeout is a GitHub Actions ceiling, not a CodeRabbit runtime guarantee.
- The official limits page establishes a per-review file-count cap, but this plan has not established a universal per-file byte-size ceiling or CodeRabbit service-timeout value. Record any per-file skip/truncation or time-limit error from the actual review run; do not infer a vendor limit from the local 720-second gate or the GitHub compare endpoint's 300 returned-file cap.

### 8.1 Review preflight and batching

Before invoking CodeRabbit, A0 records:

- PR changed-file count and full path list;
- per-file byte size and changed-line count;
- whether files are generated, vendored, lockfiles, SDL, POMs, migrations, workflows, or production source/tests;
- CodeRabbit's current plan allowance and any account-visible file/size constraints;
- CodeRabbit's processed/selected-file list, skipped/unsupported-file notices, rate-limit status, and elapsed time after the review.

Partition review work so no CodeRabbit invocation exceeds the repository's currently confirmed OSS allowance. Until the account UI proves a higher limit, plan each request against the **100-file lower bound**, then use the observed current OSS allowance to raise the batch ceiling; never plan an invocation above 300 files. If a candidate needs more than one review shard, the coverage-gate work in §8.2 is mandatory. Use semantic batches, and separately measure unusually large individual files by bytes/changed lines. Do not solve size problems by hiding production code, tests, SDL, migrations, or security workflows in `path_filters`. Any intentionally excluded file must have an alternate named check/reviewer and be listed in the review evidence.

For human-authored PRs, invoke `@coderabbitai review` once per intended review head when manual activation is required. Use the separate OSS CLI allowance for early, bounded working-diff reviews where the CLI is installed and authorized; schedule these before PR creation and record their output. CLI findings are pre-review evidence only and do not satisfy the GitHub PR review requirement or the current CodeRabbit Gate. All agents operating through the same GitHub/CodeRabbit developer identity share that identity's applicable quota; do not evade limits by creating additional identities. Avoid repeated retries while rate-limited; wait for the actual allowance to refill and preserve the failure evidence. A review is not complete merely because a status says success: inspect the real bot review, the head SHA, processed files, and findings.

### 8.2 Large final candidate: no false coverage

The current CodeRabbit Gate accepts the latest completed CodeRabbit review on the **current PR head**; it does not prove that every file in an oversized PR was processed. Before the final release PR:

1. Prove with a representative candidate that CodeRabbit's actual supported file/size limits cover the complete final diff.
2. If the final diff exceeds those limits or CodeRabbit reports skipped/unsupported files, A0 must not claim full CodeRabbit coverage.
3. A0 must design and test a supplementary **CodeRabbit Review Coverage Gate** that verifies review-shard evidence against the final candidate: every changed path is accounted for, the reviewed file content is unchanged at the candidate SHA, unsupported/generated files have explicit alternative checks, and no file is silently omitted.
4. The coverage gate must include negative tests for a missing file, changed file after review, stale head, unsupported file, and incomplete review record. It supplements the current CodeRabbit Gate; it must not create a bypass or accept an unverified summary.
5. Only A0 may change the shared workflow/branch protection, and only through a reviewed PR with the required governance and owner approval. Until the evidence-aware coverage mechanism is proven, the release PR remains blocked if CodeRabbit cannot review its complete relevant diff.

If a CodeRabbit review finishes after the local 720-second gate wait and the PR head has not moved, re-run the gate workflow itself. If the head changes, require a fresh synchronize-triggered run bound to the new head; a manual workflow-dispatch verdict on `main` is not proof for a PR head.

## 9. Completion evidence and release gates

A0's final release index must link:

- reconciled `main` and commerce SHAs and `CANDIDATE_BASE_SHA`;
- all assembled commits and their workstream/root ownership;
- root ledger rows and semantic-audit reports;
- the exact Saleor source/Dashboard evidence and skill rules consumed;
- current skill-source pins, integrity test results, license/attribution evidence, and known upstream errata;
- review-shard coverage (if required), actual CodeRabbit review IDs/head SHA, processed files, and resolved threads;
- R1–R4 findings and closure evidence;
- CI, JUnit, integration, security, Modulith, Flyway, Saleor-shaped GraphQL compatibility (the `Saleor Backend Compatibility Gate` artifact: the platform's own DGS/resolver contract tests and real HTTP GraphQL integration journeys — no Saleor backend runtime), pinned Saleor source/test evidence, Android/client contract, and Dashboard E2E results on the exact candidate SHA;
- known exclusions, owner-approved ADRs/divergences, and remaining risks.

Do not report a single blended “Saleor completion percentage”. Keep separate measured dimensions as specified by the semantic-audit directive.

## 10. First actions for A0

1. Re-fetch the live baseline and publish the new `CANDIDATE_BASE_SHA`; the 2026-10-31 measurements in §2 are a starting snapshot only.
2. Reconcile `main` into commerce and verify the full PR #771 governance/skill payload.
3. Update the stale live-baseline note in the semantic-audit handoff.
4. Validate the one-root-per-row inventory against the pinned schema and current domain matrix.
5. Run the source-provenance spike for `saleor/saleor-docs` and publish a five-skill applicability/source-pin proposal. Do not vendor or update skill files in this plan-only change.
6. Dispatch the current A1–A7 owners. Prioritize A2/A3/A4 where core skill rules give direct value, while every workstream audits its existing INTEGRATED roots.
7. Measure CodeRabbit's current quota and file-processing limits using a bounded, representative review. Design the coverage gate if the full release candidate cannot be reviewed in one request.
8. Assemble candidate work continuously, then complete independent review and the single final release PR.

**Release invariant:** one coherent, evidence-complete candidate; one final main release PR; one owner-authorized production merge. Skills improve discovery and review quality, but only pinned upstream evidence and platform tests establish correctness.
