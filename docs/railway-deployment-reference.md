# Railway Deployment Reference — app-java-v3

> **الحالة (محدّثة 2026-09-04): نشِط — Railway هو منصة الإنتاج الحية**
>
> **النشر الحي:** `https://app-java-v3-production-d020.up.railway.app` (بروفايل prod، نشر **`e38736b6`** من main **`c072cb9`** = #206 truth-sync — شجرة تشغيل متطابقة بايتاً لـ `6a47e066` = #205؛ تحقق حي 2026-09-04 بعد النشر: liveness/readiness 200 UP، jwks RSA `kid=marketplace-jwt`، OIDC discovery 200، `Schema "public" is up to date. No migration necessary.` (صفر ترحيلات جديدة — صحيح: الفرق توثيقي فقط؛ V29+V30 طبّقهما نشر `3e8de67f` السابق من `6a47e066` بإقلاع 11.537s + فحص دخان حي للبحث = 200 على المدخل الذي كان يسبب 500)، إقلاع 11.119s، صفر أسطر ERROR — دفعه المستخدم بيده عبر fork 16:20:51Z)
> **سجل النشر الحالي (سبتمبر 2026):** §0 أدناه — موجة أولى #190–#196 (7 أسباب جذرية) + موجة ثانية #197–#203 (إعادة التصميم على الوثائق الرسمية: Dockerfile الرباعي + AOT + طبقة البيانات PG18/Redis8 + IaC + V28) — §0.5 + **موجة ثالثة #205 (البحث بالدالة الرسمية + V29/V30) — §0.6**
> **ملاحظة حوكمة:** البوابة C (استضافة العملاء) في `docs/security/client-hosting-strategy-plan.md` ما زالت مفتوحة — الإنتاج الحي على Railway لا يُغلقها؛ ARCHITECTURE.md §5 يبقى خط CF المستقبلي.
>
> ---
>
> **التاريخ الأول (أبريل-مايو 2026 — يُحفظ للمرجعية والدروس):** محاولة نشر سابقة على Railway انتهت بـ 20 كوميتًا أُلغيت (تعديلات غير مصرح بها على ملفات أمنية وتكوينية) — من جذور نظام الحوكمة الحالي (§14 من SYSTEM.md). ما يلي الوثيقة الأصلية كما كتبت يومها.

---

## 0. النشر الحالي — سبتمبر 2026 (من أول فشل إلى الإنتاج الحي)

**السياق:** أمر المستخدم بنشر المستودع الجديد (fork `waelhe88-coder/app-java-v3`) على Railway. أول نشرين فشلا في مرحلة البناء. سلسلة الإصلاح الكاملة (سبعة أسباب جذرية بأدلتها — تقرير الجلسة الشامل محفوظ خارج المستودع):

| # | العرض | السبب الجذري | الإصلاح (PR/كوميت) |
|---|---|---|---|
| 1 | فشل البناء مرتين (Nixpacks) | مزوّد Java الرسمي لا يوفر JDK 25 (قائمته 8–21) والمشروع `java.version=25` | `railway.toml`: `builder=DOCKERFILE` (#190/`9dbbbb5`) |
| 2 | «.git directory is not found!» | بناء Railway يرسل لقطة المصدر بلا `.git` | `failOnNoGitDirectory=false` + `failOnUnableToExtractRepoInfo=false` — javadoc الإضافة (#191/`97dc51c`) |
| 3 | «Could not find or load main class …JarLauncher» | Boot 4.1 غيّر تخطيط الطبقات: thin jar وطبقة loader فارغة | ENTRYPOINT `exec java $JAVA_OPTS -jar app.jar` — وصفة dockerfiles الرسمية (#192/`cf0a1a9`) |
| 4 | «Connection to localhost:5432 refused» | `datasource.url` حرفي بلا placeholder (CI أعماه: PG عنده localhost) | `url: ${DB_URL:jdbc:postgresql://localhost:5432/marketplace}` + مجلدات سجلات لمستخدم app (#193/`7328ee0`) |
| 5 | السجل ينقطع صامتًا منتصف Hibernate | قتل cgroup OOM (مقاييس المنصة: USAGE 0.998/1.000) — ZGC+75% فوق ميزانية 953MiB | G1 (افتراضي JDK) + `MaxRAMPercentage=60` (#194/`5f5a301`) |
| 6 | «Bad value for type long : \xaced…» من quartzScheduler | بلا driverDelegate: StdJDBCDelegate يقرأ BYTEA كـ OID (pgjdbc) — أول تشغيل فعلي لمسار JDBC | `QuartzPostgresDelegateConfig` (customizer شرطي على jobStoreType==JDBC — مدخل yml يكسر RAMJobStore) + اختبار Testcontainers (#195/`674372e`) |
| 7 | تسليم keystore JWT للحاوية غير الجذرية | الفوليومات root-owned؛ pre-deploy بلا فوليوم ولا استبقاء؛ ssh مرفوض بالتوكن المحصور (كلها وثائق رسمية) | متغير write-only `JWT_KEYSTORE_B64` يجسّده ENTRYPOINT عند كل إقلاع بكتابة ذرّية (#196/`27b5a155`) |

**البنية الحية (بعد الموجة الثانية):** 5 خدمات: **app-java-v3** (بناء Dockerfile عبر IaC — المستودع بلا ملفات CaC؛ §0.5) + **postgres-18** (قالب postgres-ssl:18 الرسمي + فوليوم 84MB؛ نقل 57 جدولاً صفر فرق) + **redis** (8.2 بالقالب الرسمي: فوليوم + requirepass + RDB) + **postgres-17** (نافذة استرجاع — حذفها بأمر) + **netdiag** (ناقلة النقل — تنظيفها بأمر)؛ 25 متغيرًا على التطبيق (write-only، محفوظة بـ preserve() الرسمية) + فوليوم `/data` (مُصرّح به في IaC؛ فصله بأمر).

**سلسلة نشور main كاملة (GraphQL v2 حي 2026-09-04):** `c82d9831` (27b5a155) ← فشل `bf2b0acd` (41eeb05 — أُصلح جذريًا في #201) ← `6c814e28` (349052b) ← `442c3665` (349052b — قطع طبقة البيانات) ← `51b5496d` (707e052 = #202) ← `80171be7` (f9c5d34 = #203) ← `d41ed3fe` (f988c08 = #204 truth-sync — شجرة تشغيل متطابقة بايتاً) ← `3e8de67f` (6a47e066 = #205) ← **`e38736b6` (c072cb9 = #206 truth-sync — شجرة تشغيل متطابقة بايتاً) — النشط المسجَّل**. **سياسة التقارب (تُوثَّق هنا كسجل حاكم):** أي إعادة بناء لاحقة بفرق توثيقي فقط (`*.md` — يستثنيها `.dockerignore` من سياق البناء ⇒ شجرة تشغيل متطابقة) لا تُسجَّل كنشرة جديدة في السلسلة؛ يُستأنف التسجيل عند أول نشر كودي تالٍ (نشر يحمل تغيير شجرة تشغيل).

### 0.5 الموجة الثانية #197–#203 — إعادة التصميم على الوثائق الرسمية (2026-09-04)

> المبدأ الحاكم (أمر المستخدم): «التزم بالوثائق وابحث عن التصميم الصحيح للنظام ضمنها.. لا أريد ما تراه أنت صحيحًا». كل بند أدناه مُسنَد إلى مصدر رسمي محفوظ أو مُعاد التحقق منه حيًا (2026-09-04).

| PR | التغيير | الأسناد الرسمي (مُتحقق) |
|---|---|---|
| #197/`56b8d5f` | توثيق سلسلة الإصلاح الأولى (هذه الوثيقة §0) | سجلات النشور الحية + أدلة الجلسة |
| #198/`1fa9af1` | تحديث إصدارات الخدمات والأدوات (PG18/Redis8 في CI/compose/Testcontainers؛ `failOnNoGitDirectory=false`) | javadoc الإضافة الرسمية + مصادر الإصدارات الرسمية |
| #199/`19f7cea` | **Dockerfile الرباعي المراحل + قناة keystore B64 في الذاكرة** | مرجع Boot 4.1 Dockerfiles: jarmode=tools + استخراج الطبقات (النسخة الرسمية المحفوظة تحمل الوصفة حرفيًا: `RUN java -XX:AOTCacheOutput=app.aot -Dspring.context.exit=onRefresh -jar application.jar` ← `ENTRYPOINT java -XX:AOTCache=app.aot -jar application.jar`) + وصفات lifecycle لكل اعتمادية (spring-lifecycle-smoke-tests)؛ صيغة Railway للـ cache-mounts `id=s/<service-id>`؛ متغير مشغل `java` الرسمي `JDK_JAVA_OPTIONS`؛ قيود المنصة الموثقة (فوليومات root-owned؛ pre-deploy حاوية منفصلة) ⇒ B64 تُفكّ في الذاكرة لا ملف |
| #200/`41eeb05` | truth-sync سجلات الحوكمة بعد #199 | سجلات الحوكمة الداخلية |
| #201/`349052b` | **علم تدريب AOT يغلق حقن Railway لمتغيرات OTEL** (فشل نشر `bf2b0acd` من 41eeb05 بعد 94 ثانية) | `spring-configuration-metadata.json` لـ spring-boot-opentelemetry 4.1.1 من Maven Central (**أُعيد التحقق حيًا 2026-09-04**: `management.opentelemetry.map-environment-variables` افتراضي **true**) — التعطيل في مرحلة التدريب فقط يغلق قناة الحقن كاملة؛ + دورة إعادة إنتاج/إصلاح محلية حرفية (unix:///dev/otel-grpc.sock → exit 1؛ نفس البيئة + العلم → exit 0) |
| (طبقة البيانات) | **PG 18.6 + Redis 8.2 بالتصميم الرسمي + نقل متحقق** | قوالب Railway الرسمية (postgres-ssl:18 serializedConfig حرفيًا + PGDATA فرعي + SSL_CERT_DAYS؛ redis:8.2 + startCommand الرسمي) + مسار الترحيل الرسمي (pg_dump -Fc --no-acl --no-owner → pg_restore --clean --if-exists -j 4 + ANALYZE + تحقق الأعداد): **57/57 جدولاً صفر فرق** (نقل متحقق مرتين + نافذة قَطع واحدة ~7 دقائق) |
| #202/`707e052` | **ترحيل Config-as-Code → IaC** (قبل الموعد النهائي 2026-12-01) | docs.railway.com/infrastructure-as-code: أداة migrate + DSL رسمي + `preserve()` للأسرار؛ التطبيق `railway config apply` (builder=DOCKERFILE + healthcheckPath + timeout=300 خدمة-مستوى)؛ التحقق الحاسم: نشرة من مستودع بلا ملفات CaC بَنَت بـ Dockerfile (سجل البناء: [build 4/4] بذاكرة cache + [trainer 8/8] AOT) |
| #203/`f9c5d34` | **V28: جدول event_publication_archive الرسمي + حارس Flyway حقيقي** | مرجع Modulith 2.1.1 — الملحق D «PostgreSQL → Archive-enabled schema»: مطابقة عمود-عمود وفهرس-فهرس (hash على serialized_event + completion_date — النسخة الرسمية المحفوظة مُتحقّقة)؛ التحقق الحي في 80171be7: «Migrating schema… to version 28 — modulith event archive» + **صفر أخطاء `event_publication_archive`** (كان سطرين في كل إقلاع سابق) |

**نتيجة الموجة الثانية (مُتحقّقة حيًا في `80171be7`):** الإنتاج = main كامل بلا فجوات؛ إقلاع 10.634s (بروفايل prod + AOT cache)؛ صفر أخطاء أرشفة؛ الفحص الثلاثي أخضر (liveness/readiness/jwks/OIDC).

**المصادر الرسمية المعتمدة للقرارات (كل الموجتين):** مرجع Boot 4.1 Dockerfiles (jarmode=tools + AOT Cache) + javadoc git-commit-id + ملف Quartz `tables_postgres.sql` (PostgreSQLDelegate) + وثائق Railway (volumes: root mount؛ pre-deploy: حاوية منفصلة؛ healthchecks: PORT؛ cache-mounts؛ IaC + preserve()؛ قوالب postgres/redis؛ مسار dump/restore) + مرجع Modulith 2.1.1 (ملحق المخططات) + spring-configuration-metadata.json من Maven Central + Nixpacks/Railpack providers (قوائم JDK) — نسخ محفوظة محلياً (مساحة عمل الجلسة `download/audit2-docs/` + `scripts/doc-verify/`) والاقتباسات داخل المستودع تشير إلى عناوين URL الرسمية.

**ديون معلنة (مراجعة 2026-09-04 بعد الموجة الثانية — القائمة الحية الموثوقة في SYSTEM.md §15):** ~~إهمال Config-as-Code حتى 2026-12-01~~ ✅ مُغلق (#202 — IaC)؛ ~~قسم `[deploy]` الملفي لا ينعكس في manifests~~ ✅ مُغلق (#202 — إعدادات خدمة-مستوى)؛ ~~جدول الأرشفة المفقود~~ ✅ مُغلق (#203 — V28). المفتوحة بيد المستخدم حصراً: حذف `postgres-17` (نافذة استرجاع — يحرر حد الخطة)، تنظيف `netdiag`، فصل فوليوم `/data`، نسخ فوليومات احتياطية (بوابة خطة/UI)، اختيار مزوّد `MAIL_*`/`OTEL_*`، دوران مفتاح JWT قبل **2026-12-02**، تدوير التوكنين (GitHub fork PAT + Railway token — ظهرا نصًا في المحادثة).

### 0.6 الموجة الثالثة #205 — طبقة البحث بالدالة الرسمية + إزالة الآلة الميتة (2026-09-04)

> المبدأ الحاكم (أمر المستخدم): «نظام مدار آليًا من الإطار، لا عبث ولا تدخل وإدارة يدوية — ابنِ وحسّن طبقة بدون كسر الطبقات الأخرى». دفع المستخدم بيده `6a47e066` إلى fork النشر (15:44Z) فبنى Railway تلقائيًا نشر `3e8de67f` (SUCCESS) وطبّق V29+V30 عند الإقلاع.

| البند | التغيير | الأسناد الرسمي (مُتحقق) |
|---|---|---|
| #205/`6a47e066` | **البحث النصي بـ `websearch_to_tsquery('simple', :query)`** (إغلاق عيب 500 الحي: المدخل الخام بـ `(`/`"`/`-` كان يرمي خطأ صياغة tsquery) + عمليات المستخدم الرسمية (عبارة مقتبسة، `OR`، `-استثناء`) | مرجع PostgreSQL 18 §«Parsing Queries»: «simple unformatted text is a valid query» — الدالة الرسمية المصممة لمدخلات المستخدم |
| V29 (ضمن #205) | إسقاط العرض المادي الميت `mv_listing_search` وفهرسيه + حذف صفوف واجبة `searchIndexRefresh` من متجر Quartz JDBC (آلة تُحدّث كل 5 دقائق بلا قارئ واحد — grep) | ترتيب Flyway-قبل-Quartz يضمنه الإطار (`SchedulerDependsOnDatabaseInitializationDetector` — spring-boot-quartz 4.1.1) |
| V30 (ضمن #205) | تسلسل مراجعات Envers `revinfo_seq` (V24 كتبت identity بينما المولّد يستخدم التسلسل — أول كتابة `@Audited` على مخطط الترحيلات كانت ستفشل حيًا) | كشفه حارس يقلع على مخطط Flyway الحقيقي (نمط `QuartzJdbcJobStoreConfigTest` — درس «مخطط الاختبار ≠ مخطط الإنتاج») |
| (حارس الجودة) | `CatalogSearchFullTextIntegrationTest` — دلالات websearch على PostgreSQL حقيقي + `doesNotThrowAnyException` للمدخلات الخاصة + إقلاع على V1..V30 | حارس failsafe دائم: أي جدول إطاري ناقص من الترحيلات يُكشف في CI |

**التحقق الحي (نشر `3e8de67f`، 2026-09-04):** سجل الإقلاع يحمل `Migrating schema "public" to version "29 - remove dead search matview"` + `"30 - envers revision sequence"` + `Successfully applied 2 migrations … now at version v30` + **صفر أسطر ERROR** (وصفر أخطاء أرشفة — V28 مستمر)؛ إقلاع 11.537s ببروفايل prod؛ الفحص الثلاثي أخضر (liveness/readiness/jwks/OIDC)؛ **فحص دخان حي للميزة:** `GET /api/v1/search?q=cleaning (deep) -iron` (مدخل عيب الـ 500 القديم) = **200**، والعبارات المقتبسة و`OR` و`-استثناء` كلها 200.

**نشر المزامنة `e38736b6` (#206 — 2026-09-04 16:20Z):** دفع المستخدم بيده `c072cb9` (كوميت truth-sync توثيقي فقط) إلى fork النشر → Railway بنى تلقائيًا **SUCCESS في 138s** (`wall time` إنشاء→اكتمال؛ `.dockerignore` يستثني `*.md` ⇒ سياق بناء متطابق بايتاً ⇒ ذاكرة BuildKit كاملة — سابقة `d41ed3fe`) → سجل الإقلاع: `Schema "public" is up to date. No migration necessary.` (صفر ترحيلات جديدة — صحيح: V29+V30 طبّقهما `3e8de67f` قبل ساعتين) + إقلاع 11.119s (شجرة تشغيل متطابقة — مطابق لـ 11.537s ضمن التشتت الطبيعي) + **صفر أسطر ERROR** + الفحص الثلاثي أخضر بعد النشر (liveness/readiness/jwks/OIDC). الأدلة محفوظة (سجلات النشرة عبر GraphQL v2 — `deploy-e38736b6-logs.json`).

---

> **⚠️ ما يلي هو التوثيق الأصلي التاريخي (أبريل-مايو 2026 — للمرجعية فقط):**

---

## 1. الخلفية

في 30 أبريل 2026، تم نشر الباك اند على Railway. واجهت عملية النشر سلسلة من المشاكل التشغيلية التي تم حلها بـ 20 كوميت متتالي (من `f4f90f5` إلى `0db439f`). كل هذه الكوميتات تم التراجع عنها في 1 مايو لأنها احتوت تعديلات غير مصرح بها على ملفات أمنية وتكوينية.

---

## 2. المشاكل التي واجهتنا بالترتيب الزمني

### 2.1 البناء فشل — maven-wrapper.jar محجوب

| البند | التفصيل |
|-------|---------|
| **الكوميت** | `f4f90f5` |
| **المشكلة** | `.dockerignore` يحتوي على `.mvn/wrapper/maven-wrapper.jar` مما يمنع الملف من الوصول لصورة Docker |
| **الحل المطبق** | حُذف السطر من `.dockerignore` |
| **ملاحظة** | يجب مراجعة: هل `.dockerignore` يستخدم لـ Docker أم لـ Nixpacks؟ كل بناء يتعامل معه بشكل مختلف |

### 2.2 متغيرات البيئة في application-prod.yml

| البند | التفصيل |
|-------|---------|
| **الكوميت** | `7b283c3` ثم تراجع في `363dde4` |
| **المشكلة** | أول محاولة غيّرت المتغيرات بالكامل لأسماء Railway (DATABASE_URL, PGUSER, PGPASSWORD) وغيّرت Quartz لـ memory و Redis لـ simple |
| **الحل المطبق** | تُراجع بالكامل لأنه ألغى خصائص الإنتاج |
| **الملاحظة الصحيحة** | application-prod.yml يحتاج تعديلات محددة ومعتمدة، لا إعادة كتابة كاملة |

### 2.3 railway.toml — تاريخ من 5 محاولات

| # | الكوميت | المشكلة | ما تم تجربته |
|---|---------|---------|-------------|
| 1 | `35a1ff6` | إنشاء ملف أولي | `[start] cmd = "java -jar marketplace-app/target/*.jar"` — لا يعالج PORT |
| 2 | `965befc` | PORT غير ممرر | `sh -c "java -Dserver.port=$PORT ..."` + healthcheck على `/actuator/health` |
| 3 | `ae860e9` | صيغة خاطئة | استخدم `[deploy] startCommand` بدل `[start] cmd` — Railway لم يتعرف |
| 4 | `1a9058f` | تراجع كامل | رجع للنسخة الأولية بدون PORT |
| 5 | `543c5c1` | healthcheck يفشل | غيّر المسار لـ `/actuator/health/liveness` |
| 6 | `0db439f` | النسخة النهائية | `[start] cmd = "java -jar ... --server.port=$PORT"` |

**الصيغة الصحيحة لـ railway.toml (التي عملت أخيراً):**
```toml
[build]
builder = "NIXPACKS"

[phases.build]
cmd = "chmod +x mvnw && ./mvnw clean package -DskipTests -B -pl marketplace-app -am"

[deploy]
healthcheckPath = "/actuator/health/liveness"
healthcheckTimeout = 300

[start]
cmd = "java -jar marketplace-app/target/*.jar --server.port=$PORT"
```

### 2.4 Railway PORT — 4 محاولات

| # | الكوميت | الطريقة | النتيجة |
|---|---------|---------|---------|
| 1 | `965befc` | `-Dserver.port=$PORT` داخل railway.toml | فشل — ربما مشكلة اقتباس |
| 2 | `cd30649` | `SERVER_PORT=$PORT` كمتغير بيئة | فشل — لم يُقرأ بشكل صحيح |
| 3 | `05f5999` | `SERVER_PORT` مرة أخرى | فشل أيضاً |
| 4 | `3ad2f2f` | `--server.port` كـ CLI arg | نجح |

**ما نعرفه عن Railway PORT:**
- Railway يوفر متغير `PORT` بقيمة ديناميكية (ليس دائماً 8080)
- Spring Boot يقبل `--server.port` كـ CLI argument وهذا الأضمن
- `SERVER_PORT` كمتغير بيئة يعمله Spring Boot لكنه لم يعمل في سياق railway.toml
- الصيغة النهائية: `--server.port=$PORT` داخل sh -c

### 2.5 Dockerfile — PORT handling

| الكوميت | التغيير | السبب |
|---------|---------|-------|
| `ff012ec` | إضافة `SERVER_PORT=${PORT:-8080}` في ENTRYPOINT + `LOG_FILE=/tmp/application.log` | معالجة PORT + إصلاح logback |
| `9ff2581` | إزالة EXPOSE 8080، إضافة debug logging | محاولة فهم مشكلة PORT |

**Dockerfile النهائي الذي عمل (كوميت `c9376fc`):**
```dockerfile
ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=50.0 -XX:MaxMetaspaceSize=256m -XX:+ExitOnOutOfMemoryError"
ENV LOG_FILE="/tmp/application.log"
EXPOSE 8080
ENTRYPOINT ["sh", "-c", "echo \"Starting on PORT=${PORT:-8080}\" && SERVER_PORT=${PORT:-8080} java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
```

### 2.6 Metaspace OOM

| # | الكوميت | القيمة | السبب |
|---|---------|--------|-------|
| 1 | `05c6ded` | `MaxRAMPercentage=60.0 -XX:MaxMetaspaceSize=128m` | تقليل RAM لـ 60% + 128m Metaspace |
| 2 | `c9376fc` | `MaxRAMPercentage=50.0 -XX:MaxMetaspaceSize=256m` | Metaspace 128m لم يكفي — OOM |

**المشكلة:** التطبيق يحتاج Metaspace أكبر من الافتراضي بسبب عدد الكلاسات المحملة (Spring Boot + Authorization Server + Quartz + Modulith).

### 2.7 Quartz isClustered مع RAMJobStore

| البند | التفصيل |
|-------|---------|
| **الكوميت** | `cd40edf` |
| **المشكلة** | `isClustered: true` غير متوافق مع `RAMJobStore` (الافتراضي عندما لا يكون Quartz JDBC مُعدّ بالكامل) |
| **الحل المطبق** | حُذف `isClustered: true` بالكامل من application-prod.yml |
| **ملاحظة مهمة** | هذا الحل غيّر سلوك الإنتاج — يجب أن يُناقش: هل نستخدم JDBC JobStore أم RAM؟ وإذا JDBC فـ isClustered مطلوب |

### 2.8 Health Check يفشل

| # | الكوميت | المشكلة | الحل |
|---|---------|---------|------|
| 1 | `bd1dbfb` | JWT keystore env vars بدون default → فشل التشغيل | أُضيف `:` كـ empty default |
| 2 | `cd40edf` | Redis غير متاح → readiness check فشل | حُذف redis من readiness group |
| 3 | `0db439f` | `/actuator/health` لا يطابق `/actuator/health/liveness` | غيّر SecurityConfig لـ `/actuator/health/**` |
| 4 | `0db439f` | Health group يفشل بسبب missing contributors | أُضيف `validate-group-membership: false` |

---

## 3. ملخص المشاكل الحقيقية (تحتاج حلاً صحيحاً)

### 3.1 مشاكل تشغيلية — تحتاج حل صحيح معتمد

| # | المشكلة | السبب الجذري | ما يجب مناقشته |
|---|---------|-------------|---------------|
| 1 | **PORT ديناميكي** | Railway يعين PORT عشوائياً | كيف نمرره بشكل صحيح لـ Spring Boot؟ |
| 2 | **Metaspace OOM** | التطبيق يحتاج > 128m Metaspace | القيمة الصحيحة لبيئة الإنتاج |
| 3 | **LOG_FILE غير موجود** | logback يحاول الكتابة في `/var/log/marketplace/` | معالجة صحيحة في الحاوية |
| 4 | **Health check path** | Railway يحتاج `/actuator/health/liveness` | SecurityConfig يجب أن يسمح بهذا المسار |
| 5 | **maven-wrapper.jar** | .dockerignore يحجبه | هل نزيله من .dockerignore أم نستخدم بناء مختلف؟ |

### 3.2 مشاكل أمنية/تكوينية — تحتاج نقاش قبل أي تعديل

| # | المشكلة | ما حصل بالأمس | ما يجب فعله |
|---|---------|-------------|------------|
| 1 | **SecurityConfig** | غيّر `/actuator/health` لـ `/**` بدون نقاش | يجب أن يكون ضمن خطة إعادة البناء المعتمدة (4 chains) |
| 2 | **JWT keystore defaults** | أضاف `:` فارغ | يُغيّر سلوك الفشل الآمن — يجب مناقشته |
| 3 | **isClustered** | حُذف بالكامل | هل نستخدم JDBC JobStore أم RAM؟ |
| 4 | **Redis في readiness** | حُذف | هل Redis متاح في Railway؟ إن نعم يجب أن يبقى |
| 5 | **validate-group-membership** | أُضيف `false` | يُخفي مشاكل لا يحلها |

---

## 4. ما يعمل في Railway (النتيجة النهائية قبل التراجع)

عند الكوميت `0db439f`، كان التطبيق يعمل على Railway بالتكوين التالي:

### 4.1 railway.toml
```toml
[build]
builder = "NIXPACKS"

[phases.build]
cmd = "chmod +x mvnw && ./mvnw clean package -DskipTests -B -pl marketplace-app -am"

[deploy]
healthcheckPath = "/actuator/health/liveness"
healthcheckTimeout = 300

[start]
cmd = "java -jar marketplace-app/target/*.jar --server.port=$PORT"
```

### 4.2 Dockerfile (التغييرات فقط مقارنة بالأصلي)
```diff
- EXPOSE 8080 (كان قبل JAVA_OPTS)
+ EXPOSE 8080 (نقل بعد LOG_FILE)

- ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
+ ENV JAVA_OPTS="-XX:+UseZGC -XX:+ZGenerational -XX:MaxRAMPercentage=50.0 -XX:MaxMetaspaceSize=256m -XX:+ExitOnOutOfMemoryError"

+ ENV LOG_FILE="/tmp/application.log"

- ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
+ ENTRYPOINT ["sh", "-c", "echo \"Starting on PORT=${PORT:-8080}\" && SERVER_PORT=${PORT:-8080} java $JAVA_OPTS org.springframework.boot.loader.launch.JarLauncher"]
```

### 4.3 application-prod.yml (التغييرات فقط)
```diff
- isClustered: true (محذوف)
+ validate-group-membership: false (مضاف)
- include: db,redis,diskSpace
+ include: db,diskSpace
- ${JWT_KEYSTORE_PATH}
+ ${JWT_KEYSTORE_PATH:}
(ونفس الشيء لـ 3 خصائص أخرى)
```

### 4.4 SecurityConfig.java (تغيير واحد)
```diff
- .requestMatchers("/actuator/health", "/actuator/info").permitAll()
+ .requestMatchers("/actuator/health/**", "/actuator/info").permitAll()
```

---

## 5. الدروس المستفادة

1. **لا تُعدّل بدون إذن** — كل تعديل في الباك اند يحتاج موافقة مسبقة
2. **لا تُعدّل بدون مرجع رسمي** — كل تغيير يجب أن يستند لتوثيق معتمد
3. **لا تبني حول المشاكل** — ابني بشكل صحيح من الأساس
4. **افصل بين أنواع التعديلات** — أمنية / تشغيلية / بنية تحتية لكل نوع سير عمل مختلف
5. **سجّل كل شيء** — 20 كوميت بـ "fix:" بدون سياق = لا يمكن مراجعتها
6. **اختبر محلياً أولاً** — مشاكل PORT و Metaspace يمكن اكتشافها محلياً
7. **كل patch يُنشئ مشكلة جديدة** — طريقة التجربة والخطأ أنتجت 20 كوميت بدل 3-4 مدروسة

---

## 6. الخطة القادمة (بعد البناء السليم)

عندما نعود لنشر Railway:

1. **أولاً**: إكمال خطة إعادة بناء SecurityConfig (4 chains) بشكل صحيح ومعتمد
2. **ثانياً**: مناقشة كل تعديل تشغيلي بشكل منفصل:
   - PORT: هل نستخدم Dockerfile أم railway.toml؟
   - Metaspace: القيمة الصحيحة
   - LOG_FILE: المعالجة الصحيحة في الحاوية
   - Health check: المسارات الصحيحة ضمن SecurityConfig الجديد
3. **ثالثاً**: مناقشة تعديلات application-prod.yml كل واحد على حدة
4. **رابعاً**: إنشاء railway.toml بشكل صحيح من البداية
5. **خامساً**: نشر مرة واحدة — بناء صحيح لا 20 محاولة
