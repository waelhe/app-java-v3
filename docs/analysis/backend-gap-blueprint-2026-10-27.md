# مخطط استكمال الباك اند — قراءة مقيسة مقابل عقد Saleor 3.23.43

[الملف الحاكم]: `docs/governance/plans/saleor-dashboard-full-commerce-canonical-plan.md` (D-SALEOR-FULL-001)
[النواة المعمارية]: طبقة الوحدات (§5) + عقد GraphQL (DGS) + طبقة البيانات (§7)
[البند من المصفوفة §14.2]: مطابقة العقد المرجعي وحدود الوحدات
[أثر على الحدود]: لا تغيير — هذا الملف قياس وتخطيط فقط
[دين]: لا

> **حدود المرجعية:** هذا الملف **قراءة تنفيذية** للخطة الحاكمة، لا خطة بديلة ولا قرار حوكمة.
> كل رقم فيه مقيس من الشجرة والملفات المثبّتة، وكل قرار يبقى في الخطة الحاكمة وسجل الاختلافات.
> عند أي تعارض: الخطة الحاكمة هي الحاكم.

---

## 1. المنهج والتحقق

| المصدر | التحقق |
| --- | --- |
| `docs/governance/plans/saleor-source-pins.json` | Saleor 3.23.43 → 90 Query + 342 Mutation + 26 Subscription = 458 |
| مخطط Saleor المثبّت | جُلب من `raw.githubusercontent.com/saleor/saleor/3.23.43/saleor/graphql/schema.graphql` وطابق بصمة الـpin حرفيًا: `git hash-object` = `b8fa0dd809081f76dc2834bb5c286561e5783276` |
| عقد الباك اند | `marketplace-app/src/main/resources/graphql/*.graphqls` (4 ملفات) |
| التنفيذ الفعلي | عدّ `@DgsQuery` / `@DgsMutation` / `@DgsData(parentType=…)` في `marketplace-app/src/main/java` |
| الجرد القانوني | `docs/governance/campaign/saleor-458-root-inventory.csv` (458 صفًا) |
| سجل الاختلافات | `docs/governance/plans/saleor-contract-divergences.csv` (55 صفًا) |

**نتيجة التحقق المتبادل:** مجموعة الجذور الموسومة `INTEGRATED` في الجرد = مجموعة الجذور المعلنة على العقد **بالتطابق التام** (163 = 163، صفر انحراف في أي اتجاه).
أي: لا يوجد جذر معلن بلا منفّذ، ولا جذر "منجز" غير معلن. العقد والتنفيذ متزامنان؛ النقص كله في **جذور لم تُعلن بعد**.

---

## 2. لوحة الحالة المقيسة (458 جذرًا)

| القياس | العدد | النسبة |
| --- | --- | --- |
| على العقد ومنفَّذ | **163** | 35.6% |
| محجوب بانتظار قرار مالك (ADR) | **57** | 12.4% |
| لم يبدأ | **238** | 52.0% |
| **جذور Saleor غائبة عن العقد** | **295** | 64.4% |
| جذور خاصة بالمنصة (امتداد خارج Saleor) | 18 | 12 استعلام + 6 تحوير |
| أحداث اشتراك منفَّذة | **0 / 26** | 0% |

### 2.1 حسب الموجة

| الموجة | المجموع | منجز | محجوب | لم يبدأ |
| --- | --- | --- | --- | --- |
| P1 | 62 | **51** | 11 | 0 |
| P2 | 95 | **62** | 5 | 28 |
| P3 | 85 | 8 | 18 | 59 |
| P4 | 111 | 10 | 7 | 94 |
| P5 | 79 | **32** | 16 | 31 |
| P6 | 26 | 0 | 0 | 26 |

---

## 3. مصفوفة الفجوات حسب النطاق (مقيسة)

| النطاق | المجموع | على العقد | محجوب | لم يبدأ | الموجة | **نوع الفجوة** | المالك |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Products | 78 | 45 | 5 | 28 | P2 | عقد + نطاق (قنوات/مخازن) | catalog; search; media |
| Users | 53 | 47 | 6 | 0 | P1 | منجز تقريبًا — الباقي قرار | identity; console |
| Orders | 52 | 4 | 5 | 43 | P4 | **عقد** (القدرة موجودة) | orders; payments; ledger; delivery |
| Discounts | 33 | 8 | 2 | 23 | P3 | عقد جزئيًا + نطاق (sales) | pricing |
| Subscriptions | 26 | 0 | 0 | 26 | P6 | نطاق (ناشرون) | ناشرون النطاقات + النقل |
| Checkout | 23 | 6 | 0 | 17 | P4 | **عقد** (القدرة موجودة) | orders; pricing; payments |
| Gift cards | 21 | 0 | 5 | 16 | P3 | **نطاق جديد** | مالك جديد + pricing + ledger |
| Payments | 21 | 0 | 0 | 21 | P4 | **عقد** (القدرة موجودة) | payments; ledger |
| Apps | 21 | 0 | 5 | 16 | P5 | نطاق جديد (سجل امتدادات) | console + registry |
| Pages | 18 | 17 | 1 | 0 | P5 | منجز تقريبًا | console (CMS) |
| Attributes | 17 | 17 | 0 | 0 | P2 | **مكتمل** | catalog |
| Uncategorized | 16 | 0 | 7 | 9 | P5 | قرار + نطاق | إعدادات/i18n/jobs/federation |
| Shipping | 15 | 0 | 2 | 13 | P4 | عقد + نطاق (zones) | delivery; orders |
| Menu | 15 | 15 | 0 | 0 | P5 | **مكتمل** | console (CMS) |
| Taxes | 14 | 0 | 7 | 7 | P3 | نطاق جديد (`TaxRate` جزئي فقط) | pricing + مالك جديد |
| Webhooks | 9 | 0 | 3 | 6 | P5 | نطاق جديد (الواردة موجودة، الصادرة لا) | payments + events |
| Shop | 9 | 0 | 2 | 7 | P3 | قرار (إعدادات المنصة) | console |
| Authentication | 9 | 4 | 5 | 0 | P1 | منجز جزئيًا + قرار | identity; console |
| Channels | 8 | 0 | 2 | 6 | P3 | **نطاق جديد** | مالك قنوات + catalog + pricing |

---

## 4. تصنيف الفجوة — الفرق الذي يحدد التكلفة

### (أ) فجوة عقد فقط — القدرة موجودة في نطاق قائم، والجذر غير معلن
لا تحتاج وحدة جديدة ولا جدولًا جديدًا، بل محوّل GraphQL فوق حالة استخدام قائمة + اختبارات قبول.

**الدليل المقيس:**
- `marketplace-orders/.../OrdersService.java`: `confirm` · `fulfill` · `cancel` · `bulkCancelAsAdmin` · `fulfillFromDelivery`.
- `marketplace-payments/.../PaymentsService.java`: `confirmIntent` · `cancelIntent` · `refundPayment(id, amount)` · `processIntent` · `createOrderIntent`.
- `marketplace-delivery/.../DeliveryService.java` + `CourierOperationsService` (تسليم/إسناد/تسوية).

**النطاقات:** Orders (43) · Payments (21) · Checkout (17) = **81 جذرًا مؤكدة الفئة (أ)** · جزء من Shipping (13) وDiscounts ينتظر حسم القنوات/المناطق.
**الحجم:** 81 جذرًا بصفر وحدات جديدة — **أكبر مكسب بأقل مخاطرة معمارية**.

### (ب) فجوة قرار — الجذر محجوب بانتظار ADR مالك (57 جذرًا / 26 قرارًا)
لا يُكتب كود قبل القرار (قاعدة الخطة §D3: لا سجلات وهمية ولا no-op).
المجموعات: Gift cards (5) · Apps (5) · External auth (5) · Taxes (7) · Warehouse/Stock (4) · Webhooks (3) · Settings (3) · Permission groups (3) · Channels (2) · Sales (2) · Shipping zones (2) · Reporting (2) · Plugins (2) · Federation (2) · Shop (2) · Translation (2) · Avatar (2) · Draft orders (1) · Order-by-token (1) · Homepage events (1) · Page attributes (1).

### (ج) فجوة نطاق جديد — لا مالك بيانات أصلًا
`GiftCard` · `Channel` · `Warehouse/Stock` · `Plugin/Extension` · `TaxClass/TaxConfiguration` · outbound `Webhook` — كلها غير موجودة كأصناف في الشجرة (تحقّق بالبحث عن الأصناف، لا بالانطباع).
`Translation` و`CMS` موجودة جزئيًا (`TranslationWriteService`، `CmsPageTranslation`) فليست فجوة نطاق كاملة.

---

## 5. ما تبقّى في كل موجة (خطة العمل)

| الموجة | العمل المتبقي | الشرط المسبق |
| --- | --- | --- |
| P1 (11) | `customers` · `permissionGroups` · كتابات المجموعات · avatar · external auth | 5 قرارات ADR + تغطية RBAC |
| P2 (33) | إكمال ProductType/attributes/variants + media + ترجمات المنتج | حسم قرار القنوات لتجديدات channel listing |
| P3 (77) | Discounts الباقي · Gift cards · Taxes · Channels · Shop | 18 قرارًا — الموجة **الأكثر تعليقًا بقرارات** |
| P4 (101) | دورة حياة الطلب الكاملة + Payment/Transaction + Shipping + Checkout | لا شيء — تبدأ فورًا فوق الخدمات القائمة |
| P5 (47) | Pages/Menu المتبقي · Apps/Plugins · Webhooks · Uncategorized | 16 قرارًا + تصميم سجل الامتدادات |
| P6 (26) | أحداث الاشتراك (order/checkout/stock) | تصميم الناشرين + تفويض WebSocket |

---

## 6. عيوب حوكمة مقيسة (تُصلح قبل الاعتماد على الأرقام)

1. **12 صفًا محجوبًا بلا `divergence_id`** (warehouse/stocks ×4، taxClass/taxConfiguration/taxCountryConfiguration/taxTypes ×7، addressValidationRules ×1) — المعرّفات مكتوبة في نص `evidence` فقط، والعمود الفارغ يمنع التتبّع الآلي.
2. **`PROJECT_MAP.md` قديم:** يقول إن `#694` هو PR المفتوح الوحيد؛ الحقيقة المقيسة: `#694` مدموج (2026-10-21T01:45Z)، والمفتوح الآن `#723` (draft) و`#741`/`#743`/`#744`/`#746`.
3. **قناة العقد المنشورة متأخرة:** `graphql-contract` v8 (2026-10-24، المصدر `7bc2f5e5`) يحمل **3 جذور Query فقط**، لأن النشر يعمل على `main` وحدها. أي بناء للوحة ضد العقد المنشور اليوم لا يرى جذور Saleor الـ163.
4. **`dashboard_e2e` فارغ في 458 صفًا:** لا يوجد أي جذر مسجّل بقبول رحلة Dashboard — بوابة P7 لا يمكن أن تمرّ بصدق قبل ملئه.

---

## 7. الترتيب الموصى به (تسلسل تابعيات، لا رغبات)

1. **حسم القرارات الـ26** (تفتح 57 جذرًا، وتفتح P1/P3/P5) — بلا كود.
2. **P4 فوق الخدمات القائمة** (Orders → Payments → Checkout): ~81 جذرًا بصفر وحدات جديدة.
3. **إكمال P2** (33) بما لا ينتظر قرار القنوات.
4. **P3 بعد القرارات** (Gift cards/Taxes/Channels كوحدات مملوكة).
5. **P5** (CMS المتبقي + Apps/Webhooks بعد تصميم السجل).
6. **P6** (26 حدثًا) أخيرًا — تعتمد على ناشرين مستقرين.

**تعريف الإنجاز لكل شريحة:** جذر معلن بالتوقيع الحرفي + منفّذ عبر SPI مالك + اختبار DGS + اختبار سلبي للتفويض + صف جرد محدّث `INTEGRATED` + `dashboard_e2e` مسجّل.

---

## 8. ما لا يقوله هذا المخطط

- 458 جذرًا ليست 458 مهمة متساوية: بعضها عائلة واحدة (bulk/media/translations).
- مطابقة الاسم والتوقيع لا تعني تكافؤًا دلاليًا (نص الخطة §4 صريح في ذلك).
- الأرقام مقيسة على شجرة `integration/saleor-full-commerce-20261021` (HEAD `7217589d`) وليست على `main`.
