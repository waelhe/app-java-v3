# 01 — Modules & Boundaries (isolated system map)

> **Topology, import rules, ports/adapters, events.** Citations are `file:line`.

## 1. Module list (19)

- MUST | `marketplace-shared` — contracts only (DTOs, exceptions, interfaces, constants). | [code] | marketplace-shared/pom.xml:15
- MUST | `marketplace-platform-infra` — cross-cutting infra (JPA base, Security, Cache, Observability, Email). | [doc] | docs/CODING_STANDARDS.md:41
- MUST | Domain (16): identity (users/auth/OAuth2), catalog (listings), booking (bookings), payments (intents/refunds/webhooks), pricing (rules/calendar), reviews (reviews/ratings), messaging (conversations/STOMP), search (full-text), provider (profiles), availability (slots/rules), notifications (dispatch), ledger (double-entry), disputes (resolution), media (S3 assets), geo (hierarchy), realestate (property fields). | [code] | pom.xml:24-39
- MUST | `marketplace-app` — runnable composition root (GraphQL + websocket + admin modules inside). | [code] | marketplace-app/pom.xml:15

## 2. Boundary declarations

- MUST | Two patterns: (A) `@ApplicationModule` in `package-info.java` (booking, catalog, identity, payments, media, reviews, messaging, search, geo, pricing, realestate); (B) `@ApplicationModule` on `*Module.java` marker (availability, disputes, ledger, provider, notifications). | [code] | (package-info.java files)
- MUST | Other modules may import ONLY `@NamedInterface` packages — enforced by `modules.verify()` in CI. | [code] | ModulithVerificationTest.java:19
- MUST | Named sub-interfaces: `booking-spi`, `catalog-api`/`catalog-spi`, `identity-spi`, `payments-spi`, `messaging-api`, `search-api`, `admin-api`. | [code] | (respective files)
- AVOID | Importing `*.spi` adapters or internal service/entity/repo packages cross-module — consumers inject the shared-api port, never the adapter. | [code] | (adapters per implements-grep)
- ATTEND | Composition-root privilege (declared): `app → catalog-api/catalog-spi/messaging-api`; `admin → booking-spi/catalog-spi/identity-spi/payments-spi`; `realestate → catalog-spi` (sole domain→domain edge). | [code] | app/package-info.java:2-8 · admin/package-info.java:2-8 · realestate/package-info.java:3-8

## 3. Port → implementation table (shared-api)

- MUST | `AvailabilityPort→AvailabilityService`; `AvailabilityLookupPort→AvailabilityLookupAdapter`; `EffectivePricePort→PricingService`; `CatalogSearchPort+ListingPriceProvider+CatalogSpi→CatalogService`; `BookingSpi→BookingService` (+Stats/Export/Purge adapters); `PaymentRefundPort/PaymentIntentLookupPort/PaymentsSpi→adapters/PaymentsService`; `UserLookupPort/CurrentUserProvider/ProviderNameResolver/IdentitySpi→identity impls`; `ProviderLookupPort→ProviderLookupAdapter`; `ReviewStatsPort/ReviewExportPort→adapters`; `LedgerStatsPort→LedgerStatsAdapter`; `NotificationExportPort/MessagingExportPort/MediaExportPort→adapters`; `GeoLookupPort→GeoService`; `PropertyDetailsPort/RealestatePropertyFilterPort→RealestateService/adapters`; `AuthoredContentPurgePort (×7)→per-module purge adapters`. | [code] | (implements-grep per module)
- ATTEND | Pricing has NO spi sub-interface (root `"pricing"` only); reviews/messaging/geo/media/search expose no spi (root or `-api` only). | [code] | (package-info files)

## 4. Events — publisher → consumer

- MUST | Transport: `@ApplicationModuleListener` = async + REQUIRES_NEW + AFTER_COMMIT via the publication registry (`completion-mode: archive`); `CacheInvalidationRequested` uses sync AFTER_COMMIT with no retry (failures only metered). | [code] | application.yml:56 · CacheInvalidationRelay.java:54-59
- MUST | Publishers: booking (Created/Confirmed/Cancelled), catalog (`ListingCreatedEvent`), payments (`PaymentStateChangedEvent` ×7 sites), reviews (Created/Updated), media (`MediaUploadedEvent`); cache-only publishers: identity, availability, geo, realestate, provider, messaging, pricing, + expiry batch. | [code] | (service files, see research)
- MUST | Consumers: availability+booking on `DayHasPassed` (Moments); booking on payment-completed → autoConfirm; ledger on payment-completed → credit/commission; provider on review events → rating average; notifications on booking/payment events; media thumbnail listener on upload; `BookingCancelledEvent` → auto-refund. | [code] | (listener files, see research)
- ATTEND | `BookingConfirmedEvent` is published but has NO listener (durable in registry, unconsumed). | [code] | (grep: publishers only)

## 5. Verification

- MUST | `ModulithVerificationTest` runs `modules.verify()` + writes docs; skipped only on JDK 26+ (ASM limitation). | [code] | ModulithVerificationTest.java:12-21
- MUST | `ArchitectureRulesTest` enforces: cycle-free slices, no Controller→Repository, no Controller→shared.jpa/observability, no Service→Controller, shared purity, single `@SpringBootApplication`, no `@EnableJpaRepositories`, no stray `@EnableAsync`. | [code] | ArchitectureRulesTest.java:25-131
- ATTEND | ArchUnit lists omit newer modules (availability, notifications, provider, ledger, disputes, media, geo, realestate) — Modulith verify() still covers all. | [code] | ArchitectureRulesTest.java:50-95