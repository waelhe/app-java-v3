# حزمة تسليم مسار المطور (Track B) — التنفيذ المتوازي المستمر

> **أنت:** المطور المتوازي في منهجية «المصنع ذي الخطين». الوكيل (Super Z) ينفّذ المسار A بالتوازي معك، ويراجع ما تسلّمه ويدمج المنهجية. هذه الحزمة **مكتفية ذاتياً**: كل ما تحتاجه للبدء والعمل والتسليم هنا — لا تحتاج أي سياق محادثات سابق.
>
> **المستندات الحاكمة فوقك (بهذا الترتيب):** `AGENTS.md` (جذر المستودع — حاكمية §0 الإجبارية) ← `docs/parallel-execution-plan.md` (منهجية المسارين) ← `docs/official-compliance-plan.md` (المحتوى التقني ومراجعه الرسمية). عند أي خلاف: هذه الثلاثة تحكم — لا اجتهاد خارجها.

---

## 1. قواعدك العشر الملزمة (لا تُخالف مهما كان السبب)

1. **لا دمج إلى `main` أبداً** — الدمج بكلمة المالك حصراً. أنت تدفع إلى فرعك فقط.
2. **ملكيتك حصرية** (القسم 4): لا تلمس ملفاً خارجها إلا بـ«طلب تغيير» CR (القسم 5).
3. **بوابتك المحلية قبل كل دفع:** `./mvnw clean verify -pl <module> -am` أخضر + اختباراتك الجديدة خضراء. لا تدفع أحمرَ محلياً أبداً (الغلاف `./mvnw` هو قناة التنفيذ الرسمية المقيسة — CI يشغّل `./mvnw verify` وجدول البيئة §2 يثبّت 3.9.16 الذي يشترطه enforcer).
4. **لا تنتظر نتائج CI البعيد** — ادفع وحدتك وابدأ التالية فوراً. البوابات البعيدة تعمل على الـPR في الخلفية وتُتابع لاحقاً في مرحلة المتابعة.
5. **ترحيلات Flyway برقم من نطاقك ‏V150–V189 حصراً** — لا تلمس أرقام المسار A (V110–V149) ولا نطاق الإصلاحات (V106–V109). لا تعدّل ترحيلة قائمة أبداً.
6. **كل تغيير مخطط = ترحيلة Flyway جديدة** + كل كيان نطاقي `@Audited` (Envers) + كل اختبار تكاملي `@ActiveProfiles("test")` و`@Testcontainers(disabledWithoutDocker = true)`.
7. **المرجع الرسمي يحكم كل قرار تقني** — المراجع الدقيقة لكل وحدة في `docs/official-compliance-plan.md` §4/§6. ما لا نص رسمي له: علّقه للوكيل، لا تبتكر.
8. **لا أرقام نسخ يدوية في أي pom قائم** — التبعيات تُدار عبر BOM (طلب التبعيات عبر CR؛ التبعيات التي تحتاجها مهيأة سلفاً في فرع الأساس).
9. **كل وحدة تقيد في `worklog.md`** بمعرف `B-xx` فور دفعها، وتنتهي بعبارة «تسليم للمراجعة».
10. **record + `@DefaultValue` للقيم الافتراضية** عند `@ConfigurationProperties` (قاعدة AGENTS.md — القسم المتداخل بلا مفاتيح يربط `null`).

## 2. البيئة والأوامر الأساسية

| البند | القيمة |
|---|---|
| JDK | 25 (`--release 25` — البوابة تشترطها) |
| Maven | `./mvnw` (3.9.16 — enforcer يشترط ≥3.9)؛ ملاحظة بيئة معروفة: `mvnw` لا يقرأ `JAVA_HOME` — ضع JDK على `PATH` |
| قاعدة بيانات محلية | PostgreSQL 18 (docker-compose.yml في الجذر) — أو Testcontainers إن كان لديك Docker (تُفعّل اختبارات التكامل تلقائياً) |
| Redis محلي | Redis 8 (docker-compose.yml) |
| بناء الوحدة + جاراتها | `./mvnw clean verify -pl marketplace-<module> -am` |
| مفاعل كامل | `./mvnw clean verify` — المفاعل ينمو مع وحداتك: 22 عند تسليم الحزمة ← 23 بعد هبوط B-12 (تنفيذ CR-5) ← 26 عند تنفيذ CR-7/8/9 — استخدمه عند لمس المشترك |
| بوابة البنية | `ModulithVerificationTest` داخل `marketplace-app` — يجب أن يبقى أخضر في كل دفعة |

## 3. دورة عمل الوحدة (نفسها لكل وحدة B-xx)

```text
اقرأ مواصفة الوحدة (القسم 6) ← اقرأ مرجعها الرسمي (خطة المطابقة §4/§6)
← تحقق من الكود الحالي فعلياً (قياس قبل كتابة سطر)
← نفّذ (كود + اختبارات + ترحيلة إن لزم من نطاقك)
← ./mvnw clean verify -pl <module> -am أخضر
← كوميت برسالة إنجليزية: "feat(<module>): B-xx <وصف> (<المرجع الرسمي>)"
← دفع إلى feat/track-b-modules ← قيد worklog "B-xx … تسليم للمراجعة"
← ابدأ الوحدة التالية فوراً (لا انتظار CI ولا انتظار المراجعة)
```

## 4. ملكيتك الحصرية (حدائقك — تصرف فيها بحرية كاملة)

**وحدات قائمة:** `marketplace-messaging` • `marketplace-notifications` • `marketplace-disputes` • `marketplace-geo` • `marketplace-realestate` • `marketplace-reviews` • مسارات القراءة فقط في `marketplace-catalog` (`CatalogController`, `ReviewsController` + ملفات ETag — **لا تلمس** `*Job*`/`AdBilling*`/`ListingExpiry*`/`ListingRanking*` فهي ملك المسار A).

**وحدات جديدة تنشئها كاملة (pom + كود + اختبارات):** `marketplace-jobs` • `marketplace-institutions` • `marketplace-knowledge` • `marketplace-console` — اتبع نمط `marketplace-reviews` (الأقوى) للبنية والاختبارات، و`marketplace-provider` لـinstitutions (JSON-LD).

**محظورات صريحة (ملفات المسار A الساخنة):** `pom.xml` الجذر • أي `pom.xml` لوحدة قائمة • `application.yml`/`application-*.yml` في `marketplace-app` • `.github/workflows/*` • `marketplace-shared/**` • `marketplace-identity|edge|booking|payments|ledger|orders` — كلها بطلب تغيير CR فقط.

## 5. بروتوكول طلب التغيير (CR) والتبعيات

- سجّل في `worklog.md` بمعرف `CR-<رقم متسلسل>`: الملف المطلوب + الغرض + المرجع الرسمي. الوكيل ينفذه خلال وحدة عمل واحدة أو يرد ببديل داخل ملكيتك.
- **التبعيات التي ستحتاجها مهيأة في فرع الأساس** (`feat/parallel-foundation`): Bucket4j (B-05) وfirebase-admin (B-10) — إصداراهما في BOM الأساس، فلا تحتاج CR لهما. **قياس خطك الحالي (2026-10-07):** فرعك أخ للأساس (بُني من main قبل هبوط A-01)، فالبوم ليس في خطك بعد — تنفيذ B-05 وB-10 بعد درجة PR-F من سلّم الدمج (§15 في خطة التوازي) حين يدخل BOM الأساس خطك؛ ما عداهما لا يعتمد على الأساس.
- خصائص `application.yml` الجديدة: صمّمها دائماً **خصائص وحدة نمطية** (`messaging.*`, `notifications.*`) بسجل `@ConfigurationProperties` داخل وحدتك مع قيم افتراضية — الربط بالملف المشترك يمر عبر CR.

## 6. مواصفات وحداتك التسع عشرة

> المحتوى التقني الكامل (المرجع الرسمي الدقيق لكل بند) في `docs/official-compliance-plan.md` — الجدول هنا فهرس تشغيلي. **قياس الكود قبل التنفيذ إلزامي في كل وحدة.**

| الوحدة | الهدف | نطاق الملفات | المرجع الرسمي الحاكم |
|---|---|---|---|
| B-01 | الإقلاع والتحقق: بناء المفاعل أخضر + قراءة الحوكمة | لا تغييرات — قيد worklog فقط | AGENTS.md + هذه الحزمة |
| B-02 | توحيد كاش نتائج البحث `search-results-v4`→`v5` في geo + realestate | geo/realestate | Boot `reference/io/caching.html` + Framework `integration/cache/annotations.html` |
| B-03 | عدّاد غير المقروء يستثني رسائل المرسِل نفسه | messaging | Data JPA `reference/` (Query Methods) |
| B-04 | idempotency إرسال الرسائل بالقفل المتفائل `@Version` | messaging | Data JPA `reference/jpa/locking.html` |
| B-05 | محدد معدل الإرسال (Bucket4j مهيأ في الأساس) | messaging | §5.3 خطة المطابقة (مجتمع موثوق معلن) |
| B-06 | أحداث النزاعات التطبيقية + تفعيل الاسترداد الجزئي | disputes (+payments؟ لا — عبر الأحداث فقط) | Modulith `reference/events.html` |
| B-07 | حذف الإشعار + تعليم الكل كمقروء | notifications | Data JPA `reference/` |
| B-08 | حدث `MESSAGE_RECEIVED` → إشعار وصول رسالة | messaging (نشر) → notifications (مستمع) | Modulith `reference/events.html` |
| B-09 | ETag/طلبات شرطية لمسارات القراءة (304) | catalog(reviews paths)/reviews | Framework `reference/web/webmvc/mvc-caching.html` |
| B-10 | Push: تسجيل رموز الأجهزة + التسليم (FCM/firebase-admin) | notifications | §5.3 خطة المطابقة |
| B-11 | i18n عربية: قوالب الإشعارات والرسائل + رسائل النظام | notifications/messaging | Boot `reference/features/internationalization.html` |
| B-12 | وحدة jobs (الوظائف/التوظيف) كاملة end-to-end | jobs (جديد) | Data JPA + نمط reviews (خطة المطابقة C.2) |
| B-13 | وحدة institutions (الجهات) كاملة + JSON-LD | institutions (جديد) | Data JPA + Web MVC (خطة المطابقة C.3) |
| B-14 | وحدة knowledge (قاعدة المعرفة) + تكامل البحث بالأحداث | knowledge (جديد) | Data JPA + search FTS (خطة المطابقة C.4) |
| B-15 | وحدة console: أعلام ميزات + Remote Config بخصائص Boot | console (جديد) | Boot `reference/features/external-config.html` (خطة المطابقة C.5) |
| B-16 | M2 المتجر (٢/٢): أسئلة/أجوبة المنتج + ملخص البائع العام — ملفات **جديدة** في catalog (لا تلمس ملفات المسار A هناك) — **يبدأ بعد هبوط A-17** (قيده المسجل) | catalog (جديد) | Data JPA `reference/repositories/query-methods-details.html` + `reference/repositories/projections.html` + `reference/auditing.html` (خطة المطابقة C.8) |
| B-17 | الثقة خدمة عرضية: أحداث منحة/سحب التوثيق والبلاغ المحسوم من آلة `MembershipVerificationState` القائمة — بلا وحدة جديدة (نمط B-13: ملفات جديدة في community + CR للقائمة)؛ الأسماء تُقيد في سجل العقود | community (جديد + CR) | Modulith `reference/events.html` + Data JPA `reference/auditing.html` + Security `servlet/authorization/method-security.html` (خطة المطابقة C.9) |
| B-18 | إعدادات ميزات وارثة جغرافيًا (بلد←مدينة←حي): جدول بيانات على هرم geo + حلّ «الأخص يغلب الأعم» + الإدارة من console — **لا `@ConditionalOnProperty` جغرافيًا** (فحص وقت الطلب — الحد الرسمي المسجل) | geo + console (فوق B-15) | Data JPA `reference/repositories/query-methods-details.html` + Boot `reference/features/external-config.html` + Framework `reference/core/validation/beanvalidation.html` (خطة المطابقة C.10) |
| B-19 | محرك قواعد إشراف تلقائية: قواعد **بيانات** فوق آلة البلاغات القائمة + تقييم داخل معاملة الإنشاء + حدث قرار ← الإشراف والإشعارات + CRUD ببوابة الدور | community/reviews (جديد + CR) | Data JPA `reference/jpa/transactions.html` + Modulith `reference/events.html` + Security `servlet/authorization/method-security.html` (خطة المطابقة C.11) |

**قواعد الوحدات الجديدة (B-12…B-15):** `@ApplicationModule(allowedDependencies=…)` من أول يوم (Modulith `fundamentals.html`) • كيانات `@Audited` + ترحيلات من نطاقك • `package-info.java` بالاعتماديات المصرحة • `ModulithVerificationTest` أخضر بعد الإضافة • اختبار رحلة للوحدة كاملة (service + REST + حدث إن نشرت).

**وحدات الحدائق القائمة (B-16…B-19):** ملفات جديدة فقط داخل حدائقك + CR §5.4 (القسم 5) لأي ملف قائم — B-16 بعد هبوط A-17؛ B-18 بعد B-15؛ B-17/B-19 يمكنهما البدء متى وصل دورك إليهما؛ كل حدث جديد يُقيد في السجل (إضافة فقط).

## 7. العقود المجمدة (لا تكسرها)

- **كتالوج الأحداث** (`docs/governance/parallel-contracts-ledger.md` على فرع الأساس): أي حدث جديد تنشره يُضاف إليه (إضافة فقط — لا إعادة تسمية/نقل لحملات قائمة). المستمعون عبر الحدود بقاعدة «الواصل المتأخر» (خطة التنفيذ §5.3).
- **عقد الأخطاء:** RFC 7807 عبر ProblemDetail — لا تنسج صيغ أخطاء خاصة.
- **الترقيم:** ترقيم الويب الموحد القائم (Spring Data Web support) — لا نمط ترقيم جديد.
- **الإصدارات:** لا تُقدّم إصدارات API بنفسك — `@ApiVersion` ملك المسار A (A-08)؛ مساراتك الجديدة تُكتب بالعقد القائم وترقيمها يُدار لاحقاً.

## 8. بروتوكول التسليم والمراجعة (بينك وبين الوكيل)

| البند | النص |
|---|---|
| الفرع | `feat/track-b-modules` (قائم ومُدفوع — بُني من main @ dcdb5f8 قبل هبوط A-01؛ حمولة الأساس تلحقه عبر درجة PR-F من سلّم الدمج §15) |
| الـPR | ‏PR-B التكاملي مفتوح من أول وحدة — البوابات وCodeRabbit يعملان عليه تلقائياً؛ **لا تدمجه ولا تغلقه** |
| القيد | كل وحدة → قيد worklog: `Task ID: B-xx` + سطر «تسليم للمراجعة» + ملخص ما نُفّذ وقياساته |
| المراجعة | الوكيل يراجع خلال وحدة عمل واحدة: مطابقة المرجع الرسمي + بوابة محلية + احترام الملكية والنطاقات |
| أحكامها | اعتماد / اعتماد مع ملاحظات (دفعة تقوية لاحقة) / إرجاع بقائمة كل بند فيها مرجعه الرسمي |
| ملاحظات CodeRabbit على وحداتك | تُتحقق ضد الكود (بروتوكول #242): الاعتماد بكوميت يذكرها؛ الرفض بالقياس مع الوكيل — لا تتجاهل خيطاً |
| النزاع التقني | المرجع الرسمي يحكم؛ ما لا نص له يُعلَّق للمالك عبر الوكيل — لا اجتهاد |
| استمرارك | لا تتوقف بانتظار المراجعة أو CI — التدفق المستمر هو المنهجية |

## 9. الحواجز التي توقف مسارك (الوحيدة)

1. **فشل ترجمة/بناء أحمر على رأس فرعك** (P0): أصلِحه فوراً قبل أي وحدة تالية — التراكم فوق البناء المكسور محرم.
2. **تجاوز طابور الفشل/الملاحظات النشطة 10 بنود**: تصريف الطابور أولاً (سلامة البناء تسبق السرعة).
3. غير ذلك: لا شيء يوقفك — لا CI، ولا مراجعة معلقة، ولا نتائج بعيدة.

## 10. التصعيد

أي عائق خارج ملكيتك لا يُحل بـCR، أو غموض في المواصفة، أو نقص مرجع رسمي: قيّده في worklog بمعرف `ESC-<رقم>` — الوكيل يتولاه أو يرفعه للمالك. **لا تتجاوز الحاكمية أبداً بالحل اليدوي.**
