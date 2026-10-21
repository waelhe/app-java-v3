# Campaign Frozen Contracts — v1 (2026-10-21)

**Campaign:** Full Saleor Dashboard + Commerce Contract, single continuous build phase.
**Authority chain:** `docs/governance/plans/saleor-dashboard-full-commerce-canonical-plan.md` (sole governing plan) → `saleor-multi-agent-execution-playbook.md` (execution protocol) → **this file** (the frozen shared contract pack required by playbook §2 Phase A item 5) → the 458-root inventory CSV (the campaign ledger).
**Framework authority:** Spring Boot and Maven official documentation and repositories are the only absolute authority for any design/build decision; conflicting internal notes are corrected, not followed.

## 0. Frozen base (verified at campaign start)

| Artifact | Frozen identity | Verified |
|---|---|---|
| Backend `waelhe/app-java-v3` `main` | `ea0031a97db4452748a47582d3fed2c4d3ec1d52` (PR #694 head) | live API read 2026-10-21 |
| Admin `waelhe/marketplace-admin` `main` | `982ecf3667d2242cab941737cb6a0bceaee7ffcb` (PR #2) | live API read 2026-10-21 |
| Saleor reference schema | `saleor/saleor@3.23.43:saleor/graphql/schema.graphql`, Git blob SHA-1 `b8fa0dd809081f76dc2834bb5c286561e5783276` | blob SHA re-computed and matched |
| Root inventory | 458 exact roots = 90 Query + 342 Mutation + 26 Subscription; waves P1=62, P2=95, P3=85, P4=111, P5=79, P6=26 | generated from the pinned schema, totals equal the checked-in domain matrix |
| Candidate branch | `integration/saleor-full-commerce-20261021` (backend) created from frozen `main` | see campaign commits |
| Flyway head | `V247__products_provider_sort_keyset_indexes_concurrently.sql` | repo tree read |
| Dashboard base | `saleor/saleor-dashboard@3.23.39` commit `bc75e988da0fa4fbc3d47378a888d859d070746f` | source pins |

## 1. Ownership model (playbook §4 — binding)

A0 (Integration Lead) is the **sole editor** of: `marketplace-app/src/main/resources/graphql/schema.graphqls`, generated DGS classes (`com.marketplace.app.graphql.generated.*`), root `pom.xml` cross-module changes, Modulith boundary definitions, `docs/governance/plans/*` registries (matrix, pins, divergences), `.github/workflows/*`, `SYSTEM.md`, `PROJECT_MAP.md`, and Flyway version **allocation**.
Domain agents own their module source/test trees and submit **proposed deltas** for shared files:

- SDL fragments go to `docs/governance/campaign/sdl/<WAVE>/<agent>-<topic>.graphqls` on the agent's own branch. A0 integrates fragments into the shared SDL in dependency order and runs DGS codegen. Never hand-edit generated classes.
- Migration files authored by agents use **only A0-allocated version numbers**; existing migrations are immutable.
- Proposed registry/matrix/pins changes are reported in the agent completion report; A0 applies them.

## 2. Global ID contract (frozen; enforced by merged code)

`com.marketplace.app.graphql.SaleorGlobalIdCodec` (merged via PR #693) is the **only** ID translation path at the GraphQL boundary: type-tagged `Base64("<TypeName>:<uuid>")`, standard canonical Base64, UTF-8 and UUID canonical-form validated, per-callsite expected-type check, input length caps, uniform non-leaking error messages. Rules:

1. Every Saleor-wire entity ID is a typed global ID produced/consumed through this codec; raw UUIDs must not leak onto the Saleor wire.
2. The GraphQL type tag equals the Saleor type name (`User`, `Product`, `Order`, …).
3. Malformed/mistyped/global-ID inputs answer the codec's classified validation error — never a silent null and never a raw UUID fallback.
4. Native UUID primary keys remain authoritative internally (divergence registry row "Node.id / global ID").

## 3. Pagination and cursor contract (frozen; the PR #694 precedent)

1. Forward-only keyset scrolling via Spring Data `KeysetScrollPosition`/`Window`/`Limit` with the single shared `EncodingCursorStrategy<ScrollPosition>`; cursors identify a row's sort key, never an absolute offset.
2. The shared `KeysetCursorGate` validates cursors against the current sort's `(property, id)` key set with declared value types — the VAL-001 taxonomy for client-crafted cursors.
3. Connection envelopes are the **declared Saleor shapes** (`edges/pageInfo/totalCount`), built from the window's own per-element positions; `hasPreviousPage` states the honest forward-only fact; `totalCount` is the real collection count (clamped to GraphQL Int).
4. Declared-but-unexecuted wire arguments (e.g. `before`/`last`, unowned filter or sort fields) answer an **explicit classified `IllegalArgumentException`** — a recorded divergence, never a silent no-op and never a fake success.
5. Default and maximum page sizes come from `marketplace.graphql.pagination` (`GraphQlProperties`); SDL must not add literal defaults that shadow configuration.

## 4. Authorization contract (frozen)

1. Authorization is enforced at the **owning use-case boundary** (service methods receive `Authentication`), never only in resolvers and never in UI route guards. Resolvers are thin transport adapters.
2. Saleor permission requirements map through `SaleorPermissionCatalog` (ADMIN→granted codes such as MANAGE_STAFF/MANAGE_USERS/MANAGE_ORDERS; PROVIDER→ownership-scoped MANAGE_PRODUCTS; CONSUMER→none) and are enforced as platform ADMIN at the identity use-case boundary (divergence registry row "PermissionEnum <-> platform RBAC mapping"). The catalog grows only with implemented operations.
3. Every root ships with deny-path tests: anonymous rejection (the `/graphql` chain), wrong-role denial, ownership/provider scope denial, guessed-ID null, malformed/mistyped global-ID rejection.
4. `@RateLimiter` protection follows the `graphqlUserQuery` precedent with a per-domain limiter name.

## 5. Error taxonomy (frozen)

1. Retained Saleor mutations answer Saleor-shaped payload/error fields, enums, nullability and per-field error semantics; native domain validation/authorization errors are mapped into that wire shape; platform-only extension operations may additionally use stable `extensions.code` (divergence registry row "Mutation payload errors"). No false-success payloads.
2. Query-root validation errors use the house classified `IllegalArgumentException` messages (the `staffUsers` filter/before/last precedent).
3. Error responses never leak internal details (codec precedent: uniform messages).

## 6. Money and decimal contract (frozen)

Exact decimal arithmetic end-to-end; platform minor-unit integer representation stays authoritative internally; currency exponents/rounding are explicit; binary float is forbidden for authoritative amounts (divergence registry row "Decimal / PositiveDecimal money"). Monetary roots require currency-exponent and rounding tests before integration.

## 7. Module and SPI contract (frozen)

1. Resolvers live only in `marketplace-app` `com.marketplace.app.graphql` as `@DgsComponent` adapters delegating through declared module SPIs (the `IdentitySpi` precedent: typed `*View`/`*Page`/`*Sort` records; `Authentication`-carrying use-case methods).
2. No direct cross-module entity/repository access; no duplicate identity/catalog/pricing/order/payment stores; no second state machines. Modulith boundaries stay enforced.
3. Missing SPI capability ⇒ a contract-change request to A0 in the completion report; agents never redefine public SPIs, SDL type names, error codes, cursor payloads or event names on their own.
4. Legacy platform roots (`storeProduct*`, `service*`, …) remain backward-compatible extensions; they are never counted as Saleor-root matches and never removed during the campaign.

## 8. Migration allocation (frozen ledger)

Flyway SQL migrations live in `marketplace-app/src/main/resources/db/migration/V<n>__<name>.sql`. Current head: **V247**. A0 pre-allocates ranges per packet; agents use only their allocated numbers and request more through A0:

| Wave / packet | Allocated range |
|---|---|
| A1 (P1 identity) | V248–V252 |
| A2 (P2 catalog) | V253–V260 |
| A3 (P3 commercial) | V261–V268 |
| A4 (P4 orders/checkout) | V269–V280 |
| A5 (P5 surfaces) | V281–V290 |
| A6 (P6 subscriptions) | V291–V295 |
| Reserve (A0-managed) | V296+ |

Entities stay `@Audited`; integration tests use the real Flyway schema with `@ActiveProfiles("test")` and `@Testcontainers(disabledWithoutDocker = true)` — never `ddl-auto` when migration behavior matters.

## 9. Testing and evidence contract (frozen)

1. Per implemented root: DGS fetcher unit tests + real-SDL GraphQL integration tests + the §4 deny-path tests; persistence-backed roots add Flyway/Testcontainers integration tests.
2. Local Maven runs are unavailable in the agent sandbox. Agents therefore: (a) write the tests, (b) verify logic by inspection against the merged precedents, and (c) report `TESTS NOT RUN + REASON` honestly. Compilation and full suites are proven by A0-dispatched GitHub Actions runs on the candidate branch (`ci.yml`, `integration-test.yml`, `saleor-canonical-governance.yml` via `workflow_dispatch` on the candidate ref). A pending run is never reported as green.
3. A root is complete only with: owner delegation, argument validation, authorization/data-scope behavior, classified errors, and its tests — a name in SDL is not completion.

## 10. Branch, commit and report protocol (playbook §5 — binding)

1. Agent branch: `agent/saleor-<wave>-<domain>-<short-topic>` created from the **frozen candidate base SHA** declared in the work packet; one coherent commit sequence; no PRs, no merges to `main`, no force pushes after handoff.
2. Commits: English conventional messages (`feat(graphql): …`, `feat(identity): …`) with the workstream tag.
3. Completion report: the playbook §5 text format, with exact SHAs, files, roots+evidence, requested contract changes, allocated migrations used, tests written/not-run, security negatives, and gaps.
4. The campaign ledger is `docs/governance/campaign/saleor-458-root-inventory.csv` (one row per exact root; playbook §12 columns). Status vocabulary: `NOT_STARTED`, `IN_PROGRESS`, `INTEGRATED`, `TESTED`, `REVIEW_FINDING`, `FIXED_RETEST_PENDING`, `RELEASE_READY`, `BLOCKED_BY_DECISION`, `EXPLICITLY_EXCLUDED`. Only A0 updates the ledger during assembly.
