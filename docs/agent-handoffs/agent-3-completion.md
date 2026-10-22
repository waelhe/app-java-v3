# الوكيل الثالث — التسليم التصحيحي لـ PR #698

## حالة المستودع
- PR: https://github.com/waelhe/app-java-v3/pull/698 — يبقى مفتوحًا وغير مدموج.
- الفرع: `agent/saleor-p4-p5-p6-orders-events-20261021`، والقاعدة: `integration/saleor-full-commerce-20261021`.
- آخر رأس للكود والاختبارات: `a18a987eeffdf2e8fd2a784cb1fd053e235e331f`. تعديلات سجل الجذور والتوثيق تلت ذلك ولا تغيّر كود الإنتاج.
- المرجع المثبت: Saleor 3.23.43. إصدارات المشروع المسجلة: Java 25، Spring Boot 4.1.1، Spring Modulith 2.1.1.
- لم أنتظر CI أو CodeRabbit. المراجعة والدمج منفصلان عن التنفيذ.

## التصحيحات التي دُفعت إلى GitHub
1. استعدت ملفات الكتالوج المملوكة للوكيل B واختبارات تكامل GraphQL المتداخلة إلى نسخ فرع التكامل. لا يغير نطاق الوكيل الثالث الآن `ProductVariantReadService` أو `ProductVariantReadSpi` أو اختبارات الكتالوج. أبقيت mocks لـ`OrderCommandSpi` في شرائح DGS الممسوحة بالحزمة حيث يحتاج المحوّل المحقون إلى bean.
2. صححت فحص حد التمرير في `OrderReadService.scrollOrders` إلى `limit == null || limit.isUnlimited() || limit.max() <= 0`. أضفت `OrderReadServiceLimitTest` ليغطي null وunlimited وzero وnegative ويتأكد من عدم استدعاء المستودع للحالات غير الصحيحة. لا يقرأ التطبيق `max()` على unlimited. المرجع الرسمي: https://docs.spring.io/spring-data/relational/reference/data-commons/api/java/org/springframework/data/domain/Limit.html.
3. أزلت `orderConfirm` و`OrderConfirm` من SDL الإضافي ومحوّل DGS واختبارات نجاحه. عقد `OrderPaymentPort` الحالي ينشئ/يعيد PaymentIntent ولا يقدم capture أو transaction/idempotency/replay/reconciliation. لا يصح عرض تأكيد حالة الطلب وحده كنجاح مكافئ لـSaleor. انتقال `OrdersService.confirm` الأصلي باقٍ لمسار المنصة الداخلي؛ أما جذر Saleor فمحجوب إلى أن يقدّم مالك Payments عقد capture واختباراته المالية.
4. `checkout(id: ID)` يعيد null عندما يكون المعرف غائبًا أو فارغًا؛ أما المعرّف غير الفارغ غير الصحيح فيبقى خطأ تحقق. نطاق `catch ResourceNotFoundException` محصور في قراءة الـcheckout من المالك، ولا يخفي أخطاء إثراء السطر باعتبارها checkout غير موجود. أضفت اختبارًا لوحدة الـfetcher لغياب/فراغ المعرف والمعرف المشوّه.
5. يستخدم `CheckoutGraphQlMapper` واجهة الكتالوج الموجودة `ProductVariantReadSpi.findVariantById(id, authentication)` بدل `findVariantSnapshot` التي تخص الوكيل B. إذا أصبحت النسخة غير قابلة للقراءة بعد إضافتها للسلة، يرفض الإسقاط ذلك صراحة. مطلوب لاحقًا عقد مملوك للكتالوج لقراءة مرجع variant محفوظ في السلة مع تعريف الصلاحيات وحالات النشر.
6. تبقى عمليات الدفع/المعاملات، مناطق وطرق الشحن، وفروع P6 للاشتراكات محجوبة عند غياب المالك/العقد المالي/النقل المطلوب. تسعيرة التوصيل ليست إعدادًا لمناطق الشحن. Spring GraphQL يدعم اشتراكات عبر SSE أو WebSocket أو RSocket، ولا يعادل STOMP عقد GraphQL subscription. المراجع: https://docs.spring.io/spring-boot/reference/web/spring-graphql.html وhttps://docs.spring.io/spring-graphql/reference/transports.html.

## سجل الجذور
حدّثت `docs/agent-handoffs/agent-3-roots.csv`:
- `orderConfirm` أصبح `BLOCKED_BY_DECISION` لغياب capture مملوك لـPayments.
- ثمانية جذور فقط ما تزال ضمن المنفذ في نطاق هذا الوكيل، لكنها جميعًا `IMPLEMENTED_UNVERIFIED` إلى أن تتحقق اختبارات مرتبطة بالرأس النهائي. الدليل النصي في الصفوف استُبدل ليزيل ادعاءات نجاح اختبارات الرأس السابق.
- الإجمالي 181 جذرًا: 8 منفذة غير متحققة و173 محجوبة. التوزيع: P4 = 8 منفذة + 101 محجوبة؛ P5 = 46 محجوبة؛ P6 = 26 محجوبة.
- الجذور المنفذة: `orderCancel`, `orderBulkCancel`, `checkout`, `checkoutDelete`, `checkoutLineDelete`, `checkoutLinesDelete`, `checkoutLinesAdd`, `checkoutLinesUpdate`.

## نتائج التحقق، دون مبالغة
- لم يتم إثبات نجاح `mvn clean verify` أو اختبارات PostgreSQL/Flyway/Testcontainers على رأس الكود `a18a987eeffdf2e8fd2a784cb1fd053e235e331f`. أرقام 111/111 و118 اختبارًا في تقارير أقدم لا تثبت هذا الرأس.
- تحقق Action الخاص بـ`Saleor Canonical Plan and Schema Inventory` على هذا الرأس انتهى بالفشل بسبب divergence غير معتمد لقراءات العنوان `address/addressValidationRules`، ويتطلب قرار المالك/ADR لـ`D-ADDR-001`. هذا من حوكمة الخطة المركزية وخارج ملكية الوكيل الثالث؛ لم أتجاوز الحماية بتعديل السجل المركزي من فرع نطاق.
- إعادة الكتالوج إلى خط أساس التكامل تُظهر عائق البناء المعروف في `ProductVariantReadService` حول `Limit.value()`. إصلاح هذا الملف ملك الوكيل B ولا ينبغي نسخه أو تكراره هنا.
- حالة CodeRabbit/Snyk الظاهرة في سجل commit لا تثبت نجاح بناء Maven؛ يلزم ربط كل نتيجة بالـSHA الحالي.

## القرار
لا تدمج PR #698. أطلق طلب مراجعة CodeRabbit مرة واحدة على الرأس النهائي، ولا تنتظر اكتماله. لا تعِد إظهار `orderConfirm` قبل تنفيذ واختبار عقد capture المملوك لـPayments، ولا تدّع اكتمال Saleor end-to-end ما دامت الجذور المحجوبة بلا عقودها الحقيقية.
