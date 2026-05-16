# خطة تنفيذ الباك إند المعتمدة على الوثائق الرسمية

هذه الوثيقة تحول خريطة تطوير الباك إند إلى خطة تنفيذ قابلة للمتابعة، مع إلزام كل قرار تقني بالرجوع إلى مصادر Spring وMaven الرسمية. الهدف هو تطوير منصة Marketplace الحالية دون إضافة اعتماديات أو وحدات عمل عشوائية، ودون كسر توافق Spring Boot 4 وMaven multi-module وSpring Modulith.

## 1. المصادر الرسمية الملزمة

| المجال | المصدر الرسمي | متى يُستخدم |
| --- | --- | --- |
| Spring Boot runtime، الإعدادات، profiles، actuator، packaging | [Spring Boot Reference Documentation](https://docs.spring.io/spring-boot/reference/index.html) | قبل تغيير إعدادات التشغيل أو إضافة starter أو actuator/observability behavior. |
| متطلبات Java وMaven وServlet container | [Spring Boot System Requirements](https://docs.spring.io/spring-boot/system-requirements.html) | قبل تعديل `java.version` أو Maven/CI image أو إصدار Boot. |
| إدارة الاعتماديات وSpring Boot Maven Plugin | [Spring Boot Build Systems](https://docs.spring.io/spring-boot/reference/using/build-systems.html) | قبل إضافة dependency أو plugin، وخصوصًا لتجنب تحديد versions يدويًا لما يديره Boot BOM. |
| أمثلة عملية رسمية | [Spring Guides](https://spring.io/guides) | عند تنفيذ REST، JPA، Security، Messaging، Scheduling، Testing، أو Docker Compose patterns. |
| Spring Data وJPA/Redis repositories | [Spring Data](https://spring.io/projects/spring-data) | عند إضافة repositories أو paging أو auditing أو Redis data access. |
| Security وOAuth2/JWT والصلاحيات | [Spring Security](https://spring.io/projects/spring-security) | عند إضافة endpoint محمي، role، JWT claim mapping، OAuth2 flow، أو Authorization Server behavior. |
| Authorization Server | [Spring Authorization Server](https://spring.io/projects/spring-authorization-server) | عند تعديل إصدار التوكنات، registered clients، consent، أو JWK/keystore. |
| الوحدات الداخلية وحدودها | [Spring Modulith Reference](https://docs.spring.io/spring-modulith/reference/index.html) | عند إضافة module أو event أو named interface أو اختبار module boundaries. |
| Maven multi-module/reactor | [Maven Multiple Modules Guide](https://maven.apache.org/guides/mini/guide-multiple-modules.html) | عند إضافة module جديد أو تغيير علاقة بين modules. |
| Maven project model | [Maven Getting Started Guide](https://maven.apache.org/guides/getting-started/index.html) | عند إنشاء `pom.xml` جديد أو تغيير inheritance/dependency management. |

## 2. قواعد حاكمة قبل أي تنفيذ

1. **لا تُضاف dependency جديدة إلا من مصدر رسمي أو موثوق ومثبت التوافق** مع Spring Boot 4.x، مع تفضيل dependency management من Spring Boot BOM أو BOM رسمي مثل Spring Modulith.
2. **لا تُضاف version يدويًا** لأي Spring starter أو library يديرها Spring Boot parent/BOM، إلا إذا وُجد سبب موثق في التعليق أو في PR.
3. **كل وحدة عمل جديدة يجب أن تكون module مستقلة** داخل Maven reactor، وتُضاف إلى `pom.xml` الرئيسي، ويكون لها `pom.xml` خاص بها.
4. **حدود Spring Modulith إلزامية**: التواصل بين الوحدات يكون عبر API public، shared ports، أو application events، وليس عبر الوصول المباشر إلى internals.
5. **كل تغيير في قاعدة البيانات يحتاج migration جديدة** في `marketplace-app/src/main/resources/db/migration`، ولا يتم تعديل migration قديمة بعد اعتمادها.
6. **كل endpoint جديد يحتاج اختبارًا واحدًا على الأقل**: service test، controller slice test، أو integration test حسب حجم التغيير.
7. **كل feature أمنية تحتاج authorization rule واضحة** واختبارًا يثبت منع الوصول غير المصرح.
8. **أي قرار يخالف الوثائق الرسمية يجب توثيقه** في PR body مع سبب تقني واضح وخطة مراجعة لاحقة.

## 3. baseline الحالي الذي يجب الحفاظ عليه

- المشروع يستخدم Spring Boot parent بإصدار `4.0.6`، ويجب فحص توافق أي تطوير معه قبل التنفيذ.
- المشروع مضبوط على Java `21`، وهو اختيار مناسب طالما بقي ضمن متطلبات Spring Boot الرسمية.
- المشروع Maven multi-module، وتضاف الوحدات الجديدة من خلال reactor لا كمجلدات مستقلة خارج البناء.
- المشروع يستخدم Spring Modulith، لذلك يجب أن تبقى الوحدات الجديدة bounded contexts واضحة.
- الخدمات المحلية الأساسية هي PostgreSQL وRedis، ويجب ألا تُستبدل إلا بقرار معماري موثق.

## 4. ترتيب التنفيذ المقترح

### المرحلة 0: تثبيت الأساس التقني

**الهدف:** التأكد أن المشروع الحالي يبنى ويُختبر قبل إضافة وحدات جديدة.

المهام:

1. تشغيل build كامل:
   ```bash
   mvn clean verify
   ```
2. تشغيل اختبارات Modulith/Architecture إن وُجدت:
   ```bash
   mvn -pl marketplace-app -am test
   ```
3. توثيق أي فشل في dependency convergence أو tests قبل البدء بالميزات.
4. تثبيت profile التطوير المحلي:
   ```bash
   docker compose up -d postgres redis
   mvn -pl marketplace-app -am spring-boot:run -Dspring-boot.run.profiles=dev
   ```

معيار القبول:

- build ينجح محليًا أو تُفتح issue واضحة لأي فشل بيئي.
- لا توجد dependency versions غير مبررة خارج BOMs المعتمدة.

### المرحلة 1: وحدة مزودي الخدمة Provider

**سبب الأولوية:** الكاتالوج والحجوزات تعتمد على `providerId`، لكن لا توجد دورة حياة مستقلة لمزود الخدمة.

المصدر الرسمي الرئيسي:

- Spring Modulith للحدود بين modules.
- Maven multiple modules لإضافة module جديد.
- Spring Data JPA للـ repositories والكيانات.
- Spring Security لحماية عمليات التحقق والإيقاف.

المهام:

1. إنشاء module باسم `marketplace-provider`.
2. إضافة `ProviderProfile`, `ProviderStatus`, `ProviderRepository`, `ProviderService`, `ProviderController`.
3. إضافة migration لإنشاء جدول `provider_profiles`.
4. إضافة shared port مثل `ProviderLookupPort` عند الحاجة لاستهلاك بيانات المزود من catalog/booking.
5. إضافة endpoints:
   - `POST /api/providers`
   - `GET /api/providers/{id}`
   - `PUT /api/providers/{id}`
   - `POST /api/admin/providers/{id}/verify`
   - `POST /api/admin/providers/{id}/suspend`
6. إضافة tests للحالات الأساسية والصلاحيات.

معيار القبول:

- لا توجد dependencies مباشرة من modules أخرى إلى internals الخاصة بـ provider.
- Modulith verification ينجح.
- Admin فقط يستطيع verify/suspend.

### المرحلة 2: وحدة التوفر Availability

**سبب الأولوية:** منع الحجز المزدوج وتوليد slots قبل الدفع أو تأكيد الحجز.

المصدر الرسمي الرئيسي:

- Spring Data JPA للكيانات والـ queries.
- Spring Modulith للاتصال بين booking وavailability.
- Spring Guides للـ REST/testing patterns.

المهام:

1. إنشاء module باسم `marketplace-availability`.
2. إضافة كيانات:
   - `ProviderAvailabilityRule`
   - `ProviderTimeOff`
   - `AvailabilitySlot`
3. إضافة service لحساب slots والتحقق من الحجز.
4. إضافة port في shared مثل `AvailabilityPort`.
5. تعديل `BookingService.create` لاستخدام port للتحقق من availability.
6. إضافة endpoints:
   - `POST /api/providers/{providerId}/availability/rules`
   - `GET /api/providers/{providerId}/availability`
   - `POST /api/providers/{providerId}/time-off`
   - `GET /api/listings/{listingId}/slots`

معيار القبول:

- لا يمكن إنشاء booking إذا كان slot غير متاح.
- يوجد اختبار يمنع double booking.
- جميع queries لها indexes مناسبة في migration.

### المرحلة 3: تكامل بوابة الدفع وWebhooks

**سبب الأولوية:** المدفوعات الحالية تحتاج abstraction واضح للتعامل مع مزود دفع خارجي ومعالجة callbacks بشكل idempotent.

المصدر الرسمي الرئيسي:

- Spring Web MVC للـ webhook endpoint.
- Spring Security لاستثناء webhook endpoint أو حماية signature بدل JWT.
- Spring Data JPA لتخزين webhook events.
- Spring Boot configuration properties لإعدادات مزود الدفع.

المهام:

1. إضافة `PaymentGatewayClient` داخل payments أو كـ SPI.
2. إضافة implementation أولي `MockPaymentGatewayClient` للتطوير والاختبارات.
3. إضافة جدول `payment_webhook_events` لمنع التكرار.
4. إضافة endpoint:
   - `POST /api/payments/webhooks/{provider}`
5. إضافة signature validation حسب مزود الدفع عند اختيار provider حقيقي.
6. ربط حالات الدفع بأحداث `PaymentStateChangedEvent`.

معيار القبول:

- webhook مكرر لا يغير الحالة مرتين.
- فشل الدفع لا يؤكد الحجز.
- نجاح الدفع ينشر event قابل للاستهلاك من ledger/notifications لاحقًا.

### المرحلة 4: وحدة الإشعارات Notifications

**سبب الأولوية:** إرسال إشعارات موحدة للحجز والدفع والرسائل، بدل استدعاءات بريد مبعثرة.

المصدر الرسمي الرئيسي:

- Spring Modulith events/listeners.
- Spring Boot Mail support.
- Spring Data JPA لتخزين in-app notifications.

المهام:

1. إنشاء module باسم `marketplace-notifications`.
2. إضافة `Notification`, `NotificationChannel`, `NotificationService`.
3. استهلاك أحداث:
   - `BookingCreatedEvent`
   - `PaymentStateChangedEvent`
   - `ReviewCreatedEvent`
4. ربط email channel بـ `EmailService` الموجود في infra.
5. إضافة endpoints:
   - `GET /api/notifications`
   - `POST /api/notifications/{id}/read`

معيار القبول:

- notification تُنشأ عند إنشاء booking أو تغيير payment state.
- المستخدم لا يستطيع قراءة إشعارات غيره.

### المرحلة 5: Ledger/Settlements

**سبب الأولوية:** أي Marketplace تجاري يحتاج عمولة المنصة، رصيد المزود، التسويات، والسحوبات.

المصدر الرسمي الرئيسي:

- Spring Modulith للأحداث والحدود.
- Spring Data JPA للمعاملات المالية.
- Spring Security لعمليات admin المالية.

المهام:

1. إنشاء module باسم `marketplace-ledger`.
2. إضافة كيانات:
   - `LedgerEntry`
   - `ProviderBalance`
   - `Settlement`
   - `Payout`
3. استهلاك `PaymentStateChangedEvent` عند نجاح الدفع أو refund.
4. إنشاء entries للعمولة، مستحقات المزود، والرصيد.
5. إضافة admin endpoints للرصيد والسحوبات.

معيار القبول:

- entries متوازنة لكل عملية دفع ناجحة.
- لا يمكن إنشاء payout أكبر من الرصيد المتاح.
- العمليات المالية transactional وidempotent.

### المرحلة 6: النزاعات والبلاغات Disputes/Reports

**سبب الأولوية:** إدارة الخلافات والبلاغات ضرورية بعد تفعيل الدفع والحجوزات.

المصدر الرسمي الرئيسي:

- Spring Web MVC وSpring Security للـ APIs والصلاحيات.
- Spring Modulith events لربط النزاع بالدفع أو الإشعارات.
- Spring Data JPA للتخزين.

المهام:

1. إنشاء module باسم `marketplace-disputes`.
2. إضافة `Dispute`, `DisputeMessage`, `Report`, `DisputeStatus`.
3. إضافة endpoints للمستخدم والإدارة.
4. نشر event عند حل النزاع.
5. ربط النزاع بالـ booking وpayment عند الحاجة.

معيار القبول:

- طرفا الحجز فقط أو admin يستطيعون رؤية النزاع.
- حل النزاع يمكن أن يطلق refund flow لاحقًا.

### المرحلة 7: تحسين البحث Search

**سبب الأولوية:** البحث الحالي يحتاج criteria واضحة للفلاتر والفرز.

المصدر الرسمي الرئيسي:

- Spring Data paging/sorting.
- Spring Guides للـ REST query parameters.
- PostgreSQL indexes عبر Flyway migrations.

المهام:

1. إضافة `SearchCriteria`.
2. دعم فلاتر السعر، التصنيف، التوفر، التقييم، والموقع.
3. تعديل shared search port ليقبل criteria.
4. إضافة indexes مناسبة.
5. إضافة tests للفلترة والفرز.

معيار القبول:

- لا يتم كسر API البحث الحالي دون versioning أو compatibility plan.
- pagination والفرز يعملان بشكل متوقع.

### المرحلة 8: WebSocket للرسائل الفورية

**سبب الأولوية:** وحدة messaging موجودة REST، لكن realtime يحتاج WebSocket/STOMP إذا كان مطلوبًا للمنتج.

المصدر الرسمي الرئيسي:

- Spring Guides الخاصة بـ WebSocket messaging.
- Spring Security لحماية القنوات.

المهام:

1. إضافة `WebSocketConfig`.
2. تفعيل endpoint مثل `/ws`.
3. نشر الرسائل عبر `SimpMessagingTemplate`.
4. التأكد أن المشاركين فقط يستقبلون رسائل المحادثة.

معيار القبول:

- إرسال message عبر REST أو socket يحدّث المشتركين.
- المستخدم غير المشارك لا يستطيع subscribe لمحادثة غيره.

### المرحلة 9: OpenAPI وتوثيق الواجهات

**سبب الأولوية:** تسهيل تكامل الفرونت إند والاختبار اليدوي.

المصدر الرسمي الرئيسي:

- Spring Boot compatibility documentation قبل اختيار library.
- وثائق library الرسمية المختارة، مع التأكد من توافقها مع Spring Boot 4.

المهام:

1. اختيار OpenAPI library متوافقة صراحةً مع Spring Boot 4/Spring Framework 7.
2. إضافة security scheme لـ Bearer JWT.
3. إضافة tags للوحدات.
4. توثيق رابط Swagger UI في README عند إضافته.

معيار القبول:

- Swagger/OpenAPI يعمل مع security scheme.
- لا توجد dependency conflicts مع Boot 4.

## 5. Definition of Done لكل وحدة جديدة

كل module جديد لا يعتبر مكتملًا إلا إذا تحقق التالي:

1. مضاف إلى parent `pom.xml`.
2. لديه `pom.xml` مستقل ولا يكرر versions يديرها parent/BOM.
3. لديه package واضح تحت `com.marketplace.<module>`.
4. لديه migration جديدة إن أضاف تخزينًا.
5. لديه service tests أو integration tests.
6. لا يكسر `mvn clean verify`.
7. ينجح `ModulithVerificationTest`.
8. يحتوي على authorization rules إن كان لديه endpoints.
9. موثق في هذه الخطة أو README عند تغيير طريقة التشغيل.

## 6. أوامر التحقق القياسية

يجب تشغيل هذه الأوامر قبل كل PR كبير:

```bash
mvn clean verify
mvn -pl marketplace-app -am test
mvn -pl marketplace-app -am spring-boot:run -Dspring-boot.run.profiles=dev
```

وعند إضافة أو تعديل قاعدة البيانات محليًا:

```bash
docker compose up -d postgres redis
mvn -pl marketplace-app -am spring-boot:run -Dspring-boot.run.profiles=dev
```

## 7. أولويات MVP المعتمدة

لإطلاق MVP متوازن، الترتيب المعتمد هو:

1. Provider
2. Availability
3. Payment Gateway + Webhooks
4. Notifications
5. Admin Enhancements
6. Search Filters
7. Ledger/Settlements
8. Disputes
9. Realtime Messaging
10. OpenAPI

هذا الترتيب يمنع بناء ميزات مالية أو إشعارات فوق بيانات مزودين ومواعيد غير مكتملة.

## 8. سياسة PRs

- PR لكل مرحلة أو feature صغيرة داخل المرحلة، وليس PR واحد ضخم لكل الخطة.
- PR body يجب أن يحتوي:
  - المصدر الرسمي المعتمد.
  - التغيير الوظيفي.
  - migrations المضافة.
  - tests التي شُغلت.
  - أي انحراف عن الوثائق الرسمية أو عن قواعد هذه الوثيقة.
- لا يُدمج PR يضيف module دون passing tests وModulith verification.
