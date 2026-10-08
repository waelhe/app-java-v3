# خطة المطابقة التامة للوثائق الرسمية — التنفيذ end-to-end

> **طبيعة هذه الوثيقة:** الوثيقة الحاكمة الوحيدة لأي قرار وتصميم وخطة ومعمرية في المنصة. بُنيت **حصراً** من الوثائق الرسمية ومستودعات الإطار (مع المجتمع الموثوق عند الحاجة) بأمر المالك بتاريخ 2026-10-07. لا تُراجَع أي وثيقة أو خطة داخلية كمرجع — الخطط الداخلية السابقة أرشيف تاريخي للقراءة فقط.
>
> **قاعدة الحاكمية المطلقة:** المستودع والوثائق الرسمية هي الحاكم المطلق والوحيد. لا يجوز مخالفتها مطلقاً ولا التفكير والاجتهاد خارجها. ما لا يوجد له مرجع رسمي يُعلَّق بقرار المالك أو يُقيَّد بأداة مجتمع موثوقة معلنة.

---

## §0 الإعلان الحاكم (بروتوكول AGENTS)

| البند | القيمة |
|---|---|
| **الملف الحاكم** | هذه الوثيقة — تستبدل مرجعية كل الوثائق والخطط الداخلية السابقة |
| **النواة** | الوثائق الرسمية (`spring.io` + `docs.spring.io`) + مستودعات `github.com/spring-projects` حاكم مطلق ووحيد |
| **البند** | أي قرار / تصميم / خطة / معمرية يُشتق من مرجع رسمي دقيق (رابط + قسم) فقط |
| **الأثر** | كل انحراف قديم أو جديد (تدخل يدوي، اجتهاد، مخالفة) يُزال فور اكتشافه وبمرجعه الرسمي |
| **الدين** | صفر — لا يُسمح بتراكم أي انحراف «مؤقت» مهما كان صغيراً |

**التشبيه الحاكم:** الوثيقة الرسمية هي «الميزان القانوني في دار المقاييس» — لا تُوضع طوبة في البناء إلا بعد وزنها عليه؛ وأي طوبة وُضعت بيد خبير بلا ميزان (تدخل يدوي أو اجتهاد) تُنزع من الجدار فوراً ويعاد وزنها.

---

## §0.1 بيان هوية المنصة — توجيه المالك (مسجّل حرفيًا، 2026-10-07)

> «كل الوحدات كاملة وأنا أحدد المسار — وحدة حجز كاملة وما سمّيته أنت أمازون للبضائع كاملة وهكذا كل وحدة... المنصة الآن أصبحت منصة متعددة الأدوار والوظائف والمزودين — نظام مجتمعي على مستوى البلد والمدينة والأحياء، يعتمد على التوثيق والتوصية سواء لانضمام الأعضاء أو مثل Nextdoor العالمي»

**سلطة هذا البند:** سلطة المنتج — توجيه المالك المباشر (نفس نمط قرار الـAttic §9). يحكم **هوية ما يُبنى** حصراً؛ أما **كيفيته التقنية** فتبقى مقيدة بأعمدة «المرجع الرسمي الدقيق» في هذه الخطة — البيان لا يفتح أي اجتهاد تقني خارج الوثائق الرسمية (قاعدة §0 الحاكمة).

### القواعد الحاكمة الثلاث (من نص المالك)

1. **سقف الاكتمال لكل وحدة:** «كل وحدة كاملة» — لا تُعدّ وحدة منجزة ببوابات CI وحدها دون شقها الوظيفي الكامل؛ لكل وحدة عمود DoD مُصاغ **رحلةً** لا اسم ميزة (امتداد بوابة الرحلة سداسية الأضلاع §6 إلى مستوى كل بند من بنود هذا البيان).
2. **المالك يحدد المسار:** ترتيب الوحدات كلمة المالك — الخطة تقترح التبعيات والمالك يخصص التسلسل.
3. **«الهوية البسيطة ← التجربة الكاملة»** — قاعدة التفسير النافذة على كل مسمّى أدناه. نص المالك: «إن أذكر الهوية البسيط لتجربة المستخدم وأما تجربة المستخدم الكاملة أنت أكملها.. أي عندما أقول نشر تعليق أقصد كل تجربة المستخدم وليس فقط هؤلاء». فكل مسمّى يُقرأ تلقائيًا كتجربة كاملة end-to-end، والنموذج المرجعي المفكّك على أنماط المنصة القائمة: الدخول بـ403 التعاقدي لغير المصرّح (لا زر ميت) · التحقق وحدود الطول برسائل العقد · idempotency بمفتاح إدخال يتحمله الطالب تحت فهرس فريد جزئي (سابقة V5 للمدفوعات المقيّسة؛ `@Version` قفل تفاؤلي على تحديثات الصفوف — لا إزالة تكرار المحاولات) · الثبوت والعرض المرتب المرقّم · التفاعلات اللاحقة ببوابات الملكية · الإشعار لكل حدث (حدث بلا مستمع = عيب مقيس) · العرضي النافذ (390px صفر تجاوز) · الجولة الموقّعة النهائية قبل اعتبار البند منجزًا.

### أقسام المنصة الأربعة + الأنظمة العرضية ← الوحدات (قياس الكود الحي 2026-10-07)

| القسم | محتواه بنص المالك | الوحدات المقابلة | الحكم المقيس |
|---|---|---|---|
| **المجتمع** | جديد/شائع · منشورات (سؤال/طلب/توصية/استطلاع/مفقودات) · إعارة مدفوعة · الأخبار والبث ونشاطات ومبادرات ومجموعات ومؤسسات · تفاعل/تعليق/اتصال/محادثة/تواصل | community · messaging · notifications | المنشورات والتعليقات والتفاعلات والمجموعات والأحداث والاستطلاعات (V100) قائمة مقيسة؛ فئتا «سؤال/طلب» إضافة عدّاد فئات على `PostCategory` (سجل الانتظار §13)؛ الإعارة المدفوعة والبث جديدان كليًا (§13)؛ توثيق الانضمام قائم (`MembershipVerificationState`) والتوصية بالتزكية مسجلة (§13) |
| **السوق** | المنتجات · المستعمل · العقار · السيارات · الخدمات (مهني/علمي) · الوظائف | catalog (+ الموجة C الجديدة C.7/C.8) · سوق الحي المستعمل (القائم) · realestate · jobs (C.2) | المنتجات = جذر المتجر M1/M2 (C.7/C.8)؛ السيارات تصنيف قوائم جديد (§13)؛ الخدمات المهنية/العلمية (§13)؛ الوظائف = C.2 |
| **دليل الأعمال** | مراجعة · تقييم · ملف تجاري | reviews · provider | الوحدتان قائمتان مقيسًا؛ عقود الدليل المجمعة في §13 |
| **تعرف على** | دليل معلومات متكاملة عن الحي وسكانه يبنيه المجتمع | knowledge (C.4) | مطابق لتعريف الوحدة الجديدة حرفيًا |
| **الأنظمة العرضية** | لوحة تحكم متقدمة · إشعارات لكل قسم وجزء وموضوع · منتجات وخدمات المجتمع والدليل تظهر في السوق مباشرة · أحدث إصدار · التغيير من اللوحة لا من الكود · بحث بتصنيف وعلى مستوى المدينة والحي | console (C.5) · notifications · كتالوج الأحداث · إصدارات API (B.1) · search + geo | الإشعارات والإصدارات والبحث مقيسة في الموجات؛ «التغيير من اللوحة لا من الكود» يرفع سقف console من أعلام ميزات إلى إدارة مظهر/محتوى (§13 — مواصفة قرار قبل التنفيذ)؛ «الظهور المباشر في السوق» عقد تكامل عبر كتالوج الأحداث (additive-only — §13) |

---

## §1 سلم المرجعية المطلق

| الدرجة | المصدر | ما جُلب وقيِس حياً بتاريخ 2026-10-07 |
|---|---|---|
| 1 | كتالوج وأدلة `spring.io` | `spring.io/projects` (النسخ + Attic) • `spring.io/guides` • `spring.io/projects/spring-boot` |
| 2 | مراجع `docs.spring.io` | فهارس Boot 4.1 / Security 7.1.1 / Modulith 2.1.1 / Framework 7 / Data JPA + الصفحات الحاكمة العميقة (33 صفحة — منها 10 جُلبت في جولة إدراج حزمة المسار A: Data JPA «Persisting Entities / Defining Query Methods / Projections / Auditing» + Boot «SQL Databases / Database Initialization» + Framework «Validation / Java Bean Validation / MVC Caching») |
| 3 | مستودعات `github.com/spring-projects` | المنظمة + `spring-boot` + `spring-security` |
| 4 | مجتمع موثوق — **فقط حيث لا بديل رسمي** | Testcontainers (رسمي أصلاً داخل مرجع Boot!) • springdoc-openapi • MapStruct • Resilience4j (مُدار عبر Spring Cloud CircuitBreaker) • Bucket4j • firebase-admin • ArchUnit (مقترن قياساً مع Modulith) |

**محظورات صريحة (قواعد نافذة):**
1. الرجوع إلى أي وثيقة أو خطة داخلية كمرجع تصميم — تُقرأ كتاريخ فقط.
2. أي اجتهاد أو تصميم خارج نص الوثائق الرسمية.
3. أي تدخل يدوي حيث يوجد مسار تلقائي رسمي موثق.
4. أي نسخة مكتبة خارج سلم BOM `spring-boot-starter-parent` — وهو الحاكم **للنسخ التي يديرها حصراً**: ما يديره الـBOM لا يحمل رقماً من عندنا أبداً. وأدوات المجتمع الموثوقة المعلنة في §5.3 (springdoc-openapi، ArchUnit ...) لا يديرها الـBOM أصلاً، فتُثبَّت أرقامها في pom الجذر بقرار §5.3 نفسه — ليس استثناءً من القاعدة بل نطاقها المُقاس.
5. أي بند جديد في هذه الخطة بلا مرجع رسمي دقيق — يُرفض هيكلياً.

**الأدلة القابلة لإعادة القياس:** كل الجلب الحي محفوظ في `scripts/official-docs-fetch/` (JSON خام + ملخصات نصية لكل صفحة) — إعادة القياس بأمر واحد دون اجتهاد.

---

## §2 أساس النسخ الرسمي المقيس (2026-10-07)

المصدر الرسمي الوحيد للنسخ: `spring.io/projects` (قياس مباشر):

| المشروع | أحدث Stable رسمي | النسخة في المستودع | الحكم |
|---|---|---|---|
| Spring Boot | **4.1.1** (و4.1.2 في الرزنامة الرسمية 2026-10-22) | `spring-boot-starter-parent 4.1.1` | ✅ مطابق تماماً |
| Spring Modulith | **2.1.1** | BOM 2.1.1 | ✅ مطابق تماماً |
| Spring Security | **7.1.x** | 7.1.1 (عبر BOM) | ✅ مطابق تماماً |
| Spring Cloud | **2025.1.3** | 2025.1.3 (edge) | ✅ مطابق تماماً |
| Spring AI | **2.0.1** | 2.0.1 | ✅ مطابق تماماً |
| ArchUnit | (زوج Modulith الرسمي) | 1.4.2 محاذى قياساً بـdependency:tree | ✅ مطابق |
| springdoc-openapi | (مجتمع) | 3.1.0 | ✅ أحدث خط رئيسي |
| Spring Session | **4.1.x** | عبر BOM (spring-session-core) | ✅ مطابق |

**⚠️ قياس حاكم جديد — «Spring Authorization Server» في الـAttic:**
قياساً على الصفحة الرسمية `spring.io/projects`: المشروع مدرج تحت **Projects in the Attic**، وصفحة مشروعه `spring.io/projects/spring-authorization-server` تُرجع **404 مقيساً**. في المقابل (قياساً لا تخميناً):
- مرجع Boot 4.1.1 ما زال يوثّق فئة `spring-boot-security-oauth2-authorization-server` في ملحق auto-configuration الرسمي.
- مرجع Security 7.1.1 ما زال يضم أقسام `servlet/oauth2/authorization-server/*` كاملة (getting-started, configuration-model, protocol-endpoints, core-model-components).
→ **الاستخدام الحالي موثق رسمياً** من طرفي Boot وSecurity، لكن وضعه الاستراتيجي «متقاعد». الحسم بيد المالك حصراً — التفصيل والخيارات في §9.

**قاعدة المعمرية (نافذة):** أي ترقية مستقبلية = `spring.io/projects` + `Release Calendar` + `docs.spring.io/spring-boot/upgrading.html`، والتنفيذ عبر BOM Boot حصراً (ملحق dependency-versions الرسمي هو سلم النسخ المُدار).

---

## §3 الوضع الحالي المقاس (حقائق كود مباشرة)

### §3.1 البنية
- **22 وحدة Maven** تحت أب واحد `spring-boot-starter-parent 4.1.1`، Java 25:
  `shared, platform-infra, identity, catalog, booking, payments, pricing, reviews, messaging, search, provider, availability, notifications, ledger, disputes, media, geo, realestate, community, ai, app, edge`
- **Modulith 2.1.1** بالكامل الرسمي: `starter-core` + `starter-jpa` + `events-jpa` + `actuator` + `observability` + `docs` + `starter-test`.
- **بوابات البنية قائمة مقيساً:** `ModulithVerificationTest` + `ModulithDocumentationTest` في `marketplace-app` (تعملان ضمن CI).
- **العقود:** springdoc-openapi 3.1.0 (107 مسار مُوثقة حياً) + `oauth2-authorization-server` + `spring-session-core`.
- **الاختبار:** Testcontainers (junit-jupiter + postgresql) + JaCoCo بعتبة 0.70 + maven-enforcer.
- **المكدس المكمّل:** Redis • WebSocket (STOMP) • MapStruct 1.6.3 • Resilience4j 2.4.0 • Spring AI 2.0.1 • Spring Cloud 2025.1.3 (edge BFF).

### §3.2 بوابات CI القائمة (تعمل فعلاً على PR #504)
CI (Build & Test JDK 25) + CodeQL + Container Scan (Trivy) + Integration Test (JDK 25) + CodeRabbit.

### §3.3 جودة الوحدات (تدقيق مقيس سابق، متوسط ~70/100)
| الوحدة | الدرجة | الوحدة | الدرجة | الوحدة | الدرجة |
|---|---|---|---|---|---|
| platform-infra | 88 | reviews | 85 | app | 85 |
| shared | 82 | search | 82 | identity | 78 |
| media | 78 | payments | 78 | provider | 72 |
| geo | 70 | edge | 70 | community | 70 |
| messaging | 70 | catalog | 74 | pricing | 68 |
| realestate | 65 | booking | 64 | availability | 55 |
| ledger | 62 | disputes | 38 | ai | 35 |

صفر TODO في كل الوحدات — لكن الفجوة إلى «المستوى المتقدم» مقياسة ومغلقة في هذه الخطة.

### §3.4 عيوب مقيسة على الكود (تُغلق في الموجة 0 بمرجعها الرسمي)
1. انحراف اسم الكاش `search-results-v4` في GeoService + RealestateService مقابل v5 الحي.
2. عدّاد غير المقروء يشمل رسائل المرسل نفسه.
3. غياب idempotency للرسائل ومحدد معدل الإرسال.
4. حدث `BookingConfirmedEvent` منشور بلا مستقبِل (تسليم ميت) + عيوب 404/403.
5. وحدة disputes: صفر أحداث تطبيقية (المال صحيح، المنتج حقل نصي).
6. غياب حذف الإشعار/تعليم الكل كمقروء.
7. قوالب استعادة كلمة المرور وتوثيق البريد نائمة (غير مفعلة).
8. غياب إشعار `MESSAGE_RECEIVED`.

---

## §4 مصفوفة المطابقة العرضية — قلب الخطة

كل قطاع أفقي في المنصة مقيد بمرجعه الرسمي الدقيق، وكل إغلاق محمي ببوابة آلية (لا قرار يدوي):

| # | المجال | المرجع الرسمي الحاكم | الوضع المقيس | المستوى المتقدم المستهدف | البوابة الآلية |
|---|---|---|---|---|---|
| 1 | بنية الوحدات وحدودها | `docs.spring.io/spring-modulith/reference/fundamentals.html` + `verification.html` | verify() قائم في CI | `@ApplicationModule(allowedDependencies=…)` لكل وحدة + NamedInterfaces + خيار jMolecules (يُفعّل تلقائياً عند وجوده بالوثيقة) | `ModulithVerificationTest` |
| 2 | الأحداث التطبيقية والاتساق | `docs.spring.io/spring-modulith/reference/events.html` | سجل نشر JDBC قائم (`events-jpa`) + عيوب مقيسة (§3.4) | إتمام آلي للنشر + إعادة نشر الأحداث المعلقة عند إعادة التشغيل (`spring.modulith.events.republish-outstanding-events-on-restart` — **بقيد الطوبولوجيا الموثق**: مع مثيل تطبيق واحد سليم حصراً؛ تعدد المثيلات قد يعالج الحدث المعلق نفسه تزامناً، فإما مستمعات مُdemo تُحل بالمقاطعة أو مستمعات idempotent — وثيقة Modulith نفسها تحذر من هذا) + مراقبة Actuator | اختبارات `PublishedEvents` + IT للرحلة |
| 3 | الوظائف الزمنية | `docs.spring.io/spring-modulith/reference/moments.html` (جديد 2.1) | وظائف يدوية مجدولة (AdBillingJob, ListingExpiryJob, ListingRankingJob) | أحداث `HourHasPassed…YearHasPassed` + `TimeMachine` في الاختبارات (`spring.modulith.moments.enable-time-machine=true`) | اختبارات TimeMachine حتمية |
| 4 | الجلسات وتعدد الأجهزة | `docs.spring.io/spring-security/reference/servlet/authentication/session-management.html` | remember-me مستمر + جلسة 30 يوماً (PR #504) | سياسة الأجهزة المتعددة: `maximumSessions` + `maxSessionsPreventsLogin` + حماية session fixation (`changeSessionId`) | اختبارات MockMvc للجلسات |
| 5 | تخزين كلمات المرور | `docs.spring.io/spring-security/reference/features/authentication/password-storage.html` | PasswordEncoder قائم | `DelegatingPasswordEncoder` مع `upgradeEncoding` (bcrypt/argon2 حسب الوثيقة) | اختبار ترميز + فحص دوري |
| 6 | تفويض الطرق | `docs.spring.io/spring-security/reference/servlet/authorization/method-security.html` | تغطية جزئية | تغطية كاملة لكل عملية حساسة بـ`@PreAuthorize` | اختبارات تفويض لكل مسار حساس |
| 7 | عقد الأخطاء (RFC 7807) | `docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html` | عقد أخطاء قائم + عيوب 404/403 مقيسة | تفعيل التلقائي الرسمي `spring.mvc.problemdetails.enabled` + `ErrorResponseException` الموحد (التلقائي قبل اليدوي حرفياً بنص الوثيقة) | اختبارات عقد الأخطاء لكل الوحدات |
| 8 | إصدارات API للجوال | `docs.spring.io/spring-framework/reference/web/webmvc-versioning.html` (جديد Framework 7) | غير موجود | `@ApiVersion` + استراتيجيات (header/query/media-type/path) + **deprecation hints في الاستجابات** (`ApiVersionDeprecationHandler`) | اختبارات توجيه الإصدارات والإهمال |
| 9 | الترقيم والاستعلام | `docs.spring.io/spring-data/jpa/reference/` (repositories/projections.html) | ترقيم قائم | توحيد ترقيم الويب + Projections للواجهات | اختبارات شرائح البيانات |
| 10 | التدقيق والزمن | `docs.spring.io/spring-data/jpa/reference/auditing.html` | جزئي | `@EnableJpaAuditing` (createdBy/createdDate/…) في كل الكيانات الحساسة | اختبار حقول التدقيق |
| 11 | الكاش | `docs.spring.io/spring-boot/reference/io/caching.html` + `docs.spring.io/spring-framework/reference/integration/cache.html` | انحراف v4/v5 مقيس | توحيد المفاتيح + إبطال بمعامل الأحداث الرسمي (`@CacheEvict` قبل التحديث) | اختبارات إصابة/إبطال الكاش |
| 12 | المراسلة الحية | `docs.spring.io/spring-boot/reference/messaging/websockets.html` + `docs.spring.io/spring-security/reference/servlet/integrations/websocket.html` | /ws قائم بمصادقة JWT موثقة | توجيه `/ws` عبر edge + تكامل أمان الرسائل الرسمي (CSRF للقناة + تفويض الاشتراك) | IT حي للقناة |
| 13 | الاختبار القاري | `docs.spring.io/spring-boot/reference/testing/testcontainers.html` | Testcontainers قائمة | `@ServiceConnection` حيث تدعمها رسمياً (حاويات الخدمات المعروفة — الوثيقة نفسها تحصرها) بدل `DynamicPropertySource`؛ وتبقى `DynamicPropertySource` أداة الخصائص الاعتباطية وبدائل الاختبار والخدمات غير المدعومة — لا استبدال شاملاً | CI + Integration Test |
| 14 | المراقبة | `docs.spring.io/spring-boot/reference/actuator/observability.html` + Modulith `production-ready.html` | اعتماديات actuator/observability قائمة | health groups + مقاييس Micrometer + تتبع + نقاط Modulith للنشر غير المكتمل | اختبار دخان /actuator |
| 15 | النشر والحاويات | `docs.spring.io/spring-boot/reference/packaging/container-images/dockerfiles.html` + `cloud-native-buildpacks.html` | Dockerfile + Container Scan قائمان | المساران موثقان رسمياً — البقاء على Dockerfile المحسّن مع تقييم buildpacks عند الحاجة | Container Scan (Trivy) |
| 16 | العقود المفتوحة للجوال | `docs.spring.io/spring-data/jpa/reference/` + دليل `spring.io/guides` «Building a RESTful Web Service» + springdoc (مجتمع موثوق) | springdoc بـ107 مسار | مواصفة OpenAPI حية + فحص diff في CI + عينات REST Docs الرسمية للمسارات الحرجة | فحص توافق المواصفة |

---

## §5 خريطة الوحدات ↔ المشاريع الرسمية الحاكمة

### §5.1 الوحدات القائمة (22)

| الوحدة | المشروع الرسمي الحاكم | مرجع القسم الحاكم | أبرز فجوة متقدمة |
|---|---|---|---|
| shared | Spring Framework | `docs.spring.io/spring-framework/reference/` (core) | --- |
| platform-infra | Spring Boot | `reference/features/external-config.html` + `reference/actuator/*` | health groups |
| identity | Spring Security + Session | `servlet/authentication/*` + `docs.spring.io/spring-session/reference/` | استعادة كلمة المرور + توثيق البريد (§6-A) |
| catalog | Data JPA + Cache | `auditing.html` + Boot `io/caching.html` | جذر المتجر M1/M2 (C.7/C.8: كيان Product + القاموس + أسئلة/أجوبة المنتج) ثم variants عبر orders |
| booking | Modulith Events | `events.html` | سياسات إلغاء/إعادة جدولة بالأحداث |
| payments | Boot Web + Resilience (Cloud CircuitBreaker) | `reference/io/rest-client.html` + Resilience4j | payouts عبر ledger |
| pricing | Data JPA | `repositories/projections.html` | كوبونات |
| reviews | Data JPA | `repositories/projections.html` | --- (الأقوى منتجاً) |
| messaging | WebSocket + Security | `messaging/websockets.html` + `servlet/integrations/websocket.html` | إيصالات القراءة + idempotency |
| search | Data JPA | `jpa/locking.html` للعدّادات | تحليل عربي |
| provider | Web MVC | `docs.spring.io/spring-framework/reference/web/webmvc.html` | KYC |
| availability | **Modulith Moments** | `moments.html` | تصحيح كتابي + iCal |
| notifications | Boot Email + Events | `reference/io/email.html` + Modulith events | Push + i18n عربية |
| ledger | Data JPA | `jpa/transactions.html` + `jpa/locking.html` | قيد مزدوج |
| disputes | Modulith Events | `events.html` | **صفر أحداث — إغلاق في الموجة 0** |
| media | Boot | `reference/features/index.html` | هدف `productId` الثالث في بوابة القناة (C.7) + EXIF-strip + فيديو |
| geo | Data JPA | --- | توحيد الكاش v5 |
| realestate | Data JPA | --- | توحيد الكاش v5 |
| community | Data JPA + Events | `events.html` | تعميم العضوية لنواة المؤسسات (C.3 — حكم المالك) + خيوط متداخلة + منشنات |
| ai | **Spring AI** | `docs.spring.io/spring-ai/reference/` | سطح منتج + تكلفة |
| app | Modulith + Boot Packaging | `reference/packaging/*` | --- (البوابة الأم) |
| edge | Security OAuth2 + Cloud | `servlet/oauth2/*` + Cloud 2025.1.3 | توجيه `/ws` + قرار Attic (§9) |

### §5.2 الوحدات الخمس الجديدة (بنية Modulith الرسمية حصراً)

| الوحدة الجديدة | الغرض (رؤية المالك) | المشروع الرسمي الحاكم | قاعدة البنية |
|---|---|---|---|
| orders | سوق الطلبات (سلة→طلب→تنفيذ) | Modulith (events + verification) + payments/ledger عبر أحداث | `@ApplicationModule(allowedDependencies)` من اليوم الأول |
| jobs | الوظائف | Data JPA + Moments (وظائف زمنية إن لزم) | نمط reviews (الأقوى) |
| institutions | الجهات/المؤسسات | Data JPA + نمط provider | كيانات + JSON-LD عبر Web MVC |
| knowledge | قاعدة المعرفة | Data JPA + search (FTS) | تكامل بحث بالأحداث |
| console | لوحة تحكم بلا كود | Boot `reference/features/external-config.html` (ConfigurationProperties) + actuator | أعلام ميزات + Remote Config بخصائص Boot (بحدود §C.10 المقيسة: خصائص Boot للثابت زمن الإقلاع حصراً؛ والتشغيلي-الجغرافي بيانات تُفحص في طبقة الخدمة وقت الطلب) |

### §5.3 أدوات المجتمع الموثوقة (فقط حيث لا بديل رسمي — معلنة صراحة)

| الأداة | الغرض | مسوّغ الثقة | البديل الرسمي (إن وجد) |
|---|---|---|---|
| Testcontainers | اختبارات قارية | **موثقة رسمياً داخل مرجع Boot** (`reference/testing/testcontainers.html`) | هي المسار الرسمي فعلاً |
| springdoc-openapi 3.1.0 | مواصفة OpenAPI حية | المعيار الفعلي للمنظومة | Spring REST Docs 4.0.1 (رسمي — يُستخدم للعينات الحرجة) |
| MapStruct 1.6.3 | تحويل الكيانات | معيار مجتمعي واسع | لا يوجد رسمي |
| Resilience4j 2.4.0 | الصمود | مُدار عبر Spring Cloud CircuitBreaker (رسمي) | --- |
| Bucket4j | محدد معدل الإرسال | معيار مجتمعي لـrate limiting | لا يوجد رسمي في الإطار |
| firebase-admin | Push (FCM) | SDK رسمي من Google للمنظومة | لا يوجد مكافئ رسمي في Spring |
| ArchUnit 1.4.2 | تحقق معماري إضافي | الزوج المقيس لـModulith (تعليق pom الموثق) | Modulith verify() (رسمي — الأساس) |
| Instancio 6.0.0 | بيانات اختبار | معيار مجتمعي | --- |
| GreenMail 2.1.14 | خادم بريد داخل JVM لاختبارات السلسلة البريدية الحقيقية (رحلة A-04) | معيار مجتمعي معتمد — وصفة امتداده الرسمية لـJUnit 5؛ تبنٍّ بكلمة المالك 2026-10-07 | لا يوجد بديل رسمي في الإطار |
| jqwik 1.10.1 | اختبارات الخواص: ثوابات رياضيات المال (ledger) على مدخلات مولَّدة | دليل المستخدم الرسمي للمكتبة؛ تبنٍّ بكلمة المالك 2026-10-07 | لا يوجد بديل رسمي |

---

## §6 الموجات — شرائح رأسية ببوابة الرحلة الكاملة

**قاعدة بوابة الرحلة (نافذة في كل موجة):** لا تُعدّ أي موجة «منتهية» حتى تُسلّم رحلة مستخدم كاملة قابلة للقياس آلياً، بأضلاعها الستة:
1. خلفية (API + أحداث) 2. عقد مفتوح للجوال (OpenAPI) 3. إشعار (القناة المناسبة) 4. بحث/اكتشاف 5. عربية/i18n 6. **ضلع عمر التطبيق** (اللحظات المعنية من §7) — ويُقاس ذلك باختبار رحلة آلي واحد على الأقل لكل موجة.

**قاعدة الدفع الحاكمة:** كل كوميت يُدفع بلا دمج — الدمج بكلمة المالك حصراً بعد اخضرار البوابات الخمس (CI + CodeQL + Container Scan + IT + CodeRabbit).

### الموجة 0 — بوابة المطابقة الآلية وإغلاق العيوب المقيسة
| البند | الإجراء | المرجع الرسمي الدقيق | البوابة الآلية |
|---|---|---|---|
| 0.1 | رفع بوابة البنية: `allowedDependencies` لكل وحدة قائمة | `spring-modulith/reference/fundamentals.html` («Explicit Application Module Dependencies») | `ModulithVerificationTest` يفشل عند أي اعتماد غير مصرح |
| 0.2 | توحيد الكاش `search-results-v5` في GeoService + RealestateService | Boot `reference/io/caching.html` + Framework `integration/cache/annotations.html` | اختبار إصابة/إبطال |
| 0.3 | عدّاد غير المقروء يستثني رسائل المرسل | Data JPA `reference/` (Query Methods) | اختبار وحدة |
| 0.4 | idempotency الرسائل بمفتاح إدخال يتحمله الطالب (فهرس فريد جزئي — سابقة V5/V150 المقيّسة، مع إعادة تشغيل المفتاح و403 ملكية المفتاح) | Data JPA `jpa/locking.html` (للتزامن فقط) + سابقة V150 المقيسة | اختبار مفتاح + إعادة محاولة |
| 0.5 | محدد معدل الإرسال (Bucket4j — مجتمع موثوق معلن) | §5.3 | اختبار حد المعدل |
| 0.6 | تسليم `BookingConfirmedEvent` الميت + إصلاح 404/403 بـ`ErrorResponseException` | Modulith `events.html` + Framework `mvc-ann-rest-exceptions.html` | اختبار PublishedEvents + عقد الأخطاء |
| 0.7 | أحداث النزاعات + تفعيل الاسترداد الجزئي | Modulith `events.html` | اختبار أحداث النزاعات |
| 0.8 | حذف إشعار + تعليم الكل كمقروء | Data JPA `reference/` | اختبار REST |
| 0.9 | تفعيل `spring.mvc.problemdetails.enabled` (التلقائي الرسمي) | Framework `mvc-ann-rest-exceptions.html` (نص الوثيقة) | اختبارات عقد موحدة |
| 0.10 | إشعار `MESSAGE_RECEIVED` (Modulith event) | Modulith `events.html` | اختبار نشر الحدث |

### الموجة A — الهوية والأمن المتقدم (Spring Security)
| البند | الإجراء | المرجع الرسمي الدقيق | البوابة |
|---|---|---|---|
| A.1 | استعادة كلمة المرور (رمز أحادي محدد الزمن) | Boot `reference/io/email.html` + Security `features/authentication/password-storage.html` | IT كامل للرحلة |
| A.2 | توثيق البريد (تفعيل القوالب النائمة) | Boot `reference/io/email.html` | IT |
| A.3 | حذف الحساب ذاتياً (متطلب متاجر التطبيقات) | Security `servlet/authentication/logout.html` + Data JPA (GDPR purge قائم) | IT حذف |
| A.4 | سياسة تعدد الأجهزة | Security `servlet/authentication/session-management.html` (`maximumSessions`, `maxSessionsPreventsLogin`) | اختبار جلسات متزامنة |
| A.5 | تغطية تفويض الطرق الكاملة | Security `servlet/authorization/method-security.html` | اختبارات @PreAuthorize |
| A.6 | `upgradeEncoding` للترميز | Security `features/authentication/password-storage.html` | اختبار ترميز |

### الموجة B — عقود الجوال وقنوات الوصول
| البند | الإجراء | المرجع الرسمي الدقيق | البوابة |
|---|---|---|---|
| B.1 | إصدارات API: `@ApiVersion` + deprecation hints | Framework `web/webmvc-versioning.html` | اختبار توجيه/إهمال |
| B.2 | بيئة رمل staging بـProfiles | Boot `reference/features/profiles.html` | فحص profile |
| B.3 | ETag/طلبات شرطية للقراءة دون اتصال | Framework `web/webmvc/mvc-caching.html` | اختبار 304 |
| B.4 | توجيه `/ws` عبر edge | Security `servlet/integrations/websocket.html` | IT قناة حية |
| B.5 | قناة Push (FCM عبر firebase-admin — مجتمع معلن) | §5.3 | IT تسجيل رمز + تسليم |
| B.6 | i18n عربية للإشعارات والرسائل | Boot `reference/features/internationalization.html` | اختبار locales |
| B.7 | مواصفة OpenAPI كاملة + فحص diff في CI | springdoc + دليل «Building a RESTful Web Service» | بوابة CI |

### الموجة C — الوحدات الخمس الجديدة (بنية Modulith الرسمية)
| البند | الإجراء | المرجع الرسمي الدقيق | البوابة |
|---|---|---|---|
| C.1 | وحدة orders (سلة→طلب→تنفيذ بآلة حالات بالأحداث) | Modulith `fundamentals.html` + `events.html` | verify() + اختبار رحلة طلب |
| C.2 | وحدة jobs | Data JPA `reference/` + نمط reviews | verify() + اختبارات |
| C.3 | وحدة institutions فوق نواة المجتمع لا بجانبه — حكم المالك 2026-10-07 المسجّل حرفيًا: «المؤسسات فيها جزء من المجتمع»: تعميم العضوية داخل community على الآلة القائمة المقيسة (`NeighborhoodMembership` + `MembershipVerificationState`) + حواف المؤسسة الخاصة (سجل الجهة + JSON-LD + التحقق المؤسسي) في الوحدة الجديدة — **بلا تكرار أي آلية عضوية خارج community** | Data JPA `reference/` + Web MVC (JSON-LD) + أحداث community | verify() + اختبارات |
| C.4 | وحدة knowledge | Data JPA + search بالأحداث | verify() + اختبارات |
| C.5 | وحدة console (أعلام ميزات + Remote Config بخصائص Boot — بحدود §C.10: الثابت زمن الإقلاع في الخصائص، والتشغيلي-الجغرافي بيانات وقت الطلب) + تصنيفية اللوحة الثلاثية (تشغيل/محتوى/نظام — تقرير الرؤية المُدرج): التشغيل (مستخدمون/مزودون/مدفوعات/دعم) · المحتوى (مجتمع/سوق/دليل/إشراف) · النظام (وحدات وإعدادات C.10 · بحث · تحديثات C.12) + التحليلات والتدقيق: قراءة المقاييس من Actuator وسجل التدقيق من حقول Data JPA Auditing — **اللوحة إعداد وتشغيل ومحتوى وسياسات؛ والقدرة غير الموجودة كوداً تبقى تطويراً** (حد §0.1 النافذ) | Boot `reference/features/external-config.html` + Actuator `reference/actuator/endpoints.html` + Data JPA `reference/auditing.html` | اختبار خصائص + فحص لوحة + اختبار قراءة المقاييس/التدقيق |
| C.6 | ترحيل الوظائف الزمنية إلى Moments | Modulith `moments.html` + TimeMachine | اختبارات TimeMachine |
| C.7 | M1 — جذر المتجر (١/٢): كيان Product في وحدة catalog (بائع/فئة/سعر/حالة — الكيان والمستودع ومساراته) + قاموس فئات المتجر **بياناتًا لا ترحيلات** (جدول سجل مرجعي + بوابة كتابة 400/403) وبذرته فئات «سوق الحي» الثماني من نموذج تصميم المالك نفسه + توسيع قناة الوسائط لقبول `productId` هدفًا ثالثًا في بوابة «هدف واحد بالضبط» القائمة | Data JPA `reference/jpa/entity-persistence.html` (حفظ الكيان واكتشاف حالته) + Boot `how-to/data-initialization.html` (البذور بيانات تهيئة والترحيل بأداة Migration الرسمية الموثقة) + Framework `reference/core/validation/beanvalidation.html` (بوابة الكتابة — عقد التحقق) | verify() + جولة صور موقّعة |
| C.8 | M2 — جذر المتجر (٢/٢): أسئلة/أجوبة المنتج + ملخص البائع العام (ملفات جديدة في catalog) | Data JPA `reference/repositories/query-methods-details.html` (اشتقاق الاستعلامات) + `reference/repositories/projections.html` (الإسقاط المغلق للملخص) + `reference/auditing.html` (حقول التدقيق) | verify() + اختبارات عقد |
| C.9 | «الثقة والتوثيق» خدمة عرضية مشتركة (تقرير الرؤية المُدرج بأمر المالك 2026-10-07) — **بلا وحدة جديدة** (نمط حكم C.3): (١) عقد حالة التوثيق المرجعي = آلة `MembershipVerificationState` القائمة في community (طلب ← تحقق ← منحة/رفض) مع حقول التدقيق الرسمية؛ (٢) قناة الاستهلاك العرضية = كتالوج أحداث Modulith (حدث عند كل منحة/سحب توثيق وبلاغ محسوم — additive-only) تستهلكه الوحدات (search للترتيب · catalog لشارة المتجر · community لسقف الرؤية)؛ (٣) بوابات مسارات التوثيق الإدارية بأدوار Security الرسمية حصراً | Modulith `events.html` (كتالوج الأحداث — قناة الاستهلاك العرضية) + Data JPA `reference/auditing.html` (حقول التدقيق) + Security `servlet/authorization/method-security.html` (بوابة الدور) | verify() + IT رحلة إشارة ثقة: منحة توثيق ← حدث ← أثر مقيس في وحدة مستهلكة |
| C.10 | إعدادات ميزات وارثة جغرافيًا: بلد ← مدينة ← حي (تقرير الرؤية المُدرج) — تُدار من console (C.5) و**بيانات لا ترحيلات** (نمط قاموس V70): جدول إعدادات على هرم geo القائم (مفتاح ميزة + نطاق جغرافي + قيمة) بحلّ «الأخص يغلب الأعم» (استعلام أقرب سلف)؛ بوابة كتابة إدارية بعقد التحقق 400/403 (نمط C.7)؛ **الحدود المقيسة**: تهيئة Boot (`external-config`) تحكم الثابت زمن الإقلاع حصراً — والجغرافي-التشغيلي بيانات تُفحص في طبقة الخدمة وقت الطلب (لا `@ConditionalOnProperty` جغرافيًا: آليته زمن الإقلاع لا زمن الطلب)؛ علم ميزات خارج هذا التصميم ⇒ بديل رسمي أو مجتمع موثوق بقاعدة §5.3 وبكلمة المالك | Data JPA `reference/repositories/query-methods-details.html` (استعلام حلّ الهرم — أقرب سلف) + Boot `reference/features/external-config.html` (حدود الثابت/التشغيلي) + Framework `reference/core/validation/beanvalidation.html` (بوابة الكتابة) | اختبار الوراثة (الحي يغلب المدينة يغلب البلد) + IT رحلة بوابة ميزة بسياق جغرافي |
| C.11 | محرك قواعد إشراف تلقائية (تقرير الرؤية المُدرج) — فوق الجهاز المقيس القائم (ContentReport + ModerationAdminController + إشراف reviews): جدول قواعد **بيانات لا ترحيلات**: شرط عتبي (عمر الحساب · عدد البلاغات المفتوحة · حالة الثقة C.9) ← إجراء من مفردات الإشراف القائمة حصراً (لا إجراء/تحذير/إخفاء/تجميد/طلب توثيق/رفع للمراجعة)؛ التقييم داخل معاملة إنشاء البلاغ (رجوع ذري) + حدث Modulith بالقرار يستهلكه الإشراف والإشعارات؛ CRUD القواعد ببوابة الدور الإدارية | Data JPA `reference/jpa/transactions.html` (التقييم داخل المعاملة) + Modulith `events.html` (حدث القرار) + Security `servlet/authorization/method-security.html` (بوابة إدارة القواعد) | اختبار المحرك (شرط ← إجراء آلي) + اختبار عقد CRUD القواعد |
| C.12 | نظام تحديثات المنصة (تقرير الرؤية المُدرج + «أحدث إصدار» §0.1): صفوف إصدار **بيانات** (رقم · قناة android/ios/web · سجل تغييرات · حد أدنى للإصدار · إجبارية التحديث · نافذة توفير) ينشرها المدير من console؛ العقد العام: مسار إصدار عام يقرؤه العميل عند الإقلاع (لحظة §7/2) + الإهمال المدار للـAPI عبر `@ApiVersion` deprecation hints (B.1) — تحديث التطبيق (بيانات الإصدار) وإهمال الواجهة (رأس الاستجابة الرسمي) **عقدان متمايزان** — الأول يفرض تحديث التطبيق، والثاني يوصي بالإهمال ولا يفرض شيئاً — يلتقيان في عرض العميل معاً ويبقى لكل منهق حاكمه؛ الفان-آوت حدث Modulith ← notifications | Framework `web/webmvc-versioning.html` (deprecation hints — الإهمال المدار) + Data JPA `reference/` (صفوف الإصدار) + Modulith `events.html` (حدث النشر) | IT رحلة: نشر إصدار من اللوحة ← المسار العام ← حدث الإشعار |

### الموجة D — الأحداث المتقدمة والصمود
| البند | الإجراء | المرجع الرسمي الدقيق | البوابة |
|---|---|---|---|
| D.1 | مراقبة النشر غير المكتمل عبر Actuator | Modulith `production-ready.html` | اختبار دخان |
| D.2 | سياسة إعادة نشر الأحداث المعلقة | Modulith `events.html` (`republish-outstanding-events-on-restart`) | IT إعادة تشغيل |
| D.3 | استحداث الأحداث خارجياً عند الحاجة (Kafka/AMQP الرسميان `spring-modulith-events-kafka/amqp`) | Modulith `events.html` (externalization) | IT وسيط |
| D.4 | صمود القنوات الخارجية | Spring Cloud CircuitBreaker (Resilience4j) | اختبار عزل |

### الموجة E — المراقبة والرحلة والنشر
| البند | الإجراء | المرجع الرسمي الدقيق | البوابة |
|---|---|---|---|
| E.1 | health groups + مقاييس + تتبع | Boot `reference/actuator/observability.html` | دخان /actuator |
| E.2 | اختبار الرحلة الكاملة الآلي (أضلاع §6 الستة) | تجميع المراجع (Modulith testing + Boot testing) | بوابة الرحلة في CI |
| E.3 | توثيق معماري مولّد آلياً (Documenter) | Modulith `documentation.html` | تجدد الوثائق في CI |
| E.4 | تقييم buildpacks مقابل Dockerfile المحسّن | Boot `packaging/container-images/*` | Container Scan |

---

## §7 ضلع عمر التطبيق — التجربة من فتح التطبيق إلى إغلاقه

كل لحظة مقيدة بمرجعها الرسمي (لا اجتهاد):

| # | اللحظة | الحكم الرسمي الحاكم | الموجة |
|---|---|---|---|
| 1 | الإقلاع البارد وجاهزية النظام | Boot Actuator: `reference/actuator/endpoints.html` (health/readiness) | قائم |
| 2 | الشاشة الأولى/إعدادات الإقلاع | عقد OpenAPI (springdoc §5.3) + دليل «Building a RESTful Web Service» | B |
| 3 | الأذونات (إشعارات/موقع) | راية أذونات عبر عقد API — client-side يحكمها المتجر | B |
| 4 | تسجيل الدخول (PKCE للجوال) | Security `servlet/oauth2/client/authorization-grants.html` | قائم (عميل عام PKCE) |
| 5 | استعادة الجلسة بعد إعادة الفتح | Security `servlet/authentication/rememberme.html` + `session-management.html` | قائم (PR #504) |
| 6 | تعدد الأجهزة | Security `session-management.html` (concurrent sessions) | A |
| 7 | العمل دون اتصال (مزامنة لاحقة) | Framework `web/webmvc/mvc-caching.html` (ETag) + Data JPA `jpa/locking.html` (قفل متفائل) | B |
| 8 | الخلفية والإشعارات | قناة Push (B.5) + Modulith events | B |
| 9 | الروابط العميقة | معرفات مستقرة عبر عقد OpenAPI + `@ApiVersion` | B |
| 10 | التحديث الإجباري/الاختياري | Framework `web/webmvc-versioning.html` (deprecation hints — بُعد إهمال الواجهة، لا فرض تحديث) + console (سياسة إصدار التطبيق — البعد الآخر) | B/C → **C.12 (المصمَّم — العقدان متمايزان)** |
| 11 | الإغلاق الآمن / الخروج | Security `servlet/authentication/logout.html` (+ Clear-Site-Data منفذة في PR #504) | قائم |
| 12 | انتهاء الجلسة/الرمز | Security `session-management.html` (`invalid-session-url`, انتهاء الجلسات المتزامنة) | A |
| 13 | حذف الحساب والإغلاق النهائي | Data JPA (GDPR purge قائم) + Security logout | A |

## §8 عقد مطور الجوال — ما يحتاجه حكماً

| المطلوب | الوضع المقيس | المرجع الرسمي | الموجة |
|---|---|---|---|
| مواصفة OpenAPI حية | ✅ 107 مسار (springdoc) | دليل «Building a RESTful Web Service» + springdoc | B (فحص diff) |
| عقد أخطاء موحد RFC 7807 | ✅ قائم + تحسين تلقائي | Framework `mvc-ann-rest-exceptions.html` + `spring.mvc.problemdetails.enabled` | 0 |
| مصادقة OIDC/PKCE للعميل العام | ✅ قائمة | Security `oauth2/client/authorization-grants.html` | قائم |
| استعادة الجلسة | ✅ (PR #504) | Security `rememberme.html` | قائم |
| ترقيم موحد | ✅ قائم | Spring Data `reference/` (Web support) | قائم |
| مراسلة حية | ✅ `/ws` بـJWT | Boot `messaging/websockets.html` | B (توجيه edge) |
| إصدارات API وإهمال تدريجي | ❌ غائب | Framework `web/webmvc-versioning.html` | B |
| بيئة رمل (sandbox) | ❌ غائب | Boot `reference/features/profiles.html` | B |
| تسجيل رموز Push | ❌ غائب | §5.3 (firebase-admin) | B |
| سياسة الأجهزة المتعددة | ❌ غائب | Security `session-management.html` | A |
| تحديث إجباري/اختياري | ❌ غائب | §7 لحظة 10 — صُمم في C.12 | B/C |
| دليل تكامل للجوال | ❌ غائب | عينات REST Docs الرسمية | B |

## §9 حوكمة الـAttic — قرار المالك المطلوب (القرار التقني الوحيد المعلق — سجل الانتظار §13 احتياجات منتجية بمعزل عنه)

الحقائق الأربع المقيسة (§2): (1) «Spring Authorization Server» في Attic رسمياً؛ (2) صفحة مشروعه 404؛ (3) Boot 4.1.1 يوثّق فئة auto-configuration له؛ (4) مرجع Security 7.1.1 يوثّق أقسامه كاملة.

| الخيار | الوصف | الأثر |
|---|---|---|
| **أ — الاستقرار المراقَب (توصية الخطة)** | البقاء على الوضع الحالي تحت BOM Boot 4.1.1 (موثق رسمياً بالطرفين) + رصد دوري لسحب الدعم من مرجع Boot التالي | صفر مخاطرة فورية؛ رصد بروتوكول §12 |
| ب — خادم هوية خارجي (Keycloak — مجتمع موثوق) | ترحيل edge إلى وسيط هوية مستقل في موجة مستقلة | تكلفة موجات إضافية؛ قرار استراتيجي |
| ج — تقليص النطاق | تخدم edge العملاء الحاليين فقط بلا ميزات جديدة | أقل كلفة، يقيد نمو الجوال |

القرار بكلمة المالك حصراً — والخطة لا تفترض أي خيار.

## §10 قواعد منع التدخل اليدوي (التلقائي أولاً — نافذة)

1. **التلقائي حيث يوجد رسمياً:** كل ميزة لها مسار تلقائي موثق تُنفَّذ به (مثال نصّي: `spring.mvc.problemdetails.enabled` يهيّئ المعالج تلقائياً بدل كتابته يدوياً).
2. **الوثائق تُولَّد لا تُكتب:** توثيق المعمارية عبر Modulith Documenter (`documentation.html`) يتجدد في CI.
3. **البنية محمية ببوابة:** `ModulithVerificationTest` + `allowedDependencies` يفشلان عند أي تدخل بنيوي يدوي.
4. **الزمن بالأحداث لا بالمجدولات اليدوية:** الوظائف الزمنية عبر Moments (`moments.html`) قابلة للاختبار الحتم بـTimeMachine.
5. **كل إصلاح يترك اختباراً يمنع عودته** — لا إصلاح بلا بوابة.
6. **الاختبارات القارية عبر `@ServiceConnection`** — لا خصائص يدوية (`DynamicPropertySource`) حيث يوجد البديل الرسمي.
7. **النسخ عبر BOM Boot حصراً** — لا رقم نسخة يدوياً خارج السلم (enforcer قائم).
8. **سجل الانحرافات:** أي انحراف يُكتشف يُوثّق ويُزال بمرجعه الرسمي في نفس الموجة — صفر دين معماري.
9. **مراجعة آلية بلا تدخل:** CodeRabbit + Greptile يعلقان؛ البوابات الخمس تحكم؛ لا تجاوز يدوي لأي بوابة.

## §11 تعريف المطابقة التامة (Definition of Done)

الخطة تصل «حالة المطابقة التامة» عندما تتحقق **جميعاً**:
1. كل بند في هذه الوثيقة مطبّق أو موثّق سبب تأجيله بقرار المالك.
2. كل بند مطبّق مقيس باختبار آلي يمنع انحرافه (بوابته خضراء في CI).
3. البوابات الخمس (CI + CodeQL + Container Scan + Integration Test + CodeRabbit) خضراء على main.
4. `ModulithVerificationTest` أخضر مع `allowedDependencies` كاملة الوحدات الـ27.
5. صفر عناصر §3.4 (العيوب المقيسة) وصفر انحرافات مسجلة.
6. بوابة الرحلة الكاملة (الأضلاع الستة + اختبار الرحلة الآلي) تمر.
7. قرار §9 (الـAttic) محسوم بكلمة المالك وموثّق.

## §12 سياسة المعمرية (النافذة دائماً)

- **مصدر النسخ الوحيد:** `spring.io/projects` + `Release Calendar` — وقياسه محفوظ قابلاً لإعادة التشغيل في `scripts/official-docs-fetch/`.
- **الترقية:** عبر BOM Boot فقط + `docs.spring.io/spring-boot/upgrading.html` + البوابات الخمس كاملة بلا استثناء.
- **مراقبة الـAttic:** فحص دوري لكتالوج `spring.io/projects` — أي مشروع تعتمده المنصة يدخل Attic يُرفع فوراً لقرار المالك (النموذج الجاري: §9).
- **الإهمال المدار للجوال:** عبر deprecation hints الرسمية (`webmvc-versioning.html`) لا عبر قرارات يدوية.
- **المسار الافتراضي عند الشك:** الرجوع للمرجع الرسمي الدقيق أولاً، وإن لم يوجد — تعليق القرار للمالك، لا اجتهاد.

---

## §13 سجل الانتظار — احتياجات مسجلة لم تُجدول (قاعدة عدم الفقد الصامت)

بنود سُجّلت من توجيهات المالك وبيان هويته (§0.1) وتصميمه ولم تُدرج في الموجات — **لا يُحذف بند منها ولا يُجدول إلا بكلمة المالك**. أصل الجدول: حزمة تسليم المطور الثاني (2026-10-07) المُدرجة بأمر المالك؛ والمصادر المذكورة أدناه مسجلات قراءة لا مرجعية تقنية (قاعدة §0: الداخلية أرشيف). المصدر الثاني: تقرير رؤية معمارية خارجي رُوجع مقيسًا (2026-10-07 — مفاعل 22 وحدة والبلاغات والإشراف قائمة مقيسة)؛ مفاهيمه الخمسة **صُمِّمت في الموجات بأمر المالك** («أدرجه ضمن الخطة وتصميمه حسب الوثائق والمستودعات الرسمية»): الثقة C.9 · الإعدادات الجغرافية C.10 · قواعد الإشراف C.11 · نظام التحديثات C.12 · تصنيفية console في C.5 — كل آلية بمرجعها الرسمي الدقيق في عمودها؛ ويبقى هذا السجل لما لم يُصمَّم بعد (قاعدة عدم الفقد الصامت).

| الاحتياج المسجل | مصدر التسجيل | قيده المسجّل |
|---|---|---|
| قائمة المحادثات + الملف العام للجار | خطة المنصة الموحدة (أرشيف قراءة فقط) | عقودهما تحتاج مواصفة قرار أولًا |
| سياق العقارات (`GET /properties/{id}/context` · المجاميع · التقدير) | خطة المنصة الموحدة (أرشيف) + ميثاق المنتج | خلف بوابة مالك قائمة أصلًا |
| الخدمات والدليل (تجميع leads · حزم الخدمة · شارة التوثيق · لوحة صاحب العمل) | خطة المنصة الموحدة (أرشيف) | قرار جدولة |
| M4/M5/M6: التنفيذ والنزاعات ودفتر البائع · التقييم بعد الاستلام · منتجات المنصة | عمود المتجر في سجل التسليم | M4 خلف بوابة نسبة الضمان؛ M5/M6 يبنيان فوق M1 (C.7) |
| فئتا منشور «سؤال» و«طلب» | بيان هوية المنصة §0.1 | العدّاد القائم مقيسًا `GENERAL/CLASSIFIED/LOST_FOUND/RECOMMENDATION` — ملف قائم ⇒ عبر تعميم العضوية (C.3) أو طلب تغيير |
| الإعارة المدفوعة · البث | بيان هوية المنصة §0.1 | جديدان كليًا — يحتاجان عقدًا (الإعارة: مزيج community + booking + payments) |
| السيارات كتصنيف قوائم | بيان هوية المنصة §0.1 | تصنيف جديد بنمط الفئات القائم — قرار جدولة |
| UI dash: التغيير من لوحة التحكم لا من الكود | بيان هوية المنصة §0.1 | يرفع سقف console (C.5 المصمَّم) — مواصفة قرار قبل التنفيذ |
| التوصية في الانضمام (تزكية عضو موثّق — نمط Nextdoor) | بيان هوية المنصة §0.1 | توسعة عقد العضوية فوق الآلة القائمة (C.3) |
| الظهور المباشر لمنتجات/خدمات المجتمع والدليل في السوق | بيان هوية المنصة §0.1 | عقد تكامل عبر كتالوج الأحداث (additive-only) — قرار معماري |

