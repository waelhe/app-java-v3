# D-TAX-001 — Tax ownership, tax classes and channel tax configuration

**Status:** PROPOSED — recommended option A; owner approval required before implementation.  
**Decision date:** 2026-10-31  
**Authority:** D-SALEOR-FULL-001, P3; frozen money contract §6.  
**Affected roots:** taxTypes, taxClass(es), taxConfiguration(s), taxCountryConfiguration(s), tax-class/configuration mutations, taxExemptionManage and shopFetchTaxRates; pricing, catalog, shipping and order snapshots.  
**Related decisions:** D-CHANNEL-001, D-SHOP-001, D-SETTINGS-001.

## Context and measured gap

The current pricing owner already contains the tax-rate capability identified by the divergence record: per-country active TaxRate rows, inclusive/exclusive mode, exact basis-point calculation and frozen order tax breakdown. The current owner does not yet contain Saleor's full tax-class model, per-class country rates or channel tax strategy/configuration. The divergence registry therefore correctly rejects projecting ordinary configured rates as a Saleor tax gateway.

Saleor's pinned 3.23.43 model separates:
- TaxClass: tax classification and country rates, assignable to products/product types and shipping methods.
- TaxConfiguration: channel-level tax charging/calculation/display strategy and country exceptions.
- TaxType: gateway-reported tax types/rates; it is not simply a list of locally configured statutory rates.

The distinction also applies to root dispositions: `Query.taxTypes` is explicitly deprecated in 3.23.43 with the reason "Use `taxClasses` field instead"; it is not the same capability as `taxClasses`. `Mutation.shopFetchTaxRates` is a separate, deprecated tax-gateway operation. Neither root may be satisfied by projecting the platform's local statutory `TaxRate` rows.

## Proposed decision — option A

Keep **marketplace-pricing** as the sole tax calculation and rate authority, and extend that bounded context to support tax classes and channel-scoped tax configuration. Do not create a second tax engine.

1. Introduce an explicit tax-class model with stable IDs, names and status. A migration must map existing products/rates without silently changing historical prices; the default class mapping requires an audited backfill decision (for example a standard/default class only where the current data proves that assumption).
2. Add country/jurisdiction rates per tax class with effective dates and audit history. Preserve the existing per-country TaxRate as a compatibility/default path during migration; do not delete or reinterpret V223-era data until a tested backfill proves equivalence.
3. Product/product-type and shipping-method assignments remain owned by their domains. They reference tax-class identifiers through declared SPI/contracts; pricing owns the classification/rates and the calculation, not the product or shipping aggregate. Resolve assignment deterministically: an explicit Product tax-class assignment overrides its ProductType assignment; ProductType is the fallback only when the Product has no assignment; ShippingMethod keeps its own independent tax-class assignment. Missing assignments must follow an explicitly approved fallback/backfill rule, never an arbitrary class selected by pricing.
4. Add channel tax configuration after D-CHANNEL-001 establishes a real channel identity. The configuration selects charging strategy, inclusive/exclusive display/input behavior and supported country exceptions. It is not a global console toggle.
5. Keep the existing exact-money policy: no binary floating point for authoritative amounts; preserve currency exponent/rounding rules and snapshot the applied tax class, rate version, jurisdiction, strategy and computed line/order tax at placement. Saleor's wire contract exposes `TaxClassCountryRate.rate` as `Float!` percentage; this is a boundary representation only. Convert and validate it at the GraphQL adapter boundary into exact decimal/basis-point form supported by the authoritative model; reject values that cannot be represented at the supported precision rather than silently rounding or truncating. Resolver output must preserve the pinned percentage semantics. Later rate/policy changes must not rewrite old orders.
6. Tax exemptions require explicit permission, an eligibility rule, evidence/identifier where legally required, reason, audit actor/time and negative tests. A Boolean bypass is not sufficient.
7. Design a provider SPI only if it isolates future dynamic-tax integration. Do not add AvaTax, TaxJar or another provider, network call or credentials until a jurisdiction/sales requirement and provider decision exist.
8. `Query.taxTypes` is deprecated upstream in favor of `taxClasses`, but its description still denotes gateway-reported tax types; do not treat the name/deprecation as permission to synthesize gateway data from local statutory rates. Keep this root explicitly unsupported/undeclared unless a genuine provider-owned tax-gateway contract is accepted. Do not invent a `taxCode`.
9. `Mutation.shopFetchTaxRates` is a separate deprecated gateway operation, not the read counterpart of locally configured `TaxRate`. Keep it unsupported/undeclared without a real Tax Gateway SPI and provider-owned response. In particular, neither `shopFetchTaxRates` nor `taxTypes` may read or serialize the local `marketplace-pricing.TaxRate` table as a fake gateway result.

## Alternatives considered

- **A — Extend marketplace-pricing with tax classes, class rates and channel configuration. RECOMMENDED.** Retains one calculation authority and evolves the real tax owner.
- **B — Keep only the present per-country default rate and explicitly exclude Saleor tax-class/configuration workbenches. Viable fallback, not recommended for the full-commerce target.** This preserves current truthful capability, but cannot represent product-class and channel-specific tax differences required by the pinned contract and multi-market business.
- **C — Add a separate tax microservice/provider now. Rejected.** No validated market/jurisdiction/provider need justifies a second calculation authority, extra operations or external dependency.

## Ownership boundaries

- marketplace-pricing: tax classes, tax-rate versions, channel tax strategy, calculation, rounding and tax breakdown.
- marketplace-catalog: product/product-type tax-class assignment through its own write model/SPI.
- Delivery/shipping owner: shipping method tax-class assignment through its own contract.
- marketplace-orders: immutable order tax snapshot and historical read model.
- marketplace-app: Saleor wire adapter only.
- Tax gateway/provider: absent until a separate accepted decision.

## Required acceptance evidence

- Historical-data migration/backfill tests prove legacy TaxRate behavior remains identical for existing products/orders.
- Calculation matrix tests cover tax class × jurisdiction × channel strategy × inclusive/exclusive price × shipping × exemptions, with exact currency precision and boundary/rounding cases.
- Assignment tests prove Product overrides ProductType, ProductType is the fallback only when no Product assignment exists, and ShippingMethod assignment is independent; missing assignment/backfill behavior is explicit and deterministic.
- GraphQL boundary tests prove `Float!` percentage values convert to/from exact internal decimal/basis-point values without silent precision loss; unsupported precision is rejected.
- Negative contract tests prove `taxTypes` and `shopFetchTaxRates` do not return local TaxRate rows or fake tax-gateway success.
- Effective-date tests prove new rates affect only orders calculated after their effective boundary; frozen-order tests prove historical tax is immutable.
- Authorization/audit tests cover tax configuration, tax class assignment and exemptions.
- Modulith verification proves catalog/shipping use pricing through published contracts, not private repositories; no cycle is introduced.
- Real PostgreSQL integration tests validate constraints and effective-rate selection.
- Pinned Saleor 3.23.43 contract tests compare the retained wire fields and mutation behavior. taxTypes remains explicitly unsupported without a real provider.

## Work that may continue without this decision

The existing per-country tax rate implementation and independent tax calculation tests remain valid. A3 may continue non-dependent promotions/pricing work and other NOT_STARTED roots whose semantics do not depend on tax classes or channel tax strategy. Tax-class/configuration read roots remain blocked; no schema or migration is authorized by this proposal.

## Release impact

P3/P7 cannot claim full tax-class/channel tax support until this decision is accepted and the tests above pass. The A0-owned divergence registry and inventory remain unchanged until owner approval. This proposal selects option A for review; it does not assert that option A has already been approved.
