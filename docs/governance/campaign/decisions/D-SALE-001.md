# D-SALE-001 — Legacy Sale operations versus catalogue Promotions

**Status:** PROPOSED — A0 recommendation; owner/A3/A7 review required.  
**Decision date:** 2026-10-31  
**Authority:** Pinned Saleor 3.23.43 SDL/API reference; D-SALEOR-FULL-001, P3.  
**Affected roots:** sale, sales, saleCreate, saleUpdate, saleDelete, saleBulkDelete, saleCataloguesAdd, saleCataloguesRemove, saleChannelListingUpdate, saleTranslate; promotions and catalog price presentation.  
**Related decisions:** D-TAX-001, D-CHANNEL-001.

## Context

Saleor 3.23.43 retains the `Sale` type and its operation family, but deprecation is per entry point, not blanket: `Query.sale` / `Query.sales`, `saleCreate`, `saleUpdate`, `saleDelete`, `saleCataloguesAdd`, `saleCataloguesRemove`, `saleTranslate` and `saleChannelListingUpdate` carry deprecation guidance; `type Sale` itself has no type-level deprecation and **`Mutation.saleBulkDelete` is not deprecated**. Therefore `saleBulkDelete` requires its own explicit disposition and must not be excluded merely by saying "Sale is deprecated". The pinned API distinguishes catalogue promotions (which affect catalog-visible product prices) from order promotions (which affect checkout/order totals). The canonical domain matrix assigns the Discounts family to marketplace-pricing; the central inventory records `sale` / `sales` as `BLOCKED_BY_DECISION` under D-SALE-001 and the eight sale mutations as `NOT_STARTED`. No decision may silently erase those roots or count a same-named promotion as equivalent.

## Proposed decision

Do **not** create a new long-lived Sale aggregate or duplicate discount engine. Keep **marketplace-pricing** as the single promotion/discount authority.

Use the following per-surface rule:

1. Keep `marketplace-pricing` as the single promotion/discount authority, but do not claim that its current `Promotion` entity is a catalogue-promotion model: the measured entity and `CommercialPricingService` are order-level and do not carry catalogue targets, per-channel catalogue listings, or a channel input; `ProductPricingPort` has no catalogue-discount seam and the current platform `ProductVariant` GraphQL type has no price/on-sale/discount fields. A catalogue-promotion capability must be designed and owned inside `marketplace-pricing` (using plain-UUID cross-module references and declared SPIs), not created as a second engine.
2. Before retaining a Saleor sale operation as an adapter, compare exact pinned fields and behavior: category/collection/product/variant targets, `Sale.type` and value, dates, translation, mutation errors, events and permissions. `SaleChannelListing.discountValue` is per-channel and includes its currency; Saleor `PromotionRule` fixed value is interpreted in the current channel currency and is one value for all channels on that rule. A lossless fixed-value mapping therefore requires one channel-scoped rule per channel (or another explicitly proved equivalent), not one shared fixed rule. No sale/channel-listing root may be enabled before D-CHANNEL-001 and that mapping matrix are accepted.
3. Where the mapping is not lossless, explicitly disposition that Sale root as EXPLICITLY_EXCLUDED (or the correct owner-approved inventory status), leave the unsupported wire surface undeclared, and disable every Dashboard route/control that requires it. `saleBulkDelete` must have an explicit row even though it is not deprecated. `saleTranslate` / `promotionTranslate` remain unsupported until a real translation owner and behavior exist. The Dashboard route audit is a required A7 deliverable; do not assume the deprecated API is unused.
4. Never return a successful Sale payload after merely creating a vaguely similar promotion. No placeholder CRUD and no silent no-op fields.
5. Keep promotion precedence, allocation and catalogue-vs-order behavior grounded in pinned Saleor 3.23.43 source/tests and the adopted saleor-core skill only for its documented discount-precedence/allocation rules. The measured platform currently applies the best order promotion and then a voucher to the post-promotion subtotal (they stack); the pinned Saleor order-discount path suppresses/clears the order promotion when an order voucher is present, while catalogue line promotions may coexist with an order-level voucher. This is an existing semantic divergence, not a presumed equivalence: A0 must either require conformance before catalogue-promotion release or register an owner-approved divergence and test the exact three-way precedence. The skill does not define Saleor tax, channel or gift-card behavior.
6. Keep existing promotion/voucher models and consumers backward compatible until a consumer inventory and migration gate authorizes deprecation. Do not rename existing platform operations or delete existing data as part of this decision.

## A0 Dashboard 3.23.39 route / operation evidence

The pinned Dashboard source at `bc75e988da0fa4fbc3d47378a888d859d070746f` requires a per-route, not family-wide, disposition:

- The visible `/discounts/sales`, `/discounts/sales/add` and `/discounts/sales/:id` paths in `src/discounts/index.tsx` are wired to `DiscountList`, `DiscountCreate` and `DiscountDetails`. Those components use `promotions`, `promotionCreate`, `promotionUpdate` and `promotionDelete`; the URL's legacy “sales” segment does **not** prove that the page calls `sales` or performs Sale CRUD. Keep this route only for the Promotion behavior that the platform actually supports, with ORDER versus CATALOGUE behavior separately gated.
- The separate Translations → Sales route is active: `src/translations/views/EntityLists/TranslationsSaleList.tsx` uses `translations(kind: SALE)`; `src/translations/views/TranslationsSales.tsx` uses the Sale translation details query and `updateSaleTranslations`; `src/translations/mutations.ts` calls `saleTranslate`. Therefore `saleTranslate` is a real retained Dashboard dependency. If no lossless Sale translation owner/mapping is approved, disable only the Sale translation route/control and retain evidence that this is an intentional capability exclusion.
- `src/discounts/queries.ts` still declares `saleList` and `saleDetails` documents, but the visible Discounts route components above use Promotion hooks. Do not count the static legacy documents as proof of an active Sale CRUD flow; conversely, do not count the route as proof that Sale translation is unused.
- No retained Dashboard operation using `saleBulkDelete` was found. Its non-deprecated upstream status still requires an explicit root disposition, but it is not a current Dashboard UI blocker in this pinned build.


## Alternatives considered

- **A — No new Sale aggregate; adapt legacy Sale roots to catalogue Promotions only after field-level semantic proof, otherwise explicitly exclude and disable the route. RECOMMENDED.** Avoids duplicate business state without pretending the two APIs are automatically identical.
- **B — Create a separate Sale aggregate alongside Promotion. Rejected.** Duplicates overlapping pricing rules, raises precedence ambiguity and creates two sources of truth.
- **C — Map every Sale operation directly to the existing generic Promotion object without a compatibility audit. Rejected.** It can convert catalogue price changes into order discounts and lose target/channel/date/value semantics.
- **D — Keep legacy Sale operations as separate deprecated functionality indefinitely. Not selected as the default.** It increases permanent maintenance cost and retains a deprecated contract; consider only if the Dashboard route audit demonstrates a required behavior that cannot yet be expressed by catalogue Promotions and an explicit owner approves the exception.

## Required acceptance evidence

- Pinned Saleor 3.23.43 source/schema tests prove the exact Sale fields, operations, mutation inputs and deprecation metadata.
- A mapping matrix covers every retained Sale field and mutation against the catalogue-promotion owner, with explicit rows for `saleBulkDelete` (not deprecated), `saleTranslate`, per-channel fixed values (one rule per channel), event mapping and exact exclusions.
- Catalogue price-presentation contract tests cover the required variant fields (`onSale`, `discount`, `price`, `priceUndiscounted`) and exact money/rounding; these remain blocked until the platform wire surface and owning pricing capability exist.
- Precedence tests prove catalogue line promotions can coexist with order-level vouchers, while order-level promotion/voucher interaction matches the pinned contract or is covered by an owner-approved divergence; tests also prove no same-level stacking.
- Price-parity tests prove catalogue promotions affect the same products/variants and amounts as the approved policy; order-promotion tests prove the two effects are not conflated.
- Tests cover stacking/precedence, date boundaries, channels, currency, category/collection/product targeting, translations, permission and audit.
- Dashboard 3.23.39 route/operation inventory identifies every call site for sale/sales/mutations; tests prove any excluded control is absent/disabled and no runtime request depends on it.
- The generated 458-root report and central inventory show an explicit disposition for every Sale root; no legacy root is silently dropped.

## Work that may continue without this decision

ORDER-type promotion and voucher work may continue where it does not depend on catalogue targeting or Sale compatibility. `promotion` / `promotions` order-level behavior is distinct from `CATALOGUE` semantics. Catalogue-type promotion rules, catalogue-targeting voucher roots (`voucherCataloguesAdd` / `voucherCataloguesRemove`), catalogue price-presentation fields and the dependent `productVariantDiscountedPriceUpdated` event remain blocked until the catalogue-promotion model, channel semantics and money-on-wire prerequisites are accepted. Sale roots remain unapproved for implementation or exclusion until the mapping and Dashboard route evidence are reviewed.

## Release impact

P3/P7 remain blocked for every retained Dashboard surface depending on Sale operations until the adapter is proven or the route/root is explicitly excluded by owner-approved evidence. This decision proposal does not change the root inventory, divergence registry or SDL.
