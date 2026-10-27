# مخطط تطبيق SHEIN 15.5.0 (`com.zzkko`) — قراءة معكوسة مقيسة

> **النوع:** مخطط تحليلي (blueprint) مبني على الأدلة من الحزمة المشحونة نفسها عبر REA.
> **ليس** خطة حوكمة ولا قرارًا معماريًا، ولا يغيّر أي عقد أو كود.
> **حدّ الإثبات:** تحليل ساكن فقط (لم يُشغَّل التطبيق، ولا التُقط ترافيك حي).

---

## 1. هوية الأصل (observed)

| الحقل | القيمة |
| --- | --- |
| الحزمة | `com.zzkko` |
| الإصدار | 15.5.0 (versionCode 2941) |
| minSdk / targetSdk | 23 / 36 |
| الحزمة الأصلية | `com.zzkko_15.5.0-2941_4arch_7dpi_2feat_….apkm` — 48,212,198 بايت |
| SHA-256 للحزمة | `d130c3a3436561e57f75c3160c011753fa5683fb375ba955d7542383265a2e0a` |
| `base.apk` | 82,809,065 بايت (بعد فك APKM) |
| التقسيمات | 4 معماريات (arm64-v8a, armeabi-v7a, x86, x86_64) + 7 كثافات + `split_si_dynamic_common` |
| الفئات / الموارد | 38,240 فئة · 7,661 مورد · 6 ملفات DEX |
| الأدلة | `ev_a62bc6a1…` (جرد الحزمة) · `ev_734343d2…` (هوية الحزمة) · `ev_128177e8…` (المخطط الكامل) |

---

## 2. البنية التقنية المقيسة

**تطبيق أصلي (Native) + طبقة هجينة داخلية تُسمى Wing — لا React Native ولا Flutter:**

| المؤشر | القياس |
| --- | --- |
| `com.facebook.react.*` | **0 فئة** |
| `com.facebook.hermes.*` | **0 فئة** |
| `io.flutter.*` | **0 فئة** |
| `com.shein.wing.*` | **446 فئة** (محرك H5 + `WingAxios` + `WingDiskLruCache` + `WingConfigCenter`) |
| `com.alibaba.android.arouter.*` | **227 فئة** — توجيه بين الوحدات (modular routing) |
| `com.shein.sui.*` | 219 فئة — نظام تصميم داخلي |
| `cn.tongdun.*` | 307 فئة — بصمة جهاز/كشف احتيال طرف ثالث |

**القراءة المعمارية:** شاشات أصلية (156 نشاطًا) + صفحات H5 تُقدَّم عن بُعد وتُعرض داخل حاوية `shein.wing.uifeature.SheinH5Activity`، ويُحدَّد المسموح لها بالتنقّل عبر سجل مسارات محلي `assets/route_rule.json` (44 مسارًا فريدًا). أي أن «الشاشة» في هذا التطبيق قد تكون أصلية أو ويب-هجينة، والقرار بيد السجل لا بيد الكود وحده.

**أثقل النطاقات بالكود (دليل على مركز الثقل الوظيفي):**

| النطاق | الفئات |
| --- | --- |
| `com.zzkko.business.new_checkout` | 2,061 |
| `com.zzkko.bussiness.order` | 1,727 |
| `com.zzkko.bussiness.checkout` | 743 |
| `com.shein.cart.shoppingbag2` | 728 |
| `com.zzkko.si_goods_platform` | 681 + 428 + 157 |
| `com.zzkko.bussiness.payment` | 571 |
| `com.zzkko.si_store` | 995 |
| `com.zzkko.si_goods_detail_platform` | 1,688 |

القاعدة المقيسة: أكبر تكتّل كودي واحد في التطبيق هو **الشراء والدفع والطلب** — `new_checkout` + `bussiness.order` + `bussiness.checkout` + `bussiness.payment` = **5,102 فئة (13.3% من 38,240)**، مقابل 995 فئة للتجارة المرئية (`si_store`) و677 لـ`si_sales`. أي أن وزن التعقيد في المسار النقدي لا في التصفح.

---

## 3. سجل المسارات الهجينة (44 مسارًا — observed من `assets/route_rule.json`)

| المجموعة | المسارات |
| --- | --- |
| الإطار العام | `/web/web` · `/main/main` · `/main/shop` · `/main/category` · `/main/new` · `/common/pop_preview` · `/3m/meta_link` |
| القوائم التجارية | `/goods/real_goods_list` · `/goods/item_picking_list` · `/goods/coupon_for_goods_list` · `/goods/selling_point` · `/goods/flashsale` · `/goods/daily_new_list` · `/goods/discount_list` · `/recommend/home_selected_list` · `/recommend/similar_list` |
| البحث | `/search/search_home` · `/search/search_result` · `/search/search_image` · `/search/camera` |
| المنتج | `/goods_details/goods_details` · `/review/goods_reviews_list` |
| السلة | `/cart/shop_cart` · `/cart/shop_cart_tab` · `/cart/common_add_items` |
| الطلب | `/order/order_list` · `/order/order_detail` |
| الحساب | `/user/me` · `/account/login` · `/account/login_new` · `/event/authorizationSocialMediaSuccess` |
| الأمنيات | `/wish/my_wish_list` · `/wish/wish_board_list` · `/wish/wish_board_share_list` · `/wish/recently_viewed_list` |
| المحتوى | `/community/community_home_page` · `/gals/gals` · `/live/live_detail` · `/live/media_view` · `/video/video_detail` |
| الولاء والترفيه | `/point/check_in` · `/regulars/feed_dog` · `/trial/trial_center_home` |
| المتجر | `/store/home` |

**ملاحظة دلالية:** وجود مسار لا يعني شاشة أصلية؛ بعضها يُقدَّم من الويب. ولذلك بنى الفريق الحقيقي هو «سجل المسارات + حاوية H5»، وهو ما يجب أن يُحاكى في أي مخطط مقارن.

---

## 4. الخريطة الوظيفية — 12 مجموعة (156 نشاطًا أصليًا)

| # | المجموعة | شاشات مرصودة (أمثلة) | الفئات |
| --- | --- | --- | --- |
| 1 | الإقلاع والرئيسية | `WelcomeActivity` · `MainTabsActivity` · `NewChannelActivity` | `com.shein.welcome`, `si_main` |
| 2 | التصفح والقوائم | `RealListActivity` · `SelectListActivity` · `InformationFlowLandingPageActivity` · `DiscountActivity` | `si_goods` (6 أنشطة) |
| 3 | البحث | `SearchHomeDynamicActivity` · `SearchListActivityV2` · `SearchGoodsListActivity` · `SearchImageResultActivity` · `CameraActivity` | `si_search` + `si_visual_search` |
| 4 | المنتج | `GoodsDetailActivity(+Pop)` · `GalleryActivity` · `VideoFeedActivity` · `BatchBuyDialogActivity` | `si_goods_detail` (6) |
| 5 | السلة | `ShoppingBagActivity2` · `CartGalleryActivity` · `PromotionAddOnActivity` | `shein.cart` (2,080 فئة) |
| 6 | الشراء السريع (OCB) | `OneClickPayActivity` · `OcbCartDialogActivity` · `OcbV2DialogActivity` · `OcpActivity` | `bussiness.ocb_checkout` (219) |
| 7 | الدفع | `CheckoutV2Activity` · `CashierDeskActivity` · `CardPaymentActivity` · `PaymentBLIKCodeActivity` · `AfterPayCashAppHandleActivity` · `ThreeDSecureActivity` · `FriendPaymentActivity` | `bussiness.payment` (571) |
| 8 | التقسيط (BNPL) | `ParceladoHomeActivity` · `ParceladoOtpActivity` · `ParceladoAllOutstandingActivity` · `ParceladoMonthBillDetailActivity` · `ParceladoInvoiceDetailActivity` | `bussiness.parcelado` (94) |
| 9 | الطلبات والمرتجعات | `OrderListActivity` · `OrderDetailActivity` · `OrderPaymentDetailActivity` · `OrderSearchActivity` · `ExchangeListActivity` · `ExchangeSearchActivity` · `OrderCodSureActivity` | `bussiness.order` (1,727) |
| 10 | حساب وإعدادات | `SignInActivity` · `MyProfileActivity` · `AccountListActivity` · `EditMySizeActivity` · `AddKidsActivity` · `CurrencyActivity` · `SettingLanguageActivity` · `SettingActivity` · `MyAddressActivity` | `bussiness.login` (322), `shein.me` (537) |
| 11 | قسائم ونقاط | `MeCouponActivity` · `MeCouponHistoryActivity` · `CheckoutCouponActivity` · `CashierCouponActivity` · مسار `/point/check_in` · `club_saver` (361 فئة) | `si_guide.coupon` (323) |
| 12 | محتوى ومجتمع وبث | `LiveListActivity` · `LiveNewActivity` · `LiveAddBarrageActivity` · `MediaActivity` · `TakePhotoActivity` (gals) · `FeedbackActivity` | `shein.live` (312) |
| 13 | دعم وإشعارات | `SettingNotificationActivity` · `SettingMessageNotificationActivity` · `PDFViewerActivity` (تذاكر الدعم) | `si_message`, `si_customer_service` |
| 14 | خدمات داخلية (خلف الكواليس) | 10 مزوّدات App Widget (`android.appwidget.provider`) + 4 مستقبلات مرافقة + `LiveUpdateActionReceiver` + `WorkManager` + `CountdownService` | `appwidget` (463) |

---

## 5. الروابط العميقة والاستحواذ (observed)

- **المخططات:** `https` · `http` · `sheinlink` · `sheinbranchlink` · `sheinpaylink` · `pushscheme` · `fbconnect` · `kakao…` · `lineauth` · `naver3rdpartylogin` · `com.zzkko.braintree` · `geo`.
- **النطاقات:** `app.shein.com` وفروعها المحلية (`app.shein.tw` · `.se` · `.com.vn` · `.com.mx` · `.co.uk` · `.com.co`) + `onelink.shein.*` + `api-shein.shein.com` + `s.pro`.
- **الاستحواذ:** تكامل Branch (روابط onelink) + AppsFlyer (281 فئة) + Google Play Install Referrer.

**القراءة:** التطبيق مبني على «سوق متعدد المواقع»: نفس الحزمة تخدم 25 رمز موقع (`andshus`, `andsheur`, `andshfr`, … `andshtr`) وتُوجّه القياس إلى ثلاثة نطاقات (US / EU / default). أي مخطط خلفي يجب أن يكون **multi-site** من الأساس، لا إضافة لاحقة.

---

## 6. التكاملات الخارجية (observed من المخطط والموارد)

| الفئة | التكاملات |
| --- | --- |
| هوية الدخول | Google · Facebook · Kakao · LINE · Naver |
| الدفع | Braintree + Cardinal (3DS) · Cash App Pay · BLIK (بولندا) · COD |
| القياس والتحليلات | Firebase (Messaging/Analytics/RemoteConfig/Crashlytics) · Sensors Analytics · srmdata |
| الاستحواذ | AppsFlyer · Branch |
| المخاطر | Tongdun (بصمة جهاز) |
| رؤية حاسوبية | ML Kit Text Recognition (مسح بطاقة بنكية — `ultron.service.bank_card_ocr`) |
| أخرى | Maps · WorkManager · Room · OkHttp/Retrofit · ARouter |

---

## 7. الأذونات (37 إعلانًا، منها 13 حساسة)

الكاميرا · الموقع الدقيق والتقريبي · الملفات/الصور · الإشعارات (بما فيها `POST_PROMOTED_NOTIFICATIONS`) · البصمة/Biometric · التقويم (قراءة/كتابة) · Wi-Fi/الشبكة · التذكيرات · Foreground Service · AD_ID.

---

## 8. الخلفية المطلوبة لكل مجموعة — وحالتنا المقيسة

| قدرة يحتاجها التطبيق | في SHEIN | في نظامنا (مقيس من الشجرة) |
| --- | --- | --- |
| كتالوج منتجات/أنواع/خصائص | ✔ | ✔ `marketplace-catalog` (P2: 45/78 جذرًا) |
| بحث نصي | ✔ | ✔ `marketplace-search` |
| بحث بالصورة | ✔ | ✘ لا مالك (يحتاج `media` + `ai`) |
| قسائم/خصومات | ✔ | ✔ `marketplace-pricing` (Voucher/VoucherRedemption) |
| سلة + checkout | ✔ | ✔ `marketplace-orders` (CheckoutView) |
| شراء سريع OCB (طريقة دفع محفوظة) | ✔ | ✘ لا capability محفوظة |
| دفع + تسوية + دفتر | ✔ | ✔ `marketplace-payments` + `marketplace-ledger` |
| تقسيط BNPL | ✔ | ✘ لا مالك — `marketplace-lending` المقيس = إعارة أصناف (بدل يومي + تأمين)، **ليس** أقساطًا |
| طلبات + مرتجعات | ✔ | ✔ `OrdersService` + `OrderReturnsService` |
| قائمة أمنيات (4 مسارات) | ✔ | ✘ لا مالك |
| نقاط/عضوية/check-in (club_saver) | ✔ | ✘ لا مالك (Membership الحالي = أحياء، ليس ولاءً) |
| بث مباشر | ✔ | ✘ `messaging` للـWS فقط، لا streaming |
| إشعارات/دعم | ✔ | ✔ `marketplace-notifications` + `messaging` (جزئي) |
| App Widgets | ✔ (10 مزوّدات) | ✘ لا مقابل (عميل فقط) |
| Multi-site/عملات/لغات | ✔ (25 رمزًا) | ~ إعدادات موجودة، لا فصل بيانات per-site |

---

## 9. حدود الإثبات

- لم يُشغَّل التطبيق؛ كل ما سبق **ساكن** (DEX/موارد/مخطط). لا ادعاء سلوك وقت تشغيل.
- لا تحقّق من توقيع الحزمة (`signature_verification: not_performed`).
- تربيط الشاشة↔المسار لم يُثبت آليًا لكل الحالات؛ بعض المسارات ويب-هجينة بالكامل.
- ترتيب الرحلات في §4 مستخرج من أسماء المكوّنات وحزمها، لا من تسجيل تنقّل حقيقي.

---

## 10. الملفات المكملة

| الملف | ماذا يضيف |
| --- | --- |
| `e2e-journeys.md` | الرحلات الثماني شاشة بشاشة، و57 نقطة اتصال خلفية مفترضة، ومصفوفة تغطية الأدلة. |
| `backend-action-items.md` | إسقاط الخريطة على 30 بندًا قابلًا للتنفيذ، بمالك ونوع فجوة وموجة واختبار قبول، مع جذور Saleor المقيسة حرفيًا من الجرد. |
