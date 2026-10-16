# Saleor Commerce Backend Study — Fit/Gap for app-java-v3

**Status:** Research and implementation-planning evidence only. This document does not supersede accepted ADRs, the official-compliance policy, or the platform-administration product charter. No production code, schema, or dependencies are changed by this study.  
**Inspection baseline:** `waelhe/app-java-v3`, main at `d3c873d3595f5c7a7b9f6104dfb7bb5477b2fb9b` (truth-sync commit dated 2026-10-16); research performed 2026-10-17.  
**Functional reference:** Saleor's official documentation and source.  
**Technical implementation authority:** the project's pinned Spring / Spring Modulith / Spring Data official documentation and current repository constraints.

## 1. Decision in brief

Study Saleor's backend deeply and use it as a **behavioral and domain-maturity reference** for the commerce part of the platform. Do not copy its Django models, repository layout, or application code into a new large `marketplace` module.

The target is to implement the useful commerce behaviors in the existing Spring Modulith boundaries and to add only the capabilities confirmed missing after a source and contract audit. In particular:

1. Keep a single source of truth for each capability. The accepted ownership boundary remains: `marketplace-catalog` owns store product identity and its current inventory model; `marketplace-orders` owns cart, checkout, and order state; `marketplace-payments` owns the payment-intent/refund lifecycle. Do not introduce a second commerce engine. See [ADR-0002](../adr/ADR-0002-commerce-fit-gap.md).
2. Use [ADR-0010](../adr/ADR-0010-multi-seller-cart-split.md), not ADR-0002's now-superseded mixed-seller deferral, as the current rule: a mixed-seller cart is placed atomically and split into one order per seller; whole-cart stock reservation is all-or-nothing.
3. Preserve separate domain models for retail goods, unique second-hand listings, property, vehicles, jobs, and bookable services. Saleor's retail-product model is not a universal schema for every marketplace vertical.
4. Fill the demonstrated Saleor-level commerce gaps through small, reviewable vertical slices. Do not infer implementation completion from a route, table, UI screen, or passing general CI alone.

## 2. Sources and method

The Saleor source and its official documentation were inspected for product/variant, checkout, order, stock, discounts, payment transactions, channel, and marketplace concepts. The current backend inspection focused on the source of truth and executable contracts in the existing catalog, orders, pricing, payments, provider, ledger, disputes, bookings, and administrative specifications.

### Saleor's relevant official references

- [Product Overview](https://docs.saleor.io/developer/products/overview) — product vs. variant, product types/attributes, SKU, per-channel price/availability, warehouse assignment, categories vs. collections.
- [Channels](https://docs.saleor.io/developer/channels/overview) — channel context can affect visibility, pricing, availability, taxes, discounts, checkout, payments, and access to orders.
- [Checkout](https://docs.saleor.io/developer/checkout/overview) — checkout contains the data and operations needed to move from cart activity to order creation; completion may process payment and reserve stock.
- [Transactions](https://docs.saleor.io/developer/payments/overview) — payment operations, external gateways, refunds, and custom payment flows.
- [Marketplace Recipe](https://docs.saleor.io/recipes/marketplace) — vendor modeling, access control, seller-specific fulfillment, shipping, marketplace fees/payouts, taxes, and operator/vendor portals.
- [Saleor product models](https://github.com/saleor/saleor/blob/main/saleor/product/models.py), [checkout models](https://github.com/saleor/saleor/blob/main/saleor/checkout/models.py), [order models](https://github.com/saleor/saleor/blob/main/saleor/order/models.py), [warehouse models](https://github.com/saleor/saleor/blob/main/saleor/warehouse/models.py), [discount models](https://github.com/saleor/saleor/blob/main/saleor/discount/models.py), and [payment models](https://github.com/saleor/saleor/blob/main/saleor/payment/models.py).

### Spring implementation references

- [Spring Modulith — Application Module Verification](https://docs.spring.io/spring-modulith/reference/verification.html): modules must be acyclic and interact through exposed APIs; explicit allowed dependencies can be enforced.
- [Spring Modulith — Application Events and Publication Registry](https://docs.spring.io/spring-modulith/reference/events.html): use events to decouple interested modules and use the publication registry/recovery mechanisms according to the required delivery semantics.
- [Spring Data JPA — Modifying Queries](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html): conditional write operations can be expressed as modifying queries where that is the correct persistence operation.
- The existing repo policy remains binding: [AGENTS.md](../../AGENTS.md), [official compliance plan](../official-compliance-plan.md), and [architecture](../architecture/ARCHITECTURE.md). These references govern how capabilities are implemented; Saleor is a functional benchmark, not a replacement framework.

**Evidence limitation:** this is source/document inspection, not a live API contract test or a fresh full local Maven verification. “Not evidenced” below means that no matching implementation was found in the inspected source, module declarations, and repository code search. Before any code change, truth-sync against current main, the actual schema/migrations, API contract, and tests again.

## 3. What is worth learning from Saleor

### 3.1 Product identity is not a variant

Saleor separates the product's descriptive identity from purchasable variants. A product type defines structured attributes; a variant represents a purchasable option/SKU and carries its own price, availability, stock behavior, and warehouse assignment. The model deliberately supports different variants being available or priced differently.

**Application:** preserve this distinction where a product genuinely has purchasable options (size, color, bundle, package, etc.). Do not create fake variants for unique items such as a particular used sofa or a particular property. The current store `Product` does not yet express Saleor's full product/variant hierarchy.

### 3.2 Categories and collections solve different problems

Saleor categories are hierarchical taxonomy; collections are flexible merchandising sets that can group products across a category hierarchy.

**Application:** the existing category dictionary is useful for classification and filtering. It does not by itself supply collection campaigns such as “new arrivals”, “featured in this neighborhood”, or “seasonal picks”. Keep taxonomy and editorial/merchandising membership distinct rather than treating every display row as a category.

### 3.3 Channel is business context, not just a UI filter

In Saleor, a channel may change a product's publication, variant availability, price/currency, tax and promotion eligibility, checkout context, and order access. It is a coherent business context with consequences throughout the transaction.

**Application:** the platform's country/city/neighborhood scope, store, provider, audience, currency, and fulfillment area must not be equated automatically with one Saleor channel. First decide which dimensions are actual pricing/sales contexts and which are geographic visibility or authorization scopes. A neighborhood feed filter alone is not a full channel implementation.

### 3.4 Checkout and order are distinct lifecycle stages

Saleor exposes a checkout object for cart-like editing and completion. The order then becomes the durable agreement: lines, quantities, monetary breakdown, and fulfillment/payment states must remain interpretable even if catalog values subsequently change.

**Application:** keep authoritative re-pricing at placement and frozen order-line snapshots. Extend the frozen agreement only when new pricing components are introduced: discount/tax/shipping amounts, currency, relevant rule references, and calculation/version data where required. Do not mutate an already-placed order by silently re-reading a current product price.

### 3.5 Inventory is more than a quantity field

Saleor distinguishes SKU-level stock, warehouse assignment, allocations, and reservation behavior. Reservation protects a quantity during checkout; allocation/fulfillment describes which stock is committed to the order and how it is fulfilled.

**Application:** the existing atomic reservation mechanism is a sound baseline for one product stock record. It is not yet a model for multiple stock locations, transfers, location selection, separate shipments, or a full stock-movement history.

### 3.6 Payment state, order state, and seller settlement are separate concerns

Saleor tracks transaction operations and payment state, while marketplace payout and vendor-specific shipping/tax workflows require marketplace-specific logic or integrations.

**Application:** do not put gateway state transitions inside the order aggregate or duplicate Stripe logic in the order module. Use the existing payment-intent/refund machinery and ledger boundaries. Marketplace commissions, vendor balances, and payout policy need explicit rules; they must not be inferred from a successful customer charge.

### 3.7 Multi-vendor access is not automatic

Saleor's own marketplace recipe says: “In Saleor, vendors are not a built-in entity type.” It also states that Saleor Dashboard cannot itself provide vendor-specific management access. Seller ownership, seller-scoped operations, vendor-specific fulfillment, shipping, taxes, and payouts require application-level design and enforcement.

**Application:** Saleor is an excellent commerce reference, but copying the default Saleor schema alone would still not deliver the platform's multi-provider marketplace. Server-side resource ownership and operational scope checks remain mandatory for every seller command.

## 4. Current app-java-v3 baseline

The following are observed in source at the inspected baseline. They describe existing capability; they do not claim every path has been exhaustively re-tested in this study.

| Area | Observed implementation | Evidence |
|---|---|---|
| Store product | `Product` has store category, title, description, one authoritative `priceMinor`/currency, provider owner, stock counters, and ACTIVE/SUSPENDED/ARCHIVED lifecycle. There is no SKU or variant member on this entity. | [Product.java](../../marketplace-catalog/src/main/java/com/marketplace/catalog/Product.java), [V184 migration](../../marketplace-app/src/main/resources/db/migration/V184__commerce_inventory_checkout.sql) |
| Category attributes | Registry of per-category attribute definitions with `TEXT`, `NUMBER`, and `BOOLEAN` value types. This is useful dynamic metadata, but not a full product-type/variant-option/SKU system. | [CategoryAttribute.java](../../marketplace-catalog/src/main/java/com/marketplace/catalog/CategoryAttribute.java) |
| Inventory | Atomic conditional reserve/release/commit operations; reservation is held in counters on the product record and rejects unavailable stock. No warehouse/stock-location aggregate is evidenced in the inspected catalog model. | [ProductRepository.java](../../marketplace-catalog/src/main/java/com/marketplace/catalog/ProductRepository.java), [ProductInventoryService.java](../../marketplace-catalog/src/main/java/com/marketplace/catalog/ProductInventoryService.java) |
| Cart and line snapshot | Active cart and cart items exist; the caller-supplied amount is ignored on the authoritative write path. Placement re-prices from the store product and freezes price/quantity/currency in order lines. | [Cart.java](../../marketplace-orders/src/main/java/com/marketplace/orders/Cart.java), [CartItem.java](../../marketplace-orders/src/main/java/com/marketplace/orders/CartItem.java), [OrderItem.java](../../marketplace-orders/src/main/java/com/marketplace/orders/OrderItem.java), [OrdersService.java](../../marketplace-orders/src/main/java/com/marketplace/orders/OrdersService.java) |
| Order state machine | `PLACED → CONFIRMED → FULFILLED`, with cancellation from open states; terminal guards and audited transitions exist. Existing state machine is narrower than a full commercial order/fulfillment/return lifecycle. | [OrderStatus.java](../../marketplace-orders/src/main/java/com/marketplace/orders/OrderStatus.java), [OrdersService.java](../../marketplace-orders/src/main/java/com/marketplace/orders/OrdersService.java) |
| Mixed-seller cart | Current accepted ADR splits a mixed-seller cart into one order per seller, retains all-or-nothing stock reservation for the whole cart, and uses the same per-order contracts. ADR-0002's earlier “mixed seller = 409” deferral is superseded. | [ADR-0010](../adr/ADR-0010-multi-seller-cart-split.md) |
| Payment | Existing payment-intent lifecycle, Stripe PSP channel, webhook processing, idempotency, cancellation/refund paths, and order association exist. Avoid a second payment engine. | [PaymentsService.java](../../marketplace-payments/src/main/java/com/marketplace/payments/PaymentsService.java), [PaymentIntent.java](../../marketplace-payments/src/main/java/com/marketplace/payments/PaymentIntent.java), [ADR-0002](../adr/ADR-0002-commerce-fit-gap.md) |
| Merchant order commands | The orders service has confirm/fulfill machinery, but `OrdersController` explicitly documents that the merchant-side HTTP surface has not been exposed yet. This is a clear contract gap for a functioning merchant/admin workspace. | [OrdersController.java](../../marketplace-orders/src/main/java/com/marketplace/orders/OrdersController.java) |
| Pricing | `marketplace-pricing` currently implements listing/calendar/weekend/seasonal pricing for bookable listings. It is not, by itself, a Saleor-like store price-list, channel price, promotion, and voucher engine. | [PricingService.java](../../marketplace-pricing/src/main/java/com/marketplace/pricing/PricingService.java) |
| Surrounding domains | Separate modules already exist for provider, ledger, disputes, reviews, bookings, availability, real estate, jobs, geo, community, search, notifications, and discovery. Keep their responsibilities rather than moving all records into a retail-product table. | [Root module list](../../pom.xml), [architecture](../architecture/ARCHITECTURE.md) |
| Journey evidence | A real-chain order journey integration test exercises authentication, PostgreSQL/Flyway, Redis, order transitions, Modulith event delivery, and privacy behavior. It is useful existing assurance, but it does not prove all Saleor-level workflows are covered. | [OrderJourneyIntegrationTest.java](../../marketplace-app/src/test/java/com/marketplace/orders/OrderJourneyIntegrationTest.java) |

The accepted product-administration specification already sets the required target breadth: [S20 — Market and Catalog Suite](../governance/plans/platform-admin-detailed-screen-specification.md) calls for products/listings, variants/options, product/listing types, categories/collections, inventory locations/reservations, channels/regions, pricing/price lists/fees, promotions/vouchers, order/checkout, service bookings, fulfillment/delivery/returns/refunds, customers/groups, providers/sellers, shipping/local delivery, taxes/payment methods, reviews/search/merchandising, reports/exports, staff permissions, and market settings. That specification is a product target, not evidence these backend contracts already exist.

## 5. Fit/gap matrix

Classifications:
- **Present:** the relevant model or contract is evidenced.
- **Partial:** a useful subset exists, but the target behavior is broader.
- **Not evidenced:** no equivalent implementation was found in this inspection; verify the full current source and deployed contract before implementation.
- **Separate domain:** should not be forced into the retail-product model.

| Capability benchmark | Current fit | Gap / next verification |
|---|---|---|
| Store product identity, owner and basic lifecycle | **Present** | Keep the existing product identity and state owner. |
| Authoritative base price and currency | **Present / narrow** | One base amount is not per-variant, channel, customer segment, or price-list pricing. |
| Product types and typed attribute definitions | **Partial** | Category attributes exist with three scalar types; validate requirements for required/optional fields, enums, reference values, searchable/filterable flags, localization, and variant-level options. |
| Product variants / options / SKU | **Not evidenced; explicitly deferred in ADR-0002** | A product-variant model, unique SKU/slug rules, option combinations, variant lifecycle, and price/stock ownership need an explicit domain decision and migrations. Do not retrofit variant assumptions to unique listings. |
| Hierarchical categories | **Present in a limited form** | Confirm parent-child hierarchy, localized labels, safe taxonomy moves, redirects, and impact on current listing/store categories. |
| Merchandising collections | **Not evidenced** | Distinguish curated collections from the category hierarchy; support ordering, membership, publication windows, and impact preview if adopted. |
| Channel-scoped publication, price and availability | **Not evidenced as a coherent sales-channel model** | Define actual sales contexts first; keep country/city/neighborhood authorization and discovery scope separate unless product rules prove they are the same dimension. |
| Single-record stock reservation | **Present** | Preserve atomic conditional reservation and whole-placement rollback. Extend only with measured needs. |
| Multi-location warehouses, stock movements and allocation | **Not evidenced in the inspected catalog model** | Define locations, available/reserved/allocated quantities, transfers, adjustment reasons, fulfillment assignment, reconciliation, and concurrency tests. |
| Cart, re-pricing and immutable order lines | **Present** | When adding totals, record an auditable monetary breakdown and stable order-time agreement; add contract tests for price changes between add-to-cart and placement. |
| Multi-seller placement | **Present at order-splitting level** | Verify seller-scoped reading/actions, partial cancellation/refund policy across sibling orders, seller-level fulfillment and notification behavior; do not undo ADR-0010's all-or-nothing placement. |
| Seller confirmation / fulfillment APIs for the operational UI | **Partial** | Service operations exist; controller documentation says merchant HTTP surface is not exposed. Add owned, secured commands and outcome reads before wiring an admin button. |
| Shipping methods, local delivery areas, shipping estimates/tracking | **Not evidenced** | Separate vendor shipping from platform/local-delivery policy; snapshot the buyer-agreed charge and selected method in the order. |
| Taxes / tax classes / jurisdictional calculation | **Not evidenced** | Establish the product/legal requirements first; do not imply tax compliance from currency or price fields. Tax calculation must be explicit and frozen at order placement. |
| Catalog discounts, promotions, vouchers/coupons and stacking rules | **Not evidenced as a store-commerce rule engine** | Keep paid ad campaigns distinct from purchase discounts. Define eligibility, minimum spend, duration, limits, scope, concurrency, and stacking/exclusion behavior before implementing a rule engine. |
| Payment processing / refund primitive | **Present** | Reuse existing payment intents, webhook handling, idempotency, and refund contract; test one-effect behavior for retries and ambiguous outcomes. |
| Marketplace commissions, seller payout and financial reconciliation UX | **Partial / policy decision needed** | Existing ledger/payment/dispute primitives are not proof of a complete commission-and-payout product. The current commerce ADR explicitly does not introduce a commission rate. Decide the business policy before designing payout logic. |
| Return / exchange / RMA lifecycle | **Not evidenced as an order-domain lifecycle** | Separate return authorization, received condition, restock decision, refund, and dispute. A payment refund alone does not model a returned item. |
| Merchant/staff permission groups and granular market scope | **Partial** | Continue authoritative server-side ownership checks; define staff-to-store/provider relationships, roles, resource scope, and audit of consequential actions. |
| Service/property/job/vehicle/second-hand offers | **Separate domain** | Preserve specialized lifecycle/booking/location models; provide cross-domain catalog/search projections where needed instead of adding fake SKU, warehouse, or retail checkout fields to every listing. |
| Import/export, bulk edits, saved views, reporting | **Not evidenced as a complete commerce operator workflow** | Admin product target requires these workflows; define preview/dry-run, partial outcomes, safe retries, permission checks, and export retention before implementation. |

## 6. Recommended target boundaries

This is the recommended direction for the next design pass, not permission to add modules without a measured gap.

- **`marketplace-catalog`:** remains the owner of store-product identity and its currently accepted stock/lifecycle model. Investigate product types, variants/options, SKU identity, merchandising membership, and location-aware stock before deciding whether an existing owner or a new one is justified.
- **`marketplace-orders`:** owns cart, checkout, order snapshots, and order lifecycle. Expose seller/administrator actions through owned application services and secured API contracts; preserve the ADR-0010 split and per-order invariants.
- **`marketplace-payments`:** remains the only payment-intent, provider-webhook and refund owner. Do not create order-side PSP calls or a second transaction state machine.
- **`marketplace-ledger` / `marketplace-disputes`:** keep financial posting and dispute decisions in their owners; don't compute balances or silently refund by editing order rows.
- **`marketplace-provider`:** remains the provider/seller identity and verification anchor. Product ownership, store staff delegation, commission policy and seller portal behavior require explicit contracts; OIDC roles alone do not scope access to a particular seller's resources.
- **`marketplace-pricing`:** currently represents calendar/listing pricing. Do not label it a complete merchandise price-list/promotion engine. Reassess the domain boundary after the required price-list rules are known.
- **Service and vertical domains:** `booking`, `availability`, `realestate`, `jobs`, and relevant catalog listing paths keep their own state machines. Shared search and admin views may compose their read models, but must issue writes to the owning domain.

For cross-module integration, use explicit exposed APIs for synchronous invariants and Spring Modulith events for decoupled consequences where the business transaction does not require synchronous completion. An event does not replace a required synchronous stock/price check. Event consumers must remain recoverable and idempotent according to the configured publication semantics.

## 7. Execution sequence

### Phase 0 — Re-truth-sync before code

1. Confirm the current main SHA and open commerce-related PRs.
2. Re-read the current files, migrations, public REST/OpenAPI and GraphQL SDL/resolvers that touch catalog, orders, pricing, payment, provider, ledger, and admin routes.
3. Record every feature as **implemented and tested**, **implemented but contract not exposed**, **partial**, **not evidenced**, **deferred by decision**, or **requires owner policy**.
4. Do not change source code during this pass.

### Phase 1 — Complete the operational commerce loop

Priority is driven by the operational UI gaps, not by copying all Saleor feature names at once:

1. Expose authorized seller/operator order detail and allowed confirm/fulfill/cancel commands, with post-command read-back and audit.
2. Add journey coverage for seller scope, split orders, price changes, payment success/failure, duplicate webhooks, cancellations/refunds, and stock consistency.
3. Decide whether local delivery and fulfillment tracking are needed for the first retail operating journey; represent chosen charges and method in the immutable order agreement.
4. Ensure each management command provides a stable error contract and a way for the UI to verify the final state.

### Phase 2 — Merchandise model depth

1. Specify product types, required attributes, variant options, SKU uniqueness, product/variant publication and price ownership.
2. Specify category hierarchy vs. collections.
3. Decide whether channels represent independently configurable sales contexts and which dimensions belong in geography/access control instead.
4. Add multi-location inventory only with a defined reservation/allocation/transfer model and tests that prove no oversell under concurrent placements.

### Phase 3 — Commercial rules and seller operations

1. Define price lists, promotions, vouchers, eligibility/stacking, taxes and shipping as explicit business policies.
2. Keep advertising spend and purchase discounts distinct.
3. Decide commission and payout policy before extending ledger workflows.
4. Model returns/exchanges separately from payment refunds and disputes.

### Phase 4 — Saleor-level acceptance

For each supported workflow, require a real end-to-end journey across authenticated API, ownership/scope checks, database migration, domain service, transaction/event behavior, outward API contract, operator outcome read-back, audit, and tests. At minimum demonstrate:

- Create/edit/publish a physical product with a real variant and SKU, where variants are enabled.
- Change category/collection without silent orphaning or invalid storefront visibility.
- Correct stock at a location and prove concurrent checkout cannot oversell.
- Configure an actual sales context and prove its price/availability rules are enforced by the server.
- Apply/reject a promotion at checkout with deterministic eligibility and an immutable resulting order breakdown.
- Place a mixed-seller cart and independently verify the resulting seller orders, payments, fulfillment, cancellation/refund policies, and notifications.
- Execute seller/operator confirmation, fulfillment, delivery, return/refund, and final financial reconciliation where each feature is enabled.
- Prove unauthorized sellers cannot read or mutate another seller's product, order, or settlement.
- Recover safely from a failed event/webhook/job without duplicate money movement or stock changes.

Required technical gates remain the repository's current gates: additive Flyway migration discipline, Envers audit parity for audited entities, Spring Modulith verification, source-owned APIs, OpenAPI/GraphQL compatibility gates as applicable, JaCoCo thresholds, and the integration suite. Run the current required Maven verification on affected modules before shipping implementation PRs.

## 8. Explicit non-goals and risks

- No copy of Saleor's Python/Django/Graphene implementation into Java.
- No new generic `marketplace` module that duplicates catalog, orders, payments, pricing, ledger, bookings, or listings.
- No assumption that an existing UI screen implies a supported API operation.
- No universal product/variant/warehouse schema for properties, jobs, services, or unique second-hand items.
- No new tax, commission, payout, payment, or refund rules without explicit business decisions.
- No merchant action that trusts client-side filtering instead of server-side ownership and scope enforcement.
- No production implementation is proposed by this research PR; the purpose is to establish an evidence-backed fit/gap map before selecting the first code slice.

## 9. Sources inside this repository

- [ADR-0002 — commerce fit/gap closure](../adr/ADR-0002-commerce-fit-gap.md)
- [ADR-0010 — mixed-seller cart split](../adr/ADR-0010-multi-seller-cart-split.md)
- [Platform Administration — Domain-Grade Administration Suites](../governance/plans/platform-admin-domain-grade-suites.md)
- [Platform Administration — Detailed Screen Specification, S20](../governance/plans/platform-admin-detailed-screen-specification.md)
- [Platform Administration — End-to-End Product and Gap Specification](../governance/plans/platform-admin-end-to-end-product-spec.md)
- [Backend architecture](../architecture/ARCHITECTURE.md)
- [Official compliance plan](../official-compliance-plan.md)
