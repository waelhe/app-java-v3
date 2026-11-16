# تقرير التدقيق الأمني — سطح الهجوم المصدَّر (Exported Attack Surface)

**التطبيق:** Nextdoor 5.34.1 (versionCode 1254037)
**التاريخ:** 2026-10-16
**المحلل:** تدقيق ثابت آلي (REA + jadx-headless-mcp 0.7.1) — **لم يُنفَّذ التطبيق إطلاقاً**

---

## 1) ملف الهدف والأساس المرجعي

| العنصر | القيمة |
|---|---|
| الحزمة المصدرية | `com.nextdoor_5.34.1-1254037_1arch_2dpi_8lang_56c1285e8915c2a1c0b48051bbba7e7e_apkmirror.com.apkm` |
| SHA-256 (.apkm) | `300402c56784d8943c5a126be788ca46fa86c319cbdfee7a459aa63c9db64e5f` |
| base.apk | SHA-256 `7cb7ae84815cf399c0070354aaafb97f02429eb2785f93273b40e1c888adeb12` |
| المحرك | jadx-headless-mcp 0.7.1 · jar `6e5eacf500b64292bfb73c49797c1958f6ee44646e43e868039ae7feb573ff75` · Java 21.0.9 |
| Bridge | rea-jadx-bridge `40c6972bc7e1689e09979b64ba49ba04b9c05e49976b838315d63d6415e762ba` |
| الإحصاءات | 79,609 صنفاً · 3,971 مورداً · minSdk 29 · targetSdk 36 |

---

## 2) الملخص التنفيذي

فُحص سطح الهجوم المصدَّر كاملاً: **198 مكوّناً، منها 20 مصدَّراً (5 محمية بصلاحيات نظامية)**. 

**الحصيلة الجوهرية: لم يُعثر على ثغرة intent-redirection أو إساءة استخدام مكوّنات قابلة للاستغلال المباشر.** التصميم الأمني للتوجيه العميق (DeepLinkActivity) سليم معمارياً: قائمة أنماط مسموحة → مُصنّع إجراءات مُقيّد → لا إطلاق لمكوّن/Class من الإضافات الواردة.

النتائج المتبقية **منخفضة الشدة** وتتركّز في: نظافة الإصدار (أدوات QA/تطوير مصدَّرة)، انحراف واحد عن قالب FCM الرسمي، وممارسات تسجيل/متانة.

أُغلقت **جولة الإكمال end-to-end** (القسم 5-ملحق) بنود «حدود التحليل» الرئيسية: سلسلة المصنع/المنفّذ لـDeepLink كاملة، قراءة المشاركة في PostComposer، دورة CustomTab، خدمة FCM، المستمعون، PreviewActivity، وعائلة trackingdemo الخمستها — **بلا أي تغيير في الحكم العام (PASS)**. رصدت الجولة ملاحظات إضافية منخفضة/INFO (F-11…F-15) وإغلاقاً موسَّعاً لـF-16.

| # | النتيجة | الشدة |
|---|---------|-------|
| F-01 | `NextdoorFirebaseMessagingService` مصدَّر بلا permission (انحراف عن قالب Google) | LOW |
| F-02 | تسجيل حمولة Push كاملة نصاً في اللوغ (PII/log-injection) | LOW |
| F-03 | `NumberFormatException` غير مُلتقط على حقلي `type`/`badge_count` في خدمة FCM | INFO (متانة) |
| F-04 | 5× `com.nextdoor.trackingdemo.*` مصدَّرة بلا فلاتر (أدوات QA في الإصدار) | LOW |
| F-05 | `androidx.compose.ui.tooling.PreviewActivity` مصدَّر في الإصدار | LOW (نظافة) |
| F-06 | ثنائية `com.facebook.CustomTabActivity`/`CustomTabMainActivity` مصدَّرة (نمط FB SDK) | LOW |
| F-07 | DeepLinkActivity — **نتيجة سلبية موثّقة** (لا redirection) | PASS |
| F-08 | EngageBroadcastReceiver — نمط Google Engage الرسمي | by-design |
| F-09 | 3× مستمعو إشعارات — بثوث محمية نظامياً | by-design |
| F-10 | Launcher alias + 5 مكوّنات محمية بصلاحيات | by-design |
| F-11 | extra `INTERNAL` في DeepLink قابل للضبط من الرابط (منطق أعمال فقط — لا سلطة) | INFO |
| F-12 | نسخ extras كاملة عند علم `routed_from_gcm_through_deeplink` (confused-deputy نظري مشروط بقناة FCM) | INFO |
| F-13 | `collapse_id` يُلغي إشعاراً (المستقبِل غير مصدَّر) | INFO |
| F-14 | `bf3.O()` يقرأ `getParcelable` بلا try/catch (BadParcelableException ذاتية) | INFO (متانة) |
| F-15 | مسار بريد DeepLink غير متزامن بلا مهل ظاهرة (يحتاج تأكيداً يدوياً) | INFO |
| F-16 | `PostComposerActivity` — إغلاق by-design موسَّع (انتقالات/اختصار/مشاركة عبر defaults) | PASS |

---

## 3) المنهجية والنطاق

1. تفريغ `AndroidManifest.xml` الكامل من base.apk (198 مكوّناً، جرد الصلاحيات والفلاتر).
2. تحديد المصدَّر العشرين وحالات الحماية (`permission=` / بثوث نظامية / نمط Google الموثّق).
3. فكّ وتتبع تدفق الإدخال الخارجي في كل مكوّن عبر jadx (دوال onCreate/onNewIntent/onReceive/onMessageReceived + سلاسل الـBase classes).
4. تصنيف الأدلة: كود مُتحقق منه ≠ استنتاج من أسماء مُشوّشة ≠ غير معروف.
5. قيود معلنة: تحليل ثابت فقط (لا تنفيذ)، لا تحقق حيّ من CVE (شبكة البحث محجوبة)، التشويش R8 يتطلب تتبعاً يدوياً للسلاسل.

---

## 4) سطح الهجوم المصدَّر — الجدول الكامل (20)

| # | المكوّن | النوع | الحماية | الاتجاه | الحكم |
|---|---------|-------|---------|---------|-------|
| 1 | `LoadAppConfigurationActivity` (alias → `MainFeedActivity`) | activity-alias | MAIN/LAUNCHER | — | قياسي |
| 2 | `com.nextdoor.activity.DeepLinkActivity` | activity (singleTask) | VIEW+BROWSABLE · 132 فلتراً · schemes: nextdoor/https/com.nextdoor · 21 host | مُدقَّق بالكامل | **PASS** |
| 3 | `com.nextdoor.composition.activity.PostComposerActivity` | activity | SEND/SEND_MULTIPLE+DEFAULT | مُدقَّق | by-design |
| 4 | `com.nextdoor.trackingdemo.TrackingDemoActivity` | activity | بلا فلاتر | مُدقَّق | LOW |
| 5 | `…trackingdemo.fragmentflow.FragmentFlowActivity` | activity | بلا فلاتر | عائلة مُشابهة | LOW |
| 6 | `…trackingdemo.composableflow.ComposeFlowActivity` | activity | بلا فلاتر | عائلة مُشابهة | LOW |
| 7 | `…trackingdemo.draggableview.DraggableViewDemoActivity` | activity | بلا فلاتر | عائلة مُشابهة | LOW |
| 8 | `…trackingdemo.list.ListDemoActivity` | activity | بلا فلاتر | عائلة مُشابهة | LOW |
| 9 | `com.nextdoor.pushNotification.NextdoorFirebaseMessagingService` | service | **بلا permission** ⚠️ | مُدقَّق بالكامل | F-01 |
| 10 | `…NotificationSettingsListener` | receiver | بث `APP_BLOCK_STATE_CHANGED` | محمي نظامياً | by-design |
| 11 | `…NotificationIndividualChannelSettingListener` | receiver | بث `NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED` | محمي نظامياً | by-design |
| 12 | `…NotificationGroupChannelSettingListener` | receiver | بث `NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED` | محمي نظامياً | by-design |
| 13 | `com.nextdoor.engage.EngageBroadcastReceiver` | receiver | `PUBLISH_RECOMMENDATION` | مُدقَّق | by-design |
| 14 | `com.facebook.CustomTabActivity` | activity | `fbconnect://cct.com.nextdoor` | مُدقَّق | F-06 |
| 15 | `com.facebook.CustomTabMainActivity` | activity | (غير مُفلتر — يُستدعى صريحاً) | مُدقَّق | F-06 |
| 16 | `androidx.compose.ui.tooling.PreviewActivity` | activity | بلا فلاتر | مكتبة قياسية | F-05 |
| 17 | `androidx.work.impl.background.systemjob.SystemJobService` | service | `BIND_JOB_SERVICE` | محمي | by-design |
| 18 | `androidx.work.impl.diagnostics.DiagnosticsReceiver` | receiver | `DUMP` | محمي | by-design |
| 19 | `com.google.firebase.iid.FirebaseInstanceIdReceiver` | receiver | `c2dm SEND` | محمي | by-design |
| 20 | `com.google.android.feedback.RevocationBoundService` + `ProfileInstallReceiver` | service+receiver | `DUMP` | محمي | by-design |

**الاتجاهات الإضافية:** `allowBackup=false` ✅ · الصلاحيات المخصصة كاملة: `com.nextdoor.permission.C2D_MESSAGE` (signature) و`com.nextdoor.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` (signature) ✅ · لا يوجد أي `provider` مصدَّر.

---

## 5) النتائج بالتفصيل

### F-01 — خدمة FCM مصدَّرة بلا صلاحية (LOW)

**الدليل:** `android:exported="true"` على `NextdoorFirebaseMessagingService` دون `permission` — خلافاً لقالب Firebase الرسمي (`exported="false"`).

**تحليل الاستغلال:** على الرغم من الانحراف، سطح الاستغلال العملي منخفض:
- دالة الاستقبال `c(jb20)` (= onMessageReceived بعد R8) لا تحتوي **أي sink**: فحص شامل أكد صفر `startActivity / PendingIntent / sendBroadcast / WebView / setData` — الدالة مجرد relay.
- لا يمكن لتطبيق خارجي "تزوير" رسالة FCM: الاستقبال يتطلب كائن `RemoteMessage` يصل حصراً عبر القناة الملزَمة مع Google Play Services، وليس عبر extras عادية.

**تدفق الرسالة:** نسخ الحمولة كاملة → `Bundle` → قراءة مفاتيح (`is_badge_update`, `badge_count`, `type`, `tracking_name`) → تسجيل تحليلات → بوابة `bc70.c(bundle)` → مصنع الإشعارات `swv.b0(type, bundle)` → العرض `w2x.f(...)`. معالجة deep-link/intent المحتملة تقع أسفل هذه السلسلة (خارج نطاق هذا الصنف).

**التوصية:** ضبط `android:exported="false"` مطابقةً للقالب الرسمي أعمالاً بمبدأ الدفاع بالعمق.

### F-02 — تسجيل الحمولة كاملة (LOW — خصوصية)

**الدليل:** `ghuVar2.a("push: … PAYLOAD EXTRACTED: " + bundle)` — الحمولة كاملة (بما فيها أي روابط/معرّفات) تُسجَّل نصاً في logcat، وقيم مثل `tracking_name` تدخل السجل بلا تعقيم (log-injection إن أمكن التحكم بالحمولة).

### F-03 — متانة: استثناء غير مُلتقط (INFO)

**الدليل:** `Integer.parseInt(bundle.getString("type", "0"))` و`Integer.parseInt(badge_count)` خارج أي try/catch — حمولة مشوّهة (تتطلب قدرة إرسال FCM) تحدث `NumberFormatException` تنفلت من الدالة. الإصلاح: تعقيم رقمي.

### F-04 — أدوات QA مصدَّرة (LOW — نظافة إصدار)

**الدليل:** نادي `com.nextdoor.trackingdemo.*` الخمسة مصدَّرة بلا فلاتر intent. `TrackingDemoActivity.onCreate` = `super` + تسجيل activity-result فقط (صفر حقول، 3 دوال) — شاشات عرض ثابتة، لا مدخلات مهاجم تصل إليها. الأثر: أي تطبيق يستطيع إطلاق الشاشات (سطح هجوم/ارتباك واجهة + انكشاف معلوماتي محتمل من محتوى العروض).

**التوصية:** الإزالة في الإصدار عبر `tools:node="remove"` أو حصرها ببناء debug.

### F-05 — PreviewActivity في الإصدار (LOW — نظافة)

مكوّن أدوات Compose القياسي: يقرأ اسم دالة composable مؤهَّلاً ويعرضها عبر reflection. لا تنفيذ كود عشوائي (دوال التطبيق نفسه فقط)، لكنه غير مرغوب في الإصدارات الإنتاجية. توصية: `tools:node="remove"`.

### F-06 — ثنائية Facebook CustomTab (LOW)

**الدليل المُتحقق منه:**
- `CustomTabActivity.onCreate`: intent صريح → `CustomTabMainActivity` مع تمرير `getIntent().getDataString()` في extra.
- `CustomTabMainActivity.onCreate`: **يبني URL داخلياً**: `facebook.com/dialog/<action>` أو `oauth/authorize` من ثوابت + قيم extras — **لا فتح لعنوان URL عشوائي من المهاجم**. Domain ثابت facebook.com؛ extras تؤثر على مقطع المسار فقط. تثبيت حزمة اختياري (extra `e`) — أثره محدود (يتطلب تطبيقاً مالكة مثبّتاً).

**الحكم:** نمط FB SDK القياسي ("hackbook")؛ لا open-redirect لعناوين خارجية.

### F-07 — DeepLinkActivity: نتيجة سلبية موثّقة (PASS)

**تدفق كامل مُتبع (بدليل كود):**
- **onCreate:** `setTheme` → `getReferrer()` → `V()` → تسجيل Activity Result.
- **`V()`:** `uri = getIntent().getData()` — إن `null/EMPTY` → `startActivity(MainFeed)` + `finish`. وإلا: فحص hosts البريد (`link.ms.email.nextdoor.com`, `links.itr.email.nextdoor.com`) → معالج غير متزامن؛ ثم `W()`.
- **`W()`:** قراءة extra `"INTERNAL"` (منطق أعمال فقط) → تحليلات → **فحص نمط `rgl0.e(uri, patterns)`** → إن تطابق: `deepLinkActionFactory.h(uri, internal, extras)` → إجراء مُصنَّف؛ وإلا fallback قياسي.
- **`T()`:** إن كان الإجراء قابلاً للتنفيذ → تنفيذ mid-extras؛ وإلا: `startActivities` لـ(1) intent صريح لـMainFeed + (2) **متصفح خارجي لنفس URL الوارد** (ACTION_VIEW + FLAG_ACTIVITY_NEW_TASK + REFERRER `android-app://nextdoor.com/` + البيانات عبر `dht.l()` لتنظيف URL).
- **onNewIntent:** `super` → سلسلة `bf3` تستدعي **`setIntent(intent)` مركزياً** (مُتحقق منه في `defpackage.bf3`) → `V()` يقرأ دائماً الـintent الجديد ✅ — الخلل الوظيفي الشائع (stale intent) **غير موجود**.

**لماذا لا توجد ثغرة redirection:** لا يُستخرج اسم مكوّن/Class من أي إضافة؛ لا WebView.loadUrl من مصدر خارجي مباشر (webviewNavigator يستقبل فقط بعد تطابق قائمة الأنماط الداخلية)؛ الإضافات تُمرَّر للمُصنّع حسب التصميم (تُقرأ بواسطة معالجات إجراءات محددة — الامتداد الداخلي أُغلق بالكامل في 5-ملحق: كل الintents الناتجة صريحة داخلية).

**ملاحظة دقة:** جسم `if` ظهرياً فارغ في حلقة `V()` — أثر jadx المرجّح (مع محاذير معلَنة من المحرك)، لا يغيّر الحكم.

### F-08 — EngageBroadcastReceiver (by-design)

**الدليل:** `onReceive` **لا يقرأ المسجَّل (Intent) إطلاقاً** — فحص null فقط، ثم بناء الناشر وتشغيل إعادة نشر غير متزامنة وتسجيل الحدث. البث `com.google.android.engage.action.PUBLISH_RECOMMENDATION` هو آلية تكامل Engage **الرسمية من Google** (يُرسله النظام ليطلب تحديث التوصيات). تطبيق طرف ثالث يستطيع إرساله (غير محمي على مستوى المنصة) — الأثر: تحفيز إعادة نشر فقط؛ لا يُستهلك أي مدخل من المرسل. LOW/INFO.

### F-09 — مستمعو الإشعارات الثلاثة (by-design)

تستمع لـ`APP_BLOCK_STATE_CHANGED` / `NOTIFICATION_CHANNEL_BLOCK_STATE_CHANGED` / `NOTIFICATION_CHANNEL_GROUP_BLOCK_STATE_CHANGED` — بثوث **محمية نظامياً** (لا يستطيع أي تطبيق طرف ثالث إرسالها)؛ التصدير إلزامي لاستقبالها. لا إجراء.

### F-10 — صفحة إيجابيات (Defenses observed)

- لا providers مصدَّرة إطلاقاً.
- صلاحيتان مخصصتان بمستوى signature فقط.
- `allowBackup=false`.
- مكوّنات ويب داخلية (`GenericWebviewActivity`) **غير مصدَّرة**.
- التوجيه العميق مبني على allowlist + مُصنّع مُصنّف (نمط best-practice).

---

## 5-ملحق) جولة الإكمال End-to-End — إغلاق بنود Residual (2026-10-16)

جولة ثانية أكملت تتبع السلاسل حتى نقاط النهاية وأغلقت «حدود التحليل». معرّفات الأدلة الكاملة في القسم 8.

### F-07-مكمل — سلسلة المصنع/المنفّذ كاملة (تدعيم PASS)

- **السلسلة مُتتبَّعة كاملة:** `W()` → `o95.h(uri, internal, extras)` → مصنع `v1c` → `d2c.a` (تصنيف الإجراء) → `o95.q` (المنفّذ) → `e2c.invoke` → `o95.s` (الإجراء الفعلي). كل الintents المُنتَجة داخل السلسلة **صريحة داخلية**؛ لا يُستخرج اسم مكوّن/Class من النص الوارد.
- **حاجز دفاعي مؤكد:** `v1c.a` يرمي `"I am an invalid action, do not execute me!"` — الإجراء العاري `y2p` غير قابل للتنفيذ مطلقاً.
- **مسار القرار:** تحقق `ui2.i()` → `T()` أو coroutine (`qj8`)؛ خلافه `H.d()` ثم `T()`؛ وجود `auto_token` → `zqq.d(..., DEEP_LINK_AUTO_SIGN_IN, ...)` + `finish`؛ بلا توكن → intent دخول (`redirect=sign_in`).
- **معالجة الأخطاء:** خطأ مسار → تقرير Sentry (`deep_link_route_handle_error`) بلا انهيار؛ لوج `String.format("Bad deep link uri : %s", uri)`.

### F-11 — extra `INTERNAL` قابل للضبط عبر الرابط (INFO)

`W()` يقرأ extra باسم `INTERNAL` ويغذّي به منطق أعمال/تحليلات. لا يمنح سلطة أمنية ولا يقود sink، لكن يجب اعتباره مدخلاً غير موثوق (أي طرف يستطيع إرسال الرابط مع extra).

### F-12 — نسخ extras كاملة عند علم `routed_from_gcm_through_deeplink` (INFO)

في `e2c.invoke`: عند وجود هذا المفتاح تُنسخ الإضافات الواردة كاملة ويُضاف علم `FROM_GCM_NOTIFICATION` — نمط confused-deputy **نظري** مشروط أصلاً بقدرة إرسال FCM (مقيَّدة بقناة GMS). لا استغلال عملي ثابت.

### F-13 — `collapse_id` ↔ إلغاء إشعار (INFO)

مسار إجراءات الإشعار يقرأ `collapse_id` (int) ويستدعي `w2x.b(id)` لإلغاء الإشعار. الوصول يتطلب بثاً صريحاً محلياً نحو مستقبِل **غير مصدَّر** (`NotificationActionReceiver`) ⇒ أثر محدود. المفاتيح الأخرى (`notification_uid`, `notification_action_id`) تُعالج داخل try/catch (فشل ⇒ حدث `PUSH_NOTIFICATION_DISPLAY_FAIL`).

### F-14 — `bf3.O()` بلا try/catch حول `getParcelable` (INFO — متانة)

`bf3.O()` (إطار الانتقالات المشترك) يقرأ `intent_args_bundle_key` عبر `getParcelable` دون حماية: حقن نوع غير متوافق ⇒ `BadParcelableException` تُنهي النشاط المُستدعى (ذاتي فقط؛ لا تصعيد). الإصلاح: try/catch + تجاهل آمن.

### F-15 — مسار البريد الإلكتروني في DeepLink: غير متزامن بلا مهل ظاهرة (INFO — يحتاج تأكيداً يدوياً)

hosts البريد (`link.ms.email.nextdoor.com`, `links.itr.email.nextdoor.com`) تُعالج عبر معالج غير متزامن (طلب HTTP) دون مهل ظاهرة/تحقق كامل من حلقة إعادة دخول. خارج الحكم النهائي؛ توصية: مهل صريحة + منع إعادة الدخول.

### F-16 — PostComposerActivity: إغلاق by-design موسَّع (PASS)

- `onCreate` = إطار `bf3` (انتقالات) + `t270.g(this, "shortcut_post")` حيث **`t270.g` = `ShortcutManager.reportShortcutUsed`** فقط — صفر معالجة intent.
- **`fm40` صنف ثوابت int فقط (a/b/c/e) = إعدادات انتقال/أنيميشن، وليس معالج مشاركة.** `M()` جالبه، و`Y(List)` → `ArrayList<gc80>` (مرفقات في حالة الواجهة).
- `bf3.O()` = إطار الانتقالات فقط: `getParcelable("intent_args_bundle_key")` → غلاف `owo` → تطبيقه مع `overridePendingTransition`.
- **قراءة المشاركة** تصل عبر نمط defaults/SavedStateHandle إلى `com.nextdoor.composition.viewmodel.a` (data-class حالة: نص + مرفق `gc80` + جمهور + LocalDate + أعلام)، مع `b` آلة coroutine و`c` مساعد أحداث/تواريخ. ViewModels مموَّهة (a–f)؛ ViewEffects بأسماء محفوظة.
- **النتيجة:** لا sink خارجية، لا رفع تلقائي — by-design. `PostComposerComposeActivity` غير مصدَّر.

### إغلاق CustomTab (يدعم F-06)

- `CustomTabMainActivity` **غير مصدَّر** (بلا فلتر ⇒ default=false)؛ المصدَّر هو `CustomTabActivity` (VIEW+BROWSABLE، `scheme=fbconnect host=cct.com.nextdoor`).
- `CustomTabActivity.onCreate`: يمرّر **فقط** `getIntent().getDataString()` كextra `f` إلى Main عبر استدعاء صريح `startActivityForResult(..., 2)`.
- `Main.onResume`: `super` + إن سبق الاستئناف `a(0, null)` (إلغاء) ثم `this.a = true` — نمط FB SDK للإنهاء.
- `Main.a(int, Intent)`: إكمال OAuth — إلغاء تسجيل broadcast، قراءة extra `f`، دمج query+fragment، `o0.f(getIntent(), bundle, null)` نتيجة داخلية، `setResult` + `finish`. لا فتح URL خارجي من extras.

### إغلاق FCM (يدعم F-01)

- `d(String)` (= onNewToken): كتابة التوكن في SharedPreferences (`FCM_SYSTEM_DEVICE_TOKEN`) + تحديث repository (`g610.b(null)`) — بلا تسجيل/شبكة مباشرة؛ نظيف.
- `onCreate`: Hilt DI (12 متعاوناً) + `super` + عند BACKGROUNDED فقط warmup (`ndActivityHandler.d`). صفر معالجة intent.
- `exported=true` + فلتر MESSAGING_EVENT = سطح تكامل قياسي؛ توصية F-01 تبقى تحسينية.

### إغلاق المستمعين (يدعم F-09)

- `NotificationGroupChannelSettingListener` (مصدَّر): البث نظامي محمي؛ يقرأ `NOTIFICATION_CHANNEL_GROUP_ID` ويُرسل حدثاً تحليلياً فقط (`PUSH_NOTIFICATION_CHANNEL_GROUP_BLOCKED`).
- `NotificationActionReceiver` (**غير مصدَّر**): `notification_uid` (warmup) و`notification_action_id` داخل try/catch، ثم dispatch عبر `oxg0` (`APINotificationActionType.PRIVATE_MESSAGE_REPLY` → `pf00(ufb)`) + `collapse_id` (انظر F-13).
- `PackageReplacedReceiver` (**غير مصدَّر**): يشترط `MY_PACKAGE_REPLACED` ثم `goAsync` + coroutine لإعادة عرض الإشعارات.
- ملاحظة أداة: سكربت جرد الmanifest أسقط كتل `<receiver>` المتتالية (منها مستمعا الإشعارات الآخران) — أثر سكربت فقط؛ حالتهما النظامية في F-09.

### إغلاق PreviewActivity (يدعم F-05)

`androidx.compose.ui.tooling.PreviewActivity` (مصدَّر، بلا فلاتر): `onCreate` يفحص `ApplicationInfo.flags & FLAG_DEBUGGABLE` ⇒ في الإصدار (غير debuggable): `Log.d("Application is not debuggable. Compose Preview not allowed.") + finish()` **قبل أي معالجة extras** — عطل تام في الإصدار؛ sink الreflection يلزمه build debuggable. توصية الإزالة تبقى «نظافة».

### إغلاق عائلة trackingdemo ×5 (يدعم F-04)

الخمسة مصدَّرة بلا فلاتر، وكلها **صفر قراءة intent**: `FragmentFlowActivity` هيكل فارغ (باني فقط)؛ `ComposeFlowActivity` عرض ديمو (`setContent(n49(...))`)؛ `DraggableViewDemoActivity` و`ListDemoActivity` ثوابت ديمو. الأثر محصور في إطلاق شاشات عرض (ارتباك واجهة/DoS تافه).

### تحقق حيّ CVE (NVD)

`services.nvd.nist.gov/rest/json/cves/2.0?keywordSearch=nextdoor` → **0 نتائج**؛ `keywordSearch=facebook login android` → 0. القيد: بحث keyword-فقط (لا CPE للتطبيق) — ليس برهان خلو شامل.

---

## 6) حدود التحليل (Residual Unknowns)

| البند | الحالة |
|---|---|
| معالجات إجراءات DeepLink (تنفيذات `v1c` الفردية) | ✅ أُغلقت — السلسلة الكاملة مُتتبَّعة (5-ملحق)؛ التنفيذات الفردية تبقى نقاط امتداد داخلية بلا أثر خارجي مُثبت |
| سلسلة compose تحت PostComposerActivity (fm40) | ✅ أُغلق — `fm40` ثوابت انتقالات؛ المشاركة تصل عبر defaults/SavedStateHandle (F-16) |
| `CustomTabMainActivity.onResume` (التحقق من redirect) | ✅ أُغلق — نموذج FB SDK قياسي (إكمال/إلغاء OAuth) |
| 4 من عائلة trackingdemo | ✅ أُغلقت — الخمسة بلا أي قراءة intent |
| مسار بريد DeepLink غير المتزامن (مهل/إعادة دخول) | ⚠️ جديد — لم يُتحقق بالكامل (F-15) |
| نسخ extras عند علم `routed_from_gcm_through_deeplink` | ⚠️ جديد — نظري مشروط بقناة FCM (F-12) |
| أثر jadx: كتل مكررة في خدمة FCM (شروط صلاحية قناة) | ➖ يبقى — غير حاجز (بنية موثوقة، قيم منطقية جزئية) |
| تحقق حيّ CVE | ⚠️ جُزئياً — NVD مباشر: 0 نتائج (keyword-فقط، بلا CPE للتطبيق) |

---

## 7) التوصيات (مرتبة)

1. `exported="false"` لخدمة FCM (F-01).
2. إزالة مكوّنات QA/الأدوات من الإصدار (`trackingdemo.*`, `PreviewActivity,` `CustomTabActivity` إن لم تعد مستخدمة) بـ`tools:node="remove"` (F-04/05/06).
3. وقف تسجيل الحمولة الخام وتعقيم القيم في السجلات (F-02).
4. تعقيم رقمي مع try/catch للحمولات الرقمية (F-03).
5. اعتماد IntentSanitizer/allowlist لأي معالج deep-link جديد مستقبلاً (الحفاظ على النمط الحالي).
6. اعتبار extra `INTERNAL` مدخلاً غير موثوق في مسار DeepLink — لا يُمنح أي سلطة (F-11).
7. في سلسلة `e2c`: نسخ المفاتيح المتوقعة حصراً بدل النسخ الكامل عند علم `routed_from_gcm_through_deeplink` (F-12).
8. مهل صريحة + حماية إعادة الدخول لمسار البريد الإلكتروني غير المتزامن، وتقييد `collapse_id` بمصدر موثوق (F-13/F-15).
9. try/catch حول `getParcelable` في `bf3.O()` مع تجاهل آمن (F-14).

---

## 8) ملحق: مراجع الأدلة (Traceability)

| الدليل | Evidence ID |
|---|---|
| `DeepLinkActivity.T()` | `ev_0ba0ac28431ed3907ca60b58e9de0b0ed3f8eb61ab0071d6f5eb78ecc805e955` |
| `DeepLinkActivity.onNewIntent` (سلسلة bf3) | `ev_3b869f525aaa0f8ad8876a0895a52f0bdba8f9487d64fffd62bd42387da7c7f1` |
| `bf3.onNewIntent` (setIntent) | `ev_8499077561adc909c2373c0893191fe5b52c9e5e4951e384f1b223030ccc215e` |
| `ahr.onNewIntent` | `ev_b5cf7fc18340e64c7d6933ec51c590060e5806963f3ace5a75fc280fd0a3339d` |
| `c29.onNewIntent` (ComponentActivity) | `ev_ce2d2af46346c94dcb2f895ff273f59cac088d94c33dd4a2d97ce2a559ec94d5` |
| `DeepLinkActivity.onStart` | `ev_d4dc42e779fc684224d9175ba826d9f2e90ef2c86516b2fe4c49e31cc497dce6` |
| `PostComposerActivity.onCreate` | `ev_816acdd58db9593b6386930ed9e4e6a2b2d992869c7ffcde2ac1dbc2f0c69092` |
| `EngageBroadcastReceiver.onReceive` | `ev_d3b9de57927f348368fcbf9ab9486c77cd17b09a1db1289aeeadcbc3b421fd72` |
| `CustomTabActivity.onCreate` | `ev_b7085bff6cb4ca44be408292f7583a2feb94b3939685190462df0310f5bfdd26` |
| `CustomTabMainActivity.onCreate` | `ev_31f512a29ab9c161b165c3ccc96229a159b1ca3d7e12b253e8e9c7081325fb2f` |
| `TrackingDemoActivity.onCreate` | `ev_aecb7e1862b79ebc1e579dc0a126bfc5e4f23791da02a7289a82e8448a4c374f` |
| FCM `c()` الكاملة (مخرج محفوظ) | `C:\Users\w-co\.local\share\opencode\tool-output\tool_142f3df8a001dWD7zI1gZapUuU` |
| DeepLink `W()` | `ev_18b488b98d1a751e6c026321f1708e0e252e1742240749c1aa17e9cef16583b3` |
| `qra` (معالج غير متزامن في مسار DeepLink) | `ev_e3e305debae3858ce40416825ffe5d4f5800c0dd483240504374744926f57480` |
| مصنع `v1c` | `ev_c8e9eb563186f8a2b4fe301c7ba34a8b2938d6533084b57af665b4854ae2bea9` |
| `o95.h` (مدخل المصنع) | `ev_f58270da53d483be14c70f2d6054c029a8a5547c79970f8eb9c484de35591deb` |
| `v1c.a` (حاجز "invalid action") | `ev_cc3d0a46a0e5718d0c37962f54f56944f870a18400e72813287daa3874105551` |
| `d2c.a` (تصنيف الإجراء) | `ev_d0f16dde43db15dfe2b1fec9ddfbf9f513596d182ddab60364f03d4e1276c76b` |
| `o95.q` (المنفّذ) | `ev_082b970e9d9185acc3e820dd15242e285740706726804a27d6b439d85f24a115` |
| `e2c.invoke` | `ev_e1e8254c7dbcc9b081ea7355c6d932dc2e59f03e7fdb48a135d863f10e8c2d5c` |
| `o95.s` (الإجراء النهائي) | `ev_409be2891d329917c01a6e840c4e783e35a4bb7207ae4eaa3d99eeb39106a3fa` |
| `PostComposerActivity.M()` (جالب fm40) | `ev_8c66b0f14c7e1b9b0a384592279ff870186af6f574135e7f288760a11020c0ad` |
| `PostComposerActivity.Y(List)` | `ev_7da0ba51d825c57b041f3660d7fe1870e12aeb7d8d5ab469acf5f81811c339fa` |
| `PostComposerActivity.P()` | `ev_9eadeb58a7517fea986962bcafb41c720358e62ad9f9a521aca3bc257a44324a` |
| `PostComposerActivity.F()` | `ev_c309711cfcc7f76c1e224c4eb517e0f1dfcff4961043a4a6d41b3f5bb49bbbe7` |
| `PostComposerActivity.U()` | `ev_cdc3e4ab1bf7d9ce57a136603ad79e3dcc90ee3782f0237acf760c611e795e9f` |
| صنف `fm40` (ثوابت الانتقالات) | `ev_2e94a0d89152ef1938fe8b377082d03c77a29ec2f484205e6b6dee72f76f6c64` |
| `t270.g` (= ShortcutManager.reportShortcutUsed) | `ev_bf9ecf6d2142595b038cc66c72f4716739cae1d521578eca75df2f4b2c65bf55` |
| `bf3.O()` (إطار الانتقالات + getParcelable) | `ev_d8c82956a9a685188dfce4b56be59c118efd1f2ef49352451e268da6d79f7de1` |
| بحث `ShareComposer` | `ev_cbf579e9031617a6de7ac2c853658db9a28a7029969c6447c4449f2be76bb1db` |
| بحث `ComposerLanding` | `ev_ea771b662e1777149bdaba63d36c81f307d4ed5a7a53fe54c6537203e1265977` |
| بحث `composition.viewmodel` | `ev_0f2071d8cd4921508649ccbe2fd758d9fb57d1fa91a76b82deac6235f770c6c2` |
| `viewmodel.a` (حالة الواجهة) | `ev_2d99e7f14fdaeeff88f7bfda85b4b9b01ff209ebaeb2f66dbdfbb0cabe3066fc` |
| `viewmodel.b` (آلة coroutine) | `ev_5406761d2e3371a2b4175ca812dd53de5e77cc6d58d992c640593bd0be381cb2` |
| `viewmodel.c` (أحداث/تواريخ) | `ev_e5b87e206b2afc72689c797ed51e477833402a9794ea63303a3e52372d54d3da` |
| `CustomTabActivity` (ملخص الصنف) | `ev_93f5467ffc3f1c5e32ea8f1957d6abb71111ef97815688706c8ae838eb9c3a93` |
| `CustomTabActivity.onCreate` (إعادة تحقق) | `ev_affc503d0b5045c762bf0fcb7e795b9c65708ec2005dc68b43bf747cc6bd08ad` |
| `CustomTabMainActivity.onResume` | `ev_979a4d9c1814d3302c74aad396cf424d20b0f1b390a940ca57edf47afe5bddf5` |
| `CustomTabMainActivity.a(int, Intent)` | `ev_cb364bffa4386203cc8f885a76fffde480a490c4ffc622b0b98ac5a356f15510` |
| FCM `d(String)` (= onNewToken) | `ev_5c358dd1874b7adf3e3e7b2dac00a40afddceadfda3500955390121f7e19d84c` |
| FCM `onCreate` | `ev_53e103bc8f465e9437f445208133030c31e6f207bc217793141643bf99669d25` |
| `NotificationGroupChannelSettingListener.onReceive` | `ev_e45fc385c5a547c6b110f049f5b3be2b2a6a9b04dba46a5eb86c44da2cc41819` |
| `NotificationActionReceiver.onReceive` | `ev_ebb4f4d282755e3e78bc9f54252130bf964ba1a0fe0346b67eb95c48a16fcfb4` |
| `PackageReplacedReceiver.onReceive` | `ev_afd383e66159d3f504a431c4ed045c3b01e10e86ab02e0eec26af66b8650496d` |
| `PreviewActivity.onCreate` (حارس debuggable) | `ev_f000b1bb0d65933fc7874069ebadab9288bee6de54b4338ac57206f803e70c84` |
| `ComposeFlowActivity.onCreate` | `ev_5ad17c4f563e42d28dfa0e11c8bfe3be44cbc376bdc4fdca9ede156f912c0915` |
| `DraggableViewDemoActivity.onCreate` | `ev_2d0e6019c6263c7155bf61c9ec9c6410f7a82f16fcec2effc85ab25017baa33c` |
| `ListDemoActivity.onCreate` | `ev_2ce194b986d98dfd024694c2ac2341ea21c1c69abe30f5473b80a7224e62a273` |

**ملاحظة:** حزمة الأدلة المحتفظ بها في جلسة التحليل = **80 سجلاً** كاملاً (subject موحّد: base.apk `7cb7ae84…`، كلها `shipped-artifact`) — قابلة للتصدير كاملة.

**تحذير منهجي:** كل الاستنتاجات ثابتة (static) — التنفيذ لم يحدث، ولا بد من إعادة تحقق يدوي قبل أي قرار إفصاح/تعديل.

---

*نهاية التقرير — أُنتج عبر REA (reverse-engineering tools) بتحليل ثابت؛ التطبيق لم يُنفَّذ ولن تُنفَّذ مكوناته.*
