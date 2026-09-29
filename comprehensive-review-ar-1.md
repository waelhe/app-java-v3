# مراجعة شاملة للكود — app-java-v3

**التاريخ:** 2026-09-28 · **النسخة:** `73da7395bb45fde40f902da6f0eb1462a5cd9af1`.

## الخلاصة

تأكدت **10 نتائج قابلة للإصلاح: 8 بأولوية P1، و2 بأولوية P2**. أبرز المخاطر: استرداد مالي مسجّل دون استرداد فعلي، إعادة فتح فترات محجوزة، استمرار جلسة الحساب المعطّل، وكشف صور إعلانات غير منشورة. أعادت عشرة فحوص معزولة إنتاج السلوك الأساسي لهذه النتائج باستخدام أصناف المشروع الفعلية ومكوّن التفويض من SAS 7.1.1.

P1 = إصلاح عاجل بسبب أثر أمني أو مالي أو فساد حالة الحجز. P2 = خلل وظيفي مؤثر يُصلح في الدورة التالية. النتائج لا تتضمن ادعاء استغلال الإنتاج أو اختبار حسابات حقيقية.

## النطاق والمنهج

- حصر 22 وحدة Maven و472 ملف Java إنتاجياً و337 ملف Java اختبارياً؛ هذه أعداد جرد، وليست ادعاء قراءة يدوية لكل سطر.
- قراءة تعليمات AGENTS وخريطة SYSTEM وحالة PROJECT_MAP وخطط الإصلاح السابقة، ثم تتبع المسارات من المتحكم إلى الخدمة والمستودع والحدث والقيود في الترحيلات.
- مراجعة الأمن وOAuth2 وWebSocket والحجز والتوافر والدفع والمحاسبة والوسائط والبحث والأسعار والعقار والجغرافيا والمجتمع والرسائل والإشعارات والواجهات المشتركة وإعدادات البناء وCI.
- فحوص إثبات خارج المستودع: كود الإنتاج ومكوّنات الإطار حقيقية، ومستودعات البيانات ومزوّدو الخدمات الخارجية بدائل Mockito. هذه فحوص مكوّنات وليست محاكاة شاملة للمتصفح أو Stripe/S3.
- لا تغييرات في ملفات المشروع ولا نشر أو دفع أو تفاعل مع الإنتاج.

## النتائج

### R8 — P1: تعطيل الحساب لا يبطل جلسة الدخول التي تصدر تفويضات جديدة

**الموضع:** [UserService.java:538](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-identity/src/main/java/com/marketplace/identity/UserService.java:538)، وكذلك مسار تغيير الدور في [UserService.java:447](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-identity/src/main/java/com/marketplace/identity/UserService.java:447).

تعطيل الحساب يحدّث `auth_users.enabled` ويحذف `oauth2_authorization`، لكنه لا يحذف جلسات Spring Session أو يعيد التحقق من حالة الحساب عند إعادة التفويض. جلسة المستخدم تحمل Authentication صالحة من دخوله السابق. يستطيع مكوّن Authorization Server إصدار رمز تفويض جديد من هذه الجلسة، ثم يستعمل تخصيص JWT صلاحيات الـprincipal المحفوظة.

**السيناريو:** تسجيل دخول ناجح → تعطيل الحساب إدارياً → طلب authorization_code جديد باستخدام الجلسة نفسها. حذف refresh tokens لا يغلق هذا المسار. وينطبق خطر بقاء الصلاحيات القديمة أيضاً على تغيير الدور ما لم تُنهَ الجلسة.

**الإثبات:** `SessionProbe` استدعى `UserService.updateUserStatus` وتحقق من إرسال مستخدم معطّل إلى الخزينة، ثم مرّر Authentication الجلسة السابقة إلى `OAuth2AuthorizationCodeRequestAuthenticationProvider` الحقيقي؛ أُصدر رمز تفويض وحُفظ تفويض جديد. الاختبار معزول ولا يدّعي تنفيذ تبادل الرمز عبر HTTP.

**الإصلاح المقترح:** إبطال الجلسات المرتبطة بالـprincipal عند تعطيله أو تعديل صلاحياته، وتأمين إعادة التفويض ضد الـprincipal المتقادم. أضف اختباراً يبدأ بجلسة قبل التعطيل، ثم يعيد استعمالها بعده. هذا يختلف عن نافذة صلاحية JWT الصادرة مسبقاً والمقبولة صراحة في التصميم.

### R1 — P1: إلغاء الحجز يسجّل استرداداً دون إرجاع المال عبر PSP

**الموضع:** [PaymentsService.java:563](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-payments/src/main/java/com/marketplace/payments/PaymentsService.java:563)؛ الاستدعاء من [BookingCancelledEventListener.java:23](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-payments/src/main/java/com/marketplace/payments/BookingCancelledEventListener.java:23).

`refundFully` يغيّر النية والدفعة إلى `REFUNDED` وينشر حدث الاسترداد، دون استدعاء `PspChannel.createRemoteRefund` حتى عند وجود `pspIntentId` وقناة دفع مفعّلة. المسار الإداري `refundPayment` يستدعي القناة، ولذلك يعتمد السلوك على نقطة الدخول.

**السيناريو:** حجز مدفوع ومرتبط بعملية PSP ناجحة → إلغاء الحجز → السجل المحلي والدفتر يبلّغان باسترداد بينما لم يُرسل طلب رد المال إلى المزود.

**الإثبات:** النية والدفعة الحقيقيتان انتقلتا إلى `REFUNDED`، مع `verifyNoInteractions(channel)` رغم ربط النية بقناة وهمية متاحة.

**الإصلاح المقترح:** توحيد تنفيذ الاسترداد المالي تحت خدمة نطاق مشتركة تستدعي القناة بمفتاح idempotency، وتربط انتقال السجل المحلي بالنتيجة الفعلية. لا يكفي استدعاء واجهة الإدارة من مستمع الحدث بسبب اختلاف سياق الصلاحيات. اختبار الانحدار يجب أن يغطي النجاح والفشل والاسترداد المعلّق.

### R10 — P1: تسجيل Webhook قبل التسوية يترك نافذة فقد دائم عند توقف العامل

**الموضع:** [PaymentsService.java:118](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-payments/src/main/java/com/marketplace/payments/PaymentsService.java:118)، و[WebhookEventRecorder.java:51](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-payments/src/main/java/com/marketplace/payments/WebhookEventRecorder.java:51).

سجل منع التكرار يُحفظ في معاملة `REQUIRES_NEW` قبل معالجة حدث الدفع. عند توقف العامل بعد التزام هذا السجل وقبل التسوية، يبقى السجل بينما لم تُسوّ الدفعة. إعادة التسليم ترى وجود الصف وتعيد «سبق التنفيذ» دون التحقق من اكتمال المعالجة. الكيان لا يحمل حالة معالجة أو حمولة قابلة لإعادة التنفيذ، ولا يوجد عامل يسترد هذه الحالة. حذف السجل داخل `catch` يعالج بعض الاستثناءات العادية فقط؛ لا يعمل إذا توقفت العملية.

**الأثر:** قد تكون الدفعة ناجحة لدى PSP، وتبقى محلياً PROCESSING، بينما يُجاب على المحاولات التالية بنجاح HTTP دون تنفيذ التسوية. يعتمد إصلاحها عندئذ على التدخل اليدوي.

**الإثبات:** `WebhookProbe` جهّز الحالة التي تبقى بعد هذه النافذة: سجل حدث موجود ونية غير مسوّاة. إعادة التسليم عبر الخدمة الفعلية أعادت false ولم تستدعِ التسوية. هذا إثبات معزول لمعالجة حالة التعافي، وليس ادعاء تنفيذ قتل عملية وقاعدة بيانات حقيقية.

**الإصلاح المقترح:** ربط تأشير اكتمال الحدث بالتسوية ذرّياً، أو استعمال صندوق وارد دائم يحفظ الحمولة وحالة المعالجة وآلية استرداد للعامل المتوقف. وجود صف «استُقبل» وحده لا يجوز أن يعني «اكتمل». اختبار الانحدار يحقن توقفاً بين التسجيل والتسوية ثم يعيد تسليم الحدث.

### R2 — P1: إلغاء حجز PENDING يحرّر فترة حجز آخر CONFIRMED

**الموضع:** [BookingService.java:193](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-booking/src/main/java/com/marketplace/booking/BookingService.java:193) و[BookingService.java:209](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-booking/src/main/java/com/marketplace/booking/BookingService.java:209)؛ التحرير في [AvailabilityService.java:176](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-availability/src/main/java/com/marketplace/availability/AvailabilityService.java:176).

إنشاء حجز PENDING يفحص التوافر ولا يحجز الفترة؛ الحجز الفعلي يحدث عند التأكيد. مع ذلك، `cancel` و`autoCancel` يستدعيان `releaseSlot` مهما كانت الحالة السابقة. التحرير يعتمد على المزوّد والوقت فقط ولا يتحقق من الحجز الذي يشغل الفترة.

**السيناريو:** إنشاء A وB كحجزين معلّقين للفترة نفسها → تأكيد A → إلغاء B أو انتهاء صلاحيته آلياً → تُصبح فترة A متاحة مع بقائه CONFIRMED، ويمكن حجزها مجدداً.

**الإثبات:** تشغيل `BookingService.autoConfirm(A)` ثم `autoCancel(B)` فوق `AvailabilityService` الفعلية ترك A مؤكداً والفترة `booked=false`.

**الإصلاح المقترح:** التحرير فقط لحجز سبق أن امتلك الفترة، مع حفظ مرجع الحجز/الفترة والتحقق منه ذرّياً. يجب أن يغطي الاختبار الإلغاء اليدوي والانتهاء الآلي مع حجزين متنافسين.

### R3 — P1: توليد التوافر ينشئ نسخة مفتوحة من الفترة المحجوزة

**الموضع:** [AvailabilityService.java:144](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-availability/src/main/java/com/marketplace/availability/AvailabilityService.java:144)؛ المخطط [V15__availability_slots.sql:13](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-app/src/main/resources/db/migration/V15__availability_slots.sql:13).

مهمة الأيام السبعة تعتبر الفترة موجودة فقط عندما `booked=false`. بعد حجزها يختفي تطابقها من هذا الاستعلام، فتُنشأ فترة جديدة بالمزوّد والبداية والنهاية نفسها. مخطط القاعدة يحمل فهرساً عادياً، ولا يوجد قيد فريد يمنع هذا التكرار.

**السيناريو:** توليد فترة من قاعدة أسبوعية → تأكيد حجز فيها → تشغيل `DayHasPassed` مجدداً ضمن نافذة الأيام السبعة → ظهور فترة مفتوحة مكررة.

**الإثبات:** الفحص ترك صفين للفترة نفسها: أحدهما محجوز والآخر متاح، وأعاد `hasExactAvailableSlot` قيمة true.

**الإصلاح المقترح:** فحص وجود الفترة بغض النظر عن `booked` وإضافة قيد فريد مناسب للصفوف الحية بترحيلة جديدة؛ يلزم أيضاً منع التكرار عند تزامن التشغيل. لا تعديل لترحيلة V15 القائمة.

### R4 — P1: السماح بنيات دفع متعددة للحجز يكسر الاسترداد ويتيح الدفع المتكرر

**الموضع:** [PaymentsService.java:287](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-payments/src/main/java/com/marketplace/payments/PaymentsService.java:287)، [PaymentIntentRepository.java:16](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-payments/src/main/java/com/marketplace/payments/PaymentIntentRepository.java:16)، [V5__payments.sql:32](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-app/src/main/resources/db/migration/V5__payments.sql:32).

منع التكرار يقتصر على `idempotencyKey`، وهو اختياري. إرسال مفتاحين مختلفين للحجز CONFIRMED نفسه ينشئ نيتين. لا يوجد قيد فريد على `booking_id`، وكل نية تولّد مفتاح PSP مستقلاً. في المقابل، `findByBookingId` يعيد Optional لصف واحد ويُستخدم في الإلغاء والنزاعات، فيفشل عند وجود أكثر من صف.

**السيناريو:** إنشاء نية → إلغاؤها أو فشلها أو إعادة الطلب بمفتاح جديد → إنشاء نية ثانية للحجز نفسه. يمكن أيضاً معالجة النيتين والدفع مرتين؛ وإذا أُلغي الحجز، لا يستطيع مسار الاسترداد ذو النتيجة الواحدة قراءة حالته.

**الإثبات:** استدعاء الخدمة مرتين بمفتاحين مختلفين أعاد معرفين مختلفين للحجز نفسه؛ مراجعة الترحيلات أكدت غياب القيد الذي يمنع تخزينهما. لم يُنفّذ خصم حقيقي.

**الإصلاح المقترح:** تحديد نموذج محاولات الدفع: نية واحدة للحجز أو محاولات متعددة مع اختيار واضح للمحاولة الحالية وتسوية جميع المدفوعات عند الاسترداد، مع حارس قاعدة بيانات ضد أكثر من محاولة قابلة للتحصيل في الوقت نفسه.

### R9 — P1: دفتر المزوّد يجمع مبالغ بعملات مختلفة في رصيد واحد

**الموضع:** [LedgerPaymentEventListener.java:55](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-ledger/src/main/java/com/marketplace/ledger/LedgerPaymentEventListener.java:55)، و[ProviderBalance.java:18](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-ledger/src/main/java/com/marketplace/ledger/ProviderBalance.java:18).

الإعلانات والحجوزات والدفع تدعم `currency`، لكن مستمع اكتمال الدفع يمرّر `priceCents` فقط إلى الدفتر. الرصيد مفتاحه `providerId` وحده، وقيود الدفتر واستجابة الرصيد لا تحفظ العملة. لا يجري تحويل إلى عملة تسوية قبل الجمع.

**السيناريو والإثبات:** دفعان للمزوّد نفسه: 100 SAR و100 USD، بقيمة 10000 وحدة صغرى لكل منهما. بعد خصم عمولة 10% لكل دفع، أعادت خدمة الدفتر الفعلية رصيداً واحداً `18000`؛ الصحيح أن يبقى 9000 بوحدة SAR و9000 بوحدة USD، أو أن يُطبّق تحويل موثّق قبل التجميع. لا تحمل النتيجة الحالية وحدة مالية صحيحة ولا يمكن إعادة تفسيرها كسعر تحويل.

**الإصلاح المقترح:** حفظ العملة في القيود والأرصدة وتجميع الرصيد حسب `(providerId, currency)`، أو اعتماد عملة تسوية ثابتة مع حفظ سعر التحويل ومصدره وقت القيد. يجب معالجة البيانات القديمة قبل الاعتماد على الرصيد للتقارير أو السحب. الفحص `LedgerProbe` استعمل مستمع الأحداث وخدمة الدفتر الفعليين مع خزائن بديلة.

### R5 — P1: مسار الصور العام يتجاوز إخفاء الإعلان

**الموضع:** [MediaService.java:153](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-media/src/main/java/com/marketplace/media/MediaService.java:153) و[MediaController.java:80](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-media/src/main/java/com/marketplace/media/MediaController.java:80).

قراءة الوسائط تصفّي حالة الصورة إلى UPLOADED فقط، ولا تتحقق من حالة الإعلان. المسار مسموح للمجهول في SecurityConfig. من يعرف معرّف إعلان أُوقف أو أُرشف يستطيع طلب روابط تنزيل جديدة لوسائطه رغم أن قراءة الإعلان العام تعيد 404.

**الإثبات:** أصدرت الخدمة رابطاً موقّعاً دون أي استعلام إلى منفذ الإعلان. لم يكن زمن صلاحية رابط سابق هو السبب؛ يمكن إصدار روابط جديدة باستمرار.

**الإصلاح المقترح:** فرض أهلية العرض العام للإعلان قبل إنشاء روابط التنزيل، مع مسار منفصل محكوم بالملكية إذا احتاج المزوّد إلى مشاهدة صور المسودة. اختبار إعلان ACTIVE ثم PAUSED/ARCHIVED مع طلب مجهول.

### R7 — P2: هوية WebSocket لا تتوافق مع هوية المستخدم المسجّل

**الموضع:** [ConversationSubscriptionAuthorizationManager.java:37](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-messaging/src/main/java/com/marketplace/messaging/ConversationSubscriptionAuthorizationManager.java:37)، [MessagingWebSocketController.java:48](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-app/src/main/java/com/marketplace/app/websocket/MessagingWebSocketController.java:48)، [WebSocketSecurityConfig.java:27](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-messaging/src/main/java/com/marketplace/messaging/WebSocketSecurityConfig.java:27).

التسجيل يختار البريد الإلكتروني كـsubject. محوّل JWT يحافظ على هذا الاسم، لكن متحكم الرسائل وحارس الاشتراك يحاولان `UUID.fromString(authentication.name)`. إضافة إلى ذلك، الإشعارات تُنشر تحت UUID المستخدم، بينما حارس الاشتراك يقارن جزء المسار بالـsubject النصي.

**الأثر:** مستخدم حقيقي مثل `review@example.invalid` يستطيع المصادقة لكنه يُرفض عند إرسال الرسائل أو الاشتراك في محادثته؛ والاشتراك باسم البريد لا يستقبل إشعارات منشورة تحت UUID. اختبار التوصيل الحالي ينشر مباشرة إلى موضوع يحمل اسم principal، فيتجاوز عدم الاتساق بين الوحدات.

**الإثبات:** JWT مصادق بصلاحية CONSUMER وبريد كـsubject رُفض في حارس المحادثة قبل أي تحقق من مشاركته في المستودع.

**الإصلاح المقترح:** توحيد تحويل subject إلى UUID عبر `CurrentUserProvider`/منفذ الهوية عند حدود WebSocket، واستعمال الهوية نفسها في إرسال الإشعارات وحراسة الاشتراك. اختبار كامل من التسجيل إلى JWT إلى إرسال واستقبال رسالة وإشعار.

### R6 — P2: البحث النصي يهمل الفئة والسعر وعدد الضيوف

**الموضع:** [SearchService.java:521](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-search/src/main/java/com/marketplace/search/SearchService.java:521)؛ النمط نفسه في البحث المقيّد بالوقت والعقار وفي [SavedSearchMatcher.java:106](/home/vercel-sandbox/workspace/repos/github.com/waelhe/app-java-v3/marketplace-search/src/main/java/com/marketplace/search/SavedSearchMatcher.java:106).

وجود `q` غير فارغ يختار `searchFullText` ويرسل النص والترقيم فقط. فلاتر `category/minPrice/maxPrice/guests` الموجودة في SearchCriteria لا تصل إلى استعلام الكتالوج. فلاتر المكان/الوقت قد تبقى فعالة في فروعها، لكنها لا تعوّض فقدان فلاتر الكتالوج.

**السيناريو:** `q=villa&category=stay&maxPrice=100&guests=8` قد يعيد إعلاناً من فئة أخرى وبسعر يتجاوز 100 وسعة أقل من 8. عقود المتحكم تصف هذه المعايير كمرشحات، والاستثناء الموثّق يخص الترتيب حسب الصلة فقط.

**الإثبات:** أعاد الفحص إعلاناً بسعر 9999 ومن فئة مختلفة؛ الاستدعاء الوحيد للكتالوج كان البحث النصي دون المعايير.

**الإصلاح المقترح:** جمع مسند النص مع جميع المرشحات قبل حساب عدد النتائج والترقيم؛ تطبيق فلتر بعد جلب الصفحة سيجعل العدد والصفحات غير صحيحة. أضف حالات الجمع نفسها لتنبيهات البحث المحفوظ.

## ثغرات التغطية التي سمحت بهذه النتائج

1. اختبارات الإلغاء لا تربط حجزين بالفترة نفسها.
2. اختبار توليد التوافر يغطي فترة مفتوحة سابقة، ولا يغطي فترة محجوزة سابقة.
3. اختبارات الاسترداد التلقائي تتحقق من الحالة والأحداث دون إثبات استدعاء PSP عند ربطه.
4. فحوص تعطيل الحساب تغطي محاولة تسجيل دخول جديدة وتجديد التوكن، ولا تغطي إعادة التفويض من جلسة سابقة.
5. فحوص WebSocket تستخدم هوية/موضوعاً اصطناعياً لا يطابق سلسلة التسجيل والإشعارات الفعلية.
6. اختبارات البحث تفحص المعايير مفردة أو داخل فروع معيّنة، وتحتاج مصفوفة جمع النص والفئة والسعر والسعة.

## قيود مقصودة لا أعدّها نتائج جديدة

- نافذة بقاء JWT الصادرة مسبقاً حتى انتهاء TTL موثقة؛ R8 يتعلق بالحصول على تفويض **جديد**.
- التسعير المسطح عند غياب قواعد التقويم موثّق كتوافق خلفي؛ لم أصنفه خطأ رغم اختلافه عن ضرب سعر الليلة في عدد الليالي.
- تجاهل الدفتر لأحداث PARTIALLY_REFUNDED مثبت في اختبار صريح؛ يلزم مراجعته تجارياً عند اعتماد رصيد قابل للسحب، لكنه ليس انحرافاً خفياً عن الاختبارات الحالية.
- تعطيل قنوات الدفع والتخزين والذكاء الاصطناعي عند غياب إعداداتها تصميم مقصود؛ لم يُفترض أنها مفعّلة في الإنتاج.

## التحقق

| الفحص | النتيجة |
|---|---|
| اختبارات الوحدة وشرائح التطبيق (Surefire) | **1709** في 253 فئة؛ صفر فشل/أخطاء/تجاوز |
| اختبارات التكامل (Failsafe) | **362** في 80 فئة؛ صفر فشل/أخطاء/تجاوز |
| فحوص إعادة إنتاج العيوب | **10/10** أثبتت السلوك الموثق؛ ليست إصلاحات |
| Gitleaks 8.30.1 على شجرة Git الحالية | صفر نتائج خارج استثناءات ملفات الاختبار في `.gitleaks.toml` |
| بوابات البناء والتغطية | `BUILD SUCCESS` لاستكمال التطبيق واعتمادياته، ولتشغيل وحدة edge؛ اجتازت بوابات JaCoCo المعلنة |

**البيئة:** OpenJDK 25.0.2، Maven Wrapper 3.9.16، Docker 25.0.16، PostgreSQL/PostGIS 18-3.6 وRedis 8. بقيت شجرة المصدر نظيفة عند النسخة المذكورة.

**تفاصيل الاستكمال:** بدأ التحقق بالأمر الموثق `./mvnw clean verify --batch-mode`. أُوقف بعد نجاح اختبارات الوحدة و35 فئة تكامل بسبب انتظار تنظيف سياقات Spring لاتصالات حاويات توقفت. حُفظت النتائج الناجحة واستُكملت الفئات الباقية دون تكرارها في العد. محاولة تقصير مهلة Hikari أظهرت تعارض تهيئة، فأُزيل هذا التعديل. نجحت فئتان في JVM منفصل، ثم نجحت الفئات الـ41 الباقية بالإعدادات الأصلية لقاعدة البيانات ومع `-Dspring.test.context.cache.maxSize=128`. نجح تشغيل edge مستقلاً. عند نهاية الاستكمال ظهرت مهلة إغلاق JVM مقدارها 30 ثانية؛ أنهى Surefire العملية، وأكمل Maven بوابات التغطية والبناء بنجاح وخروج 0. العد أعلاه يجمع النتائج الناجحة المعاد استخدامها والنتائج الجديدة مع التحقق من وجود جميع فئات التكامل الثمانين، ولا يحتسب المحاولات المتكررة.

**حدود التحقق:** لم تُختبر حسابات إنتاجية أو عمليات مالية حقيقية أو تكامل كامل مع Stripe/S3. فحص الأسرار شمل الشجرة الحالية دون تاريخ Git؛ لم يُنفّذ فحص CVE أو CodeQL. فحوص إثبات العيوب معزولة عند حدود قواعد البيانات والمزوّدين، كما هو موضح لكل نتيجة. لم يُشغّل CLI لمراجعة CodeRabbit؛ هذه النتائج من مراجعة الكود والفحوص المبينة أعلاه.

## ترتيب الإصلاح المقترح

1. R8 ثم R1 وR10: وقف إعادة التفويض بعد التعطيل وتصحيح الاسترداد وضمان تعافي أحداث الدفع.
2. R2 وR3 معاً: استعادة ملكية الفترة ومنع إعادة فتحها أو تكرارها.
3. R4 وR9 وR5: نموذج محاولات الدفع ووحدات الرصيد واتساق خصوصية الإعلان ووسائطه.
4. R7 ثم R6: اتساق الهوية في الاتصال الفوري وتركيب مرشحات البحث.

حزمة الفحوص المرفقة تحتوي المصادر وطريقة تشغيلها والنتائج المختصرة. نجاح فحص إعادة الإنتاج يعني أنه أثبت العيب القائم؛ لا يعني أن العيب أُصلح.
