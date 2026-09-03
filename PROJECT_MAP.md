# PROJECT_MAP — Marketplace Backend (app-java-v3)

## Phase A — Host-Neutral Readiness (2026-09-03) 🏗️ ✅ IMPLEMENTED (awaiting merge word)

**Order:** «أبدأ A» — مع توجيه صريح: تصميم رسمي من الوثائق لا اجتهاد، لا ترقيعات ولا ديون. القيد: `docs/security/client-hosting-strategy-plan.md` §7/§8 البند 10.

**التنفيذ (فرع واحد، سلوك prod فقط، صفر وحدات/اعتماديات):**
- `server.forward-headers-strategy: FRAMEWORK` في `application-prod.yml` **فقط** — قرار مسند كليًا من المصادر الرسمية: how-to/webserver 4.1 («If this is not enough … FRAMEWORK») + مرجع SS 7.1.1 exploits/http («Do not assume that only one of them is in use» — ForwardedHeaderFilter يعالج RFC 7239 + X-Forwarded-* كليهما) + قضية Boot #42804 (مغلقة duplicate/external: NATIVE لا يحترم X-Forwarded-Port) + مصدر spring-web 7.0.9 (المنفذ الغائب يُشتق 443/80 حسب scheme) + مصدر Boot 4.1.1 (التسجيل بترتيب `Ordered.HIGHEST_PRECEDENCE` لكل REQUEST/ASYNC/ERROR — قبل سلاسل Security). dev/test تبقى NONE (حد الثقة: خلف بروكسي موثوق فقط).
- `server.tomcat.redirect-context-root: false` — وصفة الجافادوك الرسمية حرفيًا: «When using SSL terminated at a proxy, this property should be set to false.»
- **اكتشافا التدقيق النهائي لبروفايل prod (أُغلقَا بنفس الدفعة — لا ديون متروكة):**
  1. `spring.security.oauth2.authorizationserver.issuer: ${AUTH_SERVER_ISSUER}` بلا افتراض في prod — كان افتراض `http://localhost:8080` من `application.yml:81` يتسرب للإنتاج (نفس عقد fail-fast لـ DB_PASSWORD/JWT_KEYSTORE_*/CORS_ALLOWED_ORIGINS).
  2. إصلاح عيب YAML كامن في `logging.pattern.console` بـ prod: `\s` غير مُهرَّبة داخل سلسلة مقتبسة مزدوجة جعلت الملف غير قابل للتحليل (اكتشفه اختبار الحارس الجديد — لم يظهر قط لأن بروفايل prod لم يُنشَّط في أي بيئة، اتساقًا مع الدين التشغيلي الموثق).
- **الحارسان (marketplace-app):** `ForwardHeadersProdConfigTest` (3: prod يفعّل FRAMEWORK + يطفئ redirect-context-root؛ الأساس/dev/test بلا استراتيجية — حد الثقة؛ issuer بلا افتراض) + `ForwardedHeaderFilterBehaviorTest` (3: الفلتر الرسمي يكرّم Proto/Host/Port ويزيل الترويسات؛ المنفذ الغائب → 443؛ بلا ترويسات → لا تغيير). **6/0 أخضر محليًا** (JDK 25.0.4.1؛ بلا سياق Spring — لا يحتاجان Redis).
- **مصادر رسمية مُنزَّلة ومحفوظة:** `scripts/prod-design-docs/` — صفحة how-to/webserver (Boot 4.1) + مرجع SS 7.1.1 exploits/http + مصادر Maven Central الرسمية (spring-boot 4.1.1 + وحداتها web-server/tomcat + spring-web 7.0.9 + spring-security-web 7.1.1) تحت `src-verify/`.
- **مزامنة الدفعة:** الخطة الحاكمة (§6 صفان + §7 صف المرحلة A + §8 البند 10 ⚠️→✅ + §10 ثلاثة اقتباسات جديدة) + SYSTEM.md §11 (بند المرحلة A) + هذا القسم.

**بانتظار كلمة الدمج** (الحوكمة: الدمج بأمر صريح). ما بعد الدمج: البوابات B (تقنية العميل) ثم C (الاستضافة) — بيد المستخدم.

## PR #184 — OAuth2 client bootstrap on the official path (2026-09-02) 🏗️ ✅ MERGED (`773558e`)

**Branch:** `feat/jacoco-70pct-coverage-v5` — قيدها المواصفة المعتمدة: `docs/security/oauth2-client-bootstrap-spec.md`. **PR:** https://github.com/waelhe/app-java-v3/pull/184 — مدمج

**التنفيذ والتحقق أكملاه أخضر محلياً (PR دُفع، ينتظر CI):**
- `OAuth2ClientSecretInitializer` أعيد تصميمه (المرجعية §4.1):
  - **تأسيس** (غائب ⇒ `RegisteredClient.withId(CLIENT_ID)` + التعريف الكامل + باني `ClientSettings`/`TokenSettings`) — المسار الرسمي الوحيد `save(RegisteredClient)`.
  - **converge-on-boot** (`from(existing)` للهوية فقط + باني الإعدادات — لا نقل خريطة مرضوضة).
  - **حارس idempotence موسّع**: `save` ⇔ السرّ اختلف ∨ خرائط الإعدادات اختلفت.
  - القيم الظرفية مثبتة صراحةً: reuse=false / 900s / 604800s / 300s / consent=true / proof-key=true؛ يعمل encode عند الحاجة فقط.
- `R__seed_oauth2_client.sql` — **أُخلي العميل كاملاً** (`oauth2_registered_client`)، أُبقي admin `auth_users`/`auth_authorities`، وتوثيق السبب في رأس الملف (تحرير repeatable موثق).
- اختبارات: `OAuth2ClientSecretInitializerTest` (6→**7**) + `AuthorizationServerLoginGateIntegrationTest` (**S2/S3/S4** عبر `@TestPropertySource` + صف المُهيّئ الفعلي، consent رسمي) — **6/0 أخضر بأثر حي** (openid→consent→code→id-token كامل).
- **Verify محلي:** `marketplace-platform-infra` **46/0** (الغطاء met) + `marketplace-app` **46/0** — BUILD SUCCESS للمودوليْن.
- **المواصفة §4.4:** سجل الانحرافات/التصحيحات (أ/ب/ج/د) —كل تصحيح بدليل من بايت-كود 7.1.1 أو التشغيل.

**مدمج (2026-09-02):** PR #184 → main `773558e` (CI أخضر ×2) — التأسيس الموحّد على المسار الرسمي منفّذ كما راجعته المراجعة الحرفية (7 ملفات +741/−98؛ R__ أُخلي من العميل نهائياً). المتابعة الجراحية: PR #185 (استورد يتيم + javadoc + تأكيد id_token الحي في consent gate + §3-ج) — **مدمجة لاحقًا** (انظر القسم التالي). مزامنة §13 مرفقة بنفس الدفعة.

## PR #185 + #186 — المتابعة الجراحية + المرحلة 4 (D6) (2026-09-03) 🏗️ ✅ MERGED (`1ebdf07` / `6fdec07`)

**مدمج (2026-09-03):**
- **PR #185** → main `1ebdf07` (CI + Integration أخضر ×2 على الرأس وعلى main): إزالة `clientSecretExpiresAt(null)` الميتة (مُثبتة من بايت-كود 7.1.1 + V13:23) + استورد يتيم + javadoc + **تأكيد id_token الحي** في consent gate (non-blank، 3-part JWT، iss/aud/sub) + §3-ج بالمواصفة. Documentation+tests only — لا كود إنتاجي غير التهذيف.
- **PR #186** → main `6fdec07` (CI + Integration أخضر ×2): **D6/المرحلة 4 مغلقة** — حارس prod fail-fast في `jwkSource` (`Environment` كوسيط `@Bean`؛ العابر مستحيل في prod) + بوابة `JwkSourceProdHardeningTest` (3 حالات؛ الثالثة JKS حقيقي RSA-2048 من keytool) + runbook `keys/README.md` + تصحيح دين ARCHITECTURE الموثقي (`RotatingJWKSource` الوهمية ×4 → ADR-004 Revised).
- **تحقق محلي (JDK 25.0.4.1 في tools/):** `JwkSourceProdHardeningTest` 3/3 ثم المنظومة الكاملة (marketplace-shared + marketplace-platform-infra) **79/79** — 0 failures/errors/skipped؛ امتثال رسمي 12/12 بندًا (Spring 7.0.9 / SAS 7.1.1 / Maven — مصادر محفوظة scripts/prod-design-docs/).
- **ملاحظة الدمج:** #186 كان مكدّسًا فوق #185؛ بعد squash #185 فُكّ التكدّس بـ rebase فوق main الجديد (`a49a44f`) — الفرق متحقق التطابق **بايتًا-بايت** مع ما اختبره CI، ثم أعاد CI التحقق على الرأس المفكوك قبل الدمج.
- **بعد الدمجين:** main = `6fdec07` — المراحل 0/1/2/4 ✅. **المتبقي:** المرحلة 3 أُعيد تأطيرها في الخطة الحاكمة الجديدة `docs/security/client-hosting-strategy-plan.md` (معتمدة بأمر المستخدم 2026-09-03 «اعتمدها»): بوابة B «عميل أول» (نمط حسب مكان السر: BFF سري / SPA عام / فلاتر عام+PKCE) ثم بوابة C «استضافة» (غير محددة بعد — لا افتراض CF)، ثم التشغيل.

### Client & Hosting Strategy — الخطة الحاكمة الجديدة (2026-09-03) ✅ ADOPTED
- **الأمر:** «اعتمدها» — اعتماد `docs/security/client-hosting-strategy-plan.md` كوثيقة حاكمة بعد عرض مسودتها (`download/client-hosting-strategy-plan.md` خارج المستودع) وسؤال المستخدم عن اتساقها الرسمي.
- **الجوهر:** الباك اند (هذا المستودع) هو المرساة الثابتة؛ تقنيات العملاء (فلاتر/Next.js/أي لغة) ووجهة الاستضافة = بوابات قرار مفتوحة بيد المستخدم، تُخطط لا تُفترض. تصنيف العملاء الرسمي: مكان السر لا اللغة (SAS how-to حرفيًا). لا كود ولا اعتماديات جديدة — كل عميل جديد = صف RegisteredClient بإعدادات صريحة (INV-2) عبر مسار #183 + اختبار S2/S3.
- **الديون الموثقة داخل الخطة:** `server.forward-headers-strategy` غير مضبوط (يُغلق عند النشر، أي مضيف) + افتراض «React SPA على CF Pages» في ARCHITECTURE.md §5 منتهي الصلاحية (يُقفل بـ PR عند حسم الاستضافة).

## Current State (2026-08-30)

**Branch:** `main` (HEAD: `9eb0687`) — PR #180 **merged** (squash `9eb0687`); all review-report items A1–A6 resolved/refuted below

### Review report deferred items — resolved (2026-08-30) ✅

#### A2 — `CatalogSpi` entity leak across module boundary — FIXED
- **Basis:** the module's own documented convention (`ProviderListingSummary` Javadoc: "Decouples admin from the full JPA entity in the catalog module") applied to the whole `catalog-spi` named interface
- **Changes:**
  - New `ProviderListingView` record in `shared :: shared-api` (id, title, description, category, priceCents, providerId, status, createdAt, updatedAt) — read-only, no `com.marketplace.catalog` import (keeps `shared` pure per ArchUnit rule)
  - `CatalogSpi`: `getActiveById/findAll/create` now return `ProviderListingView`; dead `getById(UUID)` removed (zero consumers in code — only `CatalogController`/`CatalogService` internal use survived)
  - `CatalogService`: entity→view mapping stays module-internal (`toProviderListingView`); `findAll` keeps the ACTIVE filter
  - Presentation adapters: `ServiceGraphQlController`/`ServiceMapper` + `CatalogController`/`ListingMapper` (new view overload; `currency` ignored — unmapped today)
- **Tests updated:** `ServiceGraphQlControllerTest`, `ServiceMapperTest`, `CatalogServiceTest`, `CatalogControllerWebMvcTest`, `CatalogServiceSecurityTest`

#### A3 — `syncFromOidc` unconditional cache invalidation — FIXED
- `User.updateProfile(...)` now returns `boolean changed` (`Objects.equals` guard)
- `UserService.syncFromOidc` publishes `CacheInvalidationRequested` **only on user creation or actual profile change** — no more `event_publication` archive row + cache thrash on every `GET /users/me` when nothing changed
- **Tests:** exist + create tests now assert publish; new `syncFromOidc_whenProfileUnchanged_doesNotPublishInvalidation` → identity module 25 tests

#### A1 — "5 modules open without @ApplicationModule" — REFUTED (no change)
- `Availability/Disputes/Ledger/Notifications/Provider` already declare `@ApplicationModule(allowedDependencies = {"shared :: shared-..."})` on their `*Module.java` marker class (e.g. `AvailabilityModule.java`)
- Official docs (Spring Modulith 2.1.1, Fundamentals §Explicit Application Module Dependencies): *"you can also use the annotation on a single type located in the application module's root package"* — the exact pattern used; prior audit looked only at `package-info.java`

#### A4/A5 — confirmed not-debt (no change): failsafe 40/40 is exact (reviewer's "55" counted `@Testcontainers` lines as `@Test`); Cloudflare check not in `.github/workflows`
- **Cloudflare external sweep (2026-08-30) — resolved, not debt:** CF exists only as docs/ADR target (`ARCHITECTURE.md §5`, ADR-003, diagrams) — no repo artifact implements it: no `wrangler.toml` anywhere; `Dockerfile` / `railway.toml` / `docker-compose.yml` and all three CI workflows are CF-free; `application-prod.yml` is env-var driven (`DB_URL`/`REDIS_HOST`/`OTEL_*`) with no CF-specific config. Operator-side config (Worker proxy, Hyperdrive) lives on the CF dashboard as the docs' Phase-0 account action ⇒ **no repo change**

#### A6 — "async missing / `@ApplicationModuleListener` run synchronously" — REFUTED (no change)
- **Root cause of the false finding:** the prior DOC-GROUNDED-DESIGN-AUDIT inspected `spring-boot-autoconfigure` + `modulith-runtime/actuator` but **not `spring-modulith-events-core`** — exactly the jar that owns the async auto-config (same "only read package-info" class of error as A1); verified independently byte-for-byte from `spring-modulith-events-core-2.1.0.jar`
- **Basis:**
  - `EventPublicationAutoConfiguration$AsyncEnablingConfiguration`: `@EnableAsync` + `@ConditionalOnMissingBean(AbstractAsyncConfiguration.class)` — backs off only if the app itself declares `@EnableAsync`/`AsyncConfigurer`; registered via `META-INF/spring/*AutoConfiguration.imports`
  - `AsyncPropertiesDefaulter`: defaults `spring.task.execution.shutdown.await-termination=true` (period 2s); gated by `@ConditionalOnProperty("spring.modulith.default-async-termination", matchIfMissing=true)`
  - `@ApplicationModuleListener` = `@Async` (unqualified) + `@Transactional(REQUIRES_NEW)` + `@TransactionalEventListener` — official events.html *"Spring Modulith provides @ApplicationModuleListener as a shortcut"* for the pattern that *"decouples the original transaction"*; zero mention of `@EnableAsync` in the page
- **Code facts — no declarative async anywhere (main):** no `@EnableAsync`, no `AsyncConfigurer`, no custom `Executor`, no `autoconfigure.exclude`; only `ArchitectureRulesTest` mentions `@EnableAsync` ⇒ `@ConditionalOnMissingBean` never backs off; `dependency:tree` confirms `starter-jpa` → `events-core:2.1.0` on the app runtime classpath
- **Executor identity:** Boot's `applicationTaskExecutor` (web auto-config in Boot 4.1.1 defines no competing executor — looks it up by name) + `spring.threads.virtual.enabled: true` ⇒ the 11 listener methods across 7 classes already run async post-commit on virtual threads
- **Count (corrected):** 11 methods / 7 classes — BusinessMetrics ×4, Notification ×2, + Availability / BookingExpiration / BookingPayment / BookingCancelled / Ledger ×1 each
- **Rejected alternative:** declaring `@EnableAsync` on `MarketplaceApplication` — redundant, and it would trip the ArchUnit guard `enableAsyncRequiresAsyncMethods` (checks direct `@Async`; listener methods are meta-annotated) ⇒ the repo already self-protects against that change

#### Verification — `mvn clean verify` → **BUILD SUCCESS** (17 modules)
- Surefire full reactor: **539 / 0 / 0 / 3** (identity 24→25; app 157 unchanged)
- Failsafe: 40 / 0 / 0 / 34 · Jacoco ≥ 0.70 all bundles · `-Werror` clean

## Sprint 5 — Auth System Design — `feat/auth-system-design` (2026-08-31)

**الفرع:** `feat/auth-system-design` (الخطة المعدلة النهائية). **الحالة النهائية: مُلتزم ومدفوع في `77fc651` → PR #181.** (هذا القسم يسجّل مسار التطوير؛ الحقيقة المعتمدة النهائية في قسم «الخطة المعدلة النهائية» أدناه.)

### كل ما عبثت به — ملفات المشروع المعدّلة (git status)
| الملف | ما فعلته |
|------|----------|
| `marketplace-platform-infra/.../SecurityConfig.java` | ب.0 OAuth2TokenCustomizer (roles+aud)؛ ب.1 jwtDecoder مبسّط + requiredAudiencesValidator؛ ب.2 resourceServer chain `@Order(2)` + default `@Order(3)`؛ ب.3 beans `sessionRegistry` + `httpSessionEventPublisher` + `maximumSessions`؛ **هذا الأسبوع:** نوعا الـ wildcard `? extends Session` (سطر 152 معامل default chain، سطر 166-170 bean) |
| `marketplace-platform-infra/.../MarketplaceProperties.java` | ب.3: إضافة سجل `Security.Session(@DefaultValue("2") int maxSessions)` |
| `marketplace-app/src/main/resources/application.yml` | ب.3: كتلة `spring.session.data.redis` (indexed / on_save / namespace: marketplace:session) + `timeout: 30m`؛ **هذا اليوم:** كتلة `marketplace.security.session.max-sessions` المفقودة — إصلاح الخطأ M4 |
| مواقع الإنشاء الأربعة | `ModuleTestConfig.java`، `MessagingModuleIntegrationTest.java`، `SecurityConfigJwtDecoderTest.java` (تحديث بناء `MarketplaceProperties`) + ملف **جديد غير متتبع** `OAuth2TokenCustomizerTest.java` (ب.0) |

### أمراض جانبية خارج الـ workspace (يجب تنظيفها لاحقاً)
- **حاوية Docker حية:** `redis-verify` (`-p 6379:6379 redis:7-alpine`) — ما زالت تعمل؛ تُحذف مع نهاية العمل ثم تستأنف عند الحاجة.
- **سكربت فحص معزول:** `C:\Users\w-co\AppData\Local\Temp\opencode\session-wiring-check\src\` — AutoLike, ConsumerTyped/Wildcard/Concrete, Probe, ProbeConfig, RawIndexedRepo, RealAutoConsumer, RunRealAuto, RunAll (تمثيل مستقل خارج الـ reactor).
- **سجلات تحقق:** `sec-test-full.log`، `sec-test-2.log`، `sec-test-3.log`، `infra-test-1.log` في `C:\Users\w-co\AppData\Local\Temp\opencode\`.

### أخطائي (بترتيب زمني — عُولج كلٌّ منها بالإثبات)
1. **M1 — استنتاج خاطئ من فحص معزول** (جلسات سابقة): كتبتُ `RawIndexedRepo implements FindByIndexNameSessionRepository<Session>` مباشرة، فنجح الحقن فاستنتجتُ «عيب Spring Generics». **استنتاج باطل**: الصنف المحاكي يطابق `<Session>` مباشرة بينما الـ bean الحقيقي يتنفّذ `<RedisSession>`. أُبطل هذا الأسبوع بالتمثيل الحي للـ autoconfig.
2. **M2 — الوثوق بـ EXIT=0 الوهمي**: `.\mvnw.cmd` لا ينقل exit code في PowerShell مع `*>` — رصدت `EXIT=0` مع `Errors: 3` و `BUILD FAILURE`. القاعدة: التحقق الوحيد من السجل (`Tests run:` + `BUILD SUCCESS|FAILURE`).
3. **M3 — الوقوع في رسالة Spring المضللة**: طلب `? extends Session` يُطبع في نص الخطأ كـ `<Session>`؛ كدت أُعيد التشخيص خطأ. الحقيقة (A/B حي): يطابق كلا الفولين بدقة.
4. **M4 — إغفال كتلة `marketplace.security.session`**: `@DefaultValue` لا يهيّئ عقدة الأب؛ `properties.security().session()` = null → NPE في `defaultSecurityFilterChain:159` ظهر حياً بعد إصلاح wildcard. أُصلح بإضافة الكتلة في yml.
5. **M5 — اقتراح ب.4 بوثيقة خاطئة مؤقتاً**: أنزلت Configuration Model الخاص بـ AS 1.5.8 (المستقل) القائل بـ `authorizationServer()` الثابت بينما 7.1.1 المدمج لا يملكه (مثبّت بالبايتكود). لم يُطبَّق شيء خاطئ — التحقق سبق التطبيق.
6. **M6 — افتراض `$env:JAVA_HOME`**: غير معيّن في هذه البيئة؛ الحل المسار المباشر `C:\Program Files\Java\jdk-26\bin\javap.exe`.
7. **M7 — فرضية «الخطأ قديم/قبل الإصلاح»** (جلسة سابقة): أُبطلت بالتمثيل الحي مع Docker — الخطأ حيّ ومتكرر بالضبط.
8. **M8 — ميل التشخيص نحو auto-config**: تقرير الشروط أثبت `IndexedRedisSessionConfiguration matched` — اللوم على المعامل الخام لا على الإعدادات.

### اكتشافاتي (مؤيدة بالبايتكود/الوثائق)
- **D1 — السبب الجذري النهائي (ب.3)**: `RedisIndexedSessionRepository implements FindByIndexNameSessionRepository<RedisIndexedSessionRepository$RedisSession>` — نوع متداخل خاص. حقن `<Session>` يفشل **بموجب تصميم spring-session 4.1.1** لا بعيب إطار ولا إعداد خاطئ.
- **D2 — الحل الرسمي**: النمط الموثّق `<S extends Session>` في وثيقة 4.1.1؛ التكافؤ العملي `? extends Session` (يُقلع السياق الحقيقي + Redis حية).
- **D3 — براءة خادم الـ AS**: `OAuth2AuthorizationServerConfigurer.initSessionRegistry` (spring-security-config 7.1.1) يطلب `SessionRegistry.class` خام فقط — لا علاقة له بـ `FindByIndexNameSessionRepository`.
- **D4 — واجهة 7.1.1 المدمجة**: لا يوجد `authorizationServer()` ثابت؛ فقط منشئ بلا وسائط + `getEndpointsMatcher` + `.with(...)` ⇒ **ب.4 = إضافة `@EnableWebSecurity` فقط** (بانتظار الموافقة؛ أيضاً `oauth2-client` حزمة ميتة بلا أي import ⇒ ب.5 حذف آمن).
- **D5 — أمر الفحص** يجب أن يحوي `-am` وإلا `NoClassDefFoundError: ProviderListingView` مضلّل؛ بيئة: Java 26، JAVA_HOME غير معيّن.
- **D6 — ربط سجلات Boot**: غياب عقدة الأب في yml = null (رخاوة)؛ `@DefaultValue` يقع على الورقة فقط.
- **D7 — فجوة classpath للفحص المعزول**: قائمة جرار الـ .m2 المطلوبة لإعادة التمثيل محفوظة في `~/.config/opencode/AGENTS.md` (Current Session State).

**التحقق الحالي:** `SecurityProblemDetailIntegrationTest` 3/3 ✅ + infra 36/36 ✅ (بعد إصلاح M4) ⇒ **ب.3 مكتمل ومُتحقَّق منه حياً**.

### الخطة المعدلة النهائية — التوثيق الصادق للحجم (2026-08-31) ✅
**الخلفية (من السجل، لا تخمين):** الخطة القديمة ب.0–ب.5 نفّذت **ب.0–ب.3** وحققتها حياً (3/3 + 36/36). أثناء عملها اكتُشفت افتراضات خاطئة في البنية **الداعمة** (M1–M8 + D1–D7 أعلاه، كلها في: البادئة `spring.security.oauth2.authorizationserver`، OIDC الإجباري، عدّ 14 ملف، الكتل الميتة، ب.4=D4، ب.5=D5). وُثّقت هذه الأخطاء، ثم صُمّمت **خطة معدلة** تحافظ على ب.0–ب.3 المثبتة وتصحّح البنية الداعمة.

**المُعتمَد الآن (خطة معدلة = 7 بنود جراحية + ب.0–ب.3 المُبقاة):**

| النطاق | البنود | الحالة |
|---|---|---|
| **خطة معدلة (7 بنود)** | 1) DSL الرسمي `http.oauth2AuthorizationServer(...)` + `@EnableWebSecurity` + securityMatcher داخل اللامدا + `.oidc` + `.cors`<br>2) `jwtDecoder` يحقن `AuthorizationServerSettings.getIssuer()`<br>3) حذف فول `authorizationServerSettings` اليدوي<br>4) حذف `Security.AuthServer` من `MarketplaceProperties`<br>5) `spring.security.oauth2.authorizationserver.issuer` في base+test + حذف كتل الميتة<br>6) حذف `spring-boot-starter-security-oauth2-client` من infra/pom<br>7) 14 WebMvcTest (حذف استيرادات/استثناءات oauth2-client) + حذف google + 4 مواقع إنشاء | ✅ منفذة في `77fc651` |
| **ب.0–ب.3 (من الخطة القديمة، مثبتة — مُبقاة)** | ب.0 `jwtTokenCustomizer` roles+aud + حذف `jti` اليدوي + `OAuth2TokenCustomizerTest` (3 اختبارات)<br>ب.1 `jwtDecoder` مبسّط + `requiredAudiencesValidator`<br>ب.2 دمج السلاسل 4→3 (public+protected في `resourceServerSecurityFilterChain`) + `/v3/api-docs/**`<br>ب.3 `sessionRegistry` + `httpSessionEventPublisher` + `maximumSessions` + `spring.session.data.redis` (indexed) | ✅ مثبتة حياً ومُبقاة |

**حقيقة الـcommit:** كل ما سبق — البنود السبع + ب.0–ب.3 — مُلتزم ومدفوع في **commit واحد `77fc651`** على `feat/auth-system-design`، وأنشئ منه PR #181. **سطر 47 أعلاه («غير ملتزمة حتى الآن») لم يعد يطابق الواقع** — استُبدل بهذا القسم.
- **CI على `77fc651`:** Build & Test ✅ + Full Integration ✅ (pass على JDK 25).
- **PR #181:** OPEN / MERGEABLE / CLEAN.
- **وصف الـPR:** حُدّث ليعكس الحجم الصادق (A = البنود السبع، B = ب.0، C = ب.1/ب.3، D = ب.2) — لا تقليص.
- **تحقّق بنص الصدق:** كل ادعاءات الوصف طُوبقت بنداً بنداً ضد كود `77fc651` (لا ضد التقرير) — بلا تناقض.

### Sprint 4 — Cache After-Commit + Listener Retry Tests (2026-08-29)

#### PR #179 — ALL cache invalidation deferred to AFTER_COMMIT ✅ (merged `08c4e95`)
- **Files:** 8 services (CatalogService, AvailabilityService, BookingService, PricingService, PaymentsService, ProviderService, ReviewsService, UserService), `CacheInvalidationRelay.java`, `CacheInvalidationMetrics.java`
- **Changes:**
  - Removed 30× `@CacheEvict` from service methods
  - New `CacheInvalidationRelay`: `@TransactionalEventListener(AFTER_COMMIT)` publishes `CacheInvalidationRequested`
  - New `CacheInvalidationMetrics`: Micrometer counter for invalidation events
  - `CacheConfig`: `@EnableScheduling` for `EventPublicationCleanup`
- **Tests:** `CacheInvalidationRelayTest` (6), `CacheInvalidationMetricsTest` (2)

#### PR #179 additions — EventPublicationCleanup + Conversations cache
- **Files:** `EventPublicationCleanup.java` (new), `MessagingService.java`
- **Changes:**
  - `EventPublicationCleanup`: `@Scheduled(cron="0 0 3 * * ?", zone="UTC")` purges completed publications >7 days
  - `MessagingService`: publishes `CacheInvalidationRequested` on `createConversation()`
- **Tests:** `MessagingServiceTest` updated

#### Issues #146, #147, #148 — Closed ✅
- **#146** (Unify @PreAuthorize): Closed "won't fix" — system verified against the official model: fine-grained rules at the service layer, coarse admin gates at controllers, plus the HttpSecurity catch-all (`anyRequest().authenticated()` + `/api/v1/admin/**` → ADMIN) that the docs mandate for unannotated methods (see "Security Design" below)
- **#147** (verify ReviewUpdatedEvent): Added `verify(eventPublisher.publishEvent(ReviewUpdatedEvent.class))` to `ReviewsServiceTest`
- **#148** (ApplicationModuleListener retry semantics): 15 tests across 4 modules:
  - `BookingPaymentEventListenerTest` (3): non-completed state, propagate exception, annotation check
  - `BookingExpirationServiceTest` (3): cancel stale bookings, propagate exception, annotation check
  - `BookingCancelledEventListenerTest` (3): auto refund, propagate exception, annotation check
  - `NotificationEventListenerTest` (6): 2 listeners × 3 tests each

#### Method Security — D3 documented test pattern complete (2026-08-29) ✅
- **Basis:** Spring Security reference — "You can then test the class to confirm it is enforcing the authorization rule" (`@WithMockUser(roles="ADMIN")` → `...ThenInvokes()` + `...ThenAccessDenied()`), `https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html`
- **Commits (`main`):**
  - `a2d51a7` — 4 missing **negative** tests (`BookingService.complete`, `CatalogService.activate/pause/archive`)
  - `da1bfe9` — 10 **positive** tests: BookingService (create/confirm/complete/cancel) + CatalogService (create/update/activate/pause/archiveListing/archive)
  - `6d1c522` — 11 **positive** tests: ReviewsService (create/update), ProviderService (create/update/verify/suspend), PaymentsService (createIntent/processIntent/confirmIntent/cancelIntent/refundPayment)
  - `?` — 6 **positive-gap closure** tests: AvailabilityService (createSlot/createRule/createTimeOff when owner), DisputeService (resolve when admin), PaymentsService (2-arg `refundPayment` when admin + its own negative) — closes the 21/26 gap from the review report
- **Coverage:** all 26 service-layer `@PreAuthorize` annotations have BOTH negative (`AccessDenied`) and positive (`ThenInvokes`) tests following the exact documented names; the 4 controller-level gates are covered by `WebMvcTest` (negative `..._withUserRole_returnsForbidden` + ADMIN positives) ⇒ **30/30 annotations covered**
- **Gap closure (review report):** 6 tests added (Availability +3, Disputes +1, Payments +2 on the 2-arg `refundPayment(paymentId, amountCents)` overload — a rule that self-invocation had left untestable in both directions) → **52 service security tests = 26 rules × 2**; 21/26 gap closed, "26/26 بقسميه" now factually true
- **Cleanup:** `cddfeff` — `@SuppressWarnings("unchecked")` isolated from public `getRevisions()` into private `queryRevisions()` in `RevisionService` (Hibernate Envers raw `List`); wildcard `import java.util.*` verified per `CODING_STANDARDS.md`
- **Verification:** `mvn clean test` (full reactor) → **538 tests / 0 failures / 0 errors / 3 skipped** (skips environmental: Modulith verification + Testcontainers off); `marketplace-app` module alone = 157

#### Security Design — official model, framework-managed (2026-08-31) ✅
- **Decision:** Full redesign of auth system to the official Spring Security 7.1.1 / Boot 4.1.1 pattern (verified via bytecode + GS 7.1.1 + Boot OAuth2 reference).
- **Code facts — 3-chain architecture (unchanged):**
  - **AS chain `@Order(1)` — authorizationServerSecurityFilterChain:** official DSL `http.oauth2AuthorizationServer(...)` with `securityMatcher(endpointsMatcher)` + `.oidc(withDefaults())` + `.authorizeHttpRequests` + `.exceptionHandling` + `.cors` — follows GS 7.1.1 verbatim.
  - **Resource server chain `@Order(2)` — resourceServerSecurityFilterChain:** `.oauth2ResourceServer(jwt(jwtAuthConverter))` + `.sessionManagement(STATELESS)` + app policies.
  - **Default chain `@Order(3)` — defaultSecurityFilterChain:** `formLogin` + `sessionManagement(maximumSessions(maxSessions) + sessionRegistry)` + `@EnableWebSecurity`.
- **Key facts (bytecode-verified):**
  - `DefaultWebSecurityCondition` = `AllNestedConditions(@ConditionalOnMissingBean(SecurityFilterChain) + @ConditionalOnClass(...))` → Boot's default chains NEVER register when custom chains exist → our 3 chains are the official extension point (F1).
  - `HttpSecurity.oauth2AuthorizationServer(Customizer<OAuth2AuthorizationServerConfigurer>)` exists in `spring-security-config-7.1.1.jar` (F2 — GS 7.1.1 `Defining Required Components`).
  - `@ConditionalOnMissingBean` on `authorizationServerSettings()` in `OAuth2AuthorizationServerConfiguration` is independent of chains → framework provides bean from `spring.security.oauth2.authorizationserver.issuer` even with our custom chains (F4).
  - `with()` is NOT deprecated (only `requiresChannel`) — pre-7.0 pattern replaced by new DSL (settled: 3 definitive proofs).
- **Single-source issuer:** `spring.security.oauth2.authorizationserver.issuer` → framework creates `AuthorizationServerSettings` bean → `jwtDecoder` injects it via `authorizationServerSettings.getIssuer()`. Removed `MarketplaceProperties.Security.AuthServer` record + manual `authorizationServerSettings` bean.
- **Deleted:** `spring-boot-starter-security-oauth2-client` (zero Java usage outside 14 WebMvcTest exclusion annotations); dead google blocks from `application-test.yml` + `application-dev.yml`.
- **`marketplace-web-client` — DECISION: intentionally deferred (not a gap, not dead code):** the registered client in `R__seed_oauth2_client.sql` is a **forward-looking placeholder with NO Relying Party**. Direct greps confirm: no `oauth2Login`/`ClientRegistration`/`OAuth2LoginConfigurer` anywhere; no module declares `oauth2-client`; the sole consumer of the old server-side client integration was removed **by design** in this redesign (`spring-boot-starter-security-oauth2-client` deleted above, and no SPA/BFF/frontend module exists in this backend-only Modulith). Completing the client (SPA vs BFF, PKCE, `client_credentials`, redirect URI) is a **future architectural decision**, deferred until a real consumer is defined. **No change to the seed now** — any PKCE/grant/redirect change before the consumer type is known would be speculative rework. E2E gate test (`it-login-gate-client`) remains isolated with its own client; it does NOT extend to the production placeholder.
  - **Governance update (2026-09-03):** this deferral is now governed by the adopted `docs/security/client-hosting-strategy-plan.md` (D9 reframed as multi-client gates — §7): the placeholder's fate resolves automatically when the first real consumer arrives (Gate B); until then, option (ج) removal remains on the table.
- **Retained (production):** Jdbc `RegisteredClientRepository`/`OAuth2AuthorizationService`/`OAuth2AuthorizationConsentService` (Boot docs: "For production, consider using JdbcRegisteredClientRepository"); OIDC enabled (`R__seed_oauth2_client.sql:36` requests `scopes='openid,profile'`); JWT keystore + audience validator; all ProblemDetail handlers; session concurrency.
- **3-layer authorization (unchanged):** 26 service SpEL + 4 controller coarse gates + catch-all `anyRequest().authenticated()` — NOT affected by this redesign.

#### Auth Redesign — Phase 0 (docs & verifications) done (2026-09-01) ✅ → plan: `docs/security/auth-system-redesign-plan.md`
- **Status:** Redesign plan reviewed twice with independent verification; now the **governing plan**. Phase 0 = docs only (no behavior change, no merge).
- **Phase 0 delivered:** `docs/security/auth-system-redesign-plan.md` — phases 0-4, findings F-A/F-B/F-C, decisions D1-D9, invariants INV-1..7, test ladder T1-T4, risk log (all quotes verbatim from RFC 9068/9700).
- **F-A (bytecode 7.1.1):** `ClientSettings.withSettings(map)` constructs a fresh `Builder()` without applying defaults → production `marketplace-web-client` (R__seed: consent only) runs with `isRequireProofKey()=false` **by omission** — closed in Phase 1 by direct seed fix (T4 relocated to Phase 2 with T3, see below).
- **F-B:** E5's `it-login-gate-client` is synthetic (proofKey=true/consent=false via `RegisteredClientRepository.save(...)`, schema loaded from `V13`); no test loads the production R__seed client — to close in Phase 2 (T3).
- **F-C:** `marketplace-web-client` has no consumer (deferral record in line 151) — pending D9.
- **D2 (Boot 4.1.1 bytecode):** `spring.security.oauth2.authorizationserver.client.*` only feeds an `InMemoryRegisteredClientRepository` behind `@ConditionalOnMissingBean(RegisteredClientRepository.class)` + `@Conditional(RegisteredClientsConfiguredCondition)` → inert here because we define the Jdbc bean.
- **D5 javadoc fixed:** `SecurityConfig:353` now cites **RFC 7519 §4.1.3** for `aud` (was the wrong `rfc9068#section-4.1.3`).
- **Pending user decisions:** merge order **#181→#182**; D9 (web client fate); confirm D4 (consent=true); permission to start Phase 1.

#### Auth Redesign — Phase 1 (F-A closure) — APPROVED (2026-09-01) ✅
- **Independent review (user, bytecode/sources 7.1.1 local):** all 4 documented layers confirmed; the `validateCodeChallenge` hypothesis is now source-proven:
  - `OAuth2AuthorizationCodeRequestAuthenticationValidator.java:67,206-224` — missing `code_challenge` + `isRequireProofKey()` → `invalid_request` citing RFC 7636 §4.4.1; `:218-221` enforces S256-only when present = **double downgrade shield**.
  - `ConfigurationSettingNames.java:33,43,49,55` — keys built by **concat** in `<clinit>` (`"settings."+"client."+"require-proof-key"` / `"...consent"`) ⇒ never visible as one string in grep; effective keys **verbatim-match** the seed JSON.
  - `ClientSettings.java:53-55` (`Boolean.TRUE.equals(...)` null-safe ⇒ F-A = absent-key state); `:107` builder default `requireProofKey(true)` vs `:115-118` `withSettings` = bare `putAll` (no defaults); `JdbcRegisteredClientRepository.java:359-360` read path `withSettings(map).build()`.
  - **Bonus findings:** `JdbcRegisteredClientRepository.java:363-365` compensates the missing default for **TokenSettings** (`accessTokenFormat=SELF_CONTAINED`) but **not** for ClientSettings — the framework itself attests «DB map is the truth» (D2). Flyway/PostgreSQL doc layers: accepted as written (quotes correct).
- **Approved scope (verbatim — 1 file, 2 hunks):** `R__seed_oauth2_client.sql` — L37 `client_settings` + `"settings.client.require-proof-key":true` (sibling of the consent key, same `settings.client.` namespace); L40 `ON CONFLICT (id) DO NOTHING` → `ON CONFLICT (id) DO UPDATE SET client_settings = EXCLUDED.client_settings, token_settings = EXCLUDED.token_settings` (convergent upsert per PostgreSQL `excluded` semantics).
- **Excluded by design (reviewer-confirmed):** `client_secret` / admin rows / `client_id_issued_at` / redirect / scopes stay out of SET (preserves rotated secrets in live envs — the seed itself says «rotate immediately»); `token_settings` value unchanged (rewritten only as a no-op on conflict).
- **T4 relocated (single-line governance record per review mandate):** T4 leaves Phase 1 → merges into **Phase 2 with T3** — one shared harness loads the real `R__seed` via Flyway then reads via JDBC; fixing the earlier spec error (seed lives in `marketplace-app` resources; infra module cannot reach it).
- **Residual accepted risk:** V13 defines PK on `id`, no UNIQUE(`client_id`) — an id-different/same-`client_id` row is not producible by any code path (only the seed writes the table in prod; E5 isolated with its own client). Accepted as theoretical.
- **Post-merge behavior:** fresh DB → full settings from first boot; seeded DB → R__ checksum change → Flyway reapplies (repeatable, ordered last, after V13) → `DO UPDATE` converges → authz requests without a valid `code_challenge` rejected with `invalid_request`. Dormant until a consumer is born in Phase 3 (F-C).
- **Delivery:** execution in my hands (prior merge/batch record clean); direct-to-main single commit (CI runs on `push: main`), same precedent as the Phase 0 batch (`f80b945`); local `main` = `origin/main` = `f80b945`.
- **Open (2 words):** start **Phase 2** (T3+T4 — real-seed harness, closes the consent gap D4) or **Phase 3** (D9=(a) public/confidential consumer + PKCE flow).

#### Auth Redesign — Client-secret source — OFFICIAL-DESIGN DECISION (2026-09-01) ✅ MERGED (PR #183 → `c16e750`)
- **Reframe (per user mandate «system, not patches»):** F-A closure (5b5a238) aligns PKCE with official docs; a **residual design gap** surfaced — the `marketplace-web-client` **client_secret** is a bcrypt literal in `R__seed_oauth2_client.sql:30`, i.e. a known, checked-in value. Official design(s) read as follows:
  - **Spring AS 7.1.1 official schema** (extracted from jar `oauth2-registered-client-schema.sql`): `client_secret varchar(200) DEFAULT NULL` — **nullable by design**, PK on `id`; client_secret is operator-supplied, not framework-generated.
  - **Spring AS core-model-components.html §RegisteredClientRepository (verbatim):** providers are `InMemoryRegisteredClientRepository` (recommended **ONLY** dev/test) and `JdbcRegisteredClientRepository` (persists via `JdbcOperations`); the repository is a **REQUIRED** component. The one true bean in `SecurityConfig.java:250-251` returns `new JdbcRegisteredClientRepository(jdbcTemplate)` — already the official singleton pattern.
  - **Spring Boot /security/oauth2.html (OAuth2 client/RP side):** `spring.security.oauth2.client.registration.<id>.<client-secret>` — Boot binds client secrets from **external config / env** via relaxed binding (`OAUTH_CLIENT_SECRET`), i.e. secrets are env-driven, never committed.
  - **Project precedent (prod):** `application-prod.yml` is env-driven fail-fast with **no defaults** — `${DB_PASSWORD}`, `${JWT_KEYSTORE_PASSWORD} ${JWT_KEY_ALIAS} ${JWT_KEY_PASSWORD}`, `${CORS_ALLOWED_ORIGINS}`, `${OTEL_*}` — the established official/secure pattern this project already follows (MarketplaceProperties keeps empty `@DefaultValue("")` in code; prod overrides to fail-fast).
- **USER DECISION (Q&A, 2026-09-01):** **«Env → DB at startup (Official Jdbc singleton pattern)»** — selected over (a) keep seed bcrypt + ops rotation and (b) defer. Rationale: reconcile the nullable-secret `DEFAULT NULL` schema + Boot's env-driven binding with the Jdbc singleton that reads `client_secret` from DB, so a **rotated secret is never committed** and prod fails fast if unset — consistent with the whole prod profile.
- **Chosen design (documented BEFORE implementation):**
  1. `MarketplaceProperties.Security` gains `OAuth2.Client clientId` + `secret` (mirrors `Jwt.KeyStore`: `@DefaultValue("")` in code, no prod default).
  2. `application-prod.yml`: fail-fast `${OAUTH_CLIENT_ID}` + `${OAUTH_CLIENT_SECRET}` (no default — same as `${JWT_KEYSTORE_PASSWORD}`).
  3. Dev/default YAML keeps the known seed values so local dev is unchanged.
  4. Small **bootstrap updater** (`OAuth2ClientSecretInitializer`, `ApplicationRunner` — the official Boot startup slot, before Readiness): when a clientId+secret are configured, bcrypt-encode via the existing `PasswordEncoder` bean (DelegatingPasswordEncoder, `SecurityConfig:198-200`) and rotate through the **official mutation path `RegisteredClientRepository.save()`** (bytecode-verified `JdbcRegisteredClientRepository` UPDATE: `client_secret + client_secret_expires_at` by PK `id`) — built via `RegisteredClient.from(existing)`; encoded only if different from current (guarded by `passwordEncoder.matches(raw, stored)` to stay cluster-safe/idempotent); fail-fast if clientId configured but not found.
- **Facts locked before coding:** V13 schema matches official jar schema (client_secret VARCHAR(200) DEFAULT NULL, PK id); read path `JdbcRegisteredClientRepository` compares via the same `PasswordEncoder` bean; bean location `SecurityConfig.java:250-251 + 197-200`; type-safe props `MarketplaceProperties.java:26-44`; official runner slot quote captured ("ready shortly before the runners", "Tasks ... should be executed by `CommandLineRunner`/`ApplicationRunner` components"); Flyway runs **during context refresh, before runners** ⇒ the `R__seed` client exists when the initializer runs (fail-fast "client not found" can never fire spuriously in a managed env).
- **Spec-open items for reviewer (NOT decisions taken unilaterally):**
  - **a)** reset `client_secret_expires_at` to NULL on rotation (official schema keeps it NULL by default; secret has no expiry unless policy later sets one) — ✅ **CLOSED by reviewer: official default is NULL; reset to NULL on rotation.**
  - **b)** fail-fast vs tolerant when the configured clientId is absent from the table (insert vs update) — an *unregistered* clientId is a config error (fail-fast); a *registered* id with missing seed row should not self-create. ✅ **CLOSED by reviewer: fail-fast + `save()` round-trip preserves seed settings (INV-4).**
  - **c)** test strategy: unit test for the updater + keep E5/`it-login-gate` intact (it uses its own client via `save()`, untouched). ✅ **CLOSED by reviewer: unit tests + CI is the arbiter (failsafe ITs run only under managed CI services).**
- **⚠ Silent-run hazard (reviewer finding, closed):** absence of BOTH env vars must NOT silently keep the seed placeholder in production. `application-prod.yml` placeholders fail at binding already, but a prod jar booted **without `SPRING_PROFILES_ACTIVE=prod`** would fall to default profile and no-op silently. **Decided:** profile-gated fail-fast inside the initializer — `prod` active + blank clientId/secret ⇒ `IllegalStateException` at startup; non-prod + blank ⇒ deliberate no-op (dev stays on the seed secret).
- **⚠ CI regression found & closed (PR #183, official-doc solution):** `MarketplaceApplicationTest.contextLoads` NPE'd in CI with `MarketplaceProperties$Security.oauth2()` null at `OAuth2ClientSecretInitializer.run`. Root cause is Boot's **documented** constructor-binding rule: *"If the `security` property is not present at all, then the `Security` instance will be `null`"* — `marketplace.security.oauth2` keys exist only in `application-prod.yml`, so under test/dev/default the component binds to `null`. **Official fix (verbatim doc):** the docs' constructor-binding section offers empty `@DefaultValue`: *"If you want to always bind a non-null instance of `Security`, even when properties are missing, you can use an empty `@DefaultValue` annotation"*. Applied on both nested components `Security.oauth2` and `OAuth2.client` (section present ⇒ non-null; keys absent ⇒ `clientId`/`secret` = `""`). The initializer dereferences directly per that binding guarantee; a naive null-guard was rejected per user mandate («لا تحل المشكلة برأيك» — official docs only). Guard test: `MarketplacePropertiesBindingTest` (Binder + keys-absent ⇒ non-null nested defaults).
- **Reviewer verdict 2/2 ✅ + merged:** independent review approved every layer (official-doc quote matched verbatim, all 6 initializer paths, INV-4 proven from SAS source `RegisteredClient.java:302-327`); cosmetic note fixed (`MessagingModuleIntegrationTest:112` indent). **Open ops verification (in Railway, outside repo — reviewer & plan):** no `SPRING_PROFILES_ACTIVE` is set anywhere in the repo (yml/deploy files); the prod fail-fast gate only guards «prod profile active + env blank». Prod artifact booted **without** `SPRING_PROFILES_ACTIVE=prod` still no-ops silently ⇒ verify the variable is set in the Railway panel (one check, no code).

#### Release gate — `mvn clean verify` green (2026-08-31) ✅
- **Targeted build:** `mvn clean verify -pl marketplace-app -am` → **BUILD SUCCESS**
- **Tests:** 157 run / 2 skipped (environmental) / 0 failures / 0 errors — `marketplace-app` module
- **Per-module jacoco `check`** (INSTRUCTION COVEREDRATIO ≥ 0.70): "All coverage checks have been met" across all modules
- **Compiler** `failOnWarning=true`: clean
- **Security:** 3/3 `SecurityProblemDetailIntegrationTest` + infra 38/38 (incl. `SecurityConfigJwtDecoderTest` + `OAuth2TokenCustomizerTest` updated with new `AuthorizationServerSettings` injection + `JwtRolesRoundTripTest`: roles claim round-trip customizer→converter→`hasRole("ADMIN")`, incl. Security 7.1.1's unconditional `FactorGrantedAuthority.BEARER_AUTHORITY`)

## Sprint 3 — Notification Transaction Semantics + Catalog Read-Surface Integrity (2026-08-29)

### PR #178 — Email Transaction Semantics (Spring Modulith alignment)
- **Files:** `EmailNotificationService.java`, `NotificationService.java`, `EmailNotificationServiceTest.java`
- **Changes:**
  - Removed `@Transactional(REQUIRES_NEW)` from `sendEmail()` — `@ApplicationModuleListener` already provides `REQUIRES_NEW`
  - Corrected Javadoc to reflect actual behavior
  - Removed dead `log` field + unused imports from `NotificationService`
- **Tests:** notifications 18/18 ✅

### PR #178 — Catalog Public-Read ACTIVE-Only + Admin All-Statuses
- **Files:** `CatalogSpi.java`, `CatalogService.java`, `CatalogController.java`, `ServiceGraphQlController.java`, `CatalogServiceTest.java` (new)
- **Changes:**
  - Added `getActiveById(UUID)` to `CatalogSpi` + `CatalogService` (ACTIVE-only, 404 otherwise)
  - GraphQL `service(id)` → `getActiveById` (closes draft leak)
  - REST `GET /api/v1/catalog/{id}` → `getActiveById` (closes draft leak)
  - `findAllSummaries()` → `listingRepository.findAll(pageable)` (restores admin all-statuses view)
- **Tests:** 126/0 failures/3 skipped (environmental) ✅

## Sprint 2 — Retry Semantics, Architecture & Final Polish (2026-06-18)

### P0 — Retry Semantics (catch(Exception) removal) ✅
- **Files:** `BookingPaymentEventListener.java`, `BookingCancelledEventListener.java`, `BookingExpirationService.java`, `NotificationEventListener.java` (×2), `PaymentsService.java`
- **Changes:**
  - Removed 5× `catch(Exception)` from `@ApplicationModuleListener`s — let exceptions propagate for proper retry
  - `BookingExpirationService`: `Instant.now()` → `event.getDate().atStartOfDay(ZoneOffset.UTC).toInstant()`
  - `PaymentsService.autoRefundByBooking()`: added `@Transactional(propagation = REQUIRES_NEW)` to prevent `UnexpectedRollbackException`
- **Tests:** `BookingPaymentEventListenerTest`, `BookingCancelledEventListenerTest`, `BookingExpirationServiceTest`

### P1 — Architecture & Security ✅
- **Files:** `ProviderController.java`, `ServiceGraphQlController.java`, `ReviewsService.java`, `SearchController.java`, `ReviewUpdatedEvent.java`, `CatalogSpi.java`, `package-info.java`
- **Changes:**
  - `@PreAuthorize` on `ProviderController.create()` (CONSUMER), `verify()`/`suspend()` (ADMIN)
  - `ServiceGraphQlController` → `CatalogSpi` instead of `CatalogService`
  - `ReviewUpdatedEvent` created + published from `ReviewsService.update()`
  - Dead `search()` method removed from `SearchController`
  - `CatalogSpi` expanded: `getById()`, `findAll()`, `create()`
  - `app` module `package-info.java`: added `"catalog :: catalog-spi"` to `allowedDependencies`
- **Tests:** `ResilienceAnnotationTest` fixed for renamed search method

### P2 — Final Consistency ✅
- **Files:** `BookingConfirmedEvent.java` (new), `BookingService.java`, `PaymentsService.java`
- **Changes:**
  - `PaymentRepository.save(payment)` added after `payment.markRefunded()` in `autoRefundByBooking()`
  - `BookingConfirmedEvent` record created in `marketplace-shared/api/`
  - Published from `BookingService.confirm()` and `autoConfirm()`

## Sprint 1 — H4+H9+H10 (2026-06-14)

### H8 — Webhook eventType dispatch (2026-06-15)
- **Files:** `PaymentsService.java`, `PaymentsController.java`
- **Changes:**
  - Added `processWebhookEvent(..., paymentIntentId, externalId)` overload with event dispatch
  - Added `dispatchWebhookEvent()` — `payment_intent.succeeded` → `confirmIntent()`, logs for other types
  - Added `paymentIntentId` and `externalId` query params to `POST /webhooks/{provider}` endpoint
  - Event names aligned with Stripe convention (`payment_intent.succeeded`)
- **Tests:** 3 new (succeeded dispatches confirmIntent, succeeded without intentId logs warning, controller with intentId)
- **Tests:** 27/27 ✅ (PaymentsServiceTest: 17, PaymentsControllerTest: 9, PaymentWebhookEventServiceTest: 1)
- **WebMvcTest:** 6/6 ✅ (new: webhook with paymentIntentId + externalId returns Accepted)
- **Official docs:** Stripe event types (docs.stripe.com/api/events/types) — `payment_intent.succeeded`, `.processing`, `.payment_failed`


### H4 — PaymentIntent REFUNDED transition
- **Files:** `PaymentIntentStatus.java`, `PaymentIntent.java`, `PaymentsService.java`
- **Changes:**
  - Added `REFUNDED` enum value with transition `SUCCEEDED→REFUNDED`
  - Added `PaymentIntent.markRefunded()` method
  - Updated `PaymentsService.autoRefundByBooking()` to transition intent status
- **Test:** `PaymentIntentStatusTest` — 2 new tests (succeeded_acceptsRefunded, refunded_rejectsAnyTransition)
- **Tests:** 49/49 ✅

### H9 — Email sending from NotificationService
- **Files:** `UserLookupPort.java` (new), `UserLookupPortImpl.java` (new), `NotificationService.java`
- **Changes:**
  - Created `UserLookupPort` interface in `shared` module (`UserLookupPort.findById() → UserSummary`)
  - Implemented `UserLookupPortImpl` in `identity` module
  - `NotificationService.onBookingCreated()/onPaymentStateChanged()` now call `emailService.send()` with resolved user email
- **Test:** `NotificationServiceTest` — new test verifies emailService.send() is invoked
- **Tests:** 14/14 ✅

### H10 — WebSocket notifications
- **Files:** `WebSocketNotification.java` (new), `NotificationService.java`, `pom.xml`
- **Changes:**
  - Added `spring-boot-starter-websocket` dependency to notifications module
  - `NotificationService` injects `Optional<SimpMessagingTemplate>`
  - Sends `WebSocketNotification` to `/topic/notifications/{userId}` on booking creation and payment state change
- **Test:** `NotificationServiceTest` — new test verifies `convertAndSend()` is invoked
- **Tests:** 14/14 ✅

### H5 — Slot auto-generation from ProviderAvailabilityRule (2026-06-15)
- **Files:** `AvailabilityService.java`, `ProviderAvailabilityRule.java`, `ProviderAvailabilityRuleRepository.java`, `pom.xml`
- **Changes:**
  - Added `spring-modulith-moments` + `spring-modulith-events-api` dependencies to availability module
  - Added getters (`getProviderId`, `getDayOfWeek`, `getStartTime`, `getEndTime`) to `ProviderAvailabilityRule`
  - Added `findByDayOfWeek(DayOfWeek)` to repository
  - Added `@ApplicationModuleListener onDayHasPassed(DayHasPassed)` that generates `AvailabilitySlot` for each matching rule, skips duplicates
- **Tests:** 3 new (generates slots, skips existing, does nothing when no rules)
- **Tests:** 18/18 ✅ (AvailabilityServiceTest: 10)
- **Official docs:** Spring Modulith Moments (docs.spring.io/spring-modulith) — `DayHasPassed` event pattern

**Java:** 25 (target `--release 25`)
**Spring Boot:** 4.1.0 | **Spring Modulith:** 2.1.0 | **Maven:** 3.9.16 | **JaCoCo:** 0.8.15

---

## Modules (16)

| Module | Type | Coverage | Status |
|--------|------|----------|--------|
| **marketplace-app** | Composition root | ✅ 70% | ✅ |
| **marketplace-platform-infra** | Shared infra (JPA, Security, Cache, Observability) | n/a | ✅ |
| **marketplace-shared** | Shared API interfaces + exceptions | n/a | ✅ |
| **marketplace-provider** | Domain (provider profiles) | ✅ 70% | ✅ |
| **marketplace-catalog** | Domain (listings) | ✅ 70% | ✅ |
| **marketplace-booking** | Domain (bookings, expiration) | ✅ 70% | ✅ |
| **marketplace-payments** | Domain (payments, intents, webhooks) | ✅ 70% | ✅ |
| **marketplace-pricing** | Domain (pricing rules) | ✅ 70% | ✅ |
| **marketplace-reviews** | Domain (reviews) | ✅ 70% | ✅ |
| **marketplace-messaging** | Domain (conversations, WebSocket) | ✅ 70% | ✅ |
| **marketplace-notifications** | Domain (notifications) | ✅ 70% | ✅ |
| **marketplace-availability** | Domain (availability slots) | ✅ 70% | ✅ |
| **marketplace-ledger** | Domain (ledger, balances) | ✅ 70% | ✅ |
| **marketplace-search** | Domain (full-text search) | ✅ 70% | ✅ |
| **marketplace-admin** | Package in app (admin REST) | ✅ (in app) | ✅ |

---

## CI/CD

| Workflow | Status |
|----------|--------|
| Build & Test (JDK 25) — `mvn verify` | ✅ Passing (all 17 modules) |
| Full Integration Test | ✅ Passing |
| Maven Publish | ✅ |
| Gitleaks Secret Scan | ✅ |
| OpenAPI Compat Check | ✅ |

## Testing

- **JaCoCo**: 70% coverage threshold — all 15 business modules pass
- **Unit tests**: 300+ across all modules (0 failures, 0 errors)
- **Integration tests**: 14 ModuleIntegrationTest classes in `marketplace-app` (95 tests, 0 failures, 0 errors)
- **WebMvcTest**: 44 controller tests across modules
- **Infrastructure**: Testcontainers (PostgreSQL 17), Redis 7 (CI service)

## Test Infrastructure Fixes (2026-06-13)

### Integration Test Fixes
All 14 ModuleIntegrationTests pass after fixing:

1. **ModuleTestConfig** (`test.config` package):
   - Created shared test config outside component scan root → avoids bean conflicts
   - `@EnableJpaAuditing` — enables `@CreatedDate` for module tests
   - Inline `WebMvcConfigurer` bean (replaces `ApiVersioningConfig`) — API version strategy for module context
   - `@Primary` on `marketplaceProperties()` — resolves conflict with `@ConfigurationProperties` auto-registered bean
   - `@ConditionalOnMissingBean` on `auditorAware()` — uses real bean when present (ALL_DEPENDENCIES mode)

2. **AdminModuleIntegrationTest** (ALL_DEPENDENCIES mode):
   - Removed `@MockitoBean` for `ListingPriceProvider` + `CatalogSearchPort` (both extend `CatalogSpi` → override real `catalogService` bean)
   - Kept `@MockitoBean` for non-CatalogSpi SPIs: `ProviderLookupPort`, `AvailabilityPort`, `PaymentIntentLookupPort`, `BookingParticipantProvider`, `CurrentUserProvider`, `ProviderNameResolver`

3. **LedgerModuleIntegrationTest**: Added `@MockitoBean BookingParticipantProvider`

4. **MessagingModuleIntegrationTest**:
   - Replaced `@MockitoBean MarketplaceProperties` with `@TestConfiguration` inner class providing properly-mocked bean
   - Avoids NPE on `cors()` during `WebSocketConfig` context initialization

5. **application-test.yml**: Added `spring.main.allow-bean-definition-overriding: true`

## Phase 2 — Envers Auditing Hooks (2026-06-13)

All 18 `@Audited` domain entities now have repositories extending `RevisionRepository<Entity, UUID, Integer>`:

- `BookingRepository` — `RevisionRepository<Booking, UUID, Integer>`
- `AvailabilitySlotRepository` — `RevisionRepository<AvailabilitySlot, UUID, Integer>`
- `ProviderAvailabilityRuleRepository` — `RevisionRepository<ProviderAvailabilityRule, UUID, Integer>`
- `ProviderTimeOffRepository` — `RevisionRepository<ProviderTimeOff, UUID, Integer>`
- `CatalogListingRepository` — `RevisionRepository<CatalogListing, UUID, Integer>`
- `ConversationRepository` — `RevisionRepository<Conversation, UUID, Integer>`
- `MessageRepository` — `RevisionRepository<Message, UUID, Integer>`
- `NotificationRepository` — `RevisionRepository<Notification, UUID, Integer>`
- `PaymentIntentRepository` — `RevisionRepository<PaymentIntent, UUID, Integer>`
- `PaymentWebhookEventRepository` — `RevisionRepository<PaymentWebhookEvent, UUID, Integer>`
- `PricingRuleRepository` — `RevisionRepository<PricingRule, UUID, Integer>`
- `ProviderProfileRepository` — `RevisionRepository<ProviderProfile, UUID, Integer>`
- `ReviewRepository` — `RevisionRepository<Review, UUID, Integer>`
- `ProviderBalanceRepository` — `RevisionRepository<ProviderBalance, UUID, Integer>`
- `LedgerEntryRepository` — `RevisionRepository<LedgerEntry, UUID, Integer>`
- `SearchIndexRepository` — `RevisionRepository<SearchIndexEntry, UUID, Integer>`
- `ListingSearchViewRepository` — `RevisionRepository<ListingSearchView, UUID, Integer>`

Plus new `RevisionService` in `marketplace-admin` package for unified audit querying.

## JaCoCo Coverage Fixes (2026-06-13)

| Module | Before | After | Fix |
|--------|--------|-------|-----|
| **marketplace-booking** | 0.61 | 0.70+ | Added 11 new tests: `getByIdForUser`, `listByConsumer/Provider/Status`, `listAll`/`listAllSummaries`, `listByStatusSummary` (string + invalid), `autoCancel` (pending + already), `autoConfirm` (pending + already) |
| **marketplace-ledger** | 0.61 | 0.70+ | Added `LedgerPaymentEventListenerTest` with 3 tests: ignores non-completed events, credits ledger on completion, logs on failure |

## Phase 1 — Critical Integration Links (2026-06-12)

> 6 cross-module business logic integrations implemented.

### 1.1 Booking ↔ Availability
- **Files:**
  - `marketplace-app/.../db/migration/V26__add_booking_time_range.sql` — New Flyway migration: `starts_at`/`ends_at` columns on `bookings`
  - `marketplace-booking/.../Booking.java` — Added `startsAt`/`endsAt` fields with `markBooked()`/`markAvailable()` methods
  - `marketplace-booking/.../BookingService.java` — `create()` now accepts `startsAt`/`endsAt`, calls `AvailabilityPort.isAvailable()` before persisting; `confirm()` calls `AvailabilityPort.bookSlot()`; `cancel()` calls `AvailabilityPort.releaseSlot()`
  - `marketplace-booking/.../BookingController.java` — `CreateBookingRequest` record includes `startsAt`/`endsAt`
  - `marketplace-booking/.../BookingResponse.java` — Exposes `startsAt`/`endsAt`
  - `marketplace-shared/.../api/BookingSummary.java` — Exposes `startsAt`/`endsAt`
  - `marketplace-shared/.../api/AvailabilityPort.java` — Added `bookSlot()`/`releaseSlot()` interface methods
  - `marketplace-availability/.../AvailabilityPort.java` — Implements `bookSlot()` (finds unlocked slot, marks booked) and `releaseSlot()` (marks slot available)
  - `marketplace-availability/.../AvailabilitySlotRepository.java` — Added `findFirstByProviderIdAndStartsAtAndEndsAtAndBookedFalse()` and `findFirstByProviderIdAndStartsAtAndEndsAtAndBookedTrue()`

### 1.2 Payment COMPLETED → Ledger credit
- **Files:**
  - `marketplace-ledger/.../LedgerPaymentEventListener.java` — Listens for `PaymentStateChangedEvent("COMPLETED")`, resolves provider via `BookingParticipantProvider`, calls `LedgerService.creditFromPayment()`

### 1.3 Payment COMPLETED → Booking auto-confirm
- **Files:**
  - `marketplace-booking/.../BookingPaymentEventListener.java` — Listens for `PaymentStateChangedEvent("COMPLETED")`, resolves booking via `PaymentIntentLookupPort`, calls `BookingService.autoConfirm()`
  - `marketplace-booking/.../BookingService.java` — Added `autoConfirm(UUID id)` method (no auth check, internal system call)

### 1.4 Notification → Email pipeline
- **Files:**
  - `marketplace-notifications/.../NotificationService.java` — Injects `Optional<EmailService>`, attempts email send on `onBookingCreated` and `onPaymentStateChanged` (safe when email is unconfigured)

### 1.5 Provider VERIFIED check in Catalog
- **Files:**
  - `marketplace-catalog/.../CatalogService.java` — `create()` now calls `ProviderLookupPort.findById()` and rejects non-VERIFIED providers with `BadRequestException`

### 1.6 Booking CANCELLED → Payment refund
- **Files:**
  - `marketplace-shared/.../api/BookingCancelledEvent.java` — New event record in shared
  - `marketplace-payments/.../BookingCancelledEventListener.java` — Listens for `BookingCancelledEvent`, calls `PaymentsService.autoRefundByBooking()`
  - `marketplace-payments/.../PaymentsService.java` — Added `autoRefundByBooking(UUID bookingId)` (idempotent, finds PaymentIntent by bookingId, marks Payment as REFUNDED, publishes `PaymentStateChangedEvent("REFUNDED")`)

### New/Modified Event Records
- `BookingCreatedEvent` — unchanged
- `BookingCancelledEvent` — new (shared)
- `BookingConfirmedEvent` — new (shared, Sprint 2 P2)
- `ReviewUpdatedEvent` — new (shared, Sprint 2 P1)
- `PaymentStateChangedEvent` — unchanged (used by 1.2, 1.3)
- `BookingSummary` — extended with `startsAt`/`endsAt`

## Completions

- **Sprint 4 — Complete**: Cache invalidation deferred to AFTER_COMMIT (PR #179), EventPublicationCleanup, Conversations cache, Issues #146/#147/#148 closed, Method Security D3 test pattern complete (30/30 rules: negative + positive)
- **Sprint 3 — Complete**: Email transaction semantics aligned with Spring Modulith docs, catalog public-read surface enforced ACTIVE-only, admin all-statuses view restored
- **Sprint 2 — Complete**: Retry semantics (5× `catch(Exception)` removed), `@PreAuthorize` on ProviderController, `CatalogSpi` in GraphQL, `ReviewUpdatedEvent`, `BookingConfirmedEvent`, `save()` consistency
- Upgraded to **Spring Boot 4.1.0**, **Maven 3.9.16**, **JaCoCo 0.8.15**
- **Java 25** target (`--release 25`)
- 6 cross-module integration links implemented and tested
- All verified violations from audit have been fixed
- All 15 business modules have `@NamedInterface` on `package-info.java`
- 8 read-heavy services have `@Cacheable` on entity lookups
- Pricing + Disputes controllers use DTOs with MapStruct (entities no longer exposed)
- `BookingService.cancel()`/`confirm()`/`complete()` have `@Retry` + `@ConcurrencyLimit`
- 5 services annotated with `@Observed` (Observability metrics)
- All 14 controllers have `@WebMvcTest` (44 tests, with OAuth2 auto-config exclusion)
- Spring Data Projections used for read-only endpoints (ListingSimple, ReviewSummary, BookingSummary)
- build-info (`META-INF/build-info.properties`) + git-commit-id (`git.properties`) — `/actuator/info` populated
- Tomcat access log enabled with `%{ms}T` pattern (milliseconds) via `server.tomcat.accesslog.*`
