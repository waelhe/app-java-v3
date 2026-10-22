# Agent 3 — completion and handoff

## Source state and authority

- PR: https://github.com/waelhe/app-java-v3/pull/698 — open, draft, unmerged.
- Branch: `agent/saleor-p4-p5-p6-orders-events-20261021`.
- Frozen base: `b3c75d0584cda3b07722c424195bcd1c1c87bbd7`.
- Starting head: `0a8b847c368ba2812d3b0d632e7836c72351e111`.
- Final verified code head: `5a5c96476e326f2ef1e800b2537a4a3364f50878`.
- Governing plan: `docs/governance/plans/saleor-dashboard-full-commerce-canonical-plan.md`.
- Upstream pin: Saleor `3.23.43`, schema Git blob SHA `b8fa0dd809081f76dc2834bb5c286561e5783276`.
- Framework authority: Spring Boot 4.1.1, Spring Modulith 2.1.1, Java 25, DGS, measured from the reactor.
- Transport references: https://docs.spring.io/spring-boot/reference/web/spring-graphql.html and https://docs.spring.io/spring-graphql/reference/transports.html.

## Implemented roots

`orderCancel`, `orderConfirm`, and `orderBulkCancel` are implemented and verified end to end within their owned scope.

- Additive SDL: `marketplace-app/src/main/resources/graphql/agent-3-orders-events.graphqls`. Exact signatures `orderCancel(id: ID!)`, `orderConfirm(id: ID!)`, `orderBulkCancel(ids: [ID!]!)`; payloads expose both deprecated `orderErrors` and canonical `errors: [OrderError!]!`; `OrderErrorCode` matches all 37 pinned values.
- Adapter: `OrderMutationDataFetcher` decodes typed Order global IDs, returns read-back views, and emits `field="id"` for malformed identifiers.
- Boundary: `OrderCommandSpi` exposes only `OrderView`/`OrderBulkCancelResult`, not persistence entities.
- Authorization ordering: `OrderCommandSpi.requireAdmin()` is called before any caller-supplied ID is decoded in all three mutations. `OrdersService.requireAdmin()` carries `@PreAuthorize("hasRole('ADMIN')")` on the proxied bean, so an unauthorized caller receives the permission decision before validation behavior can be observed. The command methods retain their own `@PreAuthorize` checks as defense in depth.
- Bulk semantics: repeated IDs are silently deduplicated before transition, matching upstream `get_nodes` behavior; count is the number of distinct cancelled orders. `OrderBulkCancelResult.FailureKind` is `{NOT_FOUND, CONFLICT}`; `DUPLICATE`/`DUPLICATED_INPUT_ITEM` was removed.
- Missing IDs: each missing order is reported as a per-item `NOT_FOUND` failure. This is a deliberate safety divergence from upstream, where `get_nodes` asserts and the request fails with a server error instead of a typed item error.
- Confirmation: uses the existing `PLACED → CONFIRMED` transition, row lock, and event publication; returns `UNFULFILLED` on the wire.
- Cancellation: uses the existing guarded transition, reservation release, and `OrderCancelledEvent`; `marketplace-payments` `OrderCancelledEventListener` calls `autoRefundByOrder`, so cancellation settlement is covered.

## Known divergence: `orderConfirm` payment capture

Pinned upstream `order_confirm.py` confirms the order and captures an authorized/capturable payment. This implementation only performs the native lifecycle transition. `PaymentIntentStatus` has no `AUTHORIZED` state and `OrderPaymentPort` exposes intent creation, not capture. Implementing capture would require a Payments-owned transaction/capture capability and integration tests; this PR does not invent a second PSP path. Recorded as the follow-up for full Saleor parity.

## Root accounting

`docs/agent-handoffs/agent-3-roots.csv` disposes all 181 roots; none remains `NOT_STARTED`.

- `IMPLEMENTED_VERIFIED` (3): `orderCancel`, `orderConfirm`, `orderBulkCancel`.
- `BLOCKED_BY_DECISION` (178), each with a root-specific blocker: checkout family and D-ADDR-001; draft orders/bulk create (no DRAFT state or second lifecycle); fulfillment/warehouse (no line-level aggregate); order-edit/note/discount roots (immutable placement snapshots); money/PSP roots (frozen money contract section 6 and the `IMPLEMENTATION_REQUIRED` Decimal/PositiveDecimal row); shipping zones (D-SHPZ-001); invoices, reports, token lookup, apps/plugins, metadata, outbound webhooks/exports/files, translations, shop, federation; all 26 subscriptions (section 5.8 intentionally disables WebSocket/STOMP and no `DgsSubscription` exists).
- Phase split: P4 109 (3 implemented + 106 blocked), P5 46 blocked, P6 26 blocked.

No placeholder resolver or false-success surface was added. Central SDL, shared POMs, `application.yml`, workflows, security/Modulith configuration, the canonical 458-root inventory, divergence registry, domain matrix, pins, `SYSTEM.md`, and `PROJECT_MAP.md` were left untouched.

## Validation

Executed on `5a5c96476e326f2ef1e800b2537a4a3364f50878`:

```text
.\mvnw -q -pl marketplace-orders -am "-Djacoco.skip=true" test
marketplace-orders: 111 tests, 0 failures, 0 errors, 0 skipped (9 surefire reports)
```

Coverage includes the ADMIN gate before decoding, silent bulk deduplication, per-item missing/conflict failures, stable lock ordering, read-back views, and real method-security proxy denial for non-ADMIN callers.

App-level DGS/unit tests cannot compile on this branch because of pre-existing defects outside this agent's ownership (assignment rule 262):

- `marketplace-catalog/.../ProductVariantReadService.java`: four `Limit.value()` calls; measured `spring-data-commons 4.1.1` exposes `max()`.
- `ProductReadServiceTest` and `ProductVariantReadServiceTest`: `when(authentication.getAuthorities()).thenReturn(...)` fails Mockito wildcard capture; `doReturn` is required.
- Four app GraphQL integration tests import nonexistent `com.marketplace.shared.test.IntegrationContainers`, call removed `GraphQlTester.Errors.satisfies(...)`, and mix `org.testcontainers.postgresql.PostgreSQLContainer` with `org.testcontainers.containers.PostgreSQLContainer`.

A transient local application of the catalog correction proved `marketplace-app` main sources compile, including the new fetcher; it was reverted and is not part of this branch. No app-test pass is claimed.

## Review and CI state

- Checks inspected at prior head `f0ce4875fb5c8356da3883131bd00d84bd951b80`: Snyk passed; CodeRabbit reported success but the review itself was rate limited; the governance check failed only on the pre-existing unapproved `D-ADDR-001` (Address) divergence in the unchanged central registry. No application-build/test pass is claimed from those checks.
- All prior CodeRabbit threads were resolved; no unresolved thread remains. No CI or bot review was awaited.
- No merge was performed.

## Required out-of-scope integration fix

Agent 2 owns the catalog slice and must land before app-level suites can run:

- `ProductVariantReadService`: replace `limit.value()` with `limit.max()`.
- Catalog tests: replace `thenReturn` on `getAuthorities()` with `doReturn(...).when(authentication).getAuthorities()`.
- App GraphQL integration tests: restore a real `IntegrationContainers` package/import, replace removed `satisfies(...)` assertions, and align Testcontainers imports with the measured dependency version.

The orders module and its 111-test suite do not depend on those fixes.
