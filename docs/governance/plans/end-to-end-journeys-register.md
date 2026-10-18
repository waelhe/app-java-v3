# سجل جرد الرحلات End-to-End — الوثيقة الحية (W1)

**تاريخ الإنشاء:** 18 أكتوبر 2026  
**أساس القياس:** main عند [`658ee5155`](https://github.com/waelhe/app-java-v3/commit/658ee5155eea1a7506fcb39251ff0253b225271e) — بعد دمج الخطة الموحدة (#636) ومزامنة الحقيقة (#635).  
**الغرض:** تنفيذ W1 من الخطة الموحدة: تحويل كتالوج الرحلات (§3 في الخطة) إلى سجل قابل للتحديث في كل PR. كل صف هنا يُعاد فحصه من main قبل أي ادعاء، ويُحدَّث بـPR الذي يغلقه.

> **قاعدة السجل:** حالات الدليل المعتمدة حصراً: `IMPLEMENTED_AND_PROVEN` (اجتاز اختبار القبول العابر للحدود المعنيّة) / `IMPLEMENTED_NOT_E2E` (بنية واختبارات وحدة/تكامل موجودة، والرحلة المركبة لم تُثبت) / `GAP_CONFIRMED` (فجوة مقيسة بلا تنفيذ) / `BLOCKED_EXTERNAL` (تنفيذ موجود وعائق خارجي موثق) / `NOT_IN_SCOPE` (خارج النطاق بقرار موثق). **عمود منفصل لساق Android** لأن رحلات الخطة تشترط الواجهة الأصلية للاكتمال الكامل، بينما تُقاس البنية الخلفية باستقلال.

## 0. الحقائق المرجعية المقيسة عند الأساس

- **الوحدات:** 30 وحدة Maven (جرد `pom.xml`). **الترحيلات:** 177 نسخية `V1..V230` + 3 بذور `R__`؛ سلسلة الموجة الحالية: V223 (سياسات تجارية، #624) → V224-V228 (اختيار التوصيل والأرجل، #628) → V229 (عمليات التوصيل، #630) → V230 (دورة RMA، #629).
- **اختبارات التكامل المركزية:** 23 `ModuleIntegrationTest` في `marketplace-app` + أجنحة الرحلات المركزية (منها `OrderJourneyIntegrationTest` و`CartVariantJourneyIntegrationTest` و`LoanDisputeJourneyIntegrationTest` و`WebhookInboxRecoveryIntegrationTest` و`WebhookSettlementRetryIntegrationTest` و`MediaUploadFlowIntegrationTest` و`AccountMailJourneyIntegrationTest`).
- **الإنتاج:** نشر `f5179c1fe5` SUCCESS (deployment `7149086491`)؛ دخان حي طازج 2026-10-18: liveness/readiness/health ‏UP، jwks `kid=marketplace-jwt-r3`، OIDC ‏200، modulith/graphql ‏401 بالعقد، api-docs ‏308 مسارات/376 عمليات؛ #623 مغلقة بالقياس.
- **ساق Android العام:** تطبيق Compose بعقود Apollo مولّدة من قناة CI (`graphql-contract` v2) وبوابة `Android GraphQL Contract Gate` — **الشاشات المنفذة لرحلات كاملة غير مثبتة** (المرحلتان 5/6 أنشأتا القناة والنماذج والرحلة المركبة الأولى للقراءة فقط). كل رحلة تظهر هنا ساقها الأصلي `GAP_CONFIRMED` ما لم يُذكر خلاف ذلك بمقاس.

## 1. السجل — J01–J35

| المعرّف | الحالة (البنية الخلفية) | ساق Android | المالك (وحدات) | الدليل المقيس (عند الأساس) | الفجوة الرئيسية ← الموجة المغلقة |
|---|---|---|---|---|---|
| J01 تسجيل/دخول/جلسة/خروج | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | identity, edge | `IdentityModuleIntegrationTest` (23 ملف اختبار) + `AccountMailJourneyIntegrationTest` + 7 متحكمات REST + بوابة edge | دورة الجلسة الكاملة على العميل الأصلي (استعادة/تبديل/خروج بأثر) ← W2/W12 |
| J02 إثبات الحي والعضوية الجغرافية | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | geo, community, identity | `GeoModuleIntegrationTest` + `NeighborhoodMembershipModuleIntegrationTest` + `NeighborhoodVerificationAdminControllerWebMvcTest` | مسار تقديم دليل العنوان من العضو + تحديث السياق عند الانتقال ← W2 |
| J03 موجز الحي والاهتمامات | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | community, discovery, ai | `NeighborhoodPostModuleIntegrationTest` + رحلة GraphQL المركبة الأولى (المرحلة 6، #612) + `DiscoveryController` | شخصنة الاهتمامات ورحلة المصدر-إلى-البطاقة-إلى-التفاصيل كاملة ← W3/W4 |
| J04 منشور ووسائطه | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | community, media | `NeighborhoodPostModuleIntegrationTest` + `MediaUploadFlowIntegrationTest` + `MediaModuleIntegrationTest` | دورة الرفع الموقّع→التأكيد→العرض داخل المنشور كمركّب واحد ← W3 |
| J05 تعليقات وتفاعلات | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | community | 35 ملف اختبار في community + moderation | سباقات التحديث وحالات فقدان الأهلية على الواجهة ← W3 |
| J06 مفقودات/إهداء/مستعمل | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | community, catalog | نموذج المفقودات في community (موثق بالتدقيق #627) | بيع السلع الفريدة المستعملة خارج نموذج المتغيرات ← W6 |
| J07 مجموعات/فعاليات/استطلاعات | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | community | `NeighborhoodEventModuleIntegrationTest` + استطلاعات ومجموعات (35 ملف) | دورة RSVP/النتائج/الخصوصية كمركّب ← W3 |
| J08 خبر محلي/بث رسمي | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | knowledge, institutions | `NewsController` + `NewsAdminController` + `UrgentAlertAdminController` + `InstitutionAdminController` | دورة التصحيح/السحب وتتبع التوصيل ← W9 |
| J09 توصية بعمل/خدمة | `IMPLEMENTED_AND_PROVEN` (خلفي) | `GAP_CONFIRMED` | community, provider, reviews | **الجسر مبني (W4):** حقل `recommendedProviderId` اختياري (V232) + بوابة وجود 404 قبل أي كتابة + عرض الموجز يحمل حقائق المزود **الحالية وقت القراءة** (دفعة واحدة للمعرّفات المميزة عبر ProviderLookupPort.findAllByIds) + فتح المصدر عبر الصفحة العامة + تغيّر اسم المزود يظهر في القراءة التالية (لا تجميد) — `RecommendationSourceJourneyIntegrationTest` (3 اختبارات) + 3 اختبارات وحدة | ساق الواجهة ← W12 |
| J10 بلاغ وعاجل واعتراض | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | community, reviews, disputes, institutions | `ContentReportModuleIntegrationTest` + `ReviewModerationAdminControllerTest` + `ModerationAdminControllerTest` + **منع التكرار العاجل منفذ (W3):** بوابة الخدمة (نافذة متداخلة→409) + V231 فهرس فريد جزئي كحارس سباق + ترجمة 23505→409 (`UrgentAlertService`/`UrgentAlertServiceTest` 4 اختبارات جديدة) | دورة الاعتراض/إعادة النظر وسلسلة القرار الموثقة ← W11 (التظلمات) |
| J11 محادثة وإشعار وجهاز Push | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | messaging, notifications | `MessagingModuleIntegrationTest` + `DirectConversationModuleIntegrationTest` + `NotificationsModuleIntegrationTest` + `FcmHttpV1PushAdapter` | تسليم FCM الحي `BLOCKED_EXTERNAL` (اعتمادات الإنتاج غير متحققة) + دورة الجهاز الكاملة ← W9 |
| J12 بحث واكتشاف متعدد المجالات | `IMPLEMENTED_AND_PROVEN` (اكتشاف المجتمع) | `GAP_CONFIRMED` | search, discovery, app | `DiscoverySourceLinkJourneyIntegrationTest` (W4): بطاقة LOST_FOUND معرّفها يفتح **نفس** المنشور في موجز المالك + حذف واحد يزيله من السطحين معًا (لا حقيقتين للظهور) + بلا عضوية: لوحة فارغة صادقة. البحث النصي الموحد يبقى بأدلته السابقة (`SearchModuleIntegrationTest` + `UnifiedSearchController`) | فتح نتائج المجالات الأخرى (عقار/وظائف/منتجات) لمصادرها كمركّب + ساق الواجهة ← W4 المتبقي/W12 |
| J13 استكشاف دليل الأعمال | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | provider, reviews, search | `ProviderModuleIntegrationTest` + صفحة عامة وساعات ومناطق وخدمات | حداثة البيانات وثقة المصدر واكتمال الملف ← W5 |
| J14 مطالبة صاحب العمل بالملف | `GAP_CONFIRMED` | `GAP_CONFIRMED` | provider, identity | لا دورة مطالبة/تحقق/اعتراض مقيسة | بناء الدورة بمالك واحد ← W5 |
| J15 مراجعة وردّ وإعلان ممول | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | reviews, catalog, payments | `ReviewsModuleIntegrationTest` + `POST /reviews/{id}/reply` (مقيس #627) + تصويت وإشراف + أصل الدفع AD + ترقية العروض | فصل تقييمات العملاء عن الممول وسياسة الأهلية كاملة ← W5/W7 |
| J16 متجر وكتالوج ومتغيرات | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | catalog, provider | `CatalogModuleIntegrationTest` + `ProductVariantJourneyIntegrationTest` + V219 + 28 ملف اختبار | تهيئة المتجر والتحقق وتحديثات ظاهرة للعميل ← W5 |
| J17 سلة متعددة البائعين ووضع الطلب | `IMPLEMENTED_AND_PROVEN` (خلفي) | `GAP_CONFIRMED` | orders, catalog, pricing | `OrderJourneyIntegrationTest` + `CartVariantJourneyIntegrationTest` + `OrdersServiceTest`/`OrdersControllerTest` (تجميد التسعير V223 + اختيار التوصيل V224 + التقسيم ADR-0010 + الحجز الذري) | ساق الواجهة والأخطاء المعروضة ← W12 |
| J18 تنفيذ الطلب (تاجر/تسليم) | `IMPLEMENTED_AND_PROVEN` (خلفي) | `GAP_CONFIRMED` | orders, delivery | سطح التاجر #607 (confirm/fulfill/cancel + قراءة عائدة) + `OrdersDeliveryPortTest` (اكتمال الأرجل) + `OrderFulfillmentListener` | ساق التاجر وواجهة الاستثناء ← W11/W12 |
| J19 إرجاع/استبدال/استرداد | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | orders, payments, ledger | V230 (#629): `OrderReturnsServiceTest` + `OrdersControllerTest` — الدورة منفصلة عن الاسترداد بالبناء | قبول مركّب RMA→قرار→استلام→استرداد المال بعقد الدفع ← W6/W7 |
| J20 حجز خدمة/إقامة | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | booking, availability, payments, reviews | `BookingModuleIntegrationTest` + `AvailabilityModuleIntegrationTest` + أصل BOOKING + `BookingCancelledEventListenerTest` | مركّب حجز→دفع→تأكيد→تقييم مؤهل بفروعه ← W6/W7 |
| J21 عقار ومركبات | `GAP_CONFIRMED` (التفاصيل والفلاتر `IMPLEMENTED_NOT_E2E`) | `GAP_CONFIRMED` | realestate, catalog | `PropertyModuleIntegrationTest` + تفاصيل منظمة وفلاتر | دورة الاستفسار/المعاينة/العقد غير منفذة ← W6 (خلف بوابة أولوية المالك) |
| J22 وظائف وتقديم | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | jobs | 4 ملفات اختبار: نشر/تقديم/سحب/صندوق صاحب العمل | المقابلة/العرض/التوظيف خارج النطاق الحالي — يُحدَّد النطاق ← W6 |
| J23 إعارة ورسوم | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | lending, payments, disputes | `LoanDisputeJourneyIntegrationTest` + `LendingModuleIntegrationTest` (بنعومة الاختبار) + أصل LOAN | مركّب الرسوم→التسليم→العودة→الإغلاق بفروعه ← W6/W7 |
| J24 توصيل مباشر | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | delivery, payments | شريحة #604 (اقتباس→طلب→دفع DELIVERY→إسناد→إثبات) + `DeliveryServiceTest` + `DispatchQueueService`/`CourierOperationsService` (V229) | قبول مركّب مستقل المسارات (فشل/تكرار/إلغاء/تسوية) ← W8 |
| J25 توصيل طلب متجر | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | delivery, orders | V224-V228: أرجل الطلب من الاختيار المجمد + سقف التقسيم + الإتمام مرة واحدة (`OrdersDeliveryPortTest`) | مركّب الرجل من الطابور إلى الإثبات والإتمام ← W8 |
| J26 إدارة الأسطول والاستثناءات | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | delivery | V229 (#630): `DeliveryReconciliationService` + `CarrierRegistry` + 16 اختبارًا جديدًا | قبول تشغيلي (تسوية بعملات، مندوب موقوف، قدرات ناقل معلنة) ← W8 |
| J27 مصادر الدفع الخمسة | `IMPLEMENTED_NOT_E2E` (المصفوفة المقيسة منشورة) | لا ينطبق (باك إند) | payments | **المخرج الإلزامي الأول لـ W7 منشور:** `docs/payments/origin-state-matrix.md` (مقيس من الكود: مفاتيح/قيود/مستمعون/استردادات/دفاتر/بوابات/غيابات G1-G7) + الأدلة السابقة (`PaymentsModuleIntegrationTest` + `OrderIntentContractTest` + `AdBillingEventListenerTest` + `AdBillingDeterminismIntegrationTest` + `WebhookInbox/SettlementRetry`) | أجنحة القبول لكل أصل (تكرار/متأخر/إلغاء/استرداد) ← W7 |
| J28 دفتر وتسوية ونزاعات | `IMPLEMENTED_NOT_E2E` (المصفوفة المقيسة منشورة) | لا ينطبق (باك إند) | ledger, payments | المصفوفة نفسها وثّقت بوابة التسوية الوحيدة في الكود (يومية التوصيل CAPTURE→RECOGNIZE) والغياب G5 (لا كود بوابة للأصول الأربعة — سطح قراءة W11) + الأدلة السابقة (`LedgerModuleIntegrationTest` + تجميد العمولة V220/V221 + `WebhookSettlementRetryIntegrationTest` + قراءة مالية موحدة #620) | أهلية التسوية كقراءة تشغيلية ← W11؛ أجنحة القبول ← W7 |
| J29 فريق المزود وصلاحياته وتوفره | `GAP_CONFIRMED` (الملف والساعات `IMPLEMENTED_NOT_E2E`) | `GAP_CONFIRMED` | provider, availability, identity | `ProviderModuleIntegrationTest` + `AvailabilityModuleIntegrationTest` | تفويض فريق/فرع بصلاحيات ونطاق — غير منفذ ← W5/W11 |
| J30 رحلات الغرض المركبة | `GAP_CONFIRMED` | `GAP_CONFIRMED` | عبر المجالات | لا منسّق مقيس | تصميم كل غرض ثم إثبات المنسّق ← W10 |
| J31 حل مشكلة عضو (مشرف/دعم) | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | console, disputes, community | `ConsoleAdminController` + طوابير إدارة + `DisputesModuleIntegrationTest` | ملف 360 وتكليف وتصعيد وسلسلة قرار ← W11 |
| J32 إعداد وتشغيل تجربة (مدير) | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | console, app | `ConsoleAdminController` + بوابات ومقاييس وإعدادات + `PlatformReleaseJourneyIntegrationTest` | معاينة الجمهور والإطلاق التدريجي وقياس الأثر ← W11 (PAOS هدف) |
| J33 إشعارات وحوادث (مدير) | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | notifications, institutions | `NotificationsModuleIntegrationTest` + تنبيه عاجل مؤسسي + تفضيلات | تتبع تسليم/فشل القنوات وإعادة المحاولة الآمنة؛ FCM ‏`BLOCKED_EXTERNAL` ← W9 |
| J34 خصوصية وحساب | `IMPLEMENTED_NOT_E2E` | `GAP_CONFIRMED` | identity, app | `POST /users/{id}/pseudonymize` + `purge-content` + `purge-audit-history` (مقيسة في `AdminController`) + `AccountMailJourneyIntegrationTest` | تصدير/تصحيح البيانات وسياسة الاحتفاظ وأثر الطلب ← W2/W13 |
| J35 استعادة الخدمة (فريق الإصدار) | `IMPLEMENTED_AND_PROVEN` | لا ينطبق (تشغيل) | عبر المنصة | **الدليل الحي:** جدار Neon الموثق (التكرار الثالث) → قرار المالك (الخيار C) → نشر الاسترداد `ef7066a1` SUCCESS → دخان ALL PASS → إغلاق #623 بقياس طازج (هذه الدورة) + runbook §11.1 بدروسه المقيسة | الاستمرار: مراقبة النافذة وتحديث الدخان عند كل إطلاق ← W13 |

## 2. قواعد تحديث هذا السجل

1. يُحدَّث الصف في نفس PR الذي يغيّر دليله (اختبار جديد يرفع الحالة، ترحيلة تفتح قدرة، قياس حي يرفع `BLOCKED_EXTERNAL`) — لا تحديث لاحق منفصل بلا سبب.
2. رفع الحالة إلى `IMPLEMENTED_AND_PROVEN` يتطلب اختبار القبول العابر للحدود التي تعتمدها الرحلة (جناح مركّب باسم الرحلة وفق الخطة §5) — لا يكفي اختبار وحدة أو واجهة.
3. كل صف يحمل أدلته بمسارات ملفات/اختبارات فعلية عند رأس محدد؛ يُعاد التحقق من المسارات عند التحديث.
4. ساق Android عمود مستقل: لا تُرفع رحلة إلى «مكتملة للمنتج» وساقها `GAP_CONFIRMED` (الخطة §10).
5. `BLOCKED_EXTERNAL` يتطلب توثيق العائق ومالك قراره؛ لا يُرفع إلا بقياس حي بعد فتح العائق.

## 3. الاعتمادات الفورية المقيسة من هذا السجل

- **مسار W2 (الهوية والحي):** J01+J02+J34 — الأساس الخلفي قوي (identity 23 ملف اختبار + membership/geo)؛ العمل على ساق العميل الأصلي ومسار دليل العنوان.
- **مسار W3-W5:** الفجوات المؤكدة الوحيدة المبنية من الصفر: J09 (وصل التوصية بالمصدر) وJ14 (مطالبة الملف) وJ29 (تفويض الفريق) — البقية رفع دليل مركّب فوق بنية قائمة.
- **مسار W6-W8:** J17/J18 مثبتان خلفياً بالفعل — العمل المركّب يبدأ من J19 (RMA) وJ24/J25/J26 (التوصيل) حيث البنية جديدة (V229/V230) والقبول المركّب لم يُبنَ بعد.
- **مسار W7:** مصفوفة أصل×حالة هي المخرج الإلزامي الأول (الخطة §6).
