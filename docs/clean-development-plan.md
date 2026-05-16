# خطة تطوير نظيفة — Clean Development Plan
> Based on Spring Boot 4.0.6, Spring Modulith, Spring Framework 7,
> Official Sources: https://docs.spring.io/spring-boot/reference, https://spring.io/guides, https://maven.apache.org/guides
>
> استثناءات المجتمع: springdoc-openapi 3.0.3 (Exception #7), MapStruct 1.6.3 (Exception #8), Instancio (Exception #9)

---

## الالتزام بالقواعد — 3 استثناءات مجتمعية فقط (#7 springdoc, #8 MapStruct, #9 Instancio)

| القاعدة | الالتزام |
|---------|----------|
| 1. لا اعتماديات خارج توافق Boot 4.x | ✅ استثناءات #7-#9 فقط (موثقة أدناه) |
| 2. لا تحديد إصدارات BOM | ✅ جميع الإصدارات من Boot BOM أو Modulith BOM |
| 3. قدرة أعمال جديدة = موديول Maven جديد | ✅ لا حاجة لموديولات جديدة |
| 4. حدود Modulith عبر APIs/أحداث عامة | ✅ المحافظة على التواصل بين الموديولات |
| 5. تغيير DB = ملف ترحيل جديد | ✅ عند الحاجة |
| 6. كل endpoint = اختبار واحد على الأقل | ✅ كل مرحلة تضيف اختبارات |
| 7. ميزات أمنية = قواعد تفويض + اختبارات | ✅ GraphQL + WebSocket تتضمن اختبارات أمنية |
| 8. أي انحراف عن الوثائق الرسمية = موثق | ✅ جميع الاستثناءات موثقة أدناه |

---

## قائمة الاستثناءات (Exceptions Registry)

| # | المكتبة | الإصدار | النوع | السبب | المصدر |
|---|---------|---------|-------|-------|--------|
| 1 | Spring Modulith BOM | 2.0.6 | رسمي | Boot لا يدير Modulith | https://docs.spring.io/spring-modulith |
| 3 | Resilience4j BOM | 2.4.0 | رسمي | Boot لا يدير resilience4j-spring-boot4 | https://resilience4j.readme.io |
| 4 | ArchUnit | 1.4.2 | رسمي | اختباري فقط، ليس في Boot BOM | https://www.archunit.org |
| 5 | Spring REST Docs BOM | 4.0.0 | رسمي | يدير spring-restdocs-mockmvc | https://docs.spring.io/spring-restdocs |
| 6 | spring-graphql-test | 2.0.3 | رسمي | ليس في Boot BOM | https://docs.spring.io/spring-graphql |
| 7 | springdoc-openapi | 3.0.3 | **مجتمع** | يولّد OpenAPI/Swagger تلقائياً | https://springdoc.org |
| 8 | **MapStruct** | **1.6.3** | **مجتمع** | **يولّد mapper بين Entity↔DTO تلقائياً** | **https://mapstruct.org** |
| 9 | **Instancio** | **6.0.0-RC3** | **مجتمع** | **يملأ كائنات الاختبار ببيانات عشوائية** | **https://www.instancio.org** |

---

## المصادر الرسمية (Official Sources)

| المصدر | الرابط |
|--------|--------|
| Spring Boot Reference | https://docs.spring.io/spring-boot/reference/index.html |
| Spring Boot Testing | https://docs.spring.io/spring-boot/reference/testing/index.html |
| Spring Boot GraphQL | https://docs.spring.io/spring-boot/reference/web/spring-graphql.html |
| Spring GraphQL Testing | https://docs.spring.io/spring-graphql/reference/testing.html |
| Spring Framework WebSocket | https://docs.spring.io/spring-framework/reference/web/websocket/stomp/testing.html |
| Spring Modulith Reference | https://docs.spring.io/spring-modulith/reference/index.html |
| Spring Modulith Testing | https://docs.spring.io/spring-modulith/reference/testing.html |
| Spring Security Method Security | https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html |
| Maven Multi-Module | https://maven.apache.org/guides/mini/guide-multiple-modules.html |
| JaCoCo Maven Plugin | https://www.jacoco.org/jacoco/trunk/doc/maven.html |
| Guide: GraphQL Server | https://spring.io/guides/gs/graphql-server |
| Guide: Testing Web Layer | https://spring.io/guides/gs/testing-web |
| Guide: CORS | https://spring.io/guides/gs/rest-service-cors |
| Guide: Actuator | https://spring.io/guides/gs/actuator-service |
| Guide: WebSocket | https://spring.io/guides/gs/messaging-stomp-websocket |

---

---

## كيف يصبح الوضع مع MapStruct + Instancio؟

### قبل الاستثناءات — يدوياً (16 ملف، ~160 سطر)

**كل موديول يكرر نفس النمط:**
```java
// marketplace-booking/BookingResponse.java — 9 سطور يدوية
public record BookingResponse(UUID id, UUID consumerId, UUID providerId,
                              UUID listingId, BookingStatus status,
                              Long priceCents, String currency, String notes) {
    public static BookingResponse from(Booking booking) {
        return new BookingResponse(
            booking.getId(), booking.getConsumerId(),
            booking.getProviderId(), booking.getListingId(),
            booking.getStatus(), booking.getPriceCents(),
            booking.getCurrency(), booking.getNotes()
        );
    }
}
```

**كل خدمة تكرر نفس النمط:**
```java
// BookingService.java — 13 سطر يدوية
private BookingSummary toBookingSummary(Booking booking) {
    return new BookingSummary(
        booking.getId(), booking.getConsumerId(),
        booking.getProviderId(), booking.getListingId(),
        booking.getStatus(), booking.getPriceCents(),
        booking.getCurrency(), booking.getNotes(),
        booking.getCreatedAt()
    );
}
```

**كل اختبار يكرر إنشاء البيانات:**
```java
// BookingTest.java — 5 أسطر لكل كيان
var id = UUID.randomUUID();
var consumerId = UUID.randomUUID();
var providerId = UUID.randomUUID();
var listingId = UUID.randomUUID();
var booking = new Booking(id, consumerId, providerId, listingId, 3000L, "test");
```

### بعد الاستثناءات — MapStruct + Instancio

**1. ملف mapper واحد بدلاً من 16:**
```java
// MapperCentral.java — يُنشئ كل mappings المكررة تلقائياً
@Mapper(componentModel = "spring")
public interface CentralMapper {
    BookingResponse toBookingResponse(Booking booking);
    BookingSummary toBookingSummary(Booking booking);
    UserResponse toUserResponse(User user);
    UserSummary toUserSummary(User user);
    ProviderResponse toProviderResponse(ProviderProfile profile);
    PaymentResponse toPaymentResponse(Payment payment);
    PaymentIntentResponse toPaymentIntentResponse(PaymentIntent intent);
    MessageResponse toMessageResponse(Message message);
    ConversationResponse toConversationResponse(Conversation conversation);
    ReviewResponse toReviewResponse(Review review);
    ListingResponse toListingResponse(ProviderListing listing);
    // MapStruct ينفذ 11 method تلقائياً — بدون كتابة أي سطر
}
```

**2. في RestController — استخدام mapper:**
```java
@RestController
public class BookingController {
    private final CentralMapper mapper;

    @GetMapping("/api/bookings/{id}")
    public BookingResponse getBooking(@PathVariable UUID id) {
        Booking booking = bookingService.findById(id);
        return mapper.toBookingResponse(booking);  // لا mapping يدوي
    }
}
```

**3. في الاختبارات — Instancio يملأ الكيانات تلقائياً:**
```java
class BookingTest {
    @Test
    void createSetsPendingStatus() {
        var booking = Instancio.create(Booking.class);
        // booking مملوء ببيانات عشوائية — id, consumerId, providerId, إلخ
        assertNotNull(booking.getId());
        assertEquals(BookingStatus.PENDING, booking.getStatus());
    }

    @Test
    void confirmChangesFromPendingToConfirmed() {
        var booking = Instancio.create(Booking.class);
        booking.confirm();
        assertEquals(BookingStatus.CONFIRMED, booking.getStatus());
    }
}
```

### إعدادات Maven لإضافة الاستثناءات

**في `pom.xml` (root):**
```xml
<properties>
    <mapstruct.version>1.6.3</mapstruct.version>
    <instancio.version>6.0.0-RC2</instancio.version>
</properties>

<dependencyManagement>
    <dependencies>
        <!-- Exception #8: MapStruct — يلغي ~160 سطر mapping يدوي -->
        <dependency>
            <groupId>org.mapstruct</groupId>
            <artifactId>mapstruct</artifactId>
            <version>${mapstruct.version}</version>
        </dependency>
        <!-- Exception #9: Instancio — يلغي إنشاء بيانات الاختبار يدوياً -->
        <dependency>
            <groupId>org.instancio</groupId>
            <artifactId>instancio-junit</artifactId>
            <version>${instancio.version}</version>
            <scope>test</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

**في `marketplace-app/pom.xml`:**
```xml
<!-- Exception #8: MapStruct — معالج annotations لإنشاء mapper -->
<dependency>
    <groupId>org.mapstruct</groupId>
    <artifactId>mapstruct</artifactId>
</dependency>

<!-- Exception #9: Instancio — بيانات اختبار عشوائية -->
<dependency>
    <groupId>org.instancio</groupId>
    <artifactId>instancio-junit</artifactId>
    <scope>test</scope>
</dependency>
```

**في `maven-compiler-plugin` (root pom.xml):**
```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <annotationProcessorPaths>
            <path>
                <groupId>org.mapstruct</groupId>
                <artifactId>mapstruct-processor</artifactId>
                <version>${mapstruct.version}</version>
            </path>
        </annotationProcessorPaths>
    </configuration>
</plugin>
```

### الفرق بالعدد

| المقارنة | بدون استثناءات | مع MapStruct + Instancio |
|----------|---------------|------------------------|
| ملفات mapping يدوية | 16 ملف (160 سطر) | ملف mapper واحد (11 سطر) |
| إنشاء بيانات في الاختبارات | كل اختبار يكتب UUID يدوياً | `Instancio.create()` سطر واحد |
| صيانة الحقول الجديدة | تعديل 16 ملف | إضافة method واحدة في mapper |
| أخطاء runtime | ❌ ممكنة (ينسى مطوّر حقل) | ✅ اكتشاف في compilation |

> **الخلاصة:** MapStruct يلغي 16 ملف mapping ويحوّلها إلى interface واحد. Instancio يلغي كتابة `UUID.randomUUID()` وبيانات الكيان يدوياً في كل اختبار.

---

## مراحل التنفيذ (Execution Phases)

### 🟢 Phase 0: Baseline Verification & JaCoCo Setup — P0 (10 دقائق)

**المصادر:**
- https://www.jacoco.org/jacoco/trunk/doc/maven.html
- https://maven.apache.org/surefire/maven-surefire-plugin

**ما يجب فعله:**
1. تشغيل `mvnw clean test -Djacoco.skip=true` — التحقق من أن النسخة النظيفة تبني بدون أخطاء
2. إضافة **JaCoCo 0.8.14** (مفقود تماماً في النسخة النظيفة!):
   - إضافة خاصية `<jacoco.version>0.8.14</jacoco.version>` في `pom.xml` (root)
   - إضافة خاصية `<jacoco.coverage.threshold>0.70</jacoco.coverage.threshold>`
   - إضافة `jacoco-maven-plugin` في `<build><plugins>` بنفس إعدادات النسخة المعدّلة
3. تشغيل `mvnw clean verify -Djacoco.skip=true` — تحقق من البناء

**الملفات المتأثرة:**
- `pom.xml` (root) — إضافة JaCoCo properties + plugin

**التحقق:**
```bash
mvnw clean compile -Djacoco.skip=true
# BUILD SUCCESS
```

---

### 🟢 Phase 1: Library Updates & Exceptions Setup — P0 (15 دقيقة)

**المصادر:**
- https://docs.spring.io/spring-modulith/reference/index.html
- https://maven.apache.org/guides/introduction/introduction-to-the-pom.html

**التغييرات في `pom.xml` (root):**

| الخاصية | من | إلى | السبب |
|---------|-----|-----|-------|
| `archunit.version` | 1.4.1 | **1.4.2** | إصلاحات أخطاء + توافق Modulith |
| Modulith BOM | 2.0.5 | **2.0.6** | إصلاح فقدان أحداث JDBC (#1653) |

**إضافة استثناء #7 — springdoc-openapi:**
```xml
<properties>
    <springdoc.version>3.0.3</springdoc.version>
</properties>

<dependencyManagement>
    <dependencies>
        <!-- Exception #7: springdoc-openapi — community OpenAPI auto-generation (non-Spring) -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>${springdoc.version}</version>
        </dependency>
    </dependencies>
</dependencyManagement>
```

**إضافة استثناء #5 — Spring REST Docs BOM:**
```xml
<!-- Exception #5: Spring REST Docs BOM — manages spring-restdocs-mockmvc -->
<dependency>
    <groupId>org.springframework.restdocs</groupId>
    <artifactId>spring-restdocs-bom</artifactId>
    <version>4.0.0</version>
    <type>pom</type>
    <scope>import</scope>
</dependency>
```

**إضافة استثناء #6 — spring-graphql-test:**
```xml
<!-- Exception #6: spring-graphql-test — not managed by Spring Boot BOM -->
<dependency>
    <groupId>org.springframework.graphql</groupId>
    <artifactId>spring-graphql-test</artifactId>
    <version>2.0.3</version>
    <scope>test</scope>
</dependency>
```

**إضافة الاعتماديات في `marketplace-app/pom.xml`:**
```xml
<!-- Exception #7: springdoc-openapi — community OpenAPI + Swagger UI -->
<dependency>
    <groupId>org.springdoc</groupId>
    <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
    <exclusions>
        <exclusion>
            <groupId>org.apache.commons</groupId>
            <artifactId>commons-lang3</artifactId>
        </exclusion>
    </exclusions>
</dependency>

<!-- Exception #5: Spring REST Docs MockMvc -->
<dependency>
    <groupId>org.springframework.restdocs</groupId>
    <artifactId>spring-restdocs-mockmvc</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-restdocs</artifactId>
    <scope>test</scope>
</dependency>

<!-- Exception #6: spring-graphql-test -->
<dependency>
    <groupId>org.springframework.graphql</groupId>
    <artifactId>spring-graphql-test</artifactId>
    <scope>test</scope>
</dependency>
```

**التحقق:**
```bash
mvnw clean compile -Djacoco.skip=true
# BUILD SUCCESS — جميع الاستثناءات السبع
```

---

### 🟡 Phase 2: springdoc Integration (Replace Manual OpenAPI) — P2 (30 دقيقة)

**المصادر:**
- https://springdoc.org (استثناء مجتمعي #7)
- https://docs.spring.io/spring-boot/reference/web/spring-graphql.html
- https://spring.io/guides/gs/rest-service-cors

**المشكلة:**
المشروع حالياً يخدم OpenAPI يدوياً عبر `OpenApiDocsController` من ملف YAML ثابت (2,140+ سطر). springdoc يُنشئ التوثيق تلقائياً من الكود.

**ما يجب فعله:**
1. **الاعتمادية:** تمت إضافتها في Phase 1
2. **حذف الملفات اليدوية (اختياري):**
   - حذف `marketplace-app/src/main/java/com/marketplace/app/docs/OpenApiDocsController.java`
   - حذف `marketplace-app/src/main/resources/openapi/marketplace-openapi.yaml`
   - حذف `marketplace-app/src/test/java/com/marketplace/app/docs/OpenApiDocsControllerTest.java`
   - حذف `marketplace-app/src/test/java/com/marketplace/app/docs/OpenApiDocsStaticPageTest.java`
   - حذف `marketplace-app/src/test/java/com/marketplace/app/docs/OpenApiSpecValidationTest.java`
3. **تحديث application.yml أو application-dev.yml** — إضافة تكوين springdoc:
```yaml
springdoc:
  api-docs:
    path: /v3/api-docs
  swagger-ui:
    path: /swagger-ui.html
    enabled: true
```

**التحقق:**
```bash
# بعد تشغيل التطبيق:
# GET http://localhost:8080/v3/api-docs — يعرض OpenAPI JSON تلقائي
# GET http://localhost:8080/swagger-ui.html — يعرض Swagger UI
```

**الاختبارات:** springdoc لا يتطلب اختبارات إضافية (لا يوجد كود جديد لاختباره).

---

### 🟡 Phase 3: REST Docs Integration — P2 (30 دقيقة)

**المصادر:**
- https://spring.io/guides/gs/testing-web
- https://docs.spring.io/spring-restdocs/docs/current/reference/html5/

**ما يجب فعله:**
1. الاعتماديات تمت إضافتها في Phase 1
2. إنشاء `BookingApiDocumentationTest.java` — اختبار REST Docs لـ `BookingController` باستخدام `@ExtendWith(RestDocumentationExtension.class)` + `MockMvc` مستقل (standalone)

```java
@ExtendWith(RestDocumentationExtension.class)
class BookingApiDocumentationTest {
    private MockMvc mockMvc;

    @BeforeEach
    void setUp(RestDocumentationContextProvider provider) {
        mockMvc = MockMvcBuilders.standaloneSetup(new BookingController(...))
            .apply(documentationConfiguration(provider))
            .build();
    }

    @Test
    void createBooking() throws Exception {
        mockMvc.perform(post("/api/bookings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(""" { "listingId": "...", "consumerId": "...", "providerId": "..." } """))
            .andExpect(status().isOk())
            .andDo(document("bookings/create"));
    }
}
```

**ملاحظة مهمة:** `spring-restdocs-mockmvc` غير مُدار من Boot BOM. استثناء #5 (REST Docs BOM 4.0.0) يديره.

**التحقق:**
```bash
mvnw test -Dtest=*Documentation* -Djacoco.skip=true
# BUILD SUCCESS — مستندات REST API تُولد في target/generated-snippets
```

---

### 🟡 Phase 4: GraphQL Data Fetchers + Tests — P1 (ساعة)

**المصادر:**
- https://spring.io/guides/gs/graphql-server
- https://docs.spring.io/spring-boot/reference/web/spring-graphql.html
- https://docs.spring.io/spring-graphql/reference/testing.html
- https://docs.spring.io/spring-security/reference/servlet/authorization/method-security.html

**ما يجب فعله:**

#### 4.1 — تحديث GraphQL Schema
`src/main/resources/graphql/schema.graphqls`:
```graphql
input ServiceInput {
    name: String!
    description: String!
    price: Float!
    category: String!
}
```

#### 4.2 — إنشاء ServiceGraphQlController
```java
@Controller
public class ServiceGraphQlController {
    private final CatalogService catalogService;
    private final CurrentUserProvider currentUserProvider;

    @QueryMapping
    public ServiceResponse service(@Argument String id) { ... }

    @QueryMapping
    public List<ServiceResponse> services() { ... }

    @MutationMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ServiceResponse createService(@Argument ServiceInput input) { ... }
}
```

#### 4.3 — إنشاء DTOs
- `ServiceResponse.java` — يحوّل `ProviderListing` إلى GraphQL type
- `ServiceInput.java` — record لمدخلات mutation

#### 4.4 — إنشاء POJO Tests (لأن `@GraphQlTest` غير متوفر في Boot 4.0.6)
- `ServiceGraphQlControllerTest.java` — 5 اختبارات POJO مع mock
- ملفات query: `graphql-test/service.graphql`, `services.graphql`, `createService.graphql`
- اختبار إضافي للتحقق من وجود `@PreAuthorize` (annotation presence test) — 1 test

**الاختبارات:**
- `service_byId_returnsResponse` — استعلام بمعرف موجود
- `services_returnsAll` — استعلام كل الخدمات
- `services_whenEmpty_returnsEmptyList` — قائمة فارغة
- `service_byUnknownId_throwsResourceNotFound` — معرف غير موجود
- `createService_createsAndReturnsResponse` — إنشاء خدمة
- `createService_hasPreAuthorizeAdmin` — التحقق من وجود `@PreAuthorize` (1 test)

---

### 🟡 Phase 5: WebSocket Message Handling + Tests — P2 (30 دقيقة)

**المصادر:**
- https://spring.io/guides/gs/messaging-stomp-websocket
- https://docs.spring.io/spring-framework/reference/web/websocket/stomp/testing.html

**ما يجب فعله:**

#### 5.1 — إنشاء MessagingWebSocketController
```java
@Controller
public class MessagingWebSocketController {
    private final MessagingService messagingService;

    @MessageMapping("/chat/{conversationId}")
    @SendTo("/topic/conversations/{conversationId}")
    public MessageResponse sendMessage(@DestinationVariable UUID conversationId,
                                       @Payload SendMessagePayload payload,
                                       Principal principal) { ... }
}
```

#### 5.2 — إنشاء POJO Tests
- `MessagingWebSocketControllerTest.java` — 2 اختبارات:
  - `sendMessage_returnsResponse` — إرسال رسالة واستلام رد
  - `sendMessage_withInvalidConversation_throws` — محادثة غير صالحة

---

### 🔵 Phase 6: Cross-Module @ApplicationModuleTest — P1 (30 دقيقة)

**المصادر:**
- https://docs.spring.io/spring-modulith/reference/testing.html

**ما يجب فعله:**
إنشاء 14 ملف اختبار في `marketplace-app/src/test/java/com/marketplace/<module>/`:

| الموديول | الملف | Docker؟ |
|----------|-------|---------|
| marketplace-shared | `SharedModuleIntegrationTest` | ❌ لا (يعمل بدون Docker) |
| marketplace-platform-infra | `PlatformInfraModuleIntegrationTest` | ✅ نعم |
| marketplace-booking | `BookingModuleIntegrationTest` | ✅ نعم |
| marketplace-payments | `PaymentsModuleIntegrationTest` | ✅ نعم |
| marketplace-pricing | `PricingModuleIntegrationTest` | ✅ نعم |
| marketplace-availability | `AvailabilityModuleIntegrationTest` | ✅ نعم |
| marketplace-provider | `ProviderModuleIntegrationTest` | ✅ نعم |
| marketplace-catalog | `CatalogModuleIntegrationTest` | ✅ نعم |
| marketplace-messaging | `MessagingModuleIntegrationTest` | ✅ نعم |
| marketplace-identity | `IdentityModuleIntegrationTest` | ✅ نعم |
| marketplace-notifications | `NotificationsModuleIntegrationTest` | ✅ نعم |
| marketplace-ledger | `LedgerModuleIntegrationTest` | ✅ نعم |
| marketplace-disputes | `DisputesModuleIntegrationTest` | ✅ نعم |
| marketplace-reviews | `ReviewsModuleIntegrationTest` | ✅ نعم |

**نمط الاختبار للموديولات التي تحتاج Docker:**
```java
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class BookingModuleIntegrationTest {
    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgres:17-alpine"))
            .withDatabaseName("marketplace");

    @Test
    void contextLoads() {}
}
```

**نمط الاختبار للموديولات بدون Docker (shared فقط):**
```java
@ApplicationModuleTest(mode = BootstrapMode.DIRECT_DEPENDENCIES)
@ActiveProfiles("test")
class SharedModuleIntegrationTest {
    @Test
    void contextLoads() {}
}
```

**التحقق:**
```bash
mvnw test -pl marketplace-app -Djacoco.skip=true
# 14 اختبار (1 يجتاز + 13 يتخطى بسبب عدم وجود Docker)
```

---

### 🔵 Phase 7: Domain Entity Unit Tests — P2 (30 دقيقة)

**المصادر:**
- https://spring.io/guides/gs/testing-web
- https://junit.org/junit5/docs/current/user-guide/

**ما يجب فعله:**
إنشاء اختبارات للكيانات ذات المنطق التجاري:

| الموديول | الكيان | الاختبارات | الملف |
|----------|--------|-----------|-------|
| marketplace-provider | `ProviderProfile` | 5 | `ProviderProfileTest.java` |
| marketplace-reviews | `Review` | 7 | `ReviewTest.java` |
| marketplace-pricing | `PricingRule` | 4 | `PricingRuleTest.java` |

**ProviderProfileTest (5 اختبارات):**
- `createSetsPendingStatus` — التحقق من الحالة الابتدائية PENDING
- `verifyChangesToVerified` — تغيير الحالة إلى VERIFIED
- `suspendChangesToSuspended` — تغيير الحالة إلى SUSPENDED
- `updateChangesDisplayNameAndBio` — تحديث الاسم والوصف
- `verifyFromSuspendedStillWorks` — إعادة التفعيل بعد التعليق

**ReviewTest (7 اختبارات):**
- `createSetsFields` — إنشاء تقييم والتحقق من جميع الحقول
- `createWithNullComment` — إنشاء بتعليق فارغ
- `createThrowsWhenRatingBelow1` — رفض تقييم أقل من 1
- `createThrowsWhenRatingAbove5` — رفض تقييم أكثر من 5
- `updateChangesRatingAndComment` — تحديث التقييم
- `updateThrowsWhenRatingInvalid` — رفض تحديث بتقييم غير صالح
- `allArgsConstructorSetsFields` — اختبار constructor الكامل

**PricingRuleTest (4 اختبارات):**
- `createSetsFields` — إنشاء قاعدة تسعير
- `deactivateSetsActiveToFalse` — إلغاء التفعيل
- `activateReactivatesAfterDeactivate` — إعادة التفعيل بعد الإلغاء
- `allArgsConstructorSetsFields` — اختبار constructor الكامل

**تبسيط مع Instancio (Exception #9):** استخدم `Instancio.create(Booking.class)` بدلاً من كتابة `UUID.randomUUID()` يدوياً لكل حقل. Instancio يملأ الكائن كاملاً ببيانات عشوائية — مفيد في اختبارات الحالة والتحقق من المنطق دون إعداد يدوي.

**ملاحظة:** الكيانات الأخرى (AvailabilitySlot, Message, Conversation, Notification, LedgerEntry, ProviderBalance, PaymentWebhookEvent) هي **للقراءة فقط ولا تحتوي على منطق تجاري** — اختباراتها ذات أولوية منخفضة.

---

### 🔵 Phase 8: Modulith Documentation & Architecture Tests — P3 (15 دقيقة)

**المصادر:**
- https://docs.spring.io/spring-modulith/reference/testing.html
- https://docs.spring.io/spring-modulith/reference/documentation.html

**ما يجب فعله:**
1. إضافة `spring-modulith-docs` في `marketplace-app/pom.xml` (اختباري):
```xml
<dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-docs</artifactId>
    <scope>test</scope>
</dependency>
```

2. إنشاء `ModulithDocumentationTest.java`:
```java
@Test
void generateDocumentation() {
    // يُنشئ مخططات PlantUML تلقائياً
}
```

3. `ArchitectureRulesTest.java` — 9 اختبارات للقواعد المعمارية (تبعيات الموديولات، التسمية، إلخ)

---

### 🔵 Phase 9: CORS, API Versioning & Actuator — P3 (20 دقيقة)

**المصادر:**
- https://spring.io/guides/gs/rest-service-cors
- https://docs.spring.io/spring-boot/reference/web/spring-mvc.html#web.servlet.embedded-container.cors
- https://spring.io/guides/gs/actuator-service

**ما يجب فعله:**
1. `CorsConfigurationTest.java` — 6 اختبارات للتحقق من تكوين CORS
2. `ApiVersioningTest.java` — 2 اختبارات للتحقق من رأس `X-API-Version`
3. إضافة `spring-modulith-actuator` في `marketplace-app/pom.xml`:
```xml
<dependency>
    <groupId>org.springframework.modulith</groupId>
    <artifactId>spring-modulith-actuator</artifactId>
</dependency>
```

---

## ملخص التنفيذ (Summary)

| Phase | الوصف | الأولوية | المدة | اختبارات |
|-------|-------|----------|-------|----------|
| 0 | Baseline + JaCoCo | P0 | 10 د | 0 |
| 1 | Library Updates + Exceptions | P0 | 15 د | 0 |
| 2 | springdoc Integration | P2 | 30 د | 0 (حذف يدوي) |
| 3 | REST Docs | P2 | 30 د | 1 |
| 4 | GraphQL + Tests | P1 | 60 د | 6 |
| 5 | WebSocket + Tests | P2 | 30 د | 2 |
| 6 | Module Integration Tests | P1 | 30 د | 14 (1 + 13 skip) |
| 7 | Entity Tests | P2 | 30 د | 16 |
| 8 | Modulith Docs + Architecture | P3 | 15 د | 10 |
| 9 | CORS + API Versioning + Actuator | P3 | 20 د | 8 |

**إجمالي الاختبارات المتوقعة:** ~57 اختبار جديد (بالإضافة إلى الاختبارات الموجودة).

---

## أوامر التحقق القياسية

```bash
# البناء الكامل (بدون Docker)
mvnw clean test -Djacoco.skip=true

# البناء مع التغطية (JaCoCo)
mvnw clean verify

# تشغيل موديول محدد
mvnw -pl marketplace-app -am test -Dtest="*GraphQl*" -Djacoco.skip=true

# تشغيل التطبيق محلياً (يتطلب Docker)
mvnw -pl marketplace-app -am spring-boot:run -Dspring-boot.run.profiles=dev

# التحقق من تبعيات Maven
mvnw dependency:resolve -pl marketplace-app -am -Djacoco.skip=true
```

---

## المحظورات (Blocked Items)

| الميزة | السبب |
|--------|-------|
| WebSocket E2E Tests (STOMP broker) | يتطلب Docker |
| Testcontainers integration per module | يتطلب Docker |
| Docker Compose dev profile | يتطلب Docker |
| GraalVM Native Image | Modulith + OAuth2 + Quartz مشاكل انعكاس |
| Kafka/AMQP events | يتطلب Docker للوسيط |
