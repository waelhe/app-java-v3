# وكيل 2 — الكتالوج والتسعير والخصومات والضرائب (P2 + P3)

> **تعليمات هذا PR للوكيل المستلم:** اقرأ هذه الوثيقة كاملة قبل كتابة الكود. هذا PR هو ظرف التكليف وفرع التنفيذ الخاص بك (`agent/saleor-p2-p3-catalog-pricing-20261021`)، وليس PR الدمج النهائي. اكتب commits التنفيذ مباشرة على فرع هذا PR ولا تنشئ PR آخر. ابدأ من اللقطة المجمدة `b3c75d0584cda3b07722c424195bcd1c1c87bbd7` ولا تنتقل إلى `main` أو تغيّر الأساس. نفّذ نطاقك باستقلال تام في جلسة تنفيذ متصلة قدر الإمكان؛ لا تنتظر مراجعة/دمج جزء من وكيل آخر، ولا تتواصل مع الوكلاء الآخرين. لا تدمج هذا PR ولا تدمج إلى فرع الأساس. سيحدث الجمع والمراجعة والدمج في مرحلة لاحقة مستقلة بعد تسليم الوكلاء الثلاثة.

**الهدف:** إكمال الجذور المتبقية للكتالوج والخصائص والمنتجات/المتغيرات والخصومات والتكوين التجاري، وإصلاح العطل الحالي في مسار الكتالوج قبل التوسع. هذا نطاق التنفيذ الأثقل في دلالات البيانات والأموال؛ لا تختصره إلى إضافة SDL.

## نطاق مملوك حصريًا

- `marketplace-catalog/**`، `marketplace-pricing/**`، و`marketplace-search/**` أو `marketplace-media/**` فقط حيث يثبت العقد الحالي أن العمل مملوك لهما؛ اختبارات هذه الوحدات فقط.
- ملفات GraphQL/اختبارات الجذور المسندة لك فقط في `marketplace-app`، وSDL الخاص بك `marketplace-app/src/main/resources/graphql/agent-2-catalog-pricing.graphqls`.
- فرعك: `agent/saleor-p2-p3-catalog-pricing-20261021` من `b3c75d0584cda3b07722c424195bcd1c1c87bbd7`.

## أولويات العمل الإلزامية

1. **أصلح عائق التجميع الأعلى أولوية:** في `marketplace-catalog/src/main/java/com/marketplace/catalog/ProductVariantReadService.java` تفشل أربع مواضع عند الأسطر 122 و175 و184 و186 بسبب استدعاء `org.springframework.data.domain.Limit.value()` غير الموجود. استخدم واجهة `Limit` الفعلية المضمنة في BOM/إصدارات المشروع (مثل `isLimited()` و`max()` بحسب توقيع الإصدار المثبت)، وحافظ على حدود `pageSize + 1` والتصفح بالـkeyset. لا تخفّض الجودة أو تغير API framework ولا تستبدل SQL keyset بتحميل غير محدود. هذا الإصلاح ضمن ملكيتك لأنه في catalog service.
2. بعد الإصلاح، أعد تشغيل التجميع واختباراتك في CI على رأس فرعك. فشل بوابتي GraphQL Schema/OpenAPI في تشغيل `b3c75d0584cda3b07722c424195bcd1c1c87bbd7` كان نتيجة تعذّر تجميع/تشغيل التطبيق؛ لا تفترض اختلاف عقد حتى تعمل البوابات على كود قابل للبناء.
3. **أصلح اختبارات Global ID القديمة داخل نطاقك.** في `AttributeGraphQlIntegrationTest` حُسب prefix بـ`Base64("Attribute:")` ثم استخدم مع `startsWith`، والحشو `==` يجعل المقارنة غير صحيحة. التغيير المناسب هو فكّ الترميز والتحقق من tag/UUID أو مقارنة encoded expected-ID كامل معروف. افحص اختبارات Attribute وCategory وProduct وProductType وProductVariant وPromotion/Voucher؛ أصلح جميع الأنماط المماثلة التي تملكها.
4. **أصلح اختبار حفظ Promotion/Voucher:** في تشغيل أقدم أخفق `PromotionVoucherGraphQlIntegrationTest.seedPolicyRows` بقيد `promotions_type_chk` لأن عمود `discount_type` يستقبل قيمة enum عددية بدل `PERCENTAGE`/`FIXED`. افحص `Promotion.java` و`Voucher.java` وmapping JPA وترحيل `V223__commercial_pricing_policies.sql`، وأضف تعيينًا نصيًا صريحًا حيث يلزم ثم اختبر Flyway/PostgreSQL؛ لا تغير CHECK أو بيانات الاختبار للتحايل.
5. تابع من الحالة الحالية بدل إعادة تنفيذ ما هو متكامل: `attribute(s)`, `category(ies)`, `product(s)`, `productType(s)`, `productVariant(s)` و`promotion(s)`/`voucher(s)` موجودة في المرشح. راجع واختبر تلك الحدود بينما تبني الجذور غير المكتملة، لا تُنشئ بدائل لخدمات القراءة الموجودة ولا تعِد كتابة الـSPI دون سبب مثبت.
6. ابنِ بقية P2/P3 حسب قائمة الجذور أدناه. للضرائب/القنوات/بطاقات الهدايا/المستودعات: اقرأ المالك والكيانات الفعلية قبل اتخاذ قرار. الضرائب جزء حقيقي من التسعير؛ نفّذها فوق مالك تسعير/تكوين صالح إذا كانت الحدود الحالية تسمح. لا تُنشئ gift card دون مالك مسؤولية مالية/ledger، ولا تنشئ channel أو warehouse على الورق فقط، ولا تعد بتغطية غير موجودة. إذا تعذر قرار مالك، سجّل طلب ADR مع جذور/عمليات Mutation التابعة التي ينبغي أيضًا وسمها `BLOCKED_BY_DECISION` في ترحيل السجل النهائي.

## قائمة الجذور `NOT_STARTED` المسندة لك (143 جذرًا)

### P2 — الكتالوج والمنتجات والأنواع والخصائص (80)

- [ ] `collection`
- [ ] `collections`
- [ ] `createWarehouse`
- [ ] `updateWarehouse`
- [ ] `deleteWarehouse`
- [ ] `assignWarehouseShippingZone`
- [ ] `unassignWarehouseShippingZone`
- [ ] `stockBulkUpdate`
- [ ] `productAttributeAssign`
- [ ] `productAttributeAssignmentUpdate`
- [ ] `productAttributeUnassign`
- [ ] `categoryCreate`
- [ ] `categoryDelete`
- [ ] `categoryBulkDelete`
- [ ] `categoryUpdate`
- [ ] `categoryTranslate`
- [ ] `collectionAddProducts`
- [ ] `collectionCreate`
- [ ] `collectionDelete`
- [ ] `collectionReorderProducts`
- [ ] `collectionBulkDelete`
- [ ] `collectionRemoveProducts`
- [ ] `collectionUpdate`
- [ ] `collectionTranslate`
- [ ] `collectionChannelListingUpdate`
- [ ] `productCreate`
- [ ] `productDelete`
- [ ] `productBulkCreate`
- [ ] `productBulkDelete`
- [ ] `productUpdate`
- [ ] `productBulkTranslate`
- [ ] `productTranslate`
- [ ] `productChannelListingUpdate`
- [ ] `productMediaCreate`
- [ ] `productVariantReorder`
- [ ] `productMediaDelete`
- [ ] `productMediaBulkDelete`
- [ ] `productMediaReorder`
- [ ] `productMediaTranslate`
- [ ] `productMediaUpdate`
- [ ] `productTypeCreate`
- [ ] `productTypeDelete`
- [ ] `productTypeBulkDelete`
- [ ] `productTypeUpdate`
- [ ] `productTypeReorderAttributes`
- [ ] `productReorderAttributeValues`
- [ ] `productVariantCreate`
- [ ] `productVariantDelete`
- [ ] `productVariantBulkCreate`
- [ ] `productVariantBulkUpdate`
- [ ] `productVariantBulkDelete`
- [ ] `productVariantStocksCreate`
- [ ] `productVariantStocksDelete`
- [ ] `productVariantStocksUpdate`
- [ ] `productVariantUpdate`
- [ ] `productVariantSetDefault`
- [ ] `productVariantTranslate`
- [ ] `productVariantBulkTranslate`
- [ ] `productVariantChannelListingUpdate`
- [ ] `productVariantChannelListingAvailabilityUpdate`
- [ ] `productVariantReorderAttributeValues`
- [ ] `productVariantPreorderDeactivate`
- [ ] `variantMediaAssign`
- [ ] `variantMediaUnassign`
- [ ] `exportProducts`
- [ ] `attributeCreate`
- [ ] `attributeDelete`
- [ ] `attributeUpdate`
- [ ] `attributeBulkCreate`
- [ ] `attributeBulkUpdate`
- [ ] `attributeTranslate`
- [ ] `attributeBulkTranslate`
- [ ] `attributeBulkDelete`
- [ ] `attributeValueBulkDelete`
- [ ] `attributeValueCreate`
- [ ] `attributeValueDelete`
- [ ] `attributeValueUpdate`
- [ ] `attributeValueBulkTranslate`
- [ ] `attributeValueTranslate`
- [ ] `attributeReorderValues`

### P3 — التسعير والخصومات والضرائب والقنوات والإعدادات التجارية (63)

- [ ] `taxClassCreate`
- [ ] `taxClassDelete`
- [ ] `taxClassUpdate`
- [ ] `taxConfigurationUpdate`
- [ ] `taxCountryConfigurationUpdate`
- [ ] `taxCountryConfigurationDelete`
- [ ] `taxExemptionManage`
- [ ] `shopDomainUpdate`
- [ ] `shopSettingsUpdate`
- [ ] `shopFetchTaxRates`
- [ ] `shopSettingsTranslate`
- [ ] `shopAddressUpdate`
- [ ] `giftCardSettingsUpdate`
- [ ] `refundSettingsUpdate`
- [ ] `returnSettingsUpdate`
- [ ] `giftCardActivate`
- [ ] `giftCardAssignUser`
- [ ] `giftCardUnassignUser`
- [ ] `giftCardBalanceAdjust`
- [ ] `giftCardCreate`
- [ ] `giftCardDelete`
- [ ] `giftCardDeactivate`
- [ ] `giftCardUpdate`
- [ ] `giftCardResend`
- [ ] `giftCardAddNote`
- [ ] `giftCardBulkCreate`
- [ ] `giftCardBulkDelete`
- [ ] `giftCardBulkActivate`
- [ ] `giftCardBulkDeactivate`
- [ ] `promotionCreate`
- [ ] `promotionUpdate`
- [ ] `promotionDelete`
- [ ] `promotionRuleCreate`
- [ ] `promotionRuleUpdate`
- [ ] `promotionRuleDelete`
- [ ] `promotionTranslate`
- [ ] `promotionRuleTranslate`
- [ ] `promotionBulkDelete`
- [ ] `saleCreate`
- [ ] `saleDelete`
- [ ] `saleBulkDelete`
- [ ] `saleUpdate`
- [ ] `saleCataloguesAdd`
- [ ] `saleCataloguesRemove`
- [ ] `saleTranslate`
- [ ] `saleChannelListingUpdate`
- [ ] `voucherCreate`
- [ ] `voucherDelete`
- [ ] `voucherBulkDelete`
- [ ] `voucherUpdate`
- [ ] `voucherCataloguesAdd`
- [ ] `voucherCataloguesRemove`
- [ ] `voucherTranslate`
- [ ] `voucherChannelListingUpdate`
- [ ] `voucherCodeBulkDelete`
- [ ] `exportGiftCards`
- [ ] `exportVoucherCodes`
- [ ] `channelCreate`
- [ ] `channelUpdate`
- [ ] `channelDelete`
- [ ] `channelActivate`
- [ ] `channelDeactivate`
- [ ] `channelReorderWarehouses`

## الجذور `BLOCKED_BY_DECISION` في نطاقك (23)

- [ ] `warehouse`
- [ ] `warehouses`
- [ ] `stock`
- [ ] `stocks`
- [ ] `reportProductSales`
- [ ] `taxConfiguration`
- [ ] `taxConfigurations`
- [ ] `taxClass`
- [ ] `taxClasses`
- [ ] `taxCountryConfiguration`
- [ ] `taxCountryConfigurations`
- [ ] `giftCardSettings`
- [ ] `refundSettings`
- [ ] `returnSettings`
- [ ] `giftCard`
- [ ] `giftCards`
- [ ] `giftCardCurrencies`
- [ ] `giftCardTags`
- [ ] `sale`
- [ ] `sales`
- [ ] `taxTypes`
- [ ] `channel`
- [ ] `channels`

لا تتعامل مع هذه القائمة كمبرر لإضافة resolvers تعيد قوائم فارغة. افحص إمكانية توفير مالك فعلي أولًا، خصوصًا tax owner. في حالة warehouse/stock وchannel وgift-card liability وsales reporting أو discount catalogue غير المملوك، سلّم قرارًا وأثره على mutations التابعة بدل اختلاق نموذج.

## ترحيلات Flyway المحجوزة

- P2: `V253`–`V260`.
- P3: `V261`–`V268`.
- لا تعدّل ترحيلًا قديمًا ولا تستخدم ترحيلات الوكيل 1 أو 3. إذا احتجت أكثر، اكتب طلب تمديد نطاق.

## اختبارات القبول

- كل Query/Mutation: اختبار DGS حقيقي وتوقيع مطابق للمخطط المثبت؛ اختبار GraphQL على SDL المدمج/fragment؛ تحقق nullable/enum/input/output وعدم وجود no-op.
- DB: PostgreSQL حقيقي على Flyway مع `ddl-auto=none`؛ قيود/فهرسة/soft delete، read-back، تزامن وidempotency للعمليات الجماعية.
- IDs: tag صحيح، raw UUID مرفوض، ID من نوع آخر مرفوض، guessed ID يعيد غيابًا آمنًا، ومقارنة round-trip كاملة.
- Pagination: sort fields المنفذة فقط، cursor مرتبط بحقل الترتيب وID، no-overlap، `hasNextPage` و`totalCount` حقيقيان حسب النطاق، وحد الحجم من `GraphQlProperties`.
- التسعير: decimal/minor units، currency exponent/rounding، overflow، ضريبة/منطقة/عملة، صلاحية العرض، stacking، إعادة الحساب snapshot إن انطبق. لا تستخدم `double` لمبالغ authoritative.
- الصلاحيات: ADMIN/PROVIDER/CONSUMER، نطاق ملكية المورد، include-unpublished، منع قراءة منتج/متغير غير منشور بلا تفويض.
- التغطية: استعد شرط JaCoCo الحالي ولا تخفض gates. اختبر `ProductVariantReadServiceTest` وكل فروع الخدمة، ثم DGS/integration.

## ما لا تلمسه

لا تعدّل SDL المركزي أو `application.yml` المشترك أو POMs/Modulith/CI أو الجرد وسجل الاختلافات والمصفوفة وpins و`SYSTEM.md`/`PROJECT_MAP.md`. الملفات المشتركة لها مقترحات مستقلة في `docs/agent-handoffs/`.

## سياق موحّد يجب اتباعه

- المستودع الخلفي: `waelhe/app-java-v3`؛ فرع المرشح الحالي: `integration/saleor-full-commerce-20261021`.
- **نقطة البداية المجمدة لكل الوكلاء:** `b3c75d0584cda3b07722c424195bcd1c1c87bbd7`. `main` الحالي وقت القياس `ea0031a97db4452748a47582d3fed2c4d3ec1d52`. المقارنة الحية للفرع أظهرت 57 commit متقدمًا عن `main`، و0 خلفه، و120 ملفًا متغيرًا. لا تستخدم `main` بدل فرع المرشح لأن التغييرات المندمجة سابقًا جزء من أساس العمل.
- ملف جرد الجذور في هذه اللقطة: `docs/governance/campaign/saleor-458-root-inventory.csv`، SHA للـblob `23f10b64107d083eb1ceed44c8325202402a5545`؛ يضم 458 جذرًا. الحالة المسجلة فيه الآن: **22 `INTEGRATED`، و57 `BLOCKED_BY_DECISION`، و379 `NOT_STARTED`**. سجل الجذور نفسه هو مرجع التخصيص، وليس تقارير الحملات النصية إذا تعارضت معه.
- الخطة الحاكمة الوحيدة: `docs/governance/plans/saleor-dashboard-full-commerce-canonical-plan.md`. المراجع المساعدة: `docs/governance/plans/saleor-commerce-domain-matrix.csv`، `docs/governance/plans/saleor-source-pins.json`، `docs/governance/plans/saleor-contract-divergences.csv`، و`docs/governance/campaign/frozen-contracts.md`.
- عقد المصدر: Saleor `3.23.43`، blob `b8fa0dd809081f76dc2834bb5c286561e5783276`؛ Dashboard الأساس `3.23.39`، commit `bc75e988da0fa4fbc3d47378a888d859d070746f`.
- لوحة الإدارة مستقلة في `waelhe/marketplace-admin`؛ `main` المقاس ` 982ecf3667d2242cab941737cb6a0bceaee7ffcb`. لا تغيّر هذا المستودع ضمن هذا التكليف؛ سجّل أي تعديل Dashboard ضروري كطلب تكامل محدد.

### العوائق الحية عند انطلاق الحملة

1. [CI على الرأس `b3c75d0584cda3b07722c424195bcd1c1c87bbd7`](https://github.com/waelhe/app-java-v3/actions/runs/39589081462) فشل في البناء. الخطأ المباشر أربع استدعاءات لـ`Limit.value()` في `marketplace-catalog/src/main/java/com/marketplace/catalog/ProductVariantReadService.java` (الأسطر 122 و175 و184 و186): `org.springframework.data.domain.Limit` لا يملك `value()`. لا يُعد فشل بوابة GraphQL Schema وOpenAPI دليلًا مستقلًا على اختلاف العقد في هذه الجولة؛ تعذر عليها استخراج المخطط/تشغيل التطبيق بسبب فشل التجميع.
2. [اختبار التكامل على الرأس نفسه](https://github.com/waelhe/app-java-v3/actions/runs/39589084355) فشل في مرحلة بناء التطبيق بسبب الخطأ ذاته، ولم تُشغّل مجموعة الاختبارات كاملة.
3. [بوابة الحوكمة على الرأس نفسه](https://github.com/waelhe/app-java-v3/actions/runs/39589087583) فشلت بسبب: `unapproved divergence Address read roots (address/addressValidationRules) — owner decision D-ADDR-001 must identify its pending ADR`.
4. تقرير `docs/governance/campaign/reports/campaign-b6-b.md` يحتوي وصفًا عدديًا لا يتطابق مع سجل الجذور: يدّعي 37 Query `INTEGRATED` و50 `BLOCKED` و11 `NOT_STARTED` (مجموعها 98، لا 90)، بينما السجل الحالي يقول 22/57/379 على كل الجذور الـ458. لا تُعدّلوا سجل الجذور المركزي أو التقرير المشترك؛ اذكروا هذا التعارض في التسليم النهائي ليُصحّح بعد جمع الفروع.
5. في تشغيل أقدم عند SHA `9d053707a73dff0bca7ca286dfd1412a14a1fe64`، فشل `CustomerGraphQlIntegrationTest.customersFollowsTheForwardKeysetCursorWithoutOverlap` لأن الصفحة التالية أعادت عنصرًا واحدًا بدل اثنين؛ وفشل اختبارا Attribute بسبب افتراض بادئة Base64 تتضمن padding؛ كما فشل seed في `PromotionVoucherGraphQlIntegrationTest` بقيد `promotions_type_chk`. هذه أدلة تاريخية تحتاج إعادة قياس بعد إصلاح التجميع، وليست ادعاءً بأن الاختبارات الحالية قد أُعيد تشغيلها.

### بروتوكول تنفيذ مستقل — لا يوجد اتصال أو مدير بين الوكلاء

1. أنشئ فرعك فقط انطلاقًا من `b3c75d0584cda3b07722c424195bcd1c1c87bbd7`، ولا تعدّل فرع المرشح أو فرع زميل. أسماء الفروع: `agent/saleor-p1-cms-identity-20261021`، `agent/saleor-p2-p3-catalog-pricing-20261021`، `agent/saleor-p4-p5-p6-orders-events-20261021` بحسب التكليف.
2. هذا PR مفتوح عمدًا بوصفه تكليفك. نفّذ الكود والاختبارات مباشرة في فرع `agent/saleor-p2-p3-catalog-pricing-20261021` وأضف commits إليه؛ لا تنشئ PR ثانيًا، ولا تدمج هذا PR أو فرعك في فرع الأساس/`main`. بعد اكتمال نطاقك، اترك PR مفتوحًا وسلّم SHA نهائيًا وتقريرًا بالاختبارات الحقيقية؛ يتولى مالك التكامل التجميع والمراجعة المستقلة لاحقًا.
3. جميع الوكلاء يستخدمون أساسًا وعقودًا مشتركة مجمدة؛ لا تعتمد على تغييرات زميل أو تنتظرها. إذا كان البناء الكلي متوقفًا بعطل خارج نطاقك، أكمل ما يمكن اختباره في الوحدات التي تملكها، واكتب دليل العائق. لا تُدخل إصلاحًا من نطاق زميل في commit لك.
4. SDL: ملفك المستقل فقط تحت `marketplace-app/src/main/resources/graphql/agent-<N>-<domain>.graphqls`. POM DGS يقرأ دليل `src/main/resources/graphql` عبر `schemaPaths`. تحقّق من أن codegen/runtime يلتقط الملف، واستخدم `extend type Query`/`extend type Mutation` عند وجود الجذر، ولا تعرّف نوعًا مشتركًا مكررًا. لا تعدّل `schema.graphqls` المركزي ولا الملفات المولدة `com.marketplace.app.graphql.generated.*` يدويًا.
5. الملفات التي لا يملكها أي وكيل منفردًا: `pom.xml` الجذري و`marketplace-app/pom.xml`، `marketplace-app/src/main/resources/graphql/schema.graphqls`، `application.yml` المشترك، إعدادات/قوائم Modulith والأمن المشتركة، ملفات workflows، `SYSTEM.md`، `PROJECT_MAP.md`، ملف الجذور، مصفوفة المجالات، pins وسجل الاختلافات. اكتب مقترحات التعديل المشترك تحت `docs/agent-handoffs/agent-<N>-...` ليُجمع لاحقًا؛ لا تجرِ تعديلًا متعارضًا في الملف المركزي.
6. استخدم SPIs ومالكي البيانات الموجودين. لا تصل إلى مستودع/كيان وحدة أخرى مباشرة، ولا تنشئ قاعدة ثانية أو Saleor backend، ولا تعِد بناء هوية/طلبات/تسعير موجودة. لا تفترض أن جذرًا مكتوبًا في SDL منفذ.
7. قاعدة كل جذر: توقيع Saleor الدقيق من المخطط المثبت، إغلاق نوع مملوك بقدر الإمكان، تنفيذ حقيقي فوق مالك البيانات، validation، صلاحيات ونطاق ملكية عند use-case owner، أخطاء مصنفة، واختبارات موجبة وسالبة. لا silent no-op، ولا نجاح وهمي، ولا ترجمة global ID بتجاهل النوع.
8. الترحيلات الموجودة غير قابلة للتعديل. أعلى ترحيل من العقد المجمد هو V247. أرقامك المحجوزة موضحة في تكليفك فقط؛ لا تستخدم رقمًا خارج نطاقك ولا رقمًا من نطاق وكيل آخر. اختبر Flyway على PostgreSQL حقيقي بـ`ddl-auto=none`.
9. أنشئ `docs/agent-handoffs/agent-<N>-completion.md` و`docs/agent-handoffs/agent-<N>-roots.csv` في فرعك. لا تعدّل الجرد المركزي. لا تكتب `RELEASE_READY` بنفسك؛ هذا القرار بعد الدمج والمراجعة المستقلة.
10. عند تسليم الفرع: اذكر Base SHA، رأس الفرع، الجذور التي أنجزتها بالأسماء، الجذور المتوقفة وسببها، الملفات، ترحيلات Flyway، SPIs المستهلكة، أوامر/نتائج `mvn` المحلية، عدد الاختبارات الناجحة/الفاشلة/المتخطاة، وCI URLs المرتبطة برأسك الفعلي. لا تدّع نجاح run على SHA آخر.
