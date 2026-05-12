# Phase 0 Baseline Checklist

Use this checklist before implementing any new backend module.

## Environment and compatibility
- [ ] Java version is compatible with current Spring Boot version.
- [ ] Maven version meets project minimum requirements.
- [ ] No unmanaged or duplicated dependency versions were introduced.

## Build and test gates
- [ ] `mvn clean verify` passes.
- [ ] `mvn -pl marketplace-app -am test` passes.
- [ ] Modulith verification test passes.
- [ ] Architecture rules test passes.

## Local runtime gates
- [ ] Infrastructure services started when needed:
  - [ ] `docker compose up -d postgres redis`
- [ ] App starts in dev profile:
  - [ ] `mvn -pl marketplace-app -am spring-boot:run -Dspring-boot.run.profiles=dev`

## Governance gates
- [ ] New work item references official Spring/Maven sources.
- [ ] Planned changes are scoped to one feature PR.
- [ ] DB changes include a new migration file (if applicable).
- [ ] Security changes include authorization rules + negative access tests.

## PR readiness
- [ ] `.github/pull_request_template.md` fully completed.
- [ ] Tests and commands output captured in PR body.
- [ ] Any deviation from official docs is explicitly documented.
