# Agent 2 — Shared-file requests for A0 (the central integration pass)

> **Round 8 (the owner's new guidance, PR comment 2026-10-22T09:36Z):** these requests were previously direct shared-file edits inside the agent-2 branch; per the guidance they are now REMOVED from the PR's scope (commit `f683f682`) and handed to A0 here. Every request carries its measured value, its rationale, and its DECLARED interim behavior (never silent).

## 1. Rate-limiter instances — `marketplace-app/src/main/resources/application.yml` (resilience4j.ratelimiter.instances)

The Java `@RateLimiter` annotations on the agent-2 fetchers reference these nine named instances. Until A0 registers them, each annotation falls back to the registry's default budget — the base's own measured default config: `limit-for-period: 100`, `limit-refresh-period: 1m`, `timeout-duration: 5s`. **This interim fallback is DECLARED here, not silent** — it is materially more permissive than the intended fail-fast write budgets (10/min, `timeout-duration: 0`).

| Key | Proposed entry (verbatim yaml) | Owning roots (the annotation sites) |
|---|---|---|
| `graphqlCategoryMutation` | `base-config: default` / `limit-for-period: 10` / `timeout-duration: 0` | `CategoryMutationDataFetcher` (categoryCreate/Update/Delete/BulkDelete) |
| `graphqlProductTypeMutation` | same shape | `ProductTypeMutationDataFetcher` |
| `graphqlVoucherMutation` | same shape | `VoucherMutationDataFetcher` |
| `graphqlAttributeMutation` | same shape | `AttributeMutationDataFetcher` |
| `graphqlAttributeValueMutation` | same shape | `AttributeValueMutationDataFetcher` |
| `graphqlProductVariantMutation` | same shape | `ProductVariantMutationDataFetcher` (incl. the bulk roots) |
| `graphqlCollectionMutation` | same shape | `CollectionMutationDataFetcher` |
| `graphqlProductMediaMutation` | same shape | `ProductMediaMutationDataFetcher` |
| `graphqlCollectionQuery` | `base-config: default` / `limit-for-period: 50` / `timeout-duration: 0` | `CollectionDataFetcher` (collection/collections + Collection.products) |

**Rationale (the platform's own precedents):** the write instances ride the `graphqlStoreProductMutation` precedent's own number (10/min fail-fast — mutation traffic must never queue); the read instance rides the shared catalog read budget (50/min — the `graphqlCategoryQuery` precedent). The earlier binding mistake (mutations sharing READ buckets) was the closed CodeRabbit finding — dedicated write instances are the fix, and they need these keys.

## 2. Migration-checksum registration — `marketplace-app/src/test/resources/migration-checksums.properties`

Register, within the central integration pass after verifying the final migration content:

```
V253__collections.sql=572066309
```

The value is the Flyway `ChecksumCalculator` (BOM-pinned 12.4.0) computation over the V253 file as it stands on this branch — the same value `flyway_schema_history.checksum` stores. **DECLARED CONSEQUENCE of the removal:** `MigrationChecksumGuardTest` rule 2 (unregistered migration) now FAILS BY DESIGN on this branch until A0 adds the line — the guard itself is untouched (weakening a guard is forbidden); the failure is the visible, documented cost of moving registration to the central pass, exactly as the owner's guidance directs.

## 3. permission_scope drifts — the 31-row central-ledger list + enforcement review request

The full 31-row table lives in `agent-2-completion.md` §1.0.2 (root / ledger status / central cell drift / pinned requirement). The request to A0: mirror the pinned requirements into the central root inventory during the collection pass.

**The enforcement review request (the guidance's own point):** the CSV correction alone does not prove the permission is enforced in Java. The enforcement reality on this packet's delivered roots (measured in the owning use cases' own code): the `@PreAuthorize` families ride the platform's role model — PROVIDER for the MANAGE_PRODUCTS-family roots (the two-sided ownership law over provider-owned rows), ADMIN for the MANAGE_TRANSLATIONS-family roots and the MANAGE_PRODUCT_TYPES_AND_ATTRIBUTES-family roots. A0's integration review should re-verify each delivered root's `@PreAuthorize` at the use-case boundary against the pinned requirement — the ten delivered rows were verified pinned-faithful at the correction pass; the blocked rows' enforcement lands with their future implementations.

## 4. SYSTEM.md derived counts (the standing A0 proposals, unchanged)

(a) The failsafe selection line: "**176**" → "**179**" (the measured tree: 178 on the clean frozen head + `CollectionGraphQlIntegrationTest`; the `DocumentationNumbersGuardTest` legs fail identically on this branch head until the central edit).
(b) The migration inventory: "194 ترحيلة نسخية `V1..V247` — **197 ملفًا في الشجرة**" → "195 ترحيلة نسخية `V1..V253` — **198 ملفًا في الشجرة**".
Both live in the central `SYSTEM.md` this packet must not edit; the exact edits are recorded here for A0's collection pass.
