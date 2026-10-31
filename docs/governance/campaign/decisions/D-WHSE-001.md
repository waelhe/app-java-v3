# D-WHSE-001 — Multi-warehouse inventory is a conditional capability, not CRUD

**Status:** PROPOSED — A0 recommendation; owner approval required.  
**Decision date:** 2026-10-31  
**Authority:** D-SALEOR-FULL-001, P2/P3; inventory and stock owners.  
**Affected roots:** warehouse, warehouses, stock, stocks, channelReorderWarehouses; catalog inventory, allocation and fulfillment.  
**Related decisions:** D-CHANNEL-001, D-SHOP-001.

## Context and measured gap

The current divergence registry records no warehouse entity or multi-warehouse allocation model. The measured stock facts reside on the existing product/inventory owner: product stock quantity and reserved quantity with database constraints and conditional stock updates. This is effectively one implicit inventory location per product. Adding a Warehouse table and CRUD endpoints would not create Saleor-equivalent inventory behavior.

A real warehouse capability requires more than a list of locations: ownership, stock per location, reservation, allocation, transfer, fulfillment routing, cancellation/release and reconciliation all need semantics.

## Proposed decision

Defer multi-warehouse capability until an evidenced operational requirement and a complete domain design exist. Keep the existing inventory owner authoritative and keep stock behavior truthful for the current single-location model.

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

Only after these decisions and acceptance tests exist should a dedicated module/owner be selected. Do not preselect module boundaries or migration numbers in this deferral ADR.

## Alternatives considered

- **A — Defer and explicitly exclude multi-warehouse Dashboard surfaces while preserving current product-level inventory. RECOMMENDED for current evidence.** Does not invent capability and does not block unrelated commerce work.
- **B — Implement a complete warehouse/fulfillment-location domain now.** Viable only after a documented market requirement, owner model, stock migration and fulfillment allocation design; not justified by the current measured model.
- **C — Add Warehouse CRUD with a fixed/default location and map existing product stock into it. Rejected.** It creates misleading multi-warehouse semantics and risks future migration while still lacking allocation/transfer/fulfillment invariants.

## Required acceptance evidence for any future re-opening

- Real provider/warehouse ownership and permission matrix, including cross-seller isolation.
- PostgreSQL concurrency tests for reservations and allocation across multiple locations; no oversell under races.
- Transfer/adjustment audit trail and stock ledger reconciliation.
- Multi-location order allocation, split shipment, cancellation and return-to-stock E2E tests.
- Backfill rehearsal and reconciliation of current product stock/reserved values.
- Modulith tests proving inventory and fulfillment use declared public contracts.

## Work that may continue without this decision

Single-location product inventory and existing checkout reservation work may continue. Channel/catalog/pricing work not depending on warehouses may continue. Only the warehouse/stock multi-location roots and channelReorderWarehouses remain blocked/excluded pending the actual owner decision.

## Release impact

The full Saleor release cannot claim warehouse/stock root parity while this capability is excluded. If the owner accepts the deferral, A0 must explicitly exclude those roots and disable dependent Dashboard routes in the release inventory and divergence registry. This proposal alone does not change their current OWNER_ADR_REQUIRED status.
