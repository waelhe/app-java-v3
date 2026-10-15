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

1. الرئيسية
2. العمل والقرارات
3. البحث في المنصة
4. الأعضاء والثقة
5. المجتمع والسلامة
6. الأخبار والتنبيهات الرسمية
7. السوق والأموال
8. تجربة التطبيق
9. صحة المنصة
10. الإعدادات والقواعد
11. البيانات والأداء
12. المساعد والمعرفة
13. الصلاحيات والخصوصية
14. حوكمة المنصة

The 14 workbenches are not a mandatory memorization exercise. Three task-oriented entry routes must also exist:
- “أحتاج حل مشكلة”
- “أريد مراجعة طلب”
- “أريد تغيير شيء في المنصة”

Contextual shortcuts lead to the correct workspace without removing the full navigation.

### 3.3 Common layout

- Main content: one active task, consistent breadcrumbs, preserved filter/query state.
- Queue pages: list/detail split view. Closing the detail returns to the same row, scroll position and filters.
- Context rail: related records, evidence, owner, recent decisions and the next permitted action.
- Work drawer: assignments, deadlines, approvals, escalations, long-running operations and recent outcomes.
- Every page shows the last successful update. Stale information is explicitly marked; high-risk writes are blocked when freshness is a required precondition.
- Arabic requires genuine RTL layout across navigation, forms, tables, dialogs, charts, maps, numbers, mixed-language references and keyboard focus—not just text alignment.
- Production/test state is always visible. Prototype data must be labelled “بيانات تجريبية” and never look like live control.

## 4. Screen contracts

### S01 — الرئيسية: مركز قيادة المدير

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

### S02 — البحث في المنصة

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

### S10 — البلاغات وسلامة المجتمع

Queue row: content/item, neighborhood, report reason, age, current exposure, safe related-report count, prior actions and urgency.

Detail: surrounding context, policy/rule, similar reports, permitted author history, potential overreach, effect of each choice and appeal route.

Actions are distinct: “لا توجد مخالفة”، “اطلب مراجعة إضافية”، “أخفِ المحتوى مؤقتًا”، “أزل المحتوى وفق السياسة”، “قيّد الحساب ضمن النطاق المسموح”، “أحل لجهة مختصة”. Group actions require sample review, dry-run and higher approval for broad enforcement.

### S11 — الأخبار والتنبيهات الرسمية

Stages: “مسودة”، “جاهز للمراجعة”، “مجدول”، “منشور”. “منشور” and “وصل للأعضاء” are separate states.

Composer: title/body, authorized source, geography/audience, publish time/timezone, urgency, language/localization, expiry and correction reference. Preview as members see it on their phones; inspect excluded audience and estimate freshness; check duplicate/conflicting alerts; obtain independent approval if required.

After publishing show accepted, delivered, failed and unknown counts per channel, retryable subset, receipts and correction/withdrawal path. A delivered alert cannot be “unsent”; a correction is a linked new message.

### S12 — السوق والأموال

Categories: “مشكلات الطلبات”، “الحجوزات والخدمات”، “مزودو الخدمة”، “المبالغ والمدفوعات”، “النزاعات والاسترداد”، “التسويات المالية”.

Payment issue shows order/booking, expected amount/currency, member-facing state, payment provider response, platform financial state, last update, duplicate/retry risk and related dispute.

Actions: “افحص حالة الدفع”، “اطلب مطابقة السجلات”، “اطلب استرداد المبلغ”، “راجع طلب الاسترداد”، “أحل للمراجعة المالية”. No direct ledger/balance edits.

Refund flow: identify exact payment → show refundable amount/currency from source owner → reason → preview customer/ledger effects → independent approval if required → execute via payment owner → verify provider and ledger → member message when justified.

If outcome is unknown, state “لم نتأكد بعد من نتيجة العملية؛ إعادة الطلب الآن قد تسبب تكرارًا. افحص الحالة أولًا.” and do not expose a blind retry.

### S13 — تجربة التطبيق والإصدارات

Tabs: “ما يظهر للأعضاء”، “ترتيب الصفحة الرئيسية”، “التجربة حسب النسخة”، “إصدارات التطبيق”، “نسبة التحديث”، “مشكلات النسخة”.

Visual editor provides phone preview, platform/app version, locale, viewport, supported components, content bindings and visibility conditions. Never require raw JSON.

Release flow: select build → devices/minimum version → notes → mandatory-update behavior → rollout groups/regions → compatibility checks → review → staged release → observe crash/login/verification/search/primary journey → expand or pause/restore. Warn plainly that raising minimum version may prevent older members from continuing until they update.

### S14 — صحة المنصة

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

### S17 — البيانات والأداء

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
