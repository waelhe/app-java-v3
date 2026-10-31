# D-SHOP-001 — Shop as an assembled projection, not a commerce god-object

**Status:** PROPOSED — A0 recommendation; owner approval required.  
**Decision date:** 2026-10-31  
**Authority:** D-SALEOR-FULL-001, P3; campaign module/SPI contract §7.  
**Affected roots:** shop, shopDomainUpdate, shopSettingsUpdate, shopSettingsTranslate, shopAddressUpdate, shopFetchTaxRates; Dashboard shop configuration routes.  
**Related decisions:** D-CHANNEL-001, D-TAX-001, D-SETTINGS-001, ADR-0013.

## Context and measured gap

The Saleor shop root groups settings for a running Saleor instance. The current marketplace-console SystemSettings key/value capability is an internal operational configuration/feature-flag surface, not a commercial shop profile or source of truth for currencies, tax policies, domains, seller legal identities or channel settings. The divergence registry therefore leaves the shop root undeclared pending an owner decision.

The platform is a multi-provider platform with commerce, services and other verticals. A single Shop entity must not represent all providers, their legal identities or commercial settings.

## Proposed decision

Implement the Saleor shop query as a read projection assembled from authoritative domain owners. A projection is not a new owner for the fields it displays, and it must not read private tables directly.

| Information | Authoritative owner |
|---|---|
| Platform public trading name, description, logo and general public contact profile | A narrowly scoped marketplace-shop-profile capability, unless an existing profile owner is proven to own these exact platform-level facts |
| Commercial channels and each channel's currency/status | marketplace-channels (D-CHANNEL-001) |
| Tax rates, classes and tax configuration | marketplace-pricing (D-TAX-001) |
| Platform merchant-of-record and settlement disclosures | Existing payments/ledger/legal policy owners, consistent with ADR-0013 |
| Provider/seller identity and legal entity details | marketplace-provider and relevant legal-identity owner |
| Product, availability and stock facts | Catalog/inventory owners |
| Shipping countries, methods and delivery options | marketplace-delivery |
| Internal operational toggles/secrets | marketplace-console configuration, not exposed as shop commerce data |

Only create marketplace-shop-profile if code inspection confirms there is no existing module with ownership of the platform-level public trading profile. Do not create it merely because Saleor has a Shop type.

### Pinned Shop field and mutation dispositions

| Surface | Proposed owner/disposition | Contract boundary |
|---|---|---|
| `includeTaxesInPrices`, `displayGrossPrices`, `chargeTaxesOnShipping` | marketplace-pricing under D-TAX-001, scoped to a real channel | These are tax/channel policy facts, not global Shop settings; do not expose them as a global setting before Channels/Tax owners exist |
| `channelCurrencies`, `defaultCountry` | marketplace-channels under D-CHANNEL-001 | Derived from real channel currency/default-country data; never maintained as a second Shop list |
| `useLegacyShippingZoneStockAvailability` | Existing catalog/inventory owner only if a matching capability is proved; otherwise unsupported | Do not synthesize legacy shipping-zone stock behavior from product stock counters; warehouse/zone dependencies remain gated |
| `shopFetchTaxRates` | Unsupported/undeclared without a genuine Tax Gateway SPI | It is a distinct deprecated provider/gateway operation; it must never return local `marketplace-pricing.TaxRate` rows |
| `shopDomainUpdate` | Unsupported/undeclared in this proposal | The pinned root is deprecated; no platform domain owner and audited domain lifecycle are established by the current Shop profile evidence |
| `shopSettingsTranslate` | Unsupported until the platform-profile owner and translation contract are established | The pinned mutation writes `headerText` and `description`; it is not a generic translation of all Shop fields |
| `shopAddressUpdate` | Unsupported until a single platform-level public/trading-address owner and replacement/deletion semantics are established | Must not overwrite or erase legal-entity/provider addresses or create success-shaped settings writes |
| `shopSettingsUpdate` | Per-field delegated command only; no generic key/value write | Every field needs a named owner, permission, SPI, audit and Dashboard route disposition |

### Pinned Shop read-projection dispositions

| Saleor `Shop` field family | Proposed owner/disposition | Contract boundary |
|---|---|---|
| `name`, `description`, `headerText`, `translation` | A narrow platform-level shop-profile capability only after A5 proves no existing owner | Never reuse provider/seller legal profiles; until an owner and translation SPI exist, keep unsupported |
| `channelCurrencies` | marketplace-channels | Derived from real channels; no duplicate currency list |
| `availableShippingMethods` | marketplace-delivery | Resolve only for the supplied channel and optional address; no global unscoped method list |
| `availablePaymentGateways` | marketplace-payments | Channel-scoped provider capability only; deprecated currency-only lookup must not become a second configuration path |
| `permissions` | identity/RBAC | Caller-specific effective permissions; do not return a static role list |
| `availableExternalAuthentications` | Unsupported until a real external-authentication provider/registry contract exists | The platform's own authentication flow is not a Saleor external-auth plugin registry |
| `countries`, `languages`, `phonePrefixes`, `defaultCountry` | Unsupported until a single authoritative locale/region configuration is measured | Do not confuse the global Shop default country with `Channel.defaultCountry` |
| `domain` | Read-only runtime deployment URL only if the deployed `PUBLIC_URL` equivalent is authoritative | `shopDomainUpdate` remains unsupported; no database setting may override deployment truth |
| `companyAddress` | Platform merchant-of-record/legal-entity owner under ADR-0013 | Never map to a seller/provider address; unsupported until the legal-entity owner contract exists |
| `defaultMailSenderName`, `defaultMailSenderAddress`, `staffNotificationRecipients` | Notification/mail configuration owner only if A5 proves an existing capability | Otherwise unsupported; never expose credentials or private provider configuration |
| `trackInventoryByDefault`, `defaultWeightUnit` | marketplace-catalog / inventory owner | Defaults only; must not rewrite existing ProductVariant tracking or stored measurements |
| `reserveStockDurationAnonymousUser`, `reserveStockDurationAuthenticatedUser`, `limitQuantityPerCheckout` | marketplace-orders checkout policy, with inventory reservation contract via SPI | Versioned policy; active checkouts/orders retain their frozen reservation/quantity rules |
| `fulfillmentAutoApprove`, `fulfillmentAllowUnpaid` | Unsupported pending explicit fulfillment lifecycle owner and permission/audit contract | Do not infer this from delivery quotes or courier assignment |
| `customerSetPasswordUrl`, `enableAccountConfirmationByEmail`, `allowLoginWithoutConfirmation`, `passwordLoginMode`, `accountConfirmMergeMode` | identity | Identity-owned commands and effective policy; no console key/value shadow |
| `allowStorefrontTraffic` | Unsupported as a public commerce setting until an explicit platform-security owner approves it | Do not let a GraphQL Shop mutation weaken the app's request-access boundary |
| `preserveAllAddressFields` | Unsupported pending a measured address-validation owner and country-specific behavior contract | No silent change to address normalization |
| `includeTaxesInPrices`, `displayGrossPrices`, `chargeTaxesOnShipping` | marketplace-pricing / delivery via D-TAX-001 and D-CHANNEL-001 | Deprecated Shop aliases; channel/tax-class semantics only, not global Shop switches |
| `useLegacyShippingZoneStockAvailability` | Unsupported under D-WHSE-001 until location/warehouse and delivery-zone semantics exist | Do not implement a legacy toggle over global product/variant stock |
| `useLegacyUpdateWebhookEmission` | Unsupported | No Saleor legacy webhook-emission mode is invented in the platform |
| `availableTaxApps`, `limits`, `announcements`, `version`, `schemaVersion` | Platform runtime/extension capability; expose only when an exact owned source exists | No fabricated app registry, limits, announcements or version metadata |

### Pinned ShopSettingsInput mutation dispositions

`shopSettingsUpdate` is a single Saleor mutation with `MANAGE_SETTINGS`; it is **not** a generic console settings write. Its exact input fields are mapped as follows:

- `name`, `headerText`, `description`, `metadata`, `privateMetadata`: platform shop-profile owner only; private metadata remains permission-protected.
- `trackInventoryByDefault`, `defaultWeightUnit`: catalog/inventory owner.
- `fulfillmentAutoApprove`, `fulfillmentAllowUnpaid`: unsupported until a real fulfillment lifecycle owner and matching state transitions are proven.
- `defaultMailSenderName`, `defaultMailSenderAddress`, `staffNotificationRecipients`: notification/mail configuration owner only if measured; otherwise unsupported.
- `customerSetPasswordUrl`, `enableAccountConfirmationByEmail`, `allowLoginWithoutConfirmation`, `passwordLoginMode`, `accountConfirmMergeMode`: identity owner.
- `reserveStockDurationAnonymousUser`, `reserveStockDurationAuthenticatedUser`, `limitQuantityPerCheckout`: orders/checkout owner; reservation mechanics use inventory SPI and immutable checkout/order policy snapshots.
- `allowStorefrontTraffic`: platform-security-owned deployment/API boundary; unsupported from GraphQL until an explicit safe control plane exists.
- `preserveAllAddressFields`: unsupported until an address-validation owner is measured.
- `useLegacyShippingZoneStockAvailability`: unsupported under D-WHSE-001; do not fake shipping-zone stock behavior.
- Deprecated `includeTaxesInPrices`, `displayGrossPrices`, `chargeTaxesOnShipping`: do not store global aliases; use the per-channel tax configuration and shipping-method TaxClass contract only.
- Deprecated `useLegacyUpdateWebhookEmission`: unsupported; never add a compatibility mode without actual legacy event semantics.

Mutations are commands delegated to the relevant owner. A field may appear in the GraphQL projection only if its owner supports a real read contract; a mutation may appear as supported only if its authoritative owner has a real write use case, permission policy, audit and persistence. Unsupported fields remain undeclared or explicitly classified; no synthetic defaults and no settings-shaped success response.

The GraphQL adapter must not return secrets, PSP credentials, signing keys, private provider details or internal feature flags.

## Alternatives considered

- **A — Aggregated read projection with owner-delegated commands. RECOMMENDED.** Matches a modular monolith and avoids a god-object.
- **B — One large Shop aggregate owning channels, tax, providers, stock, shipping and payments. Rejected.** Duplicates existing bounded contexts, invites cycles and creates a second source of truth.
- **C — Map Saleor Shop to marketplace-console SystemSettings. Rejected.** Operational settings are not authoritative commercial configuration and can include secrets/toggles.
- **D — Exclude the entire Shop surface. Viable only if every corresponding Dashboard route is disabled.** Safer than fake settings, but forfeits useful platform-level profile and configuration views.

## Required acceptance evidence

- A field-ownership manifest maps every retained Shop field and mutation to one module SPI/use case; duplicates, unowned fields and direct cross-module repository access fail CI. It explicitly covers the tax/channel fields above and all `shop*` mutations, including the separate unsupported disposition for `shopFetchTaxRates`.
- Modulith tests prove the projection has no cyclic dependency and consumes only public interfaces.
- GraphQL integration tests prove field-by-field authorized reads and command round trips against the owning modules.
- Security tests prove secrets and internal settings never appear in GraphQL responses, logs or error payloads.
- Dashboard tests prove unsupported settings are not editable and do not display a success state, including P5 Shop administration/PageType routes as well as P3/P7 dependencies.
- Change/audit tests prove profile and commercial configuration mutations record actor, reason and timestamp.

## Work that may continue without this decision

Existing operational configuration owned by console and existing independent domain settings remain valid. Channel/tax work may proceed under their separate ADRs. No general Shop CRUD or SDL declarations are authorized by this proposal.

## Release impact

P3/P5/P7 must retain an honest disabled/unsupported disposition for Shop administration and PageType-related routes until each field has an owner and evidence. P5 is included because the A5 campaign scope owns the Shop administration/PageType route audit; it is not implicitly cleared by this P3 proposal. This proposal does not turn shop into an implemented query and does not change the divergence registry or inventory.
