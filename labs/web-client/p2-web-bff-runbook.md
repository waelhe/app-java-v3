# Runbook محلي — بناء عميل الويب BFF وتدفق الدخول الحي (P2: Next.js + BFF Live Verification)

> **الغرض:** المرجع التشغيلي الوحيد لبناء عميل الويب (Next.js + BFF) وربطه الحي بالباك اند: إقلاع الباك اند بعنوان الـcallback الرسمي، تأسيس مشروع الويب الشقيق على أحدث الإصدارات المستقرة، تنفيذ BFF (السرّ خادميًا + PKCE + جلسة عديمة الحالة)، ثم **تدفق دخول حي كامل** (authorize→login→consent→code→session→قراءة `/me` عبر البروكسي) بالمعايير القابلة للفحص. يُقرأ مع `docs/security/client-hosting-strategy-plan.md` (§4 النمط 1 — BFF سري)، و`labs/oauth2-verification/oauth2-local-bootstrap-runbook.md` (محطة التأسيس السابقة)، و`labs/platform/03-web.md` + `labs/platform/08-build-roadmap.md` (P2).
>
> **المرجع الحاكم:** `docs/security/client-hosting-strategy-plan.md` (§4-1 + §7-B «معًا») + `labs/platform/08-build-roadmap.md` (P2 — معيارها: login/read-API flow على localhost ضد الباك اند المحلي).
>
> **الحالة الحية:** المحطة المنفَّذة بالقياس 2026-09-15 — كل قيمة هنا قيمة مقيسة من تلك الجلسة. المشروع الشقيق: `backend java\web-marketplace\` (مستودع مستقل، صفر مساس بمستودع الباك اند — §0.3 «الباك اند مرساةً»).
>
> **قاعدة الصدق:** لا أمر هنا بلا قياس مسجَّل. ما لم يُتحقق حيًّا (النقر المتصفحي المباشر، دورة التجديد بعد 15 دقيقة) مُعلَّن كحدّ تحقق في §7 لا كنجاح.

---

## 0. متى هذا الملف؟

| الحاجة | المرجع هنا |
|---|---|
| بناء عميل ويب جديد ضد هذا الباك اند من الصفر | §2 + §3 (كامل السلسلة) |
| أول دخول من الويب يتعطل (redirect/code/session/401) | §3.3 (التدفق العشر خطوات) + §7 (القيم المرجعية) |
| `email_not_found` من Better Auth ضد مزود بلا بريد | §1 (الجسر الرسمي) + §3.3-8 |
| الشك في عنوان الإرجاع (placeholder مقابل سلسلة دقيقة) | §1 (قياس السلك) + §3.0 |
| بعد أي تجربة: ماذا أنظف وما الذي لا أمسّه | §3.5 |
| تكرار القياسات (إصدارات/جلسة/`/me`) | §7 |

---

## 1. مبادئ العملية (لماذا هذا التصميم لا غيره)

- **BFF سري — السرّ خادميًا حصرًا:** خادم Next يؤدي authorization-code + `client_secret_basic` + PKCE S256، والتوكنات (وصول/تجديد) تبقى خادميًا في كوكيز مشفرة؛ المتصفح لا يرى إلا كوكيز الجلسة (`labs/platform/03-web.md` §2؛ OWASP SPA — لا توكنات في localStorage).
- **عنوان الإرجاع = عنوان الإطار المسجل لدى المزوّد (الحل الرسمي):** Better Auth يطالب رسميًا: «Make sure your OAuth provider is configured to use this URL» (`${baseURL}/api/auth/callback/:providerId` — وثيقة Generic OAuth). سُجِّل `http://localhost:3000/api/auth/callback/marketplace-web` في صف العميل عبر env (المسار المصمم §4.4-هـ — صفر كود باك اند)، وأُسقطت فكرة route التحويل الالتفافية (كانت موجودة وحُذفت — لا كود ميت).
- **قاعدة RFC 9700 §4.1.3 (المطابقة النصية التامة):** المزوّد يقارن الـredirect حرفيًا — **وقِيس على السلك** أن Better Auth يرسل `redirectURI` حرفيًا دون استبدال أي placeholder (كان `.../code/:providerId` حرفيًا في الطلب الصادر)، فالقيمة هنا هي السلسلة المسجلة بالضبط لا قالبًا.
- **الوضع عديم الحالة بدل SQLite (قرار بالقياس):** `better-sqlite3@13.0.3` لا ثنائية مسبقة له على هذا الـABI ولا toolchain بناء C++ هنا (فشل `node-gyp` مقيس)؛ و`node:sqlite` ما زال RC مع بلاغ كسر بناء Next — فالمعتمد هو الوضع عديم الحالة الموثق رسميًا (جلسة + مادة الحساب في كوكيز مشفرة، والتجديد التلقائي يعمل عبرها).
- **جسر البريد الرسمي للمزود بلا بريد:** هوية الباك اند `sub` فقط (`syncFromOidc` يصفّر البريد من الادعاءات الغائبة — بالكود)، وBetter Auth يشترط بريدًا على كل صف مستخدم — فالمعتمد هو الجسر الرسمي الموثق (`mapProfileToUser` ببريد بديل حتمي من الـ`sub` الثابت في النطاق المحجوز `.invalid` — RFC 2606، لا توجيه له أبدًا ولا مراسلة عليه؛ والتعرف على العائد بالمفتاح الثابت `(providerId, accountId)` لا بالبريد).
- **المكدس المرفوض بالدليل:** `next-auth` v4 إرثي (المشروع نفسه يوجه إلى Better Auth) وv5 ما زال بيتا (مرفوض بقاعدة production-ready)؛ iron-session اليدوي (~150 سطرًا) خسر أمام الإعداد الرسمي (~30 سطرًا) بقاعدة Simplicity First.
- **التحقق الآلي ليس بديلًا عن القياس:** `npm run build` + الأنواع المشحونة يتحققان من التطابق مع API الرسمية، لكن معيار P2 هو التدفق الحي العشر خطوات (§3.3) — لا يُدَّعى نجاح قبله.

---

## 2. المتطلبات المسبقة (جلسة 2026-09-15 المنفَّذة)

| المكوّن | القيمة المقيسة |
|---|---|
| Node | محمول رسمي `v26.8.2` في `backend java\.tools\node-v26.8.2-win-x64` (تثبيت MSI تعذّر: 1603 لبقايا معطوبة ثم 1925 لغياب الرفع — سجلّا التركيب محفوظان في temp الجلسة ثم حُذفا؛ نسخة الجهاز 25.9.0 أزالها المستخدم يدويًا) + `npm.cmd`/`npx.cmd` مُنشآن + PATH المستخدم (HKCU) |
| npm | `11.19.1` (المرفقة مع Node 26.8.2) |
| JDK/الباك اند | `jdk-26` + الجرة المبنية `marketplace-app-0.1.0-SNAPSHOT` (أُقلعت من نسخة temp بلا مسافات لعائق اقتباس الغلاف — الأصل في `target/` لم يُمس) |
| قاعدة محلية | `marketplace-db` (postgis 18.6) + `marketplace-redis` (redis 8) — أُعيد تشغيلهما من Exited؛ الباك اند القديم تعافى عليهما ثم استُبدل بالإقلاع الرسمي |
| مشروع الويب | `backend java\web-marketplace\` — `next@16.3.5` + `react@19.3.0` + `better-auth@1.7.5` + TS + ESLint (بلا Tailwind — أُزيل جراحيًا بعد دخوله افتراضيًا) + `git init` بلا commit |
| ملفات البيئة | `.env.local` (gitignored — سطر `.gitignore:34`) + `.env.example` (عناصر نائبة بلا أسرار) |
| أسرار dev المحلية فقط | `OAUTH_CLIENT_ID=marketplace-web` / `OAUTH_CLIENT_SECRET=web-secret-dev` (قيم المحطة السابقة) + `BETTER_AUTH_SECRET` مولّد محليًا (64-hex) — لا سرّ في أي مستودع |

---

## 3. الخطوات

### 3.0 إقلاع الباك اند بعنوان الإطار (الطريقة الموثقة — لا كود)

الخصائص نفس إقلاع المحطة السابقة حرفيًا **عدا خاصية واحدة** (عنوان الإرجاع — الحل الرسمي):

```text
java -Dspring.profiles.active=default -DDB_PASSWORD=marketplace
  -DJWT_KEYSTORE_PASSWORD=changeit -DJWT_KEY_PASSWORD=changeit
  -DCORS_ALLOWED_ORIGINS=http://localhost:3000
  -Dmarketplace.security.oauth2.client.client-id=marketplace-web
  -Dmarketplace.security.oauth2.client.secret=web-secret-dev
  -Dmarketplace.security.oauth2.client.redirect-uris=http://localhost:3000/api/auth/callback/marketplace-web
  -Dmarketplace.security.oauth2.public-client.client-id=marketplace-mobile
  -Dmarketplace.security.oauth2.public-client.redirect-uris=com.marketplace.mobile://callback
  -jar marketplace-app/target/marketplace-app-0.1.0-SNAPSHOT.jar
```

- المهيّئ يقارب صف `marketplace-web` على العنوان الجديد عند أول إقلاع (التقارب المصمم §4.4-هـ) — يُقرأ حيًّا من `oauth2_registered_client` (المعيار: `http://localhost:3000/api/auth/callback/marketplace-web` + `marketplace-mobile` بلا مساس).
- قياس القبول: `liveness=200` + `readiness=UP` + صفّا العميلين بالقيم أعلاه.

### 3.1 ملفات البيئة (الطريقة الموثقة — لا أسرار بالمستودع)

`.env.local` (محلي فقط) بالمفاتيح الخمسة (`BETTER_AUTH_SECRET` مولّدًا بـ`crypto.randomBytes(32)`، والبقية قيم المحطة السابقة)؛ و`.env.example` بالعناصر النائبة. المعيار: `git check-ignore .env.local` صامت + لا سرّ في `git status`.

### 3.2 خادم التطوير

```text
npm run dev   # http://localhost:3000 — المعيار: GET / = 200
```

### 3.3 تدفق الدخول الحي — عشر خطوات (معيار P2 الكامل)

بجرار كوكيز منفصلة (Next/Backend) وتتبّع يدوي للتحويلات — الدلالة نفسها دلالة المتصفح (سكربت الإثبات المحفوظ خارج المستودعين):

1. `GET /api/auth/get-session` ⇒ **200 null** (لا جلسة).
2. `POST /api/auth/sign-in/social` (`{provider, callbackURL}` + ترويستا `Origin/Referer` — فحص `originCheck` الرسمي يرفض 403 بدونهما) ⇒ **200 `{url}`** (العميل الرسمي يتنقل بها؛ ليست 302).
3. `GET` رابط التفويض (PKCE S256 + `openid+profile` + الـredirect الدقيق) ⇒ **302 `/login`**.
4. `GET /login` ⇒ **200** (استخراج `_csrf`).
5. `POST /login` (مستخدم الجلسة + `_csrf`) ⇒ **302** لمتابعة التفويض.
6. متابعة التفويض ⇒ **200 صفحة Consent** («Consent required» — أول دخول) أو **302 بالكود** مباشرة (دخول لاحق — الموافقة محفوظة لكل principal).
7. `POST` الموافقة (قيم الصفحة المستخرجة) ⇒ **302 إلى عنوان الإطار `?code=…&state=…` مباشرة** (لا وسيط).
8. `GET` عنوان الإطار (بكوكيز Next) ⇒ **302 `/profile`** + كوكيز الجلسة (المشفرة `account_data` حاضرة).
9. `GET /api/auth/get-session` ⇒ **200** بجلسة حية (`session.id` + `userId`).
10. `GET /api/backend/api/v1/users/me` (بكوك almacenar الجلسة) ⇒ **200** بجسم الملف الحقيقي (`id` + `email:null` + `displayName:null` — الهوية `sub` بتصميم الباك اند).

> **الفشل الموجَّه:** `email_not_found` على الـcallback ⇒ الجسر §1 غائب/مكسور؛ `401 {reauth:true}` من البروكسي ⇒ جلسة غائبة (أعد الدخول)؛ `redirect_uri_mismatch` ⇒ الصف لم يتقارب (أعد الإقلاع §3.0).

### 3.4 القراءة الحية (ليست خرائط تخمينية)

```sql
select client_id, redirect_uris from oauth2_registered_client order by 1;
```

| العميل | redirect المسجل (مقيس) |
|---|---|
| `marketplace-web` | `http://localhost:3000/api/auth/callback/marketplace-web` |
| `marketplace-mobile` | `com.marketplace.mobile://callback` (بلا مساس) |

### 3.5 التنظيف (مخلفات المحطة فقط — نُفّذ)

مستخدم الجلسة المؤقت (`s2_user` + سلطته + تفويضاته وموافقاته) حُذف بالكامل — المعيار: `auth_users` بصف واحد (`admin`) و`oauth2_authorization` صفر صفوف له. **لا يُمس:** صفّا العميلين، `admin`، أي فهرس/مخطط، ملفات المستودعين.

---

## 4. دخان عام بعد المحطة

| النقطة | الصحيح | ملاحظة |
|---|---|---|
| `:3000/` | 200 (داخل/خارج الجلسة) | صفحة الدخول/الملف |
| `:3000/profile` | رابط الدخول بلا جلسة / الملف بجلسة | خادمية بالكامل |
| `:3000/api/auth/get-session` | 200 null / 200 جلسة | — |
| `:3000/api/backend/api/v1/users/me` | 401 `{reauth:true}` بلا جلسة / 200 بجلسة | البروكسي الحامل |
| `:8080/actuator/health/liveness` | 200 | — |
| `:8080/actuator/health/readiness` | 200 UP | db/redis/disk |
| `:8080/oauth2/jwks` | 200 | مفتاح dev العابر |
| `:8080/.well-known/openid-configuration` | 200 | الاكتشاف الذي يستهلكه الإطار |

---

## 5. ممنوعات (تقف العملية عند أي منها بدون سؤال)

- **لا** سرّ عميل في الكود/المستودع — env محلي فقط (`.env*` مُتجاهلة)؛ `web-secret-dev` قيمة dev موثقة لا تُنشر.
- **لا** بريد مُختلَق خارج النطاق المحجوز `.invalid` — الجسر §1 حده الأقصى؛ لا مراسلة من BFF أبدًا (لا مزود بريد مثبّت).
- **لا** إحياء route التحويل المحذوف — العنوان المسجل هو عنوان الإطار (الحل الرسمي)؛ أي URI جديد = تسجيل مزود جديد لا تحويلة.
- **لا** placeholder في `redirectURI` — تُرسل حرفيًا على السلك (مقيس) فتُرفض؛ السلسلة الدقيقة المسجلة حصرًا.
- **لا** كود باك اند لاحتياج عميل (§0.3) — العميل = إعداد + اختبار؛ أي تغيير سلوكي في الباك اند = PR مستقل بدورته.
- **لا** تعديل ترحيلة `V__*.sql` مطبَّقة ولا `flyway repair` (قاعدة AGENTS + درس V51) — هذه المحطة لا تمس المخطط أصلًا.
- **لا** `DROP SCHEMA` إلا بقرار صريح بيد المالك (درس المحطة السابقة).

---

## 6. حرّاس هذه العملية (أين تعيش خارج المحطة)

| الحارس/المرجع | الدور |
|---|---|
| `npm run build` (مشروع الويب) | بوابة الإنتاج: TypeScript ضد الأنواع الرسمية المشحونة + كل المسارات (`/`، `/profile`، `/api/auth`، `/api/backend`) |
| `npm run lint` | ESLint الرسمي صامت |
| سكربت الإثبات العشر خطوات (خارج المستودعين — temp الجلسة) | إعادة تشغيل معيار P2 كاملًا عند أي تغيير إعداد |
| `MigrationChecksumGuardTest` (الباك اند — لم يُمس) | قفل بصمات الترحيلات قبل أي دمج هناك |
| `docs/security/client-hosting-strategy-plan.md` §7-B | بوابة المرحلتين التاليتين (P3 ثم C) — بيد المستخدم |

---

## 7. القياسات الحية (جلسة 2026-09-15 — مرجع التطابق)

| المقياس | القيمة |
|---|---|
| إصدارات المكدس | Node `v26.8.2` + npm `11.19.1` / Next `16.3.5` / React `19.3.0` / Better Auth `1.7.5` |
| بوابتا الجودة | `lint` صامت + `build` أخضر بالملفات النهائية |
| الجلسة الحية | `session.id=f9G3f8zA…` + `userId=zNT5pbcQ…` (عابرة — تُقاس لا تُحفظ) |
| `/me` عبر البروكسي | **200** `{"id":"413d9be3-…","email":null,"displayName":null,…}` |
| البريد البديل الرسمي | `${sub}@marketplace.placeholder.invalid` (مشتق لا مخزون خارج الجلسة) |
| redirect المسجل/المرسل | متطابقان حرفيًا (`.../api/auth/callback/marketplace-web`) |
| النظافة الختامية | `auth_users`={admin}؛ `oauth2_authorization` (للمؤقت)=0؛ عميلان مسجلان |
| **حدود التحقق المعلنة (ليست نجاحًا)** | النقر المتصفحي المباشر (السكربت يماثل دلالته HTTP فقط)؛ دورة التجديد بعد 900s (سلوك إطاري موثق) |

---

## 8. سجل المحطة 2026-09-15 (نُقلت خلاصته من الجلسة — أمر «أنشئ وثيقة مثل runbook المحطة السابقة»)

> الإعلان المعياري قبل التنفيذ (بروتوكول §0.2): **الملف الحاكم:** `docs/security/client-hosting-strategy-plan.md` (§4-1 + §7-B) + `labs/platform/08-build-roadmap.md` (P2)؛ **النواة:** طبقة عرض جديدة خارج مستودع الباك اند + إعداد مزود (env) — صفر كود/ترحيلات/حدود باك اند؛ **البند:** «الوثائق الرسمية مصدر الحقيقة» + Simplicity First + State Sync؛ **أثر الحدود:** صفر؛ **الدين:** لا.

- **نقطة البداية (ورِثت من محطة 2026-09-14):** باك اند حي (PID 612 بإقلاع المحطة السابقة) + حاويتا DB/Redis متوقفتان (Exited) — أُعيد تشغيلهما فتعافى الباك اند ذاتيًا (liveness/readiness خضراوان)؛ العمل السابق **لم ينقطع**.
- **التأسيس:** مجلد شقيق `web-marketplace` + `create-next-app@16.3.5` (TS/ESLint/App Router/src-dir/npm) — Tailwind دخل افتراضيًا فأُزيل جراحيًا (4 مواضع)؛ React رُقّي `19.2.8→19.3.0` (السجل الرسمي)؛ `git init` بلا commit.
- **الأدوات:** MSI الرسمي تعذّر (1603 ثم 1925 بلا رفع) — Node محمول رسمي v26.8.2 + shims + PATH المستخدم؛ `better-auth` + `better-sqlite3@13.0.3` ثُبّتا ثم سقط الثاني بالقياس (node-gyp بلا toolchain) فأُزيل — والوضع عديم الحالة الموثق حلّ محله (صفر اعتماديات أصلية).
- **القرارات بالقياس (كلٌّ بدليله):** رفض next-auth (v4 إرثي بتوجيه رسمي + v5 بيتا)؛ `redirectURI` حرفية على السلك (لا استبدال)؛ فحص `Origin` الرسمي (403 سكربتية)؛ `email_not_found` ⇒ الجسر الرسمي `.invalid` (لا اختلاق)؛ إسقاط route التحويل لصالح تسجيل المزود (الحل الرسمي + إعادة إقلاع بصف متقارب مقيس).
- **عارضان تشغيليان وُثّقا:** نداءات الإطلاق الطويلة تُقتَل من الغلاف (الحل: ملف `.cmd` قصير) — وأحدها أطلق الباك اند الجديد فعلًا قبل موته (الصف تقارب عليه)؛ مسار الـjar ذو المسافات انشطر (الحل: نسخة temp).
- **الإثبات:** 10/10 خضراء + `lint`/`build` + تنظيف المؤقت + مخلفات temp حُذفت (عدا سكربت الإثبات). **المتبقي بيد المستخدم حصرًا:** P3 (KMP) ثم بوابة C (الاستضافة) + commit المشروع الجديد عند الطلب.
