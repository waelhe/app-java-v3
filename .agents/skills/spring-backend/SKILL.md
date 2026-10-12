---
name: spring-backend
description: Conventions and commands for the app-java-v3 Spring Boot backend — build/test commands that survive PowerShell, module map, Modulith boundaries, RFC 7807 error contract, Flyway discipline, money rules, and Testcontainers test patterns. Load before touching Java code, pom.xml, migrations, or CI in this repo.
license: Apache-2.0
metadata:
  opencode/autoinvoke: "true"
---

# Spring Boot backend — the working contract

This repo: Java 25 · Spring Boot 4.1.1 · Spring Security 7 (OAuth2 Authorization
Server module) · Spring Modulith · PostgreSQL + PostGIS · Redis · Flyway ·
Maven multi-module (22 modules). `AGENTS.md` is the constitution and stays the
authority on governance; this file is the mechanical layer an agent needs while
writing code. Where they disagree, `AGENTS.md` wins.

## 1. Commands (PowerShell — quoting is the recurring failure)

Quote every `-D` argument or PowerShell splits it and Maven reports a bogus goal.

```powershell
# One test class (fastest signal)
.\mvnw.cmd -pl marketplace-app -am test "-Dtest=SomeTest" "-DfailIfNoTests=false" "-Dsurefire.failIfNoSpecifiedTests=false" "-Djacoco.skip=true"

# Whole module, full verify (the gate before pushing)
.\mvnw.cmd -pl marketplace-app -am verify "-Djacoco.skip=true"

# Integration tests only (failsafe)
.\mvnw.cmd -pl marketplace-app -am verify "-Dit.test=SomeIT" "-DfailIfNoTests=false" "-Djacoco.skip=true"

# Full reactor (slow, CI-class)
.\mvnw.cmd clean verify
```

The `-am` flag is mandatory: without it Maven resolves sibling modules from
stale `~/.m2` snapshots and you get phantom compile errors.

Read the failure from the report file, not the console tail:

```
marketplace-<module>/target/surefire-reports/<fqcn>.txt
```

## 2. Module map (short form)

`marketplace-app` (wiring, admin, releases) · `marketplace-shared` (contracts,
`ApiErrorTaxonomy`, `SystemSettingKeys`) · `marketplace-platform-infra`
(SecurityConfig, EmailService, BaseEntity) · `marketplace-edge` (BFF/TokenRelay)
· `marketplace-console` (feature flags, remote config, geo settings, audit) ·
domain modules: identity, provider, catalog, marketplace-orders, marketplace-discovery,
booking, availability, payments, settlement, ledger, messaging, notifications,
search, media, reviews, disputes, realestate, geo, community, institutions,
knowledge, ai.

Rules that are enforced (not vibes):
- Domain → `marketplace-app` dependency: **forbidden** (ArchUnit).
- Controllers must not inject repositories/entities (ArchUnit).
- Only two cross-domain imports exist, both the intentional `CatalogSpi`.
- New business capability = its own Maven module with `@ApplicationModule` and
  `package-info.java` declaring `allowedDependencies`.

## 3. Error contract (never invent a second one)

All API errors are RFC 7807 `ProblemDetail` produced by
`marketplace-shared/.../api/GlobalExceptionHandler.java` with an `errorCode`
from `ApiErrorTaxonomy` (VAL-001, RL-001, INT-001, …). Rules:
- Throw a domain exception; let the handler map it. Never build a
  `ResponseEntity.status(...).body(Map.of(...))` by hand — a governance test
  counts bypasses and the count is zero.
- Domain-only exception messages may reach the client as `detail`; never put
  SQL, stack traces, class names or paths in a message.
- Security failures go through the handler too (`SecurityProblemDetailHandlers`).

## 4. Migrations (Flyway)

- `validate.enabled=true`, `out-of-order=false`, `clean-disabled=true` — a checksum
  guard test and a `migration-checksums.properties` file pin the history.
- Latest version wins the next number; check `ls db/migration | tail` first.
- Prefer idempotent DDL (`CREATE TABLE IF NOT EXISTS`, `NOT VALID` then
  `VALIDATE CONSTRAINT`) — integration tests re-run scripts on fresh containers.
- Money-schema changes use expand/contract: add nullable → deploy → backfill →
  enforce. Never `DELETE FROM` a money table inside a migration.
- After adding a migration, regenerate the checksums file per the procedure
  documented at the top of that file, then run the module suite.

## 5. Money

- Amounts are **integer minor units** (`long amountCents`) everywhere; a bare
  amount on a response without its `currency` is a defect (`monetary-shape` rule).
- `Float` for money is banned in any contract, including GraphQL.
- Ledger rows are append-only: a transition inserts a row, never updates one.

## 6. Tests

- Unit: `*Test` (surefire) · Integration: `*IntegrationTest` / `*IT` (failsafe).
- Every integration test carries `@ActiveProfiles("test")` and **owns its
  containers** — `@Testcontainers(disabledWithoutDocker = true)` with
  `@Container @ServiceConnection` Postgres (postgis 18-3.6-alpine) + Redis
  (redis:8-alpine). Sharing the workflow's PostgreSQL service is the measured
  defect (order-dependent failures); a guard test enforces per-class ownership.
- Spring-managed container beans are the Boot 4.1-preferred lifecycle.
- No `Thread.sleep` in tests (Awaitility is the sanctioned wait); no disabled
  tests; no assertion-free tests.
- Money, ownership and authorization behaviour each get a test.

## 7. Dependencies and build

- Never pin a version the Spring Boot BOM manages; use `dependencyManagement`.
- Only three community exceptions are allowed: springdoc 3.0.3, MapStruct 1.6.3,
  Instancio 6.0.0-RC3. Anything else needs a documented exception first.
- Everything the platform already provides (Jackson 3 mapping, Micrometer
  observations, spring-javaformat, Testcontainers) is preferred over a new library.

## 8. Governance

- `PlatformGovernanceFilesTest` and `ArchitectureRulesTest` pin platform rules;
  if your change makes one fail, fix the code, not the guard — unless the owner
  explicitly approves the rule change.
- Never edit `.github/workflows/` without declaring the protocol first.
- Merge is the owner's explicit word only.
