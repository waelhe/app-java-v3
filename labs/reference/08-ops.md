# 08 — Production: Actuator, Observability, AOT, Packaging (isolated official reference)

> **Tree refs:** 1.10 (Production-ready/Actuator), 1.9 (Packaging/Deployment/AOT), 1.13 (Actuator endpoints), 1.14 (Executable Jar), 1.12 (Maven Plugin), 1.3 (Graceful Shutdown).
> **Source:** `https://docs.spring.io/spring-boot/reference/` @ 4.1.1 — verified live 2026-09-12.
> **Format:** `TYPE | RULE | [quote|paraphrase] | URL`. **Isolation:** no internal repo data. **Encoding:** UTF-8.

---

## 1. Actuator & Exposure (1.10)

- MUST | Add the `spring-boot-starter-actuator` to enable production-ready features. | [paraphrase] | actuator/enabling.html
- MUST | By default only the `health` endpoint is exposed over HTTP and JMX — expose others explicitly: `management.endpoints.web.exposure.include=health,info`. | [quote] | actuator/endpoints.html
- AVOID | `exposure.include=*` except behind a firewall — ensure exposed actuators don't leak sensitive information. | [paraphrase] | actuator/endpoints.html
- ATTEND | Health details default to `never` — configure `management.endpoint.health.show-details`/`show-components` (never/when-authorized/always) and `roles` as needed. | [paraphrase] | actuator/endpoints.html
- ATTEND | Kubernetes probes: `LivenessStateHealthIndicator`/`ReadinessStateHealthIndicator` available at `/actuator/health/liveness` and `/actuator/health/readiness` (health groups). | [paraphrase] | actuator/endpoints.html#actuator.endpoints.kubernetes-probes
- ATTEND | Spring Security auto-secures other actuators when no custom `SecurityFilterChain` exists. | [paraphrase] | actuator/endpoints.html

## 2. Observability: Metrics, Tracing, Logging (1.10)

- ATTEND | Micrometer provides metrics via `/actuator/metrics`; Prometheus via `/actuator/prometheus` (add micrometer-registry-prometheus). | [paraphrase] | actuator/metrics.html · actuator/prometheus.html
- ATTEND | Tracing via Micrometer Tracing with Brave/OpenTelemetry — a `Tracer`/spans are auto-configured; export spans via configured `tracing-exporter` (Zipkin, OTLP), not via `/actuator/httpexchanges` (that endpoint records HTTP exchanges, not tracing). | [paraphrase] | actuator/tracing.html
- MUST | Set logging levels via `logging.level.<logger>=<level>` (TRACE..FATAL, OFF) and `logging.level.root`; env vars like `LOGGING_LEVEL_ORG_SPRINGFRAMEWORK_WEB=DEBUG`. | [quote] | features/logging.html
- MUST | Define logging groups: `logging.group.<name>=<loggers>` then set the level for the whole group: `logging.level.<group>=<level>`. | [quote] | features/logging.html#features.logging.log-groups
- AVOID | Logging secrets/tokens/JWTs/card data — follow a redaction policy (see 09-api-web.md OWASP logging). | [paraphrase] | features/logging.html + owasp (cross-ref)

## 3. AOT & Native (1.9)

- ATTEND | AOT inspects the `ApplicationContext` at build time and applies discovery/decisions normally done at runtime; enable JVM AOT via `spring.aot.enabled=true`. | [paraphrase] | packaging/aot.html
- MUST | Under AOT: classpath fixed at build time, beans can't change at runtime, `@Profile` chosen at build time, `@Conditional` on env props only at build time; instance-supplier beans and `registerSingleton` can't be transformed. | [paraphrase] | spring-framework core/aot.html (cross-ref, see 01-core.md §5)
- MUST | Use `@ImportRuntimeHints`/`RuntimeHintsRegistrar`/`@Reflective` near the requiring component; test with `RuntimeHintsPredicates` and the RuntimeHints agent. | [paraphrase] | packaging/aot.html
- ATTEND | GraalVM native images: use AOT to produce a native executable; not required for a plain JVM deployment. | [paraphrase] | packaging/native-image/index.html

## 4. Packaging & Deployment (1.9)

- MUST | Package as an executable JAR (`spring-boot-maven-plugin` repackage) — the nested-JAR launcher runs the app standalone. | [paraphrase] | packaging/executable-jar.html · maven-plugin/packaging-executable-jars.html
- ATTEND | The Maven plugin also supports building OCI images (`boot-build-image`) with cloud-native buildpacks; efficient layers separate app deps from code. | [paraphrase] | maven-plugin/packaging-oci-images.html · packaging/container-images.html
- ATTEND | Dockerfile guidance: layering improves cache + startup; use the optimized image via the plugin. | [paraphrase] | packaging/container-images/dockerfiles.html
- ATTEND | Boot 4.1 default: `server.shutdown=graceful` is enabled on all embedded servers — new requests stop at the network layer while in-flight ones complete; tune `spring.lifecycle.timeout-per-shutdown-phase`. | [quote] | web/graceful-shutdown.html
- ATTEND | Devtools are disabled in fully packaged apps and "running devtools is a security risk" in production — never package them. | [paraphrase] | using/devtools.html

## 5. Dependency Versions & Starters (1.15)

- MUST | Manage dependency versions via the Boot BOM (`spring-boot-dependencies`); never pin versions the BOM manages. | [paraphrase] | using/build-systems.html#using.build-systems.dependency-management
- ATTEND | Starters follow `spring-boot-starter-*`; a third-party starter must NOT use the `spring-boot` prefix. | [paraphrase] | using/build-systems.html#using.build-systems.starters

---

## Cross-cutting gap insertions

| Gap | Home | Rule added |
|---|---|---|
| Graceful shutdown + timeout | §4 | Graceful is default in 4.1; tune timeout per phase. |
| Actuator minimal exposure | §1 | Explicit `include=health,info`; `*` only behind firewall. |
| Devtools out of production | §4 | Never package devtools in production images. |

## Verification note
- Pages verified live 2026-09-12 at Boot 4.1.1; `/actuator/modulith` is Modulith-owned (see 05-modulith.md §7).
- Tree coverage: 1.10 ◐ · 1.9 ◐ · 1.13 ◐ (per-need endpoints) · 1.14 ◐ · 1.12 ◐ · 1.3 ◐ (graceful).