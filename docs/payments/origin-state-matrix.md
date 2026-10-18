# مصفوفة أصل الدفع × الحالة — القياس من الكود (المخرج الإلزامي الأول لـ W7)

**تاريخ القياس:** 18 أكتوبر 2026  
**أساس القياس:** main عند `0de7138688` (بعد #636/#635/#638).  
**المرجع الحاكم:** [ADR-0013](../adr/ADR-0013-financial-allocation-decisions.md) + [عمارة المدفوعات والتوصيل](../architecture/platform-wide-payments-and-delivery.md) + الخطة الموحدة §6.  
**قاعدة الوثيقة:** كل خلية أدناه مقيسة من ملف في الكود الفعلي. **الغياب حقيقة مقيسة وليست فراغًا في التوثيق** — كل غياب موسوم صراحة وله مالك قرار.

---

## 0. الآلية المشتركة (تحكم المصادر الخمسة كلها)

### 0.1 آلة حالة الدفع (لا يوجد `PaymentOrigin` enum — قرار W1 مقصود)

الأصل عمود نصي مقيد بـ CHECK في قاعدة البيانات (`chk_payment_intents_origin`، ترحيلات V103→V184→V200→V215). آلة الحالة الوحيدة المشتركة:

**`PaymentIntentStatus`** (`marketplace-payments/.../PaymentIntentStatus.java`):
`CREATED, PROCESSING, SUCCEEDED, FAILED, CANCELLED, REFUNDED, PARTIALLY_REFUNDED`

| من | إلى |
|---|---|
| CREATED | PROCESSING، CANCELLED |
| PROCESSING | SUCCEEDED، FAILED |
| SUCCEEDED | REFUNDED، PARTIALLY_REFUNDED |
| PARTIALLY_REFUNDED | REFUNDED، PARTIALLY_REFUNDED |
| FAILED / CANCELLED / REFUNDED | (نهائية) |

الانتقال غير القانوني ⇒ `ConflictException`. النطاقات: `COLLECTIBLE=[CREATED, PROCESSING]`، `COLLECTED=[SUCCEEDED, PARTIALLY_REFUNDED, REFUNDED]`، `RETRYABLE=[FAILED, CANCELLED]`.

**`PaymentStatus`** (صف الشحنة): `PENDING → COMPLETED/FAILED`؛ `COMPLETED → REFUNDED/PARTIALLY_REFUNDED`. **لا فروق بين المصادر في حالات الدفع إطلاقًا** — الفروق في الأحداث المستهلكة وآلة حالة كل مجال.

### 0.2 صندوق الويبهوك (مشترك)

- المساران: `POST /api/v1/payments/webhooks/{provider}` (توقيع HMAC عام) و `POST /api/v1/payments/webhooks/stripe` (توقيع Stripe؛ 503 SU-001 عند غياب القناة).
- الجدول الدائم `payment_webhook_events`: تفرد `(provider, event_id)`؛ حالات `RECEIVED/SETTLED/FAILED`.
- التدفق: فحص تفرد → تسجيل بـ REQUIRES_NEW (وُلد RECEIVED) → dispatch → عند الاستثناء حذف تعويضي (إعادة محاولة المزود تعيد المعالجة) → `markSettled` idempotent.
- التكرار: 202 للجديد / 200 لـ «معالج سابقًا»؛ الاستلام المتزامن المكرر ⇒ `DataIntegrityViolationException` يجاب «already processed».
- المتأخر/خارج الترتيب: لقطات `charge.refunded` الأقدم من الإجمالي المتزامن تُتجاهل؛ `payment_intent.succeeded` غير القابل للحل يُرفض قبل التسجيل ليعيد المزود المحاولة.
- الاسترداد: `WebhookInboxRecovery` يجتاح كل دقيقة (RECEIVED أقدم من دقيقتين)؛ الفشل الدائم ⇒ FAILED نهائي بأسباب؛ SETTLED يُنقى بعد 7 أيام.

### 0.3 تجميد العمولة (ADR-0013 D2 — مشترك لغير التوصيل)

- `app.commission.rate` افتراضي `0.10`، fail-closed `[0,1]` (`CommissionProperties`).
- التجميد لحظة إنشاء النية: `commissionRate` على صف النية (V220 عمود + V221 VALIDATE). قارئ الدفتر يستخدم القيمة المجمدة (`LedgerPaymentEventListener.effectiveCommissionRate`)؛ الإعداد الحي fallback لصفوف ما قبل V220 فقط.
- **DELIVERY وحدها بلا عمولة** (`createForDelivery` يمرر null عمدًا — D4: مستحقات المندوب في يومية التوصيل).

### 0.4 الأسطح الإدارية الثلاث

`GET /api/v1/admin/payments` (بحث origin/status/subject/consumer/نافذة) + `GET /{id}` + `GET /{id}/financial-facts` (تكامل مع `LedgerAllocationPort`).

---

## 1. المصفوفة المقيسة

| البعد | BOOKING | AD | ORDER | LOAN | DELIVERY |
|---|---|---|---|---|---|
| **مفتاح الـidempotency** | يقدمه المتصل **اختياريًا** — **لا نمط منصة `booking-{id}`** (غياب مقيس) | `ad-debit-{campaignId}-{windowStart}` (مشتق من المستمع) | `order-{orderId}` (مشتق من الخدمة) | `loan-{loanId}` (مشتق من الخدمة) | `delivery-{deliveryRequestId}` (مشتق؛ **أقوى عقد إعادة تشغيل**: إعادة تحقق من payer+subject+origin+amount+currency) |
| **قيد التفرد** | `idempotency_key` UNIQUE (V5) + فهرس جزئي «محاولة نشطة واحدة» (V81) — نمط إعادة المحاولة الوحيد | المفتاح UNIQUE؛ تفرد النافذة أعلى المنبع (صف الشحنة V103) ومفتاح AD_DEBIT في الدفتر | `order_id` UNIQUE (V184) — نية واحدة للطلب أبدًا | `loan_id` UNIQUE (V200) | `delivery_request_id` UNIQUE (V215) + CHECK الأصول الخمسة |
| **مالك المبلغ/العملة** | booking (`BookingParticipantProvider`؛ يجب أن يكون CONFIRMED والمتصل مشاركًا) | catalog (`AdBillingBatchExecutor`: انطباعات×سعر + نقرات×سعر بحد الميزانية؛ الدافع = المزود) | orders (`totalAmountMinor` المجمد عند الوضع؛ ADR-0002) | lending (`feeMinor` المجمد من العرض؛ ينشأ **داخل** معاملة الموافقة) | delivery (عرض الخادم من مسار OSRM؛ commissionRate=null عمدًا) |
| **مستمع حالة المجال** | `BookingPaymentEventListener`: COMPLETED فقط → `autoConfirm` (بحجز المقعد) مع حارس الأصل | **لا يوجد** — لا يستهلك catalog أي حدث دفع (غياب مقيس؛ حقيقة النافذة هي صف الشحنة المجمد) | `OrdersPaymentListener`: COMPLETED → PLACED→CONFIRMED (idempotent بإعادة التسليم) | `LendingPaymentListener`: COMPLETED → بوابة markPaid (التسليم يتطلبها) | `DeliveryPaymentEventListener`: **أوسع مجموعة** — COMPLETED/FAILED/CANCELLED/REFUNDED/PARTIALLY_REFUNDED مع إعادة تحقق السلطة |
| **الاسترداد الكامل** | إداري + نزاع REFUND_CONSUMER (يُحترم فيه المبلغ) + إلغاء → `autoRefundByBooking` | إداري (أعمى للأصل) + مزامنة `charge.refunded`؛ الدفتر يعكسها | إلغاء الطلب → `autoRefundByOrder` | إلغاء/نزاع → `LoanCancelledEvent` → `autoRefundByLoan` | حدث `DeliveryRefundRequestedEvent` (إلغاء مدفوع/فشل مندوب/دفع متأخر) → `autoRefundByDelivery` |
| **الاسترداد الجزئي** | إداري + نزاع (مبالغ تُحترم لموضوعات BOOKING فقط) | **لا مسار مجال** — إداري خام فقط | **المسار المنتجي الوحيد**: دورة RMA (V230) بمفتاح PSP محدد بالإرجاع | **لا مسار مجال** (النزاعات تحترم المبالغ الجزئية لموضوعات BOOKING فقط) | **موجود** — ترحيلات الفرق التراكمي في يومية التوصيل |
| **أثر الدفتر العام عند الاسترداد الكامل** | REFUND_DEBIT يعكس القيد (نفس العملة) | REFUND_DEBIT يعكس القيد | REFUND_DEBIT (على رصيد البائع) | REFUND_DEBIT (على رصيد المالك) | **لا شيء في الدفتر العام** (المستمع يتخطى DELIVERY — منع ترحيل مزدوج) |
| **أثر PARTIALLY_REFUNDED** | **صفر — no-op مقصود** (يؤكد ADR-0013 D3) | **صفر** | **صفر — مقصود** (javadoc المهايئ ينص حرفيًا) | **صفر** | **الوحيد ذو أثر**: يومية التوصيل ترحّل **فرق التراكم فقط** (`REFUND:{cumulative}`) — استثناء D4 الموثق لا الدفتر العام |
| **قيد التحصيل** | PAYMENT_CREDIT (مزود) + COMMISSION_DEBIT | **قيدان مستقلان على حافتين**: AD_DEBIT وقت الفوترة (مفتاح بنيوي محدد) + PAYMENT_CREDIT بالمبلغ الخام **بلا عمولة** عند التسوية | PAYMENT_CREDIT (بائع) + COMMISSION_DEBIT | PAYMENT_CREDIT (مالك) + COMMISSION_DEBIT | في اليومية المخصصة: CAPTURE ثم RECOGNIZE + COURIER_PAYABLE |
| **بوابة أهلية التسوية (مقيسة)** | **لا كود** — ADR-0013 يسمي COMPLETED أهلية؛ القيد يقع عند التحصيل | **لا كود** — الحافة = صف النافذة المشحونة | **لا كود** — ADR-0013 يسمي FULFILLED؛ لا شيء في payments/ledger يقرأها | **لا كود** — «تسوى عند التحصيل» = القيد عند COMPLETED؛ `LoanClosedEvent` بلا مستهلك دفتر | **البوابة الوحيدة في الكود**: `LedgerJournalService.recognize` يشترط وجود CAPTURE مطابق ويشتغل فقط من `DeliveryCompletedEvent` |
| **الإلغاء قبل الدفع** | CREATED→CANCELLED (المستهلك مباشرة أو المستمع) | **لا مسار إلغاء** (غياب مقيس؛ الإيقاف يوقف النوافذ المستقبلية بلا مال) | CREATED→CANCELLED عبر المستمع | CREATED→CANCELLED (الرفض = لا نية أصلًا) | AWAITING_PAYMENT/PAYMENT_FAILED/READY_FOR_DISPATCH/ASSIGNED→CANCELLED (قبل الالتقاط فقط) |
| **الإلغاء بعد الدفع** | استرداد كامل عبر S12 (خريطة الحالة القانونية-أو-لا-شيء) | **غير موجود** | استرداد كامل؛ الإلغاء محجوز أثناء أرجل توصيل نشطة | استرداد كامل؛ تجميد DISPUTED حتى الحل | حدث استرداد كامل |
| **دليل الاختبار** | PaymentsServiceTest، BookingPaymentEventListenerTest، BookingCancelledEventListenerTest، PaymentsModuleIntegrationTest، WebhookInbox/SettlementRetry، DisputeFinancialResolution، LedgerPaymentEventListenerTest | AdBillingEventListenerTest، LedgerAdBillingEventListenerTest، **AdBillingDeterminismIntegrationTest** (السلسلة كاملة مرة واحدة لكل نافذة)، فروع AD في LedgerPaymentEventListenerTest | OrderIntentContractTest، **OrderJourneyIntegrationTest**، OrderReturnsServiceTest، FinancialOperatorReadIntegrationTest | LendingServiceTest، LendingListenersTest، **LoanDisputeJourneyIntegrationTest**؛ **لا اختبار وحدة مخصص لـ LoanCancelledEventListener** | DeliveryServiceTest (~40 حالة)، LedgerJournalServiceTest، فروع التفويض في LedgerPaymentEventListenerTest |

---

## 2. الغيابات المقيسة (سجل الديون — لكل غياب مالك وشرط إغلاق)

| # | الغياب | الأثر | القرار المطلوب |
|---|---|---|---|
| G1 | **لا مفتاح idempotency مشتق للمنصة لـ BOOKING** | إعادة المحاولة بلا مفتاح قد تنشئ نية ثانية (يمنعها فهرس V81 الجزئي — محاولة COLLECTIBLE واحدة) | قرار منتج: اعتماد `booking-{id}` أم الاكتفاء بـ V81؟ أي تغيير = تعديل عقد وليس ترقيعًا |
| G2 | **لا مستمع حالة مجال لـ AD** | حقيقة الحملة هي صف الشحنة، لا آلة حالة مدفوعة بالدفع | مقبول تصميميًا (الفوترة مجمّدة أعلى المنبع) — يوثق ولا يغير |
| G3 | **لا مسار استرداد جزئي مجال لـ AD وLOAN** | الإداري الخام هو الوحيد؛ وبلا أثر دفتر (no-op) | قرار مالي موثق إن احتاج المنتج أثرًا (خطة §6.6) — لا تعميم |
| G4 | **لا PARTIALLY_REFUNDED في الدفتر العام لأي أصل** | مطابق ADR-0013 D3 — قرار مثبت لا مخالفة | يبقى؛ أي تغيير يمر عبر تحديث ADR واختبار محاسبي |
| G5 | **لا بوابة تسوية في الكود لـ BOOKING/AD/ORDER/LOAN** | ADR-0013 D3 يسمي الأهلية لكنها سطح قراءة تشغيلي لا كود بوابة | W11 (مساحة المشغل): بناء قراءة الأهلية فوق القيود القائمة |
| G6 | **لا اختبار وحدة لـ LoanCancelledEventListener** | مغطى تكامليًا فقط (LoanDisputeJourney) | **مغلق (W7):** `LoanCancelledEventListenerTest` — التوأم الحرفي لاختبار البوكرينغ |
| G7 | **نجاح PSP متأخر على نية FAILED** (declared gap في `autoRefundByBooking`) | دفع محصّل مقابل نية فاشلة | **مقيس (W7):** `LateSuccessOnFailedIntentTest` — الحارس الطرفي يجيب 409 (لا قيامة ولا حجز مزدوج)؛ التعافي نية جديدة بنموذج إعادة المحاولة (V81) |

**قاعدة الخطة §6:** أي اختلاف عن ADR-0013 يوقف التوسعة المالية حتى تُحدّث ADR وتُختبر الحالة — الغيابات أعلاه موثقة لا مخفية، ولكل منها مالك.

---

## 3. عقد كل أصل — التفصيل المقيس

### 3.1 BOOKING
- الإنشاء: `POST /api/v1/payments/intents` (CONSUMER) → فحص CONFIRMED والمشاركة → تجميد العمولة. نمط إعادة المحاولة الوحيد: محاولات متعددة قانونية كتاريخ، واحدة COLLECTIBLE.
- الإلغاء: `BookingCancelledEvent` → خريطة S12: CREATED→CANCELLED، PROCESSING→FAILED (نجاح متأخر = G7)، SUCCEEDED/PARTIALLY_REFUNDED→استرداد كامل بعقد الاسترداد الواحد، REFUNDED→no-op.
- النزاع: REFUND_CONSUMER يحترم `refundAmountCents` **لموضوعات BOOKING فقط** (DisputeService).

### 3.2 AD
- الإنشاء خادمي بالكامل داخل معاملة فوترة النافذة (REQUIRES_NEW لكل حملة)؛ الحدث AFTER_COMMIT ينشئ النية.
- **قيدان مستقلان**: AD_DEBIT وقت الفوترة + PAYMENT_CREDIT وقت التحصيل — كل بنيوي idempotence مختلف؛ نجح الاثنان معًا في AdBillingDeterminismIntegrationTest (نافذة واحدة = مرة واحدة).

### 3.3 ORDER
- `POST /api/v1/orders/{id}/payment-intent` (يجب PLACED) → نية `order-{id}` → `linkPaymentIntent`.
- COMPLETED → CONFIRMED (idempotent). الاسترداد الجزئي المنتجي: RMA (V230) `OrderReturnsService.complete` → `OrderRefundAdapter` بمفتاح PSP محدد بالإرجاع.

### 3.4 LOAN
- النية تنشأ **داخل معاملة موافقة الإعارة** (أقوى تلازم مجال-دفع بعد DELIVERY).
- DISPUTED يجمّد الحواف النشطة حتى الحل: REFUND_CONSUMER→إلغاء+استرداد، غير ذلك→عودة ACTIVE.

### 3.5 DELIVERY
- آلة المجال الأغنى: `AWAITING_PAYMENT, PAYMENT_FAILED, READY_FOR_DISPATCH, ASSIGNED, PICKED_UP, IN_TRANSIT, DELIVERED, FAILED, CANCELLED, RETURNED` — أصل الدفع الوحيد بحالة فشل دفع صريحة.
- اليومية: CAPTURE عند COMPLETED → RECOGNIZE + COURIER_PAYABLE عند `DeliveryCompletedEvent` (إثبات التسليم) → REFUND بفروق تراكمية. بوابة التعرف هي الكود الوحيد الذي يفرض «لا تسوية قبل نهاية المجال».
- الدفع المتأخر بعد حالة نهائية ⇒ حدث استرداد (لا تحصيل مزدوج). الطلب يكتمل فقط بإكمال الأرجل (V224).

---

## 4. قاعدة التحديث

تُحدَّث هذه المصفوفة في نفس PR الذي يغير أي خلية فيها (قاعدة السجل §2.1 نفسها). أي مصدر جديد أو تغيير سياسة مالية يمر عبر ADR أولًا ثم ينعكس هنا.
