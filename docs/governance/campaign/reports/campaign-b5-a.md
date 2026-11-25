# Campaign packet campaign-b5-a — completion report

**Packet:** the Saleor Products-domain `productVariant` / `productVariants` read roots (P2).
**Executed by:** A0 directly, in-branch, on the single candidate lane (the sequential single-writer flow; no agent branches, no PRs).
**Base:** `9c33d6ec8f` (the b4-c/b4-d close).
**Commits:** `c93943069e` (the catalog owner) -> `cad3b26d72` (the GraphQL boundary + the sibling DgsTest mock fix) -> the governance close (this report, the ledger, the divergence row, `SYSTEM.md`).

## What the Saleor pinned contract demands (3.23.43)

- `productVariant(id: ID, sku: String, externalReference: String, channel: String): ProductVariant` — "Look up a product variant by ID or SKU. Requires one of the following permissions to include the unpublished items: MANAGE_ORDERS, MANAGE_DISCOUNTS, MANAGE_PRODUCTS."
- `productVariants(ids: [ID!], channel: String, filter: ProductVariantFilterInput @deprecated, where: ProductVariantWhereInput, search: String, sortBy: ProductVariantSortingInput, before: String, after: String, first: Int, last: Int): ProductVariantCountableConnection` — "List of product variants…" (the same include-unpublished permission note).
- `ProductVariantSortField` closes over exactly TWO values: `LAST_MODIFIED_AT` and `ID` (the latter added in 3.23).
- The `ProductVariant` type's upstream closure is wide (metadata, channel-scoped pricing/listings, attributes, weight, reporting, media, translations) — the platform owns none of those capabilities at the variant boundary yet.

## The honest platform mapping

**The population (the packet's central decision):** the public variant population is the TWO-ROW gate the platform's own reservation write carries verbatim — the variant's ACTIVE shelf AND the parent product's ACTIVE shelf (`ProductVariantRepository.reserve`'s own predicate as a read; Saleor's own public-variant population is the variants of published products). The ADMIN capability's scroll and lookup widen to every state and every parent (the include-unpublished mapping, the same registered divergence family the product roots carry).

**The scrolls are REAL SQL keyset scrolls, both populations:**
- Public: NATIVE keyset SQL (the parent EXISTS over the plain `product_id` column cannot be a derived method) — the expanded row-value comparison over the executed sort keys, BOTH soft-delete filters stated explicitly (native SQL gets no `@SoftDelete`), the pageSize+1 fetch whose full fetch IS `hasNext`.
- ADMIN: the derived `Window`-returning methods (the `scrollStaffUsers` precedent) — no gate at all, the framework's own keyset machinery with Hibernate's soft-delete filter.
- BOTH pinned sort fields execute in both directions: `LAST_MODIFIED_AT` (the entity's own non-null `updated_at` audit column, `id` tiebreaker) and `ID` (the unique key — also the default sort).
- ONE cursor semantics: the public path's windows are hand-built from the native rows' own sort keys through `Window.from`, so the boundary's one `EncodingCursorStrategy` encodes both paths identically; the ONE `KeysetCursorGate` decodes `after` with the executed sort's own key set.

**The bounded wire closure:** `id` (typed global ID, tag `ProductVariant`), `name` (NULLABLE — the row's own optional column; the registered divergence from upstream's non-null name; a legacy free-form variant answers the honest null), `sku` (the seller's own SKU — non-null here), `product: Product!` (the parent's bounded projection rendered through the SAME `Product` wire shape the product roots serve — one shape, one codec, one ID vocabulary), `status` (the platform extension fact: ACTIVE/SUSPENDED/ARCHIVED). The parent projections load as ONE batched `findAllById` per page — never a per-row N+1.

**The classified boundaries (declared on the wire, explicit errors, never silent no-ops):** `productVariants`' `ids` (no owning id-set query capability yet), `filter` (deprecated upstream), `where`, `search`, `channel` (no channel model — the PENDING-ADR commercial-context row), `before`/`last` (forward-only); `productVariant`'s `sku` (the platform's SKU is seller-namespaced by `uq_product_variants_provider_sku`, NOT globally unique — a bare global lookup is ambiguous), `externalReference` (no column), `channel`, and the keyless call (the recorded divergence from upstream's null answer).

**Referential honesty:** a variant whose parent row no longer resolves (a soft-deleted parent) answers the safe absence even for ADMIN — include-unpublished widens lifecycle states, never referential integrity, and a null `Product!` is never rendered.

## The defects this packet found and fixed on the candidate

The b4-a/b4-c packets added `CategoryDataFetcher` and `ProductDataFetcher` to the shared GraphQL adapter package WITHOUT adding their read-SPI mocks to the seven older package-slice DgsTests (User/Customer/PermissionGroup/ProductType/Attribute/Promotion/Voucher) and the Category test (missing `ProductReadSpi`). Every one of those contexts would have failed to boot — a surefire-suite RED the candidate's CI never caught (no completed run on any head after `9d053707a7`). This packet fixed all eight files (and the Product test) and added `ProductVariantReadSpi` mocks across the whole set — the package slice boots again as one unit.

## Evidence

- `marketplace-catalog`: `ProductVariantReadSpi`/`ProductVariantView`/`ProductVariantSort`/`ProductVariantPage` (the `catalog-spi` named interface — no Modulith change needed), `ProductVariantReadService`, the repository's four native public scrolls + four derived ADMIN scrolls + the public count, `ProductVariantReadServiceTest` (13 tests: both populations, both sort fields, both directions, the cursor-bind extraction, the two-row data-scope negatives, the guards, the batched parents).
- `marketplace-app`: `ProductVariantDataFetcher`/`ProductVariantGraphQlMapper`, the additive-only SDL (2161 -> 2371 lines: 2 Query roots + the bounded `ProductVariant` + connection/edge + the bounded filter/where inputs + the verbatim sorting enum), the `graphqlProductVariantQuery` limiter (50/min fail-fast, the catalog read budget), `ProductVariantDataFetcherDgsTest` (19 tests) and `ProductVariantGraphQlIntegrationTest` (26 tests: the two-row permission-aware population legs with BOTH data-scope negatives, cursor follow-through in both sort fields, the round-trip global ID, the guessed-ID null, the VAL-001 legs, the anonymous 401 problem+json, the nameless honest null, the empty page, every classified boundary).
- Governance: the ledger reads 20 INTEGRATED / 458 (the two variant rows, `candidate_commit` filled); the divergence registry carries the variant type-closure/population row; `SYSTEM.md`'s derived failsafe selection count reads 175 (the new integration-test file).

## Tests

- Written and committed: 13 unit (catalog) + 19 DGS + 26 integration.
- Run locally: NOT run (the sandbox has no Docker/JDK build for this Maven reactor; the campaign's execution discipline is GitHub-side commits with CI as the executor). The next completed CI run on the packet head is the proof — monitored simply, never waited on.
- The known RED this packet inherits and repairs: the 9d05370 JaCoCo marketplace-catalog 0.68 < 0.70 gate (fixed by the already-merged coverage tests at `ca2a6baff6`) and the sibling DgsTest context boots (fixed here).

## Ledger

`productVariant`, `productVariants` -> `INTEGRATED` (20/458). No other rows touched.
