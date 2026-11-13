# Completion Report — campaign-b4-a

```
WORKSTREAM: A2 / P2
BASE SHA: 9d053707a73dff0bca7ca286dfd1412a14a1fe64 (the candidate head this packet started from)
HEAD SHA: the commit sequence carrying this report on integration/saleor-full-commerce-20261021
  (JOB 1 was developed on agent/saleor-p2-catalog-categories-read and merged by A0 as ca2a6baff6;
  JOB 2 and the documentation close were implemented IN-BRANCH by A0 under the owner-directed
  sequential single-writer flow — see COMMITS TO INTEGRATE)
ROOTS CLAIMED: category, categories
ROOTS IMPLEMENTED + EVIDENCE:
  JOB 1 (coverage-gate restoration — the RED "Tests — modules (JDK 25)" gate on 9d05370:
  JaCoCo marketplace-catalog instructions 0.68 < 0.70, root-caused to the b3 read services
  landing without unit tests) — implemented by the sequential agent, merged by A0:
    - marketplace-catalog/src/test/java/com/marketplace/catalog/AttributeReadServiceTest.java
      (+484): every reachable branch of AttributeReadService — both lookups (null/unknown/found
      keys, the bilingual nameEn-else-nameAr rendering), the in-memory keyset scroll in BOTH
      owning sorts and BOTH directions with the id tiebreaker, the keyset predicate (cursor
      follow-through both directions, the string-form id parsing leg, the malformed-cursor
      rejections: backward keyset, other-sort property, missing id key, unparseable id), the
      limit legs (absent/under/over), the empty collection, the totalCount fact.
    - marketplace-catalog/src/test/java/com/marketplace/catalog/ProductTypeReadServiceTest.java
      (+374): the same branch-complete proof for ProductTypeReadService.
  JOB 2 (the Saleor category/categories read roots) — implemented by A0 in-branch:
    - categories — delegated to the catalog owner through the new CategoryReadSpi.
      scrollCategories: the in-memory forward-only keyset scroll over the bounded reference
      registry (the AttributeReadService precedent's own documented scope decision for
      reference collections — the categories registry is the controlled vocabulary the
      listing category column references, read through the repository's whole-table seam;
      SQL keyset methods replace it without changing the seam if the registry ever grows).
      The owning sort NAME (the rendered bilingual name) executes deterministically with the
      id tiebreaker; a nameless row's ordering key is the empty string (total and non-null
      for the keyset positions) while its wire name stays the honest null. Connection
      envelope from the Window's own per-element positions through the shared
      EncodingCursorStrategy with the house KeysetCursorGate over (name, id). totalCount =
      the registry's real count, clamped to GraphQL Int. Page sizes from GraphQlProperties
      (default 20, hard max 100, first<=0 answers the empty page with totalCount null).
    - category — the bounded lookup: id executes through the typed global-ID codec (type tag
      Category; unknown-but-well-formed answers null; malformed/raw-UUID/mistyped answers the
      codec's uniform classified VAL-001 error); slug executes through the owner's code seam
      (findByCode — the code-as-slug mapping the attribute slice recorded); id wins when both
      are sent (upstream's own precedence).
    - PUBLIC-ROOT authorization mapping (the packet's own decision, recorded as a divergence
      row): Saleor's category/categories are PUBLIC storefront roots — no permission for
      published content — and the platform registry carries no publication flag, so the
      owning use case enforces NO role gate and the include-unpublished surface is not
      applicable; the platform's authentication boundary for this surface is the /graphql
      chain itself (401 anonymous). The ADMIN-gated identity/attribute slices are the
      staff-root contrast — never a silent approximation of either.
    - Declared-but-unexecuted wire arguments answer explicit classified
      IllegalArgumentExceptions (VAL-001 through the house resolver): filter, where, level
      (the flat registry owns no tree), before/last (the forward-only family), the unowned
      sort fields PRODUCT_COUNT/SUBCATEGORY_COUNT, and the category root's slugLanguageCode
      (no translation owner), externalReference (no external-ID column) and the keyless call
      shape — never a silent no-op, never a fake success.
    - SDL (A0 stewardship, integrated in-branch — the sequential single-writer flow): the two
      Query roots appended at the end of the Query block; the bounded Category type (id/name/
      nameAr/slug/position — name NULLABLE per the registered divergence, nameAr and position
      the two documented PLATFORM EXTENSION facts), CategoryCountableConnection/Edge (the
      declared-shape precedent; PageInfo reused), the bounded LanguageCodeEnum (AR/EN — the
      registry's own bilingual vocabulary, so the slugLanguageCode argument's declared wire
      shape is honest), the bounded CategoryFilterInput (search/ids/slugs) and
      CategoryWhereInput (ids), CategorySortingInput + the verbatim CategorySortField enum;
      every unowned Saleor field documented with its reason in the SDL comments. ADDITIVE-ONLY
      edits (the GraphQL Schema Compatibility Gate contract).
    - application.yml: the graphqlCategoryQuery rate-limiter instance at the shared catalog
      read budget (50/min, fail-fast — the graphqlCatalogAttributeQuery precedent).
    - The generated DGS types (Category, CategoryCountableConnection/Edge, the inputs and
      enums) materialize through the standard codegen at build time — the designed campaign
      flow; the adapter and tests reference them exactly as the attribute slice's do.
    Evidence: marketplace-catalog/src/main/java/com/marketplace/catalog/CategoryReadService.java,
    spi/CategoryReadSpi.java, spi/CategoryView.java, spi/CategoryPage.java, spi/CategorySort.java
    (@NamedInterface catalog-spi — the module's existing named interface, already exposed in
    the app's Modulith allow-list: no package-info change needed); marketplace-app/src/main/
    java/com/marketplace/app/graphql/CategoryDataFetcher.java, CategoryGraphQlMapper.java;
    tests CategoryReadServiceTest / CategoryDataFetcherDgsTest / CategoryGraphQlIntegrationTest.
ROOTS ADAPTED/EXCEPTED + REGISTRY ROW:
  - "Category read roots (category/categories) type closure" — ADAPT /
    IMPLEMENTATION_REQUIRED (registered): the bounded closure, the code-as-slug mapping, the
    nullable name divergence, the platform extension facts, the total empty-string ordering
    key for nameless rows, and every undeclared Saleor field with its reason (metadata
    family, SEO fields, rich-text descriptions, the parent/children/ancestors/level tree,
    the products connection — the P2 products slice's own later fact, translations,
    externalReference, updatedAt — no typed DateTime scalar on this surface).
  - "Category public-root authorization mapping" — ADAPT /
    IMPLEMENTATION_REQUIRED (registered): the public/permission split mapping above.
  - before/last — the forward-only contract's own recorded boundary (the standing family
    row; no separate row requested).
FILES CHANGED:
  JOB 1 (merged via agent branch ca2a6baff6):
    marketplace-catalog/src/test/java/com/marketplace/catalog/AttributeReadServiceTest.java (new)
    marketplace-catalog/src/test/java/com/marketplace/catalog/ProductTypeReadServiceTest.java (new)
  JOB 2 + close (A0, in-branch on the candidate):
    marketplace-catalog/src/main/java/com/marketplace/catalog/CategoryReadService.java (new)
    marketplace-catalog/src/main/java/com/marketplace/catalog/spi/CategoryReadSpi.java (new)
    marketplace-catalog/src/main/java/com/marketplace/catalog/spi/CategoryView.java (new)
    marketplace-catalog/src/main/java/com/marketplace/catalog/spi/CategoryPage.java (new)
    marketplace-catalog/src/main/java/com/marketplace/catalog/spi/CategorySort.java (new)
    marketplace-catalog/src/test/java/com/marketplace/catalog/CategoryReadServiceTest.java (new)
    marketplace-app/src/main/java/com/marketplace/app/graphql/CategoryDataFetcher.java (new)
    marketplace-app/src/main/java/com/marketplace/app/graphql/CategoryGraphQlMapper.java (new)
    marketplace-app/src/main/resources/graphql/schema.graphqls (additive-only: 2 Query roots
      + 9 type declarations, 1683 -> 1897 lines)
    marketplace-app/src/main/resources/application.yml (the graphqlCategoryQuery limiter
      instance, following the graphqlCatalogAttributeQuery precedent)
    marketplace-app/src/test/java/com/marketplace/app/graphql/CategoryDataFetcherDgsTest.java (new)
    marketplace-app/src/test/java/com/marketplace/app/graphql/CategoryGraphQlIntegrationTest.java (new)
    docs/governance/campaign/sdl/P2/b4-a-categories-read.graphqls (new — the SDL record)
    docs/governance/campaign/saleor-458-root-inventory.csv (category/categories rows ->
      INTEGRATED; candidate_commit left for A0's post-CI fill)
    docs/governance/plans/saleor-contract-divergences.csv (+2 rows)
    SYSTEM.md (the derived Failsafe selection count truth-sync: 172 -> 173 — the new
      CategoryGraphQlIntegrationTest)
    docs/governance/campaign/reports/campaign-b4-a.md (this report)
  NOT touched (verified): Category.java, CategoryRepository.java (the owner's own entity and
    port — reused as-is, zero owner-file changes), StoreCategory* (a DIFFERENT legacy
    dictionary — never conflated), every pom.xml, package-info files (catalog-spi named
    interface already covers the new SPI), existing Flyway migrations, .github/workflows,
    PROJECT_MAP.md, docs/governance/plans/* registries other than the divergence registry.
SPI/CONTRACT CHANGES REQUESTED: none — the sequential single-writer flow executed the SDL
  integration, the limiter declaration and the ledger/registry updates in-branch directly
  (the A0 pre-authorization the sequential protocol records in the campaign worklog).
DATABASE MIGRATIONS ADDED: none — the read roots carry no schema change (V253-V260 remain
  allocated to A2 and unused by this packet; the registry is an existing table).
TESTS RUN: none locally — no local Maven/JDK in the A0 sandbox (verified: mvn is absent and
  the only JVM is a headless JRE without javac; the project targets Java 25). Logic verified
  by inspection against the merged precedents (AttributeReadService + its DgsTest/
  IntegrationTest pairs, the committed AttributeReadServiceTest API surface — ScrollPosition.
  forward/backward, null-limit, Window.positionAt casts) and against the pinned Saleor
  3.23.43 schema fetched live for the exact root signatures and type closures.
TESTS NOT RUN + REASON: CategoryReadServiceTest / CategoryDataFetcherDgsTest /
  CategoryGraphQlIntegrationTest — written, not run locally (no Maven/JDK in the sandbox);
  compilation and the suites are proven by the candidate branch's GitHub Actions runs
  (the merge commits land them on the candidate; the next Actions run on the branch head is
  the proof — never assumed green in advance).
SECURITY/AUTHZ/OWNERSHIP NEGATIVES:
  - anonymous rejection: CategoryGraphQlIntegrationTest.anonymousCategoryQueryIsRejectedAs401-
    ProblemDetail (the /graphql chain's 401 problem+json AUTHN-001 contract with the RFC 6750
    bare-realm challenge, asserted for the categories journey itself — the public-root
    surface's own authentication boundary).
  - public-root allow path (the mapping's own positive proof):
    CategoryDataFetcherDgsTest.consumerCallerReadsThePublicCategoryList +
    CategoryGraphQlIntegrationTest.consumerSeesTheSaleorEnvelopeOverTheRealRegistry (an
    authenticated CONSUMER caller reads the SAME registry the staff does — Saleor's
    storefront-root semantics behind the chain; the ADMIN-gated slices are the contrast).
  - unauthenticated fetcher guard: CategoryDataFetcherDgsTest.unauthenticatedCallerAnswers-
    TheCredentialsError (the credentials error, never a silent read).
  - guessed-ID null + guessed-slug null: integration guessedButWellFormedGlobalIdAnswersNull-
    NotAnError / guessedSlugAnswersNullNotAnError.
  - malformed/raw-UUID/mistyped global-ID rejection: integration malformedGlobalIdAnswers-
    TheClassifiedValidationError / rawUuidWhereAGlobalIdIsExpectedAnswersTheClassified-
    ValidationError / mistypedGlobalIdAnswersTheClassifiedValidationError (VAL-001, the
    codec's uniform "Invalid global ID" message — no type-discrimination leak); the list's
    own emitted IDs are asserted to be exactly base64("Category:<uuid>") typed global IDs.
  - client-crafted cursor rejection: DGS cursorFromAnotherSortIsRejectedThroughTheRealCursor-
    Gate + integration cursorFromAnotherSortIsRejectedByTheCursorGate (VAL-001); the service
    unit test covers the backward-keyset / other-property / missing-id / unparseable-id
    rejections.
  - declared-but-unexecuted arguments never silently no-op: DGS + integration
    filter/where/level/backward-pagination/unowned-sort-field/slugLanguageCode/external-
    Reference/keyless legs (each asserting the explicit classified error).
KNOWN GAPS / BLOCKERS:
  - The adapter/tests compile only through the DGS codegen of the newly integrated SDL —
    the designed campaign flow, proven by the candidate branch's CI run on the commit
    sequence (a pending run is never reported green).
  - The category tree family (parent/children/ancestors/level, PRODUCT_COUNT/
    SUBCATEGORY_COUNT) stays unimplemented: the platform registry is FLAT by its own
    contract — a tree owner decision would be a new work packet, never a fake surface.
  - The products connection on Category belongs to the P2 products slice (its own packet).
  - filter/where execute nothing (the honest boundary above); when the owning query
    capability lands, the bounded input shapes may grow toward Saleor's full closure with
    their owning types (updatedAt ranges need the DateTime-scalar family decision).
COMMITS TO INTEGRATE:
  1) JOB 1 (already on the candidate): ca2a6baff6 — integrate(agent-b4a): catalog read-service
     unit tests — restore the marketplace-catalog JaCoCo 0.70 gate (merged from
     agent/saleor-p2-catalog-categories-read head 6e70621c9e)
  2) feat(catalog): category read use case over the catalog owner — packet campaign-b4-a
  3) feat(graphql): Saleor category/categories read roots — packet campaign-b4-a
  4) docs(campaign): b4-a ledger + divergence registry + report — packet campaign-b4-a
  (2-4 land directly on integration/saleor-full-commerce-20261021 under the sequential
  single-writer flow; the branch consolidation record 7494b484b8 sits between 1 and 2.)
```
