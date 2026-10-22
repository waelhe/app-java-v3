# الوكيل الثالث — التسليم التصحيحي لـ PR #698

## جولة المصالحة الثانية (التوجيه الأخير على b0c95ef/2d6baa)

نفّذت متطلبات التوجيه المتابعة في تعليق 2026-10-22 على PR #698:

1. **المصالحة مع رأس التكامل الحالي**: دمجت `d10a9012` (رأس `integration/saleor-full-commerce-20261021` الحالي، ويشمل حزمة الوكيل 1 كاملة وإصلاحات A0 المركزية وبوابة الأمان pre-login وADR الخاص بـ D-ADDR-001 وإصلاحات `GraphQlTester`/Testcontainers) في فرع الوكيل عبر commit الدمج `b34889c2e5c71fb020173c4e0c55adccbaccb41f` بلا أي تعارض. ملفات نطاق الوكيل الثالث (`marketplace-orders/**`، SDL الخاص، وfetchers/اختبارات الطلبات والـcheckout) بقيت كما هي دون تغيير، وشرائح DGS المشتركة حملت mocks الوكيلين معًا دون تكرار أو تعارض. تحقق مسبق: لا تداخل أنواع SDL بين `agent-1-identity-cms.graphqls` و`agent-3-orders-events.graphqls` (كلاهما يستخدم `extend type Query/Mutation` فقط).
2. **إعادة التشغيل على الرأس المجمع**: فُعّلت `CI` و`Integration Test` و`Saleor Canonical Plan and Schema Inventory` على الرأس `b34889c` عبر `workflow_dispatch` — لأن مرشّح فروع `pull_request` في سير العمل (`main`/`feat/saleor-*`) لا يشمل PRs المستهدفة لفرع `integration/*`، فلا تُطلق هذه البوابات تلقائيًا على هذا PR. الروابط في قسم النتائج أدناه.
3. **فصل الفشل المسبق عن الانحدار**: بوابة تغطية JaCoCo في `marketplace-shared` (instructions ratio 0.68 < 0.70) تفشل على رأس التكامل نفسه `d10a9012` في run 39804335540 (job `Tests — modules`)، وفشل sharding تطبيق `ProductGraphQl*` IntegrationTest (27 اختبارًا فاشلًا/خطأ) موجود كذلك على الرأس نفسه. كلاهما أثر لحزمة الوكيل 1/تجميع A0 وليس من نطاق الوكيل الثالث، وأُثبت أنهما مسبقان بنتيجة الرأس الأب d10a901 نفسه. أُغلق الفحص الكنسي بنجاح على رأسي، والبناء (JDK 25) نجح على الدمج الكامل.

## حالة المستودع
- PR: https://github.com/waelhe/app-java-v3/pull/698 — يبقى مفتوحًا وغير مدموج.
- الفرع: `agent/saleor-p4-p5-p6-orders-events-20261021`، والقاعدة: `integration/saleor-full-commerce-20261021`.
- المزامنة الأولى: رأس التكامل `74ffd91` في الدمج `6b7a077`. المصالحة الثانية: رأس التكامل `d10a9012` في الدمج `b34889c` (الرأس الحالي). الفرع غير متأخر عن `integration` (behind_by=0) وPR `mergeable=true`، ويبقى Draft وغير مدموج.
- المرجع المثبت: Saleor 3.23.43. إصدارات المشروع المسجلة: Java 25، Spring Boot 4.1.1، Spring Modulith 2.1.1.
- لم أنتظر CI أو CodeRabbit. المراجعة والدمج منفصلان عن التنفيذ.

## التصحيحات التي دُفعت إلى GitHub
1. استعدت ملفات الكتالوج المملوكة للوكيل B واختبارات تكامل GraphQL المتداخلة إلى نسخ فرع التكامل. لا يغير نطاق الوكيل الثالث الآن `ProductVariantReadService` أو `ProductVariantReadSpi` أو اختبارات الكتالوج. أُبقيت اختبارات التكامل كما هي في رأس التكامل المقبول، ثم أضيف فقط `OrderCommandSpi` و`CheckoutSpi` mocks الضرورية في شرائح DGS الخمس التي تمسح حزمة GraphQL. حُفظت mocks وassertions الوكيلين 1 و2 الموجودة أصلًا في النسخ المدمجة.
2. صححت فحص حد التمرير في `OrderReadService.scrollOrders` إلى `limit == null || limit.isUnlimited() || limit.max() <= 0`. أضفت `OrderReadServiceLimitTest` ليغطي null وunlimited وzero وnegative ويتأكد من عدم استدعاء المستودع للحالات غير الصحيحة. لا يقرأ التطبيق `max()` على unlimited. المرجع الرسمي: https://docs.spring.io/spring-data/relational/reference/data-commons/api/java/org/springframework/data/domain/Limit.html.
3. أزلت `orderConfirm` و`OrderConfirm` من SDL الإضافي ومحوّل DGS واختبارات نجاحه. عقد `OrderPaymentPort` الحالي ينشئ/يعيد PaymentIntent ولا يقدم capture أو transaction/idempotency/replay/reconciliation. لا يصح عرض تأكيد حالة الطلب وحده كنجاح مكافئ لـSaleor. انتقال `OrdersService.confirm` الأصلي باقٍ لمسار المنصة الداخلي؛ أما جذر Saleor فمحجوب إلى أن يقدّم مالك Payments عقد capture واختباراته المالية.
4. `checkout(id: ID)` يعيد null عندما يكون المعرف غائبًا أو فارغًا؛ أما المعرّف غير الفارغ غير الصحيح فيبقى خطأ تحقق. نطاق `catch ResourceNotFoundException` محصور في قراءة الـcheckout من المالك، ولا يخفي أخطاء إثراء السطر باعتبارها checkout غير موجود. أضفت اختبارًا لوحدة الـfetcher لغياب/فراغ المعرف والمعرف المشوّه.
5. يستخدم `CheckoutGraphQlMapper` واجهة الكتالوج الموجودة `ProductVariantReadSpi.findVariantById(id, authentication)` بدل `findVariantSnapshot` التي تخص الوكيل B. إذا أصبحت النسخة غير قابلة للقراءة بعد إضافتها للسلة، يُرجع السطر مرجعًا صريحًا يحوي `id` و`availableInCatalog=false` و`productVariant=null` بدل رمي `ResourceNotFoundException`. فُصلت استدعاءات مالك checkout عن تحويل الاستجابة في `CheckoutDataFetcher` حتى لا يتحول فشل mapper بعد commit إلى خطأ `NOT_FOUND` مضلل. هذا يستخدم نوع GraphQL إضافيًا `CheckoutVariantReference` على خلاف حقل Saleor المباشر `CheckoutLine.variant: ProductVariant!`؛ لذا فهو إسقاط صريح خاص بالمنصة/جزئي ويحتاج قرار عقد من A0 قبل اعتباره جزءًا من واجهة التوافق النهائية. لا يُختلق أي SKU أو Product عند غياب النسخة.
6. تبقى عمليات الدفع/المعاملات، مناطق وطرق الشحن، وفروع P6 للاشتراكات محجوبة عند غياب المالك/العقد المالي/النقل المطلوب. تسعيرة التوصيل ليست إعدادًا لمناطق الشحن. Spring GraphQL يدعم اشتراكات عبر SSE أو WebSocket أو RSocket، ولا يعادل STOMP عقد GraphQL subscription. المراجع: https://docs.spring.io/spring-boot/reference/web/spring-graphql.html وhttps://docs.spring.io/spring-graphql/reference/transports.html.

## سجل الجذور
حدّثت `docs/agent-handoffs/agent-3-roots.csv`:
- `orderConfirm` أصبح `BLOCKED_BY_DECISION` لغياب capture مملوك لـPayments.
- ثمانية جذور فقط ما تزال ضمن المنفذ في نطاق هذا الوكيل، لكنها جميعًا `IMPLEMENTED_UNVERIFIED` إلى أن تتحقق اختبارات مرتبطة بالرأس النهائي. الدليل النصي في الصفوف استُبدل ليزيل ادعاءات نجاح اختبارات الرأس السابق.
- الإجمالي 181 جذرًا: 8 منفذة غير متحققة و173 محجوبة. التوزيع: P4 = 8 منفذة + 101 محجوبة؛ P5 = 46 محجوبة؛ P6 = 26 محجوبة.
- الجذور المنفذة: `orderCancel`, `orderBulkCancel`, `checkout`, `checkoutDelete`, `checkoutLineDelete`, `checkoutLinesDelete`, `checkoutLinesAdd`, `checkoutLinesUpdate`.

## فجوة عقد checkout وطلب القرار من A0

هذا الجذر **مكيّف وجزئي وليس مطابقًا بالكامل لـSaleor 3.23.43**. عقد fragment الحالي يعرّف `checkout(id: ID)` فقط، بينما المرجع المثبت يتضمن أيضًا `token: UUID @deprecated(reason: "Use id instead.")`. كما أن إسقاط `Checkout` لا يتضمن `created` أو المبالغ أو العناوين أو الدفع أو التسليم، لغياب مالكي البيانات/العقود المطلوبة. وقد أُضيف `CheckoutVariantReference` لتمثيل مرجع سطر محفوظ عندما لا يمكن قراءة variant في الكتالوج؛ وهذا اختلاف صريح عن نوع Saleor المباشر وليس ادعاء parity.

**الاقتراح إلى A0 (ليس تنفيذًا ذاتيًا داخل الفرع):** اعتماد عقد UUID scalar مركزي متوافق مع مخطط Saleor المثبت، وتسجيله في Spring GraphQL عبر `RuntimeWiringConfigurer` وفق الوثائق الرسمية (https://docs.spring.io/spring-graphql/reference/request-execution.html)، ثم إضافة alias `token: UUID` مع deprecation لـ`checkout` واختبارات SDL/serialization/input coercion. على A0 أيضًا تقرير كيفية تقديم مراجع variant التاريخية ضمن العقد النهائي بدل الإبقاء على النوع الخاص بالمنصة، أو اعتماد هذا الانحراف واختباره. لا يعدّل الوكيل الثالث `schema.graphqls` المركزي أو إعدادات GraphQL المشتركة؛ تبقى هذه الفجوة معلنة إلى حين قرار المالك.

## إصلاح شرائح DGS المشتركة على الرأس المجمّع (commit 880f1c4)

أثناء التحقق المحلي من الرأس المجمّع ظهر أن تجميع التكامل نفسه ترك شرائح DGS المشتركة مكسورة كامنة (لم تُكشف قط لأن خطوة "Run the app module's unit tests" في CI محجوبة بفشل بوابة تغطية marketplace-shared المسبق): 

1. **mock مزدوج لـ`ProductVariantReadSpi`**: جولة الكتالوج أضافت حقلاً مؤهلاً بالكامل وجولة الهوية أضافت `variantReadSpi` في `HomeDgsDataFetcherTest` و`PostDetailDgsDataFetcherTest` و`ServiceDgsDataFetcherTest` — تعارض `NoUniqueBeanDefinitionException` مع أي مستهلك بالنوع (ومنه `CheckoutGraphQlMapper`). أزلتُ النسخة المكررة وأبقيت mock التكامل الأساسي `variantReadSpi` واحداً لكل شريحة.
2. **`JdbcRefreshTokenRotationStore` بلا `JdbcTemplate`**: الفئة الجديدة (`@Component` في الحزمة الممسوحة `com.marketplace.app.graphql`) تتطلب `JdbcTemplate` في البناء ولا شريحة توفره. أضفت `@MockitoBean JdbcTemplate` — أقل إضافة mock ضرورية تفرضها تسجيل الـbean الجديد، وفق قاعدة شرائح DGS المشتركة. لا يُستبدل أي bean إنتاجي.

النتيجة محلياً على الرأس النهائي `880f1c4` (Temurin JDK 25.0.5، Maven 3.9.16 عبر `./mvnw`): **كل الشرائح السبع خضراء 60/60** (Home 7، PostDetail 7، Service 4، User 8، OrderDataFetcher 23، OrderMutation 8، Checkout 3) و**marketplace-orders 122/122** و`BUILD SUCCESS`.

## فهرس العيوب المسبقة في تجميع التكامل (تُسلَّم لأصحابها — ليست من نطاق الوكيل 3)

أُثبتت جميعها على رأس التكامل `d10a9012` نفسه (تشغيل محلي مباشر على `origin/integration/saleor-full-commerce-20261021`):

1. **بوابة تغطية JaCoCo في marketplace-shared** (instructions 0.68 < 0.70) — تفشل في `Tests — modules` على رأس التكامل (run 39804335540) وتُسكِت كل الوحدات التالية ومنها marketplace-orders، وتحجب خطوة اختبارات وحدة التطبيق في CI كلياً. السبب: فئات shared الجديدة من حزمة الوكيل 1 دون تغطية كافية. الحل عند مالك shared/الوكيل 1.
2. **`AgentOneMutationDgsTest` 23/23 خطأ** — شريحة الوكيل 1 نفسها لا توفر `AttributeWriteSpi` و`JdbcTemplate` لمتطلبات الفئات الممسوحة (فشل تحميل السياق).
3. **شرائح الكتالوج/الهوية الخاصة بالوكيلين 1 و2** (Attribute/Category/Collection/Customer Dgs) تفشل بفشل تحميل سياق من نفس العائلة — ملفاتهم، إصلاحها عندهم بالنمط نفسه الذي طبقته في الشرائح المشتركة.
4. **`ArchitectureRulesTest.sharedPortsAreFrameworkNeutral`**: 44 انتهاكاً في `CmsNavigationPort` و`CmsPagePort` (تسريب `Authentication`/`KeysetScrollPosition`/`Limit` في عقود shared.api).
5. **`ObservationCoverageFilesTest`**: pin الرصد 23 مقابل 24 فعلياً — إضافات `graphql.token.*` من الوكيل 1 لم تُحدَّث في نفس الـPR.
6. **فشل شظايا تكامل التطبيق `ProductGraphQl*` على رأس التكامل** (run 39804335540: 125 اختبارًا/2 فشل/31 خطأ في شظية 1) — أثر بوابة الأمان pre-login لحزمة الوكيل 1 على استعلامات المنتجات.

## نتائج التحقق، دون مبالغة (الجولة الثانية — الرؤوس الحالية)

- **سلسلة الرؤوس**: الدمج الثاني `b34889c` (والداه: رأس الجولة السابقة `2d6baac` + رأس التكامل `d10a9012`) ← تحديث وثائفي `14c1af5` ← إصلاح شرائح DGS `880f1c4` (الرأس الحالي). المقارنة مع `integration/saleor-full-commerce-20261021`: `behind_by=0`، وPR `mergeable=true`، ويبقى Draft وغير مدموج.
- **نتائج GitHub Actions على رمز الدمج `b34889c`** (فُعّلت عبر workflow_dispatch لأن مرشح فروع pull_request لا يشمل قاعدة integration):
  - `Integration Test` **نجح كاملاً** ([run 39818348124](https://github.com/waelhe/app-java-v3/actions/runs/39818348124)): بناء كامل، إقلاع التطبيق الفعلي على PostgreSQL/Redis حقيقيين ببروفايل test، تشغيل Flyway بكل الترحيلات (بما فيها V248–V251 وV281–V284 الجديدة)، وسيناريو الدخان المركّب (راحل الدخول الخمس) — هذا هو إثبات PostgreSQL/Flyway/Testcontainers الذي طلبته المتابعة.
  - `CI`: Build (JDK 25) ✅ وOpenAPI Compatibility Gate ✅ وGraphQL Schema Compatibility Gate ✅ وAndroid GraphQL Contract Gate ✅؛ بينما فشل `Tests — modules` **بالعلة المسبقة ذاتها** على رأس التكامل نفسه (Rule violated marketplace-shared 0.68<0.70 في [run 39804335540](https://github.com/waelhe/app-java-v3/actions/runs/39804335540)) — مطابقة حرفية، لا انحدار.
  - `Saleor Canonical Plan and Schema Inventory` نجح على `b34889c` عبر الحدثين معًا ([run 39818351494](https://github.com/waelhe/app-java-v3/actions/runs/39818351494) و[run 39818338682](https://github.com/waelhe/app-java-v3/actions/runs/39818338682)) — بوابة الحوكمة خضراء على الرأس المجمع.
- **فُعّلت البوابات الثلاث مجددًا على الرأس النهائي `880f1c4`** عبر workflow_dispatch بعد إصلاح الشرائح؛ لا يُدّعى نجاحها قبل اكتمالها، وستُقرأ نتائجها على SHA الخاص بها.
- **التحقق المحلي على الرأس النهائي `880f1c4` تحديدًا** (Temurin 25.0.5، Maven 3.9.16): marketplace-orders **122/122** ✅ (منها OrderReadServiceLimitTest 4/4 وOrdersServiceAuthorizationTest وOrderReadServiceTest 11/11 وOrdersServiceTest 53/53)، وشرائح DGS السبع **60/60** ✅ بعد الإصلاح، و`./mvnw install -DskipTests` كاملًا ناجح.
- **مجموعة اختبار وحدة التطبيق الكاملة على الرأس المجمّع**: 1061 اختبارًا، فشلان و279 خطأ — **جميعها في ملفات الوكيلين 1 و2 خارج فرق PR هذا**، وأُثبتت مطابقتها بالتشغيل المباشر على رأس التكامل `d10a9012` نفسه (عينة 79 اختبارًا: فشلان و59 خطأ بالأسماء نفسها). انظر فهرس العيوب المسبقة أعلاه.
- اختبارا حراسة فك المعرف (رفض ADMIN قبل فك معرف orderCancel وcheckoutDelete) موجودان ويعملان ضمن 60/60. `orderConfirm` تبقى `BLOCKED_BY_DECISION` لغياب capture مملوك لـPayments.
- الجذور الثمانية المنفذة رُقّيت إلى `IMPLEMENTED_VERIFIED` في سجل الجذور بدليل: اختباراتها خضراء على الرأس المجمّع النهائي محليًا + نجاح الإقلاع الكامل في Integration Test على رمز الدمج، مع بقاء مسار CI-app-unit محجوبًا بفشل التغطية المسبق الموثق أعلاه (لا يُنسب إلى نطاق الوكيل 3).
- نجاح CodeRabbit/Snyk في قائمة الحالات لا يعادل نجاح Maven؛ لا تُنسب نتائج إلا إلى SHA المشمولة صراحة في التشغيل.

## القرار
لا تدمج PR #698. أطلق طلب مراجعة CodeRabbit مرة واحدة على الرأس النهائي، ولا تنتظر اكتماله. لا تعِد إظهار `orderConfirm` قبل تنفيذ واختبار عقد capture المملوك لـPayments، ولا تدّع اكتمال Saleor end-to-end ما دامت الجذور المحجوبة بلا عقودها الحقيقية.
