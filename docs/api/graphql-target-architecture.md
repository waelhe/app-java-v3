# GraphQL Target Architecture and Evolution Plan

**Status:** Target architecture and staged implementation plan — not implementation-complete  
**Last repository inspection:** 2026-10-20  
**Repository baseline:** waelhe/app-java-v3, Java 25, Spring Boot 4.1.1, Spring Modulith 2.1.1, Maven modular monolith  
**Target GraphQL model:** Netflix DGS Framework programming model, using DGS's Spring for GraphQL integration; Spring Boot owns runtime auto-configuration and Maven owns backend build/code generation.  
**Scope:** GraphQL in marketplace-app, its security/configuration/tests, Modulith boundaries, Maven generation, CI contract gates, and the Android client contract.  
**Phase-0 baseline record:** docs/api/graphql-baseline-2026-10.md — versions, consumers, tests, and CI gates measured 2026-10-15. The record describes the pre-DGS baseline and must remain historical evidence, not be rewritten as if DGS were already installed.

> This is a target design and staged acceptance plan. It does not authorize a wholesale REST migration, an all-at-once schema expansion, or a change to anonymous/public access policy without the required product and security decisions. Implementation steps must remain incremental and reviewable.

## 1. Executive decision

Adopt **Netflix DGS Framework as the single GraphQL application programming model** for marketplace-app. Use its modern Spring for GraphQL integration through the DGS starter: Spring for GraphQL remains the underlying execution/transport integration, not a second resolver programming model.

Ownership is explicit:

- **Spring Boot** owns application startup, dependency injection, environment/profile configuration, managed Spring integrations, and the application context.
- **Maven** owns dependency alignment, plugin lifecycle, code generation, compilation, tests, architecture checks, and build gates. A normal Maven clean verify of marketplace-app and its required modules must reproduce generated code from a clean tree.
- **DGS** owns the GraphQL programming model: @DgsComponent, @DgsQuery, @DgsMutation, @InputArgument, and DGS DataLoader conventions. Do not mix these with Spring resolver annotations such as @QueryMapping/@MutationMapping in the final codebase.
- **Spring Security, Spring Modulith, Spring Data, Bean Validation, transactions/events, Micrometer and Actuator** keep their existing ownership of security, module boundaries, data access, correctness and operations. GraphQL is an adapter into those capabilities, not an alternate implementation of them.
- **Apollo Kotlin** consumes a versioned schema artifact to generate Android operation types; it is not a server runtime dependency.

The DGS Framework 10+ integration uses Spring for GraphQL internally; the DGS documentation specifically recommends choosing one programming model instead of mixing DGS and Spring for GraphQL resolver/data-loader models. The framework's dgs-starter and dgs-starter-test are the intended runtime and test entry points. The DGS platform BOM aligns DGS modules. These must be verified against Boot 4.1.1 and the repository's resolved dependency graph before pinning a DGS release.

The CodeGen constraint is explicit: Netflix's official CodeGen Gradle plugin is the vendor-supported plugin, while the Maven plugin path is a community adapter. Because this project requires Maven to own backend build-time automation, the plan chooses the community Maven adapter only with its version pinned centrally and its lifecycle, output isolation, dependency convergence and CI behavior enforced. This is a conscious tool-source trade-off, not a claim that Netflix publishes an official Maven plugin.

Do not turn GraphQL into a mirror of all REST endpoints, a public database graph, or a home for domain logic. Use it for bounded, journey-aware data composition and selected commands that delegate to authoritative use cases. REST remains for webhooks, uploads/downloads, partner integrations, and operations whose HTTP contract is the better fit. Both transports must invoke the same business use case.

**Success means the schema, generated source, security, boundaries, client operations, and compatibility checks are managed by the build and framework wherever possible, while business rules remain explicit and testable.**

## 2. Current implementation status (verified 2026-10-19)

### 2.1 Implemented GraphQL surfaces

The earlier pre-DGS inventory is preserved in `docs/api/graphql-baseline-2026-10.md`; it is historical evidence, not the current implementation.

The active application uses **Netflix DGS as its only resolver programming model**:
- `ServiceDataFetcher` exposes `service`, bounded `services(first, after)`, and `createService`.
- `StoreProductDataFetcher` exposes provider-owned catalog read/write operations for products, ProductTypes, typed attributes, and variants.
- Both adapters live in `marketplace-app` and delegate to declared catalog SPIs/use cases; domain entities and repositories do not cross module boundaries.
- ProductType remains separate from StoreCategory. Product and variant lifecycle state and typed attribute scopes are domain-owned, not resolver-owned.
- The public wire contract for money in the goods catalog uses decimal-string minor units; legacy service fields remain legacy contracts and should not be reused as the platform-wide money contract.

### 2.2 Framework ownership and automatic build contract

- `ArchitectureRulesTest` enforces a single GraphQL resolver model and rejects the old Spring `@QueryMapping`/`@MutationMapping` annotated-controller model. DGS owns the resolver model; Spring Boot and Spring for GraphQL keep runtime auto-configuration, security, validation, transactions, metrics and HTTP execution.
- Maven generates DGS constants and generated transport types from the authoritative SDL under `marketplace-app/src/main/resources/graphql/**`; generated output is build output, not hand-edited source.
- The DGS BOM/starter/test integration is pinned centrally. The Maven CodeGen adapter is community-maintained (Netflix's official CodeGen plugin is Gradle-only); this provenance is an explicit Maven-workflow tradeoff, not an official Netflix Maven-plugin claim.
- The build has GraphQL schema compatibility and accepted-SDL publication gates. The current `main` after the catalog GraphQL merge has green JDK 25 build/test, coverage, full integration, GraphQL/OpenAPI compatibility, CodeQL, container and Android GraphQL contract checks.
- Pagination defaults and hard maxima, query-depth and weighted-complexity instrumentation, rate limits, safe base-profile GraphiQL/introspection/WebSocket defaults, and GraphQL observation wiring are configured and covered by code/tests. Staging/production keep introspection and GraphiQL disabled; no GraphQL WebSocket path is configured while no Subscription journey is approved.

### 2.3 Known gaps that remain open

| Finding | Verified state | Next acceptance target |
|---|---|---|
| Platform-wide graph coverage | The implemented vertical slices do not yet constitute the whole social-commerce-tourism graph. Community, institutions, cross-experience discovery, services, property, booking, order/payment, reviews and messaging journeys must be introduced only against approved end-to-end journeys. | Expand incrementally by journey; do not copy every REST endpoint or database table into GraphQL. |
| Cursor semantics | PR #677 introduces Spring Data `Window`/`KeysetScrollPosition` for `storeProducts` and `services`, with stable `createdAt DESC, id DESC` ordering and matching partial PostgreSQL indexes. Its build, GraphQL/OpenAPI compatibility, integration, Android contract, CodeQL and Trivy checks passed on the inspected head; the app/module test shards had not all completed at that inspection. A stacked follow-up adds a bounded product search/filter/sort path on the same keyset contract. Neither change closes full Saleor parity. | Keep cursors opaque and require PostgreSQL continuation tests plus GraphQL HTTP round-trips on the accepted final heads. Do not close P0 until all test shards, codegen/publication, compatibility and downstream-client gates pass. |
| Anonymous public discovery | The current GraphQL endpoint is protected (anonymous calls return 401); the new catalog contract enforces authenticated provider ownership. | Keep it protected until public-read policy and field/object authorization are explicitly decided and tested per journey. |
| Saleor-aligned product roster controls | Dashboard 3.23.39’s upstream product roster uses filter/search/where/sort/channel controls. The platform-specific roster is not Saleor’s `products` field; this backend slice adds typed search over title/description, category, ProductType, lifecycle state, currency-scoped minor-unit price bounds, and allow-listed sort by creation time/title/price/status. Every query retains provider scope from the authenticated principal. Channel, collection, stock, metadata/attribute, ranking and published-state filters are not represented by this slice and remain deferred until their domain contracts, authorization and indexing are implemented. | Keep the platform operation typed against the accepted generated schema; do not hand-edit generated SDL or claim upstream `ProductFilterInput` parity. A cursor is a keyset position, not a query fingerprint: clients must start again without `after` when changing filter or sort and keep both filter and sort stable while continuing. Since each product has its own currency, both price bounds and `PRICE_MINOR` sorting require an explicit currency filter; minor-unit prices from different currencies must never be compared in one range or order. Bounds remain non-negative decimal integer strings to avoid GraphQL `Float` and 32-bit `Int` precision loss. Publish the accepted SDL, regenerate platform operation types, then prove UI-to-GraphQL filters and sorted cursor continuation through Playwright. |
| P3 seller order workflow | The stacked draft PR adds `mySoldOrders`, `soldOrder`, `confirmSoldOrder`, `fulfillSoldOrder`, and `cancelSoldOrder` through `SellerOrderManagementPort`. The adapter delegates to existing seller-scoped `OrdersService` methods, so the state machine, stock-commit/release, audit, and events remain the existing ones. Seller history is clamped to at most 50 records and no provider identifier is accepted from the caller. | Requires final JDK 25/GraphQL/OpenAPI/security CI and browser journey before the seller workbench gate closes. For platform-delivery orders, order confirmation already links to the delivery lifecycle; no separate delivery state machine is introduced here. |
| P1 category and product-media GraphQL surface | The existing store-category dictionary and media service are now exposed through `storeCategories`, `storeCategory`, `storeProductMedia`, `requestStoreProductMediaUpload`, `confirmStoreProductMediaUpload`, and `deleteStoreProductMedia` in the stacked P1 PR (not merged yet). Reads and commands delegate through the catalog SPI and the media module's `ProductMediaAdminPort` to the existing services; the upload uses the existing presigned-URL + server-side confirmation contract, not GraphQL multipart streaming. A real PostgreSQL GraphQL HTTP test covers category reads, request/confirm/list/delete media lifecycle, provider-only category visibility, and foreign-owner denial with storage mocked at the external boundary. | The new P1 backend contract still needs final CI acceptance, accepted-SDL publication/client regeneration, and a Dashboard browser journey before the P1 wave can be closed. The custom `StoreCategory` remains the platform's product-registration dictionary, not a claim of full Saleor `Category` parity. |
| P2 buyer cart/checkout/order/payment journey | Stacked draft PR #680 adds `myCart`, `addToCart`, `removeFromCart`, `checkoutPreview`, `placeOrder`, `myOrders`, `commerceOrder`, and payment-intent queries/commands through `OrderCommercePort` and `PaymentIntentClientPort`. Money uses decimal-string minor units; the resolver does not accept client-supplied unit prices or totals. | Keep order pricing, seller splitting, stock reservation, delivery-fare freezing, PSP processing, cancellation, and payment-event/refund behavior in the existing owning services. Acceptance still requires the final JDK 25 build, GraphQL/OpenAPI compatibility, integration/security/idempotency tests, accepted-SDL publication, and a real Dashboard browser journey. |
| P2 fulfillment sub-wave — seller order controls | Stacked draft PR #681 adds `mySoldOrders`, `soldOrder`, `confirmSoldOrder`, `fulfillSoldOrder`, and `cancelSoldOrder` through the orders module's `SellerOrderManagementPort`. The adapter delegates to seller-scoped `OrdersService` methods, so status transitions, stock commit/release, audit, cancellation/refund events, and delivery lifecycle remain domain-owned. Seller history is capped at 50 and no seller ID is accepted from the caller. The domain guard also blocks direct fulfillment of `PLATFORM_DELIVERY` orders before a delivery leg is attached. | This closes only the seller-command portion of P2, not the end-to-end gate. Require green JDK 25/GraphQL/OpenAPI/security CI, real domain and GraphQL integration coverage, accepted-SDL publication, and a Dashboard Playwright journey for list → detail → confirm/fulfill/cancel. The plan's P3 users/permissions wave remains open. |
| P2 returns/refunds GraphQL lifecycle | The buyer can open/read/cancel a return and the ADMIN queue can approve/reject/receive/complete it through the existing `OrderReturnsService`. Approval freezes the amount; completion uses the existing `OrderRefundPort`/`PaymentsService` with return-scoped idempotency. The GraphQL API exposes bounded page-number lists (maximum 50), buyer-owned detail, and admin-only operational commands; line data is batch-loaded to avoid N+1 queries. | Require DGS binding/security tests and a real PostgreSQL + payment-provider-stub integration that goes GraphQL request → approval → receipt/restock → completion → PARTIALLY_REFUNDED, then proves duplicate completion does not refund twice. Add the Dashboard Playwright workflow only after accepted SDL is published by the schema publisher; do not hand-edit generated Dashboard types. |
| P3 users and permissions parity | Not implemented by the current catalog and commerce slices. The governing Saleor parity plan keeps `me`, `users`, and permission-group reads as a later identity/console-owned wave. | Add only after role-specific field/object authorization, privacy boundaries, and negative access tests are defined; do not infer staff permissions from authentication alone. |
| Dashboard platform lifecycle proof | The adapted Dashboard build passed on the merged source, and the protected shared-production Spring GraphQL Playwright lifecycle passed in run 39351624876 (1 test, 27.8s); the post-merge build passed in run 39354691502. This proves the selected typed catalog journey only, not all Dashboard routes. | Expand the same contract-first E2E discipline to each migrated workbench and keep the broader platform parity gate open. |
| Production measurements | Instrumentation is wired and tested; representative production p50/p95 latency, response-size, query-count and error-rate baselines are not yet a substitute for the passing functional CI. | Measure real representative journeys before claiming production performance acceptance. |

The seven staged implementation phases below now record completed framework adoption separately from the unfinished journey expansion and production acceptance. Do not use the historical baseline table as if it described the current DGS code.

## 3. Target responsibilities and schema philosophy

### 3.1 GraphQL is a journey-aware read/composition graph

The schema should expose stable product concepts, not table names or a one-to-one copy of every REST controller. The eventual graph will need coherent concepts for journeys actually approved by product design, likely including:
- viewer/current-user and public-safe identity representations;
- experience/discovery/home read models with server-side eligibility and ranking;
- community posts, groups, local context and official-source metadata;
- business/institution profiles and separately modelled products/services/offers;
- market listings and their source (individual, business, or another eligible entity);
- property/real-estate offers separated from the underlying property and from resulting transactions;
- availability, booking/order lifecycle and safe transaction summaries;
- reviews/ratings with evidence/verification metadata and moderation state where visible;
- messaging/notifications only where client and authorization needs are defined.

These are candidate domain concepts, not a committed final root-field list. Do not create all of them in one giant schema patch. The schema follows the user's approved product journeys, and fields are added with acceptance tests for the corresponding journey.

Keep SDL schema-first. Split a growing schema into readable schema files under marketplace-app/src/main/resources/graphql/** by bounded topic, while keeping one coherent GraphQL schema. Spring Boot automatically discovers SDL files in that resource tree.

### 3.2 Experience entry points must serve the real product model

The product principle is that each major experience has a stable, complete baseline, then individual personalization is layered on top. A user must not receive only a small fraction of relevant needs buried among irrelevant content.

GraphQL can help fetch the fields needed for a screen and relate a listing to its seller, reviews, locality, and availability, but GraphQL alone does not decide what matters. The backend must provide eligibility/ranking/read projections that reflect the selected experience, locality, access policy, and permitted personalization.

Do not solve this with an unconstrained homeEverything query or a huge selection set. Use explicit, bounded experience/read-model entry points where the server owns eligibility/ranking, then allow clients to select only the fields they render. Add a stable root field only after its screen/journey is specified and its backend projection can be measured.

### 3.3 Separate identity, entity, offer, and transaction

This is a product and API invariant:
- A Person has one private personal account and identity.
- An Entity (business, institution, official body, group, initiative, etc.) has its own profile, delegated management and permissions.
- An Offer/Listing may be a recurring business offer or a one-off individual used-item/property/service offer; the existence of an offer does not imply a permanent business.
- A Transaction (booking/order/rental/sale/service request) has its own parties, terms, state, timeline and responsibilities.
- A Channel/context (market, business directory, community, tourist/expat/local experience) is where the user discovers the offer, not necessarily a distinct copy of the offer.

GraphQL should let journeys move from one context to the relevant offer and transaction without duplicating authoritative records or exposing private owner/delegate data.

### 3.4 Choose query and command shapes deliberately

Queries:
- Use filter and sort enums/inputs, not arbitrary SQL-like client expressions.
- Bound every list. Feeds/search/large listings should use stable cursor/keyset-style connections where supported by the owning query. Page-number pagination can remain appropriate for administrative tables requiring page jumps/counts.
- Return page metadata only where it can be computed economically. Avoid expensive total counts on every feed when the journey does not need them.
- Preserve deterministic order with a stable tie-breaker.
- Avoid N+1 lookups. Use the DGS DataLoader programming model (@DgsDataLoader and request-scoped batching) or pre-composed read projections when nested fields would otherwise perform one database call per item. Do not mix Spring resolver annotations with DGS merely to access batching; verify any underlying Spring GraphQL execution extension against the selected DGS integration version.
- Use request-scoped DataLoader caching; any cross-request cache must include every relevant identity, authorization, locale, experience, and filter dimension.

Mutations:
- Design around business commands/use cases (create/update listing, create booking, cancel booking, submit review) rather than generic database CRUD.
- Use explicit Input and result/payload types. Validate at both schema/binding and domain layers.
- Commands that can be retried must define idempotency and conflict behavior. A GraphQL mutation delegates to the authoritative service that owns transaction boundaries and state transitions.
- Do not implement multi-domain transactions by chaining resolver-side writes. Use an application/domain orchestration use case and existing event/outbox conventions when needed.
- Existing REST writes can remain authoritative. Add a GraphQL mutation only when a client workflow benefits; it must call the same business use case, not duplicate it.

### 3.5 Exact money and lifecycle vocabulary

The target public money contract must distinguish input minor units from display units, and must include an ISO 4217 currency. Avoid floating-point money and avoid silently narrowing domain Long values to GraphQL's built-in 32-bit Int.

Recommended target representation for cross-client exactness:
- an object such as Money { amountMinorUnits: String!, currency: CurrencyCode! }, or a repository-approved custom scalar with exact serialization if code generation and JSON-number precision are proven across all clients;
- mutation inputs must use the same unit and range semantics as the REST/domain contract;
- a future migration should add the exact field, mark the existing price: Float as deprecated, migrate clients, then remove it only through the compatibility process.

Do not change the current schema in place before consumer inventory and compatibility checks. Define public status enums in business terms, not by collapsing every non-ACTIVE persistence status into INACTIVE.

## 4. REST and GraphQL coexistence rules

Use GraphQL where client-selectable composition materially helps screens or nested relationships. Keep REST for:
- third-party webhooks and provider callbacks;
- media upload/complete/delete and large download flows where HTTP signed URLs are appropriate;
- file exports/imports and bulk operations with HTTP streaming semantics;
- external partner integrations that require documented resource endpoints;
- existing operations for which GraphQL provides no demonstrated benefit.

For each business operation there must be one authoritative use case and one set of business rules. REST and GraphQL transports may delegate to the same use case; they must not maintain separate implementations.

Do not migrate all REST routes just because GraphQL exists. Conversely, do not make mobile clients perform many unrelated REST calls if a bounded GraphQL query or explicit experience projection gives a measured improvement.

## 5. Security target

The current endpoint is protected by the stateless Bearer chain. Keep that protection until an explicit public-read policy and field-level enforcement are designed.

Required controls:
1. Authentication policy is explicit for each root and nested field, not inferred from possession of an ID.
2. Mutations enforce required role, ownership/entity relationship, state, and delegation at the service/use-case boundary. A generic authenticated principal is not sufficient authorization.
3. Public profile/read data and private owner/admin data are separated so selecting an adjacent field cannot bypass privacy.
4. Anonymous public discovery must be a deliberate decision. If required by tourist/marketplace journeys, add operation/field-level protection and negative tests before opening the endpoint; do not change the endpoint to permitAll and assume the schema is safe.
5. Keep GraphiQL and introspection off in staging/production. Prefer disabled base defaults and explicit local-dev opt-in. Verify all property keys against exact Boot 4.1.1 metadata.
6. Use authenticated rate limits and anonymous IP limits where anonymous reads are supported. Rate-limit mutations by subject and operation; protect ID enumeration and batched attempts.
7. Apply request body/query length limits, parser/validation budgets, depth and weighted complexity limits, maximum list sizes, and execution timeouts. Verify the APIs available in the exact Boot-BOM-managed GraphQL Java version before choosing instrumentation; do not pin an independent version just for one class.
8. Explicitly decide whether GraphQL subscriptions are required. Because no Subscription type currently exists and the system already has STOMP messaging, disable the unused GraphQL WebSocket transport until a subscription journey is designed.
9. Sensitive personal/health/family/expat/property data must not enter global caches, metrics labels, or logs without proper scope and redaction.

## 6. Pagination, complexity and resilience

The current unbounded services field is the immediate defect to close. Introduce a bounded, paginated field/connection with deterministic ordering and a documented default and maximum page size. Do not leave an unbounded legacy field indefinitely:
- inventory any consumer first;
- if there is no contracted consumer, replace the demo contract with a page/connection directly;
- otherwise add the bounded field, cap/deprecate the old field and remove it after measured migration.

For every collection:
- define a bounded default page size and hard maximum;
- validate filters, sort options, and cursor/page arguments;
- use database-level pagination, not fetch-all-and-slice in memory;
- preserve deterministic ordering;
- test empty, final, invalid, repeated, and high-limit requests;
- include page/has-more metadata appropriate to the query.

Query cost must be bounded in addition to page size. A depth limit alone does not cap a query that requests many aliases/fields or asks for huge lists. Adopt limits appropriate to the exact resolved GraphQL Java version and test pathological nested/aliased operations. Verify whether request-array batching is supported and disable or bound it where it creates an abuse path.

For relationship-heavy types, use batching and measured query-count tests. Prefer a targeted read projection for complex cards over a graph that triggers many repository round-trips.

## 7. Error contract target

Keep the GraphQL-spec response envelope (data plus errors[] when applicable); do not try to force every GraphQL response into an HTTP ProblemDetail body. Keep REST's RFC 9457 contract unchanged.

GraphQL errors must have:
- a stable machine-readable extensions.errorCode;
- a canonical extensions.category from the shared vocabulary;
- safe client-facing messages;
- path and locations supplied by GraphQL where applicable;
- a correlation/trace identifier only under the established policy and after verifying context propagation in synchronous and asynchronous fetchers.

Normalize category/value naming; avoid ad-hoc values such as lower-case authz beside uppercase taxonomy values without an explicit migration. Prefer mapping known domain exceptions to the repository's canonical error taxonomy instead of duplicating the REST taxonomy in string literals.

Do not expose raw internal exception messages, stack traces, SQL, provider secrets, or persistence details. Only expose messages deliberately designated as safe domain errors. Treat unknown failures as generic internal errors.

Test two distinct layers:
- Request-level failures: syntax/parse errors, schema validation, unknown fields, invalid scalar coercion and missing required arguments.
- Data-fetcher/business failures: not-found, validation, conflict, authentication/authorization, and masked internal error.

Spring's DataFetcherExceptionResolver handles data-fetcher exceptions; it does not automatically mean all request parsing/validation failures use the same custom mapping. Test what the exact Spring version returns before custom-normalizing it.

## 8. Observability target

Use the existing Micrometer/OTel setup instead of inventing a parallel telemetry layer. Verify that GraphQlObservationInstrumentation or the exact Spring Boot auto-configuration equivalent is active; Spring for GraphQL's official observability documentation describes graphql.request, graphql.datafetcher, and graphql.dataloader observations.

Measure:
- request outcome and latency by operation name/type;
- expensive root fields/read projections;
- DataLoader batches and query counts;
- validation/limit rejections and authorization denials;
- timeout/cancellation and downstream errors.

Do not use user IDs, raw search text, email, property IDs, or health/family data as metric dimensions. Put sensitive correlation details only in access-controlled traces/logs and avoid logging full GraphQL variables. Ensure trace context survives asynchronous resolver execution.

## 9. Schema lifecycle, compatibility, and documentation

GraphQL is not covered by the existing OpenAPI compatibility gate. Add a GraphQL-specific compatibility control:
- SDL validates at app startup;
- schema diff blocks unreviewed removal, nullability tightening, argument requirements, enum-value removal, and other breaking changes;
- deprecations identify a replacement and a removal condition/date where needed;
- a curated set of named operations representing important client journeys runs against the real schema in CI;
- client code generation, if adopted for Android, consumes the versioned schema artifact produced by CI rather than hand-maintained duplicate models;
- changes to error codes, pagination, money units, auth/publicness, and deprecations are documented in the API contract.

Keep SDL schema-first. Split growing schema files by bounded topic while keeping one coherent schema and operation contract. Avoid multiple GraphQL endpoints or federation now: the backend is a single Spring application/modular monolith, not a network of independently deployed GraphQL subgraphs.

## 10. Test strategy and definition of done

Use three test layers:

1. **DGS data-fetcher tests:** use dgs-starter-test / @EnableDgsTest / DgsQueryExecutor for isolated query, mutation, mapping, input and exception behavior where appropriate.
2. **Real GraphQL transport integration tests:** retain HttpGraphQlTester tests for the real SDL, HTTP endpoint, Spring Security chain, validation, exception envelope and profile settings. These transport-level Spring GraphQL test utilities may remain where they test the integrated runtime; resolver code itself uses only the DGS programming model.
3. **Journey/contract tests:** named client operations execute against the real schema and verify permitted fields, pagination, authorization, stable ordering, and the business result expected by the journey.

The GraphQL work is not production-ready until all applicable gates below pass:
- no unbounded collection resolvers;
- all collection limits and stable ordering tested;
- maximum query cost/depth/size and rate limits verified under the effective dependency version;
- every root, nested field and mutation has an explicit public/authenticated/role/ownership policy with negative tests;
- no JPA entity/repository leaks across module boundaries;
- no N+1 growth for list + nested data (assert repository/query counts or measured batch behavior);
- exact money/unit contracts and nullability documented;
- request-level and resolver-level error contracts tested without internal leakage;
- production/staging GraphiQL/introspection posture verified;
- no unused GraphQL WebSocket transport without a Subscription contract;
- GraphQL schema compatibility gate runs in CI;
- GraphQL request/field/DataLoader observations are measurable;
- REST contracts remain unchanged unless a separate migration is approved;
- Modulith and architecture checks remain green.

## 11. Staged path from current implementation to target

The phase-0 measurement is preserved in `docs/api/graphql-baseline-2026-10.md`. The resolver-model migration and initial typed goods-catalog GraphQL vertical slice are implemented. The plan is **not complete overall**: wider platform journey coverage and production acceptance remain open.

### Phase 0 — Measured baseline (complete; historical snapshot)

1. Consumer/source inventory, initial SDL, security contract and pre-DGS tests are recorded in the baseline document.
2. The resolved pre-DGS chain is historical evidence only; the active DGS dependency graph and its green JDK 25 build are validated by current CI.
3. In-repository consumer search found no Kotlin GraphQL clients and no contracted external consumer in repository source at the time of the baseline. This does not prove there are no out-of-repository consumers.
4. Anonymous `/graphql` remains protected (401). Latency, response-size and database-query baselines remain deferred until representative journeys are measured.

### Phase 1 — Bound and harden the current surface (core guardrails implemented; performance measurement open)

Implemented and exercised: bounded database pagination with deterministic ordering, default/max page settings, query-depth/weighted-complexity instrumentation, rate limits, request/data-fetcher error handling tests, safe GraphiQL/introspection/WebSocket defaults, GraphQL observation integration tests and a GraphQL compatibility gate.

Historical baseline only: the cursor adapter originally wrapped offset-backed pagination and did not provide keyset guarantees. PR #677 replaces that implementation for `storeProducts` and `services` with Spring Data `Window`/`KeysetScrollPosition`, stable sorting and PostgreSQL indexes; acceptance is tied to the latest required CI heads, not this historical statement. Functional/resource guardrails are still distinct from a measured production performance baseline.

### Phase 2 — Netflix DGS as the sole resolver programming model (implemented and guarded)

1. The DGS platform BOM, `dgs-starter` and `dgs-starter-test` are managed through Maven.
2. `ServiceDataFetcher` and `StoreProductDataFetcher` use `@DgsComponent`, `@DgsQuery`, `@DgsMutation` and `@InputArgument`; business authorization and rules remain in their owning use cases/SPIs.
3. `ArchitectureRulesTest` guards against reintroducing Spring annotated-controller resolver APIs, preventing the old mixed-programming-model end state.
4. The current merge/main checks pass startup/build, test, coverage, integration and security/compatibility gates for this DGS implementation. Re-run all required checks for any later changes.

### Phase 3 — Maven-owned DGS CodeGen (implemented; provenance exception documented)

- SDL-first source remains under `marketplace-app/src/main/resources/graphql/**`.
- DGS constants and eligible input/output transport types are generated by the centrally pinned community Maven adapter into isolated build output. The normal Maven lifecycle owns generation; generated source is not manually edited or committed.
- Netflix's official CodeGen plugin is Gradle-only. Retaining a pinned community Maven adapter is an explicit tradeoff to preserve the repo's Maven-owned build, and must not be described as an official Netflix Maven plugin.
- A clean build must continue to prove that SDL changes regenerate types and fail compilation if referenced generated schema symbols drift.

### Phase 4 — Spring platform integration (core integration implemented; broader journey work remains)

- Spring Security and the existing catalog use cases remain authoritative for authentication, ownership and business authorization.
- DGS components remain in the `marketplace-app` composition root and use declared SPIs; domain modules do not depend on application GraphQL code.
- Bean Validation, transactions, Spring Data, query budgets, rate limits and Micrometer observations retain their framework ownership. Tests cover the current surface; every new field or mutation must add its own authorization, error, resource and persistence proof.

### Phase 5 — GraphQL contract lifecycle and Android integration (gates operational; continued schema governance required)

- The backend GraphQL compatibility check and accepted-SDL publication are in CI.
- The Android GraphQL contract gate uses the accepted schema/operation workflow and passed on the merged catalog contract.
- The independent admin UI synchronizes from an immutable SHA of the published `graphql-contract` branch and derives TypeScript `TypedDocumentNode` operations from `.graphql` documents. It must fail closed if the published SDL lacks the required operation set; it must not merge a pending PR's SDL or maintain handwritten schema copies.
- Continue to publish/version the accepted SDL and have clients generate their own operation types against that approved artifact.

### Phase 6 — Expand by complete user journey (in progress; broad platform scope remains)

The current typed catalog lifecycle is one goods-commerce slice. Next additions must follow approved product journeys and owner SPIs/use cases. Candidate areas include community/local discovery, business and institution identity, services/offers, property, booking/payment/order, reviews, messaging, and separate resident/expat/tourist experiences. These remain staged, not claims of existing GraphQL coverage. Do not add one root field per REST endpoint or expose a database-shaped graph.

### Phase 7 — Production acceptance (not complete)

Production readiness still requires:
- all relevant Maven/CI/build/coverage/Enforcer and DGS gates green for the final release head;
- GraphQL compatibility and generated-client contract gates green;
- negative security and field/object authorization tests for each exposed journey;
- paging/resource budgets exercised against actual HTTP execution;
- representative production p50/p95 latency, response size, database-query count and error rate measured;
- the protected Spring-backed Playwright lifecycle for the adapted Dashboard passing against the owner-approved target and correct test credentials/configuration;
- an explicit rollback plan for runtime/dependency/schema/client changes.

No wholesale REST-to-GraphQL rewrite is required.

## 12. Official and community references

The official documents are the authority for API use. The version chosen for implementation must be checked against the actual Maven dependency tree under Boot 4.1.1, not inferred from a framework release tag.

- Netflix DGS Framework — getting started, Maven/Gradle coordinates and DGS BOM/starter: https://netflix.github.io/dgs/
- Netflix DGS — Spring GraphQL integration, programming-model guidance and overlapping property names: https://netflix.github.io/dgs/spring-graphql-integration/
- Netflix DGS — Code Generation capabilities: https://netflix.github.io/dgs/generating-code-from-schema/
- Netflix DGS Framework repository and Boot compatibility matrix: https://github.com/Netflix/dgs-framework
- Netflix DGS CodeGen source repository: https://github.com/Netflix/dgs-codegen
- Community Maven adapter provenance/source: https://github.com/deweyjose/graphqlcodegen
- Spring Boot 4.1.1 — Spring GraphQL integration/auto-configuration: https://docs.spring.io/spring-boot/4.1/reference/web/spring-graphql.html
- Spring for GraphQL — Security: https://docs.spring.io/spring-graphql/reference/security.html
- Spring for GraphQL — Request execution and batching: https://docs.spring.io/spring-graphql/reference/request-execution.html
- Spring for GraphQL — Observability: https://docs.spring.io/spring-graphql/reference/observability.html
- Spring Boot — testing: https://docs.spring.io/spring-boot/4.1/reference/testing/spring-boot-applications.html
- GraphQL Java — query analysis/instrumentation: https://github.com/graphql-java/graphql-java
- GraphQL Inspector — schema change detection: https://github.com/robrichard/graphql-inspector
- OWASP GraphQL Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/GraphQL_Cheat_Sheet.html

Maven build lifecycle reference: https://maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html. The accepted build must run generation from the normal Maven lifecycle; no manually generated code is treated as a source-of-truth artifact.

## 13. Decision record

- **GraphQL programming model:** adopt Netflix DGS Framework; use the DGS programming model consistently.
- **Runtime ownership:** Spring Boot remains the runtime/container and auto-configuration owner. DGS uses its supported Spring for GraphQL integration; do not create a second engine or mix DGS and Spring resolver annotations.
- **Build/code generation ownership:** Maven owns backend DGS dependencies, generation, compile/test/verify and CI prerequisites. The Maven CodeGen adapter is community-maintained and its provenance/risk is explicit; the official vendor CodeGen plugin is Gradle.
- **Dependency policy:** DGS BOM aligns DGS artifacts; the Spring Boot parent/BOM continues to manage Spring artifacts. No independent GraphQL Java/DataLoader pins without a measured and documented exception.
- **Single source of business rules:** data fetchers delegate to domain/application use cases and declared SPIs; no persistence access across Modulith boundaries.
- **Pagination/resource limits:** mandatory before schema growth; the phase-1 guardrails are reusable across the DGS migration but are not evidence of DGS adoption by themselves.
- **REST coexistence:** keep REST where it has better HTTP/integration semantics; shared business operations use one authoritative use case.
- **Anonymous GraphQL reads:** protected contract retained — anonymous /graphql returns 401. Reopen only for an approved public-discovery journey after tested per-field/object authorization.
- **GraphQL WebSocket:** no approved Subscription journey is evidenced; keep it disabled until the use case is accepted.
- **Money schema migration:** exact-money contract required before expanding commerce/property contracts; migrate compatibly.
- **DGS CodeGen output:** generated files stay in target/, never committed or hand-edited.
- **Implementation status:** the Phase-0 baseline is measured; current P0 hardening work is separate from the required DGS programming-model migration. The target architecture is not complete until DGS runtime/programming model, generation, Spring integrations, schema/client compatibility and production acceptance gates all pass.
