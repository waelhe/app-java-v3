# Saleor Dashboard Baseline and Concurrent PR Evidence Audit

**Evidence date:** 19 October 2026  
**Repository baseline:** waelhe/app-java-v3 main at 72ebec6319f51f13ecaf5c24aa6566b5b6457ea7  
**Status:** Evidence snapshot; this document does not authorize a merge, deployment, or claim of full Saleor parity.

## 1. Decision summary

The plan now pins a reproducible upstream Dashboard source and identifies the first-slice API gaps. The unmodified Saleor Dashboard E2E baseline has passed against a disposable Saleor instance at the exact pin (run [39190394506, job 117861701008](https://github.com/waelhe/app-java-v3/actions/runs/39190394506/job/117861701008)). This closes only the upstream baseline gate; it does not prove compatibility with Spring. The separate Spring-backed catalog browser journey, current-base reconciliation, full CI and fork GO/NO-GO evidence remain outstanding.

Evidence states must not be conflated:
- **Source inspected** means the linked source revision was fetched and examined.
- **Workflow success** means GitHub reported a passing result for that exact commit and named workflow.
- **Journey proven** requires an executable test which traverses the specified real contracts and fails on an unmet condition.
- **Production verified** requires a separate deployment/smoke observation. None is claimed by this documentation audit.

## 2. Pinned upstream source

| Item | Evidence |
|---|---|
| Repository | https://github.com/saleor/saleor-dashboard |
| Release tag | 3.23.39 |
| Exact commit | [bc75e988da0fa4fbc3d47378a888d859d070746f](https://github.com/saleor/saleor-dashboard/commit/bc75e988da0fa4fbc3d47378a888d859d070746f) |
| Package version | 3.23.39 in [package.json at the tag](https://github.com/saleor/saleor-dashboard/blob/3.23.39/package.json) |
| License | BSD-3-Clause, as declared in package.json |
| Runtime | Node >=24 and <25; pnpm >=11; package manager pinned to pnpm 11.13.1 |
| Lockfile | pnpm lockfile v9 |
| Normal local E2E runner | [e2e/run.ts](https://github.com/saleor/saleor-dashboard/blob/3.23.39/e2e/run.ts); requires a disposable Docker-backed Saleor stack |
| Dedicated E2E workflow | [e2e-local.yml](https://github.com/saleor/saleor-dashboard/blob/3.23.39/.github/workflows/e2e-local.yml); manual/nightly workflow distinct from the regular main CI workflow |

### Upstream workflow evidence available during this audit

The commit-linked workflow records for the pinned revision included successful runs named Testing, Check Licenses, Knip, MacawUI migration, CodeQL Advanced, QA, and Changesets. A separate PR cleanup workflow was reported failed; that housekeeping failure must not be represented as a completely green set of all upstream workflows.

The E2E local-stack workflow is distinct from regular CI. The pinned upstream E2E suite passed against disposable Saleor in [run 39190394506, job 117861701008](https://github.com/waelhe/app-java-v3/actions/runs/39190394506/job/117861701008), including the original stable-target Playwright suite, report upload and teardown. The upstream baseline gate is therefore **PASSED at the pinned source**. This result is not evidence that custom platform GraphQL operations or the Spring-backed UI journey work.

### Version identity check

The official [Saleor Dashboard releases page](https://github.com/saleor/saleor-dashboard/releases/) marked **Dashboard 3.23.39** as its latest release during this evidence snapshot. The similarly numbered **3.23.40** seen on [Saleor Core releases](https://github.com/saleor/saleor/releases) belongs to the different `saleor/saleor` repository, not `saleor/saleor-dashboard`. Do not substitute a Core tag for the Dashboard source pin.

## 3. Dashboard operation inventory for the initial goods slice

The inventory below was gathered from the pinned source files, not inferred from screenshots or a different moving branch.

### Routes/components located

| Work surface | Route / source evidence | Candidate scope |
|---|---|---|
| Products | [src/products/index.tsx](https://github.com/saleor/saleor-dashboard/blob/3.23.39/src/products/index.tsx): product list, create redirect, product details/edit, variant add/edit, product image | C2 candidate; must be adapted to the Spring product ownership model |
| Product types | [src/productTypes/index.tsx](https://github.com/saleor/saleor-dashboard/blob/3.23.39/src/productTypes/index.tsx): list, create, update | C2 only after Spring ProductType contracts exist |
| Attributes | [src/attributes/index.tsx](https://github.com/saleor/saleor-dashboard/blob/3.23.39/src/attributes/index.tsx): list, create, detail | C2 only after reusable attribute contracts exist |
| Categories | [src/categories/index.tsx](https://github.com/saleor/saleor-dashboard/blob/3.23.39/src/categories/index.tsx): list, create, detail | Separate navigation taxonomy; do not equate it with ProductType |
| Collections | [src/collections/index.tsx](https://github.com/saleor/saleor-dashboard/blob/3.23.39/src/collections/index.tsx): list, create, detail and product membership | Defer to C3 and its own canonical-source/audience rules |
| Orders | [src/orders/index.tsx](https://github.com/saleor/saleor-dashboard/blob/3.23.39/src/orders/index.tsx): orders/drafts, fulfillment and several refund/return routes | Later work surface; map each operation to the owning Spring module before adoption |
| Promotions/vouchers | [src/discounts/index.tsx](https://github.com/saleor/saleor-dashboard/blob/3.23.39/src/discounts/index.tsx): sale/promotion and voucher routes | Later C6; do not expose unsupported stacking/eligibility rules |

The main Dashboard route table also includes channels, warehouses, shipping, taxes, staff, permission groups, site settings, translations, extensions and other surfaces. Their presence upstream does not make them part of C2; each must be explicitly selected and mapped before implementation.

### Product operations found in pinned source

Product queries include ProductList, ProductCount, ProductDetails, ProductType, ProductVariantDetails, ProductVariantCreateData, ProductMediaById, GridAttributes, AvailableColumnAttributes, StockVisibilityMode, ChannelDiagnostics, ProductVariantsGrid, ProductDoctorVariants, ProductVariantSiblings, ProductVariantSkusExist, and ProductVariantGeneratorExistingVariants.

Product mutations include ProductCreate, ProductUpdate, ProductDelete, productBulkDelete, VariantCreate, VariantUpdate, VariantDelete, ProductVariantBulkCreate, ProductVariantBulkDelete, ProductVariantBulkUpdate, ProductVariantReorder, ProductVariantSetDefault, ProductMediaCreate, ProductMediaDelete, ProductMediaBulkDelete, ProductMediaUpdate, VariantMediaAssign, VariantMediaUnassign, ProductExport, ProductChannelListingUpdate, and ProductVariantChannelListingUpdate.

ProductType operations include ProductTypeList, ProductTypeDetails, ProductTypeCreate, ProductTypeUpdate, ProductTypeDelete, ProductTypeBulkDelete, AssignProductAttribute, UnassignProductAttribute, ProductTypeAttributeReorder, and ProductAttributeAssignmentUpdate.

Attribute operations include AttributeList, AttributeDetails, AttributeCreate, AttributeUpdate, AttributeDelete, AttributeBulkDelete, AttributeValueCreate, AttributeValueUpdate, AttributeValueDelete, AttributeValueBulkDelete, and AttributeValueReorder.

This is the source-operation inventory for the adopted candidate screens, not an instruction to reproduce all of Saleor's operation surface. Before each screen is adopted, its selected operations must be connected to an owning Spring contract, authorization rule, error contract, read-back, and test or deliberately removed/deferred.

## 4. Current Spring contract comparison

The current application tree inspected in this audit is main at 72ebec6319f51f13ecaf5c24aa6566b5b6457ea7.

| Backend evidence | Actual observed capability | Compatibility outcome |
|---|---|---|
| [ProductController.java](https://github.com/waelhe/app-java-v3/blob/72ebec6319f51f13ecaf5c24aa6566b5b6457ea7/marketplace-catalog/src/main/java/com/marketplace/catalog/ProductController.java) | POST /api/v1/store/products and GET /api/v1/store/products/{id}; product registration plus owner-scoped read | No direct equivalent for the Dashboard product list/editor workflow; product update/publish operations are not present in this controller |
| [ProductVariantController.java](https://github.com/waelhe/app-java-v3/blob/72ebec6319f51f13ecaf5c24aa6566b5b6457ea7/marketplace-catalog/src/main/java/com/marketplace/catalog/ProductVariantController.java) | Variant create/list/read, inventory update, suspend/reactivate/archive | A foundation exists, but no typed selectable-option contract or variant update contract for the full Dashboard editor was found in this controller |
| [CategoryAttributeController.java](https://github.com/waelhe/app-java-v3/blob/72ebec6319f51f13ecaf5c24aa6566b5b6457ea7/marketplace-catalog/src/main/java/com/marketplace/catalog/CategoryAttributeController.java) | Category-scoped attributes, limited to TEXT/NUMBER/BOOLEAN | Not a product-type schema or reusable product/variant attribute-assignment model |
| [ProductVariantJourneyIntegrationTest.java](https://github.com/waelhe/app-java-v3/blob/72ebec6319f51f13ecaf5c24aa6566b5b6457ea7/marketplace-app/src/test/java/com/marketplace/catalog/ProductVariantJourneyIntegrationTest.java) | Existing real product/variant journey coverage | Useful baseline; does not prove a Dashboard-backed product list/create/edit/publish journey or ProductType behavior |
| [GraphQL schema SDL](https://github.com/waelhe/app-java-v3/blob/72ebec6319f51f13ecaf5c24aa6566b5b6457ea7/marketplace-app/src/main/resources/graphql/schema.graphqls) | Retail Product, ProductVariant and ProductType are not exposed as GraphQL types/operations; Mutation currently contains createService | Dashboard's Saleor GraphQL operations cannot be pointed at this SDL; no URL-only adaptation works |

The exact REST surface and its security/error semantics must be expanded by source contract inventory before UI implementation. The separate catalog listing, provider directory, property listing, job and booking contracts must not be mislabeled as interchangeable with store Product. Social and tourism use cases retain their own source-owned lifecycles.

### Required backend-first contracts before the initial Dashboard flow

1. Define ProductType separately from StoreCategory/navigation taxonomy; define reusable Attribute definitions and explicit product/variant/selection assignments, validation and allowed values.
2. Expose owner-scoped read/list/create/update/publication operations needed by the chosen product-list and editor journeys; use existing versioned API conventions, not invented Saleor operation names.
3. Add the typed variant option/allowed-value and canonical combination uniqueness rules while preserving free-form name compatibility and the zero-variant path.
4. Map cart/order read-back to the selected variant and frozen authoritative price; keep ADR-0010 all-or-nothing multi-seller reservation and one-order-per-seller behavior.
5. Add additive Flyway migrations and Envers for new audited domain entities, method/service authorization, negative cases, OpenAPI compatibility checks and real integration journeys.
6. Only then adapt the selected Dashboard routes to the platform's real contract, with generated frontend types from that approved contract and no Saleor schema fetch/backend at runtime or build-time.

## 5. Live open-PR wave — refreshed 2026-10-19 04:06 +03:00

This section is a time-bounded snapshot fetched from GitHub pull-request metadata, commit status contexts, and workflow runs. It must be refreshed again before any merge decision. The repository `main` head at this observation is `72ebec6319f51f13ecaf5c24aa6566b5b6457ea7`; its parent is `63871754ba516a32dd03af791d8836fba09d4486`. The previously-open PR #652 has since been merged and is not part of the open queue. The nine currently open PRs in scope are #649, #651, #653–#658, and #664.

| PR | Current head SHA (short) | Base metadata at observation | Workflow run state for that exact head | External commit status contexts |
|---|---|---|---|---|
| [#649](https://github.com/waelhe/app-java-v3/pull/649) booking/payment/review/cancel journey | `ef30b27f` | `main` at `72ebec63…` | Integration Test **queued**; Container Scan **queued**; CodeQL **success**; CI **queued** | Snyk **success**; CodeRabbit **success** |
| [#651](https://github.com/waelhe/app-java-v3/pull/651) jobs journey | `521f8afd` | recorded base SHA `63871754…`, one commit behind current `main` | Integration Test **success**; Container Scan **success**; CodeQL **success**; CI **queued** | Snyk **success**; CodeRabbit **success** |
| [#653](https://github.com/waelhe/app-java-v3/pull/653) ad-origin payment/refund journey | `7a0e7d7f` | recorded base SHA `63871754…`, one commit behind current `main` | Integration Test **success**; Container Scan **success**; CodeQL **success**; CI **queued** | Snyk **success**; CodeRabbit **success** |
| [#654](https://github.com/waelhe/app-java-v3/pull/654) store-order delivery leg | `acbf32a4` | recorded base SHA `63871754…`, one commit behind current `main` | Integration Test **success**; Container Scan **queued**; CodeQL **success**; CI **queued** | Snyk **success**; CodeRabbit **success** |
| [#655](https://github.com/waelhe/app-java-v3/pull/655) direct delivery/fleet journeys | `b644a7ba` | recorded base SHA `63871754…`, one commit behind current `main` | Integration Test **success**; Container Scan **success**; CodeQL **success**; CI **queued** | Snyk **success**; CodeRabbit **success** |
| [#656](https://github.com/waelhe/app-java-v3/pull/656) device registration/preferences/push journey | `1112cccfff` | recorded base SHA `63871754…`, one commit behind current `main` | Integration Test **success**; Container Scan **success**; CodeQL **success**; CI **queued** | Snyk **success**; CodeRabbit **success** |
| [#657](https://github.com/waelhe/app-java-v3/pull/657) news/urgent-alert journeys | `b152f88b` | recorded base SHA `63871754…`, one commit behind current `main` | Integration Test **success**; Container Scan **success**; CodeQL **success**; CI **queued** | Snyk **success**; CodeRabbit **success** |
| [#658](https://github.com/waelhe/app-java-v3/pull/658) daily-need/tourism-stay/returnee journeys | `f3b91605` | recorded base SHA `63871754…`, one commit behind current `main` | Integration Test **success**; Container Scan **success**; CodeQL **success**; CI **queued** | Snyk **success**; CodeRabbit **success** |
| [#664](https://github.com/waelhe/app-java-v3/pull/664) Saleor alignment plan and evidence | `1098afea` | `main` at `72ebec63…` | Integration Test **success**; Container Scan **success**; CodeQL **success**; CI **queued** | Snyk **success**; CodeRabbit **success** |

**Interpretation and safety conditions**

- A queued workflow is not a pass. At this observation, none of the open heads above has a completed CI run; #649 and #654 also have Container Scan queued. These statuses are attached to the exact SHAs in the table, not inferred from a different head.
- GitHub reports `mergeable=true` for the listed PR metadata, but that flag alone does not demonstrate completion of CI or that an older recorded base has been reconciled. PRs #651–#658 retain recorded base SHA `63871754…` while current `main` is one commit ahead at `72ebec63…`; confirm the live compare/base state and reconcile each branch in an intentional order before accepting it.
- Eight journey/feature PRs (#649 and #651–#658) overlap in `SYSTEM.md` and `docs/governance/plans/end-to-end-journeys-register.md`. They must be reconciled rather than copied or merged as a batch. Recalculate any derived Failsafe counts after each accepted change.
- #654 is higher risk than a test-only PR: its head changes the production Order entity and adds Flyway V235. Validate that change against the accepted #604/#607 delivery/order baseline, migration ordering/checksums, compatibility, and the final merged base before it is treated as current. #656 also changes production `PushTokenService` and requires its own semantic review.
- PR #652 is reported `merged=true`; do not count it as open or reapply its work. Its merged code may be treated as repository history, but resulting behavior still needs the relevant current tests and environment proof.
- For the #664 head recorded in this snapshot (`1098afea`), Snyk and CodeRabbit were successful; Integration Test, Container Scan, and CodeQL were successful; CI was queued. Later documentation refreshes advanced the branch head, so these results **do not apply to a newer SHA**. Before any merge decision, fetch the live PR head and confirm its own workflow runs and status contexts; a queued workflow is not a pass.
- The upstream baseline is evidenced as passing in [run 39190394506, job 117861701008](https://github.com/waelhe/app-java-v3/actions/runs/39190394506/job/117861701008). The open platform PRs do not prove production behavior or the separate Spring-backed Playwright create/edit/publish/read-back journey, which remains unproven until its protected workflow passes. C1 ProductType/attribute/typed-option contracts and current PR/base reconciliation remain merge gates. No merge or deployment is authorized by this audit.

Recheck the linked live pull requests before acting: [#649](https://github.com/waelhe/app-java-v3/pull/649), [#651](https://github.com/waelhe/app-java-v3/pull/651), [#653](https://github.com/waelhe/app-java-v3/pull/653), [#654](https://github.com/waelhe/app-java-v3/pull/654), [#655](https://github.com/waelhe/app-java-v3/pull/655), [#656](https://github.com/waelhe/app-java-v3/pull/656), [#657](https://github.com/waelhe/app-java-v3/pull/657), [#658](https://github.com/waelhe/app-java-v3/pull/658), and [#664](https://github.com/waelhe/app-java-v3/pull/664).

## 6. Journey evidence and boundaries

- #649 is an open backend integration-test PR for booking/payment/review/cancellation/refund, not UI coverage.
- #658 is an open backend integration-test PR for daily-need purchase, tourism stay/booking/refund/rebooking, and returnee access, not proof that the Android or Dashboard client journey is complete.
- The tourism audit must continue to distinguish lodging/tour/experience availability and guest capacity from retail ProductVariant warehouse stock. Booking prices/terms freeze at their owner boundary, with payment, provider fulfillment, reschedule/cancel/refund and review eligibility tested as appropriate.
- The community journey must retain canonical product/provider/destination/experience identifiers. A recommendation is not a shadow product; publication, neighborhood membership, privacy and moderation remain enforced by their owning modules.
- The open test PRs should be reconciled, not copied. A single test's existence, CI's translation result, a merged commit or a migration file on an unmerged branch is not by itself end-to-end or production proof.

## 7. Dashboard-fork GO / NO-GO gate

After completing the first Spring-backed goods journey, record measured results for each item:

- **Operation mapping:** 100% of the selected route's runtime operations map to an approved Spring-owned API contract or are explicitly removed/deferred before shipping; zero unmapped live operations.
- **Real journey:** Playwright creates/edits/publishes a product through the platform contract and verifies the actual storefront/discovery read-back. At least one negative suite verifies wrong-owner access, invalid/duplicate variant combinations, validation errors and failed/no-op outcomes.
- **Legacy compatibility:** existing zero-variant products and their cart/order price snapshots pass unchanged.
- **E2E preservation:** every upstream product/editor test scenario is either retained or replaced with a demonstrated equivalent; list retained, replaced, and intentionally removed cases explicitly.
- **No parallel backend:** zero runtime calls to Saleor APIs and zero build-time fetch/generation against Saleor schemas in the adapted app.
- **Upstream maintainability:** demonstrate applying/rebasing one selected upstream fix to the fork and passing the targeted regression suite; publish the source-file change ratio for the scoped product/admin code and the files kept unmodified.
- **Security/operability:** approved API permissions/CSRF/auth, audit trail, telemetry, error handling, build/release and accessibility checks are included in the acceptance evidence.

**GO** only when every hard gate above has evidence and the measured fork changes remain isolated to the documented adapter and adopted screens. **NO-GO / reassess extraction** if the core UI requires pervasive changes across unrelated Dashboard domains, upstream scenario coverage cannot be retained/replaced, or the platform's contracts cannot support the flow safely. No percentage for allowable fork churn is silently invented here; the first pilot must record the measured ratio, after which the owner can ratify a justified threshold before broader migration.

## 8. Evidence limitations and finality verdict

Not completed by this audit:
- No local Maven build, platform integration suite, browser Playwright test, disposable Saleor launch, or production smoke test was executed.
- The unmodified Dashboard 3.23.39 E2E baseline is verified successful: [run 39190394506, job 117861701008](https://github.com/waelhe/app-java-v3/actions/runs/39190394506/job/117861701008). The custom Spring-backed Playwright journey remains a separate, unpassed gate.
- No live staging OpenAPI/GraphQL document was fetched from the running platform; current contracts were inspected from the pinned repository tree.
- No open PR was merged or deployed.
- PR check statuses can change; the open PR table must be refreshed before execution or merge.

**Verdict: NOT YET FINAL.** The pinned upstream tag, disposable-Saleor E2E baseline and initial source-route/operation inventory are evidenced. The upstream E2E gate is closed as **PASSED** by run 39190394506/job 117861701008. Final approval remains blocked on current-main/open-PR reconciliation, the backend-first C1 contract/acceptance gates, a real Spring-backed Dashboard Playwright journey and the remaining fork GO/NO-GO evidence.
