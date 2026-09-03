# خطة إعادة تصميم نظام المصادقة والتفويض (Auth System Redesign Plan)

| البند | القيمة |
|------|--------|
| الحالة | **خطة حاكمة — المراحل 0-2 و4 منفّذة ومدمجة (main `6fdec07` 2026-09-03؛ انظر SYSTEM.md §11 للحالة الحية) — متبقٍ: المرحلة 3 (D9 بقرار المستخدم) ثم مراحل CF** |
| التاريخ | سبتمبر 2026 |
| الفرع | `feat/auth-system-design` |
| الحوكمة | Protocol-Enforcer 6-Stage + Source Mandate (اقتباس رسمي → مطابقة → حل) |

> قاعدة ملزمة: أي تنفيذ في مرحلة لاحقة لا يُبدأ إلا بإذن صريح. دمج أي PR مرتبط
> (خاصة **#181 ثم #182**) لا يتم إلا بكلمة صريحة من المستخدم.

---

## 1) الملفات المتأثرة (Impact Map)

| دور | المسار/الكائن |
|-----|----------------|
| السلاسل (Chains) | `marketplace-platform-infra/src/main/java/com/marketplace/shared/security/SecurityConfig.java` |
| تخزين العملاء | `marketplace-app/src/main/resources/db/migration/R__seed_oauth2_client.sql` (عميل الإنتاج `marketplace-web-client`) |
| طبقة التحقق (Wire Gate) | `marketplace-app/src/test/java/com/marketplace/config/AuthorizationServerLoginGateIntegrationTest.java` (E5، في `test/auth-e5-login-gate`) |
| وحدة الدائرة (Unit Round-Trip) | `marketplace-platform-infra/src/test/java/com/marketplace/shared/security/JwtRolesRoundTripTest.java` (T1، في `a941af2`) |
| المفاتيح | `application*.yml` + `keys/README.md` + `SecurityConfig.jwkSource()` |
| القوالب | لا توجد قوالب مخصصة لـ login/consent — فقط `templates/email/*.html` |

---

## 2) المراحل والجدول

### المرحلة 0 — التوثيق والتحقيقات (مكتملة في هذا المستند)
مستندات فقط + تصحيح استشهاد javadoc. لا تغيير سلوكي.

### المرحلة 1 — طبقة بيانات الإعدادات (تُغلق F-A)
- إعادة كتابة `R__seed_oauth2_client.sql` بكتابة **صريحة وكاملة** لإعدادات عميل الإنتاج:
  `require-proof-key: true` **و** `require-authorization-consent: true` (الاثنتان مكتوبتان صراحة).
- اختبار T4 (round-trip للإعدادات XML/JSON عبر `JdbcRegisteredClientRepository`).
- **ملاحظة حوكمة:** تحرير ملف `R__` (repeatable) مقبول لأن repeatable seeds مصمَّمة لإعادة التشغيل؛
  يُوثَّق الانحراف كـ justification في جسم الـ PR وفق القاعدة 8.

### المرحلة 2 — تكافؤ الاختبار والتغطية (تُغلق F-B)
- اختبار T3: اختبار طبقة تطبيق يحمّل **نفس عميل R__seed الفعلي** (لا عميل مُصنَّع) ويؤكد:
  - PKCE مفروض فعلياً عند التبادل (التحقق من downgrade حسب RFC 9700 §2.1.1).
  - consent مطلوب لـ `marketplace-web-client` (اختبار موجب وسالب).

### المرحلة 3 — عميل الويب (تُغلق F-C — مشروطة بـ D9)
- (أ) التسجيل عبر `oauth2Login`، أو (ب) SPA/PKCE، أو (ج) إزالة العميل الميت.
- **لا يبدأ إلا بقرار D9 من المستخدم.**

### المرحلة 4 — مفاتيح الإنتاج (منفّذة — PR المرحلة-4)
- **الكود:** `SecurityConfig.jwkSource()` يحمل حارس fail-fast مقيد بـ `prod` (نفس نمط `OAuth2ClientSecretInitializer`): أي خانة keystore فارغة مع بروفايل prod ⇒ `IllegalStateException` عند الإقلاع — السقوط إلى المفتاح العابر مستحيل في prod. دفاع مزدوج مع ربط `application-prod.yml` بلا افتراضات.
- **فحص CI (تخفيف سجل المخاطر §8):** `JwkSourceProdHardeningTest` (وحدة داخل infra) يفرض الحالات الثلاث: prod+فراغ ⇒ فشل؛ غير prod+فراغ ⇒ مفتاح عابر (نمط quickstart الرسمي)؛ prod+keystore كامل ⇒ تحميل المفتاح الدائم من JKS حقيقي مولّد بـ keytool (`src/test/resources/keys/test-jwt.jks`).
- **Runbook:** `keys/README.md` (توليد keytool بـ JKS صريح + متغيرات البيئة الأربعة + تدوير ≤90 يومًا وفق secrets-policy §3 + حدود التصميم الموثقة بصدق).
- **تصحيح دين موثقي مرافق:** `ARCHITECTURE.md` كان يوثّق `RotatingJWKSource` بتدوير تلقائي لا وجود له في الكود (4 مواضع، منها ADR-004) — صُحّحت كلها إلى التصميم المنفّذ (ADR-004 Revised).

---

## 3) الاكتشافات (Findings) مع الأدلة

### F-A — PKCE سقط بالإغفال (مُثبت بالبايت-كود 7.1.1)
- `ClientSettings.builder()`: `requireProofKey(true)` + `requireAuthorizationConsent(false)` كافتراضي.
- `ClientSettings.withSettings(map)` تنشئ `new Builder()` **بدون** تطبيق الافتراضي ثم `settings(s -> s.putAll(map))`
  و`build()` = `new ClientSettings(getSettings())` **بلا دمج**.
- مسار القراءة: `JdbcRegisteredClientRepository.AbstractRegisteredClientRowMapper.mapRow` →
  `ClientSettings.withSettings(map).build()`.
- `isRequireProofKey()` = `Boolean.TRUE.equals(getSetting(ClientSettings.REQUIRE_PROOF_KEY))`.
- عميل الإنتاج (R__seed): `client_settings` يحوي `require-authorization-consent: true` فقط
  → `isRequireProofKey()` = **false** أثناء التشغيل (بالفوات، لا بالقرار).
- الأدلة: `spring-security-oauth2-authorization-server:7.1.1` (بايت-كود `ClientSettings`، `JdbcRegisteredClientRepository`).

### F-B — لا اختبار يحمّل عميل R__seed الفعلي
- E5 يحمّل مخطط `V13__authorization_security.sql` عبر
  `spring.sql.init.schema-locations`، ويسجّل عميل **بنيوي** (synthetic) `it-login-gate-client`
  بـ `requireProofKey(true)` + `requireAuthorizationConsent(false)` عبر
  `RegisteredClientRepository.save(...)` — أي لا يمس عميل الإنتاج إطلاقاً.
- T1 (JwtRolesRoundTripTest) وحدة داخل infra على عميل مُصنَّع.
- النتيجة: مسار consent الحقيقي لـ `marketplace-web-client` بلا تغطية.

### F-C — عميل ميت بلا مستهلك
- لا يُستخدم `marketplace-web-client` فعلياً (لا `oauth2Login` ولا مكتبة client).
- حُفظ قرار التأجيل كسجل حالة في `PROJECT_MAP.md` (انظر D9).

---

## 4) القرارات (D1–D9)

| # | القرار | الحالة | التوصية | المصدر |
|---|--------|--------|----------|--------|
| D1 | تعرف التطبيق كل سلاسله صراحة (RS بـ `oauth2ResourceServer.jwt` + سلسلة AS عبر DSL + default) | ثابت/موثق | إبقاء التوليف الصريح كـ INV-5 | بايت-كود + `SecurityConfig:92-157` |
| D2 | مصدر الحقيقة لإعدادات العملاء | **مغلق حسمه** (DB عبر `JdbcRegisteredClientRepository` — الخصائص الذاكرية خاملة) | (ب) Flyway بإعدادات كاملة صريحة → تحقق لاحقًا إلى: DB هي الحقيقة عبر المُهيّئ على المسار الرسمي | Boot 4.1.1: `OAuth2AuthorizationServerConfiguration.registeredClientRepository()` هو `@ConditionalOnMissingBean` + `@Conditional(RegisteredClientsConfiguredCondition)`؛ وهو **بلا أثر** هنا لأن التطبيق يعرّف `JdbcRegisteredClientRepository` |
| D3 | `require-proof-key: true` كتابة صريحة لكل العملاء | **مغلق (منفّذ)** | يثبّته باني المُهيّئ صراحة (`requireProofKey(true)`) ويفرضه S2 على الصف الفعلي | RFC 9700 §2.1.1 |
| D4 | consent لعميل الإنتاج | **مغلق (منفّذ)** | `requireAuthorizationConsent(true)` في باني المُهيّئ + S3 موجب/سالب بمستخدمين مختلفين | RFC 9700 §4.11.2/§4.14.2 |
| D5 | عقد الادعاءات | ثابت | `roles` (RFC 9068 §7.2.1.1 — أصله RFC 7643 §4.1.2 / RFC 9068 §2.2.3.1)؛ `aud` (RFC 7519 §4.1.3 — javadoc صُحّح في المرحلة 0)؛ `FACTOR_BEARER` لا يُبنى عليه شيء | RFC 9068 §2.2: `aud REQUIRED - as defined in Section 4.1.3 of [RFC7519]` |
| D6 | مصدر المفاتيح | **مغلق (منفّذ — PR المرحلة-4)** | Keystore دائم عبر env في الإنتاج؛ لا مفاتيح عابرة — يُنفّذ بحارس fail-fast مقيد بـ prod + فحص CI (`JwkSourceProdHardeningTest`) + runbook `keys/README.md` | `application.yml` (افتراضيات فارغة) + `SecurityConfig.jwkSource()` (الحارس) + `keys/README.md` |
| D7 | `JdbcUserDetailsManager` بـ SQL مخصص | ثابت | إبقاؤه | `SecurityConfig:236-246` |
| D8 | الجلسات/CSRF | ثابت | سلسلة AS: جلسة + CSRF؛ سلسلة RS: `STATELESS` مع تجاهل CSRF لمسارات `/**` المطابقة | `SecurityConfig:119,121,150-154` |
| D9 | مصير `marketplace-web-client` | **قرار المستخدم** | (أ) عميل سري server-side/BFF مستقبلاً، أو (ج) تعليق/إزالة العميل؛ (ب) SPA/PKCE | RFC 9700 §2.1.1 |

---

## 5) الثوابت (INV-1..7)

1. الإعدادات المكتوبة في قاعدة البيانات هي الحقيقة — لا اعتماد على افتراضي Builder (يُفرض بـ T4).
2. كل تسجيل عميل يحمل إعدادات كاملة صريحة (`require-proof-key` و `require-authorization-consent`).
3. عميل الإنتاج مغطى باختبار يحمّله كما هو (T3).
4. عقد الادعاءات: `aud` = RFC 7519 §4.1.3، `roles` = RFC 9068 §7.2.1.1؛ `FACTOR_BEARER` غير معتمد.
5. كل سلاسل الأمن معرّفة صراحة — لا سلاسل ضمنية.
6. الجلسات/CSRF: AS بجلسة، RS `STATELESS` — لا تراجع.
7. لا مفاتيح توقيع عابرة في غير بيئة التطوير.

---

## 6) سلم الاختبارات (T1–T4)

| Id | الاختبار | المرحلة | الحالة |
|----|----------|---------|--------|
| T1 | `JwtRolesRoundTripTest` (وحدة داخل infra — العميل المُصنَّع + conversion) | — | ✅ موجود (`a941af2`) |
| T2 | `AuthorizationServerLoginGateIntegrationTest` (سلك كامل: PKCE موجب، 403 سالب، تدوير refresh، صفحة login/CSRF) | — | ✅ موجود (wire، قبل `a941af2`) |
| T4 | round-trip إعدادات العميل الفعلي | 1 | **✅ تُحقّق بـ S1/S4/S5** (PR #184): قراءة الصف الذي أسسه المُهيّئ عبر `mapRow` الحقيقي — خريطة كاملة 8 مفاتيح + القيم الظرفية + convergence (R__ لم يعد يحمل العميل — الصف الفعلي = صف المُهيّئ) |
| T3 | تحميل العميل الفعلي + التحقق من consent وPKCE | 2 | **✅ تُحقّق بـ S2/S3** (PR #184): PKCE مفروض عند التبادل على الصف الفعلي + consent موجب/سالب بمستخدمين مختلفين (مواصفة §4.3 — الشكل المعتمد عبر `@TestPropertySource` + ApplicationRunner) |

---

## 7) الاقتباسات الرسمية (Verbatim)

- **RFC 9068 §2.2**: «aud  REQUIRED - as defined in Section 4.1.3 of [RFC7519]».
- **RFC 9068 §7.2.1.1**: «Specification Document(s): Section 4.1.2 of [RFC7643] and Section 2.2.3.1 of RFC 9068».
- **RFC 9700 §2.1.1**: «Public clients MUST use PKCE [RFC7636]...» و«For confidential clients, the use of PKCE [RFC7636] is RECOMMENDED...» و«Authorization servers MUST support PKCE [RFC7636]» و«If a client sends a valid PKCE code_challenge parameter in the authorization request, the authorization server MUST enforce the correct usage of code_verifier at the token endpoint» و«Authorization servers MUST mitigate PKCE downgrade attacks by ensuring that a token request containing a code_verifier parameter is accepted only if a code_challenge parameter was present in the authorization request».
- **RFC 9700 §4.1.3**: «the authorization server MUST ensure that the two URIs are equal; see Section 6.2.1 of [RFC3986], Simple String Comparison».
- **RFC 9700 §4.14.2**: «If refresh tokens are issued, those refresh tokens MUST be bound to the scope and resource servers as consented by the resource owner».

---

## 8) سجل المخاطر

| المخاطرة | الأثر | التخفيف |
|----------|------|---------|
| Jackson 3.2.2/ليست بيضاء للـ settings JSON عند إعادة كتابة `client_settings` | كسر قراءة العميل | كتابة الـ JSON بنفس تنسيق `{"@class":"..."}` الحالي في T4 |
| مفتاح RSA عابر لكل إقلاع (من غير env) | إبطال التوكنات عند إعادة التشغيل | المرحلة 4 + فحص CI يحظر `JWT_KEYSTORE_*` الفارغة في الإنتاج |
| تدفق إنتاج ساكن (لا يماثله CI) | فقدان اكتشاف التحولات | T3 يحمّل R__seed الفعلي |
| الاعتماد على `FACTOR_BEARER` | كسر غير مضمون الإصدارات | ممنوع — توثيق فقط |

---

## 9) بوابات المرحلة 0 (مُتحقَّق منه → يُغلق بالتنفيذ)

- [x] جرد الملفات والحالة (الفرع، `docs/security`، القوالب، `keys/README`).
- [x] D2: آلية Boot 4.1.1 مقصورة على InMemory المشروط — `JdbcRegisteredClientRepository` هنا يعلّقها.
- [x] D3/D4: اقتباسات RFC 9700 verbatim (المرجع `rfc-editor.org/rfc/rfc9700.txt`).
- [x] D5: اقتباسات RFC 9068 verbatim + تصحيح javadoc `SecurityConfig:353`.
- [x] D6: الافتراضيات الفارغة + فرع التوليد العابر.
- [x] F-B: E5 عميل بنيوي (لا R__seed) — تسجيله عبر `RegisteredClientRepository.save(...)` بـ proofKey=true/consent=false.
- [ ] المرحلتان 1 و2 تتطلبان موافقة المستخدم الصريحة؛ قرار D9 وترتيب الدمج (#181 ثم #182) بانتظار كلمته.