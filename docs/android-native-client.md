# ضَيف — Native Android Client

## القرار والنطاق

تطبيق Android أصيل بـ **Kotlin + Jetpack Compose**. هذا القرار يحل محل اقتراح Flutter الوارد في مستندات تنفيذ الجوال غير المدمجة؛ لا يغير وحدات Spring أو مخطط قاعدة البيانات.

التقسيم:
- **Compose UI**: عرض الحالات وإرسال أفعال المستخدم فقط.
- **PlatformViewModel**: حالة الشاشة والعمليات غير المتزامنة ونتائجها.
- **PlatformRepository**: عمليات المنصة التي تستهلكها الشاشات.
- **PlatformApi**: نقل HTTP الوحيد، حقن Bearer token، مهلات الشبكة، تطبيع أخطاء API.
- **SecureSessionStore**: حالة AppAuth مشفّرة بـ AES-GCM، ومفتاحها داخل Android Keystore.

لا تضع أي `client_secret` في APK. تنفيذ OAuth يستعمل AppAuth ومتصفح النظام؛ لا WebView ولا API key.

## رحلات فعلية مربوطة بعقود الخادم الحالية

| الرحلة | ما ينفذه التطبيق | عقد الخادم |
|---|---|---|
| تسجيل الدخول | Authorization Code + PKCE، اكتشاف OIDC، تبادل الرمز، حفظ حالة مشفرة | عقد OAuth العام في `docs/api/public-client-auth.md` |
| بدء الاستخدام | قراءة ملفي الحالي وشجرة المواقع، ثم اختيار المحافظة/المدينة/الحي | `GET /api/v1/users/me`, `GET /api/v1/geo/tree` |
| عضوية الحي | الانضمام أو تبديل الحي وعرض حالة التوثيق وطلب المراجعة | `GET/PUT /api/v1/me/neighborhood`, `POST /api/v1/me/neighborhood/verification-requests` |
| منشورات الحي | خلاصة حقيقية، تصنيفات المنشورات، فتح التفاصيل، التعليقات، التفاعل، النشر | `GET/POST /api/v1/neighborhood/posts`, `GET/POST /api/v1/posts/{postId}/comments`, `POST/DELETE /api/v1/posts/{postId}/reactions` |
| صور المنشورات | اختيار صورة، طلب presigned upload، رفع bytes إلى التخزين بعميل لا يحمل Bearer token، تأكيد الرفع، ثم إعادة قراءة الخلاصة وعرض الصور | `POST /api/v1/media/uploads`, `POST /api/v1/media/{id}/complete` |
| سوق الحي والحراج | عرض إعلانات فعلية، بحث وفلاتر الفئات و«إعلاناتي فقط»، نشر غرض وسحبه | `GET/POST /api/v1/neighborhood/market`, `DELETE /api/v1/neighborhood/market/{itemId}` |
| فعاليات الحي | قراءة الفعاليات القادمة، تنظيم فعالية، تسجيل الحضور وإلغاؤه | `GET/POST /api/v1/neighborhood/events`, `POST/DELETE /api/v1/events/{eventId}/rsvp` |
| مجموعات الجيران | قراءة المجموعات والانضمام/المغادرة | `GET /api/v1/neighborhood/groups`, `POST/DELETE /api/v1/neighborhood/groups/{groupId}/membership` |
| استطلاعات الرأي | إنشاء استطلاع بخيارين إلى خمسة، التصويت مرة واحدة وسحب التصويت | `GET/POST /api/v1/neighborhood/polls`, `POST/DELETE /api/v1/polls/{pollId}/vote` |
| الإشعارات | قائمة الإشعارات، عدد غير المقروء، وتعليم الإشعار كمقروء | `GET /api/v1/notifications`, `GET /api/v1/notifications/unread-count`, `POST /api/v1/notifications/{id}/read` |
| دليل الجهات | السجل العام للمؤسسات وحالة توثيقها ومعلومات اتصالها المتاحة | `GET /api/v1/institutions`, `GET /api/v1/institutions/{id}` |
| العقار والخدمات | بحث حقيقي في الإعلانات وقراءة التفاصيل والتفاصيل العقارية إن كانت مرفقة | `GET /api/v1/search`, `GET /api/v1/listings/{id}` |

يُفرض التحقق النهائي في الخادم؛ تحقق الواجهة لا يستبدل 401/403/404/409/429 أو قواعد التوثيق والعضوية والملكية. إذا أعاد الخادم قائمة فارغة، تعرض الواجهة حالة فارغة بدل بيانات عرض ملفقة.

## حدود المنتج الحالية — لا نخفي فجوات الـ API

- **كتالوج المتجر العام ليس مكتملًا بعد**: في المستودع الحالي واجهات تسجيل/قراءة منتج المالك، وأسئلة/أجوبة المنتج وملخص البائع العام، لكنها ليست API تصفح عام لجميع المنتجات. لذلك لا تدّعي شاشة السوق أنها متجر إلكتروني كامل، ولا تعرض منتجات أو خصومات أو تقييمات أو مخزونًا افتراضيًا. السلة والطلبات تحتاجان تدفقًا موصولًا إلى بيانات المنتجات وأسعارها المعتمدة.
- **سوق الحي والحراج مختلف عن كتالوج المتجر**: هذا المسار مكتمل من جهة القراءة والنشر والسحب باستخدام `NeighborhoodMarketController`، مع احترام شرط «مجاني ⇔ بلا سعر» وقواعد العملة والحالة.
- **العقار** يستخدم محرك بحث الإعلانات العام الحالي؛ واجهة التفاصيل تظهر حقل العقار عندما يعيده العقد، ولا تضيف تفاصيل غير موجودة.
- **دليل الجهات** يعرض المؤسسات المسجلة في `/api/v1/institutions`. دليل كل الأعمال/مقدمي الخدمات مع بحث عام وتصفية يحتاج إلى عقد Browse/Search عام؛ لا نسمي سجل المؤسسات دليلًا تجاريًا كاملًا.
- لا توجد في عقود القراءة الحالية معلومات هوية عامة لكل مؤلف منشور؛ لذا يعرض التطبيق اسم الحساب الحالي لصاحب حساب المستخدم فقط، ويستخدم «عضو من الحي» للآخرين بدل استنتاج أسماء.
- واجهة الجوال لا تزال بحاجة إلى عرض صور الإعلانات العقارية، فتح روابط عميقة من الإشعارات، تحرير التفضيلات، التبليغ/السلامة، دورة حذف الحساب، واختبارات UI/end-to-end على جهاز فعلي.
- رفع الصور يعتمد على تفعيل تخزين الوسائط في بيئة الخادم، وحدّ الحجم والأنواع المسموحة في تكوين الخادم. فشل بوابة التخزين يظهر كحالة خطأ ولا يُسجّل رفعًا ناجحًا.

## OAuth والبيئة

المصدر الحاكم: `docs/api/public-client-auth.md` و`docs/security/client-hosting-strategy-plan.md`.

- العميل عام، يستخدم Authorization Code + PKCE، بلا سرّ عميل.
- عمر Access Token هو 900 ثانية؛ عقد الخادم لا يصدر Refresh Token لهذا العميل العام. عند انتهاء الرمز، على التطبيق إعادة المصادقة من خلال تدفق الخادم، لا صناعة جلسة بديلة محلية.
- يجب أن يتطابق Redirect URI حرفيًا مع أحد عناصر `OAUTH_PUBLIC_CLIENT_REDIRECT_URIS` على الخادم.
- متغيرات البناء:

| Gradle property | Environment variable | الغرض |
|---|---|---|
| `apiBaseUrl` | `DAYF_API_BASE_URL` | أصل واجهة REST |
| `authIssuer` | `AUTH_SERVER_ISSUER` | Issuer الدقيق المنشور في إعداد الهوية |
| `oauthPublicClientId` | `OAUTH_PUBLIC_CLIENT_ID` | معرّف العميل العام، وهو ليس سرًا |
| `oauthRedirectUri` | `DAYF_OAUTH_REDIRECT_URI` | التحويلة المسجلة؛ الافتراضي `com.marketplace.dayf:/oauth2redirect` |

لا تعلن أن تسجيل الدخول الإنتاجي مؤكد قبل مطابقة المُصدر ومعرّف العميل والتحويلة مع الإعداد الحي. تسجيل الخروج في هذا الإصدار يمسح بيانات الاعتماد المحلية؛ لا يزعم أنه أنهى جلسة المتصفح لدى موفر الهوية.

## البناء والتحقق

Workflow `.github/workflows/android.yml` يثبت JDK 17 وGradle 8.13 وAndroid SDK 36 ثم يشغل:

```bash
gradle --no-daemon testDebugUnitTest lintDebug assembleDebug
```

اختبار البناء من CI هو بوابة القبول لهذا الفرع. بيئة التأليف لم تحتو Android SDK/Gradle، لذلك لا أدّعي أن APK بُني محليًا أو جُرّب على جهاز. لا يحتوي الفرع بعد على Gradle Wrapper binary؛ الـ workflow يثبت نسخة Gradle المطلوبة صراحةً بدل الاعتماد على Gradle عشوائي على العامل.

المراجع الرسمية:
- Android app architecture: https://developer.android.com/topic/architecture
- AppAuth-Android: https://github.com/openid/AppAuth-Android
- Android Gradle Plugin 8.13 compatibility: https://developer.android.com/build/releases/agp-8-13-0-release-notes
- Android Compose image loading (Coil): https://github.com/coil-kt/coil
