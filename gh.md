أولاً: تصحيح إلزامي لإجابتي السابقة
قلت سابقاً «صفر @EnableWebSocketMessageBroker في المستودع» — هذا خاطئ، بحثي كان معطوباً. الواقع: marketplace-messaging/.../WebSocketConfig.java:12 موجود و/ws مُسجَّل. لكن النتيجة العملية لا تزال سلبية، وبسبب أدق: /ws لا تطابق أي سلسلة أمن (السلسلة 2 تغطي /api/** فقط) فتسقط على السلسلة 3 ⇒ 302 إلى /login بدل 101. أي أن الدفع الفوري مبني لكنه غير قابل للوصول بمتوكن JWT.
ثانياً: الفحص الشامل — 22 وحدة + العقود + الحدود + القصص
استخدمت 6 وكلاء متوازين (dispatching-parallel-agents) على نطاقات مستقلة، ثم تحققت بنفسي من أثقل 6 ادعاءات قبل عرضها.
مصفوفة الوحدات
وحدة
الحالة
الدليل الحاسم
search
✅ جاهز
16 فلتر + ترتيب مبيَّن + id ككاسر تعادل
geo
✅ جاهز (تحفّظ)
suggest حسّاس لحالة الأحرف، سقف 20 بلا مؤشر has-more
reviews
⚠️ تحفّظ
لا حذف/إشراف/إبلاغ، التقييم على المزوّد لا الخدمة
disputes
⚠️ تحفّظ
OPEN→RESOLVED فقط، لا طابور إداري (الأدمن يتنقّل في الحجوزات)
community
✅ جاهز (تحفّظ)
10 أسطح + إشراف كامل، بلا GET /posts/{id} ولا حذف تعليق
realestate
✅ جاهز ضيّق
تفاصيل عقارية فقط؛ الوصول البعيد عبر /search لا عبر endpoint
notifications
⚠️ poll فقط
لا paging ولا عدّاد غير مقروء
media
⚠️ بحاجز
بايتات مباشرة إلى S3 (ممتاز) لكن GET /api/v1/media/** ليس public
catalog
⚠️ فجوات
ListingSummary بلا currency ولا providerId ولا صور/موقع/تقييم
pricing
❌ فجوة
تقويم السعر للمالك فقط، convert = 503 ومصادق
booking
✅ سليم
_states صحيحة + 409 على التعارض + قفل تفاؤلي يمنع الحجز المزدوج
payments
❌ حاجز
بلا Stripe: processIntent يتخطّى، وconfirmIntent = hasRole('ADMIN')
ledger
✅ سليم
idempotent بـsource_id UNIQUE + listeners تتجاهل غير COMPLETED/REFUNDED
provider
❌ حاجز
ترقية الدور لا تُفعّل الصلاحيات (تفصيل أدناه)
identity
❌ حاجز
لاendpoint تسجيل + لا كلمة مرور + لا تحقق بريد
admin
⚠️ فجوات
14 عملية، لكن بلا بحث/فلترة + بلا طابور نزاعات + بلا داشبورد
ai
❌ لا سطح
صفر controller ⇒ أي مسار = 404 لا 503
edge
⚠️ كود مكتمل/نشر غير مثبت
12/12 اختبار أخضر، لكن الجرة fat غير مُتحقق منها + EDGE_BACKEND_ALLOW_INSECURE_TRANSPORT=true لازم
العقود والحدود (الجزء الأخطر)
العقد
الحكم
@ApplicationModule
❌ 5 وحدات بلا تصريح: provider, availability, notifications, ledger, disputes ⇒ Modulith 默认 يمنحها وصولاً غير مقيّد
إنفاذ الحدود
⚠️ ModulithVerificationTest يُعطّل نفسه على JDK 26+ (assumeTrue(feature < 26)) — يوم ترفع Java، الصمت يلتهم كل الحدود
تغطية OpenAPI
❌ 91/125 = 73% — 34 عملية بلا @Operation (كل الـadmin + payments + pricing + disputes + ledger)
شكل المال
❌ ListingResponse فيه price+currency، ListingSummary فيه price فقط ⇒ لا تنسيق موحّد
problem+json
✅ متسق + traceId شرطي (يتطلب X-Correlation-ID)
الترقيم
⚠️ pageNumber/pageSize/last مخصص — بلا first/sort، والفهرسة من 0
الأعداد
⚠️ enums بالاسم (PENDING) بلا @JsonValue
GraphQL
⚠️ عقد ثانٍ بمعالجة أخطاء خارج GlobalExceptionHandler — لا تدمجه في عميل واحد
القصص (ما سيرتبط به الفرونت)
القصة
الحكم
تصفّح/بحث/عرض لوحة
⚠️ بلا صور/موقع/تقييم/رابط مزوّد على البطاقة، بلا فئات، sort=price يرجع 400
إنشاء حساب
❌ غير موجود — لا مسار كتابة auth_users في كود الإنتاج إطلاقاً
«كن مزوّداً»
❌ ترقية الدور تكتب users.role فقط (UserService.java:205 → User.java:80)، وادعاء roles في JWT يُسكب من auth_authorities (SecurityConfig.java:609,380) ⇒ ترقية بلا صلاحية
نشر عرض + توفّر + تسعير
✅ مكتمل
حجز → دفع
❌ الحجز يعمل، الدفع لا يكتمل أبداً بلا Stripe (المخرج الوحيد confirmIntent إداري)
تقييم
❌ مشروط بحجز COMPLETED — وهو مستحيل بلا دفع ⇒  dead end
محادثات/إشعارات
⚠️ تعمل، بلا مرفقات، بلا حد إرسال، بلا push
صورة لوحة لزائر مجهول
❌ GET /api/v1/media/** يُطابَق بـanyRequest().authenticated()
مجتمع/إشراف
✅ كامل
SEO
⚠️ sitemap.xml = 503 حتى绑定 MARKETPLACE_CATALOG_SEO_PUBLIC_SITE_BASE_URL
عيب حقيقي مكتشف
⚠️ إلغاء حجز له intent غير مدفوع ⇒ PROCESSING→REFUNDED غير قانوني ⇒ يرمي داخل @ApplicationModuleListener بعد COMMIT ⇒ الدفع يعلق + إعادة محاولة Modulith لا تنتهي
ثالثاً: جواب سؤالك — «هل يصبح أي برنامج منصة بمجرد ربط فرونت اند؟»
لا. وهذا المستودع نفسه هو الدليل المضاد: الباك اند يملك ~90% من سطح المنصة (125 عملية، 22 وحدة، 70 ترحيل، عمود أحداث كامل، problem+json، أدوار) — لكنه ينقصه بالضبط الطبقة التي تحوّل سطحاً إلى منصة:

دورة حياة الهوية — لا تسجيل، لا كلمة مرور، لا تحقق بريد، لا حذف حساب، لا MFA. مستخدم جديد لا يستطيع الدخول.
حلقة المال المغلقة — لا PSP ⇒ لا إتمام دفع ⇒ لا تأكيد تلقائي، لا قيد دفتر أستاذ، لا استحقاق تقييم.
قناة التسليم — لا صورة للزائر المجهول، لا push.
ربط فرونت اند الآن = واجهة جميلة فوق نظام لا يستطيع فيه مستخدم واحد أن يسجّل، ولا تُكتمل فيها دفعة واحدة، ولا يرى زائر مجهول صورة واحدة. الـ12 حاجزاً أعلاه كلها في هذه الطبقة تحديداً — لا في طبقة الميزات.
الطريق الواقعي: الواجهة تُبنى وتُختبر الآن على الـstaging (المسارات القراءة + الاستكشاف جاهزة 100%؛ الكتابة تحتاج إنشاء الحساب)، وتُغلق الحواجز بالترتيب: تسجيل ← صلاحيات الدور ← Stripe ← media public ← push/فئات.



إعلان §0: [الملف الحاكم]: docs/security/client-hosting-strategy-plan.md + docs/frontend-dev-oauth-setup.md — [النواة]: قراءة/قياس فقط، صفر تعديل — [أثر على الحدود]: لا — [دين]: لا.

الإجابة المختصرة: الباك اند جاهز لاستقبال الفرونت اند لمعظم وظائف المنصة، لكن «كامل الوظائف» غير متحقق الآن — هناك ثلاث فجوات وظيفية حقيقية وبوابة تشغيلية واحدة.

ما قيّسته بنفسي: قرأت SYSTEM.md و`PROJECT_MAP.md` و`docs/security/client-hosting-strategy-plan.md` و`docs/frontend-dev-oauth-setup.md`، وجلبت حياً /v3/api-docs من staging (HTTP 200)، وفحصت الكود والـ`application.yml`.

الجاهز (بأدلة):
- تغطية نطاقية واسعة: 32 متحكماً عبر كل النطاقات (catalog, search, booking, payments, pricing, reviews, messaging, community, realestate, geo, media, provider, availability, notifications, ledger, disputes, admin) + GraphQL + عقد المصادقة OAuth2/OIDC كامل — مسارات staging موثقة حياً (القياس المسجّل في PROJECT_MAP.md: 108 مساراً).
- المصادقة جاهزة للفرونت اند: عميل staging خاص marketplace-web-staging مسجّل ومختبَر حياً، مع redirect http://localhost:3000/api/auth/callback/marketplace-web — الإعداد كله في docs/frontend-dev-oauth-setup.md (صفر عمل على خوادم المطوّرين).
- CORS مربوط بـ`CORS_ALLOWED_ORIGINS` (fail-fast في prod — application-prod.yml:163) مع credentials و`Authorization` و`X-API-Version` و`Idempotency-Key` مسموحة (SecurityConfig.java:243-258).
- عقد أخطاء موحّد RFC 7807 بـ`application/problem+json` موثّق في docs/api/error-contract.md — الفرونت اند يستطيع بناء معالجة أخطاء واحدة لكل المنصة.
- WebSocket STOMP على /ws + /topic (WebSocketConfig.java:23-29)، وواجهة Swagger حية على `/v3/api-docs`، وبوابة توافق OpenAPI تمنع كسر العقد.

الفجوات (لا تجعل «كامل الوظائف» متحققاً):
1. لا يوجد endpoint لتسجيل مستخدم جديد — لا /auth/register ولا /signup`؛ `createUser عبر JdbcUserDetailsManager موجود فقط كـ bean واختبارات (SecurityConfig.java:377-381)، ولا متحكم ينشئ حساباً للجمهور. «تسجيل جديد» في الفرونت اند سيفشل الآن. كذلك لا استرجاع كلمة مرور (forgot/reset غير موجود).
2. `CORS_ALLOWED_ORIGINS` يجب ضبطه على أصل الفرونت اند (مثل http://localhost:3000) — القيمة الافتراضية في dev هي https://marketplace.com (application.yml:472)، وإلا تُرفض كل الطلبات المتصفحية. إعداد لا كود.
3. `marketplace-edge` (بوابة BFF) غير منشورة فعلياً كخدمة إنتاج — نشرها معطّل ببوابة PORT/probes و`cacheKey` (سجل PROJECT_MAP.md دمج #363/#366)، ومسارها /api/** لا يمرّر /ws — فإن أردت المرور عبر البوابة فـWebSocket لن يعمل من خلفها. الاتصال المباشر بالباك اند يعمل.
4. **AI chat معطّل بيئياً** (SPRING_AI_MODEL_CHAT=none — runbook §9): إن كان لدى الفرونت اند ميزة محادثة ذكاء اصطناعي فلن تعمل قبل تفعيل المتغير واختيار مزوّد.

أصغر خطوة تالية: إن كان المطلوب «تسجيل جديد» للمستخدمين، فذلك تغيير وظيفي يحتاج خطة/بوابة (بوابات §7 لا تُبدأ بلا كلمة صريحة) — كلمة واحدة تكفي لأفتحها كـPR. أما الباقي (CORS env + بيانات staging) فجاهز الآن لارتباط الفرونت اند.


## الحكم: نعم — جاهز، مع 6 فجوات يجب أن تعرفها قبل أن تعد الفرونت

الملف الحاكم: docs/security/client-hosting-strategy-plan.md §4/§7 (مصفوفة الأنماط + بوابة C) + docs/frontend-dev-oauth-setup.md · النواة: الأمن/البيانات/الإعدادات · أثر الحدود: صفر (هذا قياس، لا إجراء مُغيِّر — لم أعدّل شيئاً)

### مقيس حي الآن (2026-09-25)
| القناة | prod | staging |
|---|---|---|
| OIDC discovery | 200 | 200 |
| /v3/api-docs | 200 — 108 مساراً / 125 عملية / 30 متحكماً | 200 — نفس الـ108 |
| liveness + readiness | UP / UP | UP / UP |

- مصادقة الفرونت جاهزة مسبقاً: عميل BFF سري marketplace-web-staging على staging (callback http://localhost:3000/api/auth/callback/marketplace-web) + marketplace-bff على الإنتاج. صفر كود/وحدة/متغير جديد — الإعداد + CORS_ALLOWED_ORIGINS فقط.
- التغطية الوظيفية كاملة: كتالوج+بحث، مزوّدون+توفّر+تسعير، دورة حجز كاملة، تقييمات، محادثات، إشعارات، وسائط S3، جغرافيا، مجتمع (عضوية/تغذية/منشورات/تعليقات/تقارير/إشراف)، عقار (تفاصيل/اكتمال/ترويج/تحليلات مشاهدة)، leads، نزاعات، دفتر أستاذ، لوحة admin كاملة (14 عملية)، SEO.

### الفجوات الصادقة (ما لا يعمل اليوم)
1. الواقع الزمني WebSocket مبنيّ لكنه غير قابل للوصول بمتوكن JWT: WebSocketConfig.java:12 يفعّل @EnableWebSocketMessageBroker ويسجّل /ws (والحبة موجودة ⇒ Optional<SimpMessagingTemplate> ممتلئ)، لكن /ws ليس ضمن securityMatcher السلسلة 2 (تغطي /api/** و/actuator/** فحسب) فيسقط على السلسلة 3 (formLogin) ⇒ مصافحة HTTP ترجع 302 إلى /login بدل 101 Switching Protocols ⇒ لا يستطيع عميل الاتصال ⇒ الإشعارات بـpoll لا push.
2. الدفع محاكاة لا حقيقي: لا مفاتيح Stripe في بيئة الإنتاج (45 متغيراً) ⇒ pspChannel.getIfAvailable() يسقط للمسار المحلي — لا خصم حقيقي.
3. المحادثة AI مطفأة: SPRING_AI_MODEL_CHAT=none حيّاً.
4. GraphQL: introspection معطّل في prod ⇒ العقد هو REST.
5. بوابة edge (الوحدة 22) غير منشورة: 3 محاولات نشر ماتت (17ث/33ث/91ث). لا يعرقل الفرونت لأن BFF الخاص به يتكلم مباشرة مع /oauth2/token.
6. `/actuator/health` المجمّع = 503 DOWN على prod وstaging مع أن liveness/readiness UP ⇒ منسوب صحة DOWN يحتاج تشخيصاً (البوابة التي تستعملها Railway هي liveness ⇒ النشر غير متأثر).

### ديون توثيقية (لا وظيفية)
- PROJECT_MAP.md متأخر: لا سجل لـstaging (#373/#375) ولا runbook الفرونت (#372) ولا Grafana (#378) ولا عزل الاختبارات (#382).
- ARCHITECTURE.md §5 ما زال يحمل افتراض «React SPA على Cloudflare Pages» المنتهي — يُقفل ببوابة C (جهة الاستضافة) بكلمتك.

الخطوة التالية بيدك: أعطِ أصل الفرونت الحقيقي لأضيفه إلى `CORS_ALLOWED_ORIGINS`، وقل لي إن أردت تشخيص الـDOWN في الصحة أو إغلاق فجوة الـWebSocket (وهي الوحيدة التي تحتاج كوداً).
