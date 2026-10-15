# GraphQL Phase-0 Baseline — Measured 2026-10-15

**Status:** Measured record for `docs/api/graphql-target-architecture.md` §11 (Phase 0)
**Baseline head:** `docs/graphql-target-architecture` @ `4750264e` (PR #582, all gates green)
**Measurement doctrine:** every number below was measured on 2026-10-15 from the GitHub REST API (repository content at the branch head), GitHub Actions runs and downloadable artifacts (surefire XML), GitHub code search, and Maven Central POMs. Per the owner's directive of 2026-10-15 — *push first, test on GitHub, never work locally* — no local build, no local Redis, and no local application runtime participated in this record. GitHub Actions CI is the verification vehicle: it already provides the `redis:8-alpine` service container (measured in both the 8-shard CI matrix and the Full Integration Test workflow) that the retired local-Redis plan was meant to supply.

## 1. Resolved dependency chain (Phase 0, item 3 — measured)

| Level | Coordinate | Version | Evidence source |
|---|---|---|---|
| Root POM (branch head) | Spring Boot parent/BOM | 4.1.1 | GitHub contents API, `pom.xml` @ `4750264e` |
| `marketplace-app` POM | `org.springframework.graphql:spring-graphql-test` | *(declared test-scope, no version — BOM-managed)* | GitHub contents API, `marketplace-app/pom.xml` @ `4750264e` |
| Boot 4.1.1 BOM | `spring-graphql.version` | **2.0.5** | Maven Central `spring-boot-dependencies-4.1.1.pom` |
| spring-graphql 2.0.5 POM | `com.graphql-java:graphql-java` (compile) | **25.0** | Maven Central `spring-graphql-2.0.5.pom` |
| graphql-java 25.0 POM | `com.graphql-java:java-dataloader` (compile) | **6.0.0** | Maven Central `graphql-java-25.0.pom` |

Cross-check: `mvn dependency:tree` executed on `marketplace-app` (2026-10-15, the session before this record) resolved the identical chain — `spring-graphql-test:2.0.5:test` → `spring-graphql:2.0.5:compile` → `graphql-java:25.0` → `java-dataloader:6.0.0`. The two independent sources agree, which satisfies the §12 requirement ("Do not infer the application's resolved transitive GraphQL Java version from the Spring GraphQL release tag; confirm the exact resolved dependency tree").

Consequences:

1. The transitive GraphQL Java version is now **measured, not inferred** — security remediation planning (including the CVE-2026-41699/41700/41856 family noted in §12) can target the exact resolved versions.
2. Versions remain **BOM-managed** — no pinning is introduced by this record, and no dependency change is made by PR #582.

## 2. Current surface — re-measured at the branch head

Schema (`marketplace-app/src/main/resources/graphql/schema.graphqls`, 1014 bytes, fetched via the GitHub contents API):

- Root types present: `type Query`, `type Mutation`. **No `type Subscription` root exists.**
- Fields: `service(id: ID!): Service`, `services: [Service!]!`, `createService(input: ServiceInput!): Service!`, plus the `Service`, `ServiceStatus`, and `ServiceInput` types.

Base `application.yml` `spring.graphql` block (measured at the branch head):

```yaml
graphql:
  graphiql:
    enabled: true
  websocket:
    path: /graphql/ws
  schema:
    introspection:
      enabled: true
    inspection:
      enabled: true
```

Staging/production profiles disable GraphiQL and introspection (per the §2.2 audit; unchanged by this PR). The measured combination — a configured WebSocket path with **no Subscription root** — is the concrete evidence behind Phase 0 item 4 and the Phase 1 action to disable the unused transport.

## 3. Consumer inventory (Phase 0, item 1 — measured)

GitHub code search over the repository, executed 2026-10-15:

- **Kotlin: 0 files** reference GraphQL. The Android client does not consume GraphQL — no mobile or deployed client binds to this schema.
- **Java: 21 files** reference GraphQL, all internal to this repository: the GraphQL controller package (5 main files), its 6 test classes, the shared API contract layer (`SpringPagination`, `ApiProblemDetailException`, `GlobalExceptionHandler` + its test, `ProviderListingView`), the security chain (`SecurityConfig` — the `/graphql` stateless Bearer chain), catalog javadoc-level references (`CatalogService`, `ProviderListingRepository`), and two configuration tests (`StagingProfileConfigTest`, `ObservationCoverageFilesTest`).

Conclusion: the only consumers of the current GraphQL surface are this repository's own integration tests and the CI smoke contract (anonymous `/graphql` → 401). There is no external consumer to break. Schema-compatibility leverage is highest now and decreases after the first client binds — the compatibility gate must land (Phase 1) before any external consumer is attached.

## 4. Test baseline (Phase 0, item 5 — measured part)

Measured from the surefire XML artifacts of CI run `#38697120094` on the baseline head (GitHub-hosted runners):

| Test class | Tests | Failures/Errors | Class time | Source artifact |
|---|---|---|---|---|
| `ServiceGraphQlMutationIntegrationTest` | 4 | 0/0 | 21.568 s | `test-results-app-shard-3` |
| `GraphQlErrorIntegrationTest` | 3 | 0/0 | 12.131 s | `test-results-app-shard-2` |
| `GraphQlExceptionResolverTest` | 4 | 0/0 | 0.293 s | `test-results-modules` |
| `ServiceGraphQlControllerTest` | 3 | 0/0 | 0.174 s | `test-results-modules` |
| `ServiceInputValidationTest` | 5 | 0/0 | 0.051 s | `test-results-modules` |
| `ServiceMapperTest` | 2 | 0/0 | 0.004 s | `test-results-modules` |
| **Total** | **21** | **0/0** | **34.221 s** | |

Reading the numbers honestly: per-class Spring context boot dominates the two integration classes (~20.1 s of the mutation class's 21.568 s; ~11.4 s of the error class's 12.131 s). Net per-test times on the runners are sub-second — the slowest single case is `createServiceWithNegativePriceIsRejected` at 1.326 s (the full argument-binder/validation round-trip), and the error-mapping cases measure 0.029–0.694 s. These are test-execution wall-times on shared GitHub runners, **not** request latencies of a production deployment; §6 records what remains deferred.

## 5. CI gate baseline (measured on head `4750264e`, event `pull_request`, 2026-10-15)

| Workflow | Run | Total | Longest job (measured) | Conclusion |
|---|---|---|---|---|
| CI | `#38697120094` | **16 m 22 s** | Tests — app shard 3/8: 13 m 27 s (8-way parallel matrix) | success |
| Integration Test | `#38697120049` | **2 m 57 s** | Full Integration Test (JDK 25): 2 m 13 s, `redis:8-alpine` service container | success |
| CodeQL | `#38697120059` | **9 m 24 s** | CodeQL Analyze (Java 25): 8 m 28 s | success |
| Container Scan | `#38697120065` | **5 m 04 s** | Container Image Scan (Docker): 4 m 16 s | success |

Inside the CI workflow (measured job times): Build (JDK 25) 61 s; OpenAPI Compatibility Gate 2 m 17 s; Tests — modules 10 m 33 s; Tests — app shards 1–8/8 in parallel (fastest 9 m 45 s, slowest 13 m 27 s); Coverage Gate 52 s; Build & Test summary gate 1 s.

Check-runs on the head: **18/18 success**, plus CodeRabbit `success` and `security/snyk (waelhe)` `success`. This is the measured green baseline against which every later GraphQL change (starting with the Phase-1 PR) must hold or improve.

## 6. Explicitly not yet measured (deferred to the Phase-1 executable gates)

This record is honest about its boundaries — the following are **not** measured anywhere in it, and land as executable gates in the Phase-1 PR (relay pagination, query-cost budgets, the canonical error contract):

1. **Request latency (p50/p95) of a deployed runtime.** The §4 numbers are test wall-times including context boot; they are an upper-bound proxy, not latency.
2. **Response payload sizes per operation.** No instrumentation exists yet.
3. **DB query counts per operation.** No instrumentation exists yet; the unbounded `services()` collection query remains the primary resource risk until pagination lands.
4. **Default depth/complexity behavior of the resolved graphql-java 25.0.** Unverified until the explicit, tested budgets land (Phase 1); the resolved versions in §1 remove the version ambiguity but not the behavioral one.
5. **GraphQL WebSocket transport usage.** Measured unused (§2: no Subscription root; §3: no consumers); Phase 1 disables it unless a subscription use case is approved.

## 7. Phase-0 decision ledger

| Phase-0 item (target architecture §11) | Status after this record |
|---|---|
| 1. Inventory GraphQL consumers and operations | **Measured — closed:** no external consumers (0 Kotlin references; 21 internal Java files); consumers are the repository's own tests and the CI smoke contract |
| 2. Anonymous-read policy for public discovery | **Decided — closed (2026-10-15):** retained protected contract — anonymous `/graphql` stays 401 (grounded in OWASP GraphQL Cheat Sheet authorization enforcement + Spring for GraphQL Security reference + the measured 401 smoke contract and zero external consumers). Re-open trigger: an approved public-discovery journey with designed and tested per-field/object authorization |
| 3. Confirm exact Spring GraphQL and GraphQL Java versions | **Measured — closed:** Boot 4.1.1 BOM → spring-graphql 2.0.5 → graphql-java 25.0 → java-dataloader 6.0.0 |
| 4. Measure WebSocket subscription usage | **Measured — closed:** unused (no Subscription root, no consumers, path still configured — disable in Phase 1) |
| 5. Baseline for latency / response size / DB query count | **Partially measured:** CI gate timings + GraphQL test suite + resolved versions recorded (§1, §4, §5); runtime latency/size/DB-count deferred to the Phase-1 executable gates (§6) |

## 8. Re-measurement path (no local environment required)

Every input to this record is remote and repeatable: repository content at a named ref (GitHub contents API), Actions runs and their downloadable artifacts (`/actions/runs/{id}/artifacts`, surefire XML), code search (`search/code`), and the Maven Central POM chain (`spring-boot-dependencies` → `spring-graphql` → `graphql-java` → `java-dataloader`). Re-running the same path next month, after a dependency bump, or on another provider yields a fresh baseline without any local Redis, build, or runtime.
