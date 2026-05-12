# Backend Execution Plan (Official Sources-Driven)

## Objective
Deliver backend features through small PRs while enforcing compatibility with Spring Boot 4, Maven multi-module, and Spring Modulith boundaries.

## Official Sources (Mandatory)
- Spring Boot Reference: https://docs.spring.io/spring-boot/reference/index.html
- Spring Boot System Requirements: https://docs.spring.io/spring-boot/system-requirements.html
- Spring Boot Build Systems: https://docs.spring.io/spring-boot/reference/using/build-systems.html
- Spring Guides: https://spring.io/guides
- Spring Data: https://spring.io/projects/spring-data
- Spring Security: https://spring.io/projects/spring-security
- Spring Authorization Server: https://spring.io/projects/spring-authorization-server
- Spring Modulith Reference: https://docs.spring.io/spring-modulith/reference/index.html
- Maven Multiple Modules Guide: https://maven.apache.org/guides/mini/guide-multiple-modules.html
- Maven Getting Started: https://maven.apache.org/guides/getting-started/index.html

## Governance Rules
1. Do not add dependencies unless officially documented and compatible with Spring Boot 4.x.
2. Do not pin versions for artifacts managed by Spring Boot BOM.
3. Every new business capability must be a dedicated Maven module in the reactor.
4. Enforce Modulith boundaries: communicate via public APIs/ports/events.
5. Any DB change requires a new migration under `marketplace-app/src/main/resources/db/migration`.
6. Every new endpoint requires at least one test.
7. Security features require explicit authorization rules and tests.
8. Any deviation from official docs must be justified in PR body.

## Execution Phases
0. Baseline stabilization (build/tests/dev profile)
1. Provider module
2. Availability module
3. Payment gateway + webhooks
4. Notifications module
5. Ledger/settlements
6. Disputes/reports
7. Search improvements
8. Realtime messaging (WebSocket)
9. OpenAPI documentation

## Definition of Done (for each new module)
- Added to parent reactor and has independent `pom.xml`.
- Uses BOM-managed versions correctly.
- Own package namespace `com.marketplace.<module>`.
- Adds new migration if persistence changes.
- Adds tests (service/controller/integration as needed).
- Passes `mvn clean verify`.
- Passes Modulith boundary verification.
- Adds/updates authorization rules if exposing endpoints.
- Updates README/plan when run behavior changes.

## Standard Verification Commands
```bash
mvn clean verify
mvn -pl marketplace-app -am test
mvn -pl marketplace-app -am spring-boot:run -Dspring-boot.run.profiles=dev
```

For local DB-backed changes:
```bash
docker compose up -d postgres redis
mvn -pl marketplace-app -am spring-boot:run -Dspring-boot.run.profiles=dev
```

## PR Policy
- One PR per phase or small feature slice.
- PR body must include:
  - official source(s) used,
  - functional change summary,
  - migrations added,
  - tests executed,
  - documented deviations and rationale.
