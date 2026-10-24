# Completion Report — campaign-b4-c

```
WORKSTREAM: A2 / P2
BASE SHA: a42884f7fb (the candidate head this packet started from, after b4-b)
HEAD SHA: the commit sequence carrying this report on integration/saleor-full-commerce-20261021
  (implemented IN-BRANCH by A0 under the owner-directed sequential single-writer flow)
ROOTS CLAIMED: product, products
ROOTS IMPLEMENTED + EVIDENCE:
  products — delegated to the catalog owner through the new ProductReadSpi.scrollProducts:
    a REAL SQL forward-only keyset scroll (the scrollStaffUsers derived-method precedent — the
    products table is populated commerce data, never bounded reference data) through five new
    ProductRepository methods (findByOrderByTitleAsc/DescIdAsc/Desc, findByStatusOrderByTitle-
    Asc/DescIdAsc, countByStatus), the owning NAME sort (the title column) deterministic with the
    id tiebreaker. THE PERMISSION-AWARE POPULATION (the packet's core decision, recorded as a
    divergence row): Saleor's products roots are public storefront reads whose include-
    unpublished variant carries MANAGE_ORDERS/MANAGE_DISCOUNTS/MANAGE_PRODUCTS — mapped through
    the platform's ADMIN capability (the SaleorPermissionCatalog contract) enforced in the owning
    use case: the public population is the entity's own ACTIVE lifecycle slice with ITS count
    (countByStatus); the ADMIN caller's scroll includes SUSPENDED/ARCHIVED/DRAFT with the whole
    population's count. Connection envelope from the Window's own per-element positions through
    the shared EncodingCursorStrategy with the house KeysetCursorGate over (name, id); totalCount
    is the CALLER-SCOPE population's count; page sizes from GraphQlProperties.
  product — the bounded lookup through ProductReadSpi.findProductById over the SAME
    permission-aware readable population: id executes through the typed global-ID codec (type
    tag Product); an unknown but well-formed ID answers null; a KNOWN but unpublished row
    answers the safe null for non-ADMIN callers (the data-scope boundary — never an error,
    never a leak) and is readable by the ADMIN capability.
  The bounded Product type: id, name (the entity's own title), productType (the optional
    reusable-goods-schema reference rendered as the typed global ID of the EXISTING ProductType
    root's type — null keeps schema-less legacy products honest), and status (the display
    lifecycle state name — a documented PLATFORM EXTENSION fact). Money and stock facts stay
    off the wire (the frozen money contract's registered row and the inventory slice's own
    later fact).
  Declared-but-unexecuted wire arguments answer explicit classified IllegalArgumentExceptions
    (VAL-001 through the house resolver): filter, where, search, channel (no channel model —
    the PENDING-ADR commercial-context row), before/last, the attributeId sort leg, the thirteen
    unowned ProductOrderField values, and the product root's slug (no slug column),
    slugLanguageCode, externalReference, channel and keyless call shape.
  SDL (A0 stewardship, integrated in-branch): the two Query roots + the bounded Product type +
    ProductCountableConnection/Edge + ProductOrder/ProductOrderField (the fourteen pinned values
    verbatim with upstream's deprecation notes) + the bounded ProductFilterInput{search,ids,
    slugs}/ProductWhereInput{ids} — ADDITIVE-ONLY (verified no name collisions, balanced
    braces), 1897 -> 2161 lines. application.yml: the graphqlProductQuery limiter instance.
  Evidence: marketplace-catalog/src/main/java/com/marketplace/catalog/ProductReadService.java,
  ProductRepository.java (five new keyset/count methods), spi/ProductReadSpi.java,
  spi/ProductView.java, spi/ProductPage.java, spi/ProductSort.java (@NamedInterface catalog-spi
  — already exposed in the app's Modulith allow-list); marketplace-app/src/main/java/com/
  marketplace/app/graphql/ProductDataFetcher.java, ProductGraphQlMapper.java; tests
  ProductReadServiceTest / ProductDataFetcherDgsTest / ProductGraphQlIntegrationTest.
ROOTS ADAPTED/EXCEPTED + REGISTRY ROW:
  - "Product read roots (product/products) type closure" — ADAPT / IMPLEMENTATION_REQUIRED
    (registered): the bounded closure, the productType reference mapping, the status extension
    fact, and every undeclared Saleor field with its reason (metadata family, SEO fields,
    rich-text descriptions, pricing connection — the frozen money contract, variants/media/
    attributes connections, category — the product's own dictionary is the STORE-categories
    vocabulary, never conflated with the Saleor Category registry, weight/sku/rating,
    translations, updatedAt).
  - "Products public-root permission-aware population mapping" — ADAPT /
    IMPLEMENTATION_REQUIRED (registered): the public/include-unpublished split mapped onto the
    entity's own lifecycle column through the ADMIN capability.
  - before/last — the forward-only contract's own recorded family row; no separate row
    requested.
FILES CHANGED:
  marketplace-catalog/src/main/java/com/marketplace/catalog/ProductRepository.java (modified:
    five new derived keyset/count methods — the module's own repository, A2-owned)
  marketplace-catalog/src/main/java/com/marketplace/catalog/ProductReadService.java (new)
  marketplace-catalog/src/main/java/com/marketplace/catalog/spi/ProductReadSpi.java (new)
  marketplace-catalog/src/main/java/com/marketplace/catalog/spi/ProductView.java (new)
  marketplace-catalog/src/main/java/com/marketplace/catalog/spi/ProductPage.java (new)
  marketplace-catalog/src/main/java/com/marketplace/catalog/spi/ProductSort.java (new)
  marketplace-catalog/src/test/java/com/marketplace/catalog/ProductReadServiceTest.java (new)
  marketplace-app/src/main/java/com/marketplace/app/graphql/ProductDataFetcher.java (new)
  marketplace-app/src/main/java/com/marketplace/app/graphql/ProductGraphQlMapper.java (new)
  marketplace-app/src/main/resources/graphql/schema.graphqls (additive-only, 1897 -> 2161 lines)
  marketplace-app/src/main/resources/application.yml (the graphqlProductQuery limiter instance)
  marketplace-app/src/test/java/com/marketplace/app/graphql/ProductDataFetcherDgsTest.java (new)
  marketplace-app/src/test/java/com/marketplace/app/graphql/ProductGraphQlIntegrationTest.java (new)
  docs/governance/campaign/sdl/P2/b4-c-products-read.graphqls (new — the SDL record)
  docs/governance/campaign/saleor-458-root-inventory.csv (product/products -> INTEGRATED)
  docs/governance/plans/saleor-contract-divergences.csv (+2 rows)
  SYSTEM.md (Failsafe selection count 173 -> 174)
  docs/governance/campaign/reports/campaign-b4-c.md (this report)
  NOT touched (verified): Product.java (the owner's own entity — reused as-is), every pom.xml,
  package-info files, existing Flyway migrations, .github/workflows, PROJECT_MAP.md.
SPI/CONTRACT CHANGES REQUESTED: none — executed in-branch under the sequential single-writer
  flow (the A0 pre-authorization the campaign worklog records).
DATABASE MIGRATIONS ADDED: none — the read roots carry no schema change (V253-V260 remain
  allocated to A2; the scroll reads the existing products table through its own columns).
TESTS RUN: none locally — no local Maven/JDK in the A0 sandbox. Logic verified by inspection
  against the merged precedents (AttributeReadService + its three test layers, the
  UserRepository keyset-method shapes, the StoreProductKeysetGraphQlTest seeding pattern —
  the jdbcTemplate store_categories insert, the User provider seed, Product.register's own
  ACTIVE/DRAFT factory paths, suspend()) and against the pinned Saleor 3.23.43 schema fetched
  live for the exact products/product signatures, ProductOrder/ProductOrderField closures.
TESTS NOT RUN + REASON: the three suites — written, not run locally (no Maven/JDK in the
  sandbox); compilation and the suites are proven by the candidate branch's GitHub Actions
  runs on the commit sequence (never assumed green in advance).
SECURITY/AUTHZ/OWNERSHIP NEGATIVES:
  - permission-aware population (the packet's core): integration consumerSeesOnlyTheActive-
    PopulationWithItsOwnCount (status list containsOnly ACTIVE; the caller-scope total) vs
    adminSeesTheIncludeUnpublishedPopulationWithTheWholeCount (DRAFT/SUSPENDED present); unit
    ProductReadServiceTest publicCallerScrollsTheActivePopulationWithItsOwnCount (verifies the
    ACTIVE-scoped repository method AND count, never() the unscoped ones) and
    adminCallerScrollsTheIncludeUnpublishedPopulationWithTheWholeCount (the reverse).
  - data-scope negative (the lookup boundary): integration consumerLookupOfAnUnpublishedRow-
    AnswersTheSafeNull — the SAME DRAFT row answers null for the consumer and DRAFT for the
    admin in the same test leg (the row provably exists); unit unpublishedRowIsReadableOnlyBy-
    TheAdminCapability.
  - anonymous rejection: integration anonymousProductQueryIsRejectedAs401ProblemDetail (the
    /graphql chain's 401 problem+json AUTHN-001 contract asserted for the products journey).
  - unauthenticated fetcher guard: DGS unauthenticatedCallerAnswersTheCredentialsError.
  - guessed-ID null: integration guessedButWellFormedGlobalIdAnswersNullNotAnError.
  - malformed/raw-UUID/mistyped global-ID rejection: integration malformed/rawUuid/mistyped
    legs (VAL-001, the codec's uniform message); the list's own emitted IDs are asserted to be
    exactly base64("Product:<uuid>") typed global IDs.
  - client-crafted cursor rejection: DGS cursorFromAnotherSortIsRejectedThroughTheRealCursor-
    Gate (VAL-001).
  - declared-but-unexecuted arguments never silently no-op: DGS + integration
    filter/where/search/channel/backward/attribute-sort/unowned-sort/slug/externalReference/
    keyless legs.
KNOWN GAPS / BLOCKERS:
  - The adapter/tests compile only through the DGS codegen of the newly integrated SDL — the
    designed campaign flow, proven by the candidate branch's CI run.
  - The products pricing/variants/media/attributes connections belong to their own later
    slices (money waits for the frozen money contract's rendering decision).
  - filter/where/search execute nothing (the honest boundary); when the owning query
    capability lands, the bounded input shapes may grow toward Saleor's full closure.
  - warehouse(s)/stock(s) roots are BLOCKED_BY_DECISION D-WHSE-001 (no warehouse model on
    this platform — the stock facts live on the product row; the decision packet b4-d records
    it).
COMMITS TO INTEGRATE:
  1) feat(catalog): product read use case over the catalog owner — packet campaign-b4-c
  2) feat(graphql): Saleor product/products read roots — packet campaign-b4-c
  3) docs(campaign): b4-c ledger + divergence registry + report — packet campaign-b4-c
```
