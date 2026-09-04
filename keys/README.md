# مفاتيح توقيع JWT — دليل التشغيل (Runbook)

> **المرجع الحاكم:** `docs/security/auth-system-redesign-plan.md` (المرحلة 4، القرار D6، الثابت INV-7)
> + `docs/security/secrets-policy.md` §3 (دورة التدوير) + §5 (خط أساس الإنتاج).
> **الحالة الحية:** `SYSTEM.md` §11.

## 1) السياسة (لماذا هذا الملف موجود)

- **لا مفاتيح توقيع عابرة في غير بيئة التطوير** (INV-7). المفتاح العابر يولد عند كل إقلاع
  ⇒ كل التوكنات الصادرة تُبطل عند كل إعادة تشغيل، والنشر متعدد النسخ ينكسر كليًا
  (كل نسخة توقع بمفتاح مختلف).
- المسار الرسمي للبداية السريعة في وثائق Spring Authorization Server (توليد RSA عند
  الإقلاع) صُرّح عنه رسميًا بأنه «minimal configuration for getting started quickly» —
  مسموح هنا في **dev/test فقط**.
- **بوابة الإنفاذ (بالكود):** `SecurityConfig.jwkSource()` يفشل الإقلاع فورًا
  (`IllegalStateException`) إذا كان بروفايل `prod` نشطًا وأي خانة من خانات الـ keystore
  الأربع فارغة. تُفرض البوابة في CI عبر `JwkSourceProdHardeningTest` (وحدة داخل
  `marketplace-platform-infra`): prod + فراغ ⇒ فشل؛ غير prod + فراغ ⇒ مفتاح عابر؛
  prod + keystore مكتمل ⇒ تحميل المفتاح الدائم.
- **دفاع مزدوج مع الربط:** `application-prod.yml` يربط الخانات الأربع من متغيرات البيئة
  بلا افتراضات (فشل عند الربط إن غابت)؛ حارس `jwkSource` يمسك حالات السلاسل الفارغة
  وأي انجراف ربط مستقبلًا.
- **بند عمليات معلوم (خارج المستودع):** الحارس مقيد ببروفايل `prod` — يجب ضبط
  `SPRING_PROFILES_ACTIVE=prod` في بيئة النشر (بند مفتوح موثق في `SYSTEM.md` §11، بيد
  المستخدم، لا كود). بدونه يُقلع jar بلا بروفايل ويقع على السلوك العابر.

## 2) توليد المفتاح (keytool)

```bash
keytool -genkeypair \
  -keyalg RSA -keysize 2048 \
  -alias marketplace-jwt \
  -dname "CN=marketplace-jwt, OU=platform, O=marketplace" \
  -validity 90 \
  -keystore jwt-keystore.jks \
  -storetype JKS \
  -storepass "${JWT_KEYSTORE_PASSWORD}" \
  -keypass "${JWT_KEY_PASSWORD}"
```

ملاحظات:
- **JKS صراحة** (`-storetype JKS`): كود التحميل يستخدم `KeyStore.getInstance("JKS")`
  (تحذير keytool بالترحيل إلى PKCS12 متوقع ومقصود تجاهله هنا).
- RSA-2048 يطابق جدول إدارة المفاتيح في `docs/architecture/ARCHITECTURE.md` §6.
- صيغة الإنتاج: `JWT_KEYSTORE_PATH=file:/secure/path/jwt-keystore.jks`
  (البادئات المدعومة: `classpath:` / `file:` / مسار مجرد يُفسَّر `file:`).
- **لا يُخزَّن الملف ولا كلمتا المرور في المستودع أبدًا** (secrets-policy §1/§2) —
  الحقن عبر متغيرات البيئة فقط.

## 3) متغيرات البيئة (بيئة prod)

قناتان للمصدر — تفصل بينهما `SecurityConfig#jwkSource` (b64 تتقدم عند توفرهما معًا):

| المتغير | الخاصية المرتبطة | ملاحظة |
|---|---|---|
| `JWT_KEYSTORE_B64` | `marketplace.security.jwt.keystore.b64` | base64 لبايتات الـ JKS — قناة المنصات التي تسلّم الأسرار كمتغيرات write-only (Railway)؛ تُفكّ في الذاكرة (`KeyStore.load`) ولا يُكتب ملف إطلاقًا |
| `JWT_KEYSTORE_PATH` | `marketplace.security.jwt.keystore.path` | `file:` أو `classpath:` — قناة مضيفي التطوير والملفات المركّبة |
| `JWT_KEYSTORE_PASSWORD` | `marketplace.security.jwt.keystore.password` | كلمة مخزن الـ JKS (مشتركة بين القناتين — مطلوبة متى ضُبط أحدهما) |
| `JWT_KEY_ALIAS` | `marketplace.security.jwt.keystore.alias` | يصبح `keyID` للتوقيع |
| `JWT_KEY_PASSWORD` | `marketplace.security.jwt.keystore.keyPassword` | كلمة المفتاح الخاص |

مصدر ناقص (b64 أو path) بلا بيانات اعتماد كاملة = فشل إقلاع صريح في **كل** البروفايلات — لا يهبط أبدًا إلى مفتاح عابر.

## 4) التدوير (secrets-policy §3: كل ≤ 90 يومًا)

1. ولّد JKS جديدًا بمُعرّف جديد (alias جديد أو ملف جديد) — **لا تكتب فوق الحالي** (نافذة تراجع).
2. انشر المتغيرات الجديدة في بيئة التشغيل.
3. أعد النشر/التشغيل خلال نافذة حركة منخفضة (canary أولًا وفق `docs/release/rollout-strategy.md`).

**حد التصميم الحالي (موثق بصدق):** المصدر يحمل **مفتاحًا واحدًا نشطًا** (`ImmutableJWKSet`) —
استبداله يبطل فورًا كل توكنات الوصول (TTL ‏300 ثانية — أثر لحظي) وتوكنات التجديد
(7 أيام — يعيد المستخدمون تسجيل الدخول). لا تراكب مفتاحين (active+previous) اليوم؛
إن لزم مستقبلًا فهو قرار تصميمي جديد يُوثَّق في الخطة الحاكمة أولًا.

## 5) التحقق

- محليًا (dev): خانات فارغة ⇒ مفتاح عابر — طبيعي ومقصود.
- CI: `JwkSourceProdHardeningTest` (6 حالات: الثلاث الأصلية + قناة b64 في prod + الاعتمادات الناقصة في كل البروفايلات + تقدم b64 على path) ضمن `mvn clean verify`.
- في الإنتاج: نجاح الإقلاع نفسه هو الدليل (لا يقلع prod بمفاتيح فارغة).
