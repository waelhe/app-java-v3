# خطة إصلاح مراجعات Codex — التدقيق الكامل والخارطة المرجعية

- **المستند الحاكم لهذا الملف:** نتائج تدقيق مراجعات `chatgpt-codex-connector[bot]` (104 تعليقاً على 68 PR قديمة) مقابل الكود الحالي على `main` (`fd59d70` — آخر تحديث بعد #252)، مؤصولة بالوثائق الرسمية.
- **التاريخ:** 2026-09-07.
- **الوضع:** معتمدة ومنفَّذة جزئياً — Batch A (A1→A6) و B1/B2 من Batch B و C1/C2 من Batch C منفَّذة عبر PR #253 (فرع `feat/codex-still-valid-sweep`، مفتوح قيد المراجعة)، بما فيها جولات إصلاح ملاحظات CodeRabbit (تحدي WWW-Authenticate وفق RFC 6750 §3/§3.1، تأكيد `senderId` في اختبار WebSocket، وجولة ج3 على سكربتات C2: تقويم صلاحية الاستثناء حقيقياً (أطوال الشهور والسنوات الكبيسة) + إغلاق فجوة تغييرات المعاملات/الطلب خارج Return Type بالفشل المقفول [UNREPRESENTABLE] وفق دلالات نموذج openapi-diff الرسمي) وإصلاح عيوب تغليف سكربتات C2 على رأس PR المحدَّث (BOM قبل الـshebang يمنع التنفيذ المباشر، صلاحية 644 بدل 755 فتفشل حالة «مغطى → خروج 0» فعلياً على لينكس، وسطر نهاية ملف غائب). C3 من Batch C ينتظر التنفيذ، وبنود DECISION (A7/A8/B3/B4/B5) تنتظر قرار المستخدم — لا يُنفَّذ منها شيء قبل القرار.
- **الامتداد:** كل ما هنا مُعاد التحقق منه بقراءة مباشرة للملفات و`git log` في جلسة 2026-09-07 — لا اعتماد على آراء الأدوات الفرعية وحدها.

---

## 1. المنهجية والتصنيف

1. **الجمع:** كل تعليقات PR عبر `gh api` الصفحات (179: 104 Codex / 73 CodeRabbit / 2 وaelhe)، مفروزة في سجل مرجعي (`$env:TEMP\opencode\codex-all.txt`).
2. **التدقيق:** ثلاث جلسات إعادة-تدقيق مستقلة (Security/Auth، Payments/Ledger/Search، Migrations/CI/Docs) ثم **إعادة تحقيق شخصي** لكل ملف بنية تبقى على القائمة.
3. **التصنيف:**
   - **VIOLATION** — تناقض مقصور داخل الكود/المخطط نفسه، مُثبت بقراءة مباشرة (لا يحتاج رأياً).
   - **HARDENING** — غياب حراسة/تحقق رغم أن المسار الحالي محمي سلفاً من قيم سيئة فعلياً.
   - **DECISION** — تغيير يمس اتفاقية تصميمية قائمة، صانع قراره المستخدم (لا يُعدّ خطأً قبل القرار).
   - **ADDRESSED** — عولج بالفعل لاحقاً في history.
   - **OBSOLETE** — الأساس اختفى من الشجرة (لا يوجد هدف للتعديل).

---

## 2. مصفوفة التحقق الشخصي (قراءة مباشرة 2026-09-07)

| البند | الأدوات المقروءة مباشرة | git history | مؤكد |
|---|---|---|---|
| A1 | `V2__catalog.sql:4`, `V3__booking.sql:5`, `V6__reviews.sql:6`, `CatalogController.java:63`, `CatalogService.java:166,244`, `ProviderLookupAdapter.java:23`, `ProviderRepository.java:9`, `MediaService.java:230-241`, `AuthHelper.java:29-34` | — | ✅ |
| A2 | `SecurityConfig.java:187`, `ApiVersioningConfig.java:43` | — | ✅ |
| A3 | `SecurityConfig.java:213-244` | — | ✅ |
| A4 | `GraphQlExceptionResolver.java:29-41` | — | ✅ |
| A5 | `GlobalExceptionHandler.java:97-134`, `ApiErrorTaxonomy.java:9-17` | — | ✅ |
| A6 | `CorrelationIdFilter.java:27-37`, `GlobalExceptionHandler.java:136-158` | — | ✅ |
| A7 | `SecurityConfig.java:405-413` (jwtDecoder), `ExpiredAuthorizationsCleanup.java` | — | ✅ |
| A8 | `SecurityConfig.java:415-423` | — | ✅ |
| B1 | `MessageResponse.java:7-14` | — | ✅ |
| B2 | `LedgerService.java:21-42` | — | ✅ |
| B3 | `BookingInfo.java:18-26`, `V2__catalog.sql:8` | — | ✅ |
| B4 | `AvailabilityService.java:64-68,142-157`, `BookingService.java:120-141` | — | ✅ |
| B5 | `V17__payment_webhook_events.sql:4` | — | ✅ |
| C1 | `OpenApiConfig.java:33-39,54-60`, `GlobalExceptionHandler.java:123-128` | — | ✅ |
| C2 | `.ci/check-openapi-compat.sh:61-68` | — | ✅ |
| C3 | `V23__backfill_provider_user_id.sql:8-14`, `V1__init.sql:8,17`, `BaseEntity.java:40` | — | ✅ |
| C4 | `application.yml:50-51` | `7e12c78→26bb663→a943694` (V6)، `7e12c78→26bb663→19e6510→a943694` (V11) | ✅ |

النسخة التقنية المؤكدة: Spring Boot 4.1.1 / Hibernate **7.4.5.Final** (مدار من BOM — لا يُشترط إصداره يدوياً) / Spring Security 7 / Spring for GraphQL / springdoc 3.0.3.

---

## 3. البنود التنفيذية (VIOLATION / HARDENING)

### A1 — فساد فضاء المعرّف في التحقق من ملكية الـcatalog/media/availability
- **المصدر:** PR#121 (P1 — `CatalogService.java:193`).
- **مراجعة Codex:** «Compare listing owners against user IDs — `verifyOwnership` يبحث بـ`listing.getProviderId()` كمعرّف ملف مقدم، بينما الإعلانات تخزّن معرّف المستخدم؛ فصاحب الإعلان يُصاب بـ`AccessDeniedException`».
- **دليل الكود (تناقض داخلي):**
  - `V2__catalog.sql:4`: `provider_listings.provider_id uuid not null references users(id)` — فضاء **users.id**.
  - `V3__booking.sql:5` و`V6__reviews.sql:6`: نفسهما.
  - `CatalogController.create:63`: `providerId = currentUserProvider.getCurrentUserId(authentication)` — **user-id** فعلاً.
  - `ProviderLookupAdapter.java:23`: `providerRepository.findById(providerId)` — استعلام في **platform_profiles.id** (معرّف مستقل).
  - النتيجة: `CatalogService.create:166` (بوابة VERIFIED)، `CatalogService.verifyOwnership:244`، `MediaService.verifyOwnership:235`، `AuthHelper.ownsProvider:31` كلها تبحث عن user-id في فضاء profile-id ⇒ **المالك الشرعي يُرفض دائماً** (والمراجعات مصفوفة بذلك في `V6` فالمسارـ لـREFERENCES يربط users).
- **الأساس الرسمي:** نمط `@PreAuthorize + verifyOwnership` هو التصميم الأمني الموثق للمستودع («Security Design» في `PROJECT_MAP.md`، commit `d8c0aaf`) وفق Spring Security method-security: https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html. غرض الـ`findByUserId` («قارن المالك بمعرّف المستخدم») هو إسناد المستند الذي يوصي به Codex نفسه.
- **الإصلاح الجراحي:** إضافة `findByUserId(UUID userId)` إلى `ProviderLookupPort` + `ProviderLookupAdapter` (عبر `ProviderRepository.findByUserId` في موديول provider)؛ استخدامه في المواقع الأربعة بدل `findById`. تحديث الموكسات المتأثرة (`CatalogServiceSecurityTest`، `MediaServiceTest`، `AuthHelperTest`).
- **الاختبار:** حالة «المالك الشرعي يعبر الإنشاء/التحديث» + حالة «مقدم آخر يُرفض» لكل موقع.
- **التصنيف:** VIOLATION.

### A2 — `X-API-Version` غائب من allowedHeaders (CORS)
- **المصدر:** PR#39/#40/#41 (P1 — `SecurityConfig.java:152/157/99`).
- **مراجعة Codex:** «إزالة `X-API-Version` من allowedHeaders يكسر preflight للمتصفحات بينما `ApiVersioningConfig` ما زال يقرأ الترويسة».
- **دليل الكود:** `SecurityConfig.java:187` allowedHeaders = `Authorization, Content-Type, X-Correlation-ID, Idempotency-Key` (بلا X-API-Version) مقابل `ApiVersioningConfig.java:43`: `useRequestHeader("X-API-Version")`.
- **الأساس الرسمي:**
  - Spring Framework — CORS (السماح بالترويسات المرسلة عبر preflight): https://docs.spring.io/spring-framework/reference/web/webmvc/cors.html
  - Spring Framework — API Versioning («useRequestHeader» documented): https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-config/api-version.html
- **الإصلاح:** إضافة `"X-API-Version"` إلى `SecurityConfig.java:187`.
- **الاختبار:** `CorsConfigurationTest` (أو المكافئ) يقرأ allowedHeaders ويؤكد الحضور.
- **التصنيف:** VIOLATION (تناقض بين طبقة الأمان وطبقة versioning).

### A3 — `WWW-Authenticate` مفقود من 401/403
- **المصدر:** PR#98/#113 (P1 — `SecurityConfig.java:138/143`).
- **مراجعة Codex:** «استبدال معالجي الخاملين الافتراضيين يزيل تحدي Bearer القياسي؛ عملاء OAuth2 يعتمدون على RFC 6750».
- **دليل الكود:** `writeProblemDetail` (`SecurityConfig.java:233-244`) لا يستدعي `setHeader(HttpHeaders.WWW_AUTHENTICATE)` مطلقاً؛ الـentry point وaccess-denied handler كلاهما يكتبان JSON فقط.
- **الأساس الرسمي:**
  - RFC 6750 §3.1 (The WWW-Authenticate Response Header Field): https://www.rfc-editor.org/rfc/rfc6750.html
  - Spring Security — Bearer Tokens (chapter: «the resource server sends 401 with WWW-Authenticate challenge»): https://docs.spring.io/spring-security/reference/servlet/oauth2/resource-server/bearer-tokens.html
- **الإصلاح:** في `problemDetailAuthenticationEntryPoint` تعيين `WWW-Authenticate: Bearer realm="marketplace", error="invalid_token"`، وفي `problemDetailAccessDeniedHandler` تعيين `error="insufficient_scope"` قبل كتابة الجسم.
- **الاختبار:** اختبار WebMvcTest يفحص الرأس على 401 (بلا توكن/توكن تالف) و403 (ADMIN ناقص الصلاحية).
- **التصنيف:** VIOLATION (امتثال RFC متعارض مع عقد OpenAPI الذي يعد بتصنيف AUTHN/AUTHZ).

### A4 — GraphQL يحوّل `AccessDeniedException` إلى INTERNAL_ERROR
- **المصدر:** PR#99 (P1 — `GraphQlExceptionResolver.java:40`).
- **مراجعة Codex:** «ذراع catch-all يلتقط الفشل الأذوني ويحوله إلى INTERNAL؛ العميل لا يميّز الرفض من العطل».
- **دليل الكود:** `GraphQlExceptionResolver.java:29-41` — لا فرع لـ`AccessDeniedException`؛ يقع في `:40` → `INTERNAL_ERROR`.
- **الأساس الرسمي:** Spring for GraphQL — Error handling (فئة `DataFetcherExceptionResolver` تُعيّن الاستثناءات إلى أنواع خطأ/أخطاء GraphQL): https://docs.spring.io/spring-graphql/reference/execution/error-handling.html
- **الإصلاح:** فرع `AccessDeniedException` → `ErrorType.FORBIDDEN` بالرمز `ACCESS_DENIED` (يتماشى مع `ApiErrorTaxonomy.AUTHZ`).
- **الاختبار:** اختبار resolver: `AccessDeniedException` → FORBIDDEN.
- **التصنيف:** VIOLATION (تسرب تصنيف الخطأ؛ يتنافر مع معالجة REST والـtaxonomy).

### A5 — `IllegalArgumentException`/`IllegalStateException` → 500 بدل 4xx
- **المصدر:** PR#102 (P1 — `GlobalExceptionHandler.java:76`).
- **مراجعة Codex:** «تضييق المعالج جعل Illegal* يقع على handleGeneral ويعيد 500 بدل حالات العميل (كانت 400/409)».
- **دليل الكود:** `GlobalExceptionHandler.java:97-134` لا يملك معالَجَي نوعَين؛ `:130-134` catch-all → `ApiErrorTaxonomy.INTERNAL` (500). ومصادر حية معروفة اليوم: `BookingInfo.java:20,30,36` ترمي `IllegalStateException` لقيم صحيحة مجالياً.
- **الأساس الرسمي:**
  - RFC 9457 (Problem Details for HTTP APIs — التفريق الدلالي بين 4xx العميل و5xx الخادم مدخل تصميم المعيار لا الجدول نفسه): https://www.rfc-editor.org/rfc/rfc9457.html
  - الـtaxonomy الداخلية هي العقد: `ApiErrorTaxonomy.java:10` `VALIDATION→400`، `:14` `CONFLICT→409` — (الحجّة هيكلية التوافق الداخلي لا ادعاء معياري).
- **الإصلاح:** `@ExceptionHandler(IllegalArgumentException.class)` → `VALIDATION` (400) و`@ExceptionHandler(IllegalStateException.class)` → `CONFLICT` (409) — تماماً كتعيين GraphQL الموجود (`GraphQlExceptionResolver.java:33-38`).
- **الاختبار:** `GlobalExceptionHandlerTest` — حالات 400/409 تُستقبل شكل RFC 7807.
- **التصنيف:** HARDENING (الغاية تطابق affine داخلي محفوظ؛ لا تجاوز يعرض أثراً حقيقياً اليوم لأن مصادر Illegal* شن`BookingInfo` تُحمى أخوياً بـ`StrictType` عند الربط).

### A6 — `traceId` المولّد من جانب الخادم يغيب عن جسم الخطأ
- **المصدر:** PR#115 (P2 — `GlobalExceptionHandler.java:96`).
- **مراجعة Codex:** «الفلتر لا يضبط attribute؛ يرجع الخطأ في جسم Response دون traceId عند غياب ترويسة العميل».
- **دليل الكود:** `CorrelationIdFilter.java:27-37` يضبط `MDC` + هيدر الاستجابة فقط؛ `GlobalExceptionHandler.java:138-147` يقرأ header ثم attributes فقط — فإذا لم يرسل العميل الترويسة، يولد الفلتر UUID (MDC) ولا يصل إلى الجسم.
- **الأساس الرسمي:** عقد الملاحظة الداخلي للمستودع: schema `traceId` في `OpenApiConfig.java` (`problemDetailSchema`) + `docs/observability/runbooks.md` (مُدمج #245).
- **الإصلاح:** في `CorrelationIdFilter` وضع `request.setAttribute("correlationId", correlationId)` قبل السلسلة (سطرٌ واحد)، فيعمل المسار الثالث القائم في `GlobalExceptionHandler:142-147`.
- **الاختبار:** `GlobalExceptionHandlerTest` بلا ترويسة عميل: استجابة body تضم traceId.
- **التصنيف:** VIOLATION (انقطاع بين مولد الـtraceId وعقد OpenAPI).

### B1 — `MessageResponse` بلا `senderId`
- **المصدر:** PR#23 (P1 — `MessageResponse.java:10`).
- **مراجعة Codex:** «الـDTO يسقط senderId؛ العميل لا يعرف كاتب الرسالة ولا يقرأ علامة read صحيحة».
- **دليل الكود:** `MessageResponse.java:7-14` record بلا senderId — «خلل» نسبي بـواجهة الاستهلاك.
- **الأساس الرسمي:** عقد API داخل المستودع (OpenAPI المتولد) — لا معيار خارجي؛ تمديد العقد قرار توافقي مع الـFE.
- **الإصلاح:** إضافة مكوّن `UUID senderId` + تعيينه في الـmapper.
- **الاختبار:** اختبار ماب-الرسالة يؤكد القيمة.
- **التصنيف:** HARDENING (تحسين عقد استهلاك مُكتشف، لا تجاوز أمني).

### B2 — `LedgerService.creditFromPayment` بلا حارس مبلغ
- **المصدر:** PR#64 (P2 — `LedgerService.java:26`).
- **مراجعة Codex:** «creditFromPayment يطبّق amountCents مباشرة بلا تحقق؛ قيم صفر/سالبة تكتب قيوداً مضللة وتنقص الرصيد».
- **دليل الكود (على أساس `fd59d70`):** `LedgerService.creditFromPayment:21-30` لا تحقق مطلقاً، كما `debitFromCommission:33-45`، و`debitFromRefund:47-66` (الجديدة بـPR#252) — الثلاثة بلا حارس مبلغ؛ المتصل اليوم (`LedgerPaymentEventListener`) يمرر `priceCents` من `BookingInfo` (نطاقه يسمح بـ0 — انظر B3).
- **الأساس الرسمي:** الحلقة اخترم للمالي — قيد دفتر بمبلغ ≤ 0 يكتب أثراً مالية زائفاً؛ الممارسة أن يُرفض أو يُتخطى (لا معيار خارجي؛ قاعدة دفترية عامة موثقة ضمن `docs/…` للموديول).
- **الإصلاح:** حارس في المسارات الثلاثة (creditFromPayment/debitFromCommission/debitFromRefund): `amountCents < 0` → رفض (400)؛ `amountCents == 0` → إرجاع الرصيد بلا قيد (حفظ قيد صفر بلا أثر؛ مكمل للتوصية B3).
- **الاختبار:** `LedgerServiceTest` — سالب يرفض وصفر يتجاوز.
- **التصنيف:** HARDENING.

### C1 — OpenAPI بلا 503 في الردود الافتراضية
- **المصدر:** PR#96/#97 (P2 — `OpenApiConfig.java:60`).
- **مراجعة Codex:** «الحقن الافتراضي يسقط 503 رغم أن `handleCircuitBreakerOpen` يعيد Service Unavailable».
- **دليل الكود:** `OpenApiConfig.java:54-60` حقن 400/401/403/404/409/429/500 فقط مقابل `GlobalExceptionHandler.java:123-128` → `SERVICE_UNAVAILABLE` (503).
- **الأساس الرسمي:** مواصفة OpenAPI 3.0 — Components Object / Responses Object (أي استجابة يعاد استخدامها يجب تعريف `content`): https://spec.openapis.org/oas/v3.0.3.html
- **الإصلاح:** مكوّن `ServiceUnavailable` + سطر `addResponseIfMissing(…, "503", …)` في المحرك `:48-63`.
- **الاختبار:** اختبار ينفي تغيير الـcontract (فحص وجود 503 في الافتراضيات عبر customizer أو اختبار معاينة `/v3/api-docs`).
- **التصنيف:** VIOLATION (contract/behavior drift مع كتلة الـ503 الحقيقية).

### C2 — سكربت `check-openapi-compat.sh` يموتّ ذراع الاستثناءات
- **المصدر:** PR#104 (P2 — `.ci/check-openapi-compat.sh:67`).
- **مراجعة Codex:** «الفرعان يخرجان بـ1 دائماً؛ الاستثناء الموثق لا يسمح أبداً بمرور تغيير مقصود — التناقض مع سياسة الاستثناءات الموصى بها في المستندات».
- **دليل الكود:** `.ci/check-openapi-compat.sh:61-68` — `grep` لـ«[]» فارغة ثم `exit 1` في كلا الفرعين؛ `openapi-compat-allowlist.yml:3` = `exceptions: []`.
- **الأساس الرسمي:** سياسة التذكرة المرجعية داخل المستودع (تعريف الاستثناء «موقت قابل للإثارة») — يقابلها Conformity الوظيفي للـscript؛ لا معيار خارجي.
- **الإصلاح:** ذراع `exceptions` غير الفارغ: فحص التغطية (تطابق paths/status المحصورة) ثم `exit 0` إن كانت مغطاة، إلا فـ `exit 1` مع تسمية الأخطاء. الحفاظ على `exit 1` للفارغ.
- **الاختبار:** تشغيل محلي (النص) بحالة استثناء فارغة/غير فارغة -> رموز خروج مختلفة (نص اختباري داخل `.ci`).
- **التصنيف:** VIOLATION (لا تقوم وظيفة موصى بها).

### C3 — V23 backfill يربط مستخدمين محذوفين
- **المصدر:** PR#123 (P2 — `V23__backfill_provider_user_id.sql:14`).
- **مراجعة Codex:** «الـUPDATE لا يفلتر `is_deleted=false` في join ولا في count؛ يمكن ربط ملف بسجل soft-deleted (والمستخدم soft-deleted وحده يؤثر على خصوصية التطابق)».
- **دليل الكود:** `V23__backfill_provider_user_id.sql:8-14` — بلا `is_deleted`؛ `V1__init.sql:8` العمود موجود؛ `V1:17` فهرس الأدوار يرتكز على `is_deleted=false`.
- **الأساس الرسمي:** Hibernate ORM — @SoftDelete (الفلترة التلقائية على عمود الحذف الناعم): https://docs.jboss.org/hibernate/orm/current/userguide/html_single/Hibernate_User_Guide.html#soft-delete — والـ`BaseEntity.java:40` يفعّلها.
- **الإصلاح:** ترحيل **جديد** V{n}: ترميم أي ربط إلى مستخدم محذوف (set null) + backfill مصحح بإضافتي `u.is_deleted = false` و`u2.is_deleted = false`. **لا تعديل V23** (ترحيل مطبق — ممنوع إعادة الكتابة، انظر C4).
- **الاختبار:** التحقق عبر `mvn clean verify -pl marketplace-app` وحالة ترحيل خضراء على DB نظيف.
- **التصنيف:** VIOLATION.

---

## 4. البنود القرارية (DECISION — توصيات موصى بها بانتظار موافقة المستخدم)

| البند | الموضوع | التوصية | البديل |
|---|---|---|---|
| A7 | إبطال JWT في زمن اعتماده (PR#168) — SAS يدير `/oauth2/revoke` (https://docs.spring.io/spring-authorization-server/reference/protocol-endpoints.html) لكن التوكن المكوّن ذاتياً يُقبل محلياً حتى expiry — عقدة معروفة لـOAuth2 | **قبول موثق:** TTL قصير للوصول + Revoke server-side للـrefresh (موجود) + توهّم في runbook | قائمة رفض `jti` بجدول+validator (ترحيل + DB-هيت لكل طلب) |
| A8 | `requiredAudiencesValidator` يستخدم `anyMatch` (RFC 7519 §4.1.3 — https://www.rfc-editor.org/rfc/rfc7519.html) | **توثيق لا تعديل:** audience واحد (`marketplace-api`) لذا anyMatch ≡ allMatch عملياً | تقوية إلى allMatch عند تنوع مستقبلي |
| B3 | `BookingInfo` يرفض `priceCents <= 0` بينما المخطط يسمح `>= 0` (V2:8) ونموذج الطلب لا يفرض سوى `@NotNull` — Jakarta Validation `@Positive` مرشح الانضباط: https://jakarta.ee/specifications/bean-validation/ | **السماح `0`:** قبول `0` ورفض `<0` فقط — يطابق المخطط الحالي ويمكّن الخدمات المجانية | فرض `>0` منبجاً عبر `@Positive` + CHECK جديد (تغيير عقد قائم) |
| B4 | `isAvailable` (تراكب) مقابل `bookSlot` (تطابق تام) — حجز نافذة فرعية يَمُرّ `create` ويفشل `confirm` | **Option M:** فرض التطابق التام عند `create` فيتوقف الخطأ مبكراً (400) — أبسط وصادق | **Option S:** تقسيم الفتحة عند الحجز (دقة عليا، عبء أعلى) |
| B5 | dedup وبvب بمعرّف الحدث وحده (`V17:4`) لا بمقدّم الحدث (PR#58/62/64) — اليوم القناة الوحيدة Stripse بمعرّفات عالمية | **تبنٍ فوري:** ترحيل: إسقاط UNIQUE(event_id) → فهرس UNIQUE(provider,event_id) + بحث provider-scoped | إبقاء الحالة وإثبات النية في المستند |

---

## 5. عولج (ADDRESSED) — مثبت بالقراءة المباشرة

- **#26/#27 السلسلات** — `SecurityConfig.java:105-169` ثلاث سلاسل مرتّبة `@Order` securityMatcher.
- **#30 jwkSource/seed** — fail-fast prod فقط `:337-344`؛ seed admin-only.
- **#49 ملكية ProviderService** — `ProviderService.java:49-57,77-83` (binding بـuserId عند الإنشاء).
- **#49 webhook`confirmIntent`** — وراء بوابة توقيع fail-closed (`PaymentsService.java:182`، `PaymentWebhookSecurity.java:38-45`).
- **#58-63 سباق الـdedup** — `PaymentsService.java:98-142` + `WebhookEventRecorder.java:50-66` (REQUIRES_NEW + catch DUPLICATE + مسح تعويضي).
- **#60/61/65 fail-open** — `e51dae5` fail-closed.
- **#50 صفرية القيد / الأرباح** — `LedgerPaymentEventListener.java:46-57` (مصدر حقيقي للرصيد والمبلغ).
- **#63 الـcache** — يفقد في طبقة الـcatalog (`CatalogService.java:67,75,94`).
- **#53-65 أعمدة BaseEntity** — `V25__add_base_entity_columns.sql` (كل الجداول العشرة).
- **#136 V26 audit** — `V33:22-25` يضيف الـ`_aud` الأعمدة (ناهج الترحيل الجديد الصحيح).
- **PR#136 البريد placeholder** — `EmailNotificationService.java:56-63` يتحول userId → email فعلي عبر `UserLookupPort`.
- **PR#119 WebSocket** — `WebSocketSecurityConfig.java:29-30` عبر `ConversationSubscriptionAuthorizationManager` (فحوص مشاركة).
- **PR#86/85/88 OpenAPI schema** — `OpenApiConfig.java:74-98` (content مطلوب).
- **PR#104 بيئة CI** — `ci.yml:73-91` env كامل للبوابة.
- **PR#106 prod YAML** — `application-prod.yml:108` (هروب `\\s`).
- **PR#44 Dockerfile** — `ENTRYPOINT ["java","-jar","app.jar"]`.

## 6. أصبح بلا قيمة (OBSOLETE) — الأساس اختفى

- **PR#28 frontend** — لا `frontend/` في الشجرة إطلاقاً.
- **PR#29 compose app** — `docker-compose.yml` يخدم postgres+redis فقط.
- **PR#150/151 الهوية الرمزية** (AuthController/VerificationToken/MFA/TOTP/BruteForce/OneTimeToken/AdminIp) — أُعيد تصميم الهوية؛ لا توجد فئات أو جداول.
- **PR#48 docs/backend-official-execution-plan.md** — الملف لم يظهر في `git log` قط.
- **PR#118 audience** — `SecurityConfig.java:455-467` customizer يكتب `aud` فعلاً.

---

## 7. دفعات التنفيذ (Batch A + B1/B2 + C1/C2 منفَّذة عبر PR #253)

```
Batch A (أمن + فضاء المعرّف):  A1→A2→A3→A4→A5→A6 — **منفَّذ عبر PR #253** (A3 مُحسَّنة بجولة CodeRabbit: تحدّي عارٍ لطلبات بلا توكن، `error="invalid_token"` للتوكن المُقدَّم التالف عبر بوابة `oauth2ResourceServer().authenticationEntryPoint`)
Batch B (دومين/API):            B1→B2 — **منفَّذ عبر PR #253** (+ B3/B4/B5 بعد قرار المستخدم)
Batch C (بنية/CI):              C1→C2 — **منفَّذ عبر PR #253** (C2 مُكمَّلة بجولتين: إصلاح تغليف — إزالة BOM قبل الـshebang، صلاحية 755 للسكربتين الجديدتين، وسطر نهاية الملف — ثم جولتا CodeRabbit ج3: تقويم حقيقي للتواريخ + فشل مقفول [UNREPRESENTABLE] لتغييرات المعاملات (بما فيها Add — التقرير النصي لا يُظهر required) والطلب غير الممثَّلة في قائمة (path,status) وفق دلالات ChangedParameters/ChangedParameter/ChangedContent/ChangedMediaType الرسمية)؛ C3 ينتظر، وC4 قرار موثق
```

- فرع واحد: `feat/codex-still-valid-sweep` من `origin/main`؛ دمج عبر PR واحد squash (حوكمة §14.3).
- لكل تعديل: TDD — اختبار يفشل→ينجز→ينجح (حوكمة «جراحة»)؛ لا تلمس مجاوراً.
- تحقق: `mvn clean verify -pl <module>`، واختبارات التكامل `@ActiveProfiles("test")`+Testcontainers.
- لا ترحيلات جديدة إلا عبر V{n} جديد؛ لا إعادة كتابة ترحيلات مطبقة (C4).
- انحراف عن أي اقتباس رسمي هنا يُبرر في PR body (حوكمة §8 — Deviation).