# OpenRewrite integration and Spring/Maven guard

## Purpose and contract

This uses the current OpenRewrite Maven plugin in the Java 25 / Spring Boot 4.1.x reactor. The default run is a read-only dependency inventory and resolution diagnostic. Source/POM change suggestions are available in a manually selected recommendations mode. This separation is based on measured output: the first actual run generated a 2.36 MB patch affecting 1,000 unique files, largely because generic AutoFormat did not match the project's current formatting style.

## Current engine and official version alignment

The official OpenRewrite module/version table checked on 2026-10-24 lists `rewrite-maven-plugin:6.50.0` and `rewrite-recipe-bom:3.41.0`. The selected Apache-licensed artifacts are pinned as follows:

| Maven artifact | Version | Purpose |
|---|---:|---|
| `org.openrewrite.recipe:rewrite-all` | `1.30.2` | Broad language/recipe catalog |
| `org.openrewrite.recipe:rewrite-third-party` | `0.49.0` | Community-contributed Apache-licensed recipes |
| `org.openrewrite.recipe:rewrite-java-dependencies` | `1.64.1` | Dependency inventory and resolution diagnostics |
| `org.openrewrite.recipe:rewrite-jackson` | `1.31.1` | Jackson recipes |
| `org.openrewrite.recipe:rewrite-netty` | `0.13.2` | Netty recipes |
| `org.openrewrite.recipe:rewrite-openapi` | `0.35.0` | OpenAPI recipes |
| `org.openrewrite:rewrite-docker` | `8.93.11` | Docker/container files |

The official Quickstart explicitly says Maven does not apply BOM management to plugin versions or plugin dependencies. The BOM repository README shows the Maven import pattern for normal project dependencies, but plugin-scoped recipe dependencies are different. Therefore each recipe module is pinned manually to the matching versions from official BOM release `3.41.0`; this is intentional and documented.

## Free account and licensing

Code Genome requires authentication for downloading current artifacts. The workflow reads `CODEGENOME_USERNAME` and `CODEGENOME_TOKEN` from GitHub Actions secrets and writes a temporary, permission-restricted Maven `settings.xml`. Secrets are not committed, printed, or uploaded.

Licensing is module-specific. The official table marks `rewrite-spring:6.41.0`, `rewrite-static-analysis`, `rewrite-migrate-java`, and other modules as Moderne Source Available. These are not direct dependencies here. A free authenticated account does not turn Source Available/Proprietary licenses into Apache 2.0.

## Two run modes

### `inventory` — automatic for relevant pull requests

The default recipe `com.marketplace.governance.OpenRewriteBaseline` runs:
- `org.openrewrite.java.dependencies.DependencyList` with `scope=TestRuntime`, `includeTransitive=true`, and `validateResolvable=true`;
- `org.openrewrite.java.dependencies.DependencyResolutionDiagnostic`.

The official DependencyList docs say it produces a data report without changing source; `validateResolvable=true` attempts to resolve dependency coordinates and reports failures. The generated summary is shown in the GitHub Actions Summary panel and included in the downloadable artifact.

### `recommendations` — manual dispatch

Select **Actions → OpenRewrite inventory and recommendations → Run workflow → recommendations** to preview:
- `org.openrewrite.java.format.AutoFormat`;
- `org.openrewrite.java.RemoveUnusedImports`;
- `org.openrewrite.maven.BestPractices`.

The first run found 900 AutoFormat result rows and changes in 1,000 unique files. It also revealed formatting drift in multi-line Java records. Consequently these suggestions remain review-only, never auto-applied, until a project style is explicitly established and the diff is accepted.

## Report and failure behavior

Each run checks the two secrets and fails if either is absent; creates temporary Maven credentials; discovers recipes; executes the selected dry run; generates `openrewrite-summary.md`; renders the summary in GitHub Actions; uploads the report, recipe discovery output, tables, and patches for 14 days; and asserts that tracked source/build files remain unchanged.

Green means the selected recipes executed and produced a valid report; it does not claim full Spring Boot compliance. The inventory isn't a formatting gate. Promote rules to blocking only after reviewing their actual coverage and the repository's existing build/tests/API/architecture/security gates.

## Official references

- [Latest module versions and licenses](https://docs.openrewrite.org/reference/latest-versions-of-every-openrewrite-module)
- [Maven quickstart, Code Genome access, and BOM limitations](https://docs.openrewrite.org/running-recipes/getting-started)
- [Official recipe BOM repository/readme](https://github.com/openrewrite/rewrite-recipe-bom)
- [Maven plugin configuration and dryRun/discover semantics](https://docs.openrewrite.org/reference/rewrite-maven-plugin)
- [DependencyList and validateResolvable](https://docs.openrewrite.org/recipes/java/dependencies/dependencylist)
- [Apache Maven best practices](https://docs.openrewrite.org/recipes/maven/bestpractices)
- [OpenRewrite licensing](https://docs.openrewrite.org/licensing/openrewrite-licensing)
