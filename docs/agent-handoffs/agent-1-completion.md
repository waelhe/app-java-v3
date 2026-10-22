# Agent 1 Completion Report — Identity / Authentication / CMS Pages & Navigation

**Branch:** `agent/saleor-p1-cms-identity-20261021`
**Base SHA (frozen start):** `b3c75d0584cda3b07722c424195bcd1c1c87bbd7`
**Assignment packet:** PR #696 + `docs/governance/campaign/agent-tasks/agent-1-identity-cms-navigation.md`

## Headline

77 of the 89 assigned roots are **implemented with real behavior, real tests and real
migrations** (66 mutations + 11 queries); 12 roots carry documented decision rows (5 external-auth
plugin bridge, 2 avatar, 3 permission-group, 1 address-validation-rules, 1 page-attribute-values)
— every one either stays undeclared on the wire with a decision file, or maps onto a real owner
this packet built. No fake resolvers, no silent no-ops.

## Roots implemented (66 mutations + 11 queries)

**P1 Authentication (4):** `tokenCreate` (the dashboard's password grant over the platform's OWN
primitives: the framework-assembled `AuthenticationManager` for credential verification, the
official `NimbusJwtEncoder` over the platform's `JWKSource` with the OIDC access-token claim
contract, authorization rows persisted through the authorization server's own
`OAuth2AuthorizationService` against the public dashboard client — one issuer, one key material,
zero second token path), `tokenRefresh` (rotation through the official `findByToken` API),
`tokenVerify` (the resource server's own `JwtDecoder`), `tokensDeactivateAll` (the caller's
sign-out-everywhere: JDBC kill of the principal's authorizations, the L23 basis).

**P1 account/password lifecycle (11):** `requestPasswordReset` / `sendConfirmationEmail` /
`confirmAccount` / `setPassword` (the standing A-04 flows, verbatim reuse), `passwordChange`
(password-verified rotation + authorization kill), `requestEmailChange` / `confirmEmailChange`
(the V251 `EMAIL_CHANGE` token purpose + the pending-pair column on `users`; both stores moved in
one transaction), `accountRegister` (the standing registration flow), `accountUpdate` (profile),
`accountRequestDeletion` / `accountDelete` (the V251 `ACCOUNT_DELETION` purpose redeemed through
the standing I7 erasure).

**P1 addresses (8 mutations + 1 query):** `accountAddressCreate/Update/Delete`,
`accountSetDefaultAddress`, `addressCreate/Update/Delete`, `addressSetDefault`, `address` — the
D-ADDR-001 adopted owner (`docs/agent-handoffs/adr-addr-001.md`): V248 + `AccountAddressService`
(IDOR wall on the self legs, ADMIN/MANAGE_USERS on the staff legs, one-default-per-type
invariant) + the `User.addresses/defaultBilling/defaultShipping` read-back (`extend type User`,
additive).

**P1 customers/staff (10):** `customerCreate` (both stores via the standing register flow with a
generated unknowable secret + the emailed set-password right — Saleor's invite semantics),
`customerUpdate` (profile + the two-store email move + activation), `customerDelete` /
`customerBulkDelete` (the standing I7 erasure), `customerBulkUpdate` (both error policies
honestly: REJECT_EVERYTHING all-or-nothing, IGNORE_FAILED per-row results), `staffCreate` /
`staffUpdate` / `staffDelete` / `staffBulkDelete` (the platform's staff concept = the ADMIN
population per the merged `UserAccountView.staff` truth; `UserService.registerStaff` added),
`userBulkSetActive` (the standing status flip with its last-active-ADMIN guard).

**P1 customer types (6 mutations + 2 queries):** `customerTypeCreate/Update/Delete` (V250; the
one-default invariant swapped under the registry), `customerTypeAssignAttributes` /
`customerTypeUnassignAttributes` / `customerTypeReorderAttributes` (the assigned catalog
attribute IDs — existence validated at the composition boundary through the catalog module's
declared `AttributeReadSpi`), `customerType` / `customerTypes` (forward-only keyset reads).

**P1 notification recipients (3):** `staffNotificationRecipientCreate/Update/Delete` — the V249
registry with the oneof CHECK (user | email), live-row uniqueness, full Envers audit. The
order-event consumer joins when the orders module exposes its event surface.

**P5 CMS pages (11 mutations + 4 queries):** `pageCreate/Update/Delete/BulkDelete/BulkPublish/
Translate`, `pageTypeCreate/Update/Delete/BulkDelete`, `pageAttributeAssign/Unassign`,
`pageTypeReorderAttributes`, `page` / `pages` / `pageType` / `pageTypes` — the D-CMS-001 owner
(console module, V281/V282) behind the declared `CmsPagePort` (shared-api; see the contract
requests below).

**P5 navigation (11 mutations + 4 queries):** `assignNavigation` (V284 slot registry),
`menuCreate/Update/Delete/BulkDelete`, `menuItemCreate/Update/Delete/BulkDelete/Translate/Move`
(V283; the tree with the oneof target CHECK, cross-menu parent guard and the cycle-guarded move
that walks the ancestor chain), `menu` / `menus` / `menuItem` / `menuItems`.

## Roots blocked with decision rows (12)

| Root | Decision id | The honest reason | Proposed owner |
|---|---|---|---|
| `externalAuthenticationUrl`, `externalObtainAccessTokens`, `externalRefresh`, `externalLogout`, `externalVerify` | D-EXTAUTH-001 | No external-auth plugin registry exists (the platform's OIDC sign-in is the SAS protocol endpoints themselves; a `pluginId`+`JSONString` registry would be invented structure — the frozen §7 no-second-system rule) | identity, when a real IdP-federation registry capability is needed |
| `userAvatarUpdate`, `userAvatarDelete` | D-AVATAR-001 | `users` has no avatar column; the media module's `MediaOwnerKind` vocabulary (LISTING/POST/PRODUCT) has no USER kind and media is outside this agent's ownership | media module: add `MediaOwnerKind.USER` + a user-avatar owner (A0-scoped) |
| `permissionGroupCreate`, `permissionGroupUpdate`, `permissionGroupDelete` | D-PGRP-001 | The platform's permission-group collection IS the closed `UserRole` vocabulary (the merged read roots map it 1:1); creating arbitrary permission bundles requires a second permission system — forbidden by the frozen contract §7.2 and pinned by the V187 CHECK | stay blocked; the closed-vocabulary mapping remains the recorded RBAC divergence |
| `addressValidationRules` | D-ADDR-001-b | No per-country validation-rules engine (see `adr-addr-001.md`) | geo module, when form metadata is truly needed |
| `pageReorderAttributeValues` | D-PAGE-ATTR-001 | Page attribute VALUES have no owner this slice: the catalog module's attribute-value store is catalog-internal (no cross-module value SPI); inventing a second value store violates the no-second-system rule | catalog `AttributeReadSpi` gains value listing in A2's scope, then pages ride the catalog's store |

## The two mandatory priorities

1. **D-ADDR-001** — resolved: the ADR (`docs/agent-handoffs/adr-addr-001.md`) with the measured
   data facts, the adopted owner and the implementation evidence. The governance gate's red flag
   (`unapproved divergence Address read roots … pending ADR`) has its closure evidence now; the
   central registry edit itself is A0's (per the isolation rules).
2. **The deterministic customers keyset test** — fixed without weakening a single assertion: the
   class now seeds its own 4th and 5th CONSUMER rows (5 total, immune to the seed migration's
   population), the second page still proves strict no-overlap and `hasPreviousPage=true`, and a
   NEW assertion proves `hasNextPage=true` on the second page (the fifth row sits beyond).

## Files

- SDL: `marketplace-app/src/main/resources/graphql/agent-1-identity-cms.graphqls` (1,772 lines:
  66 mutation roots + 11 query roots + the `extend type User` read-back; exact Saleor signatures
  minus the absent-by-design metadata fields; no new scalars — Date/DateTime/JSONString/
  GenericScalar render as String).
- Migrations: V248 (user_addresses), V249 (staff_notification_recipients), V250
  (customer_types + assignments), V251 (auth_action_tokens purpose widening + users.pending_email
  + aud mirror), V281 (cms_pages + translations), V282 (cms_page_types + attributes + deferred
  FK), V283 (cms_menus + items + translations), V284 (cms_navigation_slots).
- identity: 12 new classes (entities, repositories, services) + 4 SPI interfaces + 6 SPI records
  + `AccountLifecycleSpiAdapter`; additive edits to `User`, `UserService` (registerStaff),
  `AuthActionTokenPurpose` (V251's widened vocabulary).
- console: 7 entities + 7 repositories + 2 owning services (`CmsPageAdminService`,
  `CmsNavigationAdminService`).
- shared-api: `CmsPagePort`, `CmsNavigationPort` + view records + the 2 mail-leg events
  (`EmailChangeRequestedEvent`, `AccountDeletionRequestedEvent`).
- app: 10 new classes (8 fetchers + `PlatformTokenGrantService` + `AgentOneGraphQlMappers` +
  `SaleorMutationErrors`).
- tests: `AgentOneMutationDgsTest` (21 legs), `AgentOneMutationIntegrationTest` (6 real-stack
  journeys), the deterministic `CustomerGraphQlIntegrationTest` fix, and the full mock-set restore
  across the 14 standing package-slice DgsTests.

## SPIs consumed / added

Consumed: `IdentitySpi` (existing), `AttributeReadSpi` (catalog, existing — attribute-ID
existence validation at the composition boundary). Added (all inside the existing
`identity-spi` named interface — additive, no allowlist change): `AddressManagementSpi`,
`AccountLifecycleSpi`, `UserManagementSpi`, `CustomerTypeSpi` (+ view records). The console seam
rides the shared-api port idiom (see contract requests).

## Local verification (the honest numbers)

Maven 3.9.16 / JDK 25 (`./mvnw`), on the working tree at the reported head:

- `marketplace-identity` `test`: **186 run, 0 failures, 0 errors, 0 skipped**.
- `marketplace-console` `test`: **68 run, 0 failures, 0 errors**.
- `marketplace-app` `test -Dtest='*DgsTest,*DgsDataFetcherTest'`: **188 run, 2 failures** — both
  pre-existing on the branch (see the blockers section; not this packet's code).
- `AgentOneMutationDgsTest`: **21 run, 0 failures** (the full list above).
- `AgentOneMutationIntegrationTest`: **TESTS NOT RUN + REASON: no Docker daemon in the agent
  sandbox** — the class carries `@Testcontainers(disabledWithoutDocker = true)` per the frozen §9
  contract. It also **cannot execute in this branch's GitHub Actions suites**: both dispatched
  suites die at the `marketplace-catalog` compile defect (blocker #1 below) before any test runs —
  measured, not assumed: CI run [39668326640](https://github.com/waelhe/app-java-v3/actions/runs/39668326640)
  and Integration Test run
  [39668331861](https://github.com/waelhe/app-java-v3/actions/runs/39668331861) on head
  `9597e0e9` both fail at the identical `Limit.value()` compilation error and never reach a test
  phase. The journeys first execute in the assembly phase's suites once the branches combine (the
  owning fix already sits on Agent 2's delivered head `0cc696df18`).
- Full reactor `compile`: **BUILD SUCCESS**.

## Known blockers on the branch (pre-existing, outside this packet's scope — evidence)

1. `marketplace-catalog` `ProductVariantReadService` does not compile at the frozen base: 4 calls
   to `Limit.value()` (the resolved spring-data-commons 4.1.1 `Limit` interface has
   `max()` — verified by javap). Agent 2's scope; locally verified against a scratch
   (uncommitted) `max()` fix — the fix is NOT part of this branch's commits.
2. `marketplace-orders` `OrderReadService.java:90` — the same `Limit.value()` defect (Agent 3's
   scope; same scratch treatment locally).
3. `marketplace-app` test-compile is broken at the frozen base: 4 integration test files import
   `com.marketplace.shared.test.IntegrationContainers`, a package that does not exist in the
   tree (the class lives at `test.config.IntegrationContainers`); after scratch-fixing those
   imports, the same 4 files carry 63 uses of a `GraphQlTester.Errors.satisfies`-era API the
   resolved spring-graphql-test no longer offers. Both defects are in Agent 2/3-domain test
   files; locally moved out (uncommitted) to run this packet's own tests. **A0's assembly pass
   needs the owning agents' fixes for the app test phase to go green.**
4. Two standing `ProductVariantDataFetcherDgsTest` legs fail on the branch as-committed (their
   prod module is defect #1's module) — pre-existing, not touched by this packet.

## Post-delivery self-review pass (this branch, follow-up commit)

The delivery was re-audited root-by-root against the packet and the platform's own house
patterns; two defects were found and fixed in place (both inside this packet's own files, never
a shared or teammate file):

1. **V248 carried dead structure and a factually wrong comment**: a tautological CHECK
   (`NOT is_default_shipping OR is_deleted OR TRUE` — always TRUE, enforced nothing) and two
   NON-unique partial indexes over the default flags, while the comment claimed a partial
   UNIQUE index "can't express" the one-default-per-type invariant. V250's own
   `customer_types_one_default_live_uq` proves the opposite — a partial unique index expresses
   exactly that guard. Fixed: the tautological constraint is gone and the two default read
   indexes are now `user_addresses_user_default_{shipping,billing}_live_uq` partial UNIQUE
   indexes (the V249/V250 house shape), with the service's clears-before-set swap order kept as
   the working path and the guard as the backstop. The migration file was edited in place —
   safe because it had never been applied by any run (the CI suites never reached Flyway on
   this branch, and the local runs were Docker-less mock suites).
2. **`PlatformTokenGrantService` carried a dead `encoder` field** (declared, assigned `null`,
   never read — the working encoder is built per call over the lazily resolved `JWKSource`).
   Removed.

The audit also re-verified: all 89 roots against the assignment list (66 mutations + 11 queries
implemented, 12 documented decision rows), the SDL's 77 declared roots and their extend-type
composition, the migrations' allocation range (V248-V251, V281-V284 only), the transport-only
fetcher discipline (authorization in the owning services), the cycle-guarded menu move, the
oneof target CHECKs, and the additive-only edits to `User`/`UserService`.

## Contract-change requests to A0 (per the isolation rules — not applied by this agent)

1. **Modulith allowlist (the shared file):** the console CMS seam currently rides the shared-api
   port idiom (`CmsPagePort`/`CmsNavigationPort`) because the app layer's allowlist entry
   `"console :: console-spi"` does not exist and the allowlist is A0-owned. If A0 prefers the
   module-SPI idiom: create `marketplace-console/.../spi/` with the named interface, move the
   two port interfaces there, and add the allowlist entry — mechanical, no behavioral change.
2. **application.yml (the shared file):** add the tighter rate-limiter instances this packet's
   surfaces reference (they currently auto-create with the shared `default` budget — 100/min,
   measured empirically against resilience4j 2.4.0's registry fallback):
   `graphqlTokenMutation` (proposed 10/min, timeout 0 — the password-grant surface),
   `graphqlAccountMutation` (proposed 50/min, timeout 0), `graphqlCmsMutation` /
   `graphqlCmsQuery` (proposed 50/min, timeout 0).
3. **Ledger + divergence registry:** the root-status flips for the 89 implemented/blocked roots
   in `agent-1-roots.csv`, the `D-ADDR-001` closure, and the new decision rows (`D-ADDR-001-b`,
   `D-EXTAUTH-001`, `D-AVATAR-001`, `D-PGRP-001`, `D-PAGE-ATTR-001`).
4. **Notifications follow-up (gap, documented):** the `EmailChangeRequestedEvent` and
   `AccountDeletionRequestedEvent` mail legs have no renderer yet — the notifications module is
   outside this packet's ownership; the two listeners + templates land with the notifications
   owner (the events' contracts are frozen in shared-api so the renderer lands without
   re-negotiation).
5. **SYSTEM.md:** the derived failsafe selection count grows with the 11 new Query roots
   (A0-owned file — flagged here per the packet protocol).
6. **The campaign-b6-b report mismatch** (the assignment's known blocker 4): the report claims
   37/50/11 Query dispositions (sum 98 ≠ 90) against the ledger's 22/57/379-of-458 — flagged for
   A0's assembly pass, untouched here per the isolation rules.

## The CodeRabbit closure round (follow-up commit — 11 major + 11 minor findings, all closed)

The manually-triggered CodeRabbit review on this branch's delivery posted **11 major (1
Critical) + 11 minor** actionable findings. Every one is closed in this round, inside this
packet's own files only:

**Major findings — the fixes:**

1. **CmsPageRepository derived queries (🔴 Critical):** the entity's JavaBean property is
   `published` (`isPublished()` reader) — `IsPublished` in a derived name resolves to a property
   named `isPublished` that does not exist, and the bootstrap-time `QueryCreationException`
   kills the whole context. The three declared-but-never-called broken shapes
   (`findByIsPublishedTrue` / `findByIsPublishedOrderBySlugAscIdAsc` /
   `countByIsPublishedTrue`) were removed — dead declarations that broke startup.
2. **`pageTypes` NAME-sort cursor key:** `keysetPosition` now maps `NAME` → the `name` property —
   the service orders that window by `(name, id)`, and the previous slug fall-through handed the
   gate a cursor the next request's `(name, id)` window rejects.
3. **The pre-login operation gate (the security reopening):** `tokenCreate`/`tokenRefresh`/
   `tokenVerify`, the reset/confirmation/redemption family and `accountRegister`/
   `accountDelete` were unreachable — `/graphql` rode the blanket
   `anyRequest().authenticated()` and anonymous callers died at 401 before dispatch. The fix is
   the documented house pattern: the security chain permits the anonymous POST (the
   register/password-reset public-POST precedent, one `requestMatchers` line in the
   resource-server chain), and the NEW `PreLoginGraphQlGate` — a `WebGraphQlInterceptor`, the
   officially supported interception point — parses the ACTUAL GraphQL document (never the
   spoofable `operationName`) and denies-by-default: an anonymous caller reaches ONLY the
   enumerated pre-login family; everything else answers the classified AUTHN-001 GraphQL error
   (the same canonical taxonomy pair the exception resolver emits) before dispatch. This is the
   §13 decision record's reopening condition satisfied — "an approved public-discovery journey
   after tested per-field/object authorization": the campaign's per-root role-matrix tests are
   that authorization, the gate is the per-operation wall.
4. **`menus`/`menuItems` ignored the `after` cursor:** both connections now decode `after`
   through `KeysetCursorGate.forwardKeyset` against the connection's own sort keys — every
   request no longer returns the first page.
5. **`CmsMenuItem.apply` oneof switch:** a non-blank URL write now clears `categoryId`/`pageId`
   in the SAME write — the merged row previously carried two targets and the V283
   at-most-one-target CHECK rejected the flush with a raw database error.
6. **Deterministic relative moves:** `moveMenuItems` and `reorderPageTypeAttributes` apply
   remove-and-reinsert at `clamp(index + delta)` then dense positions — a shifted position
   never ties with a sibling, so the UUID tie-breaker never decides the order. The groups load
   AFTER re-parenting, so the delta applies to the index in the NEW sibling group.
7. **`menuItems` pagination:** `itemsWindow` (whole-table load, memory truncation, offset
   cursors, always-false hasNext) is gone — `CmsMenuItemRepository` carries the real keyset
   window queries (`findAllByOrderByNameAscIdAsc/Desc`), `scrollMenuItems` honors `sortField`
   with the classified error for unknown fields, and `hasNext` comes from the window itself.
8. **The login-identity move (`AccountEmailChangeService.confirmChange` +
   `UserAccountAdminService.moveEmail`):** the subject now moves TOGETHER with the email
   (`User.changeLoginIdentity` — the platform's subject==username invariant; the account was
   effectively broken after a confirmed change before), the V112 token history purges BEFORE
   the old login row dies (`AuthActionTokenService.purgeHistory` — the FK rejects the delete
   otherwise — a PHYSICAL native delete, because BaseEntity's Hibernate
   `@SoftDelete` turns `repository.delete()` into an `is_deleted` update and the
   row would survive to keep rejecting the login row's death; the token rows'
   lifecycle revisions stay in `auth_action_tokens_aud`), and the shared aftermath runs
   (`UserService.loginIdentityMoved`: the old principal's authorizations die with their refresh
   grants + the two user caches drop their stale subject-keyed copies).
9. **`accountUpdate` persistence:** the adapter delegates to the new transactional
   `UserService.updateOwnProfileBySubject` — the previous one-liner mutated a `@Cacheable`,
   read-only, detached copy whose `updateProfile` was invisible to dirty checking.
10. **`CustomerTypeService` default swap:** the cleared default FLUSHES
    (`saveAndFlush`) before the new default writes — Hibernate's action queue runs inserts
    before updates, so the pending clear + insert previously hit V250's
    `customer_types_one_default_live_uq` with the old row still TRUE.
11. **`customerBulkUpdate` IGNORE_FAILED:** each row now runs in its own REQUIRES_NEW
    transaction (a `TransactionTemplate` over the platform's transaction manager — the official
    programmatic pattern): a failing row rolls back its own partial writes and records its
    per-row result without marking the caller's transaction rollback-only;
    REJECT_EVERYTHING keeps the single all-or-nothing transaction.

**Minor findings — the fixes:** the duplicate-ID seen-set in `assignPairs` and
`assignAttributes` (a request like `[a, a]` answers the classified conflict, never the pair
unique index's raw error); the root-name uniqueness on EVERY create/rename/promote path
(`requireFreeRootName` — move-to-root, delete-time promotion, root rename previously flushed
into the V283 partial unique index); the merged-state address validation (partial updates
validate the merged row, not the patch); the email normalization on the self-service path
(`toLowerCase(Locale.ROOT)` — the requireEmail discipline); the address-default flush ordering
(`addressRepository.flush()` between clears and set — the V248 partial unique indexes'
backstop); the subject-shaped authentication in the password grant (`tokenCreate.user` was
always null); the registration input validation at the GraphQL boundary (the REST DTO's own
contract: email ≤50 + shape, password 8..72, the lowercase key); the IDOR test's completeness
(a full valid address, the NF-001 classification, and the admin row's provable integrity); the
search leg's real keyset windows in `CustomerTypeRepository` (both sort fields, both
directions — the missing descending slug method completes the vocabulary); the ADR's V248 index
description; and this report's own root-count typo (89, not 78).

**The gate's test contract:** `GraphQlSecurityIntegrationTest` now pins the reopened anonymous
contract end to end — the pre-login gate's classified denial (leg 1: a valid query; leg 3: an
unparsable document — deny-by-default; leg 5: the `operationName` spoof, which the gate ignores
because it parses the document), the clean execution of the pre-login family (leg 6:
`tokenVerify` answers its own payload for the anonymous caller), and the standing 401
problem+json contract for the GET surface and the invalid-bearer challenge (legs 2 and 4).

**Local verification (the honest numbers, Maven 3.9.16 / JDK 25 on this round's head):**

- **The branch AS PUSHED does not compile the full reactor** — the frozen base's
  `marketplace-catalog` `ProductVariantReadService` (`Limit.value()`, 4 call sites) and
  `marketplace-orders` `OrderReadService` (the same defect) fail before `marketplace-app`;
  the owning fixes sit on Agents 2/3's delivered branches (blockers 1–2 above). Every module
  UP TO those defects compiles green on the pushed head — including all three this packet
  owns (`marketplace-platform-infra`, `marketplace-identity`, `marketplace-console`), measured
  by the dispatched CI run on 43fef706 (the build log's compile order proves it).
- The verification numbers below were measured on a working tree that ALSO carried the two
  documented cross-module scratch fixes (uncommitted — Agent 2's `ProductVariantReadService`
  + catalog test-shape fixes and Agent 3's `OrderReadService`, each already delivered on
  their own branches): with those in place the full reactor compiles **BUILD SUCCESS**.
- `marketplace-identity` `verify`: **244 tests run, 0 failures, 0 errors** — the JaCoCo 0.70
  instruction gate PASSES on the unit tests alone (CI adds the Testcontainers legs).
- `marketplace-console` `verify`: **116 tests run, 0 failures, 0 errors** — the JaCoCo 0.70
  instruction gate PASSES on the unit tests alone.
- `AgentOneMutationDgsTest`: **21 run, 0 failures** — the real DGS wiring boots with the
  `PreLoginGraphQlGate` in the interceptor chain.
- The new unit suites in this round: `AccountAddressServiceTest` (8), `AccountEmailChangeServiceTest`
  (6), `UserAccountAdminServiceTest` (12), `CustomerTypeServiceTest` (8),
  `AccountSelfServiceTokenServiceTest` (7), `AccountLifecycleSpiAdapterTest` (4),
  `StaffNotificationRecipientServiceTest` (12), `CmsPageAdminServiceTest` (16),
  `CmsNavigationAdminServiceTest` (27) — every fix above carries its own regression leg.
- The Testcontainers integration classes (`AgentOneMutationIntegrationTest`,
  `GraphQlSecurityIntegrationTest`, the deterministic customers keyset test) carry
  `@Testcontainers(disabledWithoutDocker = true)` per the frozen §9 contract and execute in the
  GitHub suites; on THIS branch the dispatched suites still die at the frozen base's
  `marketplace-catalog` compile defect (blocker 1 — the owning fix sits on Agent 2's delivered
  head), so the branch CI's app legs stay red-by-design until the assembly pass combines the
  branches. The journeys' updated assertions (the IDOR leg's NF-001 classification and the
  admin-row integrity check; the reopened anonymous-contract legs) first execute there.

## The CodeRabbit round 2 (follow-up commit — 5 incremental findings, all closed)

The incremental review of the closure round posted **2 major + 3 minor** findings on the new
code itself; every one is closed:

1. **The fragment bypass (Major, the gate):** the gate's top-level walk collected only plain
   `Field` selections — a fragment SPREAD (or an INLINE fragment) riding an allowed field could
   select any protected root and execute it for the anonymous caller. The gate now refuses any
   top-level selection that is not a plain `Field`: a shape it cannot decide without resolving
   the fragment graph never reaches execution. `PreLoginGraphQlGateTest` (9 legs — the gate's
   full decision table: authenticated pass-through, the pre-login pass, the protected denial,
   the operationName spoof, the fragment spread, the inline fragment, the unparsable document,
   the empty document, the mixed document) and the integration legs 7–8 pin it.
2. **The soft-delete FK trap (Major, the login-identity move):** `BaseEntity` carries
   Hibernate `@SoftDelete` — `repository.delete(...)` (and a bulk JPQL delete) translate into
   `is_deleted` UPDATEs, so the token rows would SURVIVE the purge and V112's plain FK keeps
   rejecting the login row's death. `purgeHistory` now rides a native
   `DELETE FROM auth_action_tokens WHERE username = :username` — the physical removal the FK
   leg actually requires; the token rows' lifecycle revisions (issue + consumption) stay in
   `auth_action_tokens_aud`.
3. **The 50-character login limit on the self-service email change (Minor):** the target is
   rejected at the request boundary — before the password check and the token issue — instead
   of surfacing at confirm time as a storage error after the mail leg.
4. **The `requireEmail` contract on the administrative move (Minor):** `moveEmail` rides the
   SAME shape + length validation the create paths carry (`requireEmail` now enforces the
   50-character login-store domain for both).
5. **The report's own compile-claim separation (Minor):** the branch-as-pushed compile state
   and the locally-scratch-verified state are now stated as two distinct claims above.
