# 06 — Build: Maven 3.9.x + Flyway 12.x + Java 25 (isolated official reference)

> **Tree refs:** 6.1-6.6 (Maven), 7.1-7.5 (Flyway), 8.1-8.2 (Java).
> **Sources:** `https://maven.apache.org/guides/` + `https://maven.apache.org/pom.html` + `https://documentation.red-gate.com/flyway/` + `https://dev.java/` (docs.oracle.com 403 → dev.java) — verified live 2026-09-12.
> **Format:** `TYPE | RULE | [quote|paraphrase] | URL`. **Isolation:** no internal repo data. **Encoding:** UTF-8.

---

## 1. Maven Lifecycle (6.1)

- MUST | Built-in lifecycles: default, clean, site; default phases run sequentially: validate → … → test → package → integration-test → verify → install → deploy. | [quote] | maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html
- MUST | Call only the LAST phase you need — `mvn verify` runs everything up to and including verify (integration tests included). | [paraphrase] | maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html
- ATTEND | Hyphenated phases (`pre-*`/`post-*`/`process-*`/`integration-test`) are part of the default lifecycle but not usually called directly — bind goals to phases in the POM; invoking `integration-test` directly can leave the test environment hanging. | [paraphrase] | maven.apache.org/guides/introduction/introduction-to-the-lifecycle.html
- MUST | Use the reactor for per-module builds: `mvn -pl <module> -am test` builds only the module + its dependencies. | [quote] | maven.apache.org/guides/ (reactor)

## 2. Dependency Management (6.2)

- MUST | Import the BOM / use `spring-boot-starter-parent` so the framework manages dependency versions; never pin a version the BOM already manages. | [paraphrase] | maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html
- ATTEND | `dependencyManagement` centralizes versions for transitive deps; only declare a version where the BOM does not cover it (with a documented exception). | [paraphrase] | maven.apache.org/pom.html#Dependency_Management
- AVOID | Duplicate plugin declarations in the same build (pluginManagement pitfall) — resolution/merge behavior can surprise. | [paraphrase] | maven.apache.org/ref/current/maven-core

## 3. Reactor (6.3)

- ATTEND | The reactor orders modules by dependency, not by `<modules>` declaration order; do not rely on `<modules>` order for correctness. | [paraphrase] | maven.apache.org/guides/mini/guide-multiple-modules.html
- ATTEND | `dependencyManagement` does not change reactor build order — only actual dependencies do. | [paraphrase] | maven.apache.org/guides

## 4. Reproducible Builds (6.4)

- MUST | Fix plugin versions and avoid network-drifting dependencies so builds are reproducible; Maven supports output reproducibility. | [paraphrase] | maven.apache.org/guides/mini/guide-reproducible-builds.html

## 5. Failure handling (6.5)

- ATTEND | `--fail-fast` (default) kills the build on the first module failure; `--fail-at-end`/`--fail-never` alter that behavior deliberately. | [paraphrase] | maven.apache.org/guides

---

## 6. Flyway (7.x)

- MUST | Name versioned migrations `V{n}__description.sql` (unique, applied in ascending order, forward-only) and repeatable migrations `R{name}__description.sql`. | [paraphrase] | documentation.red-gate.com/flyway/ — Executable Migrations
- MUST | Never delete or modify an already-applied migration — a change is a new `V{n+1}`; checksums in `flyway_schema_history` guard against tampering. | [quote] | documentation.red-gate.com/flyway/ — Never delete or modify applied migrations
- MUST | Run `validate` as part of the build (Boot auto-runs it) to catch drift against applied migrations. | [paraphrase] | documentation.red-gate.com/flyway/ — Validate
- ATTEND | `baseline` is for adopting an existing DB; repeatable migrations re-run when their content/checksum changes. | [paraphrase] | documentation.red-gate.com/flyway/
- ATTEND | Boot auto-configures Flyway via `spring-boot-starter-flyway` (+ a database-specific module such as `flyway-database-postgresql`) — it applies migrations before the app serves traffic and participates in the health/lifecycle. | [quote] | spring-boot how-to data-initialization

---

## 7. Java 25 (8.x)

- MUST | Java 25 is LTS (released 2025-09-16), not 2026; use records for immutable carriers (DTOs/events/responses), sealed types + pattern matching for idiomatic dispatch. | [paraphrase] | dev.java
- ATTEND | The project toolchain (source/target) is fixed by the root POM; do not raise/lower it without a decision. | [paraphrase] | dev.java
- ATTEND | Use `Optional` for presence, not for control flow; prefer explicit records for typed configuration (see 01-core.md §1). | [paraphrase] | dev.java

---

## Cross-cutting gap insertions

| Gap | Home | Rule added |
|---|---|---|
| Flyway forward-only / immutability | §6 | Never edit applied migrations; fixes are new `V{n+1}`. |
| Call only the final phase | §1 | `mvn verify` runs through `integration-test`; don't invoke `integration-test` directly. |
| Never pin BOM-managed versions | §2 | BOM manages versions; exceptions documented. |

## Verification note
- Maven/Flyway pages verified live 2026-09-12; `dev.java` used for Java 25 (docs.oracle.com 403).
- Tree coverage: 6.1 ✅ · 6.2 ✅ · 6.3 ✅ · 6.4 ✅ · 6.5 ✅ · 6.6 ◐ · 7.1 ✅ · 7.2 ✅ · 7.3 ✅ · 7.4 ✅ · 7.5 ◐ · 8.1 ✅ · 8.2 ◐.