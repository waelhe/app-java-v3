# Platform Administration — Global Product, UX Journeys, and Backend Gap Specification

**Status:** Supporting target-state journey/gap specification; the backend-independent Product Charter is the canonical UX and product authority. Not an implementation-completion declaration  
**Baseline inspected:** waelhe/app-java-v3 / main, 15 October 2026  
**Observed backend baseline (not the target product or frontend stack):** Java 25, Spring Boot 4.1.1, Spring Security 7.1.x, Spring Modulith 2.1.1, Maven modular monolith

> **Truth rule:** Current code is evidence of what exists, not the ceiling of the target. A route is not a complete administrator journey. A database row is not a usable capability. A feature flag is not an effective kill switch unless every promised path observes it. Proposed screens, endpoints and records below are target-state proposals unless explicitly identified as existing.

## Detailed UX authority — required alongside this gap register

Manager navigation and control-studio direction are governed by the [Global Manager UX Research](platform-admin-global-manager-ux-research.md); the old 14-module navigation should not be reintroduced from gap inventory headings.

- [Detailed screen and interaction specification](platform-admin-detailed-screen-specification.md) defines what the manager sees, the plain-language Arabic UI, action preview, statuses, and empty/error/stale/permission states.
- [24 step-by-step End-to-End journey recipes](platform-admin-e2e-journey-recipes.md) defines the complete user journey, branches, recovery and acceptance tests.

This document remains the evidence register for current backend capability and gaps; it cannot be used to lower the product experience or delete a manager step. Missing capability means implement, adapt or replace the backend capability—not omit the user requirement.

## 0. Executive decision

> **Product authority:** The independent [Platform Administration Operating System Product Charter](platform-admin-operating-system-product-charter.md) defines the desired interface, operator journey, frontend architecture and backend-independent contracts. This companion document adds current-backend evidence, cross-domain journeys and the gap register; it must not constrain or downgrade the charter's target.


The target is an **administrative operating system for a multi-country, multi-city, neighborhood-based community and commerce platform**. It combines five views of one product: control plane, work plane, evidence plane, product intelligence plane and reliability plane. These are not five new microservices.

Every major operator task must complete this cycle:

**Signal/request → scoped context → related records and evidence → policy/authority check → preview/risk assessment → approval where required → source-owned command → durable side effects → observed outcome → audit and follow-up.**

The product target includes:
- Leadership and operations command center with explainable, actionable signals.
- Global search and entity 360 views linking users, memberships, institutions, posts, listings, orders, bookings, payments, disputes, notifications, sources, releases, jobs and incidents.
- Unified case/work management with assignment, deadlines, escalation and timeline, while domain state machines remain owned by the domains.
- Fine-grained permission and scope governance, separation of duties and expiring emergency access.
- Typed capability/configuration governance, feature rollout, mobile layout authoring, release management, approval and rollback.
- Workbenches for trust, safety, community, institutions, publishing, commerce, finance, services, property, jobs, lending, knowledge, notifications and search quality.
- Operational response connecting SLOs, traces, logs, event publications, jobs, delivery ledgers, webhooks, circuit breakers, cache invalidation and runbooks.
- Product analytics, funnels, cohorts, controlled experiments and safe exports.
- A constrained AI copilot that explains with evidence and proposes actions, but never gains authority beyond the operator.

The target is deliberately larger than the present console. Delivery is phased; the final product is not reduced to the current implementation.

## 1. Repository baseline and observed gaps

### 1.1 Current source inspected

- [ConsoleView](https://github.com/waelhe/app-java-v3/blob/main/marketplace-console/src/main/java/com/marketplace/console/ConsoleView.java): a three-section inventory linking existing routes; not a task-oriented information architecture.
- [ConsoleWebController](https://github.com/waelhe/app-java-v3/blob/main/marketplace-console/src/main/java/com/marketplace/console/ConsoleWebController.java): Spring MVC/Thymeleaf console with flags/config/geo/planning forms, redirects and flash messages.
- [ConsoleService](https://github.com/waelhe/app-java-v3/blob/main/marketplace-console/src/main/java/com/marketplace/console/ConsoleService.java): feature flags, remote config, geo resolver, MeterRegistry readings and a limited change history.
- [ConsoleQueuesWebController](https://github.com/waelhe/app-java-v3/blob/main/marketplace-app/src/main/java/com/marketplace/admin/ConsoleQueuesWebController.java): four queues (membership verification, institution review, review moderation, community reports), bounded to the first 50 records per queue.
- [V214 console seed](https://github.com/waelhe/app-java-v3/blob/main/marketplace-app/src/main/resources/db/migration/V214__console_seed.sql): an initial planning revision and five feature flag rows.
- [PlanningPayloadValidator](https://github.com/waelhe/app-java-v3/blob/main/marketplace-console/src/main/java/com/marketplace/console/PlanningPayloadValidator.java) and [ConsolePlanningService](https://github.com/waelhe/app-java-v3/blob/main/marketplace-console/src/main/java/com/marketplace/console/ConsolePlanningService.java): a closed six-section vocabulary, validation, append-only revisions and rollback by republishing a prior payload; the web input remains raw JSON.
- [AdminController](https://github.com/waelhe/app-java-v3/blob/main/marketplace-app/src/main/java/com/marketplace/admin/AdminController.java): central admin routes for identity and additional cross-domain operations that delegate to source services/ports.
- [IdentitySpi](https://github.com/waelhe/app-java-v3/blob/main/marketplace-identity/src/main/java/com/marketplace/identity/spi/IdentitySpi.java) and [ADR-0001](https://github.com/waelhe/app-java-v3/blob/main/docs/adr/ADR-0001-account-identity-roles-and-verification.md): account roles and additional role assignments, with structured audit lines in identity commands.
- [PlatformReleaseService](https://github.com/waelhe/app-java-v3/blob/main/marketplace-app/src/main/java/com/marketplace/releases/PlatformReleaseService.java): versioned release publication with minimum version, mandatory update and grace period; it does not prove rollout-ring/adoption analytics exist.
- [JpaActuatorAuditEventRepository](https://github.com/waelhe/app-java-v3/blob/main/marketplace-platform-infra/src/main/java/com/marketplace/shared/security/JpaActuatorAuditEventRepository.java): persistent security audit events, not a complete business admin-action audit.
- [ModulithEventBusHealthIndicator](https://github.com/waelhe/app-java-v3/blob/main/marketplace-platform-infra/src/main/java/com/marketplace/shared/observability/ModulithEventBusHealthIndicator.java) and [SLO specification](https://github.com/waelhe/app-java-v3/blob/main/docs/observability/slo.md): event-bus health signals, SLO definitions, alert rules and runbooks exist; live external dashboard/alert delivery is a separate deployment fact.
- [Feature flag convention](https://github.com/waelhe/app-java-v3/blob/main/docs/governance/feature-flags.md): percentage rollout, A/B bucketing and remote targeting are explicitly outside the current convention pending a decision.

This is a static source inspection, not a claim that every production configuration, external dashboard or endpoint was live-tested as part of the design. Each implementation wave must truth-sync against the current main branch and real schema before code changes.

### 1.2 Truthful current classifications

| Capability | Current evidence | Classification |
|---|---|---|
| Console shell | Thymeleaf page, tri-partite route catalog and control boards | PARTIAL: useful foundation, not a complete admin product |
| Review queues | Four source-owned queues, first-50 view and delegated decisions | PARTIAL: no global case workspace, SLA, assignment and full cross-entity context established by this controller |
| Domain admin APIs | User/role/status, settings, revisions, moderation, institutions, news/urgent alert, release, payment/booking and other routes exist in their owners | PARTIAL: route presence does not prove complete admin UX |
| Admin access | ADMIN gates and account role assignments exist | PARTIAL: target requires permission + resource/scope + risk/context policies |
| Audit | Actuator security audit, selected structured logs, Envers and domain histories | PARTIAL: no single queryable cross-domain audit for every admin command is evidenced |
| Runtime metrics | Actuator/Micrometer/Prometheus/SLO foundations exist | PARTIAL: application console metrics are diagnostic; they are not business analytics and do not prove external telemetry is deployed |
| Event operations | Modulith publication registry and event-bus health signal exist | PARTIAL: no full operator workbench for inspect/retry/verify is evidenced |
| Feature flags | Commit #572 wires community events, market and polls. V214 also seeds lost-found and urgent-institution keys | PARTIAL / NOT SAFE TO EXPOSE AS EFFECTIVE: #572 explicitly leaves the latter two for later waves. Do not advertise them as working kill switches before checking consumers and proving the effect end-to-end |
| Configuration | Remote config strings, system settings, geographic settings and planning revisions exist | PARTIAL: multiple storage concepts need one ownership/type map; do not merge stores blindly |
| Mobile planning | Raw JSON validation/revisions for six supported sections | PARTIAL: no visual component editor and version-specific Android preview are evidenced |
| Releases | Version/channel/minimum/mandatory/grace data exists | PARTIAL: no evidence of staged rollout/adoption telemetry from this service alone |
| Product analytics | Some domain metrics and impression/discovery records exist | PARTIAL: no unified definitions/events/funnels/cohorts contract is evidenced |
| Global case/search/bulk/approval workflows | Related primitives exist in places | GAP / VERIFY: require targeted search and integration tests before declaring complete |

## 2. Product principles

### 2.1 One shell, task-oriented workbenches

Every screen shares:
- Explicit environment badge and risk state; production is never confused with test data.
- Operator identity, active permissions, active scope and expiry of temporary access.
- Global search / command palette, assigned workload, urgent alerts and approvals.
- Geographic/organizational scope selector. Selecting a scope filters data only; it does not grant authority.
- Breadcrumbs and back-navigation preserving case, entity and source context.
- Data freshness timestamp and stale-data warnings on operational cards.

Navigation reflects administrator tasks, not the set of REST endpoints. The canonical experience is defined by the independent [Platform Administration Operating System Product Charter](platform-admin-operating-system-product-charter.md). The current Thymeleaf console is evidence of current behavior only; it is not a presumed shell or selected implementation foundation. The admin UI must have a dedicated repository chosen through an explicit fitness review or a newly created repository. The backend, including the current Spring platform, is a replaceable adapter: when it cannot meet target contracts, security, outcome verification or reliability requirements, redesign or rebuild the relevant capability rather than deleting the product requirement.

### 2.2 Entity 360-degree view

Any supported entity detail page shows identity/status, owner/scope, current state and legal next transitions; related objects; domain and admin timeline; open cases/approvals; relevant notifications/events; and safe permitted actions.

Examples: membership ↔ user ↔ neighborhood; institution ↔ representatives ↔ authorized sources ↔ alerts; listing ↔ provider ↔ orders/bookings ↔ reviews/reports; order ↔ payment intent ↔ ledger ↔ dispute; post ↔ author ↔ neighborhood ↔ reports; release ↔ adoption ↔ incident; event/job ↔ source record ↔ delivery effects.

Read models come from explicit owner DTO/query ports. The console never imports another module's JPA entity or writes its tables directly.

### 2.3 Every action is explainable

Before a consequential write, show target, current state, proposed state, affected scope/audience, expected side effects, irreversibility, dependency state and approval requirements. After submission, report the actual outcome. “Published” is not “delivered”; “retry requested” is not “retry succeeded”.

Use explicit target-state commands, optimistic concurrency for stale edits, and idempotency for bulk, financial, publish and retry operations. A rollback creates a new revision when possible; it never claims to reverse an irreversible side effect.

### 2.4 Separate health planes

- Runtime: latency, errors, database/cache saturation, event age/failure, circuit breakers, dependency and job health.
- Product/business: verification time, report resolution, order/payment success, search quality, notification delivery, release adoption and funnel completion.
- Administrative work: queue age, due times, ownership, escalations, approval backlog and completion outcomes.

Every metric has a definition, source, unit, time window, scope, freshness and drilldown. No fabricated zeros; no business KPI inferred from a JVM counter.

## 3. Operator personas and authority

| Persona | Responsibility | Scope | Restrictions |
|---|---|---|---|
| Platform owner | Platform-wide policy and highest-risk approvals | Global or delegated | Cannot silently self-approve a dual-control action |
| Operations lead | Command center, incidents, work queues, integrations | Assigned region or global | No automatic permission to refund, export sensitive data or modify IAM |
| Neighborhood manager | Membership, local reports and neighborhood health | Assigned neighborhoods/cities | Cannot read unrelated neighborhoods |
| Trust/identity reviewer | Membership, institution/source verification, appeals | Assigned queue/scope | No role elevation because an attestation was granted |
| Community moderator | Reports, rules, content actions and appeals | Authorized content scope | Bulk sanctions require dry-run and risk controls |
| Official publisher | News, broadcasts, urgent alert audience and scheduling | Authorized institution/geography | High-reach publish may require separate approval |
| Commerce operations | Providers, listings, orders, bookings and disputes | Commerce scope | No direct ledger mutation |
| Finance/reconciliation | Payment, provider callback, ledger mismatch and refunds | Finance scope | Refund/adjustment goes through source owner and approval policy |
| Security/IAM administrator | Grants, scopes, access reviews, security audit | Security scope | Temporary emergency permissions expire and are reviewed |
| Product/data analyst | Funnels, cohorts, search quality, experiments | Aggregated approved data | Row-level PII/export not implied by analytics access |
| Privacy auditor | Data requests, retention/erasure and audit | Approved scope | Read-only by default; audit access may be audited |
| SRE/platform engineer | SLO, traces/logs, event/job/dependency recovery | Runtime scope | No routine business-record editing |
| AI/knowledge curator | Source provenance, retrieval, evaluation, prompt/model cost | Approved knowledge scope | No unrestricted DB/shell/admin tools |

### 3.1 Target authorization model

Define a permission catalogue such as membership.review, report.resolve, content.publish, payment.refund.request, payment.refund.approve, config.publish, role.grant, audit.export and event.resubmit. Every decision considers the actor, operation, resource type/id, geographic/organizational scope, current status, reason, approval, risk and temporary grant expiry as applicable.

Keep the existing account roles separate from administrative permissions. Use Spring Security request/method authorization and explicit resource/scope checks. The UI may hide forbidden actions for clarity, but all requests and source service commands recheck authorization. High-risk operations have separation of duties; a requester cannot approve their own protected payload. Emergency access has a specific scope, expiry, reason and post-use review.

## 4. Information architecture and screen requirements

All pages are permission-aware; deep links remain authorized at the server. RTL, keyboard operation, focus visibility, date/timezone clarity and accessible error/status announcements are first-class acceptance criteria.

| Screen | Purpose/content | Primary interactions |
|---|---|---|
| 01 Command Center | Runtime health, product KPIs, work backlog, risks, release/config changes, stale integrations | Open source card, incident, queue or runbook |
| 02 Global Search | Authorized search over member, institution, report, post, listing, order, booking, payment, source, event/job, release and case refs; filters by scope/status/date | Open authorized entity 360, create linked investigation |
| 03 Work Center | Cross-domain work, totals, oldest age, SLA, priority, owner/team and saved filters | Claim/assign, defer with reason, escalate, bulk preview |
| 04 Case Detail | Case goal, linked records, timeline, assignment, notes, due time, decisions, approvals and source outcomes | Link evidence, request approval, execute source-owned command, resolve/reopen |
| 05 Entity 360 | Shared status, relations, domain history, admin timeline and legal actions | Valid source-owned state transition; open/attach case |
| 06 Incident Desk | Severity, impact, affected scopes/releases, signals, recent changes, traces, runbook | Assign incident, mitigation preview, verify recovery, postmortem |
| 07 Capability Registry | Code-owned capability, owner, consumers, exact switch semantics, scope, fallback and runbook | Set state explicitly, preview scope, approve, publish or retire |
| 08 Configuration Lab | Typed values, schema, current revision, owner, precedence, diff and secret-reference status | Draft, validate, simulate, approve, publish and rollback |
| 09 Policy / Audience / Experiment Studio | Rules, scope, eligible audience, exclusions, sample estimate, hypothesis and guardrails | Preview, simulate, stage, approve, pause or stop |
| 10 Trust & Membership | Claim/evidence, membership, attestations, referrer/source, history and appeal | Request evidence, approve/reject/revoke per domain workflow |
| 11 Safety & Moderation | Reports grouped by source/root cause, content context, rule version, past actions and appeals | Dismiss, hide, escalate, request review, simulate/retire rule |
| 12 Institutions & Local Governance | Representatives, evidence, authorized source, geographic scope, official alerts and history | Verify/reject, grant source authority, manage authorized scope |
| 13 Editorial & Broadcast Studio | Draft/scheduled/live/withdrawn content, audience, approval, provider handoff and delivery | Preview, schedule, publish, correct or withdraw |
| 14 Marketplace & Provider Workbench | Providers, listings/products, stock/availability, promotions, orders/bookings and disputes | Review/freeze listing, inspect fulfillment, refer dispute |
| 15 Finance & Reconciliation | Intent/provider callback/ledger/order/dispute chain, amounts, currency and mismatch class | Safe source-owned reconcile/retry/refund request and approval |
| 16 Services/Property/Jobs/Lending | Domain status, parties, eligibility, schedule/availability and disputes | Owner-valid transition, exception review, case assignment |
| 17 Knowledge & Source Governance | Provenance, trust, scope, freshness, consumers and withdrawn state | Verify, correct, revoke, trigger controlled reindex and verify retrieval |
| 18 Search Quality | Zero results, abandonment, latency, result mix, evaluation set and ranking revision | Inspect cohort, run offline evaluation and authorized reindex job |
| 19 Notifications & Delivery | Template/channel, preferences, audience, delivery ledger, provider result and retry state | Inspect failure, bounded retry, pause template/channel |
| 20 Geo Map & Boundary Studio | Polygons, hierarchy, boundary source/version, overlap and affected-record impact | Draft/import, validate, preview, approve and publish version |
| 21 Mobile Experience Builder | Typed component registry, Android compatibility, device/locale/scope preview, revision diff | Compose screen, validate, preview, stage/publish and rollback |
| 22 Release & Adoption | Channel/version/minimum/mandatory/grace, rollout ring and adoption measures | Draft, compare, stage, publish, pause and rollback |
| 23 Analytics & Experiments | Defined KPIs, funnel/cohort/retention, experiments and freshness | Filter/compare, save report, aggregate export, stop invalid experiment |
| 24 Admin Access Governance | Grants/scopes/expiry, conflicts, last use, access review and security events | Grant/revoke, review, expire emergency access, invalidate sessions if required |
| 25 Audit & Evidence Explorer | Actor/action/target/reason/scope/outcome/approval/trace/time filters | Follow source event, compare safe snapshots, restricted export |
| 26 Background Operations | Import/export/batch progress, attempt history, per-item failures and cancellation policy | Inspect, pause/cancel if safe, restart only when restartable, download expiring result |
| 27 Event & Integration Health | Failed publications, webhooks, delivery ledger, cache invalidation, circuit breakers and dependencies | Inspect runbook, resubmit eligible failures, verify final state |
| 28 Privacy & Data Governance | Subject request lifecycle, export/pseudonymization/purge eligibility, retention/legal hold | Preview, approve, execute per-domain workflow and collect completion evidence |
| 29 AI Copilot | Read-only cited evidence, proposed action diff, data/model/prompt version and cost | Ask, investigate, draft proposal; submit normal command for human-approved mutation |
| 30 Platform Settings & Dependencies | Source of truth, owner, type, health, version and secret reference status | Validate safe configuration and inspect drift; never show secret values |

## 5. Shared UX behavior contract

Each screen must implement loading, empty, no-result, stale, partial-failure, validation, conflict, forbidden, not-found, retryable, permanent refusal, approval-pending and asynchronous-completion states where relevant.

Tables need deterministic sorting (including ID tie-breaker), bounded pagination and explicit filters. Sensitive search terms must not leak through shared URLs or logs. A form for a high-risk action re-reads current source state at write time. Stale revision is a conflict, not a silent overwrite. A bulk operation always previews the exact selection, impact and exclusions before execution. Every result states what happened, what remains pending, and where to see subsequent side effects.

## 6. End-to-end journey catalogue

| Journey | Start → screens and decisions → outcome | Source ownership / evidence needed |
|---|---|---|
| J01 Daily operations | Command Center → review critical signals → assigned/overdue work → inspect recent change/release → acknowledge or open case | Aggregation reads with freshness and source definition; role filtered; no fabricated counts |
| J02 Cross-domain incident | Alert → incident context → scope/release/change analysis → runbook and traces → mitigation preview → approval if required → execute → verify recovery → postmortem | SLO/runtime + capability/config owner + source command; incident timeline; measured recovery and recurrence |
| J03 Emergency kill switch | Capability Registry → verify consumer/effect semantics → choose geographic scope → show affected read/write/background paths → set disabled → verify effective decision and outcome | Capability metadata + feature consumer; disabled-path E2E and propagation measurement |
| J04 Progressive rollout | Define context/cohort → audience preview → exclusion/default validation → stage ring/percentage → monitor guardrails → stop/expand | Feature evaluator + event/guardrail contract; deterministic assignment; only after explicit product/provider decision |
| J05 Live config change | Typed key → owner/schema/current version → draft → server validation → simulation → diff → approval → publish revision → verify consumers | Configuration owner, revision/hash, audit, optimistic concurrency and rollback |
| J06 Mobile layout change | Component registry → compose/translate → preview Android version/locale/scope → compatibility validation → impact preview → approval → staged publish → measure/rollback | Exact client component schema; invalid component rejected; revision and client compatibility tested |
| J07 App release | Release draft → channel/version/changelog/minimum/grace → compatibility/policy review → stage → publish → inspect adoption/guardrails → pause/advance | Existing release contract plus rollout/adoption telemetry only when client events exist |
| J08 Membership decision | Work queue → claim → inspect evidence/referral/neighborhood → approve/reject/request evidence → verify effective member access | Community owner commands; correct scope, reason, audit and post-decision access proof |
| J09 Institution/source verification | Institution detail → representative/evidence/scope → verify authority → approve/reject/refer → grant appropriate source use → validate downstream permission | Institution/knowledge owners; evidence ref; no self-declaration and no automatic role elevation |
| J10 Community report | Deduplicate/group → open content/author/neighborhood/context/history → assess policy → dismiss/hide/escalate/request review → verify visibility/appeal route | Community/reviews/identity owner commands, safe timeline, duplicate/replay tests |
| J11 Moderation rule | Define closed-vocabulary rule → simulate historic sample → inspect estimated hits/false positives → peer review → activate → observe appeal rate → pause/revise | Existing rule owner plus evaluation read path; versioned policy and false-positive measures |
| J12 Bulk operation | Define filter/selection → dry-run count/sample/exceptions → approval above risk threshold → durable chunked execution → per-item status → retry eligible failures → expiring report | AdminOperation + owner commands; selection hash, idempotency, pause/cancel/restart policy |
| J13 Official broadcast | Draft → target audience/geo → eligible-recipient estimate → source/permission check → preview → second approval if high-reach → publish/schedule → monitor handoff/delivery/engagement separately | News/institution/notification owners; immutable publish version and distinct delivery states |
| J14 Commerce order | Search order → order 360 → provider/item/booking state → payment/ledger → notification/dispute trail → legal owner command or linked case | Orders/booking/catalog/payments/ledger/disputes; joined DTO refs and source invariants |
| J15 Financial mismatch/refund | Match order/payment/provider callback/ledger → classify delayed/duplicate/mismatch → preview reconciliation or refund → approval if needed → execute → re-read final ledger state | Payments/ledger owner; amount/currency/provider refs/idempotency; one-effect replay test |
| J16 Admin grant/revoke | Access Governance → inspect effective roles/grants → select permission/scope/expiry → check conflicts/recovery admin → reason/approval → update authority → verify scope | Identity + permission service; audit grant lifecycle and deny cross-scope tests |
| J17 Privacy request | Verify subject request → enumerate domain data/retention → preview impact/legal hold → approve → execute owner workflows → gather receipts → close | Source privacy/export ports and durable orchestration; partial results shown honestly; no direct cross-domain delete |
| J18 Audit investigation | Filter actor/action/target/reason/scope/time → inspect event → follow source/trace/job → compare safe before/after → preserve approved evidence → close | New admin audit plus security audit/Envers/logs; audit read/export tracked; no secrets in output |
| J19 Event/job recovery | Failure signal → inspect age/attempts/source/runbook → verify current state and duplication risk → retry only eligible failed item → verify target state | Modulith publication/job/webhook owner; reason, authorization, idempotency and final-state proof |
| J20 Notification failure | Delivery record → inspect consent/preferences/token/provider result → classify transient/permanent/disabled → safe retry or token prune → verify | Notification owner and provider response; preference-respecting, bounded retry, no token exposure |
| J21 Search regression | Zero-result/latency signal → cohort/scope/result distribution → compare fixed evaluation set and recent index/ranking → approved reindex/measure → compare outcome | Search/discovery owners; genuine query events; privacy-safe aggregates |
| J22 Boundary change | Draft geometry → validate topology/overlap/containment → impact count/sample → approval → publish version → verify effective scope/cache behavior | Geo/PostGIS owner; named hierarchy, impact, new version and rollback semantics |
| J23 Withdraw source | Open source/provenance/consumers → correct or revoke → invalidate/reindex dependent content → test search/AI no longer cites withdrawn source | Knowledge/search/discovery/AI owners; source event and downstream completion visibility |
| J24 Product experiment | State hypothesis/primary metric/guardrails → define audience/assignment → privacy review → launch → inspect assignment/data quality → stop/ship/rollback with evidence | Feature evaluator + analytics contract; stable cohort, real event data and documented limits |
| J25 Export data | Define purpose/dataset/filters/fields → minimize/preview → authorize/approve → queue job → progress → expiring download → audit download/expiry | Durable operation + source export ports; field allowlist, row limits, expiry and access logging |
| J26 Restrict account/appeal | Entity 360 → evidence/trust and prior decisions → choose temporary restriction vs disable vs pseudonymize/purge → approve if required → execute → notify/appeal/expiry review | Identity/domain owner; explicit semantics, duration, affected access and appeal |
| J27 Dependency outage | Dependency health → affected capabilities/runbook → test safe fallback → authorized operational action → verify recovery | Infra/integration owner; no secret values displayed; action scoped and logged |
| J28 AI-supported investigation | Ask question → authorized retrieval → cited source facts/time/uncertainty → action proposal → preview → human uses standard command → verify outcome | Spring AI tools/ports, sources and audit; AI never receives implicit ADMIN or self-approves |
| J29 Approval decision | Inbox → inspect immutable payload/hash/scope/impact/evidence → approve/reject/request changes → notify requester → execute only approved payload | Approval service; separation of duties, expiry and tamper rejection |
| J30 Case closure/learning | Verify source state and side effects → resolution code/root cause → link postmortem/rule/source fix → assign follow-ups → close and measure recurrence | Case overlay + source owners + analytics; closure requires evidence, not a status-only update |

### 6.1 Deep sequence: incident response and kill switch

1. A real alert/report identifies an affected capability, client release, dependency or geography.
2. The Incident Desk shows the signal time, current/last healthy state, relevant recent changes, affected scopes and trace/runbook links. Temporal proximity is evidence to investigate, not proof of root cause.
3. An incident commander sets severity, owner and next review time; a durable case/timeline captures each action.
4. The capability registry explains exact switch semantics: surface visibility only, reads, writes, background jobs, notifications or all of these. If the code does not guarantee the promised effect, the action is blocked or the label is narrowed.
5. Authorization and approval are checked against operator permission, scope, risk and emergency-access policy.
6. Execute an explicit set-state command with expected revision and idempotency key. Persist the control change and required after-commit side effects.
7. Re-read effective state from the same resolver consumers use; verify source behavior or trusted health signal. Until verified, display “in progress”, not “mitigated”.
8. On failure, distinguish stale-revision conflict, unwired consumer, delayed event/cache invalidation and missing measurement. Do not display unknown impact as zero.
9. Close only after recovery evidence and follow-up ownership; re-enable is a new auditable command.
10. Acceptance: E2E proves all claimed read/write paths for the specified capability/scope behave as promised; replay does not toggle; wrong-scope operator is denied; propagation and recovery are measured.

### 6.2 Deep sequence: configuration/mobile layout

1. Pick a capability or component from code-owned metadata rather than typing an arbitrary key.
2. The backend returns owner, schema, allowed values, consumers, supported scope, default, sensitivity and runbook.
3. Create a draft, validate server-side, show a revision diff and preview exact Android/client version, locale, geography and cohort where those dimensions exist.
4. Simulate representative contexts and explain rule precedence, exclusions and estimation limits.
5. Approval points to the exact immutable payload hash. Any change invalidates that approval.
6. Publish a new revision and emit appropriate after-commit effects. Show event/cache invalidation status separately from database commit.
7. Enable staged rollout only if actual audience assignment and client measurement exist; otherwise do not display fictional rollout controls.
8. Rollback creates a new revision from known-good payload.
9. Acceptance covers unsupported component refusal, stale-write conflict, approval immutability, preview/contract equivalence and consumer-effective verification.

### 6.3 Deep sequence: payment exception

1. Correlate stable order, booking, payment intent, provider event, ledger entry and dispute references through owner projections.
2. Present each source state separately. A provider callback, payment intent, fulfillment state and ledger posting are not interchangeable.
3. Classify matched, delayed, duplicate, amount/currency mismatch, orphan, or state conflict.
4. Preview reconciliation/retry/refund including amount, currency, ledger effect, parties, current state and approval threshold.
5. Source services recheck state, permission, concurrency and idempotency; console never edits a ledger record directly.
6. Re-read payment and ledger status after execution; show downstream notifications separately.
7. Acceptance: replay callback/retry in integration tests, prove exactly one financial effect, preserve ledger invariants and reject unauthorized/self-approved refund.

## 7. Proposed backend contract families

These are target contracts, not routes claimed to exist. Reuse existing source endpoints where semantics fit; do not add redundant URLs for cosmetic uniformity.

| Contract | Target surface | Ownership and behavior |
|---|---|---|
| Command Center | GET /api/v1/admin/command-center | Composition/read facade over owner summary ports and observability; each widget has source, scope, freshness and partial failure state |
| Global Search | GET /api/v1/admin/search | Permission-aware domain query ports; typed results/facets/stable paging; authorization rechecked when detail opens |
| Work Center | GET /api/v1/admin/work-items and source-specific actions | Queue adapters plus assignment/SLA overlay; source status remains domain-owned |
| Admin Case | GET/POST /api/v1/admin/cases... | Case-owned assignment/notes/timeline/linked refs; no duplicate source state machine |
| Admin Audit | GET /api/v1/admin/audit-events and restricted export job | Append-only admin command audit; filters, safe snapshots, retention and audit-read tracking |
| Permissions / approvals | Grants, permission catalogue, approval request and decision contracts | Scoped grants/expiry and immutable approval payload; service-layer enforcement |
| Capability registry | GET /api/v1/admin/capabilities and effective state by scope | Code metadata + canonical runtime settings; un-wired keys cannot be marketed as effective |
| Configuration lifecycle | Draft/validate/simulate/approve/publish/rollback contracts | Typed schema, owner, revision/hash, expected-version conflict and consumer-effect verification |
| Policy/moderation | Owner-controlled rules + simulation/evaluation | Closed vocabulary, no arbitrary executable expressions, versioned policy and outcome measures |
| Bulk operation | Preview, submit, status/progress, pause/cancel/retry eligible items, expiring result | Durable operation metadata; commands execute through owner ports; per-item errors and idempotency |
| Product analytics | Typed aggregate query/read model | Versioned source events, metric definitions, privacy and freshness; separate from Prometheus |
| Release/layout | Existing release and planning services expanded with schema, draft, preview and staged controls | Exact Android client component/version compatibility and telemetry-backed adoption only |
| Incident/operations | Specific health/incident/failed-publication/job/retry commands | Runbook, allowlisted action, bounded retry, reason and final-state verification |
| AI assistant | Read-only tool queries and action proposal contract | Each tool checks live operator authorization; all mutation through normal commands and approvals |

### 7.1 Architecture rules

- Keep the modular monolith. Do not create a microservice per screen.
- Cross-domain reads are composed through ports/DTOs; each state-changing command goes through the owner service.
- No shared JPA entity/repository leakage and no generic endpoint accepting arbitrary table, entity, field or SQL.
- Keep deployment/secret configuration separate from operator-owned runtime policy data.
- Keep admin global search separate from public search authorization and projections.
- External monitoring, CI/CD, secret management and hosting tools remain constrained integrations, not a shell/SQL console inside the product.
- Define deadlines, paging, partial result semantics and query budgets for cross-domain screens.
- Treat after-commit side effects as asynchronous until verified. Record the durable source command outcome separately from delivery/consumer completion.

## 8. Proposed target data model and event envelopes

Concrete migration numbers must be assigned only after checking the current main schema and active branches. Never modify an already-applied migration.

### 8.1 AdminCase and timeline

**AdminCase**: id, case type, status, severity, summary, originating signal, scope type/id, assigned team/operator, created/updated/due/closed times, resolution code, reopen count and optimistic version.

**AdminCaseLink**: case id, source module/type/id, relationship label, linking actor/time and safe display metadata where necessary. The linked source remains the record owner; existence/visibility is checked through owner ports.

**AdminCaseEvent**: immutable event id, case id, event type/time, actor, overlay state transition, reason, safe metadata and correlation/trace reference. Internal notes have distinct access/retention rules and are not published to residents by default.

### 8.2 AdminActionAudit

Fields include event id/time, actor subject, safe snapshot of effective permissions/scope, action code, domain, target type/id, geographic/organizational scope, reason, approval ref, expected version, allowlisted before/after snapshots, outcome/failure code, request/correlation/trace IDs, source surface and idempotency key.

The store is append-only and queryable with paged filters. Audit snapshots must not include entire entities, credentials, tokens, provider secrets or arbitrary personal data. Retention and visibility are declared by audit class. The audit query/export is itself restricted and may itself be audited.

For sensitive domain commands, write the admin audit record within the same database transaction where feasible through a narrow shared port. Define the failure semantics explicitly when the source command cannot commit. Security audit, Envers history, logs, domain delivery ledgers and incident timeline are distinct but linkable evidence stores.

### 8.3 Approval records

**AdminApprovalRequest**: id, operation type, target/scope, requester, required reason, request/expiry time, immutable payload hash, expected revision, risk classification, status and execution idempotency key.

**AdminApprovalDecision**: request id, reviewer, decision/reason/time, permission snapshot and approval-policy version. Separate row per decision.

Approval grants permission to execute the exact scope/payload/revision only. A modified payload invalidates approval. Approval does not prove execution succeeded.

### 8.4 AdminOperation

Store operation id/type, initiator, scope, immutable criteria/payload hash, selection semantics, dry-run counts, status, progress counters, started/completed/deadline times, cancelability, failure summary, per-item outcome refs, audit/correlation refs and expiring output metadata.

Possible states: DRAFT → VALIDATED → WAITING_APPROVAL → QUEUED → RUNNING → SUCCEEDED / PARTIAL / FAILED / CANCEL_REQUESTED → CANCELLED, according to explicitly defined semantics. Item operations are idempotent and independently reported. Use Spring Batch where restartable steps, durable execution metadata and graceful shutdown are needed.

### 8.5 Capability/configuration metadata

Code-owned metadata must include stable key, localized title/description, owner, consumers and effect class; schema/type/default/fallback; supported scopes and precedence; sensitivity and approval class; rollback behavior; runbook and instrumentation; version compatibility and retirement plan.

A database row cannot invent a consumer. New capability PRs include the consumer, metadata and end-to-end behavior tests together. Runtime settings must have one authoritative owner and a documented mapping to any legacy storage.

### 8.6 Event consistency rules

- Synchronous domain invariants and source-state commit remain in the owner transaction.
- Use the existing Spring Modulith publication registry for suitable after-commit events.
- External/long-running processing uses durable state owned by the responsible domain/job abstraction.
- Each side effect that influences money, access, content, visibility or notification has an explicit duplicate/retry policy.
- Resubmit only eligible failed/incomplete events with authorization, reason, idempotency and final-state verification—not a blind “retry all” action.

## 9. Product analytics and operational evidence

Every KPI has stable key/name, business definition, numerator/denominator where applicable, source owner, unit, time window/timezone, scope, aggregation/privacy level, refresh frequency, freshness bound and drilldown.

Suggested families:
- Trust: median/p95 membership decision time, evidence-request rate and overturned appeal rate.
- Safety: report volume per active neighborhood, median/p95 resolution, backlog age, recurrence and appeal outcomes.
- Broadcast: publish delay, eligible audience and separate queued/provider-accepted/delivered/opened states.
- Commerce/finance: order/booking completion, payment success, refund/dispute age, mismatch class and ledger-vs-provider discrepancies.
- Search/discovery: zero-result, abandonment, latency, click/conversion by approved query cohort.
- Mobile: version adoption, mandatory-update effect and errors only where actual client telemetry exists.
- Operations: SLO/error budget, database/cache saturation, event age, notification backlog, cache invalidation, circuit breaker and job duration.
- Administration: approval backlog/age, command denial, partial bulk failure, audit completeness and task elapsed time.

Use product events emitted by actual actions, with a versioned event contract, provenance, pseudonymous identifiers where permitted, deduplication, privacy class and retention. Do not use raw user IDs, payment IDs or arbitrary query text as high-cardinality metric labels. Start with existing PostgreSQL projections/aggregates where sufficient. Choose external product analytics/warehouse only after estimating volume, query requirements, data residency, deletion, cost and operational ownership.

For reliability, the repository already has SLO/alert/runbook definitions and operational metrics foundations. Verify whether Prometheus/Alertmanager/Grafana/tracing are actually deployed before surfacing “integrated” status. Incident timeline correlates trace/request IDs, release/config revision, event/job IDs and source records. Every alert has a runbook or an explicit missing-runbook status.

## 10. Technology decision matrix

Exact versions must come from the project’s resolved Maven BOM and compatibility gates, not from whichever documentation page was most recently updated.

| Need | Baseline / candidate | Decision | Cost / risk |
|---|---|---|---|
| Admin web UI | Existing Spring MVC + Thymeleaf; optionally HTMX for partial updates | Retain as viable initial delivery, but do not let it define the final UX ceiling; choose richer frontend only after screen complexity/research warrants it | SPA adds build/deploy/auth/typed-client responsibilities |
| Security | Spring Security request/method authorization and scoped policy components | Reuse framework enforcement plus explicit resource/scope grants; no custom security framework | Requires comprehensive wrong-scope and service-command tests |
| Audit | Boot AuditEventRepository for security; JPA Auditing/Envers for their own purposes; new admin-action audit | Keep histories distinct and link via IDs | Persistence and PII/retention costs; audit failure semantics matter |
| Events | Existing Spring Modulith publication registry | Reuse failure inspection/resubmission; add subscriber idempotency | Retry can duplicate effects without explicit contracts |
| Long jobs | Spring Batch at compatible version | Use for restartable imports/exports and chunk jobs; do not overengineer small commands | Adds repository state and operations burden |
| Runtime observability | Actuator, Micrometer, OpenTelemetry and Prometheus pipeline already indicated in repo | Truth-sync collectors, traces and alert delivery; Actuator metrics endpoint is diagnostic, not backend | External retention/cardinality/sampling costs |
| Technical app console | Spring Boot Admin community project | Optional separate SRE view for multiple Spring applications; not business/marketplace admin | Adds a secured service; avoid duplication with existing stack |
| Feature targeting | Current booleans/geo overrides; OpenFeature Java/provider evaluation if needed | Current convention excludes percentages/A/B. Add only after explicit product decision, deterministic evaluation and event measurement | Provider/SDK and consistency become runtime dependencies |
| Product analytics | Versioned event contract and aggregates first; evaluate PostHog or analytics store after volume review | Keep distinct from Micrometer technical metrics | User data, privacy, retention and vendor dependence |
| Search | Existing PostgreSQL/search/geo capabilities with owner ports | Start with permission-aware federated projections; separate admin/public search | Additional index introduces sync, freshness and access-filter risks |
| Geo UI | Existing Geo/PostGIS owner; MapLibre or other visual map component if frontend chooses it | Reuse geometry and version it; add impact preview | Boundary write errors can affect many users and content |
| Policy rules | Existing closed moderation vocabulary plus sample simulation | Typed rules; no arbitrary code/SQL/SpEL uploaded by operator | False-positive harm; rule simulation needs validated sample data |
| BPM/workflow engine | Source-owned state machines and admin-case overlay; evaluate BPMN/Flowable only if needed | No general engine unless parallel workflows, timers and task routing exceed explicit simple processes | New engine/model/versioning/operational costs |
| AI assistant | Spring AI 2.0.1 tool calling | Read-only cited tools first; mutations become normal human-approved commands | Prompt injection, leakage, model drift and cost |
| Tests/contracts | Testcontainers, MVC/Security tests, Modulith verification, REST Docs and current OpenAPI gates | Expand journey-level tests using real migrations | Integration suite cost; cannot substitute unit mocks for end-to-end proof |
| Accessibility | WCAG 2.2 AA target | Verify keyboard/RTL/contrast/forms/status/error semantics as feature acceptance | No library alone guarantees compliance |

### Official/community reference set

- Spring Boot Actuator auditing: https://docs.spring.io/spring-boot/reference/actuator/auditing.html
- Spring Boot metrics endpoint (diagnostic, not a production metrics backend): https://docs.spring.io/spring-boot/api/rest/actuator/metrics.html
- Spring Boot observability: https://docs.spring.io/spring-boot/reference/actuator/observability.html
- Spring Security 7.1 method authorization: https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html
- Spring Security AuthorizationManager architecture: https://docs.spring.io/spring-security/reference/servlet/authorization/architecture.html
- Spring Modulith publication registry: https://docs.spring.io/spring-modulith/reference/events.html
- Spring Modulith repository: https://github.com/spring-projects/spring-modulith
- Spring Batch JobRepository/restartability: https://docs.spring.io/spring-batch/reference/job/configuring-repository.html
- Spring Batch job configuration: https://docs.spring.io/spring-batch/reference/job/configuring-job.html
- Spring AI 2.0.1 Tool Calling: https://docs.spring.io/spring-ai/reference/api/tools.html
- Spring Boot Testcontainers: https://docs.spring.io/spring-boot/reference/testing/testcontainers.html
- Spring REST Docs: https://docs.spring.io/spring-restdocs/
- OpenTelemetry semantic conventions: https://opentelemetry.io/docs/concepts/semantic-conventions/
- NIST SP 800-162 ABAC: https://www.nist.gov/publications/guide-attribute-based-access-control-abac-definition-and-considerations
- OWASP Logging: https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html
- OWASP Authorization: https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html
- WCAG 2.2: https://www.w3.org/TR/WCAG22/
- Spring Boot Admin community repo: https://github.com/codecentric/spring-boot-admin
- OpenFeature evaluation context: https://openfeature.dev/specification/sections/evaluation-context/
- Google SRE Workbook: https://sre.google/workbook/alerting-on-slos/

Framework references establish mechanisms, not that this project already uses every mechanism correctly. Compatibility must be confirmed against resolved versions and actual source wiring.

## 11. Backend gap register

Classification: PARTIAL = related code exists but target contract is incomplete; GAP = target surface not evidenced; VERIFY = wiring/coverage is unproven; OPTIONAL = explicit decision gate; NOT SAFE TO EXPOSE = UI control is unsafe until specific tests pass.

| ID | Gap / current evidence | Target backend work and acceptance |
|---|---|---|
| G01 Product navigation/capability manifest — PARTIAL: ConsoleView is a route inventory | Task-oriented versioned screen/capability metadata, owners and permission mapping; test that unavailable actions never appear as available |
| G02 Code-owned capability registry — PARTIAL: arbitrary flag rows can be registered | Consumer/effect metadata, default/fallback, scope and runbook; activation of unknown/unwired key refused |
| G03 Lost-found/urgent kill switches — VERIFY; #572 description leaves them for later | Truth-sync consumers, wire all promised paths or narrow/hide control; end-to-end disabled behavior |
| G04 Typed config owner/schema — PARTIAL: strings coexist with system settings/geo/planning | One owner/type/schema/range/default/sensitivity contract per key; no secrets displayed |
| G05 Draft/simulate/approve/publish config lifecycle — PARTIAL: planning has revisions, other config simpler | Immutable revisions/hash, expected-version conflict, approval and consumer-effect verification |
| G06 Explainable geo resolution — PARTIAL: resolver exists, read response returns boolean/id | Return winning scope, named ancestry, fallback/source revision; test precedence |
| G07 Fine-grained scoped authorization — PARTIAL: ADMIN gates and account roles exist | Permission catalog and resource/geography/institution checks at route and service; wrong-scope denial tests |
| G08 Separation of duties/approvals — GAP as universal cross-domain workflow | Approval request/decision, immutable payload hash, expiry, requester/approver separation, service-level check |
| G09 Central admin-action audit — PARTIAL: security audit, Envers, logs and limited console history are different | Append-only command audit with actor/target/reason/scope/safe before-after/outcome/trace/approval; query/export APIs |
| G10 Audit access, retention and integrity — PARTIAL: histories/policies are not one governed explorer | Retention classes, allowlisted redaction, restricted export, archive/integrity strategy and audit-read logging |
| G11 Command center aggregate — GAP | Owner summary ports, freshness/partial errors, query budgets, links to source and evidence |
| G12 Queue totals, filters, assignment/SLA/escalation — PARTIAL: four queues, first-50 slice | Source queue summary/page/detail plus admin assignment overlay; total counts and oldest age; source status remains owner-owned |
| G13 Linked case/investigation model — GAP | Case, links and immutable timeline with SLA/owner/notes; never duplicate source state machine |
| G14 Domain 360 workbenches — PARTIAL: many admin routes exist | Source projections/relations/action contracts and truthful outcomes for each major domain |
| G15 Global permission-aware search — GAP as unified experience | Federated query/read ports, facets, stable paging, safe snippets, authorization recheck on detail |
| G16 Durable bulk/export operations — GAP as unified lifecycle | Dry-run, immutable selection hash, idempotency, bounded chunks, per-item outcome, safe pause/cancel/retry and expiring output |
| G17 Product KPI catalogue/API — PARTIAL: raw meters and some domain metrics | Defined metric metadata, aggregation, scope/freshness and source drilldown; no fabricated values |
| G18 Versioned product event contract — PARTIAL | Source-emitted event taxonomy, dedupe, pseudonymous subject, privacy and retention; funnels/cohorts/notification/search analytics |
| G19 Experiment/progressive rollout — OPTIONAL: current convention excludes it | After explicit decision, deterministic cohort assignment, guardrails, event linkage, pause/rollback and valid result methods |
| G20 Visual mobile builder — PARTIAL: six-section JSON planning | Typed component/schema registry, Android compatibility, preview, translation, audience/scope preview, diff and approvals |
| G21 Release rings/adoption — PARTIAL: version/minimum/mandatory/grace only proven | Telemetry-backed rollout/adoption view, pause/stop and distinct API deprecation semantics |
| G22 Event/job operations UI — PARTIAL: registry/health signals exist | Failure inspection, source link, bounded authorized resubmit, idempotency and verification after retry |
| G23 Payment reconciliation workbench — PARTIAL: payment/ledger/disputes primitives exist | Cross-source read projection, mismatch classification, authorized reconciliation/refund commands and single-effect tests |
| G24 Notification delivery workspace — PARTIAL: notification/token/delivery primitives exist | Filterable delivery ledger, preferences/consent, provider result, bounded retries and no token leakage |
| G25 Geo-boundary versioning/impact preview — PARTIAL: Geo/PostGIS exists | Draft geometry, topology/overlap, affected-record estimate, approval, publication and rollback rules |
| G26 Knowledge provenance/downstream invalidation — PARTIAL: source lifecycle primitives exist | Show provenance/freshness/consumers and reindex status; prove withdrawn source disappears from search/AI reads |
| G27 Search evaluation studio — GAP as admin workbench | Zero-result/abandonment aggregates, fixed evaluation sets, ranking comparison, authorized job and result metrics |
| G28 Durable incident lifecycle — PARTIAL: SLO/rules/runbooks, no full incident UX evidenced | Incident case/timeline, scope, source changes, mitigation approval and recovery proof |
| G29 Unified admin inbox — GAP | Approval/assignment/incident notifications with dedupe and acknowledgement; reuse existing delivery where suitable |
| G30 Privacy request orchestration — PARTIAL: individual privacy/pseudonymize patterns exist | Cross-domain inventory, retention/legal hold, preview, approval, durable owner calls and per-domain completion receipts |
| G31 Governed AI copilot — PARTIAL: Spring AI module exists, proposed admin copilot unproven | Read-only scoped tools, citations, versions/evals, prompt-injection tests, redaction and cost limits |
| G32 Policy/config simulation — GAP | Read-only evaluator using exact production resolver on representative contexts; estimates clearly labeled |
| G33 Accessible error/state API contract — VERIFY | Stable problem/error taxonomy and UI semantics for validation/conflict/forbidden/stale/partial outcomes; admin accessibility tests |
| G34 End-to-end journey assurance — PARTIAL: source tests exist | Real migration + permissions + source command + events/cache + result + audit coverage for each journey |
| G35 Capacity/cost evidence — VERIFY | Benchmark dashboard/search/audit/jobs/analytics/export before adding infrastructure; document owner, SLO and cost decisions |

## 12. Execution waves and dependencies

These are delivery slices, not cuts to the target.

### Wave 0 — Truth sync
Re-read SYSTEM.md, PROJECT_MAP.md, governing plans, open PRs, current main, all admin mappings, consumers, templates, tests, migrations and deployment settings. Produce a source-linked inventory classified COMPLETE/PARTIAL/GAP/VERIFY/NOT SAFE/OPTIONAL. Verify which telemetry collectors/alerts truly run and which are only declared. Reconcile active migration numbers before assigning a new one.

### Wave 1 — Control plane and safe authorization
Capability metadata, typed config owner map, wired switch validation, effective geographic resolution, scoped permissions, approval model and central admin-action audit. Acceptance: no false control, no wrong-scope access, no stale overwrite, and audited high-risk action.

### Wave 2 — Work center, case and global search
Owner read ports, totals/age/filter/paging, assignment/SLA overlay, case timeline, related-entity links and authorized global search. Acceptance: an operator finishes sample work from the console without ad-hoc token tooling or direct DB access.

### Wave 3 — Domain workbenches
Trust/identity, community/safety, institution/content, commerce/orders/bookings, finance/ledger/disputes, services/property/jobs/lending, knowledge/sources and notification delivery. Each command remains owner-owned and shows its actual outcome.

### Wave 4 — Incident and operations center
Event publications, webhooks, scheduled/batch tasks, cache invalidation, providers, circuit breakers, incident timeline and runbook actions. Failure injection in test/CI proves one correct recovery without duplicate effect.

### Wave 5 — Mobile layout and release governance
Typed component contracts, visual preview, client-version compatibility, localization, diff, approval and rollback. Add rollout rings only after valid audience assignment and mobile telemetry exist.

### Wave 6 — Product analytics and experiments
Versioned event taxonomy, aggregates, funnels/cohorts, notification/search quality, saved views and experiment evaluation. Choose external analytics only after cost/privacy/volume decision.

### Wave 7 — Bulk operations and privacy
Durable operation lifecycle, progress/per-item errors, safe resume/cancel, expiring exports and cross-domain privacy request workflow with retention/legal hold and completion receipts.

### Wave 8 — AI copilot and simulation
Read-only evidence retrieval, cited explanations, policy/config simulation, action proposals and controlled evaluations. No autonomous destructive/high-risk action.

### Wave 9 — Scale and assurance
Load/query budgets, audit retention, scoped-security regression, accessibility review, E2E traceability, REST Docs/OpenAPI, Testcontainers migrations, Modulith verification and dependency/security scans. Calibrate thresholds from actual workloads.

## 13. PR-level traceability requirements

Every implemented journey gets a maintained record containing:
1. Journey ID/business goal.
2. Persona and permission/scope.
3. Trigger and origin screen.
4. Screens and UI states.
5. Source owner for each field.
6. Read query/port, paging and freshness.
7. Write command/source state machine.
8. Migration/schema evidence where relevant.
9. Events and async effects.
10. Audit fields/reason policy.
11. Approval policy and immutable payload/revision.
12. Idempotency/concurrency behavior.
13. Failure/timeout/conflict/stale/partial-completion branches.
14. Observability/KPI.
15. Unit/security/MVC/integration/E2E test IDs and results.
16. Accessibility/localization.
17. Remaining gap, decision owner and exclusion rationale.

A route/controller test or HTTP 200 alone never marks a journey complete.

## 14. Definition of Done

### Product
- Every workbench has named persona, task, navigation path, data hierarchy, legal actions and failure/empty/stale/conflict states.
- The 30 journeys above are mapped to detailed screens, state transitions and reviewed end-to-end across community, trust, institution, commerce, finance, operations and privacy.
- Every metric has definition/source/freshness and drilldown.
- UI meets keyboard/RTL/accessible-status expectations with WCAG 2.2 AA as a target.
- High-risk actions have appropriate preview, reason, approval and recovery semantics.

### Backend
- Reads use owner projections/ports and authorize every resource.
- Writes call the source owner and recheck current state, permission, scope, version and idempotency.
- High-risk actions have durable queryable audit with safe snapshots, reason, approval and outcome.
- Long work is durable, bounded and restart-safe where necessary.
- Event/notification/job/payment effects have duplicate/retry semantics and are not labeled complete before proof.
- Real migration schemas are used in Testcontainers; applied migrations are never edited.
- Modulith verification, security/API/coverage gates, REST Docs and E2E evidence pass.
- SYSTEM.md, PROJECT_MAP.md, governing plans and traceability matrix are kept synchronized in each implementation PR.
- No destructive production-data tests; no new external system without measured need, operating owner, cost model and runbook.

## 15. Architectural conclusions

1. Keep the modular monolith. Do not create a microservice per admin screen.
2. Avoid a generic CRUD engine. Build a few well-defined cross-cutting capabilities (case, audit, approval, durable operation) plus source-specific ports.
3. Keep authentication audit, entity revision history, admin-action audit, domain delivery ledgers and incident timelines separate but linkable.
4. Reuse Spring Security, Modulith, Actuator/Micrometer/OTel, Testcontainers, REST Docs and Spring Batch where requirements justify them.
5. Treat a progressive-rollout provider, analytics warehouse, workflow engine, Spring Boot Admin, or additional search index as explicit decisions based on scale, privacy and cost—not default badges of a “global” platform.
6. The operator sees work, evidence, scope, policy and outcome, not the backend module graph.
7. AI is a cited evidence assistant, not an identity/permission engine. Every tool call rechecks the acting administrator's actual authority.

## 16. Official and trusted references

- Spring Boot Actuator auditing: https://docs.spring.io/spring-boot/reference/actuator/auditing.html
- Spring Boot metrics endpoint (diagnostic, not production backend): https://docs.spring.io/spring-boot/api/rest/actuator/metrics.html
- Spring Boot observability: https://docs.spring.io/spring-boot/reference/actuator/observability.html
- Spring Security 7.1 method authorization: https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html
- Spring Security AuthorizationManager architecture: https://docs.spring.io/spring-security/reference/servlet/authorization/architecture.html
- Spring Modulith publication registry: https://docs.spring.io/spring-modulith/reference/events.html
- Spring Modulith repository: https://github.com/spring-projects/spring-modulith
- Spring Batch JobRepository/restartability: https://docs.spring.io/spring-batch/reference/job/configuring-repository.html
- Spring Batch job configuration: https://docs.spring.io/spring-batch/reference/job/configuring-job.html
- Spring AI 2.0.1 Tool Calling: https://docs.spring.io/spring-ai/reference/api/tools.html
- Spring Boot Testcontainers: https://docs.spring.io/spring-boot/reference/testing/testcontainers.html
- Spring REST Docs: https://docs.spring.io/spring-restdocs/
- OpenTelemetry semantic conventions: https://opentelemetry.io/docs/concepts/semantic-conventions/
- NIST SP 800-162 ABAC: https://www.nist.gov/publications/guide-attribute-based-access-control-abac-definition-and-considerations
- OWASP Logging Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Logging_Cheat_Sheet.html
- OWASP Authorization Cheat Sheet: https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html
- WCAG 2.2: https://www.w3.org/TR/WCAG22/
- Spring Boot Admin community repository: https://github.com/codecentric/spring-boot-admin
- OpenFeature evaluation context: https://openfeature.dev/specification/sections/evaluation-context/
- Google SRE Workbook: https://sre.google/workbook/alerting-on-slos/

Framework references establish mechanisms, not that this project uses every mechanism correctly. Compatibility must be tested against resolved versions and actual code wiring.

---

**Honest completion statement:** This document defines the target product, journeys, backend contract families, candidate records, gap register and implementation waves. It does not claim the target is implemented or every proposed interface exists. The first implementation PR must re-verify current main, migrations, open PRs and runtime wiring; every journey earns COMPLETE only with evidence.
