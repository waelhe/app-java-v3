# Delivery Domain Contract — Direct Requests and Marketplace Fulfillment

**Status:** Proposed bounded-context contract for review; not executable API or implementation evidence.  
**Source audit:** [Platform Payment Source and Ledger Audit](../research/platform-payment-source-and-ledger-audit.md).  
**Platform target:** [Platform-Wide Payments and Delivery Network](./platform-wide-payments-and-delivery.md).  
**Implementation rules:** current `AGENTS.md`, `docs/official-compliance-plan.md`, approved ADRs, Spring Modulith verification and repository migration/test gates remain authoritative.

## 1. Contract decision

Introduce a delivery-specific bounded context (working name **`marketplace-delivery`**) only after checking the latest source tree and module ownership at implementation time. It owns the physical delivery service, including standalone requests and order-linked delivery/shipments. It is not an order sub-state machine and not a payment processor.

The first shipping target is intentionally limited to two source modes:

- `DIRECT`: a person/business requests delivery independently of a marketplace purchase.
- `ORDER_FULFILLMENT`: a seller order requires one or more delivery legs or parcels.

Later service-booking transport, returns, transfers between locations, pickup-point networks and richer fleet features can be enabled only when a real owner workflow and contract exists. A bookable appointment is not automatically a parcel delivery.

## 2. Ownership boundaries

| Capability | Sole owner | Contract with delivery |
|---|---|---|
| Delivery quote/rates and quote expiry, service area, package/service limits, delivery request/job, shipments/packages, driver/courier assignment, tracking events, pickup/delivery proofs, delivery-specific failures, returns and claims | Proposed `marketplace-delivery` | Its own source of truth |
| Product/order/cart totals, seller split, checkout, order cancellation and customer-visible order lifecycle | Existing `marketplace-orders` | Supplies a confirmed order fulfillment request and receives delivery lifecycle facts; does not write delivery rows |
| Payment-intent creation, provider channel, verified webhook inbox, collection/cancel/refund | Existing `marketplace-payments` | The delivery domain submits a server-calculated payable through a narrow source-owned adapter; no direct PSP calls |
| Provider/driver/provider-company identity and verification | Existing identity/provider owners, plus delivery-owned job eligibility if that distinct lifecycle is needed | References authorized subject ids, does not duplicate canonical identity |
| Ledger entries, owner balances and statement projections | Existing `marketplace-ledger` | Receives approved transaction/settlement facts and follows a named settlement policy |
| Dispute cases and financial/delivery claim authority | Existing `marketplace-disputes` and the applicable source authority | Supplies linked evidence and state; does not decide order or payment state by rewriting another domain |
| Geography and availability | Existing `marketplace-geo` and `marketplace-availability` through exposed interfaces | Queries canonical service-area geometry and any applicable shared capacity; delivery owns courier dispatch/capacity |
| Notifications, media, messaging and audit | Existing owners | Receives committed delivery/payment/order facts through defined contracts |

No cross-module repository/entity access, cross-module table writes, or duplicate provider/order/payment tables. Use explicit named interfaces and dependency verification. Delivery must not import internal payment entities or the order aggregate; it receives source-owned request DTOs and identifiers.

## 3. Domain model (logical concepts, not prescribed table names)

### 3.1 Delivery quote

A quote is an immutable, time-bounded offer. It contains:

- Quote id and revision, origin mode, requester, currency, creation/expiry time.
- Pickup and drop-off address/location snapshots (and stable geo references when supported); contact references and handling instructions are stored with data-minimization rules.
- Package declaration: kind, piece count, dimensions/weight where required, permitted handling, restricted-item decision.
- Requested service level and scheduled window or quoted pickup/delivery window.
- Provider/mode offer, serviceability decision, fare components (base, zone/distance/package/time supplements and any approved fee), total in the selected currency, rate-rule version, provider quote/reference, assumptions, and cancellation/failure terms.
- An explicit state such as `OFFERED`, `ACCEPTED`, `EXPIRED`, or `REJECTED`; a new price/rule produces a new revision rather than mutating an accepted quote.

A client may select a valid quote id but cannot submit a trusted amount. At acceptance, the delivery owner loads and validates that quote, the requester, expiry, destination/provider capability and any approved rule. Quote use is one-time or otherwise concurrency-controlled according to a documented multi-use policy.

### 3.2 Delivery request/job and shipment

A delivery request records the business origin (`DIRECT` or `ORDER_FULFILLMENT`), requester, authorized source reference if any, accepted quote revision, operational lifecycle, and idempotency key. Use explicit source kinds rather than an unconstrained polymorphic string.

One delivery job may contain one or more shipment/package records and, where necessary, several legs. Each shipment/leg has its own provider tracking reference, custody hand-offs, and physical-progress events. This permits split parcels and failed/returned legs without creating multiple fake orders.

The job must retain the accepted fee/currency and service promise. A changed rate affects new quotes; it must not silently rewrite an accepted request or an existing order's historical total.

### 3.3 Assignment, tracking and proof

- Assignment records courier/team/provider, assignment/reassignment history, actor, reason, acceptance/decline and timestamps.
- Tracking events are append-only normalized facts with source, external event id, observed/received time, location if permitted, and payload fingerprint/reference. Duplicate and out-of-order messages do not regress state.
- Pickup/delivery proof contains a proof type, verification result, timestamp, actor/source, and restricted media/evidence reference. Proof access is job-scoped; a public tracking reference must not disclose address, phone, private live location, signature, or photos beyond policy.
- Provider callbacks must be authenticated and deduplicated. On timeout/ambiguous provider response, query/reconcile current provider state before dispatch/create/cancel retry.

## 4. Lifecycle and invariants

Keep **physical delivery lifecycle**, **payment lifecycle**, **source order lifecycle**, and **settlement lifecycle** distinct. The UI may present one composed timeline, but the underlying source owner must remain identifiable.

### 4.1 Physical lifecycle proposal

Normal path:

`REQUESTED → CONFIRMED → READY_FOR_DISPATCH → ASSIGNED → PICKUP_EN_ROUTE → PICKED_UP → IN_TRANSIT → DELIVERED`

Guarded exception paths:

- `REQUESTED/CONFIRMED → CANCELLED` only when quote/request terms and no irreversible handoff permit it; after collection or assignment, cancellation may require an explicit financial and operational outcome.
- `READY_FOR_DISPATCH → ASSIGNMENT_FAILED` when no courier/provider can take it; retry is allowed only with a stable operation key and current eligibility.
- `ASSIGNED/PICKUP_EN_ROUTE → PICKUP_FAILED` with a reason, attempt count, next action and customer notification.
- `PICKED_UP/IN_TRANSIT → DELIVERY_FAILED` with reason and a policy-bound retry / recipient-contact / return decision.
- `DELIVERY_FAILED → ASSIGNED` or `RETURNING` only through authorized, audited commands and a new valid assignment where required.
- `RETURNING → RETURNED`; physical return proof is distinct from refund eligibility or provider settlement.

This is a reviewable target; final enum values and allowed transition graph must be frozen with tests before schema creation. External provider states are mapped to these states using a documented adapter mapping; raw provider status strings do not become the platform contract.

### 4.2 Independent state dimensions

At minimum, the domain/read model must distinguish:

- Request state: accepted/expired/cancelled and whether delivery operations are eligible to proceed.
- Payment projection: not required, pending, confirmed, failed/unknown, partially refunded, refunded—only states supported by the payment contract. Payment remains owned by `marketplace-payments`.
- Provider/assignment state: unassigned, assigned, accepted, rejected, provider outcome unknown.
- Fulfillment state: one shipment/leg's physical status and proof.
- Settlement/reconciliation state: not eligible, eligible, pending, reconciled, exception—financial execution remains ledger/payment-owned.

An event indicating payment `COMPLETED` must not mark a package picked up or delivered. A tracking event saying `DELIVERED` must not by itself mark the customer payment collected, authorize a refund, or pay a courier without verified policy inputs.

### 4.3 Dispatch and transition invariants

1. No dispatch while payment is required and not verified, unless an explicitly approved merchant-credit/deferred-payment policy applies.
2. A direct request can be created only from a valid, unexpired quote and an authorized requester. The final quote amount/currency is server-derived.
3. One idempotency key plus one accepted request results in at most one logical delivery job and at most one payment intent for that obligation.
4. Do not hold database transactions open during external provider HTTP calls. Persist intent/state first, call through a recoverable adapter/worker, and reconcile uncertain outcomes before retry.
5. A courier/provider can access only assigned/authorized work; sender, recipient, seller and operator views are scoped to their roles and source relationship.
6. Every state-changing command validates the current persisted state and version/locking strategy; stale or duplicate commands cannot move a job backward.
7. A state transition records who/what caused it, the source event id where applicable, timestamp, reason/evidence, and a stable operation identifier.
8. A failed attempt, return, cancellation or claim must state the next operational owner and its financial consequence; never call all exception outcomes “failed” with no recovery path.

## 5. Logical application contract

These are semantic operations to shape the API; final URI, DTO names and REST/GraphQL exposure must follow the repository's current API naming and compatibility conventions.

### 5.1 Member/business direct-delivery operations

- `quoteDirectDelivery(request)` → quote id/revision, price breakdown/currency, service/mode, promise, expiry, terms and available alternatives.
- `createDirectDelivery(quoteId, idempotencyKey)` → request id, current physical state, payable/payment projection, next required action; it does **not** accept client-authoritative amount/currency.
- `getDeliveryRequest(requestId)` / `listMyDeliveryRequests(filters, page)` → authorized request, jobs/shipments, timeline and only the permitted contact/address/evidence fields.
- `cancelDeliveryRequest(requestId, reason, idempotencyKey)` → eligible outcome and any required payment/refund command or explicit “needs review”; it does not return a success response before source state is committed/read back.

### 5.2 Merchant/order fulfillment operations

- `quoteOrderFulfillment(orderSnapshot, destination, packageDeclaration, options)` → allowed modes/providers, quote revisions and charges before the order is placed.
- `createOrderFulfillment(sourceOrderId, sellerId, acceptedQuoteId, orderSnapshotRef, idempotencyKey)` → delivery job/shipment references under that single seller order. The input carries a validated order-owned snapshot/amount semantics, not a direct repository lookup.
- `getOrderDeliverySummary(sourceOrderId)` → shipment/leg projections safe for the order detail page.
- `requestReturnOrRedelivery(jobOrLegId, reason, idempotencyKey)` → a guarded new operational workflow and associated financial review where relevant.

### 5.3 Operator/courier commands

- Quote/rate/service-area management with version, effective period and preview impact; no rewriting previous quote/order snapshots.
- Assign, accept, decline, reassign and request-provider-status operations, always with actor, scope, reason, idempotency and post-command result read-back.
- Record pickup, failed attempt, delivered proof, return hand-off and claim evidence through authenticated courier/provider/operator channels.
- A courier app may cache permitted job data for poor connectivity, but commands need server-side authorization, durable event ids and conflict/replay handling when connectivity returns.

### 5.4 Error semantics

Use the repository's existing error envelope and stable machine-readable codes. At minimum distinguish:
- invalid/expired quote;
- address/route not serviceable;
- item/package not permitted;
- provider/courier unavailable;
- stale/illegal state transition;
- unauthorized requester/operator/courier scope;
- idempotency key reused with different intent;
- payment required/payment result unknown;
- external provider temporarily unavailable vs. definitive rejection;
- proof missing/invalid;
- refund/settlement needs source-owner or finance review.

An external timeout is not a definitive rejection; represent an outcome-unknown state and reconcile before any retry that could duplicate an external effect.

## 6. Payment contract for DELIVERY origin

### 6.1 Initial financial rule

Use a new `DELIVERY` payment origin only for a **standalone direct-delivery payable** (or another explicitly approved, separately billed delivery obligation). For ordinary marketplace checkout, the default is that the accepted shipping/delivery charge is included in the immutable ORDER payable; do not also create a DELIVERY intent for the same fee. If a later separately payable delivery adjustment is approved, it must be a distinct, clearly disclosed payable with a separate amount, consent and refund rule.

The payment intent references the delivery request id and the server-derived final amount/currency. It must not copy a client amount. Before payment-intent creation, quote acceptance and delivery request creation must be idempotent.

### 6.2 Coordinated payment code changes

Adding DELIVERY requires a single coordinated, source-audited change set:

1. Add the delivery subject id/relationship to `PaymentIntent`, its origin constants/factory, the `PaymentIntentDetails` record and every mapping adapter/projection.
2. Add an additive Flyway migration widening both origin and origin-pairing CHECK constraints, preserving the split validation migration pattern when needed; add the Envers audit column and the uniqueness/index required by the one-obligation rule.
3. Add a narrow source-facing Payment SPI method and the delivery-side adapter. The idempotency key must be derived from the stable delivery payable/request id, and replay must confirm the same payer/source/amount/currency—not merely return an arbitrary intent.
4. Audit every origin consumer: ledger settlement/refund, notifications, access checks, cancellation adapters, admin summaries, read models and tests. Source-origin dispatch must be exhaustive/fail-closed; no unknown DELIVERY origin may fall through to the booking path.
5. Do not add DELIVERY ledger credit/commission/payout logic until the operator defines merchant-of-record, recipient, courier/provider cost, platform fee/commission, refund and loss/chargeback allocation. The current `app.commission.rate` default of 0.10 is not permission to apply the same rate to delivery.
6. Use the existing `PspChannel`, webhook inbox, verified settlement, payment state events and refund path. Do not call provider SDKs from delivery or duplicate provider webhooks.

### 6.3 Refund and settlement behavior

Payment rows currently retain partial-refund state, but the current ledger listener intentionally ignores `PARTIALLY_REFUNDED` events. Before the delivery network allows partial returns, partial delivery cancellation/fee adjustments or split-leg financial recovery, define a refund-operation identity and cumulative-versus-delta posting strategy that is safe under concurrent and repeated provider events. One full-refund identity keyed only to the intent cannot safely represent multiple distinct partial refunds.

Delivery completion, a platform service fee, courier/provider payable and payout are different facts. Record them according to the accepted financial policy and existing ledger data model. Do not assume a successful customer collection automatically settles the courier or an external carrier invoice.

## 7. External adapter contract

Define a provider-neutral adapter for both platform-operated courier flow and external carriers. Minimum operations/capabilities:

- `estimate(request)` — optional when the platform's own rate engine determines quote; returns amount/currency, service level, promise, expiry and provider quote reference.
- `createDelivery(job, operationKey)` — idempotent provider-side creation and provider reference; supports reconciliation after timeout.
- `cancelDelivery(providerReference, operationKey)` — returns confirmed cancelled, cannot cancel, or unknown; never fabricates a terminal result.
- `fetchDelivery(providerReference)` — authoritative state reconciliation.
- `verifyWebhook(headers, rawBody)` and `normalizeEvent(payload)` — signature/security verification and mapping to the internal event vocabulary.
- Declared capability metadata: geography, package restrictions, quote, scheduled pickup, cancel, live tracking, proof, returns, insurance/claims and rate limits.

Provider API requests run outside database transactions via recoverable application work. Persist the attempted operation and stable key before dispatch; store the provider result and event ids; alert on unresolved ambiguity. Secrets stay in approved secret storage, not DB logs or admin-visible fields.

## 8. Core lifecycle and failure tests

Before considering the delivery domain production-capable, prove all of these in real integration tests with PostgreSQL/Flyway and the relevant Modulith event contracts:

1. Valid direct quote → request creation → PAYMENT intent → successful verified provider outcome → ready-for-dispatch → assignment → pickup proof → tracking → delivery proof.
2. A repeated request/quote submission and repeated payment callback create no duplicate request, intent, dispatch or ledger effect.
3. Expired, reused, tampered, wrong-currency or non-serviceable quote cannot create a payable/dispatch.
4. Payment failure/unknown/timeout blocks dispatch when prepayment is required; late success and out-of-order webhook are reconciled safely.
5. Duplicate/out-of-order carrier events never regress the lifecycle; provider create/cancel timeout triggers reconciliation rather than duplicate external work.
6. Courier rejection, no courier available, missed pickup, recipient unavailable, failed delivery, return-to-sender and cancellation take explicit, audited recovery paths.
7. Only the authorized sender/recipient/assigned courier/seller/operator can see their allowed addresses, contacts, tracking and proof.
8. A standalone delivery's ledger effect uses the agreed recipient and policy; full and multiple partial refunds reconcile correctly without repeated debits/credits.
9. Marketplace order fulfillment freezes the accepted fee and method in the order snapshot, attaches each delivery to the correct seller order (including ADR-0010 split), and never double charges shipping.
10. Order, delivery, payment and ledger states remain independently correct through a failed event consumer and its recovery.
11. Spring Modulith verifies acyclic module boundaries and permitted API use; API compatibility gates and coverage gates pass.

## 9. Implementation order after review

1. Re-truth-sync main and any intervening payment/order changes. Complete the read-only audit against actual controller/API DTOs, source SPI implementations, ledger event consumers, migrations, and test coverage.
2. Approve owner decisions for delivery provider mode, initial operating geography, currency/payment method, dispatch-before/after-payment policy, price components, commission/recipient allocation, cancellation and refund policy.
3. Freeze the domain contract and allowed state-transition graph before creating production schema.
4. Implement the narrow vertical slice for **direct delivery first**, including quote, payment, dispatch, tracking, proof, financial reconciliation and failure recovery.
5. Integrate order fulfillment next, with the shipping fee counted exactly once and the split-seller invariants preserved.
6. Add the complete journey and contract test suites before merging either implementation slice.

No code or schema has been added by this contract document. The PR must not be treated as a completed delivery implementation or as proof of any live carrier/gateway capability.
