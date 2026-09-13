# 03 — Data Architecture (isolated system map)

> **Flyway, BaseEntity, entities, repositories, transactions, Redis.** Citations are `file:line`.

## 1. Flyway

- MUST | Migrations at `marketplace-app/src/main/resources/db/migration/`; V1..V50 with a **V35 gap** (no file, unexplained); 2 repeatables (`R__seed_oauth2_client`, `R__seed_geo_qudsaya`). | [code] | (directory listing)
- MUST | OAuth2 client is NOT seeded by SQL — sole path is `OAuth2ClientSecretInitializer` via `RegisteredClientRepository.save`. | [code] | R__seed_oauth2_client.sql:4-10
- MUST | `validate-on-migrate: true`, `out-of-order: false`, `clean-disabled: true` (dev relaxes clean); `ddl-auto: none`; tests use `create-drop` with Flyway OFF. | [code] | application.yml:31-52 · application-dev.yml:18-19 · application-test.yml:21-25
- ATTEND | No baseline config; the V35 gap passes validation (checksums, not density) but out-of-order merges fail fast. | [code] | application.yml:50-51

## 2. BaseEntity (every entity)

- MUST | `@MappedSuperclass` + `AuditingEntityListener` + Hibernate 7 `@SoftDelete(is_deleted)`; `UUID` id contract (`getId()` abstract; subclasses declare `@Id UUID`, no generated value). | [code] | BaseEntity.java:38-66
- MUST | `@Version Long version` (optimistic lock) + `@CreatedBy/@CreatedDate/@LastModifiedBy/@LastModifiedDate` on every entity. | [code] | BaseEntity.java:46-64
- ATTEND | No `is_deleted` Java field — Hibernate rewrites DELETE→UPDATE; `Serializable` required for JDK-serialized Redis caches. | [code] | BaseEntity.java:20-36

## 3. Entities (25, scalar UUID FKs, all @Audited)

- MUST | Zero JPA associations — relationships are scalar UUID columns; FK integrity in SQL; cross-module via ports. | [code] | (grep-verified)
- MUST | Core tables: `users` (subject unique, pseudonymized_at V46), `provider_listings` (provider_id), `bookings` (consumer/provider/listing), `payments` + `payment_intents` (idempotency_key unique) + `payment_webhook_events` (UNIQUE provider+event_id), `pricing_rules`/`seasonal_rates`/`listing_weekend_rules`, `reviews` (booking/reviewer/provider in users.id space), `conversations`/`messages`, `provider_profiles` (user_id), `availability_slots/rules/time_off`, `notifications`/`notification_preferences`, `ledger_entries` (source_id unique) + `provider_balances` (natural PK = providerId), `disputes`, `media_assets`, `geo_locations` (self-ref parent), `property_details` (jsonb amenities). | [code] | (entity files per module)
- MUST | Envers: `store_data_at_delete: true`, `_aud` suffix; `revinfo` + 18 mirrors in V24; later tables mirrored with their feature migrations (V32/V40/V41/V47/V48); `revinfo_seq INCREMENT BY 50` (V30 repair). | [code] | application.yml:43-45 · V24 · V30

## 4. Repositories & adapters

- MUST | 24 Spring Data interfaces (`JpaRepository<Entity,UUID>`); all but `MediaAssetRepository` also extend Envers `RevisionRepository`; `+JpaSpecificationExecutor` on catalog/booking/payments/provider/disputes. | [code] | (repository files)
- MUST | 8 native full-text/trigram queries in `ProviderListingRepository`; 2 native queries in `GeoLocationRepository`; advisory-lock query in `MediaAssetRepository`. | [code] | ProviderListingRepository.java:82-269 · GeoLocationRepository.java:47,70 · MediaAssetRepository.java:36
- MUST | JDBC purge adapters (7, `@Transactional`, bypass Envers by design) orchestrated per-module-tx; export adapters (`readOnly`) for GDPR; stats/lookup adapters (`readOnly`). | [code] | (spi adapters per module)

## 5. Transactions & locking

- MUST | `@Transactional` on service classes (required read-write) + `readOnly=true` on every read path (all modules). | [code] | (services, see research)
- MUST | `REQUIRES_NEW` only bounded: expiry batch per-batch, media, programmatic (notification prefs, price calendar). | [code] | ListingExpiryBatchExecutor.java:57 · MediaService.java:256
- MUST | `NOT_SUPPORTED` on GDPR purge entry points (one tx per module). | [code] | UserService.java:446,467
- MUST | Optimistic: `@Version` everywhere → `ObjectOptimisticLockingFailureException→409`; pessimistic: single `findByIdForUpdate` (provider rating write); advisory: media position allocation. | [code] | GlobalExceptionHandler.java:82-83 · ProviderRepository.java:24-26 · MediaAssetRepository.java:36

## 6. Redis (3 channels; rate limits NOT in Redis)

- MUST | (1) Spring Session indexed (`marketplace:session`, 30m); (2) 15 named entity/query caches (1h TTL, `provider-stats` 5m); (3) ops/test TTL probes. | [code] | application.yml:76-82,126-159 · ProviderStatsCacheConfig.java:37-39
- MUST | Cache correctness via AFTER_COMMIT relay (no `@CacheEvict` on tx methods); values JDK-serialized (entities must stay `Serializable`). | [code] | CacheInvalidationRelay.java:17-83
- ATTEND | Prod Redis via env (host no-default); dev/test use in-memory `simple`; rate limits are in-memory Resilience4j (N× per replica). | [code] | application-prod.yml:61-64 · application.yml:284-328