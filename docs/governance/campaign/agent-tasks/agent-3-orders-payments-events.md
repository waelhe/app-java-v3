# وكيل 3 — الطلبات وإتمام الشراء والدفع والتسليم والأحداث (P4 + بقية P5 + P6)

> **تعليمات هذا PR للوكيل المستلم:** اقرأ هذه الوثيقة كاملة قبل كتابة الكود. هذا PR هو ظرف التكليف وفرع التنفيذ الخاص بك (`agent/saleor-p4-p5-p6-orders-events-20261021`)، وليس PR الدمج النهائي. اكتب commits التنفيذ مباشرة على فرع هذا PR ولا تنشئ PR آخر. ابدأ من اللقطة المجمدة `b3c75d0584cda3b07722c424195bcd1c1c87bbd7` ولا تنتقل إلى `main` أو تغيّر الأساس. نفّذ نطاقك باستقلال تام في جلسة تنفيذ متصلة قدر الإمكان؛ لا تنتظر مراجعة/دمج جزء من وكيل آخر، ولا تتواصل مع الوكلاء الآخرين. لا تدمج هذا PR ولا تدمج إلى فرع الأساس. سيحدث الجمع والمراجعة والدمج في مرحلة لاحقة مستقلة بعد تسليم الوكلاء الثلاثة.

**الهدف:** تنفيذ دورة التجارة المتصلة من checkout إلى order/payment/fulfillment مع آثار مالية صحيحة، وإكمال أحداث GraphQL والسطوح المساندة التي تملك فعليًا مصدر بيانات. لا تعِد تنفيذ قراءات `order(s)` الموجودة؛ ابنِ عليها.

## نطاق مملوك حصريًا

- `marketplace-orders/**`, `marketplace-payments/**`, `marketplace-ledger/**`, `marketplace-delivery/**`, `marketplace-jobs/**` وناشروا Modulith الذين تحتاجهم؛ لا تستخدم مستودعات الوحدات الأخرى مباشرة.
- ملفات GraphQL/الاختبارات الخاصة بجذورك فقط ضمن `marketplace-app`؛ SDL الخاص بك `marketplace-app/src/main/resources/graphql/agent-3-orders-events.graphqls`.
- فرعك: `agent/saleor-p4-p5-p6-orders-events-20261021` من `b3c75d0584cda3b07722c424195bcd1c1c87bbd7`.

## أولويات العمل الإلزامية

1. **استمر من التنفيذ الموجود:** جذرا `order` و`orders` موجودان، فوق `OrderReadSpi`/`OrderReadService`، في commit `ddc8e4801f` كما يوثق سجل الجذور. افحصهما واختبرهما فقط؛ لا تكرر read service أو تغيّر آلة حالة الطلبات الحالية لتتناسب شكليًا مع Saleor.
2. نفّذ Query roots المتبقية للدفع/المعاملات والـcheckout، ثم mutations المسندة، فوق المالكيْن الحاليين للـorders/payments/ledger. يجب الحفاظ على الـcart/checkout/order الحالي، موافقات المستخدم، snapshots غير القابلة للتغيير، ودفتر القيود المالي.
3. **الأموال والعمليات غير القابلة للتكرار:** capture/refund/void/transaction processing/webhooks/retry تتطلب idempotency/replay/concurrency، transactions صحيحة، reconciliation وledger audit. اختبر الرفض قبل أي حركة مالية إذا لم يصح scope/currency/state. لا نجاح وهمي ولا إعادة استخدام mutation بمعنى مختلف.
4. **الاشتراكات P6:** كل الجذور الستة والعشرين أدناه `NOT_STARTED`. افحص ما إذا كانت الأحداث المنشورة موجودة فعليًا من domains/outbox؛ اربط payloads بأحداث حقيقية وscope للمستخدم، ordering/reconnect/duplicate-delivery. لا تنشر أحداثًا مختلقة ولا تستخدم بث STOMP قائمًا كبديل صامت عن عقد GraphQL Subscription. SDL يملك ملفًا منفصلًا؛ إذا تطلب تفعيل WebSocket خاصية مشتركة، ضمّن مقترح property منفصلًا في handoff ولا تعدل `application.yml` المركزي.
5. **P5 المتبقي:** ابدأ بـexport files عبر `marketplace-jobs`، ثم تحقق من وجود آلية outbound webhook delivery على الأحداث الحقيقية. سجل `PaymentWebhookEvent` الموجود هو inbound payment webhook؛ لا تفترض أنه يكفي لعقد Saleor outbound webhook. نفّذ delivery/retry/audit/signature/idempotency فقط فوق owner حقيقي. افحص `metadata` و`fileUpload` و`externalNotificationTrigger` قبل التنفيذ من مصدرها الحقيقي.
6. `app*` و`plugin*` queries/mutations تتعلق بامتدادات/تطبيقات. الاستعلامات الخمسة الأساسية محجوبة حاليًا بسبب عدم وجود Extension Registry؛ لا تصنع Apps/Plugins أو manifest/token fake. ابحث عن registry فعلي، وإلا سجّل طلب ADR واطلب تحويل mutations التابعة إلى `BLOCKED_BY_DECISION` بدل اعتبارها قابلة للتنفيذ لمجرد أنها `NOT_STARTED`.
7. جذر `draftOrders` محجوب لأن آلة الطلب الحالية لا تعرف حالة DRAFT؛ لا تضف draft roots بجانب آلة أخرى. `shippingZone(s)`, `homepageEvents`, `ordersTotal`, `orderByToken` محجوبة لأسباب مالك واضحة؛ لا تنتج نتائج نجاح شكلية. دوّن أثرها على `draftOrder*` mutations وshipping-zone mutations في ملف القرار.

## قائمة الجذور `NOT_STARTED` المسندة لك (159 جذرًا)

### P4 — الطلبات/الدفع/المعاملات/إتمام الشراء والشحن (102)

- [ ] `payment`
- [ ] `payments`
- [ ] `transaction`
- [ ] `transactions`
- [ ] `checkout`
- [ ] `checkouts`
- [ ] `checkoutLines`
- [ ] `orderSettingsUpdate`
- [ ] `refundReasonReferenceClear`
- [ ] `returnReasonReferenceClear`
- [ ] `deliveryOptionsCalculate`
- [ ] `shippingMethodChannelListingUpdate`
- [ ] `shippingPriceCreate`
- [ ] `shippingPriceDelete`
- [ ] `shippingPriceBulkDelete`
- [ ] `shippingPriceUpdate`
- [ ] `shippingPriceTranslate`
- [ ] `shippingPriceExcludeProducts`
- [ ] `shippingPriceRemoveProductFromExclude`
- [ ] `shippingZoneCreate`
- [ ] `shippingZoneDelete`
- [ ] `shippingZoneBulkDelete`
- [ ] `shippingZoneUpdate`
- [ ] `paymentCapture`
- [ ] `paymentRefund`
- [ ] `paymentVoid`
- [ ] `paymentInitialize`
- [ ] `paymentCheckBalance`
- [ ] `transactionCreate`
- [ ] `transactionUpdate`
- [ ] `transactionRequestAction`
- [ ] `transactionRequestRefundForGrantedRefund`
- [ ] `transactionEventReport`
- [ ] `paymentGatewayInitialize`
- [ ] `transactionInitialize`
- [ ] `transactionProcess`
- [ ] `storedPaymentMethodRequestDelete`
- [ ] `paymentGatewayInitializeTokenization`
- [ ] `paymentMethodInitializeTokenization`
- [ ] `paymentMethodProcessTokenization`
- [ ] `draftOrderComplete`
- [ ] `draftOrderCreate`
- [ ] `draftOrderDelete`
- [ ] `draftOrderBulkDelete`
- [ ] `draftOrderLinesBulkDelete`
- [ ] `draftOrderUpdate`
- [ ] `orderAddNote`
- [ ] `orderCancel`
- [ ] `orderCapture`
- [ ] `orderConfirm`
- [ ] `orderFulfill`
- [ ] `orderFulfillmentCancel`
- [ ] `orderFulfillmentApprove`
- [ ] `orderFulfillmentUpdateTracking`
- [ ] `orderFulfillmentRefundProducts`
- [ ] `orderFulfillmentReturnProducts`
- [ ] `orderGrantRefundCreate`
- [ ] `orderGrantRefundUpdate`
- [ ] `orderLinesCreate`
- [ ] `orderLineDelete`
- [ ] `orderLineUpdate`
- [ ] `orderDiscountAdd`
- [ ] `orderDiscountUpdate`
- [ ] `orderDiscountDelete`
- [ ] `orderLineDiscountUpdate`
- [ ] `orderLineDiscountRemove`
- [ ] `orderNoteAdd`
- [ ] `orderNoteUpdate`
- [ ] `orderMarkAsPaid`
- [ ] `orderRefund`
- [ ] `orderUpdate`
- [ ] `orderUpdateShipping`
- [ ] `orderVoid`
- [ ] `orderBulkCancel`
- [ ] `orderBulkCreate`
- [ ] `invoiceRequest`
- [ ] `invoiceRequestDelete`
- [ ] `invoiceCreate`
- [ ] `invoiceDelete`
- [ ] `invoiceUpdate`
- [ ] `invoiceSendNotification`
- [ ] `checkoutAddPromoCode`
- [ ] `checkoutBillingAddressUpdate`
- [ ] `checkoutComplete`
- [ ] `checkoutCreate`
- [ ] `checkoutCreateFromOrder`
- [ ] `checkoutCustomerAttach`
- [ ] `checkoutCustomerDetach`
- [ ] `checkoutCustomerNoteUpdate`
- [ ] `checkoutDelete`
- [ ] `checkoutEmailUpdate`
- [ ] `checkoutLineDelete`
- [ ] `checkoutLinesDelete`
- [ ] `checkoutLinesAdd`
- [ ] `checkoutLinesUpdate`
- [ ] `checkoutRemovePromoCode`
- [ ] `checkoutPaymentCreate`
- [ ] `checkoutShippingAddressUpdate`
- [ ] `checkoutShippingMethodUpdate`
- [ ] `checkoutDeliveryMethodUpdate`
- [ ] `checkoutLanguageCodeUpdate`
- [ ] `orderCreateFromCheckout`

### P5 — التصدير/الويب هوكس/البيانات الوصفية/الملفات/الامتدادات (31)

- [ ] `exportFile`
- [ ] `exportFiles`
- [ ] `webhookCreate`
- [ ] `webhookDelete`
- [ ] `webhookUpdate`
- [ ] `eventDeliveryRetry`
- [ ] `webhookDryRun`
- [ ] `webhookTrigger`
- [ ] `deleteMetadata`
- [ ] `deletePrivateMetadata`
- [ ] `updateMetadata`
- [ ] `updatePrivateMetadata`
- [ ] `pluginUpdate`
- [ ] `externalNotificationTrigger`
- [ ] `fileUpload`
- [ ] `appCreate`
- [ ] `appUpdate`
- [ ] `appDelete`
- [ ] `appTokenCreate`
- [ ] `appTokenDelete`
- [ ] `appTokenVerify`
- [ ] `appInstall`
- [ ] `appRetryInstall`
- [ ] `appDeleteFailedInstallation`
- [ ] `appFetchManifest`
- [ ] `appActivate`
- [ ] `appDeactivate`
- [ ] `appProblemCreate`
- [ ] `appProblemDismiss`
- [ ] `appSelfUpdate`
- [ ] `appReenableSyncWebhooks`

### P6 — الاشتراكات (26)

- [ ] `event`
- [ ] `draftOrderCreated`
- [ ] `draftOrderUpdated`
- [ ] `draftOrderDeleted`
- [ ] `orderCreated`
- [ ] `orderUpdated`
- [ ] `orderConfirmed`
- [ ] `orderPaid`
- [ ] `orderFullyPaid`
- [ ] `orderRefunded`
- [ ] `orderFullyRefunded`
- [ ] `orderFulfilled`
- [ ] `orderCancelled`
- [ ] `orderExpired`
- [ ] `orderMetadataUpdated`
- [ ] `orderBulkCreated`
- [ ] `checkoutCreated`
- [ ] `checkoutUpdated`
- [ ] `checkoutFullyPaid`
- [ ] `checkoutFullyAuthorized`
- [ ] `checkoutMetadataUpdated`
- [ ] `productVariantDiscountedPriceUpdated`
- [ ] `productVariantOutOfStockInChannel`
- [ ] `productVariantBackInStockInChannel`
- [ ] `productVariantOutOfStockForClickAndCollect`
- [ ] `productVariantBackInStockForClickAndCollect`

## الجذور `BLOCKED_BY_DECISION` في نطاقك (22)

- [ ] `orderSettings`
- [ ] `shippingZone`
- [ ] `shippingZones`
- [ ] `homepageEvents`
- [ ] `draftOrders`
- [ ] `ordersTotal`
- [ ] `orderByToken`
- [ ] `webhook`
- [ ] `webhookEvents`
- [ ] `webhookSamplePayload`
- [ ] `translations`
- [ ] `translation`
- [ ] `shop`
- [ ] `plugin`
- [ ] `plugins`
- [ ] `appsInstallations`
- [ ] `apps`
- [ ] `app`
- [ ] `appExtensions`
- [ ] `appExtension`
- [ ] `_entities`
- [ ] `_service`

هذه القائمة تخص الـQueries المحجوبة صراحةً حاليًا؛ افحص الـmutations التابعة المذكورة في أولويات العمل وسلّم مقترحًا لضمها إلى حالة الحجب إذا كانت تعتمد على المالك المفقود نفسه. لا تعدّل الجرد المركزي.

## ترحيلات Flyway المحجوزة

- P4: `V269`–`V280`.
- باقي P5 للـexports/webhook/extension proposals: `V285`–`V290` (بعد تقسيم CMS/Menu `V281`–`V284` للوكيل 1 ضمن حزمة P5 الأصلية `V281`–`V290`).
- P6: `V291`–`V295`.
- ترحيلات موجودة غير قابلة للتعديل. اختبر migration ordering وإعادة التشغيل على PostgreSQL حقيقي.

## اختبارات القبول

- checkout-to-order حقيقي مع read-back، frozen terms/pricing/tax/shipping/currency snapshot، ومعالجة فشل قابلة للاستئناف وidempotency.
- صلاحيات المشتري على checkout الخاص به، وADMIN على أوامر الإدارة، وعدم وصول PROVIDER إلى طلبات الآخرين. اختبار guessed/mistyped global IDs ونطاق البيانات.
- الدفع والدفتر: capture/refund/void/replay/duplicate command/concurrent request/reconciliation وقيود الرصيد والحركات.
- fulfillment/shipping: انتقالات state المسموحة، tracking/quantity/partial refund، منع انتقال غير صالح، واستخدام مزود التسليم الفعلي.
- GraphQL Subscription: authenticated connection، unauthorized topic denial، ترتيب، reconnect، duplicate/replay، وسلوك الإلغاء/الانتهاء؛ كل event payload مستمد من publisher حقيقي.
- Outbound webhooks: signature، retry policy، dead-letter/recovery، idempotency، delivery attempt audit، ومنع تسريب الأسرار.
- خذ بوابات الأسرار وCodeQL/Trivy/Snyk بجدية؛ لا تسجل tokens أو بيانات دفع حساسة.

## ما لا تلمسه

لا تعدّل SDL المركزي، `application.yml`، POMs مشتركة، Modulith allowlist، `.github/workflows/**`، ملفات الجرد/الاختلافات والمصفوفة/pins، `SYSTEM.md` أو `PROJECT_MAP.md`. احتفظ بمقترحات التغيير المشترك في handoff منفصل.

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
2. هذا PR مفتوح عمدًا بوصفه تكليفك. نفّذ الكود والاختبارات مباشرة في فرع `agent/saleor-p4-p5-p6-orders-events-20261021` وأضف commits إليه؛ لا تنشئ PR ثانيًا، ولا تدمج هذا PR أو فرعك في فرع الأساس/`main`. بعد اكتمال نطاقك، اترك PR مفتوحًا وسلّم SHA نهائيًا وتقريرًا بالاختبارات الحقيقية؛ يتولى مالك التكامل التجميع والمراجعة المستقلة لاحقًا.
3. جميع الوكلاء يستخدمون أساسًا وعقودًا مشتركة مجمدة؛ لا تعتمد على تغييرات زميل أو تنتظرها. إذا كان البناء الكلي متوقفًا بعطل خارج نطاقك، أكمل ما يمكن اختباره في الوحدات التي تملكها، واكتب دليل العائق. لا تُدخل إصلاحًا من نطاق زميل في commit لك.
4. SDL: ملفك المستقل فقط تحت `marketplace-app/src/main/resources/graphql/agent-<N>-<domain>.graphqls`. POM DGS يقرأ دليل `src/main/resources/graphql` عبر `schemaPaths`. تحقّق من أن codegen/runtime يلتقط الملف، واستخدم `extend type Query`/`extend type Mutation` عند وجود الجذر، ولا تعرّف نوعًا مشتركًا مكررًا. لا تعدّل `schema.graphqls` المركزي ولا الملفات المولدة `com.marketplace.app.graphql.generated.*` يدويًا.
5. الملفات التي لا يملكها أي وكيل منفردًا: `pom.xml` الجذري و`marketplace-app/pom.xml`، `marketplace-app/src/main/resources/graphql/schema.graphqls`، `application.yml` المشترك، إعدادات/قوائم Modulith والأمن المشتركة، ملفات workflows، `SYSTEM.md`، `PROJECT_MAP.md`، ملف الجذور، مصفوفة المجالات، pins وسجل الاختلافات. اكتب مقترحات التعديل المشترك تحت `docs/agent-handoffs/agent-<N>-...` ليُجمع لاحقًا؛ لا تجرِ تعديلًا متعارضًا في الملف المركزي.
6. استخدم SPIs ومالكي البيانات الموجودين. لا تصل إلى مستودع/كيان وحدة أخرى مباشرة، ولا تنشئ قاعدة ثانية أو Saleor backend، ولا تعِد بناء هوية/طلبات/تسعير موجودة. لا تفترض أن جذرًا مكتوبًا في SDL منفذ.
7. قاعدة كل جذر: توقيع Saleor الدقيق من المخطط المثبت، إغلاق نوع مملوك بقدر الإمكان، تنفيذ حقيقي فوق مالك البيانات، validation، صلاحيات ونطاق ملكية عند use-case owner، أخطاء مصنفة، واختبارات موجبة وسالبة. لا silent no-op، ولا نجاح وهمي، ولا ترجمة global ID بتجاهل النوع.
8. الترحيلات الموجودة غير قابلة للتعديل. أعلى ترحيل من العقد المجمد هو V247. أرقامك المحجوزة موضحة في تكليفك فقط؛ لا تستخدم رقمًا خارج نطاقك ولا رقمًا من نطاق وكيل آخر. اختبر Flyway على PostgreSQL حقيقي بـ`ddl-auto=none`.
9. أنشئ `docs/agent-handoffs/agent-<N>-completion.md` و`docs/agent-handoffs/agent-<N>-roots.csv` في فرعك. لا تعدّل الجرد المركزي. لا تكتب `RELEASE_READY` بنفسك؛ هذا القرار بعد الدمج والمراجعة المستقلة.
10. عند تسليم الفرع: اذكر Base SHA، رأس الفرع، الجذور التي أنجزتها بالأسماء، الجذور المتوقفة وسببها، الملفات، ترحيلات Flyway، SPIs المستهلكة، أوامر/نتائج `mvn` المحلية، عدد الاختبارات الناجحة/الفاشلة/المتخطاة، وCI URLs المرتبطة برأسك الفعلي. لا تدّع نجاح run على SHA آخر.
