# دراسة UI/UX شاملة — Nextdoor 5.34.1 (Android)

**الهدف (الأرتيفاكت):** `base.apk`
**SHA-256:** `7cb7ae84815cf399c0070354aaafb97f02429eb2785f93273b40e1c888adeb12`
**الإصدار:** 5.34.1 · **معرّف الحزمة:** `com.nextdoor` · **البنية المتقدّمة:** `android:versionCode` 1254037 (نموذج التوزيع: APKM متعدد السبلِتات، arm64-v8a)
**امتداد النطاق:** end to end — كل تبويبات المستوى الأعلى + الرحلات الأساسية + نظام التصميم
**طبيعة الأدلة:** ساكنة (فك موارد + تحليل DEX)، وجلسة تشغيل حيّة *مؤجَّلة* (لا جهاز موصول)
**تاريخ التحليل:** 2026-10-16

---

## 0. المنهجية والمصادر

| المصدر | الأداة/الطريقة | المخرَج |
|---|---|---|
| الموارد (ألوان/مسافات/أنماط/تخطيطات) | `apktool 3.0.3 d --no-src` | 569 لوناً · 38 تجاوز ليلي · 901 dimen · 1132 نمطاً · 367 layout · 674 drawable · 191 color-state-list · 10 خطوط |
| الشيفرة (أصناف/سلاسل/طرق) | محلّل DEX مخصّص بلغة Python (بديل عن jadx بعد مهلة MCP) | **98,520 صنفاً فريداً** · 295,433 سلسلة · 14.1MB حقول · 33.6MB طرق |
| كشف Compose | وسيط `Landroidx/compose/...` في توقيعات الطرق | **5,587 دالة `@Composable`** |
| المانيفست | قراءة `AndroidManifest.xml` المفكوك | 107 Activities خاصة بـ Nextdoor · 30 Fragment · 21 ViewModel |
| التنقّل/IA | طباعة حقول أصناف الموديلات + ثوابت الـenums | NavMenuItemId(36) · Tab(7) · NavItemType(11) · NavScreen(43) · SearchTabValues(8) |
| تسميات الواجهة | `strings.xml` (base، إنجليزي) | تسميات التبويبات + حالات الفراغ/الخطأ |

> **حدّ جوهري:** لا جهاز/محاكي موصول (`adb devices` = فارغ)، فلم تُجرَ جلسة بصرية حيّة (لقطات/تفاعل/a11y حقيقي). كل الاستنتاجات البصرية مستنبطة من الموارد والشيفرة. ترتيب الشريط السفلي الفعلي حقل **مُدار بالخادم** ولا يمكن الجزم به ساكناً.

---

## 1. الخلاصة التنفيذية

- **المعمارية هجينة بنمط "Compose كطبقة كروم داخل شاشات Views":** الشاشة الرئيسية (`main_news_feed.xml`) هي `DrawerLayout`+`CoordinatorLayout` بمحتوى Views، بينما **الشريط السفلي والعلوي والمرشّحات والدرج والبانر اللاصق** كلها `ComposeView`. هذه أنضج استراتيجية هجرة تزايدية: يُستبدل ما يلي النظام (chrome) أولاً دون مخاطرة على المحتوى.
- **نظام تصميم داخلي مُسمّى ومُنضبط:** «Blocks» بطبقات توكنات (أساسية → دلالية → ليلي) وخط علامة مخصّص (**Saans**)، وتطبيق عرض مدمج (`blocksdemo`) يكشف التوثيق الذاتي للفريق.
- **IA مُوجَّهة بالخادم:** عناصر الشريط السفلي لا تُثبَّت في APK بل تصل كموديل (`MainNavigationModel`/`NavigationItemModel` عبر Moshi)، ما يمنح التجريب (A/B) والتحكم بالميزات دون إصدار جديد — وثمنه تعقيد في الاختبار وعدم قابلية تحديد IA نهائياً دون شبكة.
- **مظهر داكن حقيقي** (`Theme.MaterialComponents.DayNight` + 38 تجاوزاً ليلياً كلها مختلفة)، **دعم RTL فعلي** (`supportsRtl=true`)، **نصوص جاهزة للترجمة** (اللغات الثماني في سبلِتات منفصلة).
- **أبرز مكامن الضعف المؤكَّدة:** (1) تباين كلمات الحالة/الأخطاء العامّة المتكرّرة عبر الميزات (لكل ميزة صياغة مختلفة لنفس الخطأ)، (2) كثافة Sheets متداخلة في بعض المسارات (لوحات متتالية)، (3) `contentDescription` في الموارد قليل (60 ملفاً من 1,469) — يرجّح نقل دلالات الوصولية إلى شيفرة Compose/Views لا إلى XML، لكنه يستدعي تحقّقاً حيّاً.

---

## 2. نظام التصميم «Blocks»

### 2.1 الألوان (عمارة ثلاثية الطبقات)
**الطبقة الأساسية (Primitives):** سلالم رقمية متسقة —
`blue_0…blue_90`, `blue_gray_0…90`, `gray_0…90`, `green_*`, `orange_*`, `red_*`, `black_*`, `white_*`.

**الطبقة الدلالية (Semantic):**
- خلفيات: `blocks_bg_base`, `blocks_bg_primary`, `blocks_bg_elevated`, `blocks_bg_inlay`, `blocks_bg_underlay`, `blocks_bg_emphasis`, `blocks_bg_tint`, `blocks_bg_error`, `blocks_bg_warning`
- محتوى: `blocks_fg_primary`, `blocks_fg_secondary`, `blocks_fg_strong`, `blocks_fg_blue`, `blocks_fg_orange`, `blocks_fg_error`
- فوق الألوان: `blocks_on_fg_*`
- ثابتة (لا تتغير مع الثيم): `blocks_static_*`
- علامة: `blocks_brand_logo` = `green_40` (أخضر Nextdoor)
- خطأ: `blocks_error`

**الوضع الليلي** (`values-night/colors.xml`, 38 تجاوزاً، كلها مختلفة عن النهاري):

| التوكن | نهاري | ليلي |
|---|---|---|
| `blocks_bg_base` | (فاتح) | `gray_90` |
| `blocks_bg_primary` | (فاتح) | `gray_85` |
| `blocks_bg_elevated` | (فاتح) | `gray_80` |
| `blocks_bg_inlay` | (فاتح) | `gray_75` |
| `blocks_fg_primary` | (داكن) | `blue_gray_5` |
| `blocks_on_fg_strong` | — | `blue_gray_70` |

> **ملاحظة تصميمية:** تسلسل الارتفاع الليلي `base(gray_90) → primary(gray_85) → elevated(gray_80) → inlay(gray_75)` يتبع قاعدة Material «كلما ارتفعت الطبقة، قلّت العتمة» — اتساق صحيح.

**ألوان الحملات:** `treat_map_2025` = `#ff481e` (برتقالي Hallowe'en).

### 2.2 الطباعة — سلّم `TextAppearance.Nextdoor.Blocks.Text.*`
الخط الأساسي: **Saans** (`saans_static_regular` / `saans_static_medium`).

| النمط | الحجم | السِمة |
|---|---|---|
| Tiny | 11sp | عادي |
| Mini / MiniTitle | 13sp | عادي / متوسط |
| Detail / DetailTitle | 15sp | عادي / متوسط |
| DetailHighlight | 15sp | متوسط + ALL CAPS |
| Text (افتراضي) | 15sp | عادي |
| Body / BodyTitle | 17sp | عادي / متوسط |
| BodyHighlight | 17sp | متوسط + ALL CAPS |
| SubsectionTitle | 19sp | متوسط |
| BodyLarge | 21sp | عادي |
| SectionTitle | 22sp | متوسط |
| Headline | 28sp | **عريض** |
| Brand.Headline | 28sp | علامة |
| Shout | 34sp | **عريض** |
| Brand.Shout | 34sp | علامة |
| Brand.Folio | 42sp | علامة |

**خطوط أخرى مُضمَّنة (10):** Anton (عناوين صريخة)، Lato، Roboto Medium Numbers (أرقام جدولية)، google_sans_* (قابل للتنزيل).

> **صفة مميزة:** وجود طبقة «Brand» منفصلة (Folio/Shout/Headline/SectionTitle) بجانب الطبقة الوظيفية يكشف نية «نبرة العلامة» في لحظات منتقاة (NUX/حملات) دون تلويث السلّم العام.

### 2.3 المقاسات والأشكال (مفاتيح مختارة من 901)
- **تنقّل:** `bottom_navigation_height = 66dp`
- **هوامش/شبكة:** `startEndMargin = 24dp` · `constraintWidthMax = 500dp` (حدّ اتساع المحتوى — أساس صحيح للتابلت)
- **وصولية:** `min_touch_target_size_for_a11y = 48dp`
- **أزرار:** `buttonRadius = 4dp` · `_small = 8dp` · `_medium = 12dp` · `_round = 25dp` · ارتفاعات 36/48dp · `m3expressive_btn_min_height/width = 48dp`
- **أيقونات:** 20dp / 28dp
- **أشرطة علوية:** `toolbar_default_height = 56dp` · `toolbar_expanded_height = 100dp`
- **Stories/FCR:** `fcr_rounded_corner_radius = 8dp` · `fcr_story_padding = 16dp`
- **ملف شخصي:** `profile_photo_size_cee = 40dp`
- **NUX:** `nux_header_margin_left = 30dp` · `splashscreen_icon_*`
- **Material 3 Expressive:** `res/xml/m3expressive_button_shape_state_list.xml` (أشكال أزرار M3E — مكوّن أحدث من M3 الكلاسيكي)

### 2.4 المكوّنات (Blocks) — من الفهارس
- `buttons.Button` (Size · Type · Variant) · `pill.Pill` · `alerts.Toast` (Duration · ToastType)
- `textfields.TextInputEditText/TextInputLayout` · `pincodeinput.PincodeInput`
- `rollup.*` (RollupCardType · RollupEventHandler.ScrollDirection) · `widgets.cards.TaggedMentionCardVariant`
- `avatar/*` · `badges` · `lists` · `media` · `photogallery` · `reactions` · `tabs` · `video` · `screen`
- موديلات المكوّنات: `AvatarSizeModel`, `AvatarCornerTypeModel`, `AvatarBorderTypeModel`, `AvatarBadgeTypeModel`, `IconSizeModel`, `ImageSizeModel`, `FacepileSize/Spacing`, `CardVariant`, `ChipStyle`, `ChipVariant`, `TextFieldVariant`, `AlertVariant`, `BlocksTooltipArrowPositionEnum`, `ComposableNavigationActionEnum`, `ScreenLayout`
- `asset.AnimationKey` (Rive) · `asset.IllustrationKey`
- `theme.LinkType` (PRIMARY / SEE_MORE / SEO_PRIMARY / UNSTYLED)
- `widgets.nav.HideBottomNavOnScrollBehavior` (إخفاء الشريط السفلي عند التمرير)
- تطبيق مرجعي مدمج: `com.nextdoor.blocksdemo` (ComponentScreen / FoundationScreen / PlaygroundScreen / TopLevelScreen / WidgetScreen)

---

## 3. معمارية التنقّل و IA

### 3.1 الشريط السفلي **مُدار بالخادم**
البنية التقنية المؤكّدة (من `main_news_feed.xml` + أصناف الموديلات):
- `MainNavigationModel` (Parcelable) يحتوي قائمة `NavigationItemModel`
- `NavigationItemModel { NavItemType type; int iconRes }`
- فك الترميز عبر **Moshi** (`NavigationItemModelJsonAdapter`)
- الشريط نفسه `ComposeView` بمعرّف **`@id/compose_bottom_nav`** (وعلاقة وصولية `accessibilityTraversalAfter` من المحتوى إليه)، مع سلوك `HideBottomNavOnScrollBehavior`
- مقابله شريط تجاري: `BizNavScreen` + معرّف `bottom_nav_business`

### 3.2 أنواع عناصر التنقّل — `NavItemType` (11 نوعاً)
`FEED`, `NEWS`, `FAVES`, `FOR_SALE_AND_FREE`, `ADS`, `ALERTS`, `DASHBOARD`, `PAGE`, `SEARCH`, `TREATMAP`, `UNKNOWN`

### 3.3 معرّف عناصر القائمة — `NavMenuItemId` (36 عنصراً)
`FEED, NEWS, FAVES, FINDS, CLASSIFIEDS؟` ضمن القائمة: `ALERTS, BIZ_ADS, BIZ_DASHBOARD, BIZ_FEED, BIZ_INBOX, BIZ_NOTIFICATIONS, BIZ_PAGE, BIZ_POST, CLAIM_FLOW, DISCOVER, ENTITY_ADS, ENTITY_DASHBOARD, ENTITY_HOME, ENTITY_NOTIFICATIONS, ENTITY_OPPORTUNITIES, FAVES, FEED, FINDS, FSF_ADD_SAVED_SEARCH, FSF_SAVED_SEARCH, GROUPS, INVITES, MESSAGES, MODERATION, NEIGHBORHOOD_INTELLIGENCE, NEIGHBORHOOD_INTELLIGENCE_NEW_CHAT, NEWS, NOTIFICATIONS, POST, PROFILE, SEARCH_TAB, SETTINGS, SHARE, TREAT_MAP, BACK, menuId, resId`

### 3.4 التبويبات القابلة للترقية — `Tab` (7)
`FEED`, `NEWS`, `FAVES`, `CLASSIFIEDS`, `SEARCH`, `TREATMAP`, `NEIGHBORHOOD_INTELLIGENCE`

### 3.5 تسميات الواجهة (من `strings.xml`)
| نيّة العنصر | التسمية الظاهرة | المفتاح |
|---|---|---|
| التغذية | **Home** | `nav_home` / `home` |
| الأخبار | **News** | `nav_news` |
| المفضّلة المحلية | **Local Faves** | `nav_faves` / `faves` |
| السوق | **For Sale & Free** | `nav_for_sale_and_free_label_text` |
| البحث | **Search** / **Find** | `nav_search` / `nav_find` |
| الذكاء الحيّ (AI) | **Ask** | `nav_neighborhood_intelligence` |
| الاستكشاف | Discover section | `nav_discover_label` |
| المجموعات | Groups | `nav_groups` |
| الرسائل | Messages | `nav_messages` |
| التنبيهات | Alerts / Notifications | `nav_alerts` |
| النشر | Post | `nav_post` |
| الملف | Profile | `nav_profile` |
| الإعدادات | Settings | `nav_settings` |
| خريطة تصويت Hallowe'en | **Vote Map** | `nav_menu_vote_map` |
| تنبيهات الفرص | Opp Alerts | `nav_opp_alerts` |
| دعوة | Invite | `nav_invite` |
| بحث محفوظ | Saved Search | `nav_fsf_saved_search` |
| إضافة صفحة عمل | Add Business Page | `nav_menu_add_business_page` |

### 3.6 أكوان التبويب في البحث — `SearchTabValues` (8)
`ALL`, `BUSINESS`, `FINDS`, `LOCAL_EVENT`, `NEIGHBORHOODS`, `NEIGHBORS`, `POST`, `PAGE`

### 3.7 التنقّل العميق ورسوم الرسم البياني
- `NavDeeplinkManager$Screen` · `DeepLinkActivity` · `NavGraphKey { ROOT, BUSINESS }` (رسمان: مستهلك + تجاري) · `NavHostElevatorActivity` / `FeedNavHostElevatorActivity` (رفع ضيف Views→Navigation).
- **Entity Pages** (`com.nextdoor.entitypages`, 138 صنفاً) — تجريد يوحّد صفحات الأحياء/الأعمال/المجموعات/الإعلانات في «كيانات» وجهة واحدة.
- **نظام Composition** (`com.nextdoor.composition`, 68) — تركيب المنشورات/الأحداث.

---

## 4. جرد الشاشات والتدفقات

### 4.1 Activities الخاصة (107)
مرتّبة حسب الميزة (من المانيفست):

**الرئيسية/التغذية:** `MainFeedActivity`, `MainActivity`, `CustomFeedActivity`, `DetailFeedActivity`, `DetailFullscreenMediaActivity`, `HashtagFeedActivity`, `CameraActivity`, `SeasonalEventFullScreenTakeoverActivity`, `DeepLinkActivity`

**المنشور/التركيب:** `PostComposerActivity`, `PostComposerComposeActivity`, `EditPostCategoryActivity`, `ComposeReactionsActivity`, `MagicTextActivity` (مساعد كتابة AI)

**السوق (FSF):** `ClassifiedFeedActivity`, `ClassifiedDetailsComposeActivity`, `PostClassifiedComposeActivity`, `ClassifiedCategoryFilterActivity`, `ClassifiedDiscountConfirmationActivity`, `UserClassifiedListingsActivity`, `MarkBuyerActivity`, `SocialShareConfirmationActivity`, `FavesDetailActivity`, `UserFavesOutboundActivity`

**ابحث:** `SearchActivity`, `SearchHostActivity`, `SearchExploreActivity`, `SearchHistoryActivity`

**الأحداث:** `LocalEventFeedActivity`, `LocalEventDetailActivity`, `YourEventsFeedActivity`

**المجموعات/التواصل:** `GroupsDetailPageInfoActivity`, `SuggestedNeighborsActivity`, `ContactSyncActivity`, `InviteHubActivity`, `InviteNotificationsActivity`, `CommunitiesOnboardingActivity`, `BuildingGroupsOnboardingActivity`, `BuildingGroupsEmailInviteActivity`

**الملف:** `ViewProfileActivity`, `ActivityFeedForProfileActivity`, `UserActivity`, `BookmarkActivity`

**الرسائل:** `RTMChatInboxActivity`, `RTMConversationActivity`

**الإعدادات:** `SettingsActivity`, `PageSettingsActivity`, `FeedSettingsActivity`, `AppearanceSettingsActivity`, `NotificationSettingsActivity`, `LicensesActivity`

**الأعمال/الصفحات:** `AdsLeadGenerationFormActivity`, `EntityLandingActivity`, `EntityOnboardingActivity`, `LeadsInvitationActivity`, `LeadsV2MenuActivity`, `WelcomeMessage*Activity`, `NewNeighborsToolActivity`, `PageSettingsActivity`

**ثقة/أمان/إدارة:** `ReportActivity`, `ReportStatusActivity`, `ModerationToolActivity`, `ModerationAddNoteActivity`, `ModerationHistoryActivity`, `TransparencyActivity`, `GoodNeighborPledgeActivity`, `PhoneConfirmationActivity`, `OutdatedClientActivity`, `AppUpdateActivity`, `SelfServeSuspensionActivity`

**تسجيل/NUX:** `NuxActivity`, `NuxLandingScreenActivity`, `FoundingMemberActivity`, `ResetPasswordActivity`

**وصول/Faves:** `NeighborhoodFavesActivity`, `NeighborhoodFavesFeedActivity`, `NeighborhoodFavesOutboundActivity`, `FavesDetailActivity`

**متنوّع:** `DiscoverActivity`, `SurveyActivity`, `BestOfActivity`, `EducationActivity`, `AwardsActivity`, `PostInsightActivity`, `ViralityActivity`, `EmergencyContactsActivity`, `GeoTagDetailActivity`, `GeoTagPickerActivity`, `BrowseNeighborhoodsActivity`, `RecommendationCommentActivity`, `SearchBusinessForRecommendationActivityV2`, `GenericWebviewActivity`

### 4.2 اللوحات (Bottom Sheets) — مستخرجة من `ViewEffects`
جرد يوثّق التدفقات المُعطَّلة باللوحات عبر المنتج (عيّنة مؤكَّدة):

- **التغذية/التعليقات:** `ShowAskAiBottomSheet`, `ShowAskAiSourcesBottomSheet`, `ShowNextdoorRewindBottomSheet`, `ShowSuggestionContentBottomSheet`, `ShowTaggedCardRemovalConfirmationBottomSheet`, `Collapse/ExpandCommentBottomSheet`, `PinCommentBottomSheet`
- **النشر:** `ShowAddOnBottomSheet`, `ShowGroupsBottomSheet`, `ShowMultiSelectBottomSheet`, `ShowSuggestionContentBottomSheet`, `ShowLocationSearchSheet`
- **السوق:** `ItemOptionsBottomSheet`, `ItemDeleteConfirmationSheet`, `ShowLocationSharingPrivacyBottomSheet`, `ShowRadiusSheet` (نطاق Faves)
- **الأحداث:** `ShowAttendeesBottomSheet`, `ShowDateFilterBottomSheet`, `ShowDistanceFilterBottomSheet`, `ShowMyEventsFilterBottomSheet`, `InviteSheetSurface`
- **الصفحات/الأعمال:** `ShowCoverPhotoOptionsSheet`, `ShowLocationStatusBottomSheet`, `ShowOperatingHoursSheet`, `ShowFavesEducationSheet`, `ShowOpportunityAlertSubscriber*BottomSheet`, `ShowOpportunityAlertsSmsConsentBottomSheet`, `EditPhotoSheetSurface`, `ManageAccessAdmin/InviteBottomSheet`, `ShowRemoveUserConfirmationBottomSheet`, `ShowSignOutBottomSheet`
- **الملف:** `ShowPhotoBottomSheet`, `ShowDeletePhotoBottomSheet`, `ShowGenderBottomSheet`, `ShowPronounsBottomSheet`, `ShowHelpfulScoreBottomSheet`, `ShowModerationBottomSheet`, `ShowRoleEducationSheet`
- **التنبيهات:** `ShowNotificationActionsBottomSheet`, `ShowVirtualGiftBottomSheet`, `AlertSheet`, `ShowSpotlightBottomSheet`
- **اسأل/الذكاء الحيّ:** `ShowShareBottomSheet`, `ShowSourceBottomSheet`, `ShowThumbsDownFeedbackBottomSheet`
- **المشاركة (عالمية):** `OpenShareSheet` / `ShareSheetSurface` / `ShareSheetSurfaceV2` / `LaunchAndroidShareSheet`
- **تقييم Faves 2026:** `ShowNfVotePromptSheet`, `ShowWhatAreFavesSheet`, `ResultsAnnounceSheet`, `SmbFaveAwards*Sheet`
- **نجاح/تأكيد:** `SuccessSheetType`, `ShowCelebrationSheet`

> **ملاحظة UX:** تعدّد الـ`Sheet` بدل الشاشات الكاملة مقصود (تهدئة الاحتكاك داخل السياق)، لكن كثافة التتابع في بعض المسارات (مثل إدارة الصفحة) قد تُنتج «متاهة لوحات».

### 4.3 الرحلات الأساسية (End-to-End)

**أ) التغذية → ملف شخصي:** `MainFeedActivity` → بطاقة منشور → `DetailFeedActivity` → رد/تفاعل → `ViewProfileActivity` → `ActivityFeedForProfileActivity`.

**ب) النشر:** FAB Post → `PostComposerComposeActivity` → إضافة وسائط (`CameraActivity`/`CropActivity`) → جمهور (Sheet) → تصنيف (`EditPostCategoryActivity`) → نشر → تأكيد/مشاركة.

**ج) السوق (FSF):** تبويب For Sale & Free → `ClassifiedFeedActivity` → مرشّحات (`ClassifiedCategoryFilterActivity`) → `ClassifiedDetailsComposeActivity` (Compose) → محادثة بائع (RTM) → `MarkBuyerActivity`.

**د) الرسائل:** `RTMChatInboxActivity` → `RTMConversationActivity` → مشاركة/دعوة مجتمع (`ShowCommunityInviteBottomSheet`).

**هـ) اسأل (AI):** تبويب Ask → `NeighborhoodIntelligence` → سؤال → `ShowSourceBottomSheet` / `ShowThumbsDownFeedbackBottomSheet`.

**و) Faves 2026:** `NeighborhoodFavesActivity` → تصويت (`ShowNfVotePromptSheet`) → تصنيفات → `BestOfActivity`/`AwardsActivity`.

**ز) الإعدادات:** `SettingsActivity` → الفروع (`Appearance/Feed/Notification/Page/Licenses`)؛ `AppearanceSettingsActivity` يتحكم بالثيم/الوضع الداكن.

---

## 5. الحالات (States)

| الحالة | أمثلة نصوص مؤكّدة | المفتاح |
|---|---|---|
| **فراغ (تغذية)** | "No posts yet" | `feed_no_posts_yet`, `no_posts_yet` |
| **فراغ (مجموعات)** | "Conversations happen here" | `group_empty_state_title` |
| **فراغ (أحداث)** | "It's quiet around here" | `local_event_hub_empty_title` |
| **فراغ (Faves)** | "You have no saved faves." | `faves_detail_empty` |
| **فراغ (إشعارات)** | "All quiet in the neighborhood." | `notifications_empty_state_all_quiet_in_the_neighborhood` |
| **فراغ (مسودّات)** | "No drafts saved" | `drafts_hub_empty_title` |
| **خطأ شبكة** | "Sorry, there was a network error. Please try again later." | `network_error` |
| **خطأ عام** | "An error has occurred. Please try again." | `api_generic_error` |
| **خطأ اتصال** | "Trouble connecting to Nextdoor. Try again in a bit." | `error_trouble_connecting` |
| **خطأ خادم** | "The server is currently unavailable. Please try again later." | `server_unavailable` |
| **إعادة محاولة** | "An error occurred. Tap to retry" | `network_error_retry` |
| **خطأ تغذية/تعليقات** | "Failed to load comments. Try again?" | `failed_to_load_comments_retry` |
| **خطأ Faves Hub** | "Uh-oh — the Faves Hub didn't load" + "Try again, or head back to search." | `faves_hub_error_*` |
| **خطأ نشر** | "Sorry, there was an error creating your post." | `post_composer_failure_toast` |

> **مشكلة مؤكَّدة (اتساق المحتوى):** أكثر من 70 سلسلة خطأ مستقلة تصف نفس الحالة بصياغات مختلفة ("Please try again" / "try again" / "Try again in a bit" / "Something went wrong" / "Oh no…"). لا يوجد مكوّن خطأ موحّد مفرد مُسجَّل — فرصة توحيد واضحة.
> **عنصر تشخيصي:** `option_show_chaos_neighbor_description` = "Randomly return 400, 404, 500 errors" — محرّك فوضى داخلي للتطوير (مفيد للاختبار).

---

## 6. الوصولية (Accessibility)

| البند | الدليل | الحكم |
|---|---|---|
| دعم RTL | `android:supportsRtl="true"` | ✅ |
| Back التنبّؤي | `android:enableOnBackInvokedCallback="true"` | ✅ |
| هدف اللمس | `min_touch_target_size_for_a11y = 48dp` + `m3expressive_btn_min_* = 48dp` | ✅ |
| تسميات وصفية | نصوص `*_accessibility_label` متفرّقة (`awards_back_navigation_accessibility_label`, `post_insight_back_navigation_accessibility_label`, `claim_page_flow_address_search_accessibility`, `accessibility_text_field_clear`, `page_setup_checklist_progress_accessibility` بـ plurals) | ✅ جزئي |
| `contentDescription` في XML | **60 من 1,469 ملف XML** | ⚠️ يستدعي تحقّقاً حيّاً |
| عقد وسائط معنوي | عناية بترتيب التركيز (`importantForAccessibility`, `accessibilityTraversalAfter`) في `main_news_feed.xml` | ✅ |

> الندرة في XML مرجّحة لصالح نقل الدلالات إلى شيفرة Compose (semantics) والشيفرة التوليدية — وهو نمط شائع، لكن لا يمكن إغلاق الحكم دون شجرة وصولية حيّة.

---

## 7. التدويل والأجهزة (i18n / RTL)

- **اللغات:** base في الحزمة الأساسية إنجليزي فقط؛ **ثماني لغات** داخل سبلِتات `split_config.<lang>.apk` في ملف APKM ⇒ تحليل i18n يتطلب فك السبلِتات.
- **RTL:** مدعوم (`supportsRtl`)، و`values-ldrtl` موجود.
- **الخط:** Saans مخصّص؛ يلزم التحقّق من تغطية الرموز العربية في السبلِت العربي (لم يُفحص بعد).
- **الأجهزة:** `maxSdk`/قيود قديمة غائبة؛ `allowBackup=false` (إعداد أمني/خصوصية جيد)؛ `constraintWidthMax = 500dp` يقيّد اتساع المحتوى (مناسب للتابلت/الطيّات). لا `screenOrientation` مقفلة على الشاشات المدروسة (الوضع الأفقي مدعوم منطقياً).
- **Edge-to-edge:** وجود `compose_status_bar_background` كنقطة تحكّم بخلفية شريط الحالة ⇒ نهج يدوي لتصميم الحواف (يُنصح بالتحقق من سلوك Android 15+ الإلزامي حيّاً).

---

## 8. نقاط القوة

1. **نظام تصميم ناضج ومُوثَّق ذاتياً** («Blocks») بطبقات توكنات سليمة + خط علامة + تطبيق عرض مدمج.
2. **خطة هجرة Compose ذكية:** استبدال طبقة الكروم (top/bottom nav، مرشّحات، درج) أولاً — قيمة عالية/مخاطرة منخفضة، مع إبقاء المحتوى على Views.
3. **مظهر داكن جادّ** بإعادة تعيين دلالية كاملة (لا مجرد عكس ألوان).
4. **IA مرنة مُوجَّهة بالخادم** (تجريب/تخصيص دون إصدار) + تجريد «Entity Pages» موحّد.
5. **شمول وظيفي واسع** مع تجارب حديثة (اسأل AI، Rive animations، M3 Expressive، Faves Awards 2026، Treat Map).
6. **أساس وصولية جيد:** RTL، Back تنبّؤي، أهداف لمس 48dp، نصوص وصفية مخصّصة.

## 9. نقاط الضعف والفرص

1. **اتساق نصوص الأخطاء/الفراغ:** تنوّع صياغي كبير لنفس الحالة ⇒ يُقترح مكوّن `Blocks.EmptyState/ErrorState` موحّد + مصفوفة نبرة (Tone matrix) + تدقيق نصوص.
2. **كثافة اللوحات (Sheet fatigue):** تتابع لوحات في إدارة الصفحة/الجمهور ⇒ دمج أو تحويل بعضها لشاشات فرعية.
3. **قابلية الاختبار لـ IA مُدارة بالخادم:** يصعّب اختبارات UI الحتمية ⇒ يُقترح طبقة تجريد/مزروعات اختبار.
4. **الوصولية غير مُغلقة ساكناً:** `contentDescription` في XML قليل ⇒ يلزم فحص شجرة وصولية + تدقيق تباين على الألوان الدلالية.
5. **مخاطر توافق Compose/Views:** وسوم `visibility=gone` على ComposeView (top nav/filters) ⇒ احتمال وميض/إعادة قياس عند أول render — يستدعي قياساً حيّاً.
6. **i18n:** الاعتماد على سبلِتات يجعل تحقّق الترجمة/التشذيب (truncation) ضرورياً لكل لغة (خصوصاً الألمانية/العربية).

## 10. التوصيات (مُرتّبة بالأثر)

| # | التوصية | الأثر | الجهد |
|---|---|---|---|
| 1 | توحيد مكوّنات الحالات (فراغ/خطأ/تحميل) في Blocks + تدقيق نصوص | اتساق + سرعة تطوير | متوسط |
| 2 | تدقيق وصولية حيّ (TalkBack + شجرة دلالات + تباين AA على الليلي) | امتثال | متوسط |
| 3 | تقليل تتابع اللوحات في مسارات الإدارة/الجمهور | تقليل احتكاك | متوسط |
| 4 | مزروعات اختبار لـ IA المُدار بالخادم | استقرار اختبارات | منخفض |
| 5 | فحص i18n/RTL لكل Sprach (تشذيب + خط Saans عربي) | جودة عالمية | متوسط |
| 6 | التحقّق من edge-to-edge على Android 15+ | مظهر صحيح | منخفض |
| 7 | توحيد `contentDescription` حيث تبقى Views | وصولية | منخفض |

---

## 11. حدود الدراسة والأعمال المتبقّية

- **لا جلسة حيّة:** كل الاستنتاجات البصرية ساكنة. يجب — عند وصل جهاز (USB debugging + التطبيق مثبّتاً ومسجَّلاً) — تنفيذ: `screencap` لكل تبويب، شجرة وصولية، قياس التباين، التحقّق من ترتيب الشريط السفلي الفعلي، ومراقبة edge-to-edge.
- **IA النهائي غير قابل للجزم ساكناً:** ترتيب/ظهور العناصر يأتي من الخادم.
- **i18n:** لم تُفكّ سبلِتات اللغات الثماني (مطلوبة لتغطية ترجمات/RTL كاملة).
- **لم يُفكّ تفكيك دوال فردي:** اعتمدنا فهارس DEX؛ تفكيك دالة بعينها (smali) لم يُجرَ.
- **تفاصيل الحواف/الحركة (Rive):** الأصول موجودة (`asset.AnimationKey`)، لكن سلوك الحركة الزمني يحتاج مشاهدة حيّة.

---

### الملحق أ — مسارات الأدلة (Artifacts)
- فك apktool: `C:\Users\w-co\AppData\Local\Temp\opencode\nd5341_apktool\`
- معجم التوكنات: `tokens_colors.txt` · `tokens_colors_night.txt` · `tokens_dimens.txt` · `tokens_styles.txt`
- فهارس DEX: `dex_classes.txt` · `dex_strings.txt` · `dex_fields.txt` · `dex_methods.txt` · `dex_composables.txt` · `nd_classes.txt` · `screens.txt`
- مخرَج التنقّل: `C:\Users\w-co\.local\share\opencode\tool-output\tool_14391ccf8001N9kHVM04WIWfMc`

### الملحق ب — الحزمة (Packages) وأحجام الأدلة
`events.tracking` 26,750 · `apollo` 346 (GraphQL) · `entitypages` 138 · `newsfeed` 83 · `composition` 68 · `blocks` 68 · `groups` 66 · `network` 52 · `feedmodel` 47 · `model` 46 · `profile` 46 · `settings` 45 · `classifieds` 49 · `rtm` 34 · `popupprompt` 39 · `leadsnetworking` 40 · `registration` 33 + `registrationUi` 31 · `verification` 30 · `localalerts` 29 · `neighborhoodfaves` 27 + `campaign2026` · `ads` 27 · `search` 25 · `notificationcenterui` 24 · `nuxReskin` 23 · `moderation` 21 · `favesdirectory` 21 · `developersettings` 19 · `neighborhoodintelligence` 18 · `pushNotification` 18 · `discovermodels` 17

### الملحق ج — مجاميع التحليل
- أصناف فريدة: **98,520** (منها `com.nextdoor.*` 29,107)
- سلاسل فريدة: **295,433**
- دوال `@Composable`: **5,587**
- شاشات `*Screen`: 166 · Activities: 142 (107 Nextdoor) · Fragments: 30 · ViewModels: 21
- أنماط: 1,132 · ألوان: 569 (38 ليلي) · dimen: 901 · layouts: 367 · drawable: 674
