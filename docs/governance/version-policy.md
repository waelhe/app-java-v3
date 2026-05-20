# Version & Upgrade Policy

This document defines official version governance for the Marketplace backend.

## 1) Supported baseline

- Java: `21` (LTS)
- Spring Boot: `4.x` stable line
- Maven: `3.9+`

These baselines must remain aligned with project build enforcement rules.

## 2) Source of truth

Before any upgrade decision, confirm compatibility against official references:

- Spring Boot reference docs
- Spring Boot system requirements
- Spring Framework reference docs
- Apache Maven official guides

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
