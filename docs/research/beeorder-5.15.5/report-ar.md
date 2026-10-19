# BeeOrder — إعادة بناء UI/UX end-to-end (تقرير من النسخة السليمة)
المصدر: `BeeOrder_5.15.5_APKPure.xapk` → base `com.beeorder.customer.apk` (sha256 `1043d571...`) + Dart AOT arm32 (`config.armeabi_v7a.apk`).
المنهج: استخراج حزمة رموز Dart من `_kDartIsolateSnapshotData` في `libapp.so` (47,131 مُعرِّف) + `en.json`/`ar.json` السليمة (1710 مفتاح لكل لغة) + جرد `assets/flutter_assets` (445 مدخلة) + المانيفست المفكوك بـ REA/apktool.

---

## 1. الهوية والتقنية
| البند | القيمة |
| --- | --- |
| الحزمة | `com.beeorder.customer` — label: **BeeOrder** |
| الإصدار | 5.15.5 (code 339) · minSdk 24 · target/compileSdk 36 |
| الإطار | Flutter (Impeller معطّل) · AOT arm32/arm64 · Dart 2.14 layout (kind 3) |
| حزمة Dart | `beeorder_mobile` — معمارية `features/` |
| OTA | **Shorebird** (`assets/flutter_assets/shorebird.yaml`) |
| توقيع التحديث | `AppUpdateReceiver` + `soft_update_v2.json` (تحديث ناعم) |
| التوقيع | BNDLTOOL · `usesCleartextTraffic=false` |
| الخطوط | **Rubik** (300/400/500/600/700/800/900) · `credit` = Credit-white.ttf · MaterialIcons |

## 2. خريطة التنقّل (Bottom Navigation)
6 تبويبات (من `en.json` + رموز الواجهة): `Home` · `Offers` (نص فعلي **"Happy Hours 🔥"**) · `Search` · `My Orders` · `Cart` · `Profile`.
عناصر مساندة: `HomeBottomTab`, `HomeScreenDisplayState`, `HomeSectionsEnum`, `Offers2`, `BigBanner`, `CardBannerPosition`, `HomeFeedV2`, `HomeFeedHeaderTheme`.

## 3. خريطة الشاشات والميزات (من أصناف Dart — 395 صنفاً فلترنا منها 150+ تطبيقياً)
| الميزة | مكوّنات مرصودة |
| --- | --- |
| **Auth** | `Auth`, `AuthMethod`, `Authenticate/Authentication/Authorization(Status)`, `LoginResponseModel`, `LoginVerifyBy`, `WelcomeAuthTag`, `LoginResponseModel` · أحداث: `auth_otp_requested`, `auth_otp_verified`, `auth_profile_completed`, `auth_consent_responded` · OTP عبر واتساب (`OTP_FORCED_WHATSAPP`) |
| **Onboarding** | `Introduce`, `OnboardingQuestionType`, `LocationFlowOutcome/Reason/Mode/Source/Permission/Step`, `OnboardingSurvey` → `SUBMIT_ONBOARDING_SURVEY`, `CURRENT_ONBOARDING_STEP` |
| **Home** | `Home`, `HomePage`, `HomeBottomTab`, `HomeFeedV2`, `HomeFeedHeaderTheme`, `HomeScreenDisplayState`, `HomeSectionsEnum`, `FlashSaleRestaurants` |
| **Search** | `Search`, `SearchPage`, `SearchBody`, `SearchViewModel`, `SearchFilterEnum`, `SearchSectionEnum`, `SearchType`, `SearchWeb`, `ItemSearch`, مفاتيح: `SEARCH_HISTORY`, `HOME_SEARCH_SUGGESTED_WORDS`, `FILTERS_RECENT_LIST` |
| **Restaurant / Dish** | `Restaurant(Model)`, `RestaurantsETA`, `RestaurantSchedule`, `Dish(Model)`, `Dishes`, `DishValidationKind`, `ServiceCartItemType` · أحداث: `dishPageReportTapped` |
| **Cart** | `Cart`, `CartRepository`, `CartStateAction`, `MyCarts`, `WalletsStore` · أحداث: `cart_state_changed`, `cart_sheet`, `add_cart`, `add_to_cart`, `addToCartBlocked`, `add_more_items`, `LOCAL_CARTS` |
| **Checkout / Payment** | `Checkout`, `CheckoutBlock`, `CheckoutEtaModel`, `CheckoutFlowType`, `CheckoutViewModelHost`, `Payment(Context/Type)`, `BeePay`, `Pay` · أحداث funnel: `checkoutScreenOpened`, `checkoutOrderSummaryOpened`, `checkoutSubmitTapped`, `checkout_calc_bill_skipped/failed`, `checkout_create_order_success`, `checkout_offline_order_completed`, `checkout_online_pay_success_opened`, `checkout_validation_failed` |
| **Orders / Tracking** | `Order`, `OrderState`, `OrderType`, `OrderEndingStatus`, `OrderStepModel`, `OrderTracking(Model)`, `OrderLog`, `GetOrderState`, `EditOrder(Status/EndReason)`, `UserOrderSegment` · `OrderTracking` خريطة حيّة عبر `DriverLocationSocketManager` + `AnimatedDriverMarker` |
| **Address** | `Address(Action)`, `AddressDetailsFormMode`, `AddressNamePreset` (home/work/other), `AddressSheetEntrySource`, `AddressUpdateResult`, `UserAddressStatus`, `LocationFlow*` · أحداث: `address_started`, `address_details_entered`, `address_pin_set`, `address_completed`, `address_vote_requested/success/failed`, `address_mismatch_shown/action` |
| **BeeBox** | `BeeBox`, `BeeBoxAddressPairIssue`, `BeeBoxCheckoutStoreBase` · flow: `beeboxCategorySelected`, `beeboxPickPointOnMapTapped`, `beeboxSubtotalSelected`, `beeboxPurchaseSelected`, `beeboxCheckoutViewed`, `beeboxPlaceOrderTapped`, `beeboxValidationFailed` |
| **BeePro (اشتراك)** | `BeeProSavingsModel`, `BeeProOrderSavingsModel`, `BeeProCheckoutConfirmResult` · `BEE_PRO_ACTIVATE`, `BEE_PRO_SAVINGS`, `PRO_DISH_OFFER`, `bee_pro_*` |
| **Subscription** | `Subscription`, `SubscriptionPlans`, `SubscriptionBenifitsModel`, `SubscriptionDetails/DataModel`, `SubscriptionPayModel`, `SubscriptionPaymentModel`, `SubscriptionProfileModel`, `SubscriptionStatusModel` |
| **Wallet / Loyalty** | `Wallet`, `WalletsStore`, `Loyalty`, `Loyalty(Tiers)(Page)`, `LoyaltyInfoModel`, `Token(Model)`, `TransactionTypeEnum`, `POINTS`, `REDEMPTION_HISTORY`, `ACHIEVEMENTS_LIST`, `TIERS_INFO` |
| **Vouchers / Games** | `VoucherListViewModelBase`, `CHECKOUT_VOUCHERS`, `VOUCHERS_LIST`, `VALIDATE_VOUCHER`, `SpinnerRewardType` · `Scratch`, `ScratchAccuracy` + Lottie `spin_wheel.json`, `scratching.json` |
| **Rating / Reviews** | `Rating`, `Rating2`, `RatingTarget`, `ReviewStarType`, `RestaurantReviewsViewModelBase` · `RATE_ORDER`, `RATES_OPTIONS_V2`, `RATE_PROMPT_ORDER_IDS` |
| **Profile** | `Profile`, `ProfileViewModelBase`, `UserInfoModel`, `UserPhoneNumberModel`, `UserSuggestViewModellBase`, `AppSettingsType`, `ThemeMode`, `FORCE_DARK_*` |
| **Support / Feedback** | `ReportIssueScreen`, `ReportIssueSelectionRoute`, `ReportRouteIssueStepsViewRoute`, `SupportTopic`, `IssueReasonModel`, `FaqCategoryModel/Scope`, `FaqItemModel`, `ContactUsViewModelBase` · `GET_FAQ_CATEGORY`, `GET_FAQ_ANSWER`, `SUBMIT_FEEDBACK`, `MODIFICATION_REQUESTS`, `GET_REASEN` |
| **Map / Geo** | `GoogleMap(Controller)`, `GoogleMapMarkerType`, `MapWidget`, `MapType`, `Cluster/PlatformCluster(Manager)`, `MapsObjectId/Updates`, `MapsApi/CallbackApi`, `AnimatedDriverMarker` |
| **Notifications** | `NotificationCenter`, `Notifications`, `NotificationListener`, `OrderLiveUpdatesManager`, `GroupAlertBehavior`, قنوات FCM + `flutter_local_notifications` · `MAKE_USER_NOTIFICATION_READ`, `UPDATE_FCM`, `GET_USER_NOTFICAITION` |
| **Group Order** | `GROUP_ORDER_LOCAT_CARTS`, `enjoy_group_order`, `how_to_create_group_order`, `or_share_your_code`, `INVITE_PAGE_CONTENT`, `INVITE_CODE_PENDING`, `APPLY_INVITE_CODE`, `LINK_ACTIVATED` |
| **Currency / Region** | `CurrencyViewModelBase`, `CURRENCIES`, `AVAILABLE_COUNTRIES`, `CITIES`, `CountryModel`, `CityModel`, `DIAL_CODE` |
| **Splash / Branding** | `Splash`, `SplashVersion`, `SPLASH_LOCAL_AR/EN`, `DECLINED_APP_ICON` + activity-alias `beeproIcon`/`ramadanIcon`/`defaultIcon` |

## 4. أنماط واجهة مشتركة (Design-system tokens)
- Bottom sheets/dialogs: `AppBottomSheetIdEnum`, `BottomSheetType`, `DialogType`, `DeepLinkSheet`, `LoadingWidgetEnum`, `PageTransitionType`, `PageMetadata`, `ConfettiControllerState`, `OverlayRouter`, `IdsToRemove`.
- Theme/colors (hex strings في الرموز): أساس أصفر برتقالي — `FFFF00`, `FF7A32`, `FFB020`, `FFB93B`, `FFD07C`, `FFC2A1`, `FFA676`, `FFE6D6`, `FFF2EB`, `FFF3E5`, `FFF8EB`, `FFE9C2`; رمادي/حدود — `E2E8F0`, `CBD5E1`, `EFF2F5`, `EFF3F7`, `F8FAFC`, `F6F8FB`, `E4E8EC`; خطأ — `F04438`, `F58344`, `F8A9A3`, `FEECEB`; خلفيات — `E7F5F6`, `E8A836`.

## 5. التدفّقات end-to-end (مُستخرجة من أسماء أحداث التحليلات — دليل سلوكي دقيق)
1. **Onboarding/Auth**: `splash` → `onboarding_*` → `auth_otp_requested` → `auth_otp_verified` → `auth_profile_completed` → Home.
2. **Address**: `address_started` → `address_details_entered` → `address_pin_set` → `address_completed` (مع voice note) → عند التنازع: `address_mismatch_shown` → `address_vote_requested` → `success/failed`.
3. **Discovery→Cart**: `home_list`, `CATEGORIES_LIST` → `RESTAURANT_MENU` → `DISHE_DETAIL` → `add_to_cart` → `cart_sheet` → `CHECKOUT`.
4. **Checkout**: `checkoutScreenOpened` → `checkoutOrderSummaryOpened` → `coupon_submitted` / `checkout_voucher_get_offer` → `CHECKOUT_ETA`/`CALC_ORDER` → `checkoutSubmitTapped` → online: `checkout_online_pay_success_opened` / offline: `checkout_offline_order_completed`.
5. **Tracking**: `ORDER_TRACKING` + بثّ حيّ `DEV/STG_ORDER_STATUS_SOCKET` + `DEV_DELIVERY_SOCKET` → `OrderLiveUpdatesManager` (إشعار "تحديث حيّ" بقناة `live_updates_order_tracking`).
6. **Post-order**: `rate_order` → `RATES_OPTIONS_V2` → `REDEMPTION_HISTORY` / `ACHIEVEMENTS_LIST` / `SUBSCRIPTION_*`.

## 6. الحالة المحلية والقنوات (من ثوابت التخزين/السوكِت)
- **SharedPreferences/cache keys**: `USER_INFO`, `USER_NETWORK_INFO`, `SAVED_ON_REFRESH_TOKEN`, `CACHED_BASE_URL`, `DEV_BASE_URL_OVERRIDE`, `LANG_LOCAL_AR/EN`, `LANG_UPDATE`, `SPLASH_VERSION`, `THEME`, `FORCE_DARK_ON/OFF/AUTO`, `IS_PRIVACY_POLICY_SHOWED`, `WALLET_FIRST_VISIT`, `GUEST_VISIT`, `BEEBOX_TAB_BADGE_SEEN`, `LITE_MODE_*` (BEHAVIORS/DETECTOR/MONITOR/NEW_DOT/PROBE/SWITCH), `LITE_MODE_DETECTOR_ENABLED`, `STORAGE_LAST_SWEEP_AT`, `STORAGE_RECLAIM_VERSION`, `IS_FIRST_APP`, `APP_VERSION_ON_LAST_LANG_VERSION`, `DECLINED_APP_ICON`, `INVITE_CODE_PENDING`, `RATE_PROMPT_ORDER_IDS`.
- **Sockets**: `DEV_ORDER_STATUS_SOCKET`, `STG_ORDER_STATUS_SOCKET`, `DEV_DELIVERY_SOCKET`, `CALL_SIGNALING` (+ override keys) — مُدارة عبر `OrderStatusSocketManager`/`GlobalOrderStatusSocketManager`/`DriverLocationSocketManager`.
- **Analytics/Remote config**: Mixpanel (`MIXPANEL_TOKEN`, `$lib_version`, `$modalRouteName`), AppsFlyer (`APPSFLYER_DEV_KEY`, `AF_*`, `af_*`), Microsoft Clarity (`CLARITY_API_KEY`, `Clarity_*`), OpenPanel (`OPENPANEL_API_URL/CLIENT_ID/CLIENT_SECRET`), GAID.

## 7. i18n
- `assets/flutter_assets/assets/lang/en.json` و`ar.json` — **1710 مفتاح لكل لغة** (JSON سليم 100%).
- 923 من مفاتيح en تظهر كمُعرِّفات في حزمة Dart (سلاسل مباشرة في الكود)؛ 787 تُحمَّل عبر نظام الترجمة.
- تصنيف المجالات (بالإنجليزية): general/other, order/tracking, orders/history, group_order/invite, support, checkout/payment, addresses, cart, wallet/points, onboarding/location, auth/otp, errors/validation, profile/settings, vouchers/games, home/feed, search, rating/reviews, store/menu, beebox/beepro.

## 8. الأصول (445 مدخلة flutter_assets)
- `assets/animations` (27 Lottie): `spin_wheel`, `scratching`, `Rating`/`Rating2`, `voucher_congrats`, `empty_carts`, `saved_carts`, `reorder`, `searching_captain`, `tracking`, `4_free_deliveries`, `discount_home`, `earn`, `invite_lottie`, `location`, `otp`, `zoomin`, `cannon_animation`/`sleepy_cannon_animation`, `arrow_down(_money)`, `Bee_HurryUp`, `soft_update_v2`, Ramadan: `day_/night_/default_ramadan_animation`, `android_splash`.
- `assets/images` (385): `icons` (175), الجذر (136), `mascots` (17), `beebox` (16), `logo` (14), `invite_how_to` (8), `flash_sale` (5), `jawaker` (4), `app_icons`/`backgrounds` (3+3), `rating`/`spinner` (2+2).
- `assets/fonts` (8) · `AssetManifest.bin`, `FontManifest.json`, `NOTICES.Z`, `NativeAssetsManifest.json`, `shaders/ink_sparkle.frag` + `stretch_effect.frag`.
- (ملاحظة: `.env.dev/.stg/.live` مُضمَّنة كأصول Flutter — راجع `D:\rea-beeorder\extracted\env_secrets.txt`.)

## 9. Deep links (من المانيفست)
`https://beeorder.com/app`, `https://www.beeorder.com/app`, `beeorder.onelink.me` (AppsFlyer OneLink), scheme `com.beeorder.app://open?screen=order_tracking&orderId=...`, `unilinks://beeorder.com/app`. `flutter_deeplinking_enabled=false`. assetlinks: `https://beeorder.com/.well-known/assetlinks.json`.

## 10. ملاحظات الجودة/المخاطر
- الملف الأصلي `BeeOrder_5.15.5.apks` تالف فيزيائياً (classes.dex string_ids + resources.arsc)؛ كل ما سبق مبني على الـXAPK السليم.
- حزمة رموز Dart تُظهر أنواعاً شاذة (`CountryModelr`, `Orderr`, `Sessionr`, `Chatr`) — أثر absorption/التقاء سلاسل في الجرد، لا أسماء فعلية؛ تُقرأ بالتقريب.
- لا أسماء مسارات مسجّلة صراحةً (Flutter named routes)؛ التنقّل يبدو Imperative (`Navigator.push` + `PageRouteBuilder` + `PageTransitionType`).
- الجرد التحليلي: 923/1710 مفتاحاً سلسلة مباشرة، والباقي عبر l10n.

## 11. الطبقة الأصلية (Android) — end to end
**MainActivity (3 قنوات مخصّصة):**
1. `com.beeorder.customer/live_updates_order_tracking` — واجهة **Android 16 Live Updates** للطلب الحيّ، بدوال: `getActiveOrderIds`, `showNotification`, `endNotification`, `endAllNotifications`. وسائط `showNotification`: `orderId, title, description, eta, currentStep, totalSteps, driverMobile, etaLabel, callCaptainLabel, defaultTitle, timeoutSeconds` (ProgressStyle notification بوسم `order_live_update`).
2. `instagram_share_channel` — مشاركة إلى Instagram.
3. `change_app_icon_channel` — تبديل أيقونة الإطلاق (Default / BeePro / Ramadan).

**Broadcast Receivers:**
- `OrderLiveUpdatePushReceiver` — يستقبل FCM data push (`type=order_live_update`, `order_id`, `event=end`, `is_order_ended`, `sent_at` للتصفية الزمنية، `title/subtitle/eta/current_step_index/total_steps/driver_mobile`) بشرط `enabled` + `allowed_<orderId>`.
- `OrderLiveUpdateDismissReceiver` — action `com.beeorder.customer.ORDER_LIVE_UPDATE_DISMISSED` → `dismissed_step_<orderId>` (مع مهلة سماح 60s).
- `AppUpdateReceiver` — عند `MY_PACKAGE_REPLACED` يُعيد تفعيل الأنشطة غير المرادفة (استعادة حالة تبديل الأيقونة بعد التحديث).

**حالة الإشعارات الحيّة:** SharedPreferences `order_live_updates` → `enabled`, `allowed_<orderId>`, `eta_label`, `call_captain_label`, `default_title`, `timeout_seconds`, `sent_at_<orderId>`, `dismissed_step_<orderId>`.

**إضافات/حزم أصلية مرصودة:** App Links (`com.llfbandit.app_links`)، تسجيل صوت (`com.llfbandit.record` → الملاحظات الصوتية)، ماسح باركود (`dev.steenbakker.mobile_scanner`)، `flutter.community.plus` (share/device_info/connectivity/network_info/package_info/android_intent)، `app_settings`، شارات الإشعارات (Samsung/Huawei/Honor/Sony)، Firebase (Messaging/Crashlytics/RemoteConfig/Analytics)، Play Integrity، Mixpanel، AppsFlyer، Google Maps، WebView (inappwebview)، image_picker، url_launcher، video_player + **AndroidX Media3 1.9.2**، shared_preferences، path_provider، open_filex، rootbeer، flutter_native_splash، flutter_local_notifications.

## 12. الملفات المرجعية (على D)
| الملف | المحتوى |
| --- | --- |
| `ui\dart_tokens.txt` | 47,131 مُعرِّف Dart (كامل الحوض) |
| `ui\dart_constants.txt` | 1702 ثابت SCREAMING (مفاتيح/سوكِت/تحليلات) |
| `ui\dart_app_classes.txt` | 395 صنف تطبيقي مصنّف |
| `ui\dart_classes_all.txt` | 458 صنفاً (كل المنتهية باللواحق المميزة) |
| `ui\dart_snake_in_en.txt` / `dart_snake_not_en.txt` | مفاتيح snake داخل/خارج i18n |
| `ui\en_keys_clean.txt` / `en_keys_in_dart.txt` / `en_keys_not_in_dart.txt` | مفاتيح i18n (1710) وتقاطعها مع الرموز |
| `ui\clean_lang\en.json` / `ar.json` | ملفات الترجمة السليمة |
| `ui\assets_clean.txt` | 445 مدخلة flutter_assets |
| `ui\animations\`, `ui\brand_*.webp` | Lottie وأيقونات العلامة |
| `tools\extract_dart_tokens.py`, `tools\analyze_tokens.py`, `tools\extract_assets.py`, `tools\build_reconstruction.py`, `tools\scan_jadx.py` | أدوات الاستخراج/التحليل/البناء |
| `ui\reconstruction\index.html` | **خريطة UI/UX تفاعلية end-to-end** (نظرة عامة/الشاشات/التدفقات/الطبقة الأصلية/الأصول) |
| `ui\reconstruction\assets\` | 420 أصلاً حقيقياً (385 صورة + 27 Lottie + 8 خطوط) + العلامة |
| `ui\reconstruction\asset_manifest.json` | جرد الأصول (rel/bytes/sha256/kind) |
| `clean\jadx\sources\`, `clean\jadx\resources\` | فكّ JADX للطبقة الأصلية (10,390 ملف Java) |
| `clean\jadx_summary.json` | ملخّص القنوات/الحزم/الروابط |
