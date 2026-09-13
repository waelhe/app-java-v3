# 05 — Configuration & Runtime (isolated system map)

> **application.yml, profiles, env (fail-fast), events, scheduling, observability, shutdown.** Citations are `file:line`.

## 1. application.yml (440 lines, base profile)

- MUST | App: `marketplace`, virtual threads ON, docker-compose OFF; datasource localhost PG + Hikari 20/5; `open-in-view: false`, `ddl-auto: none`; Flyway on + validate + no-out-of-order + no-clean. | [code] | application.yml:2-52
- MUST | Modulith events `completion-mode: archive`; shutdown graceful 30s; `problemdetails.enabled: true`; GraphQL + GraphiQL on; session Redis indexed (`marketplace:session`, 30m); issuer env-default localhost:8080. | [code] | application.yml:54-87
- MUST | Redis localhost:6379 (no password key — external `SPRING_DATA_REDIS_PASSWORD`); page max 100; cache `redis` + 15 names + 1h TTL. | [code] | application.yml:107-159
- MUST | Server 8080 + graceful + tomcat access log; actuator health/info/metrics/prometheus/modulith + conditions + k8s probes + OTLP; logstash structured + rotation; Resilience4j (CB/retry/rate-limit instances per endpoint). | [code] | application.yml:172-328
- MUST | Feature props: commission 0.10; CORS default marketplace.com; catalog expiry 90/1; currency SAR (+ per-code env rates); Stripe/S3/mail blank (= inert); JWT audience + keystore channels; session max 2; HMAC key + previous-keys. | [code] | application.yml:330-440

## 2. Profiles (no in-yml activation — external only)

- MUST | Files: base `application.yml`, `application-dev.yml` (59), `application-prod.yml` (186), `application-test.yml` (63). | [code] | (resources)
- MUST | dev: compose file, hardcoded PG, show-sql, clean allowed, simple cache, mail stub, events `delete`, devtools on, SQL DEBUG, CORS localhost:3000, dev JKS. | [code] | application-dev.yml
- MUST | prod: NO defaults on DB/Redis-host/mail/issuer/CORS/JWT-password-alias/clients/OIDC-clients/OTLP URLs; devtools+GraphiQL off; cache redis; events archive + staleness + republish-on-restart; mgmt port 8081 without `modulith`; health never; log scrub. | [code] | application-prod.yml
- MUST | test: localhost PG (pool 10/1), simple cache, create-drop, Flyway OFF, stub mail, localhost issuer, OTLP off. | [code] | application-test.yml

## 3. Env vars — defaults vs fail-fast

- ATTEND | Defaulted: DB_URL/USERNAME (localhost), REDIS (localhost:6379), CACHE_TYPE redis, PORT 8080, issuer localhost:8080, CORS marketplace.com, JWT audience, media/S3 blank, Stripe blank, commission 0.10, expiry 90/1, SAR, HMAC blank. | [code] | application.yml
- MUST | Fail-fast (prod, no default): DB_URL/USERNAME/PASSWORD, REDIS_HOST, MAIL_HOST/USER/PASS, AUTH_SERVER_ISSUER, CORS_ORIGINS, JWT key password/alias/key-password, OAUTH confidential+public ids/secrets/redirects, OTLP URLs. | [code] | application-prod.yml
- MUST | Bean-level fail-fast: prod + both keystore blank → IllegalStateException; half-config → fail all profiles; confidential/public one-sided or blank-redirect → fail. | [code] | SecurityConfig.java:425-447 · OAuth2ClientSecretInitializer.java:117-134 · OAuth2PublicClientInitializer.java:118-130
- MUST | Railway preserves 33 vars (mirror list incl. `SPRING_PROFILES_ACTIVE`); healthcheck `/actuator/health/liveness` timeout 300; `JWT_KEYSTORE_PATH` deliberately deleted (b64 channel only). | [code] | .railway/railway.ts:51-90

## 4. @ConfigurationProperties (records + constructor binding)

- MUST | `marketplace.*` → `MarketplaceProperties` (Cors/Security/Jwt/Session/OAuth2/Pseudonymization, all `@DefaultValue`-primed). | [code] | MarketplaceProperties.java:23-164
- MUST | `marketplace.catalog` → `CatalogProperties` (expiryDays NULL = policy unset → 409; cooldown 1); `marketplace.pricing.currency.exchange` → `CurrencyExchangeProperties` (SAR + normalized map); `marketplace.payments` → `PaymentsProperties` (Stripe blank); `marketplace.media` → `MediaProperties` (S3 blank + limits). | [code] | (properties classes)

## 5. Events / scheduling / observability / shutdown

- MUST | Events: `@ApplicationModuleListener` (async+REQUIRES_NEW+AFTER_COMMIT, registry `archive`); cache relay sync AFTER_COMMIT no-retry; publishers/consumers table in 01-modules.md §4. | [code] | application.yml:56 · CacheInvalidationRelay.java:54-83
- MUST | `@EnableScheduling` once (CacheConfig); NO `@EnableAsync`/custom executor/`spring.task.*` (Modulith async path; ArchUnit forbids stray); virtual threads ON (`@ConcurrencyLimit` guard). | [code] | CacheConfig.java:16-17 · application.yml:5-7 · ArchitectureRulesTest.java:113-131
- MUST | Schedulers: publication cleanup 03:00 UTC (7d), authz cleanup 04:00 UTC (7d), resubmission every 15m (24h backoff), staleness gauge 60s, listing expiry every 30m (500/batch, own tx). | [code] | (scheduler classes)
- MUST | Observability: Prometheus + OTel exporters, business counters (`bookings/payments/listings/reviews.created…`), `eventbus.stale` gauge, alert rules (TargetDown/5xx/PaymentsFailing/Stale/CacheFailures/CBOpen/PoolSaturated). | [code] | BusinessMetrics.java:23-38 · marketplace-alerts.yml:15-101
- MUST | Graceful shutdown both profiles (30s phase); prod behind TLS proxy (`FRAMEWORK` headers); exec-form PID-1 java with AOT cache + RAM cap + OOM-exit; resubmission delayed 5m to avoid teardown race. | [code] | application.yml:59,173 · application-prod.yml:86-99 · Dockerfile:196,217