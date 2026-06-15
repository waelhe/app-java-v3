# PROJECT_MAP — Marketplace Backend (app-java-v3)

## Current State (2026-06-14)

**Branch:** `main` (local behind origin — run `git pull`)

## Sprint 1 — H4+H9+H10 (2026-06-14)

### H8 — Webhook eventType dispatch (2026-06-15)
- **Files:** `PaymentsService.java`, `PaymentsController.java`
- **Changes:**
  - Added `processWebhookEvent(..., paymentIntentId, externalId)` overload with event dispatch
  - Added `dispatchWebhookEvent()` — `payment_intent.succeeded` → `confirmIntent()`, logs for other types
  - Added `paymentIntentId` and `externalId` query params to `POST /webhooks/{provider}` endpoint
  - Event names aligned with Stripe convention (`payment_intent.succeeded`)
- **Tests:** 3 new (succeeded dispatches confirmIntent, succeeded without intentId logs warning, controller with intentId)
- **Tests:** 27/27 ✅ (PaymentsServiceTest: 17, PaymentsControllerTest: 9, PaymentWebhookEventServiceTest: 1)
- **WebMvcTest:** 6/6 ✅ (new: webhook with paymentIntentId + externalId returns Accepted)
- **Official docs:** Stripe event types (docs.stripe.com/api/events/types) — `payment_intent.succeeded`, `.processing`, `.payment_failed`


### H4 — PaymentIntent REFUNDED transition
- **Files:** `PaymentIntentStatus.java`, `PaymentIntent.java`, `PaymentsService.java`
- **Changes:**
  - Added `REFUNDED` enum value with transition `SUCCEEDED→REFUNDED`
  - Added `PaymentIntent.markRefunded()` method
  - Updated `PaymentsService.autoRefundByBooking()` to transition intent status
- **Test:** `PaymentIntentStatusTest` — 2 new tests (succeeded_acceptsRefunded, refunded_rejectsAnyTransition)
- **Tests:** 49/49 ✅

### H9 — Email sending from NotificationService
- **Files:** `UserLookupPort.java` (new), `UserLookupPortImpl.java` (new), `NotificationService.java`
- **Changes:**
  - Created `UserLookupPort` interface in `shared` module (`UserLookupPort.findById() → UserSummary`)
  - Implemented `UserLookupPortImpl` in `identity` module
  - `NotificationService.onBookingCreated()/onPaymentStateChanged()` now call `emailService.send()` with resolved user email
- **Test:** `NotificationServiceTest` — new test verifies emailService.send() is invoked
- **Tests:** 14/14 ✅

### H10 — WebSocket notifications
- **Files:** `WebSocketNotification.java` (new), `NotificationService.java`, `pom.xml`
- **Changes:**
  - Added `spring-boot-starter-websocket` dependency to notifications module
  - `NotificationService` injects `Optional<SimpMessagingTemplate>`
  - Sends `WebSocketNotification` to `/topic/notifications/{userId}` on booking creation and payment state change
- **Test:** `NotificationServiceTest` — new test verifies `convertAndSend()` is invoked
- **Tests:** 14/14 ✅

### H5 — Slot auto-generation from ProviderAvailabilityRule (2026-06-15)
- **Files:** `AvailabilityService.java`, `ProviderAvailabilityRule.java`, `ProviderAvailabilityRuleRepository.java`, `pom.xml`
- **Changes:**
  - Added `spring-modulith-moments` + `spring-modulith-events-api` dependencies to availability module
  - Added getters (`getProviderId`, `getDayOfWeek`, `getStartTime`, `getEndTime`) to `ProviderAvailabilityRule`
  - Added `findByDayOfWeek(DayOfWeek)` to repository
  - Added `@ApplicationModuleListener onDayHasPassed(DayHasPassed)` that generates `AvailabilitySlot` for each matching rule, skips duplicates
- **Tests:** 3 new (generates slots, skips existing, does nothing when no rules)
- **Tests:** 18/18 ✅ (AvailabilityServiceTest: 10)
- **Official docs:** Spring Modulith Moments (docs.spring.io/spring-modulith) — `DayHasPassed` event pattern

**Java:** 21
**Spring Boot:** 4.0.6 | **Spring Modulith:** 2.0.6 | **Maven:** 3.9.14 | **JaCoCo:** 0.8.14

---

## Modules (16)

| Module | Type | Coverage | Status |
|--------|------|----------|--------|
| **marketplace-app** | Composition root | ✅ 70% | ✅ |
| **marketplace-platform-infra** | Shared infra (JPA, Security, Cache, Observability) | n/a | ✅ |
| **marketplace-shared** | Shared API interfaces + exceptions | n/a | ✅ |
| **marketplace-provider** | Domain (provider profiles) | ✅ 70% | ✅ |
| **marketplace-catalog** | Domain (listings) | ✅ 70% | ✅ |
| **marketplace-booking** | Domain (bookings, expiration) | ✅ 70% | ✅ |
| **marketplace-payments** | Domain (payments, intents, webhooks) | ✅ 70% | ✅ |
| **marketplace-pricing** | Domain (pricing rules) | ✅ 70% | ✅ |
| **marketplace-reviews** | Domain (reviews) | ✅ 70% | ✅ |
| **marketplace-messaging** | Domain (conversations, WebSocket) | ✅ 70% | ✅ |
| **marketplace-notifications** | Domain (notifications) | ✅ 70% | ✅ |
| **marketplace-availability** | Domain (availability slots) | ✅ 70% | ✅ |
| **marketplace-ledger** | Domain (ledger, balances) | ✅ 70% | ✅ |
| **marketplace-search** | Domain (full-text search) | ✅ 70% | ✅ |
| **marketplace-admin** | Package in app (admin REST) | ✅ (in app) | ✅ |

---

## CI/CD

| Workflow | Status |
|----------|--------|
| Build & Test (JDK 21) — `mvn verify` | ✅ Passing (all 17 modules) |
| Full Integration Test | ✅ Passing |
| Maven Publish | ✅ |
| Gitleaks Secret Scan | ✅ |
| OpenAPI Compat Check | ✅ |

## Testing

- **JaCoCo**: 70% coverage threshold — all 15 business modules pass
- **Unit tests**: 300+ across all modules (0 failures, 0 errors)
- **Integration tests**: 14 ModuleIntegrationTest classes in `marketplace-app` (95 tests, 0 failures, 0 errors)
- **WebMvcTest**: 44 controller tests across modules
- **Infrastructure**: Testcontainers (PostgreSQL 17), Redis 7 (CI service)

## Test Infrastructure Fixes (2026-06-13)

### Integration Test Fixes
All 14 ModuleIntegrationTests pass after fixing:

1. **ModuleTestConfig** (`test.config` package):
   - Created shared test config outside component scan root → avoids bean conflicts
   - `@EnableJpaAuditing` — enables `@CreatedDate` for module tests
   - Inline `WebMvcConfigurer` bean (replaces `ApiVersioningConfig`) — API version strategy for module context
   - `@Primary` on `marketplaceProperties()` — resolves conflict with `@ConfigurationProperties` auto-registered bean
   - `@ConditionalOnMissingBean` on `auditorAware()` — uses real bean when present (ALL_DEPENDENCIES mode)

2. **AdminModuleIntegrationTest** (ALL_DEPENDENCIES mode):
   - Removed `@MockitoBean` for `ListingPriceProvider` + `CatalogSearchPort` (both extend `CatalogSpi` → override real `catalogService` bean)
   - Kept `@MockitoBean` for non-CatalogSpi SPIs: `ProviderLookupPort`, `AvailabilityPort`, `PaymentIntentLookupPort`, `BookingParticipantProvider`, `CurrentUserProvider`, `ProviderNameResolver`

3. **LedgerModuleIntegrationTest**: Added `@MockitoBean BookingParticipantProvider`

4. **MessagingModuleIntegrationTest**:
   - Replaced `@MockitoBean MarketplaceProperties` with `@TestConfiguration` inner class providing properly-mocked bean
   - Avoids NPE on `cors()` during `WebSocketConfig` context initialization

5. **application-test.yml**: Added `spring.main.allow-bean-definition-overriding: true`

## Phase 2 — Envers Auditing Hooks (2026-06-13)

All 18 `@Audited` domain entities now have repositories extending `RevisionRepository<Entity, UUID, Integer>`:

- `BookingRepository` — `RevisionRepository<Booking, UUID, Integer>`
- `AvailabilitySlotRepository` — `RevisionRepository<AvailabilitySlot, UUID, Integer>`
- `ProviderAvailabilityRuleRepository` — `RevisionRepository<ProviderAvailabilityRule, UUID, Integer>`
- `ProviderTimeOffRepository` — `RevisionRepository<ProviderTimeOff, UUID, Integer>`
- `CatalogListingRepository` — `RevisionRepository<CatalogListing, UUID, Integer>`
- `ConversationRepository` — `RevisionRepository<Conversation, UUID, Integer>`
- `MessageRepository` — `RevisionRepository<Message, UUID, Integer>`
- `NotificationRepository` — `RevisionRepository<Notification, UUID, Integer>`
- `PaymentIntentRepository` — `RevisionRepository<PaymentIntent, UUID, Integer>`
- `PaymentWebhookEventRepository` — `RevisionRepository<PaymentWebhookEvent, UUID, Integer>`
- `PricingRuleRepository` — `RevisionRepository<PricingRule, UUID, Integer>`
- `ProviderProfileRepository` — `RevisionRepository<ProviderProfile, UUID, Integer>`
- `ReviewRepository` — `RevisionRepository<Review, UUID, Integer>`
- `ProviderBalanceRepository` — `RevisionRepository<ProviderBalance, UUID, Integer>`
- `LedgerEntryRepository` — `RevisionRepository<LedgerEntry, UUID, Integer>`
- `SearchIndexRepository` — `RevisionRepository<SearchIndexEntry, UUID, Integer>`
- `ListingSearchViewRepository` — `RevisionRepository<ListingSearchView, UUID, Integer>`

Plus new `RevisionService` in `marketplace-admin` package for unified audit querying.

## JaCoCo Coverage Fixes (2026-06-13)

| Module | Before | After | Fix |
|--------|--------|-------|-----|
| **marketplace-booking** | 0.61 | 0.70+ | Added 11 new tests: `getByIdForUser`, `listByConsumer/Provider/Status`, `listAll`/`listAllSummaries`, `listByStatusSummary` (string + invalid), `autoCancel` (pending + already), `autoConfirm` (pending + already) |
| **marketplace-ledger** | 0.61 | 0.70+ | Added `LedgerPaymentEventListenerTest` with 3 tests: ignores non-completed events, credits ledger on completion, logs on failure |

## Phase 1 — Critical Integration Links (2026-06-12)

> 6 cross-module business logic integrations implemented.

### 1.1 Booking ↔ Availability
- **Files:**
  - `marketplace-app/.../db/migration/V26__add_booking_time_range.sql` — New Flyway migration: `starts_at`/`ends_at` columns on `bookings`
  - `marketplace-booking/.../Booking.java` — Added `startsAt`/`endsAt` fields with `markBooked()`/`markAvailable()` methods
  - `marketplace-booking/.../BookingService.java` — `create()` now accepts `startsAt`/`endsAt`, calls `AvailabilityPort.isAvailable()` before persisting; `confirm()` calls `AvailabilityPort.bookSlot()`; `cancel()` calls `AvailabilityPort.releaseSlot()`
  - `marketplace-booking/.../BookingController.java` — `CreateBookingRequest` record includes `startsAt`/`endsAt`
  - `marketplace-booking/.../BookingResponse.java` — Exposes `startsAt`/`endsAt`
  - `marketplace-shared/.../api/BookingSummary.java` — Exposes `startsAt`/`endsAt`
  - `marketplace-shared/.../api/AvailabilityPort.java` — Added `bookSlot()`/`releaseSlot()` interface methods
  - `marketplace-availability/.../AvailabilityPort.java` — Implements `bookSlot()` (finds unlocked slot, marks booked) and `releaseSlot()` (marks slot available)
  - `marketplace-availability/.../AvailabilitySlotRepository.java` — Added `findFirstByProviderIdAndStartsAtAndEndsAtAndBookedFalse()` and `findFirstByProviderIdAndStartsAtAndEndsAtAndBookedTrue()`

### 1.2 Payment COMPLETED → Ledger credit
- **Files:**
  - `marketplace-ledger/.../LedgerPaymentEventListener.java` — Listens for `PaymentStateChangedEvent("COMPLETED")`, resolves provider via `BookingParticipantProvider`, calls `LedgerService.creditFromPayment()`

### 1.3 Payment COMPLETED → Booking auto-confirm
- **Files:**
  - `marketplace-booking/.../BookingPaymentEventListener.java` — Listens for `PaymentStateChangedEvent("COMPLETED")`, resolves booking via `PaymentIntentLookupPort`, calls `BookingService.autoConfirm()`
  - `marketplace-booking/.../BookingService.java` — Added `autoConfirm(UUID id)` method (no auth check, internal system call)

### 1.4 Notification → Email pipeline
- **Files:**
  - `marketplace-notifications/.../NotificationService.java` — Injects `Optional<EmailService>`, attempts email send on `onBookingCreated` and `onPaymentStateChanged` (safe when email is unconfigured)

### 1.5 Provider VERIFIED check in Catalog
- **Files:**
  - `marketplace-catalog/.../CatalogService.java` — `create()` now calls `ProviderLookupPort.findById()` and rejects non-VERIFIED providers with `BadRequestException`

### 1.6 Booking CANCELLED → Payment refund
- **Files:**
  - `marketplace-shared/.../api/BookingCancelledEvent.java` — New event record in shared
  - `marketplace-payments/.../BookingCancelledEventListener.java` — Listens for `BookingCancelledEvent`, calls `PaymentsService.autoRefundByBooking()`
  - `marketplace-payments/.../PaymentsService.java` — Added `autoRefundByBooking(UUID bookingId)` (idempotent, finds PaymentIntent by bookingId, marks Payment as REFUNDED, publishes `PaymentStateChangedEvent("REFUNDED")`)

### New/Modified Event Records
- `BookingCreatedEvent` — unchanged
- `BookingCancelledEvent` — new (shared)
- `PaymentStateChangedEvent` — unchanged (used by 1.2, 1.3)
- `BookingSummary` — extended with `startsAt`/`endsAt`

## Completions

- 52 Spring/Maven features used
- All 6 Phase 1 integration links implemented and tested
- All verified violations from audit have been fixed
- All 13 modules have `@NamedInterface` on `package-info.java`
- 8 read-heavy services have `@Cacheable` on entity lookups
- Pricing + Disputes controllers use DTOs with MapStruct (entities no longer exposed)
- `BookingService.cancel()` has `@Retry` + `@ConcurrencyLimit` (consistent with `confirm()`/`complete()`)
- 5 services annotated with `@Observed` (Observability metrics)
- All 14 controllers have `@WebMvcTest` (44 tests, with OAuth2 auto-config exclusion)
- Spring Data Projections used for read-only endpoints (ListingSimple, ReviewSummary, BookingSummary)
- build-info (`META-INF/build-info.properties`) + git-commit-id (`git.properties`) — `/actuator/info` populated
- Tomcat access log enabled with `%{ms}T` pattern (milliseconds) via `server.tomcat.accesslog.*`
