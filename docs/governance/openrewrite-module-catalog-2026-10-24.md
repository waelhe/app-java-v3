# OpenRewrite official module inventory and platform relevance — 2026-10-24

Versions and license labels below are based on the official current module/version table: https://docs.openrewrite.org/reference/latest-versions-of-every-openrewrite-module (checked 2026-10-24). "Source Available" means precompiled access is limited by Code Genome entitlement. It does not, by itself, establish that a proposed source-built use is compliant: source-built modules remain opt-in and must pass CI and official license review.

## Engine, BOMs, parsers

| Artifact | Version | Official license | Relevance / treatment |
|---|---:|---|---|
| org.openrewrite:rewrite-maven-plugin | 6.50.0 | Apache-2.0 | Active recipe runner |
| org.openrewrite:rewrite-bom | 8.93.0 | Apache-2.0 | Selected core BOM; matches plugin 6.50.0's declared runtime |
| org.openrewrite.recipe:rewrite-recipe-bom | 3.41.0 | Apache-2.0 | Imported in OpenRewrite profile |
| org.openrewrite:rewrite-core | 8.93.11 | Apache-2.0 | Runtime/core |
| org.openrewrite:rewrite-java | 8.93.11 | Apache-2.0 | Java parsing and core recipes |
| org.openrewrite:rewrite-maven | 8.93.11 | Apache-2.0 | Maven/POM recipe family |
| org.openrewrite:rewrite-gradle | 8.93.11 | Apache-2.0 | Gradle recipe family |
| org.openrewrite:rewrite-gradle-plugin | 7.44.1 | Apache-2.0 | Gradle runner; not needed for the Maven project |
| org.openrewrite:rewrite-docker | 8.93.0 | Apache-2.0 | Active; aligned to selected core BOM |
| org.openrewrite:rewrite-json | 8.93.11 | Apache-2.0 | JSON files |
| org.openrewrite:rewrite-properties | 8.93.11 | Apache-2.0 | JVM configuration |
| org.openrewrite:rewrite-xml | 8.93.11 | Apache-2.0 | XML/POM files |
| org.openrewrite:rewrite-yaml | 8.93.11 | Apache-2.0 | YAML configuration |
| org.openrewrite:rewrite-templating | 1.46.0 | Apache-2.0 | Recipe implementation support |
| org.openrewrite.meta:rewrite-analysis | 2.39.0 | Apache-2.0 | Analysis metadata |
| org.openrewrite:rewrite-android | 8.93.11 | Moderne Source Available | Not relevant to current JVM stack |
| org.openrewrite:rewrite-csharp | 8.93.11 | Moderne Source Available (upstream `build.gradle.kts` applies the SA license plugin) | Required transitively by `rewrite-all`; no application C# source is in scope |
| org.openrewrite:rewrite-go | 8.93.11 | Moderne Source Available (upstream `build.gradle.kts` applies the SA license plugin) | Required transitively by the source-built catalog; no application Go source is in scope |
| org.openrewrite:rewrite-javascript | 8.93.11 | Moderne Source Available | Required by the broad catalog; no application JavaScript source is in scope |
| org.openrewrite:rewrite-python | 8.93.11 | Moderne Source Available | Not relevant to current Java modules |
| org.openrewrite:rewrite-ruby | 8.93.11 | Moderne Source Available | Not relevant to current Java modules |
| org.openrewrite:rewrite-scala | 8.93.11 | Moderne Source Available | Not relevant to current Java modules |
| org.openrewrite:rewrite-toml | 8.93.11 | Apache-2.0 | Potential configuration-file use; not explicitly selected |

## Official recipe-pack catalog

| Artifact | Version | Official license | Platform relevance | Current treatment |
|---|---:|---|---|---|
| rewrite-all | 1.30.2 | Apache-2.0 | Broad aggregate | Source build attempted after required modules; must pass CI |
| rewrite-third-party | 0.49.0 | Apache-2.0 | Third-party library migrations | Source build attempted after required modules; must pass CI |
| rewrite-java-dependencies | 1.64.1 | Apache-2.0 | Maven dependencies and resolution | Active standard inventory |
| rewrite-jackson | 1.31.1 | Apache-2.0 | Jackson/serialization | Active |
| rewrite-netty | 0.13.2 | Apache-2.0 | Netty/network stack | Active |
| rewrite-openapi | 0.35.0 | Apache-2.0 | OpenAPI contracts | Active |
| rewrite-liberty | 1.28.2 | Apache-2.0 | Liberty/WebSphere runtime | Not currently relevant |
| rewrite-micronaut | 2.39.0 | Apache-2.0 | Micronaut framework | Not currently relevant to Spring Boot runtime |
| rewrite-quarkus | 2.36.2 | Apache-2.0 | Quarkus framework | Not currently relevant to Spring Boot runtime |
| rewrite-apache | 2.34.0 | Moderne Source Available | Apache-library migrations | Source-build candidate; license and closure must be validated |
| rewrite-cucumber-jvm | 2.20.0 | Moderne Source Available | Testing/BBD | Source-build candidate |
| rewrite-feature-flags | 1.24.2 | Moderne Source Available | Feature-flag libraries | Optional; actual dependency usage not proven |
| rewrite-github-actions | 3.33.0 | Moderne Source Available | CI workflow modernization | Relevant to GitHub Actions governance |
| rewrite-gitlab | 0.25.2 | Moderne Source Available | GitLab CI | Not relevant to GitHub Actions workflow |
| rewrite-hibernate | 2.27.0 | Moderne Source Available | ORM/JPA migrations | Relevant only if Hibernate/JPA is present |
| rewrite-jenkins | 0.38.2 | Moderne Source Available | Jenkins pipeline | Not relevant to GitHub Actions workflow |
| rewrite-joda | 0.12.0 | Moderne Source Available | Joda-Time migration | Optional; usage not established |
| rewrite-logging-frameworks | 3.34.1 | Moderne Source Available | Java logging migrations | Relevant to application logging |
| rewrite-micrometer | 0.31.2 | Moderne Source Available | Metrics/observability | Relevant if Micrometer is used |
| rewrite-migrate-java | 3.46.0 | Moderne Source Available | Java/API migrations | Relevant to Java 25 maintenance |
| rewrite-okhttp | 0.25.2 | Moderne Source Available | OkHttp | Optional; usage not established |
| rewrite-pmd | 0.1.4 | Moderne Source Available | PMD/static analysis | Optional; PMD adoption not established |
| rewrite-prethink | 1.6.0 | Moderne Source Available | Static analysis | Candidate; recipes require review |
| rewrite-rewrite | 0.35.0 | Moderne Source Available | Recipe-authoring tooling | Not an application-runtime need |
| rewrite-spring | 6.41.0 | Moderne Source Available | Spring Framework/Boot/Cloud migrations | Source-built workflow targets a Community Edition Boot 4.1 property recipe |
| rewrite-spring-to-quarkus | 0.13.2 | Moderne Source Available | Migration to Quarkus | Not relevant to the Spring Boot target |
| rewrite-static-analysis | 2.45.0 | Moderne Source Available | Java static analysis/remediation | Source-build dependency candidate |
| rewrite-testing-frameworks | 3.49.1 | Moderne Source Available | JUnit/Mockito | Relevant to test suite modernization |

## Evidence boundary

- Standard inventory workflow run 40061545908 succeeded with 6,444 dependency rows and zero recorded resolution failures.
- Source-build run 40061545912 failed before any source compilation because a Bash local statement referred to sibling variables under set -u. That was an implementation bug; the script has since been corrected and rerun.
- The per-run recipe CSV records the discovered IDs. Package-prefix license attribution remains labelled as heuristic until cross-checked to each official recipe source; no claim of full catalog coverage is made solely from a count.


## Core runtime alignment to the selected Maven plugin

The official module table checked on 2026-10-24 advertises `rewrite-bom:8.93.11`, while the official `rewrite-maven-plugin:6.50.0` POM sets `rewrite.version=8.93.0`. The current runtime uses `rewrite-bom:8.93.0` and `rewrite-docker:8.93.0` so the declared plugin runtime and direct core parser stay aligned. Upgrading the plugin to a release whose own POM declares the newer core version is the appropriate way to move this core runtime forward; do not silently mix these versions.

## Recipe BOM 3.41.0 alignment observed in effective-POM

The effective POM generated in CI on 2026-10-24 reported these managed versions. These are the versions pinned by the plugin, even where the latest-module listing advertises a newer point release; the explicit validator must pass against the imported BOM before a run is counted as successful.

| Coordinate | Latest-module listing checked 2026-10-24 | BOM-resolved plugin pin |
|---|---:|---:|
| `org.openrewrite.recipe:rewrite-java-dependencies` | 1.64.1 | 1.64.0 |
| `org.openrewrite.recipe:rewrite-jackson` | 1.31.1 | 1.31.0 |
| `org.openrewrite.recipe:rewrite-testing-frameworks` | 3.49.1 | 3.49.0 |
| `org.openrewrite:rewrite-mainframe` | 3.3.1 | 3.2.1 |

This is deliberate BOM alignment rather than accidental drift. An override to a newer module outside the imported BOM must be documented as a separate tested compatibility override, not silently described as BOM-aligned.


The per-run catalog CSV resolves discovered recipe FQNs against both `.class` entries and `META-INF/rewrite/*.yml` descriptors in the downloaded/source-built JARs. The artifact coordinate and its official module license are recorded from the JAR that contains the implementation or descriptor. Unknown/conflicting attribution is a strict failure, not counted as audited. The source-built profile includes `rewrite-okhttp` because `android/app` uses an OkHttp client, plus `rewrite-feature-flags` for review against the platform's custom feature-flag model.


## Separate mainframe module version source

`org.openrewrite:rewrite-mainframe` is maintained in [moderneinc/rewrite-mainframe](https://github.com/moderneinc/rewrite-mainframe), not in the `openrewrite/rewrite` core repository. Therefore `rewrite-bom` cannot manage its version. The source-profile validator treats this as a separate-source coordinate only with the explicit version `3.2.1`; the source-build manifest records the resolved upstream commit. It is not silently omitted from the version check.
