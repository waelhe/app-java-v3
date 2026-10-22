# Agent 2 — The Post-Merge Follow-Up Round (PR #702)

The owner's post-merge review on PR #697 (comment 6347612814,
2026-10-22T17:58Z) — «مراجعة ما بعد الدمج — إخفاقات ظهرت عند تشغيل تكامل
المجالات» — read in full and executed exactly as prescribed: PR #697 stays
closed and untouched; the follow-up branches from the integration head
`d10a9012902637d0a14fd690f136706560e71f86` and carries
`7d3dc4a` (the seventeen-file fix commit) + `f46c572` (the two
park-then-settle unit-test contract updates).

## §0 The measured standing this round opened from

CI run 39804335540 on `d10a901` (the owner's cited run): the four gates
green (Build, GraphQL Schema Compatibility, OpenAPI, Android Contract),
ten jobs red. The full failure inventory, every leg classified to its
root:

| Root cause | Legs | Classes |
|---|---|---|
| A. The dead blanket-401 anonymous contract | 9 FAILUREs | Product, Collection, Customer, ProductVariant, Category, Home, PostDetail, PromotionVoucher, ProductVariantJourney |
| B. Soft-delete/FK/UNIQUE cleanup collisions | ~120 ERRORs | Product (25), Collection (17), ProductVariant (27), Category (27), Order (25), ProductMedia (6) |
| C. The NAME keyset cursor vocabulary | 1 FAILURE | Product.keysetCursorFollowThrough |
| D. Agent-2's own families (same run) | 6 | ProductTypeAttribute.reorders (2), Attribute.adminCreatesAnAttributeValue (1 — RL-001), ProductVariant.lastModifiedSort (1 — INT-001), ProductVariant.namelessLegacyVariant (1 — syntax), ProductVariantJourney (1 — counted in A) |
| E. marketplace-shared JaCoCo 0.68 < 0.70 | 1 | the modules job (stopped the reactor before the full unit round) |

## §1 The three owner-flagged defects — the root fixes

### 1) The NAME keyset cursor incompatibility
`ProductSort.Field.NAME("name")` vs Spring Data's derived scrolls
(`findByOrderByTitleAscIdAsc` / `TitleDesc` / the `findByStatus` variants)
which key the emitted cursors on `{title, id}` — the gate expected
`{name, id}` and rejected every follow-through cursor with VAL-001 («after
must be a forward keyset cursor emitted by products with the same
sortBy»). The field now carries the entity property the scrolls order by:
`NAME("title")` — the owner's own prescription («اجعل اسم الخاصية الذي
تحمله ProductSort هو خاصية الكيان الفعلية التي تستخدمها Spring Data»).
The Javadoc (which already said "the product's own title column") now
matches the code it documents.

### 2) The soft-delete/FK cleanup collisions
`BaseEntity` is `@SoftDelete(columnName = "is_deleted")` — every
repository delete is an `UPDATE is_deleted = true`, so the physical
fixture rows kept holding `fk_products_store_category` (Product,
Collection, ProductVariant, ProductMedia), `uq_categories_code` (Category
— the fixed fixture codes never re-insertable), and `fk_orders_users`
(Order — the seed INSERTs themselves failing: the seed built orders over
two random UUIDs that never existed as users, violating V113's FK at
INSERT time). All six classes now remove their fixture rows **physically,
through JDBC, in FK-respecting order**:

- Product: `products` → `product_types` → `store_categories` → `users`
- Collection: `collection_products` → `collections` → `products` →
  `store_categories` → `product_types` → `users`
- ProductVariant: `product_variants` → `products` → `product_types` →
  `store_categories` → `users`
- ProductMedia: `media_assets` → `products` → `store_categories` → `users`
- Category: `categories` (by id — the fixed codes' physical rows leave)
- Order: `orders` → `users` — and the seed now creates its two real users
  BEFORE the order INSERTs (the V113 law)

No FK weakened, no production soft-delete law changed — the owner's own
bars («لا تضعف FK ولا تغيّر سلوك soft delete الإنتاجي لتجاوز الاختبار»).

### 3) The anonymous-contract unification
Nine legs pinned the dead contract (401 + problem+json, or raw 401). All
now assert the live `PreLoginGraphQlGate` contract: HTTP 200, exactly ONE
GraphQL error carrying the canonical taxonomy pair
(`extensions.errorCode=AUTHN-001`, `extensions.category=authz`,
`extensions.classification=UNAUTHORIZED`), and **no `data` key at all** —
the non-leakage and the non-dispatch are both asserted. Home/PostDetail
keep their Mockito `never()` proofs (the resolver-did-not-run fact stays
measured, not inferred); `ProductVariantJourneyIntegrationTest` keeps its
body-level assertions through its own HTTP client. The owner's own bar:
«لا تتراجع عن سياسة المصادقة الجديدة، ولا تكتفِ بتغيير status فقط».

## §2 The same-family blockers the same run measured (the honest close)

- **`attributeReorderValues` / `productTypeReorderAttributes` INT-001**:
  the direct `saveAll(toSave)` landed UPDATEs on positions sibling rows
  still held mid-flight — `uq_catalog_attribute_options_position_live` /
  `uq_product_type_attribute_position_live` duplicate-key (the CI-measured
  23505). Both services now ride the standard **park-then-settle**
  rewrite inside the same transaction: park every moved row on a distinct
  negative position, `flush()` materializes the park, the settle writes
  the final compacted 1..n. The bijection argument (a final position
  either was held by another MOVED row — now parked — or by its own
  unchanged row, which no other row can target) makes the settle
  collision-free by construction. The two unit tests now assert BOTH
  passes (`f46c572`).
- **`lastModifiedSortScrollsAndPagesToo` INT-001 on the FIRST page**: the
  public `updated_at` keyset scrolls bound NULL without an inferable type;
  PostgreSQL refused statement preparation (the ID-keyed scrolls bound
  NULL fine — the measured delta). Every NULL-bindable parameter in the
  four updated_at scrolls now carries an explicit `CAST(:x AS
  timestamptz)` / `CAST(:lastId AS uuid)`.
- **`namelessLegacyVariantAnswersTheHonestNullName`**: the document text
  block carried stray literal quotes (`"query..."` with the quotes INSIDE
  the string) — the parser's syntax rejection, removed.
- **`adminCreatesAnAttributeValueUnderTheChoiceAttribute` RL-001**: the
  production write budgets (10/min fail-fast) are per-minute windows an
  integration class exhausts in seconds. `application-test.yml` (the
  test-only profile — the file A0 itself already owns on this branch)
  raises the shared default window via the documented relaxed-binding
  override; the named production instances and every existing rate-limit
  test (each pins its OWN instance budgets through
  `@SpringBootTest(properties=...)`, which take precedence) are untouched.
- **marketplace-shared JaCoCo 0.68 < 0.70**: closed at the root —
  `SearchCriteria` (the module's largest untested type-gate, 298 lines)
  now carries a full branch-covering contract test (every construction
  gate: the window invariant, the positive-or-absent numerics, the
  price-bound law with the zero floor, the radius triple with the
  coordinate ranges / the (0,50] cap / the meter granularity / the
  scale-6 center normalization, the min-rating bounds, the blank-category
  normalization, the legacy forms, and the presence probes). No threshold
  lowered.

## §3 The closure criteria (the owner's own bars)

1. **Keyset tests pass** — the cursor vocabulary is now one end-to-end
   (`title`+`id` from the record through the gate through Spring Data's
   emitted cursors).
2. **No FK failures in cleanup** — physical, relation-ordered fixture
   deletes across all six classes.
3. **Product/Collection HTTP-security tests pass under the unified
   contract** — the AUTHN-001 assertions above.
4. **The checks re-run on the current integration head** — this branch
   grows from that exact head (`d10a901`), and the CI dispatch on it
   (run 39842487999) IS that re-run.

## §4 Honesty notes

- This session's JDK is 21 and the reactor is `--release 25` — no local
  JVM test execution is possible; every causal claim above cites the CI
  round that measured it, and this branch's own CI run is the executable
  proof.
- The RL-001 and JaCoCo fixes are test-infrastructure (a test-profile
  window override, a new contract test) — production configuration is
  byte-identical.
- CodeRabbit was manually activated on the head (`7d3dc4a`, comment
  6349640633); the monitoring is the no-wait protocol's simple watch.
