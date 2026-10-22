# Agent 1 Review-Fix Handoff

Date: 2026-10-22
Status: draft review packet; not merged
Base branch: `agent/saleor-p1-cms-identity-20261021` at `621860f`
Review branch: `agent/saleor-p1-review-fixes-20261022`
Review source: PR #696 CodeRabbit findings

## Result

Eight of eleven findings are addressed: 1, 2, 4, 5, 6, 7, 8, and 9.
Three remain open: 3 (A0 decision), 10, and 11.
No `main` changes, no merge, and no production deployment.

## Finding Disposition

| # | Area | Status | Evidence |
|---|------|--------|----------|
| 1 | `CmsNavigationDataFetcher` | Fixed | Menus and menu items now forward the decoded keyset cursor instead of an offset. |
| 2 | `CmsPageDataFetcher` | Fixed | `pageTypes` now uses the requested cursor name; other connections report their own names. |
| 3 | `TokenDataFetcher` / shared HTTP security | Deferred to A0 | `/graphql` is authenticated at the HTTP layer in `marketplace-platform-infra`; fixing it requires an A0 shared-security decision and a full-context test. |
| 4 | `CmsMenuItem` | Fixed | URL updates trim input and clear the competing `categoryId`/`pageId` targets. |
| 5 | `CmsNavigationAdminService` and `CmsPageAdminService` | Fixed | Relative moves remove and reinsert within the destination sibling group, then assign dense positions. |
| 6 | `CmsNavigationAdminService` menu-item pagination | Fixed | Repository keyset windows now honor `KeysetScrollPosition`, `Limit`, and `sortField`; no full-table read and no fixed first page. |
| 7 | `CmsPageRepository` | Fixed | Invalid `isPublished` derived queries removed; a `PartTree` test now fails if any derived query names a non-existent property. |
| 8 | `AccountEmailChangeService` and `UserAccountAdminService` | Fixed | Login identity moves `users.subject` with the login username, migrates action tokens, clears old OAuth2 authorizations, and invalidates caches. |
| 9 | `AccountLifecycleSpiAdapter` | Fixed | Profile updates delegate to transactional `UserService.updateOwnDisplayName`, which loads the managed row and invalidates the cache. |
| 10 | `CustomerTypeService` | Open | The old default must be cleared and flushed before the new default is written. No change was applied. |
| 11 | `UserAccountAdminService` bulk updates | Open | `IGNORE_FAILED` still needs a `REQUIRES_NEW` transaction per row so a failed row rolls back independently. No change was applied. |

## Implementation Notes

- `UserService.moveLoginEmail` owns the email/login move. It validates the new address, checks both `users.subject` and `auth_users`, preserves password/authorities/account flags, migrates all action tokens, deletes the old login, removes `oauth2_authorization` rows for the old principal, changes subject and email together, and publishes `CacheInvalidationRequested`.
- `AuthActionTokenService.moveToUsername` consumes outstanding tokens and flushes all token rows before the old login is deleted. V112's foreign key makes this ordering mandatory.
- `AccountEmailChangeService` still uses `UserDetailsManager` for the request-leg password check. Only the confirm-leg identity move is delegated.
- `UserAccountAdminService` no longer moves login rows directly.
- `UserService.updateOwnDisplayName` uses `userRepository.findBySubject`; mutating the cached/detached result of `getBySubject` is avoided.

## Tests

Identity targeted tests passed:

```text
.\mvnw.cmd -q -pl marketplace-identity -am "-Dtest=UserServiceTest,AuthActionTokenServiceTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Djacoco.skip=true" test
```

Console targeted tests passed:

```text
.\mvnw.cmd -q -pl marketplace-console -am "-Dtest=CmsNavigationAdminServiceTest,CmsPageAdminServiceTest,CmsPageRepositoryQueryTest" "-Dsurefire.failIfNoSpecifiedTests=false" "-Djacoco.skip=true" test
```

The CMS cursor test was compiled and run separately through the app module because the upstream app test tree does not compile. It passed 3/3 using the manual `javac` plus `jacoco:prepare-agent surefire:test` path.
An earlier `marketplace-identity -am` run had no failures or errors in Surefire XML, but it was not a full `mvn clean verify`.

## Excluded Cross-Agent Changes

Two build-repair edits are present in the worktree but are intentionally not part of this review packet:

- `marketplace-catalog/.../ProductVariantReadService.java` - Agent 2 scope.
- `marketplace-orders/.../OrderReadService.java` - Agent 3 scope.

Both replace the non-existent `Limit.value()` with `Limit.max()`. Agent 1 should decide whether to drop them, hand them to the owning agents, or carry them as a documented integration repair.

## Open Risks

- Docker is unavailable, so Testcontainers and live Spring context verification were not run.
- The full `marketplace-app` and `marketplace-catalog` test trees currently fail to compile for pre-existing reasons unrelated to this packet.
- The email-change path has unit coverage but no full-context integration test.
- Findings 10 and 11 remain valid and unaddressed.

## Decision Requested From Agent 1

1. Accept, reject, or amend the eight implemented fixes.
2. Decide whether finding 3 belongs in A0 or in this branch's scope.
3. Authorize or defer findings 10 and 11.
4. Decide the disposition of the two cross-agent build repairs.

