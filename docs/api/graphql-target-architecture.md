# GraphQL Target Architecture and Evolution Plan

**Status:** Design proposal — not implementation-complete  
**Last repository inspection:** 2026-10-15  
**Repository baseline:** waelhe/app-java-v3, Spring Boot 4.1.1, Java 25, Spring Modulith 2.1.1, modular monolith  
**Scope:** GraphQL API in marketplace-app, its security/configuration/tests, and the module boundaries it must preserve.

> This is a target design and staged acceptance plan. It does not authorize a wholesale REST migration, an all-at-once schema expansion, or a change to anonymous/public access policy without the required product and security decisions. Implementation steps must remain incremental and reviewable.

## 1. Executive decision

Retain Spring for GraphQL as the **experience-composition API** for clients that benefit from selecting and combining fields across the marketplace. Do not make GraphQL a mirror of every REST endpoint, a public view of the database, or the home for domain business logic.

The target is a schema that lets a client request the data it needs for a well-defined user journey while the server remains responsible for:
- choosing eligible and relevant content for the selected experience;
- enforcing identity, ownership, delegation, privacy, and business rules;
- returning bounded, stable, paginated collections;
- orchestrating existing application use cases through declared SPIs and read projections;
- preserving transactional correctness for bookings, orders, payments, refunds, disputes, property management, and other stateful workflows.

REST remains supported. Keep REST when HTTP semantics, uploads/downloads, webhooks, partner integrations, redirects, or existing contracts make it the better fit. When both transports expose a business operation, they must call the same application/domain use case; neither transport may implement a second copy of the business rules.

**Success is measured by completed user journeys and proven API qualities, not by replacing all REST routes or maximizing the number of GraphQL fields.**

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

The write path already has valuable integration coverage. ServiceGraphQlMutationIntegrationTest executes the real SDL and Spring GraphQL argument binder/validation path and covers a free listing, positive price, negative price, and missing input. GraphQlErrorIntegrationTest exercises not-found, conflict, and internal-error mapping through the real HTTP GraphQL stack. GraphQlExceptionResolverTest covers resolver mapping. These are foundations to extend, not discard.

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
- GraphQL controllers/resolvers must not access JPA repositories/entities from other modules. Use the owning module's declared SPI/read projection or an application-level use case.
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
- Avoid N+1 lookups. Use Spring GraphQL BatchLoaderRegistry / @BatchMapping or pre-composed read projections when nested fields would otherwise perform one database call per item.
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

1. **Fast resolver/slice tests:** unit tests for mapping and authorization decisions; @GraphQlTest / GraphQlTester for SDL-to-controller wiring where appropriate.
2. **Real GraphQL transport integration tests:** existing HttpGraphQlTester tests for actual schema, input binding, validation, security chain, exception envelope, and production/staging settings.
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

### Phase 0 — Baseline and decisions
1. Inventory GraphQL consumers and operations (backend, Android roadmap, any deployed clients) before any breaking change.
2. Resolve the anonymous-read policy for public catalogue/business/tourism discovery. Current CI expects anonymous /graphql to return 401; retain until the policy and field rules are ready.
3. Confirm exact Spring GraphQL and GraphQL Java versions from the Boot 4.1.1 BOM/dependency tree.
4. Measure whether GraphQL WebSocket subscriptions are used; no Subscription schema was found.
5. Establish a baseline for request latency, response size and DB query count for existing operations.

### Phase 1 — Secure and bounded foundation (P0)
1. Replace or safely migrate the unbounded services field to a paginated bounded contract.
2. Add verified query-depth/weighted-complexity, request-size and list-size limits; rate-limit the surface.
3. Align error-code/category handling and test parse/coercion versus resolver failures.
4. Review mutation role/ownership authorization; preserve the current authentication boundary until public-read policy is decided.
5. Set safe default GraphiQL/introspection configuration and disable the unused GraphQL WebSocket endpoint unless a subscription case is approved.
6. Verify and enable GraphQL observations/tracing.
7. Add schema compatibility checking in CI.

### Phase 2 — Stable shared schema foundations
1. Define exact Money, date/time, pagination, error and enum contracts.
2. Add compatible exact money fields; deprecate old floating-point fields only after consumer inventory.
3. Standardize common safe references (person/entity/offer) without exposing internal database identifiers as authorization.
4. Split SDL into readable schema slices and add schema/operation documentation.
5. Add DataLoaders/read projections as actual nested relations are introduced.

### Phase 3 — Build by complete user journey, not by module count
For each journey:
1. define the journey, screens and required data before root fields are designed;
2. identify which owning domain SPI/use case supplies each datum/action;
3. build a bounded query/read projection for the relevant screen where beneficial;
4. add command mutations only where the workflow benefits and delegate to authoritative use cases;
5. test authorization, pagination, performance, error recovery and the full result.

Candidate journey families include public discovery/search, local community, business discovery, market offer purchase, provider/service request, booking/payment/dispute, property management/rental/sale, and expat/tourist/local experience entry points. Do not implement all these as a single massive schema change.

### Phase 4 — Performance and client contract maturity
1. Optimize only measured hot queries; use DataLoader batching and purpose-built projections.
2. Establish representative p50/p95 latency, rows/queries per request, payload-size and error-rate baselines.
3. Introduce persisted/allowlisted operations for known mobile-client operations if operationally useful; do not treat persisted queries as a substitute for authorization and complexity limits.
4. Generate and publish the schema for the Android consumer and verify deprecation/migration contracts.

### Phase 5 — Production review
Perform an end-to-end review of important GraphQL journeys, schema compatibility report, auth-negative suite, resource-limit tests, telemetry and rollback plan. No broad REST-to-GraphQL rewrite is required to reach this target.

## 12. Official and community references

Version-specific references checked for this proposal:
- Spring Boot 4.1.1 — Spring for GraphQL: https://docs.spring.io/spring-boot/4.1/reference/web/spring-graphql.html
- Spring Boot — auto-configured Spring GraphQL tests: https://docs.spring.io/spring-boot/4.1/reference/testing/spring-boot-applications.html
- Spring for GraphQL — Request Execution, pagination and batching: https://docs.spring.io/spring-graphql/reference/request-execution.html
- Spring for GraphQL — Security: https://docs.spring.io/spring-graphql/reference/security.html
- Spring for GraphQL — Observability: https://docs.spring.io/spring-graphql/reference/observability.html
- Spring for GraphQL — Testing / GraphQlTester: https://docs.spring.io/spring-graphql/reference/testing.html
- Official Spring example repository: https://github.com/spring-projects/spring-graphql-examples
- GraphQL Java implementation and instrumentation: https://github.com/graphql-java/graphql-java
- OWASP GraphQL Cheat Sheet (depth/amount limits, authorization, batching, safe configuration): https://cheatsheetseries.owasp.org/cheatsheets/GraphQL_Cheat_Sheet.html

Do not copy API examples from a newer GraphQL Java/Spring GraphQL release into this application without checking the version resolved by Spring Boot 4.1.1. Dependency versions remain BOM-managed unless a measured compatibility/security reason justifies a documented exception.

## 13. Decision record

- **Keep Spring for GraphQL:** yes.
- **Purpose:** experience-aware data composition plus selected use-case mutations; not a database graph or REST replacement exercise.
- **Single source of business rules:** yes; resolvers delegate to application/domain use cases and declared SPIs.
- **Pagination/resource limits:** mandatory before the surface is considered complete.
- **REST coexistence:** yes; move operations only when there is demonstrated journey/client benefit.
- **Anonymous GraphQL reads:** unresolved; current endpoint remains authenticated until access policy is explicitly decided and field security is proven.
- **GraphQL WebSocket:** unresolved/likely unnecessary with the current SDL; disable unless a real subscription journey is accepted.
- **Money schema migration:** required before expanding commerce/property contracts; must be backward-compatible.
- **Implementation status:** this document is a target plan. No production code, schema, dependency, or configuration is changed by the plan itself.
