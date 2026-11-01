# Saleor Commerce Domain Knowledge Map

**Status:** A0 campaign knowledge index; companion to (not replacement for) the canonical execution plan.  
**Governing decision:** `D-SALEOR-FULL-001`  
**Saleor source pin:** `saleor/saleor@3.23.45`, schema blob `b8fa0dd809081f76dc2834bb5c286561e5783276`  
**Dashboard pin:** `saleor/saleor-dashboard@3.23.41`, commit `ae7697bc635c46392d2fca5f88a34970097679f7`  
**Current campaign baseline:** `34d37457df53790afc92decd68af1773e6ff8578` on `integration/saleor-full-commerce-20261021`; the initial map content was reconciled onto this baseline in the A0 branch merge commit `d8fdeca3199bab2967e443d87f1b57346f208946`. Re-read the live integration HEAD before each new work packet.

> This document maps knowledge, domain relationships, evidence sources and agent routing. It deliberately does **not** list the 458 roots, copy their status, or assign a parallel root disposition. The only root inventory is `docs/governance/campaign/saleor-458-root-inventory.csv`; the category/owner/acceptance matrix is `docs/governance/plans/saleor-commerce-domain-matrix.csv`; accepted wire differences belong in `docs/governance/plans/saleor-contract-divergences.csv`.

## 1. Authority and evidence hierarchy

Use the following order when deciding what a Saleor-shaped operation should mean:

1. `docs/governance/plans/saleor-source-pins.json` — immutable Saleor/Dashboard source versions and schema identity.
2. `docs/governance/plans/saleor-dashboard-full-commerce-canonical-plan.md` — sole execution and product-scope authority for D-SALEOR-FULL-001.
3. `docs/governance/campaign/frozen-contracts.md` — accepted internal contracts and domain boundaries.
4. `docs/governance/plans/saleor-contract-divergences.csv` plus approved ADRs — explicit differences and their approval state.
5. Official Saleor 3.23.45 source and tests — primary evidence for upstream behavior.
6. Official Dashboard 3.23.41 source and tests — evidence for retained administrative journeys and their exact operation use.
7. Official Saleor documentation — conceptual/API guidance; useful for discovering scenarios, but verify version-specific behavior against pinned source/tests.
8. Pinned Agent Skills and MCP outputs — specialist reference material only. They do not override schema, source, tests, platform contracts or owner decisions.
9. Current Spring platform code/tests — evidence of what our platform actually does, never a reason to silently redefine the upstream target.

Community discussions and issue reports are useful for discovering failure modes and operational trade-offs. They are not normative requirements; reproduce the scenario against pinned Saleor source/tests and decide whether it applies to this platform.

## 2. Official agent resources and safe use

### 2.1 Documentation MCP

Official endpoint: `https://docs.saleor.io/mcp`

The Documentation MCP is read-only. It searches/reads Saleor documentation and navigation; it does not connect to a Saleor instance, inspect customer/store data, or execute GraphQL. Use it when the agent runtime supports MCP. Otherwise use the official pages linked below.

### 2.2 GraphQL MCP

The Saleor GraphQL MCP is a **separate** tool from Documentation MCP. If used, generate/download its SDL input only from the immutable `saleor-source-pins.json` reference: Saleor 3.23.45. Never use `main`, a moving latest schema, or a running Saleor backend. MCP schema introspection is a contract discovery aid, not proof that our Spring implementation works.

### 2.3 Five official Agent Skills — choose by task, do not install indiscriminately

The official AI page currently documents these five skills:

| Skill | Appropriate use in this campaign | Boundary |
|---|---|---|
| `saleor-core` | Backend discount precedence, order-discount allocation and stock-availability internals | Only these three rule families are covered. It is not a complete implementation guide for taxes, gift cards or channels. |
| `saleor-storefront` | Consumer GraphQL data patterns, products/variants, checkout and channel-aware storefront behavior | Storefront consumer reference; not the authority for backend domain ownership or the admin Dashboard. |
| `saleor-app` | App protocol, permission scopes, payment integrations and webhook behavior when an actual external app/provider is in scope | Does not imply that the platform should create a Saleor app registry, AppBridge runtime or second integration authority. |
| `saleor-configurator` | Read-only reference for configuration modeling, diff and environment promotion concepts | Never apply its configuration to this platform or provision a second Saleor shop. |
| `storefront-builder` | Buyer-facing storefront structure, product/collection data contracts and UX | Does not build or replace the pinned administration Dashboard. |

Repository policy: do not run `npx skills add`. The active integration-branch pin at this baseline remains `saleor/agent-skills@fb1a57255f58950962f6e474ccba1cade40f5163` from merged PR #771. PR #773 proposes the separate provenance migration to `saleor/saleor-docs@2cdf6590e838929c3f74c16e982cfdc11073c2d8`; it is still open and not merged, so do not describe the new pin as active until its review/merge. The vendored `saleor-core` and active pin are governed by `docs/governance/plans/agent-skills-pins.json`. Any provenance change requires a reviewed PR with hashes, license/attribution and governance tests. The other four skills remain NOT_ADOPTED applicability references.

## 3. Commerce domain knowledge and dependency map

The matrix below is a **domain navigation and dependency map**, not a root inventory. Exact operations, owner assignments and statuses must be read from the central CSV and category matrix.

**Pinned Dashboard rule:** every retained Dashboard 3.23.41 route, workbench, menu item, control and workflow stays in scope. A backend capability gap creates an implementation obligation and blocks P7; it never authorizes hiding, disabling, removing or suppressing the UI surface, or replacing it with fake data/no-op behavior. Root-level non-applicability is handled only through an owner-approved divergence, not by deleting the retained route.

| Domain | Official knowledge entry points | Platform owner anchors to verify in code | Important dependencies / routing | Skill routing |
|---|---|---|---|---|
| Identity, users, permissions and auth | API Users/Authentication; pinned auth and permission tests | `marketplace-identity`, `marketplace-console`, OIDC/PKCE Dashboard adapter | Every administrative and customer mutation; provider/resource scope; channel-scoped staff permissions | `saleor-app` only for actual app permission protocol |
| Products, variants and catalog | API Products; Products overview; product/variant source and tests | `marketplace-catalog`, `marketplace-search`, `marketplace-media` | Attributes, publication, price lists, channels, stock, promotions, tax classes, media | `saleor-storefront`; `saleor-core` stock rules only |
| Attributes and product types | API Attributes; Modeling | `marketplace-catalog` | Product classification, variant options, tax-class assignment, search/filtering | `saleor-storefront` |
| Pricing, discounts and vouchers | Developer Discounts; pinned `saleor/discount` and `saleor/order` tests | `marketplace-pricing`; frozen price components owned by `marketplace-orders` | Currency, channel availability, tax base, checkout/order snapshots, gift lines | `saleor-core` discount precedence/allocation |
| Gift cards / stored value | Developer Gift Cards; Gift Cards API; `saleor/giftcard`, gift-card payment and checkout tests | Owner **pending D-GC-001**; evaluate `marketplace-ledger`, `marketplace-payments`, `marketplace-orders`, `marketplace-pricing` and a dedicated bounded owner | Liability, issue/sell, redemption, currency, checkout completion, transactions, refunds, balance concurrency, audit and customer assignment | No dedicated `saleor-core` rule; use docs MCP + pinned source/tests. `saleor-app` only for real external payment/app integration |
| Taxes | Developer Taxes; Taxes API; `saleor/tax`, `saleor/graphql/tax`, checkout/order tax tests | `marketplace-pricing` owns the current per-country `TaxRate`; tax-class/configuration scope awaits D-TAX-001 | Country/address, channel, product/type/shipping class, inclusive/exclusive prices, shipping tax, exact currency rounding, order snapshots | No dedicated core skill; use official docs/source/tests |
| Commerce channels | Developer Channels overview/configuration/lifecycle; `saleor/channel` and cross-domain channel tests | Owner **pending D-CHANNEL-001**; dependencies on catalog, pricing, delivery, payments and orders; the previous D-WHSE-001 deferral is superseded for the full-commerce target, with A2 owning location/stock and A3 owning channel membership/order through public SPIs | Single-currency sales context, publication, price, tax, promotion, availability, shipping, checkout/order scope and permissions | `saleor-storefront` for channel-aware consumption; `saleor-configurator` read-only concepts only |
| Shop and commercial settings | Shop API reference; `saleor/graphql/shop` tests | Owner **pending D-SHOP-001**; assess `marketplace-console` without treating generic `SystemSettings` as the shop domain | Global shop identity vs channel settings vs tax strategy vs gift/refund/return settings; secret redaction and audit | `saleor-configurator` as design reference only |
| Checkout | Developer Checkout; checkout lifecycle and transaction docs/tests | `marketplace-orders`, `marketplace-pricing`, `marketplace-payments` | Channel, catalog, price/tax snapshots, gift-card authorization, shipping, inventory and idempotent order creation | `saleor-storefront`; `saleor-core` only relevant discount/stock rules |
| Orders and fulfilment lifecycle | Developer Orders; order calculations, fulfilment, refund and return tests | `marketplace-orders`, `marketplace-delivery`, `marketplace-ledger`, `marketplace-payments` | Frozen agreement, seller split, promotion/tax/shipping attribution, partial fulfilment, cancellation and refunds | `saleor-core` order-discount allocation; other behavior from pinned source/tests |
| Payments and transactions | Developer Payments/Transactions; transaction API source/tests | `marketplace-payments`, `marketplace-ledger` | Checkout and order state, gift-card stored-value payment, PSP callbacks, capture/refund, idempotency and reconciliation | `saleor-app` for provider/app protocol only |
| Shipping and delivery | Developer Shipping; shipping methods/zones and checkout tests | `marketplace-delivery`, `marketplace-orders` | Channel eligibility, tax class, address, quote freezing, seller split, dispatch and returns | `saleor-storefront` for consumer shipping choice patterns |
| Stock and warehouse availability | Developer Stock; `saleor/warehouse`, availability and stock-event tests | Current stock owner in `marketplace-catalog`; the earlier D-WHSE-001 platform-stage deferral is superseded for the full-commerce target; A2/A3/A4 must define and implement location-aware stock, warehouse membership/order, reservation/allocation and fulfilment through public SPIs | Product/variant purchasability, channel scope, reservation, delivery and click-and-collect | `saleor-core` stock-availability rule; verify all claims against pinned source/tests |
| Pages and navigation | Pages/Menu API and pinned Dashboard management screens | Current CMS/navigation services over declared SPIs in `marketplace-console` | Permissions, publication, page content, route navigation and platform workbenches | No required Saleor skill; use pinned Dashboard/source/tests |
| Apps and webhooks | Apps/Webhooks docs; app protocol and webhook delivery source/tests | `marketplace-console`, Modulith events, owning payments/domain event producers and delivery infrastructure | External extensions, permissions/scopes, signature validation, retries, replay, audit and outbox | `saleor-app` when actual app/webhook protocol semantics apply |
| Subscriptions / live events | GraphQL subscriptions and pinned event publisher tests | Owning domain publishers plus Spring for GraphQL transport | Authorization, tenant scoping, event order, duplicate delivery, reconnect and backpressure | No generic skill replaces source/tests |
| Exports, jobs and reporting | Exporting docs and exact export API tests | `marketplace-jobs` plus relevant owner read models | Snapshot consistency, authorization, file delivery, retention and PII | `saleor-app` only where external integration protocol is involved |
| Platform-native verticals/extensions | Platform product charter and the owning module plans | `marketplace-booking`, `marketplace-realestate`, `marketplace-community`, `marketplace-jobs`, `marketplace-institutions`, `marketplace-provider`, etc. | Preserve native concepts: bookable capacity, property, community membership, job and service fulfillment; do not force them into retail SKU/warehouse semantics | Saleor skills are optional reference only; platform plan and native owner remain authoritative |

### 3.1 Cross-domain journeys are the system-level proof

Domain tests remain necessary but are not sufficient. Design end-to-end journeys across the owner boundaries, including:

1. **Regional/commercial channel:** channel context → product publication and variant price → currency → tax method/class → voucher/promotion eligibility → shipping and payment conditions → checkout → frozen order → order access scope.
2. **Gift-card stored value:** issue or purchase → secure delivery → optional customer restriction → checkout authorization → committed order charge → ledger liability reduction → partial use/top-up/refund/cancellation behavior and audit.
3. **Discount and tax agreement:** competing discount rules → line/order calculation → tax base and inclusive/exclusive calculation → currency-aware rounding → immutable order price components → read-back and refund reconciliation.
4. **Inventory and delivery:** published product/variant → stock reservation → channel/destination eligibility → delivery quote → order split → fulfilment/cancel/return.
5. **Administrative governance:** authenticated staff → permission-scoped Dashboard route → mutation → persisted state/read-back → audit/event and safe error/loading/empty states.

Each journey must run against the platform's actual owning use cases and PostgreSQL state when persistence/transactions are part of the claim. Do not add an upstream Saleor runtime as a test dependency.

## 4. Required evidence chain for every claimed capability

Every report should be traceable through this chain:

`Saleor 3.23.45 source symbol + upstream test`  
→ `Dashboard 3.23.41 route/operation (if retained)`  
→ `central root reference + domain matrix category`  
→ `platform owner module/service/SPI/entity/repository`  
→ `approved decision or divergence`  
→ `exact JUnit test class/method + Maven lifecycle`  
→ `Surefire/Failsafe XML + counts + candidate SHA`  
→ `Semantic Evidence Gate result`.

Keep evidence dimensions separate: wire signature/type closure, resolver binding, real business semantics, persistence/read-back, authorization/resource scope, transaction/concurrency/idempotency, events/integrations, Dashboard journey, and operations/recovery.

A root or domain is not complete because a resolver exists, SDL compiles, a generic test passes, a skill is installed, or a Dashboard menu item renders. The report must show what was proved and what remains unknown.

## 5. Status and decision discipline

- Read root status only from `saleor-458-root-inventory.csv`. Do not copy root rows/status into this map or invent a second progress count.
- Domain map entries describe knowledge and relationships, not implementation status.
- `INTEGRATED` is not semantic proof. A0 alone updates root ledger statuses after checking reports and test evidence.
- A `SUPPORTED_ADAPTATION` is not exact Saleor parity.
- `BLOCKED_BY_DECISION` remains blocked until the owner-approved decision and any required ADR are committed.
- A `NOT_STARTED` feature is a visible gap, not a reason to create a fake success resolver or a test for nonexistent code.
- Skills are references, not authorities; verify every skill rule against the exact pinned source/test and record upstream errata to A0.
- No second Saleor backend, database or duplicated commerce engine is permitted.

## 6. Official source entry points

- Agents / Documentation MCP / Skills / GraphQL MCP: https://docs.saleor.io/developer/ai
- Documentation MCP endpoint: https://docs.saleor.io/mcp
- API reference generated from Saleor 3.23.45: https://docs.saleor.io/api-reference
- Pinned backend tag: https://github.com/saleor/saleor/tree/3.23.45
- Pinned Dashboard tag: https://github.com/saleor/saleor-dashboard/tree/3.23.41
- Official skill discovery: https://docs.saleor.io/.well-known/agent-skills/index.json
- Official channels: https://docs.saleor.io/developer/channels/overview
- Official taxes: https://docs.saleor.io/developer/taxes
- Official gift cards: https://docs.saleor.io/developer/gift-cards

This map should be updated only when a new verified domain relationship, owner, source pin, skill applicability or evidence route is established. Do not update it merely because a task was opened or a root status changed.
