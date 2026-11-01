# Completion Report — campaign-b1-a1

```
WORKSTREAM: A1 / P1
BASE SHA: 87bc07faf8faa9bdd47d3337b7766e84ce080677
HEAD SHA: 731ef978ab20ebd08df2d32a4f6509a5bff94892 (commit 2 — the branch head at report time; the pre-consolidation lineage is preserved via the PR #712 head)
ROOTS CLAIMED: permissionGroups, permissionGroup
ROOTS IMPLEMENTED + EVIDENCE:
  permissionGroups — delegated to the identity owner through the new
    IdentitySpi.scrollPermissionGroups (ADMIN-enforced inside UserService via
    @PreAuthorize, the MANAGE_STAFF->platform-ADMIN mapping of the merged
    SaleorPermissionCatalog contract); forward-only keyset scroll over the
    closed UserRole vocabulary (NAME sort + id tiebreaker, in-memory scroll
    with the same semantics the roster's SQL keyset executes); connection
    envelope built from the Window's own per-element positions through the
    shared EncodingCursorStrategy; cursor gate = house KeysetCursorGate over
    (name, id) with text value normalization; filter/before/last answer
    explicit classified IllegalArgumentException (VAL-001 through the house
    resolver); non-positive first answers the empty page with totalCount
    null (staffUsers house contract). Evidence:
    marketplace-app/src/main/java/com/marketplace/app/graphql/PermissionGroupDataFetcher.java,
    marketplace-identity/src/main/java/com/marketplace/identity/UserService.java
    (scrollPermissionGroups), tests PermissionGroupDataFetcherDgsTest /
    PermissionGroupGraphQlIntegrationTest (envelope, cursors, paging legs,
    filter/before/last negatives, wrong-role denial, empty page).
  permissionGroup — delegated to IdentitySpi.findPermissionGroupById
    (ADMIN-enforced inside UserService); the id argument executes through
    SaleorGlobalIdCodec with the exact type tag "Group" (malformed/raw-UUID/
    mistyped -> classified VAL-001, never a silent null); unknown but
    well-formed ID answers null (the safe absence). Evidence:
    PermissionGroupDataFetcher.java (permissionGroup),
    PermissionGroupGraphQlMapper.java (typed global-ID encode + permission
    rendering), tests: round-trip read-back, guessed-ID null,
    malformed/raw/mistyped-ID rejection, consumer denial legs.
ROOTS ADAPTED/EXCEPTED + REGISTRY ROW:
  Group.users — UNDECLARED (no clean role->users query exists over the
    identity owner's current repositories; the member list needs a
    primary-role U ACTIVE-assignment union query) — requested divergence
    registry row "Permission-group identity model (Group id/name/users)".
  Group.accessibleChannels / Group.restrictedAccessToChannels — UNDECLARED
    (commercial-channel owner decision PENDING-ADR; references the existing
    "Channel / commercial context" registry row).
  Group identity model — ADAPT: no stored group table exists; the
    collection is the closed UserRole vocabulary; each group's native
    handle is the deterministic name-based UUID (RFC 4122 v3 over
    "marketplace-identity:user-role:<ROLE>"), stable by construction —
    requested registry row (same row as Group.users above).
  filter/before/last — declared-but-unexecuted arguments answering explicit
    classified errors (recorded in the type-closure registry row requested
    below, the staffUsers precedent).
  Unowned sort fields — structurally N/A: the pinned Saleor
    PermissionGroupSortField enum closes over exactly one value (NAME) and
    it is owned; there is no unowned sort-field branch to answer, so no
    fake rejection test exists either (documented in the SDL fragment and
    the fetcher/mapper javadoc).
  Requested registry rows (A0 to apply):
    1) "Permission-group read roots (permissionGroups/permissionGroup) type
       closure" — ADAPT / IMPLEMENTATION_REQUIRED / D-SALEOR-FULL-001.
    2) "Permission-group identity model (Group id/name/users)" — ADAPT /
       IMPLEMENTATION_REQUIRED / D-SALEOR-FULL-001.
FILES CHANGED:
  marketplace-identity/src/main/java/com/marketplace/identity/spi/PermissionGroupView.java (new)
  marketplace-identity/src/main/java/com/marketplace/identity/spi/PermissionGroupPage.java (new)
  marketplace-identity/src/main/java/com/marketplace/identity/spi/PermissionGroupSort.java (new)
  marketplace-identity/src/main/java/com/marketplace/identity/spi/IdentitySpi.java (2 methods added)
  marketplace-identity/src/main/java/com/marketplace/identity/UserService.java (2 public SPI impls + private group projection helpers)
  marketplace-app/src/main/java/com/marketplace/app/graphql/PermissionGroupDataFetcher.java (new)
  marketplace-app/src/main/java/com/marketplace/app/graphql/PermissionGroupGraphQlMapper.java (new)
  marketplace-app/src/test/java/com/marketplace/app/graphql/PermissionGroupDataFetcherDgsTest.java (new)
  marketplace-app/src/test/java/com/marketplace/app/graphql/PermissionGroupGraphQlIntegrationTest.java (new)
  docs/governance/campaign/sdl/P1/a1-permission-groups-read.graphqls (new SDL fragment proposal)
  docs/governance/campaign/reports/campaign-b1-a1.md (this report)
SPI/CONTRACT CHANGES REQUESTED:
  1) application.yml — add the rate-limiter instance (A0-owned file):
       resilience4j.rateLimiter.limiters.graphqlPermissionGroupQuery:
         base-config: default / limit-for-period: 50 / timeout-duration: 0
     (both permission-group read roots share it, the graphqlUserQuery
     precedent's identity read budget; until declared, resilience4j falls
     back to the default config).
  2) SDL integration — splice the Query additions from
     docs/governance/campaign/sdl/P1/a1-permission-groups-read.graphqls
     into marketplace-app/src/main/resources/graphql/schema.graphqls and
     run DGS codegen (generates DgsConstants.QUERY.PermissionGroups/
     PermissionGroup and types Group, Permission,
     GroupCountableConnection/Edge, PermissionGroupFilterInput,
     PermissionGroupSortingInput, PermissionGroupSortField — the adapter
     and its tests compile only after this step, the designed campaign
     flow).
  3) Divergence registry — the two rows above.
  4) Note on the work packet's view sketch: the packet described the SPI
     record as carrying "granted permission codes"; the implemented
     PermissionGroupView carries grantedRoles (the owner's own platform
     role vocabulary) and the GraphQL boundary renders the Saleor codes
     through the tested SaleorPermissionCatalog — the single source of
     that mapping (the UserAccountView.effectiveRoles seam). Putting the
     Saleor codes in the identity module would duplicate the catalog's
     authority across the module boundary.
  5) Group.users follow-up (not blocking this packet): a role->users union
     query needs UserRepository additions + an SPI method — a contract
     change for A0 to schedule with the member-surface work.
DATABASE MIGRATIONS ADDED: none — the read roots carry no schema change
  (V248–V252 allocated but unused; the collection is the closed role
  vocabulary, no group table).
TESTS RUN: none — no local Maven/JDK in the agent sandbox (verified: mvn
  and a JDK are not available); logic verified by inspection against the
  merged PR #694 precedents (UserDataFetcher/UserGraphQlMapper/
  UserDataFetcherDgsTest/UserGraphQlIntegrationTest) and the exact
  upstream sources fetched for the APIs used (Spring Data Window/Limit).
TESTS NOT RUN + REASON:
  PermissionGroupDataFetcherDgsTest and PermissionGroupGraphQlIntegrationTest
  — written, not run locally (no Maven/JDK in sandbox) AND they require the
  A0 SDL integration + DGS codegen first (they reference the generated
  types of the proposed SDL). Compilation and the suites are proven by
  A0-dispatched GitHub Actions runs on the candidate branch.
SECURITY/AUTHZ/OWNERSHIP NEGATIVES:
  - anonymous rejection: PermissionGroupDataFetcherDgsTest.
    unauthenticatedCallerAnswersTheCredentialsError (the fetcher guard) +
    the standing /graphql chain 401 tripartite proof
    (GraphQlSecurityIntegrationTest).
  - wrong-role denial: DGS ownerDenialMapsToTheAuthzTaxonomyThroughTheRealResolver
    (AUTHZ-001 + category authz through the real resolver); integration
    providerCallerIsDeniedTheGroupListByTheOwningUseCase and
    consumerCallerIsDeniedTheGroupLookupByTheOwningUseCase (real
    @PreAuthorize in UserService).
  - guessed-ID null: integration guessedButWellFormedGlobalIdAnswersNullNotAnError.
  - malformed/mistyped/raw-UUID global-ID rejection: DGS
    rawUuidWhereAGlobalIdIsExpectedAnswersTheClassifiedErrorThroughTheRealResolver;
    integration malformedGlobalIdAnswersTheClassifiedValidationError /
    rawUuidWhereAGlobalIdIsExpectedAnswersTheClassifiedValidationError /
    mistypedGlobalIdForAnotherGraphqlTypeIsRejected (VAL-001, codec's
    uniform message — no type-discrimination leak).
  - client-crafted cursor rejection: DGS
    cursorFromAnotherSortIsRejectedThroughTheRealCursorGate (VAL-001).
  - declared-but-unexecuted arguments never silently no-op: DGS + integration
    filter / backward-pagination legs.
KNOWN GAPS / BLOCKERS:
  - Group.users undeclared (member-list owner query missing — registry row
    requested; not blocking the two roots).
  - accessibleChannels/restrictedAccessToChannels undeclared (PENDING-ADR
    channel owner — existing registry row).
  - userCanManage is observable only as true through these roots (the roots
    are ADMIN-enforced, and ADMIN is exactly the manage right) — computed
    from the caller's actual authorities in UserService, never hardcoded.
  - The adapter/tests do not compile until A0 integrates the SDL fragment
    and runs codegen (designed flow; flagged here so assembly order is
    explicit).
COMMITS TO INTEGRATE:
  1) 81aca54b89a9cbc2a68dff9f7611a204b13f02b0 — feat(identity): expose
     permission-group read views over the role owner
  2) 731ef978ab20ebd08df2d32a4f6509a5bff94892 — feat(graphql): Saleor
     permissionGroup/permissionGroups read roots (P1) — the GraphQL
     adapter, the SDL fragment proposal, the two tests and this report
     (the branch head; recorded in full after the report's creation, per
     the review)
```
