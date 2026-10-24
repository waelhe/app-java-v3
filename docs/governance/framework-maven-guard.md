# OpenRewrite integration and Spring/Maven guard

## Purpose and contract

This uses the current OpenRewrite Maven plugin in the Java 25 / Spring Boot 4.1.x reactor. The default run is a read-only dependency inventory and resolution diagnostic. Source/POM change suggestions are available in a manually selected recommendations mode. This separation is based on measured output: the first actual run generated a 2.36 MB patch affecting 1,000 unique files, largely because generic AutoFormat did not match the project's current formatting style.

## Current engine and official version alignment

The official OpenRewrite module/version table checked on 2026-10-24 lists `rewrite-maven-plugin:6.50.0` and `rewrite-recipe-bom:3.41.0`. The selected Apache-licensed artifacts are pinned as follows:

| Maven artifact | Version | Purpose |
|---|---:|---|
| `org.openrewrite.recipe:rewrite-java-dependencies` | `1.64.1` | Dependency inventory and resolution diagnostics |
| `org.openrewrite.recipe:rewrite-jackson` | `1.31.1` | Jackson recipes |
| `org.openrewrite.recipe:rewrite-netty` | `0.13.2` | Netty recipes |
| `org.openrewrite.recipe:rewrite-openapi` | `0.35.0` | OpenAPI recipes |
| `org.openrewrite:rewrite-docker` | `8.93.11` | Docker/container files |

The official Quickstart explicitly says Maven does not apply BOM management to plugin versions or plugin dependencies. The BOM repository README shows the Maven import pattern for normal project dependencies, but plugin-scoped recipe dependencies are different. Therefore each recipe module is pinned manually to the matching versions from official BOM release `3.41.0`; this is intentional and documented.

## Free account, observed artifact entitlements, and license boundary

Code Genome requires authentication for downloading current artifacts. The workflow reads `CODEGENOME_USERNAME` and `CODEGENOME_TOKEN` from GitHub Actions secrets and writes a temporary, permission-restricted Maven `settings.xml`. Secrets are not committed, printed, or uploaded.

**Observed failure that changed the design:** run [40057579728](https://github.com/waelhe/app-java-v3/actions/runs/40057579728) failed in `rewrite:discover` with HTTP 403 for transitive artifacts including `rewrite-python`, `rewrite-mainframe`, `rewrite-csharp`, `rewrite-javascript`, `rewrite-migrate-java`, `rewrite-github-actions`, `rewrite-static-analysis`, and `rewrite-joda`. The top-level `rewrite-all` and `rewrite-third-party` artifacts are listed as Apache-licensed, but their resolved dependency graph attempted to download artifacts this free account could not access. They have therefore been removed from the executable profile rather than leaving a known-red workflow or claiming that the aggregate is usable.

The current profile uses explicit Apache-licensed recipe packs only. This intentionally limits breadth compared with the catalog's aggregate packs; the exact account entitlement and dependency graph, not the top-level artifact license label alone, determine what actually executes in this environment.

`rewrite-spring:6.41.0` is listed as Moderne Source Available. The official license permits an organization to apply such recipes to its own code and links to build-from-source instructions; the precompiled artifact still requires the right Code Genome entitlement. The official `rewrite-spring` source declares runtime dependencies on additional source-available packs, including `rewrite-static-analysis`, `rewrite-apache`, and `rewrite-hibernate`. **A source-built Spring dependency closure is not yet integrated or verified in this PR**; adding only `rewrite-spring` would reproduce the same dependency-resolution failure.

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
