# Version & Upgrade Policy

This document defines official version governance for the Marketplace backend.

## 1) Supported baseline

- Java: `25` (LTS) — upgraded from Java 21 in PR #144 (Spring Boot 4.1.0 GA). Java 27 (2026-09-15) is non-LTS and does not move the baseline.
- Spring Boot: `4.1.2` GA stable line — 4.0.6 → 4.1.0 in PR #144; → 4.1.1 platform wave; → 4.1.2 in the 2026-10-23 platform wave (carries Spring Security 7.1.2; the CVE-2026-41707 / CVE-2026-47841 fixes first shipped in Spring Security 7.1.1, August 2026 — plus Spring Framework 7.0.10, Micrometer 1.17.2, Hibernate 7.4.13, Tomcat 11.0.27, Netty 4.2.19.Final). With 4.1.2, five manual version overrides returned to automatic Boot parent management per §6 (tomcat, netty, postgresql, commons-lang3, slf4j-api — removal conditions measured from spring-boot-dependencies-4.1.2.pom).
- Spring Modulith: `2.1.2` GA — 2.0.6 → 2.1.0 in PR #144; → 2.1.1 platform wave; → 2.1.2 in the 2026-10-23 platform wave (archunit pairing unchanged: spring-modulith-core 2.1.2 resolves archunit 1.4.2, measured).
- Spring Cloud: `2025.1.4` (exception #14 — Oakwood train, edge BFF).
- Spring AI: `2.0.2` (exception #13 — "Spring AI 2.0.x supports Spring Boot 4.0.x and 4.1.x", docs.spring.io/spring-ai).
- Maven: `3.10.0` (Maven Wrapper tool: 3.3.4 — unchanged, independent of the distribution number; Maven distribution: 3.10.0, upgraded from 3.9.16 on 2026-10-24 per the deferred-upgrades plan). Official evidence read in full before the bump (maven.apache.org/docs/3.10.0/release-notes.html): Java 8+ floor (baseline Java 25 satisfies it); the Maven team supports the last two GA lines (3.10.x and 3.9.x) — 3.9.16 was the older of the two; the official upstream compatibility context is cited precisely: the Maven Releases History (maven.apache.org/docs/history.html) states "Currently, plugins provide Maven API compatibility down to 3.9.0", and the Maven Plugins Compatibility Plan (maven.apache.org/developers/compatibility-plan.html) sets 3.9.0 as the minimum Maven runtime prerequisite for plugins — both 3.9.16 and 3.10.0 sit inside that supported plugin runtime range; the 3.10.0 release notes additionally state "The core release is independent of plugin releases". Repository-scoped verification (the dated PR #714 CI run on the 3.10.0 distribution, 2026-10-24, head 03ed54d2 — full-reactor `./mvnw install -DskipTests --batch-mode`, module-complete `./mvnw verify --batch-mode`, and the full-integration `clean verify` all green) proves every plugin this build binds runs on 3.10.0; it does not by itself prove behavioral equivalence with 3.9.16, and no such equivalence is claimed — the upgrade-from-3.9.x notes document the move as straightforward. Breaking-change watch items recorded for the reactor gates (build behavior, zero code changes): stricter resolver/POM validation (escape hatch `-Dmaven.resolver.validation=off`), classpath ordering breadth-first, Super POM `release-profile` removal, Reproducible Builds on by default. The enforcer floor was raised to `[3.10,)` in the same PR — the wrapper pin and the floor mirror each other, so a silent rollback to 3.9.x fails the build (the PR #711 floor-reflection pattern).
- Flyway: Boot-managed (`12.4.0` via spring-boot-dependencies 4.1.2 — unchanged from 4.1.1). The 13.x major line exists on Maven Central; a major-line move is a planned initiative per §3.
- Stripe SDK: `33.4.2` — 34.0.0 is a MAJOR line on Central; a planned initiative per §3 with a dedicated migration window, not an automatic bump.
- springdoc-openapi: `3.1.1` — upgraded 2026-10-24 (deferred-upgrades plan phase 2b), after the gate report-format decision landed first (the official openapi-diff JSON report): optional parameter additions are COMPATIBLE per the official ChangedParameters model and pass without an exception; required additions are documentable with kind: parameter-add allowlist entries. Security motive: CVE-2026-75838 (XSS in the DOMPurify bundled with swagger-ui, fixed by swagger-ui 5.32.14) plus eight GHSAs around the MCP layer, which 3.1.1 makes opt-in (springdoc.ai.mcp.enabled defaults to false). Measured during phase 2b's first CI run: the #3351 java.time change (Duration/LocalTime/OffsetTime now resolved by swagger-core with partial-time/duration formats instead of the forced bare string) produces incompatible param-change and response-schema breaks the fail-closed gate deliberately cannot represent — resolved, not reverted, by the documented contract pin `OpenApiJavaTimeSchemaStabilityConfig` (restores the exact three `PrimitiveType.customClasses` STRING mappings springdoc 3.1.0 held; §6 discipline: reason = a security patch must not evolve the published contract, evidence = swagger-core 2.2.55 fromType lookup order + the clean 3.1.0 diff vs the same baseline, owner = platform maintainers, review = the next release re-baseline, where adopting the richer formats is a deliberate contract-evolution decision). Remaining generated-spec deltas stay compatible: Pageable query parameters on endpoints (optional adds), PagedModel-related response-schema resolution, uniqueItems on Set-derived types, JsonNullable unwrapping; the stale-from-birth disputes allowlist entry (a runtime 200→201 change the spec never carried — springdoc documents the default 200 for programmatic ResponseEntity statuses) is removed in the same PR, with the creation-endpoint spec-accuracy gap tracked in its own issue. swagger-core 2.2.52 → 2.2.55 moves the Jackson-2 override floor 2.22.0 → 2.22.1 (the 2.22.4 pin stays above it, re-measured latest of its line) and swagger-annotations-jakarta (exception #12) moves with the chain to 2.2.55. The 2026-10-07 revert decision is closed by this upgrade.
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

