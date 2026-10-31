# D-CHANNEL-001 — Commercial channel as a first-class sales context

**Status:** PROPOSED — technical contract for implementation; full channel scope reaffirmed by the owner under D-SALEOR-FULL-001 D6 (2026-10-31).  
**Decision date:** 2026-10-31  
**Authority:** D-SALEOR-FULL-001, P3; frozen module/SPI contract §7.  
**Affected roots:** `channel`, `channels`, `channelCreate`, `channelUpdate`, `channelDelete`, `channelActivate`, `channelDeactivate`, `channelReorderWarehouses`, Channel settings and all channel-scoped product, variant, voucher, promotion, checkout, order, tax, shipping, payment, warehouse-membership and inventory operations. These remain part of the full Saleor target; none is omitted because an owner/module is currently incomplete.  
**Related decisions:** D-TAX-001, D-GC-001, D-SHOP-001, D-WHSE-001.

## Context

The pinned Saleor documentation describes channels as distinct commercial contexts: channel-specific product publication/availability, variant availability/pricing, tax strategy/country exceptions, vouchers/promotions, checkout, order visibility, payment settings, shipping and a single currency. It explicitly warns that channels are not a good substitute for dynamic currency conversion, customer-specific pricing or supplier-specific pricing.

The current platform does not have a first-class channel owner. This is the measured implementation gap, not a reason to exclude the capability. The earlier `ADR-0012 §5` single-sales-context deferral belongs to the narrower prior platform phase and is superseded for the pinned Saleor full-commerce contract by D-SALEOR-FULL-001 D6 (2026-10-31). A neighbourhood, persona, provider, legal identity or RBAC scope is not automatically a channel; the platform will create a distinct commercial-context model without conflating those concepts.

## Proposed decision

Create **marketplace-channels** as the owner of channel identity, the commercial-context lifecycle, channel configuration and channel-owned membership relationships. It owns:
- stable channel ID, name, slug, status, creation/audit facts and default country;
- one currency assigned at creation and immutable thereafter;
- active/default-channel lifecycle, with explicit safeguards for default reassignment;
- the public `ChannelContext` read contract (ID, currency, country, lifecycle state and policy-version references);
- channel-to-warehouse membership and ordering, including `channelReorderWarehouses` (warehouse entities and their stock remain catalog-owned);
- persistence/versioning of the channel's `stockSettings.allocationStrategy`; inventory execution and stock movements remain catalog-owned;
- archive/deactivation and the complete Saleor `channelDelete` workflow, including same-currency order migration where `ChannelDeleteInput.channelId` is provided, rejection when orders exist and no migration target is supplied, checkout cleanup, dependent relation cleanup, and audit/event publication. The migration must preserve the old commercial context in an immutable audit/order snapshot even where Saleor's current channel reference is moved.

It does **not** own or duplicate the domain state configured in a channel:

| Channel field / configuration | Sole write owner | Channel API boundary and security | Snapshot/freeze responsibility |
|---|---|---|---|
| Channel ID, name, slug, active state, immutable `currencyCode`, default country, default-channel lifecycle, public/private metadata | marketplace-channels | `channelCreate` / `channelUpdate`; `MANAGE_CHANNELS`; metadata is audited and private metadata is never projected as public data | Publish immutable `ChannelContext` (ID, currency, country, state) via public SPI/events |
| Product publication and channel availability | marketplace-catalog | Catalog-owned command/SPI; channel scope validated by Channels | Catalog supplies the versioned publication/availability facts used at checkout |
| Channel price lists and monetary rules | marketplace-pricing | Pricing-owned command/SPI | Pricing supplies the applied price/rule version to the order snapshot |
| Tax classes/rates and `taxConfiguration` | marketplace-pricing (D-TAX-001) | Pricing-owned tax configuration SPI; no duplicate Channel table | Pricing supplies rate/class/strategy versions to the order snapshot |
| Promotions/vouchers eligibility | marketplace-pricing | Pricing-owned command/SPI | Pricing supplies the resolved discount allocation and version |
| `orderSettings` (including the nested `channelUpdate(input.orderSettings)` path) | marketplace-orders is the single persistence/behavior owner | Preserve the pinned Saleor permission alternatives: `MANAGE_CHANNELS` is sufficient for the general channel update path; when the input contains only `orderSettings`, `MANAGE_ORDERS` is an alternative permission. If the update combines `orderSettings` with non-settings Channel fields, require `MANAGE_CHANNELS`. Do not incorrectly require both permissions for an orderSettings-only update. Audit actor/reason/time | Orders freezes the effective order policy/version on each order |
| `checkoutSettings` | marketplace-orders (the measured `OrdersService implements CheckoutSpi` owner) | Orders/checkout SPI; Channels validates context but does not persist checkout policy | Orders freezes the applicable checkout policy at placement |
| `paymentSettings` | marketplace-payments | Payments-owned SPI; channel context is passed as a validated scope; apply owner permission plus channel permission | Payments/Orders persist the actual tender/payment facts needed for historical order interpretation |
| Product/variant stock availability (current platform behavior) | Existing marketplace-catalog/inventory owner, with no location dimension | Existing catalog/inventory SPI; do not imply channel-specific or warehouse-scoped stock | Inventory facts used for reservation are recorded with the order/fulfillment snapshot |
| `stockSettings` / warehouse allocation strategy | `marketplace-channels` owns the channel setting and version; `marketplace-catalog` owns the warehouse/stock model and executes the selected allocation strategy through a declared SPI; `marketplace-orders` freezes the selected strategy/version on order placement | `channelUpdate` delegates the setting to the channel owner; catalog validates and executes `PRIORITIZE_SORTING` / `PRIORITIZE`, or the exact supported pinned enum values, through the inventory SPI | Orders records the selected warehouse allocations and strategy/version on the order/fulfillment snapshot |
| `warehouses`, `addWarehouses`, `removeWarehouses`, `channelReorderWarehouses` | `marketplace-catalog` owns Warehouse identities; `marketplace-channels` owns channel membership and ordering, using catalog's public WarehouseRead/InventoryMembership SPI | `channelCreate` / `channelUpdate` / `channelReorderWarehouses`; validate warehouse owner scope, currency/serviceability and channel permissions; no direct table access | The channel membership/order version is captured with order/fulfillment allocation facts |
| `addShippingZones`, `removeShippingZones`, channel `countries`, shipping-zone membership, `availableShippingMethodsPerCountry`, shipping methods and delivery options | marketplace-delivery owns the shipping-zone/method domain and the channel-membership relationship through its public SPI | Delivery-owned read/write SPI; channel context supplied as input. Channel adapter must not write delivery tables directly | Orders freezes the selected method, quote and applicable delivery facts |
| Channel-scoped staff authorization | identity/RBAC owner | Resource-scope authorization; channel ID knowledge is not authority | Audit records retain actor and channel scope |

Cross-module relationships are owned by the domain that owns the relationship. `channelUpdate` is a composite adapter command, not permission for marketplace-channels to persist every nested settings object: the adapter delegates each field to its sole owner SPI, validates both channel and owner permissions, and reports success only after all required owner operations succeed under a defined consistency contract. Other modules consume the Channels SPI/events; they do not read or update marketplace-channels tables directly. Avoid a central channel aggregate that becomes a god-object or creates a Modulith dependency cycle.

The measured freeze boundary is `marketplace-orders`: at order placement it captures the channel context and the versioned commercial facts returned by pricing, catalog, delivery and payments. Each domain remains the authority for its own policy; Orders owns the immutable historical order snapshot. A later channel-setting change must not rewrite that snapshot.

### Invariants

1. Channel currency is immutable after creation. A different currency or materially different sales policy requires a distinct channel; do not implement ad hoc currency conversion by mutating channel currency.
2. Channel is commercial context, not identity/persona/geography/provider partition. Supplier-specific/customer-specific price lists remain explicit pricing features; neighbourhood visibility and user authorization remain their respective owners.
3. `channelDelete` remains a real Saleor operation. With an input target channel, require a distinct target with the same immutable currency, delete origin-channel checkouts and migrate orders atomically to the target as Saleor 3.23.43 does; preserve the origin channel ID, currency, prices, tax, delivery and tender facts in immutable historical snapshots/audit. Without a target, reject deletion when orders exist. A channel with no orders may be deleted after its live dependent relationships are reconciled. The Dashboard delete flow remains available and reports exact validation errors.
4. A request carrying a channel must validate that the channel exists, is active for the requested operation, and is compatible with the relevant entity and principal. Do not infer authorization from knowledge of a channel ID.
5. The channel context and effective policy versions must be frozen by `marketplace-orders` onto checkout/order where required to preserve currency, pricing, tax, delivery, tender and reporting history. Configuration owners supply their versioned facts; they do not update historical orders.
6. Channel-level configuration is delegated to the owner in the table above. No duplicate ChannelPrice, tax, stock, promotion or payment state is added to the Channels module merely to satisfy the Dashboard.
7. Warehouse membership and `channelReorderWarehouses` are first-class parts of the full channel contract. Their persisted ordering is owned by marketplace-channels; warehouse stock and allocation remain owned by catalog under D-WHSE-001.
8. A channel's legal entity/issuer relationship is explicit. Channel is not itself proof of merchant-of-record or seller authorization.

## A0 Dashboard 3.23.39 route / operation evidence

The pinned Dashboard 3.23.39 source at `bc75e988da0fa4fbc3d47378a888d859d070746f` has active Channel operations:

- `src/channels/mutations.ts` defines `channelCreate`, `channelUpdate`, `channelDelete`, `channelActivate`, `channelDeactivate`, and `channelReorderWarehouses`; the Channels list/details components expose the related routes and controls.
- `channelUpdate` is also used by channel setup/details for channel-scoped settings and membership changes. The upstream input carries order, checkout, payment and stock settings, plus warehouse and shipping-zone assignment fields; these are not safe to satisfy with a generic Channel row update.
- The Channel details setup path creates a warehouse and assigns it through Channel update; the inventory card exposes warehouse assignment/create actions. These controls depend on D-WHSE-001 and must be capability-gated in platform mode.
- Keep the complete Channels list/details/setup flows, including delete, activate/deactivate, warehouse assignment/create/reorder, shipping-zone assignment and nested settings. The workbench is an implementation requirement: the backend must supply every operation and payload used by the pinned Dashboard rather than hiding the control when its SPI is missing. `channelDelete` follows the pinned same-currency order-migration/checkout-deletion semantics with the historical snapshot safeguard above.


## Alternatives considered

- **A — First-class marketplace-channels module with delegated domain settings. RECOMMENDED.** Makes the commercial context explicit while retaining existing pricing, inventory, delivery, order and payment authorities.
- **B — Represent channel as a key/value configuration in marketplace-console. Rejected.** It cannot safely express immutable currency, commercial lifecycle, referential history and cross-domain eligibility invariants.
- **C — Reuse neighbourhood, provider or persona identifiers as channel IDs. Rejected.** These concepts have different lifecycle, purpose and security semantics.
- **D — Reuse geography/persona/provider scope instead of a real channel. Rejected.** These concepts do not define the currency, publication, tax, promotion, shipping and order context required by Saleor.

## Required acceptance evidence

- Modulith architecture tests prove channel identity has one owner and no cyclic dependencies; channel-dependent owners use public SPIs/events.
- A field-level manifest for `Channel` and `channelUpdate` proves the sole write owner, permission, SPI, adapter behavior and order-freeze rule for Channel metadata, `orderSettings`, `checkoutSettings`, `paymentSettings`, `taxConfiguration`, `stockSettings`, `addShippingZones`/`removeShippingZones`, warehouse assignments, country projection and `availableShippingMethodsPerCountry`. It distinguishes Channel projection fields from actual `ChannelUpdateInput` fields: `taxConfiguration` is owned by pricing through its tax mutation/SPI, not written as a nested `channelUpdate` field.
- Permission/audit tests reproduce the pinned permission alternatives: `MANAGE_CHANNELS` for general Channel updates; `MANAGE_CHANNELS` OR `MANAGE_ORDERS` for an orderSettings-only update; `MANAGE_CHANNELS` OR `MANAGE_CHECKOUTS` for a checkoutSettings-only update; `MANAGE_CHANNELS` OR `HANDLE_PAYMENTS` for paymentSettings-only updates. If multiple nested setting fields are supplied without `MANAGE_CHANNELS`, require every corresponding specialized permission. Mixed non-settings fields require `MANAGE_CHANNELS`.
- Lifecycle tests cover `channelActivate`, `channelDeactivate` and the complete `channelDelete` paths: no-order deletion, order deletion rejected without target, same-currency target migration, target-self rejection, currency mismatch rejection, atomic checkout cleanup, historical context/audit, relationship cleanup and event publication. Dashboard journeys exercise the actual delete dialog and read-back.
- PostgreSQL constraints/integration tests prove currency immutability, unique slug/ID rules, safe default-channel transitions and no deletion of referenced channels.
- Cross-channel tests cover catalog publication, price, tax, discount/voucher, shipping, warehouse membership/allocation, payment and checkout/order access; unauthorized or inactive channel use fails closed.
- Frozen-order tests prove channel currency/context does not drift after order placement.
- Tests prove persona, geography, provider ID and legal entity ID cannot be silently substituted for a channel.
- Saleor parity tests compare channel create/update/delete/activate/deactivate/reorder signatures and field behavior to pinned 3.23.43, including warehouse memberships and all nested ChannelUpdateInput fields.

## Work that may continue without this decision

Channel, catalog, pricing, tax, promotion, shipping, payment and order work must converge on the declared ChannelContext/SPI contracts. Unrelated work can proceed in parallel; no P3 root remains blocked solely by ADR-0012 §5's previous single-channel assumption.

## Release impact

P3/P7 cannot claim Saleor channel parity until all channel roots, ChannelUpdateInput fields, memberships, permissions, snapshots and Dashboard journeys pass their acceptance gates. D-SALEOR-FULL-001 D6 resolves the scope/owner-decision blocker; the remaining state is implementation and test work, not permission to omit the channel model or disable routes. Central inventory/divergence updates remain A0-controlled and occur only after evidence on the reconciled candidate SHA.
