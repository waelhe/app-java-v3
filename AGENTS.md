# Project-Specific Rules — Local Community Platform (app-java-v3)

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
     - platform product/experience/architecture → docs/product-platform-blueprint.md
     - user journeys and acceptance criteria     → docs/community-platform-user-journeys.md
     - visual UX and design system               → docs/community-platform-ux-design.md
     - product decisions                         → docs/platform-product-decisions.md
     - product delivery method                   → docs/community-platform-product-management.md
     - إصلاح أخر               → الملف المعني + CODING_STANDARDS.md
4. ثم الشجرة المستهدفة من خريطة §10
```
قاعدة: «الملف الحاكم» = المستند الذي يملك قرارات المهمة. لا يقبَل إجراء بلا إسناد صريح إليه.

### 0.2 قبل أي إجراء، أعلن بنص صريح (لا ضمنياً)
```text
[الملف الحاكم]: <مسار الملف الذي يحكم هذه المهمة>
[النواة المعمارية]: <أي طبقة من SYSTEM.md §1-§9 تمسها (بناء/إقلاع/وحدات/أمن/بيانات/إعدادات)>
[البند من المصفوفة §14.2]: <11 بنود الاتساق أو بوابة A/B/C إن كانت مهمة>
[أثر على الحدود]: <Modulith/SPI/أحداث أم لا>
[دين: لا/نعم - موثق]
```
أي إجراء لا يحمل هذا الإعلان **يُتوقف تلقائياً** قبل التنفيذ.

### 0.3 تحديث نطاق المنتج — المنتج والتجربة يقودان التنفيذ (توجيه المالك 2026-10-11)

القاعدة الأقدم التي تعاملت مع الباك إند باعتباره نطاقًا مغلقًا والعميل باعتباره إعدادًا فقط **لم تعد حاكمة لنطاق المنتج**. طلب المالك واضح: المطلوب منصة مجتمعية متكاملة، لا غلافًا ضعيفًا للواجهات الموجودة. يُستخدم الباك إند الحالي أساسًا لما يثبت أنه صالح، وتُستكمل الفجوات اللازمة في المجالات المالكة عندما تتطلبها رحلة مستخدم كاملة.

- لا يبدأ العمل بواجهة أو endpoint منفرد. ابدأ بنتيجة مستخدم من مخطط المنتج والرحلات، ثم قِس الفجوة في الكود والبيانات والعقود والاختبارات.
- الاتجاه المعتمد للعميل الأول هو Android أصلي بـKotlin وJetpack Compose؛ لا يُنشأ عميل موازٍ أو WebView بوصفه بديلًا.
- لا تُنشأ مصادقة أو ملكية بيانات أو قناة إشعارات موازية إذا كانت القدرة الحالية مناسبة؛ تُمدد القدرة المالكة عندما يكشف القياس عن فجوة.
- تظل ضوابط Spring Boot/Security وFlyway وModulith والسرية والبيئات ملزمة تقنيًا. توسيع نطاق المنتج لا يبرر تجاوز وثائق الإطار الرسمية أو إعادة كتابة السجلات المطبقة.
- كل شريحة يجب أن تربط تجربة العميل بعقد حقيقي وقواعد مجال واستدامة بيانات واختبارات؛ لا بيانات وهمية أو نجاح متفائل.


### 0.4 قرارات المنتج والعمل الجاري

- قرار العميل الأصلي Android Kotlin/Jetpack Compose محسوم بتوجيه المالك السابق؛ تُراجع إصدارات الأدوات المستقرة والوثائق الرسمية عند التنفيذ.
- لا يُعتبر أي PR من محاولات Android المتكررة مصدرًا حاكمًا أو تطبيقًا معتمدًا. راجع المخطط الجديد والرحلات قبل إعادة استخدام كود منه.
- قبل كل تغيير، افحص PRs المفتوحة والفروع والتداخل في الترحيلات وملفات الحالة؛ لا تدمج تغييرات متنافسة لمجرد اخضرار CI.
- لا دمج إلى main ولا إطلاق دون أمر صريح من المالك. يحفظ سجل التنظيف والأثر في docs/github-recovery-log.md.

## Additional Rules

- **CI**: Run `mvn clean verify -pl <module>` before pushing
- **Testing**: All integration tests MUST have `@ActiveProfiles("test")` and `@Testcontainers(disabledWithoutDocker = true)` or `@Container`
- **Flyway**: Any schema change = new V{number} migration file. Never modify existing migrations
- **Envers**: All domain entities MUST have `@Audited`
- **`@ConfigurationProperties` binding**: a nested record section with absent keys binds to `null` (official constructor-binding rule); prime the component with an empty `@DefaultValue` to always bind a non-null defaulted instance (Spring Boot reference — Features › Externalized Configuration › Constructor binding)
- **Protocols enforced**: Planning → Execution → Surgical Editing (see global AGENTS.md)

## 1. بروتوكول تشغيل الوكيل — النسخة الدائمة على GitHub (مضاف 2026-09-28)

- الوكيل الذي يستلم المستودع **بذاكرة صفرية** يبدأ من `docs/agent-protocol.md` — طبقة الإقلاع والاستحواذ التي تربط عائلة ملفات الجذر (هذا الملف / SYSTEM.md / PROJECT_MAP.md / docs/METHODOLOGY.md / CONTRIBUTING.md) في سلسلة واحدة تُبنى من GitHub وحده. ومهارة الإقلاع القابلة للاشتقاق لأي نظام وكلاء في `docs/agent-skill.md`.
- عند أي خلاف بين الملف العالمي المحلي (`~/.config/opencode/AGENTS.md`) أو مهارة `protocol-enforcer` المحلية وهذين التوأمين: **التوأم في المستودع يحكم** وتُرقَّى النسخ المحلية لمطابقته (النسخ المحلية conveniences تُعاد بناؤها منه، لا اعتماديات).
- سياسة اللغة (ملزمة): الإنجليزية لطبقة الآلة (الكود، عناوين الـcommits، الوثائق التقنية الموجهة للـAI)؛ العربية لطبقة المالك (الردود، أجسام الـPRs بالأرقام المقاسة) — ملفات الجذر القائمة تبقى كما هي.
- لا دمج إلى `main` إلا بكلمة صريحة من المالك عبر بوابات الدمج الثلاث المقيسة — الدمج نفسه مُحرّك النشر إلى الإنتاج (auto-deploy).
