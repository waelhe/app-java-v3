# PROJECT_MAP — Marketplace Backend (app-java-v3)

## Current State (2026-06-11)

**Branch:** `fix/jackson-3x-api-compat`
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

## Completions

- 42 Spring/Maven features used
- 25 documented features identified as future backlog (not required now)
- All verified violations from audit have been fixed in this session
