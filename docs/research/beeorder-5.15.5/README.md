# BeeOrder 5.15.5 — تحليل عكسي ساكن end-to-end (UI/UX + الطبقة الأصلية)

بحث توثيقي ساكن لحزمة **BeeOrder** (`com.beeorder.customer`، versionName `5.15.5`، versionCode `339`، Flutter/Dart AOT).
بلا تنفيذ للتطبيق وبلا اتصال شبكي: كل الاستنتاجات من قراءة الحزمة ساكنًا.

- **مرجع digest الحزمة (XAPK):** `8E615DF0FD5E6611698CA42E352FE83A4C402CD347965DBDB75A9222022C48D4`
- **مرجع digest القاعدة (base APK):** `1043D571…` (1176 مدخلة)
- **حزمة Dart:** `beeorder_mobile` (معمارية `features/`)
- **بصمة snapshot:** `ace654289f5abc240509fc941453ebc5` (Dart 2.14 FullAOT، arm32) · `libflutter.so` build-id `e1b53047497372d9a451e1c4e73393afb2ba95c4`

## النطاق المُقاس
- **الهوية والسطح:** package/label/version، minSdk 24 · target/compileSdk 36، 33 صلاحية، موقّع BNDLTOOL، `usesCleartextTraffic=false`.
- **المكوّنات:** MainActivity + 3 activity-alias (تبديل الأيقونة: default/beepro/ramadan) + 3 BroadcastReceivers + provider ملفات.
- **الطبقة الأصلية:** 3 قنوات MethodChannel مخصّصة، وحالة Live Updates (`order_live_updates`)، وجرد قنوات الإضافات (app_links, record, mobile_scanner, community.plus…).
- **UI/UX:** 6 تبويبات، 23 شاشة/ميزة، 6 تدفقات E2E مُستخرجة من أحداث التحليلات، طبقة تصميم، i18n (1710 مفتاحًا عربي/إنجليزي)، 420 أصلاً (385 صورة + 27 Lottie + 8 خطوط).
- **الرموز:** 47,131 مُعرِّف Dart مُستخرج من `libapp.so` (تجاوز سقف الأداة المفتوح).

## المحتوى
| الملف | الوصف |
|---|---|
| `report-ar.md` | التقرير الكامل (12 قسمًا، عربي) — الهوية، الشاشات، الطبقة الأصلية، التخزين/السوكِت، i18n، الأصول، الـdeep links. |
| `ui-map.html` | **خريطة UI/UX تفاعلية** (6 تبويبات؛ بيانات مضمّنة، لا ثنائيات). |
| `asset_manifest.json` | جرد الأصول (rel/bytes/sha256/kind). |
| `jadx_summary.json` | خريطة القنوات/المستقبِلات/الحزم/حالة الإشعارات الحيّة. |
| `dart_app_classes.txt` · `dart_constants.txt` | أصناف التطبيق وثوابته (من رموز Dart). |
| `i18n_keys.txt` · `assets_inventory.txt` | مفاتيح الترجمة (en) وجرد `flutter_assets`. |
| سكربتات | `extract_dart_tokens.py`, `analyze_tokens.py`, `extract_assets.py`, `build_reconstruction.py`, `scan_jadx.py`. |

## ملاحظات
- تغيير **توثيقي بحت**: لا أثر على البناء أو وقت التشغيل (0 موديول Maven، 0 ملف Java/SQL).
- **لا تُضمَّن الملفات الثنائية** عمدًا (`*.apk`/`*.xapk`، شجرة `jadx`، شجرة `apktool`، صور/خطوط التطبيق الـ420) التزامًا بسابقة `docs/research/airbnb-26.42`. لذلك معاينات الأصول في `ui-map.html` تظهر كعناصر نائبة عند فتح الملف محليًا.
- `ui-map.html` ملف واحد مكتفٍ بذاته (بيانات مضمّنة)، ويُفتح مباشرة في المتصفح.
