# وكيل 1 — الهوية والمصادقة وإدارة الصفحات والتنقل (P1 + شريحة P5)

> **تعليمات هذا التكليف للوكيل المستلم:** اقرأ هذه الوثيقة كاملة قبل كتابة الكود. فرع التنفيذ المخصص لهذا التكليف هو `agent/saleor-p1-cms-identity-20261021` من اللقطة المجمدة `b3c75d0584cda3b07722c424195bcd1c1c87bbd7` — commits تنفيذ التكليف وجهتها فرع الوكيل هذا حصراً، ولا تنشئ PR آخر، ولا تنتقل إلى `main` ولا تغيّر الأساس. هذه الوثيقة اليوم محفوظة على PR التكامل والمراجعة (`integration/saleor-full-commerce-20261021`) بعد دمج فروع الوكلاء (سجل دمج الفروع 2026-10-21): لا تُدفع أي commits وكيل إلى PR التكامل — الجمع والمراجعة والدمج مرحلة لاحقة مستقلة بيد المالك بعد تسليم الوكلاء الثلاثة. نفّذ نطاقك باستقلال تام في جلسة تنفيذ متصلة قدر الإمكان؛ لا تنتظر مراجعة/دمج جزء من وكيل آخر، ولا تتواصل مع الوكلاء الآخرين. لا تدمج فرعك ولا تدمج إلى فرع الأساس.

**الهدف:** إكمال الجذور غير المكتملة في نطاق الهوية/المستخدمين والمصادقة، وإكمال صفحات الإدارة والتنقل إذا أمكن إسنادها إلى مالك بيانات حقيقي. هذا تنفيذ كود واختبارات، وليس إنتاج خطة جديدة.

## نطاق مملوك حصريًا

- `marketplace-identity/**` والاختبارات التابعة له؛ `marketplace-console/**` فقط عند الحاجة إلى عمليات إدارة منصة يثبت الفحص أن مالكها الصحيح هو Console؛ ملفات GraphQL/الاختبارات الخاصة بجذورك فقط داخل `marketplace-app`.
- ملف SDL الخاص بك: `marketplace-app/src/main/resources/graphql/agent-1-identity-cms.graphqls`.
- تقريرك: `docs/agent-handoffs/agent-1-completion.md` و`docs/agent-handoffs/agent-1-roots.csv`.
- فرعك: `agent/saleor-p1-cms-identity-20261021` من `b3c75d0584cda3b07722c424195bcd1c1c87bbd7`.

## أولويات العمل الإلزامية

1. **عالج عائق الحوكمة الذي يخص نطاقك:** افحص `address` و`addressValidationRules` وجميع مسارات/كيانات العناوين الفعلية في المستودع، ولا تخلط عنوان المستخدم بعنوان حجز/طلب أو عنوان العقار. أعدّ ADR مقترحًا محددًا لـ`D-ADDR-001` يوضح المالك المعتمد، حقائق البيانات الموجودة، أو سبب الاستبعاد الصريح. بوابة الحوكمة الحالية حمراء بسبب هذا القرار. لا تعدّل `saleor-contract-divergences.csv` المركزي؛ سلّم المقترح منفصلًا.
2. **أصلح اختبار التصفح الحالي للعملاء:** `CustomerGraphQlIntegrationTest.customersFollowsTheForwardKeysetCursorWithoutOverlap` فشل في SHA أقدم لأن الاختبار طلب صفحتين بحجم 2 مع مجموعة seeded لا تضمن 4 صفوف ضمن النطاق. اعزل بيانات الاختبار/الترتيب بطريقة حتمية، ثم أثبت عدم التداخل وصحة `hasPreviousPage` و`hasNextPage`، ولا تُضعف التأكيد إلى مجرد نجاح الاستجابة.
3. نفّذ جذور P1 ذات الحالة `NOT_STARTED` أدناه فوق `identity-spi` ومالكي المصادقة الحاليين، مع إعادة استخدام `SaleorGlobalIdCodec` و`SaleorPermissionCatalog` و`UserGraphQlMapper` حيث تنطبق. الأولويات: token/OIDC/PKCE، استعادة/تأكيد الحساب، عمليات account، customer CRUD، staff CRUD، permission groups، والحقول/الإشعارات ذات المالك الفعلي.
4. نفّذ عمليات صفحات CMS والقوائم المعيّنة أدناه ببيانات حقيقية. الفحص الأولي لا يُظهر في شجرة الملفات اسمًا واضحًا لكيان CMS Page/Menu عام؛ `ProviderPublicPage` و`ProviderBusinessPage` ليستا بديلًا عن صفحات Dashboard. ابحث عن المالك قبل الكتابة. إذا لم يوجد مالك مناسب، استخدم قرارًا موثقًا صغيرًا لاختيار مكان الملكية داخل البنية الحالية (لا تُنشئ قاعدة أو نظامًا ثانيًا). يلزم CRUD، النشر/إلغاء النشر، الترتيب/شجرة navigation، تدقيق، صلاحيات، وترحيل بيانات حقيقي. لا تترك جذور mutations كـno-op.
5. الجذور المحجوبة أسفلها ليست إذنًا لإنشاء داتا وهمية: حوّلها إلى قرار/اقتراح مالك واضح عند غياب capability. `customerType(s)` لا تساوي أدوار المستخدم الحالية تلقائيًا؛ لا تصنع نموذجًا موازيًا بلا مبرر.

## قائمة الجذور `NOT_STARTED` المسندة لك (77 جذرًا)

### P1 — الهوية والمستخدمون والمصادقة (52)

- [ ] `staffNotificationRecipientCreate`
- [ ] `staffNotificationRecipientUpdate`
- [ ] `staffNotificationRecipientDelete`
- [ ] `tokenCreate`
- [ ] `tokenRefresh`
- [ ] `tokenVerify`
- [ ] `tokensDeactivateAll`
- [ ] `externalAuthenticationUrl`
- [ ] `externalObtainAccessTokens`
- [ ] `externalRefresh`
- [ ] `externalLogout`
- [ ] `externalVerify`
- [ ] `requestPasswordReset`
- [ ] `sendConfirmationEmail`
- [ ] `confirmAccount`
- [ ] `setPassword`
- [ ] `passwordChange`
- [ ] `requestEmailChange`
- [ ] `confirmEmailChange`
- [ ] `accountAddressCreate`
- [ ] `accountAddressUpdate`
- [ ] `accountAddressDelete`
- [ ] `accountSetDefaultAddress`
- [ ] `accountRegister`
- [ ] `accountUpdate`
- [ ] `accountRequestDeletion`
- [ ] `accountDelete`
- [ ] `addressCreate`
- [ ] `addressUpdate`
- [ ] `addressDelete`
- [ ] `addressSetDefault`
- [ ] `customerCreate`
- [ ] `customerUpdate`
- [ ] `customerDelete`
- [ ] `customerBulkDelete`
- [ ] `customerBulkUpdate`
- [ ] `customerTypeCreate`
- [ ] `customerTypeUpdate`
- [ ] `customerTypeDelete`
- [ ] `customerTypeAssignAttributes`
- [ ] `customerTypeUnassignAttributes`
- [ ] `customerTypeReorderAttributes`
- [ ] `staffCreate`
- [ ] `staffUpdate`
- [ ] `staffDelete`
- [ ] `staffBulkDelete`
- [ ] `userAvatarUpdate`
- [ ] `userAvatarDelete`
- [ ] `userBulkSetActive`
- [ ] `permissionGroupCreate`
- [ ] `permissionGroupUpdate`
- [ ] `permissionGroupDelete`

### P5 — إدارة الصفحات والتنقل (25)

- [ ] `pageCreate`
- [ ] `pageDelete`
- [ ] `pageBulkDelete`
- [ ] `pageBulkPublish`
- [ ] `pageUpdate`
- [ ] `pageTranslate`
- [ ] `pageTypeCreate`
- [ ] `pageTypeUpdate`
- [ ] `pageTypeDelete`
- [ ] `pageTypeBulkDelete`
- [ ] `pageAttributeAssign`
- [ ] `pageAttributeUnassign`
- [ ] `pageTypeReorderAttributes`
- [ ] `pageReorderAttributeValues`
- [ ] `assignNavigation`
- [ ] `menuCreate`
- [ ] `menuDelete`
- [ ] `menuBulkDelete`
- [ ] `menuUpdate`
- [ ] `menuItemCreate`
- [ ] `menuItemDelete`
- [ ] `menuItemBulkDelete`
- [ ] `menuItemUpdate`
- [ ] `menuItemTranslate`
- [ ] `menuItemMove`

## الجذور `BLOCKED_BY_DECISION` في نطاقك (12)

- [ ] `addressValidationRules`
- [ ] `address`
- [ ] `customerType`
- [ ] `customerTypes`
- [ ] `page`
- [ ] `pages`
- [ ] `pageType`
- [ ] `pageTypes`
- [ ] `menu`
- [ ] `menus`
- [ ] `menuItem`
- [ ] `menuItems`

لا تتعامل مع كل هذه الأسماء على أنها مستحيلة التنفيذ إلى الأبد: ابحث عن مالك بيانات حقيقي، وقدّم قرارًا/تغيير عقد إذا أمكن إسنادها بأمان. بالنسبة للصفحات والتنقل، قد يزيل بناء مالك حقيقي سبب الحجب؛ القرار والتحديث المركزي للجرد يبقيان ضمن التجميع النهائي.

## ترحيلات Flyway المحجوزة

- P1: `V248`–`V252`.
- P5 Pages/Menu: `V281`–`V284` (تقسيم فرعي ضمن الحزمة المجمدة `V281`–`V290`؛ المجال المتبقي `V285`–`V290` محفوظ لباقي P5 في تكليف الوكيل 3).
- استخدم فقط ما تحتاجه من الأرقام، واحتفظ بالـauditing/envers والقيود والفهارس الملائمة. إن احتجت أكثر، سجّل طلب ترحيل ولا تخترع رقمًا.

## اختبارات القبول

- DGS fetcher tests + تشغيل على SDL الحقيقي + PostgreSQL/Flyway/Testcontainers لعمليات الكتابة والقراءة الراجعة.
- اختبار مصفوفة الأدوار: anonymous، CONSUMER، PROVIDER، ADMIN؛ منع IDOR وID مخمّن/نوع global ID خطأ؛ منع الوصول إلى مستخدم/عنوان غير مخول.
- اختبار دورة الحساب: تسجيل/تأكيد/تغيير بيانات/تغيير بريد/تعطيل/حذف وفق الموجود فعلًا، مع replay/idempotency عند انطباقها.
- اختبار صفحات CMS: create/update/publish/unpublish/delete، صلاحيات الدور، واستقرار القراءة بعد الترحيل. القوائم: ترتيب العناصر/نقلها، منع الدورات في الشجرة، وقراءة الشجرة مرة أخرى.
- أصلح فشل `customers` الحتمي، وافحص prefix tests للـglobal ID داخل اختبارات User الخاصة بك: لا تستخدم `startsWith(Base64("User:"))` عندما يحتوي prefix على `=` padding؛ فكّ الترميز وتحقق من tag/UUID.

## ما لا تلمسه

لا تعدّل SDL المركزي أو الجرد/سجل الاختلافات/المصفوفة/source pins، POMs المشتركة، `application.yml`، Modulith allowlist المشتركة، ملفات CI، أو `SYSTEM.md`/`PROJECT_MAP.md`. سلّم مقترحاتها في `docs/agent-handoffs/`.

## سياق موحّد يجب اتباعه

- المستودع الخلفي: `waelhe/app-java-v3`؛ فرع المرشح الحالي: `integration/saleor-full-commerce-20261021`.
- **نقطة البداية المجمدة لكل الوكلاء:** `b3c75d0584cda3b07722c424195bcd1c1c87bbd7`. `main` الحالي وقت القياس `ea0031a97db4452748a47582d3fed2c4d3ec1d52`. المقارنة الحية للفرع أظهرت 57 commit متقدمًا عن `main`، و0 خلفه، و120 ملفًا متغيرًا. لا تستخدم `main` بدل فرع المرشح لأن التغييرات المندمجة سابقًا جزء من أساس العمل.
- ملف جرد الجذور في هذه اللقطة: `docs/governance/campaign/saleor-458-root-inventory.csv`، SHA للـblob `23f10b64107d083eb1ceed44c8325202402a5545`؛ يضم 458 جذرًا. الحالة المسجلة فيه الآن: **22 `INTEGRATED`، و57 `BLOCKED_BY_DECISION`، و379 `NOT_STARTED`**. سجل الجذور نفسه هو مرجع التخصيص، وليس تقارير الحملات النصية إذا تعارضت معه.
- الخطة الحاكمة الوحيدة: `docs/governance/plans/saleor-dashboard-full-commerce-canonical-plan.md`. المراجع المساعدة: `docs/governance/plans/saleor-commerce-domain-matrix.csv`، `docs/governance/plans/saleor-source-pins.json`، `docs/governance/plans/saleor-contract-divergences.csv`، و`docs/governance/campaign/frozen-contracts.md`.
- عقد المصدر: Saleor `3.23.45`، blob `b8fa0dd809081f76dc2834bb5c286561e5783276`؛ Dashboard الأساس `3.23.41`، commit `ae7697bc635c46392d2fca5f88a34970097679f7`.
- لوحة الإدارة مستقلة في `waelhe/marketplace-admin`؛ `main` المقاس `982ecf3667d2242cab941737cb6a0bceaee7ffcb`. لا تغيّر هذا المستودع ضمن هذا التكليف؛ سجّل أي تعديل Dashboard ضروري كطلب تكامل محدد.

### العوائق الحية عند انطلاق الحملة

1. [CI على الرأس `b3c75d0584cda3b07722c424195bcd1c1c87bbd7`](https://github.com/waelhe/app-java-v3/actions/runs/39589081462) فشل في البناء. الخطأ المباشر أربع استدعاءات لـ`Limit.value()` في `marketplace-catalog/src/main/java/com/marketplace/catalog/ProductVariantReadService.java` (الأسطر 122 و175 و184 و186): `org.springframework.data.domain.Limit` لا يملك `value()`. لا يُعد فشل بوابة GraphQL Schema وOpenAPI دليلًا مستقلًا على اختلاف العقد في هذه الجولة؛ تعذر عليها استخراج المخطط/تشغيل التطبيق بسبب فشل التجميع.
2. [اختبار التكامل على الرأس نفسه](https://github.com/waelhe/app-java-v3/actions/runs/39589084355) فشل في مرحلة بناء التطبيق بسبب الخطأ ذاته، ولم تُشغّل مجموعة الاختبارات كاملة.
3. [بوابة الحوكمة على الرأس نفسه](https://github.com/waelhe/app-java-v3/actions/runs/39589087583) فشلت بسبب: `unapproved divergence Address read roots (address/addressValidationRules) — owner decision D-ADDR-001 must identify its pending ADR`.
4. تقرير `docs/governance/campaign/reports/campaign-b6-b.md` يحتوي وصفًا عدديًا لا يتطابق مع سجل الجذور: يدّعي 37 Query `INTEGRATED` و50 `BLOCKED` و11 `NOT_STARTED` (مجموعها 98، لا 90)، بينما السجل الحالي يقول 22/57/379 على كل الجذور الـ458. لا تُعدّلوا سجل الجذور المركزي أو التقرير المشترك؛ اذكروا هذا التعارض في التسليم النهائي ليُصحّح بعد جمع الفروع.
5. في تشغيل أقدم عند SHA `9d053707a73dff0bca7ca286dfd1412a14a1fe64`، فشل `CustomerGraphQlIntegrationTest.customersFollowsTheForwardKeysetCursorWithoutOverlap` لأن الصفحة التالية أعادت عنصرًا واحدًا بدل اثنين؛ وفشل اختبارا Attribute بسبب افتراض بادئة Base64 تتضمن padding؛ كما فشل seed في `PromotionVoucherGraphQlIntegrationTest` بقيد `promotions_type_chk`. هذه أدلة تاريخية تحتاج إعادة قياس بعد إصلاح التجميع، وليست ادعاءً بأن الاختبارات الحالية قد أُعيد تشغيلها.

### بروتوكول تنفيذ مستقل — لا يوجد اتصال أو مدير بين الوكلاء

1. أنشئ فرعك فقط انطلاقًا من `b3c75d0584cda3b07722c424195bcd1c1c87bbd7`، ولا تعدّل فرع المرشح أو فرع زميل. أسماء الفروع: `agent/saleor-p1-cms-identity-20261021`، `agent/saleor-p2-p3-catalog-pricing-20261021`، `agent/saleor-p4-p5-p6-orders-events-20261021` بحسب التكليف.
2. هذا PR مفتوح عمدًا بوصفه تكليفك. نفّذ الكود والاختبارات مباشرة في فرع `agent/saleor-p1-cms-identity-20261021` وأضف commits إليه؛ لا تنشئ PR ثانيًا، ولا تدمج هذا PR أو فرعك في فرع الأساس/`main`. بعد اكتمال نطاقك، اترك PR مفتوحًا وسلّم SHA نهائيًا وتقريرًا بالاختبارات الحقيقية؛ يتولى مالك التكامل التجميع والمراجعة المستقلة لاحقًا.
3. جميع الوكلاء يستخدمون أساسًا وعقودًا مشتركة مجمدة؛ لا تعتمد على تغييرات زميل أو تنتظرها. إذا كان البناء الكلي متوقفًا بعطل خارج نطاقك، أكمل ما يمكن اختباره في الوحدات التي تملكها، واكتب دليل العائق. لا تُدخل إصلاحًا من نطاق زميل في commit لك.
4. SDL: ملفك المستقل فقط تحت `marketplace-app/src/main/resources/graphql/agent-<N>-<domain>.graphqls`. POM DGS يقرأ دليل `src/main/resources/graphql` عبر `schemaPaths`. تحقّق من أن codegen/runtime يلتقط الملف، واستخدم `extend type Query`/`extend type Mutation` عند وجود الجذر، ولا تعرّف نوعًا مشتركًا مكررًا. لا تعدّل `schema.graphqls` المركزي ولا الملفات المولدة `com.marketplace.app.graphql.generated.*` يدويًا.
5. الملفات التي لا يملكها أي وكيل منفردًا: `pom.xml` الجذري و`marketplace-app/pom.xml`، `marketplace-app/src/main/resources/graphql/schema.graphqls`، `application.yml` المشترك، إعدادات/قوائم Modulith والأمن المشتركة، ملفات workflows، `SYSTEM.md`، `PROJECT_MAP.md`، ملف الجذور، مصفوفة المجالات، pins وسجل الاختلافات. اكتب مقترحات التعديل المشترك تحت `docs/agent-handoffs/agent-<N>-...` ليُجمع لاحقًا؛ لا تجرِ تعديلًا متعارضًا في الملف المركزي.
6. استخدم SPIs ومالكي البيانات الموجودين. لا تصل إلى مستودع/كيان وحدة أخرى مباشرة، ولا تنشئ قاعدة ثانية أو Saleor backend، ولا تعِد بناء هوية/طلبات/تسعير موجودة. لا تفترض أن جذرًا مكتوبًا في SDL منفذ.
7. قاعدة كل جذر: توقيع Saleor الدقيق من المخطط المثبت، إغلاق نوع مملوك بقدر الإمكان، تنفيذ حقيقي فوق مالك البيانات، validation، صلاحيات ونطاق ملكية عند use-case owner، أخطاء مصنفة، واختبارات موجبة وسالبة. لا silent no-op، ولا نجاح وهمي، ولا ترجمة global ID بتجاهل النوع.
8. الترحيلات الموجودة غير قابلة للتعديل. أعلى ترحيل من العقد المجمد هو V247. أرقامك المحجوزة موضحة في تكليفك فقط؛ لا تستخدم رقمًا خارج نطاقك ولا رقمًا من نطاق وكيل آخر. اختبر Flyway على PostgreSQL حقيقي بـ`ddl-auto=none`.
9. أنشئ `docs/agent-handoffs/agent-<N>-completion.md` و`docs/agent-handoffs/agent-<N>-roots.csv` في فرعك. لا تعدّل الجرد المركزي. لا تكتب `RELEASE_READY` بنفسك؛ هذا القرار بعد الدمج والمراجعة المستقلة.
10. عند تسليم الفرع: اذكر Base SHA، رأس الفرع، الجذور التي أنجزتها بالأسماء، الجذور المتوقفة وسببها، الملفات، ترحيلات Flyway، SPIs المستهلكة، أوامر/نتائج `mvn` المحلية، عدد الاختبارات الناجحة/الفاشلة/المتخطاة، وCI URLs المرتبطة برأسك الفعلي. لا تدّع نجاح run على SHA آخر.
