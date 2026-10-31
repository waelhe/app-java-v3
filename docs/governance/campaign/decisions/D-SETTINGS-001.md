# D-SETTINGS-001 — Return, refund and order settings follow domain ownership

**Status:** PROPOSED — A0 recommendation; owner/A3/A4 review required.  
**Decision date:** 2026-10-31  
**Authority:** D-SALEOR-FULL-001, P3/P4; campaign playbook §§2 and 10.  
**Affected roots:** refundSettings, returnSettings, refundSettingsUpdate, returnSettingsUpdate, refundReasonReferenceClear, returnReasonReferenceClear, Query.orderSettings, orderSettingsUpdate, and the nested `channelUpdate(input.orderSettings)` path; returns/RMA, orders, payments and ledger.  
**Related decisions:** D-SHOP-001, D-GC-001, D-CHANNEL-001, ADR-0013.

## Context — distinguish the wire object from the business policy

Saleor 3.23.43's Shop `refundSettings` and `returnSettings` surfaces expose a `reasonReferenceType` (a PageType reference) and are global across the shop/all channels. The platform has a persisted CMS page-type registry (`cms_page_types` and `cms_page_type_attributes`) introduced by V282 under `marketplace-console` ownership. That proves a database/domain ownership surface only; it does **not** prove a Saleor-compatible PageType GraphQL type, `pageTypes` query, create/update mutations, or a public SPI. The current integration-base `marketplace-app/src/main/resources/graphql/schema.graphqls` contains no `PageType`, `pageTypes`, or `pageType` fields/types. A5 must therefore verify both semantic equivalence and the missing wire/API boundary before these reason references can be exposed. These settings are not, by themselves, a full return-eligibility policy, an RMA workflow, a refund execution policy, a PSP configuration or an accounting policy. The platform already has separate owners for orders, RMA, payments and ledger.

A GraphQL field name is not enough evidence to assign all post-sale behavior to a generic settings module.

## Proposed decision

Keep ownership distributed by business capability:

| Concern | Owner and boundary |
|---|---|
| Concern | Measured owner and boundary |
|---|---|
| Return request, eligibility, return window, condition, evidence, inspection, disposition and RMA lifecycle | `marketplace-orders/OrderReturnsService` (REQUESTED → APPROVED → RECEIVED → COMPLETED, with reject/cancel paths) |
| Refund execution to the original payment method | `marketplace-payments`, through `OrderRefundPort` → `OrderRefundAdapter` → `PaymentsService.refundPayment` with the stable return-scoped idempotency contract |
| Refund allocation across original tender instruments, including gift-card tender | `marketplace-orders` orchestration using the frozen tender-allocation snapshot and declared payment/gift-card ports; D-GC-001 owns that snapshot contract |
| Financial reversal, commission reversal, liability movement and reconciliation | `marketplace-ledger`; `LedgerPaymentEventListener.processRefundDebit` mirrors REFUNDED, while PARTIALLY_REFUNDED remains the documented no-op under ADR-0013/D3 until separately changed and tested |
| Order lifecycle, cancellation and immutable commercial snapshot | `marketplace-orders` |
| Gift-card refund credit | `marketplace-gift-cards` (D-GC-001), with the corresponding central-ledger liability posting |
| PageType reason-reference data | `marketplace-console` (`CmsPageType` / `CmsPageTypeAttribute`) via a public SPI; A5 must prove Saleor PageType semantic equivalence before enablement |
| Global Shop `refundSettings` / `returnSettings` | Their reason-reference selection is persisted by the PageType owner; the settings adapter exposes only this global-for-shop configuration, not a new RMA/refund policy owner |
| Channel-scoped `orderSettings` | `marketplace-orders` is the sole persistence/behavior owner; `marketplace-channels` supplies validated channel context and delegates `channelUpdate(input.orderSettings)` to the Orders SPI under D-CHANNEL-001 |

Do not create a generic marketplace-settings module containing RefundSettings, ReturnSettings and OrderSettings merely because the Dashboard groups them in an administration section.

### Contract clarifications

1. A successful `refundSettingsUpdate` or `returnSettingsUpdate` may update only the actual Saleor-compatible global-for-shop reason-reference selection backed by the real PageType owner. It must not imply that the platform has changed return windows, eligibility or PSP refund behavior. `refundReasonReferenceClear` and `returnReasonReferenceClear` are the complementary clear operations and must be represented/dispositioned independently.
2. V282 establishes persisted page-type tables, but the integration-base SDL has no Saleor `PageType` type/query/mutations. Do not describe the upstream `pageType`/`pageTypes` roots as integrated or assume `CmsPageType` IDs can be returned on the Saleor wire. A5 must verify the required PageType contract, stable ID adapter, authorization and SPI; until the resolver/wire surface exists and matches, do not coerce IDs or expose a success-shaped write.
3. The measured platform `OrderReturnsService.request` currently carries a free-text reason, while Saleor's setting points to a PageType. Do not silently store a PageType ID in the free-text field. A0/owner must explicitly choose a real PageType-reference extension with a historical snapshot or an explicit per-root exclusion; the Dashboard must reflect that choice.
4. `refundSettings` and `returnSettings` are global for the whole shop/all channels. `orderSettings` is a distinct channel-scoped order policy. `marketplace-orders` is its single write/persistence owner; `channelUpdate(input.orderSettings)` is only the composite Saleor adapter path. Match the pinned permission alternatives: `MANAGE_CHANNELS` is sufficient for the general Channel update path; an input containing only `orderSettings` may alternatively be updated with `MANAGE_ORDERS` alone. If the input combines `orderSettings` with non-settings Channel fields, `MANAGE_CHANNELS` is required. Audit actor/reason/time. `Query.orderSettings` is deprecated in Saleor 3.23.43 and resolves the settings from the first active channel ordered by slug; it is not a global order-settings store. Retain that exact legacy alias only if the pinned Dashboard route audit requires it; otherwise keep it explicitly unsupported and use the channel-scoped query. It must never create a second settings store.
5. Any configurable RMA eligibility/policy must be versioned. The order/return record captures the policy version and relevant decision facts applied at purchase/request time. A later policy edit must not rewrite an existing customer's historical rights.
6. Partial refunds and multi-tender refunds must reverse the frozen original tender allocation from D-GC-001, not recompute a new split from current settings. Payment, gift-card and ledger operations must share stable idempotency/correlation identifiers. Exclusive-tax refunds must preserve the frozen gross/net/tax share.
7. `refundReasonReferenceClear` / `returnReasonReferenceClear` clear the configured PageType reference. After clear, any refund/return mutation that requires that configured reference must fail with a classified error; tests must prove it cannot accept an unconfigured reference. PageType references are global, not channel-scoped.
8. Coordinate `OrderSettings.includeDraftOrderInVoucherUsage` and `useLegacyLineDiscountPropagation` with the pricing/voucher owner. Do not build the legacy line-discount propagation path that the pinned upstream contract says will be removed; adopt the `OrderLineDiscount` representation if/when this capability is implemented.

## A0 Dashboard 3.23.39 route / operation evidence

The pinned Dashboard 3.23.39 source at `bc75e988da0fa4fbc3d47378a888d859d070746f` has retained post-sale settings consumers:

- `src/refundsSettings/queries.ts` reads `refundSettings`, `returnSettings` and `pageTypes`; `src/refundsSettings/mutations.ts` uses `refundSettingsUpdate`, `returnSettingsUpdate`, `refundReasonReferenceClear` and `returnReasonReferenceClear`. The Refunds Settings route is therefore a real dependency, not an inferred future route.
- `src/orders/queries.ts` reads `shop.orderSettings` and channel `orderSettings`; `src/orders/mutations.ts` updates Shop order settings with `shopSettingsUpdate`, while `submitOrderSettingsForm.ts` sends each changed channel's settings through `channelUpdate(input.orderSettings)`. Both read paths and the split global/channel save behavior must be preserved if those Dashboard controls remain enabled.
- `OrderReturnPage` also reads the refund/return settings; the dependency affects post-sale order journeys, not only the settings page.
- The Dashboard's PageType picker selects from `pageTypes(first: 100)` and sends a PageType ID. The existence of `marketplace-console/CmsPageType` alone does not prove equivalence. A5 must verify identifier format, ownership/permissions, required type semantics and historical-reference behavior before any of these four settings mutations are enabled. Until then the PageType-backed controls and dependent return/reason flows remain disabled or use an explicitly approved alternative.


## Alternatives considered

- **A — Domain-owned settings and operations with thin Dashboard aggregation. RECOMMENDED.** Preserves real boundaries, historical semantics and financial integrity.
- **B — One generic settings owner for all post-sale behavior. Rejected.** It would duplicate order, RMA, payment and ledger state machines.
- **C — Map every Saleor setting mutation to a successful generic key/value write. Rejected.** This could claim a refund or return policy changed when only an unrelated setting was stored.
- **D — Exclude the PageType reason-reference operations where no truthful mapping exists. Viable per-root fallback.** The corresponding Dashboard control must remain disabled/absent and explicitly recorded.

## Required acceptance evidence

- Pinned-Saleor contract tests prove exact `refundSettings` / `returnSettings` shape and global-for-shop update/clear semantics, including `refundReasonReferenceClear` and `returnReasonReferenceClear`.
- `Query.orderSettings` compatibility tests (only if retained) prove its deprecated first-active-channel-by-slug behavior; channel-scoped `Channel.orderSettings` remains the authoritative read.
- PageType contract tests must first prove the missing PageType GraphQL type/query/mutations are supplied by the owner, the V282 persistence is exposed through a public SPI, the ID adapter and permissions match Saleor, and only then prove that a cleared reference cannot be supplied/accepted by refund/return mutations.
- `channelUpdate(input.orderSettings)` and `Channel.orderSettings` round-trip the same `marketplace-orders` state under the exact permission alternatives above, with audit of actor/reason/time. Any retained deprecated `Query.orderSettings` resolves the first active channel by slug and cannot be backed by an independent global setting.
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
