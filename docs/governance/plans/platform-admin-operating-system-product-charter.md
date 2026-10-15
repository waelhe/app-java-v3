# Platform Administration Operating System
## Product Charter, Experience Architecture, and Backend-Independent Delivery Contract

**Status:** Canonical target-state product design; not yet implemented  
**Design date:** 15 October 2026  
**Authority:** This charter defines the product and user experience. The companion [End-to-End Product and Gap Specification](platform-admin-end-to-end-product-spec.md) inventories current backend evidence, proposed domain contracts, detailed journeys and gaps. If that document appears to limit this target, this charter wins.

---

## 0. The decision: design the operating system, not an admin page

The target is a **global Platform Administration Operating System (PAOS)**: a dedicated, high-trust command environment from which authorized people can understand the entire platform, find any important object, make a justified decision, coordinate work, control experiences, handle incidents, and prove the outcome.

It is not a collection of CRUD pages generated from existing endpoints; not a dashboard restricted to current controllers, entities, schema or metrics; not a beautified version of the existing Thymeleaf console; not a new frontend attached to an assumed or abandoned UI repository; not an AI chat window with unrestricted access; and not a set of buttons that says “success” after an HTTP request without verifying the side effect.

### Product constitution

1. **The desired operator outcome defines the product.** Existing code is evidence of present capability, not the boundary of the product.
2. **The admin frontend is an independent product.** It must live in a dedicated repository selected or created for this product. No existing UI repository is presumed suitable. The repository waelhe/web-marketplace-platform is explicitly excluded as a foundation.
3. **The backend is replaceable.** The present Spring platform is one possible adapter and source of domain capabilities. If it cannot meet target contracts, reliability, security or outcome semantics, the plan may replace, split or rebuild it.
4. **The interface does not write domain tables directly.** It issues authorized, typed commands to the system that owns each domain operation, and verifies the result.
5. **Every important action leaves evidence.** Intent, actor, active scope, policy decision, preview, approvals, command, execution, side effects and final outcome must be correlated.
6. **Global power does not mean unbounded personal privilege.** The experience can manage a worldwide platform while preserving least privilege, separation of duties, privacy boundaries and emergency controls.
7. **The operator is shown uncertainty honestly.** Stale telemetry, missing data, delayed events and unverified effects are visibly marked; zero is never fabricated from missing data.
8. **AI accelerates understanding; it does not silently acquire authority.** It must cite evidence, expose uncertainty, propose actions and defer authorization to deterministic policy.
9. **Design for the whole journey.** A feature is not complete because its page exists; it is complete when the operator achieves the goal, recovers from failure and verifies the real-world effect.
10. **No framework lock-in in the product contract.** UI framework, BFF, domain implementation and storage can evolve independently behind versioned interfaces.

“No limits” means the vision must not be artificially reduced to current code, a preselected repository, or a small CRUD backlog. It does not remove security, legal, correctness or operational safety requirements.

## 1. The operator promise

A capable platform director can answer these questions from one place:

- **What needs my judgment right now?** High-impact risks and decisions are prioritized ahead of vanity charts.
- **What changed, why, and who or what is affected?** Signals link to source evidence, related entities, recent changes and the relevant geographic or business scope.
- **What can I do, and what will happen if I do it?** Every action identifies its target, effects, scope, risk, dependencies and approval requirements before execution.
- **Who is working on this and what is blocked?** Work, ownership, deadlines, escalation and approvals are visible end-to-end.
- **Did the action really work?** The system checks resulting state and downstream delivery. A requested retry is not a successful retry, and a published notice is not proof of delivery.
- **Can I change the platform experience safely?** Capabilities, policies, targeting, mobile layouts, releases and experiments have validation, preview, staged rollout, observation and rollback or compensation.
- **Can I prove what happened later?** The complete evidence and audit chain is searchable and exportable under explicit permission.

## 2. The core mental model

PAOS is organized around a connected operating graph, not a backend module tree:

**Signal → scope → entities → evidence → case/work → policy decision → proposed command → preview → approval → execution → observed outcome → audit/learning.**

Each major object can be entered from several contexts without duplication. A payment issue may be discovered on the command centre, attached to a customer case, linked to an incident, shown in a finance workbench, and included in a service-health report. These are contextual views of the same payment, command and evidence.

### 2.1 Universal object types

| Object | Meaning | Non-negotiable behavior |
|---|---|---|
| Signal | Metric breach, report spike, failed delivery, customer complaint, anomaly or human-raised concern | Includes source, time, scope, confidence/freshness and drill-down |
| Entity | Member, neighborhood, institution, post, listing, provider, order, payment, booking, release, event/job or integration | Canonical identity, owner, status and relationships |
| Evidence | State snapshot, event reference, audit entry, telemetry, proof, relevant policy/rule version or delivery receipt | Provenance, time, permissions and integrity metadata |
| Case | Cross-domain investigation or decision with objective, owner, due time, links and timeline | Survives retries, team changes and backend migration |
| Task | Bounded work item within a case or workflow | State, ownership, due time, reason and completion criteria |
| Command | Requested state transition against a source-owned target | Typed, authorized, idempotent where appropriate and traceable |
| Approval | Decision about an immutable command/payload and scope | Cannot be reused for materially changed payload; separation of duties applies |
| Run | Execution record for synchronous or long-running command | Queued/running/succeeded/failed/partial/cancelled and verified effects |
| Policy decision | Why an actor may or may not perform an action in context | Policy/version, scope, reasons and obligations without leaking secrets |
| Capability | Product/platform behavior with owner, consumers, semantics, fallback and controls | A switch is effective only after its consumers and effects are proven |
| Revision | Immutable version of config, policy, template, layout, rule or release | Validation, diff, author, approval, activation and compensation history |
| Audit event | Record of who requested/performed what, against which target and with what outcome | Correlates request, approval, execution, resulting changes and evidence |
| Experiment | Hypothesis and controlled exposure with eligibility, guardrails and outcome criteria | Preflight, exposure limits, monitoring and explicit stop criteria |

### 2.2 A first-class geographic and organizational scope

The platform spans countries, cities, neighborhoods, institutions, businesses and delegated operational teams. The UI keeps the **current data scope** and **operator authority** separate.

A scope selector may narrow data to a city or neighborhood; selecting it never grants permission. Each row and action is checked against the actor, resource, action, geography/organization, environment, policy context and temporary grant. The scope appears in deep links and command previews so a global-looking screen cannot silently operate on the wrong population.

### 2.3 One source of truth per domain; one joined operator experience

PAOS provides a cross-domain experience but must not create competing domain truth. The membership owner decides membership transitions; the finance owner decides ledger/refund transitions; the publishing owner decides publication state; the release owner decides rollout state. A case or workflow may coordinate them but must not write their persistence directly.

## 3. The actual interface: mission control for a complex platform

The design should feel like a premium operational cockpit: clear, calm, information-dense, fast under pressure, and unmistakable about risk. It must not resemble a wall of tiny charts or a generic SaaS admin theme.

### 3.1 Universal shell

**Persistent top bar**
- Product/tenant identity and explicit environment (production, test or another named environment); production is unmistakable.
- Global geographic/organizational scope selector paired with an “authority in this scope” indicator.
- Universal search and command palette (Ctrl/Cmd+K) for entities, cases, actions, runbooks, policies and navigation.
- Live alert tray with severity and freshness; no “green” status if telemetry is missing.
- My work, approvals requiring me, notifications and current shift.
- Operator profile, active grants, session-security state and step-up authentication for sensitive actions.

**Left navigation**
- Collapsible, keyboard-operable domain navigation grouped by operator intent.
- Favorites and saved workspaces, pinned cases, recently opened entities and recent scopes.
- Dynamic navigation only where useful; it never hides an authorization denial by pretending a function does not exist. Inaccessible actions explain the needed permission when policy permits disclosure.

**Main workspace**
- One primary task canvas at a time; consistent breadcrumbs and filter state.
- List/detail split views for queues, with saved filters, keyboard shortcuts and fast drill-down.
- Entity 360, case and incident pages share an interaction grammar.
- Long-running operations remain visible while the operator navigates elsewhere.

**Context rail**
- Optional right-side panel for related entities, evidence, ownership, policy result, recent events, open cases and proposed next action.
- Expands into a full detail workspace without losing the current task or filter context.

**Global work drawer**
- Personal/team workload, approvals, assigned cases, escalations, long-running operations and recently completed work.
- Each item shows owner, deadline, current blocker and the operator’s next action.

### 3.2 Command centre layout

The landing page is not one universal analytics dashboard for every employee. It is a role-aware command centre whose modules are backed by the operator's work and authority.

1. **Critical now:** incidents, harmful regressions, security alerts, financial integrity problems or high-impact user-facing failures, ordered by impact and confidence.
2. **Decisions waiting for me:** approvals and irreversible/high-risk choices, with due times and separation-of-duties state.
3. **Work at risk:** cases breaching SLA, unowned critical queues, repeated failures and tasks blocked by another team.
4. **Experience health:** real user journeys (verification, posting, search, booking/order, payment, notification and onboarding), with denominators, trends, segments, source, freshness and drill-down.
5. **Platform health:** dependencies, event/job lag, errors/latency, rollout health and incident status, correlated with product impact.
6. **What changed:** recent releases, capability/config/policy changes, high-impact admin commands and notable shifts in product metrics.
7. **Next best operational steps:** explainable recommendations linked to evidence and the underlying task/action; dismissals record why when needed.

The page prioritizes decisions and effects. Charts are secondary to actionable queues, meaningful changes, confidence and drill-down. No decorative KPI card exists without a source, definition, period, scope and freshness.

### 3.3 Shared page patterns

**Queue workspace:** queryable server-side filtering, sorting and grouping; saved/shared views; bulk selection with preview; SLA and ownership; accessible keyboard navigation; counts marked as exact, partial or stale.

**Entity 360:** identity and state; owner/scope; legal next transitions; related entities; evidence; domain timeline; administrative timeline; open cases/approvals; recent commands/events; notifications and downstream outcomes. Show differences across sources when data has not converged.

**Case/incident workspace:** objective and impact; commander/owner; affected scope and cohorts; linked evidence; chronological timeline; tasks and handoffs; runbook; approvals; commands/outcomes; customer communication; resolution criteria and post-incident review.

**Change studio:** editable draft; schema validation; semantic diff; sample/audience preview; dependency/conflict check; risk tier; approval; staged activation; live observation; stop/rollback or compensation; revision history.

**Bulk operation studio:** scope, included/excluded rows, permission failures, estimated effect, duplicates/conflicting targets, irreversible subset, sample rows and downloadable validation report before execution. Large operations run asynchronously with resumability, per-item outcomes and a cancel path where safe.

**Map and neighborhood workspace:** country/city/neighborhood hierarchy, permitted aggregate heatmaps, boundary overlays, local incidents, membership claims, content/report signals and official sources. Sensitive individual locations are never exposed by default.

### 3.4 Visual and interaction language

- Strong hierarchy and generous space around high-risk decisions; dense tables may be compact but never unreadable.
- Default light theme for long shifts, with a tested dark theme; theme does not encode severity by color alone.
- Small semantic palette: neutral, information, success, warning, critical and unknown/stale. Text/icon labels accompany color.
- Purposeful short motion that respects reduced-motion settings; animation never hides state or blocks urgent operations.
- Arabic-first layouts with real RTL behavior—not just right-aligned text—plus English and future locales. Tables, maps, charts, shortcuts, numbers, currency, dates and bidirectional identifiers are tested in both directions.
- Responsive desktop-first information architecture with functional tablet support. Essential incident acknowledgement, task assignment and safe stop controls remain usable at narrow widths; complex authoring and bulk work may require a larger viewport with a clear explanation.
- Accessibility target: WCAG 2.2 AA, full keyboard operation, visible focus, semantic controls, non-color statuses, screen-reader announcements for asynchronous outcomes and zoom/reflow testing.

## 4. Information architecture

Navigation follows operator responsibilities and real user journeys, not package names or endpoint groups.

| Workbench | Core pages and capabilities |
|---|---|
| **01 — Command Centre** | My shift; global pulse; critical signals; experience health; release impact; global map; what changed; next actions |
| **02 — Work & Decisions** | My work; team queues; case manager; task board; approvals; escalations; SLA/routing; bulk operation studio; handover and shift report |
| **03 — Global Explore** | Universal search; saved investigations; relationship explorer; entity 360; evidence viewer; timeline and command history |
| **04 — People, Identity & Trust** | Members; membership claims; verification evidence; referrals/attestations; institutions/representatives; appeals; account security; privacy profile; trust-source registry |
| **05 — Community & Safety** | Posts/discussions; reports; coordinated abuse; moderation rules; enforcement/appeals; lost-and-found; groups/events; neighborhood health; safety response |
| **06 — Official Reach & Publishing** | News/editorial; broadcast/urgent alerts; audience builder/simulation; scheduling; institutional source authority; delivery receipts; correction/withdrawal history |
| **07 — Commerce & Money** | Providers/business directory; products/listings; property; jobs/services; inventory/availability; orders/bookings; lending; disputes; promotions/ads; payment intents; ledger/reconciliation; refunds; settlement |
| **08 — Product Experience** | Onboarding/verification funnel; discovery/search relevance; ranking diagnostics; subscriptions/preferences; notification quality; feature/capability registry; experiments; mobile home/layout studio; releases/adoption |
| **09 — Platform Reliability** | Service/dependency map; incidents; SLO/error budgets; traces/logs/profiles; event publications; jobs/workflows; webhooks/integrations; delivery pipelines; circuit breakers; caches/indexes; runbooks; deployment history |
| **10 — Configuration & Policy** | Typed settings; policy studio; geographic rules; notification routing; taxonomy; moderation rules; capability controls; secrets references; schema validation; approval/revision history |
| **11 — Data & Intelligence** | Metric catalogue; data lineage/freshness; funnels; cohorts; retention; exports; search evaluation; experiment results; anomaly evidence; data-quality cases |
| **12 — AI & Knowledge** | Copilot; approved knowledge/source registry; retrieval/provenance; evaluation datasets; model/router configuration; quality/cost budgets; prompt/model revisions; AI action policy; incident controls |
| **13 — Security, Privacy & Audit** | IAM; permission/scope explorer; just-in-time access; access reviews; policy simulation; audit search; privacy requests; retention/deletion; evidence export; compliance cases |
| **14 — Platform Governance** | Capability ownership; service catalogue; dependency/version registry; environment differences; operational readiness; migrations/compatibility; deprecation; change calendar; recovery exercises |

A capability may appear in several workbenches through the same canonical entity. The UI must not create a separate copy of a record just because another team needs a different view.

## 5. The universal command lifecycle

Every state-changing UI uses one command protocol. Low-risk actions may use a compact preview; high-impact operations must expose every phase.

1. **Intent:** choose a named business action (pause capability, approve institution, publish alert, request refund), not a raw database edit.
2. **Target resolution:** resolve canonical target, current state, owning domain and expected version. Fail closed if ownership or target is ambiguous.
3. **Context:** capture actor, environment, active scope, reason, case/incident link and relevant user/customer impact.
4. **Policy decision:** evaluate action, resource, context, authority, conflicts of interest, approval and step-up requirements.
5. **Preview/dry run:** show proposed change, exact audience/records, dependencies, downstream effects, irreversible consequences, estimated blast radius and uncertainty.
6. **Approval:** where required, bind approval to an immutable payload hash/version. The requester cannot approve their own protected action. Changed payload invalidates approval.
7. **Execution:** submit a typed command with idempotency key, correlation/trace ID, expected version and durable operation ID.
8. **Progress:** long-running work exposes queued/running/retrying/partial/failed/cancelled states and per-item results. The app remains usable.
9. **Verification:** query the source owner or effect receipt. Distinguish “requested,” “accepted,” “applied,” “delivered” and “verified.”
10. **Closure and audit:** persist request, decision, previews, approvals, execution steps, outcome, actor and evidence; attach it to the case and update work queues.
11. **Recovery:** offer safe retry, compensation, rollback-to-new-revision, handoff or escalation. Never promise rollback for an irreversible effect.

Required command metadata includes command and idempotency identifiers, correlation ID, actor, scope, target reference, action, expected version, reason, case reference, policy decision, approval references, timestamp, status, result reference and structured recovery details. These are illustrative contract fields, not a predetermined database schema.

## 6. The Super Manager journey — arrival to verified outcomes

The super manager is an orchestrator, not a god-mode user who edits everything. The experience combines strategic context, tactical execution and evidence-backed decisions.

### Phase A — Arrive and orient

1. Authenticate through a dedicated admin identity boundary, with MFA/step-up available for sensitive actions.
2. See the environment, personal authority, active scope, on-call/shift state, outstanding approvals and a concise briefing of important changes since handover.
3. Separate confirmed incidents, suspected anomalies and stale/unavailable telemetry.
4. Open a critical signal and its impact view without losing the command-centre context.

**Success:** the manager knows what is urgent, what is uncertain, what they may do, and what must be delegated or approved.

### Phase B — Understand the whole problem

5. Traverse the evidence graph: affected users/neighborhoods, domain entities, recent releases/config changes, related reports, event/job failures and traces.
6. Use global search or the copilot to assemble an investigation; every finding links to evidence and timestamps.
7. Convert the investigation into a case/incident, state an objective, choose severity and affected scope, appoint a commander and create work items.
8. Assign tasks to authorized teams; recipients inherit case context and evidence, not a detached notification.

**Success:** one shared case, one timeline, named owners and clear recovery criteria.

### Phase C — Contain safely

9. Choose a validated mitigation: disable/narrow a capability, pause a release/experiment, disable a failing integration, reroute a notification path or apply a scoped safety control.
10. See affected scope, known consumers, estimated blast radius, current revision, fallback behavior and uncovered consumers that make the control unsafe.
11. Request emergency access or independent approval when policy requires it. Pre-authorized emergency controls show authorization basis, scope and automatic expiry.
12. Execute and observe effects. If the effect does not arrive, mark containment unverified and escalate rather than showing false success.

**Success:** risk is mitigated with a known blast radius and evidence-backed containment.

### Phase D — Resolve the underlying journey

13. Open a joined domain workbench with case context retained.
14. Complete source-owned operations—review evidence, correct source records, reconcile payment status, fix delivery, restore a provider/listing or repair the member experience.
15. Use previews/per-item outcomes for batch work; route protected actions through independent approval.
16. Verify the user-facing journey through source state and outcome telemetry.

**Success:** the root problem is fixed, not merely hidden by a dashboard state change.

### Phase E — Restore and learn

17. Stage restoration: re-enable a capability, resume rollout, restore delivery or publish a corrected mobile/config revision only after guardrails pass.
18. Check error budget, journey metrics, affected cohorts and recurrence window.
19. Record root cause, contributing factors, successful mitigations, unresolved risk and follow-up tasks.
20. Generate a shift/incident report, transfer ownership if needed, and close only when explicit exit criteria hold.

**Success:** recovery is verified, the chain is auditable, follow-up work is owned, and the platform learns from the incident.

## 7. End-to-end journey catalogue

Each journey must be implemented and tested from initiating screen through actual side effect. A successful page render or API response alone is not completion.

| ID | Trigger and goal | Required UX path | Completion evidence |
|---|---|---|---|
| J01 — Start shift | Understand the platform now | Briefing → priority signals → my work/approvals → saved incident or queue | Scope, freshness, ownership and deadlines are visible |
| J02 — Investigate anything | Trace a problem across domains | Search → entity 360 → relationship graph → evidence → create/link case | Canonical references, no duplicated records |
| J03 — Neighborhood safety | Contain high-impact local issue | Signal/map → neighborhood/member/content context → incident → policy → mitigation → assignment → recovery | Geographic blast radius and verified containment |
| J04 — Platform degradation | Stop faulty capability/release | Experience/SLO signal → recent change diff → capability preview → approval/emergency policy → execute → verify | Consumers known; journeys recover; action audited |
| J05 — Configuration change | Safely tune behavior | Typed editor → schema/secret-reference validation → diff → simulation → approval → stage → observe → promote/rollback | Active revision and observed effect match intent |
| J06 — Mobile experience | Manage layout and release | Visual studio → device/version preview → content binding/accessibility → audience preview → approval → staged release → adoption/rollback | Rendered versions, adoption and rollback verified |
| J07 — Member verification/appeal | Decide membership in a scope | Claim/evidence → identity/member 360 → source/referral checks → policy → decide/request evidence → notify → appeal | State, evidence, authority and notification reconcile |
| J08 — Coordinated harmful content | Stop repeated abuse without overreach | Report cluster → accounts/content/neighborhood links → evidence/rule review → action preview → review → apply → appeal/monitor | Per-target outcomes and false-positive/appeal history |
| J09 — Official broadcast | Send correct notice to right audience | Draft → source authority → audience simulation/map → reach/exclusions → localization → approval → publish → delivery → correction | Publish, audience, approval and delivery separately recorded |
| J10 — Payment exception | Resolve mismatch safely | Order/booking/payment/callback/ledger/dispute graph → classify → source-owned reconcile/retry/refund request → approval → verify | No direct ledger edit; final amount/currency/ledger reconcile |
| J11 — Provider/listing problem | Protect members while resolving service issue | Report/order/booking → provider/listing 360 → customer impact → scoped restriction/communication → provider case → resolve/restore | Related customer outcomes tracked; restriction justified |
| J12 — Notification failure | Restore delivery for channel/region | Spike → provider/channel/template/segment → samples/correlation → fallback → retry/reroute → receipt verification | Requested/delivered/duplicate/failed counts and provider response |
| J13 — Large bulk operation | High-volume action safely | Query → selection/scope → dry run → conflict/unauthorized targets → sample diff → approval → background run → per-item results | Partial success explicit; resumable; no false all-or-nothing claim |
| J14 — Experiment/rollout | Test change and stop on harm | Hypothesis → eligible audience/exclusions → guardrails → staged exposure → segment outcomes → expand/pause/stop | Reproducible exposure and enforced stop criteria |
| J15 — Search regression | Improve discovery with evidence | Journey KPI → query/cohort/relevance → index freshness → examples/evaluator → offline evaluation → guarded release → compare | Evidence-backed quality change with rollback |
| J16 — Privacy request | Fulfil data request safely | Verify subject/authority → discover systems/retention → scope review → export/delete/restrict → approval → run → receipt/audit | Required systems, exceptions and retained legal basis recorded |
| J17 — IAM/access | Grant/revoke correctly | Identity/permission graph → scope simulation → least-privilege grant/revoke → step-up/approval → effective-access test | Effective access verified; temporary grant expires |
| J18 — Failed job/event | Recover async operation | Run workbench → source entity/trace → retryability/deduplication preview → retry/replay → verify downstream effect | No duplicate business effects; run and final effect agree |
| J19 — AI-assisted investigation | Shorten diagnosis time | Question → evidence-linked answer/uncertainty → inspect context → action proposal → standard preview/policy/approval path | AI cannot bypass command lifecycle; evidence references logged |
| J20 — Cross-country policy | Change global behavior safely | Policy diff → countries/scopes/localizations → compatibility/legal review → simulation → regional approvals → staged rollout | Exceptions and exact policy revision traceable |
| J21 — Metric dispute | Resolve contradictory dashboards | Metric catalogue → owner/definition/lineage/time → freshness → compare versions → data-quality case | No silent override; definition/correction history visible |
| J22 — Integration incident | Repair provider/webhook | Dependency graph → requests/certificates/secret reference → deploy/config diff → secure repair → eligible replay → verify | No secret leakage; retries bounded and deduplicated |
| J23 — Official source dispute | Prevent unauthorized publishing | Source/representative 360 → proof/authority → restriction preview → independent review → notify/appeal → restore/revoke | Publishing rights distinct from previously delivered messages |
| J24 — Shift handover | Keep operations continuous | Unresolved cases → pending decisions → active operations → risks/deadlines → handover owner → acknowledgement | Ownership, open risk and acknowledgements persisted |

### Definition of done for every journey

Each journey needs a named persona/scope, authority, entry points, read and command contracts, domain owner, failure modes, loading/empty/partial/stale/denied/conflict/timeout/retry states, risk-appropriate preview and approval, real-adapter integration or clearly labelled contract-only mode, post-action source/effect assertion, audit/evidence assertion, recovery test, and applicable RTL/accessibility/responsive coverage.

## 8. Risk tiers and permission design

| Tier | Examples | Minimum control |
|---|---|---|
| R0 — Read | Authorized records, metrics, audit | Scoped read policy, freshness and provenance |
| R1 — Reversible low impact | Assign task, internal note, update draft | Authorization, optimistic concurrency, audit |
| R2 — Material scoped change | Moderate content, verify member/institution, ordinary publication | Reason, impact preview, source-owner check, confirmation |
| R3 — High impact/financial/security | Refund, sensitive export, high-reach broadcast, broad moderation, IAM grant, production rollout | Step-up auth, policy simulation, immutable payload, independent approval where required, idempotency, verified effect |
| R4 — Emergency/break-glass | Pre-authorized containment, global kill switch, security response | Tight scope, reason/basis, short expiry, strong audit/alert, independent post-use review, recovery test |

Risk tier follows action semantics and context, not URL or UI location. It can rise with audience size, geography, amount, data sensitivity or blast radius. Emergency response is pre-designed, not an unaudited bypass.

### Access control target

Use a single authorization contract combining job function/role, explicit action permission, resource relationship and scope, environment/state/risk, separation of duties, temporary grants, reason/evidence, approval and step-up authentication. The browser improves usability but never establishes authority.

A production candidate is relationship-aware authorization (for example OpenFGA) combined with contextual policy (for example Cedar) behind one policy decision contract. Adopt both only if a proof of concept proves distinct responsibilities and one canonical decision/audit trail. Otherwise choose one engine/model to avoid split-brain authorization.

## 9. Backend-independent contract architecture

The frontend depends on a capability contract, not the current controller map or JPA model.

### 9.1 Logical architecture

1. **Dedicated Admin Web App:** presentation, routing, workflows, accessible design system and client cache.
2. **Admin BFF/API façade:** same-origin session boundary, query aggregation, request correlation, response normalization and frontend-specific read models. It does not own domain truth.
3. **Identity and Policy boundary:** identity/session, effective permissions, scope, risk decisions, step-up requirements and approval constraints.
4. **Admin Control Plane:** cases, assignment, approvals, operator grants, command orchestration, capability/config revisions, experiment metadata and admin audit/evidence index.
5. **Domain systems:** member/trust, community/safety, publishing, commerce/finance, search/discovery, notifications, mobile experience and other domain-owned transitions.
6. **Durable workflow/event plane:** long-running operations, retries, scheduled work, delivery receipts, event lineage and correlation.
7. **Observability/product intelligence:** traces/metrics/logs, SLOs, user-journey metrics, funnel definitions, freshness and incident signals.
8. **Evidence/audit store:** integrity-protected, queryable records of intent, policy, approval, command, execution and result with explicit retention/access controls.

These are logical boundaries, not a mandate to create eight deployables. Deployment topology is an architectural decision justified by reliability, scale, security and ownership.

### 9.2 Mandatory API/event contract

- Versioned OpenAPI contracts for synchronous query/command APIs; generate frontend types from published contracts.
- Versioned event contracts (AsyncAPI and/or CloudEvents conventions) for changes, execution progress, delivery receipts and product signals.
- Capability manifest with owner, supported actions/scopes, config schema, status semantics, dependencies, fallback, rollout semantics, health evidence and runbook.
- Server-side authorization on every call; machine-readable non-sensitive effective-scope and policy information.
- Query APIs with scope filtering, pagination/cursors, sorting, saved-view semantics and server-side large-data processing.
- Mutations with expected-version/concurrency checks, idempotency, command/run IDs, correlation IDs, structured conflict/failure details and recovery guidance.
- Long-running operations with durable status/progress and per-item results.
- Dry-run/preview for material changes, including exact affected audience and policy constraints.
- Events with event time, schema version, source, correlation/causation IDs and deduplication semantics.
- Domain-owned commands and outcomes remain separate. A generic “admin update any entity” endpoint is prohibited.
- Missing/unsupported capabilities render as unavailable with owner/reason and path to fulfilment, not as fake live buttons.

### 9.3 Current backend versus replacement

The present app-java-v3 backend can be integrated only after passing these contracts. The companion gap register guides the adapter. A capability is marked supported only after contract conformance and side-effect tests; partial is explicitly constrained; unavailable is not exposed as a live button.

If the current backend cannot provide correct command semantics, policy enforcement, source ownership, event/effect verification, audit, scale or reliability at acceptable cost, create a new implementation or replace the relevant slice. The UX, cases, workflows and contract suite survive the replacement unchanged.

**Do not choose or adapt a frontend repository until it passes a documented fitness review.** The intended home is a dedicated new admin product repository. The existing web-marketplace-platform repository is not a candidate.

## 10. Technology recommendation for a standalone frontend

Choose for operational clarity, security, accessibility, large workspaces and replacement—not hype. Recheck and pin versions at implementation time; prereleases are never silently accepted.

### 10.1 Recommended starting stack

| Layer | Candidate | Why it fits | Gate/caution |
|---|---|---|---|
| UI runtime | **React 19.3+** | Mature ecosystem; current official React release lists stable View Transitions and Fragment Refs | Pin patched versions and follow official security advisories |
| Language | **TypeScript strict** | Types for page state, contracts, commands and permissions | Enable strict and unchecked-index checks; runtime-validate trust boundaries |
| Build/dev | **Vite 8.1+** | Current Vite line uses Rolldown and mature React tooling | Verify plugin/browser compatibility; measure experimental modes before use |
| Routing | **TanStack Router** | Typed routes, validated URL/search state and nested workspaces | URL/route state is not authorization |
| Server state | **TanStack Query v5+** | Query cache, mutations and invalidation fit operational screens | Define stale-time, refetch, invalidation and event-stream rules centrally |
| UI behavior primitives | **React Aria Components** | Accessibility, interaction and internationalization behavior | Build one product design system above it; avoid inconsistent primitive libraries |
| Styling/tokens | **Tailwind CSS 4.x + CSS design tokens** | Fast consistent implementation and theming | Use semantic tokens, not repeated arbitrary values |
| Enterprise grids | **AG Grid Enterprise, server-side row model** | Large server-owned datasets, grouping and advanced operator tables | Paid product; licensing decision and spike required. Alternative: TanStack Table + virtualization, with more UX to build |
| Charts | **Apache ECharts** | Rich operational/cohort/time-series visualization and accessibility features | Use only where informative; provide data table/description fallback |
| Maps | **MapLibre GL JS** where needed | Flexible vector-map foundation | Set map/tile/geocoding/data-license and privacy requirements separately |
| API types | OpenAPI-generated TypeScript + runtime validation such as Zod | Prevent manual contract drift and validate untrusted payloads | Generated types do not replace runtime validation or server authorization |
| Tests | **Vitest + Playwright + Storybook interaction/a11y tests** | Unit/component, real-browser E2E, visual/accessibility regression | Contract tests and final-effect assertions required; mocks never prove production completion |
| Telemetry | **OpenTelemetry conventions** surfaced by the selected telemetry backend | Correlate user interaction, BFF, domain operation and effect | Select/size infrastructure using measured retention and cost |
| Auth/session | **OIDC Authorization Code + PKCE**, preferably same-origin BFF session with Secure/HttpOnly/SameSite cookies | Keeps long-lived privileged tokens out of browser storage and centralizes policy checks | CSRF, session rotation, step-up MFA, reauthentication and CSP need explicit tests |
| Long-running workflows | Evaluate **Temporal** for durable multi-step work and compensation | Useful for approvals, bulk jobs, releases and recovery across failures | Adopt only where durable orchestration is actually needed |
| Policy | One policy-decision contract; evaluate **Cedar** and relationship-based authorization such as **OpenFGA** | Separates UX from fine-grained action/scope decisions | POC must prove one decision authority, test corpus and explainable audit |
| AI orchestration | Provider/model abstraction with approved tools and evidence retrieval | Model changes do not grant more platform authority | No unrestricted shell/SQL/admin tools; every action uses standard lifecycle |

**Why SPA + BFF as the baseline?** This is a privileged authenticated workspace, not a public SEO site. Prefer a same-origin SPA and secure BFF so browser responsibilities stay explicit and server-action semantics are not introduced without a demonstrated need. SSR can be added for a measured requirement.

**Current-framework note (15 October 2026):** TanStack Start 1.0 was announced stable on 13 October, and its official blog records a reflected-XSS security update on 30 September. It is a serious candidate for a time-boxed comparison, but its very recent 1.0 release means it should not replace the simpler SPA+BFF baseline without security review, patched-version confirmation, deployment testing and operational proof. This is controlled risk, not a rejection of modern technology.

### 10.2 Frontend engineering shape

Organize the new repository by product capability, not one huge flat components folder:

- **app-shell:** identity/session bootstrap, global navigation, command palette, scope, theme and layout.
- **design-system:** tokens, accessible primitives, semantic tables, forms, status patterns, charts, maps and Storybook.
- **capabilities:** one folder per operator workbench with route, queries, commands, local domain types and tests.
- **entity-graph:** universal entity references, relationships and timelines.
- **work-management:** cases, tasks, approvals, SLAs, bulk runs and handover.
- **command-runtime:** preview/submit/status, idempotency, optimistic concurrency and recovery UI.
- **policy-experience:** effective scope, policy explanation, step-up and approval constraints; presentation only, never policy authority.
- **contract-clients:** generated API clients, event schemas and adapter conformance tests.
- **observability:** interaction correlation, errors, UX performance and telemetry controls.
- **ai-copilot:** evidence-grounded responses, proposals, citations and explicit human approval.

Enforce module boundaries, typed route/query keys, no raw fetch in page components, no direct database access, no hidden cross-workbench state, and no hard-coded permission assumptions.

### 10.3 Security baseline

- Dedicated admin audience/client, distinct from the public/mobile customer experience.
- Short-lived server-managed sessions; Secure/HttpOnly/appropriate SameSite cookies; CSRF defense; session revocation/rotation.
- MFA and step-up for R3/R4, tied to fresh intent and scope.
- CSP, strict dependency/update policy, pinned lockfile, vulnerability scanning, SBOM and reproducible builds.
- Server authorization on every call; deny by default; re-evaluate scope at execution time.
- No secrets in UI, URLs, local storage, audit messages or traces. Secret values are references accessed by controlled rotation workflows.
- Redaction and field-level access for PII; privacy-aware logs, exports and retention.
- Integrity-protected audit trail with separate write/read privileges and monitored export.
- Dual control for sensitive operations; emergency grants are bounded and expire.
- Test cross-scope access, stale/revoked permission, session revocation, CSRF, payload tampering, replay/idempotency, approval binding and unauthorized direct URL/API access.

## 11. Product data, analytics and AI rules

### 11.1 Experience-level observability

For every primary member journey, define the start/completion events, success/failure/abandonment/recovery, denominator and segments, owner/source/time window/freshness/blind spots, drill-down to permitted evidence/case, and alert/runbook where warranted.

Distinguish a system metric (CPU/latency/errors), a product outcome (verification success, search usefulness, payment completion, official-notice delivery) and an admin workflow metric (case age, backlog, approval wait). They are correlated, not interchangeable.

### 11.2 Copilot behavior

The copilot may summarize cases/shifts with source links, compare before/after outcomes without asserting unproven causality, find related objects, propose investigation steps, draft communications/runbooks/config diffs, estimate impact and prepare typed command proposals.

It may not grant itself access, execute unrestricted SQL/shell/direct writes, approve its own proposal, bypass human approvals, treat untrusted content as instructions, claim success without checking a result, or omit uncertainty/provenance/freshness.

Tool calls use explicit capabilities, data scope and budgets. High-risk proposals show source evidence, assumptions and a human-readable diff. The platform can turn off an AI capability through a tested control path.

## 12. Quality gates: how done is proven

### Product and UX
- All J01–J24 journeys have an owner, acceptance criteria, screen map, empty/error/partial/denied/stale states and recovery.
- Shell, work list, entity 360, case/incident workspace, command preview and status patterns are coherent.
- Arabic RTL, English LTR, keyboard, screen reader and WCAG 2.2 AA automated/manual checks pass.
- Layout works at agreed desktop/tablet/zoom sizes; dense tables remain operable.
- User tests measure time-to-triage, context switches, failed actions and outcome verification against a baseline; targets are not invented.

### Contracts and backend
- Contract types generated and compatibility checked in CI.
- Each capability declares reads, commands, owners, scopes, risk, side effects, events and recovery.
- Every command rechecks authorization and records audit/correlation.
- Long-running operations have durable status/resume policy.
- Every switch/release/config has verified activation/compensation.
- Payment/privacy/IAM/high-reach commands have appropriate dual control, idempotency and audit.
- The same adapter conformance suite runs against current and replacement backends.

### E2E and operations
- Browser journeys verify real APIs and final source/effect state. Mock journeys are labeled prototype/contract-only.
- Failure injection covers timeout-after-commit, duplicate delivery, stale version, partial bulk outcome, worker restart, revoked permission and unavailable dependency.
- Audit tests correlate actor/scope/intent/policy/approval/command/result/evidence.
- Load-test large grids, filters, scope changes, maps, streams and long-running bulk operations.
- Security review includes threat model, permission matrices, direct API tests, CSP/session/CSRF and dependency/SBOM review.
- Runbooks, owners, dashboards, recovery drills, retention and support handover exist.

A merged PR is not completion. State whether a capability is visual prototype, contract-complete against test adapter, integrated with real backend, or verified E2E in target environment.

## 13. Delivery strategy

This plan is product-first. It assumes neither the existing console nor backend is the final destination.

### Wave 0 — Product foundations
Approve this charter; create task-based wireframes and interactive prototypes for command centre, entity 360, work centre, incident desk and command preview. Test representative operator tasks and freeze semantics before optimizing screen count.

### Wave 1 — Independent product home
Create a dedicated admin UI repository after a new repo is created or a candidate passes an explicit fitness review. Set up React/TypeScript/Vite, module rules, design system, Storybook, test stack, lint/type gates, security workflow and preview deployment. Do not begin from the abandoned web-marketplace-platform repository.

### Wave 2 — Contract-first core
Define capability manifest, OpenAPI/event schemas, entity refs, case/command/approval/run/audit contracts and adapter conformance tests. Generate clients and realistic contract fixtures. Prototype data stays visibly labelled as simulated.

### Wave 3 — Admin identity and shell
Implement dedicated session, MFA/step-up, scope display, command palette, saved workspaces, access-denial explanation, audit correlation and accessible shell.

### Wave 4 — Universal work system
Deliver global search, entity 360, case timeline, assignments, tasks, approvals, escalation, saved queries and safe bulk operations.

### Wave 5 — Command and evidence runtime
Implement preview/dry run, policy result display, payload binding, idempotency, long-running status, final-state verification, compensation/retry UI and audit/evidence drill-down.

### Wave 6 — First high-impact verticals
Select operational journeys by measured risk and user pain, not easiest endpoints. Recommended pilots: platform incident/kill switch; member/institution verification; report/appeal; official broadcast; payment/reconciliation. Every pilot uses the same E2E gate.

### Wave 7 — Experience and reliability plane
Add capability/config lab, mobile layout/release studio, notification delivery, events/jobs, observability correlation, journey analytics, search quality and staged rollout.

### Wave 8 — Governance and intelligence
Add IAM/policy administration, privacy, advanced audit/export, cross-country policy, experiment management, AI knowledge/citations and constrained copilot actions.

### Wave 9 — Scale and resilience
Load-test large grids, global scope switches, maps, streams and bulk work; test failover/reconnect, stale data, concurrency and worker restarts; perform security/accessibility audits.

### Wave 10 — Backend adapter decision
Run the same contract/journey suite against app-java-v3. For every gap, decide whether to implement an adapter/capability or replace/rebuild the relevant slice. Sunk cost and the current module list are not reasons to preserve a weak design.

## 14. Decisions that must not be made by accident

- **No assumed UI repository.** Dedicated new admin repository unless a candidate proves fitness in a written scorecard.
- **No current-backend ceiling.** Missing contracts/models/workflows remain requirements, not items deleted to fit current code.
- **No auto-microservices.** Logical capability boundaries are mandatory; deployment boundaries follow measured needs.
- **No arbitrary framework chasing.** Stable versions, security advisories, maturity, accessible primitives and contract quality outrank novelty; experimental features require benchmark and approval.
- **No generic superuser escape hatch.** Cross-domain authority is governed, with independent approval where appropriate.
- **No button without an outcome.** Every write reaches a source owner and proves the resulting effect.
- **No fake live data.** Demo/test adapter state is visibly labelled and never masquerades as production.
- **No AI bypass.** All proposals use the same authorization, preview, approval, command and audit path as human actions.
- **No merge equals completion.** Status describes verified end-to-end completion and environment.

## 15. Official technical references (checked 15 October 2026)

- [React 19.3 release](https://react.dev/blog/2026/09/09/react-19-3) — current React release and stable transition APIs.
- [Vite 8.1 release](https://vite.dev/blog/announcing-vite8-1) — current Vite line and Rolldown direction.
- [TanStack Router docs](https://tanstack.com/router/latest/docs/framework/react) — typed route and search-state foundation.
- [TanStack Query v5 docs](https://tanstack.com/query/latest/docs/framework/react) — server-state cache and mutation model.
- [React Aria](https://react-spectrum.adobe.com/react-aria/) — accessible interaction and internationalization primitives.
- [Radix accessibility](https://www.radix-ui.com/primitives/docs/overview/accessibility) — alternative primitive foundation; do not casually mix competing primitives.
- [Tailwind CSS](https://tailwindcss.com/docs/installation/framework-guides) — token-driven styling and Vite integration.
- [AG Grid server-side row model](https://www.ag-grid.com/react-data-grid/server-side-model/) — advanced large server-owned datasets; licensing is a separate decision.
- [Apache ECharts features](https://echarts.apache.org/en/feature.html) — multidimensional data, dynamic charts and accessibility.
- [OpenTelemetry semantic conventions](https://opentelemetry.io/docs/concepts/semantic-conventions/) — common names for traces, metrics, logs and resources.
- [Cedar policy language](https://docs.cedarpolicy.com/) — decoupled, testable authorization policies.
- [OpenFGA documentation](https://openfga.dev/docs) — relationship-based authorization; use behind one canonical contract.
- [Temporal documentation](https://docs.temporal.io/temporal) — durable workflow execution and recovery semantics.
- [TanStack Start stability and security blog](https://tanstack.com/start/latest/docs/blog) — confirms recent 1.0 stability and records the September 2026 security update.
- [Storybook accessibility testing](https://storybook.js.org/docs/writing-tests/accessibility-testing) — component-level checks, not a substitute for manual/E2E audits.

---

## Final target statement

The product is a new, independent command environment for governing the entire community-and-commerce platform. It gives the manager a connected view of people, places, trust, content, services, commerce, money, mobile experience, data, AI and reliability; converts signals into authorized, coordinated, evidence-backed action; and proves outcomes.

The present backend is evaluated against that promise. It does not define it.
