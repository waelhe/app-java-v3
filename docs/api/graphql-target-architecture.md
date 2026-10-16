# GraphQL Target Architecture and Evolution Plan

**Status:** Target architecture and staged implementation plan — not implementation-complete  
**Last repository inspection:** 2026-10-15  
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

## 2. Current-state audit (verified from the repository)

### 2.1 Implemented surface

The current SDL is in marketplace-app/src/main/resources/graphql/schema.graphqls and has only:
- Query service(id: ID!): Service
- Query services: [Service!]!
- Mutation createService(input: ServiceInput!): Service!
- Service, ServiceStatus, and ServiceInput types.

The implementation is under marketplace-app/src/main/java/com/marketplace/app/graphql/:
- ServiceGraphQlController
- ServiceInput, ServiceResponse, ServiceMapper
- GraphQlExceptionResolver

It is a useful vertical slice: the controller consumes CatalogSpi and CurrentUserProvider, maps the shared catalog view into a GraphQL response, and does not directly use a JPA repository or entity. Preserve that application-composition boundary.

The write path already has valuable integration coverage. ServiceGraphQlMutationIntegrationTest executes the real SDL and current Spring GraphQL argument-binding/validation path and covers a free listing, positive price, negative price, and missing input. GraphQlErrorIntegrationTest exercises not-found, conflict, and internal-error mapping through the real HTTP GraphQL stack. GraphQlExceptionResolverTest covers exception mapping. These are pre-migration baseline tests to preserve and run unchanged as contract tests while converting the resolver programming model to DGS; they do not mean the final target should retain Spring resolver annotations.

### 2.2 Material gaps

| Finding | Evidence | Consequence / target |
|---|---|---|
| Unbounded collection query | services() calls catalogSpi.findAll(Pageable.unpaged()); SDL returns a list without page arguments or metadata | A growing catalogue can cause unbounded database work, heap use, payloads, and latency. Pagination and a hard maximum are the first functional fix. |
| Schema is only a catalogue demo | Two read fields and one create mutation | It does not yet serve the platform's real journeys (community, discovery/search, businesses, offers, services, properties, bookings, orders, reviews, messaging, institutions, etc.). Add fields only when backed by a defined journey/use case. |
| Money contract is asymmetric | Input uses priceCents: Int! with a signed 32-bit GraphQL scalar and a ceiling; output is price: Float! in major units plus currency | The wire contract invites precision/unit mistakes and does not generalize to every future high-value product/property. Design a precise money contract and migrate compatibly. |
| Status vocabulary can be lossy | ServiceMapper currently maps ACTIVE vs non-ACTIVE to a two-value ServiceStatus | Before exposing more lifecycle states, define the public business vocabulary rather than leaking or collapsing internal persistence states. |
| Error taxonomy can drift | GraphQlExceptionResolver has a separate mapping. The access-denied category is currently authz while the documented category vocabulary uses uppercase categories such as RESOURCE/DOMAIN/VALIDATION/INTERNAL | One stable GraphQL extension contract, aligned with the canonical taxonomy, must cover authorization, validation, domain conflicts, not-found, and internal failures. |
| Request-level errors need distinct tests | DataFetcherExceptionResolverAdapter handles data-fetcher exceptions; parsing/schema validation/coercion can fail before a data fetcher runs | Do not assume the resolver normalizes every GraphQL error. Test and document both request errors and resolver errors. |
| Query/resource limits are not proven | The 2026-09-25 quality audit lists GraphQL depth/complexity limits as unverified; no explicit application GraphQL depth/complexity instrumentation was found in current code search | Verify the exact GraphQL Java version and effective defaults; add explicit tested budgets for this API. |
| Schema compatibility is not covered by the REST OpenAPI gate | The repository has an OpenAPI compatibility gate, but GraphQL has a separate SDL and operation contract | Add a GraphQL-specific compatibility check and a curated executable-operation suite. |
| GraphQL WebSocket is configured without a subscription contract | Base application.yml sets spring.graphql.websocket.path to /graphql/ws, but the current schema has no Subscription root. The application already has a separate STOMP messaging transport | Explicitly disable this transport until a real GraphQL subscription use case is approved, or define and test that contract. Do not keep an unexplained second push protocol. |
| Dev-oriented GraphQL switches are enabled in base configuration | Base application.yml enables GraphiQL and schema introspection; staging and production disable them | Prefer safe defaults in the base profile and explicit opt-in for local development. Verify any additional schema.inspection property against Boot 4.1.1 configuration metadata before treating it as a control. |
| Authentication policy may block public journeys | /graphql is in the stateless Bearer security chain; current CI smoke expects anonymous GraphQL requests to return 401. This may be correct for the present protected contract, but public marketplace/tourism discovery is a product requirement to resolve | Decide explicitly whether anonymous public reads must be supported. Do not loosen endpoint security until every field and mutation has tested field/object authorization. |
| Observability needs explicit proof | No GraphQlObservationInstrumentation bean was found in the searched code | Verify the effective Spring GraphQL/Micrometer instrumentation. Provide bounded-cardinality metrics and trace coverage for requests, expensive fields, and batching. |

### 2.3 Repository boundaries to preserve

- The repository is a Spring Modulith modular monolith. marketplace-app is the composition root; domains own domain state and logic.
- DGS data fetchers/resolvers must not access JPA repositories/entities from other modules. Use the owning module's declared SPI/read projection or an application-level use case.
- No domain module should depend on marketplace-app. Do not add GraphQL dependencies to every domain merely to make SDL modular.
- Keep public API DTOs independent of persistence entities. Add DataLoaders/batch projections when nested fields need cross-module relationships.
- Domain rules, authorization, transaction boundaries, idempotency, state machines, and audit must live in the appropriate application/domain service, not in field resolvers.
- Existing REST endpoints and contracts remain supported unless a separate reviewed migration says otherwise.

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

## 11. Staged path from current partial state to target

The measured baseline is recorded in docs/api/graphql-baseline-2026-10.md. Do not repeat the baseline exercise unless a new code or CI measurement is required. Treat the current Spring-annotation implementation as the migration source, not the final programming model.

### Phase 0 — Measured baseline (complete, bounded to repository evidence)

1. Consumer/source inventory, current SDL, current security contract and tests are recorded in the baseline document.
2. The resolved pre-DGS chain is recorded as Boot 4.1.1 BOM → Spring for GraphQL 2.0.5 → GraphQL Java 25.0 → java-dataloader 6.0.0. This is the baseline only; the post-DGS dependency tree must be measured again.
3. In-repository consumer search found no Kotlin GraphQL clients and no contracted external consumer in repository source. This does not prove there are no out-of-repository consumers.
4. Anonymous /graphql remains protected (401), WebSocket use is not evidenced, and latency/payload/database-query baselines remain deferred until the guarded API is measurable.

### Phase 1 — Bound and harden the current surface (P0; implementation in progress)

The in-flight phase-1 work is intended to close the demonstrated safety gaps before the schema grows: bounded Relay pagination, deterministic ordering, weighted query-cost/depth budgets, request and list bounds, rate limits, the canonical GraphQL error contract, safe GraphiQL/introspection/WebSocket configuration, observation verification, and a schema-compatibility gate.

The open phase-1 implementation currently starts from the existing Spring resolver surface. Its guardrails and executable tests are reusable requirements, but phase 1 is not proof of DGS adoption. Preserve its observable contracts and tests; the next phase must migrate the data-fetcher programming model to DGS and re-run the entire phase-1 suite before any new journey fields are added. Do not leave mixed @QueryMapping/@MutationMapping and @DgsQuery/@DgsMutation styles as the end state.

Acceptance:
- every collection has a bounded default and hard maximum and uses database-level pagination;
- query depth/weighted complexity limits and rate limits are actually exercised by HTTP tests;
- request-level parse/coercion failures and data-fetcher/domain failures have separately tested contracts;
- GraphiQL/introspection defaults are safe, and the unused GraphQL WebSocket path is disabled;
- the SDL compatibility gate blocks unreviewed breaking changes and maintains a documented, expiring exception only when needed;
- Spring Security policy remains unchanged (anonymous request → 401);
- observation/metrics are measured rather than inferred from dependencies being present.

### Phase 2 — Adopt the DGS Framework as the sole GraphQL programming model

1. Add the DGS platform BOM, dgs-starter, and dgs-starter-test through Maven dependency management. Keep the Spring Boot 4.1.1 parent and let Boot manage Spring artifacts; let the DGS BOM align DGS artifacts. Do not independently pin GraphQL Java or java-dataloader without a measured and documented compatibility/security need.
2. Verify the exact candidate DGS release against Java 25, Spring Boot 4.1.1, Spring for GraphQL, GraphQL Java and Maven Enforcer. DGS 11+ is the documented Spring Boot 4 line; the upstream release page showed 12.1.0 on 2026-10-15. The exact selected version is accepted only after the effective Maven dependency tree and full CI prove convergence and runtime startup. If that candidate fails, select a compatible DGS 11+ release; do not patch around conflicts with unexplained overrides.
3. Use the DGS Spring GraphQL integration and the DGS-specific property names for overlapping settings (notably dgs.graphql.schema-locations and dgs.graphql.graphiql.enabled) as directed by the official integration guide. Retain Spring Boot configuration properties/profiles for validated, environment-specific budgets. Do not create a second GraphQL engine, endpoint, schema source, or manual GraphQLSchema lifecycle.
4. Migrate the existing service, services, and createService data fetchers to @DgsComponent, @DgsQuery, @DgsMutation and @InputArgument. Keep the current use-case/SPI calls, auth behavior, SDL contracts (except already documented Phase-1 schema changes), and exception semantics.
5. Keep DGS data fetchers only in marketplace-app. They call CatalogSpi/other declared SPIs or application use cases; never import repositories or entities from other domain modules.
6. Migrate/extend data-fetcher unit tests using DgsQueryExecutor and the DGS test starter; retain real HTTP tests using HttpGraphQlTester where they verify the integrated Spring Security/transport contract. Remove the old Spring resolver annotations only when the equivalent DGS implementation is covered.
7. Add a test/build guard that the final GraphQL adapter uses one resolver programming model. A temporary mixed state is allowed only inside a migration PR and must not be the accepted end state.

Acceptance:
- clean application startup on the exact Boot/JDK baseline;
- no duplicate GraphQL engine or duplicated starter configuration;
- dependency convergence, security, Modulith, CI, integration and coverage gates stay green;
- the P0 contracts and schema compatibility gate behave identically across the migration.

### Phase 3 — Maven-owned DGS code generation

1. Keep SDL schema-first as the canonical server contract under the single configured DGS schema location. Split files by bounded topic only when size warrants it; DGS loads them as one schema.
2. Use the Maven DGS CodeGen adapter pinned centrally and bound to generate-sources. Its output is isolated under marketplace-app/target/generated-sources/graphql-codegen, separate from MapStruct’s target/generated-sources/annotations. Never commit or hand-edit generated files.
3. Generate DGS constants and GraphQL transport/input/output types that can be generated without changing public semantics. Disable backend client API generation unless there is a concrete server-to-server client use case; do not generate placeholder data fetcher implementations.
4. Use generated GraphQL transport types as the contract at the adapter boundary. Domain entities, shared SPI views and application commands remain their owners’ types. MapStruct may generate mappings where the mappings are mechanical. Value constraints not expressible in SDL must be enforced once in the relevant validator/use case; do not keep a second DTO solely to duplicate rules unless generated-code validation equivalence cannot be proven.
5. Since Netflix officially publishes the CodeGen Gradle plugin and the Maven adapter is community-maintained, track that provenance explicitly, pin its plugin version in root Maven properties, inspect its dependency graph, isolate its output, and enforce a clean-build CI test. It is a build plugin only; it does not add DGS runtime dependencies by itself.
6. Make the normal Maven clean verify of marketplace-app and its required modules perform generation, compile, test and verify from a clean tree. No separate manual generation command and no generated source checked into Git.

Acceptance:
- deleting target and running the normal Maven build recreates all required code;
- generated source does not collide with MapStruct or enter the source history;
- SDL changes update generated symbols and fail compilation/tests if the adapter references removed or renamed fields;
- custom validation, money and status semantics remain covered by tests.

### Phase 4 — Integrate the full Spring platform around DGS

- **Spring Security:** retain the existing stateless Bearer chain; enforce ownership, delegation and field/object authorization in the owning use-case/service boundary; test 401/403 and private-field access. Keep anonymous GraphQL protected until a public-discovery journey explicitly reopens the decision.
- **Spring Modulith:** run ApplicationModules.verify() in the architecture gate; keep DGS components in the composition root; use named interfaces and existing SPIs/events only. No DGS dependency or resolver code in domain modules.
- **Bean Validation:** validate generated/input DTOs at the boundary where supported and test every constraint; enforce domain invariants again in the authoritative use case. Never rely on SDL non-nullability alone for business validation.
- **Spring Data/JPA and projections:** enforce bounded database pagination and stable ordering; do not fetch-all-and-slice; use focused read projections for screen composition where that is better than traversing a graph of entities.
- **DGS DataLoaders:** use request-scoped DGS DataLoader batching for measured relationship fan-out. Add query-count tests; prefer a purpose-built projection if batching would obscure or multiply expensive queries.
- **Transactions and Modulith events:** mutations call the owning use case, whose transaction/state machine remains authoritative. Follow existing event-publication/outbox conventions for side effects; GraphQL must not introduce resolver-side transaction chains.
- **Micrometer/Actuator:** verify effective request/data-fetcher/DataLoader observations under the chosen DGS Spring GraphQL integration. Do not register instrumentation that replaces Boot’s observation infrastructure; compose custom depth/complexity instrumentation and verify no observation regression.
- **Configuration/profiles:** bind page sizes, cost limits, rate limits and error options to validated @ConfigurationProperties; invalid production values fail startup. Use the DGS property namespace where the official integration says it overlaps with spring.graphql.*, and test the actual base/dev/test/staging/prod files.

Acceptance:
- security tests, Modulith and architecture gates, real HTTP tests, resource-limit tests, transaction/event tests and observation tests pass;
- each framework feature has one owner and one configured path—no parallel cache, auth, telemetry, transaction or GraphQL runtime layer.

### Phase 5 — GraphQL contract lifecycle and Android integration

1. Keep the GraphQL compatibility gate in CI: compare the runtime schema actually booted by the app against the approved tagged baseline; block unreviewed field/type removals, nullability tightening, required-argument changes and enum-value removals.
2. Keep allowlisted breaking changes exceptional and expiring; after a release baseline advances, remove stale exceptions.
3. Publish the accepted SDL as a versioned CI artifact. Do not fetch schema from the production server during Android builds.
4. Have the Android Gradle build consume that artifact and use Apollo Kotlin CodeGen for operation-specific Kotlin types. Operation documents are authored from approved screens/journeys; models and operation result types are generated, not manually duplicated.
5. Validate the DGS-generated server types and Apollo-generated client types against the same schema publication, while preserving independently managed Maven and Gradle lifecycles.

Acceptance:
- no hand-copied SDL between repositories;
- an incompatible server schema change or stale Android operation fails a required build/check before release;
- the schema tag and client-generated types are reproducible from CI artifacts.

### Phase 6 — Expand by complete user journey

For each journey, define screen/data requirements first, then identify the owning SPIs/use cases, add bounded read projections and only the mutations that add measurable benefit, and cover authorization, error recovery, pagination, query cost and end-to-end client behavior.

Candidate journeys remain public-safe discovery (only after the public-read gate is reopened), local community, business/service discovery, marketplace offers, provider requests, bookings/payments/disputes, property journeys, and expat/tourist/local entry points. Do not introduce federation or split GraphQL into multiple endpoints: this is one Spring application and a modular monolith.

### Phase 7 — Production acceptance

Production readiness requires all of:
- full Maven/CI/build/coverage/Enforcer and DGS test gates green;
- GraphQL schema compatibility gate green;
- negative security and per-field/object authorization tests;
- resource budgets and pagination verified over HTTP;
- measured p50/p95 latency, response size, database query count and error rate for representative journeys;
- DGS/DataLoader and request observations verified with bounded metric cardinality;
- Android operations compiled against the versioned accepted SDL;
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
