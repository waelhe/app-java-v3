# Domain-Grade Administration Suites — Product Depth and Screen Contracts

**Status:** Product target and screen contract; not implementation evidence.  
**Owner:** Platform product/UX and domain owners.  
**Applies to:** Market and business directory, community and trust, members/neighborhoods, official publishing, money/growth and member experience.  
**Reference date:** 15 October 2026.

## 1. Product decision: a destination is not a management suite

A top-level navigation item is only an entry point. It must not be mistaken for a completed domain experience.

The prior prototype treated “Market & Businesses” and “Community & Trust” as summary screens with a few shortcuts. That is insufficient. A director or domain manager expects a real operating surface: overview, complete work areas, entity lists, filters, saved views, detail workspaces, create/edit flows, lifecycle operations, bulk tools, domain configuration, permissions, history, reporting, exceptions and recovery.

The target is **domain-grade depth**:
- Commerce administration must reach the operational breadth and interaction maturity of Saleor Dashboard, while also handling a multi-provider local marketplace with goods, services, real estate, cars, jobs and bookings.
- Community administration must reach the breadth of mature community platforms such as Discourse, plus neighborhood-local governance, geographic membership, resident/institution authority, official local communications, events and local recommendations.
- The business directory must reach the operating depth expected from Yelp for Business: business profile and claim/verification, review/reply workflows, reputation, leads, visibility and measurable customer actions.
- Other domains must follow the same rule: no major area is considered implemented merely because one dashboard exists.

This is a target for capabilities and quality of workflows, not a claim that our current backend already supplies these capabilities. Each capability must be mapped to an existing verified contract, a required adapter contract, or an explicit product/backend gap.

## 2. Reference evidence and interpretation

### 2.1 Saleor Dashboard and commerce administration

The official Saleor product overview treats products, product types and attributes, variants, per-channel availability/prices, inventory and warehouse assignment, tax classes, categories/collections and media as separate concepts. Variants carry SKU-level price and stock behavior; categories and collections have different merchandising purposes. [Saleor product overview](https://docs.saleor.io/developer/products/overview)

Saleor channels are not just a display toggle: the selected channel can affect catalog visibility, variant price/availability, taxes, vouchers, promotions, checkout and access to orders. [Saleor channels](https://docs.saleor.io/developer/channels/overview) [Saleor checkout](https://docs.saleor.io/developer/checkout/overview)

The Dashboard source exposes dedicated routes for products, product types, customers, customer types/attributes, orders, gift cards, discounts, shipping, taxes, translations, staff/permission groups, site settings, and other operations. The official extensibility model allows additions in navigation, list-level actions and record-detail widgets while binding extensions to permissions. [Saleor Dashboard source](https://github.com/saleor/saleor-dashboard/blob/main/src/index.tsx) [Dashboard extensions](https://docs.saleor.io/recipes/extending-dashboard)

**Design consequence:** Saleor-level maturity cannot be approximated by one “market” page. It requires a catalogue of distinct workflows with consistent list/detail/edit/action patterns. We should adopt the depth and compositional patterns, not blindly fork Saleor or force our real-estate/service/neighbor marketplace into a retail-only model.

### 2.2 Medusa confirms that everyday commerce control should be usable without technical knowledge

Medusa's official Admin guide explicitly targets admin users and merchants who need not know the technical internals. It provides separate management paths for products, options/variants, collections/categories, inventory items, stock locations, reservations, price lists and related settings. Its product creation flow includes variant editing, SKU, inventory handling, regional/currency prices and optional inventory kits. [Medusa Admin guide](https://docs.medusajs.com/user-guide) [Products](https://docs.medusajs.com/user-guide/products) [Inventory](https://docs.medusajs.com/user-guide/inventory) [Price lists](https://docs.medusajs.com/user-guide/price-lists)

**Design consequence:** The interface must be able to explain and safely guide the full operational task, not expose a record form without context.

### 2.3 Discourse provides domain depth beyond moderation buttons

Discourse's 10 August 2026 redesign organizes its admin dashboard around the question “How is my community doing?”, not an assortment of unrelated charts. It provides configurable sections, clear KPI definitions, traffic analysis, pinned reports, engagement and trust-level progress, content/category breakdowns, search-result success, and support resolution metrics. It links overview metrics to detailed reports. [Discourse Admin Dashboard, 10 August 2026](https://blog.discourse.org/2026/08/introducing-the-all-new-admin-dashboard/)

Discourse's review-queue model and category/tag/group administration provide separate controls for content review, categorization, tag groups, automatic tagging and scoped moderation. [Review queue](https://meta.discourse.org/t/a-new-review-queue-layout-with-all-new-features/388194) [Categories](https://meta.discourse.org/t/create-a-category-in-discourse/197224) [Tags](https://meta.discourse.org/t/admin-guide-to-tags-in-discourse/121041)

**Design consequence:** Community management needs its own operating suite covering content, people, structure, participation, safety, discovery and community health—not just a report queue.

### 2.4 Yelp for Business provides a useful business-directory maturity benchmark

Yelp for Business distinguishes exposure from customer interest. Its documented metrics include business-page visits and leads such as calls and website visits, with explicit de-duplication rules for repeated actions. [Yelp for Business leads](https://www.yelp-support.com/article/How-do-you-measure-User-Views-and-Customer-Leads-on-Yelp-for-Business?bui=null&l=en_GB)

**Design consequence:** Directory administration must operate business profiles, ownership claims, reviews and owner responses, reputation and listing completeness, customer intent/lead measurement and local visibility. “Manage businesses” as one list is not a Yelp-level product.

### 2.5 Important limits of the references

The references are evidence of product depth, not permission to copy every feature or implementation. Saleor is commerce-centred, Discourse is community-centred, and Yelp is directory-centred. Our platform combines geographic membership, neighborhood governance, multi-provider marketplace, local business discovery, official information, real-world services and member trust. These capabilities must join at shared canonical records and member journeys, not be duplicated in separate subsystems.

## 3. Domain maturity contract — required for every suite

A domain is not accepted as a complete management experience until it has the following, where applicable:

1. **Domain landing/workbench:** domain goals, exceptions needing decisions, key outcomes and links into relevant work.
2. **Real collection screens:** searchable lists, column configuration, filters, sorting, pagination, selection, bulk actions, saved views and safe import/export where suitable.
3. **Record detail workspace:** summary, lifecycle/status, related entities, activity timeline, internal notes, files/evidence, prior versions, permissions and contextual actions.
4. **Create/edit journeys:** guided or multi-step flows where necessary; field-level help; conditional fields; validation; unsaved-change protection; duplicate/conflict detection; save-draft and publication semantics.
5. **Lifecycle and exceptions:** clearly defined states, permitted transitions, reason codes, recovery, retries and escalation. Invalid actions explain what blocks them.
6. **Contextual bulk operations:** selected/included/excluded counts, sample result, impact preview, row-level failures, resumable execution and audit; a selection never silently executes.
7. **Domain configuration:** taxonomies, attribute/schema-like forms translated into business language, templates, policies, automation and defaults.
8. **Locality and eligibility:** country/city/neighborhood, channel, provider, language, member segment and app-version scope whenever relevant.
9. **Role-scoped permissions:** view, edit, approve, publish, refund/enforce, export and administer are separate permissions where risk warrants it; row and geographic scope are enforced server-side.
10. **Outcome analytics:** metric definitions, time range, baseline/target, breakdowns and drill-down to the underlying records or journey; stale/unknown data is not displayed as success.
11. **Audit and revision history:** who did what, when, why, affected scope, before/after and observed result.
12. **Operational states:** loading, empty, no matches, stale source, partial failure, lost permissions, conflict, timeout, unsupported action and recovery path.
13. **Localization and accessibility:** RTL/LTR, language/currency/date formatting, accessible tables/forms, keyboard paths, screen-reader labels and mobile-responsive use by staff.
14. **Context-preserving navigation:** opening a record from a queue and returning restores the exact query, saved view, sort, selection and scroll position.
15. **Verified action feedback:** distinguish requested, accepted, in progress, completed, failed and unknown outcomes. A toast alone is not proof of completion.

These are cross-domain contracts. A domain-specific UX may choose the right layout, but cannot omit them merely to ship faster.

## 4. Market & commerce suite — Saleor-level breadth plus marketplace depth

The main “السوق والأعمال” destination opens a specialized commerce navigation. It is not a single dashboard. The suite comprises the workspaces below.

### 4.1 Market home / operations overview

The home answers: Can members find what they need, can providers fulfil demand, and where are money or trust risks growing?

It includes configurable KPI panels for listing completeness, search success, zero-result searches, available supply by category/locality, seller response, order/booking completion, cancellations, returns, disputes, refunds, review/complaint trends and provider activation. Every panel has definitions, date range, geography, channel, comparison, freshness, and a drill-down.

Separate priority queues cover: incomplete listings, rejected/expired listings, missing inventory/availability, late provider responses, stuck orders/bookings, payment uncertainty, overdue fulfilments, return/refund review, reported listings, suspicious seller behavior and provider verification. Each exception has owner, target and next action.

### 4.2 Catalog: products, listings and services

**Collection view**
- Search by title, listing ID, SKU/reference, provider, category, locality and status.
- Filter by product kind, provider verification, publication status, completeness, availability, moderation, price range, expiry, channel, and last-updated period.
- Saved views such as “Needs content”, “Awaiting review”, “Expires soon”, “Out of stock”, “Reported”, “Missing price/availability” and “Published in Berlin”.
- Configurable columns, sorting, paging, bulk selection and CSV/approved import/export.
- Bulk actions are previewed first and include per-row success/failure.

**Listing/product editor**
- Identity, title, description, media and alt text, localized content, owner/provider, category, tags, condition, price/currency, tax/fee presentation where applicable, locality/delivery area, availability, listing dates, contact/conversion path, trust status, SEO/public presentation, policy disclosures and moderation notes.
- Preview the real member-facing card and detail view in Arabic/German and appropriate RTL/LTR states.
- Validation explains exactly what prevents publication and offers field-level fixes.
- Duplicate listing suggestions and an immutable edit/activity timeline.
- Lifecycle: draft → completeness review → moderation (when required) → scheduled/published → paused/sold/fulfilled/expired → renewal/archive; transitions and terminal states must be defined per listing type.

### 4.3 Product types, categories, attributes and taxonomy

Dedicated management areas must exist for:
- Hierarchical categories and subcategories.
- Product/listing types and required fields by type.
- Reusable attribute definitions and allowed values.
- Variant options and SKU/reference rules for applicable goods.
- Collections/curated lists independent from the main category tree.
- Tags, synonyms, localized names, searchable aliases and deprecation/merging.
- Service vertical templates: professional services, appointments, jobs, vehicles, real estate, lending/used goods and local goods.

Managers can see how changes affect existing records, search filters and member navigation before publishing taxonomy changes. A category merge/re-parent operation must preview affected listings, saved searches, pages and navigation references, with conflicts and rollback/compensation semantics.

### 4.4 Variants and bulk editing

For applicable goods, a dedicated variant matrix supports option dimensions such as size/material/color, per-variant identifier/SKU, images, price/currency, availability, inventory tracking, stock by location and channel eligibility. Managers can create, edit and bulk-update variants without opening each one individually.

For services, the corresponding capability is service offers/tiers, appointment duration, bookable slots, service area, capacity, provider, cancellation policy and price rules—not an artificial retail SKU.

### 4.5 Inventory, locations and reservations

The inventory workspace must support:
- Inventory items and identifiers.
- Locations, local pickup points and fulfilment areas.
- On-hand, reserved, available and unavailable quantities as separate, defined concepts.
- Reservation visibility and expiry, adjustment reason and correction history.
- Low-stock thresholds, discrepancy queues and location-specific availability.
- Inventory import/export and safe bulk adjustments.
- Links from every inventory row to affected product variants and open orders.

An inventory adjustment shows before/after counts, reason, scope and conflict warning. A repeated or timed-out adjustment cannot be blindly retried.

For non-stock services, expose availability calendars, staff/provider capacity, service-area coverage and slot reservations as equivalent domain-native tools.

### 4.6 Channels, regions and publishing availability

A channel/market workspace manages where and how the same catalog is sold or discovered:
- Country, city/neighborhood coverage, currency, language and storefront/mobile audience.
- Listing and variant availability, catalog visibility, scheduled publication and channel-specific copy.
- Market eligibility and provider/service restrictions.
- Taxes/fees, promotions, delivery methods and payment methods selected for that channel.
- Channel-specific performance and exceptions.

A channel is a business context, not just a feature flag. Changing it must preview impact on catalog, checkout, prices, tax, payments and active orders.

### 4.7 Pricing, price lists, commissions and fees

Separate workspaces must define:
- Base price and applicable currencies.
- Price lists for eligible audiences/provider contracts and time-bound prices.
- Promotion overrides versus the base price.
- Marketplace commission, provider fee and member-facing service fees where the business policy supports them.
- Start/end dates, priority and mutually conflicting price rules.
- Cost information where allowed, kept distinct from selling price.
- Preview of affected listings/orders and explicit financial approval when material.

Pricing rules require deterministic precedence and a way to explain why a particular member/channel sees a particular price.

### 4.8 Promotions, discounts, vouchers and campaigns

A promotion studio supports:
- Catalogue/product price promotions.
- Order-level discounts.
- Fixed or percentage value, eligible goods/services/categories/providers/channels/regions and audience.
- Minimum totals, usage limits, per-member limits, code/voucher lists, start/end date and stacking/exclusion rules.
- Clear distinction between paid advertising, sponsored placement and price discount.
- Preview of eligibility, example calculations, maximum liability and expected exposure before publishing.
- Performance by exposure, clicks/leads, completed orders and cancellation/refund/complaint guardrails.

The UI must show which rule actually wins if promotions overlap.

### 4.9 Orders, checkouts and bookings

**Order collection**
- Search/order number, member, provider, channel, payment/fulfilment state, time, locality, amount and risk.
- Saved queues: “Needs action”, “Payment unknown”, “Ready to fulfil”, “Overdue”, “Cancellation requested”, “Disputed”, “Refund awaiting approval” and “Completed today”.
- Row actions only reflect the verified legal/domain transitions available for that state.

**Order detail workspace**
- Member and provider identities, each order line and quantity, variant/service slot, price calculation, discounts/fees/tax, payment transactions, delivery/fulfilment, status history, customer communications, notes, disputes, refund and ledger references.
- A timeline separates customer action, platform state, payment provider confirmation and fulfilment evidence.
- Contextual actions include capture/confirm only where supported, cancel, fulfil/ship, mark pickup, message, partial/full refund request, dispute escalation or re-open support.
- Every consequential action has eligibility, amount, target, reason, approval, before/after and read-back verification.
- Unknown payment or booking state blocks unsafe repeat commands and routes to reconciliation.

**Bookings/service jobs**
- Slot and capacity, provider assignment, acceptance/decline, rescheduling, no-show/cancellation, evidence of work, completion and customer feedback.
- Appointment and project services need different workflow templates rather than pretending every transaction is a parcel order.

### 4.10 Fulfilment, deliveries, cancellations, returns and refunds

Dedicated queues for packing/dispatch, tracking gaps, delivery failure, pickup, cancellation, return authorization, item-condition review, partial return, refund eligibility and late completion.

The detail view joins order, shipment/booking, provider/member communications, relevant evidence and financial status. Return receipt and refund execution are separate states. Refund preview shows eligible lines, shipping/fees, currency, amount, transaction state and accounting impact. An action may require independent finance approval. Partial provider failure must remain visible per line/step.

Local delivery and collection points should be configurable by geography, operating hours, capacity, blackout dates and serviceability. The market suite handles fulfillment attached to orders; independent delivery bookings and platform-wide dispatch/provider operations are owned by the delivery network described in [Platform-Wide Payments and Delivery Network](../../architecture/platform-wide-payments-and-delivery.md). Do not model a standalone courier request as a fake store order.

### 4.11 Customers, members and customer groups

Commerce customer views link to the canonical platform member instead of creating a duplicate identity. The suite provides order/booking history, addresses/allowed contact data, consent/preference view, support/dispute/review history, permitted segments and privacy-request status.

Customer groups/segments can drive allowed pricing, promotions and communication; membership, neighborhood and trust rules remain owned by their corresponding governance policies. Segments need size previews, criteria explanations and a “why is this person included?” view.

### 4.12 Providers, sellers and business accounts

A provider workspace must support:
- Organization/business profile, owners and authorized representatives.
- Claim and verification flow, evidence, status/reason, service area and business categories.
- Store/catalog completeness, hours, contact methods, inventory/availability, response time and policy acknowledgements.
- Onboarding documents, reviewer assignment, expiration/renewal and appeals.
- Offer/listing portfolio, orders/bookings, reviews, complaints, cancellations, disputes and financial standing.
- Provider staff and scoped roles; one provider cannot see another provider's private data.
- Suspension/limited visibility and reinstatement through reasoned, reversible and appealable processes where policy allows.

### 4.13 Shipping, local delivery, tax and payment settings

Dedicated settings workspaces for:
- Shipping/delivery methods, geography and postal/zone eligibility.
- Pickup/collection points, hours and capacity.
- Tax treatment/rules and appropriate professional/legal review.
- Payment methods and eligibility by channel/region.
- Cancellation/return/refund policy templates by transaction type.
- Currency, rounding and presentation.
- Communication templates for order/booking states.
- Integration health in human language, with technical detail restricted to specialist diagnostics.

Changing these controls requires an impact simulation against example orders/markets and must never silently rewrite historical orders.

### 4.14 Reviews, quality, search and merchandising

Because this is a local marketplace and directory, commerce requires an operating surface for:
- Product/service reviews and provider responses.
- Listing reports, policy violations, duplicate/spam detection and appeals.
- Search query analysis, zero-result queries, filter usage and result-to-contact/order conversion.
- Category and local supply gaps.
- Completeness and trust factors behind eligible ranking and visibility.
- Curated collections, featured listings and paid placement with explicit labeling and policy.
- Evidence-supported quality suggestions and outcome measurement.

Ranking controls are bounded and explainable; the UI must not allow arbitrary manipulation of protected or safety-relevant decisions.

### 4.15 Market reports and scheduled exports

Standard reports include catalog completeness, active/suppressed/expired listings, stock/availability, supply/demand by locality, provider response, search success, listing views, qualified leads, conversion to transaction, cancellations/returns, complaints, refunds and gross/net money movement as defined by finance.

Reports provide filters, comparison periods, row-level drill-through, metric definitions, freshness, export permissions and saved views. Schedules and recipients respect privacy/access rules.

### 4.16 Market configuration and authorization

The suite needs:
- Staff permission groups and scoped assignments (catalog, pricing, orders, refunds, providers, reports, settings).
- Separate rights for view/edit/import/export/publish/refund/approve and financial administration where needed.
- Channels and provider/locality scopes.
- Taxonomy/attribute administration.
- Business policy templates and automation.
- Full activity/audit history and change calendar.

### 4.17 Market journeys that must be testable end-to-end

1. Launch a new category and required fields → inspect affected records → migrate/resolve invalid data → preview search/navigation → publish taxonomy change → verify discovery.
2. Add a variant-rich product → configure identifiers, variants, media, market price, channel eligibility and stock → preview member detail → validate → publish → verify live availability.
3. Add a local service provider → claim/verify business → configure categories/service area/hours/slots → publish offers → test contact/booking → measure leads/completion.
4. Fix a zero-result search problem → inspect actual queries and location filters → locate supply/taxonomy gap → edit approved taxonomy/merchandising/configuration → test known queries → measure successful discovery.
5. Resolve a stuck order → establish provider-confirmed payment state → inspect fulfilment timeline → take a valid state-dependent action → verify order/provider/ledger/member communications.
6. Introduce a promotion → define audience, time, eligibility, stacking and maximum exposure → calculate sample orders → obtain approval → schedule → monitor completed orders and complaints → end or pause.
7. Update inventory across locations → preview selected variants/locations → inspect conflicting reservations → apply adjustments with reason → verify available stock and affected orders.
8. Suspend or reinstate a provider → inspect evidence and scope → choose proportionate policy action → preview member impact and active transactions → approve when needed → communicate and track appeal → verify actual listing/transaction state.

### 4.18 Explicit Saleor Dashboard parity map

The product review must compare concrete work surfaces, not use “Saleor-level” as a label. The table below separates the comparable ecommerce capability from our local-marketplace extension and the proof expected.

| Saleor concept / admin surface | PAOS market workspace | Local-marketplace extension | Acceptance proof |
|---|---|---|---|
| Products and media | Catalog/listing collection and product detail/editor | Listing types for goods, used items, vehicles, property, services and jobs | Create/edit a listing, validate media/required fields, preview member card/detail, publish and verify discovery |
| Product types and attributes | Type builder and field definitions | Type-specific fields, required evidence/disclosures, service-specific schemas | Change a required field with impact preview and show affected records/forms/search filters |
| Product variants/options and identifiers | Variant matrix, options, SKU/reference and per-variant data | Attribute-specific variation plus unit/condition and booking offers where appropriate | Add/edit many variants, preserve identifiers and check per-variant price/availability |
| Categories and collections | Hierarchical category manager plus curated collections | Geographic visibility, neighborhood navigation and cross-links to community context | Move/merge category and prove affected records, discovery filters, saved views and pages reconcile |
| Inventory and stock locations | Location-aware on-hand/reserved/available workspace | Pickup points, delivery zones, service capacity, booking slots and provider calendars | Correct multi-location inventory with live reservations and prove no duplicate adjustment |
| Channels | Channel/market configuration | Country/city/neighborhood scope, language/currency, audience and app surfaces | Preview product/price/availability differences across two channels/locales |
| Price lists and promotions | Price/fee rules and promotion/voucher workspaces | Provider contracts, local pricing, service rates, advertising distinction and commission | Evaluate overlapping rules for sample baskets, show deterministic winner/liability and approve effective revision |
| Orders and checkout | Order list, order detail, checkout/payment/fulfilment timeline | Local delivery, appointment bookings and service fulfillment | Move a valid order through every allowed transition and safely resolve unknown payment state |
| Customers and customer groups | Customer/segment view linked to canonical member | Member trust/geography/profile/preferences remain governed by member suite | Explain why a member is in a permitted segment and avoid duplicate member identity |
| Shipping and fulfilment | Delivery methods, zones, shipments/returns | Local couriers, pickup points, provider fulfillment and area-specific capacity | Reconfigure a zone and preview affected offers/orders without silently rewriting history |
| Taxes/payment configuration | Governed tax/payment settings and finance review | Local market eligibility and provider capabilities | Validate a sample order, require appropriate approval and audit the applied rule |
| Staff/permission groups | Market roles/scopes | Separate provider, geography and action permissions | Demonstrate allowed and denied actions, approval separation and audited access |
| Translations/site settings | Localization and public-presentation settings | RTL/LTR, city-language defaults, institution/business profile copy | Preview changes in each configured locale and prove fallback for untranslated fields |
| Dashboard extension points | Approved extensions at list/detail/overview contexts | Add local verticals without creating a second inconsistent admin shell | Extension respects permission, record context, loading/error state and shared design contract |
| Commerce reports | PAOS outcome reports across the listed workspaces | Supply/demand, local search, leads, bookings, complaints and trust measures | Each metric has definition, freshness and drill-through to real source records |

**Scope rule:** we should not claim one-to-one parity solely because a similarly named screen exists. A parity item passes only when the user can complete the intended workflow, including validation, state transitions, failure recovery, permissions and outcome confirmation. Saleor-specific concepts that do not fit the product (for example stored value/gift cards if no such product policy is approved) are not copied to manufacture superficial parity.

## 5. Community & trust suite — Discourse depth plus neighborhood governance

The main “المجتمع والثقة” destination opens a complete community operating suite. Safety is only one area of the suite; it is not a synonym for the whole community.

### 5.1 Community overview and health

A configurable community dashboard answers: Are members finding relevant local information, participating safely and getting help?

Sections include:
- Active/new/returning members with clear definitions and time ranges.
- Membership verification and resident activation pipeline.
- Posts and comments by type, locality and category.
- Participation and trust progression, not just raw signups.
- Reports, review backlog, response time, repeat harm and appeal overturns.
- Unanswered questions/requests, recommendations, lost-and-found resolution and poll participation.
- Group/event discovery, attendance and recurring activity.
- Search terms with no result/no useful click, category-level information gaps.
- Official alerts: prepared/published/delivered/unknown outcomes separated.
- Member feedback and support resolution.
- Local comparisons across neighborhoods and time; pinned and saved reports.
- Every metric links to its definition, source freshness, segment and underlying records.

It must support configurable sections/date ranges and drill-down, informed by Discourse's redesigned admin dashboard. The dashboard summarizes and routes work; it does not replace domain modules.

### 5.2 Unified review queue and case management

The review queue brings together reports for posts/comments, suspicious accounts, groups/events, business/provider content, official information and appeals, while retaining each item's domain-specific allowed actions.

Queue features:
- Assignments, team and geographic scope, priority, reason, age, deadline/SLA and saved filters.
- Duplicate/linked reports, similar content, reporter/context signals and prior actions.
- Full post/thread context and neighboring conversation; no decisions from snippet alone where context matters.
- Rule/policy explanation showing which criteria matched.
- Private notes, evidence attachments, timeline and handovers.
- Bulk operations restricted to safe, low-risk actions and preceded by impact preview.
- Separate state/action values for request more context, no violation, temporary hide, label/warn, remove per policy, limit account, escalate, preserve evidence and resolve.
- Appeal and restoration pathways, with review by a different person for high-impact actions when required.
- False-positive rate, overdue cases, repeat offenders and appeal outcomes.
- Partial failure and uncertain execution are retained as open cases.

### 5.3 Posts, comments and content operations

A dedicated content workbench includes list views for questions, requests, recommendations, polls, lost-and-found, neighborhood news, official notices, paid lending and relevant community posts.

Operators can search by ID/author/place/type/time/status/reports/reach, open a full record, inspect comment tree and edit history, link duplicate reports, see audience/distribution state, apply approved labels or moderation actions, hide/restore within policy, lock/close when supported, move/recategorize with impact preview, and inspect member visibility state.

Content lifecycle and owner-authoring tools must support:
- Draft/review/scheduled/published/edited/corrected/expired/removed states as appropriate.
- Type-specific required fields, media/accessibility, geographic scope and expiration.
- Distinct member experience for official notice vs ordinary post vs paid promotion.
- Correction history for information already delivered.
- Preservation/appeal evidence and audit of moderator changes.
- Controlled imports/exports where lawful and permissioned.

### 5.4 Categories, tags and community taxonomy

Separate screens for:
- Category tree and purpose/description.
- Categories visible in all neighborhoods vs specific geography.
- Post types and required fields.
- Tag dictionary, synonym sets and tag groups.
- Automatic tagging suggestions and human override.
- Posting permissions, who can see/create/reply, and approved local moderators.
- Review thresholds and category-specific policies.
- Sort/feed eligibility, default views and cross-link to marketplace/directory taxonomy where content should surface across contexts.
- Localization, deprecation, merge and impact preview.

Taxonomy changes preview affected content, saved filters, member navigation and rules; content itself is not duplicated to show in more than one context.

### 5.5 Members, trust, sanctions and appeals

Member workspaces include profile and verified neighborhood relationship, membership evidence/state, referrals/attestations, public vs private identity fields, account history, content and reports, groups, event activity, business/provider roles, safety actions, communications, open cases and appeals.

Supported member lifecycle includes applied → needs evidence → under review → approved/declined → active → challenged/suspended/limited → reinstated/closed where the policy permits. Each transition needs reason, responsible actor, member notification, effective scope, duration/expiry, and a permitted appeal route.

Trust signals must be explainable and kept separate from legal/identity verification. A recommendation is evidence, not a substitute for required address proof. A platform decision should show the criteria and source evidence the reviewer is permitted to use.

### 5.6 Neighborhood and geographic governance

A dedicated geography workspace manages countries, cities, neighborhoods, boundaries/polygons, official/validated place aliases, merges/splits, service coverage, moderators and local owners.

Features:
- Map plus hierarchy, search and bulk import/update of geographic reference data.
- Boundary versioning and impact on resident assignment, feed visibility, local businesses, official announcements, saved searches and groups.
- Preview affected members and records before a boundary changes.
- Disputed-address/ambiguous-boundary queue with human decision and evidence.
- Local health panel: membership, content activity, trust/safety, service coverage, institutions, events and information gaps.
- Scoped local roles and delegated decisions without accidentally granting global authority.

A boundary edit is a governed domain change, not a freehand map update with invisible side effects.

### 5.7 Groups and group membership

Group management supports:
- Directory/list, locality, topic, visibility/privacy, purpose, rules, cover/media and lifecycle.
- Public, approval-based and private membership modes where supported.
- Owner/moderator assignment, succession and inactive-owner recovery.
- Membership requests, questions, admission/decline/appeal, removal and role change.
- Group feed, pinned rules, report queue, events, member engagement, spam controls and lifecycle health.
- Geographic/category discovery and eligible audience preview.
- Member-facing preview and change history.

### 5.8 Events and initiatives

Separate event/initiative tools manage:
- Organizer authority and linked group/institution.
- Location, local scope, remote/in-person mode, time zone, schedule, capacity, registration/RSVP, waitlist and eligibility.
- Reminders, cancellations, changes, check-in and attendance outcome.
- Consent/safety/accessibility notes and local permit/authorization checklist where applicable.
- Initiatives, volunteers, roles, milestones, required support and completion.
- Reports, cancellation/no-show and follow-up messages.
- Audience and change preview before broadcasting.

### 5.9 Official publishing and emergency/local alerts

This is a source-authority workflow, not a generic post editor:
- Verified institution/source directory and authorized publishing roles.
- Draft/source evidence, notice type/severity, affected geography, audience, language, expiration, correction contact and update history.
- Preview on the member app with “official source” identity and geographic target.
- Approval ladder based on source, urgency, affected population and risk.
- Scheduled release and controlled urgent-release pathway with retrospective audit.
- Separate counts for approved, queued, attempted, provider-accepted, confirmed-delivered, failed and unknown.
- Duplicate suppression, safe retry, correction and withdrawal/expiry behavior.
- Delivery confirmation and post-publication monitoring.
- Clear distinction between official information, community posts and sponsored content.

### 5.10 Recommendation/relevance controls and discovery quality

A bounded discovery workspace includes:
- Content reach and engagement by locality/type/cohort.
- Search queries and zero-result/no-click gaps.
- Unanswered questions and unmet local requests.
- Eligible sources/signals used by approved ranking policies.
- Content freshness, geography relevance, user preference and quality rules.
- Explicit sponsored placement labels.
- Simulation with representative member profiles and current/proposed comparisons.
- Guardrails for safety, spam, diversity/local relevance and business disclosure.
- Experiment, rollout, monitoring and restore.

Do not expose opaque arbitrary ranking sliders that allow a manager to accidentally suppress legitimate voices or bypass safety rules. Controls must have explanations, preview, bounded choices and evidence.

### 5.11 Community rules and moderation automation

A visual rule builder supports declared triggers, conditions, action sets, deadlines, assignment, notifications, exceptions and review/expiry. For each rule: purpose, owner, version, scope, sample simulation, false-positive risks, approval, effective period and success/stop criteria.

Examples:
- A group-level posting policy routes first-time reports to a local moderator.
- Several duplicate reports group into one case without automatically proving a violation.
- Potentially urgent official information is sent to an authorized emergency workflow, not ordinary publishing.
- An unanswered local question is surfaced to eligible helpers after a stated delay.
- A member restriction expiring today opens a review and cannot be silently made permanent.

### 5.12 Appeals, corrections and restorative action

Separate appeal workspace with:
- Original item and decision side by side.
- Member's explanation and new evidence.
- Applicable policy/version and original reviewer notes according to access.
- Independent reviewer assignment when required.
- Outcomes: uphold, reduce/replace sanction, restore, request evidence, or refer.
- Visible downstream operations: content restore, account reinstatement, notification/correction, linked incident/rule review.
- Reasoned decision template, response deadline, audit and appeal trend reporting.

Appeal acceptance must be tied to actual restored state, not just a “closed” case status.

### 5.13 Community reports, saved questions and metric drill-down

Report builder uses named dimensions and definitions rather than requiring SQL: geography, post type/category, trust stage, entry route, cohort, time and state. It can save reports, pin them, schedule delivery to permitted recipients and drill into records.

Standard reports:
- Engagement and returning members.
- New member activation after verification.
- Posts/comments and participation by type/locality.
- Recommendations and business discovery.
- Poll response and event RSVP/attendance.
- Unanswered questions and unresolved lost-and-found.
- Search gaps and content demand.
- Reports/moderation response and decision reversal.
- Appeal outcome and repeat harm.
- Official notice delivery and engagement.
- Group lifecycle and local participation.
- Member feedback/support resolution.

### 5.14 Explicit Discourse administration parity map

| Discourse concept / admin surface | PAOS community workspace | Neighborhood-platform extension | Acceptance proof |
|---|---|---|---|
| Admin dashboard and reports | Community health and configurable, defined KPIs | Comparisons by neighborhood/city, member verification stage, local service coverage and official notices | Open a summary metric, inspect definition/freshness, drill to segment and specific records |
| Review queue and flagged content | Unified review queue and case room | Geography, repeat reports, source authority, local moderator ownership and appeals | Decide from full thread context, record rationale, verify result and follow through appeal |
| Topics/posts/replies | Content operations and thread detail | Questions, requests, recommendations, polls, lost/found, local news and paid lending | Edit/recategorize/hide/restore using permitted lifecycle, preserve comment/history context |
| Categories, tags and tag groups | Taxonomy and posting policy | Global vs neighborhood categories, local synonyms, type-specific required fields and cross-domain discovery | Merge/reparent taxonomy with preview of affected content, filters, moderators and saved views |
| Users, trust levels and suspensions | Member trust, scoped sanctions and member 360 | Verified address/neighborhood, referral evidence and separate legal verification | Explain evidence for decision and prove actual local access state and appeal path |
| Groups and group permissions | Group directory, membership requests, owners/moderators | Local membership, eligibility, institution-linked groups and neighborhood discoverability | Create group, test privacy/admission/membership, replace inactive owner and audit |
| Announcements/notification settings | Official publishing and notification controls | Verified institutional source, emergency/local reach, geography, translations and delivery receipts | Publish authorized alert, separate queued/published/delivered/unknown and handle correction |
| Search and content discovery | Search/relevance analysis | Unanswered local requests, zero-result location queries, local provider supply and cross-surface relevance | Repair a taxonomy/supply/discovery gap, test known searches and measure helpful result/outcome |
| Moderation rules/trust configuration | Rules & Automation Studio | Local policy scopes, thresholds, expiry, human review, jurisdiction/source scope | Simulate on historical cases, inspect false positives, publish bounded rule and prove pause/restore |
| Appeals/admin history | Independent appeal, correction and audit timeline | Restore post/membership/official notice and correct local information already delivered | A successfully upheld appeal shows the resulting member-facing state, not only a closed case |

Discourse is a benchmark for community-specific depth, not the final product model. Our suite adds real-world neighborhood relationship, place boundary, resident verification, institutional authority, local groups/events and geographically targeted official reach.

### 5.15 Community journeys that must be testable end-to-end

1. Review a potentially harmful post → see thread and location → consolidate reports → choose proportionate action → communicate → track appeal → verify content/account state.
2. Change category structure → preview affected posts, filters, moderators, navigation and rules → approve → publish → verify member app and taxonomy reporting.
3. Create a neighborhood group → set purpose/privacy/admission rules/owners → preview local audience → publish → test joining and membership decisions → measure activity.
4. Launch a local initiative → verify organizer → define roles/capacity/locations → recruit participants → communicate schedule → track attendance and outcomes.
5. Publish official alert → verify source and publisher → choose severity/geographic audience/languages → preview and approve → dispatch → reconcile delivery states → correct/expire if needed.
6. Improve search usefulness → review failed local queries → determine missing content/category/relevance cause → edit supported taxonomy/approved ranking controls → test sampled queries → measure successful results.
7. Change a moderation automation → simulate against representative historical cases → review expected actions/false positives → approve bounded scope → observe outcomes and appeals → pause/restore if guardrails fail.
8. Adjudicate a membership appeal → compare evidence and policy revision → independent review → decide → verify effective member-neighborhood scope → communicate and record outcome.

## 6. Business Directory suite — Yelp-level workflow depth

The business directory is distinct from ordinary marketplace listings. A business may maintain a profile, receive public reviews and leads, respond to feedback and measure discovery even where an online checkout is absent.

Required workspaces:
1. **Business portfolio:** searchable list, profile status, owner/claim state, category, locality, completeness and reputation.
2. **Claim and verification:** claim request queue, ownership evidence, representative authorization, duplicates, conflicting claims, verification expiry and appeals.
3. **Business profile editor:** identity, branches, categories/services, local coverage, hours/holidays, contacts, media, amenities/accessibility, service attributes, languages and member-facing preview.
4. **Reviews and ratings:** new/reported/reviewed reviews, duplicate/spam signals, policy status, business response, edit/appeal history and aggregated reputation definitions.
5. **Owner responses and customer recovery:** response templates, escalation, private contact options where allowed, response time and resolved/unresolved outcomes.
6. **Leads and intent:** calls/clicks/messages/website/contact/booking events with explicit definitions, deduplication windows, source/channel, conversion and measurement freshness.
7. **Discovery and local visibility:** profile views, map/search appearance, search terms, category position as appropriately defined, zero-result gaps and discovery by local segment.
8. **Promotions and paid visibility:** budget, audience, eligible geography, disclosure, run dates, spend limits, leads/orders and guardrails.
9. **Quality and compliance:** missing fields, outdated hours, verification review, conflicting locations, content reports, policy flags, duplicate profiles and local authority issues.
10. **Branch and multi-location management:** shared fields vs per-location fields, delegated branch managers and aggregate/local reporting.
11. **Business analytics:** page views → profile actions → qualified leads → bookings/orders where observable; conversion, response and quality trends; definitions and drill-through.
12. **Business permissions:** owner, branch manager, reviewer, support and directory administrator rights separated and audited.

Required journey: claim a profile → verify authority → resolve duplicate/conflict → complete details and service area → preview public page → publish → respond to first review → inspect leads and profile actions → improve missing information → measure change.

Important boundary: aggregate visibility/clicks, a lead, a booked service and a completed transaction are different events. They must never be labelled as the same “conversion”.

### 6.13 Explicit Yelp for Business parity map

| Yelp for Business capability | PAOS directory workspace | Acceptance proof |
|---|---|---|
| Business profile and owner claim | Business portfolio, claim queue and verification evidence | Resolve conflicting claims and connect the correct authorized representative |
| Profile completeness and public page | Business profile editor with public member preview | Publish correct hours, categories, services, contact, media and geographic coverage |
| Public reviews and rating health | Reviews/ratings, policy review, response and appeal | Reply to a review, handle a report according to policy and preserve history |
| Owner/customer interaction | Response workflow, escalation and recovery | Track response time and whether the issue was resolved, without exposing private notes |
| Views and customer leads | Funnel separating impressions/views/profile actions/calls/messages/leads/bookings/transactions | Prove each event is distinct and defined; no “lead” count from a raw view |
| Local search and visibility | Local visibility, query and category analytics | Break down results by geography, category, date and source and drill into sample records |
| Paid visibility and campaigns | Promotion/ads controls with disclosure, audience and budget | Preview scope/spend, confirm paid disclosure, cap exposure, measure valid lead outcomes |
| Branches/multi-location business | Parent business + branches, shared and per-branch profile attributes | Delegate one branch without allowing cross-branch data leakage; aggregate and local reports |
| Quality and operational trust | Outdated-hour, duplicate, incomplete-profile, verification-expiry and report queues | Resolve quality case, update public profile, verify member-visible changes and audit |
| Access and reporting | Owner/branch-manager/reviewer/platform-admin scopes | Demonstrate effective permissions, export restrictions and attributed history |

## 7. Members, neighborhoods and governance suite

This suite must go beyond a basic member list and must support:
- Member application/reverification queues, address evidence, referral/attestation sources, ambiguous geography, expiry and appeals.
- Member profile 360: verified scope, permitted public profile, member preferences, activity and relationship history, open cases, groups, business/provider roles and privacy requests.
- Neighborhood map/hierarchy, boundary changes, member assignment and dispute review.
- Institutions and authorized representatives with evidence, publishing authority, expiry and revocation.
- Scoped staff roles by geography, domain, record type, action and duration.
- Data access/export/privacy requests and deletion/retention obligations.
- Duplicate identity/conflicting source resolution with separate evidence and decision logs.
- Lifecycle dashboards measuring time to verification, completion, reversals, unresolved exceptions and local activation.
- Member-facing communication previews and read-back of resulting access/authorization state.

Required journey: resolve ambiguous address → see candidate neighborhoods/evidence → request missing proof or select approved adjudication → record reason → apply effective neighborhood membership → verify member content visibility and local permissions → provide appeal route.

## 8. Platform-wide money, payments and growth suite

This is a cross-domain financial operations suite, not a second payment engine and not just an order-payment screen. It must cover every approved payable source on the platform through the same payment operations and reconciliation experience while each business domain continues to own its own eligibility, amount, lifecycle and cancellation rules. See [Platform-Wide Payments and Delivery Network](../../architecture/platform-wide-payments-and-delivery.md).

Required operational surfaces:
- One permission-aware search and timeline across payment intents/transactions, provider callbacks, source-domain records, ledger entries, refunds, disputes, allocations, settlements and payouts.
- Source coverage for bookings/services, product orders, paid lending/rental flows, provider advertising/promotions, standalone delivery, delivery charges attached to orders, and future monetized capabilities only when explicitly approved. The existing code's BOOKING/AD/ORDER/LOAN origins are evidence of current integrations, not proof every future source is wired.
- Provider/gateway eligibility and health by region, currency and method; do not represent an unavailable/unconfigured gateway as usable.
- Reconciliation across external provider state, platform payment state, the source business state and the ledger. Distinguish accepted, pending, authorized, captured/collected, refunded, disputed, failed and unknown only where the underlying adapter actually supports those states.
- Refunds with source-owned eligibility, original currency, remaining refundable amount, line/fee/delivery allocation, approvals and result read-back; separate customer refund from transfer reversal and seller/courier recovery.
- Seller/provider/courier allocation and settlement where the approved commercial policy requires them; show payable calculation, holds, payout state, failure and retry eligibility without direct balance edits.
- Disputes/chargebacks, deadlines/evidence, fraud/abuse exceptions, provider webhooks, duplicate deliveries and ambiguous outcomes.
- Promotions/campaign billing, budgets, audience rules and spend caps, separated from purchase discounts and delivery fees.
- Defined finance reports for gross/net/refund/chargeback/tax/fee/payout concepts, with source records, freshness, currency and drill-through.
- Acquisition, neighborhood activation, retention and cohort analytics, with metric definitions.
- Permission separation among viewing, initiating, approving, executing, reconciling and exporting financial data. High-risk actions support separation of duties.

**Required money journey:** identify the payable source → resolve its authoritative amount/currency and current business state → verify provider outcome → reconcile provider/payment/ledger/source → preview the exact correction and who bears it → obtain independent approval if policy requires → execute once → re-read all affected records → verify the financial and member-visible outcomes → record audit evidence and notify the parties.

No wallet, escrow-like holding, cash collection, cash-on-delivery, recurring off-session charge, commission, merchant-of-record or payout policy is implied merely by making payments platform-wide. Each requires explicit commercial/legal review and configuration.

## 8.1 Delivery as a platform service and a fulfillment network

Delivery is a first-class service, not solely an order field. Its complete target contract is described in [Platform-Wide Payments and Delivery Network](../../architecture/platform-wide-payments-and-delivery.md). The admin UX must support both a **standalone direct-delivery request** and **delivery/fulfillment attached to an order or applicable service**, using the same delivery domain without merging their business states.

Required workspaces:
- Direct delivery requests: quote, pickup/drop-off, package declaration, service window, payment mode, dispatch, tracking, proof, cancellation, failed attempt, return-to-sender and claim.
- Marketplace shipments/fulfillment: delivery options, seller/package hand-off, multi-package/leg tracking, local courier, carrier shipping, seller-delivery and pickup/collection.
- Serviceability and rates: delivery zones, operating hours, capacity, cut-offs, holidays, package limits/restrictions, provider pricing, quote expiry and fee allocation.
- Courier/driver and delivery-provider operations: eligibility, scoped access, assignment/accept/reject/reassignment, workload, incidents, performance and settlement eligibility.
- External integration control: provider/carrier capabilities, credentials in approved secret storage, webhook authentication/deduplication, health, rate limits and safe recovery.
- Exceptions/claims: unassigned or late jobs, provider outage, missing tracking, pickup failure, recipient unavailable, damage/loss, return, cancellation-fee dispute and customer refund.
- Reconciliation of customer delivery charge, partner/courier cost, provider invoice, platform fee, refund, compensation and ledger entries.

A direct-service quote and a delivery fee selected inside checkout are not interchangeable records. Quote versions and customer-agreed prices must be retained; a later rate change must not silently rewrite an accepted order. Each shipment/delivery leg has its own physical-progress timeline while order state remains order-owned.

Required journeys:
1. Request direct delivery → confirm serviceability and quoted terms → confirm/payment → dispatch → pickup proof → tracking → delivery proof → customer/provider notification → financial reconciliation.
2. Select delivery at checkout → freeze the delivery option and accepted price in the order agreement → confirm order → create shipment/job idempotently → track hand-off and proof → update the order's fulfillment projection → reconcile fee/refund/settlement.
3. Handle no serviceable quote, courier rejection, provider timeout, duplicate/out-of-order webhooks, failed pickup, failed delivery, return-to-sender and partial refund without duplicate charges or phantom delivery completion.
4. Correct a rate/zone/capacity rule after previewing affected geographies/orders; prove historical quote and order snapshots remain unchanged.
5. Confirm a driver or carrier cannot access other jobs or more address/proof data than the role and assignment permit.

## 9. Member Experience Studio and Change Centre are cross-domain, not substitutes for domain depth

The Experience Studio changes what members see and how they move through journeys. It does not replace the product, order, group, member or moderation administration surfaces. A listing still requires a detailed listing editor; a group still requires group settings and membership administration; a moderation case still requires the contextual case room.

The Change Centre unifies draft, validation, review, approval, staged release, observation and restore. It should not turn every domain action into a generic configuration card. Normal daily operations remain within the domain; material reusable policy/experience changes follow the shared governed lifecycle.

## 10. Required screen families and interaction patterns

Every domain must have, where applicable:
- Overview/workbench.
- Collection/list and saved views.
- Detail workspace with timeline/relationships.
- Guided create/edit wizard.
- Bulk edit/import/export preview and results.
- Configuration/taxonomy editor.
- Queue and exception management.
- Report builder/metric drill-down.
- Role/scope assignment.
- Audit/revision history.
- Member-facing preview for published experiences.
- Domain-specific “what is blocking this?” explanation.

Common collection design: global search, advanced filters, saved views, visible columns, sortable headings, pagination, keyboard access, selection counts, permitted bulk actions and stable query state. Common detail design: title/status/scope, actions that match current state, summary, dedicated tabs by domain, related records, activity timeline, internal notes, attachments/evidence and audit.

Common edit design: structured sections with clear completeness, inline validation, draft save, unsaved-change warning, semantic diff, preview, action consequence and permission reason. Common state design must distinguish saved draft, pending approval, scheduled, active, paused, failed, partially completed and unknown.

## 11. Domain parity matrix and release gates

| Domain | Must reach reference depth | Required distinct workspaces (minimum product families) | Critical proof before accepting |
|---|---|---|---|
| Commerce/market | Saleor Dashboard breadth plus Medusa operational detail and multi-provider marketplace extension | Catalog; product types/attributes; variants; categories/collections; inventory/locations/reservations; channels; prices/fees; promotions; orders/checkouts; bookings; fulfilment/returns/refunds; customers/groups; providers/sellers; shipping/local delivery; tax/payment settings; reviews/search/merchandising; reporting; staff permissions | Create and publish a variant-rich good; configure a local service; edit stock/pricing/channel; process an order through fulfilment/refund; inspect audit and outcome |
| Community/trust | Discourse admin, moderation and community health breadth with verified neighborhood governance | Overview; post queues; content; category/tag/type configuration; users/trust/sanctions; neighborhood scope; groups/members; events/initiatives; official notices; appeal/correction; search/relevance; policies/automation; reports | Review a report with context, apply a scoped decision, handle an appeal, modify taxonomy, publish official alert and prove member-visible results |
| Business directory | Yelp for Business workflow depth | Profiles; claims; verification; branches; reviews; responses; reports; leads; visibility/search; promotions; service coverage; quality; analytics; permissions | Claim and verify a profile, resolve duplicates, publish profile, respond to review and measure real lead event semantics |
| Members/geography | Local network governance | Membership evidence; referrals; ambiguous address; neighborhood boundaries; institution authority; member 360; appeals; privacy; scoped roles | Apply/review a geographic membership decision and verify actual access scope and audit |
| Official reach | Governed local/institutional publishing | Source registry; message editor; audience; geographic reach; localization; schedule; urgent lane; approvals; delivery/unknown states; correction/expiry | Publish an authorized alert with scoped audience and distinguish approved/published/delivered/unknown |
| Money/growth | Payment/order integrity plus outcome-led growth tooling | Payment state; reconciliation; refunds; disputes; payouts; fees; campaign budget; cohorts/retention; experiments; reporting | Resolve an unknown payment without duplicate action and tie a controlled experience change to measured business/member outcomes |
| Member experience | Visual product control, not mere feature flags | Journey map; home/navigation editor; approved components; per-persona preview; localization/version compatibility; content eligibility; rules; experiments; release/restore | Edit a member surface without code, validate states/audience, stage it and verify effect |
| Team/governance | Enterprise-grade delegated authority | Roles/scopes; ownership; approvals; audit; privacy; role reviews; saved workspaces; handover | Prove denied actions, approval separation, scope enforcement and actor-attributed history |

A domain must not pass simply because every row name exists in navigation. Acceptance is evidence of the workflow and its lifecycle behavior.

## 12. Product and engineering contract

For every workspace and action, create a capability contract with:
- The human job and member outcome.
- The domain owner and canonical record.
- List/detail/editor states.
- Allowed actions per lifecycle state.
- Scope rules (geography/provider/channel/role/version).
- Required data and freshness requirements.
- Preview/simulation and failure behavior.
- Approval and separation-of-duties requirements.
- Completion/read-back evidence and audit event.
- Existing implementation evidence or explicit gap.
- API/service/event requirements only after the UX journey is stable.
- Acceptance tests including partial failures and recovery.

The manager-facing screen must not be implemented as a thin page around whichever endpoint is currently convenient. If a domain cannot fulfil the workflow, record the gap and design the correct contract; do not shrink the manager journey to hide the gap.

## 13. Acceptance testing with non-technical managers

Test full workflows with people who do not know the codebase, tables, endpoint paths or module boundaries. Use tasks, not guided tours.

Market tasks:
- Find all listings in one city missing essential information, bulk request correction, verify status change and audit.
- Define a new service category with type-specific fields and preview effects before publishing.
- Correct variant stock in two locations without breaking reservations.
- Configure a time-bound audience-specific promotion, preview example transactions and stop it when a guardrail fails.
- Resolve a pending order/refund while payment is unknown without making a duplicate financial command.
- Claim a local business, publish its profile and understand which metric is a visit, lead, booking or completed transaction.

Community tasks:
- Find and review a report across the complete conversation, decide proportionately and process an appeal.
- Create a neighborhood group with scoped moderators and an approval-based member journey.
- Publish an official alert to a defined geography, prove delivery states and issue a correction.
- Change categories/tags without losing content or moderator assignments.
- Find unanswered local questions and failed searches, identify the cause and improve discovery.
- Configure automation and explain its scope, risk, test outcome and restore path.

Measure task completion, time, wrong turns, decision comprehension, error recovery, confidence and whether the manager can explain what members will see. Let results change the product information architecture, not only visual style.

## 14. Sources

### Official product documentation
- Saleor [product overview](https://docs.saleor.io/developer/products/overview), [channels](https://docs.saleor.io/developer/channels/overview), [checkout](https://docs.saleor.io/developer/checkout/overview), [promotions](https://docs.saleor.io/developer/discounts/promotions), [dashboard extensions](https://docs.saleor.io/recipes/extending-dashboard).
- Saleor Dashboard source: https://github.com/saleor/saleor-dashboard/blob/main/src/index.tsx
- Medusa [Admin user guide](https://docs.medusajs.com/user-guide), [products](https://docs.medusajs.com/user-guide/products), [inventory](https://docs.medusajs.com/user-guide/inventory), [price lists](https://docs.medusajs.com/user-guide/price-lists).
- Discourse [new Admin Dashboard, 10 August 2026](https://blog.discourse.org/2026/08/introducing-the-all-new-admin-dashboard/), [review queue](https://meta.discourse.org/t/a-new-review-queue-layout-with-all-new-features/388194), [categories](https://meta.discourse.org/t/create-a-category-in-discourse/197224), [tags](https://meta.discourse.org/t/admin-guide-to-tags-in-discourse/121041).
- Yelp for Business [views and leads](https://www.yelp-support.com/article/How-do-you-measure-User-Views-and-Customer-Leads-on-Yelp-for-Business?bui=null&l=en_GB).
- Directus [flows](https://directus.com/docs/guides/flows) and [access control](https://directus.com/docs/guides/auth/access-control).

### Open-source source repositories
- https://github.com/saleor/saleor-dashboard
- https://github.com/medusajs/medusa
- https://github.com/discourse/discourse
- https://github.com/directus/directus
- https://github.com/PostHog/posthog
- https://github.com/twentyhq/twenty
- https://github.com/appsmithorg/appsmith

## Final decision

PAOS is a family of deep domain-management suites under one coherent manager-first shell—not a shell of thin dashboards. The shell makes the platform understandable; each domain workspace supplies the full operational depth of its reference class. The market must be Saleor-depth plus local multi-provider workflows, the community must be Discourse-depth plus neighborhood trust and governance, and the directory must be Yelp-depth. Every other primary domain follows the same explicit breadth, state, and end-to-end acceptance model.
