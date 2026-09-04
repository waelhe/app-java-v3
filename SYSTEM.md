# SYSTEM.md — الخريطة المرجعية لآلية عمل النظام

> **غرض هذا الملف:** المرجع الدائم لفهم «كيف يعمل نظامنا» — يُقرأ **أولاً** قبل أي تعمّق في أي طبقة، ويُحدَّث عند كل حقيقة جديدة موثَّقة. هو الثالث في عائلة ملفات الجذر: `AGENTS.md` (القواعد) / `PROJECT_MAP.md` (سجل الحالة والقرارات) / `SYSTEM.md` (الآلية والفهم).
>
> **قاعدة الذهب:** كل حقيقة هنا تحمل دليلها `ملف:سطر` من هذا المستودع أو من مصدر رسمي محفوظ. لا يُضاف ادعاء بلا دليل، ولا يُعدَّل دليل بلا إعادة تحقق من الكود.
>
> **نظام الحوكمة (§14):** من يقرر ماذا وعلى أي دليل — قواعد العمل الدائمة (§14.1) + آليات الخطة الحاكمة (§14.2) + أعراف الدمج والفروع (§14.3).

---

## 1. بطاقة الهوية (كلها مثبتة من الكود هذه الجلسة)

| البند | القيمة | الدليل |
|---|---|---|
| النظام | Marketplace Backend — REST + GraphQL، سياق Spring **واحد** (حزمة الجذر `com.marketplace`) | `MarketplaceApplication.java` |
| Java | 25 (`--release 25`) | `pom.xml:41` |
| Spring Boot | **4.1.1** عبر الوراثة من `spring-boot-starter-parent` | `pom.xml:7-10` |
| Spring Modulith | 2.1.1 (BOM مستورد) | `pom.xml:65-67` |
| Spring Authorization Server | 7.1.1 (قادم عبر BOM الإطار؛ مصادره مخبأة محلياً — §10) | `scripts/verify-aud-claim/sas-all/` |
| Maven | wrapper 3.9.16 (`./mvnw`)، enforcer يشترط `[3.9,)` | `mvnw` + `pom.xml:230` |
| قاعدة البيانات | PostgreSQL 18 في المستودع (CI/compose/Testcontainers) **وفي الإنتاج** (خدمة `postgres-18` = postgres-ssl:18 الرسمية + فوليوم، 18.6، نقل 57 جدولاً صفر فرق 2026-09-04 — §15)؛ Flyway 12.4.0 (BOM) يقبلها — `handlesDatabaseProductNameAndVersion` يفحص البادئة لا النطاق | `.github/workflows/ci.yml:23-24` + `docker-compose.yml:3` + سجل الإقلاع الحي (§15) |
| الذاكرة/الجلسات | Redis 8 في المستودع (CI/compose) **وفي الإنتاج** (8.2: ترقية في المكان + فوليوم + requirepass + RDB 2026-09-04 — §15)؛ Lettuce 7.5.2 (BOM) يدعم رسمياً «Redis 2.6+ up to Redis 8.x» | `.github/workflows/ci.yml:36-37` + `application.yml:109-111` |
| الجودة | JaCoCo 0.8.15، عتبة تغطية ≥ 70% لكل وحدة (BUNDLE) | `pom.xml:44-45` |
| الوحدات | **16** وحدة Maven في Reactor الجذر | `pom.xml:22-37` |
| النشر | Dockerfile + `.railway/railway.ts` (IaC — إعدادات خدمة-مستوى) + docker-compose.yml | جذر المستودع |

---

## 2. نموذج الملكية المزدوج — من يملك ماذا ومتى

النظام يقوم على مبدأين لا يُخلط بينهما: **Maven يملك زمن البناء** و**Spring Boot يملك زمن التشغيل**. أي سؤال «من قرر هذا؟» يُحل بتحديد الطبقة أولاً.

| الجانب | المالك | الآلية | الدليل |
|---|---|---|---|
| ترتيب بناء الوحدات | Maven | Reactor يستنتجه من جراف الاعتماديات في `<modules>` | `pom.xml:22-37` |
| إصدارات الاعتماديات | Maven | الوراثة (parent 4.1.1) + `dependencyManagement` (BOMs + استثناءات موثقة) | `pom.xml:7-10, 60-210` |
| بوابات الجودة | Maven | أهداف plugins مربوطة بمراحل دورة الحياة | `pom.xml:217-260` |
| مخطط قاعدة البيانات | Flyway | ترحيلات V/R — **وحدد صفر `ddl-auto:none`** | `application.yml:28, 41` + `db/migration/` |
| إنشاء الفول والتوصيل | Boot | component scan + auto-configuration شرطية | `MarketplaceApplication.java:9-11` |
| قراءة الإعدادات | Boot | ربط نوعي `@ConfigurationProperties` | `MarketplaceProperties.java:17` |
| دورة الحياة عند الإقلاع | Boot | runners ثم الجاهزية | صفحة `spring-application` الرسمية المحفوظة (`scripts/doc-verify/`) |

---

## 3. طبقة البناء — كيف يبني Maven النظام

**البنية:** الجذر `pom.xml` بـ `packaging: pom` (`:17`) — **مجمِّع (Reactor)** يبني 16 وحدة بترتيب يُستنتج آلياً من جراف الاعتماديات، وكل وحدة ترث من `spring-boot-starter-parent:4.1.1` فتحصل على إدارة الإضافات والافتراضات. `dependencyManagement` في الجذر يثبّت BOM مودولِث والاستثناءات (springdoc, mapstruct, resilience4j, instancio, archunit, jackson, prometheus — `pom.xml:60-210`).

**الدورة الحياتية (من الوثيقة الرسمية المحفوظة):** ثلاث دورات (default / clean / site). المراحل نقاط تسلسل صارمة؛ كل هدف plugin يرتبط بمرحلة؛ استدعاء `./mvnw verify` يشغّل كل ما قبله ضمن default. **ربطاتنا:**

| المرحلة | Plugin | ماذا يفعل عندنا |
|---|---|---|
| compile | compiler | `failOnWarning` — أي تحذير يُفشل البناء |
| test | surefire | اختبارات الوحدة (`*Test`) — منفصلة تماماً عن التكامل |
| integration-test / verify | failsafe | اختبارات التكامل (`*IT`) — 34 منها بيئية تُتخطى بلا Docker |
| verify | jacoco | تقرير + **check: BUNDLE ≥ 0.70 لكل وحدة** (`pom.xml:242-244`) |
| validate | enforcer | 5 قواعد؛ أشهرها Maven `[3.9,)` (`:230`) وJava `[21,)` (`:233`) |
| package | spring-boot-maven | `repackage` → jar تنفيذي لوحدة `marketplace-app` فقط |

**الأوامر المعتمدة:** البوابة الكاملة `./mvnw clean verify` — البناء الموجّه `./mvnw clean verify -pl <module> -am` (قاعدة AGENTS.md: تُشغَّل قبل أي دفع).

---

## 4. طبقة الإقلاع — كيف يقوم النظام (من `main()` حتى أول ترافيك)

نقطة الدخول `MarketplaceApplication.java` (17 سطراً): `@SpringBootApplication` + `@Modulithic` + `@EnableConfigurationProperties(MarketplaceProperties.class)` (`:9-11`)، ثم `SpringApplication.run(...)` (`:14-16`).

**التسلسل (مثبت من وثيقتي spring-application وauto-configuration الرسميتين المحفوظتين في `scripts/doc-verify/`):**

1. `SpringApplication.run` ينشئ السياق ويجهّز البيئة: مصادر الإعدادات الخارجية (yml → متغيرات بيئة → args) بترتيب أسبقية موثق (`scripts/auth-design/boot-external-config.txt`).
2. **انعاش السياق:** حبوب `com.marketplace` (scan) + **التهيئة التلقائية**: كل وحدة auto-configuration تُقرأ من `META-INF/spring/...AutoConfiguration.imports` وتخضع لشروط (`@ConditionalOnClass` / `@ConditionalOnMissingBean`) — **حبّة يعرّفها المستخدم تزيح نسخة الإطار**. مثال حي عندنا: خاصيات `spring.security.oauth2.authorizationserver.client.*` خاملة لأننا نعرّف `JdbcRegisteredClientRepository` بأنفسنا (قرار D2 الموثق).
3. أثناء الانعاش نفسه تُهاجر قاعدة البيانات: **Flyway** يطبّق `db/migration` (30 ترحيلة V + الـ seed R__) لأن `ddl-auto: none` (`application.yml:28`) — أي أن المخطط ملك Flyway حصراً.
4. يبدأ خادم الويب (Tomcat، المنفذ 8080 — `application.yml:127`)، ومعه خيوط افتراضية مفعّلة (`spring.threads.virtual.enabled: true` — `application.yml:4-7`).
5. تُستدعى `ApplicationRunner`/`CommandLineRunner` — ثم فقط يصبح التطبيق **جاهزاً**. اقتباس رسمي حرفي: *"ready as soon as application and command-line runners have been called"* — ومهام الإقلاع مكانها الـ runners لا `@PostConstruct` (اقتباس الوثيقة نفسها). **هذه البوابة هي مكان أي مُهيّئ مستقبلي (مثل مُهيّئ سرّ العميل env→DB).**
6. المراقبة تعمل: Actuator (يشمل `conditions` — `application.yml:135-141`)، معلومات البناء من `build-info` + `git-commit-id` في `/actuator/info`، والقياسات عبر OTLP (`application.yml:163-173`).

**لماذا يهم هذا عملياً:** أي سلوك «غريب» عند الإقلاع يُشخَّص بترتيب هذا التسلسل — مثال مثبت: اختبارات `@SpringBootTest` بسياق كامل تحتاج Redis حياً لخزين الجلسات (`application.yml:70-75`)، فتفشل محلياً بلا خادم بينما CI (services) يوفره — الآلية إطارية سليمة والفرق بيئي.

---

## 5. البنية النمطية — 16 وحدة تحت Modulith

**القسمة (من `pom.xml:22-37`):** وحدة تجميع `marketplace-app` (جذر التركيب: ymls، الترحيلات، `main()`) + بنية تحتية مشتركة `marketplace-platform-infra` (ثماني حزم: `cache/config/email/jpa/observability/resilience/security/web`) + `marketplace-shared` (واجهات SPI + الأحداث المشتركة + الاستثناءات) + **13 وحدة نطاق**: identity, catalog, booking, payments, pricing, reviews, messaging, search, provider, availability, notifications, ledger, disputes.

**آلية الحدود:** كل وحدة نطاق تحمل `package-info.java` يعلن `@NamedInterface` + `@ApplicationModule(allowedDependencies=...)`. نموذج حرفي (booking):

```java
@org.springframework.modulith.NamedInterface("booking")
@org.springframework.modulith.ApplicationModule(
    allowedDependencies = {"shared :: shared-api", "shared :: shared-security", "shared :: shared-jpa"}
)
package com.marketplace.booking;
```

الاعتماد العابر بين النطاقات **ممنوع بنيوياً** — التواصل يمر عبر: (أ) واجهات SPI في `shared/api` (AvailabilityPort, ProviderLookupPort, CatalogSpi, UserLookupPort, BookingParticipantProvider, PaymentIntentLookupPort…) ينفّذها مالك البيانات، أو (ب) أحداث نطاق تُنشر بعد الالتزام (AFTER_COMMIT). الحدود مفروضة زمنياً بفحص Modulith/ArchUnit ضمن CI.

**آلية الأحداث (كلها موثقة في PROJECT_MAP):** مستمعو `@ApplicationModuleListener` يعملون بمعاملة `REQUIRES_NEW` مع إعادة محاولة عند فشل الاستماع (لا `catch(Exception)` — أُزيلت 5 مرات عمداً). إكمال النشر: `archive` في base/prod و`delete` في dev — في وضع ARCHIVE ينقل السجل كل صف (حدث، مستمع) منجَزاً من `event_publication` إلى **`event_publication_archive` (V28 — مخطط PostgreSQL الرسمي «Archive-enabled schema» من مرجع Modulith 2.1.1، محفوظ `scripts/doc-verify/modulith-schema-2.1.html`)** ويختم `completion_date` ثم يحذف الأصل؛ مهمة `EventPublicationCleanup` المنظّمة (`@Scheduled` 3 صباحاً) تطهّر أرشيف المنشورات المنجزة الأقدم من 7 أيام. الحارس: `EventPublicationArchiveIntegrationTest` (failsafe) يقلع بالسياق الكامل على Flyway الحقيقي (`flyway.enabled=true` + `ddl-auto=none`) — لأن بروفايل test (create-drop بلا Flyway) كان يبني الجدول من الكيان فيحجب فجوة المخطط (درس #archive). الأحداث المشتركة المعيشة: BookingCreated / BookingConfirmed / BookingCancelled / PaymentStateChanged / ReviewUpdated / CacheInvalidationRequested.

**روابط النطاقات الفعالة (6):** booking↔availability (حجز/إعتاق الخانة)؛ Payment COMPLETED → ledger (قيود) + booking (autoConfirm)؛ Booking CANCELLED → payment (استرجاع)؛ catalog → provider (شرط VERIFIED)؛ notifications → email/WebSocket عبر `UserLookupPort`.

---

## 6. طبقة الأمن — Authorization Server داخل التطبيق (SAS 7.1.1)

**ثلاث سلاسل فلترة بترتيب `@Order` (SecurityConfig في `marketplace-platform-infra/.../security/`):**

| @Order | السلسلة | الوظيفة | الدليل |
|---|---|---|---|
| 1 | `authorizationServerSecurityFilterChain` | نقاط AS + OIDC، `securityMatcher(endpointsMatcher)` | `:93-111` |
| 2 | `resourceServerSecurityFilterChain` | واجهات API عديمة الحالة (STATELESS) عبر JWT، `/api/v1/admin/** → ADMIN` + `anyRequest().authenticated()` | `:113-132` |
| 3 | `defaultSecurityFilterChain` | تسجيل الدخول النموذجي + جلسات (Redis) | `:143-152` |

**مصدر المفاتيح JWK — المسار المزدوج بحارس prod (`SecurityConfig.jwkSource`):** في prod يُقرأ keystore من `MarketplaceProperties.Security.Jwt.KeyStore` (record متداخل: path/password/alias — `MarketplaceProperties.java:30-37`)، وخاناته الأربع في `application-prod.yml` **إلزامية بلا افتراضات ⇒ فشل فوري إن غابت** (قرار D6: مفاتيح دائمة في الإنتاج). **حارس إضافي بالكود (المرحلة 4):** بروفايل `prod` نشط + أي خانة فارغة ⇒ `IllegalStateException` عند الإقلاع — السقوط إلى المفتاح العابر مستحيل في prod (دفاع عميق يُمسك السلاسل الفارغة وانجراف الربط)؛ تُفرض البوابة في CI عبر `JwkSourceProdHardeningTest` (وحدة infra — 3 حالات على JKS حقيقي). في dev الخانات فارغة افتراضاً ⇒ توليد RSA عابر (نمط quickstart الرسمي — للتطوير فقط) بتصميم مقصود. Runbook التوليد/التدوير: `keys/README.md`.

**العملاء يحيون في قاعدة البيانات:** `JdbcRegisteredClientRepository` (جدول `oauth2_registered_client` من `V13__authorization_security.sql`) — وليس ملف إعدادات. حبّة JDBC تزيح auto-config الذاكرية (D2). سلوك الحفظ الحاسم: `save()` يبحث بالـ **PK (`id`)** — موجود ⇒ UPDATE، غائب ⇒ INSERT (`JdbcRegisteredClientRepository.java:147-156` بالمصادر المخبأة).

**المسار الرسمي الوحيد لتأسيس العميل — `OAuth2ClientSecretInitializer` (PR #184 — مدمج `773558e`):** الـ seed اليدوي (`R__seed_oauth2_client.sql`) **لم يعد يحمل العميل إطلاقاً** (أُخلي `oauth2_registered_client`؛ admin `auth_users`/`auth_authorities` باقٍ). التأسيس/التقارب/التدوير يجري عبر **`RegisteredClientRepository.save(RegisteredClient)` بالباني الرسمي** (`ClientSettings`/`TokenSettings`) في `ApplicationRunner`:
- **تأسيس** (غائب) ⇒ `RegisteredClient.withId(a7bd8b0d-…)` + التعريف الكامل (grants `authorization_code,refresh_token,client_credentials`, redirect `127.0.0.1:8080/...`, scopes `openid,profile`, `client_secret_basic`).
- **converge-on-boot** (موجود) ⇒ إعادة اشتقاق كاملة عبر `RegisteredClient.withId(existing.getId())` (بوابة B: redirect URIs أصبحت موجهة بالبيئة — باني `from()` يزرع المجموعات المخزنة بلا عملية استبدال، INV-4) + باني الإعدادات (لا نقل خريطة مرضوضة بفجوة id-token).
- **حارس idempotence**: save ⇔ السرّ اختلف ∨ خرائط الإعدادات اختلفت.
- القيم الظرفية مثبتة: reuse=false / 900s / 604800s / 300s / consent=true / proof-key=true (الباني الافتراضي يختلف — يُثبَّت صراحةً).

انسحب البند السابق «بذرة العميل تحمل المفتاحين معاً (D4) + upsert تقاربي» — أصبح تاريخياً بعد إخلاء العميل من R__ ودور المُهيّئ الكامل (تأسيس لا تدوير فقط).

**بوابة B — العميلان الأولان (عام + سري) عبر مسار #183 نفسه:**
- **العميل العام (النمط 3 — فلاتر/أصيل):** `OAuth2PublicClientInitializer` (ApplicationRunner ثانٍ) — `client_authentication_method: none` (بلا سرّ؛ العمود NULL-able في `V13:22`) + `authorization_code` **حصرًا** (لا منح refresh — سلوك gh-297) + `requireProofKey`/consent إلزاميان + access 900s/code 300s؛ redirect URIs من `OAUTH_PUBLIC_CLIENT_REDIRECT_URIS` (مخطط مخصص RFC 8252، فصل بفواصل)؛ id صف ثابت `10b588c6-4e85-43ec-9ecf-c588676774d7`؛ fail-fast في prod (كلا الخانتين)؛ حارس idempotence موسّع (تعريف كامل + خرائط).
- **السري (النمط 1 — BFF):** `OAuth2ClientSecretInitializer` نفسه توسّع بـ `OAUTH_CLIENT_REDIRECT_URIS` (prod إلزامية بلا افتراض — يغلق دين «redirect الإنتاج مثبّت على ثابت التطوير»؛ dev بلا env يبقى على ثابت 127.0.0.1) + حارس idempotence موسّع للتعريف الكامل.
- البوابة الحية للعميل العام: `PublicPkceClientGateIntegrationTest` (authorize+PKCE بمخطط مخصص → login → consent → code → تبادل بلا مصادقة عميل → **لا refresh_token** + id_token ثلاثي الأجزاء + aud/sub → admin يمر/user 403؛ سالباتها: بلا code_challenge=302 خطأ، Basic=401 invalid_client، refresh grant=401 فارغ).

**نموذج التفويض ثلاثي الطبقات (موثق «Security Design» في PROJECT_MAP):** catch-all في HttpSecurity + 26 قاعدة `@PreAuthorize` دقيقة في طبقة الخدمات + 4 بوابات controllers إدارية — لكل قاعدة اختبار سالب وموجب (52 اختباراً).

**دروس مثبتة تجريبياً (لا تُعَد اكتشافها):** `ClientSettings.withSettings(map)` لا يطبق الافتراضات (`:115-118`) بينما نفس الصف يعوّض افتراض TokenSettings دون ClientSettings (`JdbcRegisteredClientRepository.java:362-367` — عدم تناظر الإطار يقوّي «قاعدة البيانات هي الحقيقة» D2)؛ وخريطة Jackson متعددة الأشكال تسمح `UnmodifiableMap` وترفض `ImmutableCollections$List12` (جولة aud/E5 مثبتة بـ `AudRoundTrip.java`).

**الجلسات:** خزين Redis بمساحة `marketplace:session` (`application.yml:70-75`)، بحد أقصى جلستين (`:245-246`)، وكلمة المرور `DelegatingPasswordEncoder` مع bcrypt (`SecurityConfig:197-199`).

---

## 7. طبقة البيانات — Flyway يملك المخطط

- **المصدر الوحيد للمخطط:** 30 ترحيلة نسخية `V1..V30` + بذرة واحدة `R__seed_oauth2_client.sql` في `marketplace-app/src/main/resources/db/migration/`. **V28 = `event_publication_archive`** (مخطط Modulith الرسمي — المطلوب لوضع الإكمال `archive`؛ انغلاق الدين الموثق الذي ظهر حياً في إنتاج 51b5496d). **V29 = إزالة آلة البحث الميتة** (إسقاط `mv_listing_search` + فهرسيها + حذف صفوف واجبة `searchIndexRefresh` من متجر Quartz JDBC — البند التالي). **V30 = تسلسل مراجعات Envers `revinfo_seq`** (V24 كتبت يدويًا عمود identity بينما مولّد Envers يستخدم التسلسل `revinfo_seq` — أول كتابة كيان `@Audited` على مخطط الترحيلات كانت ستفشل بالخطأ الحي «relation "revinfo_seq" does not exist»؛ كشفه اختبار يقلع على المخطط الحقيقي — ثالث تحقق لدرس «مخطط الاختبار ≠ مخطط الإنتاج»). قاعدة AGENTS.md صارمة: أي تغيير مخطط = ملف V جديد؛ **لا تُعدَّل ترحيلة موجودة أبداً**. **درس الوضع-الرسمي:** بروفايل test يعطّل Flyway (`ddl-auto: create-drop`) فيبني المخطط من الكيانات — أي جدول يملكه Modulith/إطار يجب أن يكون في ترحيلة V وإلا حُجب الفرق عن CI (الحارس: اختبارات `flyway.enabled=true + ddl-auto=none` على نمط `QuartzJdbcJobStoreConfigTest`).
- **دلالة R__ (Repeatable):** يعاد تطبيقه عند تغيّر checksum، وترتيبه **بعد آخر V** — هذه هي الآلية التي تجعل البذرة «حية» تتقارب مع الكود (أساس upsert المرحلة 1).
- **تدقيق Envers:** كل الكيانات `@Audited` (جداول `_aud` من `V24`) والمستودعات `RevisionRepository` مع `RevisionService` موحّد للحجج التاريخية في `marketplace-admin`.
- **Redis بثلاث أدوار:** جلسات + 13 مخبأة مسماة (`application.yml:111`) + خزين Quartz JDBC (`:112-118`, من `V21`). الإبطال **بعد الالتزام حصراً**: `CacheInvalidationRelay` (`@TransactionalEventListener(AFTER_COMMIT)`) ينشر `CacheInvalidationRequested` — لا `@CacheEvict` مباشر على الخدمات (أزيلت 30 إزالة في PR #179).
- **البحث (حقيقة V29):** البحث النصي = استعلام أصلي واحد على `provider_listings` عبر فهرس GIN `idx_listing_search_title` (`V9`) بترتيب `ts_rank`، والتحليل النصي للمدخل الخام بـ **`websearch_to_tsquery('simple', :query)`** (الدالة الرسمية المصممة لمدخلات المستخدم — مرجع PostgreSQL 18: «simple unformatted text is a valid query»؛ تقبل `"عبارة مقتبسة"` و`OR` و`-استثناء` ولا ترمي خطأ صياغة أبدًا — أغلقت عيب 500 الحي على المدخلات الخاصة، PR بالفرع `fix/search-websearch-and-dead-matview`). **العروض المادية والواجبات المنظّمة أُزيلت:** `mv_listing_search` (V9) كانت تُحدَّث كل 5 دقائق بواجبة Quartz متينة بينما **لا يقرؤها أي استعلام** (البحث يقرأ الجدول عبر GIN مباشرة) — V29 أسقطتها مع فهرسيها وحذف صفوف `searchIndexRefreshJob/Trigger` من متجر Quartz JDBC (يضمن حذف الصفوف أن الحذف الآمن للفئة لا يترك ClassNotFound متكررًا؛ ترتيب Flyway-قبل-Quartz يضمنه الإطار: `SchedulerDependsOnDatabaseInitializationDetector` في spring-boot-quartz 4.1.1). الحارسان: `CatalogSearchFullTextIntegrationTest` (دلالات websearch على PostgreSQL حقيقي + حماية الاستعلام الأصلي من انحراف مخطط الكيانات) و`QuartzJdbcJobStoreConfigTest` (معاد كتابته بفاعل بديل — درس b5d9f7f5 محفوظ) + projections للقراءة فقط (ListingSimple, ReviewSummary, BookingSummary).

---

## 8. طبقة الإعدادات — الربط النوعي والبروفايلات

- **الملفات:** `application.yml` (أساس) + `application-dev.yml` + `application-prod.yml` (في `marketplace-app/src/main/resources/`) + `application-test.yml` (في موارد الاختبار). أسبقية المصادر الخارجية وفق ترتيب Boot الموثق (نسخة محفوظة: `scripts/auth-design/boot-external-config.txt`).
- **الربط النوعي:** سجل `MarketplaceProperties` (`@ConfigurationProperties(prefix="marketplace")` — `:17`) بشجرة records متداخلة (Cors/Security/Jwt/KeyStore/Session/OAuth2 — بعد PR #183). **السابقة الحاكمة:** `Jwt.KeyStore` — إعداد يُقرأ من env ويُحسم سلوكه (prod إلزامي/dev افتراضي فارغ)، ونفس النمط اتبعه `OAuth2.Client` (client-id/secret عبر `OAUTH_CLIENT_ID/SECRET`، §11). **درس الربط الرسمي (مثبت):** المكوّن المتداخل الغائب يُربط `null` — والوثيقة الرسمية تحلها بـ `@DefaultValue` فارغة (اقتباس حرفي مطابق في الصفحة المخبأة `boot-external-config.txt:1098`).
- **متغيرات البيئة الحية:** `SPRING_DATASOURCE_URL`, `REDIS_HOST/PORT`, `MAIL_*`, `SESSION_MAX_SESSIONS`, `SERVER_PORT` (كلها ببادئات في `application.yml`).

---

## 9. طبقة الجودة و CI

- **ثلاثة workflows** في `.github/workflows/`: `ci.yml` (services: postgres:18-alpine + redis:8-alpine بصحة مُتحقَّقة، gitleaks@v3، JDK matrix temurin، `./mvnw verify --batch-mode` مع env قاعدة البيانات)، `integration-test.yml` (`clean verify` + تشغيل فعلي للخادم ببروفايل test)، `maven-publish.yml`. إصدارات الأكشنات مرفوعة إلى checkout@v7 / setup-java@v6 / upload-artifact@v7 / gitleaks@v3 (2026-09-04 — Node 20 يُحذف من عدّاءات GitHub في 2026-09-16 فكان الترقيل ضرورة لا رفاهية).
- **تصنيف الاختبارات:** وحدة (surefire, `*Test`) / تكامل (failsafe, `*IT`) — **فصل صارم لا يُخلط**. `@WebMvcTest` شرائح controllers (44 اختباراً)؛ `@SpringBootTest` سياق كامل (يحتاج Redis حياً — §4)؛ تكاملات الوحدات 14 صنف `ModuleIntegrationTest` في `marketplace-app`؛ Testcontainers مع `disabledWithoutDocker`.
- **البوابات الخمس في كل build:** تغطية ≥70%، صفر تحذيرات مترجم، enforcer، فحص Modulith/ArchUnit، gitleaks. آخر بوابة خضراء مسجلة: `mvn clean verify` كامل الـ Reactor — 538 اختبار وحدة / 0 فشل (سجل PROJECT_MAP 2026-08-29، زمن 96037ef؛ الأعداد تنمو مع الدفعات).

---

## 10. خريطة التعمق — لكل طبقة: نقطة الدخول، ترتيب القراءة، المصادر المخبأة

| الطبقة | ابدأ من | ثم اقرأ | المصادر الرسمية/المصدرية المخبأة محلياً |
|---|---|---|---|
| البناء (Maven) | `pom.xml` (كاملاً) | poms الوحدات (patterns التكرارية) | `scripts/doc-verify/maven-lifecycle.html` |
| الإقلاع (Boot) | `MarketplaceApplication.java` | `application.yml` كاملاً | `scripts/doc-verify/{spring-application,auto-configuration}.html` + مصادر Boot 4.1.1 في `scripts/boot-as-verify/boot-as-src/` |
| الوحدات (Modulith) | `package-info.java` للوحدة المستهدفة | `marketplace-shared/.../api/` (SPIs + الأحداث) | `scripts/modulith-events.html` |
| الأمن (SAS) | `SecurityConfig.java` | `R__seed_oauth2_client.sql` → `V13` → `application-prod.yml:127-131` | مصادر SAS 7.1.1 في `scripts/verify-aud-claim/sas-all/` + وثائق `scripts/auth-design/` (sas-howto-pkce, core-model-components, boot-security…) + `scripts/doc-verify/{sas-model,sas-pkce}.html` |
| البيانات | `db/migration/` بالترتيب العددي | كيان الوحدة + `RevisionService` + `CacheInvalidationRelay` | `scripts/doc-verify/flyway*.html` + `postgres*.html` |
| الإعدادات | `MarketplaceProperties.java` | ymls الثلاثة + `application-test.yml` | `scripts/auth-design/boot-external-config.txt` |
| CI | `.github/workflows/ci.yml` | `integration-test.yml` | — |
| الجودة/الأحداث | اختبارات الوحدة المستهدفة | `@ApplicationModuleListener` في الوحدة | `scripts/modulith-check/` |

**طقوس التعمق (تعلمت بالتجربة المسجلة في worklog):** (1) كل اقتباس رسمي يُنزَّل ويُطابق نصياً لا من الذاكرة؛ (2) المفاتيح المركبة قد لا تظهر في grep لأنها تُبنى concat (`ConfigurationSettingNames`)؛ (3) قراءة بايت-كود/مصادر الجرة عند غموض المصدر المتاح؛ (4) «خارج المتناول» ادعاء يُختبر قبل تصديقه.

---

## 11. الحالة الحالية للخطة الحاكمة (auth redesign)

المستند الحاكم: `docs/security/auth-system-redesign-plan.md` (عربي، 149 سطراً — مراحل 0-4، F-A/F-B/F-C، D1-D9، INV-1..7، سلّم T1-T4). الوضع على main:

- **خطة حاكمة تالية معتمدة ✅ (2026-09-03، أمر المستخدم «اعتمدها»):** `docs/security/client-hosting-strategy-plan.md` — الباك اند مرساة؛ تقنيات العملاء (فلاتر/Next.js/أي لغة) ووجهة الاستضافة بوابات قرار مفتوحة (B ثم C)، لا تُفترض. D9 أُعيد تأطيره متعدد العملاء (مكان السر لا اللغة — اقتباس SAS الحرفي + توصية BFF). صفر كود/اعتماديات لهذه الخطة.
- **نقل الخطة للمستودع — PR #187 ✅ مدمج (2026-09-03، `922b5bf`؛ وبعده كوميت الحوكمة الإجباري §0 `ff9d9cd` بأمر «اجعله جبرياً»):** فرع `docs/client-hosting-strategy-plan`: الخطة داخل `docs/security/` + إحالات جراحية (auth-system-redesign-plan / PROJECT_MAP / هذا الملف §11) + **نظام الحوكمة §14** (أُمر به بعد الاعتماد: «حدِّث الخريطة المرجعية وأضف نظام الحوكمة») — ماركداون فقط، لا كود ولا سلوك؛ CI + Integration أخضر ×2 قبل الدمج. البوابات اللاحقة: B ثم C (+ A الاختيارية) — آلياتها في §14.2.
- **المرحلة A — جاهزية مضيفة-محايدة ✅ مدمجة (2026-09-03، أمر «أبدأ A»؛ PR #188 → `4c0455f`، CI أخضر ×2):** `server.forward-headers-strategy: FRAMEWORK` في `application-prod.yml` **فقط** (حد الثقة الرسمي: dev/test تبقى NONE) + `server.tomcat.redirect-context-root: false` (وصفة الجافادوك الرسمية) + **اكتشافا التدقيق النهائي لبروفايل prod أُغلقَا بنفس الدفعة**: (1) ربط `AUTH_SERVER_ISSUER` بلا افتراض في prod (كان افتراض `http://localhost:8080` من application.yml يتسرب للإنتاج) (2) إصلاح عيب صياغة YAML كامن في نمط تعقيم السجلات (`\s` غير مُهرَّبة داخل سلسلة مقتبسة مزدوجة — الملف كان غير قابل للتحليل أصلاً ولم يُكتشف لأن بروفايل prod لم يُنشَّط قط). الحارسان: `ForwardHeadersProdConfigTest` (الإعدادات) + `ForwardedHeaderFilterBehaviorTest` (سلوك الفلتر الرسمي) — 6/6 محليًا. الأساس الرسمي محفوظ: `scripts/prod-design-docs/` (صفحة how-to/webserver 4.1 + مرجع SS 7.1.1 exploits/http + مصادر Boot 4.1.1 وspring-web 7.0.9 من Maven Central + قضية #42804).

- **بوابة B — العميلان الأولان ✅ مدمجة (2026-09-03، أمر المستخدم: النمطان معًا «فلاتر عام+PKCE وBFF سري»؛ PR #189 → `c52cef8`، CI أخضر ×2):** فرع `feat/phase-b-public-pkce-client`: العميل العام (`OAuth2PublicClientInitializer` — `none`/PKCE إلزامي/بلا refresh/redirect من env) + توسيع السري بـ `OAUTH_CLIENT_REDIRECT_URIS` (fail-fast في prod — يغلق دين «redirect الإنتاج مثبَّت على ثابت التطوير»). 17+59 اختبار وحدة + 12 تكامل حية خضراء محليًا (بوابة الدخول 6/6 + بوابة PKCE العامة 6/6). التفاصيل: خطة العملاء §7-B + المواصفة §4.4-هـ/و + §6 أعلاه.

- **المرحلة 0 ✅** (`f80b945`): التوثيق والقرارات.
- **المرحلة 1 ✅** (`5b5a238`): إغلاق F-A بالخنقتين المعتمدتين (§6 أعلاه) + سجل PROJECT_MAP كامل.
- **المرحلة 2 ✅** (PR #184 → مدمج `773558e` 2026-09-02، CI أخضر ×2؛ **المتابعة الجراحية #185 → مدمجة `1ebdf07` 2026-09-03، CI أخضر ×2**: إزالة `clientSecretExpiresAt(null)` الميتة + تأكيد id_token الحي في consent gate): F-B مغلقة بالمعنى المقصود — S1–S6 تمرّن على **صف المُهيّئ الفعلي** (`@TestPropertySource` + `ApplicationRunner` يأسّس أثناء الإقلاع، لا عميل مُصنّع في كود الاختبار): قراءة الخريطة الكاملة (8 مفاتيح)، PKCE مفروض على الصف الفعلي، consent موجب/سالب بمستخدمين مختلفين، القيم الظرفية، convergence بصف قديم مرضوض. المواصفة: `docs/security/oauth2-client-bootstrap-spec.md`.
- **المرحلة 4 ✅ (PR #186 → مدمج `6fdec07` 2026-09-03، CI أخضر ×2):** D6 منفّذ: حارس fail-fast مقيد بـ prod في `jwkSource` (نمط المُهيّئ الرسمي نفسه) + فحص CI `JwkSourceProdHardeningTest` (تخفيف سجل مخاطر §8) + runbook `keys/README.md` + تصحيح دين ARCHITECTURE الموثقي (`RotatingJWKSource` الوهمية في 4 مواضع → ADR-004 Revised يسجل التصميم المنفّذ بصدق). (كان #186 مكدّسًا فوق #185؛ فُكّ التكدّس بـ rebase بعد squash #185 — الفرق متحقق التطابق بايتًا-بايت، CI أعاد التحقق قبل الدمج.)
- **المفتوح (بقرار D9 فقط):** المرحلة 3 (عميل مستهلك بموجب D9=(أ) confidential/BFF، يغلق F-C — والتوصية الرسمية المحررة حرفياً في دليل SAS «How-to: SPA with PKCE»: «We recommend the backend for frontend (BFF) pattern»). سلوك PKCE **ساكن حتى يولد مستهلك** في المرحلة 3.
- **مُغلق حسمه:** D4 (consent=true قائم عبر باني المُهيّئ — لا بذرة بعد الآن)، D2 (خصائص AS الذاكرية خاملة).
- **مُهيّئ سرّ العميل env→DB ✅** (PR #183 → `c16e750`، CI أخضر ×2): `OAuth2ClientSecretInitializer` (`ApplicationRunner`) + `MarketplaceProperties.Security.OAuth2.Client` (client-id/secret بـ `@DefaultValue` فارغة — اقتباس الربط الرسمي) + fail-fast مقيد بـ prod + `application-prod.yml` يربط `OAUTH_CLIENT_ID/SECRET` بلا افتراضات. جولة `save()` تحفظ الإعدادات بلا انجراف (INV-4 مثبت: `RegisteredClient.java:302-327` ينقل الخرائط نفسها عبر `withSettings`).
- **تأسيس كامل على المسار الرسمي ✅ (PR #184 → مدمج `773558e`، CI أخضر ×2):** المُهيّئ نفسه يتولى الآن **التأسيس** (لا التدوير فقط): غائب ⇒ `withId(a7bd8b0d-…)` + التعريف الكامل (grants/redirect/scopes/name/auth-methods — نقل SQL→Java بند مستقل في PR) + باني الإعدادات؛ converge-on-boot للهوية فقط؛ حارس save موسّع (سرّ ∨ خرائط). `R__seed_oauth2_client.sql` أُخلي من العميل (admin باقٍ) — يغلق الدين الأمني (سرّ مضمن) والفجوة الحية (id-token). مرجع: `docs/security/oauth2-client-bootstrap-spec.md` §4.1/§4.2/§4.4 (سجل الانحرافات).
- **بند عمليات مُغلق ✅ (2026-09-04):** `SPRING_PROFILES_ACTIVE=prod` مضبوط الآن على خدمة Railway (مع خانات `JWT_KEYSTORE_*` الأربع وسر webhook المدفوعات) — كل نشر حي منذ `c82d9831` يقلع ببروفايل prod («The following 1 profile is active: "prod"»)؛ الحاجز صار فعليًا على منصة الإنتاج. السجل: §15.

---

## 12. حقائق مؤكدة مسبقاً — لا تُدرس من جديد، فقط استشهد بها

1. `save()` عند JDBC: upsert بالـ PK `id` لا بـ client_id (`:147-156`).
2. `ClientSettings.withSettings` بلا افتراضات + `isRequireProofKey` null-safe (غياب المفتاح = false).
3. مدقق PKCE: غياب التحدي + requireProofKey ⇒ `invalid_request` (RFC 7636 §4.4.1)؛ S256 فقط عند وجوده.
4. عدم تناظر التعويض: TokenSettings يُعوَّض افتراضياً عند القراءة، ClientSettings لا.
5. Jackson: `UnmodifiableMap` مسموح، `List12` مرفوض (PolymorphicTypeValidator) — صيغة `@class` في البذرة مشروعة.
6. R__ يعاد تطبيقه عند تغيّر checksum، وترتيبه بعد كل V.
7. `EXCLUDED` في PostgreSQL = الصف المقترح للإدراج.
8. خصائص `spring.security.oauth2.authorizationserver.client.*` خاملة عندنا (D2).
9. اختبارات السياق الكامل تحتاج Redis حياً (خزين الجلسات) — CI يوفره services.
10. كل اقتباسات الخطة الأربع (Flyway/PostgreSQL/SAS/RFC) مُطابَقة نصياً 9/9 (`scripts/verify_docs_quotes.py`).
11. `PublicClientAuthenticationConverter` (منذ 7.0) يطابق **طلب PKCE فقط** في نقطة التوكن (authorization_code+code+code_verifier) ويوثّق العميل العام عبر code_verifier نفسه — طلب refresh_token (بلا code_verifier بحكم البروتوكول) يبقى مجهولاً (مصدر 7.1.1 المخبأ + TRACE حي بوابة B).
12. فلاتر نهايات AS تعمل **بعد** `AuthorizationFilter` في السلسلة (ترتيب 7.1.1 المرصود: AuthorizationFilter 21/26 ثم نهايات authorize/token 22-26) — طلب توكن بلا مصادقة عميل يُرفض مجهولاً قبل وصوله للمنحة.
13. `OAuth2AuthorizationServerConfigurer` يسجّل `HttpStatusEntryPoint(UNAUTHORIZED)` لنقاط AS (بايت-كود spring-security-config 7.1.1: `defaultAuthenticationEntryPointFor`) — رفض طلب refresh من عميل عام = **401 بجسم فارغ** (موثّق باختبار حي).


---

## 13. بروتوكول صيانة هذا الملف

- **متى يُحدَّث:** عند كل دفعة تغيّر آلية (لا مجرد حالة — تلك لـ PROJECT_MAP)، أو عند تحقق جديد يعدّل حقلاً هنا. التحديث يرافق نفس دفعة التغيير إن أمكن.
- **كيف:** الحقيقة + دليلها معاً؛ يُشار للـ commit عند كون الحقيقة زمنية (كما في §11).
- **من:** المستخدم ينفّذ git (دفع مباشر أو PR)؛ المساعد يقترح التعديل موثقاً ولا يدفع بنفسه إلا بإذن صريح (قاعدة الجلسات الدائمة).
- **اقرأ قبل أي مهمة في هذا المستودع:** هذا الملف → `PROJECT_MAP.md` (الحالة) → `AGENTS.md` (القواعد) → ثم الشجرة المستهدفة من §10.

---

## 14. نظام الحوكمة — من يقرر ماذا، وعلى أي دليل (أُضيف بأمر المستخدم 2026-09-03)

طبقتان لا تُخلطان: **حوكمة طريقة العمل** (كيف تُتخذ القرارات وتُنفَّذ وتُوثَّق — معمول بها منذ بداية الجلسات) و**حوكمة الخطة الحاكمة** (كيف تُضبط القرارات المعمارية المفتوحة — أُسست مع اعتماد خطة العملاء والاستضافة).

### 14.1 حوكمة طريقة العمل — القواعد الدائمة

| القاعدة | المعنى العملي | الدليل |
|---|---|---|
| الوثائق الرسمية مصدر الحقيقة | أي مساس بـ Boot 4.1.1 / Maven / SAS 7.1.1 يستند إلى مستند رسمي محفوظ أو كود منفَّذ مختبر — لا اجتهاد ولا «معلومة شائعة» | §10 (خريطة المصادر المخبأة) + §12 البند 10 (مطابقة اقتباسات نصية 9/9) |
| لا ديون مخفية | كل فجوة تُصرَّح كتابةً وتحمل نقطة إغلاق محددة — لا ترقيع صامت؛ القائمة الحية معلنة في المستند الحاكم لا في الذاكرة | خطة العملاء §6 (دين forward-headers: يُغلق عند النشر) + §8 البند 15 (افتراض SPA المنتهي: يُقفل بـ PR عند حسم بوابة C) |
| جسم PR صادق | يذكر ما نُفِّذ وما تُرك وما لا يمسه التغيير؛ والانحراف عن القاعدة يُسجَّل صراحةً لا يُدفن | أجسام #183–#187 (كل واحد يحمل قسم تحقق وقسم «ما لم يُلمس») |
| القرار للمستخدم حصراً | الدمج والحذف وبدء أي مرحلة بأمر صريح («ادمج»، «اعتمدها»)؛ المساعد ينفِّذ ويوثِّق ولا يفتح مرحلة أحادياً | §13 (من) + سجل أوامر المستخدم في PROJECT_MAP |
| تحقق قبل الدفع وبعده | `./mvnw clean verify -pl <module> -am` قبل أي دفع (قاعدة AGENTS.md)؛ CI سلطة الحكم بعد الدفع | §3 (الأوامر المعتمدة) + §9 (بوابات الجودة) |
| الحذف مدمر ⇒ يُوثَّق قبله | قبل أي حذف: إثبات أن كل المحذوف مدموج أو محفوظ في حزمة أمان قابلة للاستعادة | حزمة 2026-09-03 (9 مراجع كاملة — خارج المستودع؛ قبل تنظيف الفروع) |

### 14.2 حوكمة الخطة الحاكمة — آليات خطة العملاء والاستضافة

المستند: `docs/security/client-hosting-strategy-plan.md` (معتمدة بأمر «اعتمدها»). آلياتها الضابطة:

- **مصفوفة الاتساق (§8 من الخطة):** 15 بنداً كل بند مصنَّف إلزامياً — 13 مستندة رسمياً (بينها دين موثق واحد: البند 10) + بوابة قرار واحدة (البند 12) + افتراض منتهي الصلاحية (البند 15). لا يُقبل بند «غير معروف الحالة».
- **بوابات الإذن (§7 من الخطة):** المرحلة A (قفل دين forward-headers — اختيارية) / B (عميل أول) / C (نشر) — لا تبدأ أي منها إلا بكلمة المستخدم.
- **ترتيب القرارات (§6 من الخطة):** نمط العميل (بوابة B) يسبق جهة الاستضافة (بوابة C) — الاستضافة تتبع العميل منطقياً (BFF يحتاج Node؛ فلاتر لا يحتاج استضافة ويب؛ SPA يحتاج ملفات ثابتة).
- **مبدأ التصنيف (§3-§4 من الخطة):** العملاء يصنَّفون بمكان السر لا باللغة (اقتباس SAS الحرفي) — يصلح لأي لغة/إطار مستقبلاً بلا إعادة تصميم للباك اند.
- **المرجعية للأمام:** أي قرار لاحق يمس العملاء أو الاستضافة يُقاس على هذا المستند الحاكم، لا يُتخذ خارجه.

### 14.3 أعراف الفروع والدمج

- **نمط الدمج الحاكم:** squash برسالة = عنوان PR + `(#رقم)` + جسم يمثل كوميتات الفرع — بعد تحقق مسبق إلزامي: PR مفتوح + `mergeable=clean` + كل الفحوص success. سلطة الحسم: CI على الرأس قبل الدمج وعلى main بعده.
- **حذف رأس الفرع تلقائياً بعد الدمج** مفعَّل في إعدادات المستودع (2026-09-03) — البعيد يحمل `main` فقط منذ تنظيف ذلك اليوم.
- **درس التكدُّس المسجَّل:** PR مكدَّس فوق فرع دُمج squash يظهر dirty رغم تطابق المحتوى — يُفكّ بـ `git rebase --onto` مع إثبات تطابق الفرق بايتاً-بايت، ثم يعيد CI التحقق على الرأس المفكوك قبل الدمج (مُطبَّق على #186 — سجل PROJECT_MAP).
- **الاستثناء المحلي الموثَّق:** فرع `feat/redis-listener-pubsub` (عمل غير مدموج — مقاربة P3 قديمة) لا يُحذف إلا بأمر صريح.

---

## 15. سجل نشر الإنتاج — Railway (حالة حية، آخر تحقق 2026-09-04)

> الوثيقة المرجعية الكاملة للنشر: `docs/railway-deployment-reference.md` (رأسه حُدّث 2026-09-04) + تقرير الجلسة الشامل بالأدلة (خارج المستودع: مساحة عمل الجلسة `download/railway-deployment-doc-2026-09.md`). هذا القسم: الحقائق الحية التي تُقرأ قبل أي قرار يمس الإنتاج.

- **الإنتاج حي (آخر تحقق 2026-09-04، دفعة truth-sync ثانية):** `https://app-java-v3-production-d020.up.railway.app` — نشر نشط **`e38736b6` (SUCCESS) من main `c072cb9` (#206 truth-sync — شجرة تشغيل متطابقة بايتاً لـ `6a47e066`: `.dockerignore` يستثني `*.md`)** عبر fork النشر المتزامن حرفياً (دفع المستخدم بيده 2026-09-04 16:20:51Z — بوابة دفع #206 أُغلقت؛ `check_deploy_fork` = EXACTLY synced — تحقق حي). بروفايل **prod** مفعّل، إقلاع 11.119s، PostgreSQL 18.6 + Redis 8.2 على الشبكة الداخلية (طبقة البيانات أدناه). liveness/readiness 200 UP، jwks بمفتاح RSA دائم `kid=marketplace-jwt`، OIDC discovery 200 (تحقق ثلاثي 2026-09-04 بعد النشر؛ فحص health الكامل = DOWN بعنصر البريد الموثق — البند 3 من الديون). **دليل إقلاع e38736b6 (سجلات النشرة عبر GraphQL v2):** `Schema "public" is up to date. No migration necessary.` (صفر ترحيلات جديدة — صحيح: الفرق توثيقي فقط) + **صفر أسطر ERROR في الإقلاع** (وصفر أخطاء أرشفة — V28 مستمر). **دليل #205 (من نشر `3e8de67f` الذي طبّق الموجة الثالثة):** `Migrating schema "public" to version "29 - remove dead search matview"` + `"30 - envers revision sequence"` + `Successfully applied 2 migrations … now at version v30` + إقلاع 11.537s. **فحص دخان حي للميزة (#205):** `GET /api/v1/search?q=cleaning (deep) -iron` (المدخل الذي كان يكسر `to_tsquery` الخام ← 500) = **200**؛ عبارات مقتبسة و`OR` و`-استثناء` (دلالات `websearch_to_tsquery`) كلها 200. **سلسلة النشور الحاملة لـ main:** `c82d9831` (27b5a155) ← فشل `bf2b0acd` (41eeb05 — أُصلح جذره في #201) ← `6c814e28` (349052b) ← `442c3665` (قطع طبقة البيانات) ← `51b5496d` (707e052 = #202 IaC) ← `80171be7` (f9c5d34 = #203 V28) ← `d41ed3fe` (f988c08 = #204 truth-sync — شجرة تشغيل متطابقة بايتاً) ← `3e8de67f` (6a47e066 = #205) ← **`e38736b6` (c072cb9 = #206 truth-sync — شجرة تشغيل متطابقة بايتاً) — النشط المسجَّل**؛ **سياسة التقارب الموثقة:** أي إعادة بناء لاحقة بفرق توثيقي فقط (`*.md` يستثنيها `.dockerignore` ⇒ شجرة تشغيل متطابقة) لا تُسجَّل كنشرة جديدة — تُستأنف السلسلة عند أول نشر كودي تالٍ.
- **طبقة البيانات (التصميم الرسمي الكامل 2026-09-04):** postgres على خدمة **`postgres-18`** (قالب postgres-ssl:18 الرسمي + فوليوم 84MB + متغيرات القالب حرفياً + إعادة استخدام بيانات اعتماد marketplace) — نقلت بالمسار الرسمي (pg_dump -Fc → pg_restore --clean -j 4 + ANALYZE + تحقق الأعداد): **57/57 جدولاً صفر فرق**؛ redis رُقيت في مكانها إلى 8.2 (فوليوم 32MB + requirepass + RDB save 60 1). ~~الخدمة القديمة `postgres-17` تعمل كنافذة استرجاع~~ ✅ **حذفها المستخدم بيده (تحقق حي 2026-09-04 — أصبحت الخدمات 4 بعد 5)**؛ ~~`netdiag` ناقلة النقل بانتظار التنظيف~~ ✅ **حُذفت بأمره عبر التحويل الرسمي `serviceDelete` (المخطط الحي: «Deletes a service»)** — قبل الحذف تيقّن: **صفر فوليومات لها** (فوليومات البيئة = redis/app/postgres-18 حصراً)، متغيراتها الثلاثة (PGUSER/PGPASSWORD/PGDATABASE) بيانات نقل نسخها موجودة على التطبيق وpostgres-18 (حُذفت مع الخدمة)، صفر مراجع إليها (إقلاع التطبيق يقرأ postgres-18.railway.internal)، نشوراتها الخمسة كلها أوامر لمرة واحدة؛ بعده: الخدمات **3** + الفوليومات الثلاثة READY + الفحص الثلاثي أخضر (الأدلة: `netdiag-cleanup-evidence.json`).
- **آلية البناء الحاكمة (تصميم #199 + إغلاق CaC في #202):** إعدادات **خدمة-مستوى عبر IaC `.railway/railway.ts`** (`railway config apply`: builder=DOCKERFILE + healthcheckPath=`/actuator/health/liveness` + timeout=300؛ المتغيرات محفوظة بـ preserve() الرسمية) — المستودع بلا ملفات CaC (أُزيل railway.toml؛ ترحيل IaC نُفّذ قبل الموعد النهائي 2026-11-15 بشهرين). الدليل الفعلي أن البناء Dockerfile: مراحل سجل البناء الأربعة ([build 4/4] بذاكرة ك.cache s/4fbac104 + [trainer 8/8] AOT) في نشرة من مستودع بلا railway.toml. Dockerfile **رباعي المراحل**: build (temurin 25-jdk + Railway cache mount لـ `~/.m2` بالصيغة الرسمية `id=s/<service-id>-<path>`) / extractor (طبقات `jarmode=tools` الرسمية) / **trainer (تدريب AOT cache لـ Java 25+ بوصفة Boot 4.1 الرسمية: `exit=onRefresh` + وصفات lifecycle لكل اعتمادية + `-Dmanagement.opentelemetry.map-environment-variables=false` — إغلاق جذري لفشل bf2b0acd حيث حقنت بيئة Railway متغيرات OTEL بمقبس unix://)** / runtime (temurin 25-jre، مستخدم غير جذري `app`). ENTRYPOINT خالص `"java", "-jar", "app.jar"`.
- **جسر JVM↔المنصة (تصميم #199):** `application.yml` يربط `server.port: ${PORT:8080}` («Railway will inject a PORT environment variable that your application should listen on» — آلية placeholder الموثقة)؛ خيارات JVM عبر `JDK_JAVA_OPTIONS` (متغير مشغّل `java` الرسمي — صفحة الدليل JDK 25) = `-XX:AOTCache=/app/app.aot -XX:MaxRAMPercentage=60.0 -XX:+ExitOnOutOfMemoryError` (G1 الافتراضي + AOT cache المقيس محليًا: مسار إقلاع −38%) مقابل سقف 953MiB — الذاكرة المستقرة المقيسة ~0.56GB.
- **قناة الأسرار (تصميم #199 — لم يعد للـ ENTRYPOINT أي دور):** keystore عبر متغير write-only `JWT_KEYSTORE_B64` **تفكّه `SecurityConfig.jwkSource` في الذاكرة** (`KeyStore.load` فوق `ByteArrayInputStream` — لا يُكتب ملف إطلاقًا؛ b64 تتقدم عند توفر القناتين؛ بيانات ناقصة بأي قناة = fail-fast في كل بروفايل — حارس `JwkSourceProdHardeningTest` بست حالات). **دوران المفتاح = تحديث متغير** (الوصفة: `keys/README.md §3` — القناتان، الموعد النهائي `ROTATION_DEADLINE=2026-12-02`).
- **المتغيرات على الخدمة:** 25 (أسماؤها فقط قابلة للقراءة عبر API — القيم write-only؛ الأسرار محفوظة خارج المستودع في مساحة عمل الجلسة، `scripts/secrets/prod-secrets.env` بتصاريح 600).
- **محددان بيئيان حاكمان (اقتباسا الوثائق الرسمية):** الفوليومات تُركب root-owned؛ وأوامر pre-deploy في حاوية منفصلة لا تكتب شيئًا يبقى — لذلك آلية B64 هي القناة الصحيحة لهذا التخطيط.
- **ديون معلنة بنقاط إغلاق (لا ترقيع صامت):**
  1. ~~**إهمال Config-as-Code بصلاحية 2026-12-01**~~ ✅ **مغلق 2026-09-04 (#202):** الترحيل إلى IaC `.railway/railway.ts` نُفّذ وتم التحقق منه نهاية-إلى-نهاية (نشرة 51b5496d من مستودع بلا ملفات CaC) — قبل الموعد النهائي بشهرين.
  2. ~~قسم `[deploy]` الملفي لا ينعكس في manifests~~ ✅ **مغلق (#202):** healthcheckPath + timeout=300 مطبّقان كإعدادات خدمة-مستوى الآن.
  3. `MAIL_*` placeholders (503 على فحص health الكامل فقط) و`OTEL_*` localhost (بلا collector) — بانتظار اختيار المزوّد. (خريطة متغيرات بيئة OTEL في مرحلة التدريب أُغلقت جذرياً في #201.)
  4. ~~مخلفات: فوليوم `/data` غير مستخدم~~ — **نُقل إلى فوليوم طبقة البيانات (region ams/sizeMB 500) عبر IaC (#202)**؛ ~~خدمة `netdiag` (ناقلة النقل بانتظار التنظيف)~~ ✅ **مغلق 2026-09-04:** حُذفت بأمر المستخدم عبر `serviceDelete` الرسمي بعد تحقق أمان كامل (صفر فوليومات، صفر مراجع، متغيرات نقل نسخها قائمة) — البنية الآن 3 خدمات نظيفة.
  5. ~~**الخدمة القديمة `postgres-17`** (نافذة استرجاع تحمل البيانات الأصلية)~~ ✅ **مغلق 2026-09-04:** حذفها المستخدم بيده (تحقق حي: الخدمات 4←3 بعد netdiag) — نافذة الاسترجاع أُغلقت بقراره؛ الإنتاج يعمل على `postgres-18` (نقل 57/57 متحقق) والفحص الثلاثي أخضر بعدها. حُرر حدّا الخطة/الخدمات.
  6. **النسخ الاحتياطية للفوليومات** — `volumeInstanceBackupCreate` محجوبة بالتوكن/الخطة (بوابة خطة/UI بيد المستخدم).
  7. ~~دين `event_publication_archive`~~ ✅ **مغلق ونشر ومتحقق حياً (#203/V28 — نشر `80171be7`):** خطأ إنتاج حي «relation "event_publication_archive" does not exist» في كل إقلاع منذ أول نشر (نشِط آخر مرة في 51b5496d) — الجذر: وضع الإكمال archive بلا جدول في ترحيلات Flyway (بروفايل test كان يحجبه بـ create-drop). الإغلاق: ترحيلة V28 بالمخطط الرسمي + حارس `EventPublicationArchiveIntegrationTest`؛ **التحقق الحي في نشر `80171be7`**: Flyway طبّق V28 (سجل الإقلاع: «Migrating schema "public" to version "28 - modulith event archive"» + «Successfully applied 1 migration, now at version v28») وصفر أخطاء أرشفة في الإقلاع (مقابل سطرين في كل إقلاع سابق) — المنشورات المعلّقة عالجها `republish-outstanding-events-on-restart` (المستمعات idempotent: توليد الخانات يتخطى الموجود والإلغاء يفلتر PENDING).
  8. دوران مفتاح JWT قبل **2026-12-02** + **تدوير التوكنين** (GitHub fork PAT وRailway token — كلاهما ظهر نصاً في المحادثة).
- **علاقة خطة الاستضافة:** الإنتاج الحي على Railway لا يُغلق بوابة C (خطة العملاء §7) — الاستضافة القادمة للعملاء قرار مستقل موثق هناك؛ ARCHITECTURE.md §5 يبقى خط CF المستقبلي.
- **حوكمة النشر:** أي إعادة نشر تمر عبر fork (`waelhe88-coder/app-java-v3` — يُدفع ببيانات اعتماد يقدمها المستخدم عند التنفيذ، ولا تُخزّن)؛ CI سلطة الحكم على كل رأس قبل الدمج؛ فحص ما بعد النشر: liveness + jwks + OIDC discovery (read-only عبر curl) + فحص سجلات النشرة (GraphQL API).

