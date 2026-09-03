# مواصفة توحيد تأسيس عميل OAuth2 على المسار الرسمي (OAuth2 Client Bootstrap Spec)

| البند | القيمة |
|------|--------|
| الحالة | **منفّذ ومدمج** (main `773558e`، CI أخضر ×2) — المتابعة الجراحية [#185](https://github.com/waelhe/app-java-v3/pull/185) |
| التاريخ | سبتمبر 2026 |
| المستند الحاكم | `docs/security/auth-system-redesign-plan.md` (المرحلتان 2 و3، F-A/F-B/F-C، D1-D9، T1-T4) |
| الحوكمة | Protocol-Enforcer 6-Stage + Source Mandate + تثبيتات مراجعة (5) |
| PR الفعلي | [#184](https://github.com/waelhe/app-java-v3/pull/184) — **مدمج** (773558e، 2026-09-02)؛ متابعة: [#185](https://github.com/waelhe/app-java-v3/pull/185) (استورد يتيم + javadoc + تأكيد id_token الحي + §3-ج) |

> هذه المواصفة تحوّل تثبيتات المراجع الخمسة إلى **معايير قبول** إلزامية — لا سطر كود
> قبل أن تُثبَّت كلها في التنفيذ، ولا خروج عنها إلا بتوثيق انحراف في PR body.

---

## 1) الهدف (Goal)

توحيد تأسيس `marketplace-web-client` على **المسار الرسمي الوحيد** —
`RegisteredClientRepository.save(RegisteredClient)` المبنية بالبايلدر الرسمي
(`ClientSettings.builder()` / `TokenSettings.builder()`) — بدلاً من محاكاة الصيغة
الداخلية في SQL، مع الحفاظ على كل القيم الظرفية الحالية وكسر الفجوة الحية في id-token.

**معيار النجاح:** بعد التنفيذ، يُقرأ العميل من قاعدة البيانات بمسار `mapRow` الحقيقي
(Jackson 3) بمخطط إعدادات **كامل** (لا مفتاح ناقص)، يمرّ CI بتدفق PKCE+oidc كامل
ضد العميل الفعلي، ولا يحوي المستودع سرّ عميل.

---

## 2) الوقائع المرجعية (مع الأدلة `ملف:سطر`)

### الفجوة الحية — عميل الـ seed غير صالح لأي تدفق openid
- `R__seed_oauth2_client.sql:38` يكتب خريطة token جزئية (4 مفاتيح فقط)
  — **لا** `settings.token.id-token-signature-algorithm` ولا `access-token-format`.
- `JdbcRegisteredClientRepository$AbstractRegisteredClientRowMapper.mapRow`
  يعوّض **مفتاحاً واحداً فقط** عند القراءة (`access-token-format`, L364-366).
- `JwtGenerator:106` يستدعي `getIdTokenSignatureAlgorithm()` **بلا حارس null**
  (مثبت ببايت-كود 7.1.1) — غياب المفتاح ⇒ أول تدفق `openid` ضد `marketplace-web-client`
  (scopesه `openid,profile`) **يرمي**. تدوير PR #183 لا يشفيها (انظر §4-ب).
- `execute` (العملية التجريبية `SeedSettingsRoundTrip`): `read-back getIdTokenSignatureAlgorithm() THROWS`.

### انجراف الصيغة مؤكد لكنه متسامح اليوم
- الإطار (Jackson 3) يكتب Duration نصياً `"PT15M"`؛ الـ seed كتبها رقماً `900`.
  المقروئية الحالية = **تسامح لا عقد** — ليست مطابقة لما يكتبه الإطار.
- خريطة `client_settings` من الباني خرجت **مطابقة حرفياً بايت-بايت** لنص الـ seed الحالي.

### حقائق بايت-كود/مصدر 7.1.1 (مؤكدة هذه الجلسة)
- `ClientSettings.builder()` افتراضي: `requireProofKey(true)` + `requireAuthorizationConsent(false)`;
  `isRequireProofKey()` null-safe (غياب المفتاح = false) — المصدر L106-108.
- `TokenSettings.builder()` افتراضي: code/access **5m**، refresh **60m**، **reuse=true**،
  `idTokenSignatureAlgorithm=RS256`، `accessTokenFormat=SELF_CONTAINED` — المصدر L137-146.
- `JdbcRegisteredClientRepository.save()`: بحث **بالـ PK `id`** عبر
  `findBy(PK_FILTER, id)` (L149) ثم **update أو insert**؛
  `assertUniqueIdentifiers` (**قبل** الإدراج، L170) يفحص `client_id` و`client_secret`
  بعدد `SELECT COUNT(*)` — **تصحيح مراجع لمقام سابق**.
- `RegisteredClient.from(x)` **ينقل الخرائط كما هي** (`withSettings` نفس map) — INV-4.
- **لا قيد فريد على `client_id`** — لا في المخطط الرسمي ولا في `V13__authorization_security.sql:32`
  (`PRIMARY KEY (id)` فقط) ولا في الرسمي (postgresql schema).
- `JwtGenerator` و`OAuth2AccessTokenGenerator:68` يستدعيان `getAccessTokenFormat()`
  مع `Assert.notNull`؛ `mapRow` يعوّضه يدوياً إن غاب (L364-366).

### حقائق المستودع
- `R__seed_oauth2_client.sql:30` — سر bcrypt **ثابت** في المستودع (`{bcrypt}$2a$10$EqK…`) = دين أمني.
- `OAuth2ClientSecretInitializer` — بعد التنفيذ: **يؤسس** العميل (غائب ⇒ `withId` + التعريف الكامل
  + باني الإعدادات) ويدّور السرّ (مختلف) ويقارب الإعدادات (خرائط مختلفة)؛
  **سبقه** = تطابق سرّ + خرائط (لا save). لم يعد يرث تأسيس الـ seed اليدوي (أُخلي من R__).
- `SecurityConfig:249-252` — `JdbcRegisteredClientRepository` كـ `@Bean` (يُزيح الذاكرية، D2).
- `AuthorizationServerLoginGateIntegrationTest:252-271` — عميل `it-login-gate-client`
  يُسجَّل عبر `save()` بالباني الرسمي ويمرّ CI بتدفق PKCE كامل — **المسار الرسمي مثبَت داخل المستودع أصلاً**.
- اختبار المُهيّئ القائم = mocks فقط؛ عميل الـ R__seed الحقيقي لم يُقرأ عبر القارئ الفعلي في أي اختبار.

---

## 3) التثبيتات الخمسة — عقد المواصفة غير القابل للتفاوض

### أ) توازن القيم (مصيدة الانزلاق)
الباني وحده **يغيّر السلوك** (الآتي افتراضياً: reuse=true / 5m / 60m / consent=false)
مقابل القيم الظرفية الحالية (reuse=false / 900s / 604800s / 300s / consent=true).
يُثبَّت **صراحةً** في البناء:
- `reuseRefreshTokens(false)`
- `accessTokenTimeToLive(Duration.ofSeconds(900))`
- `refreshTokenTimeToLive(Duration.ofSeconds(604800))`
- `authorizationCodeTimeToLive(Duration.ofSeconds(300))`
- `requireAuthorizationConsent(true)` (مفرض — الباني الافتراضي false)
- `requireProofKey(true)` (صراحةً — لا اعتماد على الافتراضي، D3)

### ب) ملكية الإعدادات — converge-on-boot
على البيئات القائمة: **`from(existing)` للهوية فقط** ثم
`.clientSettings(builder).tokenSettings(builder)`. **لا يُنقل أي جزء من الخريطة الموجودة**
(`withSettings` ينقل الخريطة المرضوضة بفجوة id-token كما هي — INV-4).
بهذا يتقارب الصف القائم على المخطط الكامل عند أول بدء ولا يبقى مريضاً أبداً.

### ج) قرار سلوك dev عند غياب env — التصميم الرسمي (مستند للوثيقة الرسمية)
**المرجع:** Spring Authorization Server — [Core Model / Components](https://docs.spring.io/spring-authorization-server/reference/core-model-components.html) (رسمية): «A client **must be registered** with the authorization server before it can initiate an authorization grant flow» — التسجيل للعميل يتم **حصراً** عبر `RegisteredClientRepository` (المركزي للمشتق/التسجيل) بالباني الرسمي؛ **لا وجود لعميل بلا تعريف**.
بعد إخلاء العميل من R__، التصميم الرسمي:
- **prod**: `application-prod.yml` يربط `OAUTH_CLIENT_ID/SECRET` إلزامياً (حاجز fail-fast قائم داخل المُهيّئ).
- **غير prod (dev/test)**: بلا مستهلك (D9 مفتوح) وبلا `OAUTH_CLIENT_*` = **لا عميل إطلاقاً** (العملية no-op) — كما في الكود `OAuth2ClientSecretInitializer.run` (`:99-110`). **مرفوض**: سرّ dev **مضمَّن في الكود** (يخالف المبدأ الرسمي «لا عميل بلا تعريف» ويخالف سطر المعيار §1 «لا يحوي المستودع سرّ عميل»)، وfail-fast شامل (يكسر dev/CI)، وسرّ عشوائي مسجَّل (فوضى).
- **لاتماثل convergence (موثق):** يُعالج الإعدادات لا الهوية — انحراف identity لاحقاً
  (scopes جديدة مثلأ) لن يصل للبيئات القائمة عبر المُهيّئ؛ له أثر في D9.

### د) الموقف من سباق الإقلاع
لا قيد فريد على `client_id` في المخططين (الرسمي + V13)، والتحقق **قبل** الإدراج
(assertUniqueIdentifiers)، و`findBy` يعيد **أول صف فقط** ⇒ سباق إقلاع مزدوج يُنتج تكراراً **صامتاً**.
- **الموقف**: Railway أحادية اليوم ⇒ العنصر مخرّج في المواصفة كسياسة تشغيلية محلية مؤكدة
  (المُهيّئ مبني للتلاقي على نفس `id` الثابت، فإن سبق سابقٌ قائماً تخطّاه بأمان).
- **عند HA مستقبلاً**: catch-and-recheck داخل المعاملة (موثق كـ follow-up، لا يُنفَّذ الآن).

### هـ) ثبات `id`
`id = a7bd8b0d-7d42-4a64-9e34-1ad3ab22e37e` **يُحفظ كثابت** — مراجع
`oauth2_authorization.registered_client_id` / `oauth2_authorization_consent.registered_client_id`
تشير إليه (V13:43-86). أي تغيير فيه يقطع السجلات القائمة.

---

## 4) شكل التنفيذ المعتمد (معايير قبول — يُنفّذ بسكربت TDD)

### 4.1 المسار الرسمي
`OAuth2ClientSecretInitializer` يمتد ليؤسس/يدوّر عميل الإنتاج عبر:

```java
RegisteredClient.client(properties.security().oauth2().client(), seedId)  // البناء الرسمي
```
أو صراحةً — **المكافئ البنائي** (تُكتب بالشكل المطابق للأسلوب الحالي):

```java
ClientSettings.builder()
    .requireProofKey(true)
    .requireAuthorizationConsent(true)
    .build();
TokenSettings.builder()
    .reuseRefreshTokens(false)
    .accessTokenTimeToLive(Duration.ofSeconds(900))
    .refreshTokenTimeToLive(Duration.ofSeconds(604800))
    .authorizationCodeTimeToLive(Duration.ofSeconds(300))
    .build();
RegisteredClient.from(existing)      // الهوية فقط — لا نقل خريطة الإعدادات (تثبيت ب)
    .clientId(clientId)
    .clientSecret(encoded)
    .clientSettings(settings)
    .tokenSettings(tokenSettings)
    .build();
```

**التعريف الكامل (نقل SQL→Java — مراجعة §Gap1 إلزامية):** الـ seed المحذوف يحمل أيضاً
الهوية الخام (`R__seed:27-36`) — `client_authentication_methods`, `authorization_grant_types`,
`redirect_uris`, `post_logout_redirect_uris`, `scopes`, `client_name` — وهي **مطلوبة لتفعيل S2/S3**
(مطابقة `redirect_uri` في authorize صارمة نصّياً). تُقرَّر **كثوابت Java في المُهيّئ** مطابقةً
حرفياً لمحتوى الـ seed، ويُعلن هذا النقل في جسم PR **بنداً مستقلاً** (أكبر من «قيم إعدادات»).
`client_authentication_methods = client_secret_basic` و`redirect` = `http://127.0.0.1:8080/login/oauth2/code/marketplace-web-client`
يعملان مع منفذ الاختبار العشوائي لأن مطابقة redirect نصّية لا اتصالية.

**حارس idempotence الموسّع (مراجعة §Gap2 إلزامية):** `save` يحدث ⇔ **السرّ اختلف ∨ خرائط
الإعدادات اختلفت** — لا `if (matches) return` وحده (`:91-93` يمنع convergence حين يطابق السرّ
وتختلف الإعدادات، فيُحبط S5). عند التأسيس: `RegisteredClient.builder().id(seedId)` (تثبيت هـ)
مع التعريف الكامل أعلاه. **قرار موثق:** الـ `id` الثابت يحوّل أسوأ حالة سباق إقلاع من تكرار
صامت (UUID عشوائي + لا UNIQUE على client_id) إلى فشل صوتي قابل لإعادة المحاولة — موقف مقصود.

### 4.2 إخلاء R__seed من أعمدة العميل
- `R__seed_oauth2_client.sql`: تُحذف `oauth2_registered_client` كاملة (`:12-40`) —
  **يُبقى** admin `auth_users`/`auth_authorities` (`:4-10`) لمستودع JdbcUserDetailsManager (D7).
- **انحراف موثق**: تحرير R__ (repeatable) مقبول — مصمَّم لإعادة التشغيل؛
  يُبيَّن في PR body (القاعدة 8) ويُلاحَظ في الملاحظة الحاكمة `auth-system-redesign-plan.md:37-38`.
- مع حذفه يتولى المُهيّئ **التأسيس** (لا التدوير فقط)، فيُغلق البند `Initializer:85-88`.
- `LoginGate` غير متأثر: `@TestPropertySource` يطبّق V13 مباشرة عبر
  `spring.sql.init.schema-locations` (بلا Flyway) ⇒ حذف R__ لا يمسه إطلاقاً.
- **قرار موثق (dev/لا-عميل):** بعد حذف الـ seed، dev/CI بلا `OAUTH_CLIENT_*` = لا عميل —
  مقبول للمرحلة 2 (لا مستهلك اليوم؛ الاستهلاك D9). يُثبَّت نصاً في PR body لا بصمت.
  بيئات dev القائمة تحتفظ بالصف القديم حتى أول إقلاع بإعداد env (convergence، تثبيت ب).

### 4.3 اختبارات (TDD — تفشل أولاً ثم تنجز)
**الشكل المعتمد (توصية المراجعة 3):** S2/S3/S4 تُمرَّن على **الصف الفعلي الذي يؤسسه المُهيّئ**
أثناء إقلاع السياق — تُضاف `marketplace.security.oauth2.client.*` إلى `@TestPropertySource`
فيعمل الـ `ApplicationRunner` بنفسه (لا يُسجَّل العميل داخل كود الاختبار). هذا وحده يُغلق
الفجوة الحية end-to-end ويثبت شيئاً فعلياً عن المُهيّئ لا عن فيكٍّ له. عميل `it-login-gate-client`
القائم (consent=false + id عشوائي — `LoginGate:252-271`) يبقى كما هو لاختباراته الأصلية.

| Id | اختبار | يُثبت | الحالة |
|----|--------|-------|--------|
| S1 | قراءة العميل الذي أسسه المُهيّئ عبر `mapRow` الحقيقي | خريطة **كاملة** (8 مفاتيح): `getIdTokenSignatureAlgorithm()`/`getAccessTokenFormat()` non-null + التعريف الكامل (grants/redirects/scopes) | جديد |
| S2 | تدفق openid كامل (authorize→code→token مع PKCE) ضد صف المُهيّئ + توليد id-token | الفجوة الحية مغلقة؛ PKCE مفروض (RFC 9700 §2.1.1) | جديد |
| S3 | consent مطلوب — **موجب وسالب بمستخدمين مختلفين** (يُخزَّن في `oauth2_authorization_consent` لكل principal) | D4 قائم (تثبيت أ) | جديد |
| S4 | قراءة القيم الظرفية مطابقة (reuse=false / 900s / 604800s / 300s + consent/proof-key) | لا انزلاق قيمي (تثبيت أ) | جديد |
| S5 | convergence: صف قديم → `from(existing)` للهوية + باني الإعدادات → خريطة كاملة | تثبيت ب — **حاكِ الصف القديم بـ `TokenSettings.withSettings(خريطة جزئية)`** (الباني ينتج الكاملة دائماً) | جديد |
| S6 | المُهيّئ: تأسيس (id ثابت + تعريف كامل)، تدوير، **السبق=سرّ يطابق ∨ خرائط تطابق** (لا save)، dev بلا env، prod fail-fast | تثبيتات ج/هـ/د + Gap2 | موسّع |

**تطور اختبارين قائمين (موثق — لا حذف):**
- `failsWhenConfiguredClientIdNotFound` (`InitializerTest:79-88`) **ينقلب أصله** مع S1:
  غياب العميل الآن = **تأسيس ونجاح** (بلا رمي) في غير إعداد fail-fast.
- `doesNothingWhenSecretAlreadyMatchesStoredValue` (`:44-53`) يُرتقى fixture
  إلى عميل **بخريطة كاملة**، وإلا انقلب توقعه (سرّ مطابق + إعدادات مطابقة = لا save؛
  سرّ مطابق + إعدادات مختلفة = save).

**متريّات القبول:** صفر اختبارات تُحذف أو تتعطل؛ اختباران يتطور توقعهما (موثقان أعلاه).

**مراجع نمطية:** `AuthorizationServerLoginGateIntegrationTest` (تصعيد PKCE، صفحة login/CSRF)،
`JwtRolesRoundTripTest` (T1)، اختبار المُهيّئ القائم (مُستبدل mocks بالحقيقي).

### 4.4 سجل الانحرافات والتصحيحات (موثّق أثناء التنفيذ — كل تصحيح بدليل لا بظن)

| # | الانحراف الملاحَظ | الدليل | التصحيح المطابق للرسمي |
|---|-------------------|--------|--------------------------|
| أ | اختبار S2 طلب `openid` وحده وتوقّع شاشة consent | بايت-كود `isAuthorizationConsentRequired` في 7.1.1: طلب scopes = `{openid}` بالضبط (حجم 1) يُستثنى **رسمياً** من الـ consent | S2/S3 يطلبان `openid+profile` (حجم 2، سكوبا تعريفان رسميان) — يُجبر consent |
| ب | توقّع أن الـ authorize المعتمد يعيد 302 إلى صفحة consent | التشغيل: `OAuth2AuthorizationEndpointFilter` **forward** إلى `DefaultConsentPage` كرد **200** (جسم "Consent required") | `consentGate` يتعامل مع 200 (body consent) و302 (كود مباشر موجود) كمسارين مشروعين |
| ج | إرسال `state`/`client_id` خاصتَي authorization في POST الـ consent | `OAuth2AuthorizationConsentAuthenticationProvider` يولّد الـ consent **state** مختلفاً ("Generated authorization consent state")؛ الصفحة تحوي hidden `state`/`client_id` | POST يعيد استخدام **القيم المستخرجة من جسم صفحة الـ consent** (اختبار مقبول بلا تخمين) |
| د | ظنّ `OAuth2AuthorizationConsentService.remove(id, principal)` ممكن | توقيع الواجهة: `remove(OAuth2AuthorizationConsent)` | `remove(findById(id, principal))` حين غير null |

**السياق**: كل تصحيح (أ-ج) تحقق منه **التشغيل الحيّ الأخضر** (`AuthorizationServerLoginGateIntegrationTest` 6/0:
تدفق openid→consent→code→id-token كامل + consent لكل principal + القيم الظرفية المطابقة).
لا تغيير في كود الإنتاج إلا ما مرّ تحت §4.1/§4.2.

---

## 5) ملفات الآثر (Impact Map) — خارج نطاق هذا المستند

| الدور | الملف |
|-------|-------|
| تعديل | `marketplace-platform-infra/src/main/java/com/marketplace/shared/security/OAuth2ClientSecretInitializer.java` |
| تعديل | `marketplace-app/src/main/resources/db/migration/R__seed_oauth2_client.sql` (إخلاء العميل) |
| تعديل/توسيع | اختبارات infra + `AuthorizationServerLoginGateIntegrationTest` |
| إضافة | اختبارات S1-S6 |
| قراءة فقط | `V13__authorization_security.sql`، `SecurityConfig.java`، `MarketplaceProperties.java`، `application-prod.yml` |

**في حدودها:** لا تُعدَّل V migrations القائمة، لا ملفات نشر (`application*.yml` تُقرأ فقط
ما دامت الربط قائماً)، لا تغيير سلوك PKCE (ساكن حتى المرحلة 3 — D9).

---

## 6) تبعات حوكمة وقرارات مفتوحة

- **المرحلة 2 (T3+T4) ليست "سداد دين" بل شرط تمكيني للمرحلة 3** — المرحلة 3 ستصطدم
  بفجوة id-token فور أول تدفق openid (القرار §5 من تقرير المراجعة).
- **ربط سجل حالة:** WORKLOG + `PROJECT_MAP.md` يُحدَّثان مع التنفيذ؛
  `SYSTEM.md §6` إشارة بذرة العميل — تُحدَّث عند اكتمال الـ PR.
- **مفتوحة خارج نطاق المواصفة:** قرار D9 للمرحلة 3 (بيد المستخدم)؛
  بند Railway `SPRING_PROFILES_ACTIVE=prod` (بيد المستخدم، خارج المستودع).

---

## 7) بوابات القبول

- [ ] التثبيتات الخمسة (أ-هـ) كلها ظاهرة في الكود والاختبارات.
- [ ] `mvn clean verify -pl marketplace-app -am` أخضر (رسمي فقط).
- [ ] `mvn clean verify -pl marketplace-platform-infra -am` أخضر.
- [ ] JaCoCo ≥ 70% وModulith boundary سليمان (لا انتهاك).
- [ ] gitleaks صامت (لا سرّ في المستودع).
- [ ] CI أخضر بسجل أعداد قبل/بعد — **صفر اختبارات تُحذف أو تتعطل**؛ اختباران يتطور توقعهما (موثقان §4.3).
- [ ] body PR يضم: انحراف تحرير R__ (القاعدة 8) + بند نقل التعريف الكامل SQL→Java + بند تطور الاختبارين + القرارات الثلاثة الموثقة (dev/لا-عميل في المرحلة 2، id الثابت كموقف مقصود لسباق الإقلاع، لاتماثل convergence: يُعالج الإعدادات لا الهوية).