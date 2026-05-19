# PROJECT_MAP — Marketplace Platform (Spring Boot + Maven)

## [PLANNING_ASSUMPTIONS]
- تاريخ التدقيق الزمني من النظام: **2026-05-19 (UTC)**.
- نطاق الخطة يقتصر على backend متعدد الوحدات الحالي فقط (لا front-end، لا mobile، لا data platform مستقل).
- أي تحسين خارج تدفق الأعمال المحدد يعد Feature Creep ويُرحّل إلى backlog رسمي.

## [TECH_STACK]
- Java 21.
- Spring Boot 4.0.6 (موثق في صفحات Spring Boot الرسمية الحالية).
- Apache Maven 3.9.15 (آخر إصدار GA حسب صفحة Maven Releases History الرسمية).
- Maven multi-module architecture (Aggregator root `pom.xml` + domain modules).
- Spring Modulith (وفق BOM بالمشروع) لتنظيم الحدود بين الوحدات.

## [SYSTEM_FLOW]
### تدفق أعمال قابل للتحقق (API-first)
1. **Identity**: إنشاء مستخدم/تسجيل دخول/استرجاع بيانات المستخدم.
2. **Catalog + Provider + Availability**: قراءة الخدمة/المزوّد/التوافر.
3. **Pricing**: تسعير الحجز مع قواعد الرسوم.
4. **Booking**: إنشاء الحجز وتأكيده.
5. **Payments + Ledger**: تنفيذ الدفع وتسجيل القيود المحاسبية.
6. **Notifications + Messaging**: إرسال إشعارات الحالة.
7. **Reviews + Disputes**: تقييم الخدمة ومعالجة النزاعات.
8. **Search**: فهرسة/استرجاع نتائج البحث.

> معيار الالتزام: لا إضافة ميزات خارج هذا التدفق (No Feature Creep).

## [ARCHITECTURE]
### مبادئ التخطيط (من البروتوكول + المصادر الرسمية)
- **Simplicity First**: الالتزام بخصائص Spring Boot التلقائية قبل أي تخصيص.
- **Domain-driven module boundaries**: كل نطاق أعمال في module مستقل مع واجهات تكامل واضحة.
- **Minimal shared core**: استخدام `marketplace-shared` فقط للكود المتكرر فعلياً.
- **Safe Logging**:
  - استخدام مستويات logging الأساسية (ERROR/WARN/INFO/DEBUG) فقط.
  - تفعيل async appenders عبر Logback عند ارتفاع الحمل.
  - منع تسريب PII في السجلات.
- **Dependency governance (Maven)**:
  - الاعتماد على Spring Boot parent/BOM لإدارة الإصدارات.
  - أي override يجب توثيقه بسبب واضح (CVE، bugfix، أو توافقية).
  - تثبيت maven-enforcer rules (convergence + upper bounds + Java/Maven versions).

## خطة تطوير (Milestones) مبنية على أهداف قابلة للتحقق

### M1 — Baseline & Dependency Trust
- تحديث شهري للإصدارات المستقرة (Spring Boot + Maven + المكونات الحرجة).
- التحقق من عدم وجود dependencies deprecated أو ذات CVEs حرجة.
- **Goal قابل للتحقق**:
  - `mvn -q -DskipTests validate` ينجح على root aggregator.
  - `mvn -q -DskipTests verify` ينجح لضمان تفعيل قواعد enforcer ضمن دورة البناء الطبيعية.
  - توثيق أي استثناءات إصدار داخل root `pom.xml`.

### M2 — API Contract Hardening
- توحيد عقود REST (request/response/error envelope) لكل module عبر OpenAPI.
- تطبيق validation annotations + standardized exception handling.
- **Goal قابل للتحقق**:
  - اختبارات controller الأساسية تمر في كل module.
  - OpenAPI endpoints تعمل دون تعارض schema.

### M3 — Transactional Consistency
- توثيق boundaries للمعاملات بين Booking/Payments/Ledger.
- إضافة اختبارات تكامل للسيناريوات الحرجة (success, rollback, idempotency).
- **Goal قابل للتحقق**:
  - اختبار تكامل end-to-end لحجز + دفع + قيد محاسبي يمر.

### M4 — Observability & Operational Safety
- تفعيل Actuator health/readiness/liveness + metrics الأساسية.
- ضبط logging async وcorrelation ids على مسارات الطلب.
- **Goal قابل للتحقق**:
  - `/actuator/health` و`/actuator/metrics` تعمل ضمن profile التشغيل.

### M5 — Quality Gate & Regression Safety
- رفع حد التغطية إلى المتفق عليه في JaCoCo مع منع تراجع الجودة.
- التحقق من حدود المعمارية بواسطة ArchUnit.
- **Goal قابل للتحقق**:
  - `mvn test` يمر في المشروع كاملاً.
  - بوابة JaCoCo + ArchUnit تمنع merge عند أي خرق.

## [ORPHANS & PENDING]
- [x] توثيق مصفوفة توافق الإصدارات (Boot/Java/Maven/plugins) داخل `docs/dependency-matrix.md`.
- [x] إضافة Runbook تشغيل production (profiles, env vars, health checks) داخل `docs/production-runbook.md`.
- [x] إضافة سيناريوات chaos/timeout لاختبارات Payments وMessaging داخل `docs/chaos-timeout-test-plan.md`.
- [x] توحيد سياسة error codes عبر جميع controllers داخل `docs/error-code-policy.md` مدعومة باختبارات `GlobalExceptionHandler`.

## [EXECUTION_TRACK]
- ترتيب التنفيذ العملي اليومي موثق داخل: `docs/practical-execution-order.md`.
- أي تغيير في الأولويات يجب أن ينعكس في هذا الملف قبل بدء التنفيذ الفني.

## المصادر الرسمية المعتمدة
- Spring Boot Documentation Overview: https://docs.spring.io/spring-boot/documentation.html
- Spring Boot Reference: https://docs.spring.io/spring-boot/reference/index.html
- Spring Boot System Requirements: https://docs.spring.io/spring-boot/system-requirements.html
- Spring Blog (إصدارات Boot): https://spring.io/blog
- Maven Releases History: https://maven.apache.org/docs/history.html
- Maven 3.9.15 Release Notes: https://maven.apache.org/docs/3.9.15/release-notes.html
- Spring Guides: https://spring.io/guides
- Guide — Building a RESTful Web Service: https://spring.io/guides/gs/rest-service/
- Guide — Actuator Service: https://spring.io/guides/gs/actuator-service
