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
  contract; it executes in the GitHub Actions integration suite on this branch.
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
3. **Ledger + divergence registry:** the root-status flips for the 78 implemented/blocked roots
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
