# مصفوفة رحلات Android — إعادة بناء W12 من الشجرة المقيسة

**المستودع:** waelhe/app-java-v3
**تاريخ إعادة البناء:** 18 أكتوبر 2026
**أساس القياس:** main عند [`4f136336`](https://github.com/waelhe/app-java-v3/commit/4f136336535a02f5c1e0525ada1ac6453ce46d28) (فرع `docs/w12-android-journey-matrix`) — بعد دمج #644 (جسر التوصية J09) و#646 (دورة المطالبة J14/V233).
**التفويض:** الخطة الموحدة §4-W12 ([`end-to-end-final-execution-plan.md`](../governance/plans/end-to-end-final-execution-plan.md)) — «إعادة بناء مصفوفة Android مقابل العقود الحالية… لا يُعاد وصف PR مدموج قديم بأنه سدّ كل الفجوات».
**حالة الوثيقة:** هذه إعادة البناء الكاملة لجرد 16 أكتوبر 2026 الذي كان يقيس شجرة ما قبل Apollo وقناة العقود. كل ادعاء أدناه مقيس من ملفات الشجرة عند الرأس أعلاه ويحمل مساره. الوثيقة لا تبني شيئًا: عمود ساق Android في [السجل الحي](../governance/plans/end-to-end-journeys-register.md) يبقى `GAP_CONFIRMED` كما هو.

---

## 0. الخلاصة التنفيذية (كل رقم مقيس)

1. **النواة المجتمعية مبنية في الشجرة؛ ولا رحلة واحدة مثبتة على الجهاز.** سبع رحلات (J01–J07) لها أسطح Compose وعمليات REST كاملة في الشجرة (`MarketplaceApi.kt` يحمل 44 عملية)، لكن الدليل الحي الوحيد هو اختبار entry واحد (`AuthEntryPointE2ETest.kt`) يثبت شاشة الدخول غير الموثّقة تعرض خياري الدخول — لا غير.
2. **استهلاك GraphQL ما زال صفريًا وقت التشغيل.** ثلاث وثائق عمليات Apollo مؤلفة ومنشورة في الشجرة (`HomeFeed.graphql`، `ListServices.graphql`، `GetService.graphql`) وتُدقّق عبر بوابة CI إلزامية، لكن الاعتمادية هي `apollo-api` فقط دون `apollo-runtime` (`android/app/build.gradle.kts:143-146`)، ولا يوجد `ApolloClient` في أي ملف Kotlin (قياس rg). القراءة الرئيسية التي يصفها `HomeFeed` تُنفَّذ فعليًا عبر طلبَي REST (`CommunityViewModel.kt` ‏`loadDiscoveryHome` + `loadFeed`).
3. **العقد المنشور تقدّم على التطبيق.** SDL المنشور في قناة `graphql-contract` هو **v3** (من `9ba0fd7d`) ويحمل ستة حقول Query بينها مركّبة تفاصيل المنشور `post`/`postComments` (المرحلة 7) — **ولا توجد وثيقة عملية Android لهذه المركّبة أصلًا**. كذلك REST ينشر اليوم واجهات المتجر/السلة/الطلبات/الإرجاع/الحجز/الوظائف/الإعارة/التوصيل بينما شاشة السوق ما زالت تقول نصًا إنها «لم تُفتح بعد في واجهة الباك إند» (`DiscoveryScreens.kt:45`).
4. **من أصل 32 رحلة تحمل ساقًا أصليًا في السجل:** 7 مبنية النواة (J01–J07)، 7 بأسطح جزئية (J09–J13، J21، J34)، **18 بلا أي سطح أصلي** (J08، J14–J20، J22–J26، J29–J33). ثلاث رحلات لا ينطبق عليها ساق تطبيق عضو (J27/J28/J35 — إدارية/تشغيلية).
5. **المحور العقدي:** 28 من رحلات J01–J30 لها عقد خادم قابل للاستهلاك على main عند الرأس (REST و/أو SDL)؛ الاستثناءات المقيسة: J29 (تفويض فريق المزود — REST الخاص به في PR مفتوح لم يصل main) وJ30 (مركّبة فوق البقية بلا عقد خاص). التطبيق يستهلك عمليات فعلية لـ**13 رحلة فقط**.
6. **مطابقة المعمارية موجبة في المقيس:** تدفق أحادي الاتجاه تام (StateFlow واحدة → `DayfApp(state, callbacks)`)، مستودع واحد يملك HTTP وحقن الـbearer، وصفر استدعاءات شبكة في عناصر الواجهة (قياس rg على `ui/`). **الفجوتان البنائيتان:** لا طبقة تنقل أصلاً (لا `androidx.navigation`، لا deep links سوى redirect الأوثان) والعربية مضمّنة حرفيًا في الكود مع RTL مفروض — لا resources ولا مسار إنجليزي/LTR، وهو شرط خروج W12 غير المتحقق.

---

## 1. منهجية القياس وحدوده

- كل صف أدناه مقيس بقراءة الملفات عند الرأس: مسارات `.kt` تحت `android/app/src/main/java/com/marketplace/android/`، وثائق `.graphql` تحت `android/app/src/main/graphql/`، سير العمل تحت `.github/workflows/`، ووحدات الخادم تحت `marketplace-*/`.
- حالة العقود المنشورة مقيسة من فرع التوزيع `graphql-contract` (أُحضر خصيصًا: `git fetch origin graphql-contract`) — `manifest.json` يقول v3 من `9ba0fd7d` بتوقيت `2026-10-18T05:52:49Z` وبصمة `e4a7c90c…`، والـSDL **مطابق بايت-بايت** لملف الشجرة `marketplace-app/src/main/resources/graphql/schema.graphqls` (قياس `git diff`).
- سجل الرحلات ([`end-to-end-journeys-register.md`](../governance/plans/end-to-end-journeys-register.md)) هو مرجع تعريف J01–J35 وحالات البنية الخلفية؛ هذه المصفوفة تقيس **الساق الأصلي** فقط ولا تعدّل السجل.
- **الحدود:** هذا جرد شجرة لا تشغيل: لم تُنفَّذ اختبارات CI أو المحاكي هنا، ولا يُدَّعى نجاح أي بناء. حالات PRs المفتوحة خارج main لا تُقرأ من الشجرة وتُذكر فقط حيث وثّقها السجل/سجل العمل على الشجرة.

---

## 2. المحور الأول — الرحلات J01–J35: الساق الأصلي المقيس

الأعمدة: **الساق المقيس** (ما في الشجرة فعلًا) / **الأدلة** (مسارات الملفات) / **العقد القابل للاستهلاك على main عند الرأس** (هل ينشر الخادم عقدًا يخدم الرحلة — بمعزل عن استهلاك التطبيق).

| الرحلة | الساق الأصلي المقيس | الأدلة (مسارات عند الرأس) | العقد القابل للاستهلاك |
|---|---|---|---|
| J01 تسجيل/دخول/جلسة/خروج | **مبني — غير مثبت كرحلة**: دخول PKCE عبر AppAuth + دخول/تسجيل مفتاح مرور عبر Credential Manager + تسجيل حساب + إعادة إرسال التحقق + خروج RP-initiated + استعادة جلسة صامتة عند 401 | `MainActivity.kt` (`beginSignIn`/`beginPasskeySignIn`/`beginRegisterPasskey`/`beginSignOut`)، `SignInScreen` في `ui/DayfApp.kt:311`، `MarketplaceApi.kt:16-38`، `AuthEntryPointE2ETest.kt` (entry فقط) | REST/OIDC موجود ومستهلك. **قيود مقيسة:** العميل العام بلا refresh token (`android/README.md:30`)؛ لا استدعاء إبطال RFC 7009 من العميل؛ اختبار الجهاز يثبت شاشة الدخول فقط |
| J02 إثبات الحي والعضوية | **مبني — غير مثبت كرحلة**: اقتراح مواقع + شجرة جغرافية + انضمام + طلب تحقق + عرض حالة العضوية؛ تبديل النطاق يمحو حالة الاكتشاف المشتقة | `NeighborhoodSetupScreen` في `ui/DayfApp.kt:535`، `CommunityViewModel.kt` ‏`setLocationQuery`/`joinNeighborhood`/`requestVerification`/`loadAccount` (محو حالة الاكتشاف عند تغير النطاق)، `MarketplaceApi.kt:50-63`، `ProfileScreen` في `ui/MainScaffold.kt:708` | REST موجودة ومستهلكة. **فجوة مقيسة:** لا عملية مغادرة عضوية في `MarketplaceApi.kt`؛ مسار دليل العنوان الخارجي فجوة خلفية (G-N2 في السجل) |
| J03 موجز الحي والاهتمامات | **مبني (قراءة REST) — والقراءة المركبة GraphQL مؤلفة لا منفَّذة**: موجز حي + صفوف اكتشاف منزلية + تنبيهات عاجلة + انطباعات، عبر REST؛ وثيقة `HomeFeed` تجمع الاثنين في عملية واحدة لكنها codegen فقط | `CommunityViewModel.kt` ‏`loadFeed`/`loadDiscoveryHome`/`loadUrgentAlerts`، `ui/MainScaffold.kt` (صفوف home و`homeDiscoveryRows`)، `MarketplaceApi.kt:65-73` + `:118-132`، `android/app/src/main/graphql/HomeFeed.graphql` | REST مستهلك + SDL ينشر `discovery` و`neighborhoodFeed` (v3). **الفجوة:** تنفيذ `HomeFeed` وقت التشغيل (يحتاج `apollo-runtime`)؛ شخصنة الاهتمامات فجوة خلفية (السجل: `IMPLEMENTED_NOT_E2E`) |
| J04 منشور ووسائطه | **مبني — غير مثبت كرحلة**: مؤلف منشورات بأربع أنواع + منتقي صور رسمي (حتى 5) + رفع موقّع PUT مباشر بلا bearer + تأكيد + تحديث حالة المفقودات | `ui/ComposerSheet.kt`، `MarketplaceRepository.kt:173-239` (`uploadPostMedia` — فحص النوع/الحجم، رفض إرسال الـtoken للتخزين)، `MainActivity.kt` ‏`postPhotoPickerLauncher`، `MarketplaceApi.kt:75-88` | REST موجودة ومستهلكة. **فجوة مقيسة:** لا قراءة تفاصيل منشور مفرد في التطبيق (لا `getPost` في `MarketplaceApi.kt` رغم أن `GET /posts/{postId}` صار موجودًا — `NeighborhoodPostController.java:144`) |
| J05 تعليقات وتفاعلات | **مبني — غير مثبت كرحلة**: قراءة تعليقات مرقّمة + كتابة + إعجاب/إلغاء بعدّاد حي | `CommunityViewModel.kt` ‏`openComments`/`sendComment`/`toggleReaction` (`:1700-1770`)، `ui/CommunityCards.kt`، `MarketplaceApi.kt:90-104` | REST موجودة ومستهلكة + SDL ينشر `post`/`postComments` (المرحلة 7) **بلا وثيقة عملية Android** |
| J06 مفقودات/إهداء/مستعمل | **مبني — غير مثبت كرحلة**: لوحة سوق الحي + إنشاء (مجاني/مسعّر) + سحب + تحديث حالة المفقودات | `CommunityViewModel.kt` ‏`loadMarket`/`publishMarketItem`/`withdrawMarketItem`/`updateLostFoundState`، `MarketplaceApi.kt:106-113` + `:78-82` | REST موجودة ومستهلكة. **فجوة مقيسة:** عملية «بيعت» (`POST /neighborhood/market/{id}/sold`) ليست على main عند الرأس (في PR مفتوح وفق سجل العمل) ولا في `MarketplaceApi.kt` |
| J07 مجموعات/فعاليات/استطلاعات | **مبني — غير مثبت كرحلة**: قوائم + إنشاء فعاليات/استطلاعات + RSVP انضمام/إلغاء + تصويت/سحب/إعادة + عضوية مجموعات | `CommunityViewModel.kt` ‏`loadEvents`/`toggleEventRsvp`/`toggleGroupMembership`/`castPollVote`/`withdrawPollVote`، `MarketplaceApi.kt:140-174`، `ExploreMode` (`CommunityViewModel.kt:37`) | REST موجودة ومستهلكة |
| J08 خبر محلي/بث رسمي | **لا سطح أصلي**: لا عملية أخبار في `MarketplaceApi.kt` إطلاقًا (لا قراءة لوحة ولا تفاصيل ولا تصحيح/سحب) | قياس rg على `MarketplaceApi.kt` — لا `news` | **العقد موجود غير مستهلك:** `GET /api/v1/news` + `GET /api/v1/news/{id}` (`NewsController.java:45,58`)؛ دورة التصحيح/السحب مثبتة خلفيًا (#657 وفق السجل، PR مفتوح عند القياس) |
| J09 توصية بعمل/خدمة | **جزئي — معاينة بلا مصدر**: بطاقات الاكتشاف تحمل `sourceType/sourceId/reason/paid/state` وتُعرض بمعاينة محدودة؛ **لا تنقل إلى تفاصيل المصدر** | `CommunityViewModel.kt` ‏`previewDiscoveryCard`/`dismissDiscoveryPreview` (`:1088-1106`)، `core/network/DiscoveryDtos.kt`، `DiscoveryCardPreviewDialog` في `ui/UiComponents.kt:567` | **العقد موجود جزئيًا غير مستهلك للمصدر:** الموجز يحمل حقائق المزود الحالية (جسر V232 على main) + صفحة المزود العامة `GET /providers/{id}/public` (`ProviderController.java:85`) — لا شاشة تستهلكها |
| J10 بلاغ وعاجل واعتراض | **جزئي**: إبلاغ منشور/تعليق بإيصال صادق + عرض التنبيهات العاجلة للنطاق؛ لا تتبع حالة بلاغ ولا اعتراض | `CommunityViewModel.kt` ‏`reportPost`/`reportComment`/`submitContentReport`/`loadUrgentAlerts`، `MarketplaceApi.kt:115-119` | REST للإرسال والتنبيهات مستهلكة. **فجوة عقد مقيسة:** `ContentReportController.java` لا يعرض إلا `POST /reports` — لا قراءة «بلاغاتي»/حالتها للعضو (فجوة خلفية أيضًا)؛ الاعتراضات خلف W11 |
| J11 محادثة وإشعار وجهاز Push | **جزئي — الإشعارات فقط**: قائمة إشعارات + تعليم مقروء؛ **لا محادثات** و**لا تسجيل جهاز Push** | `CommunityViewModel.kt` ‏`loadNotifications`/`markNotificationRead`، `MarketplaceApi.kt:134-138`؛ قياس rg — لا `messaging` ولا `push-tokens` | **العقود موجودة غير مستهلكة:** `MessagingController.java`/`LeadsController.java` و`PushTokenController.java` (`/me/push-tokens`)؛ FCM حي `BLOCKED_EXTERNAL` (السجل) |
| J12 بحث واكتشاف متعدد المجالات | **جزئي**: بحث منشورات + بحث عروض (`/api/v1/search` بفلاتر) + صفوف موضوع الاكتشاف بترقيم؛ فتح نتائج المجالات الأخرى لمصادرها غير مبني | `CommunityViewModel.kt` ‏`searchPosts`/`loadPropertyListings`/`loadDirectoryListings`/`openDiscoveryRow`/`loadMoreDiscoveryRow`، `MarketplaceApi.kt:40-49` + `:124-129` | REST موجودة ومستهلكة؛ حل المصدر (نفس فجوة J09) هو العائق |
| J13 استكشاف دليل الأعمال | **جزئي — ملخصات فقط**: شاشة دليل تبحث العروض المنشورة مع حد أدنى للتقييم وبيانات تقييم المزود ضمن الملخص؛ **لا ملف مزود** | `BusinessDirectoryScreen` في `ui/DiscoveryScreens.kt:108`، `MarketplaceApi.kt:40-49` | **العقد موجود غير مستهلك:** صفحة المزود العامة وساعاته وخدماته (`ProviderController.java:49,85` + `ProviderListingViewsController.java`)؛ GraphQL ‏`ListServices`/`GetService` مؤلفتان (كيان الخدمات التجريبي) غير منفَّذتين |
| J14 مطالبة صاحب العمل بالملف | **لا سطح أصلي** | قياس rg — لا `claim` في `MarketplaceApi.kt` | **العقد على main غير مستهلك:** دورة المطالبة كاملة V233 (#646 — رأس القياس نفسه): `ProviderClaimController.java` (بذر إداري/مطالبة/سحب/طوابير) |
| J15 مراجعة وردّ وإعلان ممول | **لا سطح أصلي** | قياس rg — لا `reviews` في `MarketplaceApi.kt` | **العقد موجود غير مستهلك:** `ReviewsController.java` (قراءات عامة + كتابة عضوية + ردّ + تصويت) |
| J16 متجر وكتالوج ومتغيرات | **لا سطح أصلي — شاشة صادقة بديلة**: شاشة «سوق» تعرض نصًا أن قائمة المنتجات/السلة/الطلبات «لم تُفتح بعد في واجهة الباك إند» وتحيل إلى حراج الحي | `StorefrontScreen` في `ui/DiscoveryScreens.kt:30` (النص عند السطر 45) | **العقد موجود غير مستهلك:** `StorefrontController.java` (`/api/v1/store`) + `StorefrontProductController.java` + `ProductController.java` + `ProductVariantController.java` — نص الشاشة صار أقدم من العقد المقيس |
| J17 سلة متعددة البائعين ووضع الطلب | **لا سطح أصلي** | قياس rg — لا `cart`/`orders` في `MarketplaceApi.kt` | **العقد موجود غير مستهلك:** `CartController.java` + `OrdersController.java` (البنية الخلفية `IMPLEMENTED_AND_PROVEN` وفق السجل) |
| J18 تنفيذ الطلب (تاجر) | **لا سطح أصلي** | قياس rg — لا `seller-orders` في `MarketplaceApi.kt` | **العقد موجود غير مستهلك:** `SellerOrdersController.java` (confirm/fulfill/cancel + قراءة عائدة) |
| J19 إرجاع/استبدال/استرداد | **لا سطح أصلي** | قياس rg — لا `returns` في `MarketplaceApi.kt` | **العقد موجود غير مستهلك:** `OrderReturnsController.java` (V230 على main) |
| J20 حجز خدمة/إقامة | **لا سطح أصلي** | قياس rg — لا `booking`/`availability` في `MarketplaceApi.kt` | **العقد موجود غير مستهلك:** `BookingController.java` + `marketplace-availability` (نوافذ التوفر) |
| J21 عقار ومركبات | **جزئي — بحث فقط**: شاشة عقار بفلاتر purpose/propertyType فوق بحث العروض؛ لا دورة استفسار/معاينة | `PropertySearchScreen` في `ui/DiscoveryScreens.kt:71`، `MarketplaceApi.kt:40-49` | REST للتفاصيل/الفلاتر موجودة (`PropertyController.java` على `/api/v1/listings`)؛ **دورة الاستفسار نفسها `GAP_CONFIRMED` خلفيًا** (السجل — خلف بوابة W6-d) |
| J22 وظائف وتقديم | **لا سطح أصلي** — بطاقة `JOB` تظهر في الاكتشاف كمعاينة فقط | قياس rg — لا `jobs` في `MarketplaceApi.kt`؛ `DiscoverySource.JOB` في SDL | **العقد موجود غير مستهلك:** `JobsController.java` (نشر/تقديم/سحب/صندوق) |
| J23 إعارة ورسوم | **لا سطح أصلي** | قياس rg — لا `lending` في `MarketplaceApi.kt` | **العقد موجود غير مستهلك:** `LendingController.java` (`/api/v1/lending`) |
| J24 توصيل مباشر | **لا سطح أصلي** | قياس rg — لا `delivery` في `MarketplaceApi.kt` | **العقد موجود غير مستهلك:** وحدتا delivery (اقتباس→طلب→إسناد→إثبات — شريحة #604/#630 على main) |
| J25 توصيل طلب متجر | **لا سطح أصلي** | قياس rg — لا عمليات أرجل طلب في `MarketplaceApi.kt` | **العقد موجود غير مستهلك:** أرجل الطلب V224–V228 + `OrdersDeliveryPort` (السجل) |
| J26 إدارة الأسطول والاستثناءات | **لا سطح أصلي** (سطح مشغّل) | قياس rg | العقد إداري (`marketplace-delivery` ‏V229) — نطاق واجهة المشغّل قرار PAOS/W11 لا تطبيق العضو |
| J27 مصادر الدفع الخمسة | لا ينطبق (ساق عضو) — لا سطح دفع في التطبيق أصلًا | — | إداري/خلفي (السجل: «لا ينطبق (باك إند)») |
| J28 دفتر وتسوية ونزاعات | لا ينطبق (ساق عضو) | — | إداري/خلفي (السجل: «لا ينطبق (باك إند)») |
| J29 فريق المزود وصلاحياته | **لا سطح أصلي** | قياس rg | **لا عقد على main عند الرأس:** تفويض الفريق V234 في PR مفتوح لم يُدمج بعد (سجل العمل) — ساقه تنتظر دمج الخلفية أولًا |
| J30 رحلات الغرض المركبة | **لا سطح أصلي** — لا منسّق أغراض في التطبيق | قياس rg — لا غرض/مسار مركّب في `ui/` | مركّبة فوق رحلات البقية بلا عقد خاص (بالتصميم) |
| J31 حل مشكلة عضو (مشرف/دعم) | **لا سطح أصلي** | قياس rg | إداري: `ConsoleAdminController.java` + طوابير — نطاق PAOS/W11 |
| J32 إعداد وتشغيل تجربة (مدير) | **لا سطح أصلي** | قياس rg | إداري: `ConsoleAdminController.java` — نطاق PAOS/W11 |
| J33 إشعارات وحوادث (مدير) | **لا سطح أصلي** | قياس rg | إداري — نطاق PAOS/W11 |
| J34 خصوصية وحساب | **جزئي — بلا شاشات خصوصية**: بطاقة «الخصوصية والجلسة» تصف سياسة الذاكرة فقط؛ لا قراءة `/me` ولا تصدير Art.20 ولا سلسلة محو | `ProfileScreen` في `ui/MainScaffold.kt:708` (بطاقة الخصوصية والجلسة) | **العقد موجود غير مستهلك:** `UserController.java` — ‏`GET /me` (`:61`) و`GET /me/export` (`:82`) و`DELETE /me` (`:133`) |
| J35 استعادة الخدمة | لا ينطبق (تشغيل) | — | تشغيلية (السجل: «لا ينطبق (تشغيل)») |

**عدّ المصفوفة:** مبني النواة 7 (J01–J07) · جزئي 7 (J09–J13، J21، J34) · لا سطح 18 (J08، J14–J20، J22–J26، J29–J33) · لا ينطبق 3 (J27، J28، J35) = **35**.

---

## 3. المحور الثاني — العقود: ما يستهلكه التطبيق مقابل ما ينشره الخادم

### 3.1 ما يستهلكه التطبيق اليوم (REST حصرًا وقت التشغيل)

واجهة واحدة: `android/app/src/main/java/com/marketplace/android/core/network/MarketplaceApi.kt` — **44 عملية** كلها ضمن هوية/جغرافيا/مجتمع/وسائط/إشعارات/اكتشاف/بحث:

| المجموعة | العمليات (عدد) | أمثلة المسارات في الملف |
|---|---|---|
| مصادقة ومفاتيح مرور | 6 | `registerAccount`، `exchangePasskeyGrant` (‏`oauth2/token` بمنحة `urn:marketplace:params:oauth:grant-type:passkey`) — السطور 16–38 |
| جغرافيا وعضوية | 6 | `suggestLocations`، `getGeoTree`، `getMyNeighborhood`، `joinNeighborhood`، `requestNeighborhoodVerification` — السطور 50–63 |
| منشورات وموجز ومفقودات | 4 | `getFeed`، `searchPosts`، `createPost`، `updateLostFoundState` — السطور 65–82 |
| وسائط (رفع موقّع) | 2 | `requestPostMediaUpload`، `confirmPostMediaUpload` — السطور 84–88 |
| تعليقات وتفاعلات | 4 | `getComments`، `createComment`، `reactToPost`، `removePostReaction` — السطور 90–104 |
| سوق الحي | 3 | `getMarket`، `createMarketItem`، `withdrawMarketItem` — السطور 106–113 |
| بلاغات وتنبيهات | 2 | `createContentReport`، `getUrgentAlerts` — السطور 115–119 |
| اكتشاف | 3 | `getDiscoveryHome`، `getDiscoveryRow`، `recordDiscoveryImpression` — السطور 121–132 |
| إشعارات | 2 | `getNotifications`، `markNotificationRead` — السطور 134–138 |
| فعاليات ومجموعات واستطلاعات | 12 | `rsvpEvent`، `toggleGroupMembership`… — السطور 140–174 |
| بحث العروض (عقار/دليل) | 1 | `searchListings` — السطور 40–49 |

**صفر عمليات** في `MarketplaceApi.kt` لأي من: الأخبار، المحادثات، تسجيل أجهزة Push، ملفات المزودين، المطالبات، المراجعات، المتجر/الكتالوج، السلة/الطلبات، الإرجاع، الحجز، الوظائف، الإعارة، التوصيل، `/me` والخصوصية (قياس rg مباشر).

### 3.2 عمليات Apollo GraphQL في الشجرة (ثلاث — كلها codegen بلا تنفيذ)

| الوثيقة | المسار | تخدم | حالتها المقيسة |
|---|---|---|---|
| `HomeFeed` | `android/app/src/main/graphql/HomeFeed.graphql` | J03 — القراءة الرئيسية المركبة (`discovery` + `neighborhoodFeed` في عملية واحدة) | تولّد نماذجها وتُدقّق في CI؛ **لا تنفّذ**: لا `apollo-runtime` ولا `ApolloClient` في الشجرة، والقراءة الفعلية عبر طلبَي REST |
| `ListServices` | `android/app/src/main/graphql/ListServices.graphql` | شريحة بحث الخدمات (كيان `Service`) | كذلك — كذلك |
| `GetService` | `android/app/src/main/graphql/GetService.graphql` | تفاصيل خدمة (كيان `Service`) | كذلك — كذلك |

قناة التوليد: `apollo { service("marketplace") }` في `android/app/build.gradle.kts:101-107` — المخطط من `src/main/graphql/schema.graphqls` وهو **gitignored** (`android/.gitignore`) وتضعه CI حصرًا.

### 3.3 ما ينشره الخادم — قناة عقود CI والبوابة الفعلية

- **SDL المنشور (v3):** `schema.graphqls` على فرع `graphql-contract` — مطابق بايت-بايت لـ`marketplace-app/src/main/resources/graphql/schema.graphqls` عند الرأس. المحتوى: ستة حقول Query (`service`، `services`، `discovery`، `neighborhoodFeed`، `post`، `postComments`) + Mutation واحد (`createService`) + أنواع الرحلات (`DiscoveryRow/DiscoveryCard/NeighborhoodPost/NeighborhoodPostMedia/PostComment` ومساراتها المعدودة enums). الوصول يتطلب مصادقة (401 للمجهول — دخان السجل §0).
- **النشر:** `.github/workflows/graphql-contract.yml` — بعد كل CI ناجح على main يُدفن SDL الإقلاع الفعلي (من `graphql-schema-gate`) إلى فرع التوزيع بإصدار أحادي الاتجاه (`manifest.json`: v3 من `9ba0fd7d`، بصمة `e4a7c90c…`).
- **الإنفاذ المقيس (ماذا تتحقق البوابة فعلًا):**
  1. `graphql-schema-gate` (`ci.yml:443-520`): بوابة توافق خلفي baseline-first على دومب الإقلاع، مع قائمة استثناءات مؤقتة (`.ci/graphql-compat-allowlist.yml`) — حذف حقل/نوع أو تشديد nullable يفشل البناء.
  2. `android-graphql` — **Android GraphQL Contract Gate** (`ci.yml:584-636`): يضع دومب SDL لنفس التشغيل في مسار Apollo، يشغّل `generateApolloSources` ثم `compileDebugKotlin` — أي عملية Android ثقيلة (حقل لم يعد المخطط يحمله) أو تغيير مخطط كاسر **يفشل فحصًا إلزاميًا** عبر `build-test` (`ci.yml:646-648` — «Build & Test (JDK 25)» المطلوب في الـruleset).
  3. `android-client.yml`: يجلب SDL **المنشور** (لا تشغيل PR نفسه) ويبني APK ويشغّل اختبار المحاكي (`AuthEntryPointE2ETest.kt`) — إشارة ثانية غير إلزامية بفارق زمن النشر الموثق في `android/README.md:62-65`.
- **جرد سطح REST الخادم عند الرأس:** 83 ملف `*Controller.java` عبر 26 وحدة (قياس find) — منها أسطح عامة/عضوية كاملة غير مستهلكة بالتطبيق: الأخبار، المحادثات، أجهزة Push، صفحة المزود، المطالبات، المراجعات، المتجر والكتالوج، السلة والطلبات، الإرجاع، الحجز، الوظائف، الإعارة، التوصيل، و`/me` وتصديره ومحوه.

### 3.4 قياس التغطية العقديّة للرحلات

- **لها عقد قابل للاستهلاك على main (يستطيع ساق Android أن يُبنى فوقه فورًا):** ‏J01–J28 جميعها + ‏J34 — أي **28 من رحلات J01–J30** (الاستثناءان: J29 وJ30) بالإضافة إلى J34، بملاحظة أن J21 دورة الاستفسار ناقصة خلفيًا وJ26–J28 أسطح مشغّل/إدارة يحتمل قرار PAOS.
- **بلا عقد على main:** J29 (تفويض الفريق — REST في PR مفتوح) وJ30 (مركّبة بلا عقد خاص بالتصميم).
- **يستهلكها التطبيق فعلًا:** 13 رحلة — النواة المبنية J01–J07 كاملةً، والجزئيات J09 وJ10 وJ11 وJ12 وJ13 وJ21 (عملية واحدة على الأقل لكل منها). أي أن **12 رحلة عضو لها عقد جاهز على main وساقها الأصلي صفر** (J08، J14–J20، J22–J25).
- **GraphQL تحديدًا:** رحلة واحدة لها وثيقة عملية مركّبة (J03 ‏`HomeFeed`) بلا تنفيذ، ومركّبة منشورة في SDL بلا وثيقة (تفاصيل المنشور J04/J05 ‏`post`/`postComments`) — أي أن **صفر رحلات GraphQL منفَّذة وقت التشغيل** عند الرأس.

---

## 4. المحور الثالث — بنود عمل W12: قائمة البناء الصادقة لكل فجوة

الترتيب مقترح بالتبعية المقيسة (الأعلى أولًا)، لا جدولًا زمنيًا. كل بند مسمّى بأجزائه الناقصة فقط (شاشة/عملية/تنقل/deep link) — لا اختراع.

| الأولوية | الرحلة | البنود الناقصة المقيسة |
|---|---|---|
| 1 | J04/J05 (تفاصيل المصدر) | **عملية + شاشة:** قراءة المنشور المفرد — إما REST ‏`GET /posts/{postId}` (موجود: `NeighborhoodPostController.java:144`) أو تأليف وثيقة `post`/`postComments` على SDL ‏v3 المنشور (لا وثيقة اليوم)؛ ثم شاشة تفاصيل تجمع المنشور + الوسائط + التعليقات مع read-back بعد الإعجاب/التعليق |
| 2 | J09/J12 (حل المصدر) | **تنقل:** من بطاقة اكتشاف (`sourceType/sourceId`) إلى وجهة المصدر الحقيقية: `NEIGHBORHOOD_POST` → شاشة التفاصيل (بند 1)، `PROVIDER_LISTING` → شاشة ملف المزود (بند 3)، `JOB` → شاشة وظيفة (بند 8)… المعاينة الحالية (`previewDiscoveryCard`) ليست وجهة. يتطلب طبقة تنقل (بند 12) |
| 3 | J13 (دليل الأعمال) | **شاشة + عمليات:** صفحة المزود العامة (`GET /providers/{id}/public`) بساعاته وخدماته ومناطقه، تُفتح من الدليل ومن بطاقة التوصية |
| 4 | J03 (القراءة المركبة) | **تشغيل GraphQL الأول:** إضافة `apollo-runtime` وشاشة تستهلك `HomeFeed` فعليًا (وثيقتها جاهزة) مع قياس REST-vs-GraphQL الموثق، أو قرار مالك بإبقاء REST — القرار موثق لا صامت |
| 5 | J34 (خصوصية وحساب) | **شاشات + عمليات:** قراءة `/me` (`UserController.java:61`)، تنزيل تصدير Art.20 (`:82`)، طلب المحو بحالاته الصادقة (`:133`) — العقود جاهزة |
| 6 | J11 (محادثة وPush) | **شاشات + عمليات:** قائمة محادثات/Inbox وخيط مباشر (`MessagingController.java`)، وتسجيل جهاز/تفضيلات (`PushTokenController.java`)، وربط إشعار بوجهته (deep link — بند 12) |
| 7 | J08 (الأخبار) | **شاشة + عمليات:** لوحة الأخبار وتفاصيلها وعرض التصحيح/السحب كما يقررهما الخادم (`NewsController.java`) |
| 8 | كتلة التجارة (J16–J19) | **الكتلة الأكبر:** شاشات المتجر/الكتالوج (`StorefrontController` و`StorefrontProductController`) → السلة والدفع (`CartController`/`OrdersController`) → صندوق طلبات التاجر (`SellerOrdersController`) → دورة الإرجاع (`OrderReturnsController`) — وتحديث نص «لم تُفتح بعد» في `DiscoveryScreens.kt:45` الذي صار أقدم من العقد المقيس |
| 9 | J20/J22/J23 (حجز/وظائف/إعارة) | **شاشات + عمليات** فوق العقود الجاهزة: نافذة توفر وحجز (`BookingController` + availability)، لوحة وظائف وسلّم تقديم (`JobsController`)، دورة الإعارة (`LendingController`) |
| 10 | J24/J25 (تتبع التوصيل) | **شاشات قراءة** لحالة الاقتباس/الطلب/الإسناد/الإثبات فوق عقود delivery (سطح العضو؛ إدارة الأسطول J26 قرار PAOS) |
| 11 | J14/J15 (مطالبة/مراجعات) | **شاشات + عمليات:** تقديم مطالبة ملكية ملف أعمال وحالتها (`ProviderClaimController` — على main)، وكتابة مراجعة مؤهلة وعرض الردود (`ReviewsController`) |
| 12 | عرضي (كل ما فوق) | **طبقة تنقل وdeep links:** لا `androidx.navigation` في الشجرة أصلًا (`build.gradle.kts` dependencies) والتنقل حالة `MainTab/ExploreMode` في ViewModel؛ أهداف W12 (صفوف→مصادر، إشعار→وجهة، استئناف عملية) تحتاج ركيزة تنقل أولاً |
| 13 | J01–J07 (النواة المبنية) | **إثبات الرحلات:** أجنحة instrumentation تمرر الرحلة الكاملة على المحاكي/الجهاز بحالات النجاح/الفشل (الدليل الحالي `AuthEntryPointE2ETest.kt` يقيس شاشة الدخول فقط)؛ وقرار مالك لفجوة refresh-token للعميل العام (`android/README.md:30`) |
| 14 | J02 | **عملية مغادرة** العضوية + تدفق التبديل الكامل مع محو حالة النطاق السابق (المحو موجود للاكتشاف — `loadAccount` — ويحتمل التعميم المقيس) |
| 15 | J06 | **عملية «بيعت»** عندما يصل REST الخاص بها إلى main (PR مفتوح عند القياس) |
| 16 | J30 | **منسّقو الأغراض** فوق رحلات البقية بعد اكتمال مكوناتها (توصية→مصدر→معاملة→نتيجة) — لا قبلها |
| — | J26/J31–J33 | أسطح مشغّل/إدارة — تُقاس ضمن نطاق PAOS/W11 لا ضمن تطبيق العضو (قرار مالك للنطاق) |
| — | J27/J28/J29/J35 | J27/J28/J35 لا ينطبق عليها ساق عضو (السجل)؛ J29 تنتظر دمج عقدها الخلفي أولًا |

---

## 5. قراءة مطابقة المعمارية (مقيسة من الشجرة)

### 5.1 ما هو مطابق لقانون W12

- **تدفق أحادي الاتجاه تام:** `MainActivity.kt:92` يجمع `StateFlow<AppUiState>` عبر `collectAsStateWithLifecycle` ويمرر الحالة + دوال الأحداث إلى `DayfApp` (`ui/DayfApp.kt:94`)؛ `CommunityViewModel` يملك الحالة (`feature/CommunityViewModel.kt:207`) — والقانون الموثق في `android/README.md:84-89`.
- **صفر استدعاءات شبكة في عناصر الواجهة:** قياس rg على `ui/**` — لا `Retrofit`/`OkHttp`/`MarketplaceApi`. مستودع واحد هو مدخل البيانات الوحيد (`data/MarketplaceRepository.kt:43-105`: بناء Retrofit، حقن الـbearer، وعزل عميل رفع التخزين الموقّع عن الهيدرات).
- **لا منطق دفع/صلاحية في الواجهة:** لا سطح دفع أصلًا (صادق)، والصلاحيات كلها خادمية (الخادم يملك الأهلية — مبدأ موثق في كل من README والمخطط).
- **الرموز في الذاكرة فقط:** `@Volatile accessToken` بلا أي تخزين دائم (`MarketplaceRepository.kt:70-105` + `android/README.md:28`).

### 5.2 الفجوات البنائية المقيسة (ليست انتهاكات بل حدودًا يجب على W12 التعامل معها)

1. **لا طبقة تنقل:** لا اعتمادية `androidx.navigation` في `android/app/build.gradle.kts`؛ «التنقل» تبديل حالة `MainTab`/`ExploreMode` (`CommunityViewModel.kt:36-37`)؛ الـintent-filter الوحيد هو redirect أوثان AppAuth (`AndroidManifest.xml` — `RedirectUriReceiverActivity`). لا deep links للإشعارات أو المصادر.
2. **العربية مضمّنة لا موردية:** RTL مفروض برمجيًا (`MainActivity.kt:108` — ‏`CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl)`) وكل النصوص حرفية عربية داخل الـcomposables (مثل `ui/MainScaffold.kt:708+` و`MainActivity.kt` رسائل الأخطاء)؛ `res/values/` يحمل `styles.xml` فقط — **لا `strings.xml` ولا `values-ar`** ولا مسار LTR/إنجليزي. شرط خروج W12 (عربي/RTL **وإنجليزي/LTR**) غير متحقق.
3. **مستودع واحد وViewModel واحد لسبع وجهات:** `MarketplaceRepository` (44 عملية) و`CommunityViewModel` (1883 سطرًا) — التدفق سليم لكن قابلية التوسع لبنود §4 تقتضي تقسيمًا بالرحلة (قرار تصميمي للمالك عند البدء، والبيان هنا قياس لا حكم).
4. **نص شاشة السوق متقادم مقابل العقد:** `DiscoveryScreens.kt:45` يقول إن السلة/الطلبات/التوصيل «لم تُفتح بعد في واجهة الباك إند» بينما `CartController.java`/`OrdersController.java`/وحدتا delivery منشورة على main — مثال مقيس على ضرورة إعادة القياس هذه قبل كل شريحة.

### 5.3 إنفاذ بوابة العقد (كما تقيسها سير العمل)

السلسلة الكاملة موصوفة في §3.3. خلاصتها لغرض المطابقة: **تغيير مخطط كاسر أو عملية Android ثقيلة يفشل فحصًا إلزاميًا قبل الدمج** (عبر `build-test` المطلوب في الـruleset)، والـSDL الذي يُبنى عليه التطبيق يأتي من قناة CI المنشورة حصرًا (لا من الإنتاج ولا نسخًا يدويًا — والملف gitignored). هذه البنية مطابقة لاشتراط الخطة «توثيق SDL وإصداره من مصدر CI، وتوليد العميل من النسخة المعتمدة».

---

## 6. حدود هذا الجرد ومراجعه

**الحدود:** جرد شجرة عند رأس محدد؛ لم تُشغَّل اختبارات أو محاكيات هنا ولا يُدَّعى نجاح بناء؛ حالات PRs المفتوحة مأخوذة مما وثّقه السجل وسجل العمل على الشجرة لا من استعلام GitHub حي؛ والسجل الحي يظل المرجع الوحيد لحالات الرحلات — هذه المصفوفة تقيس الساق الأصلي ولا ترفع أي صف.

**المراجع (كلها على الشجرة عند الرأس):**

- السجل الحي للرحلات: [`docs/governance/plans/end-to-end-journeys-register.md`](../governance/plans/end-to-end-journeys-register.md)
- الخطة الموحدة §4-W12: [`docs/governance/plans/end-to-end-final-execution-plan.md`](../governance/plans/end-to-end-final-execution-plan.md)
- معمارية GraphQL والمراحل: [`docs/api/graphql-target-architecture.md`](graphql-target-architecture.md) وجاهزية المرحلة 7: [`docs/api/graphql-stage7-composite-journeys-readiness-ar.md`](graphql-stage7-composite-journeys-readiness-ar.md)
- كود Android: [`android/README.md`](../../android/README.md) (§GraphQL contract consumption و§Code boundaries)، `android/app/build.gradle.kts`، `android/app/src/main/graphql/*.graphql`
- سير العمل: `.github/workflows/ci.yml` (السطور 443–648)، `.github/workflows/graphql-contract.yml`، `.github/workflows/android-client.yml`، `.github/workflows/android-release.yml`
- عقد الإقلاع: `marketplace-app/src/main/resources/graphql/schema.graphqls` (＝ المنشور v3 على فرع `graphql-contract`)
