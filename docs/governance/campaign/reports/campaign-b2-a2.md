# Completion Report — campaign-b2-a2 (A2 / P2)

**IMPLEMENTER DISCLOSURE:** this packet was implemented by **A0 (the Integration Lead) as a documented fallback**, not by an independent domain agent: three consecutive subagent launches failed with backend transport timeouts (context deadline exceeded) while the campaign's standing instruction is to continue executing the maximum safe portion without stopping. Phase D's independent reviewers (R1-R4) review this work like any other — the same agent being implementer and approver is exactly what the playbook guards against, and that guard is preserved by the independent review phase, which is still ahead.

```
WORKSTREAM: A2 / P2
BASE SHA: 87bc07faf8faa9bdd47d3337b7766e84ce080677
HEAD SHA: commit c369f3951e + the adapter commit on branch agent/saleor-p2-catalog-typed-attributes-read
ROOTS CLAIMED: productType, productTypes, attribute, attributes
ROOTS IMPLEMENTED + EVIDENCE:
  productType — owner delegation: ProductTypeReadSpi.findProductTypeById (marketplace-catalog/src/main/java/com/marketplace/catalog/spi/ProductTypeReadSpi.java) implemented in ProductTypeReadService with @PreAuthorize("hasRole('ADMIN')") at the use-case boundary (Saleor staff MANAGE_PRODUCTS-class → platform ADMIN, the RBAC registry row); id executed through SaleorGlobalIdCodec with exact type tag "ProductType" (no raw UUIDs on the wire); unknown-but-well-formed ID → null (safe absence); malformed/raw/mistyped → codec's classified VAL-001. Rendering in ProductTypeGraphQlMapper. Tests: ProductTypeDataFetcherDgsTest (read-back by typed global ID, envelope serialization, raw-UUID classified error, AUTHZ-001 owner denial through the real resolver, filter/backward/unowned-sort classified errors, cursor-gate mismatch, anonymous guard) + ProductTypeGraphQlIntegrationTest (round-trip of the list's own emitted ID with same-ID stability, guessed-ID null, malformed/raw-ID VAL-001 legs, provider + consumer denials by the real @PreAuthorize).
  productTypes — owner delegation: ProductTypeReadSpi.scrollProductGroups→scrollProductTypes forward-only keyset scroll (in-memory over the bounded reference collection — the repository's own whole-table findAllByOrderByCodeAsc seam is the codebase's standing precedent for reading product_types; documented: the SQL keyset replaces it if the collection ever grows beyond reference-data scale); NAME sort with id tiebreaker; filter/before/last answer explicit classified IllegalArgumentException (VAL-001 through the house resolver — never silent no-ops); non-positive first → empty page with totalCount null (staffUsers house contract). Envelope built from the Window's own per-element positions through the shared EncodingCursorStrategy + house KeysetCursorGate over (name, id). Tests: as above plus the keyset follow-through (no overlap, hasPreviousPage honest), NAME DESC ordering, bilingual fallback leg (nameEn-null seed renders its Arabic primary name on the wire), empty-page leg.
  attribute / attributes — NOT_STARTED (reported honestly): the A2 packet's priority order put productType/productTypes first; the attribute roots need their own bounded Attribute/AttributeValue closure over CatalogAttributeDefinition/CatalogAttributeOption and land in the next A2 packet.
ROOTS ADAPTED/EXCEPTED + REGISTRY ROW:
  ProductType bounded closure — undeclared: hasVariants/isShippingRequired/isDigital/kind/weight/taxType/productAttributes/variantAttributes/metadata family (no owning facts on product_types today; the attribute assignments will carry productAttributes/variantAttributes when the attribute packet lands its type closure). Requested registry row "Product-type read roots (productType/productTypes) type closure".
  Bilingual name rendering — name = nameEn ?? nameAr (the registered translation divergence's bilingual model). Covered by the same registry row.
  Filter input bounded closure — declared: search/ids/slugs; undeclared: configurable (deprecated, no effect), metadata (heavy closure), kind, productType (unowned facts). Covered by the same registry row.
  In-memory keyset over the bounded reference collection — documented scope decision (SQL keyset lands if the collection grows unbounded). Covered by the same registry row.
FILES CHANGED:
  marketplace-catalog/src/main/java/com/marketplace/catalog/spi/ProductTypeReadSpi.java (new)
  marketplace-catalog/src/main/java/com/marketplace/catalog/spi/ProductTypeView.java (new)
  marketplace-catalog/src/main/java/com/marketplace/catalog/spi/ProductTypeSort.java (new)
  marketplace-catalog/src/main/java/com/marketplace/catalog/spi/ProductTypePage.java (new)
  marketplace-catalog/src/main/java/com/marketplace/catalog/ProductTypeReadService.java (new)
  marketplace-app/src/main/java/com/marketplace/app/graphql/ProductTypeDataFetcher.java (new)
  marketplace-app/src/main/java/com/marketplace/app/graphql/ProductTypeGraphQlMapper.java (new)
  marketplace-app/src/test/java/com/marketplace/app/graphql/ProductTypeDataFetcherDgsTest.java (new)
  marketplace-app/src/test/java/com/marketplace/app/graphql/ProductTypeGraphQlIntegrationTest.java (new)
  docs/governance/campaign/sdl/P2/a2-typed-attributes-read.graphqls (new)
  docs/governance/campaign/reports/campaign-b2-a2.md (new)
SPI/CONTRACT CHANGES REQUESTED:
  1) application.yml (A0-owned): add resilience4j rate-limiter instance graphqlCatalogRead (base-config: default, limit-for-period: 50, timeout-duration: 0) — until declared, resilience4j falls back to the default config.
  2) SDL integration: splice this fragment's Query additions + new types into the shared schema and run DGS codegen (generates DgsConstants.QUERY.ProductTypes/ProductType + the bounded ProductType, ProductTypeCountableConnection/Edge, ProductTypeFilterInput, ProductTypeSortingInput, ProductTypeSortField).
  3) Divergence registry: the row above.
DATABASE MIGRATIONS ADDED: none — read-only roots over the existing product_types table (V253–V260 allocated, none used).
TESTS RUN (exact commands and observed result): none — no local Maven/JDK25 in the sandbox (verified: mvn/java unavailable); all code verified by inspection against the merged PR #694 / campaign-b1-a1 precedents and the exact upstream sources fetched for every API used (Spring Data Window.from/positionAt/hasNext, Limit.of/isLimited/max, KeysetScrollPosition keys/scrollsForward/isInitial, codec/gate/resolver behavior).
TESTS NOT RUN + REASON: ProductTypeDataFetcherDgsTest and ProductTypeGraphQlIntegrationTest — written, not run locally (no Maven/JDK25 in sandbox) AND they target the integrated state (they reference the generated types of the proposed SDL); compilation and full suites are proven by A0-dispatched GitHub Actions runs on the candidate branch after SDL integration + codegen.
SECURITY/AUTHZ/OWNERSHIP NEGATIVES:
  anonymous — DGS unauthenticatedCallerAnswersTheCredentialsError (fetcher guard) + the standing /graphql chain 401 tripartite proof.
  wrong-role — DGS ownerDenialMapsToTheAuthzTaxonomyThroughTheRealResolver (AUTHZ-001); integration providerCallerIsDeniedTheListByTheOwningUseCase and consumerCallerIsDeniedTheLookupByTheOwningUseCase (real @PreAuthorize in ProductTypeReadService).
  guessed-ID null — integration guessedButWellFormedGlobalIdAnswersNullNotAnError.
  malformed/mistyped/raw-UUID global IDs — DGS rawUuidWhereAGlobalIdIsExpectedAnswersTheClassifiedErrorThroughTheRealResolver; integration malformed + raw-UUID legs (VAL-001).
  client-crafted cursor — DGS cursorFromAnotherSortIsRejectedThroughTheRealCursorGate (VAL-001).
  declared-but-unexecuted args never no-op — DGS + integration filter, backward-pagination and unowned-sort legs.
KNOWN GAPS / BLOCKERS:
  attribute/attributes NOT_STARTED (next A2 packet — needs the bounded Attribute/AttributeValue closure).
  productAttributes/variantAttributes on ProductType undeclared until that closure exists.
  The adapter/tests do not compile until A0 integrates the SDL fragment and runs codegen (the designed campaign flow; assembly order is explicit).
COMMITS TO INTEGRATE: (filled at commit time — see the branch history)
```
