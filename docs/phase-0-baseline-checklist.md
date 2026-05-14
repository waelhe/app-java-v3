# Phase 0 Baseline Checklist

Use this checklist before implementing any new backend module.

## Environment and compatibility
- [x] Java version is compatible with current Spring Boot version.
- [x] Maven version meets project minimum requirements.
- [x] No unmanaged or duplicated dependency versions were introduced.

## Build and test gates
- [x] `mvn clean verify` passes.
- [x] `mvn -pl marketplace-app -am test` passes.
- [x] Modulith verification test passes.
- [x] Architecture rules test passes.

## Local runtime gates (requires Docker — skipped, Docker unavailable)
- [ ] Infrastructure services started when needed:
  - [ ] `docker compose up -d postgres redis`
- [ ] App starts in dev profile:
  - [ ] `mvn -pl marketplace-app -am spring-boot:run -Dspring-boot.run.profiles=dev`

## Governance gates
- [x] New work item references official Spring/Maven sources.
- [x] Planned changes are scoped to one feature PR.
- [x] DB changes include a new migration file (if applicable).
- [x] Security changes include authorization rules + negative access tests.

## PR readiness
- [x] `.github/pull_request_template.md` exists and is fully completed.
- [ ] Tests and commands output captured in PR body.
- [ ] Any deviation from official docs is explicitly documented.
