# D-CHANNEL-001 — Commercial channel as a first-class sales context

**Status:** PROPOSED — A0 recommendation; owner approval required.  
**Decision date:** 2026-10-31  
**Authority:** D-SALEOR-FULL-001, P3; frozen module/SPI contract §7.  
**Affected roots:** channel, channels, channelCreate, channelUpdate, channelDelete, channelActivate, channelDeactivate; channel arguments on products, variants, vouchers, promotions, checkout, orders, tax, shipping and payments.  
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

| Channel-dependent fact | Data owner |
|---|---|
| Product publication and channel availability | marketplace-catalog |
| Channel price lists and monetary rules | marketplace-pricing |
| Tax classes/rates and channel tax strategy | marketplace-pricing (D-TAX-001) |
| Promotions/vouchers eligibility | marketplace-pricing |
| Stock availability / reservation | Existing inventory/catalog owner until a warehouse decision |
| Shipping methods, zones and delivery options | marketplace-delivery |
| Checkout and order channel snapshot / visibility | marketplace-orders / checkout owner |
| External payment execution | marketplace-payments |
| Channel-scoped staff authorization | identity/RBAC owner, with channel context supplied as a resource scope |

Cross-module relationships are owned by the domain that owns the relationship. Other modules consume the Channels SPI/events; they do not read or update marketplace-channels tables directly. Avoid a central channel aggregate that becomes a god-object or creates a Modulith dependency cycle.

### Invariants

1. Channel currency is immutable after creation. A different currency or materially different sales policy requires a distinct channel; do not implement ad hoc currency conversion by mutating channel currency.
2. Channel is commercial context, not identity/persona/geography/provider partition. Supplier-specific/customer-specific price lists remain explicit pricing features; neighbourhood visibility and user authorization remain their respective owners.
3. A channel with orders/checkouts or other historical references is never hard-deleted. Deactivate/archive it for new commercial activity and preserve historical resolution.
4. A request carrying a channel must validate that the channel exists, is active for the requested operation, and is compatible with the relevant entity and principal. Do not infer authorization from knowledge of a channel ID.
5. The channel context must be frozen onto checkout/order where required to preserve currency, policy and reporting history.
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
- PostgreSQL constraints/integration tests prove currency immutability, unique slug/ID rules, safe default-channel transitions and no deletion of referenced channels.
- Cross-channel tests cover catalog publication, price, tax, discount/voucher, shipping and checkout/order access; unauthorized or inactive channel use fails closed.
- Frozen-order tests prove channel currency/context does not drift after order placement.
- Tests prove persona, geography, provider ID and legal entity ID cannot be silently substituted for a channel.
- Saleor parity tests compare channel create/update signatures and field behavior to pinned 3.23.43. Channel warehouse reordering stays unsupported until D-WHSE-001 is decided.

## Work that may continue without this decision

Pricing, tax-rate and promotion work that is genuinely global or does not require channel-scoped behavior may proceed. P2 catalog work may continue without channel publication semantics. Channel-dependent arguments and roots stay blocked until approval and implementation evidence.

## Release impact

P3/P7 cannot claim Saleor channel parity while the commercial-context owner is unresolved. This proposal changes no SDL, migration, root status or divergence row and does not authorize A3 to implement the blocked channel roots.
