# OpenRewrite integration and Spring/Maven guard

## Purpose and status

This is the current OpenRewrite integration for the Java 25 / Spring Boot 4.1.x / Maven multi-module platform. It is a recipe engine plus CI execution, reports, explicit licensing boundaries, and the existing framework/architecture gates. It is not represented as universal Spring compliance: the free recipe catalog and paid/source-available Spring catalog are different scopes.

## Current engine and free recipe modules

The versions below were checked against the official OpenRewrite module/version and license table on 2026-10-24.

| Maven artifact | Version | Official license | Purpose in this repository |
|---|---:|---|---|
| `org.openrewrite.maven:rewrite-maven-plugin` | `6.50.0` | Apache 2.0 | Maven reactor integration, discovery, dry-run, recipe reports |
| `org.openrewrite.recipe:rewrite-java-dependencies` | `1.64.1` | Apache 2.0 | Dependency inventory and resolution diagnostics |
| `org.openrewrite.recipe:rewrite-jackson` | `1.31.1` | Apache 2.0 | Jackson recipe catalog, available for targeted reviewed migrations |
| `org.openrewrite.recipe:rewrite-netty` | `0.13.2` | Apache 2.0 | Netty recipe catalog, available for targeted reviewed migrations |
| `org.openrewrite.recipe:rewrite-openapi` | `0.35.0` | Apache 2.0 | OpenAPI recipe catalog, available for targeted reviewed migrations |
| `org.openrewrite:rewrite-docker` | `8.93.11` | Apache 2.0 | Docker recipe catalog for container build files |

The active, non-mutating baseline comprises:

- `org.openrewrite.java.format.AutoFormat`
- `org.openrewrite.java.RemoveUnusedImports`
- `org.openrewrite.maven.BestPractices`
- `org.openrewrite.java.dependencies.DependencyList` with transitive dependencies included
- `org.openrewrite.java.dependencies.DependencyResolutionDiagnostic`

The first three generate reviewable Java/Maven suggestions. The dependency recipes add a transitive dependency inventory and repository/resolution diagnostics to the report set. These recipe IDs and licenses are documented by the official OpenRewrite catalog.

The current `rewrite-spring:6.41.0` module is listed under the Moderne Source Available License, as are some other recipe modules. The authenticated Code Genome account makes Apache-licensed modules downloadable without a commercial subscription; it does not grant access to source-available or proprietary recipe binaries. This integration therefore does **not** add `rewrite-spring`, `rewrite-static-analysis`, `rewrite-migrate-java`, `rewrite-hibernate`, or other source-available/proprietary modules. It does not claim Spring Boot 4-specific recipe coverage that is not in this free artifact set.

## Credentials

The current release artifacts are hosted by the official Code Genome Maven repository and require authentication. The repository profile uses the matching server id `codegenome`.

- GitHub Actions secret `CODEGENOME_USERNAME`: the Code Genome sign-in email or username.
- GitHub Actions secret `CODEGENOME_TOKEN`: the Code Genome download token.
- CI writes these values to a temporary, permission-restricted `~/.m2/settings.xml`; the token is not printed, committed, embedded in the POM, or uploaded as an artifact.
- If either secret is missing, the workflow fails clearly. It must never return green while silently skipping the entire OpenRewrite run.

Local development uses the same `codegenome` server entry in the user's `~/.m2/settings.xml`. Keep Maven Central configured for the project's dependencies; only OpenRewrite/MODerne artifacts are hosted at Code Genome.

## How to run

From the repository root using JDK 25 and the Maven Wrapper:

```shell
./mvnw --batch-mode --no-transfer-progress -Popenrewrite rewrite:discover
./mvnw --batch-mode --no-transfer-progress -Popenrewrite rewrite:dryRun
```

The dedicated GitHub Actions workflow runs on manual dispatch and on relevant pull requests. It:

1. Fails clearly if authentication secrets are not available.
2. Sets up JDK 25 and writes temporary Maven credentials without printing them.
3. Discovers the configured recipe catalog and fails on invalid recipe IDs.
4. Runs `rewrite:dryRun` on the multi-module reactor.
5. Verifies that tracked source/build files remain unchanged.
6. Uploads generated reports and patches as a 14-day artifact for review.

The workflow is a reporting gate, not automatic source mutation. The baseline is initially non-blocking with respect to pre-existing recipe findings (`failOnDryRunResults=false`) so it can establish and review the actual diff rather than breaking the project on an unmeasured first run. Missing credentials, failed authentication, unresolved plugin/recipe artifacts, invalid recipes, execution errors, and tracked-file mutations do **not** count as a successful OpenRewrite run.

Only after reviewing the first real artifact should a separate migration PR apply a chosen patch using `rewrite:run`, then execute the existing Maven build, tests, architecture checks, API compatibility gates, and security scans.

## Existing framework/Maven guards kept in force

OpenRewrite supplements—does not replace—current enforcement:

- Maven Enforcer: exact Java 25 range `[25,26)`; Maven `[3.9.16,)`; dependency convergence; upper-bound dependencies; duplicate dependency declaration checks.
- ArchUnit rules and Spring Modulith verification for actual project module boundaries.
- Guard readiness test to fail rather than silently skip Modulith verification on an unsupported JDK.
- Surefire, Failsafe, JaCoCo 70% instruction coverage, CodeQL, container scanning, and secret scanning in the existing CI workflows.

The policy script checks the plugin version, allowlisted Apache module coordinates and versions, active baseline recipes, credential-backed workflow behavior, and the core architecture guards for drift.

## Official references

- Official latest module versions/licenses: https://docs.openrewrite.org/reference/latest-versions-of-every-openrewrite-module
- Maven plugin quickstart, Code Genome credentials, and recipe access model: https://docs.openrewrite.org/running-recipes/getting-started
- OpenRewrite licensing distinctions: https://docs.openrewrite.org/licensing/openrewrite-licensing
- Java formatting recipe: https://docs.openrewrite.org/recipes/java/format/autoformat
- Remove unused imports: https://docs.openrewrite.org/recipes/java/removeunusedimports
- Maven best practices: https://docs.openrewrite.org/recipes/maven/bestpractices
- Dependency inventory: https://docs.openrewrite.org/recipes/java/dependencies/dependencylist
- Dependency resolution diagnostics: https://docs.openrewrite.org/recipes/java/dependencies/dependencyresolutiondiagnostic
- Jackson best practices and the current Apache module: https://docs.openrewrite.org/recipes/java/jackson/jacksonbestpractices
- Spring Modulith verification: https://docs.spring.io/spring-modulith/reference/verification.html
- Maven Enforcer convergence: https://maven.apache.org/enforcer/enforcer-rules/dependencyConvergence.html
