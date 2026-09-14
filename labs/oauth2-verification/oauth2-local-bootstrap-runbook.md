# Runbook محلي — تأسيس عملاء OAuth2 والتفويض الحي (Local OAuth2 Client Bootstrap & Live Delegation)

> **الغرض:** المرجع التشغيلي الوحيد للتشغيل المحلي لمسار العملاء: إعادة بناء قاعدة محلية ببصمة مطابقة، الإقلاع بخصائص العميلين، تأسيسهما عبر المسار الرسمي (`RegisteredClientRepository.save` من المهيّئَين)، ثم **التفويض الحي** (سيل openid كامل للعميل السرّي + سيل PKCE للعميل العام) بالمعايير القابلة للفحص `ملف:سطر`. يُقرأ مع `docs/security/oauth2-client-bootstrap-spec.md` (العقد)، `docs/security/client-hosting-strategy-plan.md` (مسار العملاء والقواعد)، و`keys/README.md` (سياسة مفاتيح JWT ومتى يكون المفتاح العابر مشروعًا).
>
> **المرجع الحاكم:** `docs/security/oauth2-client-bootstrap-spec.md` (§3 التثبيتات الثلاثة، §4.3 معايير قبول S1-S6، §4.4 سجل الانحرافات أ-و) + `docs/security/client-hosting-strategy-plan.md` (§1 مبدأ «الباك اند مرساةً» — أي عميل = إعداد + اختبار، صفر كود/وحدة/اعتماديات).
>
> **الحالة الحية:** المحطة المنفَّذة بالقياس 2026-09-14 — كل قيمة هنا قيمة مقيسة من تلك الجلسة (سجل `PROJECT_MAP.md` — قسم «محطة التحقق المحلية»). المفتاح العابر (dev) طبيعي ومقصود للجلسات المحلية (`keys/README.md` §1/§5).
>
> **قاعدة الصدق:** لا أمر هنا بلا قياس مسجَّل، ولا مسار «آراء» — كل مخرَج يتحقق من القاعدة الحية أو من استجابة الخادم الحية قبل اعتباره نجاحًا.

---

## 0. متى هذا الملف؟

| الحاجة | المرجع هنا |
|---|---|
| المحل يريد ربط مستهلك (Next.js / KMP) ثم أول دخول يتعطل | §4 (تدفق S2) + §5 (العميل العام) |
| القاعدة المحلية تحمل بصمة منحرفة عن الإنتاج (فحص تحقق Flyway يشتكي) | §3.0 |
| «ماذا أفعل أول مرة ولماذا لا يوجد سوى مسار واحد؟» | §1 + §2 |
| بعد أي تجربة: ماذا أنظف وما الذي لا أمسّه | §6 |
| تكرار القياسات والرقائق (expires_in/consent/kid) | §7 |

---

## 1. مبادئ العملية (لماذا هذا التصميم لا غيره)

- **التأسيس حصرًا عبر الباني الرسمي:** العميل لا يُكتب بأيدي SQL محاكية لصيغة Jackson الداخلية (كان هذا جذر الفجوة الحية — انجراف `client_settings`/`token_settings`، المواصفة §2)، بل عبر `RegisteredClientRepository.save(RegisteredClient)` المبني بـ`ClientSettings.builder()`/`TokenSettings.builder()` من `OAuth2ClientSecretInitializer`/`OAuth2PublicClientInitializer` (المواصفة §4.1).
- **لا عميل بلا تعريف (الوثيقة الرسمية):** Spring Authorization Server — Core Model: «A client must be registered with the authorization server before it can initiate an authorization grant flow» — التسجيل من `RegisteredClientRepository` وهو المركزي. غياب خصائص العميل في غير prod = لا عميل (no-op، المواصفة §3-ج) — **مرفوض**: سرّ dev مضمَّن في الكود، fail-fast شامل، سرّ عشوائي في السجل.
- **لا يُصلَح انحراف البصمة بـ `repair`:** `flyway repair` يبدّل سجل التاريخ ليتطابق مع المخالفة — لم يُستخدم حاسمًا؛ المسار الموثوق عند الانحراف هو ترميم الملف بايت-بايت (درس حادثة V51 — `docs/release/rollout-strategy.md`). محليًا بالنص الوارد §3.0.
- **مفاتيح JWT:** في dev/CI الخانات فارغة ⇒ مفتاح عابر عند الإقلاع (طبيعي — `keys/README.md` §5)؛ JWKS يقدّم هذا المفتاح و`kid` يظهر في كل توكن/ترويسة. أي محطة تفويض محلية تطابق `kid` الـ id_token مع JWKS — لا تتجاوز هذه المطابقة لأي اعتبار.
- **التقييم المحلي ليس CI وليس إنتاجًا:** يلبس الحقيقة الكاملة لمؤسسة العميل عبر التسجيل الحي، وكل رقم §7 رقم مقيس من جلسة 2026-09-14.

---

## 2. المتطلبات المسبقة (جلسة 2026-09-14 المنفَّذة)

| المكوّن | القيمة المقيسة |
|---|---|
| JDK | `C:\Program Files\Java\jdk-26\bin\java.exe` (Java 26) |
| Maven | Wrapper `./mvnw` (3.9.16) |
| قاعدة محلية | `marketplace-db` (Docker، postgis 18.6، فوليوم `pgdata18`) — تحقق `docker exec marketplace-db psql -U marketplace -d marketplace -c "select version()"` |
| Redis | حاوية `redis:8-alpine` — بلا Redis وخزائن الجلسة لا تخدم لكن الإقلاع ينجح |
| خدمة Windows postgres | أوقِفها إن كانت تحتل 5432 (قد تعود بعد إعادة تشغيل الجهاز — فحص دائم قبل الإقلاع) |
| كلمات | متغيرات بيئة حية: `DB_PASSWORD=marketplace`، `JWT_KEYSTORE_PASSWORD=changeit`، `JWT_KEY_PASSWORD=changeit`، `CORS_ALLOWED_ORIGINS=http://localhost:3000` |

المستخدم المحلي: جدول `auth_users`/`auth_authorities` (بذر `R__seed_oauth2_client.sql` — `admin`). لتدفق S2 حي يلزم مستخدم دخول: سابقة الاختبار الرسمي تعتمد خلية `{noop}<كلمة>` عبر `JdbcUserDetailsManager` (`AuthorizationServerLoginGateIntegrationTest:665-688`) والمشفّر مفوَّض (`SecurityConfig.java:230` — `PasswordEncoderFactories.createDelegatingPasswordEncoder`).

---

## 3. الخطوات

### 3.0 (إن لزم) إعادة بناء القاعدة بمطابقة بصمية

تُتخذ هذه الخطوة فقط حال تصدّع القاعدة المحلية (عينات اختبار ملوثة، ترحيلة مفقودة) أو رغبة مرآة للإنتاج. **قرار مدمر — يوثَّق ولا يُتوى.**

```sql
DROP SCHEMA public CASCADE;  -- يسقط الكل في القاعدة المحلية (جزاؤه صريح بيد المالك)
CREATE SCHEMA public;
```

ثم إقلاع التطبيق (الخطوة 3.1) يعيد تطبيق كل `V__*.sql` من الصفر. قياس القبول (جلسة 2026-09-14): `Successfully validated 52 migrations` و`V51 = -38705425` (= القيمة الحية) و`V50 = 1473164298`.

### 3.1 الإقلاع بالخصائص (الطريقة الموثقة — لا كود)

يبني الجرة ثم يشغّلها بمسارات الإعدادات (البادئتان عميل إنشاء المهيّئين بالضبط):

```text
./mvnw package -DskipTests        # BUILD SUCCESS (التحقق كامل بـ CI)
java -Dspring.profiles.active=default \
     -DDB_PASSWORD=marketplace -DJWT_KEYSTORE_PASSWORD=changeit -DJWT_KEY_PASSWORD=changeit \
     -DCORS_ALLOWED_ORIGINS=http://localhost:3000 \
     -Dmarketplace.security.oauth2.client.client-id=marketplace-web \
     -Dmarketplace.security.oauth2.client.secret=web-secret-dev \
     -Dmarketplace.security.oauth2.client.redirect-uris=http://localhost:3000/login/oauth2/code/marketplace-web \
     -Dmarketplace.security.oauth2.public-client.client-id=marketplace-mobile \
     -Dmarketplace.security.oauth2.public-client.redirect-uris=com.marketplace.mobile://callback \
     -jar marketplace-app/target/marketplace-app-0.1.0-SNAPSHOT.jar
```

ملاحظات تشغيلية مقيسة:
- عملية قديمة تحتل 8080؟ حرّرها (`Get-NetTCPConnection -State Listen -LocalPort 8080` → `Stop-Process`) قبل الإقلاع.
- لتفكيك الإقلاع عن الجلسة: `cmd /c "start /b java ... > %TEMP%\boot-<label>.log 2>&1"` ثم تحقق بالمنفذ لا بالبريد فقط (الأداة قد تعترض على تتبع العملية رغم نجاحها — قياس §7).
- أي ‏502/تعطّل إقلاع؟ أول مريبة عند سطر `Successfully validated` في السجل (توقّف تحقق Flyway قبل JPA).

قياس القبول: `Successfully validated 52 migrations` + `Started MarketplaceApplication in ~50s` (jvm locale محلي) + `Tomcat started on port 8080`.

### 3.2 القراءة الحية للتسجيل (ليست خرائط تخمينية)

```sql
select id, client_id, client_name, client_authentication_methods,
       authorization_grant_types, redirect_uris, scopes
from oauth2_registered_client;
```

| العميل | id (ثابت — المواصفة §3-هـ) | auth | grants | redirect |
|---|---|---|---|---|
| `marketplace-web` | `a7bd8b0d-7d42-4a64-9e34-1ad3ab22e37e` | `client_secret_basic` | `authorization_code, refresh_token, client_credentials` | `http://localhost:3000/login/oauth2/code/marketplace-web` |
| `marketplace-mobile` | `10b588c6-4e85-43ec-9ecf-c588676774d7` | `none` | `authorization_code` | `com.marketplace.mobile://callback` |

**معيار ثبات id:** لا يُغيَّر أي من المعرّفين — `oauth2_authorization.registered_client_id` / `oauth2_authorization_consent.registered_client_id` ترجع إليه (المواصفة §3-هـ). `marketplace-web` المبني بـ`withId(a7bd8b0d…)` يحوّل أسوأ حالة سباق إقلاع من تكرار صامت إلى فشل صوتي قابل لإعادة المحاولة (قرار موثق §4.1).

### 3.3 خلق مستخدم الجلسة (إن لم يوجد)

خلية الاختبار الرسمي حرفيًا — محليًا فقط ولا يُنشر:

```sql
insert into auth_users (username, password, enabled) values ('s2_user','{noop}s2-pass',true);
insert into auth_authorities (username, authority) values ('s2_user','ROLE_USER');
```

### 3.4 تدفق S2 الحي — العميل السرّي (معيار القبول §4.3-S2)

مراسل أنظمة مناسبة، بجلستين وكلمات مطلوبة:

1. `GET /oauth2/authorize?response_type=code&client_id=marketplace-web&redirect_uri=http://localhost:3000/login/oauth2/code/marketplace-web&scope=openid+profile&state=…&code_challenge=…&code_challenge_method=S256` → **302 `/login`**.
2. `GET /login` → استخرج `_csrf` من الصفحة؛ `POST /login` (username/password/_csrf) → **302** إلى متابعة الـ authorize URL.
3. إعادة `GET` للمتابعة → **200 صفحة Consent (بصمة «Consent required»)** — consent مطلوب لأن السكوبين `openid+profile` (استثناء السكوب الواحد §4.4-أ منقوض قياسًا).
4. `POST /oauth2/authorize` بقيم صفحة الـ consent (hidden `state` و`client_id` و`scope`) → **302** إلى `redirect_uri?code=…&state=…`.
5. `POST /oauth2/token` بمصادقة `Basic( marketplace-web : web-secret-dev )` + `grant_type=authorization_code&code=…&redirect_uri=…&code_verifier=…` → **200**.

| المعرفة (جلسة 2026-09-14) | القيمة |
|---|---|
| `token_type` / `expires_in` | `Bearer` / `899` (= 900s — قاعدة التوازن §3-أ حية) |
| `id_token` موجود | نعم — `header {"kid":"3fe86f53-60c6-4942-8f4f-e555a9f43a66","alg":"RS256"}` |
| ادعاءات id_token | `iss=http://localhost:8080`، `aud=marketplace-web`، `sub=s2_user`، `azp=marketplace-web`، exp/iat/auth_time سليمة |
| انطباق JWKS | `GET /oauth2/jwks` يقدّم key واحد `kid=3fe86f53-60c6-4942-8f4f-e555a9f43a66` (RSA-2048) = kid الترويسة |

> **أهمية الترويسة (الفجوة الحية):** وجود `alg` في ترويسة id_token إثبات أن `mapRow` قرأ خريطة إعدادات كاملة (مفتاحا `id-token-signature-algorithm`/`access-token-format` حاضرين) — وهو بالضبط ما كان يرمي أول تدفق openid قبل المواصفة (المواصفة §2). **لا تعتبر المحطة ناجحة بدون هذا السطر.**

### 3.5 تدفق العميل العام (معايير بوابة B §4.4-و)

نفس خطوات §3.4 لكن بـ `client_id=marketplace-mobile` و`redirect_uri=com.marketplace.mobile://callback`، و**بدون** أي رأس مصادقة عميل — التحدّي `code_challenge`/`code_verifier` هو الاعتماد (PKCE)، ومعرّف العميل في جسم طلب الـ token `client_id=…`.

| المعرفة (جلسة 2026-09-14) | القيمة |
|---|---|
| رد الـ token | **200** — `Bearer expires_in=899` |
| `access_token` / `id_token` | حاضر / حاضر |
| **`refresh_token`** | **غائب** — رسمي: الخادم لا يصدر refresh لعميل عام (المواصفة §4.4-و — 401 عند محاولة المنحة لاحقًا) |
| id_token | نفس `kid`، `aud=marketplace-mobile`، `azp=marketplace-mobile` |

### 3.6 التنظيف (مخلفات الجلسة فقط)

```sql
delete from oauth2_authorization where principal_name='s2_user';
delete from oauth2_authorization_consent where principal_name='s2_user';
delete from auth_authorities where username='s2_user';
delete from auth_users where username='s2_user';
```

**لا يُمس:** `admin` (بصمة `{bcrypt}` من R__seed)، صفّا العميلين المؤسَّسين، أي فهرس/مخطط. معيار النظافة: `auth_users` بصف واحد (`admin`) و`oauth2_authorization` صفر صفوف.

---

## 4. دخان عام بعد المحطة

| النقطة | الصحيح | ملاحظة |
|---|---|---|
| `/actuator/health/liveness` | 200 UP | ping فقط |
| `/actuator/health/readiness` | 200 UP | db/redis/disk |
| `/oauth2/jwks` | 200، key واحد | kid = ادعاءات التوكنات |
| `/.well-known/openid-configuration` | 200 | `issuer=http://localhost:8080`، authorize/token معلنان |
| `/actuator/health` (الكلي) | 503 | **ليس حادثًا محليًا** — بوابة `MAIL_*` (إنذار صادق، `docs/observability/runbooks.md` §1.1) غير مربوطة في dev؛ لا تعتمد عليه للحكم |

---

## 5. ممنوعات (تقف العملية عند أي منها بدون سؤال)

- **لا** `flyway repair` على انحراف بصمة — ترميم بايت-بايت هو المسار (درس V51).
- **لا** تحرير ملف ترحيلة `V__*.sql` مطبَّقة لمجرد التوثيق — التعليق التاريخي سجل لا يُحرَّر (قاعدة AGENTS §Flyway؛ الحارس `MigrationChecksumGuardTest` يفشل البناء قبله).
- **لا** سرّ عميل في الكود/المستودع ولا `{noop}` خارج قواعد/اختبارات محلية صريحة (المواصفة §1 معيار «لا يحوي المستودع سرّ عميل»).
- **لا** تعديل `application*.yml` في هذه العملية — الربط قائم (قُرئ فقط، المواصفة §5).
- **لا** `DROP SCHEMA` إلا بقرار صريح بيد المالك، وبعد حساب مفصول للمحتوى (جلسة 2026-09-14: 65 كائنًا).

---

## 6. حرّاس هذه العملية (أين تعيش خارج المحطة)

| الحارس/المرجع | الدور |
|---|---|
| `OAuth2ClientSecretInitializerTest` (S1/S5/S6) | التأسيس/التدوير/convergence/dev-noop/prod fail-fast |
| `AuthorizationServerLoginGateIntegrationTest` | سابقة تدفق PKCE كاملة ضد صف مسجَّل (`:252-271`، عميل `it-login-gate-client`) |
| `PublicPkceClientGateIntegrationTest` | بوابة العميل العام + رفض refresh لعام |
| `MigrationChecksumGuardTest` + `migration-checksums.properties` | قفل أي انحراف بصمة لترحيلة مطبَّقة قبل الدمج |
| `docs/security/oauth2-client-bootstrap-spec.md` §7 | بوابات القبول (منها `mvn clean verify -pl marketplace-app -am` + مفاعل infra) |

---

## 7. القياسات الحية (جلسة 2026-09-14 — مرجع التطابق)

| المقياس | القيمة |
|---|---|
| فحص تحقق Flyway بعد إعادة البناء | `Successfully validated 52 migrations` |
| بصمات V50/V51 | `1473164298` / `-38705425` (مطابقة للإنتاج الحيّ) |
| إقلاع خافض | `Started MarketplaceApplication in ~50s` / `Tomcat started on port 8080` |
| ctx العملية الحية | PID على `:8080`، وسطر خصائصه يعرض `marketplace.security.oauth2.*` (سببان: `Win32_Process.CommandLine` + القراءة الحية للقاعدة) |
| expiry token | `expires_in=899` (900s — §3-أ) |
| kid (عابر dev) | `3fe86f53-60c6-4942-8f4f-e555a9f43a66` (JWKS = ترويسة = التوكنان) |
| النظافة الختامية | `auth_users`={admin}؛ `oauth2_authorization`=0؛ عميلان مسجّلان |

---

## 8. سجل المحطة 2026-09-14 (نُقل من PROJECT_MAP إلى هذا الملف المخصص — أمر «وثّق بملف خاص بالعملية» ثم «انقلهم ل lab / أنشئ ملفاً بدل PROJECT_MAP»)

> الإعلان المعياري قبل التنفيذ (بروتوكول §0.2): **الملف الحاكم:** `docs/security/oauth2-client-bootstrap-spec.md` (S1/S2 + بوابة B) + `docs/security/client-hosting-strategy-plan.md`؛ **النواة:** الأمن (§6 — تدفق SAS 7.1.1) + البيانات (سجل Flyway المحلي) + الإقلاع؛ **البند:** «الوثائق الرسمية مصدر الحقيقة» + State Sync؛ **أثر الحدود:** لا شيء؛ **الدين:** لا — جلسة قياس وتوثيق محلية خالصة (مستخدم مؤقت `{noop}` بنمط الاختبارات، حُذف بعده).

- **نقطة البداية (ورِثت من سداد #310):** القاعدة المحلية أُعيد بناؤها بقرار المستخدم الصريح (بعد استبعاد `repair` — هو يبدّل التاريخ ليتطابق مع مخالفة القاعدة): `DROP SCHEMA public CASCADE` (65 كائنًا) ثم `CREATE SCHEMA public` ثم إعادة الهجرة 52 → **`Successfully validated 52 migrations`** وV51 بصمتها `-38705425` = القيمة الحية في الإنتاج — القاعدة المحلية صارت مطابقة بصميًا للإنتاج (مسار محلي يصح لأي اختبار انجراف قبل الإقلاع).
- **تشغيل محلي بالخصائص (لا كود):** jar مبني (`./mvnw package -DskipTests` → BUILD SUCCESS) أُقلع منفصلاً بخصائص العميلَين (الخطوة §3.1) — بعد تحرير المنفذ 8080 من عملية قديمة تحتاله. المهيّئان أسسا العميلين — **قراءة القاعدة الحية:**
  - `marketplace-web` (id الثابت `a7bd8b0d-7d42-4a64-9e34-1ad3ab22e37e`) — `client_secret_basic` + `authorization_code, refresh_token, client_credentials` + redirect `localhost:3000` + scopes `openid,profile` (التثبيتات أ/هـ: 900s/604800s/300s/reuse=false/consent=true/proofKey=true — لا انزلاق قيمي).
  - `marketplace-mobile` (id `10b588c6-4e85-43ec-9ecf-c588676774d7`) — `none` + `authorization_code` + redirect `com.marketplace.mobile://callback` — العميل العام.
- **S2 الحي بحروف سابقة `AuthorizationServerLoginGateIntegrationTest`:** تدفق end-to-end ضد `marketplace-web` — authorize→302 `/login`؛ login (form+CSRF بمستخدم مؤقت `s2_user`/`{noop}`) → 302؛ **consent مطلوب** (سكوبا openid+profile — §4.4-أ) → POST consent → 302 إلى `?code=…&state`؛ token بـ `client_secret_basic`+PKCE → **200 Bearer `expires_in=899`** (=900s — §3-أ) مع access_token+refresh_token+**id_token** — **ترويسة `{"kid":"3fe86f53-60c6-4942-8f4f-e555a9f43a66","alg":"RS256"}`** ومفتاح JWKS الحي بنفس الـ kid (RSA-2048) — **الفجوة الحية (غياب `id-token-signature-algorithm` — §2 المواصفة) مغلقة مقاسًا end-to-end**: الصف المؤسَّس يقرأ بخريطة إعدادات كاملة (8 مفاتيح — S1) ويثمر id_token RS256 قابلًا للتحقق. الادعاءات: `iss=http://localhost:8080`، `aud=marketplace-web`، `sub=s2_user`، `azp=marketplace-web`، auth_time/iat/exp سليمة.
- **العميل العام (تكملة المحطة):** تدفق PKCE **بلا مصادقة عميل** ضد `marketplace-mobile` — authorize→login→consent→code (إلى `com.marketplace.mobile://callback/`)→token بمعرّف العميل في الجسم + code_verifier → **200**؛ **access_token+id_token حاضرتان وrefresh_token غائبة** — السلوك الرسمي `refreshTokenGrantIsUnavailableForPublicClient` (§4.4-و) متحقق مقاسًا بغيابها في الرد. id_token بنفس الـ kid و`aud=marketplace-mobile`.
- **التنظيف (مخلفات المحطة فقط):** حُذف المستخدم المؤقت وصفّاه من `oauth2_authorization`/`oauth2_authorization_consent` + سلطته — القاعدة عادت إلى `admin` الوحيد (`{bcrypt}` من R__seed) وصفّا العميلين المؤسَّسين لم يُمسّا و`oauth2_authorization` صفر صفوف.
- **صفر كسر مقيس:** توثيق خالص — لا سطر كود/ترحيلة/حدود/اعتماديات/workflows؛ الدخان 4/4 (liveness/readiness/jwks/openid-config) مع الـ `/actuator/health` الكلي 503 المتوقع محليًا (§4). **المتبقي بيد المستخدم حصرًا:** إقلاع المستهلكين (Next.js على `:3000` — redirect/CORS مطابقة جاهزة، وKMP بعمق `mobile://callback`) خارج نطاق هذا المستودع (مبدأ §0.3).

---