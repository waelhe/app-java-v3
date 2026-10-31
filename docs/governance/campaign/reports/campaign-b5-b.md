# Campaign packet campaign-b5-b — completion report

**Packet:** the Saleor Orders-domain `order` / `orders` read roots (P4 — the campaign's first P4 slice).
**Executed by:** A0 directly, in-branch, on the single candidate lane (the sequential single-writer flow; no agent branches, no PRs).
**Base:** `537515871b` (the b5-a close).
**Commits:** `42f513f016` (the orders owner) -> `ddc8e4801f` (the GraphQL boundary + the Modulith entry + the sibling DgsTest mocks) -> the governance close (this report, the ledger, the divergence row, `SYSTEM.md`).

## What the Saleor pinned contract demands (3.23.43)

- `order(id: ID, externalReference: String): Order` — "Look up an order by ID or external reference" (the `externalReference` leg requires MANAGE_ORDERS).
- `orders(sortBy: OrderSortingInput, filter: OrderFilterInput @deprecated, where: OrderWhereInput, channel: String, search: String, before, after, first, last): OrderCountableConnection` — "Requires one of the following permissions: MANAGE_ORDERS."
- `OrderSortField` closes over eight values; `OrderStatus` over nine.

## The honest platform mapping

**The ADMIN-only management surface (the packet's central decision):** `orders`/`order` are Saleor MANAGEMENT roots — MANAGE_ORDERS maps through the platform's ADMIN capability (the SaleorPermissionCatalog contract) and is enforced at the owning use-case boundary by Spring Security's method security (`@PreAuthorize("hasRole('ADMIN')")` — the identity module's own staff-read pattern). There is NO public population on this root: PROVIDER and CONSUMER callers answer the AUTHZ-001 authorization failure (proven in the integration suite), and the buyer's own-order surface is the `User.orders` field's own later slice — never this root.

**The lifecycle ADAPT mapping:** the platform machine's four states render through Saleor's own OrderStatus vocabulary — PLACED -> UNCONFIRMED, CONFIRMED -> UNFULFILLED, FULFILLED -> FULFILLED, CANCELLED -> CANCELED (upstream's own spelling); the other five upstream values (DRAFT, PARTIALLY_FULFILLED, PARTIALLY_RETURNED, RETURNED, EXPIRED) stay inert SDL vocabulary this platform never emits. The full pinned enum is declared VERBATIM.

**The scrolls are REAL SQL keyset scrolls — three owning sorts, both directions:** CREATED_AT (the placement instant — the D-N5 complete ordering key's own column, `id` tiebreaker; the deprecated CREATION_DATE alias rides the SAME executed sort, exactly upstream's own deprecation mapping), LAST_MODIFIED_AT (the entity's own audit column), and STATUS (the stored lifecycle text — the owning use case REBINDS the decoded cursor's text-form status key as the entity's own enum before the framework scroll, so the keyset predicate binds the column's own type; an unparseable value answers the classified rejection).

**The bounded wire closure:** `id` (typed global ID, tag `Order`), `created` (the placement instant as the ISO-8601 string the house's deliberate no-new-scalar decision carries on the whole surface — the locked Boot 4.1.1 -> spring-graphql 2.0.5 -> graphql-java 25.0 chain adds no temporal scalar; the registered divergence from upstream's DateTime scalar), `status` (the mapped Saleor vocabulary). Unowned Saleor fields stay undeclared with documented reasons: number (no order-number column), user (the identity projection lands with the buyer-surface slice), the channel-scoped money/tax totals (money stays off the wire per the frozen money contract), billing/shipping addresses (no address store — the D-ADDR-001 decision), lines/fulfillments/deliveries (their own later slices), trackingClientId, languageCode, externalReference, updatedAt, events, discounts, payments, giftCards, invoices.

**The classified boundaries (declared on the wire, explicit errors, never silent no-ops):** `filter` (deprecated upstream), `where`, `search`, `channel` (no channel model — the PENDING-ADR commercial-context row), `before`/`last` (forward-only); the five unowned sort fields (NUMBER, RANK, CUSTOMER, PAYMENT, FULFILLMENT_STATUS); the `order` root's `externalReference` (no column) and the keyless call (the recorded divergence from upstream's null answer).

## Evidence

- `marketplace-orders`: `OrderReadSpi`/`OrderView`/`OrderSort`/`OrderPage` (the new `orders-spi` named interface, type-level `@NamedInterface` — the campaign's established pattern), `OrderReadService` (the `@PreAuthorize` management boundary + the STATUS cursor rebinding), the repository's six derived keyset scrolls (placedAt/updatedAt/status, both directions), `OrderReadServiceTest` (12 tests: the three sorts, the rebinding legs including the unparseable rejection, the safe-absence legs, the guards).
- `marketplace-app`: `OrderDataFetcher`/`OrderGraphQlMapper`, the additive-only SDL (2371 -> 2626 lines: 2 Query roots + the bounded `Order` + connection/edge + the bounded filter/where inputs + the VERBATIM sorting/status enums), the `graphqlOrderQuery` limiter (50/min fail-fast), the app module's Modulith allow-list entry (`orders :: orders-spi`), `OrderDataFetcherDgsTest` (19 tests) and `OrderGraphQlIntegrationTest` (25 tests: the ADMIN allow surface with the lifecycle mapping on the wire, the AUTHZ-001 deny legs for PROVIDER/CONSUMER on both roots, the anonymous 401 problem+json, cursor follow-through in the placement-instant and status sorts, the CREATION_DATE alias leg, the round-trip global ID, the guessed-ID null, the VAL-001 legs, the empty page, every classified boundary).
- The `OrderReadSpi` mock was added to EVERY package-slice DgsTest (the 10 existing files) — the sibling-mock discipline the b5-a packet established after finding the b4-a/b4-c defect.
- Governance: the ledger reads 22 INTEGRATED / 458 (the two order rows, `candidate_commit` filled); the divergence registry carries the order type-closure/lifecycle row; `SYSTEM.md`'s derived failsafe selection count reads 176 (the new integration-test file).

## Tests

- Written and committed: 12 unit (orders) + 19 DGS + 25 integration.
- Run locally: NOT run (the sandbox has no Docker/JDK build for this Maven reactor; the campaign's execution discipline is GitHub-side commits with CI as the executor). The next completed CI run on the packet head is the proof — monitored simply, never waited on.

## Ledger

`order`, `orders` -> `INTEGRATED` (22/458). No other rows touched.
