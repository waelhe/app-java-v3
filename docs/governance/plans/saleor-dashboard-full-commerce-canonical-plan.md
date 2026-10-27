# Canonical Execution Plan — Full Saleor Dashboard + Full Saleor Commerce Schema

**Status:** CANONICAL EXECUTION AUTHORITY — owner decision recorded 2026-10-20. Target and executable governance plan; implementation is not yet complete.  
**Decision ID:** D-SALEOR-FULL-001  
**Pins:** [saleor-source-pins.json](saleor-source-pins.json)  
**Domain matrix:** [saleor-commerce-domain-matrix.csv](saleor-commerce-domain-matrix.csv)  
**Divergence registry:** [saleor-contract-divergences.csv](saleor-contract-divergences.csv)  
**Automatic guards:** [canonical plan/schema inventory](../../../.github/workflows/saleor-canonical-governance.yml), [retired Saleor PR guard](../../../.github/workflows/retired-saleor-pr-guard.yml)

> **Supersession rule:** This document is the single authority for Saleor Dashboard + commerce-schema integration. The previous Dashboard adaptation plan and schema-parity plan are historical evidence only. They must not be selected as execution authorities. CI enforces the superseded markers. Historical measurements and useful domain findings remain preserved.

## 1. Owner decisions — fixed scope

### D1. Reuse the complete Saleor Dashboard as the mature administration base

Use saleor/saleor-dashboard tag 3.23.39, commit bc75e988da0fa4fbc3d47378a888d859d070746f, as the reproducible UI baseline. Preserve the full upstream commerce workbenches, navigation, product/order screens, list/detail/editor patterns, filters, forms, permission-aware navigation, error/loading states, component tests and E2E assets. Expand this same base for the platform's provider, business, community, property, services and tourism workbenches.

“Full base” means the full maintained codebase remains the starting point. It does not mean every menu item is production-ready before its backend capability passes acceptance. Until then, CI capability metadata must disable or explicitly mark unsupported routes rather than show a fake-success screen.

### D2. Use the complete Saleor schema as the commerce reference contract

The immutable reference is saleor/saleor@3.23.43:saleor/graphql/schema.graphql, Git blob SHA b8fa0dd809081f76dc2834bb5c286561e5783276. The measured root inventory is 90 Query roots + 342 Mutation roots + 26 Subscription events = **458 root entry points**. Exact domain counts and owner mappings live in the CSV matrix.

This is the complete reference and target inventory, not permission to add null/no-op resolvers. Every root must be classified, assigned, implemented or explicitly exempted with a decision record, and tested. The release target is full support for every retained Dashboard operation and a complete disposition for every root in the pinned reference schema. No domain is silently dropped because it is difficult or does not fit an existing DTO.

### D3. Reimplement in the platform — do not run a second Saleor commerce engine

Do not deploy Saleor's Django/Graphene service or a Saleor database as a second source of truth. Spring Boot, the existing PostgreSQL database and current Spring Modulith domain owners remain authoritative. Netflix DGS is the only GraphQL resolver programming model; Maven owns code generation/build gates. Every compatibility resolver delegates to the same owner use case that governs the platform REST API. No duplicated pricing, inventory, order, payment, refund, identity or permission state machine.

### D4. Saleor wire compatibility and platform-native internals are separate

For Saleor operations used by retained Dashboard routes, preserve expected public root names, argument shapes, object/input/enum/scalar contracts, nullability, connection semantics and lifecycle behavior. A similar platform operation with another name is **not** a substitute for the Saleor operation.

Implement adapters at the GraphQL boundary:
- Relay/global IDs are translated to native identifiers by one tested codec; database primary keys do not change merely to mimic Saleor.
- Saleor decimal money values are parsed and serialized with exact decimal arithmetic and explicitly configured currency exponents, then mapped to the platform's integer minor-unit representation. Never use binary floating point for authoritative money.
- Saleor role/permission checks map to the platform's server-side RBAC, resource ownership, provider and geographic scopes. UI route guards are not security boundaries.
- Saleor channel arguments map to real **commercial context** (publication, currency, price, availability, tax, promotion, shipping and order visibility). A neighbourhood, persona or access-control scope is not automatically a Channel.
- Saleor transaction/payment operations delegate to existing payment and ledger owners; returns delegate to the existing RMA owner; fulfillment/shipping delegates to delivery owners.
- OIDC/PKCE remains the platform's identity authority. The Dashboard sign-in/session adapter must use it rather than adding a parallel password/token identity store. Any upstream authentication operation not used by the adapted sign-in flow must have an explicit disposition and must not be a fake-success resolver.

For every retained upstream Dashboard mutation, preserve the Saleor-shaped payload/error fields, enums, nullability and per-field error semantics expected by that operation. Map native domain validation/authorization errors into that wire shape; do not replace it with a platform-only `extensions.code` contract. Platform-only extension operations may additionally use stable `extensions.code` semantics.

Any wire-level exception must be recorded in [the machine-readable divergence registry](saleor-contract-divergences.csv), including the exact surface, disposition, public wire requirement, internal mapping, owner, affected Dashboard screens, acceptance tests, approval status, decision record and review trigger. CI fails if any required field is missing or two rows describe the same surface. No casual renaming of Saleor operations.

### D5. Preserve platform extensions without corrupting commerce semantics

Social/community, business/provider, second-hand, property, jobs, local-services and tourism/booking remain first-class platform capabilities. Add their operations as platform extensions and reuse identifiers/projections from each owning domain. Do not force an appointment, hotel inventory, used item, property, job or community post into a retail SKU/warehouse model.

Existing platform-only roots (including storeProduct* and social/service roots) may remain as backward-compatible extensions while existing mobile clients or integrations consume them. They are not Saleor-root matches. Migrate clients through the accepted contract channel; deprecate/remove old roots only after consumer inventory, telemetry and compatibility gates prove removal safe.

## 2. Why this is the selected design

Saleor's official Dashboard source makes the coupling explicit: its product-list query uses products, ProductFilterInput, ProductOrder, channel, Relay cursors and Saleor product fragments. The Dashboard fetches schemas and generates operation/type artifacts during pre-build. It is not a backend-neutral admin theme and cannot be integrated by changing API_URL alone.

Official sources:
- Saleor API reference: https://docs.saleor.io/api-reference/
- Pinned Dashboard product query: https://github.com/saleor/saleor-dashboard/blob/3.23.39/src/products/queries.ts
- Pinned Dashboard configuration/schema generation: https://github.com/saleor/saleor-dashboard/blob/3.23.39/docs/configuration.md
- Pinned Dashboard multi-schema generation: https://github.com/saleor/saleor-dashboard/blob/3.23.39/docs/multi-schema.md
- Saleor 3.23.43 complete source schema: https://github.com/saleor/saleor/blob/3.23.43/saleor/graphql/schema.graphql
- Saleor marketplace recipe: https://docs.saleor.io/recipes/marketplace
- Saleor extension/fork trade-offs: https://docs.saleor.io/developer/extending/overview
- DGS programming model and Spring for GraphQL integration: https://netflix.github.io/dgs/spring-graphql-integration/
- DGS code generation and Maven-plugin provenance: https://netflix.github.io/dgs/generating-code-from-schema/
- Spring GraphQL test support: https://docs.spring.io/spring-graphql/reference/testing.html
- Spring Data keyset scrolling: https://docs.spring.io/spring-data/commons/docs/current/api/org/springframework/data/domain/KeysetScrollPosition.html
- Spring Modulith transactional events/publication registry: https://docs.spring.io/spring-modulith/reference/events.html
- GraphQL Cursor Connections specification: https://relay.dev/graphql/connections.htm

Useful community evidence (context, not higher authority than upstream source/docs):
- The maintainers' marketplace discussion notes that vendor UI/permissions and seller-specific rules are application-specific and are not automatically delivered by a standard Dashboard: https://github.com/saleor/saleor/discussions/10641
- The community multi-vendor/channel discussion warns that channels alone do not implement vendor isolation: https://github.com/saleor/saleor/discussions/8599
- The official documentation is candid that maintaining a Dashboard fork costs ongoing upstream/security work. The owner decision deliberately accepts that cost because the requirement is to retain and expand the full mature UI while Spring remains the commerce engine.

## 3. Target architecture

    Pinned Saleor Dashboard base (full source tree, maintained fork)
      ├─ retained Saleor commerce workbenches
      ├─ platform admin workbenches (provider, business, community, property, tourism)
      ├─ platform OIDC/PKCE auth adapter
      └─ generated typed operations from the accepted platform contract
                           |
                           v
                Platform GraphQL endpoint
          Saleor-compatible facade + platform extensions
          Netflix DGS resolvers in marketplace-app only
                           |
                    SPIs / use cases / events
                           |
                           v
      Existing Spring Modulith owners and PostgreSQL source of truth
      catalog | identity/console | pricing | checkout/orders
      payments/ledger | delivery | returns | media/search | verticals

Boundary invariants:
1. GraphQL resolvers are thin adapters; they do not own business state transitions.
2. Cross-module access uses declared SPIs/use cases/events; domain modules do not depend on application GraphQL classes.
3. REST remains supported where already contracted. REST and GraphQL invoke the same authoritative use case.
4. Public/authenticated access is explicitly declared per root/field. Existing /graphql anonymous rejection remains until each public journey gets a deliberate policy and negative tests.
5. Every state-changing operation defines validation, idempotency, transaction ownership, audit evidence, failure/unknown-outcome recovery and permission scope.

## 4. Contract and inventory automation — no hand-maintained 458-row list

The pinned schema is the source for inventory generation. CI downloads the exact immutable tag, verifies its Git blob SHA, parses the SDL and regenerates an operation-level report. The report is an artifact, not a hand-edited registry. The checked-in CSV is the domain-level ownership/phase/acceptance matrix.

The generated operation report records each of the 458 roots:
- root type and name;
- @doc(category) group and phase;
- argument/output signature;
- whether the accepted backend schema has the same root name and signature;
- owner modules, target policy and acceptance criteria inherited from the reviewed domain matrix;
- tests and E2E journeys that prove the operation. Wire-level exceptions are linked conceptually through the separate divergence registry; they remain unapproved until their owner/ADR gate is closed.

Coverage is reported in separate columns: **exact root-signature presence**, full input/output type-closure compatibility, implementation/resolver proof, authorization proof, and Dashboard runtime journey. A matching root name alone is not full parity. Platform-only roots are reported separately and never inflate Saleor coverage.

The 12 Query + 6 Mutation + 0 Subscription figure is the **historical plan-inception baseline**, not the current Saleor root-parity result. The latest completed inventory report is [run 40479098193](https://github.com/waelhe/app-java-v3/actions/runs/40479098193), generated on `main` SHA `7836c1576cbc5f8100dd629a8240625f9bd6cb0f`; it reported 3/458 (0.66%) Saleor root names/signatures matched, 455 root names missing, and the three matches (`me`, `user`, `staffUsers`) with type closure unverified. **That report predates the subsequent accepted-contract publication**: the inventory job fetched `graphql-contract` at 09:13 UTC, while [publication run 40482268883](https://github.com/waelhe/app-java-v3/actions/runs/40482268883) completed later at 09:38 UTC from the same main SHA. The current contract branch is `482bb907f805e831ff023ad2e0623f17365960ae`, with `schema.graphqls` blob `d09fb0687856ecf118241761b9a3b181b4494c33`; it contains 15 Query, 6 Mutation and 0 Subscription roots in total. Therefore 3/458 is the latest saved inventory result, but must be regenerated against the post-publication contract before it is treated as the current measured parity figure. Platform-only roots do not count as Saleor matches. The divergence registry contains 14 rows: 10 `IMPLEMENTATION_REQUIRED` and 4 `OWNER_ADR_REQUIRED`. The scheduled inventory guard verifies that source pins and the matrix are internally consistent and accurately reports gaps; it does not prove implementation. Release-ready mode fails unless retained Dashboard operations are mapped and tested and the full root inventory has a disposition. SDL codegen compiling is not proof that a root is implemented.

## 5. Domain matrix and implementation waves

The pinned SDL generates one report row per root. The checked-in domain matrix is category-level by design and supplies ownership, phase, target policy and acceptance criteria; the generated report adds every exact root name/signature. Exceptions and non-applicability are recorded separately in the divergence registry. Wave totals:

| Wave | Root count | Domain scope | Required outcome |
|---|---:|---|---|
| P0 | 0 (governance) | Pins, inventory, supersession, generated reports, cross-repo contract lifecycle | One authority; no manual counting or stale-plan comeback |
| P1 | 62 | Users (53) + Authentication (9) | Platform OIDC/PKCE, user/profile and permission mapping, resource scopes, exact operation dispositions |
| P2 | 95 | Products (78) + Attributes (17) | Saleor-shaped product/types/attributes/variants, filters, sorting, pagination, media and publication |
| P3 | 85 | Discounts (33), Gift cards (21), Taxes (14), Channels (8), Shop (9) | Coherent price, promotion, tax, currency and commercial-context semantics |
| P4 | 111 | Orders (52), Checkout (23), Payments (21), Shipping (15) | Checkout-to-order-to-payment-to-fulfillment/return lifecycle over existing owners |
| P5 | 79 | Apps (21), Pages (18), Menu (15), Webhooks (9), Uncategorized (16) | Full workbench capabilities or explicit approved exception per root; no placeholder resolver |
| P6 | 26 | Subscription events | Scoped live events, reconnect and duplicate-delivery semantics |
| P7 | 0 (release gate) | Full contract closure, Dashboard routes, parity and operational readiness | Verified 100% disposition and all retained routes pass E2E |

Phase order is dependency-aware, not permission to skip a later category. Work can parallelize only when its SPI and acceptance contract are stable. Each wave is split into small, reviewable PRs mapped to exact operations/routes.

### P0 — Freeze the source of truth and automate the registry

1. Install the pinned manifest and domain matrix.
2. Mark the old Dashboard adaptation and schema-parity plans as superseded historical evidence.
3. Close the retired implementation path. PRs #678, #679, #680, #681 and #683 were closed with explicit comments; #676 and #677 were already closed and are marked historical in their discussions. Their commits/branches remain salvageable history. Re-home useful logic only through new operation-aligned PRs. The separate retired-PR guard fails if any of #676, #677, #678, #679, #680, #681 or #683 is reopened.
4. Add CI verifying the pinned upstream schema blob, measured 90/342/26 counts, matrix totals, superseded markers and published graphql-contract snapshot.
5. Publish an operation-level CSV artifact and CI summary on every PR/main run. A scheduled drift job opens one deduplicated issue if either official upstream release advances. Pins change only through a reviewed update.

**Gate:** matrix totals exactly 458; no unclassified domain category; no superseded plan selected as authority; generated coverage report attached to CI.

### P1 — Authorization, identities and compatibility primitives (62 roots)

- Implement retained Dashboard user/profile and permission operations using existing identity and console owners.
- Use one tested global-ID codec at the boundary; validate type tags, malformed IDs, ownership and tenant/provider scope.
- Adapt sign-in/session refresh to platform OIDC/PKCE; never create a separate staff/password database.
- Map Saleor permission enums/routes to server-enforced platform capabilities. Check permissions and data scope at service/use-case boundaries, not only in React.
- Add field-level allow/deny tests for admin, provider, consumer and unauthorized actors. Every root gets an adopt/adapt/exception disposition before it counts as ready.

**Gate:** real Dashboard login/session journey; anonymous requests remain rejected; cross-provider, cross-role and guessed-ID requests fail safely.

### P2 — Full catalog surface and typed attributes (95 roots)

- Product types and typed attributes, products, variants/options, category trees, collections, search, filters, sort, cursors, media lifecycle, publication and bulk operations map to existing catalog/search/media owners.
- Preserve the model distinction between product identity, purchasable variant, unique individual listing, property and bookable inventory. Only retail goods receive SKU/stock semantics where the domain supports them.
- products is the Saleor-facing operation for the Dashboard; storeProducts remains only a separate legacy/platform extension until consuming clients migrate.
- Implement deterministic bounded cursor/keyset paging. The current offset-backed encoded cursor is not keyset; concurrent-insert/delete invariance must be demonstrated for any surface claiming keyset semantics.
- Media upload uses a supported upload/signed-URL path and the existing media owner; fake in-memory image URLs are not acceptance.

**Gate:** Dashboard creates, edits, publishes, lists, filters, sorts and paginates a product; typed attributes and variant options persist; media lifecycle works; wrong-owner and invalid-variant tests fail as expected.

### P3 — Commercial context, promotions, tax and configuration (85 roots)

- Implement Saleor channel semantics as a real commercial context where Dashboard uses them: allowed catalog, currency, price/availability, discount/tax/shipping eligibility and order scoping.
- Do not equate a neighbourhood, persona, provider ownership or authorization boundary to a channel without a distinct sales-context decision.
- Map discounts, vouchers and tax configuration to marketplace-pricing; previews and mutations delegate to the same price engine used by checkout.
- Gift cards are part of the selected full-commerce target, not an indefinite deferral. Establish an owning domain capability (new module or explicitly selected existing owner) for liability/balance, issuance, redemption, expiry, cancellation/refund and ledger posting. Do not expose placeholder success responses; concurrency and ledger acceptance tests are release requirements.
- Shop settings read/write authoritative configuration only; secrets never appear in API responses/logs.

**Gate:** Dashboard changes channel/tax/promotion context and observes the same frozen price breakdown at order placement. Gift-card operations require race and double-redemption tests before activation.

### P4 — Checkout, order, payment and shipping lifecycle (111 roots)

- Map checkout operations to existing cart/checkout use cases; preserve atomic mixed-seller placement, seller-specific order splits, frozen line prices and delivery fees.
- Map order reads, allowed transitions, fulfillment, cancellation, returns and refunds to marketplace-orders, RMA and delivery owners. Do not create a second order state machine.
- Map Saleor transaction/payment views to marketplace-payments and ledger; PSP callbacks remain signed webhook/REST contracts where appropriate.
- Refund completion uses the existing return-scoped idempotency key and authoritative refund port. Resolvers do not compute an independent refund amount or call the PSP directly.
- Shipping zone/method selections translate to actual delivery rules. Stock quantity, reservation, warehouse allocation and bookable capacity remain separate resources.

**Gate:** authenticated Playwright journey covers mixed-seller cart → checkout → order → payment result → seller fulfillment → return request → partial refund, with database assertions, duplicate/replay failures and ledger read-back.

### P5 — Remaining Dashboard management surfaces (79 roots)

- Apps/extensions: build a real platform extension registry/adapter only where the corresponding capability exists; Saleor Apps are not installed as if the platform were Saleor Cloud.
- Pages/menu: map to real owned CMS/navigation records or document a reviewed route disposition; no nonfunctional buttons.
- Webhooks: use the platform's trusted event/publication model, persisted delivery attempts, signatures, retries and redacted payloads.
- Classify every uncategorized root from the generated inventory. Federation, plugins, translations, export jobs and other roots require an owner plus an explicit adopt/adapt/defer/N-A decision.
- Platform social/community/property/jobs/tourism workbenches are added after their owning contracts and E2E recipes exist. They do not replace Saleor commerce operations.

**Gate:** every retained route has 100% operation mapping to accepted API contracts; every removed/deferred route has an explicit capability record and automated assertion that it cannot ship enabled.

### P6 — Live events (26 subscription events)

- Introduce events with an approved end-to-end consumer using Spring for GraphQL transport integrated with the DGS programming model.
- Use the owning module event/publication mechanism, not resolver-side polling or a second event state machine.
- Authenticate setup and authorize each subscription by user, provider, order and resource scope. Define reconnect, event ordering, duplicate/replayed delivery, backpressure and retention.

**Gate:** integration tests verify authorized delivery, cross-tenant rejection, reconnect/replay, duplicate-delivery safety and no event leak after access revocation.

### P7 — Final full Dashboard release gate

Release only after:
- the pinned reference inventory has 458/458 root dispositions in the generated report. Every applicable commerce root is implemented and tested; true architectural non-applicability is permitted only with an owner-approved divergence ADR and an explicit Dashboard route disposition. No `DEFER`, `IMPLEMENTATION_REQUIRED`, or `OWNER_ADR_REQUIRED` may remain at full release.
- all Dashboard runtime operations in retained routes map to accepted contracts and are implemented/tested for the release;
- operation signatures and required nested type/input/enum/scalar closure are verified, not just root names;
- final-head backend CI, schema compatibility, generated code, Modulith boundaries and negative security tests pass;
- Dashboard schema generation, typecheck, build, component tests and platform-backed Playwright E2E pass against the same immutable contract SHA;
- no runtime call reaches a Saleor API, and no production build fetches an unpinned upstream main schema;
- the downstream contract artifact is published only from successful CI; clients consume the immutable published artifact;
- release evidence separately reports root signatures, type-closure compatibility, implemented operations, E2E-passed operations, exceptions and pins.

## 6. Automation model — default path, not manual choreography

| Event | Automated action | Fail-closed condition |
|---|---|---|
| Any PR changing plan, matrix, pins or SDL | Verify reference blob/counts, matrix totals, superseded markers; generate exact root report | Pin/count/category drift, marker removal, duplicate row or malformed SDL |
| Backend GraphQL SDL change | Existing schema compatibility gate + codegen/compile + authorization tests; publish only after green CI | Breaking contract without approved exception; generated-code drift |
| Accepted SDL publication | Publish immutable contract revision with source SHA and SDL SHA; no hand edits | Missing/incorrect manifest or non-green source |
| Dashboard adapter change | Sync accepted schema by immutable SHA; generate platform operation types; fail on unmapped required operation | Required operation absent or type-incompatible for a shippable route |
| Scheduled weekly run | Compare pinned refs against latest official releases; create/update one tracking issue per upstream drift | Network/source-verification failure is an explicit failure/unknown, never “no drift” |
| Main push / scheduled run | Read the divergence registry, maintain one deduplicated `[Saleor divergence tracking]` issue with every unapproved adapter/ADR, refresh its body on each accepted update, and auto-close only when all rows are approved | Only runs after the canonical workflow is merged to `main`; API/issue-write failure fails the job instead of silently dropping the backlog |
| Scheduled downstream Dashboard build | Companion repository PR [marketplace-admin#2](https://github.com/waelhe/marketplace-admin/pull/2) was merged on 2026-10-20. The active `.github/workflows/dashboard-build.yml` weekly workflow syncs the accepted backend contract by immutable SHA, generates platform operation types, type-checks the overlay and builds the pinned Dashboard. Latest successful scheduled run: [40328802992](https://github.com/waelhe/marketplace-admin/actions/runs/40328802992), 2026-10-26, on Dashboard `main` SHA `982ecf3667d2242cab941737cb6a0bceaee7ffcb`. | This is an active contract-drift/build guard only; a successful build is not proof that every Saleor root or retained Dashboard journey is implemented. |
| Release | Run backend and Dashboard E2E on the exact final contract SHA; publish only after checks | No green checks on final head, unresolved review threads or mismatched contract artifact |

The scheduled process may open/update an issue or candidate PR, but must not silently move production pins or auto-merge main. Here, merging deploys production and protected-branch governance requires owner authorization. Automation removes manual counting, syncing, drift discovery, stale-plan checks and routine code generation; it does not bypass production approval.

## 7. Divergence registry rules

The controlling machine-readable record is [saleor-contract-divergences.csv](saleor-contract-divergences.csv). Each row includes the Saleor surface, disposition, public wire requirement, internal mapping, owner modules, affected Dashboard surfaces, acceptance tests, approval status, decision record, rationale and review trigger. CI rejects empty/duplicate rows and unknown status values. The P7 release gate requires every row to be approved with a real decision record; `IMPLEMENTATION_REQUIRED` and `OWNER_ADR_REQUIRED` cannot ship. Examples requiring entries include:
- platform OIDC instead of stock Saleor username/password token flow;
- UUID storage behind Relay/global IDs;
- exact Decimal-to-minor-unit conversion;
- platform RBAC/provider/geographic scope;
- real commercial-channel semantics;
- platform-specific extension/event model.

Existing platform roots are not deleted in this governance change. They remain backward-compatible until consumer migration and compatibility gates authorize deprecation.

## 8. Retirement and migration of older plans/PRs

The following documents remain in history but cease to be execution authorities:
1. docs/governance/plans/saleor-dashboard-marketplace-adaptation-plan.md — original fork/source study and lessons; contradictory implementation rules are superseded.
2. docs/research/saleor-schema-parity-plan.md — measured 3.23.43 baseline and domain observations; previous wave order and narrower adoption policy are superseded.
3. docs/research/saleor-commerce-backend-fit-gap.md and docs/research/social-commerce-business-tourism-saleor-alignment.md remain useful domain evidence only; this plan owns Dashboard/schema integration.

**Actual PR history is authoritative (measured 2026-10-20):** #676 and #677 were merged before D-SALEOR-FULL-001. #678 was closed at 11:52:01 UTC and then merged at 12:20:31 UTC by the repository's subsequent merge session; its keyset-backed product filter/sort foundation is therefore part of current `main`. The owner's keep-or-revert decision on #678 remains open as documented on #686; absent an explicit revert command the content stays in place and must be assessed against the Saleor-shaped `products` contract in P2 — it does not count as implementing the full Saleor product surface by itself. PRs #679, #680, #681 and #683 remain closed and unmerged; their commits/branches are preserved as source material, not accepted implementation. A workflow fails if any retired PR number #676, #677, #678, #679, #680, #681 or #683 is reopened. Continue with new operation-aligned PRs only; never reopen an old PR to resume its retired contract strategy.

## 9. Final definition of done

Complete only when the full pinned Dashboard codebase is the deployed admin foundation, the full Saleor commerce root/type inventory has an explicit measured disposition, all required retained routes run against Spring-owned use cases, platform-specific workbenches are integrated without domain corruption, and automated backend/schema/Dashboard/E2E/drift checks keep the design reproducible. A schema count, compiled SDL, green unit tests or visible UI menu is not completion by itself.

## 10. Evidence baseline and limits

At creation, the accepted backend schema was measured in the existing parity study as 12 Query + 6 Mutation + 0 Subscription roots; preserve that figure as a historical baseline only. The latest proven Dashboard browser journey was product draft → typed attributes → variant → edit → publish → GraphQL read-back, run 39351624876. This proves that a first Spring-backed slice works; it does not prove the full Dashboard/schema target. The latest root-signature inventory and its exact limitation are recorded above and in the generated artifact linked from [run 40479098193](https://github.com/waelhe/app-java-v3/actions/runs/40479098193).

No claim is made that the full-schema operations, all phases or full Dashboard operation inventory are implemented. This plan and the CI guard establish one canonical source of truth and automatically measure future progress. The companion Dashboard weekly contract-drift monitor is **active**: `marketplace-admin#2` merged on 2026-10-20, and its latest successful scheduled build is [run 40328802992](https://github.com/waelhe/marketplace-admin/actions/runs/40328802992). That run proves the pinned Dashboard build and accepted-contract generation path worked on the measured SHA; it does not prove complete Saleor resolver or E2E coverage. As of 2026-10-28, the pins remain Saleor `3.23.43` and Dashboard `3.23.39`; [issue #739](https://github.com/waelhe/app-java-v3/issues/739) tracks the newer official releases `3.23.44` and `3.23.41`. Pins must remain unchanged until a reviewed PR reruns the required inventory, generation, compatibility and retained-route E2E gates.
