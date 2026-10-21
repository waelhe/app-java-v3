# Agent 2 — Completion Report (Catalog / Products / Attributes / Pricing / Discounts)

> **Branch:** `agent/saleor-p2-p3-catalog-pricing-20261021` (this PR's own working branch — no other PR or branch created, nothing merged)
> **Base SHA (frozen):** `b3c75d0584cda3b07722c424195bcd1c1c87bbd7`
> **Final head SHA at delivery:** `8dd2c0372e24e0832108125ef2b4541ac6f0de4a`
> **Commits (execution order):** `d3e8d7e` → `fe8f40f` → `a5094d7` → `107cadf` → `5f9c134` → `8dd2c03`

---

## 1. What this session delivered

### 1.1 The mandatory first-priority repairs (all four, plus the follow-on damage the frozen CI had never reached)

1. **The four compile errors (priority 1).** `ProductVariantReadService.java` lines 122/175/184/186 called `Limit.value()`, which does not exist on the installed `org.springframework.data.domain.Limit` (spring-data-commons 4.1.1 via Boot 4.1.1 — verified against the shipped jar with `javap`: the interface exposes `max()`/`isLimited()`/`isUnlimited()`, and `Unlimited.max()` throws `IllegalStateException`). The service now guards `!limit.isLimited()` before reading `limit.max()` and reads `max()` at the three use sites — the `pageSize + 1` fetch and the SQL keyset pagination are preserved exactly. Commit `d3e8d7e`.
2. **The Promotion/Voucher enum persistence (priority 4).** The historical `PromotionVoucherGraphQlIntegrationTest.seedPolicyRows` failure (`promotions_type_chk`) was the JPA default: Jakarta Persistence §11.1.34 defaults an enum field without `@Enumerated` to ORDINAL, while `V223` declares `discount_type VARCHAR(12)` with `CHECK (discount_type IN ('PERCENTAGE','FIXED'))`. `Promotion` and `Voucher` now carry `@Enumerated(EnumType.STRING)` (the catalog module's own `ProductTypeAttributeAssignment` precedent). The CHECK and the seed assertions are untouched. Commit `fe8f40f`.
3. **The padded-Base64-prefix Global-ID tests (priority 3).** The broken idiom computed `Base64("Tag:")` (padded — the `==` segment is never part of the full `Base64("Tag:<uuid>")` encoding) and asserted `startsWith`. Six owned test files (Attribute/Category/Product/ProductType/ProductVariant/PromotionVoucher `GraphQlIntegrationTest`) now decode the emitted ID and verify the tag + native UUID shape; the padded-prefix constants are removed. Commit `107cadf`.
4. **Four GraphQL integration tests committed against imagined APIs (the frozen CI never compiled them).** They imported a non-existent `com.marketplace.shared.test.IntegrationContainers` (the canonical class is `test.config.IntegrationContainers`, used by 165 files), the deprecated `org.testcontainers.containers.PostgreSQLContainer` location, the non-existent `GraphQlTester.Errors.satisfies(...)` (the installed spring-graphql-test 2.0.5 spells it `satisfy` — verified via javap), and non-existent `EntityList.containsOnly/should/anySatisfy/isNotEmpty/hasSizeGreaterThanOrEqualTo`. All four repaired to the installed APIs. Commit `a5094d7`.

### 1.2 Real mutation roots implemented (8 roots, two complete vertical slices)

**Slice A — the category registry (P2, 4 roots):** `categoryCreate`, `categoryUpdate`, `categoryDelete`, `categoryBulkDelete`
- Owning seam: `marketplace-catalog` `CategoryWriteSpi` + `CategoryWriteService` (`@PreAuthorize("hasRole('ADMIN')")` — the MANAGE_PRODUCTS platform capability, enforced at the owning use-case boundary, never only in a resolver).
- Domain validation answers as Saleor payload errors (the registered "Mutation payload errors" ADAPT divergence): `ProductError` field/message/code mapped from the owner's own error entries (`REQUIRED`/`INVALID`/`NOT_FOUND`/`ALREADY_EXISTS`).
- Soft delete rides the `BaseEntity` `@SoftDelete` discipline; the bulk count answers only what the registry itself deleted.
- Transport adapter: `CategoryMutationDataFetcher` (typed global-ID decode, classified VAL-001 errors for malformed/mistyped IDs, keyless calls, and the declared-but-unowned `parent`/`externalReference` arguments — never silent no-ops).
- SDL: `marketplace-app/src/main/resources/graphql/agent-2-catalog-pricing.graphqls` — exact pinned 3.23.43 signatures in `extend type Mutation`, the bounded `CategoryInput` (the owned field set: name, slug), the payload types, and the pinned 22-member `ProductErrorCode` membership set.
- **No new migration** — the `categories` table, the code CHECK and the `uq_categories_code_active` partial unique index all predate this packet (V253–V260 remain unused and reserved).
- Commit `5f9c134`.

**Slice B — the voucher policy collection (P3, 4 roots):** `voucherCreate`, `voucherUpdate`, `voucherDelete`, `voucherBulkDelete`
- Owning seam: `marketplace-pricing` `VoucherWriteSpi` + `VoucherWriteService` (same `@PreAuthorize` ADMIN boundary — the MANAGE_DISCOUNTS capability).
- The entity factory's own shapes re-checked before the write (code 4..64 uppercase normalization, ISO-4217 currency, PERCENTAGE 1..10000 basis points / FIXED positive minor, non-negative minSpend, positive-or-null limits, merged-window span re-check).
- The code-stability discipline: an already-redeemed voucher's code never moves (`VOUCHER_ALREADY_USED` — the Saleor vocabulary's own member for exactly this refusal). `applyOncePerCustomer` flattens the per-user limit to 1 (the read side's own flattening semantics); usage counts are redemption rows COUNTED, never a stored counter.
- SDL: exact pinned signatures; the bounded `VoucherInput` = the owned Saleor field set PLUS the platform-extension discount facts `discountValue`/`currency`/`minSpend` (the row's NOT NULL policy; the "platform-only fields as extensions" policy the Category wire type's `nameAr`/`position` established); payloads + the pinned `DiscountErrorCode` membership set.
- **No new migration** (V261–V268 remain unused and reserved).
- Commit `8dd2c03`.

### 1.3 Build-integrity repairs the red-CI commits left behind (each proven by the now-green suites)

- **`KeysetCursorGate` unique-key-sort bug:** the ID scroll (sortProperty `"id"` itself) crashed `Set.of("id","id")` with its own duplicate-element error instead of the classified taxonomy — measured by `ProductVariantDataFetcherDgsTest.cursorFromAnotherSortIsRejectedThroughTheRealCursorGate`. The gate now carries the single-key vocabulary `{id}` for the unique-key sort.
- **`ProductVariantDataFetcherDgsTest.namelessVariantAnswersTheHonestNullName`** asserted fields on the EDGE map instead of the node (sku failed; the null-name assertion passed by accident).
- **`CategoryReadServiceTest.theTiebreakerDecidesWhenTheCursorSharesTheSortKey`** was flaky (random UUIDs + a logically impossible else-branch): now deterministic fixed ids asserting `["tie-b"]` after the lesser key and the empty page after the greater key.
- **`CategoryReadService`** leaked the raw `Invalid UUID string` parse error for an unparseable cursor id — now the classified `forward keyset over (name, id)` family (the `OrderReadService.rebindStatusKey` discipline).
- **Sibling DGS-test mocks the b4/b5 packets prescribed but never completed** (each packet's own commit message documents the prescription): every package-slice DGS test now mocks the seams the package's fetchers need (`CategoryWriteSpi`, `VoucherWriteSpi` for this packet; `DiscountReadSpi`, `AttributeReadSpi`, `ProductTypeReadSpi`, `CategoryReadSpi`, `OrderReadSpi`, `ProductReadSpi`, `ProductVariantReadSpi` for the b4/b5 packets). Without these the shared slice context cannot boot at all.
- **`ProductReadServiceTest`/`ProductVariantReadServiceTest` Mockito wildcard-capture compile errors:** `Authentication.getAuthorities()` returns `Collection<? extends GrantedAuthority>` (the Spring Security 7.1.1 interface contract) — Mockito's own documented `doReturn().when()` form stubs the wildcard return.

---

## 2. Exact test evidence

### Local (JDK 25.0.4.1, Maven 3.9.16, the project's own wrapper versions)

| Suite | Command | Result |
|---|---|---|
| marketplace-catalog | `mvn -pl marketplace-catalog test` | **Tests run: 425, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS** (was 408 + 17 new `CategoryWriteServiceTest`) |
| marketplace-pricing | `mvn -pl marketplace-pricing test` | **Tests run: 143, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS** (was 118 + 25 new `VoucherWriteServiceTest`; JaCoCo ≥70% bundle gate GREEN) |
| marketplace-app DGS | `mvn -pl marketplace-app test -Dtest='*DgsTest'` | **Tests run: 168, Failures: 0, Errors: 0, Skipped: 0 — BUILD SUCCESS** (159 pre-existing incl. the repaired variant tests + 10 `CategoryMutationDataFetcherDgsTest` + 9 `VoucherMutationDataFetcherDgsTest`) |
| marketplace-app test-compile | `mvn -pl marketplace-app test-compile` | **BUILD SUCCESS** (the 176-file failsafe suite compiles; the Testcontainers legs skip locally without Docker by their own design and run in CI) |

### CI (GitHub Actions, on this branch's own heads)

- **Run 39608509522** (`workflow_dispatch` on final head `8dd2c03`, dispatched by this session): in progress at delivery time — https://github.com/waelhe/app-java-v3/actions/runs/39608509522
- **Run 39601766595** (`workflow_dispatch` on `107cadf`): FAILED — the failure is the out-of-scope `marketplace-orders` blocker below, NOT this packet's code: `OrderReadService.java:[90,35] cannot find symbol` (`Limit.value()`). The GraphQL Schema Compatibility Gate and OpenAPI Compatibility Gate failures in that run are downstream of the build failure ("Current tree failed to produce the schema dump") — exactly the frozen-CI phenomenon the assignment predicted; they are NOT contract-divergence evidence. https://github.com/waelhe/app-java-v3/actions/runs/39601766595

---

## 3. Live blockers (documented evidence, per the independent-execution protocol)

1. **`marketplace-orders/OrderReadService.java:90` — `Limit.value()` compile error (Agent 3's scope).** The same one-line family as the catalog fix (`!limit.isLimited() || limit.max() <= 0`). It blocks the full reactor/app build on EVERY agent's branch cut from the frozen SHA, and therefore the schema/OpenAPI gates. It was NOT fixed in this branch (the isolation rule); the exact one-line fix is recorded here for Agent 3's packet. Note: the frozen CI log only showed the four catalog errors because the reactor stops at the first failing module — the orders error surfaces only AFTER the catalog fix, which is why the assignment's premise ("after the catalog fix the gates run") needs this second fix from Agent 3.
2. **The governance gate (`saleor-canonical-governance.yml`) fails identically on the frozen SHA and on this branch's heads** — `unapproved divergence Address read roots (address/addressValidationRules) — owner decision D-ADDR-001 must identify its pending ADR`. This is the central divergence registry's own pending decision (Agent 1's address domain + the shared registry this packet must not edit). Evidence: run 39608522756 (on `8dd2c03`) fails with the exact same error the frozen SHA's run 39589087583 carried. Not attributable to this packet.
3. **The PR stays open for the later independent integration review** — nothing was merged into the base branch or `main`.
4. **The schema-manifest incremental mode gotcha (local dev only, CI unaffected):** the `graphqlcodegen-maven-plugin` tracks per-file hashes in `target/graphqlcodegen/schema-manifest.props`; when a NEW SDL file is added and only it is "changed", the incremental generation regenerates only that file's content and clobbers the shared `DgsConstants` — a clean build (or removing the manifest + generated dir) regenerates the merged whole. CI's clean checkouts are unaffected; local incremental builds after adding an SDL file must clean once.
5. **The campaign-b6-b report's numeric drift the assignment flagged (obstacle 4):** `docs/governance/campaign/reports/campaign-b6-b.md` claims 37/50/11 over 98 Query roots while the standing inventory says 22/57/379 over 458 — left untouched (shared report), restated here for the post-collection correction.

---

## 4. Roots disposition (full detail in `agent-2-roots.csv`)

- **IMPLEMENTED (8):** `categoryCreate`, `categoryUpdate`, `categoryDelete`, `categoryBulkDelete`, `voucherCreate`, `voucherUpdate`, `voucherDelete`, `voucherBulkDelete` — each with the DGS proof, the real-stack integration legs (CI), payload-error mapping, typed global-ID codec round-trips, and permission negatives.
- **BLOCKED_BY_DECISION (documented, not fake no-ops):** `promotionCreate`/`promotionUpdate`/`promotionDelete`/`promotionBulkDelete` — the Saleor 3.23 promotion model is the RULES-engine system (`PromotionCreateInput.type`/`rules` with `cataloguePredicate` rewards); the platform's V223 promotion row is the classic discount policy (`discount_type`/`discount_value`/`min_spend`, all NOT NULL) with NO rules storage. Executing promotionCreate would require either fabricating the row's NOT NULL discount facts (dishonest) or building the rules engine (its own multi-packet project with a migration + reward handlers). The decision request is recorded here and in the SDL header.
- **Allocation-discrepancy note for the integration owner:** the standing inventory's `owner_agent` column labels the voucher/promotion MUTATION rows `A3` (and, e.g., webhooks `A5` — a partition that predates this 3-agent campaign), while this packet's commissioned checklist (`docs/governance/campaign/agent-tasks/agent-2-catalog-pricing.md` §"قائمة الجذور NOT_STARTED المسندة لك") assigns them to Agent 2 over the packet's exclusive module ownership (`marketplace-pricing/**`). The voucher slice here is real, tested and over the packet-owned module; if Agent 3 delivered the same roots independently, the integration owner picks one with this evidence in hand.
- **The remaining assigned roots stay NOT_STARTED** — honestly recorded in the CSV with their owning-module assessment; no pretend resolvers, no empty-list stubs, no success-shaped no-ops were created for them.

## 5. Flyway migrations

**None consumed.** Both slices run over pre-existing tables (`categories`; `vouchers` from V223). The reserved ranges `V253–V260` (P2) and `V261–V268` (P3) remain untouched for the follow-on roots (collections need one; the promotion rules engine, if decided, needs one).

## 6. SPIs

- **Added (this packet, both owned modules):** `CategoryWriteSpi` (+ `CategoryCreateCommand`, `CategoryUpdateCommand`, `CategoryMutationResult`, `CategoryMutationError`, `CategoryMutationErrorCode`), `VoucherWriteSpi` (+ the five command/result/error records).
- **Consumed through the existing adapters:** `CategoryReadSpi`, `VoucherView` (read side), `SaleorGlobalIdCodec`, `KeysetCursorGate`, `GraphQlProperties`, `VoucherGraphQlMapper`, `CategoryGraphQlMapper`.

## 7. CodeRabbit / review bots

- The PR was left in draft state by its opener; the draft→ready toggle did not take via the REST API. A manual review request was posted to the PR (`@coderabbitai review`, comment 6317378045) with the out-of-scope-blocker context. Self-review was performed continuously (every repair above was found by this session's own review of the frozen tree, not by the bots).
