# Marketplace Backend (app-java-v3)

Local-services marketplace backend: **REST + GraphQL** in a single Spring context,
built as a **Spring Modulith** of 27 Maven modules (23 domain modules + the app
assembly + shared contracts + platform infrastructure + the edge BFF).

| | |
|---|---|
| Java | 25 (`--release 25`, enforced) |
| Spring Boot | 4.1.1 (parent) · Spring Modulith 2.1.1 (BOM) · Spring Authorization Server |
| Build | Maven wrapper `./mvnw` (3.9.x required by Enforcer) |
| Data | PostgreSQL 18 (+PostGIS) · Redis 8 · **schema owned by Flyway** (`ddl-auto: none`) |
| Deploy | Docker multi-stage image → Railway (service-level IaC in `.railway/railway.ts`) |

Live production: `https://app-java-v3-production.up.railway.app`

## Where the truth lives (read these first)

This repository keeps a strict truth hierarchy — every claim in the code,
docs and PRs is expected to carry its evidence (`file:line`, live measurement,
or an archived official source):

1. **`SYSTEM.md`** — how the system actually works (the living reference map).
2. **`PROJECT_MAP.md`** — the state log: what merged, what is open, what is deferred.
3. **`AGENTS.md`** — the binding work rules (mandatory load order before any action).
4. `docs/` — domain plans, governance decisions, SLOs, runbooks and operating guides.

## Build & verify

```bash
./mvnw clean verify          # full quality gate
./mvnw clean verify -pl <module> -am   # focused gate for one module (run before every push)
```

The `verify` gate includes unit tests (Surefire), integration tests (Failsafe,
skipped without Docker), JaCoCo coverage ≥ 70% per module, Modulith boundary
verification and Maven Enforcer rules. Any warning fails the build.

## Run locally

```bash
docker compose up -d          # PostgreSQL 18 (postgis) + Redis 8
./mvnw spring-boot:run -pl marketplace-app
```

Integration tests use Testcontainers and skip themselves automatically when
Docker is unavailable (`@Testcontainers(disabledWithoutDocker = true)`).

## Operating the platform

- **SLOs & alerting-as-code:** `docs/observability/slo.md` +
  `monitoring/prometheus-rules/marketplace-alerts.yml` (guarded by tests).
- **Incident runbooks:** `docs/observability/runbooks.md` (1:1 with alert rules).
- **JWT signing keys:** `keys/README.md` (rotation runbook — ≤ 90-day cycle).
- **Releases & rollback:** `docs/release/rollout-strategy.md`.
- **Production probes:** liveness/readiness on the public gateway; the
  GitHub-hosted watchdog workflow pages via issues when probes drift.

## Working on this repository

Follow `AGENTS.md` §0 before any change: load `SYSTEM.md` → `PROJECT_MAP.md` →
the governing plan for the task at hand, then announce the governing file and
the touched architectural layer. Schematic changes always arrive as a new
`V{number}` Flyway migration — existing migrations are never edited.
