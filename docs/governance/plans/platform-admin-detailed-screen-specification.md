# PAOS — Detailed Screen and Interaction Specification

**Authority:** Supporting implementation detail for the [Platform Administration Operating System Product Charter](platform-admin-operating-system-product-charter.md).  
**Status:** Product/UX specification; not implementation evidence.  
**Structural screen layouts:** [Primary Screen Wireframes](platform-admin-primary-screen-wireframes.md) pins down the composition and action hierarchy of the homepage, work queue, incident room, capability-stop wizard, payment exception, official alert, configuration review and scoped access grant.  
**Primary operator:** A platform manager who is not expected to know software, database, networking or cloud terminology.  
**Default language:** Arabic RTL. English LTR must be fully supported.

## 1. Product rule: the UI must speak like an operations partner

The manager needs to know four things on every screen: **what happened, who is affected, what can I do, and how will I know it worked?** If any of these is unknown, say so plainly and show the next safe step.

Every capability must cover four levels:
1. Understand the situation.
2. Decide with scope, consequences and uncertainty visible.
3. Perform or delegate the action.
4. Verify its actual effect and recover if necessary.

A polished chart or CRUD form does not complete a user journey.

## 2. Translate technical language before it reaches the manager

| Internal term | Manager-facing Arabic | Meaning shown in help text |
|---|---|---|
| SLO / SLI | مستوى جودة الخدمة | هل تعمل الخدمة بالمستوى المتفق عليه؟ |
| Error budget | هامش الأعطال المتاح | مقدار التراجع الممكن قبل إيقاف التغييرات |
| Feature flag / kill switch | تشغيل الميزة أو إيقافها | التحكم في إتاحة ميزة للأعضاء مع بيان نطاق التأثير |
| Canary / rollout | طرح تدريجي | تجربة التغيير على مجموعة محدودة قبل تعميمه |
| Webhook / callback | تأكيد من الجهة الخارجية | رسالة تؤكد أن مزود الخدمة نفّذ أو رفض طلبًا |
| Trace / correlation ID | رقم تتبّع العملية | رقم يساعد فريق الدعم على العثور على تفاصيل المشكلة |
| Reconciliation | مطابقة السجلات المالية | مقارنة الطلب مع تأكيد مزود الدفع والسجل المالي |
| Idempotency | منع تكرار العملية | الحماية من تنفيذ الأمر مرتين عند إعادة المحاولة |
| Circuit breaker | إيقاف مؤقت للاتصال المتعثر | حماية المنصة من تكرار استدعاء خدمة لا تستجيب |
| Cache invalidation | تحديث النسخة المحفوظة | التأكد من أن العرض يستخدم المعلومات الأحدث |
| Policy engine | قواعد الصلاحيات والحماية | القواعد التي تحدد من يستطيع فعل ماذا وفي أي نطاق |
| Data lineage | مصدر الرقم وطريقة حسابه | من أين جاءت المعلومة وكيف احتُسبت |
| Dead-letter queue | عمليات تعذّر إكمالها | أعمال توقفت بعد فشل محاولاتها وتحتاج مراجعة |
| Deployment | إصدار جديد للنظام | نشر نسخة جديدة من التطبيق أو إحدى خدماته |
| Rollback | الرجوع إلى إصدار سابق | إعادة النسخة السابقة إن كان ذلك آمنًا ومتاحًا |
| PII | بيانات شخصية حساسة | معلومات تكشف هوية الشخص أو تفاصيله الخاصة |
| API / endpoint | واجهة ربط تقني | تظهر فقط في التفاصيل التقنية للمختص |
| SQL / database | استعلام تقني / مخزن البيانات | لا يظهر ضمن المهام العادية |
| RBAC / ABAC / ReBAC | صلاحيات الدور والعلاقة والنطاق | لماذا يستطيع هذا الشخص تنفيذ إجراء على هذا السجل وفي هذا المكان |

Technical details may be expanded by authorized specialists, but must never be required to finish an ordinary manager task.

### Language and behavior rules

- Use specific verbs: “راجع الطلب”، “اطلب مستندًا”، “أوقف ميزة السوق في برلين”، “تحقق من وصول التنبيه”.
- Never show a vague “Manage”, “Execute”, “Submit”, “Success” or “Error” as the sole label.
- A green check is not evidence by itself. State exactly what succeeded and what remains unverified.
- Separate the states “تم استلام الطلب”، “بدأ التنفيذ”، “تم تطبيق التغيير”، “تم التحقق من النتيجة” and “وصل إلى الأعضاء”.
- Explain unfamiliar consequences next to the decision, not in a remote help page.
- Never require a technical ID when the system can search a human-readable name.
- Confirmation must identify target and scope, e.g. “إيقاف ميزة السوق في برلين لـ 18,420 عضوًا”، not just “هل أنت متأكد؟”.
- “إخفاء منشور”، “إزالة منشور وفق السياسة” and “حذف حساب” have different meanings and never share one ambiguous button.
- Every status has a label, meaning, timestamp, freshness and next step where applicable.
- Every error explains what failed, known impact, what is unknown and one safe next action.
- No toast, animation or ephemeral alert is the only record of an important outcome.

## 3. Universal app shell

### 3.1 Top bar

- Product name and unmistakable environment label. Production shows “بيئة الإنتاج — تغييرات حقيقية”.
- Scope control: “نطاق العمل الحالي” with values such as “كل المنصة” or “ألمانيا / برلين / نويكولن”. Beside it: “صلاحياتي في هذا النطاق”.
- Search: “ابحث عن عضو أو حي أو طلب أو بلاغ أو عملية…”.
- Persistent entry points: “التنبيهات”، “مهامي”، “بانتظار موافقتي”، “العمليات الجارية”، profile/session.
- The scope selector filters data; it never grants permission. If a scope exceeds current authority, the UI shows the access-request/delegation route.

### 3.2 Default navigation (Arabic labels)

1. اليوم
2. الأعضاء والأحياء
3. المجتمع والثقة
4. السوق والأعمال
5. المال والنمو
6. تجربة الأعضاء
7. القواعد والأتمتة
8. إعدادات المنصة والفريق

This is a manager-centred navigation, informed by the [global UX research](platform-admin-global-manager-ux-research.md), not a map of backend modules. Technical health and diagnostics live under “إعدادات المنصة والفريق” for the director's ordinary path and can expand for specialists when needed.

Persistent task launchers must also exist:
- “أريد تغيير ما يراه الأعضاء”
- “أريد تحسين تجربة أو نتيجة”
- “أريد وضع قاعدة أو أتمتة عمل”
- “أريد حل مشكلة في السوق أو الدفع”

The user can always navigate directly, but new or non-technical managers can start from their intended outcome.

### 3.3 Common layout

- Main content: one active task, consistent breadcrumbs, preserved filter/query state.
- Queue pages: list/detail split view. Closing the detail returns to the same row, scroll position and filters.
- Context rail: related records, evidence, owner, recent decisions and the next permitted action.
- Work drawer: assignments, deadlines, approvals, escalations, long-running operations and recent outcomes.
- Every page shows the last successful update. Stale information is explicitly marked; high-risk writes are blocked when freshness is a required precondition.
- Arabic requires genuine RTL layout across navigation, forms, tables, dialogs, charts, maps, numbers, mixed-language references and keyboard focus—not just text alignment.
- Production/test state is always visible. Prototype data must be labelled “بيانات تجريبية” and never look like live control.

## 4. Screen contracts

### S01 — اليوم: مركز قيادة المدير

**Manager question:** “ما الذي يحتاج انتباهي الآن؟”

**First viewport, top to bottom:**
1. Current environment, scope, shift and last update.
2. “يتطلب تدخلك”: critical incidents, security/financial risks and significant member-facing failures. Each row shows the problem in plain language, severity word, affected areas/users (exact or estimated), start time, confidence/freshness and “افتح المشكلة”.
3. “قرارات تنتظرني”: requester, what will change, impact, time waiting, conflict-of-interest status and “راجع القرار”.
4. “عمل معرض للتأخير”: overdue/unowned cases and the responsible team.
5. “تجارب الأعضاء”: onboarding/verification, posting, search/discovery, ordering/booking and notification outcomes, each with time range, denominator, scope and freshness.
6. “تغيّرات مهمة”: recent release/config/policy actions and observable effects.

**Primary actions:** “افتح الحادثة”، “راجع الموافقة”، “وزّع العمل”، “اعرض تجربة الأعضاء المتأثرة”.

**States**
- No critical incident: state no confirmed intervention is currently needed, with monitoring freshness and open tasks.
- Missing telemetry: “تعذّر التأكد من صحة بعض الخدمات” and an action to check data collection. Never claim everything is healthy.
- Suspicious but unconfirmed trend: say “اشتباه يحتاج مراجعة”، not “عطل مؤكد”.
- New manager: a short guided tour explains scope, work, approvals and evidence.

### S02 — البحث العام (متاح من كل مكان)

Search members, neighborhoods, institutions, posts/reports, providers/listings, orders/bookings, payments/disputes, official notices, cases/incidents, releases and operations as authorized.

**Results:** grouped by type; human-readable name, area, state, last activity, match reason and freshness. Filters include object type, scope, status, owner and period.

**Ambiguous match:** ask the manager to choose by location/state rather than open the first match. **No results:** suggest spelling and supported types. **Partial source failure:** list which object groups could not be searched. **Insufficient authority:** explain the missing permission without revealing protected record existence.

Primary action: “افتح السجل” or “أضف إلى ملف متابعة”. Adding a record links its canonical reference; it does not copy it.

### S03 — العمل والقرارات

Tabs: “مهامي”، “عمل فريقي”، “متأخرة”، “بانتظار موافقة”، “عمليات جارية”، “مكتملة مؤخرًا”.

Every row shows title, impact, area, owner, deadline, waiting age, blocker, last update and next action. Filtering/search is server-side. Counts are labelled exact, partial or stale.

Actions: “استلم المهمة”، “عيّن لعضو الفريق”، “صعّد للمسؤول”، “أنشئ مهمة متابعة”. Assignment must check authority and show the new owner. Escalation requires a reason. Bulk operations show included/excluded rows and outcome estimate before confirmation.

Empty message: “لا توجد مهام في هذا العرض. جرّب إزالة أحد المرشحات أو اعرض العمل المكتمل.”

### S04 — ملف الحالة

Header: goal/title, state, severity, scope, owner/team, due time, last update and “ما الذي يمنع الإغلاق؟”.

Sections:
- “المشكلة والأثر”: confirmed impact, suspected impact, affected user journeys and time interval.
- “السجلات المرتبطة”: member/business/order/release/etc. with relationship explanations.
- “الأدلة والتسلسل الزمني”: what happened, source, time and confidence.
- “خطة المعالجة”: tasks, owners, dependencies, deadlines and completion proof.
- “القرارات والموافقات”: requester, exact proposal revision, approval/rejection/expiry.
- “الإجراءات والنتائج”: requested/applied/verified distinction.
- “التواصل مع الأعضاء”: approved message, target audience and delivery state when relevant.
- “معايير الإغلاق”: required verification checklist.

Actions: “أضف دليلًا”، “أنشئ مهمة”، “اطلب موافقة”، “راجع الإجراء”، “صعّد”، “سلّم المسؤولية”، “أغلق المتابعة”.

Closing is blocked when required evidence is missing, a critical operation is running or follow-up work has no owner. List the exact blocker rather than show a generic error.

### S05 — ملف العضو/الجهة/الطلب

Header: human-readable name/title, current state, scope, owner/source, freshness and available actions.

Tabs: “ملخص”، “السجل الزمني”، “العلاقات”، “البلاغات والمتابعات”، “الإجراءات السابقة”، “الأدلة”، “الرسائل والتسليم” when permitted, and restricted “التفاصيل التقنية”.

Relations use explanatory text: “عضوية هذا العضو في حي…”; “الجهة التي منحت حق النشر”; “الطلب المرتبط بعملية الدفع”. If sources disagree, show “المعلومات لم تتطابق بعد” and each source timestamp.

Only legal transitions are offered. Each action says why it is available and what changes. No generic edit bypasses the source domain.

### S06 — مساحة الحوادث

Header: problem, severity, state, incident lead, start, impacted scopes/journeys and verification state.

Panels: affected members/areas; journey success/failure; recent related changes; confirmed causes versus hypotheses; dependencies; missing evidence. Timeline records alert, acknowledgement, assignment, mitigation proposal, approval, application and recovery.

Actions: “تولَّ قيادة الحادثة”، “حدّد الأثر”، “وزّع مهمة”، “راجع إيقاف الميزة”، “أرسل تحديثًا للأعضاء”، “تحقق من التعافي”، “اكتب مراجعة ما بعد الحادثة”.

Severity language: منخفض = أثر محدود؛ متوسط = جزء من تجربة متأثر؛ مرتفع = تأثير واسع أو مالي/أمني؛ حرج = خطر مباشر أو عطل واسع. Recommendations explain supporting evidence; the authorized commander decides.

### S07 — تشغيل ميزة أو إيقافها

Show business names such as “منشورات الحي” rather than environment-variable names. Each capability shows member experience, owner team, active state by geography, known consumers, fallback, active revision, last change and last verified test.

**Wizard:** (1) select feature and reason; (2) choose exact geography/audience; (3) show affected member count and confidence; (4) explain before/after member behavior; (5) verify consumers, dependencies, fallback and reversibility; (6) complete authorization/approval/step-up; (7) confirm exact effect; (8) read back actual applied revision; (9) verify impact and assign review/restore deadline.

If a control is not wired to every claimed consumer, label it “غير مثبت التأثير”, disable the production action and suggest a safe mitigation/handoff.

### S08 — تغيير إعداد

A typed business form, never a raw JSON editor as the primary flow. Each setting shows purpose, current value, allowed options, affected experience, owner, scope, last revision, dependencies, defaults/fallback and activation time.

Flow: choose setting → edit → validate → compare old/new → simulate affected areas → approval when required → schedule/publish → observe defined signals → promote or publish a new restoring revision.

Validation uses plain language: “هذا الإعداد يؤثر في كل المدن التي لا تملك قيمة محلية”. Missing dependency or stale simulation blocks publication. Secrets appear only as a named reference and rotation status, never secret values.

### S09 — التوثيق والثقة

Tabs: “بانتظار المراجعة”، “ينقصها دليل”، “تم البت”، “اعتراضات واستئناف”.

Review page includes requested area, evidence origin/date, member/source/referrer relations, relevant history, policy checklist, choice impact and notification preview.

Decisions:
- “قبول الطلب”: show what membership becomes effective and when.
- “طلب دليل إضافي”: specify exact evidence, deadline and readable message.
- “رفض الطلب”: select understandable reason and show appeal route.
- “إحالة لمراجع آخر”: transfer evidence/context to named owner.

Recommendations/referrals support a decision but never grant authority automatically. Read back actual membership state and track notification separately.

### S10 — طابور البلاغات وسلامة المحتوى (جزء من جناح المجتمع)

This screen is the review queue and case entry point only. It must not be mistaken for the entire community management suite. The full target suite is specified in [Domain-Grade Administration Suites §5](platform-admin-domain-grade-suites.md): community overview/health; content operations; categories/tags/post types; member trust and sanctions; neighborhood governance; groups; events/initiatives; official publishing; discovery/relevance; rules/automation; appeals/corrections; reports and audit.

Queue row: content/item, neighborhood, report reason, age, current exposure, linked report count, prior actions, policy matched, owner, deadline and urgency.

Detail: complete post/thread context, affected neighborhood, policy/rule explanation, similar/duplicate reports, permitted author history, prior decisions, potential overreach, effect of each allowed choice, member message and appeal path.

Actions remain distinct: no violation, request context, temporary hide, remove under policy, apply a scoped account limitation, preserve evidence, escalate, or refer to appeal. Batch actions require sample review, dry-run, counts of affected/excluded items and stronger approval for wide enforcement.

The queue also supports saved filters, team/geographic assignments, private notes, linked reports, deadline queues, handover and return-to-queue state preservation. Completion is proven by reading back the resulting content/account state, not by closing the case.

### S11 — التواصل الرسمي (ضمن المجتمع والثقة)

Stages: “مسودة”، “جاهز للمراجعة”، “مجدول”، “منشور”. “منشور” and “وصل للأعضاء” are separate states.

Composer: title/body, authorized source, geography/audience, publish time/timezone, urgency, language/localization, expiry and correction reference. Preview as members see it on their phones; inspect excluded audience and estimate freshness; check duplicate/conflicting alerts; obtain independent approval if required.

After publishing show accepted, delivered, failed and unknown counts per channel, retryable subset, receipts and correction/withdrawal path. A delivered alert cannot be “unsent”; a correction is a linked new message.

### S12 — الاستثناءات المالية والتجارية (جزء من جناح السوق والمال)

This screen handles order/booking exceptions and money integrity; it is not the complete commerce-management surface. The full market suite is specified in [Domain-Grade Administration Suites §4](platform-admin-domain-grade-suites.md), and includes dedicated screens for catalogue/listings, product types/attributes, variants, categories/collections, inventory/locations/reservations, channels, pricing/fees, promotions, orders/checkouts, service bookings, fulfilment/returns/refunds, customers, providers/sellers, shipping/local delivery, taxes/payment settings, reviews/search/merchandising, analytics, staff permissions and audit.

The exception entry screen groups “مشكلات الطلبات”، “الحجوزات والخدمات”، “مزودو الخدمة”، “المبالغ والمدفوعات”، “النزاعات والاسترداد” and “التسويات المالية”. Each queue has search, saved views, status filters, owner/deadline, last update, permitted actions and a detail workspace with a timeline.

Payment issue detail shows order/booking, expected amount/currency, member-facing state, provider response, platform financial state, ledger/reconciliation status, last update, duplicate/retry risk and related dispute.

Allowed actions are state-aware: inspect status, request reconciliation, request refund, review refund, escalate to finance, or contact the member/provider where policy permits. No direct ledger/balance edits. Refund flow identifies the exact payment, derives refundable amount from the authoritative owner, requires a reason, previews customer/ledger effect, obtains independent approval where required, executes through the payment owner, and reads back provider and ledger outcomes.

If outcome is unknown, state “لم نتأكد بعد من نتيجة العملية؛ إعادة الطلب الآن قد تسبب تكرارًا. افحص الحالة أولًا.” and do not expose a blind retry.

### S13 — تجربة الأعضاء والإصدارات

Tabs: “ما يظهر للأعضاء”، “ترتيب الصفحة الرئيسية”، “التجربة حسب النسخة”، “إصدارات التطبيق”، “نسبة التحديث”، “مشكلات النسخة”.

Visual editor provides phone preview, platform/app version, locale, viewport, supported components, content bindings and visibility conditions. Never require raw JSON.

Release flow: select build → devices/minimum version → notes → mandatory-update behavior → rollout groups/regions → compatibility checks → review → staged release → observe crash/login/verification/search/primary journey → expand or pause/restore. Warn plainly that raising minimum version may prevent older members from continuing until they update.

### S14 — صحة المنصة (مساحة متقدمة ضمن إعدادات المنصة والفريق)

Lead with “الأعضاء يواجهون مشكلة في…” or “لا توجد معلومات حديثة عن…”. Views include member-journey impact, availability, slowness, failures, delayed operations, dependencies and recent changes.

Guided questions: “هل المشكلة عامة أم في منطقة محددة؟”، “هل بدأت بعد تغيير معين؟”، “هل توجد إعادة محاولة آمنة؟”، “من الفريق المسؤول؟”.

If no telemetry, say “لم تصل بيانات كافية للحكم” and offer a collection check. Zero failures cannot be inferred from missing telemetry.

### S15 — الموافقات وتسليم العمل

Approval card: requester, plain-language change, exact target/scope, impact, risk, evidence, immutable proposal version, approver independence and expiry.

Buttons: “أوافق على هذا التغيير”، “أرفض مع السبب”، “أطلب معلومات إضافية”، “أعيد الطلب لصاحبه”. Requester cannot approve own protected action. Changed payload invalidates approval.

Handover includes unresolved work, decisions, running operations, risks and deadlines. Receiver must acknowledge critical risk; ownership is not considered transferred until recorded and acknowledged.

### S16 — الصلاحيات والخصوصية

Show effective grants in everyday language: “يمكنك مراجعة عضويات أحياء برلين؛ لا يمكنك إصدار تنبيه عام أو اعتماد استرداد مالي.” “لماذا لا أستطيع؟” explains missing authority safely and gives access-request/delegation route.

Grant flow: person → task permission → exact scope → allowed/blocked simulation → reason → mandatory expiry where temporary → conflict check → approval/step-up → grant/revoke → verify effective permission → audit.

Privacy request: verify identity/authority → list systems/data categories → explain legal retention → select export/restrict/correct/delete → approval → background run with per-system results → secure delivery → final receipt. Erasure is never a one-click unreviewed action.

### S17 — مؤشرات الأهداف وتجارب الأعضاء

Question-first entry: “هل أصبح توثيق الأعضاء أسهل؟”، “كم يستغرق حل البلاغ؟”، “هل وصلت التنبيهات؟”، “أين يتعثر إكمال الطلب؟”.

Every metric states plain-language definition, numerator/denominator, date/timezone, scope/filters, source, owner, update time and limitations. Drill-down uses permitted cases/samples. Export requires purpose, fields, scope, privacy class and expiry; aggregated access does not imply row-level export authority.

### S18 — المساعد والمعرفة

Suggested prompts: “لخّص ما تغيّر منذ بداية الوردية”، “لماذا زادت البلاغات في هذا الحي؟”، “ما الدليل على تعطل التنبيهات؟”، “حضّر مسودة تغيير ولا تنشرها”.

Answer layout: conclusion first, certainty/uncertainty, facts versus hypotheses, source links/timestamps, missing data and next safe step. Any action appears as a proposal with target, scope, impact and “راجع الإجراء”. No hidden action runs directly from model output.

### S19 — تفاصيل العملية ونتيجتها

Each operation page shows intent, requester, reason, target/scope, validation, approver, start/end, progress, succeeded/failed/skipped/unknown items, verified result and recovery actions.

Status vocabulary:
- “بانتظار البدء”
- “قيد التنفيذ”
- “اكتمل — تم التحقق من النتيجة”
- “اكتمل جزئيًا — بعض العناصر لم تنجح”
- “تعذّر التنفيذ”
- “اكتمل التنفيذ ولم نتمكن من التحقق من الأثر”
- “أُلغي قبل اكتماله”
- “لا يمكن التراجع؛ الإجراء التصحيحي المتاح هو…”

### S20 — جناح السوق والكتالوج (Saleor-level domain suite)

The navigation opens a full suite, not a market summary card. Required sub-workspaces: overview; products/listings; variants/options; product/listing types and attributes; categories and collections; inventory/stock locations/reservations; channels/regions; pricing/price lists/fees; promotions/vouchers; orders/checkouts; service bookings; fulfilment/delivery/returns/refunds; customers/groups; providers/sellers; shipping/local delivery; taxes/payment methods; reviews/search/merchandising; reports/exports; staff permissions and market settings.

Each collection provides search, configurable columns, filters, sorting, saved views, pagination, multi-select and previewed bulk actions. Each record has a dedicated detail workspace with lifecycle, related records, media/evidence, timeline, scoped actions, audit and outcome. Editors must support draft, field validation, member-facing preview, conflicts, publication state and restore semantics. Variants and physical stock use SKU/location concepts where applicable; service listings use coverage, capacity, calendars/slots, provider assignment and service-specific cancellation rules instead of fake retail fields.

Before accepting the market suite, demonstrate end-to-end catalog creation, taxonomy change, multi-location inventory correction, local-service onboarding, channel-specific price/availability, promotion eligibility, order completion and safe refund. S20 owns delivery details linked to an order; standalone delivery booking, dispatch, courier/carrier operations and delivery-service configuration are handled in **S23**. Global payment collection, payout/settlement and reconciliation across orders, bookings, ads, lending and delivery are handled in **S24**; S20 shows only the transaction context needed to operate a market record. Evidence and detailed screen families: [Domain-Grade Administration Suites §4](platform-admin-domain-grade-suites.md). Official reference: [Saleor product model](https://docs.saleor.io/developer/products/overview) and [channel model](https://docs.saleor.io/developer/channels/overview).

### S21 — جناح المجتمع والثقة (Discourse-level domain suite)

The navigation opens a complete community suite: community health/dashboard; review queue; posts/comments; categories/tags/post types; member trust and sanctions; neighborhood boundaries and scoped moderators; groups and membership; events/initiatives; official notices and source authority; search/relevance; rules/automation; appeals/corrections; reports and audit.

Each workspace has its own search/saved views, record list, detail context, permitted lifecycle actions, owner/deadline, member-facing consequences, audit and reports. Review decisions keep post context, linked reports, policy version, evidence, notes, member communication and appeals together. Community analytics must cover engagement, trust progression, category/content activity, unanswered questions, search gaps and support resolution with defined metrics and drill-through. Evidence and detailed screen families: [Domain-Grade Administration Suites §5](platform-admin-domain-grade-suites.md). Official reference: [Discourse Admin Dashboard, 10 August 2026](https://blog.discourse.org/2026/08/introducing-the-all-new-admin-dashboard/).

### S22 — جناح دليل الأعمال (Yelp-level domain suite)

The directory needs dedicated collections and detail workspaces for business profiles, ownership claims/verification, branches, reviews/ratings, owner responses, leads/customer intent, local visibility/search, promotions, content quality, completeness/expiry, analytics, permissions and audit. Page visits, contact attempts, qualified leads, bookings and completed transactions must be distinct, with explicit definitions and drill-through.

Profile changes use a member-facing preview; claims show ownership evidence and conflicts; review responses and removals require policy-aware workflows; local visibility reports break down geography, category, source and period. Detailed target and end-to-end acceptance: [Domain-Grade Administration Suites §6](platform-admin-domain-grade-suites.md). Reference: [Yelp for Business lead metrics](https://www.yelp-support.com/article/How-do-you-measure-User-Views-and-Customer-Leads-on-Yelp-for-Business?bui=null&l=en_GB).

### S23 — شبكة التوصيل والشحن (خدمة مستقلة + تنفيذ طلبات السوق)

This is a full operational workspace for the platform's delivery network, not only shipment tracking from an order. Its primary tabs distinguish **“طلبات توصيل مباشرة”** from **“شحن وتنفيذ طلبات السوق”**, while sharing provider, service-area, dispatch, tracking and exception tools.

Entry points: “السوق والأعمال” → “التوصيل والشحن”; an authorized operator can create/find a standalone delivery request without an order, or open the delivery job linked from an order's detail workspace.

**Delivery request and shipment lists**
- Search by request/order, sender, recipient, provider/seller, courier, locality, tracking reference, delivery mode and state.
- Saved queues: “بانتظار إسناد مندوب”، “موعد معرض للتأخير”، “تعذّر الاستلام”، “توصيل متعثر”، “بانتظار إثبات التسليم”، “طلب إرجاع”، “نزاع/تعويض”، “مصروف/فاتورة شريك غير مطابق”.
- Map/list split view with timestamp/freshness; map access to live courier location is restricted to operators with the proper job scope. Missing/stale location is explicit, never a fabricated point.
- Filters and saved views by geography, service level, provider, fleet mode, scheduled window, risk, SLA, status and financial state. Bulk dispatch/reassignment/retry always previews eligibility and per-item effects.

**Detail workspace**
- Origin: standalone direct request or source order/service, with owner-resolved relationship.
- Quote revision, price and currency, expiry, pickup/drop-off window, package declaration, serviceability/rate rule, provider quote, assignment history, authorized address/contact detail, pickup/delivery proofs, tracking timeline, communications, claims/cases and financial allocation.
- Independent timelines for physical delivery state, source business state, payment state and ledger/settlement state. “Delivered” is not inferred from a payment success or an unverified client callback.
- Actions: accept/decline, assign/reassign, request partner status, confirm pickup only with required evidence, record failed attempt/reason, initiate a permitted return, cancel only on a legal edge, request a source-owned refund/adjustment, escalate and verify final state.
- Every consequential action shows target/scope, reason, financial or customer impact, approval requirements, idempotency/retry risk and the authoritative read-back used to prove its result.

**Service configuration**
- Service areas and route/zone eligibility; operating hours, capacity, cut-offs and blackout windows; package classes and restricted items; rate/fare rule versions; same-day/scheduled/parcel capability; pickup points; provider/carrier capabilities; partner integration status; driver onboarding/authorization.
- Credentials stay in secret storage. UI shows connection state and last verified sync, never secret values.
- If no carrier/courier is available or a provider's API capability is not enabled, the UI explains the blocker and gives the next safe alternative instead of accepting a delivery that cannot be fulfilled.

### S24 — المدفوعات والعمليات المالية على مستوى المنصة

This is the single finance operations workspace across all approved paid journeys; it is not an order-only tool. Current payment origins and target sources are listed in [Platform-Wide Payments and Delivery Network](../../architecture/platform-wide-payments-and-delivery.md).

**Collection and reconciliation**
- One search across booking, order, advertising, lending, delivery and any later approved payable origin; payment/provider references and filters by actor, payee, country, currency, method, state and time.
- Each detail shows the authoritative source amount/currency, source business state, payment intent/operations, provider callback, ledger postings, allocations/transfers where configured, refund/dispute and settlement/payout state.
- Distinguish “provider confirmed”, “platform recorded”, “ledger reconciled” and “business effect verified”. One green indicator cannot stand for all four.
- Priority queues: “نتيجة دفع غير مؤكدة”، “مدفوع دون اكتمال أثر المجال”، “فشل تأكيد المزود”، “استرداد بانتظار مراجعة”، “تعارض الدفتر ومزود الدفع”، “تسوية بانتظار التنفيذ”، “تعذّر تحويل مستحق”، “اعتراض/مهلة شارفت على الانتهاء”.
- Actions: inspect/retrieve authoritative provider state, request reconciliation, request a source-owned cancellation/refund, approve under the required separation of duties, reprocess an eligible failed event once, review payout/transfer reconciliation, and verify the final source/payment/ledger state.

**Financial safety**
- No direct amount override, balance edit or status mutation. Refund eligibility and current refundable amount come from the originating domain/payment contract; financial execution goes through the existing payments owner and ledger consequences are reconciled.
- A timeout or unknown provider response blocks blind retry until the transaction is reconciled.
- Refunds and transfer reversals are separate operations. Delivery/courier and seller allocations must show who bears the adjustment based on the approved policy.
- Exports are permission-gated, scoped, redacted and audited; payment credentials/secrets and unnecessary personal data never appear in lists.

## 5. Universal impact preview

Every material change previews, in this order:
1. “ماذا سيتغير؟” — before/after in member/business language.
2. “أين سيطبق؟” — exact scope, audience and exclusions.
3. “من سيتأثر؟” — exact count or labelled estimate, freshness and gaps.
4. “ما الذي قد يتعطل؟” — dependencies, reversibility and credible consequence.
5. “ما الذي نحتاجه قبل التنفيذ؟” — missing evidence, permission, approval or fresh authentication.
6. “كيف نعرف أنه نجح؟” — precise verification source and observation window.
7. “إذا فشل، ماذا نفعل؟” — safe retry, compensation, restoration or escalation.

Low-risk reversible actions may use a compact preview. A material or irreversible action may not be confirmed with a dialog that only says “هل أنت متأكد؟”.

## 6. Mandatory alternate states

| Condition | Required response | Prohibited response |
|---|---|---|
| No data | Explain missing input, why it matters and how to check it | Show zero/success by default |
| Stale data | Show last update and block risky action when freshness is required | Pretend value is live |
| Missing permission | Explain allowed path to request/delegate access | Leak protected records or rely on client hiding |
| Wrong scope | Show current versus requested scope | Treat scope selection as a grant |
| Conflicting versions | Show changed fields; reload/reconfirm | Silent overwrite |
| Timeout, outcome unknown | Query operation/effect before retry | Blindly repeat financial/publish/delete action |
| Partial completion | Show succeeded/failed/skipped/unknown totals and per-item result | Label whole batch “success” |
| Approval rejected/expired | Explain next step; revise and resubmit | Reuse approval for changed payload |
| Dependency unavailable | Safe fallback or handoff | False healthy state |
| Session expiry in high-risk flow | Reauthenticate and reconfirm current intent | Continue silently on stale session |
| Delivery unknown | Keep outcome unknown while recovering receipts | Claim delivered from request acceptance |
| Irreversible action | Explain effect and available compensation | Promise undo that does not exist |
| AI evidence weak | Label hypothesis and ask for evidence | Turn guess into a command |

## 7. Screen-level acceptance tests

Every screen contract must specify actor/scope, data source/freshness, filters/count semantics, actions and risk, preview/approval/step-up, success evidence, recovery path, Arabic/English strings, RTL/LTR, keyboard order and permitted analytics.

Before declaring a flow complete, browser tests must prove:
- The normal path reaches source-of-truth verification.
- A user without permission is blocked via both direct URL and API.
- Wrong scope, stale data and conflicting version do not permit unsafe action.
- Timeout-after-commit is shown as uncertain until actual result is read.
- Duplicate click/replay does not duplicate the business effect.
- Partial bulk work is accounted for and only safe items can retry.
- Session expiry and step-up do not bypass approval.
- Approval is by a separate actor and bound to an unchanged payload.
- Final case/audit evidence links actor, scope, reason, decision, approval, operation and actual outcome.
- Arabic RTL, English LTR, accessibility and responsive states work.
- Unknown/stale result is never shown as success.

A visual prototype with sample data is explicitly marked “بيانات تجريبية”. It cannot claim to control production. Missing adapter capability is displayed “غير متاح بعد” and tracked as a gap, not simulated behind an enabled button.
