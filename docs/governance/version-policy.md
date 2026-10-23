# Version & Upgrade Policy

This document defines official version governance for the Marketplace backend.

## 1) Supported baseline

- Java: `25` (LTS) — upgraded from Java 21 in PR #144 (Spring Boot 4.1.0 GA). Java 27 (2026-09-15) is non-LTS and does not move the baseline.
- Spring Boot: `4.1.2` GA stable line — 4.0.6 → 4.1.0 in PR #144; → 4.1.1 platform wave; → 4.1.2 in the 2026-10-23 platform wave (carries Spring Security 7.1.2; the CVE-2026-41707 / CVE-2026-47841 fixes first shipped in Spring Security 7.1.1, August 2026 — plus Spring Framework 7.0.10, Micrometer 1.17.2, Hibernate 7.4.13, Tomcat 11.0.27, Netty 4.2.19.Final). With 4.1.2, five manual version overrides returned to automatic Boot parent management per §6 (tomcat, netty, postgresql, commons-lang3, slf4j-api — removal conditions measured from spring-boot-dependencies-4.1.2.pom).
- Spring Modulith: `2.1.2` GA — 2.0.6 → 2.1.0 in PR #144; → 2.1.1 platform wave; → 2.1.2 in the 2026-10-23 platform wave (archunit pairing unchanged: spring-modulith-core 2.1.2 resolves archunit 1.4.2, measured).
- Spring Cloud: `2025.1.4` (exception #14 — Oakwood train, edge BFF).
- Spring AI: `2.0.2` (exception #13 — "Spring AI 2.0.x supports Spring Boot 4.0.x and 4.1.x", docs.spring.io/spring-ai).
- Maven: `3.9.16+` (wrapper: 3.3.4). Maven 3.10.0 GA measured 2026-10-23 — the wrapper stays on 3.9.16 (documented baseline satisfied; Boot 4.1.x requirements satisfied); 3.10.0 adoption is deferred to the next quarterly minor review with its own release-notes pass.
- Flyway: Boot-managed (`12.4.0` via spring-boot-dependencies 4.1.2 — unchanged from 4.1.1). The 13.x major line exists on Maven Central; a major-line move is a planned initiative per §3.
- Stripe SDK: `33.4.2` — 34.0.0 is a MAJOR line on Central; a planned initiative per §3 with a dedicated migration window, not an automatic bump.
- springdoc-openapi: `3.1.0` — 3.1.1 was measured (2026-10-07, chore/safe-version-bumps) and reverted per its documented in-pom decision: generated-spec rendering deltas (Pageable query parameters newly documented on 7 endpoints plus response-documentation deltas) that the fail-closed OpenAPI compatibility gate cannot express as allowlist entries. The move needs a dedicated PR with the gate report-format decision.
- JaCoCo coverage threshold: `0.70` (70%) — enforced on all domain modules.

These baselines must remain aligned with project build enforcement rules
(`pom.xml` properties: `java.version`, `jacoco.coverage.threshold`).

## 2) Source of truth

Before any upgrade decision, confirm compatibility against official references:

- [Spring Boot reference docs](https://docs.spring.io/spring-boot/)
- [Spring Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Framework reference docs](https://docs.spring.io/spring-framework/)
- [Apache Maven official guides](https://maven.apache.org/guides/index.html)
- [Spring Modulith reference](https://docs.spring.io/spring-modulith/)
- [Spring Security reference](https://docs.spring.io/spring-security/reference/)
- [Spring Security — What's New](https://docs.spring.io/spring-security/reference/whats-new.html)

## 3) Upgrade cadence

- Patch upgrades: monthly review.
- Minor upgrades: quarterly review.
- Major upgrades: planned initiative with dedicated migration window.

## 4) Decision process

For each upgrade proposal:

1. Create a short ADR/change note with scope and risk.
2. List impacted modules and runtime/test implications.
3. Validate compatibility against official docs.
4. Define rollback plan before merge.

## 5) Mandatory validation checklist

- Build passes with enforced Java/Maven versions.
- Unit and integration tests pass for impacted modules.
- OpenAPI generation remains valid.
- Security-sensitive changes include negative auth tests (401/403) where relevant.
- Performance regressions are reviewed for critical paths.

## 6) Dependency rules

- Do not pin versions already managed by Spring Boot BOM unless a documented exception exists.
- Every exception must include:
  - reason,
  - compatibility evidence,
  - owner,
  - follow-up review date.

## 7) Emergency patch protocol

For urgent CVE/runtime fixes:

- Fast-track patch branch.
- Minimum required tests for impacted scope.
- Post-release follow-up PR to restore normal cadence checks and documentation.

## 8) Ownership

- Platform maintainers own baseline decisions.
- Module owners validate domain-specific impact.
- Release owner confirms rollout/rollback readiness prior to production deployment.

## 9) API compatibility policy

- Every pull request must pass the OpenAPI backward compatibility gate in CI.
- Baseline source: latest release tag unless `OPENAPI_BASELINE_TAG` is explicitly provided.
- CI must fail for backward-incompatible changes (endpoint deletion, schema narrowing, status code changes, media type changes).
- Exceptions are temporary and must be documented in `.ci/openapi-compat-allowlist.yml` with:
  - linked ticket/approval,
  - explicit rationale,
  - expiry/removal date.
- Non-expired exceptions must be reviewed during release readiness checks.

