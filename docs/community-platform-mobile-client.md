# خطة عميل الجوال — المنصة المجتمعية المحلية

**الإصدار:** 1.0 — 2026-10-10  
**الغرض:** تعريف عميل الجوال كاملًا من قرار المنصة إلى موجات التنفيذ: معمارية الطبقات، توليد طبقة الـAPI من عقد OpenAPI، تطبيق نظام التصميم (UX doc)، الإشعارات، الإصدار والتحديث، الاختبار وCI — جاهزة للمطورين ووكلاء الذكاء الاصطناعي.  
**واقع البداية (مقيس 2026-10-10):** **لا عميل موجود اليوم** — المستودع باك-إند Java خالص (26 وحدة) بعقد OpenAPI حي (107 مسارات مقيسة من الإنتاج) ومصادقة OIDC/PKCE عبر `marketplace-edge`. العميل greenfield يستهلك العقد القائم — لا يُنزع منه شيء.  
**علاقة السلطة:** [تصميم UX](community-platform-ux-design.md) يملك الشكل والحالات (CMP-01..41)؛ [مواصفة الرحلات](community-platform-user-journeys.md) تملك السلوك ومعايير القبول؛ [خطة الباك-إند](community-platform-backend-execution.md) تملك العقود والموجات C-x — عميل الجوال يواكبها بموجات M-x. المرجع التقني الحاكم يبقى [خطة المطابقة الرسمية](official-compliance-plan.md).  
**حالة الوثيقة:** خطة مقترحة لاعتماد المالك؛ قرار المنصة (D-17) بابها الأول.

---

## 1. قرار منصة العميل (D-17) — التحليل والقرار المطلوب

الفريق = مالك واحد + وكلاء ذكاء اصطناعي يكتبون معظم الكود — المعايير الحاكمة: نضج RTL/العربية رسميًا، توليد العميل من OpenAPI، قابلية الاختبار الآلي الكامل في CI، push/WebSocket، كلفة صيانة واحدة طويلة الأمد.

| المعيار | Native (Kotlin + SwiftUI) | KMP (Kotlin Multiplatform) | **Flutter (التوصية)** | React Native |
|---|---|---|---|---|
| RTL عربي أول-class | ممتاز لكل منصة على حدة | ممتاز (Compose RTL رسمي) | **ممتاز** — `Directionality`/`TextDirection` ركيزة رسمية (docs.flutter.dev/ui/layout/text-direction) | جيد (reactnative.dev/docs/internationalization) — تاريخ إصلاحات موضعية |
| شيفرة واحدة لفريق واحد | **لا** (شجرتان + منظومتان) | نعم (منطق مشترك + UI مرتين إن لم يُستخدم Compose MP) | **نعم** (شجرة واحدة) | نعم |
| توليد عميل من OpenAPI | قوي لكل منصة | قوي (Ktor/Ktorfit) | **قوي** (dart-dio عبر openapi-generator.tech) | جيد |
| اختبار آلي في CI | منفصلان | مشترك جزئي | **widget/golden tests موحدة** (docs.flutter.dev/testing/overview) | مقبول |
| كلفة الوكلاء (توليد+مراجعة) | مضاعفة | متوسطة | **الأدنى** — مجتمع ومواد رسمية موسعة | متوسطة |
| push/WebSocket | أصيل | أصيل | أصيل عبر الحزم الرسمية/المعتمدة | أصيل |

- **التوصية: Flutter** — بمعيار واحد حاسم لهذا المشروع: RTL عربي أول-class بشجرة واحدة واختبارات golden موحدة لكل مكون RTL+LTR، وهو ما تجعله قواعد UX doc (§3/§6) شرط قبول لكل شاشة.
- **الوصيف الثاني: KMP** — منطقي لو اجتُمع القرار مع فريق JVM قوي؛ لكنه يظل يفرض طبقتي UI أو التزام Compose Multiplatform الأحدّ نضجًا على iOS.
- **قاعدة الانضباط:** هذا قرار مالك (D-17) — التحليل أعلاه يُقرّ ويُسجل، ولا يبدأ M-1 قبله. أي إعادة فتح لاحقة تحتاج ADR يثبت فشلًا مقيسًا لا ذوقًا.

## 2. معمارية العميل (Flutter — إن اعتُمد D-17)

```text
client/                          (مستودع نفسه، دليل جديد بجانب marketplace-*)
├── design/tokens.json           (المصدر من UX doc — يولّد ThemeData)
├── lib/
│   ├── core/
│   │   ├── api/                 (مولّد آليًا من openapi-generator — dart-dio؛ لا تعديل يدوي)
│   │   ├── auth/                (PKCE/OIDC ضد edge — تخزين آمن للمنصة)
│   │   ├── rtl/                 (Directionality الجذري + CMP-29 DirectionSafeText)
│   │   ├── state/               (إدارة الحالة — قرار D-28: توصية Riverpod)
│   │   └── errors/              (RFC 7807 → ربط CMP-15..24)
│   ├── features/<journey>/      (feature-first: onboarding/, feed/, search/, business/,
│   │                            market/, official/, conversations/, verification/,
│   │                            moderation/, settings/, data-rights/, knowledge/)
│   └── shared/widgets/          (CMP-01..41 — كل مكون widget + golden test)
├── integration_test/            (رحلات JT حرجة ضد خادم CI)
└── .github/workflows/client.yml (analyze + test + golden + build — نفس بوابات الدمج)
```

قواعد معمارية ملزمة:

1. **طبقة API مولّدة حصرًا:** `openapi-generator` ‏(dart-dio) من عقد الباك-إند يُشغَّل في CI ويُتحقق عدم التعديل اليدوي — العقد مصدر واحد (openapi-generator.tech/docs/generators/dart-dio).
2. **Feature-first لا طبقي:** كل مجزوء رحلة يغلق على نفسه (عرض+منطق+اختبارات) — يوازي ملكية المجالات في الباك-إند ويمنع التسرب المتقاطع.
3. **إدارة الحالة قرار D-28** (توصية Riverpod — قابلية اختبار بلا سياق كامل؛ البديل Bloc) — يثبت مرة ويوحد عبر كل features.
4. **Offline-first انتقائي:** يُخزن محليًا: جلسة+ملف المستخدم، صندوق الإشعارات، مسودات **غير حساسة فقط** (قاعدة رحلات §22.4)، آخر قائمة «الأحدث» للقراءة أثناء انقطاع مع CMP-18. لا يُخزن: محتوى خاص لغير المصرح، مستندات تحقق، كلمات مرور/رموز خارج المخزن الآمن.
5. **لا منطق أعمال في العميل:** الأهلية والصلاحية والحالة تُقرأ من الخادم؛ العميل يعرض ويفسر CMP-19..24 — قاعدة «إخفاء الزر ليس أمنًا» (رحلات §20).

## 3. العربية وRTL في التنفيذ

- `Directionality.rtl` في جذر التطبيق؛ LTR وضع معكوس كامل للاختبار (golden test لكل مكون بالاتجاهين — UX doc §12.3).
- **CMP-29** `DirectionSafeText`: عزل bidi للنص المختلط (LRI/RLI/FSI) في كل عرض نصي من مصدر خارجي — منع تكسر أسماء الأعمال اللاتينية والأرقام في جمل عربية.
- الخط: D-22 (توصية IBM Plex Sans Arabic) يُثبت في `ThemeData` من tokens.json — مع بدائل سقوط عربية محددة.
- الأرقام الغربية (D-24) في كل البيانات؛ التقويم ميلادي + خيار هجري عبر `intl` ‏(pub.dev/packages/intl — حزمة Dart الرسمية) بصيغة ar-SA-u-ca-islamic.
- الإدخال: `TextDirection` يتبع أول حرف مع محاذاة RTL — سلوك موحد واحد عبر كل الحقول (UX doc §6.2.3).

## 4. مكتبة الويدجات = ترجمة CMP

كل مكون في UX doc §3 يقابله widget في `shared/widgets/` بنفس المعرف (CMP-05 → `PostCard`) مع:

1. **Golden test إلزامي:** لقطة RTL + لقطة LTR + حالاته (مثال OfficialCard: ACTIVE/CORRECTED/WITHDRAWN/EXPIRED) — docs.flutter.dev/testing/overview.
2. **Semantics عربية:** اسم/دور/قيمة لقارئ الشاشة يقرأ الحالة («منتهٍ»، «بحاجة تحقق») — WCAG 4.1.2 عبر semantics المدمجة (docs.flutter.dev/development/accessibility).
3. **هدف لمس ≥ 44×44** وتباين من tokens — يتحقق آليًا في CI (اختبار يجتاز الشجرة ويقيس الأهداف).
4. **لا قيمة بصرية خارج tokens.json** — قاعدة UX doc §2 تطبق بفحص CI على الكود (منع قيم hex/px صلبة).

## 5. الإشعارات والاتصال الحي

- **Push عبر منظومة الخادم القائمة فقط — لا قناة ثانية** (رحلات JT-10 §12.1.8): FCM (firebase.google.com/docs/cloud-messaging) وAPNs (developer.apple.com/documentation/usernotifications) بعد قرار المزود D-10؛ الرموز تسجل في `notifications` القائم.
- **WebSocket للمحادثات/الإشعارات الحية** وفق عقد القنوات (رحلات §13.3) — قطع الاتصال حالة معلنة لا صمت.
- شاشة القفل: مقتضب محايد وفق سياسة كتالوج رحلات §21؛ quiet hours وdigest خلفية خادمية (D-26).

## 6. الإصدار والتحديث (يحكمه JT-14)

- **Semantic versioning** للعميل + بيانات إصدار من `console` القائم (بعد موجته C-9): الحد الأدنى للتوافق + رابط المتجر + حالة الإهمال — **لا إجبار تحديث بقيمة ثابتة غير متصلة بإصدار منشور فعلًا** (رحلات JT-14 §16.1.8).
- فصل دلالي صريح: تحذير تحديث ↔ إيقاف تدريجي ↔ حاجز إجباري — كل حالة بأثر جانبي معلن ومسار تراجع (AC-14-06).
- حسابات المتاجر للنشر: قرار مالك (كيان قانوني/مطور) — لا يفترض.

## 7. استراتيجية الاختبار

| الطبقة | ما تثبته | أداة |
|---|---|---|
| Unit | منطق features + تحويلات الأخطاء RFC 7807 | flutter_test |
| Widget/Golden | كل CMP بحالاته واتجاهيه | flutter_test (matchesGoldenFile) |
| Integration | رحلات JT الحرجة ضد خادم CI (Postgres حقيقي عبر Testcontainers — نفس قاعدة الباك-إند) | integration_test |
| العقد | العميل المولّد يطابق العقد المنشور | openapi-generator diff في CI |
| الوصولية | Semantics + أهداف اللمس + التباين | فحوص CI + تقييم يدوي موثق (UX doc §7) |

مصفوفة الأجهزة: 320px (iOS SE/Android صغير) · 375 · 412 · تابلت 768 — إلزام على أول اثنين (AC-01-06).

## 8. CI وحدود الدمج

- workflow `client.yml`: `flutter analyze` + `flutter test --update-goldens=false` + فحص tokens + `flutter build` ‏(apk/ipa) — بذات قاعدة المستودع: **لا دمج إلا باخضرار كل الفحوص + CodeRabbit + كلمة المالك** (والمستودع <10 نجوم ⇒ تفعيل مراجعة يدوي بتعليق — مسجل تشغيليًا).
- العميل الجديد لا يمس بوابات الباك-إند القائمة — موجات M مستقلة الفروع، متزامنة العقود.

## 9. موجات العميل (M-x) — مرتبطة بموجات الباك-إند

| الموجة | بعد جاهزية | النطاق | رحلاتها |
|---|---|---|---|
| **M-0** | — | هيكل المستودع + tokens→ThemeData + CI + CMP-01/02/15/16/17 | بنية |
| **M-1** | C-1 | تسجيل/دخول PKCE + اختيار النطاق + التهيئة + DataRightsPanel | JT-01 (+CMP-38) |
| **M-2** | C-2/C-3 | الرئيسية (الأحدث/المتابعة/الشائع) + إنشاء منشور بغرضه + CMP-05/33 | JT-02, JT-03 |
| **M-3** | C-5 | البحث الموحد + العربية الشفافة + Ask بمصادره | JT-04 |
| **M-4** | C-2 | تعرّف على الحي + اقتراح التصحيح + الحداثة | JT-16 |
| **M-5** | C-6 | السوق + ملف العمل + ربط التوصية بالسجل الأصلي + التواصل | JT-05, JT-06, JT-07, JT-17 |
| **M-6** | C-4/C-7 | الرسائل الرسمية + مركز الإشعارات + المحادثات + البلاغات | JT-10, JT-11, JT-13 |
| **M-7** | C-6 | العموديات (عقار/مركبة/وظيفة/خدمات) + التوثيق | JT-08, JT-12 |
| **M-8** | C-9/C-11 | الإعدادات والإصدارات + دخان الإنتاج طرف-لطرف | JT-14 + إقفال |

كل موجة M تُغلق بتقرير قبول رحلة (رحلات §28.1): معرفات الرحلات/المعايير، الحقائق المقيسة، نتائج golden و320px وRTL، وما لم يُنفذ صراحةً.

## 10. القرارات والمراجع

**قرارات تنتظر المالك:** D-17 منصة العميل (توصية Flutter) · D-28 إدارة الحالة (توصية Riverpod) · D-10 مزود push · حسابات المتاجر (نشر) — وتسري كل قرارات UX doc §13.

**المراجع الرسمية:**
- Flutter — اتجاه النص/RTL: https://docs.flutter.dev/ui/layout/text-direction · الاختبارات: https://docs.flutter.dev/testing/overview · الوصولية: https://docs.flutter.dev/development/accessibility
- Kotlin Multiplatform (الوصيف الثاني): https://www.jetbrains.com/help/kotlin-multiplatform-dev/
- React Native — الدولية/RTL (للمقارنة): https://reactnative.dev/docs/internationalization
- openapi-generator (dart-dio): https://openapi-generator.tech/docs/generators/dart-dio
- intl (Dart الرسمية): https://pub.dev/packages/intl
- FCM: https://firebase.google.com/docs/cloud-messaging · APNs: https://developer.apple.com/documentation/usernotifications

**قاعدة الإنهاء:** لا تُعلن شاشة عميل جاهزة قبل ذهب حالاتها ومقاييس قبولها؛ ورحلة API وحدها ليست E2E (رحلات §0.1 — الإنجاز نتيجة يراها المستخدم).
