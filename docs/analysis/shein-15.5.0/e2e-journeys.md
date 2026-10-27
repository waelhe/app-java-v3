# رحلات SHEIN 15.5.0 — شاشة بشاشة، ونقاط الاتصال الخلفية المفترضة

[الملف الحاكم]: `docs/governance/plans/saleor-dashboard-full-commerce-canonical-plan.md` (D-SALEOR-FULL-001) — للربط بالعقد؛ والملف التحليلي `docs/analysis/shein-15.5.0/app-blueprint.md` هو المصدر المقيس.
[النواة المعمارية]: تحليل عقد رحلة (read-only) — لا كود ولا مخطط GraphQL ولا ترحيل.
[البند من المصفوفة §14.2]: تتبّع الدليل + حدود الوحدات (كل نقطة اتصال تُنسب إلى مالك واحد).
[أثر على الحدود]: لا تغيير. النسب إلى مالك هو اقتراح إسقاط، لا قرار.
[دين]: لا.

> **النوع:** مخطط تحليلي. ليس خطة حوكمة ولا قرارًا معماريًا.
> **حدّ الإثبات:** تحليل ساكن للحزمة (لم يُشغَّل التطبيق، ولا ترافيك حي). الشاشات والمسارات **مقيسة**؛ نقاط الاتصال الخلفية **مفترضة** من أسماء المكوّنات، لا من استدعاءات شبكة ملتقطة.

---

## 0. كيفية قراءة هذا الملف

| الوسم | المعنى |
| --- | --- |
| **OBSERVED** | مقيس من الحزمة: اسم نشاط في `AndroidManifest.xml`، مسار في `assets/route_rule.json`، أو اسم فئة في DEX. |
| **INFERRED** | استنتاج لنقطة الاتصال الخلفية من اسم المكوّن/الفئة. **لا إثبات على المسار أو الحقل الحقيقي.** |
| **UNKNOWN** | لا دليل كافٍ؛ يُترك صريحًا ولا يُخمَّن. |

**قاعدة العرض:** كل جدول «شاشات» مقيس بالكامل. كل جدول «نقاط اتصال» مفترض بالكامل، عدا ما يُوسم خلاف ذلك.

**الأدلة:** `ev_734343d2…` (هوية الحزمة) · `ev_128177e8…` (المخطط الكامل: 156 نشاطًا / 16 خدمة / 33 مستقبلًا / 13 مزوّدًا) · `ev_a62bc6a1…` (الجرد) · `ev_c700c88f…` (Wing).

---

## 1. الحالة المشتركة عبر الرحلات (Cross-journey state)

كل رحلة تعتمد على حالة تُحمل بين الشاشات. هذه أول ما يجب أن يملكه الباك اند قبل أي شاشة:

| الحالة | من يملكها في SHEIN (OBSERVED) | نقطة الاتصال المفترضة (INFERRED) | عندنا (مقيس) |
| --- | --- | --- | --- |
| الجلسة والهوية | `com.shein.user_service.*` (6 أنشطة) · `SignInActivity` · `AccountListActivity` | `me` + جلسة رمزية | ✔ `identity` + `me` INTEGRATED |
| السلة النشطة | `ShoppingBagActivity2` · `MainTabContentView` (شارة السلة) | `checkout` (قراءة) | ✔ `getOrCreateActiveCart` · `checkout` INTEGRATED |
| العنوان | `MyAddressActivity` · `PreAddressActivity` · `AddressApi` (checkout) | قراءة/كتابة عنوان | ✔ `AccountAddressService` |
| الموقع/اللغة/العملة | `CurrencyActivity` · `SettingLanguageActivity` · 25 رمز موقع | `shop` + قناة | ✘ `shop` BLOCKED_BY_DECISION · لا قنوات |
| القسائم المحفوظة | `MeCouponActivity` · `MeCouponHistoryActivity` | قائمة قسائم المستخدم | ~ `Voucher` موجود، لا قائمة per-user معلنة |
| المخاطر/البصمة | `cn.tongdun.*` (307 فئة) · `AppWidgetHeartBeat` | تقييم مخاطر عند الدفع | ✘ لا مالك |

**القراءة:** الحالتان المؤجلتان هما **القناة/الموقع** و**المخاطر**. كل ما بعدهما يعمل؛ وبدونهما لا يمكن محاكاة تعدد المواقع ولا حدّ الاحتيال.

---

## 2. الرحلة 1 — الإقلاع والتصفح (Home)

### 2.1 الشاشات (OBSERVED)

| # | الشاشة | المكوّن المقيس | المسار | حدث المستخدم |
| --- | --- | --- | --- | --- |
| 1.1 | الإقلاع | `com.shein.welcome.WelcomeActivity` | — | فتح التطبيق (بارد) |
| 1.2 | التبويبات | `com.zzkko.si_main.MainTabsActivity` | `/main/main` · `/main/shop` · `/main/category` · `/main/new` | تبديل تبويب |
| 1.3 | القناة الجديدة | `com.zzkko.newchannel.activity.NewChannelActivity` | — | دخول حملة/قناة |
| 1.4 | قائمة تصنيف | `RealListActivity` · `SelectListActivity` · `InformationFlowLandingPageActivity` | `/goods/real_goods_list` · `/goods/item_picking_list` | تصفية/تمرير |
| 1.5 | تخفيضات | `DiscountActivity` · `FlashSaleListActivity` | `/goods/discount_list` · `/goods/flashsale` | دخول عرض |
| 1.6 | ترتيب/ترند | `RankingListActivity` · `TrendChannelActivity` · `ChoicesLandingActivity` | `/goods/daily_new_list` | استكشاف |
| 1.7 | متجر بائع | `com.zzkko.si_store.store.activity.StoreMainActivityV2` | `/store/home` | زيارة متجر |
| 1.8 | متابعة متجر | `com.zzkko.si_store.follow.activity.FakeStoreFollowListActivity` | — | متابعة/إلغاء |

### 2.2 نقاط الاتصال المفترضة (INFERRED)

| # | العملية | الحقول/السلوك المطلوب | عقد Saleor المقيس | حالتنا |
| --- | --- | --- | --- | --- |
| **H1** | تمهيد الجلسة | مستخدم + سلة نشطة + عدّاد + عملة + موقع + إشعارات غير مقروءة | `me` | ✔ INTEGRATED |
| **H2** | تغذية الصفحة الرئيسية | شرائح مرتّبة (بانر/اختيارات/توصيات/عروض) + ترقيم | لا جذر Saleor — جذر منصّي (`discovery` موجود عندنا) | ✔ `DiscoverySpi` موصول |
| **H3** | شجرة التصنيفات | شجرة بعمق متغيّر + عدّادات | `categories` | ✔ INTEGRATED |
| **H4** | نافذة عرض سعري | بداية/نهاية + شريحة + سعر مخفّض لكل متغيّر | `sales` (BLOCKED) | ✘ قرار |
| **H5** | صفحة المتجر | بائع + كتالوجه + تقييمه | لا جذر؛ `pages`/`providers` | ~ جزئي |
| **H6** | متابعة متجر | علاقة مستخدم↔بائع + تغذية | لا جذر في 458 | ✘ لا مالك |

### 2.3 الانتقال

`MainTabsActivity` → (بحث/تفصيل) عبر مسار داخلي أو رابط عميق (`app.shein.com`, `sheinlink`).

---

## 3. الرحلة 2 — البحث

### 3.1 الشاشات (OBSERVED)

| # | الشاشة | المكوّن المقيس | المسار | حدث المستخدم |
| --- | --- | --- | --- | --- |
| 2.1 | مدخل البحث | `SearchHomeDynamicActivity` | `/search/search_home` | يفتح البحث (سجل/رائج/مقترحات) |
| 2.2 | النتائج | `SearchListActivityV2` | `/search/search_result` | كتابة استعلام + تنفيذ |
| 2.3 | نتائج فرعية | `SearchGoodsListActivity` | `/search/search_result` | تصفية/ترتيب/تمرير |
| 2.4 | بحث المبيعات | `SalesSearchHomeActivity` · `SalesSearchResultActivity` | — | بحث داخل الأوتلت |
| 2.5 | كاميرا | `com.shein.si_visual_search.picsearch.CameraActivity` | `/search/camera` | تصوير منتج |
| 2.6 | نتائج الصورة | `SearchImageResultActivity` | `/search/search_image` | مطابقة بصرية |
| 2.7 | إضافة من البحث | `AddItemsSearchSuggestionRepository` (داخل السلة) | — | بحث داخل «أضف منتجات» |

**ملاحظة مقيسة:** وجود مسارين منفصلين للبحث (`si_search` و`si_sales.search`) يعني **فهرسين مختلفين** لا فهرسًا واحدًا بفلتر.

### 3.2 نقاط الاتصال المفترضة (INFERRED)

| # | العملية | الحقول/السلوك | عقد Saleor | حالتنا |
| --- | --- | --- | --- | --- |
| **S1** | اقتراحات/رائج/سجل | استعلام بادئة + سجل محلي + رائج بالموقع | لا جذر | ~ `SavedSearchService` وحدة غير موصولة بالعقد |
| **S2** | بحث كتالوج | استعلام + ترقيم + ترتيب + مجالات (نطاق سعري/لون/مقاس/توفّر) + عدّادات | `products(search:)` | ~ `SearchService`/`UnifiedSearchService` موجودان، **لا `SearchDataFetcher`** |
| **S3** | بحث أوتلت | فهرس منفصل + نفس واجهة النتائج | `sales` (BLOCKED) | ✘ قرار |
| **S4** | بحث بصري | رفع صورة → متجه → نتائج + ثقة | لا جذر | ✘ `media` موجود، `ai` بلا خدمة مقيسة |
| **S5** | بحث داخل السلة | اقتراحات لمنتجات قابلة للإضافة | لا جذر | ✘ |

### 3.3 الانتقال

نتيجة → تفصيل المنتج (`/goods_details/goods_details`). البحث البصري يعيد نفس شكل النتائج.

---

## 4. الرحلة 3 — تفصيل المنتج

### 4.1 الشاشات (OBSERVED)

| # | الشاشة | المكوّن المقيس | المسار | حدث المستخدم |
| --- | --- | --- | --- | --- |
| 3.1 | التفصيل | `com.zzkko.si_goods_detail.GoodsDetailActivity` (+`…ActivityPop`) | `/goods_details/goods_details` | عرض |
| 3.2 | المعرض | `GalleryActivity` | — | تكبير صورة/فيديو |
| 3.3 | تغذية فيديو | `VideoFeedActivity` | `/video/video_detail` | تمرير فيديو |
| 3.4 | التقييمات | `com.zzkko.si_review.ReviewListFragmentV1` + `StoreReviewListFragment` | `/review/goods_reviews_list` | فرز/تصفية/صور |
| 3.5 | شراء متعدد | `BatchBuyDialogActivity` | — | اختيار عدة متغيّرات |
| 3.6 | إضافة من الخارج | `OutsideAddBagSupportActivity` | — | إضافة سريعة |
| 3.7 | قسائم المنتج | `com.shein.coupon.*` (1661 فئة) | `/goods/coupon_for_goods_list` | التقاط قسيمة |

### 4.2 نقاط الاتصال المفترضة (INFERRED)

| # | العملية | الحقول/السلوك | عقد Saleor | حالتنا |
| --- | --- | --- | --- | --- |
| **D1** | قراءة منتج | وسائط + وصف + سمات + تقييم مجمّع + سياسات | `product` | ✔ INTEGRATED |
| **D2** | متغيّرات ومخزون | مصفوفة سمات + توفّر لكل متغيّر + حدّ شراء | `productVariants` | ✔ INTEGRATED · `availability` موجود |
| **D3** | السعر بعد الخصم | سعر أصلي + خصم + عملة + ضرائب متوقعة | سعر داخل `product` | ~ الحقول موجودة، لا محرّك عروض |
| **D4** | تقييمات + وسائطها | قائمة + إحصاءات + صور/فيديو + «مفيد» | لا جذر (منصّي) | ~ `ReviewsService` وحدة غير معلنة |
| **D5** | منتجات مشابهة | توصية بمعرّف المنتج | لا جذر | ~ `DiscoveryService` |
| **D6** | إضافة للسلة | متغيّر + كمية + سياق (من التفصيل) | `checkoutLinesAdd` | ✔ INTEGRATED |
| **D7** | شراء متعدد | عدة أسطر في عملية واحدة | `checkoutLinesAdd` | ✔ INTEGRATED |

---

## 5. الرحلة 4 — السلة

### 5.1 الشاشات (OBSERVED)

| # | الشاشة | المكوّن المقيس | المسار | حدث المستخدم |
| --- | --- | --- | --- | --- |
| 4.1 | السلة | `com.shein.cart.shoppingbag2.ui.ShoppingBagActivity2` | `/cart/shop_cart` · `/cart/shop_cart_tab` | عرض/تعديل |
| 4.2 | معرض السلة | `CartGalleryActivity` | — | تكبير |
| 4.3 | إضافات ترويجية | `PromotionAddOnActivity` | `/cart/common_add_items` | «أضف مقابل خصم» |
| 4.4 | عنوان مسبق | `com.shein.si_cart_platform.preaddress.ui.PreAddressActivity` | — | تقدير الشحن قبل الدفع |
| 4.5 | حوار إرجاع مجاني | `CartFreeReturnDialog` · `CartIsReturnHandler` | — | يرى وعد الإرجاع |

**مرشّحات مقيسة داخل السلة (فئات):** `CartOcpEligibilityApi` (أهلية الشراء بنقرة) · `UnpaidOrderApi` (حدّ الطلبات غير المدفوعة) · `CellNoReturnTipsData` + `SCNoReturnTipsConverter` (منتجات غير قابلة للإرجاع).

### 5.2 نقاط الاتصال المفترضة (INFERRED)

| # | العملية | الحقول/السلوك | عقد Saleor | حالتنا |
| --- | --- | --- | --- | --- |
| **C1** | قراءة السلة | أسطر + أسعار + إجماليات + خصم + شحن + ضريبة | `checkout` | ⚠ INTEGRATED لكن `CheckoutView` = `id/lines/quantity` فقط — **فجوة دلالية** |
| **C2** | تعديل/حذف سطر | كمية + إزالة + دمج | `checkoutLinesUpdate` · `checkoutLineDelete` | ✔ INTEGRATED |
| **C3** | معاينة قسيمة | خصم متوقّع + سبب الرفض | `checkoutAddPromoCode` | ✘ NOT_STARTED (القسيمة تُطبَّق عند `place` فقط) |
| **C4** | تقدير شحن | خيارات (قياسي/سريع/استلام) + سعر + تاريخ | `deliveryOptionsCalculate` | ✘ NOT_STARTED · `shippingZones` BLOCKED |
| **C5** | عنوان مسبق | عنوان → منطقة شحن | `checkoutShippingAddressUpdate` | ✘ NOT_STARTED |
| **C6** | فحص التوفّر/الحد | حدّ لكل سطر + سبب المنع | لا جذر (منصّي) | ~ `availability` موجود، لا سياسة حدّ |
| **C7** | سياسة الإرجاع | قابل/غير قابل للإرجاع + المدة | لا جذر | ~ `OrderReturnsService` موجود |
| **C8** | أهلية الدفع بنقرة | طريقة محفوظة صالحة + مبلغ | `paymentGatewayInitializeTokenization` | ✘ NOT_STARTED |

### 5.3 الانتقال

السلة → الدفع (`CheckoutV2Activity`)، أو الشراء بنقرة (`OneClickPayActivity`) بتخطي الشاشة.

---

## 6. الرحلة 5 — الدفع (Checkout)

### 6.1 الشاشات (OBSERVED)

| # | الشاشة | المكوّن المقيس | المسار | حدث المستخدم |
| --- | --- | --- | --- | --- |
| 5.1 | الدفع | `com.zzkko.business.new_checkout.CheckoutV2Activity` (+`CheckoutDialogActivity`) | — (أصلي) | مراجعة الطلب |
| 5.2 | قسائم الدفع | `CheckoutCouponActivity` · `com.zzkko.bussiness.checkout.CouponActivity` | — | اختيار قسيمة |
| 5.3 | عنوان | `AddressApi` (داخل `new_checkout.biz.address`) | — | اختيار/إضافة |
| 5.4 | استلام من متجر | `StoreRecommendApi` (`biz.shipping`) | — | اختيار نقطة استلام |
| 5.5 | تبديل الموقع | `SwitchMallApi` · `ISwitchMallApi` | — | تغيير السوق (US/EU/…) |
| 5.6 | بطاقة هدية | `GiftCardApi` (`biz.gift_card`) | — | إدخال رمز |
| 5.7 | شراء عرضي | `IncidentallyBuyApi` | — | إضافة سلعة عرضية |
| 5.8 | حدّ الطلبات غير المدفوعة | `UnpaidOrderApi` → `UnpaidOrderLimit` | — | يُمنع من إتمام طلب |

**مكوّنات الإرسال المقيسة:** `UniversalAddOrderApi` (إنشاء الطلب) · `GoodsListApi` (إعادة قراءة الأسطر) · `CheckoutAddWholeApi` · `ProductOpApi` (حوارات المنع) · `CheckoutPopupStrategyAPI` (حوارات تحفيزية).

### 6.2 نقاط الاتصال المفترضة (INFERRED)

| # | العملية | الحقول/السلوك | عقد Saleor | حالتنا |
| --- | --- | --- | --- | --- |
| **K1** | جلسة دفع | إنشاء/استرجاع + حالة + انتهاء صلاحية | `checkoutCreate` | ✘ NOT_STARTED (السلة هي الجلسة) |
| **K2** | عنوان الشحن | عنوان كامل + تحقّق + حفظ | `checkoutShippingAddressUpdate` | ✘ NOT_STARTED · عنوان `Order.shipToAddress` نصّي فقط |
| **K3** | طريقة التسليم | قياسي/سريع/استلام + سعر + نافذة | `checkoutDeliveryMethodUpdate` | ✘ NOT_STARTED |
| **K4** | قسيمة | إضافة/إزالة + إعادة حساب | `checkoutAddPromoCode` · `checkoutRemovePromoCode` | ✘ NOT_STARTED |
| **K5** | بطاقة هدية | رصيد + استهلاك جزئي | `giftCard` (BLOCKED) | ✘ لا صنف أصلًا |
| **K6** | ضريبة/رسوم | حساب حسب الوجهة | `taxes` (BLOCKED) | ~ `Order.taxAmountMinor` يُخزَّن، لا يُحسَب |
| **K7** | إنشاء الطلب | تقسيم بحسب البائع + تجميد سعر + حجز مخزون | `orderCreateFromCheckout` | ✘ NOT_STARTED · `place()` موجود داخليًا |
| **K8** | حدّ الطلبات غير المدفوعة | عدّ + منع + رسالة | لا جذر | ✘ |
| **K9** | تبديل السوق | عملة/لغة/مستودع/سعر | `channels` (BLOCKED) | ✘ |

### 6.3 الانتقال

`CheckoutV2Activity` → `CashierDeskActivity` (دفع) أو `OcbV2DialogActivity` (شراء بنقرة).

---

## 7. الرحلة 6 — الدفع النقدي (Cashier)

### 7.1 الشاشات (OBSERVED)

| # | الشاشة | المكوّن المقيس | حدث المستخدم |
| --- | --- | --- | --- |
| 6.1 | الكاشير | `com.zzkko.business.cashier_desk.CashierDeskActivity` | اختيار طريقة دفع |
| 6.2 | قسائم الكاشير | `CashierCouponActivity` | إضافة قسيمة بعد إنشاء الطلب |
| 6.3 | بطاقة | `com.zzkko.bussiness.payment.card.CardPaymentActivity` | إدخال بطاقة |
| 6.4 | 3DS | `com.braintreepayments.api.ThreeDSecureActivity` | تحدّي بنكي |
| 6.5 | BLIK | `com.zzkko.business.blik_payment.PaymentBLIKCodeActivity` | رمز من البنك |
| 6.6 | Cash App | `AfterPayCashAppHandleActivity` | عودة من تطبيق المحفظة |
| 6.7 | جمع معلومات | `CollectInfoActivity` | استكمال بيانات |
| 6.8 | نتيجة | `com.zzkko.bussiness.payresult.ocb.OcbPaySuccessActivity` · `PaymentTempLoadingActivity` | نجاح/انتظار |
| 6.9 | COD | `com.zzkko.bussiness.order.ui.OrderCodSureActivity` | تأكيد الدفع عند الاستلام |
| 6.10 | تقسيط | `ParceladoHomeActivity` · `ParceladoOtpActivity` · `ParceladoAllOutstandingActivity` · `ParceladoMonthBillDetailActivity` · `ParceladoInvoiceDetailActivity` · `ParceladoAllInvoicesActivity` · `ParceladoHistoryBillActivity` | تقسيط + فواتير |
| 6.11 | شراء بنقرة | `OneClickPayActivity` · `OcbCartDialogActivity` · `OcbV2DialogActivity` · `OcpActivity` | دفع محفوظ |

### 7.2 نقاط الاتصال المفترضة (INFERRED)

| # | العملية | الحقول/السلوك | عقد Saleor | حالتنا |
| --- | --- | --- | --- | --- |
| **P1** | تهيئة بوابة | قناة دفع + مبلغ + عملة + مفتاح idempotency | `paymentGatewayInitialize` | ✘ NOT_STARTED · `createOrderIntent` موجود |
| **P2** | معالجة الدفع | نيّة → تحدّي → نتيجة | `transactionProcess` | ~ `processIntent` + Stripe channel |
| **P3** | تأكيد/فشل | webhook موقّع + صندوق وارد + إعادة تسليم | `transactionEventReport` | ✔ `processWebhookEvent` + inbox |
| **P4** | استرداد | كامل/جزئي + سبب + idempotency | `paymentRefund` · `orderRefund` | ~ `refundPayment(amount)` داخلي |
| **P5** | COD | تأكيد + تسوية عند التسليم | لا جذر | ✘ |
| **P6** | تقسيط | خطة + OTP + جدول فواتير + تعثّر | لا جذر | ✘ `marketplace-lending` **ليس** BNPL (مقيس: إعارة أصناف ببدل يومي + تأمين) |
| **P7** | طريقة محفوظة | رمز + إبطال + إعادة استخدام | `storedPaymentMethodRequestDelete` · `paymentMethodProcessTokenization` | ✘ NOT_STARTED |

### 7.3 الانتقال

نجاح → `OrderDetailActivity` أو `OrderDetailCashierActivity`. فشل → إعادة محاولة/تغيير طريقة.

---

## 8. الرحلة 7 — الطلب

### 8.1 الشاشات (OBSERVED)

| # | الشاشة | المكوّن المقيس | المسار | حدث المستخدم |
| --- | --- | --- | --- | --- |
| 7.1 | القائمة | `com.zzkko.bussiness.order.ui.OrderListActivity` | `/order/order_list` | تصفية بالحالة |
| 7.2 | البحث | `OrderSearchActivity` | — | بحث بمعرّف/منتج |
| 7.3 | التفصيل | `OrderDetailActivity` | `/order/order_detail` | عرض |
| 7.4 | تتبّع الشحنة | `OrderDetailPkgTrackDelegate` · `OrderDetailLogisticsInfoDelegate` | — | تتبّع |
| 7.5 | دفع غير مكتمل | `OrderDetailUnPaidPaymentMethodDelegate` · `UniversalOrderDetailApi.requestSwitchPaymentAuto` | — | «ادفع الآن» / تغيير الطريقة |
| 7.6 | دفع الطلب | `OrderDetailCashierActivity` | — | إعادة دفع |
| 7.7 | تفصيل الدفع | `OrderPaymentDetailActivity` | — | مراجعة المعاملة |
| 7.8 | استلام مؤكَّد | `BatchConfirmDeliveryRequest` (فئة طلب) | — | «استلمت» |
| 7.9 | نتيجة/توصية | `ResultRecommendActivity` | — | بعد إتمام الطلب |

### 8.2 نقاط الاتصال المفترضة (INFERRED)

| # | العملية | الحقول/السلوك | عقد Saleor | حالتنا |
| --- | --- | --- | --- | --- |
| **O1** | قائمة الطلبات | ترقيم + حالة + مجاميع | `orders` | ✔ INTEGRATED |
| **O2** | تفصيل الطلب | أسطر + دفع + شحن + عنوان | `order` | ✔ INTEGRATED |
| **O3** | تتبّع | حالة الشحنة + نقاط + ETA | `orderFulfillmentUpdateTracking` | ✘ NOT_STARTED · `delivery` يملك التتبّع |
| **O4** | دفع لاحق | نيّة جديدة لطلب قائم + تغيير الطريقة | لا جذر (منصّي) | ~ `requestPaymentIntent` موجود |
| **O5** | تأكيد الاستلام | تأكيد الدفعة/الطلب | `orderFulfillmentApprove` | ✘ NOT_STARTED |
| **O6** | إلغاء | سبب + نافذة مسموحة | `orderCancel` | ✔ INTEGRATED |
| **O7** | فاتورة | طلب إصدار + تنزيل | `invoiceRequest` | ✘ NOT_STARTED |
| **O8** | تقييم بعد التسليم | ربط شراء↔تقييم (شراء موثّق) | لا جذر | ~ `ReviewsService.createOrganic` |

### 8.3 حالة الطلب — الفرق المقيس

| عند SHEIN (مستنتج من الشاشات) | عندنا (`OrderStatus` المقيس) |
| --- | --- |
| غير مدفوع → مدفوع → قيد التجهيز → مشحون → تم التسليم → مغلق | `PLACED` · `CONFIRMED` · `FULFILLED` · `CANCELLED` |

**الفجوة:** لا وجود لـ«مشحون» ولا «تم التسليم» في `OrderStatus`؛ التتبّع يعيش في `marketplace-delivery`. أي رحلة طلب كاملة تحتاج **جسرًا صريحًا** بين حالتي الطلب والشحنة، لا حالة سادسة مرتجلة.

---

## 9. الرحلة 8 — الإرجاع والاستبدال

### 9.1 الشاشات (OBSERVED)

| # | الشاشة | المكوّن المقيس | حدث المستخدم |
| --- | --- | --- | --- |
| 8.1 | مدخل الإرجاع | `com.zzkko.bussiness.order.domain.order.ReturnOrderEntranceInfo` (داخل التفصيل) | «إرجاع/استبدال» |
| 8.2 | قائمة الإرجاع | `com.zzkko.si_exchange.business.exchange.list.ExchangeListActivity` | متابعة حالة |
| 8.3 | بحث الإرجاع | `ExchangeSearchActivity` | بحث |
| 8.4 | اختيار أصناف الاستبدال | `ExchangeOrderGoodsListActivity` | اختيار بديل |
| 8.5 | تفصيل الاسترداد | `OrderRefundDetailBean` · `OrderReturnDetail` | مراجعة المبلغ |
| 8.6 | كوبون الإرجاع | `com.shein.coupon.domain.OrderReturnCouponBean` · `ReturnCouponView` | استرداد كقسيمة |

**ملاحظة:** لا يوجد نشاط إرجاع مستقل مسمّى `ReturnActivity`؛ المدخل داخل تفصيل الطلب، والقائمة في وحدة `si_exchange`. أي أن «الإرجاع» يُعرض **كسجلّ استبدال** في هذا الإصدار.

### 9.2 نقاط الاتصال المفترضة (INFERRED)

| # | العملية | الحقول/السلوك | عقد Saleor | حالتنا |
| --- | --- | --- | --- | --- |
| **R1** | طلب إرجاع | سبب + أسطر + كميات + وسائط | `orderGrantRefundCreate` · `orderFulfillmentReturnProducts` | ✔ `OrderReturnsService.request` (RETURN/EXCHANGE) |
| **R2** | موافقة/رفض | قرار + ملاحظة + إشعار | `orderFulfillmentReturnProducts` | ✔ `approve`/`reject` |
| **R3** | استلام | فحص + إعادة مخزون | لا جذر مباشر | ✔ `receive` + `restockLines` |
| **R4** | إتمام + استرداد | مبلغ + بوابة + قيد | `orderRefund` · `paymentRefund` | ⚠ `complete` + `refundPayment` داخليًا، **لا جذر معلن** |
| **R5** | استبدال | طلب بديل مرتبط | لا جذر | ✔ `replacementOrderId` |
| **R6** | استرداد كقسيمة | قيمة + صلاحية | `voucherCreate` | ~ يدوي؛ لا ربط آلي |
| **R7** | ملصق/شحن عكسي | ملصق + تتبّع | لا جذر | ✘ |

---

## 10. مصفوفة نقاط الاتصال الفريدة (57 نقطة)

| المجموعة | العدد | موجود ومعلن | موجود غير معلن | عقد فقط (P4) | قرار (BLOCKED) | نطاق جديد |
| --- | --- | --- | --- | --- | --- | --- |
| Home (H1–H6) | 6 | 3 | 1 | 0 | 1 | 1 |
| Search (S1–S5) | 5 | 0 | 2 | 0 | 1 | 2 |
| Detail (D1–D7) | 7 | 5 | 2 | 0 | 0 | 0 |
| Cart (C1–C8) | 8 | 2 | 2 | 3 | 1 | 0 |
| Checkout (K1–K9) | 9 | 0 | 0 | 5 | 3 | 1 |
| Pay (P1–P7) | 7 | 0 | 2 | 3 | 0 | 2 |
| Order (O1–O8) | 8 | 3 | 2 | 2 | 0 | 1 |
| Return (R1–R7) | 7 | 4 | 2 | 0 | 0 | 1 |
| **الإجمالي** | **57** | **17** | **13** | **13** | **6** | **8** |

**معيار التصنيف:** «معلن» = العملية لها جذر معلن **وتنفيذ مقيس**؛ «غير معلن» = تنفيذ مقيس بلا جذر؛ «عقد فقط» = جذر غير محجوب بلا تنفيذ؛ «قرار» = جذر `BLOCKED_BY_DECISION`؛ «نطاق جديد» = لا صنف بيانات مقيس.

**القراءة الحاسمة:** الكتلة القابلة للتنفيذ فورًا هي **13 نقطة «عقد فقط»** (الدفع والكاشير أساسًا) — لا وحدة جديدة ولا جدول. والكتلة الثانية **13 نقطة «موجودة غير معلنة»** (بحث، تقييمات، استرداد، دفع لاحق، مخزون) — كود يعمل بلا عقد، وهو أرخص مكسب بعد P4. أما **8 نقاط النطاق الجديد** و**6 نقاط القرار** فلا تبدأ بلا مالك.

---

## 11. تغطية الأدلة — ما يثبته التحليل وما لا يثبته

| الادعاء | الحالة | الدليل |
| --- | --- | --- |
| الشاشات المذكورة موجودة في الحزمة | ✔ مقيس | `AndroidManifest` (156 نشاطًا) |
| المسارات الهجينة المذكورة مسموحة | ✔ مقيس | `assets/route_rule.json` (44 فريدًا) |
| ترتيب الشاشات داخل كل رحلة | ⚠ استنتاج | أسماء المكوّنات + الحزم؛ لا تسجيل تنقّل |
| نقاط الاتصال الخلفية (المسار/الحقول) | ✘ مفترض | لا اعتراض شبكة؛ لا ادعاء مسار |
| قائمة الأمنيات والنقاط تُقدَّم H5 | ✔ مقيس | مسارات فقط، بلا نشاط أصلي (`/wish/*`, `/point/check_in`) |
| شاشة الإرجاع نشاط مستقل | ✘ منفي | لا `ReturnActivity`؛ المدخل داخل تفصيل الطلب |
| التقسيط = `marketplace-lending` | ✘ منفي | `LendingService` = إعارة أصناف (بدل يومي + تأمين)، لا أقساط |

---

## 12. ما لا يقوله هذا الملف

- لا يقول إن SHEIN تستدعي GraphQL؛ واجهتها الخلفية غير معروفة (REST/gRPC/غيره) — كل نقاط الاتصال **مجردة**.
- لا يقول إن ترتيب الرحلة خطّي دائمًا: `OneClickPayActivity` و`OcbCartDialogActivity` يتخطّيان السلة والدفع.
- لا يحكم على جودة أي شاشة؛ ولا يوثّق سلوك وقت تشغيل.
- لا يقرّر أي نطاق جديد يُبنى؛ التصنيف في §10 اقتراح إسقاط يُعرض على المالك في `backend-action-items.md`.
