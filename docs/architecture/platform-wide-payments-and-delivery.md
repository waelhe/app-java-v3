# Platform-Wide Payments and Delivery Network

**Status:** Target-state product and backend architecture proposal; not implementation evidence.  
**Repository baseline inspected:** `waelhe/app-java-v3`, main as inspected 2026-10-17.  
**Related work:** [Saleor Commerce Backend Fit-Gap Study (PR #602)](https://github.com/waelhe/app-java-v3/pull/602) and [Platform Administration Domain-Grade Suites](../governance/plans/platform-admin-domain-grade-suites.md).  
**Authority:** The platform owner defines product/commercial policies. Technical implementation follows `AGENTS.md`, `docs/official-compliance-plan.md`, the current source, and official Spring documentation. The official Saleor/Stripe/carrier references below are behavioral and integration references, not a mandate to copy their internal architecture.

**Detailed implementation gates:** the source-of-truth audit is in [Payment Sources and Ledger Audit](../research/platform-payment-source-and-ledger-audit.md); the proposed interface, state graph, and cross-module/payment contract are in [Delivery Domain Contract](./delivery-domain-contract.md). These documents separate verified existing behavior from target-state design and explicitly cover the existing partial-refund ledger gap.

## 1. Decision

Treat **payments as a platform-wide capability** and **delivery/shipping as a first-class service domain** with two modes:

1. **Direct delivery service:** a resident, member, business, institution, or other authorized customer requests a courier/package delivery as a standalone service, independently of a marketplace purchase.
2. **Marketplace fulfillment service:** a store/provider uses the platform's delivery network to fulfil a product order or an agreed service transaction. Delivery may be supplied by the platform's own operation, an approved external carrier/courier, the seller/provider, or collection/pickup where offered.

These are related but not identical product flows. Both use shared quote, payment, notification, provider-integration, tracking, audit, and operations capabilities while preserving their own domain state machines.

Do not implement a second payment engine beside `marketplace-payments`, a second accounting ledger beside `marketplace-ledger`, or a shipping workflow by simply adding unrelated status columns to products/orders. The inspected root Maven module list contains no dedicated delivery/shipping domain module. A dedicated `marketplace-delivery` bounded context is therefore a **candidate to evaluate and establish through the repository's architecture process**, not an already-existing capability or an automatic instruction to code before the source/gap pass.

## 2. Platform-wide payment system

### 2.1 Responsibilities and boundaries

The platform needs one coherent payment capability with consistent operations, idempotency, provider callbacks, currency handling, refunds, reconciliation, and financial evidence. This does **not** mean that every domain's business logic moves into `marketplace-payments`.

| Responsibility | Owner |
|---|---|
| Business amount, currency, eligibility, cancellation terms, and what payment unlocks | The domain that owns the payable business action |
| Payment intent, payment-provider adapter, provider transaction identifiers, callback verification/deduplication, collection/cancellation/refund execution | Existing `marketplace-payments` |
| Double-entry entries, balances and the accounting meaning of posted movements | Existing `marketplace-ledger` |
| Dispute case and resolution decision | Existing `marketplace-disputes` plus the business/finance authority required for the case |
| Seller/provider/driver eligibility and onboarding identity | Existing identity/provider ownership; a payout provider's verification requirements remain authoritative |
| Operator investigation, approval, audit, reporting and recovery UX | Platform Administration; mutations execute through source-owned commands |
| Domain lifecycle following a payment result | The originating domain, via explicit contracts/events—not the payment module inventing its business state |

Every domain defines a small payment-facing contract: resolve the authoritative payable amount and currency, payer, subject, expiry, and permitted settlement/cancellation behavior; accept a verified outcome; and expose its current business state for read-back. The payment module must never trust a caller-supplied amount or a client claim that payment succeeded.

A provider webhook or client callback is evidence to verify, not permission to mark a domain paid. Use the existing provider-verification, idempotency, and event machinery. Webhooks can be duplicated or delayed; the processing path must deduplicate them and must reconcile ambiguous outcomes before repeating any money-moving operation.

### 2.2 Existing payable origins and target coverage

The inspected `PaymentIntent` vocabulary currently includes `BOOKING`, `AD`, `ORDER`, and `LOAN`. These already demonstrate cross-domain reuse of the same payment engine. The platform target should cover the following **only when the product rules define a real chargeable obligation**:

| Platform journey | Payment treatment |
|---|---|
| Product purchase / one seller's order | Collect against the authoritative order total; retain the immutable order-time amount and currency snapshot |
| Appointment, reservation, or bookable service | Collect under the existing booking-origin flow; booking owner controls availability, cancellation/no-show, and business completion |
| Paid lending / item rental fee | Reuse the existing loan-origin payment path; lending owner controls approval, handover, return, late-fee eligibility, and closure |
| Provider advertising / promoted placement | Reuse the existing ad billing path; the ad/campaign owner defines billable periods, budget, and campaign state |
| Standalone delivery request | A delivery-origin payable when a customer must pay for the delivery service; delivery owns quote and cancellation eligibility, payment engine collects |
| Delivery attached to a marketplace order | Include the accepted delivery charge in the commercial amount snapshot or use an explicitly modelled separate payable if the business contract requires it. Never bill the same delivery fee twice |
| Seller/provider/driver/platform fees and payouts | Explicit financial allocations and settlement rules; do not silently infer commissions or payout amounts from gross payment |
| Refund, partial refund, chargeback, payment failure, or uncertain provider state | A single source-owned command and provider/ledger reconciliation path with recorded outcome |
| Subscription, membership fee, deposit, paid community feature, or other future monetization | A new payable source only after its product and legal rules, refund/cancellation policy, and owner are explicit |

The same member may pay for multiple domains; this does not mean a single checkout must combine unrelated obligations. Preserve one stable subject and currency per payment obligation unless a separately designed multi-obligation checkout proves all-or-nothing semantics, refund allocation, recipient allocation, and failure recovery.

### 2.3 Extensible payment-source contract

The current schema encodes origin-specific identifiers and database pairing constraints. Do **not** immediately replace this with a weak free-form string/id reference. During the implementation design, compare two safe paths:

- **Explicit origin extension:** keep the measured origin + subject-column + CHECK-constraint discipline and add a new origin through an additive migration and matching tests.
- **A stable payable-subject contract:** add a typed source SPI and a canonical subject reference only if the increasing set of origins justifies it. A polymorphic reference must include authenticated source resolution, authorization, existence checks, one-source ownership, and tests for orphan/mismatched references; it must not create cross-module table joins or direct writes.

Select one approach based on actual origin growth, referential-integrity needs, migration cost, and the official Modulith boundary rules. Never run both as parallel paths for the same origin. Each new source has exactly one owner and one payment integration path.

The payment-provider adapter contract should be provider-neutral. The source inspected contains a `PspChannel` abstraction and a Stripe implementation; that does not establish that multiple live gateways, payment methods, or payout networks are already configured. Provider selection must be based on enabled country/region, currency, payment method, merchant eligibility, risk/compliance rules, cost, and operational availability—not on hardcoded UI logic.

### 2.4 Money invariants

1. **Exact money:** preserve the repository's minor-unit integer convention. The current field name `amountCents` is potentially misleading for currencies whose minor unit is not a cent; any API/domain contract must define “minor units of the declared ISO-4217 currency” unambiguously and remain backward-compatible.
2. **Currency is mandatory business context:** every quote and payable snapshots its currency. A single charge/order cannot silently combine currencies; conversions require an explicit, timestamped rate and rounding policy.
3. **Idempotency at every side effect:** client retries, provider retries, domain events, refunds, transfers and payout requests need stable operation keys and a one-effect proof.
4. **Provider truth is not ledger truth:** a provider may authorize, capture, refund, or dispute an amount; the ledger records the accounting consequence. Reconciliation joins these states; it does not overwrite one with another.
5. **Refunds are not transfer reversals:** in marketplace payment models, a customer refund may not automatically reverse transfers to a seller or courier. The operational workflow must calculate and reconcile the platform/seller/driver consequence explicitly.
6. **Pay-in, allocation, settlement, and payout are separate stages:** do not mark a vendor or driver paid simply because a customer's charge succeeded.
7. **No implicit wallet/escrow:** stored-value balances, holding money on behalf of third parties, escrow-like models, cash collection, cash-on-delivery, tips, recurring off-session charges, and instant payouts have distinct business, legal, security, and reconciliation implications. Treat them as decisions, not automatic features of “platform-wide payments”.
8. **Do not store raw card data:** payment credentials and authentication remain with compliant payment methods/providers; retain only permitted provider references/tokens.
9. **Authorization is server-side:** a member, provider, seller, driver, support agent, finance operator, and approver have different rights to inspect/initiate/approve/execute financial actions. Scope and separation-of-duties checks happen on the server.

Official references: [Stripe PaymentIntents](https://docs.stripe.com/api/payment_intents), [Stripe webhooks / handling asynchronous outcomes](https://docs.stripe.com/payments/payment-intents), [Stripe Connect: separate charges and transfers](https://docs.stripe.com/connect/separate-charges-and-transfers), [Saleor Transactions Overview](https://docs.saleor.io/developer/payments/overview).

### 2.5 Financial allocation and provider settlement

A platform-wide marketplace must distinguish at least these financial roles: payer, business seller/provider, delivery company/courier, the platform, payment provider, and—where relevant—tax authority or refund recipient.

Before implementation, define for each transaction type:
- Who is legally/contractually charging the customer and who is the merchant of record.
- Which party bears payment processing fees, refunds, chargebacks, delivery failures, and negative balances.
- Whether the platform collects for itself or collects and transfers to one or more eligible recipients.
- How commission/service fees are calculated, versioned, disclosed, and posted.
- When seller/courier settlement becomes eligible, how holds/reserves and negative balances behave, and how failed transfers are retried safely.
- How partial cancellation/refund works when a mixed-seller cart produces multiple orders or several delivery legs.
- How each financial movement is reconciled against provider events and `marketplace-ledger`.

Stripe documents materially different liability and geography behavior for charge models; for example, with separate charges and transfers the platform balance bears Stripe fees, refunds and chargebacks, and refunding a charge does not itself reverse related transfers. Cross-border eligibility is constrained. Therefore this document does **not** select Stripe Connect, a transfer model, a commission, or a merchant-of-record policy. Those choices require target-country availability, legal/accounting review, and an explicit owner decision. See the [official separate charges and transfers reference](https://docs.stripe.com/connect/separate-charges-and-transfers).

## 3. Integrated delivery and shipping system

### 3.1 What the service is

Delivery is a first-class operational capability—not merely a shipping-rate dropdown on checkout. It needs a shared API and lifecycle that can be initiated independently or attached to a marketplace transaction.

The proposed bounded context (working name `marketplace-delivery`, subject to the required architecture/gap verification) owns delivery requests, quotes, delivery jobs, consignments/shipments, courier/provider assignment, tracking events, proof of pickup/delivery, delivery failure and return logistics, service areas and delivery-specific policies. It does not own store order totals, booking status, payment execution, or accounting entries.

### 3.2 Two product modes, one delivery core

**Mode A — Direct service requested by a user or business**

The user can request transportation of an allowed item/package/document from a pickup to a drop-off point without buying another product on the platform. The full journey is:

1. Select pickup and destination, sender/recipient contacts, pickup/drop-off instructions, package class, count, dimensions/weight where relevant, handling needs, and immediate or scheduled timing.
2. Validate serviceability and prohibited/restricted-item rules; obtain a priced quote, expected pickup/delivery window, assumptions, expiry time, and any surcharge before commitment.
3. Choose an available service/provider (platform-operated delivery or an enabled external carrier/courier), see the cancellation/refund terms, and confirm.
4. Collect or authorize payment using the configured mode, or accept an approved non-prepaid mode only where a policy explicitly enables it.
5. Create one idempotent delivery job; assign an in-house courier/dispatcher or request the external provider. On timeout, reconcile provider status before retrying creation.
6. Track accepted → assigned → arriving → picked up → in transit → delivered, or the appropriate failed/cancelled/returned recovery path. Exact transitions depend on delivery mode and must be explicitly guarded.
7. Capture proof of pickup and delivery where required (e.g. recipient confirmation, permitted photo/signature/barcode), keep an immutable event timeline, and show the verified result to sender and recipient according to access policy.
8. Handle missed pickup, recipient unavailable, address correction, delay, damage/loss claim, return-to-sender, cancellation fees, refunds, and support escalation without pretending every failure is a successful delivery.
9. Reconcile the customer charge, provider/courier cost, platform fee, refund or claim, and eligible provider settlement through the existing payment and ledger owners.

**Mode B — Delivery/fulfilment provided through the platform for a seller/provider**

1. During checkout or the applicable service flow, resolve the available fulfillment options: platform delivery, external courier/carrier, seller/provider delivery, scheduled delivery, shipping, collection/pickup, or not serviceable.
2. Obtain quotes using the real origin/destination, product/package attributes, geography, schedule, capacity and merchant contract. Display the selected fee and delivery promise before the customer confirms.
3. Freeze the agreed delivery method, amount/currency, address snapshot, promised window, and relevant quote/rule version alongside the order-time commercial agreement. Historical order totals are not silently rewritten when rates change.
4. After the required commercial state (e.g. confirmed payment and/or seller acceptance), create one or more shipment/delivery jobs. One order may require multiple packages or legs; each seller order created by ADR-0010 retains its seller boundary.
5. The merchant prepares/hands off the package, the dispatch service allocates a courier/carrier, tracking events are normalized, and the buyer/provider see their authorized states.
6. Delivery outcomes feed the order's fulfillment projection, notifications, support cases, refund eligibility and reports. The order domain remains the owner of order state; the delivery domain owns physical movement.
7. Partial shipment, pickup, failed delivery, return, replacement, partial refund and charge reconciliation are represented explicitly at the correct owning boundary.

Seller-self-delivery, platform fleet, third-party delivery partner and pickup/collection must be different fulfillment modes—not hacks that all appear as “shipped”. A professional service/appointment is not a parcel by default; only services whose business requirements include physical transport create delivery jobs.

### 3.3 Minimum delivery domain concepts

These are domain concepts to validate, not a commitment to copy these exact class/table names.

| Concept | Responsibility |
|---|---|
| Serviceability area / operating schedule | Where a service is available, operating hours, cut-offs, holidays, capacity and restrictions |
| Delivery quote | Price breakdown, service level, time window/ETA, assumptions, provider offer/rate revision and expiry |
| Delivery request/job | The standalone or domain-linked request and its guarded lifecycle |
| Shipment/consignment/package | Parcel-level details, labels/tracking reference, pieces/weight/dimensions, custody and hand-off |
| Assignment | Courier/team/provider assignment, acceptance, rejection, reassignment reason and history |
| Tracking event | Append-only normalized event from the platform, courier app or external provider, preserving source and observed time |
| Pickup/delivery proof | Controlled evidence and timestamps, verification state, retention and privacy policy |
| Delivery adjustment/claim | Failed attempt, surcharge, cancellation charge, damage/loss, return-to-sender and compensation decision |
| Provider/driver operations | Eligibility, area/capacity, availability, delivery capabilities, credentials/secrets and integration health |
| Price/fare rules | Base charge, distance/zone/package/time/capacity supplements, merchant contracts, promotions if approved, and immutable quote versions |

Do not start with a full route optimizer, fleet management suite or predictive AI. First prove the quote → dispatch → pickup → tracking → proof → settlement path for one real supported operating area. Route planning and fleet optimization can follow measured volume and cost need.

### 3.4 Delivery provider integrations

Use a provider-neutral port and separate adapters for:
- The platform's own driver/courier operations.
- Local same-day/last-mile delivery partners.
- Parcel/shipping carriers with labels, tracking, pickup and returns.
- Seller/provider-managed delivery where the platform only records status and evidence.
- Pickup/collection points.

A partner's capabilities differ: quote and ETA, cancel, courier assignment, live location, proof of delivery, returns, insurance, package size, scheduled delivery and geographic coverage must be declared explicitly. Provider callbacks need signature verification, deduplication, out-of-order handling, replay protection and recovery by fetching current authoritative state when the partner supplies it.

[Uber Direct's official overview](https://developer.uber.com/docs/deliveries/overview) illustrates the merchant-triggered courier dispatch model; its [delivery estimates API](https://developer.uber.com/docs/deliveries/direct/api/v1/post-eats-deliveries-estimates), [create-delivery API](https://developer.uber.com/docs/deliveries/direct/api/v1/post-eats-deliveries-orders), and [delivery-status webhooks](https://developer.uber.com/docs/deliveries/daas/references/api/webhooks/delivery-status-webhook) illustrate quote/dispatch/tracking integration. It is an **example integration reference, not a selected vendor**; access, geography, pricing, legal availability and partner approval must be verified before adoption.

Saleor's [checkout](https://docs.saleor.io/developer/checkout/overview) and [channels](https://docs.saleor.io/developer/channels/overview) provide useful retail patterns for shipping methods, shipping zones, collection points, region/channel eligibility and shipping price. They do not by themselves implement a standalone on-demand delivery business with dispatch/courier operations; the platform needs the additional delivery domain described here.

### 3.5 Delivery, order, payment and ledger boundaries

- `marketplace-delivery` is the proposed owner of a delivery job and its physical-progress state.
- `marketplace-orders` continues to own cart/checkout/order lifecycle and immutable commercial snapshot. It references delivery/fulfillment contracts; it does not update delivery tables.
- `marketplace-payments` executes collections/refunds against a validated payable and handles gateway/provider outcomes; delivery never talks directly to a payment processor.
- `marketplace-ledger` records customer collection, platform fee, merchant/courier payable, refund, reversal, adjustment and payout entries according to the approved policy.
- `marketplace-provider` and identity remain the identity/verification owners; the delivery domain may own operational driver/courier eligibility and delivery-area capability where a distinct lifecycle is required.
- `marketplace-geo` supplies canonical geographic data and supported spatial queries through an explicit contract; it is not silently duplicated by a second neighbourhood database.
- `marketplace-availability` may supply applicable capacity primitives through an explicit contract, but courier supply, dispatch and fleet state remain delivery-owned.
- Notifications, media, messaging and disputes are integrated through their existing owners and ports/events.
- For service appointments, `marketplace-booking` remains the appointment owner; a delivery job is created only if transport is part of that service's contract.

Synchronous checks (serviceability, quote validity, source authorization, stock/slot requirements) use the owning contract. Events communicate committed facts and post-transaction effects; they do not replace a synchronous prerequisite. Avoid cross-module table writes and event consumers with no actual consumer requirement.

### 3.6 Privacy, safety and operations

Addresses, phone numbers, recipient identity, live courier location and proof-of-delivery evidence can be sensitive. Access must be role- and job-scoped, with limited exposure to senders, recipients, assigned couriers, authorized support and finance staff. Retention must differ for precise live location and legally retained transaction evidence. Never expose a public tracking URL that provides more private data than the recipient/sender needs.

Delivery acceptance also requires:
- A defined prohibited/restricted-items policy and package declaration flow.
- Delivery liability, failed-delivery, loss/damage, return, cancellation, insurance and compensation rules.
- Geographic operating-hour/capacity limits and safe fallback if no provider is available.
- Idempotent assignment/dispatch and a safe “outcome unknown” reconciliation state.
- An audited dispatcher override with reason and authorized scope.
- User-visible distinction between quote, confirmed request, courier assigned, pickup complete, in-transit, delivered with proof, failed, cancelled, and returned.
- Offline/poor-network behavior for courier workflows, with durable event ordering and conflict handling.
- Provider outage/rate-limit handling, bounded retries, operational alerting and a manual recovery route.

## 4. Platform administration: first-class operating workspaces

The dashboard must expose two related but distinct workbenches.

### 4.1 Platform payment and financial operations

Search and filter across all payment sources, payer/payee, provider/gateway, country/currency, payment status, amount, order/booking/loan/ad/delivery reference, webhook, refund, dispute, ledger entry, payout and reconciliation state. Every record has a transaction timeline that differentiates source-domain state, gateway result, platform payment state, ledger posting, provider allocation, and settlement.

Required queues include:
- Awaiting/unknown payment outcome.
- Failed or duplicate webhook/event.
- Charged but source domain not updated.
- Order/booking/delivery cancellation with pending financial action.
- Refund awaiting eligibility review/approval or provider confirmation.
- Provider vs. ledger mismatch.
- Seller/courier/provider settlement awaiting or failed.
- Dispute/chargeback deadline and required evidence.
- Unmatched transfer, payout, fee or chargeback.

Actions require authorized source-owned commands, reason, amount/currency preview, idempotency, approval separation where configured, and read-back proof. No direct balance editing and no retry of an uncertain financial action before reconciliation.

### 4.2 Delivery and shipping operations

A dedicated workbench manages direct delivery requests and marketplace fulfillment together, without conflating their business origins. It includes:
- Dispatch queue/map, failed or delayed jobs, unassigned jobs, at-risk promises, pickup/drop-off exceptions and return-to-sender queue.
- Search by human-readable request, order, seller, sender/recipient, courier/provider, locality, tracking reference and state.
- Delivery detail with quote snapshot, promised window, address disclosure appropriate to the operator's role, package, custody timeline, provider callbacks, proofs, communication, charges/refunds and linked case.
- Service areas, local delivery rates, operating windows, pickup points, capacity, blackout periods, prohibited items and service-level definitions.
- Courier/driver onboarding and scoped role, assignment, availability, incident reporting and job history.
- Carrier/partner adapters, active serviceability/capabilities, API credentials in secret storage, health, rate limits and callback failures.
- Reconciliation of quoted charge vs. collected amount vs. partner invoice/courier compensation/platform fee; correction is an audited financial operation.
- Safe overrides for reassign/cancel/retry, with previews, reason, audit and post-command verification.

Use the existing global work queue, case timeline, permission governance and audit system, but keep source state machines in their domain owners.

## 5. Backend gap assessment from current source

The following are based on source inspection, not a fresh full test run.

| Area | Evidence today | Gap to target |
|---|---|---|
| Payment cross-domain reuse | `PaymentIntent` includes `BOOKING`, `AD`, `ORDER`, `LOAN`; existing Stripe channel, webhook processing, idempotency and event consumers exist | Define a stable source contract and policy matrix for all currently/soon-to-be monetized journeys; normalize amount/currency semantics; verify each origin's success/failure/cancel/refund and ledger paths |
| Gateway selection | `PspChannel` abstraction and Stripe implementation are present | No evidence in inspected source of a fully configured multi-gateway registry/routing policy; add only after region/method/business requirements are known |
| Financial reconciliation/admin | payment, ledger and dispute primitives exist; admin target already references payment exception handling | The product-grade unified financial workbench must be verified end-to-end across all origins, including refunds/transfers/payout/reconciliation outcomes |
| Delivery/shipping domain | Root module list includes geo, availability, booking, orders, payments, ledger, provider, notifications, media and disputes; no dedicated delivery/shipping module appeared in the inspected list | Direct delivery request lifecycle, quote/dispatch, shipment/package, courier assignment, tracking, proof, external carrier adapters, delivery-specific exceptions and operations are not evidenced as a unified domain |
| Order fulfillment | Order state machine supports placed/confirmed/fulfilled/cancelled, but current order model is not a parcel/consignment network | Model the one-to-many relation from a seller order to shipment/delivery legs; integrate fulfillment without duplicating order/payment state |
| Price and financial allocation | Order snapshots, pricing, payments and ledger exist with explicit owners | Delivery quotes/rates and their immutable order-time amounts; provider/driver allocations, liability, commission and refund treatment require explicit policy and integration |
| Geography and capacity | Geo and availability modules exist | Define delivery-specific serviceability/operating capacity through explicit ports, not duplicated geo/slot state |
| Native product journeys | The target platform is Android-first and has member/provider journeys | Specify member direct-delivery request/tracking, merchant handoff, courier/driver job workflow, and support recovery; no browser-only provider flow is assumed |

Related baseline: [ADR-0002](../adr/ADR-0002-commerce-fit-gap.md) defines the single catalog/orders/payments ownership; [ADR-0010](../adr/ADR-0010-multi-seller-cart-split.md) defines atomic mixed-seller order splitting; [the Saleor study (PR #602)](https://github.com/waelhe/app-java-v3/pull/602) maps the merchandise-specific gaps.

## 6. Owner decisions required before financial or delivery production rollout

The architecture can be planned now; these product/legal/commercial settings must not be silently guessed:
1. Initial countries/regions and currencies to enable; gateway/courier/carrier availability and required approvals.
2. Merchant of record and party liable for fees, refunds, chargebacks, failed deliveries and negative balances for each transaction type.
3. Commission, platform fee, driver/courier compensation, provider payout timing, payout failures, reserves/holds, and partial-refund allocation policy.
4. Supported customer payment methods; whether authorization then capture is required; whether cash, cash-on-delivery, tips, recurring charges, stored-value wallet or escrow-like behavior is permitted. No wallet/escrow is assumed.
5. Delivery package types, restricted items, local-vs-parcel shipping scope, weight/dimension boundaries, scheduled vs. on-demand service, operating areas/hours and proof requirements.
6. Who can provide delivery in each area: platform drivers, merchant/self-delivery, third-party couriers/carriers, collection points, or combinations; fallback order and SLAs.
7. Delivery price policy: customer fee, seller fee, distance/zone/package/time surcharges, cancellation/no-show, failed-attempt, return shipping, and who absorbs provider-price differences.
8. Claim/insurance/compensation policy and personal-data retention for address, recipient contact, real-time location and proof of delivery.

These are gates to activating each mode/market, not reasons to reduce the product target. The implementation should ship only the modes whose operating and financial rules are approved, while representing unavailable modes honestly.

## 7. Recommended delivery sequence

### Phase 0 — source and policy truth-sync
- Re-read current main, open PRs, schema/migrations, payment origins and consumers, ledger posting, ADRs, API contracts and deployment/payment-provider configuration.
- Map every payable source and delivery/fulfillment path to source owner, API contract, payment outcome consumer, ledger effect, audit event and test.
- Record decisions above; do not modify production code in this research phase.

### Phase 1 — make payment handling genuinely platform-wide
- Preserve existing payment/ledger owners and the currently working BOOKING/AD/ORDER/LOAN paths.
- Introduce or document the single payment-source contract after deciding the explicit-origin vs. stable-subject approach.
- Add delivery as a first-class payable source only when its owning domain and quote/cancellation rules exist.
- Close origin-by-origin journey gaps: success, failure, duplicate/out-of-order callback, cancel before/after payment, full/partial refund where supported, ledger reconciliation and no double effect.
- Add a unified finance operator read model/workbench using source-owned read/command contracts.

### Phase 2 — implement the standalone delivery vertical slice
- Build and test quote/serviceability → request/confirm → payment → dispatch → pickup → tracking → proof → recipient notification → financial reconciliation.
- Start with one explicitly supported geography and one real delivery provider mode (platform-operated or one approved external partner), with an honest no-serviceable-quote state.
- Implement idempotent provider integration, safe recovery, privacy, driver/operator access and return/cancellation failure paths.

### Phase 3 — integrate marketplace fulfillment
- At checkout, resolve fulfillment methods and freeze the chosen rate/method/promise into the order agreement.
- Create shipment/delivery jobs against confirmed source orders; allow multiple parcels/legs where needed and preserve ADR-0010 seller isolation.
- Add delivery-fee collection/allocation and partial cancellation/refund tests.
- Prove order state, delivery state, payment state, ledger state and customer notification remain consistent under failures and retries.

### Phase 4 — platform delivery operations and additional providers
- Provide rate/service-area/capacity controls, dispatch queues, courier management, exception handling, claims/returns, carrier adapters and settlement reconciliation.
- Add more providers/regions based on measured serviceability and operational cost; no provider-specific API leaks into core domain contracts.
- Add advanced route optimization/fleet analytics only after real usage justifies the complexity.

## 8. End-to-end acceptance journeys

### Payments — platform-wide
1. A booking, store order, lending fee and ad bill each resolve amount/currency from their owner and collect through the same payment owner without changing the domain state prematurely.
2. A new delivery payable follows the same adapter and idempotency path; the user cannot alter its amount.
3. Provider success webhook is processed once and causes the appropriate domain-owned transition and ledger posting; duplicate/reordered callback causes no duplicate side effect.
4. Failed/ambiguous payment can be reconciled using provider evidence; no automatic fulfillment or unsafe repeated charge occurs.
5. Full and partial refund flows read eligibility from the owning domain, execute through payments, reconcile recipient transfers/ledger, and prove the final visible state.
6. One seller's refund or payout failure does not corrupt a sibling order or another provider's balance.
7. An authorized finance operator can investigate any origin by a single search, preview a permitted correction, obtain required approval, execute once and verify provider/payment/ledger/source state.

### Direct delivery
1. A member enters pickup/drop-off/package/time; serviceability and a quote are proven from an enabled provider/rule; unavailable route yields a truthful no-service result.
2. Confirming a quote creates one request/job and one payable operation at most; repeated taps/timeouts do not create duplicate deliveries or charges.
3. A courier accepts, arrives and records pickup; sender and recipient receive scoped tracking updates.
4. Successful delivery records required proof, visible completion, final quote adjustments (if the approved contract permits them), and courier/provider financial effects.
5. Recipient unavailable, courier rejection, provider outage, damage/loss, return, cancellation and refund use explicit guarded workflows with a named owner and audit trail.
6. Sender, recipient, courier and support each see only the address, contact, location and proof data permitted for their role/job.

### Market fulfillment
1. A buyer sees delivery options available for the actual seller/order/address and selects one with a stable quote and terms.
2. Placement freezes the agreed item and delivery amounts; rate changes after placement do not silently change the order.
3. Mixed-seller order placement preserves ADR-0010's whole-cart atomicity; each seller's delivery/shipment job references only its order, with cross-order summary handled by explicit contracts.
4. Failed delivery or a partial return calculates the correct source-domain refund and delivery-cost consequence without direct table mutation.
5. Order, shipment, payment, ledger, notification and customer status projections recover correctly after duplicated or delayed events.

Required gates: current Flyway additive migration rules, Envers parity, Spring Modulith verification, server-side authorization, OpenAPI/GraphQL compatibility, JaCoCo, real PostgreSQL integration tests and full affected journey suites. No migration or code change is authorized by this document alone.

## 9. Official references

- Saleor [Products](https://docs.saleor.io/developer/products/overview), [Channels](https://docs.saleor.io/developer/channels/overview), [Checkout](https://docs.saleor.io/developer/checkout/overview), [Transactions](https://docs.saleor.io/developer/payments/overview).
- Spring Modulith [Verification](https://docs.spring.io/spring-modulith/reference/verification.html), [Events](https://docs.spring.io/spring-modulith/reference/events.html).
- Spring Data JPA [Query Methods / modifying queries](https://docs.spring.io/spring-data/jpa/reference/jpa/query-methods.html).
- Stripe [PaymentIntents](https://docs.stripe.com/api/payment_intents), [asynchronous payment outcomes](https://docs.stripe.com/payments/payment-intents), [Connect separate charges and transfers](https://docs.stripe.com/connect/separate-charges-and-transfers).
- Uber Direct [overview](https://developer.uber.com/docs/deliveries/overview), [delivery estimate](https://developer.uber.com/docs/deliveries/direct/api/v1/post-eats-deliveries-estimates), [create delivery](https://developer.uber.com/docs/deliveries/direct/api/v1/post-eats-deliveries-orders), [delivery status webhook](https://developer.uber.com/docs/deliveries/daas/references/api/webhooks/delivery-status-webhook).
- Repository [ADR-0002](../adr/ADR-0002-commerce-fit-gap.md), [ADR-0010](../adr/ADR-0010-multi-seller-cart-split.md), and [Saleor commerce fit-gap study](../research/saleor-commerce-backend-fit-gap.md).
