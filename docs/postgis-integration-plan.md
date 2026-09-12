# خطة إضافة PostGIS — البحث الجغرافي بنصف القطر (فتح بوابة G-R2 المقيسة)

> **الحاكمة لإضافة PostGIS إلى البنية القائمة.** مُصمَّمة بأمر المستخدم (2026-09-13): «صمم خطة لاضافة postgis. ابحث في الوثائق الرسمية حول كل مايتعلق بpostgis. أفهم بنية وتصميم النظام والطبقات وتفاعلها، وآلية عمله أولا. ثم حدد ماذا ستفعل وماهي الأماكن التي سيمسها التغيير… تأكد أن الخطة والتغيير ملتزم تمام بتصميم النظام والبيانات الحية من الوثائق الرسمية».
>
> **موقعها من عائلة الخطط:** الخطة الحاكمة الخامسة (بعد الأمن، العملاء والاستضافة، التوسع `docs/feature-expansion-roadmap.md`، أنظمة العقار `docs/realestate-systems-plan.md`). **هذه الخطة تفتح البوابة التي أغلقتها الخطة الرابعة بقياس:** بوابة G-R2 (§7 من الخطة الرابعة) وثّقت أن PostGIS «غير متاح في قالب postgres القياسي — قياس حي على `pg_available_extensions`» وأن البحث بنصف قطر «يتطلب صورة منصّية خاصة». أمر المستخدم الصريح هذا هو كلمة فتح البوابة بيد مالكها (AGENTS §0.4) — الخطة تُصمَّم هنا والتنفيذ يبدأ بكلمة لاحقة.
>
> **قاعدة الخطة الذهبية (وراثةً):** كل حقيقة أدناه إما قياس من الكود/الإنتاج الحي هذه الجلسة بدليله، أو اقتباس من مصدر رسمي محفوظ محليًا. صفر اجتهاد بلا دليل، وصفر افتراض في ما يمكن قياسه.

---

## 0. نقطة الانطلاق — مقيسة لا مفترضة

### 0.1 قياسات المستودع (رأس العمل = كداسة L30-L33 فوق main)

> التنفيذ الحي للخطة الرابعة جارٍ: **main = `2f432226`** (الخطة الرابعة فقط)، **PR #297 مفتوح** (L30 — فرع `feat/geo-hierarchy-l30`)، وL31-L33 مكدَّسة محليًا فوقه (فرع `feat/listing-expiry-l33`، 6 كوميتات: L30 + تبنّي CodeRabbit + L31 + L32 + L33 + اختباراته). **تنفيذ هذه الخطة يشترط إقفال تلك الكداسة أولًا** (جدولها مرجعي — الشيفرة تعتمد على V48 ومسار L32).

| الحقيقة | الدليل |
|---|---|
| Reactor = 19 وحدة Maven (17 + geo + realestate من الكداسة) | `pom.xml:22-40` |
| آخر ترحيلة على main = **V46**؛ على الكداسة = **V49** (geo/property/expiry) — أرقام هذه الخطة **تخطيطية (V50+)** تُعتمد من العداد آنذاك | `marketplace-app/src/main/resources/db/migration/` (نمط D-R9) |
| **بوابة PostGIS موثَّقة في الشيفرة نفسها:** «PostGIS is NOT in pg_available_extensions on the standard postgres distribution — it requires a postgis-specific image… Geographic search is therefore NOT attempted here» | `V34__search_trgm_fallback.sql:17-20` |
| **إحداثيات قائمة بلا PostGIS:** `property_details.latitude/longitude` — `NUMERIC(9, 6)` مع CHECKs نطاق (-90..90 / -180..180) + تحقق المصنع بالقيم نفسها — الجافادوك: «Display-only coordinate (G-R5: client-side maps; never radius search)» | `V48__property_details.sql:35-60` + `PropertyDetails.java:118-186` |
| **نمط مجموعة التقييد القائم:** `RealestatePropertyFilterPort` (4 عمليات: مجموعتان مقيدتان + صفحتان بترتيب area) يجيب بـ**مجموعة معرفات لوحات** من جدول الوحدة نفسها — والبحث يركّبها على استعلام الكاتالوج — الجافادوك يسمّيه «the house restricted-path discipline» | `marketplace-shared/…/RealestatePropertyFilterPort.java` + `PropertyFilterAdapter.java` |
| المحوّل يستعلم بـ**Specifications الرسمية** (لا استعلامات يدوية) على مستودع الوحدة | `PropertyFilterAdapter.java:42-68` |
| **النمط البيتي للاستعلام الأصلي:** `@Query(nativeQuery = true)` على مستودع الوحدة مع مسند الحذف الناعم مكتوبًا صريحًا (لا يمر عبر `@SoftDelete` الآلي) — سابقة FTS/trgm في الكاتالوج | `ProviderListingRepository.java:58-75` (searchFullText) |
| مسار البحث: `SearchCriteria` (12 مكوّنًا) بفلسفة **بوابة النوع** (القيمة غير الصالحة ⇒ 400 عند البناء) + توزيع legacy/property في `SearchService` + حل `GeoLookupPort.findSelfAndDescendants` للهرم | `SearchCriteria.java` + `SearchService.java:110-200` |
| **المخبأة `search-results-v3`** (رقمها L32) بمولد مفتاح injective وثيقة bump (PREFIX يُرفع مع كل تغيير مكوّن) — «Bump together with the other three names» | `SearchService.java:89,109` + `SearchCriteriaCacheKeyGenerator.java:30-40` |
| **27 ملف اختبار تكاملي** يحمل الصورة `postgres:18-alpine` حرفيًا (`DockerImageName.parse`) | قياس grep هذه الجلسة (27 ملفًا في `marketplace-app/src/test/`) |
| CI: خدمة postgres `postgres:18-alpine` (job البناء والتكامل) | `.github/workflows/ci.yml:23-27` |
| compose المحلي: `postgres:18-alpine` + فوليوم `pgdata18` على `/var/lib/postgresql` (تعليق CodeRabbit #241 يوثّق نقل PG18 للمسار المُصدَر) | `docker-compose.yml:3-19` |
| Testcontainers: `testcontainers-postgresql` (خط 2.x عبر BOM) | `marketplace-app/pom.xml:220-224` |
| Modulith: الحدود محروسة بـArchUnit/Modulith verification (`allowedDependencies`) — geo/realestate تعتمد `shared` حصرًا | `package-info.java` في الوحدتين |

### 0.2 قياسات الإنتاج الحية (سكربت هذه الجلسة `scripts/postgis_gate_probe.py` — قراءة صرفة عبر GraphQL v2)

| الحقيقة | الدليل |
|---|---|
| **خدمة `postgres-18` هي ImageSource:** `source.image = ghcr.io/railwayapp-templates/postgres-ssl:18`، builder=RAILPACK، بلا startCommand — أي **نفس آلية «خدمة من صورة سجل»** التي ستحمل postgis | قياس GraphQL: `serviceInstance.source.image` |
| **الفوليوم مركّب على المسار القديم:** `mount = /var/lib/postgresql/data` (120.4MB) — قالب postgres-ssl يستخدم **PGDATA فرعيًا** تحت المسار القديم (موثَّق: «postgres-ssl:18 serializedConfig حرفيًا + PGDATA فرعي + SSL_CERT_DAYS») | قياس GraphQL: `volumeInstances` + `docs/railway-deployment-reference.md:44` |
| التطبيق يصل `postgres-18.railway.internal` ببيانات اعتماد marketplace القائمة؛ **قيم المتغيرات write-only بتصميم المنصة** (لا تُقرأ عبر API) | `SYSTEM.md §15` (تصميم متغيرات التطبيق) |
| سابقة النقل الرسمية: **57/57 جدولًا صفر فرق** بمسار `pg_dump -Fc --no-acl --no-owner` → `pg_restore --clean --if-exists -j 4` + `ANALYZE` + تحقق أعداد، نافذة قَطع ~7 دقائق (نقل PG17→18) | `docs/railway-deployment-reference.md:44` + runbook النسخ `docs/operating/manual-db-backup-runbook.md` |
| القيمة التي لا يمكن قراءتها الآن (وتُقاس في P0): هل `DB_URL` يحمل `sslmode=` (القالب SSL؛ runbook النسخ يستخدم `sslmode=require` صراحةً كقناة القياس الرسمية) | `docs/operating/manual-db-backup-runbook.md` (تبنّي CodeRabbit #266) |

### 0.3 الوثائق الرسمية المقيسة (جُلبت حية هذه الجلسة — الاستشهاد الأساسي: الرابط الرسمي المستقر + تاريخ الجلب + الاقتباس الحرفي)

> المستخدم طلب «ابحث في الوثائق الرسمية حول كل مايتعلق ب postgis» — أدناه ما حُمل ووُقرأ نصًّا (لا مقتطفات ثانوية). **قابلية التكرار من هذا المستند نفسه:** كل صف يحمل **رابطًا رسميًا مستقرًا** وتاريخ الجلب والاقتباس الحرفي — أي قارئ يطابق النص عند المصدر دون اعتماد على مساحة جلسة أحد. (النسخ الخام للأرشفة التشغيلية محفوظة خارج المستودع في مساحة جلسة التصميم — ملحق داعم لا مرجع.)

| المصدر الرسمي (الرابط المستقر) | الحقيقة الحرفية (مجلوبة 2026-09-13) |
|---|---|
| **PostGIS — مصفوفة التوافق والسند** — https://postgis.net/development/compatibility/ (تُولَّد من مستودع PostGIS المصدر، آخر توليد 2026-09-12) | **PostGIS 3.6 خط مستقر (آخر ترقيع 3.6.4 صدر 2026-06-08) ويدعم PostgreSQL 18 برتبة W (works, supported)**؛ 3.5 مع PG18 = C (compatible, not a recommended target)؛ 3.7 = beta تطويري. PG18 نفسه «supported 2025 / 1 year until 2030» |
| **postgis/docker-postgis** — README — https://github.com/postgis/docker-postgis (2026-06-19) | الوسوم: **`18-3.6`** (debian:trixie — PG18 + PostGIS 3.6.4) و**`18-3.6-alpine`** (alpine:3.24) — «Recommended versions for new users» يقدّم `18-3.6`. الصورة **مبنية على صورة postgres الرسمية** وتحترم متغيراتها كلها (POSTGRES_DB/USER/PASSWORD/PGDATA…) |
| نفس المصدر — قسم «Initialize Only on Empty Data Directory» | متغيرات التهيئة (POSTGRES_DB/USER/…) **تعمل فقط عند إقلاع أول مع مجلد بيانات فارغ** — «Any pre-existing database will be left untouched on container startup» ⇒ التبديل على فوليوم قائم **لا ينشئ الامتداد تلقائيًا** |
| نفس المصدر — initdb-postgis.sh — https://github.com/postgis/docker-postgis/blob/master/initdb-postgis.sh | عند التهيئة الأولى: `CREATE EXTENSION IF NOT EXISTS postgis; postgis_topology;` في **template_postgis و$POSTGRES_DB** بحساب $POSTGRES_USER |
| نفس المصدر — قسم «⚠️ PGDATA Volume Path Change» | «Starting from PostgreSQL 18, the default data directory (VOLUME) path has changed» — وسوم 18+ تحمل `/var/lib/postgresql` (نفس التوثيق البيتي في compose/#241) |
| **صورة postgres الرسمية — وثائق POSTGRES_USER** — https://github.com/docker-library/docs/blob/master/postgres/README.md | «This variable will create the specified user **with superuser power**» — مستخدم POSTGRES_USER **خارق بنص الصورة الرسمية** (القالب الحالي مبني على العائلة نفسها) |
| **PostgreSQL 18 — CREATE EXTENSION** — https://www.postgresql.org/docs/18/sql-createextension.html | «For many extensions this means superuser privileges are needed. However, if the extension is marked **trusted** in its control file, then it can be installed by any user who has CREATE privilege» — pg_trgm موثوقة (سابقة V34)، **postgis غير موثوقة ⇒ تتطلب خارقًا** |
| **PostGIS — Getting Started** — https://postgis.net/documentation/getting_started/ | «Connect to your database as the **postgres user or another super-user account**, and run: `CREATE EXTENSION postgis;`» — تأكيد رسمي ثانٍ لمطلب الخارق |
| **PostGIS — Tips: Use ST_DWithin for radius queries** — https://postgis.net/documentation/tips/st-dwithin/ | «For queries that involve finding "things within distance X of other things"… **use ST_DWithin** for filtering. **Do not use ST_Distance or ST_Intersects with ST_Buffer**. ST_DWithin **uses a spatial index (if available)**… supported for both geometry and geography types» |
| **دليل PostGIS §4.3 — Geography** — https://postgis.net/docs/using_postgis_dbmanagement.html#Geography | الجغرافيا إحداثيات كروية (lon/lat)، **SRID الافتراضي 4326**، الحسابات على الأقواس العظمى — أدق على سطح الأرض |
| **دليل PostGIS §4.3.3 — When to use the Geography data type** — الرابط أعلاه | «If your data is contained in a small area… GEOMETRY might be the best solution in terms of performance… If you don't understand projections and don't want to learn… GEOGRAPHY might be easier» — قرارنا (D-P4) يحسم التوازن لنطاقنا |
| **دليل PostGIS §4.9.1 — Spatial Indexes (GiST)** — https://postgis.net/docs/using_postgis_dbmanagement.html#id-1.4.7.9 | `CREATE INDEX … USING GIST (col)`؛ «Building a spatial index is a computationally intensive exercise. It also **blocks write access** to your table… on a production system you may want to do it in a slower **CONCURRENTLY**-aware way»؛ وبعدها «force PostgreSQL to collect table statistics: **VACUUM ANALYZE**» |
| **مصدر PostGIS — geography.sql.in** — https://github.com/postgis/postgis/blob/master/postgis/geography.sql.in | `CREATE FUNCTION geography(geometry) … LANGUAGE 'c' **IMMUTABLE** STRICT PARALLEL SAFE` + `CREATE CAST (geometry AS geography) WITH FUNCTION geography(geometry) AS IMPLICIT` — **التحويل IMMUTABLE** ⇒ تعبير `ST_SetSRID(ST_MakePoint(lng,lat),4326)::geography` (كله IMMUTABLE: ST_MakePoint وST_SetSRID كذلك — `postgis.sql.in:1916,3051`) **مؤهل قانونيًا لفهرس تعبيري GiST** |
| **Testcontainers — صفحة Postgres Module الرسمية** — https://java.testcontainers.org/modules/databases/postgres/ | النمط الرسمي للصور المتوافقة: `new PostgreSQLContainer(DockerImageName.parse("postgis/postgis:16-3.4-alpine").asCompatibleSubstituteFor("postgres"))` — أي وسم postgis/postgis يُستعمل عبر PostgreSQLContainer القائم نفسه (لا وحدة إضافية) |
| **Railway — Services** — https://docs.railway.com/services (قسم Deploying a public Docker image) | «Railway can deploy images from **Docker Hub**, GitHub Container Registry, Quay.io, or GitLab Container Registry» — نفس آلية الخدمة الحالية (قالب postgres-ssl صورة من ghcr)؛ متابعة الوسوم مدعومة |
| **PostgreSQL — إعلان الإصدارات** — https://www.postgresql.org/docs/release/ | PostgreSQL 18.6 صدر 2026-08-13 (الإنتاج على 18.6 — SYSTEM.md §1) |

---

## 1. القرارات الحاكمة (مقيسة من الكود والوثائق الرسمية — لا اجتهاد)

| # | القرار | الأساس |
|---|---|---|
| **D-P1** | **الفهرس تعبيري لا عمود جغرافي:** إحداثيات `latitude/longitude` الرقمية (V48) **تبقى التمثيل الوحيد ومصدر الحقيقة الواحد**، والبحث بنصف قطر يعمل عبر **فهرس GiST تعبيري** على `ST_SetSRID(ST_MakePoint(longitude, latitude), 4326)::geography` — لا عمود `geog`، لا backfill، لا ترقّب كتابة، لا مرآة Envers جديدة. الفهرس يُصان تلقائيًا مع كل كتابة (سلوك فهارس التعبير في PostgreSQL) | تحويل `geography(geometry)` IMMUTABLE (مصدر PostGIS §0.3) + فلسفة مصدر الحقيقة الواحد + فلسفة «لا تغيير جذري» (أمر المستخدم الموجه) |
| **D-P2** | **صفر اعتماديات جديدة: لا hibernate-spatial ولا JTS ولا أي مخطط JPA مكاني.** استعلام نصف القطر **استعلام أصلي** (`@Query(nativeQuery=true)`) على مستودع وحدة realestate نفسها — نمط searchFullText/trgm القائم حرفيًا (مسند الحذف الناعم صريحًا) | السابقة البيتية للسؤال الأصلي + مبدأ «الباك اند مرساةً» (AGENTS §0.3) + hibernate-spatial سيجلب JTS وربط الكيانات بنوع مكاني = تغيير جذري بلا حاجة (البحث يجيب بمجموعة معرفات لا بكيانات) |
| **D-P3** | **المنفذ نفسه يتوسع لا وحدة جديدة:** عمليات نصف القطر (المجموعة، المقيد، المُصفّح بترتيب المسافة) تُضاف إلى `RealestatePropertyFilterPort` القائم — **مالك الجدول (realestate) يجيب**، والبحث يركّب — نمط مجموعة التقييد D-E6 الموثق | `RealestatePropertyFilterPort.java` (العقد القائم) + PropertyFilterAdapter (المحوّل) — الحدود محروسة كما هي |
| **D-P4** | **`ST_DWithin` على geography(4326) حصراً** (المسافة بالمتر، قوس كبير) — لا ST_DDistance ولا ST_Buffer، ولم نستعمل geometry بمسقط محلي: النطاق محلي (قدسيا) لكن الإحداثيات WGS84 الخام من الواجهة (G-R5 خريطة العميل تعمل بها مباشرة)، ودقة القوس العظيم عند هذا النطاق تفوق الحاجة بمراحل، وكلفة geography الإضافية (دليل §4.3.3) غير ذات أثر عند حجم كتالوجنا | Tip الرسمي (§0.3) + «When to use Geography» + G-R5 من الخطة الرابعة |
| **D-P5** | **الترحيلة V50 (تخطيطي):** `CREATE EXTENSION IF NOT EXISTS postgis;` + فهرس GiST **جزئي** (`WHERE latitude IS NOT NULL AND longitude IS NOT NULL` — الصفوف بلا إحداثيات خارج الفهرس، مطابقة استعلام) + `ANALYZE property_details` (توصية الإحصاءات الرسمية؛ ANALYZE آمن معاملةً بخلاف VACUUM). **بلا CONCURRENTLY في هذه الطبقة** — الجدول فتيّ صغير والقيود الشكلية مغطاة بنافذة نشر Railway الموثقة (V44/V47 سابقة)؛ عتبة الترقية إلى CONCURRENTLY دين (D-I1) | دليل §4.9.1 + سابقة الفهارس V9/V34/V47 (كلها CREATE INDEX داخل الترحيلة) |
| **D-P6** | **معايير نصف القطر بنفس فلسفة «بوابة النوع»:** `latitude`/`longitude`/`radiusKm` اختيارية في `SearchCriteria` — الحضور الجزئي (إحداثية واحدة أو نصف قطر بلا إحداثيات) ⇒ **400 عند البناء**؛ النطاقات مطابقة لقيود V48 والمصنع (-90..90، -180..180) ونصف القطر (0، 50] كم — سقف معايرة أولية بيد المستخدم | `SearchCriteria.java` (نمط I6/L27) + قيود V48 القائمة |
| **D-P7** | **صورة واحدة لكل البيئات — عائلة `postgis/postgis:18-3.6`:** الإنتاج `18-3.6` (Debian — توصية المستودع الرسمي «Recommended for new users»)، وCI/compose/Testcontainers `18-3.6-alpine` (توازي عائلة alpine القائمة في هذه الطبقات) — الاختلاف نظام التشغيل وحده؛ الشيفلة والترحيلات واحدة | README docker-postgis (§0.3) + الوضع القائم (CI/compose/TC على alpine والإنتاج على Debian عبر القالب — الانقسام نفسه قائم أصلًا) |
| **D-P8** | **ترتيب الإقفال إجباري: صورة الإنتاج تبدَّل قبل دمج V50 على main.** بعد الدمج، أول إقلاع نشرة يشغّل Flyway V50 — إن غاب PostGIS عن الإنتاج فشل الإقلاع (فشل نشر، الخدمة القديمة تبقى تخدم — لكنه كسر نشر يجب ألا يقع). تسلسل الخطة (§4) يجعل P0 (البنية) قبل P1 (الشيفرة) | آلية نشر fork-sync الموثقة (SYSTEM.md §15) + سلوك Flyway الحاجب للإقلاع |
| **D-P9** | **الإنتاج بخيارين مقيسَين لا مفاضلة مسبقة** (§4.1): **P-1 تبديل الصورة في المكان** (نفس الخدمة/الفوليوم/الاسم ⇒ صفر متغيرات تتغير) أو **P-2 خدمة جديدة + النقل الرسمي** (سابقة 57 جدولًا). الحسم بقياسات P0 (إصدار PG الصورة ≥ 18.6، شكل PGDATA، حالة SSL) — لا بافتراض | قياسات §0.2 + وثائق Railway (§0.3) + قاعدة «قياس لا افتراض» |
| **D-P10** | **الهرم الإداري (L30) لا يُستبدل ولا يُلمس:** نصف القطر **معيار بحث إضافي** يتزامن (AND) مع locationId الهرمي ومع بقية المعايير — D-R1 يبقى حاكمًا للتصفح والتصنيف؛ وGeoLookupPort ووحدة geo وترحيلة V47 كلها خارج المس | D-R1 (الخطة الرابعة) + بنية التوزيع القائمة في SearchService |
| **D-P11** | **مسافة العرض على العميل، المسافة للترتيب على الخادم:** الاستجابة (ListingSummary) **لا تكتسب حقل مسافة** — العميل يحسبها من إحداثيات اللوحة المعروضة أصلًا (حقول L31 في جرد اللوحة)؛ الخادم يرتب `sort=distance` عبر استعلام أصلي مرتّب (`ORDER BY ST_Distance … , listing_id`) بالعقد المُصفّح نفسه (نمط searchAreaSorted) | G-R5 + نمط area-sort القائم (مجموعة كاملة من الترتيب داخل الوحدة + جلب الملخصات بذاك الترتيب) |
| **D-P12** | **مفتاح المخبأة يُرفع مع كل تغيير المكوّن:** `search-results-v3 → v4` + رفع PREFIX مولد المفتاح (l32→l34 كمرحلة) + مقاطع إحداثيات مطبَّعة (scale-6 وradius مترًا صحيحًا) تبقي المفتاح injective | سابقة D-E6/#241 (تعليق «Bump together with the other three names») |

| **D-P13** | **فصل هوية الترحيل عن هوية التشغيل (تحصين امتيازات — تبنّي مراجعة CodeRabbit ج1):** Flyway اليوم يشارك `spring.datasource` هوية التطبيق نفسها (لا `spring.flyway.url/user/password` معرّفة). الخطة تجعل تنفيذ P0/P1 يضيف **هوية ترحيل مخصّصة** خارقة عبر `spring.flyway.user/password` (متغيرا بيئة write-only بنمط البيت) ويحوّل هوية التطبيق إلى **NOSUPERUSER بعد اكتمال التبديل والترحيلة** (مبدأ الامتياز الأدنى — الهوية التي تشغّل SQL المستخدم لا تحتاج خارقًا؛ CREATE EXTENSION مسؤولية الترحيل وحدها). قبل التحويل: قياس أثر NOSUPERUSER على التشغيل (أي اعتماد خفي على الخارق — الفحوص P0.5) | مراجعة CodeRabbit (CWE-250) + وثائق Boot الرسمية لـ`spring.flyway.*` + نمط متغيرات write-only القائم (25 متغيرًا) |

---

## 2. خريطة «ماذا يُلمس / ماذا لا يُلمس» — بالتوصيات الرسمية

> المستخدم طلب: «ابحث عن التوصيات الرسمية حول كيفية التنفيذ وماذا يجب لمسه وماذا لا يجب لمسه». التوصية الرسمية تُنقل حرفية أينما وجدت؛ وما لا توصية فيه يُحسم بنمط البيت الموثق.

### 2.1 ما يجب لمسه (وكلٌ بسند رسمي)

| المكان | التوصية الرسمية الحرفية | الإجراء في خطتنا |
|---|---|---|
| **صورة قاعدة البيانات** (الإنتاج/CI/compose/TC) | postgis/postgis README: «provides tags for running Postgres with PostGIS extensions installed… **based on the official postgres image**» + Railway: «Railway can deploy images from Docker Hub…» | تبديل عائلة الصورة إلى 18-3.6 (P-1/P-2 للإنتاج — §4.1) |
| **تفعيل الامتداد** | Getting Started: «CREATE EXTENSION postgis» كخارق — ودليل الصورة: initdb ينشئها في POSTGRES_DB **عند التهيئة الأولى فقط** | `CREATE EXTENSION IF NOT EXISTS postgis;` في V50 — لا-عملية (no-op) في البيئات الجديدة (الصورة أنشأتها)، وإنشاء صريح في سيناريو التبديل في المكان (قيمة IF NOT EXISTS تحل الازدواج) |
| **استعلام نصف القطر** | Tip الرسمي: «**use ST_DWithin** for filtering… **uses a spatial index (if available)**… Do not use ST_Distance or ST_Intersects with ST_Buffer» | `ST_DWithin(geography, geography, meters)` — الاستعلام الأصلي في مستودع الوحدة (D-P2/D-P4) |
| **الفهرس المكاني** | دليل §4.9.1: `CREATE INDEX … USING GIST` — «blocks write access… on a production system you may want to do it in a slower CONCURRENTLY-aware way» | فهرس GiST تعبيري جزئي في V50 (D-P5) + CONCURRENTLY خلف عتبة دين (D-I1) — الجدول فتيّ |
| **إحصاءات المخطط بعد الفهرس** | دليل §4.9.1: «force PostgreSQL to collect table statistics… **VACUUM ANALYZE**» | `ANALYZE property_details` داخل الترحيلة (آمنة معاملةً) + autovacuum القائم يغطي لاحقًا |
| **صورة الاختبارات** | Testcontainers الرسمية: `postgis/postgis… asCompatibleSubstituteFor("postgres")` عبر `PostgreSQLContainer` القائم | تبديل السلسلة الحرفية `postgres:18-alpine` في 27 ملفًا إلى `postgis/postgis:18-3.6-alpine` مع `asCompatibleSubstituteFor("postgres")` (نمط البيوت الحالي: `DockerImageName.parse(...)`) |
| **بوابة النوع للمعايير الجديدة** | (نمط البيت — لا توصية منشورة) | D-P6: بناء السجل يرفض غير الصالح ⇒ 400 قبل أي استعلام |
| **المخبأة عند تغيّر المكوّنات** | (سابقة البيت الموثقة #241) | D-P12: v3→v4 + رفع PREFIX المولد |

### 2.2 ما لا يجب لمسه (ومنع كلٌ بسنده)

| المكان | لماذا يُمنع | السند |
|---|---|---|
| **الترحيلات المنصهرة V1..V49** | قاعدة AGENTS الصريحة: «Any schema change = new V{number} migration file. **Never modify existing migrations**» — وV34 تحديدًا وثّقت البوابة التي تفتحها هذه الخطة، فتعديلها تزوير للسجل | AGENTS.md (Flyway) |
| **كيان `PropertyDetails` وكل الكيانات** | تصميم D-P1/D-P2: صفر أنواع مكانية في JPA (لا JTS/hibernate-spatial) — الكيان يبقى BigDecimal(9,6) بتحقق المصنع؛ و«Display-only» في جافادوكه تتحول دلالتها إلى «مصدر الإحداثيات للبحث أيضًا» بتعديل الجافادوك حصرًا (لا الشيفلة) | قرار مقيس من مصدر PostGIS (IMMUTABLE cast يجعل العمود غير لازم) |
| **مرايا Envers `_aud`** | لا تغيير أعمدة في الجداول الأساسية ⇒ صفر أثر على Envers (درس V33: الأعمدة اليتيمة تكسر INSERTs — هنا لا يتيمة) | سابقة V33/V24 |
| **مسارات البحث القائمة FTS/trgm/الSpecifications** | المعايير بلا نصف قطر يجب أن تتصرف **بايت-بايت** كما الآن (dispatch يعود للفرع القديم) — نصف القطر فرع إضافي فقط؛ ترحيلات V9/V34 وفهارسها لا تُمس | سابقة «byte-identical» (dispatchLegacy في L32) |
| **وحدة geo وشجرتها V47** | D-P10: الهرم مصدر التصنيف والتصفح؛ نصف القطر معيار بحث — لا علاقة عضوية | D-R1 (الخطة الرابعة) |
| **حدود Modulith / استعلام عابر للوحدات** | الاستعلام الأصلي يعمل على جدول الوحدة نفسها (property_details) ويجيب بمعرفات — لا استعلام عبر حدود أبدًا (نفس انضباط المحوّل القائم) | نظام Modulith + ArchUnit الحارس |
| **متغيرات الإنتاج مع الخيار P-1** | نفس الخدمة/الاسم ⇒ DB_URL وبيانات الاعتماد كما هي — المتغيرات write-only ولا سبب لتحريكها | قياس §0.2 (ImageSource في المكان) |
| **آلة الحالة ListingStatus / دورة الانتهاء L33 / كل الأنظمة المكملة** | نطاق هذه الخطة طبقة بيانات+بحث واحدة — لا شيء آخر | أمر المستخدم (إضافة postgis) |
| **السقف الصلب للمصروف**: postgis_topology/raster/sfcgal** | initdb الصورة ينشئ postgis_topology في البيئات الجديدة (طبيعة الصورة — يُوثَّق ولا يُستهلك)؛ raster/sfcgal «available not initialized» بنص README — **لا نستهلكها ولا نفعّلها** | README docker-postgis (جدول الامتدادات) |

---

## 3. خريطة المس الكاملة — وكيف تتأثر وكيف تؤثر في النظام

> «الأماكن التي سيمسها التغيير» (طلب المستخدم) — بترتيب الطبقات من الأسفل، وكل صف: ماذا يتغير، كيف يتأثر المكان، أثره على النظام كله.

| # | الطبقة/الملف | التغيير | كيف يتأثر المكان | الأثر النظامي |
|---|---|---|---|---|
| 1 | **خدمة postgres-18 على Railway** | مصدر الخدمة من `ghcr.io/…postgres-ssl:18` إلى `postgis/postgis:18-3.6` (P-1) أو خدمة جديدة P-2 | نفس PG الرئيسي (18) — ثنائيات الصورة أحدث/مساوية، الامتدادات تصبح متاحة، بيانات الفوليوم كما هي (P-1) أو تُنقل بالمسار الرسمي (P-2) | **يفتح البوابة كلها**؛ أثناء النافذة: انقطاع اتصال قصير يسبقه تطبيق Hikari يعيد المحاولة — سابقة نافذة 7 دقائق مقيسة |
| 2 | **docker-compose.yml** | `postgres:18-alpine` → `postgis/postgis:18-3.6-alpine` (POSTGRES_DB يبقى marketplace — الامتداد ينشأ عند init الأولى للصورة) | فوليوم pgdata18 القائم يحمل عنقودًا بلا الامتداد ⇒ المتغيرات **لن تعمل** (قاعدة Empty-Data-Directory) — `docker compose down -v` محليًا يخلق تهيئة جديدة تحمل الامتداد تلقائيًا | بيئة المحلي تعادل CI/الإنتاج — دورة المطورة كاملة |
| 3 | **.github/workflows/ci.yml** | صورة خدمة postgres في job البناء/التكامل → `postgis/postgis:18-3.6-alpine` | الخدمة تُنشأ طازجة كل تشغيل ⇒ initdb-postgis يعمل ⇒ **الامتداد حاضر قبل أول Flyway** (V50 no-op) | كل اختبار تكاملي على PG حقيقي يقيم على الشيفلة الجديدة — V50 يصبح حارسًا (أي بيئة تفتقد الصورة تفشل صراحةً) |
| 4 | **27 ملف اختبار تكاملي** | `DockerImageName.parse("postgres:18-alpine")` → `DockerImageName.parse("postgis/postgis:18-3.6-alpine").asCompatibleSubstituteFor("postgres")` | نفس PostgreSQLContainer الحرفي — الوسم فقط يتبدل (النمط الرسمي الموثق) | تغيير ميكانيكي متطابق النمط؛ صفر منطق اختبار يتغير |
| 5 | **V50 (ترحيلة جديدة)** | `CREATE EXTENSION IF NOT EXISTS postgis` + فهرس GiST تعبيري جزئي + `ANALYZE` | المخطط يكسب فهرسًا واحدًا لا عمودًا — `flyway validate-on-migrate` (مقيس) يقبلها | **عقد الصورة الجديد يصبح إجباريًا لكل بيئة تحرّك هذه الترحيلة** — وهذا مقصود (حارس بيئات) |
| 6 | **`RealestatePropertyFilterPort` (shared-api)** | 3 عمليات: `findListingIdsWithinRadius(lat, lng, radiusMeters)` + المقيدة بالمزودين + `findWithinRadiusPaged(...)` بترتيب المسافة | عقد المنفذ يتوسع — **عقد التطور موثق (تبنّي مراجعة CodeRabbit ج1):** إضافة عمليات على واجهة تكسر التوافق الثنائي لأي تنفيذ خارجي — والمنفذ **SPI داخلي compile-time** (تنفيذ واحد حي + بدائل الاختبار) فيُحدّث الجميع في نفس PR الطبقة (المحوّل + كل stubs الاختبارات) ⇒ سيناريو AbstractMethodError مستحيل البقاء؛ لا مستهلك خارج إصدار التطبيق الواحد | البحث (search) يستهلك؛ حدود الوحدات كما هي — ArchUnit يغطي تلقائيًا |
| 7 | **`PropertyDetailsRepository` + `PropertyFilterAdapter` (realestate)** | استعلامان أصليان (ST_DWithin + ST_Distance للترتيب) + تنفيذ المنفذ الجديد | الوحدة تظل مالكة جدولها — الاستعلام الأصلي بنمط searchFullText (الحذف الناعم صريح + partial predicate مطابق للفهرس حرفيًا) | الاستعلام الأصلي الوحيد الجديد في النظام — معزول داخل وحدة، قابل للقياس بمعزل |
| 8 | **`SearchCriteria` (shared-api)** | + latitude/longitude/radiusKm اختيارية مع بوابة النوع (الحضور الثلاثي أو الغياب؛ النطاق مطابق لـV48؛ السقف 50 كم) | **عقد التوافق موثق (تبنّي مراجعة CodeRabbit ج1):** السجل record بمُنشئ أساسي من 7 مكوّنات + مُنشئان صريحان (4 و6) والواجهة تستخدم الأساسي عبر `@RequestParam` — إضافة المكوّنات ترفع الأساسي إلى 10 مع **إبقاء المُنشئين القائمين كما هما (يمرران `null` للمكوّنات الجديدة)** وتحديث مسار الواجهة و**اختبار لكل مسار بناء** (لا مسار JSON/Deserializer لهذا السجل في الكود — لا يُدّعى واحد) | كل مستهلكي SearchCriteria (الواجهة + الذاكرة) يرون العقد نفسه — المُنشئات القائمة سلوكها محفوظ |
| 9 | **`SearchService` + `SearchController` (search)** | فرع توزيع نصف القطر (قبل الفرع القديم — `hasRadius()` يدفع للفرع الملكي مثل hasPropertyCriteria) + 3 RequestParam اختيارية + حل المجموعة والتقاطع + `sort=distance` بالعقد المُصفّح | الطريقة القديمة dispatchLegacy بايت-بايت كما هي؛ الفرع الملكي يكسب التقاطع مع مجموعة نصف القطر (والفراغ الصادق قبل استعلام الكاتالوج) | المسار العمومي للبحث يكسب قدرة جغرافية — عقود البقية (FTS/trgm/معايير) غير ممسوسة |
| 10 | **`search-results-v3 → v4` + مولد المفتاح** | رفع الاسم + PREFIX + مقاطع المطابعة | إبطال المخبآت القديمة عند النشر (القديمة تنطفئ بTTL) — سلوك سابقة L32 نفسها | ذاكرة عمومية نظيفة، مفتاح injective يبقى (اختبار المولد يتوسع) |
| 11 | **الاختبارات الجديدة** | تكاملي واحد على PG حقيقي (بيئات V1..V50): صف بلا إحداثيات لا يظهر؛ صف قريب يظهر؛ بعيد خارج السقف لا يظهر؛ 400 للحضور الجزئي؛ `sort=distance` يرتب أقرب فأقرب؛ EXPLAIN يُظهر فحص فهرس GiST (حارس الأداء) | نمط الحرّاس التكامليين القائم (Testcontainers + @ActiveProfiles("test")) | الحارس يمنع أي انحدار صامت (فهرس غير مستعمل/إحداثيات مقلوبة) |
| 12 | **الوثائق الحاكمة** | هذا الملف + إحالتان جراحيتان (AGENTS §0.1 سطر الملف الحاكم + قسم PROJECT_MAP) + دفعة الحقيقة بعد التنفيذ (SYSTEM.md §15) | ملفات الحقيقة تعكس الحالة الجديدة بنمط الدفعات | الحوكمة كاملة الدورة كما في كل طبقة |

**سلسلة الاعتماد لأسفل (لماذا هذا الترتيب آمن):** الامتداد (1-3) يسبق الترحيلة (5) يسبق الشيفلة (6-10) يسبق الحارس (11) — كل خطوة تفشل بصوت عالٍ إن سبقتها خطوة ناقصة (Flyway يفشل إن غاب الامتداد؛ الحارس يفشل إن خالفت الشيفلة). **لا خطوة صامتة في السلسلة.**

---

## 4. المراحل والبوابات — التنفيذ بكلمة المستخدم

> **بوابة البدء:** هذه الخطة مُصمَّمة بكلمة المستخدم (الأمر المؤسِّس أعلاه). **التنفيذ يبدأ بكلمة صريحة لاحقة** («نفّذ») — البوابات التالية كلٌّ بيد صاحبه (AGENTS §0.4). دورة كل مرحلة = الدورة القياسية للخطة الرابعة §6 حرفيًا (CodeRabbit + CI + دمج squash + توثيق متزامن).

### P0 — قياسات ما قبل البنية ثم تبديل صورة الإنتاج (بيد المستخدم أو بتوكيله الصريح)

قياسات قراءة صرفة (تُنفَّذ من قشرة الخدمة على Railway — ببيانات الاتصال الفعلية نفسها التي يقرأها التطبيق: `DB_URL`/`DB_USERNAME`/`DB_PASSWORD` — أي **هوية Flyway بعينها** التي ستنفّذ V50):

```
0.1  psql "$DB_URL_FLYWAY" -c 'SELECT current_user, (SELECT rolsuper FROM pg_roles WHERE rolname = current_user) AS is_superuser;'
       -- يحسم: هل يستطيع مستخدم Flyway (هوية التطبيق اليوم — راجع D-P13) تنفيذ CREATE EXTENSION؟
       -- (الوثيقة الرسمية تقول نعم — POSTGRES_USER خارق؛ القياس يؤكد لا يفترض) + يُسجَّل هوية اتصال التطبيق منفصلة
0.2  psql "$DB_URL_FLYWAY" -c 'SELECT version();' -c 'SHOW data_directory;' -c 'SHOW config_file;'
       -- يحسم شكل PGDATA الفرعي للفوليوم القائم (قياس §0.2: المسار القديم /var/lib/postgresql/data)
0.3  SHOW ssl; SHOW ssl_cert_file; SHOW ssl_key_file;
     SELECT a.application_name, a.usename, s.ssl, s.version AS tls_version
       FROM pg_stat_activity a JOIN pg_stat_ssl s ON s.pid = a.pid
      WHERE a.usename = current_user AND a.application_name ILIKE '%hikari%';
       -- يحسم: هل اتصالات التطبيق نفسها مشفرة فعلًا (ارتباط pg_stat_ssl بجلسات التطبيق — لا SHOW ssl وحده)؛
       -- وDB_URL نفسه (sslmode=) يُقرأ من لوحة المتغيرات (write-only) — الشهادات: P-1 يحافظ عليها؛ P-2 يعيد تهيئتها
0.4  docker run --rm postgis/postgis:18-3.6 postgres --version
       -- (محليًا أو عبر خدمة Railway مؤقتة) يحسم: إصدار PG داخل الصورة ≥ 18.6 عنقود الإنتاج
0.5  تجربة NOSUPERUSER لتحصين D-P13: جلسة بهوية منزوعة الخارق تنفّذ قراءات التطبيق النموذجية
       (SELECT عادي + تحقق V34: pg_trgm تعمل لصاحب القاعدة بلا خارق — سابقة مقيسة) — أي فشل هنا يعني اعتمادًا خفيًا يُوثّق قبل التحويل
```

**الخيار P-1 (تبديل في المكان — الأقل تغييرًا):** تحديث مصدر خدمة postgres-18 إلى `postgis/postgis:18-3.6` مع ضبط `PGDATA` على المسار الفرعي المقيس (0.2) — الفوليوم والاسم والشبكة والشهادات (0.3) كما هي؛ قاعدة «Initialize Only on Empty Data Directory» تعني أن الامتداد **لن ينشأ تلقائيًا** ⇒ بعد التبديل يُنفَّذ `CREATE EXTENSION postgis;` مرة واحدة من قشرة الخدمة (هوية الترحيل — 0.1/D-P13)، وبعدها V50 (لا-عملية). **الرجوع بمساريه الصادقين (تبنّي مراجعة CodeRabbit ج1):** (أ) **قبل** تنفيذ `CREATE EXTENSION`: إعادة المصدر للصورة السابقة = رجوع نظيف (نفس الفوليوم، نافذة ثوانٍ)؛ (ب) **بعد** تفعيل الامتداد والترحيلة: العنقود يحمل كائنات postgis (الامتداد + فهرس GiST التعبيري وسجل Flyway لـV50) — إعادة الصورة القديمة حينها **ليست رجوعًا صامتًا صحيحًا** (الكائنات بلا مكتباتها وإعادة بناء الإقلاع تفشل على V50) ⇒ مسار الرجوع الموثق: البقاء على صورة متوافقة PostGIS، أو عكس كامل مُختبر بترتيبه (إسقاط كائنات V50 ← `DROP EXTENSION postgis` ← إزالة سجل الترحيلة بإجراء repair موثق ← إعادة الصورة) — لا يُعرض «إعادة الصورة» وحدها كرجوع.
**الخيار P-2 (خدمة جديدة + النقل الرسمي — الأنقى سندًا):** خدمة `postgres-18-postgis` من `postgis/postgis:18-3.6` (init طازجة تنشئ الامتداد في POSTGRES_DB تلقائيًا) + فوليوم جديد + النقل بالمسار الرسمي الموثق (pg_dump -Fc --no-acl --no-owner → pg_restore --clean --if-exists -j 4 + ANALYZE + تحقق الأعداد — سابقة 57/57) + تحديث DB_URL على التطبيق (متغير write-only — بيد المستخدم في اللوحة أو بتوكيل GraphQL الموثق) + إبقاء الخدمة القديمة نافذة استرجاع ثم حذفها بأمر.
**حسم الخيار:** إن اجتازت 0.2-0.4 بلا مفاجآت ⇒ P-1 (صفر متغيرات وصفر نقل بيانات)؛ أي انحراف (PGDATA غير قابل للضبط، إصدار الصورة أقدم من العنقود، SSL خارج الفوليوم بلا ضبط) ⇒ P-2. **كلاهما موثق الخطوات أعلاه — القرار بالقياس لا بالمزاج.**

> ⚠️ **البوابة الحرجة (D-P8):** P0 كاملةً **قبل** دمج P1 على main. الدمج قبل التبديل = أول نشرة post-دمج تفشل في Flyway V50 (الخدمة القديمة تظل تخدم — لكنه كسر نشر منع الخطة صراحةً).

### P1 — طبقة الشيفلة (PR واحد كامل الحوكمة — بعد كلمة «نفّذ»)

النطاق: بنود 3-11 من خريطة المس (compose + CI + 27 ملف TC + V50 + المنفذ + المحوّل + المعايير + التوزيع + المخبأة + الحارس التكاملي). الفرع `feat/postgis-radius-search` من رأس الكداسة بعد إقفالها. الاختبارات القياسية للبيت كلها: `./mvnw clean verify` محليًا + الفحصان الإلزاميان على الرأس + CodeRabbit (كل ملاحظة تُبنّى من الجذر أو تُتجاوز بدليل — بروتوكول #242) + دمج squash بأمر المستخدم.

### P2 — التحقق الحي ودفعة الحقيقة

نبض الإقلاع (سجل النشرة: `Migrating schema "public" to version "50 — postgis radius index"`) + فحص دخان حي: `GET /api/v1/search?lat=33.55&lng=36.05&radiusKm=10` (قدسيا تقريبيًا) = 200؛ `?lat=999` = 400؛ صفحة بلا معايير = السلوك القديم بايت-بايت. ثم دفعة الحقيقة (SYSTEM.md §15 + PROJECT_MAP + قلب حالة هذه الخطة إلى «مدمج (PR #N)»).

---

## 5. الديون التي تولّدها هذه الخطة (كلٌّ بنقطة إغلاق)

> **الترقيم (درس #296 مطبَّق من اليوم الأول):** القرارات الحاكمة أعلاه تحمل **D-P1..D-P13**؛ الديون هنا سلسلة مستقلة **D-I1..D-I6** (I = Implementation) — لا اصطدام بين السلسلتين.

| # | الدين | نقطة الإغلاق |
|---|---|---|
| D-I1 | الفهرس غير CONCURRENTLY — يسدّ الكتابة أثناء بنائه (الجدول فتيّ الآن) | عتبة مقيسة: > 50K صف في property_details وقت ترحيلة لاحقة تُبنَ بCONCURRENTLY (بترحيلة جديدة تتبع نمط executeInTransaction=false الموثق عند الحاجة) |
| D-I2 | سقف نصف القطر 50 كم معايرة أولية | كلمة مستخدم عند أول بيانات استخدام (نمط G-R6 معايرة الترافيك) |
| D-I3 | `EXPLAIN` الحارس قد يهتز بتقدير المخطط بين إصدارات PG | إن اهتز: يُخفَّض إلى تأكيد صفري للنتائج + فحص فهرس يدوي موثق في P2 |
| D-I4 | postgis_topology ينشأ في البيئات الجديدة طبيعةً (initdb الصورة) ولا يُستهلك — موثق لا مرفوض | لا إغلاق مطلوب — يُذكر في V50 تعليقًا |
| D-I5 | مجموعة تقييد نصف القطر (IN بحجم النتائج) تحمل كلفة D-E6 نفسها المتنامية مع الكتالوج | نفس عتبة D-E6 (10K مؤهل نموذجيًا) — تُقاس معًا عند أول توسع مدينة |
| D-I6 | خيار الإنتاج غير المنفّذ (P-1 مقابل P-2) يبقى معلقًا بكامل مساره الموثق | قياسات P0 ثم التنفيذ — هذا القسم نفسه هو نقطة الإغلاق |

---

## 6. صيانة هذا الملف

- يُحدَّث **حصرًا** عبر PR يخضع للحوكمة نفسها (ruleset 22305466) — لا تحرير مباشر على main (نمط عائلة الخطط).
- كل بند يُنفَّذ يقلب حالته في نفس دفعة الدمج؛ والأدلة لحظية تُعاد قراءتها من الكود الحي قبل أي تنفيذ (القاعدة البيتية — إن زاح دليل صُحّح في PR البند قبل أي كود).
- هذه الخطة **لا تفتح بوابات غير بوابتها**: فتح G-R2 نطاقها؛ وG-R5 (مزوّد خريطة الواجهة) وبقية بوابات الخطة الرابعة تبقى بيد أصحابها.
- المرجعية العليا عند التعارض: SYSTEM.md (الآلية) ثم AGENTS.md (القواعد) ثم docs/realestate-systems-plan.md (نطاق أنظمة العقار) — هذه الخطة عمق تشغيلي تحتها.
