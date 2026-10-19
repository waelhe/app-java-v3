# Social, Commerce/Business, and Tourism Platform — Saleor Alignment Matrix

**Status:** Current-state alignment and implementation-entry document. This is not a claim of full Saleor parity and does not authorize a production merge by itself.  
**Baseline inspected:** `waelhe/app-java-v3`, `main`, 19 October 2026.  
**Canonical platform scope:** one integrated social, commerce/business, and tourism platform.  
**Behavioral reference:** Saleor's official product, channel, checkout, transaction, and marketplace documentation.  
**Implementation authority:** existing Spring Boot / Spring Modulith ownership boundaries, accepted ADRs, migrations, actual REST/OpenAPI and GraphQL contracts, and executable tests.

## 1. Product scope and architecture decision

The platform is not a retail store with community features attached. It is an integrated platform with three mutually connected product areas:

1. **Social and local community:** neighborhood membership, posts, questions, recommendations, reports, events, official information, and trust/moderation.
2. **Commerce and business:** physical and used goods, merchant/provider offers, business profiles and branches, local services, orders, pricing, payments, fulfillment, reviews, and delivery.
3. **Tourism and travel:** destinations, accommodation, tours and activities, bookable experiences, operators/hosts, schedules and capacity, traveler requirements, reservations, cancellation/rescheduling, payments, and fulfillment/transfer as applicable.

These areas share identity, provider/ownership, geography, discovery, media, trust, reviews where policy permits, payments, notifications, and administrative operation. They do **not** share one universal domain entity or one universal lifecycle.

**Architecture rule:** Saleor is the behavioral benchmark for commerce capabilities and a source of mature administration UI patterns. Spring remains the platform's domain authority. Do not copy Saleor's Django implementation or create a second commerce engine. Do not force tourism bookings, property, jobs, community posts, business identity, or unique second-hand items into a retail SKU/warehouse schema.

A community post may reference a product, business, event, destination, or bookable offer in its source domain. Discovery and admin screens may compose these references; the source records and their lifecycle remain owned by their existing domains.

## 2. What “Saleor-compatible” means for this platform

Compatibility is evaluated at three distinct levels:

- **Behavioral capability:** the platform implements equivalent business behavior where that behavior belongs in our product.
- **Data/domain model:** the relevant concepts and invariants are modeled explicitly in the existing domain owner, with additive migrations and one source of truth.
- **Dashboard contract:** the specific Saleor Dashboard work surface is adapted to our real API/auth/permission contracts. A similar database shape alone does not make Dashboard compatible.

We will not implement a Saleor-shaped GraphQL compatibility layer wholesale. For every adopted Dashboard screen, inventory its actual queries, mutations, permission assumptions, and lifecycle expectations; then support only the real operations required by the approved platform workflow. Existing REST/OpenAPI commands remain valid where they are the appropriate contract. GraphQL read aggregation or commands are used only where the current DGS schema and security model support the real use case.

## 3. Current capability matrix

Status vocabulary: **IMPLEMENTED** = source/contract and relevant tests are evidenced; **PARTIAL** = useful behavior exists but the Saleor-level work surface or journey remains incomplete; **DEFERRED BY ADR** = consciously postponed with a trigger; **GAP / AUDIT REQUIRED** = not yet proven by this current-state review; **SEPARATE DOMAIN** = must not be collapsed into retail commerce.

| Capability | Current evidence on the inspected baseline | Current status | Next decision or work |
|---|---|---|---|
| Product identity and lifecycle | Existing `marketplace-catalog` Product model and seller ownership | IMPLEMENTED, bounded | Preserve the existing identity owner and lifecycle; do not replace it with a parallel table |
| Purchasable variants and SKU | #613 / ADR-0012, V219; variant price/stock/reservation model | IMPLEMENTED, PARTIAL | Variant options are still represented by a free-form name rather than a typed option/combination model |
| Cart consumption of variants | #616, V222; order/cart lines can refer to a variant while legacy zero-variant products remain valid | IMPLEMENTED | Preserve all-or-nothing cart placement and zero-variant backwards compatibility |
| Product types and typed attributes | Existing category attribute dictionary supports TEXT, NUMBER, BOOLEAN; it is not a separate Saleor-style ProductType/variant-attribute schema | PARTIAL — highest product-editor blocker | Define ProductType separately from navigation Category, with typed product attributes and typed variant options for retail goods |
| Categories versus collections | Current category dictionary is flat; ADR-0012 records hierarchy and collections as deferred | DEFERRED BY ADR; product scope needs re-evaluation | Keep taxonomy distinct from cross-category curated/merchandising collections. Tourism and local business discovery create credible use cases for curated seasonal/area collections |
| Sales channels | ADR-0012 records a single current sales context; geography/access control is owned elsewhere | DEFERRED BY ADR | Do not equate persona, neighborhood, tourism mode, or social feed with a Saleor Channel. Add a channel only when a real second commercial context has separately governed publication, price/currency, tax, payment or availability rules |
| Single-location stock and atomic reservation | Existing conditional stock reserve/release/commit; variant reservation added in V219/V222 | IMPLEMENTED for current model | Keep the concurrency proof and all-or-nothing placement invariant |
| Multiple warehouses, per-location stock, allocation and transfers | No complete per-variant/per-location stock model is evidenced in the reviewed catalog model; ADR-0012 records a future model and trigger | DEFERRED BY ADR | Reopen when a provider truly operates multiple stock locations. Tourism capacity/availability belongs to booking/availability, not warehouse quantity |
| Commercial pricing rules | #624 / V223 adds price lists, order-level promotion policy, vouchers, tax-rate policy, and frozen monetary components | IMPLEMENTED, PARTIAL versus full Saleor | Current promotion selection is deliberately limited (best eligible promotion, no general stacking engine). Record policy parity precisely; do not claim every Saleor discount mode |
| Cart/checkout and mixed-seller order split | ADR-0010 and the orders contracts preserve all-or-nothing stock reservation and split one cart into per-seller orders | IMPLEMENTED core, composite journey still requires continuous proof | Test failures across all seller groups, currency, stock, voucher, fulfillment, payment and notifications without partial order creation |
| Immutable order monetary agreement | #624 freezes monetary components at placement; existing order-line snapshot and repricing patterns | IMPLEMENTED core | Keep historical order totals stable when current prices, rules, tax, or delivery policy change |
| Seller/operator order work surface | #607 exposes seller-scoped order reads and confirm/fulfill/cancel with read-back | IMPLEMENTED core | Verify authorization, audit, unknown outcomes, and full operational UI journeys against current contracts |
| Checkout delivery / fulfillment legs | #604 and #628 add delivery paths, checkout choice/fee freezing and multi-leg constraints; #630 adds delivery operations | IMPLEMENTED core, PARTIAL ecosystem parity | External carrier integration and broader operator workflow remain separate adapter/operational increments |
| Returns and exchanges | #629, V230 implements a separate RMA lifecycle and connects completion to the payment/stock owners | IMPLEMENTED core | Continue validating partial returns, seller policy, stock disposition, PSP ambiguity and ledger reconciliation end-to-end |
| Payments and provider webhooks | Existing payment-intent/refund and webhook/idempotency ownership; #615 freezes commission rate on the intent | IMPLEMENTED core, PARTIAL marketplace settlement | Do not add another PSP engine. Verify payout eligibility, seller balances, disputes, reconciliation and actual payout execution separately |
| Seller/provider ownership and delegated operation | Existing provider ownership/claim/delegation work (including migrations through V233/V234 in the inspected project map) | IMPLEMENTED foundation; workflow coverage must be remeasured | Keep entity ownership, human delegation, geography, and action permission distinct; server-side scope checks are authoritative |
| Product merchandising, search and review tooling | Search/review foundations exist; no evidence here of a full Saleor-depth collection/merchandising operator workbench | PARTIAL / AUDIT REQUIRED | Make collection membership and search promotion auditable, measurable, and backed by real owner APIs |
| Business directory | Provider/business profiles, claims, branches/services and review capabilities are owned by existing provider/review domains | SEPARATE DOMAIN with shared discovery | A business profile is not a product; products and services may reference the provider without requiring purchase to enter the business profile |
| Tourism, travel and experiences | Provider, booking, availability, geography and payment foundations exist; this review does not yet prove a complete destination-to-booking-to-fulfillment tourism journey | FIRST-CLASS PRODUCT AREA; E2E AUDIT REQUIRED | Model destination/experience/itinerary and bookable inventory, guest capacity, schedules, cancellation, provider fulfillment and transfer in the owning tourism/booking domains. Reuse commerce pricing/payment/order patterns only where semantics match |
| Social/community and offers | Community content and local contexts are separate from catalog records; cross-domain links can be composed through owned identifiers/projections | SEPARATE DOMAIN with cross-links | Preserve post/report/member state machines; never duplicate a commerce/tourism source record to make it appear in social discovery |

The implementation evidence above is based on repository PR/source summaries and the current project map, not a fresh full Maven run or production replay in this document. Before each code slice, re-read the exact current source, Flyway migrations, OpenAPI/GraphQL contracts, tests, and open PRs. A closed PR is evidence of repository state, not proof that every production environment has the same migration and configuration.

## 4. Architectural rules that govern every increment

1. **One owner per capability.** Catalog owns goods/product identity and variants; orders own checkout/order state; pricing owns commercial pricing rules; payments own payment intents and refunds; ledger owns financial postings; delivery owns delivery operations; booking/availability own time/capacity commitments; provider owns provider/business identity; community owns posts, membership and moderation; specialized vertical domains own property, jobs and other unique listing lifecycles.
2. **No universal SKU model.** Variants apply to goods with genuine selectable options. A unique second-hand item, a property, a job, a tour reservation, and an appointment must not get fake variants merely to fit Dashboard.
3. **No false channel model.** Persona-led experiences (resident, tourist, expatriate, business operator) are not automatically commercial channels. A channel has meaning only when a distinct sales context changes commercial behavior.
4. **No inventory shortcut.** Stock, reserved stock, allocated stock, booking capacity and time-slot availability are different resources and need separate invariants.
5. **No payment duplication.** Orders call the payment owner through explicit ports/contracts; they do not contain PSP state machines or write ledger balances directly.
6. **No frontend authority.** Dashboard permission gates improve UX, but the backend validates actor + action + resource ownership + geographic/provider scope for every command.
7. **No false success.** Consequential commands require a source-owned read-back/operation result, audit evidence, and safe handling for timeout/unknown outcomes; retry must not duplicate money, stock, bookings, or publication.
8. **No broad-bang migrations.** Schema changes are additive Flyway migrations, preserve Envers audit parity for audited entities, pass checksum/governance guards, and keep prior product records behavior-compatible.
9. **Contracts are generated from the platform.** GraphQL client types derive from the approved platform SDL/operations; REST clients derive from the published OpenAPI contract where applicable. No production build fetches Saleor schemas or generates fake Saleor-shaped domain types.
10. **Tourism remains first-class.** Tourism experiences may be discoverable beside products and business services, but bookable schedules/capacity, guest composition, cancellation windows, host/operator responsibilities and travel fulfillment must remain explicit.

## 5. First implementation slice

### Slice C1 — Typed product types and variant options for goods

This is the first model gap that blocks a genuinely useful Saleor-derived product editor. It is deliberately narrower than implementing every deferred Saleor capability.

**Why now:** V219/V222 provide purchasable variants and cart integration, but ADR-0012 explicitly leaves variant option vocabulary free-form and the existing category-attribute dictionary is not a full product-type schema. The Dashboard product editor needs typed fields and validated option combinations; category membership alone cannot define a product's data shape.

**Target owner:** existing `marketplace-catalog`; do not add a second commerce module.

**Required model behavior:**

- Add a first-class ProductType definition separate from StoreCategory/navigation taxonomy.
- Define typed attributes with explicit product-level versus variant-level ownership, required/optional behavior, supported scalar/choice values, and validation. Only implement types/constraints actually needed by the initial goods workflow; keep the model extensible without claiming arbitrary dynamic schemas.
- Define selectable variant options and allowed values; validate variant combinations under one product, reject duplicate combinations, and preserve provider-scoped SKU uniqueness.
- Keep the existing free-form variant name as a compatible display field while clients migrate to typed option values.
- Keep existing zero-variant products sellable with the same price/stock behavior. Do not synthesize default variants or mass-convert live records.
- Keep category-based browsing/filters distinct from ProductType/attribute semantics. A type may be assigned independently from the navigation category.
- Expose source-owned secured contracts suitable for the administration client; choose REST or GraphQL per the current schema/security/contract evidence instead of inventing an extra transport just for Dashboard.
- Add additive Flyway migrations, Envers mirrors for audited entities, API compatibility assertions, owner/scope and duplicate-combination tests, and a real catalog → variant → cart → order journey proving the selected option and frozen price survive placement.

**Out of scope for C1:** channels, category hierarchy, collections, multi-warehouse inventory, generic promotion stacking, a copied Saleor GraphQL schema, tourism/booking variants, and frontend rewrites. These remain separate decisions/slices.

**Acceptance gate:** a seller can configure a typed goods product type, create a valid product and distinct selectable variants, receive honest validation for invalid/duplicate combinations and foreign ownership, add a chosen variant to cart, place an order with the correct authoritative price, and read the frozen variant/price back—all through the real API and integration suite. Existing products without variants continue to pass unchanged.

## 6. Following slices, ordered by dependency and product value

1. **C1 — Product types and variant options** (above).
2. **C2 — Curated collections and typed cross-domain collection references.** Re-evaluate the ADR-0012 deferral in light of social discovery, local-business campaigns, destinations and seasonal tourism collections. Keep collection merchandising separate from taxonomy and avoid copying source records. Specify ownership, eligible target types, ordering, scheduling, publication scope and audit before schema work.
3. **C3 — Platform/admin product API contract and Dashboard slice.** Pin a Saleor Dashboard source SHA, inventory the exact product routes/operations, and adapt the product list/detail/editor to C1's real contracts. Prove create/edit/publish/discovery in Playwright against the platform backend, not mock data.
4. **C4 — Sales-context decision.** Reopen channels only if a real second commercial context requires different prices/currencies, tax/payment conditions, publication or availability. Social/tourism experiences alone do not prove this.
5. **C5 — Multi-location stock only on a confirmed use case.** Implement location-owned stock, guarded transfers, fulfillment allocation, reconciliation and competing-checkout concurrency tests if providers have multiple real stock locations. Model tourism capacity separately.
6. **C6 — Cross-domain tourism journeys.** Audit and prove destination/experience discovery → schedule/capacity selection → booking/guest data → price/terms freeze → payment → provider/transfer fulfillment → cancellation/reschedule/refund/review. Reuse owned booking/payment/delivery contracts; do not turn a trip into an ordinary SKU unless the purchased thing truly is a standard good.
7. **C7 — Dashboard workbench parity.** Orders, pricing, stock, providers, fulfillment/RMA, reconciliation, search/merchandising, audit and permissions, only when the corresponding real backend capability and E2E acceptance test exist.

## 7. Source references

- Saleor Product Overview: https://docs.saleor.io/developer/products/overview
- Saleor Channels: https://docs.saleor.io/developer/channels/overview
- Saleor Checkout: https://docs.saleor.io/developer/checkout/overview
- Saleor Transactions: https://docs.saleor.io/developer/payments/overview
- Saleor Marketplace Recipe: https://docs.saleor.io/recipes/marketplace
- Saleor Modeling: https://docs.saleor.io/developer/modeling
- Spring Modulith verification: https://docs.spring.io/spring-modulith/reference/verification.html
- Spring Modulith events/publication registry: https://docs.spring.io/spring-modulith/reference/events.html
- Repository study: `docs/research/saleor-commerce-backend-fit-gap.md`
- Merchandise ADR: `docs/adr/ADR-0012-merchandise-variant-model.md`
- Multi-seller checkout ADR: `docs/adr/ADR-0010-multi-seller-cart-split.md`
- Financial allocation ADR: `docs/adr/ADR-0013-financial-allocation-decisions.md`
- Platform product charter: `docs/governance/plans/platform-admin-operating-system-product-charter.md`
- Dashboard adaptation plan: `docs/governance/plans/saleor-dashboard-marketplace-adaptation-plan.md`

## 8. Evidence and limitations

This document establishes product scope and a current-state implementation order. It does not claim:
- full Saleor behavioral parity;
- an implemented Dashboard fork or live Dashboard/Spring connection;
- that every tourism journey is already implemented or E2E-proven;
- that a currently deferred channel/warehouse model is automatically required;
- that tests or a production smoke run were executed while authoring this matrix.

The first code change must re-truth-sync the exact current `main`, review concurrent PRs and run the repository's prescribed Maven/contract gates before any release. No merge or production deployment is implied by this document.
