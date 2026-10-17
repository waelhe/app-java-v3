# ADR-0013: the financial allocation decisions — merchant of record, commission, settlement, and the delivery provider mode (the platform-wide payments plan's Phase 1 owner gates)

- **Status:** Accepted (the owner's standing delegation — «اتبع الممارسات العالمية فيما يحتاج بوابة خارجية» — resolves every external/owner gate by the global practice of the official documentation; governed by the official documentation exclusively)
- **Governing references (official documentation exclusively):**
  - [Stripe Connect — charge types & merchant of record](https://docs.stripe.com/connect/charges) and [separate charges and transfers](https://docs.stripe.com/connect/separate-charges-and-transfers) — the two documented marketplace charge models, their liability geography, and their refund/transfer behavior
  - [Stripe Connect — application fees](https://docs.stripe.com/connect/direct-charges#application-fees) — the fee is fixed at charge creation, never recomputed at settlement
  - [Saleor — Marketplace Recipe](https://docs.saleor.io/recipes/marketplace) — the operator-charges/payouts staging as the functional benchmark
  - The merged architecture — [platform-wide payments and delivery](../architecture/platform-wide-payments-and-delivery.md) §2.5 (the open gates this ADR closes) and §7 Phase 1, PR #603
  - The merged source audit — [payment sources and ledger behavior](../research/platform-payment-source-and-ledger-audit.md) (the measured facts: single payment engine, provider-balance ledger, `app.commission.rate:0.10`, PARTIALLY_REFUNDED no-op, commit-at-fulfillment seam)

## Context

The platform-wide payments architecture (PR #603) deliberately refused to select a merchant-of-record model, a commission/settlement policy, or a delivery provider mode — it recorded those as owner decisions and gated the remaining Phase 1 work on them. The owner's standing delegation now resolves those gates by the global practice of the official documentation. This ADR records the four decisions, each grounded in (a) the official reference, (b) the measured behavior of the existing code, and (c) the launch reality of the platform's market.

## The decisions

### D1 — The platform is the merchant of record (the marketplace facilitator model)

Stripe's Connect documentation documents exactly two marketplace charge shapes: **separate charges and transfers**, where each connected account is its own merchant of record on its own charge, and the **destination/combined charge shapes**, where the platform is the merchant of record — the customer's charge lands on the platform's account, the platform bears processing fees, refunds and chargebacks at the charge level, and the connected party receives its allocation as a transfer that a customer refund does **not** automatically reverse (the refund/transfer asymmetry the architecture's principle 5 already records).

The measured code is already the second shape: ONE payment engine owns every intent (platform-owned idempotency keys, one `payment_intents` truth, one webhook inbox), and the ledger credits PROVIDER balances as internal allocations with the commission debited from them — the customer never pays a seller's account; the seller's "balance" is the ledger's accounting of what the platform owes them. Saleor's marketplace recipe documents the same operator-charges posture as the standard curated-marketplace launch model.

**Decision:** the platform is the merchant of record. Consequences, adopted explicitly: the platform bears the PSP fees, executes refunds from its own charge, and absorbs chargebacks at the charge level; the seller-side consequences ride the ledger (the credit reversal on refund, the commission reversal mirroring it) and the settlement holds of D3 — never a silent pass-through. Per-seller connected-account MoR (separate charges) is a documented future mode that requires per-seller PSP onboarding and its own ADR; nothing in this decision precludes it as an additional mode later.

### D2 — Commission: one announced rate, frozen per transaction at agreement time

**Decision:** the platform's commission stays the single announced rate (`app.commission.rate`, default 0.10 — the measured default) and **freezes onto each transaction when the payment intent is created** — the same freeze discipline the cart already applies to prices. The ledger posts the frozen value; a rate change affects new transactions only.

**Grounding:** Stripe fixes `application_fee_amount` at PaymentIntent creation — the fee the parties agreed to is the fee collected, never a rate that drifted between checkout and settlement. The current implementation has a measured gap against this rule: the listener reads the rate at its construction and computes at event-processing time, so a config change between placement and settlement would rewrite the posted fee. The closing implementation slice (this ADR's follow-up): carry the rate on the intent at creation, read the frozen value in the ledger — until that lands, the operational discipline is to not change the rate while intents are open (the honest interim, recorded here).

**Disclosure:** the announced rate is part of the seller's operating terms; the ledger's debit entries are the per-transaction disclosure of record (the seller's statement reads them).

### D3 — Settlement: eligibility at terminal fulfillment, holds for disputes and the refund window, payout as a separate operational stage

The architecture's principle 6 — pay-in, allocation, settlement, and payout are separate stages — becomes concrete:

1. **Pay-in:** the intent's COMPLETED state credits the seller's allocable ledger balance (the existing behavior — allocation, not payment).
2. **Settlement eligibility:** the order's FULFILLED terminal state — the same edge where the stock's `commit()` already lands (the money follows the goods on the one seam that already exists). A booking's settlement eligibility is its COMPLETED state; an ad's is its charged window; a loan fee settles on collection. Each origin's eligibility rides its own terminal edge through the existing per-origin branches.
3. **Holds:** open disputes and the refund window block the eligible amount. The measured ledger behavior is already consistent: PARTIALLY_REFUNDED is a deliberate no-op, a full REFUND reverses the credit and its commission mirror — the hold policy composes with these, it does not replace them.
4. **Payout:** an operational action on the ledger's settled balance — out of the platform's account to the seller's bank account. It is NEVER automatic on pay-in (principle 6 verbatim), and the ledger's balances are bookkeeping, not stored value (principle 7's no-implicit-wallet stance — there is no seller wallet until a payout provider executes one). The payout provider integration (Stripe Connect payouts or manual bank transfer) is its own gate with its own ADR when the account structure is live.

**Grounding:** Stripe's separate-charges-and-transfers documentation stages exactly this (charge → transfer, optionally delayed → payout on schedule); Saleor's marketplace recipe stages vendor payouts after fulfillment. Both make the delay explicit rather than implied.

### D4 — Delivery provider mode: platform-operated first, external carriers via adapters later

**Decision:** the launch mode is **platform-operated delivery** (the platform's own courier fleet, dispatched by the platform) with an OSRM-compatible routing provider as the configured default for distance/time — the mode the in-flight delivery vertical slice (#604) already implements. External carrier/courier partners arrive per the architecture's Phase 4, behind the adapter boundary the delivery-domain contract already mandates («no provider-specific API leaks into core domain contracts»), selected by measured serviceability and operational cost — never by hardcoded provider logic.

**Grounding:** the architecture's own Phase 2 gate — «Start with one explicitly supported geography and one real delivery provider mode (platform-operated or one approved external partner)». The platform-operated mode is the controlled launch answer (the operator owns serviceability, quality, and the failure paths end-to-end — the Uber-model posture for a curated launch); Saleor's external-shipping-app pattern documents the later breadth (partners behind adapters). The in-flight slice's measured shape (courier verification, quote-from-routing, fail-closed settlement) matches the contract's boundary rules — this decision retroactively records the mode selection the slice already embodies, so the plan's gate is closed by record rather than by assumption.

## Consequences

- The remaining Phase 1 work of the payments plan is unblocked: the origin-by-origin journey gaps and the finance operator read model can proceed against these decisions.
- The commission-freeze implementation slice (D2's follow-up) is the one code change this ADR directly requires; it lands as its own PR with the listener's tests extended to prove the frozen value is posted, not the live config.
- No ledger engine change: every decision composes with the measured architecture (per-origin branches, commission debit, refund mirror, commit-at-fulfillment). The settlement eligibility and holds are read-model/operational surfaces over the existing entries, not a second accounting engine.
- The architecture document's §2.5 open questions are answered by this record; the document itself remains the historical analysis (ADRs are the decision records — the ADR-0010/0012 supersession pattern).
