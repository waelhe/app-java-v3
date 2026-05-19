# Practical Execution Order (Step-by-Step)

Last updated: 2026-05-19 (UTC)

هذا الملف يحول خطة `PROJECT_MAP.md` إلى تسلسل تنفيذ يومي قابل للتطبيق فوراً.

## Phase A — Baseline Stabilization (اليوم 1)

1. تحقق البيئة والبناء:
   ```bash
   mvn -q -DskipTests validate
   mvn -q -DskipTests verify
   ```
2. في حال فشل تنزيل من Maven Central (502/503):
   - أعد المحاولة.
   - ثبّت mirror موثوق في `~/.m2/settings.xml` (داخل بيئة CI/agent).
3. حدّث نتيجة baseline في `docs/phase-0-baseline-checklist.md`.

**Exit Criteria**
- جميع أوامر الـ baseline تمر.
- لا يوجد dependency override غير موثّق.

## Phase B — API Contract Hardening (اليومان 2-3)

1. اعتماد `docs/error-code-policy.md` كمرجع رسمي للأخطاء.
2. توسيع اختبارات negative-path في controllers الأعلى أولوية:
   - payments
   - messaging
   - booking
3. توسيع OpenAPI schemas للـ request/response/error payload.

**Commands**
```bash
mvn -pl marketplace-payments -am test
mvn -pl marketplace-messaging -am test
mvn -pl marketplace-booking -am test
```

**Exit Criteria**
- توحيد status/type للأخطاء في الوحدات الثلاث.
- اختبارات controller السلبية تمر.

## Phase C — Transactional Consistency (اليومان 4-5)

1. كتابة اختبار تكامل E2E لمسار:
   - Booking -> Payment -> Ledger
2. تضمين سيناريو success + rollback + idempotency.

**Commands**
```bash
mvn -pl marketplace-app -am test
```

**Exit Criteria**
- اختبار تكامل واحد على الأقل يغطي المسار الكامل ويمر بنجاح.

## Phase D — Resilience / Chaos Readiness (الأسبوع 2)

1. تنفيذ السيناريوات المذكورة في `docs/chaos-timeout-test-plan.md` داخل اختبارات فعلية.
2. قياس أثر timeout والتأكد من fail-fast behavior.
3. ربط النتائج بـ logs وmetrics.

**Commands**
```bash
mvn -pl marketplace-shared -am test
mvn -pl marketplace-payments -am test
mvn -pl marketplace-messaging -am test
```

**Exit Criteria**
- لا توجد unhandled exceptions في سيناريوات chaos المستهدفة.
- جميع نتائج الأخطاء مطابقة لسياسة `docs/error-code-policy.md`.

## Phase E — Operational Readiness (قبل أي Release)

1. تطبيق `docs/production-runbook.md` حرفياً في staging.
2. فحص actuator health/metrics.
3. التحقق من rollback path عبر إصدار تجريبي.

**Commands**
```bash
curl -fsS http://localhost:8080/actuator/health
curl -fsS http://localhost:8080/actuator/metrics
```

**Exit Criteria**
- runbook قابل للتنفيذ بالكامل.
- فريق التشغيل يوافق على إجراءات الإطلاق والتراجع.

## Single PR Strategy

- PR-1: استقرار baseline + أي إصلاحات dependency.
- PR-2: توحيد error contract + tests (payments/messaging/booking).
- PR-3: تكامل Booking-Payment-Ledger.
- PR-4: chaos/timeout tests.
- PR-5: readiness النهائي وOpenAPI coverage.
