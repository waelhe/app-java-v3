# تقرير فحص حزمة Airbnb 26.42

- **الملف الأصلي:** `C:\Users\w-co\Desktop\app\Airbnb_26.42.apks`
- **الحجم:** 141,597,860 بايت
- **SHA256:** `95D5B45BF4C3ED5E8807554513CEA07DA5573D218041A670B53A9DD81C92E920`
- **تاريخ الفحص:** 2026-10-19
- **مجلد العمل:** `D:\rea-airbnb\`

---

## 0) بنية الحاوية (كيف فُكَّت)

الحزمة `.apks` ليست ZIP عادياً: هي **ZIP مُتدفّق بلا فهرس مركزي (central directory) وبلا EOCD**، وكل مدخلاته مضغوطة بمستوى
`deflate 0` (كتل مخزّنة خام). لهذا رفضتها كل الأدوات القياسية (zipfile / apktool / REA).

حُلّت بقراءة ترويسات المدخلات المحلية (Local File Headers) مباشرةً وتفكيك تدفّق deflate لكل مدخل. المحتوى:

| # | المدخل | المخزّن (بايت) | المفكوك (بايت) | المحتوى |
|---|--------|---------------|----------------|---------|
| 0 | `base.apk` | 133,847,581 | 133,827,161 | 4446 مدخلاً، 11 ملف DEX |
| 1 | `icon.png` | 18,840 | 18,835 | أيقونة PNG |
| 2 | `split_config.ar.apk` | 1,298,920 | 1,298,725 | 4 مدخلات (تعريب عربي) |
| 3 | `split_config.arm64_v8a.apk` | 5,687,164 | 5,686,299 | 13 مدخلاً (مكتبات أصلية) |
| 4 | `split_config.xxhdpi.apk` | 745,041 | 744,931 | 160 مدخلاً (موارد كثافة) |

كل الأجزاء الأربعة اجتازت `zipfile.testzip()` بنجاح (بصمات CRC سليمة).

---

## 1) هوية التطبيق

| الحقل | القيمة |
|------|--------|
| package | `com.airbnb.android` |
| versionName | `26.42` |
| versionCode | `28026652` |
| minSdkVersion | 32 |
| targetSdkVersion | 36 |
| compileSdkVersion | 37 (Codename 17) |
| targetSandboxVersion | 2 |
| installLocation | auto |
| requiredSplitTypes | base__abi, base__density |
| كلاس التطبيق | `com.airbnb.android.ApplicationProxy` |
| الثيم | `@style/BaseUiPrimitivesTheme.Airbnb` |
| largeHeap | true (مفعّل) |
| packageId الموارد | 0x7f |

---

## 2) الملف الأمني (AndroidManifest)

### 2.1 الأذونات (46 إجمالاً)
**الخطرة/الحساسة:** ACCESS_FINE_LOCATION · ACCESS_COARSE_LOCATION · CAMERA · RECORD_AUDIO ·
READ_CONTACTS · READ_PHONE_STATE · CALL_PHONE · MANAGE_OWN_CALLS · BLUETOOTH_SCAN · BLUETOOTH_CONNECT ·
READ_EXTERNAL_STORAGE · WRITE_EXTERNAL_STORAGE · READ_MEDIA_IMAGES · POST_NOTIFICATIONS ·
AD_ID (GMS) · READ_GSERVICES · FOREGROUND_SERVICE_PHONE_CALL.

**أخرى بارزة:** INTERNET · ACCESS_NETWORK_STATE · ACCESS_WIFI_STATE · CHANGE_WIFI_STATE · NFC ·
USE_BIOMETRIC · USE_FINGERPRINT · RECEIVE_BOOT_COMPLETED · WAKE_LOCK · VIBRATE · FLASHLIGHT ·
GET_ACCOUNTS · com.android.vending.CHECK_LICENSE · com.google.android.c2dm.permission.RECEIVE ·
com.huawei.appmarket.service.commondata.permission.GET_COMMON_DATA ·
com.sfr.android.console.permission.partner.READ_STATISTICS.

**أذونات مُعرَّفة من التطبيق (protectionLevel=sig):**
`.permission.MAPS_RECEIVE` · `com.airbnb.android.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`.

### 2.2 إعدادات التطبيق
| الإعداد | القيمة |
|---------|--------|
| allowBackup | **true** (⚠ يسمح بالنسخ الاحتياطي/الاستخراج) |
| usesCleartextTraffic | true (مقيّد عبر سياسة الشبكة أدناه) |
| networkSecurityConfig | `@xml/network_security_config` |
| extractNativeLibs | false (تحميل المكتبات من الـAPK) |

### 2.3 سياسة أمان الشبكة (network_security_config.xml)
- **يُمنع** النص الصريح (cleartext) لـ: `airbnb.com`, `airbnbchina.cn`, `airbnb.cn` (مع النطاقات الفرعية).
- **يُسمح** به فقط لـ: `10010.com` (مزوّد صيني / China Unicom).

### 2.4 المكوّنات
| النوع | الإجمالي | المُصدَّر (exported=true) |
|------|---------|--------------------------|
| Activity | 147 | 28 |
| activity-alias | 1 | 0 |
| Service | 30 | 4 |
| Receiver | 26 | 5 |
| Provider | 11 | **0** (لا مزوّد محتوى مُصدَّر) |

**مُصدَّرة بارزة (Activities):** `DeepLinkEntryActivity` (VIEW/SEARCH/EDIT) · `AirlockActivity2` ·
`PaymentOptionsActivity` · `MessagingSharingContentEntryPointActivity` (SEND) · `HomeActivity` ·
`SplashScreenActivity`. **بوّابات دفع/توثيق:** Stripe (`StripeBrowserProxyReturnActivity`,
`FinancialConnectionsSheet*`) · Klarna (`KlarnaAuthenticationRedirectReceiverActivity`) · Razorpay ·
Kakao · Naver · Line · WeChat/Tencent · Facebook · Singular · `RedirectUriReceiverActivity` (AppAuth).

**مُصدَّرة (Services):** `CallService` (BIND_TELECOM_CONNECTION_SERVICE) · `SystemJobService` (WorkManager) ·
`ChooserTargetServiceCompat` · GMS `RevocationBoundService`. **Receivers:** FirebaseInstanceIdReceiver ·
ReferralBroadcastReceiver · Perfetto TracingReceiver · ProfileInstallReceiver · DiagnosticsReceiver.

---

## 3) فك الكود (jadx) وحضور مكتبات الأمان

- **42,555 ملف Java** في `D:\rea-airbnb\jadx_out`.
- أعلى الحزم: `defpackage` **30,087** (كود مُشوَّهِ بـR8) · `com.airbnb` 7,475 ·
  **`com.incognia` 1,613** · `com.google` 723 · `com.fingerprintjs` 321 · `io.jsonwebtoken` 113 ·
  `com.stripe` 124 · `com.razorpay` 111 · `com.tencent` 91 · `com.worldline` 86 · `com.singular` 51 ·
  `com.linecorp` · `com.nhn` · `com.klarna` · `com.braintreepayments` · `okhttp3` · `androidx.work` · `butterknife`.

### 3.1 حضور الأمان / مكافحة الاحتيال
- **OkHttp `CertificatePinner`** → دعم **تثبيت الشهادات (TLS pinning)**.
- **Google Play Integrity API** (`com.google.android.play.core.integrity.StandardIntegrityManager`).
- **reCAPTCHA** (يربط Play Integrity).
- **FingerprintJS Pro** (`com.fingerprintjs.android.fpjs_pro_internal`) → بصمة جهاز/مكافحة احتيال.
- **Incognia** (`com.incognia`, أكبر SDK غير Airbnb) → بصمة جهاز + مكافحة احتيال بالموقع.
- **لا يوجد** كشف root/tamper في كود Airbnb الخاص (المتاح: مكتبات BugSnag الأصلية).

---

## 4) المكتبات الأصلية (arm64 — 11 ملف `.so`)

كلها `AArch64` **مُجرَّدة** (بلا جدول رموز/بلا معلومات تنقيح)، مُبنية بـclang (12.0.8 / 14.0.7 / 19.0.1 / 21.0.0).

| المكتبة | الحجم | ملاحظات |
|---------|-------|---------|
| `libb8c6.so` | 775 KB | **اسم مُشوَّه**، بلا بصمة مترجم؛ يصدّر `JNI_OnLoad` فقط + يستدعي `fork/execv/kill/killpg/waitpid/mprotect/prctl/dlopen/dup2` → **مكتبة حماية/مكافحة عبث أصلية** |
| `libfp.so` | 385 KB | **FingerprintJS Pro** (`Java_com_fingerprintjs_android_fpjs_pro_internal_*`) |
| `libfilament-jni.so` | 2.1 MB | عرض ثلاثي الأبعاد (Filament) |
| `libavifandroid.so` | 928 KB | ترميز AVIF |
| `libbugsnag-ndk.so` | 1.1 MB | BugSnag NDK |
| `libbugsnag-root-detection.so` | 5 KB | كشف root أصلية |
| `libbugsnag-plugin-android-anr.so` | 13 KB | مراقبة ANR |
| `libdatastore_shared_counter.so` | 7 KB | DataStore |
| `libimage_processing_util_jni.so` | 28 KB | معالجة صور |
| `libsurface_util_jni.so` | 5 KB | سطح العرض |
| `libandroidx.graphics.path.so` | 10 KB | مسارات رسومية |

---

## 5) الموارد والتعريب

- **تقسيم `ar` (عربي):** تعريب **كامل** — **8,625 نص عربي** من أصل 8,645، متوسط الطول 29.7 حرف، مع خط
  `res/font-ar/airbnbcereal_variable.ttf`.
- **تقسيم `xxhdpi`:** 157 مورد كثافة (أيقونات `n2_explore_*`، أيقونات `exo_*` من Media3، Klarna/Razorpay) + `resources.arsc`.
- **base.apk:** 11 ملف DEX + `assets` + `res` (فُكّت بـapktool).

---

## 6) المخارج (على القرص D:)

`D:\rea-airbnb\` (~400 MB):
- `outer_base.apk` — الـAPK الأساسي (صالح، 4446 مدخلاً).
- `outer_split_config.ar.apk` / `outer_split_config.arm64_v8a.apk` / `outer_split_config.xxhdpi.apk`.
- `outer_icon.png`.
- `base_decoded\` — مخرجات apktool (AndroidManifest + الموارد).
- `ar_decoded\` — موارد التعريب العربي.
- `jadx_out\` — 42,555 ملف Java مُفكَّك.
- `libs\lib\arm64-v8a\` — المكتبات الأصلية.
- السكربتات: `outer_extract.py`, `elf_profile.py`, `syms.py`, `ar_strings.py`, `manifest_profile.py`.

**حالة القرص C:** بعد التنظيف → **12.41 GB حرة** (بينما كانت 2.26 GB).

---

## 7) خلاصة أمنية سريعة
1. ثقة عالية بمصدر التوقيع (4 APKs موقّعة، بصمات سليمة) ولكن الحاوية ملتوية عمداً (بلا فهرس/EOCD).
2. شبكة مقيّدة جيداً: pinning مدعوم + منع cleartext لكل نطاقات Airbnb.
3. سطح تصدير كبير (28 نشاطاً) لكن جميعها عبر deep-link/OAuth/دفع وبلا `provider` مُصدَّر.
4. تتبّع مكثّف: FingerprintJS + Incognia + Singular + AD_ID + GMS — بصمة أجهزة ومكافحة احتيال.
5. `allowBackup=true` يسمح باستخراج بيانات التطبيق على الأجهزة غير المُشفّرة.

---

## 8) تحليل واجهة المستخدم / تجربة الاستخدام (UI/UX)

### 8.1 المعمارية البصرية: Views وليست Compose
**دليل قاطع:** البحث في كامل الكود المُفكَّك (`jadx_out`) عن `@Composable` أعطى **صفر** نتائج في كود التطبيق؛
حضور `androidx.compose` محصور في مكتبة الأدوات (`androidx.compose.ui.tooling.PreviewActivity`) فقط.
إذن الواجهة مبنية بـ**Android Views + تخطيطات XML**، وهذا يطابق معمارية Airbnb المعروفة: **MvRx** + **AirEpoxy (Epoxy)**.

### 8.2 نظام التصميم: DLS (Design Language System) — نظام داخلي مكتمل
نظام تصميم دلالي (Design Tokens) مبني على القواعد التالية:

- **الطباعة (Typography):** أنماط بأسماء `DlsType_<الدور>_<المقاس>_<الوزن>[.مُعدِّل]` — نحو **70 نمطاً**:
  - **الأدوار:** `Base` (نص أساسي) · `Interactive` (نص تفاعلي/أزرار) · `Kicker` (نص علوي صغير) · `Title` (عناوين).
  - **المقاسات:** `XS · S · M · L · XL` (+ متغيّر `Tall` لارتفاع سطر أكبر).
  - **الأوزان:** `Book · Medium · Bold`.
  - **المُعدِّلات:** `.Inverse` (نص على خلفية داكنة) · `.Secondary` · `.Caps` (أحرف كبيرة) · `.Arches` (لون الخطأ/التحذير).
- **الألوان — رموز دلالية:** `dls_primary_text`, `dls_bg_*`, `dls_inverse_text` … مع نسخ للوضع الداكن.
  - هوية: `rausch` (الوردي المميّز #FF385C)، `babu` (تركوازي)، `arches` (أحمر خطأ) + تدرّجات وألوان «3D».
  - رمادي محايد: عائلة `hof / foggy / deco / bebe` ونظائرها.
- **التباعد (Spacing):** شبكة **4dp** عبر `dls_space_1x … dls_space_20x` (أي 4dp → 80dp).
- **الزوايا (Corner radius):** `4 · 8 · 12 · 16 · 20 · 24dp` (`dls_corner_radius_*`).
- **الثيم الجذري:** `BaseUiPrimitivesTheme.Airbnb` (نظام «Primitives» أقدم يُدمج مع DLS).

### 8.3 الشاشات والتخطيطات
- **650 ملف تخطيط XML** (`res/layout`) + **11** للتخطيط الأفقي (`layout-land`) + **16 قائمة** (`menu`).
- أصناف `*Screen` (نحو مئات، مثل `A11ySettingsScreen`, `AddListingsScreen`, `AcceptedDocumentsScreen`)
  تمثّل طبقة **MvRx view-model/controller** الواحدة لكل شاشة، فتتفرّع الشاشات والمنطق عن العرض عبر Epoxy.

### 8.4 الحركة (Motion)
- **69 رسوم متحركة** (`res/anim`) + **20** (`res/animator`) — انتقالات وشاشات دخول/خروج.

### 8.5 دعم RTL والعربية
- لا يوجد مجلد `layout-ldrtl` (التصميم يستخدم `start/end` أصلية)، ويوجد `drawable-ldrtl-*` للعناصر الرسومية.
- تعريب كامل بـ**8,625 نص عربي** + خط عربي مخصّص `res/font-ar/airbnbcereal_variable.ttf` (6 خطوط إجمالاً: Cereal Variable بأوزان).

### 8.6 خلاصة UI/UX
- نظام تصميم **ناضج جداً ومُرمَّز بالكامل** (DlsType + dls_space + dls_corner_radius + dls_* ألوان) يُدار مركزياً عبر الموارد.
- التطبيق **لم ينتقل إلى Jetpack Compose**؛ ما زال Views + Epoxy + MvRx.
- تجربة كاملة متعددة الأسطح: إعلانات/حجز/رسائل/دفع/تجارب وخدمات + تعريب RTL ممتاز + رسوم ثلاثية الأبعاد (Filament).

---

## 9) تعميق UI/UX (قياسات مُستخرجة فعلياً)

### 9.1 مقياس الطباعة الدقيق (DLS Type)
الخطوط: `cereal_book` · `cereal_medium` · `cereal_bold` (خط Airbnb Cereal) + `airbnbcereal_variable` للعربية.
كل نمط يحدد `textSize` + `lineSpacingExtra` (فجوة السطر) + `letterSpacing=0` + لون دلالي «stateful».

| الدور | XS | S | M | L | XL |
|------|----|----|----|----|----|
| **Base** (Book/Bold) | 10/12 | 12/16 | 14/18 | 16/20 | 18/24 |
| **Interactive** (Medium) | — | 12/16 | 14/18 | 16/20 | 18/24 |
| **Kicker** (Bold · CAPS) | 10/12 | 12/16 | — | — | — |
| **Title** | 18/24 | 22/26 | 26/30 | 32/36 | — |

(القيمة = `نص/سطر` بالـsp). متغيّر `Tall` يرفع ارتفاع السطر للنصوص الطويلة (M→20 · L→24 · XL→28).

### 9.2 التباعد والزوايا
- **`dls_space`:** شبكة **4dp** بـ20 خطوة من `1x=4dp` حتى `20x=80dp`.
- **`dls_corner_radius`:** `4 · 8 · 12 · 16 · 20 · 24dp`.

### 9.3 الألوان والوضع الداكن
- **469 لوناً نهارياً** (منها **91 رمز `dls_`**) مقابل **65 لوناً ليلاً** في `values-night`.
- تغطية داكنة كاملة: `color-night` + `drawable-night` + `drawable-night-xxhdpi` + `values-night`.

### 9.4 جرد الشاشات — 1001 صنف `*Screen`
أنماط معمارية ظاهرة في الأسماء: `feat/<feature>/trio` (شاشات ثلاثية: تحميل/نجاح/خطأ)، `flow`، `puzzle`، `quickreplies`.
أعلى الميزات عدداً للشاشات: رسائل/ردود سريعة (15) · checkout/trio (14) · campaignlisting (8) · unified/cancellations (7) · payments/mst/redirects (7) · propertyeditor/roomlist (6) · guidebook/editor (5) · earnings/insights (5) · hostingdetails (5).

### 9.5 إطار SDUI — **Nezha2** (واجهة تُدار من السيرفر)
`assets/nezha/nezhaConfigurations.json` يكشف إطار واجهات تدفّقها من الخادم:
- كل صفحة `type: remote_loading` أو `local_loading`.
- تحكّم كامل بصرياً: `navigation_bar` (style: basic/outline_text/inverse_specialty · title · trailing_elements · force_hide_divider · collapse_on_scroll · hide/display offsets · background_color) وحالات تحميل (`loading_behavior.manual`).
- ربط بالميزات التجريبية عبر `trebuchet_names` و`experiment_names` (أعلام ميزات لكل صفحة).

### 9.6 التبويبات والتنقّل (تصميم 2025-2026)
أصول التبويبات «Pill» تكشف الشرائح الرئيسية: **Stays · Experiences · Services · Hotels**
(`stays_0314`, `experiences_0314`, `services_0314`, `hotels_tab`, و`*_pill_enter`/`*_pill_select` لكل تبويب).

### 9.7 الحركة والرسوم
- **69** رسم `res/anim` + **20** `res/animator`.
- **88 ملف Lottie JSON** (مثل `badge_pulse`, `card_complete`, `check`, `ic_system_*_anim_v04`) لتفاعلات دقيقة.
- **13 ملف `.lava`** — صيغة حركة **خاصة بـAirbnb** (حاوية **RIFF بمعرّف `LAVA`**)، أبرزها حركات أزرار التنقّل/التبويبات.

### 9.8 الأيقونات والصور
- **2,487** ملف في `drawable` الأساسي + كثافات: `nodpi` 23 · `xhdpi` 22 · `xxhdpi` 28 · `xxxhdpi` 3 · `ldrtl` 3.
- 18 صورة **WebP** (ومعها دعم AVIF أصلي عبر `libavifandroid.so`) + عرض ثلاثي الأبعاد (Filament).

### 9.9 الاستجابة والتخطيطات التكيّفية
- تخطيطات: `650` `layout` + `11` `layout-land`.
- أحجام شاشة: `sw360/375/400/600/720dp` · `w688dp` · `w480dp-port` · `h720dp` · `land` → **هواتف قابلة للطي وأجهزة لوحية**.
- **Wear OS:** `values-watch` + `drawable-watch`.

### 9.10 التنقّل العميق (Deep Links)
- **320 `intent-filter`** و**32,586 `host`** مرتبطة بـ`https/http`.
- مخططات مخصّصة: `airbnb`, `com.airbnb.android(.development/.braintree…)`, `stripe*`, `klarna_*`, `kakao_*`, `lineauth`, `naver3rdpartylogin`, `tencent100746598`, `fbconnect`, `upi`, `*redirect_url_scheme`.
- تغطية عالمية: نطاق Airbnb لكل بلد + نطاقات فرعية لغوية (`ar/fr/de/ja…airbnb.com`, `airbnb.co.in`, `hi/kn/mr…`).

### 9.11 خلاصة التعميق
- نظام تصميم **DLS ناضج ومُرمَّز بالكامل** (ألوان/طباعة/تباعد/زوايا كرموز مركزية) + وضع داكن موازٍ.
- واجهة **View-based (Epoxy/MvRx)** مع طبقة **SDUI عبر Nezha2** — أي جزء من الـUI يُخدَم ويُضبط من الخادم.
- **1001 شاشة** موزّعة على ميزات كبيرة، بصيغة موحّدة (`trio`/`flow`) وبوابة **RTL عربي كامل**.
- هوية حركية خاصة (Lottie + صيغة LAVA) وشرائح تنقّل جديدة (Stays/Experiences/Services/Hotels).

---

## 10) المعمارية والنظام (Architecture)

### 10.1 نمط الواجهة والتنقّل
- واجهة **View-based (XML)** لا Compose (لا `@Composable` في كود التطبيق؛ حضور Compose محدود جداً وآثاره في `lib/fov` فقط).
- **Airbnb MvRx** (Managed ViewModel) داخل `com.airbnb.android.lib.mvrx` — الفئات الأساسية المُستخرَجة:
  `MvRxActivity` · `MvRxActivityForNotification` · `TransparentMvRxActivity` · `MvRxFragmentRouter` · `MvRxFragmentRouterWithoutArgs`.
- **التنقّل عبر Routers المُطَّعم بالأنواع:** `87` ملف `*Routers.java`، منها **77 تعرّف وجهات** `MvRxFragmentRouter<...>`، مع **610 صنف `*Args`** و`2 *Directory` و`8 *Fragments`.
- أنشطة قليلة: **16** نشاطاً في كود التطبيق (148 إجمالاً في المانيفست) → التطبيق تجزيئي (Fragments + Routers) حول أنشطة قليلة.
- الأنشطة الجذرية: `SplashScreenActivity` (LAUNCHER) → `HomeActivity`؛ و`DeepLinkEntryActivity` بوابة الروابط.

### 10.2 الطبقات المساندة
- **الحقن:** لا Dagger/Hilt/javax.inject → DI يدوي/مكوّنات (نمط Airbnb الداخلي).
- **الشبكة:** **OkHttp** (بلا Retrofit/Apollo صريح) — طبقة GraphQL/JSON خاصة.
- **الصور:** **Glide** + WebP + ترميز **AVIF** أصلي (`libavifandroid.so`).
- **الرسوم:** **Lottie** (`com.airbnb.lottie`) + صيغة **LAVA** الخاصة.
- **Epoxy:** المكتبة حاضرة (`com.airbnb.epoxy`) لكن استخدام كود التطبيق محدود (ملف واحد) — القوائم عبر RecyclerView مخصّص.
- **التخزين المحلي:** SharedPreferences متعدّدة (`airbnb_prefs`, `nezha_local_storage_prefs`, `user_consent_prefs`, `bot_detection_prefs`, `lava_capability_probe_prefs`, `dynamic_feature_state`) + **DataStore** (`CurrencyDataStore`, `accountmodeDataStore`) + Room (كمكتبة) + WorkManager.
- **الميزات الديناميكية:** Google Play Core (`playcore_split_install_internal`) + موديولات `distribution`/`dynamicfeature`/`dynamic`.
- **SDUI:** إطار **Nezha2** (انظر §9.5).

---

## 11) الجرد الكامل (Inventory)

### 11.1 ميزات التطبيق
- **227 حزمة `feat`** (5,282 ملف) + **104 موديول `lib`** (1,420 ملف) + `args` (193) + `base` (172) + `data` (146) + `ui` (89) + `core` (39).
- تغطي: الضيف (explore · pdp · checkout · trips · messaging · experiences · services · wishlist) والمضيف
  (hostcalendar · listingeditor · earnings · hostreservations · prohost · cohosting · hosttasksystem) والثقة
  (airlock · identity · knowyourcustomer · trust · pna · vishing · warden) والسفر/التأمين/المحفظة وغيرها.

### 11.2 الطرف الثالث (com.* — عدد ملفات)
| الحزمة | ملفات | الحزمة | ملفات |
|-------|------|-------|------|
| incognia | 1613 | facebook | 38 |
| google | 723 | linecorp | 23 |
| fingerprintjs | 321 | nhn (Naver) | 21 |
| stripe | 124 | klarna | 20 |
| razorpay | 111 | braintreepayments | 18 |
| tencent | 91 | evernote | 13 |
| worldline | 86 | iap | 12 |
| singular | 51 | kakao | 11 |
| cardinalcommerce | 8 · bugsnag 6 · alipay 5 · adyen 4 · bumptech 4 · theartofdev 4 · arkoselabs 2 · jakewharton 2 | | |

(بالإضافة إلى `defpackage` بـ**30,087** صنفاً = كود Airbnb مُشوَّه بـR8، و`androidx/kotlin/io/org/okhttp3`.)

### 11.3 عدّادات الموارد والتنقّل
- شاشات: **1006 `*Screen`** موزّعة على **155 ميزة** (أعلاها: messaging 54 · checkout 42 · hostcalendar 42 · pdp 42 · hostsettings 32 · locationverification 31 · hostingdetails 30 · payments 27).
- نصوص: **9,517 إنجليزي** + **8,625 عربي** → التطبيق مُترجَم بلغتين فقط (EN + AR) في هذه الحزمة.
- موارد: 2,487 drawable · 88 Lottie · 13 LAVA · 650 layout · 16 menu · 69 anim.
- تنقّل عميق: **320 intent-filter** · **32,586 host** · 28 نشاطاً مُصدَّراً.

---

## 12) الأمن — تصحيح وتعزيز

1. **TLS pinning غير مُفعَّل فعلياً:** مكتبة `CertificatePinner` موجودة داخل OkHttp، لكن **لا توجد أي بصمة `sha256/` في الكود** ⇒ الفرض السابق بأن pinning مُهيّأ **غير صحيح**. الحماية الفعلية تقوم على منع cleartext + Play Integrity.
2. **مكافحة البوت/الاحتيال:** `bot_detection_prefs` · **Arkose Labs** (FunCaptcha) · reCAPTCHA · **Incognia** · **FingerprintJS** · **Singular**.
3. **كشف root/تلاعب:** عبر **BugSnag NDK** (`libbugsnag-root-detection.so`) + مكتبة **`libb8c6.so`** المُشوَّهة (استيرادات `fork/execv/kill/killpg/waitpid/mprotect/prctl/dlopen/dup2`؛ لا سلسلة نصوص مفيدة — 103 فقط ⇒ مُجرَّدة بالكامل).
4. **التشويش:** كود التطبيق بالكامل في `defpackage` (R8) وبلا ملف mapping داخل الحزمة ⇒ تحليل الاستخراج معطّل.
5. نقاط ضعف قائمة: `allowBackup=true` · 28 مكوّناً مُصدَّراً (OAuth/دفع/مشاركة) · cleartext مستثنى لنطاق واحد (10010.com).

---

## 13) الخلاصة التنفيذية (end-to-end)

- **حزمة `.apks` بتنسيق ZIP مُتدفّق ملتوٍ عمداً** (بلا فهرس/EOCD) — فُكّت يدوياً وتحقّقت سلامتها (4 APKs).
- **تطبيق Airbnb 26.42 كامل السطح:** ضيف + مضيف + ثقة/هوية + دفع + سفر + دعم، بـ**1006 شاشة** و**227 ميزة**.
- **واجهة Views ناضجة** على نظام تصميم **DLS** مركزي (طباعة/ألوان/تباعد/زوايا) + وضع داكن موازٍ + **RTL عربي كامل**، مع طبقة **SDUI (Nezha2)** وتحوّل تدريجي محدود نحو Compose.
- **تنقّل typed-Routers** (610 Args/77 وجهة) وأنشطة قليلة، و**شبكة تنقّل عميق هائلة** (32,586 host).
- **ثقة/مكافحة احتيال متعددة الطبقات** (Play Integrity · Arkose · Incognia · FingerprintJS · Singular · bot detection · BugSnag ROOT) + حماية أصلية مُشوَّهة، مع **دعم تثبيت شهادات غير مُفعَّل**.
- **مدفوعات عالمية متعددة البوّابات** (Stripe · Razorpay · Klarna · Braintree · Adyen · Worldline · Alipay/WeChat/UPI · CardinalCommerce · IAP).
