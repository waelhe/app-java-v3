# 06 — Tests & Gates (isolated system map)

> **Test types, Testcontainers, Modulith/architecture tests, CI gates, commands.** Citations are `file:line`.

## 1. Test inventory (251 files)

- MUST | app 120, infra 18, payments 15, pricing 11, media 10, booking/provider 9, shared 9, identity 8, messaging/notifications/reviews 6, availability 5, disputes/geo/ledger/realestate 4, search 3, **catalog 0** (no `src/test`; covered from app). | [test] | (counted 2026-09-13)
- MUST | Domain modules hold unit tests; slices + integration centralized in `marketplace-app`. | [test] | (layout)
- MUST | Types: pure unit (Mockito+Instancio, 63 `MockitoBean`/18 Instancio files); 20× `@WebMvcTest` (all in app, `excludeAutoConfiguration=OAuth2ResourceServer`); 17× `@ApplicationModuleTest` (+ModuleTestConfig, cross-module `@MockitoBean`); 37× `@SpringBootTest`; 14× `*SecurityTest`; governance tests (ArchUnit/Modulith/docs-pins/contracts). | [test] | (annotation grep)
- ATTEND | Zero `@DataJpaTest` — JPA paths covered only via module/full tests (heavier, Docker-coupled). | [test] | (grep count 0)

## 2. Test infrastructure

- MUST | No abstract base; shared fixture `ModuleTestConfig` (auditorAware, apiVersioning, primary properties) imported by 18 files. | [code] | ModuleTestConfig.java:17-81
- MUST | `@ActiveProfiles("test")` (54 files); test profile: localhost PG (pool 10/1), simple cache, create-drop, Flyway OFF, stub mail, localhost issuer, OTLP off; test RSA key in test resources. | [code] | application-test.yml:1-63
- MUST | Testcontainers `postgis:18-3.6-alpine` + redis:8-alpine with `@ServiceConnection`; 46 files `disabledWithoutDocker=true` (skip, don't fail, without Docker). | [code] | MarketplaceApplicationTest.java:17-23 · UserDataExportIntegrationTest.java:99-109
- ATTEND | Surefire runs `**/*Test` (excl. `*IT`/`*IntegrationTest`); Failsafe runs 51 `*IntegrationTest` (+0 `*IT`) at `integration-test`+`verify`. | [code] | pom.xml:403-431

## 3. Verification & coverage gates

- MUST | `ModulithVerificationTest`: `verify()` + write docs; skipped only on JDK 26+ (ASM limit); same guard on docs test. | [code] | ModulithVerificationTest.java:12-22
- MUST | JaCoCo single gate: 70% bundle INSTRUCTION_COVEREDRATIO at `verify`; broad excludes (Application/Config/Entity/Spi/Port/jpa/events/HealthIndicator/records); NO per-module thresholds. | [code] | pom.xml:331-400,48
- MUST | Build gates: enforcer (convergence/upper-bound/no-dup-pom/Maven 3.9+/Java 21+), `failOnWarning=true`, dependency-analyze non-failing. | [code] | pom.xml:303-462

## 4. CI (7 workflows) + commands

- MUST | `ci.yml`: gitleaks + JDK25 + OpenAPI-compat gate (boots jar) + `./mvnw verify` (profile test + service env) + report upload. | [code] | ci.yml:53-111
- MUST | `integration-test.yml`: `clean verify` + result analyzer + install + boot + health/Data-REST/GraphQL checks. | [code] | integration-test.yml:53-179
- MUST | `container-scan.yml`: Trivy image scan CRITICAL/HIGH exit-1 (+weekly); `codeql.yml`: package-extract analyze (+weekly); `maven-publish.yml`: release gate (green CI + tag ancestry) then deploy; `watchdog.yml`: post-deploy probes (not a merge gate); `fork-sync.yml`: delivery (not a gate). | [code] | (workflow files)
- MUST | Extras: OpenAPI scripts + allowlist; CodeRabbit auto-review all branches; Dependabot weekly (Boot+Modulith grouped, actions grouped); PR template requires `mvn clean verify` + `-pl marketplace-app -am test` + dev run + Modulith/architecture checks. | [code] | .ci/ · .coderabbit.yaml · dependabot.yml · pull_request_template.md:57-68
- MUST | Commands: CI `./mvnw verify`; local `mvn clean verify -pl <module> -am` / `./mvnw clean verify -pl <module> -am`; PR `mvn -pl marketplace-app -am test`. Evidenced greens in PROJECT_MAP/SYSTEM. | [doc] | AGENTS.md:44 · SYSTEM.md · PROJECT_MAP.md

## 5. Known test debt (evidenced only)

- ATTEND | 46 Docker-required files skip silently without Docker — local runs under-report; full signal only in CI. | [test] | (count)
- ATTEND | 6 `*IntegrationTest` WITHOUT Testcontainers depend on localhost:5432/6379 (fail without CI env). | [test] | (file list)
- ATTEND | 2 JDK-26 skips (Modulith verify/docs); zero other `@Disabled`. | [code] | ModulithVerificationTest.java:16
- ATTEND | Shared-single-Postgres contention guarded (pool 10/1) but still shared; coverage via aggregate + excludes (0–5-test modules pass). | [code] | application-test.yml:7-18 · pom.xml:336-362