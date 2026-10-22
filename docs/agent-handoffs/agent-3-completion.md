# الوكيل الثالث — التسليم التصحيحي لـ PR #698

## حالة المستودع
- PR: https://github.com/waelhe/app-java-v3/pull/698 — يبقى مفتوحًا وغير مدموج.
- الفرع: `agent/saleor-p4-p5-p6-orders-events-20261021`، والقاعدة: `integration/saleor-full-commerce-20261021`.
- تمت مزامنة الفرع مع رأس التكامل المقبول `74ffd9144c88ad436bf6e3eb47674ab7174e11fd` كوالد ثانٍ في commit الدمج `6b7a07757c25f4f5bfc04308d96801411a0cd33d`. أصبح الفرع غير متأخر عن `integration`، وPR قابلًا للدمج تقنيًا لكنه يبقى Draft وغير مدموج.
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

## نتائج التحقق، دون مبالغة
- أُعيدت المصالحة مع فرع التكامل الحالي بدل الاكتفاء بفرع متأخر 38 commit. شجرة الكود المدمجة هي `6b7a07757c25f4f5bfc04308d96801411a0cd33d`، وآخر رأس للفرع بما في ذلك اختبارات الحراسة الإضافية هو `0786e1a8f8b3a42a045d0477b7873bd7a9dae412`. والد التكامل هو `74ffd9144c88ad436bf6e3eb47674ab7174e11fd`. فحص المقارنة يبيّن `behind_by=0` وPR `mergeable=true`؛ لم يتم دمج PR إلى الأساس.
- نجح فحص `Saleor Canonical Plan and Schema Inventory` على رأس الدمج الدقيق `6b7a077`: [run 39790978763](https://github.com/waelhe/app-java-v3/actions/runs/39790978763). هذا يشمل التغيير المعتمد من مالك التكامل `4360829` الذي فصل `D-ADDR-001`؛ لم أعدّل سجل الانحراف المركزي بنفسي.
- نجح فحص الجرد على `6b7a077` ([run 39790978763](https://github.com/waelhe/app-java-v3/actions/runs/39790978763)). لكن فحص الجرد على رأس الفرع الحالي `0786e1a` لا يزال `in_progress` عند آخر قراءة ([run 39791546493](https://github.com/waelhe/app-java-v3/actions/runs/39791546493)). لم يظهر تشغيل `CI` أو `Integration Test` مكتمل على SHA `0786e1a` نفسه. نتائج نجاح على رؤوس أخرى ليست دليلًا على نجاح الرأس الحالي؛ لذلك لا أزعم نجاح `mvn clean verify` أو PostgreSQL/Flyway/Testcontainers end-to-end على الرأس الحالي.
- التحديث المركزي `a466d403` في تاريخ التكامل يتضمن إصلاح Spring Data Commons 4.0 الرسمي إلى `Limit.max()` وإصلاحات واجهة اختبارات Spring GraphQL. دمجت إصدار `OrderReadService` مع فحص `limit.isUnlimited()` قبل `limit.max()`; لم أكتب إصلاحًا بديلًا على ملفات الكتالوج. لا توجد ملفات `marketplace-catalog/**` ضمن فرق PR.
- أضيف اختباران صريحان للحراسة قبل فك المعرف: رفض `orderCancel` عند رفض ADMIN، ورفض `checkoutDelete` قبل فك معرف checkout. تبقى الجذور الثمانية في حالة `IMPLEMENTED_UNVERIFIED` حتى ترد نتائج اختبارات على الرأس المرشح الحالي. أما `orderConfirm` فتبقى `BLOCKED_BY_DECISION` لأن الدفع الحالي لا يملك capture حقيقيًا.
- نجاح CodeRabbit/Snyk في قائمة الحالات لا يعادل نجاح Maven؛ لا تُنسب نتائج إلا إلى SHA المشمولة صراحة في التشغيل.

## القرار
لا تدمج PR #698. أطلق طلب مراجعة CodeRabbit مرة واحدة على الرأس النهائي، ولا تنتظر اكتماله. لا تعِد إظهار `orderConfirm` قبل تنفيذ واختبار عقد capture المملوك لـPayments، ولا تدّع اكتمال Saleor end-to-end ما دامت الجذور المحجوبة بلا عقودها الحقيقية.
