# التقرير الشامل — إعادة التحقق من كل الادعاءات + خطة الإصلاح الشاملة

| البند | القيمة |
|------|--------|
| الحالة | **قياس فقط، صفر كود** — التحقق بإجراءات حية هذه الجلسة (2026-09-26) |
| الأمر الحاكم | «تحقق من جديد من كل الفجوات والمشاكل والادعاءات وصمم حلول وخطة اصلاح شاملة، مستندة للوثائق الرسمية للإطار مايفن وغيرهم، وتفعيل مراجعات CodeRabbit» |
| البروتوكول | §0.1 مُفعَّل: AGENTS.md كاملاً + SYSTEM.md §14/§15 + PROJECT_MAP من `origin/main` (رأس `24e1e99`) |
| المواد المُحقَّقة | (1) مرفقات البوابة الثلاثة (2) ملفا PR #385 (3) إعادة تحقق فجوات تقرير Airbnb (4) خطة ما بعد النافذة |
| إعلان §0.2 | [الملف الحاكم]: SYSTEM.md §14 + خطة العملاء/الاستضافة — [النواة]: قراءة/قياس — [البند §14.2]: بوابات B/C وA1 — [أثر الحدود]: صفر — [دين]: لا |

> **قاعدة هذا التقرير:** كل حكم يحمل دليله — `ملف:سطر` من المستودع عند الرأس `24e1e99`، أو قياساً حياً بتاريخه، أو مصدراً رسمياً محفوظاً/مُجلباً هذه الجلسة (جرات Maven Central الرسمية نفسها: SAS 7.1.1 + spring-security-core 7.1.1 + Jackson 3.1.5 كما يديرها Boot 4.1.1). صفر آراء شخصية.

---

## 1. الخلاصة التنفيذية — ثلاث نتائج حاسمة

1. **مرفق «التحقق العميق — هل التعليق مضلل» مرفوض بثلاثة أدلة مستقلة.** تعليق المشروع في `SecurityConfig.java` حول `ArrayList` مقابل `List.of` صحيح بالكامل — والعمل بموجب استنتاج المرفق (العودة إلى `List.of` أو حذف التعليق) كان **سيكسر `refresh_token` في الإنتاج**. الإثبات: تجربة حية بالجرات الرسمية + المصدر الرسمي + البايت-كود (القسم 3).
2. **تقريرا PR #385 دقيقان بنيّتهما مع استدراكين مهمين:** ادعاء S8 (وحدات بلا `@ApplicationModule`) **مرفوض** بقياس تجريبي مباشر (22/22 وحدة مغلقة و`verify()` ينجح)، وادعاء «edge غير منشورة» **متجاوز زمنياً** — خدمة edge حيّة الآن ومقيسة (liveness/readiness/تداوين 200). عدا ذلك: 11 ادعاءً من 12 مؤكدة بأرقام سطرية مطابقة.
3. **النظام أغلق معظم فجوات «مستوى Airbnb» القديمة بنفسه منذ `94287ac`:** الأتمتة الأمنية مغلقة كلياً (CodeQL+Trivy/CVE-gate+Dependabot)، وSLO/قواعد التنبيه/runbooks وُلدت، والوسم `v0.1.0` أحيا بوابة OpenAPI — والباقي المفتوح كله خلف بوابات قرار (C/A1/E1) أو بنود جودة معروضة في القسم 10.

**دخان الإنتاج اليوم (2026-09-26، مقيس):** readiness/liveness 200 · OIDC 200 (issuer الحي) · api-docs 200 (108 مسارات، OpenAPI 3.1.0) · modulith 401 problem+json · **الصحة المجمّعة 503 DOWN (S10 حيّ)** · **sitemap.xml 503 (دين SEO حي)** · **edge: 200/200/200 حي**.

---

## 2. الحكم على PR #385 — جاهز، مع توصية تصحيح قبل الدمج

| البند | القياس |
|---|---|
| المحتوى | ملفان فقط: `gh.md` (+166) و`platform-readiness-audit.md` (+148) — تقريرا التدقيق المرفوعان عبر واجهة الويب |
| البوابات | **6/6 خضراء**: Build & Test · Full Integration · CodeQL Analyze · Container Scan (app) + CodeQL · Trivy (GHAS) — `mergeable_state: clean` |
| CodeRabbit | `auto_review.enabled: true` (كل الفروع) — مراجعة أنجزت 20:54:21Z، ملاحظة واحدة (WebSocket) **اعتُمدت من الجذر بكوميت `dc7c1b6`** بعد تحقق مستقل، والبوت علّم «✅ Addressed». حالة الرأس: `CodeRabbit: success "Review rate limited"` (الجولة الثانية بعد الاعتماد اصطدمت بحدّ المعدل — راقبها؛ المراجعة نفسها اكتملت) |
| الحكم | **جاهز للدمج — بانتظار كلمتك حصراً (§14.3)** |

**توصية قبل الدمج (حماية سجل الحقيقة من ادعاءين مرفوضين):** الملفين يحملان (أ) ادعاء S8 «خمس وحدات بلا `@ApplicationModule` ⇒ نصف رسم الاعتماديات غير مقيّد» — **مرفوض**: لكل وحدة صنف `*Module.java` يحمل `@ApplicationModule(allowedDependencies=...)` والقياس التجريبي على Modulith 2.1.1 يعطي 22/22 وحدة `OPEN=false` و`verify()` ناجح؛ (ب) «نشر edge غير مثبت/3 محاولات ماتت» — **متجاوز**: حالة Railway على رأس main `app-java-v3-edge: Success` والقياس الحي اليوم 200/200/200. إما كوميت تصحيح داخل نفس PR، أو truth-sync فوري بعده. **القرار لك.**

---

## 3. القضاء الحاسم — مرفق «التحقق العميق» مرفوض (ثلاثة أدلة)

المرفق ادّعى أن تعليق `SecurityConfig.java:588-602` «مضلل في 3 ادعاءات» لأن `Jackson3.createJsonMapper()` لا يستدعي `activateDefaultTyping`. **هذا تحليل أعمى عن الصنف المجهول.**

### 3.1 الدليل الأول — المصدر الرسمي (جلب حي من Maven Central هذه الجلسة)

`spring-security-core-7.1.1-sources.jar → SecurityJacksonModules.java:179-196`:

```java
private static void applyPolymorphicTypeValidator(List<JacksonModule> modules,
        BasicPolymorphicTypeValidator.@Nullable Builder typeValidatorBuilder) {
    BasicPolymorphicTypeValidator.Builder builder = (typeValidatorBuilder != null) ? typeValidatorBuilder
            : BasicPolymorphicTypeValidator.builder();
    for (JacksonModule module : modules) {
        if (module instanceof SecurityJacksonModule securityModule) {
            securityModule.configurePolymorphicTypeValidator(builder);
        }
    }
    modules.add(new SimpleModule() {              // ← SecurityJacksonModules$1 — ما فاته
        @Override
        public void setupModule(SetupContext context) {
            ((MapperBuilder<?, ?>) context.getOwner()).activateDefaultTyping(builder.build(),
                    DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY);   // ← هنا يُفعَّل
        }
    });
}
```

و`SAS 7.1.1 → JdbcOAuth2AuthorizationService.java:931-938` يؤكد أن المُسلسِل هو `JsonMapper.builder().addModules(SecurityJacksonModules.getModules(loader)).build()` — **و`addModules(...).build()` يستدعي `setupModule` لكل وحدة، ومنها المجهولة التي تستدعي `activateDefaultTyping` على الـMapperBuilder نفسه.** جافادوك الصنف الرسمي يقولها حرفياً: «enable automatic inclusion of type information and configure a PolymorphicTypeValidator».

### 3.2 الدليل الثاني — التجربة الحيّة بالجرات الرسمية

أعدتُ بناء مُسلسِل SAS حرفياً (نفس السطر) بالجرات التي يديرها Boot 4.1.1 (jackson-bom 3.1.5) وأدرت دورة كاملة على خريطة claims مطابقة لتعبيرات `SecurityConfig.java:605-613`:

| الاختبار | JSON المُسلَّس | القراءة العكسية |
|---|---|---|
| `aud = List.of("marketplace")` | `"aud":["java.util.ImmutableCollections$List12",["marketplace"]]` | ❌ `InvalidTypeIdException: Could not resolve type id 'java.util.ImmutableCollections$List12' … PolymorphicTypeValidator denied resolution` |
| `aud = new ArrayList<>(List.of(...))` | `"aud":["java.util.ArrayList",["marketplace"]]` | ✅ تكتمل الدورة (`aud` يعود `ArrayList`) |
| `roles = Set.of("USER")` | `"roles":["java.util.ImmutableCollections$Set12",["USER"]]` | ❌ نفس الرفض — **كل عائلة `ImmutableCollections` تُرفض** |
| `roles = Collectors.toSet()` | `"roles":["java.util.HashSet",["USER"]]` | ✅ (`Collectors.toSet()` = `HashSet` — مقيس) |

(السكربت محفوظ: `scripts/JacksonTruthTest.java` — قابل لإعادة التشغيل بأمر واحد.)

### 3.3 الدليل الثالث — البايت-كود (لغة المرفق نفسها)

`grep` بايت-كود `SecurityJacksonModules.class` = **لا يحمل** `activateDefaultTyping` (وهذا ما رآه المرفق وتوقف عنده) — لكن `SecurityJacksonModules$1.class` (الصنف المجهول الذي **أضافه هو نفسه إلى القائمة** ثم لم يفكّه) يحمل: `activateDefaultTyping(LPolymorphicTypeValidator;LDefaultTyping;LJsonTypeInfo$As;)LMapperBuilder;` بثوابت `NON_FINAL`/`PROPERTY`/`setupModule`.

### 3.4 الخلاصة والأثر

| ادعاء المرفق | الحكم | الدليل |
|---|---|---|
| «لا يوجد activateDefaultTyping في أي مكان» | ❌ مرفوض | المصدر 179-196 + البايت-كود `$1` + وجود `@class` في JSON الفعلي |
| «الـvalidator مسجّل لكن لا يُستخدم أبداً» | ❌ مرفوض | نص الاستثناء نفسه: «Configured PolymorphicTypeValidator denied resolution» |
| «List.of يُسلّس كـ ["marketplace"] ويعمل طبيعياً» | ❌ مرفوض | يُسلّس بمعرّف النوع `["java.util.ImmutableCollections$List12",[…]]` ويفشل |

**الأثر الهندسي:** تعليق المشروع صحيح 100% (ومنه ادعاء `JwtClaimsSet.java:113-115` — مقيس: `audience()` يمرر القائمة كما هي بلا نسخ دفاعي). **حصن جديد مُكتشف:** أي قيمة من `List.of/Set.of/Map.of` في claims أو attributes تُخزَّن عبر `JdbcOAuth2AuthorizationService` تنفجر عند القراءة — القاعدة الرسمية الآمنة: **مجموعات قابلة للتغيير (ArrayList/HashSet/LinkedHashMap) في كل ما يمر عبر مُسلسِل SAS** (وهي نفسها الأنواع المسموح بها في `CoreJacksonModule.configurePolymorphicTypeValidator`). لا PR مفتوح يمس هذا — لم يتسرب الخطأ للمستودع (مقيس على كل الـPRs الستة المفتوحة).

---

## 4. مصفوفة إعادة التحقق S1–S12 (تقرير PR #385 مقابل main `24e1e99`)

| # | الادعاء | الحكم | الدليل الفعلي الحالي |
|---|---|---|---|
| S1 | لا تسجيل حسابات | ✅ مؤكد (جوهره) — **الصياغتان الحرفيتان خطأ** | لا register/signup/forgot/reset في `src/main` إطلاقاً؛ لكن `auth_users` تُكتب إنتاجياً عبر `UserService.updateUser:277-285`، و`JdbcUserDetailsManager` يُستدعى من `AdminController:78-106` (loadUserByUsername/updateUser/deleteUser) — و`createUser` في الاختبارات فقط |
| S2 | ترقية الدور لا تُفعّل الصلاحيات | ✅ **مؤكد بالكامل** | `UserService.java:205` يكتب `users.role` فقط؛ claim الـroles من `auth_authorities` (`SecurityConfig.java:380,609`)؛ لا كود إنتاج يكتب `auth_authorities`؛ refresh لا يعيد القراءة (`UserService.java:219-228`) |
| S3 | لا إتمام دفع بلا PSP | ✅ مؤكد | `PaymentsService.java:327-335` (تخطٍّ) + `:343` (`hasRole('ADMIN')`) + `:570-578` (webhook 503) — الأرقام مطابقة حرفياً |
| S4 | `/ws` غير قابل للوصول بمتوكن | ✅ مؤكد | السلسلة 2 (`SecurityConfig.java:132-133`) تغطي `/api/**,/actuator/**,/graphql,/v3/api-docs/**,/sitemap.xml,/robots.txt` — **`/ws` غائب** ⇒ السلسلة 3 `:215-216` (formLogin) ⇒ 302 بدل 101؛ لا HandshakeInterceptor/bearer (تصويب CodeRabbit المعتمد بـ`dc7c1b6` صحيح) |
| S5 | `GET /api/v1/media/**` مصادق | ✅ مؤكد | قائمة permitAll كاملة `SecurityConfig.java:153-192` — لا matcher للوسائط، بينما listings GET عامة (`:165`) |
| S6 | لا سجل فئات | ✅ مؤكد | `CatalogController.java:275,301` نص حر؛ `V2__catalog.sql:7` varchar(100) بلا CHECK؛ لا جدول/enum للفئات |
| S7 | `ListingSummary` ناقص | ⚠️ جزئي | **`currency` موجودة** (`ListingSummary.java:26`)؛ الناقص فعلاً: `providerId` (يوجد `providerName` نصي) + صور + موقع + تقييم |
| S8 | 5 وحدات بلا `@ApplicationModule` | ❌ **مرفوض** | لكل وحدة صنف `*Module.java` بـ`@ApplicationModule(displayName, allowedDependencies)`؛ **القياس التجريبي** (Modulith 2.1.1 على أصناف المستودع): 22 وحدة كلها `OPEN=false` و`verify()` ناجح — التعليق `@Target({PACKAGE,TYPE})` يقرأ من الصنف المُعلَّم بـ`@PackageInfo` |
| S9 | الحارس يعطّل نفسه على JDK 26+ | ✅ مؤكد | `ModulithVerificationTest.java:16` — `assumeTrue(Runtime.version().feature() < 26)` (السطر 15 تعليق؛ إزاحة واحدة) |
| S10 | الصحة المجمّعة DOWN | ✅ مؤكد **حياً اليوم** | مقيس 2026-09-26: `{"groups":["liveness","readiness"],"status":"DOWN"}` مع readiness/liveness UP؛ المساهمون خارج readiness: **mail** (`application-prod.yml:66-76` — مضبوط تشغيلياً بـpreserve()) و**modulithEventBus** (`ModulithEventBusHealthIndicator.java:40-95`)؛ `show-details: never` (`:119`) يحجب التسمية |
| S11 | تغطية OpenAPI 73% | ✅ مؤكد بالعدّ | 91 `@Operation` / 125 عملية (30 متحكماً) — **وصفر `@Tag`** (جديد) |
| S12 | انتقال حالة غير قانوني | ✅ **مؤكد وله امتداد** | `BookingCancelledEventListener.java:20-23` (AFTER_COMMIT بلا فحص) → `PaymentsService.java:536` `markRefunded()` غير مشروط؛ `PaymentIntentStatus.java:19-20` تسمح من PROCESSING فقط {SUCCEEDED, FAILED}؛ إعادة المحاولة أبدية (`EventPublicationResubmission.java:79,105` كل 15د/24س إلى أجل غير مسمى)؛ **والامتداد: النية في CREATED تُرمي نفس الاستثناء** (الخريطة `:19`) |

**ادعاءات gh.md المرافقة:** كلها مؤكدة (45 متغيراً بلا Stripe — `StripeChannelConfiguredCondition.java:18-36`؛ sitemap 503 — `SitemapService.java:132-139` + المتغير غير مربوط في railway.ts؛ disputes 200 — `DisputeController.java:27-30`؛ geo LIKE حساس — `GeoLocationRepository.java:38-48`؛ notifications بلا ترقيم — `NotificationController.java:26-31`) — **ما عدا «edge غير منشورة»: تجاوزها الزمن** (القسم 7).

### 4.1 مشاكل جديدة اكتُشفت أثناء التحقق (غير مسجلة في أي تقرير سابق)

| # | المشكلة | الدليل |
|---|---|---|
| N1 | **بذرة admin ببصمة bcrypt ثابتة منشورة** تُطبَّق في كل بيئة (ترحيل Repeatable) — التعليق نفسه يطلب التدوير ولا شيء يفرضه | `R__seed_oauth2_client.sql:12-18` |
| N2 | **تجاوز `@PreAuthorize` بالاستدعاء الذاتي**: `dispatchWebhookEvent` يستدعي `confirmIntent` من نفس الصنف ⇒ بوابة `hasRole('ADMIN')` لا تنطبق فعلياً على مسار الـwebhook (يعتمد على HMAC فقط) | `PaymentsService.java:196-238 → :202 → :343` |
| N3 | **قناة الإشعار الفوري ميتة تبعاً لـS4**: `NotificationService.java:227-228` يدفع إلى `/topic/notifications/{userId}` عبر نفس الوسيط | + S4 |
| N4 | **ترقية الدور عملية صامتة ناجحة شكلاً**: `AdminController.java:60-62` يعيد نجاحاً بلا أثر تشغيلي | + S2 |
| N5 | **انحراف readiness بين البيئات**: غير prod = `db,diskSpace` بلا redis (`application.yml:282`) مقابل prod = `db,redis,diskSpace` | `application.yml` vs `application-prod.yml:125` |
| N6 | **`syncFromOidc` يمحو الترقيات اليدوية**: ينسخ الدور من JWT إلى `users.role` عند كل دخول | `UserService.java:188-193` |
| N7 | صفر `@Tag` على كل المتحكمات (تنظيم الوثائق المولّدة) | عدّ هذه الجلسة |
| N8 | حصن ImmutableCollections (القسم 3.4): أي `Set.of/Map.of/List.of` في claims SAS = فشل قراءة | التجربة الحية |

---

## 5. إعادة تحقيق فجوات «مستوى Airbnb» (التقرير القديم أساسه `94287ac` — اليوم `24e1e99`)

| الفجوة القديمة | الحكم اليوم | ما تغيّر (بالأدلة) |
|---|---|---|
| ف1: مراقبة بلا مستهلك | **مغلقة جزئياً** | وُلدت: SLO كامل (`docs/observability/slo.md`) + **8 قواعد تنبيه ككود** (`monitoring/prometheus-rules/marketplace-alerts.yml`) + runbooks إنذار 1:1 + تدوير سجلات (`application.yml:315-322`) + جسر Grafana OTLP (`application-prod.yml:140-159`). الباقي: لا Prometheus/Alertmanager موصول (بوابة A1 بيدك) ولا لوحات ولا شحن سجلات |
| ف2: إصدار يدوي | **مغلقة جزئياً** | الوسم `v0.1.0` (2026-09-05) **أحيا بوابة OpenAPI** (`.ci/check-openapi-compat.sh` يجد الأساس) + بوابة نشر في maven-publish. الباقي: لا آلية canary، Docker يتخطى الاختبارات (`Dockerfile:18`)، وسم وحيد منذ سبتمبر |
| ف3: استرداد وحوادث | **مغلقة جزئياً** | runbook نسخ/استعادة وُلد (`docs/operating/manual-db-backup-runbook.md`)؛ forward-headers **أُغلق** (`application-prod.yml:95`)؛ `SPRING_PROFILES_ACTIVE=prod` مضبوط تشغيلياً. الباقي: لا قالب postmortem، سجل المخاطر ما زال 4 مخاطر تسليم فقط |
| ف4: أتمتة أمنية | **مغلقة** | gitleaks + **CodeQL** + **Trivy/CVE-gate بصورة الإنتاج نفسها بلا كاش (#383)** + **Dependabot** + JaCoCo/Enforcer/ArchUnit. الباقي اختياري: OWASP dep-check/SpotBugs |
| ف5: انجراف توثيقي (5) | **مغلقة جزئياً (1/5)** | HEALTHCHECK صُحّح؛ logback/MFA-TOTP ما زالا ادعاءين بلا كود؛ «V1–V30» صار الفعلي **V1–V68** (اتسع الانحراف)؛ روابط مكسورة انتقلت (`ARCHITECTURE.md:234` + `METHODOLOGY.md:835`) |
| ف6: عمق الاختبارات | **مغلقة جزئياً** | **2116 اختباراً** (كانت 590) + 38 حقن Redis Testcontainer + **عزل بنيوي لكل اختبار تكاملي (#382)** + WireMock لعقد BFF. الباقي: اختبارات catalog المحلية (صفر)، لا PIT، لا توازٍ، smoke=3 curl |
| ف7: بوابات الاستضافة | A وB **مغلقتان**، C **مفتوحة بيدك** | النمطان منفذان + وحدة BFF + staging حي + runbook الفرونت (#372) |

**الصغيرة الأربع:** README غائب · مقاييس الأعمال عدادات فقط (مع gauge جديد `marketplace.eventbus.stale`) · حد المعدل داخل العملية (`application.yml:329-424` بتصريح النسخ) · ادعاء `ARCHITECTURE.md:318` (Redis rate-limit + TOTP) ما زال منحرفاً (والفعلي Redis **8**).

---

## 6. حالة بنود خطة ما بعد النافذة (I/E) — مقيسة اليوم

| البند | الحالة | الدليل |
|---|---|---|
| I1 دوران JWT | ✅ منجز (r3) — **الدوران القادم 2026-12-08 بيدك** | kid=marketplace-jwt-r3 الحي + `SYSTEM.md:417` |
| I2 معدلات ثابتة · I3 runbook نسخ · I4 عدّاد D3 · I5 إبطال مخبأة موزع · I6 فلتر ضيوف · I8 تقييم معاكس | ✅ منجزة | `StaticRatesConfiguredCondition` · `manual-db-backup-runbook.md` · `MediaThumbnailMetrics.java:43` · `CacheInvalidationRelay.java:58-85` + اختبار نسختين · `V44`+`SearchController.java:75-79` · `ReviewsTwoWayIntegrationTest` |
| I7 تمويه GDPR | ✅ مرحلة 1 مسلّحة إنتاجياً | `POST /admin/users/{id}/pseudonymize` + `PSEUDONYMIZATION_HMAC_KEY` بدوران |
| I11 PostGIS | ✅ **حي في الإنتاج** | `V50/V51` مطبقتان والفهرس valid (قياس PROJECT_MAP:425-430) |
| I9 PDF · I10 مفاتيح API · I12 معايرة | ❌ غير منجزة (خلف أولوية/بوابة) | grep صفر |
| E1 Stripe | جزئي — **الكود جاهز والمفاتيح غائبة** | `StripePspChannel` كامل + `railway.ts` بلا المفتاحين |
| E4/E5 مراقبة | جزئي — الجسر جاهز بلا مستهلك | بوابة A1 |
| E6 PITR | ❌ مدفوع بقرارك | runbook يدوي فقط |

---

## 7. اكتشاف هذه الجلسة — خدمة edge حيّة (عكس التقرير)

ادعاء gh.md رقم 5: «بوابة edge غير منشورة فعلياً — 3 محاولات نشر ماتت (17ث/33ث/91ث)». **القياس اليوم ينفيه:**

- حالة Railway على رأس main `24e1e99`: `app-java-v3-edge: state=success, desc="Success - app-java-v3-edge-production.up.railway.app"`
- القياس الحي المباشر (2026-09-26): liveness **200 UP** · readiness **200 UP** · `GET /api/v1/listings` **200** (يعيد صفحة HTML — سلوك BFF المتوقع لطلب متصفح غير موثّق: مسار المصادقة) · `/v3/api-docs` **200**
- الخلل السابق (jar غير تنفيذي) أُصلح جذرياً في #369: `marketplace-edge/pom.xml:85` يصرّح `spring-boot-maven-plugin` («the missing repackage made a plain, non-executable jar» — كوميت `d47169a` في HEAD)

**التفسير الزمني:** قياس التقرير كان 12:45Z والدمج #383 في 16:14Z — الادعاء كان صحيحاً لحظة كتابته وتجاوزه النشر اللاحق. **اللازم الآن: truth-sync** (PROJECT_MAP يسجل دين «لا بناء edge اكتمل قط» — يجب قلبه إلى سجل النجاح) + تصويب الملفين في #385 قبل دمجهما.

---

## 8. CodeRabbit — حالة التفعيل (مقيسة)

| البند | القياس |
|---|---|
| التكوين على main | `.coderabbit.yaml`: `reviews.auto_review.enabled: true`, `base_branches: [".*"]` — **مفعّل على كل الفروع** ✓ |
| ruleset | `id=22305466` — «main: CI required checks + PR-only merges» — **active** على branch main ✓ |
| على #385 | مراجعة أنجزت (20:54:21Z) + ملاحظة واحدة اعتُمدت من الجذر + الحالة النهائية `success "Review rate limited"` — الجولة الثانية (بعد كوميت الاعتماد) اصطدمت بحدّ معدل المراجعات؛ المراجعة الأولى اكتملت والخيط محلول |
| على #384 | `success "Review completed"` (الجولة الثالثة من الجلسة السابقة) |

**ملاحظة تشغيلية (دين مراقبة):** ظهور «Review rate limited» بعد مراجعة كاملة يعني أن مستوى الاستهلاك يقترب من سقف الباقة — عند فتح موجات PR متزامنة (كالمعتاد في هذا المشروع) راقب الخيط؛ إن تحولت الحالة إلى تعطيل فعلي للمراجعة، البديل الموثق هو `@coderabbitai review` اليدوي (نمط #366/#384 المعتمد عندنا أصلاً).

---

## 9. الدخان الحي (2026-09-26 — `app-java-v3-production.up.railway.app`)

| المسار | النتيجة |
|---|---|
| `/actuator/health/readiness` · `/liveness` | 200 UP · 200 UP |
| `/actuator/health` (المجمّعة) | **503 DOWN** — `{"groups":["liveness","readiness"],"status":"DOWN"}` (S10 حي) |
| `/.well-known/openid-configuration` | 200 — issuer بالنطاق الحي |
| `/v3/api-docs` | 200 — 108 مسارات · OpenAPI 3.1.0 |
| `/api/v1/modulith` | 401 AUTHN-001 problem+json ✓ |
| `/sitemap.xml` | **503** (دين SEO حي — `MARKETPLACE_CATALOG_SEO_PUBLIC_SITE_BASE_URL` غير مربوط) |
| edge: `/actuator/health/*` · `/v3/api-docs` | **200/200/200 — حي** |

---

## 10. خطة الإصلاح الشاملة — المستندة إلى الوثائق الرسمية

> **قواعد الخطة (موروثة من §14 والقواعد الثمان R1–R8):** كل بند = دورة الطبقة القياسية (فرع مستقل → `./mvnw clean verify -pl <module> -am` → PR بجسم صادق → فحصا ruleset → CodeRabbit → دمج squash **بكلمتك** → CI على main → قلب الحالة في دفعة الحقيقة). كل تغيير مخطط = ترحيلة V من العداد الحي (**التالي V69**) ولا تُعدَّل قائمة. **صفر ترقيع: كل بند يُغلق مشكلته من جذره أو يُصرَّح بدينه ونقطة إغلاقه في PR نفسه.** الوثائق الرسمية تُجلب وتُطابق نصياً عند فتح كل بند (طقوس §10).

### المرحلة 0 — مزامنة الحقيقة (صفر كود، يوم واحد)

| # | البند | الحل الرسمي/الطريقة | المصدر الحاكم |
|---|---|---|---|
| 0.1 | **دمج #385** (التقريران) — بعد تصحيح السطرين المرفوضين (S8 + edge) بكوميت داخل PR أو truth-sync فوري | توثيق بمقاس حي لكل تصحيح | §14.3 — الدمج بكلمتك |
| 0.2 | **دمج #384** (truth-sync #383) — أخضر + CodeRabbit منجز منذ الجولة السابقة | — | §14.3 |
| 0.3 | PROJECT_MAP: قلب دين «edge فاشل» إلى سجل النجاح + تسجيل #372–#383 | دفعة الحقيقة | قاعدة «لا ديون مخفية» |
| 0.4 | سجل المخاطر: إضافة المخاطر التشغيلية المقيسة (دوران JWT 2026-12-08 · S10 · rate-limit المراجعات · قرار التجديد) | `docs/governance/risk-register.md` | SRE (مخاطر تُدار لا تُذكر) |
| 0.5 | **قرار PRs المعلقة**: #371/#381 صالحان للدمج؛ #377/#380 docs-only يُعاد بناؤهما (rebase) على main ليأخذا #382 — قراراتك | §14.3 | — |

### المرحلة 1 — الإصلاحات الحرجة (أخطاء مؤكدة في الكود الحي؛ كل بند PR صغير مستقل)

| # | المشكلة (الدليل) | الحل الرسمي — من وثائق الإطار | المصدر الرسمي |
|---|---|---|---|
| 1.1 | **S12: `markRefunded()` غير مشروط في مستمع AFTER_COMMIT** ⇒ PROCESSING/CREATED→REFUNDED غير قانوني ⇒ استثناء داخل `@ApplicationModuleListener` ⇒ إعادة محاولة أبدية والنية عالقة | الحارس في مكان الحدوث: في `autoRefundByBooking` — إن كانت الحالة `SUCCEEDED` ف`markRefunded()` (المسار القانوني الوحيد)، وإلا `markCancelled()`/`markFailed()` حسب الخريطة؛ **والنية تموت نظيفة لا حلقة أبدية**. سلوك Modulith الرسمي: النشر الفاشل يُعاد (complete/republish) — لا يعالج منطقك؛ صحة المستمع مسؤولية النطاق | Spring Modulith Reference › Event Publication Registry (نسخة محفوظة `scripts/modulith-events.html`) + خريطة `PaymentIntentStatus.java:19-26` نفسها |
| 1.2 | **S10: الصحة المجمّعة DOWN** (mail أو modulithEventBus خارج readiness) و`show-details: never` يحجب التسمية | **خطوتان:** (أ) تشخيص واحد بمتوكن أدمن: `GET /actuator/health` مع `show-details` عبر `management.endpoint.health.show-details: when-authorized` مؤقتاً أو قراءة `marketplace.eventbus.stale` — يحدد المساهم الساقط؛ (ب) الإصلاح الجذري حسب النتيجة: mail لا يُرصد بأثر رجعي عبر الصحة (استبعاده من المجمّعة أو إصلاح SMTP) — **الحل النمطي الرسمي**: `management.endpoint.health.group` بتقييد المساهمين، أو `show-components: always` لعدم إخفاء السبب، والمؤشرات الحرجة تُستقى من readiness لا من المجمّعة العمياء | Spring Boot Reference › Management Endpoints › Health (نسخ محفوظة `scripts/doc-verify/boot-auto-config.html` + الوثيقة الحية `docs.spring.io/spring-boot/reference/actuator/endpoints.html`) |
| 1.3 | **N1: بذرة admin ببصمة bcrypt ثابتة في الجرة/المستودع لكل بيئة** | البذرة المشروطة بالبيئة: كلمة المرار من env (`ADMIN_SEED_PASSWORD`) بفشل مبكر في غير prod، أو إزالة البذرة من prod كلياً وإنشاء الأدمن عبر `JdbcUserDetailsManager.createUser` مرة واحدة موثقة؛ ترحيل Repeatable يعاد حسابه | Flyway Reference (محفوظ `scripts/doc-verify/flyway.html`) + مبدأ 12-factor (الأسرار من env — موروث R2) |
| 1.4 | **N2: تجاوز `@PreAuthorize` بالاستدعاء الذاتي** (`dispatchWebhookEvent → confirmIntent` بنفس الصنف) | هذا سلوك AOP موثق رسمياً: الاستدعاء الذاتي لا يمر بالوكيل. الحل الرسمي: إخراج `confirmIntent` إلى صنف خدمة منفصل (بنية Modulith لا تُكسر — داخل وحدة payments) أو حقن `ObjectProvider<PaymentsService>` ذاتي/`AopContext` (موثق كحل رسمي مع `exposeProxy`) — الأول أنظف معماريّاً | Spring Framework Reference › AOP › Proxying Mechanisms (سلوك self-invocation الموثق) |
| 1.5 | **S2/N4/N6: ترقية الدور صامتة بلا أثر + المزامنة العكسية تمحوها** | مزامنة السلطات عند الترقية في **نفس** العملية (transaction): `JdbcUserDetailsManager` يملك العقد رسمياً (`setCreateAuthoritySql` موجود ومربوط أصلاً في `SecurityConfig.java:384`) — إضافة تحديث `auth_authorities` عند `updateUserRole` + حدث نطاقي `UserRoleChanged` (SAME_TRANSACTION) للمزامنة العكسية، وعندها `syncFromOidc` يقرأ من `users.role` لا من JWT القديم (قلب اتجاه الحقيقة) | Spring Security API › `JdbcUserDetailsManager` (المصدر المحفوظ §10) + نمط أحداث Modulith R3 |
| 1.6 | **S9: الحارس المعماري يصمت على JDK 26+** | إزالة `assumeTrue` عند توفر دعم ArchUnit الرسمي لـJDK 26 (تحقق تاريخ الإصدار عند التنفيذ) أو استبدال الحارس بحزمة Modulith's `ApplicationModules.verify()` عبر Maven plugin خارج الاختبار (قناة رسمية لا تعتمد على JDK الاختبار) | Spring Modulith Reference › Maven Plugin + ArchUnit release notes |

### المرحلة 2 — طبقة المنصة (ما يجعل ربط الواجهة صادقاً) — كلها خلف بوابة B4/B1 بكلمتك

| # | المشكلة | الحل الرسمي | المصدر الرسمي |
|---|---|---|---|
| 2.1 | **S4+N3: `/ws` لا يصل بمتوكن JWT ⇒ push ميت** | النمط الرسمي الأول: إضافة `/ws/**` إلى `securityMatcher` سلسلة resource-server (JWT من query/header أثناء المصافحة) + `ChannelInterceptor` يرفع `Authentication` (نمط `AuthenticatedChannelInterceptor` الموثق) — **لا اختراع**: هذه بنية Spring Security WebSocket الرسمية | Spring Security Reference › WebSocket Security (نسخة محفوظة §10 — تُجلب حية عند الفتح) |
| 2.2 | **S5: زائر بلا صورة** | `permitAll` لـ`GET /api/v1/media/listings/{listingId}` داخل سلسلة 2 — قرار نطاقي موثق (الوسائط العامة للوحات المنشورة) | Spring Security Reference › AuthorizeHttpRequests (`scripts/auth-design/boot-security.txt`) |
| 2.3 | **S6: فئات نص حر** | ترحيلة `V69__categories` (جدول + قيد FK على listings.category) + endpoint `GET /api/v1/catalog/categories` | R5 (Flyway يملك المخطط) |
| 2.4 | **S7: `ListingSummary` ناقص الحقول** | إضافة `providerId` (+تقييم/موقع عند الحاجة) — عقد مكتمل | springdoc-openapi (عقد من الشيفرة) |
| 2.5 | **S11+N7: 34 عملية بلا `@Operation` + صفر `@Tag`** | استكمال الشروح + `@Tag` لكل متحكم — توثيق تعاقدي كامل | springdoc-openapi Official Docs › Annotations |
| 2.6 | **الإشعارات: بلا ترقيم/عدّاد** | `PagedResponse` القياسي (نمط المستودع نفسه) + `GET /notifications/unread-count` | Spring Data pagination (موروث) |
| 2.7 | **Disputes: 200 بدل 201** | `@ResponseStatus(HttpStatus.CREATED)` — دلالة الإنشاء | RFC 9110 §15.3.2 (201 Created) |
| 2.8 | **geo: LIKE حساس حالة** | `ILIKE` أو فهرس `lower(name_en)` — حسب خطة أداء الاستعلام | PostgreSQL Official Docs › Pattern Matching |
| 2.9 | **sitemap 503** | ربط `MARKETPLACE_CATALOG_SEO_PUBLIC_SITE_BASE_URL` في Railway (متغير واحد — تشغيلي) | `CatalogProperties.java:131-133` |
| 2.10 | **S1/B1: التسجيل ودورة كلمة المرور** | تصميم رسمي عبر أنماط SAS الموثقة (PKCE العام/BFF السري — R6) + سياسة كلمة مرور — **بوابة B1 بكلمتك تفتح PR الخطة أولاً** | `scripts/verify-aud-claim/sas-all/` + خطة العملاء §7 |
| 2.11 | **AI صفر سطح + `SPRING_AI_MODEL_CHAT=none`** | متزامن مع B1: أول surface حقيقي يحدد المزود — بيدك | runbook §9 (دين معلن) |

### المرحلة 3 — النضج التشغيلي (SRE/المستوى المؤسسي)

| # | البند | الحل الرسمي | المصدر |
|---|---|---|---|
| 3.1 | **مستهلك المراقبة (A1 بكلمتك)** | اختيار مزوّد OTEL (جسر Grafana جاهز `application-prop.yml:140-159`) ⇒ لوحتان + تفعيل قواعد التنبيه الثمان الموجودة ككود + قناة إشعار | Grafana Cloud Official Docs + SLO الموجود `slo.md` |
| 3.2 | **قاعدة تنبيه للصحة المجمّعة** (S10 بعد إصلاحه يبقى مرصوداً) | إضافة قاعدة + مجس watchdog للنقطة التي كشفت المرض لا لِما فوقها فقط | النمط القائم `marketplace-alerts.yml` |
| 3.3 | **قالب postmortem + تمرين أول** | قالب SRE قياسي فارغ + سجل تجريبي لحدث S10 نفسه | Google SRE Book › Postmortem Culture |
| 3.4 | **README للجذر** | نقطة دخول بشرية: ما النظام/كيف يُبنى/كيف يُشغّل/أين الحقيقة | موروثة من خارطة الإغلاق القديمة |
| 3.5 | **الانجراف التوثيقي الخماسي** | تصحيح: logback→%replace الفعلي · MFA/TOTP يُحذف الادعاء · V1–V68 · الروابط المكسورة (ARCHITECTURE.md:234, METHODOLOGY.md:835) · Redis 8 وrate-limit في-العملية | قاعدة الذهب: الكود حاكم |
| 3.6 | **دوران JWT r4** (قبل 2026-12-08) + **بقايا §15** (Gemini إن أُبقيت) | runbook `keys/README.md` — نفس دورة r3 الموثقة | §15 الحي |
| 3.7 | **canary آلي** | مرهون ببوابة C (جهة الاستضافة) — يُنفذ عند حسمها وفق `rollout-strategy.md` §3 | خطة الإطلاق الحاكمة |

### المرحلة 4 — عمق الاختبار (ف6 الباقية)

| # | البند | الحل الرسمي | المصدر |
|---|---|---|---|
| 4.1 | **اختبارات catalog داخل وحدتها** | `marketplace-catalog/src/test` بنمط باقي الوحدات (slice tests) | Spring Boot Test Slices (محفوظ) |
| 4.2 | **PIT تجريبي** على وحدة واحدة (payments أول المرشحين — آلة الحالات) | إضافة pitest-maven بمدخل واحد محدود | PIT Official Docs + `junit5-plugin` |
| 4.3 | **توازٍ الاختبارات** | `junit-platform.properties` (`junit.jupiter.execution.parallel.enabled=true` + configured mode) بعد قياس أثره على العزل البنيوي #382 | JUnit 5 User Guide › Parallel Execution + Surefire (محفوظ `surefire-includes.txt`) |
| 4.4 | **توسيع دخان CI** | سيناريو curl واحد مؤلَّف (زائر→لوحة→401/200) بدل الثلاثة المتباعدة — صفر اعتماديات | نمط `integration-test.yml` القائم |

### ترتيب التنفيذ المقترح (المنطق: الحقيقة ثم الصحة ثم المنصة ثم العمق)

```
المرحلة 0 فوراً (كلمتك على الدموجات الست)
  → 1.1 (S12) + 1.2 (S10) بالتوازي — أخطر خطأين حيين
  → 1.3–1.6 كـPRs صغيرة متتابعة
  → المرحلة 2 بترتيب B4 (وسائط+ws+فئات) ثم B6 ثم B1 بكلمتك
  → المرحلتان 3/4 بالتوازي مع 2 (لا تعارض — فوق البنية لا داخلها)
```

---

## 11. القرارات المفتوحة أمامك الآن (كلها بكلمة صريحة — §14.3)

| القرار | الخيارات المقيسة |
|---|---|
| **دمج #385** | (أ) كما هو — يُدخل ادعاءين مرفوضين لسجل الحقيقة (ب) **بكوميت تصحيح السطرين أولاً (الموصى به)** (ج) إغلاقه واعتماد تقرير هذه الجلسة مرجعاً |
| دمج #384 · #371 · #381 | الثلاثة خضراء/نظيفة — كلمتك |
| #377 · #380 | docs-only خلف CI قديم — rebase على main (عندي الأمر جاهز بكلمتك) |
| بوابة A1 (مزوّد المراقبة) | Grafana Cloud (الجسر جاهز) أو غيره |
| بوابة C (جهة الاستضافة) | تفتح canary وتقفل افتراض ARCHITECTURE.md §5 المنتهي |
| بوابة B1 (التسجيل) | تفتح PR خطة أنماط SAS |
| E1 Stripe (توقيت الأعمال) | كود جاهز — مفاتيح فقط |
| تشغيل المرحلة 1 (1.1–1.6) | أفتح أول PR (S12) بكلمتك |

---

## 12. المصادر (كلها محفوظة/مُجلبة ومُطابَقة هذه الجلسة)

| النوع | المصدر |
|---|---|
| **جرات رسمية فُكّت نصياً وبايت-كودياً هذه الجلسة** | `spring-security-oauth2-authorization-server-7.1.1(-sources).jar` · `spring-security-core-7.1.1(-sources).jar` · `spring-security-oauth2-jose-7.1.1-sources.jar` · `jackson-databind-3.1.5.jar` — كلها من `repo.maven.apache.org` بإحداثيات BOM Boot 4.1.1 (`scripts/*.jar`) |
| تجربة حية قابلة للتكرار | `scripts/JacksonTruthTest.java` (أمر تشغيل واحد، نفس classpath الجرات) |
| وثائق محفوظة (§10) | `scripts/doc-verify/` (flyway · maven-lifecycle · boot-auto-config · postgres) · `scripts/auth-design/` (boot-security · boot-external-config · boot-web-servlet) · `scripts/modulith-events.html` · `scripts/modulith-fundamentals.html` · `scripts/week4-docs/spring-boot-integration.md` · `scripts/verify-aud-claim/sas-all/` |
| مراجع رسمية حية (تُجلب عند فتح كل بند — طقوس §10) | docs.spring.io (Boot Reference › Actuator/Health · Security › WebSocket Security · Authorization Server) · springdoc.org · flyway.io · ArchUnit release notes · RFC 9110 §15.3.2 · Google SRE Book |
| قياسات هذه الجلسة | GitHub REST (PRs/checks/statuses/rulesets) · Railway commit statuses · دخان الإنتاج والـedge الحي (2026-09-26) · عدّ OpenAPI/@Test/@Operation/@Tag · البايت-كود `SecurityJacksonModules$1` |

**سكربتات الجلسة (قابلة لإعادة التشغيل):** `pr385_review.py` · `pr385_content.py` · `open_prs_check.py` · `rabbit_smoke_check.py` · `JacksonTruthTest.java`
