# Community Platform — Product Scope and Execution Plan

**Status:** Proposed execution baseline; use with the current repository, not as proof that a feature is already implemented.  
**Repository:** [waelhe/app-java-v3](https://github.com/waelhe/app-java-v3)  
**Baseline snapshot:** main at 410326c88bf7af54610686502298379f6ebd65d4 (2026-10-09 17:48:05 UTC). Re-read GitHub before every implementation phase; this snapshot and the pull-request states below can become stale.  
**Audience:** Backend developers, coding agents, reviewers, and the product owner.  
**Product name:** Not selected. Do not name the product “Dayf/ضيف” or invent another brand.  
**Technical authority:** [docs/official-compliance-plan.md](official-compliance-plan.md). This document owns product scope, dependency order, evidence gates and end-to-end acceptance. It does not override official framework documentation, actual current code, the owner’s product decisions, or AGENTS.md.

---

## 0. Execution contract

This plan is an execution map for a trusted, geographically scoped community platform inspired by public product patterns from Nextdoor, extended with a multi-domain marketplace, a business directory, institutions, local knowledge, configurable platform surfaces and cross-cutting services.

The platform is not yet named. Nextdoor is a product reference, not a code dependency; do not assume that all of its features are available in every country, and do not copy proprietary implementation details.

### 0.1 Authority and evidence order

Every implementation agent must use this order:

1. **Owner-defined product requirements** in this plan and any more recent explicit owner decision.
2. **Current repository facts** measured from the actual main head, POMs, source, migrations, tests, OpenAPI and current CI status.
3. **Official documentation and source repositories matching the versions actually managed by this repository.** Confirm version support before selecting APIs or dependencies.
4. **Established community-maintained libraries or operational practices**, only where no suitable official framework feature exists. Confirm maintenance status, compatibility, license, security posture, transitive dependencies and real integration tests before adopting them.
5. **Public Nextdoor product and transparency material** only as product research. Treat it as evidence of published product behavior, not a technical mandate or proof of universal availability.
6. **Unverified assumptions** are not implementation instructions. Record them as a decision required, design an experiment, or stop dependent work.

Internal plans may provide historical context but do not outrank verified code or official technical references. If code, an old document and a current official source disagree, record the contradiction and resolve it before relying on the affected behavior.

### 0.2 Non-negotiable implementation rules

- Read AGENTS.md, SYSTEM.md and PROJECT_MAP.md in the repository’s mandatory order. Then read this execution plan, the relevant sections of the technical authority, and CODING_STANDARDS.md.
- Research the exact installed Spring Boot, Spring Modulith, Spring Security, Spring Data, Spring AI, PostgreSQL and Flyway behavior before changing the affected layer. Do not infer support from a newer version’s documentation.
- Inspect the real source and tests before acting on a reported gap. A directory, entity, controller, migration or green test on an old commit does not prove a capability is composed into the runnable application.
- Keep the modular monolith unless measured evidence and an owner-approved architecture decision justify a different deployment boundary. Do not create a microservice because a domain has a separate screen or table.
- Domain modules own their data and business rules. Cross-module integration uses existing public module interfaces/SPI contracts and Spring Modulith events where appropriate, not access to another module’s internal repositories or entities.
- PostgreSQL/Flyway remain authoritative for durable platform data and schema. Search indexes, feed projections, caches and derived interest profiles are rebuildable projections, never alternate sources of truth.
- Never edit a migration that may already have been applied. Add uniquely numbered versioned migrations only after reconciling all work in the merge queue and reading the current migration inventory. Never enable Flyway out-of-order as a shortcut for migration collisions.
- Keep domain entities audited according to repository convention. Preserve ownership, authorization, soft-delete, deterministic ordering, idempotency, API compatibility, cache invalidation and current error-contract behavior.
- Use framework auto-configuration, starters, BOM-managed dependency versions and public APIs where the official framework provides them. A manual workaround requires documented proof that the supported official path cannot satisfy the requirement.
- No hidden placeholders, untracked TODOs, invented defaults, ignored test failures, or claims of completeness without a recorded acceptance journey.
- Do not merge to main without the owner’s explicit merge instruction. This repository’s merge-to-production path makes that a production-safety rule, not a formality.

### 0.3 What a completed requirement means

A short phrase such as “publish a post”, “recommend a business”, “send a notification” or “update the app” means a complete user journey, not just a controller and entity. The journey must include applicable authentication and authorization, validation, persistence, transaction integrity, deterministic reading and pagination, owner-scoped mutations, events and downstream effects, notifications and preferences, auditability, Arabic behavior, API documentation, tests, failure paths and client-facing contract details.

Each phase must end with evidence that can be checked on the exact commit, including any dependent merge, rather than a progress report.

---

## 1. Product definition

### 1.1 Product identity

Build a country/city/neighborhood community platform for residents, local groups, business and service providers, organizations and public institutions. It should enable trusted local participation and discovery, not merely aggregate unrelated listings.

### 1.2 Community

The community domain includes:

- Feeds: **New/Latest**, **Popular/Trending**, **Following** and **For You**. Final names may be localized, but the semantics must remain distinct.
- Post purposes: question, request, recommendation, poll, lost-and-found, general discussion and classified/community-market post where appropriate.
- Comments, reactions, replies where supported, shares, report/appeal paths, save/bookmark and clear author/visibility states.
- Local news and local information with source/provenance represented honestly.
- Official institutional announcements and time-critical alerts. “Broadcast” means authoritative messages to relevant areas, not video livestreaming.
- Neighborhood activities, initiatives and events.
- Groups and group memberships.
- A “Get to Know” area: a community-contributed, moderated neighborhood guide to places, services, local history, useful knowledge and opt-in resident/community information.
- Member-to-member contact and direct conversations, with anti-abuse and privacy protections.
- Paid lending/borrowing of items, with an explicit transaction lifecycle rather than a special price field on an ordinary post.
- Transparent membership verification and optional recommendation/referral signals. A referral is not proof of identity, residence, professional qualification or official status.

### 1.3 Marketplace

The marketplace must support discovery and fit-for-purpose detail models for:

- New products and goods offered by stores/providers.
- Used goods and local neighborhood-market items.
- Real estate.
- Cars and vehicles.
- Professional and scientific services.
- Jobs and employment applications.
- Where the agreed commerce model requires it: seller catalogs, stock/availability, offers, cart, checkout, payment, order status, delivery/collection, fulfillment tracking, cancellations, returns, complaints and disputes.

A category chip is not a complete domain implementation. A vehicle needs its relevant fields and filters; a job needs a hiring/application lifecycle; a product sale needs ownership, pricing, stock and order semantics; a service listing must not accidentally become a bookable service just because it has a price.

**Commerce decision inherited from prior product material:** an earlier owner-provided marketplace concept described local stores, a store cart and a neighborhood-unified cart, order tracking/direct delivery coordination, and “without commissions.” Before implementing any fee, seller commission, checkout or courier workflow, re-check the latest owner decision and the actual product artifact. Do not silently invent a fee model or treat an old UI mockup as a substitute for a current decision.

### 1.4 Business directory

Each business/professional profile should have a clear identity and ownership lifecycle, public details, geographic service area, hours and contact methods, products/services, reviews and ratings, relevant verification state, and links to the source products/services it offers.

Keep these concepts distinct:

- A review is a member’s experience and must follow the review eligibility/policy.
- A star rating is an aggregate of identified review records.
- A recommendation/Fave is an explicit member endorsement with source and provenance.
- Verification establishes a specific fact (for example, ownership of a profile), not general quality.
- A paid placement or advertisement is commercial content and must be labeled as such.

Business-owned promotion must not be disguised as an organic member recommendation. A business must not buy verification, votes, reviews or a recommendation score.

### 1.5 Cross-cutting platform capabilities

The backend must provide the contracts and state needed for:

- Country/city/neighborhood discovery and scope-sensitive visibility.
- A single account that may participate in multiple roles and act for multiple businesses or institutions, with permissions scoped to the relevant resource.
- Verification, recommendations, trust, abuse prevention, reporting, moderation, appeals and audit.
- Search by content/domain/category/geography and saved-search alerts.
- Notification preferences per section/type/topic/channel and relevant geographic scope.
- Contact and messaging.
- An advanced operator console that can configure existing supported layouts, sections, categories, geographic feature availability, notification policy and approved business rules without a software release for every operational change.
- Release metadata and mobile-client update policy, distinguished from backend deployment and API versioning.
- API documentation and compatibility for mobile clients and any future web frontend.
- Observability, safe deployment, recovery, data export/deletion where applicable, and privacy controls.

---

## 2. Measured repository baseline

This section records the snapshot used to create the plan; it is not a permanent statement of the latest repository state.

### 2.1 Baseline

- Repository: [waelhe/app-java-v3](https://github.com/waelhe/app-java-v3).
- main observed at commit **410326c88bf7af54610686502298379f6ebd65d4**, committed 2026-10-09 17:48:05 UTC.
- Java 25, Spring Boot 4.1.1, Spring Modulith 2.1.1, Maven reactor, PostgreSQL with Flyway, Redis, REST and GraphQL, and a single runnable app assembly.
- The root POM currently declares **25 Maven reactor modules**. The repository also contains the marketplace-institutions source directory, but it is **not declared in the root reactor nor in the runnable app dependencies** at this snapshot.
- The latest application migration filename observed is **V159**. The repository uses ddl-auto: none, and Flyway validates applied migrations. Check the complete app and edge migration locations before choosing any new number.
- Some root docs contain earlier module/migration inventories. Those counts are historical until reconciled against the actual current tree and guards.

Verify each item again from the exact main SHA before using it to plan a code change.

### 2.2 Existing code relevant to the vision

| Area | Evidence visible on the observed main | What it proves—and what it does not |
|---|---|---|
| Community posts/feed | marketplace-community: post, comment, reaction, membership and feed controller/service | A real neighborhood-scoped chronological post feed exists. This alone does not establish unified multi-source or personalized feeds. |
| Membership and trust | NeighborhoodMembershipService, membership verification states, provider verification, reviewer badges/trust score | Several verification and trust lifecycles exist. They are not one generic “verified” flag; the fact each state represents must remain explicit. |
| Groups/events/polls | Community group, event, RSVP, poll and vote models/controllers/services | These domains exist. Their composition into the primary feed and their full product policies must be independently tested. |
| Neighborhood market | NeighborhoodMarketItem and neighborhood market controller/service | A local used/free-item board exists. It is not equivalent to an inventory-backed multi-seller catalog or order system. |
| Catalog | marketplace-catalog, ProviderListing, category registry, favorites, ranking and ad-billing classes | A provider listing/catalog and commercial ranking/advertising surface exists. Do not assume a general product/SKU/stock/cart/order model exists. |
| Business/provider | marketplace-provider: provider profile, offered services, hours, service areas and public page; marketplace-reviews: review/rating logic | A business/service directory foundation exists. Recommendation provenance and product reuse across domains need a deliberate contract. |
| Real estate and jobs | marketplace-realestate and marketplace-jobs | These are domain modules, not proof of a unified marketplace experience or common discovery result. |
| Geo | marketplace-geo and public lookup ports | A hierarchical location model exists. Reuse it; do not introduce an independent city/neighborhood hierarchy in each domain. |
| Search | marketplace-search: catalog-oriented search orchestration, PostgreSQL text search and typo-tolerant paths, geo/business/property criteria and saved searches | A strong listing-search foundation exists. It is not yet a common search contract across community posts, guide entries, institutions, events, jobs, products and businesses. |
| Knowledge guide | marketplace-knowledge: community-built guide, categories, text search, publication/withdrawal events | A “Get to Know” foundation exists. Integrate it through public contracts/events instead of copying entries into other modules. |
| Messaging | marketplace-messaging: booking conversations and a direct-conversation path, message idempotency and WebSocket surface | Direct and booking chat paths exist. Expand policy and notification behavior through the existing module; do not create a duplicate chat subsystem. |
| Notifications | marketplace-notifications: database/email/WebSocket channels, typed events and preferences | In-app, email and WebSocket flows exist. Mobile push and finer section/topic/geographic routing are not established by the channel enum alone. |
| Console and settings | marketplace-console feature flags/remote config/geographic feature settings/audit, plus existing system settings | Operators can change supported runtime settings. This is not yet proof of a safe configurable layout registry or application release-management API. |
| AI | marketplace-ai with Spring AI integration, query understanding, search tools and knowledge integration | A supported AI foundation exists. AI still must share search authorization and must not become a parallel data-access path. |
| Institutions | marketplace-institutions directory, institution controllers/services and V154; community membership schema changes also exist | Source files and migrations exist, but the missing Maven/app wiring means the module is not proven to be part of the runnable composition on this snapshot. |

Relevant code entry points: [root POM](https://github.com/waelhe/app-java-v3/blob/main/pom.xml), [app POM](https://github.com/waelhe/app-java-v3/blob/main/marketplace-app/pom.xml), [community post controller](https://github.com/waelhe/app-java-v3/blob/main/marketplace-community/src/main/java/com/marketplace/community/NeighborhoodPostController.java), [search service](https://github.com/waelhe/app-java-v3/blob/main/marketplace-search/src/main/java/com/marketplace/search/SearchService.java), [catalog entity](https://github.com/waelhe/app-java-v3/blob/main/marketplace-catalog/src/main/java/com/marketplace/catalog/ProviderListing.java), [provider page service](https://github.com/waelhe/app-java-v3/blob/main/marketplace-provider/src/main/java/com/marketplace/provider/ProviderBusinessPageService.java), [notification types](https://github.com/waelhe/app-java-v3/blob/main/marketplace-notifications/src/main/java/com/marketplace/notifications/NotificationType.java), [console controller](https://github.com/waelhe/app-java-v3/blob/main/marketplace-console/src/main/java/com/marketplace/console/ConsoleAdminController.java), and [institution service](https://github.com/waelhe/app-java-v3/blob/main/marketplace-institutions/src/main/java/com/marketplace/institutions/InstitutionService.java).

### 2.3 In-flight work to reconcile first

This is also a snapshot, not a promise about current status:

- **PR #511** is an open, broad security/architecture workstream based on the observed main. Its build and full-integration checks were still in progress in the check-run snapshot obtained during this review.
- **PR #524** (“Unify AI marketplace search with canonical search orchestration”) was open/draft on an older base (d61a168…). It proposes routing AI search through the canonical SearchService path and its shared filters.
- **PR #525** (“Wire institutions module into Maven reactor and app assembly”) was open/draft on the older base (d61a168…). Its old-base checks are not proof that it composes safely with later migrations and source changes.
- **PR #529** (“Add PostgreSQL Arabic search and explicit question/request post types”) was open/draft on the older base (d61a168…). It proposes the missing QUESTION/REQUEST post purposes and a neighborhood Arabic search path, and explicitly records migration-history conflicts requiring reconciliation.
- **PR #526** added a CI-gated Railway deployment workflow and is closed/merged at the observed main head. Still re-check the exact current main check-runs and deployment result; a merged deploy workflow does not make every later main commit green.

Before phase work, query the live PRs, their latest heads and current main. Rebase/split/retire proposals as appropriate. Do not merge old-base PRs just because an earlier CodeRabbit/Snyk/build check was green. The exact commit under test is the only meaningful CI state.

---

## 3. Confirmed gaps and product decisions

“Gap” below means “not established as complete by the reviewed current code,” not necessarily “no related code exists anywhere.” Re-inspect before implementing.

| Priority | Gap or decision | Why it matters | Resolution gate |
|---|---|---|---|
| P0 | Institution source directory is not in root Maven reactor/app composition | Source and V154 can remain a dead module at runtime | Wire module and dependency management; full reactor/app/Modulith tests and real endpoint verification. |
| P0 | Pending PRs overlap in root build files and migration history | A superficially green old base may fail current build or Flyway | Reconcile against latest main and verify fresh database plus upgrade path. |
| P0 | Current User uses a single UserRole (CONSUMER, PROVIDER, ADMIN) | One person may need multiple roles across community, stores, professional services, employment and institutions | Approve a migration-safe role/actor/permission model before expanding role-specific endpoints. |
| P1 | Community post purposes QUESTION and REQUEST are absent from the observed PostCategory vocabulary | These are explicit product requirements | Merge/rework #529 after checking migration order and API vocabulary. Keep purpose separate from domain/category. |
| P1 | No dedicated unified feed module/contract was found in the root reactor | The current community feed cannot be presumed to aggregate all source domains or provide Following/Trending/For You | Fit-gap the existing community/search/read ports; then create a feed module only if it adds a distinct orchestration responsibility. |
| P1 | No verified end-to-end feed impression/position/algorithm-version record was established | API delivery is not proof that an item was shown; learning signals would otherwise be biased | Define exposure collection with client visibility semantics, idempotency, retention and privacy. |
| P1 | Institution announcements and urgent official alert workflows are not established as a complete source-to-feed/notification journey | Official messages need provenance, authority, area, expiry, correction and revocation | Wire institutions, define separate ordinary-announcement and urgent-alert state machines and test the entire journey. |
| P1 | Search is split by source domain; the AI tool has had a direct catalog path | Results and visibility rules can diverge between REST, AI and content areas | Finish one canonical search orchestration, then add typed domain-specific public search adapters. |
| P1 | General product/inventory/cart/order semantics are not established by the existing provider listings and neighborhood market item model | A used-item board is not a multi-seller commerce engine | Model product/store/stock/order ownership and reuse the catalog where suitable; do not duplicate listing data. |
| P1 | Console supports flags/configuration but is not proof of dynamic surface composition and release management | “Change from the console, not code” must mean safe configuration of existing capabilities | Add validated layout/configuration records and a release API contract; new executable features still require code and release. |
| P2 | Notification preferences are type/channel based today; topic/subsection/geographic routing and mobile push are not proven complete | The requested notification system is significantly more granular | Extend routing policy from a canonical event catalogue and choose any external push provider via a documented gate. |
| P2 | Paid lending/borrowing has no dedicated end-to-end transaction domain established | Pricing alone does not model reservations, terms, handover, return, cancellation and disputes | Approve workflow and payment/ledger/dispute integration before implementation. |
| P2 | Enrollment recommendation/referral rules are not established as a complete lifecycle | Referrals can be abused or confused with identity/residence verification | Model recommendation provenance, limits, revocation and admin review separately from verification evidence. |
| P2 | Arabic community search needs real database verification | A text search language dictionary and a named text-search configuration are different objects in PostgreSQL | Inspect the actual PostgreSQL 18 catalog in CI. Do not assume “arabic” is a registered configuration merely because an “arabic_stem” dictionary exists. |
| P2 | No complete ranking-learning dataset or evaluation gate was established | A model cannot infer interest fairly from request counts or unobserved impressions | Build deterministic baseline, labelled query set, measure exposure and outcomes, then decide whether LTR is justified. |

### 3.1 Decisions that must be recorded before coding

Use small ADRs in the relevant PRs or a short section in this plan’s decision register. Do not leave these as implicit choices:

1. **Membership cardinality:** does a person have exactly one verified primary neighborhood, or can they maintain multiple simultaneously verified neighborhood memberships? The observed service shape appears centered on a single active membership; retain or widen it only after an explicit product decision and migration analysis. Browsing/following a place should not require falsely claiming residence there.
2. **Role/actor model:** separate account identity, assigned platform roles, membership in a community, and authority over a business/institution. Confirm how existing CONSUMER/PROVIDER/ADMIN users migrate with zero unintended privilege elevation.
3. **Product ownership:** determine whether general products extend the catalog module or warrant a separate product/store module. Do not create a second generic listing table merely to create another menu.
4. **Feed exposure:** decide which client signal proves visible exposure, how duplicate callbacks are deduplicated, and how private or dismissed items are represented.
5. **Official alert authority:** define the permitted institution/agency roles and what credential or administrative approval makes an account eligible to publish an urgent alert.
6. **Paid lending:** establish whether this is rental, peer-to-peer lending with a fee, or another legal/commercial model, and how payment hold/release, fees, cancellations, returns and disputes operate.
7. **Search engine:** OpenSearch is a candidate, not a decided dependency. PostgreSQL is the measured baseline.
8. **Push provider and release policy:** choose through current provider documentation, cost, geography, platform support, secret management and operational requirements. Do not commit a provider or client-update policy by assumption.

---

## 4. Target architecture

### 4.1 Keep source ownership clear

Do not build a universal table that takes ownership of every kind of business record. A shared discovery envelope and read projections are reasonable; duplicating domain objects is not.

Each source keeps its full record and lifecycle. Discovery and feed components store only the identifiers and fields they need for retrieval/ranking, plus a source version/timestamp adequate for invalidation and refresh. On result hydration, the source owner remains authoritative for status and permissions.

A source may be represented in several places without being copied as several independent products:

- A provider’s service belongs to its source listing/profile.
- A physical product belongs to its product/store inventory.
- A community recommendation is its own authored post linked to the referenced business or product.
- A business review remains a review resource and is not replaced by a star count copied into a post.
- An official announcement belongs to the authorized publisher’s message lifecycle.
- A guide entry remains a community knowledge record.
- Search documents and feed cards are projections, not authority.

### 4.2 Proposed ownership map

This is a responsibility map, not an instruction to create every module below.

| Responsibility | Existing owner to reuse first | Proposed extension or gate |
|---|---|---|
| Account identity and base user | marketplace-identity | Introduce multi-role/actor grants through a migration-safe design; keep identity and resource ownership separate. |
| Verified neighborhood membership | marketplace-community + marketplace-geo | Extend only after membership-cardinality decision. Do not create duplicate membership services in institutions or feed. |
| Posts, comments, reactions, groups, events, polls, reports | marketplace-community | Add missing post-purpose vocabulary and integrate a new feed orchestrator through public contracts. |
| Business profile, offered services and service areas | marketplace-provider | Add recommendation provenance and cross-domain product/service links where evidence requires them. |
| Product listings and commercial catalog | marketplace-catalog | First compare its entity and API with required product/stock/order behavior. Create a separate product/store module only if ownership and transaction behavior justify it. |
| Real estate and job listings | marketplace-realestate, marketplace-jobs | Add common search/discovery adapters rather than direct repository calls across modules. |
| Reviews and ratings | marketplace-reviews | Preserve eligibility, review origin, author identity, and organic-vs-paid distinctions. |
| Local knowledge guide | marketplace-knowledge | Include in feed/search via source-owned events and read adapters. |
| Geography | marketplace-geo | Reuse the single country-to-neighborhood hierarchy and public geo ports. |
| Notifications | marketplace-notifications | Extend event catalogue/routing/preference resolution; provider selection for push is a separate decision. |
| Direct and transactional messaging | marketplace-messaging | Keep existing conversation idempotency and participant gates; integrate new context/resource links through documented contracts. |
| Canonical listing search | marketplace-search | Evolve into a common orchestrator only through public contracts; use typed source adapters to prevent module-internal access. |
| Operational UI/configuration | marketplace-console and marketplace-app system settings | Add validated layout/section registry and release metadata contract after a gap analysis; do not store executable code in settings. |
| AI | marketplace-ai | Route tools through canonical search, enforce caller scope, and call models only when deterministic/search paths cannot satisfy the task. |
| Official institutions | marketplace-institutions directory | Wire into Maven and app assembly; implement announcement/urgent-alert capabilities only after authority and lifecycle decisions. |
| Cross-source feed | No dedicated feed module was found in the observed reactor | Proposed marketplace-feed, subject to Phase 3 fit-gap approval. |
| Paid lending | No dedicated lending domain was established in reviewed main source | Proposed lending module only after Phase 8 workflow/payment decision. |

### 4.3 How a cross-module contract should work

A source module owns its facts and publishes its own events for meaningful committed transitions: created/published, amended, hidden/withdrawn, expired, verification state changed, or another contractually defined transition.

Other modules consume stable shared events or invoke declared public ports. Avoid synchronous database joins into another module’s repository, direct access to internal entity classes, or a new generic “content” entity that makes every source dependent on a central table.

The event payload should carry the committed fact needed by its consumer, with a stable source identifier and revision/version information where required. For delayed consumers, publication processing must be recoverable and the consumer must tolerate redelivery. Do not assume “exactly once” from an asynchronous listener or queue; prove idempotency at the persistence boundary.

### 4.4 Discovery envelope: dimensions, not one mega-enum

Any shared feed/search DTO should model independent dimensions instead of one mixed content type:

| Dimension | Examples |
|---|---|
| Source owner and source ID | community post, knowledge entry, product, job, business, institution announcement |
| Purpose / interaction intent | question, request, recommendation, poll, lost-found, listing, announcement, urgent alert |
| Business domain | community, goods, used goods, real estate, cars, service, employment, institution |
| Geography | country/city/neighborhood scope resolved through marketplace-geo |
| Audience/visibility | public, signed-in, members of a neighborhood/group, owner-only, admin-only |
| Publication lifecycle | draft, scheduled, published, hidden, withdrawn, expired, corrected |
| Trust/provenance | member verified for a stated fact, business ownership verified, institutional authority verified, referral/recommendation source |
| Commercial nature | organic community content, product/service offer, paid promotion/advertisement |
| Time relevance | publication/update time, valid-from, expiration, event time, urgent-alert validity |
| Ranking/explanation | candidate source, algorithm version, deterministic eligibility reason, ranking reason |

Never use “verified” as an unexplained boolean across all these concepts. A verified person, verified local membership, verified provider ownership and authorized official publisher are different facts and confer different permissions.

---

## 5. Search, feed and recommendation design

### 5.1 Unified search contract

The user-facing search experience should provide one entry point with facets for category/domain and geography, while the service may dispatch to the correct domain-specific data source. It must eventually cover community posts, groups, events, guide entries, official messages, products/used items, real estate, vehicles, services, jobs and business profiles.

First, reconcile PR #524 and complete the canonical code-first path for the search functionality it currently covers. The AI tool must not execute a separate narrower query against CatalogSearchPort when the public REST search orchestration already supports the relevant criteria. Extend the shared search contract only for well-defined, source-supported fields; avoid one huge criteria object with meaningless fields for every domain.

An unavailable or ambiguous location must not silently turn a local query into an unrestricted national/global query. Candidate locations can be returned for clarification. Eligibility filters—visibility, location, publication state, expiry, suppression, owner restrictions—must be enforced by the domain/query path and revalidated before content reaches the caller or an AI prompt.

### 5.2 PostgreSQL first; OpenSearch is a measured gate

The current search implementation already uses PostgreSQL text search, pg_trgm in supported paths, structured facets and the shared geo tree. Keep it as the baseline until the following experiment is complete:

1. Build a representative and reviewable test corpus for Arabic text, mixed Arabic/Latin names, dialect, hamza/alef and ya variants, diacritics/tatweel, common typos, business names, local place names and each supported content category.
2. Record judged query-to-result relevance; the product owner/reviewers must identify useful, incorrect and dangerously out-of-scope results. Do not judge quality from a handful of attractive examples.
3. Compare PostgreSQL lexical/full-text + trigram behavior against the existing baseline before and after any proposed normalization. Inspect the actual PostgreSQL 18 text-search configuration and dictionary catalog.
4. **Arabic configuration safety:** PostgreSQL documents an arabic_stem dictionary; that does not by itself prove a text-search configuration named arabic exists in the deployed database. The proposed websearch_to_tsquery('arabic', ...) path must not merge until CI queries pg_catalog.pg_ts_config and proves that the configured name exists and behaves as tested, or an explicit migration-managed configuration is created and verified. Do not silently rely on a local image’s custom configuration.
5. Measure correctness, relevance, latency distribution, index/update cost, data volume, memory/CPU, operational complexity, failure recovery and monthly cost for PostgreSQL and OpenSearch under the same data and workload.
6. Only if OpenSearch meets owner-approved relevance/latency/cost/operations criteria, propose it as a secondary retrieval index. PostgreSQL remains the source of truth. Build a reindex/backfill/retry and rollback plan before enabling it.
7. When hybrid search is justified, compare lexical/vector combination using OpenSearch’s official score-normalization and rank-based (RRF) approaches. Do not choose arbitrary weights before measurement.
8. If it does not earn its operational cost, retain PostgreSQL and revisit when production measurements indicate the need.

Embedding generation, vector stores, AI search and a hybrid search API are separate choices. Do not add OpenSearch solely to make AI appear more advanced. If adopting Spring AI’s OpenSearchVectorStore, use the official Spring AI 2.0.1 starter/auto-configuration where suitable and use the native OpenSearch client only for capabilities the vector-store abstraction does not cover.

### 5.3 Feed streams

Expose distinct and understandable streams:

- **Latest/New:** eligible content ordered chronologically with a stable full sort key. It should be the reliable baseline and recovery path.
- **Following:** posts and content from explicitly followed people, groups, pages, businesses or institutions, respecting each source’s publication policy and visibility.
- **Trending/Popular:** a documented time-windowed score with anti-abuse rules and geographic eligibility. Do not confuse lifetime totals with local current popularity.
- **For You:** eligibility first, then declared interests, following/membership, actual visible exposure and prior positive signals, followed by constrained discovery. It must not be a black box that broadens geography unexpectedly.
- **Official urgent alerts:** a separate priority/validity lane. They must not be hidden by engagement ranking while eligible and valid, but urgency cannot be self-assigned by an ordinary user or model.
- **Domain browse streams:** topic, category, group, event, jobs, goods and businesses should use the same eligibility and explanation principles while retaining their source-domain semantics.

At the beginning, Latest and Following can use deterministic query composition and public source ports. Do not build a large ranking platform or duplicate all source rows before workload proves that a projection is required. A dedicated marketplace-feed module is proposed only once the fit-gap confirms there is no suitable existing orchestration owner.

### 5.4 Impression, feedback and ranking data

A feed API response is not an impression. The client must submit a narrowly defined exposure signal when a card becomes meaningfully visible, with deduplication, stream/session identifier, source ID, position, candidate source, ranking version and timestamp. The precise exposure rule is a product/analytics decision and must be documented.

Do not make no-click equivalent to a negative preference. Record explicit positive and negative actions distinctly, including save/follow/reply/share, hide, “not interested,” report and block. Safety reports are not a preference signal. Keep raw events and derived interest profiles subject to documented access, retention, correction and deletion rules.

Start with rule-based ranking with measurable reason codes. Only evaluate a learned ranking model after there is sufficient reliable exposure/feedback data, a judged dataset, a holdout or online experiment method, and guardrail measurements for local relevance, diversity, recency, content quality, safety reports and exposure fairness. OpenSearch LTR is an available technology, not a pretrained social-interest model.

---

## 6. Institution messages, trust and moderation

### 6.1 Institution wiring

The first task is not to write new institution features; it is to reconcile the existing institution module, root reactor, app aggregation, migration inventory, membership contracts and module verification. The runnable app must prove that institution beans load and its documented public/admin endpoints behave on the merged commit.

Institution registration, representative authority, ownership verification and membership in a neighborhood are distinct. Do not clone the community membership state machine inside the institutions module.

### 6.2 Ordinary official announcement

Required source record and response should include:

- Institution identity and verification/authority state.
- The authorized representative/action that created or revised it.
- Geographic target and audience/visibility policy.
- Publication, correction, withdrawal and expiration state.
- Traceable source link/document where applicable.
- Audit record and notification intent.
- A public detail view that reports only verified fields; absent facts must remain absent, not be fabricated.

Ordinary official announcements are not automatically urgent alerts.

### 6.3 Urgent local alert

Before implementation, define the authority matrix and a state machine for draft/request, review/authorization where required, active, corrected, cancelled/withdrawn and expired. Identify who may issue, narrow, correct and close each alert type.

Every urgent message must carry scope and validity. Recipients and alert priority must be deterministic and auditable. Audit and test high-impact cases: unauthorized publisher, invalid scope, duplicated request, stale/expired alert, correction, withdrawal, recipient preferences, duplicate event delivery and provider outage.

No LLM, score, ordinary post, business payment or neighbor endorsement may create official authority or elevate an ordinary message to urgent status.

### 6.4 Trust, moderation and appeals

Build on existing content-report and moderation-rule mechanisms. Define report target types and actions for posts, comments, users, groups, business pages, listings, reviews and institution messages. Do not create an unrelated moderation engine for every domain.

Required safeguards:

- Reporter, target, reason, evidence, review state, actor, action, timestamp, appeal/correction and audit trail.
- Clear distinction between hiding content, removing it, suspending an account, rejecting verification, dismissing a report, and correcting an official message.
- Member-facing notification on relevant decisions without leaking reporter identity or internal moderation details where policy forbids it.
- Human review/appeal path for high-impact or contested decisions; automatic filters can triage or temporarily limit distribution only according to an explicit and tested policy.
- Rate limits, anti-spam and anti-scam measures at relevant command boundaries.
- Tests proving moderator/administrator privileges cannot be obtained from a badge, trust score, referral or business purchase.

Nextdoor publicly describes a multi-layer moderation model involving neighbor reports, community moderators, operations staff and technology. Treat this as a researched product pattern, not proof that the same staffing model or legal posture is right for this product.

---

## 7. Marketplace and business-directory integration

### 7.1 Source-of-truth rule

There must be one authoritative product/service record for each actual offer, not independent copies in the Community feed, Marketplace and Business Directory.

A product/service can appear through multiple read surfaces by reference to its source identity. Any read projection must be refreshed when the source is updated, paused, sold, expired, hidden, suspended or deleted. Search/feed results must not resurrect a withdrawn record from stale indexes.

### 7.2 Product and store domain fit-gap

Before building, compare the full required commerce journey against the current catalog/provider/booking/payment/ledger/dispute modules. Record which aggregate owns:

- Store/business owner and staff access.
- Product identity, SKU/variant where required, category, images/media, price/currency, stock/availability, sale/expiry state and geographic eligibility.
- Store catalog and category attributes.
- Cart(s) and the owner-visible distinction between store cart and neighborhood-unified cart, if the current owner-approved design still requires both.
- Checkout and creation of a durable order snapshot.
- Inventory/stock reservation and safe concurrent purchase behavior.
- Payment-intent and ledger lifecycle, cancellation/refund and disputes.
- Pickup/delivery ownership, assignment, customer tracking and completion.
- Notifications and audit.
- Search and feed visibility after each state transition.

Use existing payment, ledger and dispute contracts where appropriate. Do not build a second payment or balance subsystem in the catalog. Any use of escrow/withheld funds requires a separate legal/financial and state-machine decision; existing payment intents and disputes alone do not prove escrow semantics.

### 7.3 Specialized marketplace verticals

Specialized domains can share publication/search primitives but must retain domain-owned data and workflows:

- **Used goods:** condition, photos, pickup/delivery options, price or free, status/sold/withdrawn, messaging safety.
- **Vehicles:** make/model/year/condition/mileage/price and relevant geographic filters, where approved; no generic-category-only implementation.
- **Real estate:** existing real-estate purpose/type, geographic search, property data and inquiry flows, not a parallel real-estate entity in the feed.
- **Professional/scientific services:** provider qualifications and verification type, service scope, terms/pricing, geography, inquiry or booking rules.
- **Jobs:** employer ownership, listing lifecycle, applications and applicant privacy using the existing jobs module.
- **Neighborhood lending:** separate workflow from a sale listing.
- **Business services/products:** show the same owned source records in the business profile and Marketplace without losing commercial provenance.

Each vertical requires owner-scoped writes, queryable status, expiry/withdrawal, deterministic pagination, moderation, media privacy, audit, notifications and integration tests.

### 7.4 Reviews and recommendations

Keep paid ads out of organic trust calculations. A recommendation must retain the person/source and business/service target, with deduplication and abuse protections. Review eligibility and published aggregate counts follow the current reviews contract and actual review records. A business page may display ratings and recommendations together only with clear labels and source semantics.

The 2026 Nextdoor Fave Awards announcement is relevant product evidence: the public experience distinguishes neighbor endorsements from generic reviews and links the resulting discovery surface to business pages. Adapt the principle of provenance and clear labeling; do not copy proprietary ranking or award formulas.

---

## 8. Notification, console, UI configuration and release management

### 8.1 Notification routing

Build on marketplace-notifications and its current notification-event/preference design. First inventory event publishers and listeners; a business event with no notification policy is a gap only after its product behavior says a user should be notified. Do not send notifications for every technical event.

The design should support:

- Canonical event type, source owner/ID, recipient, section/domain, topic/category, location scope, urgency/priority, and event idempotency identity.
- Per-user preferences with sensible documented defaults, channels, optional geographic subscriptions, topic/subsection choices, and device tokens only after selecting a push provider.
- Separate policies for in-app inbox, email, WebSocket and mobile push. WebSocket delivery is not mobile push.
- Official alert routing/priority according to the authority and geography policy; not ordinary engagement preference.
- Deduplication, retry/recovery, delivery result telemetry, suppression of self-notifications where appropriate, unread/read/clear semantics and user-controlled opt-outs.
- Privacy/security checks so one user cannot inspect another user’s notification, token or preferences.

Do not create one giant enum for every combination of section × topic × geography × channel. Keep event identity, policy/routing dimensions and channel implementation separate.

### 8.2 Console-driven configuration

Reuse marketplace-console, system_settings, the existing feature flags and geographic settings after confirming the actual readers and cache invalidation behavior.

The console may configure existing capabilities, including:

- Section navigation and order.
- Enabling/disabling supported sections/rows/components.
- Known category registries and validated labels/order.
- Safe layout composition from a server-approved component registry.
- Geographic feature gates and inherited settings using the existing geography hierarchy.
- Feed mode defaults and supported weights/thresholds explicitly declared as runtime data.
- Notification policy settings and official alert categories.
- Rollout flags, maintenance status and emergency disable switches.

The console must not store executable JavaScript, arbitrary HTML, unchecked query fragments, class names, or code-like logic in configuration. The client renders only a known component vocabulary with schema-validated data. Configuration changes are audited, versioned where rollback is needed, and rejected if invalid. If a requested behavior requires a new component, permission or business workflow that deployed code does not understand, that behavior requires a normal reviewed code release.

Define cache invalidation and the effective configuration contract. A successful admin write must become visible at the expected runtime boundary without an app restart when the setting is request-time data, or explicitly identify it as a boot-time property that requires restart/redeployment.

### 8.3 Application release and update API

Separate three version concepts:

1. Backend deployment commit/release.
2. API version/deprecation contract.
3. Android/iOS client release and minimum supported client version.

The backend release registry, if approved, should expose only validated metadata such as platform, latest stable version/build, minimum supported version/build, release notes, store URLs, optional/forced update policy and rollout/status information. Do not conflate it with Spring Boot configuration or API version annotations.

Any forced-update policy needs an operator-approved emergency/roll-forward/rollback procedure, staged rollout rules if used, and tests of old clients. An app version advertised by the API must correspond to a real published artifact; never manufacture a “latest” value from a constant that is not connected to release operations.

---

## 9. AI integration and automation boundaries

### 9.1 Required AI ladder

1. **Deterministic application logic:** authentication, authorization, validation, membership, official publisher checks, publication state, price math, transaction transitions and visibility filters.
2. **Canonical search:** use the same authorized search and discovery capabilities as REST.
3. **Structured judgment/intent:** use typed outputs for ambiguous intent or routing only when needed and supported by the current Spring AI 2.0.1 path or a verified optional extension.
4. **Lower-cost model:** bounded extraction/summarization after authorized retrieval.
5. **Higher-capability model:** complex multi-step requests only when evaluation demonstrates the need.
6. **Evaluation and safeguards:** task-specific measurement and content filters where appropriate, with user-facing explanations and recovery behavior.

Do not call an LLM for every post, every feed candidate, every normal search, authorization, official-alert authority, payment decisions, fixed business rules or moderator permissions.

### 9.2 Spring AI requirements

- Confirm current marketplace-ai source, BOM/starter set and provider configuration on active main before work.
- Prefer Spring AI’s managed ChatClient.Builder, official auto-configuration, provider starters, structured-output support and documented model APIs when available.
- AI search must use canonical search ports and pass the caller’s real authorization/geographic eligibility; never make a second broad catalog/database query that bypasses visibility filters.
- Validate all model-produced identifiers and structured values before acting on them. Structured output is a typed parse contract, not proof that a model decision is correct.
- Keep provider keys external and fail safely when required secrets are absent. Never paste or commit secrets into this plan, source, logs, seed data or issue comments.
- For optional community integrations such as TypeSafe/Jev, prove current compatibility, behavior, licensing, security, cost and value; keep them opt-in until those gates pass. Do not describe an extension as an official Spring project unless its ownership/status is verified on the current source repository.
- Record latency, token/cost usage, provider failures and fallback behavior without logging sensitive prompts or personal data.
- A disabled AI provider or vector index must not make deterministic community/search functionality return fabricated results or become unavailable unnecessarily.

---

## 10. Phased execution plan and exit gates

No calendar estimate is committed. Phase order is based on architectural and data dependencies, not a presumed number of weeks. Each phase is its own scoped branch/PR with an explicit acceptance report.

### Phase 0 — Reconcile current main and in-flight work

**Objective:** establish a safe, real baseline before building more features.

**Work**
- Re-read the latest main SHA, root and app POMs, complete current migration inventory, OpenAPI contract, current PR list, and check-runs on exact commits.
- Reconcile #511, #524, #525 and #529 with the current merge queue. Do not reuse their old-base check results as evidence. Split unrelated work if needed.
- Wire marketplace-institutions into the reactor and app, or produce an evidence-backed owner-approved reason not to wire it. Verify that the actual institution controllers/services load in the runnable app.
- Reconcile every migration-number/checksum conflict. Verify schema upgrade from a clean PostgreSQL 18 database and from the current production-shaped schema using normal Flyway ordering.
- Check secret-scanning history and the live secret source. If a key has been exposed, revoke/rotate it through the provider and secret manager before re-enabling the feature; do not reproduce it in the PR.
- Establish and store baseline build, integration/security checks, test counts, OpenAPI operations/paths, migration inventory and deployment status. Current main test/deployment status must be re-queried, not inherited from old PRs.

**Exit gate**
- Exact current head has required build, full integration, OpenAPI compatibility, security and image checks green; any non-green or skipped check is explained by its documented condition.
- Institutions runtime wiring is proven or an explicit owner decision is recorded.
- Migration history is collision-free and no old applied migration was modified.
- Public endpoints and security/authorization behavior are verified against exact commit.
- PROJECT_MAP.md, this plan and any other affected source-of-truth docs match measured state.

**Must not begin dependent schema work until this gate passes.**

### Phase 1 — Identity, roles, locality and trust model

**Objective:** make the identity model capable of representing the product without breaking current consumers.

**Work**
- Inventory every authority check, role enum, user-profile and provider/institution ownership path.
- Write an ADR separating account identity, multiple account roles, business/institution representation, group/organization membership, neighborhood membership and resource-scoped permissions.
- Decide verified-neighborhood membership cardinality and how browsing/following areas differs from claiming residency.
- Define verification evidence and state transitions for identity, neighborhood membership, provider ownership, professional qualification and official publisher authority.
- Define recommendation/referral separately: who may recommend whom, what it proves, abuse limits, audit, decline/revocation, and whether it is advisory or a mandatory enrollment prerequisite.
- Migrate legacy CONSUMER/PROVIDER/ADMIN data additively, with compatibility/read-write transitions and no automatic privilege elevation.
- Add security tests for cross-owner access, role combination, admin-only decisions, revocation, suspended/deleted users and unauthenticated calls.

**Exit gate**
- Existing accounts preserve intended access without privilege expansion.
- A single account can safely hold approved combinations of roles/actors.
- Role and resource ownership are enforced at endpoint/service boundaries where required.
- Verification types are distinguishable in APIs, storage, audit and client contracts.
- Real migration tests prove old data survives and forbidden states are rejected.

### Phase 2 — Domain vocabulary and discovery contracts

**Objective:** make content discoverable without taking ownership away from source modules.

**Work**
- Finalize content-purpose/domain/geography/audience/provenance/lifecycle/commercial-kind dimensions from Section 4.4.
- Reconcile/add QUESTION and REQUEST categories without changing old category semantics or modifying an already applied migration.
- Define common read-only discovery metadata and source-owned typed payload contracts.
- Define event contracts for publish/update/hide/withdraw/expiry/verification changes only where consumers need them. Document producer, event identity, schema evolution, consumer and idempotency behavior.
- Ensure visibility and publication state are available to retrieval/adapters and rechecked on result hydration.
- Update OpenAPI examples and compatibility tests.

**Exit gate**
- Every supported content source maps to vocabulary unambiguously.
- Existing client fields/category behavior remains compatible or has an approved migration path.
- No source record must be duplicated to appear in another domain.
- Module verification remains green with no dependency into another module’s internals.
- Tests cover hidden, withdrawn, expired, unauthorized and cross-neighborhood content.

### Phase 3 — Unified community feed

**Objective:** compose local content into streams without creating a second source of truth.

**Work**
- First test whether existing public community/search/read contracts already support orchestration; document the fit-gap.
- If a separate owner is justified, add a marketplace-feed Maven module following current Modulith conventions. It coordinates discovery; it must not own community posts, products, jobs, reviews or institution messages.
- Implement Latest first with stable ordering, then Following, Trending/Popular and For You as separate strategies with documented eligibility/ranking semantics.
- Add source adapters for approved mix of posts, interaction summaries where required, groups, events, polls, knowledge, local-market items, products/services, business recommendations and institution messages.
- Preserve each source link, source ID, owner, source status, visibility, creation/update and expiration state.
- Add pagination, duplicate suppression, deterministic tie-breakers, blocked/muted-source handling and reason codes. Urgent official alerts follow their own lane.
- Add an explicit visible-impression contract. If the client cannot report exposure, analytics must label delivery and impression as different facts.
- Do not build a learned ranking model in this phase.

**Exit gate**
- End-to-end tests publish an item in each approved source and prove eligibility, appearance and withdraw/update behavior in the correct streams.
- No cross-neighborhood, private, hidden, withdrawn or expired item leaks into results.
- Latest is deterministic and works when AI/search providers are disabled.
- Following changes after follow/unfollow; Trending is local and time-bounded; For You explains a ranking reason.
- Impression records represent the agreed client exposure signal rather than API serialization.
- No duplicated domain records or repeated feed items across pages.

### Phase 4 — Institutions, official announcements and alerts

**Objective:** safely publish trusted institutional information to the right geographic audience.

**Work**
- Complete Phase 0 institution wiring.
- Finish representative registration, institution verification and authorization checks.
- Define ordinary announcement and urgent-alert lifecycles separately. Record authorizing actor, source, geography, valid interval, correction/revocation and audit trail.
- Publish committed events for downstream feed/search/notification processing; consumer failures must be recoverable and duplicate delivery safe.
- Add authorized operator views and review queues where required.
- Define notification routing and public detail representation for official messages.
- Distinguish official news, community-posted news links, paid promotion and emergency alert.

**Exit gate**
- An unauthorized member/provider cannot send an official announcement or urgent alert.
- A valid institution can publish within its authority/scope, revise/correct, cancel and expire a message.
- Only eligible recipients receive it; duplicate event delivery does not produce duplicate alerts.
- Stale search/feed projections cannot resurrect withdrawn/corrected/expired official content.
- The complete journey is covered by PostgreSQL integration tests and security-negative tests.

### Phase 5 — Canonical multi-domain search

**Objective:** make the existing PostgreSQL search path the consistent retrieval layer before considering a separate engine.

**Work**
- Reconcile and finish PR #524’s canonical-search direction after Phase 0. Search through canonical orchestration and typed source adapters, not internal repositories.
- Add adapters for community posts, guide entries, groups, events, institutions, businesses, jobs, used goods and approved product/real-estate listings.
- Keep category and geography filters composable; qualify requested geographic node through marketplace-geo and fail safely on unknown/ambiguous nodes.
- Create a fixed, versioned Arabic relevance test corpus and a review process for judged results.
- Validate PostgreSQL 18 Arabic text-search objects in actual CI database; do not treat a dictionary as proof a named text-search configuration exists.
- Measure PostgreSQL full-text, trigram, substring/prefix where used, filter correctness and ranking. Compare optional OpenSearch only after a representative benchmark.
- Define source projection update/rebuild and rollback if a search index is ever approved.

**Exit gate**
- REST and AI search return materially equivalent eligible result sets for equivalent criteria.
- Arabic tests include spelling variants, diacritics/tatweel, mixed-script queries, common misspellings and local names; actual query configuration is verified.
- No result leaks through a relaxed fallback path.
- Query and count predicates apply the same geography/visibility/category conditions.
- OpenSearch is adopted only by a measured, owner-approved decision; otherwise the implementation remains PostgreSQL.

### Phase 6 — Shared commercial identity and vertical completion

**Objective:** make product/service information reusable across Marketplace, Community and Business Directory.

**Work**
- Run catalog/provider/real-estate/jobs fit-gap from Section 7.2 and approve owning module/aggregate before adding a new product table.
- Define common source identity and read contract for a product/service appearing in a store, business page, search result or community recommendation.
- Keep a used/free neighborhood market item distinct from an inventory-backed sale unless the owner decides they share a lifecycle.
- Add needed product/store, stock, cart, order, payment/ledger, delivery and return capabilities only according to the approved transaction scope.
- Integrate existing payment, ledger, booking, provider, reviews, dispute and media ports as documented; do not introduce a parallel currency, inventory, payment or dispute engine.
- Complete domain-specific vehicle, property, job and professional service attributes and workflows.
- Preserve organic reviews and member recommendations separately from paid placement, advertisements and purchased boosts.
- Ensure state changes publish invalidation/update facts for feed and search.

**Exit gate**
- Product/service data has one owner and can be rendered in approved surfaces without duplicated source records.
- Concurrent stock/order transitions are safe and idempotent.
- Price/currency is stored in repository’s money convention; no floating-point money or unstated commission.
- Cancelled, sold, expired, hidden or suspended offers disappear from public surfaces and AI retrieval.
- Order/application/lead journeys include authorization, notifications, audit, retries and user-visible state.
- Existing jobs/real-estate/business/review behavior is not regressed.

### Phase 7 — Notification routing and direct communication integration

**Objective:** support fine-grained, explainable notifications across sections and topics.

**Work**
- Inventory every committed business event, its intended recipients and current notification listener. Add only product-meaningful notification events.
- Define one event/routing contract with section, topic, source, recipient, scope, priority/validity and idempotency identity.
- Extend the existing per-type/per-channel preference system into hierarchical topic and geographic preferences, with documented defaults and explicit opt-outs.
- Preserve in-app inbox/read/unread, email, WebSocket and direct messaging. Add mobile push only after a provider decision and official provider documentation/security review.
- Add quiet-hours/frequency/deduplication behavior only where user experience requires it and the policy is stated.
- Ensure urgent alerts bypass only the documented engagement-ranking/preference restrictions permitted by official-alert policy.
- Do not derive recipients in the notification listener if the source event can carry the committed recipient/scope fact.

**Exit gate**
- Each event has one declared notification policy or an explicit no-notification rationale.
- Preferences are actually honored by senders and match the API’s effective-preference response.
- A retried event does not create multiple in-app notifications or repeated fan-out.
- A recipient cannot access another member’s notification, channel token or preferences.
- Tests cover disablement, opt-out, send/provider failure, retries, direct-message arrival and official alert delivery.

### Phase 8 — Paid lending/borrowing

**Objective:** implement paid lending as a complete transaction domain, after the owner approves its business/legal semantics.

**Work**
- Write a workflow ADR before schema/API work: asset owner, borrower, item condition, availability/calendar, request/acceptance, lending period, fee/deposit, pickup/hand-off, return, late return, cancellation/no-show, damage/dispute, refund/settlement and prohibited-item policy.
- Determine which existing payment, ledger and dispute contracts can safely support the flow. Do not call an ordinary payment intent escrow.
- Define transaction states, allowed transitions, idempotency keys, locking/concurrency and audit.
- Define listing/availability projection separately from a confirmed lending transaction.
- Add conversations/notifications and evidence/media paths through existing modules.
- Add limits and abuse/identity gates appropriate to approved transaction risk.

**Exit gate**
- Two competing borrowers cannot reserve the same unavailable time/item.
- Replays cannot double-charge or create duplicate rentals.
- Failure at payment, acceptance, hand-off, return or dispute is recoverable and visible to the user.
- All money movements reconcile with ledger entries and approved refund rules.
- Cancellation, completion and dispute journeys are integration-tested before launch.

### Phase 9 — Operator console, configurable surfaces and release registry

**Objective:** permit safe operational evolution through the console without pretending data can create new code.

**Work**
- Audit marketplace-console and SystemSettingsService readers, writers, cache invalidation and audit trails.
- Define a versioned, validated schema for layout sections and supported components. Include order, visibility, title/label, supported filters, geographic scope and approved flags.
- Add safe rollout/rollback to layout/config changes. Reject unknown component types, unsupported property fields and invalid category references.
- Support approved geographic inheritance through existing geo hierarchy.
- Add release metadata only after defining client platforms, version/build identifiers, minimum supported API/client compatibility, update-required semantics, notes, rollout and store URL requirements.
- Keep API version deprecation and mobile app version enforcement separate contracts.
- Apply admin authorization and change audit to every sensitive mutation. A UI switch may not bypass corresponding server authorization.

**Exit gate**
- Authorized operators can change supported section order/visibility and approved configuration without a code release.
- Invalid JSON/schema/config is rejected before activation; last known valid layout can be restored.
- Admin changes are attributable and audited; geo overrides resolve by approved hierarchy.
- Release API values correspond to an actual release record and cannot silently force a user onto a nonexistent artifact.
- No executable script or arbitrary SQL is stored as configuration.

### Phase 10 — Ranking, personalization and responsible AI

**Objective:** improve relevance after reliable search/feed data exists.

**Work**
- Establish a deterministic ranker with documented signals and explanation strings.
- Build a labelled local query/feed evaluation set. Track relevance, freshness, geographic scope, diversity, source balance, hide/not-interested rates and safety outcomes.
- Validate whether enough unbiased exposure/feedback exists to train or evaluate a model; control position/exposure bias.
- Test a learned ranker or OpenSearch LTR only if evaluation set and operational data are sufficient.
- Connect marketplace-ai to canonical search and feed read contracts. Keep model output from overriding visibility, verification, official authority, transaction or moderator permissions.
- Use Spring AI’s documented typed/structured-output and ChatClient paths where suitable. Treat TypeSafe/Jev/community integrations as optional until exact current repository, version compatibility, supported features and cost have been verified.
- Introduce A/B testing only with stable assignments, user privacy controls, rollback and clearly defined success/guardrail metrics.

**Exit gate**
- Candidate ranker outperforms deterministic baseline on an agreed offline/online test and does not harm approved local relevance/safety/diversity guardrails.
- Reasons can be returned and explained without disclosing private data or internal model secrets.
- Disabling an AI provider has a tested graceful fallback.
- No LLM path can read private/hidden records or exercise permissions not granted to caller.
- Cost, latency, model/version and failure metrics are captured without leaking sensitive prompts or personal data.

### Phase 11 — Reliability, privacy, security and production proof

**Objective:** prove that the whole system is safe and operable, not merely feature-complete.

**Work**
- Re-run the whole Maven reactor, all required unit/integration suites, module-boundary and OpenAPI checks, migration checksum/integrity guards, dependency/security scans, code analysis and container scanning.
- Test fresh-schema and upgrade-schema migration journeys. Verify no new migration collides with pending PRs or production version.
- Test event publication failure, consumer retry/redelivery, stale projection recovery, cache invalidation, provider outage, service restart and disabled integrations.
- Test permissions in negative form: unauthenticated, wrong role, wrong resource owner, wrong neighborhood, wrong group, unverified/rejected state, deleted/suspended actor and stale token/session.
- Verify user privacy and data lifecycle for location, resident visibility, recommendations, reviews, feed impressions, message metadata, exported data and account deletion/pseudonymization.
- Review the applicability of GDPR, Digital Services Act, consumer/marketplace and sector-specific requirements for the actual operating model and countries. Record legal decisions and applicable obligations with qualified advice; do not infer applicability from a technical checklist.
- Re-query deployment workflow and exact CI. Document release, rollback and recovery actions. A merge is still prohibited until the owner explicitly authorizes it.

**Exit gate**
- All tests and security gates required by repository policy are green on the exact candidate commit.
- All user-critical journeys pass end-to-end against real PostgreSQL/Testcontainers-backed integration boundaries.
- Backup/restore and rollback procedures for new schema/data are proven where applicable.
- Every accepted risk or deferred capability is explicit, owned and date-bound; no unknown state is labelled done.
- Staging smoke checks pass before any production release; monitor first rollout and preserve a rollback path.

---

## 11. Definition of Done for every endpoint and journey

A feature is not complete until all applicable items below are verified.

### 11.1 API and security

- API route/version, request/response DTO, validation limits, documented errors/status codes and examples are updated.
- OpenAPI compatibility checks pass; existing mobile/web contracts are reviewed.
- Authorization exists at the right request and service boundaries. Test both allowed and rejected cases.
- Tenant/user/geographic ownership is derived from the authenticated caller and domain contract, not blindly trusted from a request body.
- No private data is returned through a public projection, search document, AI prompt, notification or audit-facing response.

### 11.2 Data and concurrency

- Entity lifecycle, enums, nullability, uniqueness, deletion, timestamps, audit/history and indexes are defined.
- Every schema change uses a new Flyway versioned migration; existing applied scripts remain byte-for-byte unchanged.
- Constraint behavior and duplicate/race outcomes are tested against the actual PostgreSQL version.
- Retried writes are idempotent where the business operation requires it; an optimistic-lock version is not mistaken for request idempotency.
- Pagination is stable with a complete sort key and has matching content/count eligibility filters.
- Money never uses floating-point arithmetic.

### 11.3 Events and notifications

- Every required committed state change has a declared downstream effect, with a source event and a named consumer.
- Publication/consumer behavior uses repository Spring Modulith event-publication/recovery conventions where applicable.
- Consumer retries are idempotent; no event is silently dropped after a failed consumer.
- Each event has a declared notification/no-notification policy and tests prove behavior.

### 11.4 Search/feed

- Private, suppressed, hidden, deleted/withdrawn or expired records are excluded at retrieval and rechecked during hydration.
- A source update/removal invalidates or updates projections/indexes, and rebuild can recover from missed projection changes.
- Arabic test cases include normalization, real dictionary/config availability, common typos, mixed-script text and geographic specificity.
- Ranking output includes stable ordering and an explainable reason where personalization is used.

### 11.5 Tests and operations

- Unit tests cover domain transitions and validation; integration tests exercise persistence/transactions and relevant inter-module paths.
- Spring Modulith verification and affected module tests pass.
- PostgreSQL integration tests use repository-required ActiveProfiles("test") and Testcontainers conventions.
- Test counts and results are recorded on the exact commit. Docker-gated tests are not described as passed if skipped locally.
- Logs/metrics avoid secrets and unnecessary personal data; relevant business operations are observable.
- Admin-configured behavior has validation, audit and rollback.
- API/mobile integration or a contract consumer test proves the client has enough information to render the full journey, including empty/error/loading/pagination states where defined.

---

## 12. Operational rules for developers and coding agents

For every phase or PR, include the following in the PR description:

1. **Measured gap:** exact source file/API/migration/CI evidence on current base.
2. **Official basis:** exact documentation section or official repository API that supports the chosen framework mechanism.
3. **Source ownership:** which module owns the data and how other modules integrate.
4. **Schema/API impact:** new migration numbers only after reconciliation, compatibility changes, OpenAPI delta and client impact.
5. **Security and privacy:** permissions, resource scope, sensitive fields and negative tests.
6. **End-to-end journey:** steps and test artifacts for success, failure, retry and rollback.
7. **Verification:** commands/results and exact tested commit; identify any test that skipped and why.
8. **Documentation sync:** update this plan’s status, PROJECT_MAP.md, relevant system/architecture documentation and OpenAPI/API client docs in the same scoped work when affected.
9. **No merge by implication:** green CI, reviewer approval, or completion of a phase is not the owner’s merge instruction.

### Required local/CI verification

Use the exact project’s wrappers and conventions rather than inventing new scripts. At minimum, the affected module must be verified with its reactor dependencies using the repository-prescribed Maven command; the full reactor and required GitHub Actions suites must decide final acceptance.

- Affected module: ./mvnw clean verify -pl MODULE -am
- Candidate full reactor: ./mvnw clean verify
- For changes to common contracts, app assembly, security, Flyway or cross-module events, include affected consumer modules and full application integration gates.
- OpenAPI backward-compatibility, migration-checksum/integrity and Modulith verification are release gates, not optional documentation checks.
- Check latest GitHub check-runs on the exact PR head and current base. Re-check after any rebase or source change.

Do not report a provider call, search index, notification channel, push provider, mobile release or production feature as “working” solely because its classes compile. Prove the real path or describe the missing runtime configuration honestly.

---

## 13. Trusted product evidence and official technical references

### 13.1 Official framework/runtime references

Pin documentation to the versions in the actual POM/lock/config and refresh the link/version before each affected change.

| Concern | Primary reference | Use |
|---|---|---|
| Spring Boot 4.1.1 auto-configuration | [Auto-configuration](https://docs.spring.io/spring-boot/reference/using/auto-configuration.html) and [Creating auto-configuration](https://docs.spring.io/spring-boot/reference/features/developing-auto-configuration.html) | Use supported auto-configurations, starters and conditional back-off; do not invent bean wiring where the framework provides a documented path. |
| Spring Boot 4.1.1 external configuration | [Externalized configuration](https://docs.spring.io/spring-boot/reference/features/external-config.html) | Separate environment/startup configuration from persisted request-time operator data. |
| Spring Boot testing | [Testcontainers and service connections](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html) | Prefer supported test service connections and real persistence integration where appropriate. |
| Spring Modulith 2.1.1 boundaries | [Verifying application module structure](https://docs.spring.io/spring-modulith/reference/verification.html) | Enforce acyclic dependencies and allowed public module interfaces. |
| Spring Modulith integration tests | [Testing application modules](https://docs.spring.io/spring-modulith/reference/testing.html) | Test domain modules in isolation or approved combinations. |
| Spring Modulith events | [Working with application events](https://docs.spring.io/spring-modulith/reference/events.html) and [ApplicationModuleListener API](https://docs.spring.io/spring-modulith/docs/current/api/org/springframework/modulith/events/ApplicationModuleListener.html) | Use event publication registry and documented recovery behavior; design consumers for retries/redelivery. |
| Spring Security 7.1.1 | [Method security](https://docs.spring.io/spring-security/reference/7.1/servlet/authorization/method-security.html) | Keep resource authorization explicit at method/service boundaries and test denied paths. |
| Spring Data JPA 4.1.1 | [Specifications](https://docs.spring.io/spring-data/jpa/reference/jpa/specifications.html) | Compose query predicates without creating a repository method for every filter combination. |
| Spring AI 2.0.1 | [ChatClient API](https://docs.spring.io/spring-ai/reference/api/chatclient.html) and [structured output](https://docs.spring.io/spring-ai/reference/api/structured-output.html) | Use managed APIs and typed output; structured output is not a substitute for validation/authorization. |
| Spring AI 2.0.1 OpenSearch integration | [OpenSearchVectorStore](https://docs.spring.io/spring-ai/reference/api/vectordbs/opensearch.html) | Use official starter/auto-configuration if OpenSearch is approved; do not enable it solely to gain a vector-store class. |
| PostgreSQL 18 text search | [Full-text search](https://www.postgresql.org/docs/18/textsearch.html), [configuration example](https://www.postgresql.org/docs/18/textsearch-configuration.html), [catalog/config debugging](https://www.postgresql.org/docs/18/textsearch-psql.html) | Verify actual text-search configurations and dictionaries on PostgreSQL 18. |
| PostgreSQL 18 typo matching | [pg_trgm](https://www.postgresql.org/docs/18/pgtrgm.html) | Use measured threshold/index behavior and the existing search baseline. |
| Flyway versioned migrations | [Versioned migrations](https://documentation.red-gate.com/fd/versioned-migrations-273973333.html) | Applied migrations are checksum-tracked; roll forward with a new version rather than rewriting history. |
| OpenSearch search evaluation | [Hybrid search](https://docs.opensearch.org/latest/vector-search/ai-search/hybrid-search/index.html), [normalization](https://docs.opensearch.org/latest/search-plugins/search-pipelines/normalization-processor/), [RRF](https://docs.opensearch.org/latest/vector-search/ai-search/hybrid-search/rrf/) | Compare score-based and rank-based merging with judged results; choose only through benchmark evidence. |
| GitHub deployment safeguards | [Deployments and environments](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments) | Treat production deployment as a protected, exact-commit operation. |

If a page redirects to a later release, find the matching installed-version reference rather than adopting a newer API by inference. The current repository’s parent/BOM and resolved dependency graph govern compatibility.

### 13.2 Nextdoor product and community evidence

These sources inform product hypotheses; they do not define the implementation:

- [Nextdoor’s 2026 Fave Awards announcement](https://about.nextdoor.com/press-releases/nextdoor-opens-voting-for-the-2026-fave-awards?hs_amp=true) — neighbor recommendations are a distinct discovery/trust signal, separate from a generic public star rating.
- [Nextdoor’s September 2026 business discovery update](https://about.nextdoor.com/press-releases/nextdoor-deepens-its-commitment-to-local-business-discovery-and-recommendations?hs_amp=true) — business pages, organic local recommendations and curated local discovery are tightly connected product surfaces.
- [Nextdoor’s 2025 Transparency Report announcement, published March 2026](https://about.nextdoor.com/press-releases/nextdoor-publishes-2025-transparency-report) — public account of integrity, verification, fraud/scam handling and layered moderation.
- [Nextdoor Policy Hub](https://about.nextdoor.com/policy/) and [Community Guidelines](https://help.nextdoor.com/articles/en_CA/Knowledge/community-guidelines) — guidance for respectful, local, safe participation, with policy and enforcement paths.
- [Nextdoor public moderation overview](https://blog.nextdoor.com/2021/02/10/how-moderation-works-on-nextdoor) — an example of combining member reporting, local moderation, trained operations and automated detection.

These are vendor-published sources. Where practical, also test the proposed experience with actual target residents and business owners; public product announcements and testimonials are not independent user research.

### 13.3 Applicable legal/compliance review

- [EU Digital Services Act, Regulation (EU) 2022/2065](https://eur-lex.europa.eu/eli/reg/2022/2065) — includes recommender-system transparency in Article 27 and rules about identification of commercial communications in Article 26. Record whether each obligation applies to the actual platform/service model; this plan does not make a legal applicability determination.
- [GDPR, Regulation (EU) 2016/679](https://eur-lex.europa.eu/eli/reg/2016/679/oj) — review lawful basis, purpose limitation, minimization, retention, access/deletion, profiling, security and data-subject rights before collecting locality, interest, referral, trust and exposure data.

Legal review is a launch gate for target countries, not a substitute for technical data-minimization, user control, audit and security design.

---

## 14. Decision register and phase tracker

Keep this register updated in the same PR that closes a decision. “Not decided” is an intentional stop condition, not permission for an implementer to guess.

| ID | Decision | Current disposition | Decision/exit owner |
|---|---|---|---|
| D-01 | Product name/brand | Not selected; use “community platform” in technical work | Product owner |
| D-02 | Verified neighborhood cardinality | Must be decided against current single-active-membership behavior and intended locality model | Product owner, supported by migration/design evidence |
| D-03 | User multi-role/actor and permission model | Required; no schema/endpoint rollout until compatibility and authority rules are approved | Product owner + identity/security implementation |
| D-04 | Institutions module wiring | Existing source/migration, missing reactor/app wiring on baseline snapshot; phase 0 gate | Backend implementation |
| D-05 | Unified feed module | Proposed; run fit-gap before creation | Architecture PR reviewer + product owner |
| D-06 | PostgreSQL vs OpenSearch | PostgreSQL baseline; OpenSearch is a benchmark-gated option | Measured search report + product/operations owner |
| D-07 | PostgreSQL Arabic configuration | Verify actual PostgreSQL 18 catalog; explicitly create/own a config if required | Search implementation |
| D-08 | General product/store/cart/order owner | Not decided; compare current catalog/provider and payments/ledger/dispute capabilities first | Product owner + backend architecture |
| D-09 | Paid lending lifecycle/fees/holds | Not decided; requires workflow and financial/legal decision | Product owner |
| D-10 | Mobile push provider | Not selected; choose only after cost, provider status, security and operational evaluation | Product/operations owner |
| D-11 | Console-driven layout vocabulary | Define schema/registry of approved components; never execute stored code | Product owner + console implementation |
| D-12 | Mobile release enforcement | Define minimum/latest-version contract and rollback policy; keep separate from API versioning | Product/operations owner |
| D-13 | Personalized/learned ranking | Rule baseline first; ML/LTR only when data and evaluation gates pass | Product owner + data/operations |
| D-14 | Community referral requirement | Define whether it is optional reputation, invitation, or an admission requirement; never conflate with identity/residence verification | Product owner |
| D-15 | Target-country legal applicability | Legal review required; no unsupported applicability assumptions | Product owner + qualified counsel |

### Status convention

For each phase use exactly one of:

- **Not started** — no implementation branch.
- **In progress** — scoped branch/PR exists; exit gate not yet met.
- **Blocked** — a dependency, product decision, migration collision, external provider or failing test blocks the next action.
- **Ready for review** — exit evidence exists on exact PR head, awaiting review/checks.
- **Accepted** — all phase gates passed and the owner accepted the result. “Accepted” does not itself mean merged.
- **Merged/deployed** — record the merge commit, deployment commit and smoke-check result separately.

Do not mark a phase completed merely because code exists, local tests pass, an older PR was green, or a source directory is present.

---

## 15. Platform-level completion criteria

The platform is ready for a production rollout of the approved scope only when all of these are demonstrated:

1. An account can hold the approved role combinations and act for its own resources; forbidden cross-resource, cross-neighborhood and unauthenticated actions are rejected.
2. Neighborhood membership/verification, referrals, business ownership verification and official authority are different auditable facts with different consequences.
3. A member creates a question/request/recommendation/poll/lost-found post; it appears in the correct feed and search scope, can be interacted with, reports moderation decisions, and disappears or updates everywhere after withdrawal/expiry.
4. Latest, Following, Trending and For You each have defined eligibility and ranking behavior; reasons are available where personalized ranking is used; actual visible impressions are distinguishable from returned feed candidates.
5. A verified institution publishes an ordinary announcement and, if authorized, an urgent alert. Scope, validity, correction, withdrawal and notification behavior pass end-to-end tests.
6. One product/service source record appears correctly in Marketplace, the related business page and approved community recommendation contexts without duplicated ownership or stale results.
7. Used goods, real estate, vehicles, services and jobs use their approved domain-specific attributes and lifecycle—not only a generic category filter.
8. The complete approved commerce/order journey is idempotent, financially reconciled and recoverable; lending/borrowing is not released until its separate workflow gate is closed.
9. Search is unified across approved source domains, retains geographic/privacy eligibility, passes Arabic tests on the actual PostgreSQL configuration, and falls back honestly when an optional search/AI provider fails.
10. Notification policies work by section/topic/channel/geography and urgency, honor user preferences, and tolerate retries without duplicate delivery. Push is reported as available only after its real provider path passes.
11. Operators can safely reorder/toggle supported UI sections and tune declared runtime data without a software release; invalid configurations are rejected and changes are auditable/rollbackable.
12. Mobile version/update metadata is distinct from API versioning and backend deployment; every advertised release exists and rollback behavior is tested.
13. Modulith boundaries, Flyway checksums/order, OpenAPI compatibility, security scans, full integration tests and the exact-commit CI gate all pass.
14. Privacy, legal applicability, data lifecycle, incident response, observability, backup/restore and rollout/rollback are documented and reviewed for the countries in which the platform will operate.

**Final rule:** optimize for a coherent, trustworthy end-to-end local platform, not a count of modules or endpoints. Every new capability must have one owner, a documented public contract, enforceable permissions, a recovery path, measurable acceptance criteria and a verified journey through the runnable application.
