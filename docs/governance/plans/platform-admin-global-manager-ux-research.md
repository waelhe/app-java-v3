# Global Admin UX Research — Manager-Centred Product Design
## Evidence from leading products, open-source repositories, and community practice

**Research date:** 15 October 2026  
**Purpose:** Replace the previous code/module-centred approach with an experience organized around what a non-technical platform director needs to accomplish.  
**Scope:** Public product documentation, official user guides, community moderation practice, and public source repositories. This is desk research, not in-person usability testing or private access to product interfaces.

---

## 1. Executive conclusion

The previous design made a category error: it described the platform's capabilities and grouped screens around those capabilities. It expected the manager to learn our system before the system could help the manager.

The redesigned experience must start from the manager's operating model:

1. What needs my attention today?
2. What outcome am I trying to improve for members, neighborhoods, businesses or the platform?
3. What must I decide, change or delegate?
4. How will the change look to real members in the places and versions it affects?
5. How can I test it safely, publish it, observe what happens, and recover if the result is poor?
6. What evidence proves whether the goal was achieved?

The important design direction is not “more dashboard cards.” It is a product in which the director can **shape member-facing experiences, configure business rules, coordinate work, launch changes and verify outcomes without knowing the application's internal code or deployment details**.

### Recommended experience

Build a manager-facing **Platform Control Studio** around four connected modes:

- **Run the platform:** daily decisions, people and places, community safety, marketplace operations, financial issues and live member impact.
- **Shape the member experience:** visual editor for home surfaces, navigation, content rows, member journeys, audience targeting, localization and device/version previews.
- **Set how the platform behaves:** guided rule and automation builder, membership/trust policies, moderation rules, routing, notification logic, taxonomy, fees/promotions and capability availability.
- **Improve outcomes:** journey funnels, goals, cohorts, experiments, feedback, quality signals and recommended next actions.

Every change moves through one familiar **Change Centre**: draft → check → preview/simulate → approval where needed → staged release → observe → promote/pause/restore. The manager should not have to learn a separate publish process for every product area.

The current backend and module names are not the organizing principle. They are implementation concerns hidden behind stable product capabilities.

---

## 2. Products studied and concrete lessons

This section distinguishes directly documented behavior from our design conclusions.

### 2.1 Shopify Admin — the business day, not the data model

**Official material:** [Shopify Admin](https://help.shopify.com/en/manual/shopify-admin) and [Shopify Home](https://help.shopify.com/en/manual/shopify-admin/shopify-home).

The official guide describes Home in terms of daily tasks, next actions on orders, sales activity, personalized recommendations, and actions to grow the business. Open order tasks include capturing payment, fulfilment, returns, high-risk orders and chargeback evidence. Home cards can contain data-led insights and guided next steps. Home metrics are user-customizable rather than forcing every staff member to see the same four metrics. [Shopify Home](https://help.shopify.com/en/manual/shopify-admin/shopify-home)

**Lessons for our design**
- The start page should explain what needs attention and what the user can do next—not list every system capability.
- Separate **work waiting for action** from **performance signals**.
- Let managers personalize their work view while retaining mandatory risk and approval alerts.
- Show a task card in business language, then reveal supporting analytics when the manager drills in.

**Do not copy blindly:** Shopify optimizes for a store's commerce operations. Our home must span neighborhood trust, community health, business supply, member experience, commerce, revenue and safety.

### 2.2 Airbnb Hosting — performance is local, comparable, and goal-oriented

**Official material:** [Tracking hosting performance](https://www.airbnb.com/help/article/2500), [professional hosting tools](https://www.airbnb.com/help/topic/1489), and [hosting essentials](https://www.airbnb.com/help/article/3116).

Airbnb separates daily/operational and planning surfaces: Today, Calendar and Listings. Its Insights surface covers conversion, occupancy/rates, quality and hosting progress, with historical/current/future views, region filters, and comparisons against previous periods or similar local listings. [Airbnb performance guide](https://www.airbnb.com/help/article/2500)

**Lessons for our design**
- Organize decisions around the manager's real objects: neighborhoods, institutions, business listings, services and member journeys.
- A platform-wide number is often not actionable; managers need comparisons by city/neighborhood, member cohort, app version, product area and time.
- Performance information should lead to action: improve a listing, fix a journey, contact a provider, adjust a local initiative.
- Keep everyday work separate from deeper trend analysis, while linking them.

**Do not copy blindly:** A host manages inventory they own. Our director governs a two-sided, multi-role network of residents, institutions, businesses, moderators, service providers and official information sources.

### 2.3 Intercom Inbox — queue, ownership, reusable actions and automation

**Official material:** [Inbox setup](https://www.intercom.com/help/en/articles/10223008-setting-up-the-inbox), [assignment](https://www.intercom.com/help/en/articles/6561699-assign-conversations-to-teammates-and-teams), and [macros](https://www.intercom.com/help/en/articles/6584504-using-macros-in-the-inbox).

Intercom supports manual/automatic assignment to a teammate or team and reusable macros that group repeated actions such as tagging, assigning, snoozing and closing. Its guided setup treats routing and repeated work as workflow design rather than individual clicks. [Intercom Inbox](https://www.intercom.com/help/en/articles/10223008-setting-up-the-inbox)

**Lessons for our design**
- A decision can bundle the routine follow-up actions into one governed operation.
- Every work item needs a clear owner, team, deadline, status and next action.
- Automate repetitive work with named, editable, testable rules—not hidden developer behavior.
- Queue triage, assignment, escalation and communication belong together.

**Do not copy blindly:** A customer-support inbox is narrower than a global platform. It is a useful work pattern, not the shell for everything.

### 2.4 Jira Service Management — work is a queue with a reason for being there

**Official material:** [About queues](https://support.atlassian.com/jira-service-management-cloud/docs/what-are-queues/) and [checking out queues](https://support.atlassian.com/jira-service-management-cloud/docs/check-out-your-queues/).

Jira queues gather work items matching defined conditions. They support custom filters and columns, prioritized groups and often SLA/time-to-target ordering. The work item provides context to triage and assign work. [Jira queues](https://support.atlassian.com/jira-service-management-cloud/docs/what-are-queues/)

**Lessons**
- Build team-owned queues around questions: “Who needs review?”, “Which issue is near missing its response target?”, “What is waiting on approval?”
- Give owners enough context to decide without bouncing across many pages.
- Let managers configure queue criteria, visible columns, sorting, assignment and escalation.
- Make deadlines meaningful in plain language.

**Do not copy blindly:** One universal queue will overwhelm users. Provide goal-specific queues and a combined leadership view.

### 2.5 Salesforce Lightning Console — preserve the record and work context

**Official material:** [Navigate Lightning Experience](https://trailhead.salesforce.com/content/learn/modules/lightning-experience-for-salesforce-classic-users/navigate-around).

Salesforce documents navigation to objects, favorites, recent records and console apps, providing task-oriented layouts. [Salesforce navigation](https://trailhead.salesforce.com/content/learn/modules/lightning-experience-for-salesforce-classic-users/navigate-around)

**Lessons**
- A director should inspect related records without losing the current case or queue position.
- Use a consistent relationship panel and activity history across member, neighborhood, institution, post, provider, order and incident.
- Support favorites, recent records, saved workspaces, keyboard navigation and return to the prior context.

**Do not copy blindly:** A dense console with many open records can overwhelm a non-technical director. Default to one active decision with optional side-by-side detail.

### 2.6 Discourse moderation — community context matters more than CRUD

**Community/admin material:** [Discourse moderation guide](https://meta.discourse.org/t/discourse-moderation-guide-part-3-managing-content/406266) and [review-queue layout announcement](https://meta.discourse.org/t/a-new-review-queue-layout-with-all-new-features/388194).

Discourse combines reviewable users, queued posts and flagged content in a prioritized queue. Filters appear at the top; reviewers can add notes and see a timeline; item details include previous flags and trust context. Actions are intentionally distinct—hide, keep visible, edit, suspend, restore, delete—and policy can apply different review rules by category. [Discourse moderation](https://meta.discourse.org/t/discourse-moderation-guide-part-3-managing-content/406266)

**Lessons**
- Moderation is contextual judgment: show content/thread, place, policy, prior decisions and appeal path together.
- Use shared triage for reports, suspicious members and queued content, but preserve distinct action semantics.
- Record moderator notes and chronological decisions in the same context.
- Allow policy customization without coding, then monitor false positives, repeat harm and appeal outcomes.
- Distinguish temporary hiding, permanent removal and account restrictions.

**Do not copy blindly:** Our platform needs verified neighborhood membership, institutional authority and geographic scope; a forum trust score alone cannot authorize membership or official publishing.

### 2.7 PostHog — analytics and controls must close the loop

**Official material:** [Dashboards](https://posthog.com/docs/product-analytics/dashboards), [feature flags](https://posthog.com/docs/feature-flags), [experiments](https://posthog.com/docs/experiments), and [product analytics](https://posthog.com/docs/product-analytics).

PostHog supports shared insights across dashboards, filters by properties/cohorts, funnels and retention, and feature flags for controlled rollout, targeting, experiments and kill switches. Exposure can be connected to outcomes, so a team can observe whether a rollout worked rather than merely whether it was issued. [PostHog flags](https://posthog.com/docs/feature-flags) · [experiments](https://posthog.com/docs/experiments)

**Lessons**
- Analytics is not a separate reporting island: a result links to the experience, segment and action that may improve it.
- Give each goal a reusable scorecard and cohort rather than duplicating chart copies.
- Support “compare current experience with proposed experience,” staged rollout, guardrails, pause and restore.
- Audience-targeted controls need an understandable eligibility preview and measurable exposure.

**Do not copy blindly:** Feature flags are not a full member-experience editor. We need structured visual controls and tested capabilities, not a bag of toggle keys.

### 2.8 Power Apps — assemble role-specific work without code

**Official material:** [Model-driven app design](https://learn.microsoft.com/en-us/power-apps/maker/model-driven-apps/design-custom-business-apps-using-app-designer).

Microsoft describes role/function-specific apps assembled from forms, views, dashboards, process flows and configurable navigation, with a visual site-map designer and validation before publishing. [Power Apps design](https://learn.microsoft.com/en-us/power-apps/maker/model-driven-apps/design-custom-business-apps-using-app-designer)

**Lessons**
- Provide a visual composition surface for member-facing layouts and manager workspaces.
- Use approved components/templates with clear properties, data source, eligibility and fallback.
- Validate dependencies before publishing.
- Let managers create views without bypassing access policy or inventing arbitrary backend writes.

**Do not copy blindly:** A model-driven app can inherit a data-table-first feel. Our editor must start from a member journey and device preview, not from a schema.

### 2.9 Directus Studio — visual control for content, relationships and workflows

**Official material:** [Directus repository](https://github.com/directus/directus) and [Data Studio overview](https://docs.directus.io/user-guide/overview/data-studio-app).

Directus describes a visual management Studio for non-technical teammates, with content/assets, relationship management, permissions, flows and dashboards. [Directus Studio](https://docs.directus.io/user-guide/overview/data-studio-app)

**Lessons**
- Give managers visual control over content, taxonomy, institutions, member-facing sections, relationships and automation.
- Use structured forms and guided actions; advanced representations are secondary.
- Permissions belong near UI affordances but are always re-enforced on execution.
- Reusable visual flows fit routine notifications, assignment, review and escalation.

**Do not copy blindly:** A generic data studio often organizes around collections and CRUD. Our primary mental model must be member journeys and platform outcomes. Directus is a reference for no-code capabilities, not a selected base repository or database abstraction.

### 2.10 Appsmith — resource permissions and audit

**Official material:** [Granular access control](https://docs.appsmith.com/advanced-concepts/granular-access-control) and [audit logs](https://docs.appsmith.com/advanced-concepts/audit-logs).

Appsmith documents granular permissions for apps, resources, workflows, users and environments, plus audit events for role/group changes and assignments. [Appsmith access control](https://docs.appsmith.com/advanced-concepts/granular-access-control) · [audit](https://docs.appsmith.com/advanced-concepts/audit-logs)

**Lessons**
- Distinguish who may view, edit, publish, run, approve and administer.
- Explain permission decisions and retain their history.
- Attribute operations to actors/groups and audit access changes.

**Do not copy blindly:** Appsmith serves internal-tool builders. A director should not navigate queries, data sources or developer environments.

### 2.11 Linear — separate incoming triage from strategic goals

**Official material:** [Triage](https://linear.app/docs/triage) and [Initiatives](https://linear.app/docs/initiatives).

Linear describes Triage as a place to review, update and prioritize incoming work before it enters a team's workflow. Initiatives group projects around objectives so leadership can see progress without drilling into every issue. [Linear Triage](https://linear.app/docs/triage) · [initiatives](https://linear.app/docs/initiatives)

**Lessons**
- Separate incoming signals from committed work.
- Group execution under director goals, e.g. improve neighborhood activation, reduce unresolved safety reports, improve order completion.
- Leadership needs owners, priorities, dependencies and outcome progress, not a flat ticket list.

**Do not copy blindly:** Issue tracking is not the product-control layer; goals must link to editable experiences, policies and measurable outcomes.

### 2.12 GitHub organization administration — bounded delegation over omnipotence

**Official material:** [Organization roles](https://docs.github.com/en/organizations/managing-peoples-access-to-your-organization-with-roles/roles-in-an-organization).

GitHub provides organization roles and custom roles to delegate selected settings without giving everyone unrestricted control. [GitHub organization roles](https://docs.github.com/en/organizations/managing-peoples-access-to-your-organization-with-roles/roles-in-an-organization)

**Lessons**
- “Complete control” means complete governability through product capabilities—not an unaudited bypass.
- Define permissions by task, target scope, data sensitivity and publish/approve authority.
- Separate access changes from content/trust decisions and use independent approval for material actions.

### 2.13 Apple Human Interface Guidelines — agency, familiarity, feedback, recovery

**Official material:** [Design principles](https://developer.apple.com/design/human-interface-guidelines/design-principles), [feedback](https://developer.apple.com/design/human-interface-guidelines/feedback), and [settings](https://developer.apple.com/design/human-interface-guidelines/settings).

Apple emphasizes people's goals, direct action, mistake recovery, familiar concepts, clear feedback and minimizing unnecessary settings. [Design principles](https://developer.apple.com/design/human-interface-guidelines/design-principles) · [feedback](https://developer.apple.com/design/human-interface-guidelines/feedback) · [settings](https://developer.apple.com/design/human-interface-guidelines/settings)

**Lessons**
- Keep the common task close; move rare/technical controls out of the main path.
- Make drafts, history, previous revisions and safe restore first-class.
- Keep feedback adjacent to the object it describes; transient toasts are not enough.
- Guided flows should help, not trap; allow experienced users to jump directly to a record or saved view.

---

## 3. Open-source repository study

The following public repositories were inspected through their README or relevant source files. The objective is to learn interaction and product-shell patterns; none is presumed a suitable starting repository for PAOS.

| Repository | Evidence inspected | Useful pattern | What it does not solve for our product |
|---|---|---|---|
| [saleor/saleor-dashboard](https://github.com/saleor/saleor-dashboard) | README, src/index.tsx, src/config.ts, src/extensions/extensionMountPoints.ts | Commerce surfaces, consistent navigation, extension points at product/order details, explicit API configuration | Does not unify neighborhood governance, trust, member-experience composition and global operations |
| [medusajs/medusa](https://github.com/medusajs/medusa) | packages/admin/dashboard/src/dashboard-app/routes/get-route.map.tsx and English translations | Central route map, protected routes/permission guards, shared layouts, lazy sections, error boundaries and centralized product-language strings | Route organization still follows ecommerce domains; must not dictate our top-level UX |
| [PostHog/posthog](https://github.com/PostHog/posthog) | Dashboard docs and products/dashboards/backend/run_insights_output.py | Reusable insight/dashboard concepts and deliberate query/result budgets; analytics tied to cohorts and exposure | Analytics is not an experience editor or cross-domain case manager |
| [directus/directus](https://github.com/directus/directus) | Repository README and official Studio docs | Visual content/data management, access controls, low-code flows and dashboards | Generic data management starts from records/collections; our primary object is a member outcome/journey |
| [twentyhq/twenty](https://github.com/twentyhq/twenty) | packages/twenty-docs/getting-started/key-features.mdx | Configurable business objects, views/pipelines, workflows and automation | CRM relationships are useful, but incomplete for geographic social trust and community safety |
| [appsmithorg/appsmith](https://github.com/appsmithorg/appsmith) | Repository README and access-control/audit documentation | Fine-grained resource access, auditable operations, reusable internal tools | Internal-tool builder concepts should stay behind product-level controls |
| [makeplane/plane](https://github.com/makeplane/plane) | Repository README and task-oriented docs trail | Team-oriented work management, triage and high-level goal patterns | Project tracking alone is not a platform control plane |
| [discourse/discourse](https://github.com/discourse/discourse) and its [moderation guidance](https://meta.discourse.org/t/discourse-moderation-guide-part-3-managing-content/406266) | Public moderation guide and review-queue announcement | Consolidated review queue, prioritized flags, moderator notes/timeline, contextual decisions | Membership and institutional authority need geographic/legal evidence too |

### Repository findings to apply

- Separate product navigation from internal route trees. Source route maps are implementation artifacts, not UX architecture.
- Use a reusable design system and explicit translations rather than scattered labels.
- Support extensions without losing consistent interaction patterns; extensions should appear where the related product record is being reviewed.
- Treat metrics as reusable, defined objects with provenance—not one-off values embedded in cards.
- Make permissions and audit part of product behavior; never rely on hiding a button.
- Use no-code builders only for declared, supported capabilities. The manager configures approved components/rules; the UI never fabricates a capability the platform cannot fulfil.

---

## 4. Why the previous interface failed

The weakness was structural, not cosmetic:

1. **Too many top-level workbenches.** Fourteen major entries asked the director to memorize our taxonomy before starting work.
2. **System-centric labels.** “Configuration”, “product intelligence”, “event operations” and “capability registry” are implementation vocabulary, not business outcome language.
3. **Pages before purposes.** Listing screens did not explain the route from a goal to a changed member experience and measured result.
4. **No visual product control.** Mentioning a “mobile layout editor” without a canvas, component library, member preview, targeting rules and publication loop was not experience design.
5. **Analytics detached from action.** A metric needs a path to the member journey, an editable control and outcome verification.
6. **The admin risked becoming a developer console.** Technical telemetry, raw JSON, resource IDs and event/job controls must be specialist progressive disclosure, not the director's primary mental model.
7. **The manager was expected to navigate architecture.** Start from a goal and offer direct expert navigation secondarily.

The answer is a new information architecture, not a more decorated version of the previous menu.

---

## 5. New manager-centred information architecture

### 5.1 Primary navigation: eight familiar destinations

The default navigation has eight destinations. Each is an understandable area of the manager's business, not a backend module.

1. **اليوم** — priorities, goals, approvals, risks, opportunities and active work.
2. **الأعضاء والأحياء** — member lifecycle, address/neighborhood verification, referrals, resident/community roles, institutions, local geography and membership/source disputes.
3. **المجتمع والثقة** — local feed and content quality, reports, moderation, groups/events, lost-and-found, official communication, appeals and community health.
4. **السوق والأعمال** — products, used goods, property, cars, services, jobs, providers, listings, catalog/taxonomy, availability and service quality.
5. **المال والنمو** — orders/bookings, disputes/refunds, payouts/reconciliation views, promotions/ads, monetization, acquisition, retention and experiments.
6. **تجربة الأعضاء** — what members see: home, navigation, feed rows, sections, categories, recommendation controls, search, notification preferences, onboarding and version-specific experience.
7. **القواعد والأتمتة** — visual if/then rules, queues/routing, approvals, moderation rules, membership requirements, notifications and reusable action bundles.
8. **إعدادات المنصة والفريق** — teams/access, roles/scopes, geography defaults, language/brand, privacy, platform-wide policies, change history and advanced operational diagnostics.

**Globally available:** one search, “أنشئ تغييرًا”, required work, pending approvals, the Change Centre and return to the current task.

### 5.2 Homepage is a personal operating board, not a universal dashboard

The manager can choose a focus such as “سلامة المجتمع”, “تنشيط الأحياء الجديدة”, “جودة السوق”, “النمو والإيرادات” or “تشغيل المنصة”. The page always retains critical risks and approvals; the remainder adapts to that focus.

Four panels:
- **يحتاج قرارًا:** prioritized by impact, urgency and confidence.
- **تقدّم أهدافي:** a few outcomes with baseline, target, current result, owner and trend.
- **فرص للتحسين:** evidence-backed findings, “لماذا ظهرت؟” and “استكشف السبب”.
- **العمل النشط:** cases, assignments, drafts waiting for review and changes being observed.

A metric never appears without a human definition, period, scope, source freshness and drill-down into the journey. Views are personalizable; critical platform-wide warnings cannot be removed.

### 5.3 The “What do you want to do?” path

Prominent entry points:
- “أريد تغيير ما يراه الأعضاء”
- “أريد تحسين تجربة أو نتيجة”
- “أريد وضع قاعدة أو أتمتة عمل”
- “أريد مراجعة عضو أو بلاغ أو جهة”
- “أريد إطلاق رسالة أو حملة”
- “أريد حل مشكلة في السوق أو الدفع”
- “أريد معرفة ما يحدث في حي أو مدينة”
- “أريد تغيير صلاحيات فريق”
- “أريد إيقاف مشكلة أو استعادة الخدمة”

Each entry opens a guided path with only relevant questions, plain-language choices, affected members/places, and a draft/command with verification criteria. The manager can switch to direct navigation at any time.

---

## 6. Core experience: Member Experience Studio

This is not a settings list. It is where the director decides what a real member experiences.

### 6.1 Start from a member journey or surface

Visual map of the major journeys:
- Join and verify a neighborhood.
- Understand what's happening nearby.
- Ask for help or recommend a trusted business/service.
- Find local products/services/property/jobs.
- Make or manage an order, booking or paid-lending interaction.
- Find and join groups/events/initiatives.
- Receive an official/local alert.
- Resolve a report, appeal, dispute or support request.

Each journey shows entry point, steps, completion/drop-off, relevant member segment/geography/version, feedback and known pain points. Selecting a journey reveals its screens, rules, content sources and outcomes together.

### 6.2 Visual home/feed/navigation editor

The editor must have:
- A realistic mobile-device preview.
- Persona, country/city/neighborhood, language, app version and device controls.
- Layers for header/navigation, introduction, feed rows, content sections, action shortcuts, sponsored content and footer.
- A component library: neighborhood updates, popular posts, lost-and-found, polls, groups/events, official notices, local businesses, products, property, jobs, services and announcements.
- Reorder, hide/show, duplicate as draft, rename, choose source, configure eligibility and presentation.
- Plain-language conditions such as “اعرض صف العقارات عندما يختار العضو العقار في مدينته”.
- Populated, no results, loading, permission-denied, outdated content, offline/failure and untranslated states.
- Side-by-side “الحالي” and “المقترح”.
- Validation for unsupported components, missing sources, accessibility, language, geography and app-version compatibility.

The library includes only capabilities the platform declares and can fulfil. A missing capability becomes a business-facing product gap, not a fake component.

### 6.3 Audience and geography are visible

Each change states where it applies: global/country/city/neighborhood, eligible member segment, language, app version and device. Preview representative member profiles and estimated eligible population. A geography selector narrows the target; it does not grant authorization.

### 6.4 Publication flow

1. Save draft.
2. Validate components, sources, audience, languages, accessibility and version compatibility.
3. Preview representative members and compare current/proposed.
4. Simulate against recent outcomes where supported.
5. Define success measure and stop criteria.
6. Request approval if scope/risk requires it.
7. Launch to a limited eligible group where supported.
8. Observe real member journeys and guardrails.
9. Promote, pause or restore a prior immutable revision.
10. Record who changed what, where, why and whether the intended effect occurred.

---

## 7. Core experience: Rules & Automation Studio

A manager must be able to configure routine behavior without writing code, within declared supported capabilities.

### 7.1 Visual rule grammar

**عندما يحدث** [trigger]  
**وإذا انطبقت** [plain-language conditions]  
**فعندها** [named action]  
**ثم** [notification / owner / approval / follow-up]  
**وإلا** [fallback / exception / manual review].

Examples:
- Missing membership proof → request that proof and put the case in “بانتظار العضو”.
- Repeated reports about the same content → route it to a priority review queue and alert a moderator; do not silently impose a permanent sanction.
- Official alert targeting multiple cities → require source authority and regional audience preview.
- Unknown payment state → create finance follow-up and prohibit blind retry.
- Listing misses required fields → mark “بحاجة لاستكمال” and give the owner a checklist.
- A case approaches its deadline → notify owner, then escalate on the chosen schedule.

### 7.2 Builder behavior

- Pick a named business trigger.
- Add conditions through field/operator/value controls with representative records.
- Select approved actions; show risk and reversibility.
- Define scope, owner, retry/timeout, exception route and notification.
- Dry-run against representative recent records; show matching, non-matching, conflict and excluded counts.
- Preview messages.
- Test as draft and see pass/fail per test record.
- Set publication window and review/expiry date.
- Version, approve, publish, pause or restore. Never ask a manager to publish raw code as a workflow.

No rule may invent permissions, perform unrestricted writes, bypass domain policy, or convert uncertain evidence into a permanent sanction. High-impact rules require limits, simulation and independent approval.

---

## 8. One Change Centre for every product edit

The manager should never wonder whether a change is draft, saved, live, scheduled or failed.

The Change Centre handles home/feed/navigation edits, localization, geography/taxonomy changes, membership/moderation rules, notifications/official publishing, promotions/fees/monetization policies, capability availability, app releases and access policies where applicable.

Each card shows plain-language description, owner, target geography/segment, reason, state, approvals, activation time, risk and verification measure.

**Common state model:** مسودة → فحص → مراجعة → موافَق عليها → مجدولة/طرح محدود → نشطة → مراقبة → مُعمّمة / موقوفة / مُستعادة.

It detects conflicting edits, shows semantic differences, binds approval to the exact revision and preserves history. “Restore” is available only when its effect is defined; irreversible actions use compensation, not a false undo.

---

## 9. End-to-end manager journeys that prove the design

These are product journeys, not API call sequences, and should be the first journeys in the interactive prototype.

### Journey A — Improve the homepage for new neighborhoods

1. Start at Today and choose the goal “مساعدة السكان الجدد على الاندماج”.
2. Review the relevant journey: which step newcomers abandon, by city/neighborhood and app version; show freshness and confidence.
3. Choose “عدّل تجربة العضو” → “الرئيسية” in the visual studio.
4. Preview a verified newcomer in the chosen neighborhood and language.
5. Add/reorder supported rows (local news, trusted businesses, groups, events, ask-for-help), edit titles and choose allowed sources.
6. Add a plain-language audience rule such as “لمن انضم خلال آخر 30 يومًا” and preview eligibility.
7. Inspect populated/empty/loading/offline states, RTL and translation.
8. Compare current/proposed and choose a success metric with safety guardrails.
9. Run preview/simulation; missing sources or capabilities remain visible as gaps.
10. Save named draft, request approval and launch to a supported small cohort.
11. Observe actual outcome; pause on harm or expand after good results.
12. Keep the previous layout available for restoration.

**Completion:** the approved experience reached the intended audience and the outcome is linked to that revision.

### Journey B — Change neighborhood verification behavior

1. Start from the “انضمام وتوثيق الحي” journey, not a role-assignment page.
2. Inspect completion, waiting time, rejection reasons and appeal reversals by geography.
3. Choose a supported business rule to change: evidence list, reminder timing, referral support, second review or escalation threshold.
4. Edit named fields/conditions, preserving policy floors.
5. Preview member instructions and outcomes for accepted, missing, expired and conflicting evidence.
6. Simulate on restricted/anonymized historical cases and review decision changes.
7. Set target improvement and fairness/safety guardrails; request policy review.
8. Roll out to selected scope; monitor time, reversals, support complaints and abuse.
9. Promote, amend or restore the prior rule.
10. Retain readable version history and a review date.

**Completion:** a supported policy change needs no code or database edit, and every resulting decision remains explainable/auditable.

### Journey C — Respond to harmful local content

1. Today surfaces a report spike with scope and confidence.
2. Open a community case showing content/thread, place, reporter signals, rule and prior decisions.
3. Group duplicates, assign a moderator, add private notes and set deadline.
4. Choose no violation, request context, temporary hide, policy removal or scoped account restriction.
5. Preview target, audience, appeal path and reversibility; require second review for broad sanctions.
6. If a pattern exists, propose a bounded temporary automation rule and dry-run it.
7. Publish only after review; observe false positives and appeals.
8. Restore items or amend the rule when evidence shows intervention was wrong.

**Completion:** the action is traceable and safe; no unbounded global rule is built from a noisy signal.

### Journey D — Launch a local campaign or official announcement

1. State goal and local scope.
2. Choose campaign/alert, authorized source, message, languages and schedule.
3. Build target audience with exclusions; preview phone appearance and data freshness.
4. Distinguish official public information from paid promotion and show budget/limits for the latter.
5. Review conflicts, authority, delivery constraints and approvals.
6. Schedule/publish the immutable approved revision.
7. Observe receipts and audience response; keep “published” separate from “delivered”.
8. Correct with a linked revision if required.

**Completion:** audience, authorization, spend, publication and actual delivery are separate recorded facts.

### Journey E — Improve marketplace results in one city

1. Choose a city and a goal: listing quality, product discovery, provider response, order completion or customer trust.
2. Review baseline/comparisons and identify the failing stage using examples.
3. Open the relevant manager-facing control: taxonomy, listing completeness, provider eligibility, search/recommendation settings, promotion, availability, fees or customer communication.
4. Preview impact on sellers/members, including exclusions and financial effect.
5. Validate/simulate and obtain finance/legal approval where appropriate.
6. Roll out locally and monitor supply, discovery, orders/bookings, cancellation/returns, complaints and revenue quality.
7. Expand only when guardrails pass.

**Completion:** the measured outcome improved through a known product control, with defined scope and safe recovery.

### Journey F — Resolve a payment concern

1. Home shows member impact and a finance task, not a callback error.
2. Open a joined transaction view: member action, amount/currency, provider-confirmed result, recorded financial state and remaining uncertainty.
3. Use “افحص الحالة” or “اطلب مطابقة السجلات”; do not repeat an unknown financial command.
4. Offer refund only after the source confirms eligibility; preview ledger/member effect and approval.
5. Finance executes; show provider result, ledger result and customer communication separately.
6. If unresolved, assign owner/deadline and keep the case open.

**Completion:** amounts/state agree or the uncertainty is explicitly owned.

### Journey G — Change platform behavior without code

1. Start from “أريد تغيير شيء في المنصة” and search by human purpose.
2. Show current behavior, owner, scope and supported choices.
3. Edit a typed/visual control, never raw SQL/JSON as the default.
4. Preview current versus proposed experience and simulate target audience.
5. Change Centre validates dependencies, risk, approval and conflicts.
6. Launch locally or globally with required approval.
7. Verify effect and monitor outcome.
8. If the capability is absent, show “هذه القدرة غير متاحة بعد” with business impact and a capability request; no fake toggle.

**Completion:** routine behavior is configurable without code; missing capability is explicit product work, not a request for the manager to learn endpoint or class names.

---

## 10. Revised screen map — build these experiences, not module dashboards

| Experience surface | Manager question | Key parts | Primary action |
|---|---|---|---|
| Today / control room | What needs my attention or decision? | Operating focus, goals, risks, approvals, active work, opportunities | Open a decision |
| Members & neighborhood map | What is happening where, and who is affected? | Geographic map/hierarchy, membership, institutions, local health, comparison | Open place workspace |
| Journey explorer | Where does the member struggle or succeed? | Step map, funnel/drop-off, feedback, segment/version filters, linked controls | Improve this step |
| Experience Studio | What should this member see? | Phone preview, component canvas, ordering, audience/locale/version, current vs proposed | Save draft |
| Rules & Automation Studio | How should the platform respond? | Trigger/condition/action/fallback builder, simulation, versioning/review | Test/publish rule |
| Community operations | How do we protect trust and participation? | Unified review queue, context, notes/timeline, differentiated decisions and appeals | Decide/follow up |
| Marketplace operations | What prevents members and businesses completing a transaction? | Listing/provider quality, availability, order/booking issues, local market health | Resolve or adjust |
| Money & growth | Are revenue and customer outcomes healthy? | Payment/dispute, campaigns, promotion, payout/reconciliation, acquisition/retention | Inspect or adjust |
| Change Centre | What is changing, who approved it, and is it safe? | Drafts, diffs, conflicts, approvals, staged release, guardrails, restore | Validate/publish |
| Team & governance | Who may decide, and who owns the outcome? | Teams, permissions/scope, approvals, handover, audit/privacy | Delegate/review |
| Platform health (advanced) | Is the platform preventing member goals from completing? | User-impact-first incidents, releases/dependencies, delayed work, evidence | Coordinate recovery |

Technical data/AI/operations functions may exist underneath, but cannot force the main management UX into technical vocabulary.

---

## 11. Manager controls versus engineering controls

The platform must be honest about no-code boundaries.

### Managers can control without code when capability exists
- Member-facing content, home layout, sections, navigation and approved components.
- Geographic/audience targeting and version-specific visibility.
- Editorial announcements, campaigns, promotions and scheduled content.
- Taxonomy/categories and approved configuration.
- Membership workflow options and evidence requirements within policy constraints.
- Supported community moderation thresholds and actions.
- Assignment, escalation, response templates and notification routing.
- Rules/automations composed from approved triggers and actions.
- Capability availability, staged exposure and restore where consumer coverage is tested.
- Team roles/scopes with approval and audit.
- Reports, goals, segments, saved views and experiment definitions.

### The interface must not pretend a manager can safely do these things
- Write arbitrary code/SQL or bypass domain ownership.
- Create an underlying capability the product has not implemented.
- Disable unknown dependencies/consumers.
- change protected legal/security/financial invariants without governed policy.
- Declare success when source/effect cannot prove it.

When a capability is missing, the manager gets a **business-facing capability request**: goal, affected journey, scope, evidence, expected outcome and priority. They do not need to provide class names, endpoint paths, table names or code. Engineering handles implementation if the request is approved.

---

## 12. Research-derived acceptance tests

The redesign is not accepted because it has more pages or looks polished. Test with a manager who does not know the codebase or architecture:

1. **Task discovery:** given “improve sign-up in two neighborhoods,” can they find the journey and relevant control without learning internal module names?
2. **Experience editing:** can they change a supported home/feed layout, preview a named resident in a named neighborhood, validate, stage and observe a result?
3. **Rule creation:** can they build an allowed rule using named triggers/actions without SQL, code or raw JSON?
4. **Impact comprehension:** before confirming, can they explain what changes, who is affected, the primary risk and the recovery option?
5. **Community decision:** can a moderator distinguish hiding, removal, restriction and appeal with context?
6. **Operational problem:** can the director reach the owner and member-facing impact without navigating technical logs?
7. **Financial integrity:** can they avoid duplicate payment/refund when provider state is unknown?
8. **Goal closure:** can they connect a product change to a target metric and decide to promote or restore?
9. **Capability honesty:** if action is unsupported, does the UI explain the gap in product language and offer a next step?
10. **Permission comprehension:** can they understand why an action is unavailable and seek access without protected-data leakage?
11. **Context preservation:** after drilling into a record, do they return to the same selection, filters and work context?
12. **Recovery:** can they find the prior revision and restore/compensate safely, with an honest explanation when undo is impossible?

Set quantitative thresholds only after representative manager sessions. Record success rate, time-to-task, wrong turns, comprehension before confirmation, recovery and confidence. The usability study must be allowed to change the information architecture itself—not just colors and spacing.

---

## 13. Sources

### Official product documentation
- [Shopify Admin](https://help.shopify.com/en/manual/shopify-admin)
- [Shopify Home, tasks, metrics and insights](https://help.shopify.com/en/manual/shopify-admin/shopify-home)
- [Airbnb performance](https://www.airbnb.com/help/article/2500)
- [Airbnb host dashboard essentials](https://www.airbnb.com/help/article/3116)
- [Intercom Inbox](https://www.intercom.com/help/en/articles/10223008-setting-up-the-inbox)
- [Jira Service Management queues](https://support.atlassian.com/jira-service-management-cloud/docs/what-are-queues/)
- [Salesforce Lightning navigation](https://trailhead.salesforce.com/content/learn/modules/lightning-experience-for-salesforce-classic-users/navigate-around)
- [Discourse moderation](https://meta.discourse.org/t/discourse-moderation-guide-part-3-managing-content/406266)
- [PostHog dashboards](https://posthog.com/docs/product-analytics/dashboards), [flags](https://posthog.com/docs/feature-flags), [experiments](https://posthog.com/docs/experiments)
- [Power Apps model-driven app design](https://learn.microsoft.com/en-us/power-apps/maker/model-driven-apps/design-custom-business-apps-using-app-designer)
- [Directus Data Studio](https://docs.directus.io/user-guide/overview/data-studio-app)
- [Appsmith granular access](https://docs.appsmith.com/advanced-concepts/granular-access-control) and [audit logs](https://docs.appsmith.com/advanced-concepts/audit-logs)
- [Linear Triage](https://linear.app/docs/triage) and [Initiatives](https://linear.app/docs/initiatives)
- [GitHub organization roles](https://docs.github.com/en/organizations/managing-peoples-access-to-your-organization-with-roles/roles-in-an-organization)
- [Apple HIG principles](https://developer.apple.com/design/human-interface-guidelines/design-principles), [feedback](https://developer.apple.com/design/human-interface-guidelines/feedback), [settings](https://developer.apple.com/design/human-interface-guidelines/settings)

### Open-source repositories
- [Saleor Dashboard](https://github.com/saleor/saleor-dashboard)
- [Medusa](https://github.com/medusajs/medusa)
- [PostHog](https://github.com/PostHog/posthog)
- [Directus](https://github.com/directus/directus)
- [Twenty](https://github.com/twentyhq/twenty)
- [Appsmith](https://github.com/appsmithorg/appsmith)
- [Plane](https://github.com/makeplane/plane)
- [Discourse](https://github.com/discourse/discourse)

---

## Final design decision

**PAOS must feel like a manager's studio for running and shaping a living community-and-commerce platform.** It cannot look like an index of technical capabilities. The director must move from a business goal or member problem to context, decision, visual configuration/rule, preview, approval, release and measured outcome—without reading code.

The developer remains responsible for building and maintaining product capabilities and technical safety boundaries. The manager owns day-to-day configuration, content, supported policy, experience composition, work priorities and outcome-based decisions. The interface makes that boundary clear without making the manager learn implementation details.
