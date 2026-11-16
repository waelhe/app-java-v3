# تقرير تحليل DEX + فحص سلسلة التوريد — Nextdoor 5.34.1 (1254037)

**الملف:** `base.apk` · SHA-256 `7cb7ae84815cf399c0070354aaafb97f02429eb2785f93273b40e1c888adeb12` · 121,288,070 بايت
**المصدر:** `com.nextdoor_5.34.1-1254037_..._apkmirror.com.apkm` (82,627,875 بايت · SHA-256 `300402c56784d8943c5a126be788ca46fa86c319cbdfee7a459aa63c9db64e5f`)
**تاريخ التحليل:** 2026-10-16

---

## 1) المنهجية

- استُخرجت ملفات DEX العشرة (9 في الجذر + `assets/audience_network/classes.dex`) وقرئت رؤوسها
  (`string_ids` / `type_ids` / `class_defs`) بسكربت Python مخصّص (`dex_scan.py`).
- تصنيف الأصناف بحسب اسم الحزمة الكامل لكل `class_def`.
- **قيد موثق:** REA لا يدعم DEX مباشرة في هذه الجلسة (انظر §5).

---

## 2) الأرقام الكلية

| المقياس | القيمة |
|---|---|
| إجمالي الأصناف (classes) | **102,125** |
| أصناف `com/nextdoor` | **29,107** (28.5%) |
| ملفات DEX | 10 |
| أكبر DEX | classes6.dex (14,674 صنفاً / 16.7MB) |

### التوزيع لكل DEX

| DEX | strings | types | classes | com/nextdoor |
|---|---:|---:|---:|---:|
| classes.dex | 57,958 | 19,868 | 9,165 | 45 |
| classes2.dex | 51,324 | 22,372 | **12,443** | **10,664** |
| classes3.dex | 45,854 | 20,766 | 11,734 | **10,300** |
| classes4.dex | 56,943 | 21,444 | 8,456 | 143 |
| classes5.dex | 51,377 | 19,156 | 10,123 | 57 |
| classes6.dex | 41,376 | 19,003 | **14,674** | 223 |
| classes7.dex | 37,383 | 21,226 | 10,362 | 6,481 |
| classes8.dex | 56,154 | 26,155 | 12,943 | 1,188 |
| classes9.dex | 45,452 | 15,397 | 8,620 | 6 |
| assets/audience_network/classes.dex | 32,685 | 5,040 | 3,605 | 0 |
| **الإجمالي** | — | — | **102,125** | **29,107** |

توزيع كود Nextdoor: **الجزء الأكبر في classes2 + classes3 (~72%)**، بينما classes.dex/4/5/9 شبه خالية منه
(بنية multidex مع تجميع مميّز).

---

## 3) بنية `com.nextdoor` (أعلى 40 حزمة فرعية)

| الحزمة | الأصناف | |
|---|---:|---|
| `events` | **26,750** | **91.9% من كود Nextdoor** — أصناف أحداث مولّدة (Apollo GraphQL) |
| `apollo` | 346 | طبقة GraphQL |
| `entitypages` | 138 | |
| `newsfeed` | 83 | |
| `blocks` | 68 | |
| `composition` | 68 | |
| `groups` | 66 | |
| `network` | 52 | |
| `classifieds` | 49 | |
| `feedmodel` | 47 | |
| `model` · `profile` | 46 · 46 | |
| `settings` | 45 | |
| `thirdparty` | 41 | |
| `leadsnetworking` | 40 | |
| `popupprompt` | 39 | |
| `rtm` · `libraries` · `registration` | 34 · 33 · 33 | |
| `localevents` · `blocksdemo` | 32 · 32 | |
| `registrationUi` | 31 | |
| `verification` | 30 | |
| `localalerts` | 29 | |
| `entitypagesmodels` | 28 | |
| `ads` · `neighborhoodfaves` | 27 · 27 | |
| `navigation` · `virality` | 26 · 26 | |
| `search` · `leads` | 25 · 25 | |
| `notificationcenterui` | 24 | |
| `nuxReskin` | 23 | |
| `favesdirectory` · `moderation` · `sharing` | 21 ×3 | |
| `developersettings` | 19 | |
| `neighborhoodintelligence` · `pushNotification` | 18 · 18 | |
| `discovermodels` | 17 | |

**الدلالة:** الكود الفعلي المكتوب يدوياً لـNextdoor يقارب ~2,400 صنفاً فقط؛
الـ27 ألف الباقية طبقة أحداث مولّدة آلياً — طبيعي لمعمارية GraphQL/Apollo حديثة.

---

## 4) الحزم الجذرية (كل الـDEX) — سلسلة التوريد الفعلية

| الحزمة | الأصناف | الدلالة |
|---|---:|---|
| `com/nextdoor` | 29,107 | كود التطبيق |
| `com/facebook` | 3,909 | Facebook SDK + Audience Network (إعلانات/تتبع) |
| `io/didomi` | 1,916 | منصة إدارة الموافقة (CMP) |
| `com/mapbox` | 1,770 | خرائط/موقع |
| `com/incognia` | 1,759 | بصمة جهاز/مكافحة احتيال (حساس) |
| `kotlin/reflect` | 1,736 | Kotlin |
| `com/withpersona` | 1,617 | التحقق من الهوية KYC |
| `io/getstream` | 777 | دردشة/Activity Feeds |
| `app/rive` | 712 | رسوم متحركة |
| `io/sentry` | 703 | مراقبة أخطاء |
| `com/google` | 437 | GMS/FCM/AdMob جزئي |
| `com/qualtrics` | 219 | استبيانات |
| `org/chromium` | 118 | WebView/AppAuth |
| `androidx/*` | متفرق | Camera 99 · Compose 46 · Fragment 39 · AppCompat 21 · Preference 15 · RecyclerView 13 · WorkManager 13 · Core 10 · Window 9 · Media/Media3 13 · Coil3 6 |
| `android/support` | 46 | حزم دعم قديمة (compat shims) |

---

## 5) قيد REA الموثق (تحليل DEX الأصلي عبر REA)

| المحاولة | النتيجة |
|---|---|
| `rea_inspect_android_package(base.apk)` | `capability_unavailable` — يتطلب `REA_JADX_MCP_JAR` = مسار `jadx-headless-mcp 0.7.1` |
| `rea_inspect_apktool_client` | `capability_unavailable` — يتطلب `REA_APKTOOL_COMMAND` |
| `rea_open_binary(classes2.dex)` | `target_unavailable` — "unsupported binary format" (DEX غير مدعوم) |
| `where.exe jadx` / `where.exe apktool` + مسارات شائعة | **غير موجودين** على الجهاز |

**التفعيل (يتطلب إعادة تشغيل opencode بعد ضبط البيئة):**
1. الحصول على jadx (متاح: `io.github.skylot:jadx-cli` **1.5.6** على Maven Central — آخر تحديث 2026-07-10).
2. الحصول على `jadx-headless-mcp 0.7.1.jar` من مشروعه الأصلي.
3. ضبط `REA_JADX_MCP_JAR` ضمن `env` لقسم `mcp.rea` في `opencode.jsonc` ثم إعادة التشغيل.
4. بديل: ضبط `REA_APKTOOL_COMMAND` لتشغيل مزوّد apktool.

---

## 6) فحص سلسلة التوريد (SBOM)

### 6.1 مكتبات موثّقة (ملفات `META-INF/**/*.version` — 148 ملفاً)

**عيّنة موثّقة:** activity 1.13.0 · appcompat 1.8.0 · compose.animation/foundation 1.12.1 ·
camera 1.6.2 (core/lifecycle/video/view/camera2-pipe) · browser 1.10.0 · biometric 1.1.0 ·
cardview 1.0.0 · autofill 1.1.0 · asynclayoutinflater 1.0.0 · annotation-experimental 1.5.1 ·
وكذلك (من جولة BoM): core 1.19.1 · material3 1.4.0 · lifecycle 2.11.0 · navigation 2.10.2 ·
room 2.8.5 · work 2.12.0 · webkit 1.17.1 · credentials 1.6.0 · datastore 1.2.1 ·
exifinterface 1.4.2 · recyclerview 1.4.0 · preference 1.2.1 · security-crypto 1.1.0 ·
tracing 2.0.3 · emoji2 1.7.0 · profileinstaller 1.4.1 · privacysandbox.ads 1.1.0-beta11.

### 6.2 فحص الحداثة (تحقق خارجي فعلي من Maven Central)

| المكتبة | إصدار التطبيق | الأحدث | الحالة |
|---|---|---|---|
| `org.jetbrains.kotlinx:kotlinx-coroutines-core` | **1.11.0** | 1.11.0 (2026-05-08) | ✅ محدَّث |
| `com.google.dagger:dagger` | **2.59.2** | 2.60.1 (2026-07-06) | ⚠️ متخلف إصداراً واحداً |

> ملاحظة: `dl.google.com/android/maven2` أعاد 404 لملفات metadata ⇒ تعذّر التحقق الخارجي من حداثة مكتبات AndroidX/Google. لم يجرِ ادعاء أي ضعف غير متحقق منه.

### 6.3 ملاحظات بنيوية

- **`privacysandbox.ads 1.1.0-beta11`** — تبعية **بيتا** مشحونة في الإنتاج (مقصودة لبيئة Privacy Sandbox).
- **`androidx.arch.core:core-runtime`** — ملف النسخة يحتوي نص مهمة Gradle بدل رقم (خلل توليد، غير مؤثر).
- `THIRD-PARTY.txt` = 34 بايت ("The project has no dependencies") — ملف مولَّد بلا قيمة، لا يُعتمد عليه كإفصاح تراخيص.
- **git revision البناء:** `69ac85fe27c83a85a0a2d9fb4597472892490c3b` (من `version-control-info`).
- توكنات زمنية لـSDKs طرف ثالث (verification.properties): stream 2024-07 · sentry-replay 2024-08 · persona 2024-09 · metro/landscapist 2024 — تدل على نسخ SDK ليست من يوم البناء الحالي.

---

## 7) الصلابة الأمنية (نتائج مقيسة من المانيفست + res/xml)

### 7.1 إعدادات التطبيق ✅

| الفحص | النتيجة |
|---|---|
| `allowBackup` | **false** ✅ |
| `debuggable` | غائب ✅ |
| `usesCleartextTraffic` | غائب؛ السياسة في `network_security_config` ✅ |
| `network_security_config` (res/xml) | `<debug-overrides>` ثقة بشهادات المستخدم (غير مفعّل — التطبيق ليس debuggable) · **cleartext مسموح فقط للنطاق `10.0.2.2`** (حلقة محاكي) في 4 كتل `domain-config` مكررة ✅ |
| `profileable shell=true` | مُعلن ⚠️ (يسمح بقياس أداء عبر adb على البناء الإنتاجي) |
| `extractNativeLibs` | false ✅ |
| minSdk / targetSdk | 29 / **36** ✅ |

### 7.2 موردو الملفات (FileProvider) ✅

- الرئيسي (`provider_paths.xml`): `external_files` · `files` · `cache` — exported=false ✅ (النطاق واسع نسبياً لكنه غير مُصدَّر).
- `stream_filepaths.xml`: cache · Pictures · Movies — مقيّد.
- `pi2_file_paths.xml` (Persona): **مقصورة على `.com.withpersona.sdk2.inquiry`** ✅ — تصميم جيد.

### 7.3 التوقيع ⚠️

- **v2 فقط** (`0x7109871a`) · **v1 غائب** (مقبول لـminSdk 29) · **لا v3/v3.1** ⇒ لا دعم تدوير مفاتيح التوقيع.
- الشهادة: `CN=Android, OU=Android, O=Google Inc., L=Mountain View...` · SHA-256 `6b0039757b3666404626f2d606ee17b56ad47770f5aebad2d7b7326882190dbd` · صالحة 2020→2050.

### 7.4 سطح التصدير (20 مكوّناً `exported=true`)

| النوع | المكوّن | الحالة |
|---|---|---|
| activity-alias | `registrationUi.nux.LoadAppConfigurationActivity` | — |
| service | `pushNotification.NextdoorFirebaseMessagingService` | مكشوف |
| receiver ×3 | `NotificationSettingsListener` · `NotificationIndividualChannelSettingListener` · `NotificationGroupChannelSettingListener` | مكشوفة |
| activity ×5 | `trackingdemo.*` (TrackingDemo / FragmentFlow / ComposeFlow / DraggableViewDemo / ListDemo) | ⚠️ **أدوات تطوير داخلية مشحونة ومُصدَّرة** |
| activity | `activity.DeepLinkActivity` | مقصود (Deep links) |
| activity | `composition.activity.PostComposerActivity` | مكشوف |
| receiver | `engage.EngageBroadcastReceiver` | مكشوف |
| service | `SystemJobService` | محمي `BIND_JOB_SERVICE` ✅ |
| receiver | `DiagnosticsReceiver` | محمي `DUMP` ✅ |
| receiver | `ProfileInstallReceiver` | محمي `DUMP` ✅ |
| receiver | `FirebaseInstanceIdReceiver` | محمي `SEND` ✅ |
| service | `RevocationBoundService` | محمي `REVOCATION_NOTIFICATION` ✅ |
| activity | `facebook.CustomTabActivity` | مكشوف (Facebook SDK) |
| activity | `compose.ui.tooling.PreviewActivity` | ⚠️ **أداة تطوير Compose في الإنتاج** |

- إجمالي: 20 exported=true · 76 exported=false · 11 مكوّناً بـ`android:permission` · 158 `<intent-filter>`.
- **توصية Nextdoor:** إزالة/تقليص `trackingdemo.*` و`PreviewActivity` من البناء الإنتاجي (تُوسّع سطح الهجوم وتعرض واجهات داخلية).

---

## 8) سطح الخصوصية والبيانات (تقييم تركيبي)

- **Didomi** (موافقة إعلانات) + **Facebook/Audience Network** + **AdMob** ⇒ منظومة إعلان/تتبع كاملة.
- **Incognia** (بصمة جهاز/موقع) + **Persona** (KYC) ⇒ معالجة بيانات تعريف حساسة.
- **Qualtrics** (استبيانات) · **Sentry** (أخطاء) · **Datadog RUM** (مذكور سابقاً) · **Mapbox** (موقع) · **Stream** (دردشة) · **Branch** (إحالة).
- الصلاحيات: 33 صلاحية بينها `SYSTEM_ALERT_WINDOW` (يُذكر كمخاطرة تصدير/نوافذ) — تُراجع مقابل إفصاح Data Safety في Play.

---

## 9) القيود الصريحة لهذا التقرير

1. **لا تحقق حيّ من CVE:** أداة البحث الشبكي محجوبة (403) ⇒ قسم الثغرات اقتصر على: إصدارات دقيقة + فحص تحديث فعلي + تقييم تهيئة. **لم يُدَّع أي CVE غير متحقق منه.**
2. لا يمكن فحص **كود** AndroidX/الأطراف الثالثة بحثاً عن أنماط خطرة حتى يُفعَّل jadx (§5).
3. تقييم Privacy سطح‑محمول (مكوّنات/SDK) لا سلوك زمن تشغيل.

---

## 10) التوصيات

1. **إزالة مكوّنات التطوير المصدَّرة** (`trackingdemo.*`, `PreviewActivity`) من إصدارات الإنتاج — أخطر بند في هذا الفحص.
2. ترقية **Dagger → 2.60.1** عند الدورة القادمة.
3. اعتماد التوقيع v3.1 لدعم تدوير المفاتيح.
4. لاستكمال التحليل العميق: تفعيل `REA_JADX_MCP_JAR` (jadx-headless-mcp 0.7.1 + jadx-cli 1.5.6) ثم إعادة تشغيل opencode.
5. عند توفر بحث CVE: مسح KY للمكتبات المثبتة (148 ملف إصدار جاهز في `META-INF`).

---

## ملحق: مخرجات خام

- جدول per-dex الكامل: `C:\Users\w-co\AppData\Local\Temp\opencode\dex_out.txt`
- سكربت الفحص: `C:\Users\w-co\AppData\Local\Temp\opencode\dex_scan.py`
- محلّل AXML المحدّث (يدعم UTF-8): `C:\Users\w-co\AppData\Local\Temp\opencode\axml4.js`
- مجلد res/xml المستخرج: `C:\Users\w-co\AppData\Local\Temp\opencode\nextdoor-apkm\resxml\`
