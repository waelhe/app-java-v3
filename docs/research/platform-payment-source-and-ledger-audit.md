# Payment Sources and Ledger — Source-of-Truth Audit

**Status:** Read-only code audit for the delivery implementation gate.  
**Reviewed branch:** `main` at `d891edb1e6001f3417fc0126efdd3995cc1655f1`. The linked platform-wide design PR was based on `d3c873d3595f5c7a7b9f6104dfb7bb5477b2fb9b`, one commit behind the current main when checked.  
**Scope:** Payment-intent contracts, origin schema, PSP boundary, webhook settlement, ledger consumers, refund behavior, test evidence, and the changes a new DELIVERY origin would require.  
**Non-goal:** No payment behavior, migration, provider configuration, or production data was changed by this audit.

## 1. Executive finding

The platform does **not** need a second payment engine. It already has a reusable payment-intent lifecycle, a PSP port, a Stripe implementation, a webhook inbox/deduplication path, refund operations, payment-state events, and a ledger listener. Four source origins are represented in the current model:

- `BOOKING`
- `AD`
- `ORDER`
- `LOAN`

A direct delivery request is **not yet a supported payment source** in the inspected contract. Adding it is not a string-only change: the source identity is encoded across Java fields, the cross-module `PaymentIntentDetails` record, factory/adapter methods, repository queries, PostgreSQL CHECK constraints, the ledger event listener, notifications/other consumers, and tests.

**Critical safety finding:** `LedgerPaymentEventListener` has explicit branches for AD, ORDER and LOAN, then falls through to a BOOKING lookup. If a DELIVERY intent were added without changing this consumer, a completed/refunded delivery event could be treated as a booking and attempt `getBookingInfo(null)`. The implementation must add explicit delivery handling and make unsupported origins fail closed—never let an unknown origin reach the booking branch.

## 2. Source evidence

### 2.1 Payment intent shape and origin constraints

- [PaymentIntent.java](https://github.com/waelhe/app-java-v3/blob/main/marketplace-payments/src/main/java/com/marketplace/payments/PaymentIntent.java) defines `origin` as a string (not a Java enum), with explicit `bookingId`, `adCampaignId`, `orderId`, and `loanId` members. The model uses `amountCents` and a three-letter `currency`; future contracts should describe amounts as minor units of the explicit ISO-4217 currency while preserving existing compatibility.
- [PaymentIntentDetails.java](https://github.com/waelhe/app-java-v3/blob/main/marketplace-shared/src/main/java/com/marketplace/shared/api/PaymentIntentDetails.java) is the shared cross-module read model and repeats the four-origin vocabulary via source identifiers and origin predicates.
- [V200__lending_loans.sql](https://github.com/waelhe/app-java-v3/blob/main/marketplace-app/src/main/resources/db/migration/V200__lending_loans.sql) widens the `chk_payment_intents_origin` constraint to BOOKING/AD/ORDER/LOAN and the `ck_payment_intents_origin_pairing` constraint so exactly the correct source-id column is present.
- [V209__payment_intents_origin_validate.sql](https://github.com/waelhe/app-java-v3/blob/main/marketplace-app/src/main/resources/db/migration/V209__payment_intents_origin_validate.sql) validates those `NOT VALID` constraints in a separate migration, consistent with the repository's lock-management discipline.
- [PaymentIntentRepository.java](https://github.com/waelhe/app-java-v3/blob/main/marketplace-payments/src/main/java/com/marketplace/payments/PaymentIntentRepository.java) provides specific lookups for order and loan origins in addition to booking and PSP id lookups.

**Implementation implication:** adding DELIVERY requires an additive migration that adds a delivery subject column/reference, uniqueness rules where required, and widened/validated origin-pairing constraints. The origin/reference must remain constrained; do not replace it with an unchecked free-form string or direct cross-module table ownership.

### 2.2 Current source creation paths

- Booking: `PaymentsService.createIntent` obtains `BookingInfo` from the booking port, checks participant and CONFIRMED state, derives the price/currency from that booking, and blocks a second live collectible attempt.
- Ads: `createAdIntent` receives the campaign/window-derived charge, requires a positive amount, and uses a deterministic billing-window idempotency key.
- Orders: `createOrderIntent` receives the source owner’s computed amount/currency, requires a positive amount, and uses `order-{orderId}` plus a unique source constraint.
- Loans: `createLoanIntent` receives the fee/currency supplied by the lending-owned adapter, requires a positive amount, and uses `loan-{loanId}` plus a unique source constraint.

Relevant source:
- [PaymentsService.java](https://github.com/waelhe/app-java-v3/blob/main/marketplace-payments/src/main/java/com/marketplace/payments/PaymentsService.java)
- [OrderPaymentAdapter.java](https://github.com/waelhe/app-java-v3/blob/main/marketplace-payments/src/main/java/com/marketplace/payments/spi/OrderPaymentAdapter.java)
- [LoanPaymentAdapter.java](https://github.com/waelhe/app-java-v3/blob/main/marketplace-payments/src/main/java/com/marketplace/payments/spi/LoanPaymentAdapter.java)

The delivery flow must take the amount only from an unexpired, server-validated delivery quote owned by the delivery domain. The HTTP client must never choose the authoritative amount or currency. Delivery should call a narrow payment port with the finalized request/quote, and the adapter should carry the amount approved by that domain and a deterministic request-scoped idempotency key.

### 2.3 PSP and settlement reliability

- [PspChannel.java](https://github.com/waelhe/app-java-v3/blob/main/marketplace-payments/src/main/java/com/marketplace/payments/PspChannel.java) is the existing provider-neutral seam; Stripe is an isolated implementation.
- Provider binding is configuration-gated: without credentials, the real PSP channel is absent and existing internal-intent behavior remains. This is not evidence that a live payment method is enabled for a given country, currency, or delivery flow.
- `PaymentsService` processes verified webhooks with deduplication and recovery; [PaymentIntentSettlementService.java](https://github.com/waelhe/app-java-v3/blob/main/marketplace-payments/src/main/java/com/marketplace/payments/PaymentIntentSettlementService.java) settles the local intent/payment and the webhook inbox in an owned transaction, and publishes `PaymentStateChangedEvent`.
- [OrderIntentContractTest.java](https://github.com/waelhe/app-java-v3/blob/main/marketplace-payments/src/test/java/com/marketplace/payments/OrderIntentContractTest.java) covers deterministic idempotency, payer ownership, amount guards, and the local transition/event contract for order payments.

Delivery must reuse this lifecycle. Do not call Stripe directly from the delivery domain or add a second webhook implementation there.

### 2.4 Ledger behavior and explicit policy boundaries

- [LedgerPaymentEventListener.java](https://github.com/waelhe/app-java-v3/blob/main/marketplace-ledger/src/main/java/com/marketplace/ledger/LedgerPaymentEventListener.java) consumes COMPLETED and REFUNDED events via the payment-intent lookup port.
- The AD completed branch credits the advertiser/provider from the amount and currency on the intent, without commission.
- The ORDER branch resolves the seller through `OrderSellerPort`, credits the order amount, and debits the configured commission.
- The LOAN branch resolves the owner through `LoanOwnerPort`, credits the fee, and debits the configured commission.
- The remaining branch resolves a booking and credits the booking provider with its amount/currency and commission.
- The listener injects `app.commission.rate` with a code default of `0.10`. This proves a configurable commission path exists; it does **not** establish that a 10% delivery commission, courier allocation, payout, or merchant-of-record policy has been approved.
- [LedgerService.java](https://github.com/waelhe/app-java-v3/blob/main/marketplace-ledger/src/main/java/com/marketplace/ledger/LedgerService.java) records source-keyed provider-balance movements (payment credit, commission debit, refund debit and ad debit) idempotently.
  
This audit does not establish that the current ledger is a complete double-entry/general-ledger accounting system. New seller/courier settlement features must follow the actual existing data model and accounting-owner decisions rather than assume capabilities from the module name.

### 2.5 Partial refunds are a confirmed gap for the intended delivery workflow

`PaymentsService` tracks cumulative partial-refund state on the payment and intent and publishes `PARTIALLY_REFUNDED`. However, the ledger listener explicitly ignores `PARTIALLY_REFUNDED`; the test [LedgerPaymentEventListenerTest.java](https://github.com/waelhe/app-java-v3/blob/main/marketplace-ledger/src/test/java/com/marketplace/ledger/LedgerPaymentEventListenerTest.java) asserts that no ledger work occurs for that event. Full refunds produce a refund debit mirroring the original credit.

That is a documented current policy, not a failure to notice a branch. It is insufficient by itself for delivery cases involving one failed leg, a partial delivery-fee adjustment, one refunded item within an order, or a seller/courier allocation that must be reversed partially.

Before activating partial delivery refunds, define an idempotent **refund operation / cumulative refund accounting contract** that records each refund amount and recipient allocation, supports provider cumulative totals and retries, and posts the correct ledger consequence exactly once. Do not reuse the current full-refund key `refund-{intentId}` for multiple partial refunds; multiple operations need stable unique operation identities or a monotonic cumulative-delta algorithm with clear concurrency behavior.

### 2.6 Existing ledger scope vs. platform-wide finance target

The module already offers per-owner, per-currency balances and statements; it is a good existing base, not proof of a complete finance operations workbench. The source audit does not establish these as already complete: multi-recipient payment allocation, seller/courier transfers/payouts, failed payout recovery, provider-vs-ledger reconciliation, refund allocation across recipients, or an all-origin operator timeline.

Do not add a recipient or payout model until the settlement/merchant-of-record and cost-bearing policies are decided. Keep a distinction between:
1. customer/provider payment state,
2. origin-domain business state,
3. ledger movements,
4. amount payable to each seller/courier/provider,
5. external transfer/payout state.

These are related states and must be reconcilable; they are not one Boolean `paid`.

## 3. Exact delivery-source change set (design gate)

When the delivery domain's quote and request contracts have been agreed, an implementation PR must account for all of the following as one reviewed change set, not piecemeal string edits:

1. Delivery-owned quote/request lifecycle and an authoritative server-side calculation of payer, final charge, currency, cancellation rules, and accepted quote revision.
2. An explicit DELIVERY origin and subject reference in `PaymentIntent`, with a unique/idempotent creation rule appropriate for a request; add it through an additive Flyway migration.
3. Widen both the origin CHECK and source-pairing CHECK, preserve the current lock discipline (`NOT VALID` plus a separate validation migration when appropriate), and add an audited-column migration for `payment_intents_aud`.
4. Extend `PaymentIntentDetails`, origin predicates, repository methods, read projections, and every mapping constructor/adapter. Search the entire repo for origin-switch/fallback assumptions, not just the central model.
5. Extend the Payment-module SPI through an exposed named interface so only the delivery owner invokes the payment source contract; no cross-module entity/table access.
6. Add explicit DELIVERY handling in ledger completion and refund paths with approved recipient/fee policy. Replace the implicit BOOKING fallback with an exhaustive, fail-closed source dispatch (for example a clear unsupported-origin exception that reaches monitoring) while preserving every existing origin.
7. Add origin-specific payment-state consumption, notification and audit mapping only where a real delivery consumer needs it; do not make notifications or ledger assume a booking for unknown origin ids.
8. Tests: origin pairing constraints, exactly-one subject, request retries produce one intent, wrong payer/foreign request denied, quote expiry/tamper rejected, payment success/failure/cancel, webhook duplicate/out-of-order recovery, partial/full refund and ledger reconciliation, unsupported origin fail-closed, and module verification.
9. Run the repository's pinned Maven/CI gates and a real PostgreSQL/Flyway integration suite; verify the applied/validated migration state and the relevant event publications. No code in this document should be considered implemented before that evidence exists.

## 4. Current status

| Item | Status |
|---|---|
| Four source origins and schema pairing | **Verified in source** |
| PSP port and Stripe adapter | **Verified in source; live configuration not implied** |
| Verified webhook inbox and transaction-owning settlement | **Verified in source** |
| Payment refund full/partial state | **Verified in source** |
| Ledger full-refund handling | **Verified in source** |
| Ledger partial-refund posting | **Explicitly absent by current listener/test policy** |
| Delivery as payment source | **Not present in inspected origin contract/schema** |
| Dedicated delivery module in root Maven modules | **Not present in inspected root `pom.xml`** |
| Delivery quote, dispatch, tracking, proof of delivery and delivery settlement journey | **Target is documented; not implemented by this research** |
| Full Maven verification performed by this audit | **No; read-only GitHub source audit** |

