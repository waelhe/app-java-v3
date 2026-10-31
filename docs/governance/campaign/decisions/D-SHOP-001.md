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

Mutations are commands delegated to the relevant owner. A field may appear in the GraphQL projection only if its owner supports a real read contract; a mutation may appear as supported only if its authoritative owner has a real write use case, permission policy, audit and persistence. Unsupported fields remain undeclared or explicitly classified; no synthetic defaults and no settings-shaped success response.

The GraphQL adapter must not return secrets, PSP credentials, signing keys, private provider details or internal feature flags.

## Alternatives considered

- **A — Aggregated read projection with owner-delegated commands. RECOMMENDED.** Matches a modular monolith and avoids a god-object.
- **B — One large Shop aggregate owning channels, tax, providers, stock, shipping and payments. Rejected.** Duplicates existing bounded contexts, invites cycles and creates a second source of truth.
- **C — Map Saleor Shop to marketplace-console SystemSettings. Rejected.** Operational settings are not authoritative commercial configuration and can include secrets/toggles.
- **D — Exclude the entire Shop surface. Viable only if every corresponding Dashboard route is disabled.** Safer than fake settings, but forfeits useful platform-level profile and configuration views.

## Required acceptance evidence

- A field-ownership manifest maps every retained Shop field and mutation to one module SPI/use case; duplicates, unowned fields and direct cross-module repository access fail CI.
- Modulith tests prove the projection has no cyclic dependency and consumes only public interfaces.
- GraphQL integration tests prove field-by-field authorized reads and command round trips against the owning modules.
- Security tests prove secrets and internal settings never appear in GraphQL responses, logs or error payloads.
- Dashboard tests prove unsupported settings are not editable and do not display a success state.
- Change/audit tests prove profile and commercial configuration mutations record actor, reason and timestamp.

## Work that may continue without this decision

Existing operational configuration owned by console and existing independent domain settings remain valid. Channel/tax work may proceed under their separate ADRs. No general Shop CRUD or SDL declarations are authorized by this proposal.

## Release impact

P3/P7 must retain an honest disabled/unsupported disposition for Shop routes until each field has an owner and evidence. This proposal does not turn shop into an implemented query and does not change the divergence registry or inventory.
