# Project-Specific Rules — Backend Java (V3)

Follows the global AGENTS.md at `~/.config/opencode/AGENTS.md`.
This file adds project-specific conventions.

## 0. الإجبار النظامي (System-Mandatory) — شرط مسبق قبل كل إجراء (جبري)

> **هذا القسم جبري (enforced) بأمر المستخدم المستمر: «اعتمد فهمك للبنية في كل إجراء قادم» و«لا تقل فقط بل اجعله جبرياً». لا يُتخَطَّى.** مقتضاه: أي إجراء (تعديل ملف، أمر bash يغيّر شيئاً، إنشاء/دفع/دمج PR، قرار معماري) **لا يبدأ** حتى يُحمَّل الفهم النظامي ويُسنَد إلى الملف الحاكم — وإلا توقف تلقائياً. (هذا القسم مكرر في `skill protocol-enforcer` §0.)

### 0.1 التسلسل الإجباري قبل أي إجراء (Mandatory load order)
```text
1. SYSTEM.md     — الخريطة المرجعية للآلية (§1-§13) + خريطة التعمق §10
2. PROJECT_MAP.md — الحالة الفعلية (ما دُمج، ما مفتوح، ما معلّق)
3. الملف الحاكم للمهمة الحالية — من §11/خريطة المرجع:
     - auth redesign          → docs/security/auth-system-redesign-plan.md
     - client bootstrap       → docs/security/oauth2-client-bootstrap-spec.md
     - client/hosting plan    → docs/security/client-hosting-strategy-plan.md
     - feature expansion      → docs/feature-expansion-roadmap.md
     - realestate systems     → docs/realestate-systems-plan.md
     - postgis integration     → docs/postgis-integration-plan.md
     - community layer        → docs/neighborhood-community-plan.md
     - unified platform       → docs/unified-platform-plan.md
     - Saleor Dashboard/full commerce schema → docs/governance/plans/saleor-dashboard-full-commerce-canonical-plan.md
     - Saleor domain/skill/source/test routing → docs/governance/campaign/saleor-domain-knowledge-map.md (companion index; never overrides the canonical plan, pins, frozen contracts or divergence decisions)
     - إصلاح أخر               → الملف المعني + CODING_STANDARDS.md
4. ثم الشجرة المستهدفة من خريطة §10
5. نظام معرفة العيوب `docs/governance/defect-knowledge/` — **إلزامي مع كل تعديل كود**: `change-protocol.md` (قبل/أثناء/بعد) + قواعد `rulebook.md` §B المُحمّلة اختيارياً بمحفزات الخطر + `pattern-catalog.md` عند أي شك في نمط متكرر
6. سياسة المهارات `docs/governance/agent-skills-policy.md` وسجل التثبيت `docs/governance/plans/agent-skills-pins.json`؛ حمّل فقط `SKILL.md` المثبتة ذات الصلة بالمهمة، مع احترام أولوية المراجع الحاكمة. لا تشغّل مثبت مهارات يكتب نسخة غير مثبتة.
```
قاعدة: «الملف الحاكم» = المستند الذي يملك قرارات المهمة. لا يقبَل إجراء بلا إسناد صريح إليه.

**مرجعية تقنية عامة لكل التقنيات:** لكل قرار أو تنفيذ أو إعداد أو تكامل، استخدم الوثائق الرسمية للإصدار الفعلي والمستودع الرسمي/مصدر التنفيذ والمعايير المنشورة ذات الصلة. تُستخدم تجارب المجتمع الموثوقة كدليل مساند فقط، ولا تتجاوز العقد أو الوثائق الرسمية. لا تنفذ إجراءً لا يمكن إسناده إلى مصدر موثوق. لا يعني ذلك منع كتابة كود مخصص؛ يُسمح به فقط عبر واجهات التوسعة أو التكوين أو دورة الحياة الموثقة رسمياً. فضّل الإدارة التلقائية والآليات المضمنة عندما توثق التقنية أنها تغطي الحالة، ولا تحذف تخصيصاً يدوياً مدعوماً قبل إثبات أنه مخالف أو غير مطلوب.

**المهارات:** قبل التنفيذ، افحص سياسة `docs/governance/agent-skills-policy.md` وسجل `agent-skills-pins.json`، وحمّل المهارات المثبتة ذات الصلة. المهارات مدخلات مرجعية وليست سلطة أعلى من العقود والخطط والكود والاختبارات. ممنوع تشغيل `npx skills add` أو أي مثبت يحدّث نسخ `.agents/skills/` خارج عملية التثبيت الموثقة والمراجعة.

قاعدة خاصة بـSaleor (ملزمة): أي عمل على Dashboard/عقد التجارة/مخطط GraphQL يخضع حصريًا لـ`docs/governance/plans/saleor-dashboard-full-commerce-canonical-plan.md`. الوثيقتان `saleor-dashboard-marketplace-adaptation-plan.md` و`saleor-schema-parity-plan.md` تاريخيتان فقط ولا يجوز اختيار أي منهما ملفًا حاكمًا. يتحقق CI من علامة الإلغاء ورابط الخطة الجديدة؛ لا تُحذف أدلة التاريخ. كل اختلاف عن العقد المرجعي يجب تسجيله في `docs/governance/plans/saleor-contract-divergences.csv` مع مالك واختبارات وحالة موافقة/ADR؛ لا يمر P7 مع اختلاف غير معتمد. لا تُحسب مطابقة أسماء الجذور على أنها اكتمال دلالي. قبل تنفيذ أي مجال Saleor، اقرأ أيضًا `docs/governance/campaign/saleor-domain-knowledge-map.md` لتحديد مصادر الوثائق والمهارات والاختبارات والاعتماديات بين المجالات؛ هذه الخريطة لا تنشئ سجل جذور بديلًا ولا تتجاوز الخطة الحاكمة.

### 0.2 قبل أي إجراء، أعلن بنص صريح (لا ضمنياً)
```text
[الملف الحاكم]: <مسار الملف الذي يحكم هذه المهمة>
[النواة المعمارية]: <أي طبقة من SYSTEM.md §1-§9 تمسها (بناء/إقلاع/وحدات/أمن/بيانات/إعدادات)>
[البند من المصفوفة §14.2]: <11 بنود الاتساق أو بوابة A/B/C إن كانت مهمة>
[أثر على الحدود]: <Modulith/SPI/أحداث أم لا>
[دين: لا/نعم - موثق]
[معرفة العيوب]: <قواعد §B/§C المحمّلة من rulebook.md حسب محفزات التغيير — أو «لا محفزات»>
```
أي إجراء لا يحمل هذا الإعلان **يُتوقف تلقائياً** قبل التنفيذ.

### 0.3 مبدأ «الباك اند مرساةً» — نطاق الحرية المنضبطة
من خطة العملاء والاستضافة (§1): النواة (Boot/Modulith/SAS/Java) مغلقة على main؛ أي عميل مستقبلي = **إعداد + اختبار** عبر مسار `RegisteredClientRepository.save` (env→DB) + CORS env + اختبار S2/S3 للصف الفعلي — **صفر كود/وحدة/اعتماديات جديدة**. لا عودة عن D6 (مفاتيح prod fail-fast) ولا عن INV-2 (إعدادات صريحة كاملة).

### 0.4 البوابات المفتوحة لا تبدأ بلا كلمة المستخدم
- بوابة B (تقنية العميل ونمطه) → بوابة C (جهة الاستضافة)، بالترتيب (§6 خطة العملاء والاستضافة)، كلٌّ بكلمة صريحة. لا تفترض إجابة.

## Additional Rules

- **CI**: GitHub Actions والبوابات المطلوبة على رأس PR هي سجل التحقق الإلزامي. يمكن تشغيل Maven محلياً كفحص إضافي عندما تكون بيئة التنفيذ متاحة، لكنه لا يحل محل CI ولا يمنع مسار GitHub-first عندما لا تتوفر بيئة محلية.
- **التحقق المحلي** (GOV-02، أضيفت 2026-11-04؛ عُرِّبت 2026-11-08 بمراجعة #811 جولة-7): عند توفر البيئة (JDK 25 + Docker/Testcontainers متاحان)، شغّل مجموعات اختبارات الوحدات المتأثرة محلياً **قبل** فتح PR — فـCI هو الحكم النهائي دائماً وليس أول تشغيل للاختبارات؛ ولا يصل أي تشغيل محلي أحمر إلى GitHub دون تفسير. وعندما لا تسمح البيئة بذلك، ينصّ متن PR صراحةً على السبب (ويحمل CI وحده عبء الإثبات حينها).
- **Testing**: All integration tests MUST have `@ActiveProfiles("test")` and `@Testcontainers(disabledWithoutDocker = true)` or `@Container`
- **Flyway**: Any schema change = new V{number} migration file. Never modify existing migrations
- **Envers**: All domain entities MUST have `@Audited`
- **`@ConfigurationProperties` binding**: a nested record section with absent keys binds to `null` (official constructor-binding rule); prime the component with an empty `@DefaultValue` to always bind a non-null defaulted instance (Spring Boot reference — Features › Externalized Configuration › Constructor binding)
- **Protocols enforced**: Planning → Execution → Surgical Editing (see global AGENTS.md)

## 1. بروتوكول تشغيل الوكيل — النسخة الدائمة على GitHub (محدث 2026-11-03)

- الوكيل الذي يستلم المستودع **بذاكرة صفرية** يبدأ من `docs/agent-protocol.md` — طبقة الإقلاع والاستحواذ التي تربط عائلة ملفات الجذر (هذا الملف / SYSTEM.md / PROJECT_MAP.md / docs/METHODOLOGY.md / CONTRIBUTING.md) في سلسلة واحدة تُبنى من GitHub وحده. ومهارة الإقلاع القابلة للاشتقاق لأي نظام وكلاء في `docs/agent-skill.md`.
- عند أي خلاف بين الملف العالمي المحلي (`~/.config/opencode/AGENTS.md`) أو مهارة `protocol-enforcer` المحلية وهذين التوأمين: **التوأم في المستودع يحكم** وتُرقَّى النسخ المحلية لمطابقته (النسخ المحلية conveniences تُعاد بناؤها منه، لا اعتماديات).
- سياسة اللغة (ملزمة): الإنجليزية لطبقة الآلة (الكود، عناوين الـcommits، الوثائق التقنية الموجهة للـAI)؛ العربية لطبقة المالك (الردود، أجسام الـPRs بالأرقام المقاسة) — ملفات الجذر القائمة تبقى كما هي.
- لا دمج إلى `main` إلا بكلمة صريحة من المالك عبر بوابات الدمج الثلاث المقيسة — الدمج نفسه مُحرّك النشر إلى الإنتاج (auto-deploy).
- **GitHub-first:** التعديلات الدائمة تكون على فرع بعيد وPR؛ لا دفع مباشر إلى `main`. استخدم SHA رأس الفرع كمرجع، وتحقق من نتائج GitHub Actions على الرأس الحالي. الاختبارات المحلية إضافية وليست بديلاً عن البوابات البعيدة.
- **عدم الانتظار:** لا تتوقف بلا عمل بسبب CI أو CodeRabbit؛ نفّذ عملاً مستقلاً على فرع منفصل أو أكمل التحليل والمراجعة، مع مراقبة محدودة للنتائج. لا تُعلن نجاح فحص لم يكتمل، ولا تتجاوز أي بوابة دمج مطلوبة.
- **المراجعات:** اطلب `@coderabbitai review` عندما تسمح حالة PR وإعدادات المستودع بذلك. إن تجاوز التغيير حد الملفات المدعوم، اتبع آلية التغطية المقسمة والبوابة fail-closed القائمة؛ لا تنشئ شرائح بديلة ولا تعتبرها قابلة للدمج.
