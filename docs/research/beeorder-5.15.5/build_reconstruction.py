import json, os

UI = r"D:\rea-beeorder\ui"
REC = os.path.join(UI, "reconstruction")
manifest = json.load(open(os.path.join(REC, "asset_manifest.json"), encoding="utf-8"))
for b in ("brand_ic_launcher.webp","brand_ic_launcher_fg.webp","brand_ic_beepro.webp","brand_ic_ramadan.webp"):
    manifest.append({"rel": "assets/brand/" + b, "kind": "image", "bytes": 0, "sha256": ""})
en = json.load(open(os.path.join(UI, "clean_lang", "en.json"), encoding="utf-8"))

def folder(rel):
    p = rel.split("/")
    if len(p) > 3 and p[1] == "images":
        return p[2] if len(p) > 3 else "(root)"
    return "animation" if p[1] == "animations" else p[1]

FEATURES = [
 {"id":"auth","t":"Auth / OTP","n":"تسجيل الدخول بالهاتف + OTP (واتساب مفروض أحياناً)","c":["Auth","AuthMethod","Authenticate","LoginResponseModel","LoginVerifyBy","WelcomeAuthTag"],"e":["auth_otp_requested","auth_otp_verified","auth_profile_completed","auth_consent_responded"],"k":["VERIFY_OTP","RESEND_OTP","VERIFY_BY","OTP_FORCED_WHATSAPP","MOBILE_NUMBER","DIAL_CODE"]},
 {"id":"onboarding","t":"Onboarding","n":"شاشات الترحيب، اختيار الموقع، الاستبيان","c":["Introduce","OnboardingQuestionType","LocationFlowOutcome","LocationStep"],"e":["SUBMIT_ONBOARDING_SURVEY","CURRENT_ONBOARDING_STEP","GET_SURVEY"],"k":["onboarding/location"]},
 {"id":"home","t":"Home","n":"الصفحة الرئيسية: بنرات، أقسام، Flash Sale","c":["Home","HomePage","HomeFeedV2","HomeFeedHeaderTheme","HomeScreenDisplayState","HomeSectionsEnum","FlashSaleRestaurants","BigBanner"],"e":["HOME_LIST","HOME_SEARCH_SUGGESTED_WORDS","CATEGORIES_LIST"],"k":["home/feed"]},
 {"id":"search","t":"Search","n":"بحث + فلاتر + اقتراحات + سجل بحث","c":["Search","SearchPage","SearchBody","SearchViewModel","SearchFilterEnum","SearchSectionEnum","SearchType"],"e":["SEARCH_HISTORY","FILTERS_RECENT_LIST","ITEM_SEARCH"],"k":["search"]},
 {"id":"store","t":"Restaurant / Menu","n":"قائمة مطاعم/متاجر + منيو + تفاصيل الطبق","c":["Restaurant","RestaurantModel","RestaurantsETA","RestaurantSchedule","Dish","DishModel","DishValidationKind"],"e":["RESTAURANT_MENU","RESTAURANT_DETAILS","RESTAURANT_REVIEWS","DISH_DETAIL","dishPageReportTapped"],"k":["store/menu"]},
 {"id":"cart","t":"Cart","n":"السلة، سلات محفوظة، حالة السلة","c":["Cart","CartRepository","CartStateAction","MyCarts"],"e":["cart_state_changed","cart_sheet","add_to_cart","addToCartBlocked","LOCAL_CARTS","add_more_items"],"k":["cart"]},
 {"id":"checkout","t":"Checkout / Payment","n":"الدفع: كاش أونلاين/أوفلاين، كوبونات، BeePay","c":["Checkout","CheckoutBlock","CheckoutEtaModel","CheckoutFlowType","CheckoutViewModelHost","PaymentContext","PaymentType","BeePay"],"e":["checkoutScreenOpened","checkoutOrderSummaryOpened","checkoutSubmitTapped","checkout_calc_bill_skipped","checkout_create_order_success","checkout_offline_order_completed","checkout_online_pay_success_opened","checkout_validation_failed"],"k":["checkout/payment"]},
 {"id":"orders","t":"Orders / Tracking","n":"سجل الطلبات + تتبّع حيّ للسائق على الخريطة","c":["Order","OrderState","OrderTracking","OrderStepModel","OrderStatusSocketManager","DriverLocationSocketManager","AnimatedDriverMarker","EditOrder"],"e":["ORDER_LIST","ORDER_DETAILS","ORDER_TRACKING","ORDER_LOGS","ORDER_NOTES_V2","ORDER_TIPS"],"k":["order/tracking","orders/history"]},
 {"id":"address","t":"Addresses","n":"إدارة العناوين + تحديد على الخريطة + تصويت","c":["Address","AddressAction","AddressDetailsFormMode","AddressNamePreset","AddressSheetEntrySource","UserAddressStatus"],"e":["address_started","address_details_entered","address_pin_set","address_completed","address_vote_requested","address_mismatch_shown","ADD_ADDRESS","UPDATE_ADDRESS","VOTE_FOR_ADDRESS"],"k":["addresses"]},
 {"id":"beebox","t":"BeeBox","n":"Pickup/Dropoff من نقاط محددة على الخريطة","c":["BeeBox","BeeBoxAddressPairIssue","BeeBoxCheckoutStoreBase"],"e":["beeboxCategorySelected","beeboxPickPointOnMapTapped","beeboxSubtotalSelected","beeboxPurchaseSelected","beeboxCheckoutViewed","beeboxPlaceOrderTapped"],"k":["beebox/beepro"]},
 {"id":"beepro","t":"BeePro","n":"اشتراك/عضوية مع توفير على التوصيل والدفع","c":["BeeProSavingsModel","BeeProOrderSavingsModel","BeeProCheckoutConfirmResult"],"e":["BEE_PRO_ACTIVATE","BEE_PRO_SAVINGS","PRO_DISH_OFFER"],"k":["beebox/beepro"]},
 {"id":"subscription","t":"Subscription","n":"خطط الاشتراك وطلب الدفع وحالته","c":["Subscription","SubscriptionBenifitsModel","SubscriptionDetailsModel","SubscriptionPayModel","SubscriptionStatusModel"],"e":["SUBSCRIPTION_PLANS","SUBSCRIPTION_PAY","SUBSCRIPTION_PAYMENT_STATUS"],"k":["wallet/points"]},
 {"id":"wallet","t":"Wallet / Loyalty","n":"محفظة، نقاط، مستويات، إنجازات","c":["Wallet","WalletsStore","Loyalty","LoyaltyPage","LoyaltyInfoModel","TokenModel","TransactionTypeEnum"],"e":["WALLET_FIRST_VISIT","POINTS","REDEMPTION_HISTORY","ACHIEVEMENTS_LIST","TIERS_INFO","TIERS_GUIDE"],"k":["wallet/points"]},
 {"id":"vouchers","t":"Vouchers / Games","n":"كوبونات + عجلة الحظ + Scratch","c":["VoucherListViewModelBase","SpinnerRewardType","Scratch","ScratchAccuracy"],"e":["VOUCHERS_LIST","VALIDATE_VOUCHER","CHECKOUT_VOUCHERS","addSpinByCode"],"k":["vouchers/games"]},
 {"id":"rating","t":"Rating / Reviews","n":"تقييم الطلب والمطعم","c":["Rating","Rating2","RatingTarget","ReviewStarType","RestaurantReviewsViewModelBase"],"e":["RATE_ORDER","RATES_OPTIONS_V2","RATE_PROMPT_ORDER_IDS"],"k":["rating/reviews"]},
 {"id":"profile","t":"Profile / Settings","n":"الملف الشخصي، الثيم، اللغة، الخصوصية","c":["Profile","ProfileViewModelBase","UserInfoModel","UserPhoneNumberModel","AppSettingsType","ThemeMode"],"e":["USER_INFO","USER_NETWORK_INFO","THEME","FORCE_DARK_ON","FORCE_DARK_OFF","IS_PRIVACY_POLICY_SHOWED"],"k":["profile/settings"]},
 {"id":"support","t":"Support / Feedback","n":"الأسئلة الشائعة، الإبلاغ عن مشكلة، تعديل الطلب","c":["ReportIssueScreen","ReportIssueSelectionRoute","SupportTopic","IssueReasonModel","FaqCategoryModel","FaqItemModel"],"e":["GET_FAQ_CATEGORY","GET_FAQ_ANSWER","SUBMIT_FEEDBACK","MODIFICATION_REQUESTS"],"k":["support"]},
 {"id":"group","t":"Group Order","n":"طلب جماعي + دعوات + رابط مشاركة","c":[],"e":["GROUP_ORDER_LOCAT_CARTS","APPLY_INVITE_CODE","INVITE_CODE_PENDING","LINK_ACTIVATED"],"k":["group_order/invite"]},
 {"id":"notifications","t":"Notifications","n":"FCM + إشعارات محلية + تحديثات الطلب الحيّة","c":["NotificationCenter","Notifications","OrderLiveUpdatesManager","GroupAlertBehavior"],"e":["MAKE_USER_NOTIFICATION_READ","UPDATE_FCM","GET_USER_NOTFICAITION"],"k":[]},
 {"id":"map","t":"Map / Geo","n":"خرائط Google، عناقيد، تتبع السائق","c":["GoogleMap","GoogleMapController","GoogleMapMarkerType","Cluster","PlatformClusterManager","MapsApi","AnimatedDriverMarker"],"e":["CURRENT_LOCATION","CHECK_LOCATION","VOTE_FOR_ADDRESS"],"k":[]},
 {"id":"currency","t":"Currency / Region","n":"العملات والدول والمدن","c":["CountryModel","CityModel","CurrencyViewModelBase"],"e":["CURRENCIES","AVAILABLE_COUNTRIES","CITIES","UPDATE_CURRENCY"],"k":[]},
 {"id":"splash","t":"Splash / Branding","n":"شاشة البداية + تبديل الأيقونة (BeePro/Ramadan)","c":["Splash","SplashVersion"],"e":["SPLASH_VERSION","SPLASH_LOCAL_AR","SPLASH_LOCAL_EN","DECLINED_APP_ICON"],"k":[]},
 {"id":"litemode","t":"Lite Mode / Storage","n":"وضع خفيف، إدارة الكاش، سياسة الشبكة","c":[],"e":["LITE_MODE_DETECTOR_ENABLED","LITE_MODE_MONITOR_ENABLED","LITE_MODE_PROBE_ENABLED","LITE_MODE_SWITCH_ENABLED","STORAGE_LAST_SWEEP_AT","STORAGE_RECLAIM_VERSION"],"k":[]},
]

FLOWS = [
 {"t":"Onboarding + Auth","s":["Splash","Language AR/EN","Onboarding slides","Survey","Phone entry","OTP (WhatsApp)","Profile complete","Home"]},
 {"t":"Address setup","s":["Open address sheet","Pick on map","Fill details / voice note","Pin set","Save","Vote if mismatch","Address active"]},
 {"t":"Discovery to Cart","s":["Home feed","Category / Search","Restaurant menu","Dish detail","Add to cart","Cart sheet","Group order (opt)"]},
 {"t":"Checkout","s":["Checkout screen","Order summary","Coupon / voucher","Bill calc (ETA)","Payment method","Submit","Online verify / Offline placed"]},
 {"t":"Live tracking","s":["Order tracking","Status socket","Driver location","Captain call","Live update notification"]},
 {"t":"Post-order & Loyalty","s":["Order history","Rate order","Points / Wallet","Redemption history","Achievements","Subscription / BeePro"]},
]

COLORS = ["FFFF00","FF7A32","FFB020","FFB93B","FFD07C","FFC2A1","FFA676","FFE6D6","FFF2EB","FFF3E5","FFF8EB","FFE9C2",
          "E2E8F0","CBD5E1","EFF2F5","EFF3F7","F8FAFC","F6F8FB","E4E8EC","F04438","F58344","F8A9A3","FEECEB","E7F5F6","E8A836"]

fonts = [("Rubik Light","300"),("Rubik Regular","400"),("Rubik Medium","500"),("Rubik SemiBold","600"),("Rubik Bold","700"),("Rubik ExtraBold","800"),("Rubik Black","900"),("Credit (display)","-")]

NATIVE = {
 "channels": [
  ["com.beeorder.customer/live_updates_order_tracking", "getActiveOrderIds · showNotification · endNotification · endAllNotifications (Android 16 Live Updates للطلب الحيّ)"],
  ["instagram_share_channel", "مشاركة إلى Instagram (stories/feed)"],
  ["change_app_icon_channel", "تبديل أيقونة التطبيق: Default / BeePro / Ramadan"],
  ["com.llfbandit.app_links/*", "App Links / Deep links (events, messages)"],
  ["com.llfbandit.record/*", "تسجيل الصوت — الملاحظات الصوتية (voice notes)"],
  ["dev.steenbakker.mobile_scanner/*", "ماسح الباركود (scanner/method, event, deviceOrientation)"],
  ["dev.fluttercommunity.plus/*", "share · device_info · connectivity · network_info · package_info · android_intent"],
  ["com.spencerccf.app_settings/methods", "فتح إعدادات النظام"],
  ["com.android.badge/badge", "شارة الإشعارات (Samsung/Huawei/Honor/Sony/TeslaCoil)"],
  ["firebase_messaging / crashlytics / remote_config / analytics", "FCM + Crashlytics + Remote Config + Analytics"],
  ["plugins.flutter.io/google_maps", "خرائط Google"],
  ["plugins.flutter.io/shared_preferences", "تخزين محلي (SharedPreferences)"],
  ["plugins.flutter.io/image_picker", "اختيار صور"],
  ["plugins.flutter.io/url_launcher", "فتح روابط خارجية"],
  ["plugins.flutter.io/video_player / media3 1.9.2", "تشغيل فيديو/Media3"],
  ["plugins.flutter.io/webview_flutter", "WebView (inappwebview)"],
  ["plugins.flutter.io/path_provider", "مسارات الملفات"],
 ],
 "receivers": [
  ["OrderLiveUpdatePushReceiver", "FCM data push: type=order_live_update · order_id · event=end · is_order_ended · sent_at (dedupe) · title · subtitle · eta · current_step_index · total_steps · driver_mobile (gated by enabled + allowed_<orderId>)"],
  ["OrderLiveUpdateDismissReceiver", "action com.beeorder.customer.ORDER_LIVE_UPDATE_DISMISSED → dismissed_step_<orderId> (احترام مهلة 60s)"],
  ["AppUpdateReceiver", "action MY_PACKAGE_REPLACED → إعادة تفعيل الأنشطة غير المرادفة (استعادة حالة تبديل الأيقونة)"],
 ],
 "prefs": "SharedPreferences اسمه order_live_updates بمفاتيح: enabled · allowed_<orderId> · eta_label · call_captain_label · default_title · timeout_seconds · sent_at_<orderId> · dismissed_step_<orderId>",
 "packages": ["com.appsflyer", "com.pichillilorenzo (flutter_inappwebview)", "com.baseflow (geolocator/image_picker/permission_handler)",
              "com.crazecoder (open_filex)", "com.scottyab (rootbeer)", "com.it_nomads (flutter_native_splash)",
              "com.dexterous (flutter_local_notifications)", "com.google (Firebase/GMS)", "io.flutter.plugins.*"],
}

data = {"manifest": manifest, "features": FEATURES, "flows": FLOWS, "colors": COLORS,
        "fonts": fonts, "i18n": {"count": len(en)}, "native": NATIVE, "tech": {
            "package":"com.beeorder.customer","label":"BeeOrder","version":"5.15.5 (339)",
            "sdk":"min 24 / target 36","engine":"Flutter (Impeller off), Dart AOT arm32/arm64",
            "dartPkg":"beeorder_mobile","ota":"Shorebird","signing":"BNDLTOOL"}}

HTML = """<!doctype html><html lang="ar" dir="rtl"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>BeeOrder 5.15.5 - UI/UX End-to-End Reconstruction</title>
<style>
:root{--bg:#0f1115;--panel:#171a21;--panel2:#1e222c;--line:#2a2f3a;--fg:#e8ecf3;--mut:#95a0b3;
--bee:#ffb020;--bee2:#ff7a32;--ok:#3fb950;--warn:#f04438;--acc:#58a6ff}
*{box-sizing:border-box}body{margin:0;background:var(--bg);color:var(--fg);
font-family:"Segoe UI",Tahoma,system-ui,sans-serif;line-height:1.6}
a{color:var(--acc);text-decoration:none}
header{position:sticky;top:0;z-index:20;background:linear-gradient(90deg,#171a21,#20160c);
border-bottom:1px solid var(--line);padding:14px 20px;display:flex;gap:16px;align-items:center;flex-wrap:wrap}
header h1{font-size:18px;margin:0}
.pill{background:var(--panel2);border:1px solid var(--line);border-radius:999px;padding:3px 10px;font-size:12px;color:var(--mut)}
.pill.bee{border-color:var(--bee);color:var(--bee)}
nav{display:flex;gap:6px;flex-wrap:wrap;padding:10px 20px;border-bottom:1px solid var(--line);position:sticky;top:63px;background:var(--bg);z-index:19}
nav button{background:var(--panel);color:var(--mut);border:1px solid var(--line);border-radius:8px;padding:7px 14px;cursor:pointer;font-size:14px}
nav button.on{background:var(--bee);color:#111;border-color:var(--bee);font-weight:700}
main{padding:22px;max-width:1300px;margin:0 auto}
section{display:none}section.on{display:block}
h2{font-size:20px;margin:.2em 0 .6em;border-right:4px solid var(--bee);padding-right:10px}
h3{font-size:16px;margin:1.2em 0 .4em;color:var(--bee)}
.grid{display:grid;gap:14px}.g2{grid-template-columns:repeat(auto-fill,minmax(340px,1fr))}
.g3{grid-template-columns:repeat(auto-fill,minmax(220px,1fr))}
.g4{grid-template-columns:repeat(auto-fill,minmax(160px,1fr))}
.card{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:14px}
.card h4{margin:0 0 6px;font-size:15px;color:var(--fg)}
.card .desc{color:var(--mut);font-size:13px;margin-bottom:8px}
.tags{display:flex;flex-wrap:wrap;gap:5px}
.tag{background:var(--panel2);border:1px solid var(--line);border-radius:6px;padding:2px 7px;font-size:11.5px;color:#c7d0dd;font-family:Consolas,monospace}
.tag.ev{border-color:#2d4a63;color:#9ecbff}.tag.kk{border-color:#5a4326;color:#ffd18c}
table{width:100%;border-collapse:collapse;font-size:14px}
td,th{border-bottom:1px solid var(--line);padding:8px 10px;text-align:right;vertical-align:top}
th{color:var(--bee);font-weight:600}
.tabs{display:flex;gap:6px;flex-wrap:wrap;margin:8px 0}
.tabs button{background:var(--panel);color:var(--mut);border:1px solid var(--line);border-radius:999px;padding:5px 12px;cursor:pointer;font-size:13px}
.tabs button.on{background:var(--panel2);color:var(--bee);border-color:var(--bee)}
.gal{display:grid;gap:10px;grid-template-columns:repeat(auto-fill,minmax(120px,1fr))}
.gal figure{margin:0;background:var(--panel2);border:1px solid var(--line);border-radius:10px;padding:8px;text-align:center}
.gal img{max-width:100%;height:74px;object-fit:contain;image-rendering:auto}
.gal figcaption{font-size:10.5px;color:var(--mut);word-break:break-all;margin-top:6px}
.flow{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:14px;margin-bottom:12px}
.steps{display:flex;gap:8px;flex-wrap:wrap;align-items:center}
.step{background:var(--panel2);border:1px solid var(--line);border-left:3px solid var(--bee);border-radius:8px;padding:7px 12px;font-size:13px}
.arrow{color:var(--bee);font-weight:700}
.sw{display:grid;gap:10px;grid-template-columns:repeat(auto-fill,minmax(130px,1fr))}
.sw div{border-radius:10px;height:74px;border:1px solid var(--line);display:flex;align-items:flex-end;justify-content:center;color:#111;font-size:11px;font-family:Consolas,monospace;padding:4px;text-shadow:0 0 3px #fff8}
.tt{display:flex;gap:16px;flex-wrap:wrap}.tt div{background:var(--panel2);border:1px solid var(--line);border-radius:10px;padding:12px 16px}
.tabbar{display:flex;gap:8px;flex-wrap:wrap}
.tabitem{background:var(--panel);border:1px solid var(--line);border-radius:12px;padding:12px 16px;min-width:120px;text-align:center}
.tabitem b{display:block;color:var(--bee);font-size:15px}
.tabitem span{color:var(--mut);font-size:11.5px}
input[type=search]{background:var(--panel);border:1px solid var(--line);color:var(--fg);border-radius:8px;padding:8px 12px;width:280px;max-width:100%}
code{background:var(--panel2);padding:1px 6px;border-radius:5px;font-size:12.5px}
small.mut{color:var(--mut)}
</style></head><body>
<header>
 <h1>BeeOrder <span style="color:var(--bee)">UI/UX End-to-End</span></h1>
 <span class="pill bee">v5.15.5 (339)</span>
 <span class="pill">Flutter AOT</span><span class="pill">com.beeorder.customer</span>
 <span class="pill">Source: APKPure XAPK</span>
</header>
<nav id="nav"></nav>
<main>
 <section id="overview" class="on"><h2>Overview</h2><div class="card"><table id="tech"></table></div>
  <h3>Navigation (Bottom tabs)</h3><div class="tabbar" id="tabbar"></div>
  <h3>Design tokens - palette</h3><div class="sw" id="swatch"></div>
  <h3>Typography</h3><div class="tt" id="fonts"></div>
 </section>
 <section id="screens"><h2>Screens &amp; Features <small class="mut" id="fcount"></small></h2>
  <input type="search" id="fs" placeholder="ابحث في الميزات/المكوّنات/الأحداث...">
  <div class="grid g2" id="features" style="margin-top:12px"></div></section>
 <section id="flows"><h2>End-to-End Flows</h2><div id="flowsbox"></div>
  <h3>Subscription / BeePro / Group order paths</h3>
  <div class="card"><p><b>BeePro:</b> Home banner → BeePro sheet → Benefits → Activate → savings applied at cart + tracking.</p>
  <p><b>Group order:</b> Cart → Create party → Share invite code/link → Participants add → Owner submits.</p>
  <p><b>BeeBox:</b> Category → Pick point on map → Pickup/Dropoff addresses + vehicle → Subtotal → Purchase → Checkout.</p>
  <p><b>Games:</b> Vouchers → Spin wheel / Scratch → Reward → Redeem in checkout or history.</p></div></section>
 <section id="native"><h2>الطبقة الأصلية (Android / Platform)</h2>
  <h3>MethodChannels</h3><div class="card"><table id="channels"></table></div>
  <h3>Broadcast Receivers</h3><div class="card"><table id="receivers"></table></div>
  <h3>حالة الإشعارات الحيّة</h3><div class="card"><p id="prefs"></p></div>
  <h3>حزم المكتبات الأصلية</h3><div class="card"><div class="tags" id="packages"></div></div>
 </section>
 <section id="assets"><h2>Assets <small class="mut" id="acount"></small></h2>
  <div class="tabs" id="afolders"></div>
  <input type="search" id="as" placeholder="ابحث باسم الملف...">
  <div class="gal" id="gal" style="margin-top:12px"></div></section>
 <section id="manifest"><h2>Identity &amp; Data sources</h2>
  <div class="card"><p>كل البيانات مستخرجة من النسخة السليمة: حزمة Dart (47,131 مُعرِّف)، i18n (1,710 مفتاح x2)، 445 أصل flutter_assets، المانيفست.</p>
  <p>i18n keys: <code id="i18n"></code> &middot; Assets: <code id="massets"></code></p>
  <p>راجع <code>UIUX_REPORT.md</code> للتقرير النصّي الكامل.</p></div></section>
</main>
<script>
const D=__DATA__;
const tabs=[["overview","نظرة عامة"],["screens","الشاشات والميزات"],["flows","التدفقات E2E"],["native","الطبقة الأصلية"],["assets","الأصول"],["manifest","المصادر"]];
const nav=document.getElementById('nav');tabs.forEach(([id,t],i)=>{const b=document.createElement('button');b.textContent=t;if(i==0)b.className='on';b.onclick=()=>{document.querySelectorAll('nav button').forEach(x=>x.className='');b.className='on';document.querySelectorAll('main section').forEach(s=>s.className='');document.getElementById(id).className='on'};nav.appendChild(b)});
// tech
document.getElementById('tech').innerHTML=Object.entries(D.tech).map(([k,v])=>`<tr><th>${k}</th><td>${v}</td></tr>`).join('');
// tabbar
const bottom=[["Home","Home feed"],["Offers","Happy Hours 🔥"],["Search","بحث"],["My Orders","سجل الطلبات"],["Cart","السلة"],["Profile","الملف"]];
document.getElementById('tabbar').innerHTML=bottom.map(([a,b])=>`<div class="tabitem"><b>${a}</b><span>${b}</span></div>`).join('');
// palette
document.getElementById('swatch').innerHTML=D.colors.map(c=>`<div style="background:#${c}">#${c}</div>`).join('');
document.getElementById('fonts').innerHTML=D.fonts.map(([f,w])=>`<div style="font-family:'${f.split(' ')[0]}'">${f} <small class="mut">w${w}</small></div>`).join('');
// features
const fb=document.getElementById('features');
function drawF(q){q=(q||'').toLowerCase();let n=0;fb.innerHTML=D.features.filter(f=>!q||JSON.stringify(f).toLowerCase().includes(q)).map(f=>{n++;return `<div class="card"><h4>${f.t}</h4><div class="desc">${f.n}</div>
 <div class="tags">${(f.c||[]).map(x=>`<span class="tag">${x}</span>`).join('')}</div>
 ${f.e&&f.e.length?`<h4 style="margin-top:10px;font-size:12.5px;color:var(--acc)">keys / events</h4><div class="tags">${f.e.map(x=>`<span class="tag ev">${x}</span>`).join('')}</div>`:''}
 ${f.k&&f.k.length?`<h4 style="margin-top:10px;font-size:12.5px;color:var(--bee)">i18n domain</h4><div class="tags">${f.k.map(x=>`<span class="tag kk">${x}</span>`).join('')}</div>`:''}
 </div>`}).join('');document.getElementById('fcount').textContent=`(${n}/${D.features.length})`}
drawF('');document.getElementById('fs').oninput=e=>drawF(e.target.value);
// flows
document.getElementById('flowsbox').innerHTML=D.flows.map(f=>`<div class="flow"><h4 style="margin:0 0 8px">${f.t}</h4><div class="steps">${f.s.map((s,i)=>`${i?'<span class="arrow">&larr;</span>':''}<span class="step">${s}</span>`).join('')}</div></div>`).join('');
// native
document.getElementById('channels').innerHTML=D.native.channels.map(([a,b])=>`<tr><th style="font-family:Consolas,monospace">${a}</th><td>${b}</td></tr>`).join('');
document.getElementById('receivers').innerHTML=D.native.receivers.map(([a,b])=>`<tr><th>${a}</th><td>${b}</td></tr>`).join('');
document.getElementById('prefs').innerHTML=D.native.prefs;
document.getElementById('packages').innerHTML=D.native.packages.map(x=>`<span class="tag">${x}</span>`).join('');
// assets
const imgs=D.manifest.filter(m=>m.kind!=='font');const vids=D.manifest.filter(m=>m.kind==='animation');
function fold(m){const p=m.rel.split('/');if(m.kind==='animation')return 'animations';if(p[1]==='images')return p.length>3?p[2]:'(root)';return p[1];}
const folders=[...new Set(imgs.map(fold))].sort((a,b)=>a.localeCompare(b));
const af=document.getElementById('afolders');let cur='all';
function drawA(){const q=(document.getElementById('as').value||'').toLowerCase();const list=imgs.filter(m=>(cur==='all'||fold(m)===cur)&&(!q||m.rel.toLowerCase().includes(q)));
 document.getElementById('gal').innerHTML=list.map(m=>{const isAnim=m.kind==='animation';return `<figure>${isAnim?`<div style="height:74px;display:flex;align-items:center;justify-content:center;color:var(--bee);font-size:30px">&#9654;</div>`:`<img loading="lazy" src="${m.rel}" alt="">`}<figcaption>${m.rel.split('/').pop()}</figcaption></figure>`}).join('');
 document.getElementById('acount').textContent=`(${list.length}/${imgs.length})`;}
const mk=(id,t)=>{const b=document.createElement('button');b.textContent=t;b.onclick=()=>{cur=id;af.querySelectorAll('button').forEach(x=>x.className='');b.className='on';drawA()};return b};
const ball=mk('all',`الكل (${imgs.length})`);ball.className='on';af.appendChild(ball);
folders.forEach(f=>af.appendChild(mk(f,`${f} (${imgs.filter(m=>fold(m)===f).length})`)));
document.getElementById('as').oninput=drawA;drawA();
document.getElementById('i18n').textContent=D.i18n.count+' keys';
document.getElementById('massets').textContent=imgs.length+' files';
</script></body></html>"""

out = HTML.replace("__DATA__", json.dumps(data, ensure_ascii=False))
p = os.path.join(REC, "index.html")
open(p, "w", encoding="utf-8").write(out)
print(json.dumps({"html": p, "bytes": len(out.encode("utf-8")),
                  "features": len(FEATURES), "flows": len(FLOWS),
                  "assets_listed": len(manifest), "base_dir": REC}, indent=1))
