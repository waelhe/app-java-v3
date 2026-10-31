# D-SETTINGS-001 — Return, refund and order settings follow domain ownership

**Status:** PROPOSED — A0 recommendation; owner/A3/A4 review required.  
**Decision date:** 2026-10-31  
**Authority:** D-SALEOR-FULL-001, P3/P4; campaign playbook §§2 and 10.  
**Affected roots:** refundSettings, returnSettings, refundSettingsUpdate, returnSettingsUpdate, orderSettingsUpdate; returns/RMA, orders, payments and ledger.  
**Related decisions:** D-SHOP-001, D-GC-001, D-CHANNEL-001, ADR-0013.

## Context — distinguish the wire object from the business policy

Saleor 3.23.43's Shop refundSettings and returnSettings surfaces expose a reasonReferenceType (a PageType reference). They are not, by themselves, a full return-eligibility policy, an RMA workflow, a refund execution policy, a PSP configuration or an accounting policy. The platform already has separate owners for orders, RMA, payments and ledger.

A GraphQL field name is not enough evidence to assign all post-sale behavior to a generic settings module.

## Proposed decision

Keep ownership distributed by business capability:

| Concern | Owner and boundary |
|---|---|
| Return request, eligibility, return window, condition, evidence, inspection, disposition and RMA lifecycle | Existing return/RMA owner |
| Refund request orchestration and refund execution to the original payment method | marketplace-payments, using the existing refund port and idempotency contract |
| Refund allocation across original tender instruments, including gift-card tender | Checkout/orders orchestration with payment and gift-card ports |
| Financial reversal, commission reversal, liability movement and reconciliation | marketplace-ledger |
| Order lifecycle, cancellation and immutable commercial snapshot | marketplace-orders |
| Gift-card refund credit | marketplace-gift-cards (D-GC-001), with corresponding ledger posting |
| Saleor refundSettings / returnSettings PageType reason-reference selection, if a matching owned PageType capability exists | CMS/Page owner via SPI; otherwise the relevant settings root remains unsupported |
| Channel-scoped order configuration | Order owner and Channels context under D-CHANNEL-001; not the global RMA/refund settings owner |

Do not create a generic marketplace-settings module containing RefundSettings, ReturnSettings and OrderSettings merely because the Dashboard groups them in an administration section.

### Contract clarifications

1. A successful refundSettingsUpdate or returnSettingsUpdate may update only the actual Saleor-compatible reason-reference configuration mapped to a real PageType owner. It must not imply that the platform has changed return windows, eligibility or PSP refund behavior.
2. If the platform's CMS PageType does not match the pinned Saleor PageType semantics, do not coerce it. Keep the relevant GraphQL fields undeclared or explicitly excluded and disable the affected Dashboard control.
3. orderSettingsUpdate belongs to P4/A4 and the orders owner. Distinguish global platform defaults from channel-scoped configuration and order-level snapshots; the P3 agent must not take ownership of the order state machine.
4. Any configurable RMA eligibility/policy must be versioned. The order/return record captures the policy version and relevant decision facts applied at purchase/request time. A later policy edit must not rewrite an existing customer's historical rights.
5. Partial refunds and multi-tender refunds must reverse the actual original allocation, not recompute a new split from current settings. Payment, gift-card and ledger operations must share stable idempotency/correlation identifiers.
6. A GraphQL settings mutation must enforce the correct server-side permission, audit actor/reason/time and classify unsupported behavior; a UI-only guard is insufficient.

## Alternatives considered

- **A — Domain-owned settings and operations with thin Dashboard aggregation. RECOMMENDED.** Preserves real boundaries, historical semantics and financial integrity.
- **B — One generic settings owner for all post-sale behavior. Rejected.** It would duplicate order, RMA, payment and ledger state machines.
- **C — Map every Saleor setting mutation to a successful generic key/value write. Rejected.** This could claim a refund or return policy changed when only an unrelated setting was stored.
- **D — Exclude the PageType reason-reference operations where no truthful mapping exists. Viable per-root fallback.** The corresponding Dashboard control must remain disabled/absent and explicitly recorded.

## Required acceptance evidence

- Pinned-Saleor contract tests prove exact refundSettings / returnSettings shape and update semantics, including the reason reference.
- RMA tests prove policy version snapshotting, eligibility boundaries, ineligible reasons, state transitions and tenant/owner authorization.
- Payment tests prove original-method refund execution, duplicate request idempotency, partial and full refunds, unknown PSP outcomes and retry recovery.
- Multi-tender tests prove refunds are allocated back to the original tender split, including gift-card partial credit; ledger reconciliation proves reversals and liabilities.
- Orders tests prove changing settings cannot mutate previous order/return decisions.
- Modulith verification proves orders/RMA/payments/ledger use declared SPIs/events without dependency cycles or cross-module repository access.
- Dashboard tests prove unsupported settings do not appear as editable controls or success notifications.

## Work that may continue without this decision

The existing RMA/payment/order implementations and tests may continue independently. A4 may implement unrelated order/payment roots against frozen contracts. A3 may implement unrelated P3 roots. The settings family remains constrained to the exact capabilities already owned; no generic settings module is authorized.

## Release impact

P3/P4/P7 cannot claim post-sale settings parity by counting the mutation names alone. This proposal does not change root status or SDL; A0 updates the registry and Dashboard route disposition only after review and owner approval.
