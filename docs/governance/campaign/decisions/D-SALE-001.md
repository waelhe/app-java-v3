# D-SALE-001 — Legacy Sale operations versus catalogue Promotions

**Status:** PROPOSED — A0 recommendation; owner/A3/A7 review required.  
**Decision date:** 2026-10-31  
**Authority:** Pinned Saleor 3.23.43 SDL/API reference; D-SALEOR-FULL-001, P3.  
**Affected roots:** sale, sales, saleCreate, saleUpdate, saleDelete, saleBulkDelete, saleCataloguesAdd, saleCataloguesRemove, saleChannelListingUpdate, saleTranslate; promotions and catalog price presentation.  
**Related decisions:** D-TAX-001, D-CHANNEL-001.

## Context

Saleor's 3.23.43 API reference still contains the Sale object and operations, but explicitly marks Sale deprecated and says to use Promotion instead. The same pinned API distinguishes catalogue promotions (which affect catalog-visible product prices) from order promotions (which affect checkout/order totals). The canonical domain matrix currently assigns the Discounts family to marketplace-pricing; the root inventory still lists the legacy sale mutations as NOT_STARTED. No decision may silently erase those pinned roots or count a same-named promotion as equivalent.

## Proposed decision

Do **not** create a new long-lived Sale aggregate or duplicate discount engine. Keep **marketplace-pricing** as the single promotion/discount authority.

Use the following per-surface rule:

1. Use the existing Promotion model only where the actual semantics are compatible. In particular, a Sale that reduces product/catalogue prices must map to a catalogue-promotion policy, not be flattened into an order promotion.
2. Before retaining a Saleor sale operation as an adapter, compare the exact pinned Sale input/output fields, catalogue targets (categories/collections/products/variants), date range, discount type/value, channel listings, translation and mutation error behavior with the platform Promotion use case. Implement a lossless mapping only where every exposed field has an authoritative owner and persisted behavior.
3. Where the mapping is not lossless, explicitly disposition that Sale root as EXPLICITLY_EXCLUDED (or the correct approved inventory status), leave the unsupported wire surface undeclared, and disable every Dashboard route/control that requires it. The Dashboard route audit is a required A7 deliverable; do not assume the deprecated API is unused.
4. Never return a successful Sale payload after merely creating a vaguely similar promotion. No placeholder CRUD and no silent no-op fields.
5. Keep promotion precedence, allocation and catalogue-vs-order behavior grounded in the pinned Saleor source/tests and the adopted saleor-core skill only for its documented discount-precedence/allocation rules. The skill does not define Saleor tax, channel or gift-card behavior.
6. Keep existing promotion/voucher models and consumers backward compatible until a consumer inventory and migration gate authorizes deprecation. Do not rename existing platform operations or delete existing data as part of this decision.

## Alternatives considered

- **A — No new Sale aggregate; adapt legacy Sale roots to catalogue Promotions only after field-level semantic proof, otherwise explicitly exclude and disable the route. RECOMMENDED.** Avoids duplicate business state without pretending the two APIs are automatically identical.
- **B — Create a separate Sale aggregate alongside Promotion. Rejected.** Duplicates overlapping pricing rules, raises precedence ambiguity and creates two sources of truth.
- **C — Map every Sale operation directly to the existing generic Promotion object without a compatibility audit. Rejected.** It can convert catalogue price changes into order discounts and lose target/channel/date/value semantics.
- **D — Keep legacy Sale operations as separate deprecated functionality indefinitely. Not selected as the default.** It increases permanent maintenance cost and retains a deprecated contract; consider only if the Dashboard route audit demonstrates a required behavior that cannot yet be expressed by catalogue Promotions and an explicit owner approves the exception.

## Required acceptance evidence

- Pinned Saleor 3.23.43 source/schema tests prove the exact Sale fields, operations, mutation inputs and deprecation metadata.
- A mapping matrix covers every retained Sale field and mutation against the Promotion owner, with explicit unowned fields and exact exclusions.
- Price-parity tests prove catalogue promotions affect the same products/variants and amounts as the approved policy; order-promotion tests prove the two effects are not conflated.
- Tests cover stacking/precedence, date boundaries, channels, currency, category/collection/product targeting, translations, permission and audit.
- Dashboard 3.23.39 route/operation inventory identifies every call site for sale/sales/mutations; tests prove any excluded control is absent/disabled and no runtime request depends on it.
- The generated 458-root report and central inventory show an explicit disposition for every Sale root; no legacy root is silently dropped.

## Work that may continue without this decision

Promotion/voucher implementation may continue where it does not depend on Sale compatibility. Catalog and pricing tests may proceed. Sale roots remain unapproved for implementation or exclusion until the mapping and Dashboard route evidence are reviewed.

## Release impact

P3/P7 remain blocked for every retained Dashboard surface depending on Sale operations until the adapter is proven or the route/root is explicitly excluded by owner-approved evidence. This decision proposal does not change the root inventory, divergence registry or SDL.
