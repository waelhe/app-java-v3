# Saleor Dashboard Adaptation Plan — Marketplace Administration Foundation

**Status:** Architecture proposal for review; no frontend implementation is claimed by this document.  
**Owner:** Platform product / admin frontend.  
**Reference date:** 15 October 2026.  
**Proposed frontend repository:** waelhe/marketplace-admin (name to confirm when the repository is created).  
**Upstream:** https://github.com/saleor/saleor-dashboard — BSD-3-Clause.  
**Product authority:** [Platform Administration Operating System Product Charter](platform-admin-operating-system-product-charter.md).  
**Domain depth contract:** [Domain-Grade Administration Suites](platform-admin-domain-grade-suites.md).  
**Backend evidence and gaps:** [Platform Administration End-to-End Product Specification](platform-admin-end-to-end-product-spec.md).

## Required governance header

- **[الملف الحاكم]:** Product Charter for admin UX; Domain-Grade Suites for marketplace depth; End-to-End Product Specification for backend evidence and gaps.
- **[النواة المعمارية]:** New React/TypeScript browser application, independently built and deployed; existing Spring modular monolith remains the source of truth for business data and commands.
- **[البند من SYSTEM.md §14.2]:** Documented product/architecture decision with source evidence, compatibility proof and synchronised governance records.
- **[أثر على الحدود]:** No Java module, Modulith dependency, SPI, database schema or migration changes in this proposal. Browser-to-backend transport contracts must be verified before each domain is connected.
- **[دين]:** Yes. A maintained Dashboard fork creates upstream merge/security-maintenance cost. The plan includes an upstream sync policy and explicit stop/continue gates.

---

## 1. Decision

**Use Saleor Dashboard as the code foundation for the new marketplace administration frontend, but do not deploy Saleor's commerce backend and do not pretend the existing Spring API is Saleor-compatible.**

The platform currently consists of a backend and native mobile application; there is no existing production admin frontend to preserve. Create a dedicated frontend repository from a pinned Saleor Dashboard release, run the upstream application unchanged first, then re-engineer its application/data boundaries and domain pages in controlled slices.

The intended result is not a themed demo or a handful of new pages. It is a full commerce-management workspace reusing mature Dashboard navigation, list/detail/editor patterns, filters, bulk-selection interaction, permission-aware route patterns, dialogs, form layouts, error boundaries, loading states and established test infrastructure wherever those can be separated from Saleor's own API semantics.

### Why this path

- Saleor Dashboard is a real, actively maintained React/TypeScript single-page admin application with thousands of commits, an extensive commerce route surface and an established E2E suite. Its repository currently identifies itself as version 3.23.35, Node requirement >=24 and <25, pnpm >=11, and requires a running Saleor instance for normal development. Sources: [repository and README](https://github.com/saleor/saleor-dashboard), [package.json](https://github.com/saleor/saleor-dashboard/blob/main/package.json).
- Its commerce information architecture is materially richer than a new bespoke console: products, product types/attributes, variants, categories, collections, orders, users, shipping, discounts, taxes, translations, staff/permission groups and settings are separate work surfaces. Inspect the [route declarations and permission gates](https://github.com/saleor/saleor-dashboard/blob/main/src/index.tsx).
- The UI is not a backend-neutral admin framework. It is tightly coupled to Saleor's GraphQL schema, generated types/operations, staff permissions, channel semantics, auth lifecycle and API versioning. It will not work against Spring by changing one URL.
- Saleor's official guidance says a fork brings non-trivial upgrades and manual security maintenance; its extension/app mechanism assumes access to a Saleor instance and API. Because this platform has no Saleor backend and needs direct control over the whole market UX, a fork is a deliberate, accepted trade-off—not a claim that forking is low-cost. Sources: [extensibility model and fork guidance](https://docs.saleor.io/developer/extending/overview), [Dashboard extension points](https://docs.saleor.io/recipes/extending-dashboard).

### Technical coupling found in the upstream source

Inspection of the upstream package and route entry point shows why this cannot be treated as a simple API URL replacement:

| Observed upstream fact | Consequence for this adaptation |
|---|---|
| package.json currently identifies Dashboard 3.23.35, Node >=24 <25 and pnpm >=11 | Pin an exact tag and commit, and build against those requirements before changing dependencies. Do not track a moving main branch as the product baseline. |
| Runtime dependencies include React 18.3.1, React Router / React Router DOM 5.3.4, Apollo Client 3.4.17, GraphQL 16.11.0, and Saleor's Macaw UI packages | Record these in the baseline dependency audit. Do not upgrade the router, Apollo, React or design system in the same change that replaces the data layer; evaluate upgrades as separate migrations with their own tests. |
| Pre-development/pre-build commands run GraphQL generation; scripts fetch the stable release schema and Saleor's main-branch schema and generate separate artifacts | The platform build must cease fetching or generating against Saleor schemas. Its generated types must come from the actual Spring OpenAPI/GraphQL contracts. |
| The route tree guards numerous pages with Saleor's PermissionEnum and uses Saleor page modules | Map routes to platform capabilities and owner domains; do not translate permission names mechanically. Route files need an inventory before deciding which components can remain unchanged. |
| The official E2E runner starts Saleor in Docker, seeds it, builds a dashboard bundle and exercises it in a browser; main and stable are separate targets | This is valuable test infrastructure, but it cannot prove a Spring-backed journey. Preserve component tests, then add platform contract/E2E suites and replace the upstream E2E target deliberately. |

Evidence: [package.json](https://github.com/saleor/saleor-dashboard/blob/main/package.json), [source route tree](https://github.com/saleor/saleor-dashboard/blob/main/src/index.tsx), and [upstream E2E instructions](https://github.com/saleor/saleor-dashboard#testing).

**Implication:** reusing Saleor's code is still preferable to drawing a weak custom console, but the real reusable asset is the mature admin product surface and its interaction design—not its GraphQL data contracts. The proof-of-fit must measure the number of route files and operations bound to Saleor before committing to the cost of a long-lived full fork. If the first complete listing journey requires invasive changes across most shared core files, stop and compare selective extraction of the shell/components against continued fork maintenance before migrating every route.

## 2. Non-goals and non-negotiable constraints

1. **No Saleor backend, Saleor database, or second commerce source of truth.** Spring/domain owners remain authoritative.
2. **No fake Saleor GraphQL compatibility layer.** Do not implement hundreds of Saleor-shaped fields and mutations only to keep generated Dashboard operations compiling. Define contracts around this platform's business concepts.
3. **No direct browser-to-database access.** The admin web app uses authenticated, authorized platform APIs only.
4. **No business logic in React.** The frontend may validate for usability, compute previews when safe, and orchestrate screens; authorization, lifecycle transitions, money, inventory, moderation, tenancy/geographic scope and idempotency are enforced by the backend owner.
5. **No one-to-one mapping of every Saleor concept.** A Saleor Channel is not automatically a neighborhood; a Saleor Variant is not automatically a property, job, car or appointment; Saleor Shipping is not automatically local service availability.
6. **No claim of feature parity because a menu item exists.** Each workflow must pass its end-to-end contract and failure/recovery tests.
7. Keep the upstream BSD-3-Clause license/copyright and required notices. Do not use Saleor's name or marks to imply endorsement. Legal review is required before distributing a derivative build.

## 3. Options considered

| Option | Advantages | Why it is or is not selected |
|---|---|---|
| Official Saleor Dashboard extension/app | Keeps the upstream dashboard untouched and is the preferred extension mechanism when Saleor is the backend | **Not a fit as the primary integration:** external apps run in the Saleor Dashboard and communicate through Saleor APIs/webhooks; our backend is Spring and no Saleor server is being adopted. It could be revisited only if the architecture changes to use Saleor as a commerce engine. |
| New admin from scratch, using Saleor only as inspiration | Clean architecture and no inherited Saleor API coupling | Reject as the first choice because it discards mature screens, component patterns and existing interaction/test assets despite no current frontend legacy to protect. Reconsider if the proof-of-fit shows the fork has too much coupling to salvage. |
| Copy selected components into a fresh app | Less long-term fork drift; potentially a smaller codebase | Not the initial path: it requires manually reconstructing the mature product-management workbench and its behavior. May become the eventual extraction strategy for reusable primitives. |
| **Dedicated maintained fork, replacing data/domain seams** | Preserves mature starting code while allowing complete ownership of the product experience | **Selected**, with a time-boxed proof-of-fit and explicit upstream-maintenance gates. |

## 4. Target architecture

    Native Android app ───────────────┐
                                      ├── Existing Spring platform (single source of truth)
    New Admin SPA (Saleor-derived) ───┘      ├── identity / scoped authorization
            │                                 ├── catalog / providers / availability
            ├── domain workbenches            ├── orders / booking / pricing / payments
            ├── shared admin UI primitives    ├── realestate / jobs / reviews / media
            └── typed Admin API client         └── community / geo / institutions / audit
                         │
                         └── existing documented REST/OpenAPI contracts by default
                             (use an existing GraphQL contract only where it already
                              represents the required admin use case and is verified)

The new frontend is an independently built and deployed repository; it is not a Maven module. It may use a same-origin reverse proxy or a tightly configured admin origin, but it must not introduce wildcard CORS or share secrets with the Android client.

### Admin API transport choice

**Default to the backend's existing REST/OpenAPI contracts for admin commands and reads.** At each domain, inspect the actual API specification and endpoints before choosing transport. If a current Spring GraphQL schema already provides a complete, permission-safe admin read model for a specific screen, it may be used there; it must not be expanded solely to imitate Saleor's schema.

Generate or maintain typed frontend contracts from the actual backend OpenAPI/schema. The frontend adapter maps those transport DTOs into screen models; it must not become a second domain model or hide unsupported capabilities. If the contract is missing, register a backend gap against the owning domain and make the screen's unsupported operation explicit until the server capability exists.

### Proposed frontend boundaries

    src/
      app/                  # bootstrap, router, error boundaries
      shell/                # navigation, global search, scope, notifications
      design-system/        # proven reusable Saleor-derived UI primitives
      platform-api/         # generated transport types, API client, error mapping
      auth/                 # admin session integration and permission context
      capabilities/         # capability catalog; no invented feature flags
      domains/
        market-home/
        catalog/
        taxonomy/
        variants-inventory/
        providers/
        orders/
        service-bookings/
        real-estate/
        vehicles/
        jobs/
        pricing-promotions/
        reviews-quality/
        search-merchandising/
        market-configuration/
        analytics/
      operations/            # bulk-operation progress, exports/imports, audit links
      test/                  # contract fixtures, route and journey helpers

This is a target boundary map, not a demand to move every upstream file in one giant refactor. Preserve the original path structure at first; create these boundaries as each route is migrated and tested. Extract generic components only when at least two real workbenches use them.

## 5. What to retain, replace, remove and add

### Retain initially (verify individually)

- React/TypeScript/Vite build and the established lint/type-check pipeline.
- App shell and mature navigation/layout behavior.
- Reusable table, filters, pagination, selection, modal/drawer, form, date/number/price inputs, media presentation, empty/loading/error states and context-preserving navigation patterns.
- Visual design tokens/components and storybook fixtures where they are not tied to Saleor data semantics.
- Error-boundary approach, accessible interaction patterns, localization infrastructure, route/component test helpers and Playwright E2E scaffolding.
- Saleor's original license and a clear record of upstream provenance.

### Replace behind a seam

- Saleor GraphQL operations, generated types, schema fetch/codegen, Apollo/client cache assumptions and hooks coupled to Saleor object lifecycles.
- Saleor staff login/session handling and its token lifecycle; this admin app must use the platform's approved administrative auth client/session flow, not invent a second identity store.
- Saleor permission enums and route guards with the platform's explicit capability/permission contracts and geographic/provider scopes.
- Channel selectors and channel-specific pricing/visibility logic with the platform's actual market scope model.
- Saleor-specific media upload, file handling, exports/imports, mutation status and error semantics wherever platform APIs differ.
- Saleor-specific configuration/version warnings and assumptions about connecting to stable/staging Saleor schemas.
- Product detail data and mutations, one route at a time, with domain-specific adapter contracts.

### Remove only after the replacement route is proven

- Routes that exist solely for Saleor-only business functions with no approved product requirement (for example gift cards, Saleor plugin management, Saleor's own staff-user model, or native Saleor tax/shipping configuration when the platform has no corresponding owned capability).
- Runtime fetching of Saleor's main/staging GraphQL schema for the new product build. Generated code must be derived from the platform's actual contracts.
- Default navigation entries that expose unfinished or unsupported operations.

Do not delete generic components just because their first consumer was a Saleor page. Prove they are either reusable or obsolete.

### Add

- Admin-specific authentication and scope resolution.
- Platform capability catalog: action identifier, owner domain, API contract/version, required permission, allowed scope, risk class, supported/unsupported state, rollout state, and verification method.
- A single typed error model for field validation, forbidden/scope denial, stale version, conflict, rate limit, partial failure, timeout and unknown command outcome.
- Durable bulk/import/export workflows only where backend contracts support asynchronous work and per-row results.
- Entity links and audit context joining products/listings to providers, bookings/orders, payments, reviews, reports and member-visible publication state.

## 6. Mapping Saleor's mature work surfaces to our market

These mappings are deliberate adaptations, not a promise that the backend supports every capability today.

| Saleor work surface/pattern | Keep from the Dashboard | Platform-owned implementation target | Important non-equivalence |
|---|---|---|---|
| Products overview and product detail | List/detail layouts, filters, saved query UX where present, bulk selection, detail tabs and editor patterns | Unified market listings with distinct type-specific views backed by catalog and the actual vertical owners | Our market listing is an umbrella experience; property, job, vehicle and service records are not all retail products. |
| Product types and attributes | Schema-driven form and attribute-management patterns | Category/type definitions with required fields, validation, localized labels and safe taxonomy migrations where supported | Do not imply arbitrary dynamic schemas if backend storage/validation does not support them. |
| Variants and SKUs | Variant matrix, option editor and bulk variant workflow for appropriate goods | Retail goods and used-goods variants only where the domain supports them | A property has rooms/area/features; a car has make/model/year/mileage; a service has duration/area/availability. These require typed vertical forms. |
| Categories and collections | Separate category hierarchy from curated collection UX | Canonical listing taxonomy and separately managed curated/featured groups if the backend supports both | Do not implement a second taxonomy in local storage or hard-code categories in the UI. |
| Inventory and warehouse | Stock table, locations, low/out-of-stock views, reservation patterns | Existing inventory owner/market contract if proven; otherwise a documented backend gap and explicit roadmap | Appointment capacity and property availability are not retail stock. |
| Channels | Scoped visibility and context-aware price/availability patterns | Platform scope selector built from actual country/city/neighborhood, vertical, provider/business, locale/currency and member audience dimensions as applicable | A scope selector filters data but never grants authorization. A neighborhood is not a Saleor Channel by default. |
| Orders and checkout | Order list, detail timeline, line-item view, safe action confirmations | Orders owner and current payment/booking contracts; cross-linked transaction state | Do not claim a refund/fulfilment succeeded until the owning service confirms it. |
| Discounts/promotions | Rule builder patterns, active/scheduled/expired states and impact preview | Pricing/promotion owner and separately governed ads/revenue rules | Do not import Saleor's commission or promotion assumptions. The product's existing money decisions remain authoritative. |
| Customers | Customer search/detail and activity-history layout | Platform identity/member 360 view with privacy-safe joins | Identity and membership remain platform-owned; no Saleor customer table. |
| Staff and permission groups | Permission editor ergonomics and explanation of unavailable actions | Platform permission catalog, geographic/provider scope, separation of duties and auditable approval | Never translate Saleor permission enums to Spring roles by string substitution. Backend rechecks every action. |
| Reviews | Moderation/detail/action patterns | Existing reviews owner plus report and seller context | Ratings, reports, replies and moderation actions have distinct semantics. |
| Shipping/tax/configuration | Mature forms and validation patterns where reusable | Only corresponding platform-owned features and verified APIs | Remove rather than simulate settings that the backend cannot execute. |
| Search/merchandising | Filters, result analysis and configurable collection patterns | Existing search owner; zero-result and quality tooling only once measured data exists | A UI control must correspond to a tested ranking/configuration contract. |

### First vertical and full product target

The first proof-of-fit should connect one complete goods workflow, but the final market IA must include every required vertical from the outset. Staging delivery is not permission to hide product requirements.

1. **Goods and used goods:** listing/catalog, images, type/category, price, condition, provider, stock/availability where supported, review/publication and member-visible verification.
2. **Services and bookings:** provider, service type, location/service area, price, duration/capacity/availability, booking policy and link to booking lifecycle.
3. **Real estate:** property type, sale/rent, location, dimensions, room counts, amenities, price/lease terms, listing proof, status and viewing/contact journey.
4. **Vehicles:** make/model/trim, year, mileage, condition, price, location and required proof, with type-specific validation.
5. **Jobs:** employer/provider, role type, work arrangement, location, compensation/range, closing date and application/contact path.
6. **Orders/payments and post-transaction:** fulfilment, cancellation, refund/dispute, provider and member communication only through the existing owning domain contracts.

The first implementation wave should make **goods listing create/edit/publish fully real end-to-end**, then expand the same mature interaction foundation to the other verticals. Do not release a surface with demo data as if it is connected.

## 7. Scope model: adapt Channels without copying their semantics

Saleor Channels scope products, variants, checkout, payment methods, tax, discounts and orders in defined ways. The platform has a different locality/member/provider model. Build a typed **Market Scope** selection from the actual backend, with only dimensions that exist and are authorized:

- Country/city/neighborhood (geographic scope).
- Vertical and category.
- Provider/business/store when relevant.
- Locale and currency when supported.
- Audience/member segment or client version only if backed by real server-side eligibility.
- Publication state and effective date/window where the owner supports them.

The UI must show the active scope at page and action level, preserve it during navigation, and preview the number and identity of affected records before bulk changes. Geographic selection is a filter, not an access grant. Every API call is authorized server-side for both action and target scope.

Do not create a general-purpose “channel” table in the UI just to match Saleor. If a first-class market-channel domain later proves necessary, define its semantics in the platform product model and owning backend before exposing it.

## 8. Admin API contract required before connecting a route

For every migrated work surface, maintain a contract sheet with:

1. Backend owner and authoritative source record.
2. List/query endpoint, stable pagination, filters/sorts and total/count semantics.
3. Detail projection and allowed linked records.
4. Create/update command, required fields and state transition rules.
5. Permission and scope requirements enforced by the server.
6. Optimistic concurrency/version token where stale edits are possible.
7. Idempotency key and duplicate-submit behavior for consequential commands.
8. Error/problem response contract, including validation fields and conflicts.
9. Async state, timeout and unknown-result recovery where applicable.
10. Audit event, actor, reason, before/after summary and command outcome.
11. Integration/E2E test that executes through the owner and proves the actual result.

The admin client maps errors to explicit states:
- Validation: highlight field(s); preserve input.
- Forbidden/out-of-scope: explain access restriction without leaking protected data.
- Version conflict/stale form: offer reload and comparison; never overwrite blindly.
- Rate limited/network timeout: retain context; retry only when safe.
- Unknown money/publish outcome: query the source state before retry; never repeat a non-idempotent action blindly.
- Partial bulk result: show successful, failed, skipped and still-running row counts with a resumable operation ID if the backend supports it.

The frontend never writes domain tables, calls another module's private API, or guesses state from a successful HTTP status alone.

## 9. Authentication, permissions and deployment

- Treat the admin website as its own client, separate from Android. Do not reuse a mobile public client identifier as a substitute for an admin client, and never embed a client secret in the SPA.
- Integrate with the platform's approved identity/session flow after reviewing the current auth redesign and available admin endpoints. Do not preserve Saleor's login screen/session code by default.
- Keep client-side permission gates for understandable navigation only. All requests/commands need server-side role + action + entity + geographic/provider scope checks.
- Distinguish read, edit, publish, bulk, import, export, moderation, seller verification, refund/payment, settings and permission administration where the domain risk warrants it.
- High-impact actions require target/current/proposed state, affected scope, reason, approval if policy requires it, and a verified result.
- Use a dedicated deployment/service for the frontend. Configure the API origin explicitly; prefer same-origin reverse proxy where operationally appropriate, otherwise use narrow CORS allowlists and correct cookie/CSRF or bearer-token policy for the selected auth flow. Never mix admin and Android credentials.
- The first setup must target a non-destructive test fixture/environment. Do not test bulk publication, account restriction, refund or deletion flows against production data.

## 10. How to perform the fork and re-engineering

### Phase 0 — Establish a reproducible upstream baseline

- Create a new frontend repository from a verified Saleor Dashboard release, not a copy/paste of screenshots and not inside the backend Maven reactor.
- Preserve upstream LICENSE/copyright and record exact tag, commit SHA, package version and download/source provenance in UPSTREAM.md.
- Run the official Node/pnpm version, install, typecheck, unit/component tests, build, and existing Playwright E2E suite unchanged against a disposable Saleor instance as described by upstream documentation.
- Save build output, route inventory, dependency inventory, test counts and bundle baseline.
- Do not start product restyling or API substitution until baseline is repeatable.

**Gate 0:** exact upstream code builds and its existing tests pass (or each upstream defect/blocked environment is documented). If this gate fails, do not assume later failures belong to our adaptation.

### Phase 1 — Draw an explicit upstream boundary and product shell

- Create a visible upstream remote/branch for the source repository and an adaptation branch for our product.
- Introduce a small adapter boundary for app config, current admin identity, permission/capability checks, active scope, API errors and telemetry.
- Keep the existing route/component layout at first; do not mass-rename files or replace GraphQL hooks in one sweep.
- Replace brand, navigation labels, product terminology and app configuration only after identifying dependencies on Saleor runtime state.
- Remove Saleor-dependent sections from the user-facing navigation only after recording the matching platform requirement and decision.
- Define a route registry with required permission/capability, owning domain, readiness status and E2E journey ID.

**Gate 1:** shell starts without connecting to Saleor; it clearly displays connection/auth state and cannot expose a fake operational action.

### Phase 2 — Replace identity and permission semantics

- Remove dependence on Saleor staff tokens and its PermissionEnum as the authorization source.
- Define the admin client contract against the platform's supported sign-in/session and permission APIs; align it with current security decisions before implementation.
- Map the platform's explicit permission catalog and scopes to route/action visibility.
- Add tests for anonymous, wrong role, correct role/wrong geography, read-only, expired session and revoked access.
- Re-check authorization after record load and before every consequential command.

**Gate 2:** no Saleor identity/role is required; denied actions are blocked by the backend even when a request bypasses the UI.

### Phase 3 — Replace the Saleor data layer with the platform API client

- Stop fetching Saleor main/staging schemas to build the new product.
- Use the backend's real OpenAPI as the preferred REST type source for existing endpoints; use an existing backend GraphQL schema only where its admin operation is already supported and verified.
- Add one typed platform client and error normalization policy. Keep transport-specific code out of React pages.
- Use mock fixtures only for component tests; real journeys run against the platform backend and real migrations.
- Add source-owner mapping to every route/query/command. Unsupported controls remain absent/explicitly unavailable.

**Gate 3:** one read-only market route displays live test data from the correct platform owner, obeys scope restrictions and passes contract tests.

### Phase 4 — Vertical slice: goods listing from start to outcome

Implement one full workflow:
1. Open catalogue with stable paging, search, filters, counts and useful empty/no-result states.
2. Open a listing detail with its provider and lifecycle timeline.
3. Create draft with typed field validation and media upload via the actual media owner.
4. Edit/save with stale-version conflict protection.
5. Preview member-facing presentation and publication scope.
6. Submit/review/publish only through the source-owned command.
7. Re-query source status and verify the published listing appears in the real discovery/search surface when its eligibility rules say it should.
8. Record actor/reason/outcome; test permission denial, validation failure, timeout, duplicate submit and recovery.
9. Prove that an unpublished or out-of-scope listing is not exposed to an unauthorized audience.

**Gate 4:** a real API-backed end-to-end journey passes without Saleor backend or hard-coded demo records.

### Phase 5 — Fill the full commerce workbench

Migrate the required surface families in dependency order:
- Taxonomy/type/attribute administration.
- Goods variants and inventory, if supported by the owning APIs.
- Provider/seller management and verification.
- Services, availability and booking management.
- Property, vehicles and jobs with typed, vertical-specific forms.
- Orders, payments, fulfilment, booking changes, cancellations, returns and refunds.
- Pricing/promotions only to the extent the platform's pricing owner provides valid commands.
- Reviews, listing quality, search/merchandising reports and market performance.
- Market configuration, permissions, audit, bulk jobs, imports/exports and saved views.

Keep domain ownership intact: catalog owns its catalog records; real estate owns property records; jobs own jobs; availability/booking owns appointments; orders/payments own their lifecycles. A shared admin screen may compose these sources through explicit API projections, but it must not merge their state machines or database ownership.

**Gate 5:** the full market route inventory is mapped to COMPLETE/PARTIAL/GAP/NOT SAFE; no major work area is represented solely by a static page.

### Phase 6 — Restore upstream fixes and test regression

- Maintain an upstream sync record: release/tag, commit SHA, upstream fixes reviewed, fixes applied/cherry-picked, conflicts, deferred items and owner.
- Sync on each relevant upstream security release and at a regular release review. Do not blindly merge a new upstream release into heavily rewritten API files.
- Prefer small, isolated adaptation seams around API/auth/permissions so generic upstream fixes merge cleanly.
- Run license/source notices, dependency vulnerability checks, typecheck, unit/component tests, E2E journeys, accessibility/RTL checks and API contract tests before any release.

**Gate 6:** an upstream security fix can be evaluated and applied without silently breaking platform admin permissions or business state transitions.

## 11. Upstream maintenance policy

Official Saleor guidance warns that maintaining a fork requires manual compatibility and security work. Therefore:

- Keep the exact upstream revision pinned and a machine-readable list of changed/upstream-derived files.
- Avoid changing upstream UI primitives when a wrapper/adapter can satisfy the platform contract.
- Group platform-specific routes under clearly named domain directories and keep API/permission/scope logic behind seams.
- Review upstream security advisories and releases; security fixes are not deferred merely because a merge is inconvenient.
- Track divergence: commits behind upstream, conflicted files, tests lost/changed, and unresolved high/critical vulnerabilities.
- Reconsider selective extraction into a new app if core files conflict across repeated updates or upstream changes become impractical to apply.

**Stop/continue review:** after the first complete goods workflow, measure how much source code had to be changed to remove Saleor API assumptions, how many route files remain tightly coupled, whether upstream E2E can remain useful, and the ongoing cost of sync. If the fork proves too coupled, pivot to extracting the verified reusable shell/components instead of continuing a blind fork.

## 12. Acceptance gates and definition of done

A migrated screen is not complete until all applicable conditions pass:

- Its navigation, permissions, scope, owner and journey ID are recorded.
- Lists support required filters, sorting, stable paging, empty/no-match/error/stale states, and preserve context returning from detail.
- Create/edit operations use source contracts, validate fields, handle duplicate/conflicting/stale submissions and preserve draft input on failure.
- Bulk actions preview target count and samples and provide per-item outcomes; unsupported bulk operations do not appear.
- Server authorization is verified for correct and incorrect roles/scopes.
- Consequential writes record actor, reason, affected scope and actual result in the source audit/event contract.
- Unknown outcomes are reconciled from the source before retry.
- A Playwright E2E test proves the workflow using the actual platform backend, not only mocked GraphQL responses.
- The native Android app sees the same underlying business state; there is no separate Saleor-only catalog.
- Keyboard access, screen-reader semantics, RTL Arabic, LTR German, date/currency formatting and responsive admin usage are tested.

## 13. Risks and mitigation

| Risk | Mitigation / stop condition |
|---|---|
| Saleor API is deeply entangled with pages | Replace one vertical slice first. If most source pages require rewrite, evaluate selective extraction against fork cost before broad migration. |
| Upstream security updates become hard to merge | Pin source; isolate adapters; require regular upstream review; maintain a conflict ledger and security deadline. |
| Saleor's data model distorts local marketplace verticals | Use it for interaction patterns and relevant goods workflows, not as the canonical domain model for real estate/jobs/services. |
| UI appears capable but backend lacks an operation | Capability catalog + API contract gate; no fake toggles or optimistic success. |
| UI permission gating is bypassed | Server-side authorization and negative tests for every consequential command. |
| Orders/refunds repeat after timeout | Idempotency + source reconciliation; unknown outcome is a separate state. |
| Bulk action damages many local records | Dry-run, immutable selection snapshot/hash, limits, row-level outcomes, approval where required. |
| Fork exposes Saleor branding as endorsement | Retain license notices but rebrand; no implication of endorsement. |
| Admin and mobile auth paths become conflated | Separate clients and session policy; no client secret inside SPA. |

## 14. First implementation backlog (ordered, not yet implemented)

1. Create the dedicated admin repository from the selected official Saleor Dashboard tag and record its source SHA/license.
2. Prove unmodified build and tests with a disposable upstream Saleor backend.
3. Write a route-to-platform-capability inventory from the exact pinned source tree.
4. Audit Spring OpenAPI/admin endpoints against the goods-listing E2E workflow, including auth, media, scope, audit and publication/discovery.
5. Define the smallest typed Admin API contract needed for that slice; classify missing endpoints as backend gaps rather than inventing client behavior.
6. Implement the platform adapter seam and a live read-only product/listing route.
7. Complete create/edit/review/publish/read-back E2E and only then move to the next commerce work surface.
8. Revisit fork sustainability after the complete slice; record go/no-go with measured code churn and test results.

## 15. Sources reviewed

- [Saleor Dashboard repository](https://github.com/saleor/saleor-dashboard) — React/TypeScript single-page admin app, prerequisites and E2E context.
- [Saleor Dashboard package.json](https://github.com/saleor/saleor-dashboard/blob/main/package.json) — version, Node/pnpm requirements, build, type-check and test scripts.
- [Dashboard route tree](https://github.com/saleor/saleor-dashboard/blob/main/src/index.tsx) — route and permission examples.
- [Dashboard configuration](https://github.com/saleor/saleor-dashboard/blob/main/docs/configuration.md) — API/schema config and generated schema expectations.
- [Official Dashboard extension guide](https://docs.saleor.io/recipes/extending-dashboard) — navigation/detail/list extension points and fork choice.
- [Official Saleor extensibility model](https://docs.saleor.io/developer/extending/overview) — extension architecture and explicit warning about fork/upgrade/security maintenance.
- [Products overview](https://docs.saleor.io/developer/products/overview) — product types, variants and merchandising concepts.
- [Channels](https://docs.saleor.io/developer/channels/overview) — exact channel scoping behavior and non-ideal cases.
- [Checkout](https://docs.saleor.io/developer/checkout/overview) — ownership/permission requirements for checkout data.
- [Domain-Grade Administration Suites](platform-admin-domain-grade-suites.md) — platform-specific market and vertical target.
- [End-to-End Product / Backend Gaps](platform-admin-end-to-end-product-spec.md) — current source owners, admin API gaps and testing requirements.

---

## Honest implementation status

This is an architecture and migration plan only. It does not claim that a Saleor fork has been created, that the Dashboard builds against Spring, or that any market management workflow is already live. Those facts require the dedicated repository to exist and the gates above to pass.


## Repository baseline synchronization — 17 October 2026

This proposal is based on the current repository line after GraphQL Phase 5 published the accepted SDL contract and connected Apollo Kotlin code generation on Android. That repository baseline does not change the architectural boundary: Spring remains the sole business-data and command authority, and the Saleor-derived dashboard remains a proposed independent admin frontend until a pinned upstream build and a complete goods-listing journey have passed the proof-of-fit gates above.
