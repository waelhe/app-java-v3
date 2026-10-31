# D-WHSE-001 — Multi-warehouse inventory is a conditional capability, not CRUD

**Status:** PROPOSED — A0 recommendation; owner approval required.  
**Decision date:** 2026-10-31  
**Authority:** D-SALEOR-FULL-001, P2/P3; inventory and stock owners.  
**Affected roots:** `warehouse`, `warehouses`, `stock`, `stocks` (currently `BLOCKED_BY_DECISION`); `createWarehouse`, `updateWarehouse`, `deleteWarehouse`, `assignWarehouseShippingZone`, `unassignWarehouseShippingZone`, `productVariantStocksCreate`, `productVariantStocksDelete`, `productVariantStocksUpdate`, `stockBulkUpdate`, and `channelReorderWarehouses` (currently `NOT_STARTED`); catalog inventory, allocation and fulfillment.  
**Related decisions:** D-CHANNEL-001, D-SHOP-001.

## Context and measured gap

The current divergence registry records no warehouse entity or multi-warehouse allocation model. The measured stock facts reside on the existing catalog/inventory owner: `ProductStockAdapter` supports reserve/release/commit for both Product and ProductVariant, and the inputs carry no warehouse/location dimension. The truthful current description is **global product/variant stock with no location dimension**, not "one implicit inventory location per product" or a single-location warehouse model. Adding a Warehouse table and CRUD endpoints would not create Saleor-equivalent inventory behavior.

A real warehouse capability requires more than a list of locations: ownership, stock per location, reservation, allocation, transfer, fulfillment routing, cancellation/release and reconciliation all need semantics.

## Proposed decision

Defer multi-warehouse capability until an evidenced operational requirement and a complete domain design exist. Keep the existing inventory owner authoritative and keep global product/variant stock behavior truthful while no location dimension exists. The central inventory currently distinguishes four blocked read roots (`warehouse`, `warehouses`, `stock`, `stocks`) from the nine `NOT_STARTED` warehouse/stock mutations and `channelReorderWarehouses`; this ADR's affected-root list intentionally includes each one and does not change any central status.

Do not:
- introduce a warehouse CRUD module solely to fill the Dashboard;
- reinterpret product stock counters as warehouse allocations;
- fabricate a default warehouse or warehouse ID;
- enable channelReorderWarehouses without real warehouse membership/order semantics;
- let a Dashboard route imply multi-location stock when only one implicit location exists.

If a business need is later accepted, a separate design must define:
1. warehouse/fulfillment-location ownership (platform, provider, seller or legal entity) and permissions;
2. inventory per SKU/variant and location, including on-hand, reserved, available and damaged/quarantined quantities;
3. atomic reservations and allocation across locations, expiry/release and oversell prevention;
4. stock movement, transfer, adjustment reason, audit and reconciliation;
5. order routing, split fulfillment, delivery promises, cancellation and return-to-stock rules;
6. channel/location eligibility and cross-owner isolation;
7. migration and backfill from the present product-level stock model without losing reservations or changing historical orders.

Only after these decisions and acceptance tests exist should a dedicated module/owner be selected. Do not preselect module boundaries or migration numbers in this deferral ADR. The nine listed mutations and `channelReorderWarehouses` must remain undeclared/unsupported while the warehouse capability is deferred; no CRUD-only implementation or Dashboard success state is permitted.

## A0 Dashboard 3.23.39 route / operation evidence

The pinned Dashboard 3.23.39 source at `bc75e988da0fa4fbc3d47378a888d859d070746f` has direct dependencies on the deferred location-aware roots:

- `src/warehouses/mutations.ts` defines warehouse create/update/delete; the Warehouses list and dialogs expose those operations.
- Channel Details / setup calls `createWarehouse`, updates Channel warehouse assignments and exposes assign/create actions. The Channel inventory/setup cards cannot remain active when warehouse membership roots are undeclared.
- `src/channels/mutations.ts` defines `channelReorderWarehouses`; the channel UI can reorder assigned locations.
- `src/products/mutations.ts` performs `productVariantStocksDelete`, `productVariantStocksCreate` and `productVariantStocksUpdate` within the variant update flow. The product variant editor therefore depends on warehouse/location stock semantics, not merely on a Warehouse list page.
- The pinned Dashboard changelog explicitly states that preorder UI was removed and that pre-sales should use ordinary stock/warehouse or `trackInventory=false`; that removal does not remove the warehouse/stock dependencies above.
- Under this deferral proposal, the Warehouses routes, Channel inventory/assignment/reorder controls and per-variant warehouse-stock editor must all be capability-gated/disabled in platform mode. A partial UI that exposes these controls and then returns a GraphQL error or fake empty inventory is not acceptable.


## Alternatives considered

- **A — Defer and explicitly exclude multi-warehouse Dashboard surfaces while preserving current product-level inventory. RECOMMENDED for current evidence.** Does not invent capability and does not block unrelated commerce work.
- **B — Implement a complete warehouse/fulfillment-location domain now.** Viable only after a documented market requirement, owner model, stock migration and fulfillment allocation design; not justified by the current measured model.
- **C — Add Warehouse CRUD with a fixed/default location and map existing product stock into it. Rejected.** It creates misleading multi-warehouse semantics and risks future migration while still lacking allocation/transfer/fulfillment invariants.

## Required acceptance evidence for any future re-opening

- Real provider/warehouse ownership and permission matrix, including cross-seller isolation.
- PostgreSQL concurrency tests for reservations and allocation across multiple locations; no oversell under races.
- Dashboard route coverage maps all four blocked read roots and all ten `NOT_STARTED` warehouse/stock mutations (including `channelReorderWarehouses`) to disabled/empty states; no route issues a request to an undeclared or excluded root.
- Transfer/adjustment audit trail and stock ledger reconciliation.
- Multi-location order allocation, split shipment, cancellation and return-to-stock E2E tests.
- Backfill rehearsal and reconciliation of current product stock/reserved values.
- Modulith tests proving inventory and fulfillment use declared public contracts.

## Work that may continue without this decision

Global product/variant inventory and existing checkout reservation work may continue without a warehouse/location dimension. Channel/catalog/pricing work not depending on warehouses may continue. Only the warehouse/stock multi-location roots and channelReorderWarehouses remain blocked/excluded pending the actual owner decision.

## Release impact

The full Saleor release cannot claim warehouse/stock root parity while this capability is deferred. The four read roots remain `BLOCKED_BY_DECISION`; the nine mutations plus `channelReorderWarehouses` remain `NOT_STARTED` until the owner chooses a disposition. If the owner accepts permanent deferral/exclusion, A0 must update the central inventory and divergence registry with each exact root and disable its Dashboard route; this ADR alone does not change those statuses. This proposal alone does not change their current OWNER_ADR_REQUIRED status.
