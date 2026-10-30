# توجيه تشغيل حملة Saleor — تعريف A0 وتكليفات A1–A7
**تاريخ اللقطة:** 2026-10-28  
**الفرع:** `integration/saleor-full-commerce-20261021`  
**سجل الجذور المستخدم:** [saleor-458-root-inventory.csv](saleor-458-root-inventory.csv) — blob SHA `ec689c643b9c906779e09f3fdaee73885052282a`  
**المخطط الحاكم الوحيد:** [D-SALEOR-FULL-001](../plans/saleor-dashboard-full-commerce-canonical-plan.md)  
**بروتوكول التنفيذ:** [saleor-multi-agent-execution-playbook.md](saleor-multi-agent-execution-playbook.md)
**توجيه التدقيق الدلالي واستخدام المهارات:** [saleor-agent-skills-semantic-audit-directive.md](saleor-agent-skills-semantic-audit-directive.md) — مكمل لهذا التكليف وللبروتوكول، وليس بديلًا عنهما.

> هذا الملف يعرّف هوية الأدوار ونطاق التشغيل من لقطة الجرد الحالية. قوائم الجذور أدناه مستخرجة من CSV، وليست تقديرات أو قوائم يدوية. عند تغير السجل أو رأس المرشح، على A0 إعادة توليد هذه اللقطة قبل بدء دورة تكليف جديدة. لا يتجاوز هذا الملف الخطة الحاكمة أو العقود المجمدة.

## 1. هوية A0 — محددة بوضوح

**A0 = Integration Lead / Contract Steward (قائد التكامل ووصي العقد).**

- **المشغّل الحالي للدور:** ChatGPT / OpenAI في جلسة التنسيق هذه، مسؤول عن توجيه التنفيذ والتحقق والتجميع من خلال GitHub.
- **هوية GitHub الفعلية:** اتصال GitHub المرتبط بهذه الجلسة مصادق باسم `waelhe`. لا يوجد في الأدلة التي فُحصت حساب GitHub أو GitHub App مستقل اسمه A0؛ **A0 اسم دور تشغيلي وليس اسم مستخدم GitHub**. لذلك ستظهر العمليات المنفذة عبر هذا الاتصال تحت هوية GitHub المتصلة، لا تحت حساب وهمي باسم A0.
- **صاحب القرار النهائي والنشر:** مالك المستودع `waelhe`. لا يدمج A0 إلى `main`، ولا يطلق نشرًا إنتاجيًا، ولا يغير pins أو قرارًا معماريًا يحتاج موافقة المالك، من دون تفويض صريح.
- **ما ليس A0:** ليس وكيل مجال إضافيًا، وليس منفذًا منفردًا لكل عمليات التجارة، وليس بديلًا عن المراجعة المستقلة R1–R4.

## 2. التزام A0 قبل إطلاق الوكلاء

على A0 تنفيذ الآتي بالترتيب:

1. **فحص الحالة الحية من GitHub:** إعادة قراءة رأس `integration/saleor-full-commerce-20261021`، و`main`، وPR #723، وحالة CI والمراجعة والتعارضات. لقطة PR السابقة أظهرت `head_sha=6e1116899af2217c7138370317e7cb474ac390c6` و`mergeable=false`؛ هذه معلومة مرجعية سابقة للفحص، وليست إذنًا بالاعتماد على رأس قديم. أعد القياس قبل أي تغيير.
2. **تسوية خط الأساس بأمان:** لا تبدأ فروع وكلاء جديدة قبل معالجة قابلية الدمج والتعارضات. قارن التغييرات في `main` مع تغييرات التكامل ملفًا بملف؛ لا تنفّذ merge أو rebase آليًا أعمى، ولا تُسقط كود التجارة. أنشئ/سجّل `CANDIDATE_BASE_SHA` واحدًا بعد التحقق، واجعل جميع فروع التنفيذ مبنية عليه.
3. **إطلاق تكليفات منفصلة:** أنشئ لكل وكيل فرع/مساحة عمل معزولة من نفس `CANDIDATE_BASE_SHA`، وانشر له رابط هذا الملف وقائمة جذوره. ملفات التكليف التاريخية التي تشير إلى `b3c75d0584cda3b07722c424195bcd1c1c87bbd7` وقوائم 379 جذرًا ليست مصدر اختيار العمل؛ اختيار الجذور الآن من السجل الحالي أدناه فقط.
4. **حراسة الملفات المشتركة:** A0 وحده يدمج SDL fragments إلى SDL المشترك ويشغّل codegen، ويملك ملف الجذور المركزي وسجل الانحرافات والمصفوفة وpins وworkflows و`pom.xml` المشترك وModulith و`SYSTEM.md` و`PROJECT_MAP.md` وتخصيص أرقام Flyway.
5. **تحديث الجرد من الأدلة:** لا تغيّر حالة جذر لأن الوكيل قال «انتهى». اطلب SHA والملفات والأوامر والنتائج وعدد الاختبارات الفعلية، ثم حدّث السجل بعد التحقق. احتفظ بالأدلة لكل جذر.
6. **التجميع حسب الاعتماد:** تحقق من أساس P1/global IDs والصلاحيات أولًا، ثم اجمع P2/P3، وبعدها P4، وP5، وP6 بحسب العقود وناشري الأحداث الحقيقيين. يمكن للوكلاء التطوير بالتوازي فقط عندما تكون واجهاتهم مثبتة.
7. **الإصدار:** عيّن مراجعين مستقلين R1 (العقد)، R2 (الأمن)، R3 (المجالات والبيانات)، R4 (الإصدار). لا يعلن الاكتمال إلا عند استيفاء P7؛ ولا يتم دمج/نشر `main` إلا بأمر المالك.

## 3. لقطة السجل الحالية

| الحالة | العدد | المعنى |
|---|---:|---|
| `INTEGRATED` | 163 | مسجل كمتكامل؛ **ليس وحده** إثبات اكتمال P7 |
| `NOT_STARTED` | 238 | نطاق التنفيذ الجديد |
| `BLOCKED_BY_DECISION` | 57 | لا يُنفذ قبل قرار/ADR معتمد |
| **الإجمالي** | **458** | 90 Query + 342 Mutation + 26 Subscription |

## 4. قواعد مشتركة إلزامية لكل وكيل

1. اقرأ `AGENTS.md` ثم `SYSTEM.md` ثم `PROJECT_MAP.md` ثم الخطة الحاكمة، و`frozen-contracts.md`، وهذا التكليف.
2. نفّذ فقط الجذور المخصصة لك، على الفرع المعزول المبني على `CANDIDATE_BASE_SHA`. لا تفتح PR دمج منفصل لكل شريحة، ولا تدمج `main`، ولا تدمج فرع وكيل آخر.
3. لا تعدّل SDL المركزي ولا الملفات المولدة. سلّم fragments/اقتراحات SDL لـA0 داخل `docs/governance/campaign/sdl/`.
4. لا تعدّل الترحيلات القديمة ولا تخترع رقم Flyway؛ اطلب رقمًا من A0 قبل إنشاء ترحيل.
5. استخدم مالك المجال وSPI القائمين؛ لا تنشئ محرك Saleor أو قاعدة أو مصدر حقيقة ثانٍ، ولا fake/no-op resolvers، ولا استجابة نجاح وهمية.
6. كل جذر منفذ يتطلب توقيعًا صحيحًا، وسلوكًا حقيقيًا، والتحقق والصلاحيات ونطاق الملكية والأخطاء المصنفة، واختبارات تناسب أثره. جذور المال تتطلب idempotency/replay وledger/reconciliation حيث ينطبق.
7. لا تستخدم مهارات `saleor/agent-skills` كسلطة أو بديل عن العقد المثبت. توزيع المعرفة يكون بحسب ملكية الموجات: A2/P2 يستخدم قواعد التوافر للمخزون والكتالوج؛ A3/P3 يستخدم أولوية الخصومات؛ A3/A4 يستخدمان توزيع خصومات الطلب عند حدود الملكية؛ A4/P4 يستخدم قواعد التوافر عند Checkout والشحن. A5/A6 لا يستخدمان `saleor-app` إلا عند وجود حاجة بروتوكول تطبيق/ويبهوك مثبتة؛ وA7 يعتمد Dashboard 3.23.39 لا مهارة storefront لبناء واجهة الإدارة. كل قاعدة تُتحقق من Saleor 3.23.43 ومصدرها واختبارها الأصلي. اتبع التوجيه التكميلي `saleor-agent-skills-semantic-audit-directive.md`، ولا تغيّر pins أو الملفات الموردة تلقائيًا.
8. لا تعلن جذرًا `RELEASE_READY` من داخل فرع الوكيل. يسلّم الوكيل حالة الإنجاز والأدلة؛ A0 يحقق ويحدّث الجرد بعد التجميع والمراجعة.
9. كل وكيل يدقق الجذور `INTEGRATED` التابعة لموجته وفق أبعاد التوافق الدلالي في التوجيه التكميلي، بالتوازي مع تنفيذ الجذور غير المحجوبة. لا توقف الحملة كلها لانتظار اكتمال التدقيق؛ ولا تكرر جرد الـ458 في ملف مستقل.

## 5. تعليمات كل وكيل بحسب أسماء الجذور في الجرد الحالي

## A1 — P1 — الهوية والمستخدمون والصلاحيات والمصادقة

**الحالة من السجل:** INTEGRATED 51 · NOT_STARTED 0 · BLOCKED_BY_DECISION 11

**التكليف:** لا تبدأ جذورًا جديدة من قائمة قديمة. دقّق الأدلة الفعلية لكل جذر INTEGRATED، وعالج جذور BLOCKED عبر حزم قرار/ADR. أعد تصنيف أي جذر فقط من الأدلة والاختبارات.

### الجذور NOT_STARTED — نطاق التنفيذ الجديد
_لا توجد جذور بهذه الحالة في لقطة الجرد الحالية._

### الجذور BLOCKED_BY_DECISION — ممنوع التنفيذ قبل الحسم
`addressValidationRules`, `externalAuthenticationUrl`, `externalObtainAccessTokens`, `externalRefresh`, `externalLogout`, `externalVerify`, `userAvatarUpdate`, `userAvatarDelete`, `permissionGroupCreate`, `permissionGroupUpdate`, `permissionGroupDelete`

### الجذور INTEGRATED — فحص أدلة القبول
`address`, `customers`, `customerType`, `customerTypes`, `permissionGroups`, `permissionGroup`, `me`, `staffUsers`, `user`, `staffNotificationRecipientCreate`, `staffNotificationRecipientUpdate`, `staffNotificationRecipientDelete`
`tokenCreate`, `tokenRefresh`, `tokenVerify`, `tokensDeactivateAll`, `requestPasswordReset`, `sendConfirmationEmail`, `confirmAccount`, `setPassword`, `passwordChange`, `requestEmailChange`, `confirmEmailChange`, `accountAddressCreate`
`accountAddressUpdate`, `accountAddressDelete`, `accountSetDefaultAddress`, `accountRegister`, `accountUpdate`, `accountRequestDeletion`, `accountDelete`, `addressCreate`, `addressUpdate`, `addressDelete`, `addressSetDefault`, `customerCreate`
`customerUpdate`, `customerDelete`, `customerBulkDelete`, `customerBulkUpdate`, `customerTypeCreate`, `customerTypeUpdate`, `customerTypeDelete`, `customerTypeAssignAttributes`, `customerTypeUnassignAttributes`, `customerTypeReorderAttributes`, `staffCreate`, `staffUpdate`
`staffDelete`, `staffBulkDelete`, `userBulkSetActive`


---

## A2 — P2 — الكتالوج والمنتجات والأنواع والخصائص والوسائط

**الحالة من السجل:** INTEGRATED 62 · NOT_STARTED 28 · BLOCKED_BY_DECISION 5

**التكليف:** نفّذ جذور NOT_STARTED فقط. راجع جذور INTEGRATED في نطاقك بحثًا عن فجوات قبول قبل اعتمادها. لا تنفّذ جذور BLOCKED_BY_DECISION قبل صدور قرار معتمد؛ جهّز طلب قرار محدد واستمر بالأعمال المستقلة.

### الجذور NOT_STARTED — نطاق التنفيذ الجديد
`createWarehouse`, `updateWarehouse`, `deleteWarehouse`, `assignWarehouseShippingZone`, `unassignWarehouseShippingZone`, `stockBulkUpdate`, `collectionChannelListingUpdate`, `productBulkTranslate`, `productTranslate`, `productChannelListingUpdate`, `productMediaCreate`, `productVariantReorder`
`productMediaTranslate`, `productMediaUpdate`, `productReorderAttributeValues`, `productVariantStocksCreate`, `productVariantStocksDelete`, `productVariantStocksUpdate`, `productVariantSetDefault`, `productVariantTranslate`, `productVariantBulkTranslate`, `productVariantChannelListingUpdate`, `productVariantChannelListingAvailabilityUpdate`, `productVariantReorderAttributeValues`
`productVariantPreorderDeactivate`, `variantMediaAssign`, `variantMediaUnassign`, `exportProducts`

### الجذور BLOCKED_BY_DECISION — ممنوع التنفيذ قبل الحسم
`warehouse`, `warehouses`, `stock`, `stocks`, `reportProductSales`

### الجذور INTEGRATED — فحص أدلة القبول
`categories`, `category`, `collection`, `collections`, `product`, `products`, `productType`, `productTypes`, `productVariant`, `productVariants`, `attributes`, `attribute`
`productAttributeAssign`, `productAttributeAssignmentUpdate`, `productAttributeUnassign`, `categoryCreate`, `categoryDelete`, `categoryBulkDelete`, `categoryUpdate`, `categoryTranslate`, `collectionAddProducts`, `collectionCreate`, `collectionDelete`, `collectionReorderProducts`
`collectionBulkDelete`, `collectionRemoveProducts`, `collectionUpdate`, `collectionTranslate`, `productCreate`, `productDelete`, `productBulkCreate`, `productBulkDelete`, `productUpdate`, `productMediaDelete`, `productMediaBulkDelete`, `productMediaReorder`
`productTypeCreate`, `productTypeDelete`, `productTypeBulkDelete`, `productTypeUpdate`, `productTypeReorderAttributes`, `productVariantCreate`, `productVariantDelete`, `productVariantBulkCreate`, `productVariantBulkUpdate`, `productVariantBulkDelete`, `productVariantUpdate`, `attributeCreate`
`attributeDelete`, `attributeUpdate`, `attributeBulkCreate`, `attributeBulkUpdate`, `attributeTranslate`, `attributeBulkTranslate`, `attributeBulkDelete`, `attributeValueBulkDelete`, `attributeValueCreate`, `attributeValueDelete`, `attributeValueUpdate`, `attributeValueBulkTranslate`
`attributeValueTranslate`, `attributeReorderValues`


---

## A3 — P3 — التسعير والخصومات والضرائب والقنوات وبطاقات الهدايا والإعدادات التجارية

**الحالة من السجل:** INTEGRATED 8 · NOT_STARTED 59 · BLOCKED_BY_DECISION 18

**التكليف:** نفّذ جذور NOT_STARTED فقط. راجع جذور INTEGRATED في نطاقك بحثًا عن فجوات قبول قبل اعتمادها. لا تنفّذ جذور BLOCKED_BY_DECISION قبل صدور قرار معتمد؛ جهّز طلب قرار محدد واستمر بالأعمال المستقلة.

### الجذور NOT_STARTED — نطاق التنفيذ الجديد
`taxClassCreate`, `taxClassDelete`, `taxClassUpdate`, `taxConfigurationUpdate`, `taxCountryConfigurationUpdate`, `taxCountryConfigurationDelete`, `taxExemptionManage`, `shopDomainUpdate`, `shopSettingsUpdate`, `shopFetchTaxRates`, `shopSettingsTranslate`, `shopAddressUpdate`
`giftCardSettingsUpdate`, `refundSettingsUpdate`, `returnSettingsUpdate`, `giftCardActivate`, `giftCardAssignUser`, `giftCardUnassignUser`, `giftCardBalanceAdjust`, `giftCardCreate`, `giftCardDelete`, `giftCardDeactivate`, `giftCardUpdate`, `giftCardResend`
`giftCardAddNote`, `giftCardBulkCreate`, `giftCardBulkDelete`, `giftCardBulkActivate`, `giftCardBulkDeactivate`, `promotionCreate`, `promotionUpdate`, `promotionDelete`, `promotionRuleCreate`, `promotionRuleUpdate`, `promotionRuleDelete`, `promotionTranslate`
`promotionRuleTranslate`, `promotionBulkDelete`, `saleCreate`, `saleDelete`, `saleBulkDelete`, `saleUpdate`, `saleCataloguesAdd`, `saleCataloguesRemove`, `saleTranslate`, `saleChannelListingUpdate`, `voucherCataloguesAdd`, `voucherCataloguesRemove`
`voucherTranslate`, `voucherChannelListingUpdate`, `voucherCodeBulkDelete`, `exportGiftCards`, `exportVoucherCodes`, `channelCreate`, `channelUpdate`, `channelDelete`, `channelActivate`, `channelDeactivate`, `channelReorderWarehouses`

### الجذور BLOCKED_BY_DECISION — ممنوع التنفيذ قبل الحسم
`taxConfiguration`, `taxConfigurations`, `taxClass`, `taxClasses`, `taxCountryConfiguration`, `taxCountryConfigurations`, `giftCardSettings`, `refundSettings`, `returnSettings`, `giftCard`, `giftCards`, `giftCardCurrencies`
`giftCardTags`, `sale`, `sales`, `taxTypes`, `channel`, `channels`

### الجذور INTEGRATED — فحص أدلة القبول
`voucher`, `vouchers`, `promotion`, `promotions`, `voucherCreate`, `voucherDelete`, `voucherBulkDelete`, `voucherUpdate`


---

## A4 — P4 — checkout والطلبات والمدفوعات والمعاملات والشحن والتسليم

**الحالة من السجل:** INTEGRATED 10 · NOT_STARTED 94 · BLOCKED_BY_DECISION 7

**التكليف:** نفّذ جذور NOT_STARTED فقط. راجع جذور INTEGRATED في نطاقك بحثًا عن فجوات قبول قبل اعتمادها. لا تنفّذ جذور BLOCKED_BY_DECISION قبل صدور قرار معتمد؛ جهّز طلب قرار محدد واستمر بالأعمال المستقلة.

### الجذور NOT_STARTED — نطاق التنفيذ الجديد
`payment`, `payments`, `transaction`, `transactions`, `checkouts`, `checkoutLines`, `orderSettingsUpdate`, `refundReasonReferenceClear`, `returnReasonReferenceClear`, `deliveryOptionsCalculate`, `shippingMethodChannelListingUpdate`, `shippingPriceCreate`
`shippingPriceDelete`, `shippingPriceBulkDelete`, `shippingPriceUpdate`, `shippingPriceTranslate`, `shippingPriceExcludeProducts`, `shippingPriceRemoveProductFromExclude`, `shippingZoneCreate`, `shippingZoneDelete`, `shippingZoneBulkDelete`, `shippingZoneUpdate`, `paymentCapture`, `paymentRefund`
`paymentVoid`, `paymentInitialize`, `paymentCheckBalance`, `transactionCreate`, `transactionUpdate`, `transactionRequestAction`, `transactionRequestRefundForGrantedRefund`, `transactionEventReport`, `paymentGatewayInitialize`, `transactionInitialize`, `transactionProcess`, `storedPaymentMethodRequestDelete`
`paymentGatewayInitializeTokenization`, `paymentMethodInitializeTokenization`, `paymentMethodProcessTokenization`, `draftOrderComplete`, `draftOrderCreate`, `draftOrderDelete`, `draftOrderBulkDelete`, `draftOrderLinesBulkDelete`, `draftOrderUpdate`, `orderAddNote`, `orderCapture`, `orderConfirm`
`orderFulfill`, `orderFulfillmentCancel`, `orderFulfillmentApprove`, `orderFulfillmentUpdateTracking`, `orderFulfillmentRefundProducts`, `orderFulfillmentReturnProducts`, `orderGrantRefundCreate`, `orderGrantRefundUpdate`, `orderLinesCreate`, `orderLineDelete`, `orderLineUpdate`, `orderDiscountAdd`
`orderDiscountUpdate`, `orderDiscountDelete`, `orderLineDiscountUpdate`, `orderLineDiscountRemove`, `orderNoteAdd`, `orderNoteUpdate`, `orderMarkAsPaid`, `orderRefund`, `orderUpdate`, `orderUpdateShipping`, `orderVoid`, `orderBulkCreate`
`invoiceRequest`, `invoiceRequestDelete`, `invoiceCreate`, `invoiceDelete`, `invoiceUpdate`, `invoiceSendNotification`, `checkoutAddPromoCode`, `checkoutBillingAddressUpdate`, `checkoutComplete`, `checkoutCreate`, `checkoutCreateFromOrder`, `checkoutCustomerAttach`
`checkoutCustomerDetach`, `checkoutCustomerNoteUpdate`, `checkoutEmailUpdate`, `checkoutRemovePromoCode`, `checkoutPaymentCreate`, `checkoutShippingAddressUpdate`, `checkoutShippingMethodUpdate`, `checkoutDeliveryMethodUpdate`, `checkoutLanguageCodeUpdate`, `orderCreateFromCheckout`

### الجذور BLOCKED_BY_DECISION — ممنوع التنفيذ قبل الحسم
`orderSettings`, `shippingZone`, `shippingZones`, `homepageEvents`, `draftOrders`, `ordersTotal`, `orderByToken`

### الجذور INTEGRATED — فحص أدلة القبول
`order`, `orders`, `checkout`, `orderCancel`, `orderBulkCancel`, `checkoutDelete`, `checkoutLineDelete`, `checkoutLinesDelete`, `checkoutLinesAdd`, `checkoutLinesUpdate`


---

## A5 — P5 — صفحات الإدارة والتنقل والتطبيقات والامتدادات والويبهوكس والتصدير

**الحالة من السجل:** INTEGRATED 32 · NOT_STARTED 31 · BLOCKED_BY_DECISION 16

**التكليف:** نفّذ جذور NOT_STARTED فقط. راجع جذور INTEGRATED في نطاقك بحثًا عن فجوات قبول قبل اعتمادها. لا تنفّذ جذور BLOCKED_BY_DECISION قبل صدور قرار معتمد؛ جهّز طلب قرار محدد واستمر بالأعمال المستقلة.

### الجذور NOT_STARTED — نطاق التنفيذ الجديد
`exportFile`, `exportFiles`, `webhookCreate`, `webhookDelete`, `webhookUpdate`, `eventDeliveryRetry`, `webhookDryRun`, `webhookTrigger`, `deleteMetadata`, `deletePrivateMetadata`, `updateMetadata`, `updatePrivateMetadata`
`pluginUpdate`, `externalNotificationTrigger`, `fileUpload`, `appCreate`, `appUpdate`, `appDelete`, `appTokenCreate`, `appTokenDelete`, `appTokenVerify`, `appInstall`, `appRetryInstall`, `appDeleteFailedInstallation`
`appFetchManifest`, `appActivate`, `appDeactivate`, `appProblemCreate`, `appProblemDismiss`, `appSelfUpdate`, `appReenableSyncWebhooks`

### الجذور BLOCKED_BY_DECISION — ممنوع التنفيذ قبل الحسم
`webhook`, `webhookEvents`, `webhookSamplePayload`, `translations`, `translation`, `shop`, `plugin`, `plugins`, `appsInstallations`, `apps`, `app`, `appExtensions`
`appExtension`, `_entities`, `_service`, `pageReorderAttributeValues`

### الجذور INTEGRATED — فحص أدلة القبول
`page`, `pages`, `pageType`, `pageTypes`, `menu`, `menus`, `menuItem`, `menuItems`, `pageCreate`, `pageDelete`, `pageBulkDelete`, `pageBulkPublish`
`pageUpdate`, `pageTranslate`, `pageTypeCreate`, `pageTypeUpdate`, `pageTypeDelete`, `pageTypeBulkDelete`, `pageAttributeAssign`, `pageAttributeUnassign`, `pageTypeReorderAttributes`, `assignNavigation`, `menuCreate`, `menuDelete`
`menuBulkDelete`, `menuUpdate`, `menuItemCreate`, `menuItemDelete`, `menuItemBulkDelete`, `menuItemUpdate`, `menuItemTranslate`, `menuItemMove`


---

## A6 — P6 — GraphQL subscriptions والأحداث الحية

**الحالة من السجل:** INTEGRATED 0 · NOT_STARTED 26 · BLOCKED_BY_DECISION 0

**التكليف:** نفّذ جذور NOT_STARTED فقط. راجع جذور INTEGRATED في نطاقك بحثًا عن فجوات قبول قبل اعتمادها. لا تنفّذ جذور BLOCKED_BY_DECISION قبل صدور قرار معتمد؛ جهّز طلب قرار محدد واستمر بالأعمال المستقلة.

### الجذور NOT_STARTED — نطاق التنفيذ الجديد
`event`, `draftOrderCreated`, `draftOrderUpdated`, `draftOrderDeleted`, `orderCreated`, `orderUpdated`, `orderConfirmed`, `orderPaid`, `orderFullyPaid`, `orderRefunded`, `orderFullyRefunded`, `orderFulfilled`
`orderCancelled`, `orderExpired`, `orderMetadataUpdated`, `orderBulkCreated`, `checkoutCreated`, `checkoutUpdated`, `checkoutFullyPaid`, `checkoutFullyAuthorized`, `checkoutMetadataUpdated`, `productVariantDiscountedPriceUpdated`, `productVariantOutOfStockInChannel`, `productVariantBackInStockInChannel`
`productVariantOutOfStockForClickAndCollect`, `productVariantBackInStockForClickAndCollect`

### الجذور BLOCKED_BY_DECISION — ممنوع التنفيذ قبل الحسم
_لا توجد جذور بهذه الحالة في لقطة الجرد الحالية._

### الجذور INTEGRATED — فحص أدلة القبول
_لا توجد جذور بهذه الحالة في لقطة الجرد الحالية._



## 6. A7 — تكامل Dashboard على مستوى المستودع

**المستودع:** [waelhe/marketplace-admin](https://github.com/waelhe/marketplace-admin)  
**النطاق:** تكامل لوحة Dashboard المثبتة مع العقد المنشور من المنصة؛ مسار OIDC/PKCE/session، تسجيل الدخول والخروج، انتهاء الجلسة وتجديدها، الصلاحيات والتنقل، وتعطيل/إظهار القدرات فقط حيث توجد قدرة خلفية مثبتة. اختبر أهم مسارات الكتالوج والعملاء والطلبات والإعدادات عبر Playwright على مرشح التكامل نفسه.

- ابدأ بمراجعة حالة PR #2 وworkflow الانجراف الأسبوعي، ثم افحص الملفات الحالية والعمليات الفعلية التي تستدعيها اللوحة.
- لا تعدّل SDL الخلفي أو الجرد المركزي؛ سلّم قائمة عمليات Dashboard لكل مسار والفجوات إلى A0.
- لا تدّعِ أن اللوحة كاملة لأن البناء ينجح أو لأن القائمة تظهر. كل مسار محتفظ به يحتاج عقدًا وقدرة خلفية واختبارًا فعليًا.

## 7. تقرير التسليم الإلزامي لكل وكيل

سلّم إلى A0 في GitHub (تعليق/ملف handoff مرتبط بالرأس) هذا النموذج:

```text
WORKSTREAM: A<n> / P<n>
CANDIDATE_BASE_SHA:
HEAD_SHA:
ROOTS_IMPLEMENTED (exact root names):
ROOTS_BLOCKED + decision IDs:
ROOTS_WITH_EVIDENCE:
FILES_CHANGED:
SPI / SDL proposals:
FLYWAY migration version allocated by A0:
TEST COMMANDS + exact PASS/FAIL/SKIPPED counts:
AUTHORIZATION / ownership negative tests:
INTEGRATION / Dashboard E2E evidence:
TESTS NOT RUN + reason:
KNOWN GAPS:
COMMITS FOR A0 TO ASSEMBLE:
```

## 8. أول أمر تشغيل

**A0 يبدأ الآن بتسوية أساس الفرع وتثبيت `CANDIDATE_BASE_SHA`.** بعد نشر هذا الـSHA في GitHub، يبدأ A1 تدقيق أدلة P1، ويبدأ A2–A6 عملهم وفق قوائم `NOT_STARTED` أعلاه على فروع منفصلة. تبدأ A7 بمسار Dashboard دون تعديل العقد الخلفي. العوائق المعمارية تُرفع إلى A0 كحزم قرار، بينما تستمر بقية الأعمال غير المعتمدة عليها.

**حد الإصدار:** P7 لا يمر مع جذور/اختلافات حظر غير محسومة، أو غياب اختبارات رحلة Dashboard اللازمة، أو نتائج CI ليست على SHA المرشح النهائي. لا دمج ولا نشر إلا بموافقة المالك الصريحة.
