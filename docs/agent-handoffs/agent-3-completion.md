# Agent 3 - completion and handoff

## Source state and authority

- PR: https://github.com/waelhe/app-java-v3/pull/698 - open, draft, unmerged.
- Branch: `agent/saleor-p4-p5-p6-orders-events-20261021`.
- Frozen base: `b3c75d0584cda3b07722c424195bcd1c1c87bbd7`.
- Starting head: `0a8b847c368ba2812d3b0d632e7836c72351e111`.
- Verified checkout-slice base: `c56790f9a62c94acaa3a0262005529aa26d4b634`. The final code head is recorded after this slice is pushed to PR #698.
- Governing plan: `docs/governance/plans/saleor-dashboard-full-commerce-canonical-plan.md`.
- Upstream pin: Saleor `3.23.43`, schema Git blob SHA `b8fa0dd809081f76dc2834bb5c286561e5783276`.
- Framework authority: Spring Boot 4.1.1, Spring Modulith 2.1.1, Java 25, DGS, measured from the reactor.
- Transport references: https://docs.spring.io/spring-boot/reference/web/spring-graphql.html and https://docs.spring.io/spring-graphql/reference/transports.html.

## Implemented roots

`orderCancel`, `orderConfirm`, `orderBulkCancel`, `checkout`, `checkoutDelete`, `checkoutLineDelete`, `checkoutLinesDelete`, `checkoutLinesAdd`, and `checkoutLinesUpdate` are implemented and verified within their owned scope.

### Order lifecycle roots

- Additive SDL: `marketplace-app/src/main/resources/graphql/agent-3-orders-events.graphqls`. Exact signatures `orderCancel(id: ID!)`, `orderConfirm(id: ID!)`, `orderBulkCancel(ids: [ID!]!)`; payloads expose both deprecated `orderErrors` and canonical `errors: [OrderError!]!`; `OrderErrorCode` matches all 37 pinned values.
- Adapter: `OrderMutationDataFetcher` decodes typed Order global IDs, returns read-back views, and emits `field="id"` for malformed identifiers.
- Boundary: `OrderCommandSpi` exposes only `OrderView`/`OrderBulkCancelResult`, not persistence entities.
- Authorization ordering: `OrderCommandSpi.requireAdmin()` is called before any caller-supplied ID is decoded in all three mutations. `OrdersService.requireAdmin()` carries `@PreAuthorize("hasRole('ADMIN')")` on the proxied bean, so an unauthorized caller receives the permission decision before validation behavior can be observed. The command methods retain their own `@PreAuthorize` checks as defense in depth.
- Bulk semantics: repeated IDs are silently deduplicated before transition, matching upstream `get_nodes` behavior; count is the number of distinct cancelled orders. `OrderBulkCancelResult.FailureKind` is `{NOT_FOUND, CONFLICT}`; `DUPLICATE`/`DUPLICATED_INPUT_ITEM` was removed.
- Missing IDs: each missing order is reported as a per-item `NOT_FOUND` failure. This is a deliberate safety divergence from upstream, where `get_nodes` asserts and the request fails with a server error instead of a typed item error.
- Confirmation: uses the existing `PLACED -> CONFIRMED` transition, row lock, and event publication; returns `UNFULFILLED` on the wire.
- Cancellation: uses the existing guarded transition, reservation release, and `OrderCancelledEvent`; `marketplace-payments` `OrderCancelledEventListener` calls `autoRefundByOrder`, so cancellation settlement is covered.

### Checkout roots

The checkout slice maps the pinned Saleor 3.23.43 checkout contract onto the existing active-cart aggregate. It does not introduce a second checkout aggregate, a migration, or an alternate pricing path.

- Additive SDL only in `agent-3-orders-events.graphqls`: `checkout(id: ID): Checkout`, `checkoutLinesAdd(checkoutId: ID, id: ID, lines: [CheckoutLineInput!]!): CheckoutLinesAdd`, `checkoutLinesUpdate(checkoutId: ID, id: ID, lines: [CheckoutLineUpdateInput!]!): CheckoutLinesUpdate`, `checkoutLinesDelete(id: ID, linesIds: [ID!]!): CheckoutLinesDelete`, deprecated `checkoutLineDelete(checkoutId: ID, id: ID, lineId: ID): CheckoutLineDelete`, and `checkoutDelete(id: ID!): CheckoutDelete`.
- Boundary: `CheckoutSpi` exposes `CheckoutView` projections and line commands only. `CheckoutView` carries `id`, ordered lines of `{id, variantId, quantity}`, and total quantity. Persistence entities never cross the module boundary.
- Ownership: read, line add, line update, and line delete require an authenticated caller and an ACTIVE cart owned by that caller. A missing or non-owned checkout is reported as not found, preserving privacy. `checkoutDelete` requires ADMIN, matching Saleor's `MANAGE_CHECKOUTS`; the admin check runs before the caller-supplied global ID is decoded.
- ID handling: every checkout, line, and variant global ID is decoded before the owner is called, so malformed or mixed-type input cannot produce a partial write. `id` and deprecated `checkoutId` are both accepted; missing or conflicting values are rejected with `INVALID`.
- Add semantics: `checkoutLinesAdd` requires a positive quantity and a variant global ID, resolves the variant through `ProductPricingPort.priceOfVariant`, and reuses `OrdersService.addCartItem`. Product-level targets and unavailable variants are rejected; pricing is authoritative and never taken from the request.
- Update semantics: `checkoutLinesUpdate` accepts `lineId`, deprecated `variantId`, or both, requires a quantity, rejects zero with `ZERO_QUANTITY`, rejects negative or over-1000 quantities with `INVALID`, and verifies line ownership inside the owner.
- Delete semantics: `checkoutLinesDelete` decodes the complete line-ID list before deleting anything. Deprecated `checkoutLineDelete` maps its single typed line ID onto the same owner command. `checkoutDelete` removes the owned cart and its lines through the admin-only owner command.
- Projection: `Checkout` exposes `id`, `lines`, and `quantity`; `CheckoutLine` exposes `id`, `variant`, and `quantity`. `variant` is enriched from a catalog snapshot lookup and is never nullable. Cart lines without a variant are rejected instead of being hidden or fabricated.
- Deliberate omissions: `created`, `token`, totals, email, addresses, channel, delivery methods, discounts/vouchers, payments/transactions, metadata, and problems are not declared. Their owning capabilities or scalar contracts do not exist yet; inventing them would be a false-success surface.
- Blocked checkout roots remain `BLOCKED_BY_DECISION` with root-specific evidence: `checkoutCreate`/`checkoutCreateFromOrder` need a channel model; `checkoutComplete` needs payment authorization/transaction and fulfillment; address updates need D-ADDR-001; shipping/delivery methods need D-SHPZ-001 plus a quote-to-selection contract; promo codes need a promotion/voucher model; customer attach/detach need a checkout customer identity distinct from the cart consumer; email/note/language need persisted fields; payment creation needs a checkout-scoped payment contract; `orderCreateFromCheckout` needs a Saleor checkout-to-order handoff; the five checkout subscriptions need the disabled subscription transport and a `DgsSubscription` contract.
- Known divergences: `token` and `created` are omitted because no UUID/DateTime scalar contract is approved; anonymous checkout is not supported because the current cart owner is an authenticated consumer; `checkoutLineDelete` is retained only as the deprecated alias; there is no checkout-scoped money, address, channel, delivery, or payment surface.

## Known divergence: `orderConfirm` payment capture

Pinned upstream `order_confirm.py` confirms the order and captures an authorized/capturable payment. This implementation only performs the native lifecycle transition. `PaymentIntentStatus` has no `AUTHORIZED` state and `OrderPaymentPort` exposes intent creation, not capture. Implementing capture would require a Payments-owned transaction/capture capability and integration tests; this PR does not invent a second PSP path. Recorded as the follow-up for full Saleor parity.

## Root accounting

`docs/agent-handoffs/agent-3-roots.csv` disposes all 181 roots; none remains `NOT_STARTED` at the frozen base, and every non-implemented root carries a root-specific blocker.

- `IMPLEMENTED_VERIFIED` (9): `orderCancel`, `orderConfirm`, `orderBulkCancel`, `checkout`, `checkoutDelete`, `checkoutLineDelete`, `checkoutLinesDelete`, `checkoutLinesAdd`, `checkoutLinesUpdate`.
- `BLOCKED_BY_DECISION` (172): draft orders/bulk create (no DRAFT state or second lifecycle); fulfillment/warehouse (no line-level aggregate); order-edit/note/discount roots (immutable placement snapshots); money/PSP roots (frozen money contract section 6 and the `IMPLEMENTATION_REQUIRED` Decimal/PositiveDecimal row); shipping zones (D-SHPZ-001); address roots (D-ADDR-001); remaining checkout roots (channel, payment, address, delivery, promotion, identity, and subscription owners as listed above); invoices, reports, token lookup, apps/plugins, metadata, outbound webhooks/exports/files, translations, shop, federation; all 26 subscriptions (section 5.8 intentionally disables WebSocket/STOMP and no `DgsSubscription` exists).
- Phase split: P4 109 (9 implemented + 100 blocked), P5 46 blocked, P6 26 blocked.

No placeholder resolver or false-success surface was added. Central SDL, shared POMs, `application.yml`, workflows, security/Modulith configuration, the canonical 458-root inventory, divergence registry, domain matrix, pins, `SYSTEM.md`, and `PROJECT_MAP.md` were left untouched.

## Validation

Executed on the checkout-slice working tree above `c56790f9a62c94acaa3a0262005529aa26d4b634`:

```text
.\mvnw.cmd -q -pl marketplace-orders -am test "-Djacoco.skip=true"
marketplace-orders: 118 tests, 0 failures, 0 errors, 0 skipped (9 surefire reports)
marketplace-platform-infra: 161 tests, 0 failures, 0 errors, 0 skipped (30 reports)
marketplace-shared: 145 tests, 0 failures, 0 errors, 0 skipped (23 reports)
```

```text
.\mvnw.cmd -q -pl marketplace-catalog test "-Dtest=ProductVariantReadServiceTest,ProductReadServiceTest" "-Djacoco.skip=true"
ProductVariantReadServiceTest: 17 tests, 0 failures, 0 errors, 0 skipped
ProductReadServiceTest: 9 tests, 0 failures, 0 errors, 0 skipped
```

```text
.\mvnw.cmd -q -pl marketplace-app jacoco:prepare-agent surefire:test "-Dtest=OrderDataFetcherDgsTest"
OrderDataFetcherDgsTest: 23 tests, 0 failures, 0 errors, 0 skipped
```

Coverage includes the ADMIN gate before decoding, silent bulk deduplication, per-item missing/conflict failures, stable lock ordering, read-back views, owner-only checkout reads and writes, typed-ID rejection before mutation, add-through-authoritative-pricing, update bounds, admin-only checkout deletion, and the real SDL wiring of the checkout query and mutations.

`marketplace-app` main sources compile with the new fetcher after the catalog `Limit.max()` correction. The app test-compile still cannot complete on this branch because of pre-existing defects outside this agent's ownership (assignment rule 262):

- Four app GraphQL integration tests still call removed `GraphQlTester.Errors.satisfies(...)` and mix `org.testcontainers.postgresql.PostgreSQLContainer` with `org.testcontainers.containers.PostgreSQLContainer`. Their imports were corrected to `test.config.IntegrationContainers`.
- `PromotionDataFetcherDgsTest` was compiled and executed directly; its Spring context fails on a missing `AttributeReadSpi` bean. That slice gap is pre-existing and unrelated to the checkout fetcher. The remaining non-order DGS slices are not claimed as verified.
- The full catalog suite reports 410 tests with 2 failures in `CategoryReadServiceTest` (`theTiebreakerDecidesWhenTheCursorSharesTheSortKey`, `cursorWithAnUnparseableIdValueIsRejected`); those failures are outside the files changed by this slice and are not claimed as fixed.

Docker is unavailable on this host (`npipe:////./pipe/dockerDesktopLinuxEngine`), so Testcontainers and a full `mvn clean verify` cannot run here. The adapter evidence is the DGS slice plus owner-unit tests, not a full Spring context pass.

## Review and CI state

- Checks inspected at prior head `f0ce4875fb5c8356da3883131bd00d84bd951b80`: Snyk passed; CodeRabbit reported success but the review itself was rate limited; the governance check failed only on the pre-existing unapproved `D-ADDR-001` (Address) divergence in the unchanged central registry. No application-build/test pass is claimed from those checks.
- The new checkout-slice head has not yet been pushed, so no CI result is claimed for it.
- All prior CodeRabbit threads were resolved; no unresolved thread remains. No CI or bot review was awaited.
- No merge was performed.

## Remaining out-of-scope integration work

- App GraphQL integration tests: replace removed `satisfies(...)` assertions and align Testcontainers imports with the measured dependency version.
- Promotion/Voucher DGS slices: add the missing slice mocks (measured first gap: `AttributeReadSpi`) before those contexts can load.
- Catalog `CategoryReadServiceTest`: two pre-existing failures remain outside this slice.
