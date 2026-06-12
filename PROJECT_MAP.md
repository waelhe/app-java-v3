# PROJECT_MAP — Marketplace Backend (app-java-v3)

## Current State (2026-06-12)

**Branch:** `main`
**Java:** 21 (CI), 26 (local)
**Spring Boot:** 4.0.6 | **Spring Modulith:** 2.0.6 | **Maven:** 3.9.14

---

## Modules (17)

| Module | Type | Status |
|--------|------|--------|
| **marketplace-app** | Composition root | ✅ |
| **marketplace-platform-infra** | Shared infra (JPA, Security, Cache, Observability) | ✅ |
| **marketplace-shared** | Shared API interfaces + exceptions | ✅ |
| **marketplace-identity** | Domain (users, auth) | ✅ |
| **marketplace-provider** | Domain (provider profiles) | ✅ |
| **marketplace-catalog** | Domain (listings) | ✅ |
| **marketplace-booking** | Domain (bookings, expiration) | ✅ |
| **marketplace-payments** | Domain (payments, intents, webhooks) | ✅ |
| **marketplace-pricing** | Domain (pricing rules) | ✅ |
| **marketplace-reviews** | Domain (reviews) | ✅ |
| **marketplace-disputes** | Domain (disputes) | ✅ |
| **marketplace-messaging** | Domain (conversations, WebSocket) | ✅ |
| **marketplace-notifications** | Domain (notifications) | ✅ |
| **marketplace-availability** | Domain (availability slots) | ✅ |
| **marketplace-ledger** | Domain (ledger, balances) | ✅ |
| **marketplace-search** | Domain (full-text search) | ✅ |
| **marketplace-admin** | Package in app (admin REST) | ✅ |

---

## CI/CD

| Workflow | Status |
|----------|--------|
| Build & Test (JDK 21) — `mvn verify` | ✅ Passing |
| Full Integration Test | ✅ Passing |
| Maven Publish | ✅ |
| Gitleaks Secret Scan | ✅ |
| OpenAPI Compat Check | ✅ |

## Testing

- **JaCoCo**: 70% coverage threshold across all 17 modules
- **Integration tests**: 16 ModuleIntegrationTest classes in `marketplace-app`
- **Unit tests**: Per-module service/controller/mapper tests
- **Infrastructure**: Testcontainers (PostgreSQL 17), Redis 7 (CI service)

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
