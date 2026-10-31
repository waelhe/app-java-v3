# D-CHANNEL-001 — Commercial channel as a first-class sales context

**Status:** PROPOSED — A0 recommendation; owner approval required.  
**Decision date:** 2026-10-31  
**Authority:** D-SALEOR-FULL-001, P3; frozen module/SPI contract §7.  
**Affected roots:** channel, channels, channelCreate, channelUpdate, channelDelete, channelActivate, channelDeactivate; channel arguments on products, variants, vouchers, promotions, checkout, orders, tax, shipping and payments; channelReorderWarehouses (deferred under D-WHSE-001). `channelDelete` is proposed as unsupported until a lossless, audited deletion/transfer contract is proved; deactivation/archive is the supported lifecycle.  
**Related decisions:** D-TAX-001, D-GC-001, D-SHOP-001, D-WHSE-001.

## Context

The pinned Saleor documentation describes channels as distinct commercial contexts: channel-specific product publication/availability, variant availability/pricing, tax strategy/country exceptions, vouchers/promotions, checkout, order visibility, payment settings, shipping and a single currency. It explicitly warns that channels are not a good substitute for dynamic currency conversion, customer-specific pricing or supplier-specific pricing.

The current platform does not have a first-class channel owner. The divergence register correctly blocks the Saleor channel roots and channel-scoped arguments. A neighbourhood, persona, provider, legal identity or RBAC scope is not automatically a channel.

## Proposed decision

Create **marketplace-channels** as the owner of channel identity and commercial-context lifecycle. It owns:
- stable channel ID, name, slug, status, creation/audit facts and default country;
- one currency assigned at creation and immutable thereafter;
- active/default-channel lifecycle, with explicit safeguards for default reassignment;
- a compact public ChannelContext read contract for other modules, including ID, currency, country and lifecycle state;
- archive/deactivation semantics and channel-history retention.

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
| `stockSettings` / warehouse allocation strategy | Future inventory/fulfillment allocation owner; no supported owner or behavior in the current global-stock model | Unsupported pending D-WHSE-001 and a real warehouse/location allocation contract; no successful no-op setting or synthetic warehouse allocation | No channel allocation snapshot until a location-aware model exists |
| `warehouses`, `addWarehouses`, `removeWarehouses`, `channelReorderWarehouses` | No owner accepted in this proposal; deferred by D-WHSE-001 | Undeclared/unsupported until a warehouse decision and real membership SPI exist | No synthetic warehouse IDs or memberships |
| `addShippingZones`, `removeShippingZones`, channel `countries`, shipping-zone membership, `availableShippingMethodsPerCountry`, shipping methods and delivery options | marketplace-delivery owns the shipping-zone/method domain and the channel-membership relationship through its public SPI | Delivery-owned read/write SPI; channel context supplied as input. Channel adapter must not write delivery tables directly | Orders freezes the selected method, quote and applicable delivery facts |
| Channel-scoped staff authorization | identity/RBAC owner | Resource-scope authorization; channel ID knowledge is not authority | Audit records retain actor and channel scope |

Cross-module relationships are owned by the domain that owns the relationship. `channelUpdate` is a composite adapter command, not permission for marketplace-channels to persist every nested settings object: the adapter delegates each field to its sole owner SPI, validates both channel and owner permissions, and reports success only after all required owner operations succeed under a defined consistency contract. Other modules consume the Channels SPI/events; they do not read or update marketplace-channels tables directly. Avoid a central channel aggregate that becomes a god-object or creates a Modulith dependency cycle.

The measured freeze boundary is `marketplace-orders`: at order placement it captures the channel context and the versioned commercial facts returned by pricing, catalog, delivery and payments. Each domain remains the authority for its own policy; Orders owns the immutable historical order snapshot. A later channel-setting change must not rewrite that snapshot.

### Invariants

1. Channel currency is immutable after creation. A different currency or materially different sales policy requires a distinct channel; do not implement ad hoc currency conversion by mutating channel currency.
2. Channel is commercial context, not identity/persona/geography/provider partition. Supplier-specific/customer-specific price lists remain explicit pricing features; neighbourhood visibility and user authorization remain their respective owners.
3. A channel with orders/checkouts or other historical references is never hard-deleted. The proposed contract does not expose `channelDelete` at all until a compatible reference-transfer/deletion design and tests exist; Dashboard delete controls remain absent/disabled. Deactivate/archive it for new commercial activity and preserve historical resolution.
4. A request carrying a channel must validate that the channel exists, is active for the requested operation, and is compatible with the relevant entity and principal. Do not infer authorization from knowledge of a channel ID.
5. The channel context and effective policy versions must be frozen by `marketplace-orders` onto checkout/order where required to preserve currency, pricing, tax, delivery, tender and reporting history. Configuration owners supply their versioned facts; they do not update historical orders.
6. Channel-level configuration is delegated to the owner in the table above. No duplicate ChannelPrice, tax, stock, promotion or payment state is added to the Channels module merely to satisfy the Dashboard.
7. channelReorderWarehouses remains blocked by D-WHSE-001; this proposal does not establish a warehouse model.
8. A channel's legal entity/issuer relationship is explicit. Channel is not itself proof of merchant-of-record or seller authorization.

## Alternatives considered

- **A — First-class marketplace-channels module with delegated domain settings. RECOMMENDED.** Makes the commercial context explicit while retaining existing pricing, inventory, delivery, order and payment authorities.
- **B — Represent channel as a key/value configuration in marketplace-console. Rejected.** It cannot safely express immutable currency, commercial lifecycle, referential history and cross-domain eligibility invariants.
- **C — Reuse neighbourhood, provider or persona identifiers as channel IDs. Rejected.** These concepts have different lifecycle, purpose and security semantics.
- **D — Add no channel concept and explicitly exclude all channel-dependent Dashboard functionality. Fallback only.** Truthful for a reduced scope, but inconsistent with the full Saleor commerce target and multi-market use case.

## Required acceptance evidence

- Modulith architecture tests prove channel identity has one owner and no cyclic dependencies; channel-dependent owners use public SPIs/events.
- A field-level manifest for `Channel` and `channelUpdate` proves the sole write owner, permission, SPI, adapter behavior and order-freeze rule for Channel metadata, `orderSettings`, `checkoutSettings`, `paymentSettings`, `taxConfiguration`, `stockSettings`, `addShippingZones`/`removeShippingZones`, warehouse assignments, country projection and `availableShippingMethodsPerCountry`. It distinguishes Channel projection fields from actual `ChannelUpdateInput` fields: `taxConfiguration` is owned by pricing through its tax mutation/SPI, not written as a nested `channelUpdate` field.
- Permission/audit tests reproduce the pinned permission alternatives: `MANAGE_CHANNELS` for general Channel updates; `MANAGE_CHANNELS` OR `MANAGE_ORDERS` for an orderSettings-only update; `MANAGE_CHANNELS` OR `MANAGE_CHECKOUTS` for a checkoutSettings-only update; `MANAGE_CHANNELS` OR `HANDLE_PAYMENTS` for paymentSettings-only updates. If multiple nested setting fields are supplied without `MANAGE_CHANNELS`, require every corresponding specialized permission. Mixed non-settings fields require `MANAGE_CHANNELS`.
- Lifecycle tests cover `channelActivate` and `channelDeactivate`; Dashboard and GraphQL contract tests prove `channelDelete` is not exposed as a successful operation while the proposed unsupported disposition remains, and prove no warehouse membership or reorder path is fabricated.
- PostgreSQL constraints/integration tests prove currency immutability, unique slug/ID rules, safe default-channel transitions and no deletion of referenced channels.
- Cross-channel tests cover catalog publication, price, tax, discount/voucher, shipping and checkout/order access; unauthorized or inactive channel use fails closed.
- Frozen-order tests prove channel currency/context does not drift after order placement.
- Tests prove persona, geography, provider ID and legal entity ID cannot be silently substituted for a channel.
- Saleor parity tests compare channel create/update signatures and field behavior to pinned 3.23.43. Channel warehouse reordering stays unsupported until D-WHSE-001 is decided.

## Work that may continue without this decision

Pricing, tax-rate and promotion work that is genuinely global or does not require channel-scoped behavior may proceed. P2 catalog work may continue without channel publication semantics. Channel-dependent arguments and roots stay blocked until approval and implementation evidence.

## Release impact

P3/P7 cannot claim Saleor channel parity while the commercial-context owner is unresolved. This proposal changes no SDL, migration, root status or divergence row and does not authorize A3 to implement the blocked channel roots.
