# Saleor Schema Parity Plan — Same Contract, Spring-Owned Implementation

**Status:** Measured schema study and staged parity roadmap. Not an implementation-complete claim, and not an authorization to mass-generate resolvers. Each wave ships only against approved journeys and passes the repository's contract gates.
**Study date:** 2026-10-20.
**Pinned Saleor reference:** [saleor/saleor](https://github.com/saleor/saleor) release tag `3.23.43` (published 2026-10-19), file `saleor/graphql/schema.graphql`, 1,101,635 bytes, fetched at that immutable tag. Every count in §1 was extracted programmatically from that file with a docstring-aware parser; nothing in this section is estimated by eye.
**Our measured baseline:** `waelhe/app-java-v3` `main` @ `418e37ea` (2026-10-20T02:08Z). Published GraphQL SDL on branch `graphql-contract`: 27,105 bytes, 23 object types, 5 inputs, 12 enums, 12 Query roots, 6 Mutation roots, 0 subscriptions. REST surface: 335 paths / 412 OpenAPI operations (measured 2026-10-20T01:30Z, recorded in SYSTEM.md §15).
**Governing authority:** Spring Boot, Spring for GraphQL, Spring Data, Spring Modulith reference documentation; official Netflix DGS documentation; the pinned Saleor schema as the contract reference. Per repository governance, internal documents that conflict with this plan's schema-scope statements are superseded by this document (§9), and the official framework references above remain the absolute authority for every design decision herein.

## 1. What the Saleor 3.23.43 schema actually is (measured)

Root-level entry points: **90 Query fields + 342 Mutation fields + 26 Subscription events = 458**.
Type inventory: **914 object types, 321 input objects, 214 enums, 9 interfaces, 16 scalars**.
Interfaces: `Node`, `ObjectWithMetadata`, `ObjectWithAttributes`, `AssignedAttribute`, `Job`, `PaymentMethodDetails`, `PromotionEventInterface`, `PromotionRuleEventInterface`, `Event`.
Scalars: `Metadata`, `DateTime`, `JSON`, `JSONString`, `Decimal`, `PositiveDecimal`, `Date`, `UUID`, `WeightScalar`, `Upload`, `PositiveInt`, `Minute`, `Day`, `Hour`, `GenericScalar`, `_Any`.

Root fields by the schema's own `@doc(category)` grouping (Query/Mutation):

| Category | Query | Mutation | Total | Our owner module today |
|---|---:|---:|---:|---|
| Products | 15 | 63 | 78 | `marketplace-catalog` (+ `media`, `search`) |
| Users | 10 | 43 | 53 | `identity` (+ `console`) |
| Orders | 7 | 45 | 52 | `marketplace-orders` |
| Discounts | 6 | 27 | 33 | `marketplace-pricing` |
| Checkout | 3 | 20 | 23 | `marketplace-orders` |
| Apps | 5 | 16 | 21 | extension surface = Modulith modules/events (§5) |
| Gift cards | 5 | 16 | 21 | none — owner decision required |
| Payments | 4 | 17 | 21 | `marketplace-payments` (+ `ledger`) |
| Attributes | 2 | 15 | 17 | `marketplace-catalog` (typed attributes shipped) |
| Pages | 4 | 14 | 18 | out of product scope (§5) |
| Menu | 4 | 11 | 15 | out of product scope (§5) |
| Shipping | 2 | 13 | 15 | `marketplace-delivery` |
| Taxes | 7 | 7 | 14 | `marketplace-pricing` (tax policy shipped) |
| Channels | 2 | 6 | 8 | deferred by ADR-0012 decision 4 (§5) |
| Authentication | 0 | 9 | 9 | `identity` — OAuth2/OIDC owned (ADAPT) |
| Shop | 2 | 7 | 9 | platform configuration owners |
| Webhooks | 3 | 6 | 9 | event externalization surface (§5) |
| (uncategorized) | 9 | 7 | 16 | federation `_entities`/`_service`, `plugin(s)`, `shop`, `translation(s)`, `exportFile(s)` |

Domain types confirmed present in the schema (all checked by exact `^type X` match): `ProductType`, `ProductVariant`, `Category`, `Collection`, `Channel`, `Warehouse`, `Stock`, `Allocation`, `Order`, `OrderLine`, `Fulfillment`, `Checkout`, `CheckoutLine`, `Payment`, `Transaction`, `TransactionEvent`, `GiftCard`, `Voucher`, `Promotion`, `Permission`, `Group`, `App`, `Webhook`, `Page`, `Menu`, `MenuItem`, `ShippingZone`, `ShippingMethod`, `TaxClass`, `TaxConfiguration`, `ProductMedia`, `Invoice`, `ExportFile`, `EventDelivery`, `Plugin`, `RefreshToken`.

Subscription events (26 measured): 16 order lifecycle events (`orderCreated`, `orderConfirmed`, `orderPaid`, `orderFullyPaid`, `orderRefunded`, `orderFullyRefunded`, `orderFulfilled`, `orderCancelled`, `orderExpired`, draft-order variants, metadata updates, bulk-created), 6 checkout events, 4 variant stock events (out-of-stock / back-in-stock, including click-and-collect variants).

## 2. What we are today (measured)

GraphQL: 12 Query roots (`discovery`, `neighborhoodFeed`, `post`, `postComments`, `service`, `services`, `storeProduct`, `storeProductAttributeValues`, `storeProductTypes`, `storeProductVariantAttributeValues`, `storeProductVariants`, `storeProducts`) and 6 Mutation roots (`createService`, `createStoreProduct`, `createStoreProductVariant`, `publishStoreProduct`, `replaceStoreProductAttributes`, `updateStoreProduct`). Zero subscriptions. One typed goods-catalog vertical slice is proven end-to-end through a real browser journey (create → typed attributes → variant → edit → publish → GraphQL read-back), measured twice against the shared production channel.

REST: 335 paths / 412 operations, including the catalog admin REST surface (`/api/v1/admin/store/categories`, `/api/v1/admin/store/product-types`), cart/checkout/orders, delivery legs, RMA, payments/webhooks, and pricing policies.

Platform: 24 Modulith modules (23 domain + composition root), boundaries enforced by `ArchitectureRulesTest` and Modulith verification in CI. Money crosses the wire as decimal-string minor units. Cursor pagination uses Spring's encoded `ScrollPosition` over an offset-backed `PageRequest` (bounded and deterministic; not keyset — the limitation is declared, not hidden). The GraphQL endpoint is protected (anonymous 401). Contract lifecycle gates are operational: backend GraphQL compatibility check, accepted-SDL publication to `graphql-contract`, the Android contract gate, and the independent Dashboard's immutable-SHA synchronization with fail-closed operation validation.

**Honest coverage ratio:** 18 of 458 Saleor root entry points (≈3.9%) have a counterpart in our published SDL — all inside the Products category plus service/social roots that Saleor does not have. Parity, therefore, is a staged program (§6), not a status we hold today.

## 3. What "the same schema under our framework" means

The owner's goal — the same schema, managed by our framework — is defined here as **contract parity over implementation copy**:

1. **The Saleor schema is the reference contract.** For every domain we adopt, field names, argument shapes, pagination semantics, and lifecycle vocabulary follow Saleor's schema unless a divergence is explicitly registered in §5. Renaming operations to "our style" without cause is not allowed; divergences require a documented reason.
2. **The implementation is 100% ours and 100% Spring.** Spring Boot owns application startup, auto-configuration, and environment handling (Spring Boot reference: Features › Externalized Configuration; auto-configuration). Spring for GraphQL owns execution and transport, including WebSocket/SSE for subscriptions and the official pagination support (`ScrollPosition`, `@QueryMapping`-equivalent DGS layer below). Netflix DGS remains the single GraphQL programming model (`@DgsComponent`/`@DgsQuery`/`@DgsMutation`/`@InputArgument`), guarded by `ArchitectureRulesTest` — per the DGS documentation's own guidance to not mix DGS with the Spring annotated-resolver model. Maven owns code generation through the pinned community adapter (documented provenance exception; Netflix ships only a Gradle CodeGen plugin), with isolated output and clean-tree reproducibility. Spring Modulith owns boundaries: resolvers live in the `marketplace-app` composition root and delegate to module SPIs/use cases; domain modules never depend on application GraphQL code; cross-domain reads go through SPIs or committed events, never direct repository access.
3. **Django/Graphene internals are never copied.** No Saleor Python code, model layout, or resolver organization is ported. Their design decisions (for example, per-mutation error unions, permission-name gating, `Node` opaque global IDs) are evaluated per domain in §4/§5 and adopted at the contract level only when they fit a Spring Modulith system.
4. **Business rules stay in owning use cases.** GraphQL is a journey-aware read/composition graph plus selected commands that delegate to authoritative services — the rule already enforced in the current codebase and repeated by DGS guidance (thin data fetchers, services own logic).

## 4. Domain gap matrix — verdict per Saleor category

Verdicts: **ADOPT** (build the Saleor-shaped contract), **ADAPT** (same capability, different shape for a documented platform reason), **DEFER** (confirmed missing, waiting on its recorded trigger), **N/A** (not applicable to this platform, with reason). "Current state" cites only measured facts.

| Saleor domain | Verdict | Current measured state | Notes |
|---|---|---|---|
| Products — core identity, variants, ProductType, attributes | **ADOPT (partial: shipped)** | `storeProduct(s)`, `storeProductTypes`, typed attribute values, variant create/read/publish shipped and E2E-proven | Remaining: filters/sort/search on lists, product media, reorder/bulk operations, report queries |
| Products — categories | **ADOPT** | REST `POST/GET /api/v1/admin/store/categories` exists (#673 store-categories dictionary surface); no GraphQL root yet | `categories(category:)`/`category(id:)` shape; tree depth per platform navigation decision |
| Products — collections | **DEFER** | none | ADR-0012 decision 5 defers collections with two named triggers (navigation depth; promotion wave); C3 in the alignment matrix |
| Products — media | **ADOPT** | `media` module owns S3 storage today (REST) | `productMediaCreate/Update/Delete/Reorder` mapped to media module; multipart upload via DGS file-upload support |
| Products — warehouses/stock/allocation | **DEFER** | single-location inventory shipped (variants reserve/release/commit) | ADR-0012 decision 6: multi-location deferred with its model fully pre-specified (transfer, allocation, no-oversell proof) |
| Checkout | **ADAPT** | cart/checkout/order REST shipped incl. frozen pricing, seller-split, variant consumption | Expose journey-aware `checkout*` roots delegating to the same use cases; Saleor's `checkoutCreateFromOrder`/token flows reviewed per journey, not copied blindly |
| Orders | **ADOPT (read-first)** | merchant order surface, delivery legs, RMA lifecycle shipped via REST + composite journeys | `order(s)`, `orderByToken`, fulfill/cancel/confirm commands map to existing owners; draft orders are N/A (no draft-order product concept) |
| Payments | **ADAPT** | single payment engine (intents, PSP channels, webhooks, settlement, ledger) exists | Expose `transaction*` read surfaces and refund commands through existing contracts; Saleor's `Payment`/`Transaction` dual model reviewed — our engine already has one source of truth |
| Discounts/promotions/vouchers | **ADOPT (read-first)** | price lists, promotions, vouchers, taxes shipped (V223) with frozen-at-placement semantics | Admin read/query roots first; stacking/precedence widening stays an explicit pricing-policy decision (alignment C6) |
| Gift cards | **DEFER (owner decision)** | none | Real commercial capability; needs owner product decision before any schema work |
| Taxes | **ADOPT (partial)** | country policy, INCLUSIVE/EXCLUSIVE, bps shipped | Read surface exists in domain; GraphQL roots for tax configuration follow pricing wave |
| Shipping | **ADAPT** | delivery legs, fees frozen at placement, carve-share assignment shipped | `shippingZone`/`shippingMethod` shapes map to delivery module contracts; multi-warehouse shipping needs the deferred inventory model |
| Users/permissions/staff | **ADAPT** | identity + console modules; OAuth2/OIDC owned; provider/CONSUMER profiles; one-live provider rule (V244) | `me`/`user(s)`/permission-group queries adapt to our RBAC; Saleor's `tokenCreate` password flow is N/A — we own OIDC with PKCE (§5) |
| Authentication | **ADAPT** | OIDC code+PKCE + consent shipped and E2E-proven | No Saleor-shaped auth mutations; client/secret flows stay on the OIDC contract |
| Apps / Plugins / Webhooks (21+9+9 roots) | **N/A → separate surface** | Modulith events + archive + resubmission + Kafka externalization dormant behind `@Externalized` | Saleor's app/plugin marketplace model is not our extension model. Webhook *delivery* surface (if a partner needs it) = event externalization, an owner decision |
| Pages / Menu (18+15 roots) | **N/A** | none | CMS surfaces are outside the platform's product scope |
| Translations | **N/A (deliberate)** | bilingual domain fields (`nameEn`/`nameAr`) are first-class in the domain model | Our i18n is native bilingual domain data, not a translation overlay table per Saleor's `*Translate` mutations |
| Shop settings | **ADAPT** | platform configuration owners exist | Only fields that map to real configuration surfaces; no fake settings toggles |
| ExportFile/bulk jobs | **DEFER** | `jobs` module exists | Bulk export operations need job-model + pagination decisions first |
| Federation (`_entities`/`_service`) | **N/A** | standalone server; no gateway composition | DGS federation support exists if composition is ever chosen; today it would be dead schema surface |

## 5. Deliberate divergences — registered, not accidental

These stay divergent from Saleor and must be documented in the SDL itself (descriptions) so no future wave "fixes" them into accidental parity:

1. **Identifiers:** UUID domain identifiers everywhere. Saleor's `Node`/opaque global-ID indirection is not adopted; our clients already consume UUIDs in REST and GraphQL. Rationale: one identity system across transports; no dual-ID translation layer.
2. **Money:** decimal-string minor units (`priceMinor: "12345"`), exact by construction. Saleor's `Decimal`/`PositiveDecimal` scalar approach is not adopted; GraphQL `Int` is signed 32-bit and `Float` is inexact — the platform's existing money contract rule.
3. **Errors:** platform error contract with stable `extensions.code` semantics per mutation family; Saleor's per-mutation error-union shapes are consulted but the platform contract governs.
4. **Authentication surface:** OIDC (code + PKCE, consent) is the only interactive login flow. Saleor's `tokenCreate`/`refreshToken` mutation family is not portable to an OAuth2/OIDC owner.
5. **Internationalization:** bilingual fields owned by the domain (`nameEn`, `nameAr`) rather than Saleor's per-language `Translation` overlay objects.
6. **Extension model:** Spring Modulith modules + domain events (+ Kafka externalization when a consumer exists) instead of Saleor Apps/Plugins/webhook subscriptions as the integration model.
7. **Channels:** deferred by ADR-0012 decision 4 — a second, independently-governed sales context is the recorded trigger; geography/access control dimensions already have owners.
8. **Pagination:** cursor connections with Spring's `ScrollPosition` cursor encoding. The current offset-backed implementation is declared (not keyset); wave P0 decides per Spring Data keyset documentation whether to upgrade to keyset windows for stable concurrent-change guarantees before any large list surface ships.

## 6. Staged execution waves

Rules that govern **every** wave, derived from the framework's official documentation and the repository's operational gates:

- **SDL-first:** schema lives under `marketplace-app/src/main/resources/graphql/**`; Maven codegen regenerates types; compilation is the first contract check; generated source is never hand-edited or committed.
- **DGS-only resolvers in the composition root**, delegating to module SPIs/use cases; `ArchitectureRulesTest` + Modulith verification stay green; no domain module gains an application-GraphQL dependency.
- **Contract gates:** backend GraphQL compatibility check and accepted-SDL publication to `graphql-contract` in CI; downstream clients (Android gate, the independent Dashboard's immutable-SHA sync) regenerate against the published artifact and must stay green — a schema change that breaks a client is a wave failure, not a client problem.
- **Per-field security:** every new field/mutation carries an authorization decision and a negative test; anonymous access stays 401 until a public-read policy is explicitly decided per journey.
- **Journey proof:** each wave closes with a real E2E journey (browser or authenticated API composite) against the platform backend — compilation, green CI, and a UI screen are not acceptance (§12 rule in the adaptation plan lineage, unchanged).
- **REST coexistence:** GraphQL is not a mirror of REST; both transports invoke the same use cases. Webhooks/uploads/partner HTTP contracts stay REST where HTTP is the better fit.

**Wave P0 — pagination and list-query hygiene (prerequisite).** Decide and, where required by list parity, implement keyset pagination using Spring Data's `Window`/`KeysetScrollPosition` support (Spring Data reference › pagination-and-sorting / Spring for GraphQL pagination support) for `storeProducts`/`services` (and every new list root). Add the sorting/filtering argument vocabulary adopted from Saleor's list inputs. Close the declared "offset-backed cursor" limitation either by upgrading to keyset or by keeping the declared bound for admin rosters. **Gate:** pagination invariance test under concurrent insert/delete; compatibility gate green; downstream regeneration green.

**Wave P1 — catalog read parity.** `categories`/`category` roots (owner: catalog; the store-categories dictionary surface is already shipped in REST), product list filters/sort/search delegation to the `search` module, product media roots mapped to the `media` module with multipart upload through DGS file-upload support. **Gate:** Dashboard regenerates and renders category navigation through generated operations; Playwright journey for category browsing + media upload; negative tests for foreign-owner access.

**Wave P2 — checkout and order journey parity.** Expose the existing cart/checkout/order use cases (frozen pricing, seller split, variant consumption, delivery fee freeze) as journey-shaped `checkout*`/`order*` roots; command mutations delegate to the same use cases that REST calls. `orderByToken`-style tracking read only if the owner confirms an unauthenticated tracking journey (security decision, not a default). **Gate:** authenticated composite E2E journey cart → checkout → order → fulfillment state read-back via GraphQL only; idempotency and duplicate-command tests. Seller-side order management is a P2 sub-wave required to close this gate: `mySoldOrders`, `soldOrder`, `confirmSoldOrder`, `fulfillSoldOrder`, and `cancelSoldOrder` delegate through `SellerOrderManagementPort` to the existing `OrdersService` and delivery lifecycle; resolvers must not own a second state machine. Cancellation/refund remains event-driven through the existing payment owner. This sub-wave does not count as P3 user/permission parity.

**Wave P3 — users and permissions parity.** `me`, `user(s)`, permission-group queries adapted to the platform RBAC through the `identity`/`console` owners. **Gate:** per-role negative authorization tests; no staff-permission leakage into provider-scoped fields.

**Wave P4 — discounts, vouchers, taxes read surfaces.** Read/admin query roots over the shipped V223 policy engine; precedence/stacking widening remains an explicit pricing-policy decision. **Gate:** policy evaluation read-back matches frozen order breakdown; no rule duplication in resolvers.

**Wave P5 — delivery/shipping surfaces.** `shipping*` roots over the delivery module's existing contracts; anything requiring multi-location inventory stays deferred to its ADR-0012 trigger.

**Wave P6 — transport capabilities.** Subscriptions over Spring for GraphQL's WebSocket transport (official `spring-boot-starter-websocket` / graphql-ws support) for a minimal, per-journey event set (start with order state + variant out-of-stock events — 26 Saleor events exist; adopting a subset is a product decision, not a schema gap). Bulk/export job surfaces only after the jobs model decision. **Gate:** subscription authorization model tested; no anonymous subscription streams.

**Wave P7 — parity audit and verdict.** Re-run this study's measurement script against the then-published SDL; produce the measured coverage table (roots by category), the divergence registry status, and the honest claim statement. Only measured deltas are reportable.

Each wave is one or more reviewable PRs; none authorizes a wholesale rewrite, and no wave starts without its governing journey being approved. No wave merges without the three merge gates (CI green on the final head, review threads resolved, clean mergeable state) — a merge to `main` is a production deployment.

## 7. Official and pinned references

- Spring Boot reference: https://docs.spring.io/spring-boot/index.html
- Spring for GraphQL reference (execution, transport, pagination, subscriptions): https://docs.spring.io/spring-graphql/reference/
- Spring Data pagination/keyset: https://docs.spring.io/spring-data/commons/reference/repositories.html (pagination-and-sorting; `Window`, `KeysetScrollPosition`)
- Spring Modulith reference (boundaries, events, verification): https://docs.spring.io/spring-modulith/reference/
- Netflix DGS documentation (programming model, file uploads, federation statement): https://netflix.github.io/dgs/
- GraphQL Cursor Connections specification: https://relay.dev/graphql/connections.htm
- GraphQL multipart request specification (uploads): https://github.com/jaydenseric/graphql-multipart-request-spec
- Saleor pinned schema: https://github.com/saleor/saleor/blob/3.23.43/saleor/graphql/schema.graphql
- Saleor API documentation: https://docs.saleor.io/developer/api-reference
- Repository governance chain: `AGENTS.md` §0, `SYSTEM.md` §5/§14, `docs/api/graphql-target-architecture.md` (framework ownership sections), ADR-0012 (merchandise model decisions 4–6).

## 8. Non-goals and risks

- No copying of Saleor's Python/Django/Graphene implementation, project layout, or test structure.
- No one-shot "generate 458 roots" implementation pass; schema growth without journey proof is explicitly rejected (a schema row is not an acceptance result).
- No second commerce engine: every wave delegates to the existing owning module.
- No anonymous/public-read policy change smuggled inside a parity wave.
- Risks: (a) list-heavy parity pressure could outpace keyset maturity — mitigated by P0 ordering; (b) Saleor's per-category mutation breadth (63 Products mutations) tempts bulk-operation scope — bulk stays deferred behind job/export decisions; (c) subscription transport adds operational surface — minimal event set, per-journey authorization; (d) schema drift from the pinned reference — P7 re-measurement keeps the claim honest, and the pin moves only by re-running this study.

## 9. Relationship to prior plans

This plan extends the #664 alignment-matrix lineage (C0–C2 closed; C3–C7 open) by fixing the schema-parity scope against a pinned, programmatically measured Saleor release. Where older internal documents state a narrower or wider schema scope (including the pre-DGS baseline document, which is historical evidence by its own header), this document governs going forward. Known stale rows in the current plan set — the "Dashboard lifecycle proof" row in `docs/api/graphql-target-architecture.md` §2.3 that predates the measured passing runs (39351624876 pre-merge on the identical tree; 39359198594 on `main`) — are superseded by SYSTEM.md §15's measured log and should be corrected by the next truth-sync wave, not by this research document.
