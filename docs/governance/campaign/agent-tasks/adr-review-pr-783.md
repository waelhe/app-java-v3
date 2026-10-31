# PR #783 — Agent review assignments for the commerce ownership ADRs

**Status:** Review directive only. The seven ADRs remain PROPOSED; this file does not approve them, change the canonical plan, or authorize implementation of blocked roots.  
**Applies to:** A0–A7 in the existing Saleor campaign playbook.  
**PR:** https://github.com/waelhe/app-java-v3/pull/783  
**Target branch:** integration/saleor-full-commerce-20261021  
**Authority order:** canonical plan → frozen contracts → approved divergence registry → current source/tests → these proposed ADRs. This document supplements, and does not replace, the A0 execution directive or the multi-agent playbook.

## 0. Order of PRs and the exact starting point

There are two separate PRs on the same target branch. They must not be treated as competing implementation PRs:

| PR | Scope | Current treatment |
|---|---|---|
| [#773](https://github.com/waelhe/app-java-v3/pull/773) | Source-provenance migration for the already adopted saleor-core skill and official Saleor AI resource catalogue. No commerce SDL or domain behavior changes. | A0 owns the last CodeRabbit digest assertion follow-up. Merge to the commerce integration branch only after the new-head CI, Integration Test, Snyk and CodeRabbit review are verified. Never merge this PR to main. |
| [#783](https://github.com/waelhe/app-java-v3/pull/783) | Seven proposed ownership ADRs plus this review assignment. | Keep Draft and review-only. Do not merge it while agents are still reviewing or before the owner has decided the proposed ADRs. |

**Measured start state on 2026-10-31:** PR #773 is open, non-draft and mergeable. Its preceding head had CI and Integration Test success, Snyk success, and a CodeRabbit APPROVED review plus one low-severity follow-up asking that the five SHA-256 digests be asserted as exact values, not only as 64-character hex strings. A0 has applied that test-only correction on commit `8c78e79b718f4d69e9e3980ec0904e3a90551ce2`; the CI and Integration Test runs for this new commit were still in progress at the last measurement, while Snyk was success. The new head requires a fresh CodeRabbit review/result.

The target branch was last measured at `435b895d2c98ada7dc2c9661bb2973c7774f78b4`. This is a live branch tip, not a permanent candidate baseline. The semantic-audit directive's earlier `CANDIDATE_BASE_SHA=6608a869...` is the reconciled baseline from before subsequent governance commits. **Do not create fresh implementation branches from either historical SHA by assumption.**

### Start immediately — review track

A1–A7 may begin their assigned review now against the exact seven ADR files in PR #783 and the live code/source evidence. Their review is read-only: submit findings as PR #783 comments. PR #773 does not change those ADR files or the commerce schema, so there is no reason to hold the review until #773 is merged.

### Start after PR #773 — implementation track

A0 must wait for the #773 new-head gates/review, merge it only into `integration/saleor-full-commerce-20261021`, then re-read the live integration and main heads, inventory blob, migration head and open PRs. A0 publishes a fresh `CANDIDATE_BASE_SHA` equal to the verified integration head after that merge. Only then should A0 create or confirm isolated A1–A7 implementation branches from that same SHA. If any agent already has a branch from an earlier base, A0 inventories its commits and reconciles it deliberately; nobody blindly rebases or throws away work.

PR #783 remains a review-only Draft against the integration branch. After #773 lands, re-check/update #783 against the new target head without changing its seven ADR meanings. Do not merge #783 merely to let agents read it; the files are already visible on its PR head.

### First action for each role

| Role | First thing to open | Review track on PR #783 | Existing implementation track after A0 publishes the new base |
|---|---|---|---|
| **A0** | PR #773 head, CodeRabbit follow-up, then #783 decision files and current inventory | Lead and consolidate all reviews; check dependencies and contradictions | Refresh candidate SHA; allocate branches/migrations; maintain shared SDL, inventory and divergence records. No domain implementation task. |
| **A1** | D-GC-001, D-CHANNEL-001, D-SHOP-001 and ADR-0013 | Verify issuer vs merchant-of-record vs seller vs cardholder; authorization, role matrix, provider isolation, bearer-code security and audit ownership | No P1 NOT_STARTED roots in the measured assignment; continue the assigned P1 INTEGRATED semantic/security audit and report P1 blockers through decision packets. |
| **A2** | D-TAX-001, D-CHANNEL-001, D-SALE-001, D-WHSE-001 | Define catalog-owned TaxClass assignment and the Catalog–Channels contract; evidence whether warehouses are actually required; compare catalogue Sale behavior | Continue only P2 NOT_STARTED roots in the live inventory that do not require the unresolved Channel, TaxClass or Warehouse contract; audit P2 INTEGRATED roots. Do not build warehouses or channel publication shortcuts. |
| **A3** | All seven ADRs, starting with D-CHANNEL-001 and D-TAX-001; inspect current TaxRate, Promotion and Voucher owners | Lead the P3 domain review. Produce a root-by-root dependency split and exact Saleor 3.23.43 evidence | Continue independent promotion/voucher semantics and audit the eight currently INTEGRATED promotion/voucher roots. Hold TaxClass/configuration, gift-card, channel, Sale and Shop/settings-dependent roots until their ADRs are owner-approved. In particular, do not infer that a NOT_STARTED status overrides a dependency identified by the ADR. |
| **A4** | D-GC-001, D-SETTINGS-001, D-CHANNEL-001 and ADR-0013 | Produce the reservation/tender/refund/ledger state-flow, including mixed tender, partial refund, idempotency and unknown PSP outcomes | Continue independent P4 payment/order/checkout/shipping work from the live inventory. Hold gift-card tender, channel-dependent behavior and settings work until interfaces and owner decisions are approved. |
| **A5** | D-SHOP-001 and D-SETTINGS-001, then pinned Dashboard Shop/PageType query call sites | Map each Shop field/mutation and reasonReferenceType to a real platform owner; separate public profile from console settings/secrets | Continue P5 roots with a verified owner and current contract; maintain the existing page/menu implementation and audit. Do not create generic Shop/Settings CRUD or expose console secrets. |
| **A6** | Pinned 3.23.43 Subscription root inventory and D-GC-001/D-CHANNEL-001/D-SETTINGS-001 | Separate real Saleor public subscription operations from internal Modulith events; verify event owner, transaction boundary, replay and authorization | Continue only subscriptions backed by real domain publishers and current owner contracts. Do not invent gift-card/channel subscription roots or use asynchronous listeners as the financial transaction boundary. |
| **A7** | Pinned Dashboard 3.23.39 query/fragment/component source for Sale, Promotions, Taxes, Channels, Gift Cards, Shop, Return/Refund Settings and Warehouse | Build the route → GraphQL operation → type closure → permission → read-back matrix; identify exact blocked controls and required UI states | Continue dashboard contract synchronization and authorized journeys. Do not remove, enable or redesign a route until A0 records an owner-approved disposition and exact backend capability. |

**Implementation root selection rule:** use the current `saleor-458-root-inventory.csv` as the sole root list and the live `NOT_STARTED` status, filtered by the existing `owner_agent` and `wave`. The old root lists in dated task files are a snapshot, not permission to execute a root whose dependency is now blocked. Where a proposed ADR reveals such a dependency, the agent raises the exact roots to A0; A0 records the correct disposition only after owner approval. Each workstream audits its own `INTEGRATED` roots in parallel with unrelated implementation work.

## 1. Instructions common to every agent

1. Read the current live versions of `AGENTS.md`, `SYSTEM.md`, `PROJECT_MAP.md`, the canonical Saleor plan, `frozen-contracts.md`, the current A0 directive, the relevant ADR(s), and the exact pinned Saleor 3.23.43 source/tests. Read the pinned Dashboard 3.23.39 operation call sites where the conclusion affects a Dashboard route.
2. Review the current branch head and central root inventory. Do not reuse stale counts, old root lists, or the 2026-10-28 snapshot as proof of current status.
3. Review decisions, not merely prose. Challenge ownership, lifecycle, consistency, migration/backfill, authorization, tenant/provider scope, money, concurrency, idempotency, audit, event delivery, history, Dashboard dependency and acceptance evidence.
4. For each finding, cite the exact repository path/symbol or pinned upstream path/test and, where relevant, line numbers and the current platform test. Label it **BLOCKER**, **CHANGE REQUIRED**, **RISK**, or **ACCEPTED**. Distinguish an upstream requirement from a platform choice.
5. Do not edit any of the seven ADR files, the central SDL, the root inventory, the domain matrix, the divergence registry, pins, POM/Modulith configuration, Flyway sequence, workflows or the canonical plan. Send proposed shared-file edits to A0.
6. Do not implement a root currently marked `BLOCKED_BY_DECISION`, do not add placeholder/no-op resolvers, and do not change root status because an ADR file exists. A root stays blocked until the owner has accepted the relevant ADR and A0 has updated the central records.
7. Continue all unrelated work already assigned by the canonical agent packet. This review must not stop the wider campaign or make an unaffected agent wait for another agent.
8. Deliver the review as a comment on PR #783. Do not open separate implementation PRs for this review. Use the response template in §4.

## 2. Role-by-role assignments

### A0 — Integration Lead / Contract Steward

**Primary responsibility:** own the review process and the central records. A0 is not a second domain implementation agent.

- Verify all seven proposed ADRs against the current canonical plan, frozen contract pack, domain matrix, divergence rows and root inventory.
- Re-read the live PR #783 head, target branch head and CI/review status before any integration action. Resolve factual inconsistencies in this directive with the current source of truth.
- Ensure that the decision graph is coherent: Channels is a dependency of channel-scoped tax/pricing/shop behavior; Gift cards depend on checkout/tender/refund/ledger contracts; shop is a projection; post-sale settings remain in their domain owners; Sale and Warehouse each require explicit Dashboard disposition.
- Check that recommendations do not silently change D-SALEOR-FULL-001, the 458-root target, frozen money rules, or the production/merchant-of-record policy in ADR-0013.
- Consolidate A1–A7 findings. Return each ADR as **ACCEPT FOR OWNER DECISION**, **REVISE**, or **REJECT**, with reasons. Do not mark an ADR Accepted on behalf of the repository owner.
- Only after explicit owner approval: update the divergence registry, central root inventory, decision references and Dashboard route dispositions in one controlled A0-owned change. Keep blocked roots blocked until their individual implementation evidence passes.
- Do not merge PR #783 to main, authorize production release, or change protected workflows.

**Required deliverable:** one A0 synthesis comment on PR #783 listing accepted/revised/rejected recommendations, dependencies, unresolved owner questions, and the exact central records that must change after owner approval.

### A1 — Identity, users, authorization and legal-party boundaries

**Review focus:** D-GC-001, D-CHANNEL-001, D-SHOP-001, with security implications of D-SETTINGS-001.

- Validate the distinction between the gift-card issuer/liable legal party, platform merchant of record, provider/seller, account user, assigned cardholder and staff actor. Identify the existing authoritative identity/provider/legal-identity models and SPIs; do not assume an entity exists just because the ADR names it.
- Review card assignment/unassignment, bearer-code possession, staff administration, owner access, provider isolation, guessed-ID behavior, rate limiting, revocation and audit actor semantics.
- Check that a Channel ID cannot substitute for a user, provider, legal entity, geography or authorization scope. Specify the actual RBAC/resource-scope enforcement boundary.
- Check who may issue, activate, adjust, deactivate, resend, assign, redeem and view gift cards, including separation of duties for manual balance adjustments and exemptions.
- Identify any identity/provider SPI that must be consumed; do not change another module's persistence or create a duplicate legal-identity store.

**Required deliverable:** security/identity review of issuer and cardholder ownership, role matrix, scope/IDOR risks, audit requirements, and any required changes to D-GC-001/D-CHANNEL-001/D-SHOP-001.

**Continue independently:** audit existing P1 INTEGRATED roots and complete any assigned P1 work. Do not start the currently blocked identity roots merely because this PR exists.

### A2 — Catalog, product types, attributes, stock and media

**Review focus:** D-TAX-001, D-CHANNEL-001 and D-WHSE-001; cross-check D-SALE-001.

- Determine how a tax class is assigned to a product/product type and how that reference is exposed to pricing through a public SPI. The Catalog owner remains responsible for its own product data; pricing must not reach into catalog repositories.
- Check whether a product can have one tax class or needs inheritance/override rules, and specify precedence for product vs product-type vs shipping tax classifications. Do not invent a default class without measured data and a backfill plan.
- Check channel-specific publication/availability and the exact contract between catalog and marketplace-channels; ensure the channel owner does not own duplicate product/stock state.
- Review the current stock_quantity/reserved_quantity model and whether any current workflows require multiple physical fulfillment locations. Identify evidence needed to reopen D-WHSE-001; do not add Warehouse CRUD or fake a default warehouse.
- For Sale, verify whether existing Promotion/catalog-pricing behavior can express Sale's catalogue-target and displayed-price semantics. If not, enumerate the exact gaps; do not conflate an order promotion with a catalog price reduction.
- Cross-check any tax-class effect on variants, bundles/sets, digital goods, bookable services and delivery fees against the platform's actual domain model.

**Required deliverable:** catalog/pricing SPI impact note, proposed tax-class assignment ownership/precedence, evidence for or against multi-warehouse need, and Sale-to-catalogue-promotion gap list.

**Continue independently:** P2 NOT_STARTED roots and the assigned P2 semantic audit, except any channel/tax-class/warehouse-dependent slice that requires an accepted contract.

### A3 — Pricing, promotions, taxes, channels, gift cards and commercial settings

**Primary domain reviewer** for D-GC-001, D-TAX-001, D-CHANNEL-001, D-SHOP-001 and D-SALE-001; co-review D-SETTINGS-001 with A4.

- Validate that marketplace-pricing remains the sole calculation authority and that extending it for tax classes does not create a second tax engine or turn the Channels module into a pricing/settings god-object.
- Inspect actual pricing code, TaxRate V223 migration/schema, exact money contract, active-rate selection, rounding/exponent logic, frozen order price/tax breakdown and current promotion/voucher models. Correct any inaccurate description of measured facts in the ADR.
- Prove whether a genuine Promotion can represent **catalogue** price rules separately from **order** promotions. Compare pinned Saleor 3.23.43 Sale and Promotion fields, input/output types, deprecation, target catalogue semantics, dates, channel listings, translations, precedence and rounding.
- Review channel invariants and its minimum SPI. Define only context identity/currency/status in Channels; list every configuration that must remain with its true owner.
- For gift cards, check that the proposed lifecycle is not assigned to vouchers/promotions or PSP payments; define the pricing boundary between discount computation and tender allocation. Coordinate with A4 on the Checkout contract and with A1 on issuer permissions.
- Identify which P3 work is independent and may continue now (for example, verified global promotion/voucher behaviors) and which exact roots depend on the proposed ADRs.
- Do not write Java, SQL, SDL or mutate the registry for blocked roots until the owner has accepted the corresponding ADR and A0 issues a reviewed implementation packet.

**Required deliverable:** domain review of the five ADRs; exact upstream source/test evidence; accepted/rejected alternatives; P3 dependency table separating unblocked work from decision-blocked roots; concrete test cases and any ADR revisions.

**Continue independently:** unblocked P3 NOT_STARTED roots and the existing promotion/voucher semantic audit. Keep all channel-, tax-class-, Sale-, gift-card- and blocked settings-dependent work gated as above.

### A4 — Checkout, orders, payments, refunds, shipping and ledger integration

**Review focus:** D-GC-001 and D-SETTINGS-001; also D-CHANNEL-001 and D-WHSE-001 impacts.

- Design the ownership boundary for gift-card tender across checkout, orders, gift cards, payments and ledger. A4 owns neither the gift-card balance nor its issuer liability account.
- Specify the exact state machine and transaction boundary for reservation → tender authorization/completion → redemption consumption, and failure/timeout/unknown-outcome → release/reconciliation. Distinguish PSP authorization/capture from internal gift-card redemption.
- Validate mixed tender (gift card + PSP or other tender), partial balance consumption, multiple cards if supported, cancellation, partial/full refund, original-tender refund allocation and idempotent replay. Do not recompute a refund split from current settings.
- Reconcile ADR-0013 merchant-of-record, commission, holds and payout behavior. Gift-card liability/credit must not become provider payout or PSP settlement by implication.
- Check channel identity/currency and the commercial-context snapshot captured by checkout/order. Define the behavior when a Channel is deactivated after order placement.
- Separate RMA eligibility/policy ownership from refund execution and financial reversal. Confirm the existing returns/RMA owner and payment refund port by code and tests, rather than assuming a module name or boundary.
- Review orderSettingsUpdate and the PageType reason-reference settings independently from return/refund lifecycle. A settings write must not be presented as an order state change or payment refund.
- Check whether warehouse decisions affect fulfillment allocation/reservation; do not assume a warehouse dimension is present.

**Required deliverable:** end-to-end money/state-flow review, transaction/idempotency/correlation model, failure/compensation table, multi-tender/refund test matrix and any required revisions to D-GC-001/D-SETTINGS-001.

**Continue independently:** A4 NOT_STARTED P4 roots and audit INTEGRATED order/checkout roots not dependent on unsettled Channel/Gift-card settings. Do not wire blocked gift-card roots.

### A5 — Pages, PageTypes, Shop administration, extensions, exports and webhooks

**Review focus:** D-SHOP-001 and D-SETTINGS-001; help A7 on route dispositions.

- Verify exactly what the pinned Saleor 3.23.43 Shop type and shop mutations expose. Build a field-by-field ownership map for each retained Shop field and mutation.
- Check whether the platform already has a genuine platform-wide public profile owner before recommending creation of marketplace-shop-profile. Separate public platform profile from each provider's profile and legal entity.
- Verify whether existing CMS PageType can truthfully implement Saleor refundSettings/returnSettings reasonReferenceType. If not, say so and keep those operations unavailable; do not coerce unrelated page types.
- Separate operational console SystemSettings/secrets from public commercial settings; identify which settings may be read, written, audited or must never be exposed.
- Verify whether shopSettingsTranslate is covered by an existing bilingual domain model or needs explicit exclusions/extension. Do not assume a general translation engine exists.
- Check shopFetchTaxRates against the real owner. It must not create a Tax Gateway concept or present a locally configured TaxRate as a provider gateway result.
- Review shop domain/address updates for platform-host ownership, DNS proof, verification and audit requirements; do not invent the public-domain model.
- Keep app/plugin/webhook/export work on its assigned P5 scope; only use saleor-app reference if the actual task requires its protocol and pinned source confirms it.

**Required deliverable:** Shop field-owner matrix, PageType mapping result, safe/unsupported settings list, secrets/redaction constraints and route/API evidence for A7.

**Continue independently:** P5 NOT_STARTED work unrelated to the Shop/return settings gaps, plus P5 semantic audit. Do not implement blocked Shop/PageType roots.

### A6 — Subscriptions and domain event delivery

**Review focus:** event, consistency and replay implications of D-GC-001, D-CHANNEL-001 and D-SETTINGS-001.

- Inspect the pinned Saleor 3.23.43 SDL to determine which subscription events actually exist for gift cards, channels, orders, checkout, product availability and returns. Do not invent subscription roots or event names.
- Determine whether the proposed gift-card lifecycle requires internal domain events for consumers even when no Saleor subscription exists. Distinguish internal events from public GraphQL subscriptions.
- For any event required by an accepted contract, identify the owning module publisher, payload contract, transaction commit point, publication registry/outbox behavior, retry/replay, duplicate/idempotency semantics, ordering and authorization.
- A balance/ledger invariant must commit atomically under its own use-case transaction; an asynchronous event listener must not be the correctness boundary for a debit or journal posting.
- Consider channel deactivation, price/tax-policy changes, order/refund and access revocation. Ensure consumers cannot observe a partial commercial transition or leak data across providers/roles.
- Keep the existing 26 subscription root inventory intact; no new event or status may be added without A0 approval and exact Dashboard/client requirement.

**Required deliverable:** upstream subscription inventory evidence, internal-vs-public event map, transactional publication/replay risks and tests required. Explicitly report when no Saleor public subscription exists for a proposed lifecycle.

**Continue independently:** assigned P6 subscription implementations and audits for existing roots; do not add gift-card/channel subscription surfaces as part of this review unless the pinned contract and an approved platform extension require them.

### A7 — Saleor Dashboard integration and route owner

**Review focus:** all seven decisions from the Dashboard 3.23.39 consumer perspective.

- Inspect the exact pinned Dashboard 3.23.39 source/queries/components for Sales, Promotions, tax classes/configurations/types, Channels, gift cards/settings, Shop settings, refund/return settings and warehouse/stock routes.
- Produce a route → GraphQL operation → required types/fields → permission → mutation/read-back matrix for every affected route. Include deprecated Sale surfaces and indirect fragments, not only the top-level page.
- State which controls depend on blocked roots, which can run on existing platform-backed operations, and which Dashboard routes may remain visible only with explicit unsupported capability metadata.
- For every proposed exclusion, prove no retained Dashboard flow requires the root and identify the exact UI route/control affected. Do not assume Sale is unused because it is deprecated.
- Verify that the Dashboard handles missing/disabled capabilities honestly: no enabled button leading to runtime failure, fake success toast, empty data presented as real, or silent hiding of an essential operation.
- Review whether Shop Projection can satisfy the UI's requested field closure without duplicating domain data; identify any direct mutation that cannot be handled by a read projection alone.
- Do not edit the Dashboard, overlay, generated operation types or backend SDL in this review. Submit an operation-level mapping and candidate route disposition to A0.

**Required deliverable:** operation-level route dependency matrix for all seven decisions, exact source references, proposed enable/disable/partial-support treatment and E2E journeys needed after decisions are accepted.

**Continue independently:** current Dashboard contract sync and existing P1/P2/P4 work that is already authorized; no route is removed or enabled by this review directive alone.

## 3. Decision ownership and dependency sequence

- **Repository owner:** final approval/rejection of proposed ADRs. A0 cannot infer owner approval from the PR's existence or CI.
- **A0:** sole integrator of review findings and sole editor of central divergence/root-inventory/SDL/pins and other shared governance files.
- **A3:** domain steward for P3 semantics and recommendations; cannot authorize itself to implement blocked P3 roots.
- **A1/A2/A4/A5/A6/A7:** supply evidence in their domains, not unilateral changes to shared contracts.

Suggested dependency order for decision resolution:
1. A1 + A4 review issuer/merchant-of-record/cardholder and tender/refund boundaries for D-GC-001.
2. A2 + A3 review tax-class ownership and the channel SPI; A3 checks pricing; A1 checks scope/security; A4 checks checkout/order currency snapshots.
3. A5 + A7 review Shop fields/settings/route dependencies, with A4 on post-sale settings and CMS PageType semantics.
4. A2 + A3 + A7 review legacy Sale and catalogue Promotion equivalence.
5. A2 + A4 + A3 review Warehouse deferral against actual stock reservation/fulfillment demand.
6. A6 cross-check event/public-subscription needs across accepted lifecycles.
7. A0 consolidates; owner approves; only then are central registries and work packets updated.

These reviews may run in parallel. The sequence above describes decision dependencies, not a serial wait state.

## 4. Required response format for each agent

Post one comment on PR #783 using this template:

```text
AGENT: A1|A2|A3|A4|A5|A6|A7
ADR(s) reviewed:
VERDICT per ADR: ACCEPT FOR OWNER DECISION | CHANGE REQUIRED | REJECT
MEASURED FACTS (exact paths/symbols/pinned upstream source + tests):
OWNERSHIP / SPI:
RISKS / COUNTEREXAMPLES:
DEPENDENT ROOTS / DASHBOARD ROUTES:
WHAT CAN CONTINUE NOW:
WHAT MUST REMAIN BLOCKED:
REQUIRED ACCEPTANCE TESTS:
PROPOSED ADR EDITS FOR A0:
```

A0 posts a separate synthesis comment. No agent may mark its own proposal approved or change the central root disposition.

## 5. Completion condition for this PR's review

The review is complete only when:
- A1–A7 have either submitted their assigned evidence or A0 records that a role is not applicable with a reason;
- every ADR has a consolidated verdict and any required amendment is incorporated by A0;
- cross-domain conflicts (merchant of record vs issuer; channel vs provider scope; tax class vs catalog ownership; gift-card debit vs ledger posting; RMA vs PSP refund; Sale vs catalogue/order promotion; warehouse vs actual inventory/fulfillment) are explicitly resolved or retained as blocking questions;
- the repository owner has made the approval decision;
- no central ledger/SDL/root status is changed before that approval.

After approval, implementation returns to the existing campaign's isolated work packets and A0 assembly model. PR #783 itself is a governance review PR, not permission to start all seven domains at once.
