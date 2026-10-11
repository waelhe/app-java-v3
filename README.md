# Local Community Platform Foundation (app-java-v3)

This repository contains a multi-domain Spring backend foundation (**REST + GraphQL**) built as a **Spring Modulith**. The target is a coherent local-community platform at country/city/neighborhood scale—not a thin Android wrapper around existing endpoints. The current backend is a foundation to reuse and extend where measured user journeys require it; this README does not claim that all target journeys are implemented.

| | |
|---|---|
| Java | 25 (`--release 25`, enforced) |
| Spring Boot | 4.1.1 (parent) · Spring Modulith 2.1.1 (BOM) · Spring Authorization Server |
| Build | Maven wrapper `./mvnw` (3.9.x required by Enforcer) |
| Data | PostgreSQL 18 (+PostGIS) · Redis 8 · **schema owned by Flyway** (`ddl-auto: none`) |
| Deploy | Docker multi-stage image → Railway (service-level IaC in `.railway/railway.ts`) |

Live production: `https://app-java-v3-production.up.railway.app`

## Product direction and journey contracts

- **Product, UX, and target architecture:** [docs/product-platform-blueprint.md](docs/product-platform-blueprint.md)
- **Journey contract JT-01..JT-20:** [docs/community-platform-user-journeys.md](docs/community-platform-user-journeys.md)
- **Design system and screen catalog:** [docs/community-platform-ux-design.md](docs/community-platform-ux-design.md)
- **Accepted and open product decisions:** [docs/platform-product-decisions.md](docs/platform-product-decisions.md)

## Where the truth lives (read these first)

This repository keeps a strict truth hierarchy — every claim in the code,
docs and PRs is expected to carry its evidence (`file:line`, live measurement,
or an archived official source):

1. **`AGENTS.md`** — mandatory work rules and reading order.
2. **`SYSTEM.md`** — system-mechanics map, with dated observations distinguished from live facts.
3. **`PROJECT_MAP.md`** — delivery history; remeasure PR, branch, migration and deployment state before relying on a snapshot.
4. **`docs/product-platform-blueprint.md`** + **`docs/community-platform-user-journeys.md`** — target product/experience and JT-01..JT-20 acceptance contract.
5. **`docs/community-platform-ux-design.md`** — visual design system, screen catalog, RTL and accessibility.
6. **`docs/platform-product-decisions.md`** — accepted owner decisions and unresolved gates.
7. Product/UX documents describe target behavior; they do not prove implementation. Re-measure code, CI, PR and deployment state before claiming a feature is complete.

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
