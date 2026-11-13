# Completion Report — campaign-b3-a1b

```
WORKSTREAM: A1 / P1
BASE SHA: f402ab44e6313ca34928e60bbe6e12cb8f59447a
HEAD SHA: the commit that carries this report (commit 2 — the branch head;
  see COMMITS TO INTEGRATE)
ROOTS CLAIMED: customers
ROOTS IMPLEMENTED + EVIDENCE:
  customers — delegated to the identity owner through the new
    IdentitySpi.scrollCustomers (ADMIN-enforced inside UserService via
    @PreAuthorize, the MANAGE_ORDERS/MANAGE_USERS->platform-ADMIN mapping of
    the merged SaleorPermissionCatalog contract — the frozen authorization
    contract's owning-use-case boundary); the population is the SINGLE
    identity store's customer-account slice (the accounts whose primary role
    is CONSUMER — the closed UserRole vocabulary's own customer/consumer
    concept, the complementary customer-account population the staffUsers
    roster reads in full: no duplicate identity store, staff accounts
    excluded by the population's own scoping); forward-only keyset scroll in
    the three UserSortingInput sorts with an owning column
    (EMAIL/CREATED_AT/LAST_MODIFIED_AT, deterministic with the id
    tiebreaker) through six new role-scoped UserRepository keyset methods +
    countByRole (the derived Spring Data JPA scrolling contract, the
    staffUsers methods' own shape plus one role predicate); connection
    envelope built from the Window's own per-element positions through the
    shared EncodingCursorStrategy with the house KeysetCursorGate over the
    sort's own (property, id) key set; totalCount = the CUSTOMER
    population's own count (countByRole, never the roster's); row mapping is
    the SHARED UserGraphQlMapper (not forked); page sizes from
    GraphQlProperties (default 20, hard max 100, first<=0 answers the empty
    page with totalCount null). Declared-but-unexecuted wire arguments
    (filter, where, search — every field combination of the two bounded
    inputs — before/last, and the unowned UserSortField values
    FIRST_NAME/LAST_NAME/ORDER_COUNT/RANK) answer explicit classified
    IllegalArgumentExceptions (VAL-001 through the house resolver) — the
    staffUsers filter/before/last precedent, never a silent no-op.
    Evidence:
    marketplace-app/src/main/java/com/marketplace/app/graphql/CustomerDataFetcher.java,
    marketplace-identity/src/main/java/com/marketplace/identity/UserService.java
    (scrollCustomers), UserRepository.java (six role-scoped keyset methods +
    countByRole), IdentitySpi.java (scrollCustomers), tests
    CustomerDataFetcherDgsTest / CustomerGraphQlIntegrationTest (envelope,
    per-element cursors, delegation (sort + clamped limit), population
    scoping staff-excluded with the complementary roster comparison, cursor
    follow-through, round-trip global-ID read-back through the user root,
    filter/where/search/before/last/unowned-sort negatives, empty page,
    wrong-role denial, anonymous 401).
ROOTS ADAPTED/EXCEPTED + REGISTRY ROW:
  Population scoping — ADAPT (the packet's own disposition): the platform's
    customer population is the single identity store's CONSUMER-primary-role
    accounts (Saleor's own customers definition — registered non-staff users
    — maps to the closed vocabulary's customer/consumer role). Saleor's
    extra "staff users who have placed an order also appear" clause is NOT
    implemented (no orders owner exists yet — a later wave's fact).
    Requested divergence registry row 1) "Customer population scoping
    (customers root)" — ADAPT / IMPLEMENTATION_REQUIRED.
  filter / where / search — declared-but-unexecuted wire arguments answering
    explicit classified errors. The two input types are PLATFORM-SHAPED
    bounded declarations (the StaffUserInput precedent): only fields whose
    types are already shared vocabulary (search/ids on the filter; ids/
    isActive on the where). The undeclared fields of Saleor's full closure
    are documented with per-field reasons in the SDL fragment header
    (DateRangeInput/DateTimeRangeInput/IntRangeInput/StringFilterInput
    families undeclared — no owning query capability; numberOfOrders/
    placedOrders belong to the orders owner — later wave; metadata — no
    metadata store; firstName/lastName — no owning columns; addresses — no
    address store; customerType — no customer-type store (Saleor 3.23 late
    addition, PENDING owner decision); attributes — no attribute store;
    AND/OR/NOT — the composition family rides the same boundary).
    Requested divergence registry row 2) "Customer filter/where/search
    bounded input shapes (customers root)" — ADAPT /
    IMPLEMENTATION_REQUIRED.
  before/last — the forward-only contract's own recorded boundary (the
    staffUsers precedent's row covers the family; no separate row
    requested).
  Unowned sort fields — the staffUsers boundary's own recorded mapping (the
    shared UserSortField enum: EMAIL/CREATED_AT/LAST_MODIFIED_AT execute,
    the rest answer the classified error); no separate row requested.
FILES CHANGED:
  marketplace-identity/src/main/java/com/marketplace/identity/UserRepository.java
    (six role-scoped keyset scroll methods + countByRole — the module's own
    repository, A1-owned)
  marketplace-identity/src/main/java/com/marketplace/identity/spi/IdentitySpi.java
    (scrollCustomers added with contract javadoc)
  marketplace-identity/src/main/java/com/marketplace/identity/UserService.java
    (scrollCustomers: @PreAuthorize ADMIN + @Transactional(readOnly) impl)
  marketplace-app/src/main/java/com/marketplace/app/graphql/CustomerDataFetcher.java (new)
  marketplace-app/src/test/java/com/marketplace/app/graphql/CustomerDataFetcherDgsTest.java (new)
  marketplace-app/src/test/java/com/marketplace/app/graphql/CustomerGraphQlIntegrationTest.java (new)
  docs/governance/campaign/sdl/P1/a1b-customers-read.graphqls (new SDL
    fragment proposal)
  docs/governance/campaign/reports/campaign-b3-a1b.md (this report)
  NOT touched (verified): UserDataFetcher.java, UserGraphQlMapper.java (PR
  #694 files — the fetcher only INJECTS the shared mapper), the shared
  schema.graphqls, application.yml, every pom.xml, PermissionGroup* files,
  existing Flyway migrations, .github/workflows, SYSTEM.md, PROJECT_MAP.md,
  the 458-root inventory CSV, docs/governance/plans/**.
SPI/CONTRACT CHANGES REQUESTED:
  1) application.yml (A0-owned file) — add the rate-limiter instance (the
     graphqlUserQuery/graphqlPermissionGroupQuery precedent):
       resilience4j.rateLimiter.limiters.graphqlCustomerQuery:
         base-config: default / limit-for-period: 50 / timeout-duration: 0
     (the customers read root's named instance at the identity read budget;
     until declared, resilience4j falls back to the default config).
  2) SDL integration — splice the Query addition and the two bounded input
     types from docs/governance/campaign/sdl/P1/a1b-customers-read.graphqls
     into marketplace-app/src/main/resources/graphql/schema.graphqls and run
     DGS codegen (generates DgsConstants.QUERY.Customers and types
     CustomerFilterInput, CustomerWhereInput — the adapter and its tests
     compile only after this step, the designed campaign flow, the b1-a1
     precedent's own flagged order).
  3) Divergence registry — the two rows above.
  4) Campaign ledger — row 82 (customers) status NOT_STARTED -> IN_PROGRESS
     (or the assembly stage's own vocabulary once the A0 CI run proves the
     suites).
DATABASE MIGRATIONS ADDED: none — the read root carries no schema change
  (V248–V252 allocated but unused; the population is an existing column's
  value scoping over the existing users table).
TESTS RUN: none — no local Maven/JDK in the agent sandbox (verified: mvn is
  absent and the only JVM is a headless JRE 21 without javac; the project
  targets Java 25). Logic verified by inspection against the merged
  precedents (UserDataFetcher/UserDataFetcherDgsTest/
  UserGraphQlIntegrationTest and the b1-a1 PermissionGroup packet's
  PermissionGroupDataFetcher/PermissionGroupDataFetcherDgsTest/
  PermissionGroupGraphQlIntegrationTest), against the exact upstream
  Spring-Data/Spring-for-GraphQL API surfaces the precedents themselves use
  (Window/Limit/KeysetScrollPosition, HttpGraphQlTester EntityListSpec
  contains/doesNotContain/satisfies chaining), and against the pinned Saleor
  3.23.43 schema fetched live for the exact root signature and filter-input
  closures.
TESTS NOT RUN + REASON:
  CustomerDataFetcherDgsTest and CustomerGraphQlIntegrationTest — written,
  not run locally (no Maven/JDK in sandbox) AND they require the A0 SDL
  integration + DGS codegen first (they reference the generated types and
  the customers root of the proposed SDL). Compilation and the suites are
  proven by A0-dispatched GitHub Actions runs on the candidate branch.
SECURITY/AUTHZ/OWNERSHIP NEGATIVES:
  - anonymous rejection: CustomerGraphQlIntegrationTest.
    anonymousCustomerQueryIsRejectedAs401ProblemDetail (the /graphql chain's
    401 problem+json AUTHN-001 contract with the RFC 6750 bare-realm
    challenge, asserted for the customers journey itself) + the standing
    tripartite proof (GraphQlSecurityIntegrationTest) + the DGS layer's
    unauthenticatedCallerAnswersTheCredentialsError (the fetcher guard
    behind the resolver's leakage mask).
  - wrong-role denial: integration providerCallerIsDeniedTheCustomerList-
    ByTheOwningUseCase and consumerCallerIsDeniedTheCustomerListByTheOwning-
    UseCase (real @PreAuthorize in UserService — AUTHZ-001 + category authz,
    data null); DGS ownerDenialMapsToTheAuthzTaxonomyThroughTheRealResolver.
  - data-scope denial (the population boundary): the customer list NEVER
    answers staff accounts — integration adminCustomersAnswersOnlyThe-
    ConsumerPopulationStaffExcluded asserts this class's ADMIN/PROVIDER rows
    and the seed migration's own provider rows are absent (emails and typed
    global IDs), while the complementary staffUsers root keeps answering the
    whole store (its totalCount strictly exceeds the customer population's —
    both roots' own totals compared).
  - guessed-ID null (the customers journey's read-back leg):
    integration guessedButWellFormedGlobalIdAnswersNullNotAnError through
    the user root (the customers root takes no id argument — documented).
  - malformed/mistyped/raw-UUID global-ID rejection:
    integration malformedGlobalIdAnswersTheClassifiedValidationError /
    rawUuidWhereAGlobalIdIsExpectedAnswersTheClassifiedValidationError /
    mistypedGlobalIdForAnotherGraphqlTypeIsRejected (VAL-001, the codec's
    uniform "Invalid global ID" message — no type-discrimination leak); the
    list's own emitted IDs are asserted to be exactly base64("User:<uuid>")
    typed global IDs, never raw UUIDs.
  - client-crafted cursor rejection: DGS
    cursorFromAnotherSortIsRejectedThroughTheRealCursorGate + integration
    cursorFromAnotherSortAnswersTheClassifiedValidationError (VAL-001).
  - declared-but-unexecuted arguments never silently no-op: DGS + integration
    filter / where / search / backward-pagination / unowned-sort-field legs
    (each asserting the VAL-001 errorCode and the explicit message).
KNOWN GAPS / BLOCKERS:
  - The adapter/tests do not compile until A0 integrates the SDL fragment
    and runs codegen (designed flow — SPI/CONTRACT CHANGES REQUESTED item 2;
    flagged so assembly order is explicit).
  - The graphqlCustomerQuery rate-limiter instance is a REQUEST (item 1);
    until A0 declares it the limiter falls back to the default config —
    bounded, never absent.
  - Saleor's "staff users who placed an order also appear in customers"
    clause is NOT implemented (no orders owner yet — registry row 1's
    recorded scope; the population is the CONSUMER primary-role slice).
  - The effective-role assignment layer does NOT widen the customer
    population: an account whose primary role is PROVIDER/ADMIN with an
    ACTIVE CONSUMER assignment is not a customer on this first slice (the
    primary role is the owned scoping fact; the assignment-union widening
    is a possible follow-up behind an owner decision — noted for the
    registry row's scope).
  - filter/where/search execute nothing (the honest boundary above); when
    the owning query capability lands, the bounded input shapes may need to
    grow toward Saleor's full closure with their owning types.
COMMITS TO INTEGRATE:
  1) feat(identity): expose the customer-account scroll over the identity
     owner (commit 1 — UserRepository keyset methods + countByRole,
     IdentitySpi.scrollCustomers, UserService implementation)
  2) this report's own commit — feat(graphql): Saleor customers read root
     (P1) — the CustomerDataFetcher adapter, the SDL fragment proposal, the
     two tests and this report (the branch head; A0 reads the exact SHA
     from the branch tip)
```
