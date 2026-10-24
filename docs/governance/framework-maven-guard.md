# OpenRewrite integration and Spring/Maven guard

## Purpose and contract

This uses the current OpenRewrite Maven plugin in the Java 25 / Spring Boot 4.1.x reactor. The default run is a read-only dependency inventory and resolution diagnostic. Source/POM change suggestions are available in a manually selected recommendations mode. This separation is based on measured output: the first actual run generated a 2.36 MB patch affecting 1,000 unique files, largely because generic AutoFormat did not match the project's current formatting style.

## Current engine and official version alignment

The official OpenRewrite module/version table checked on 2026-10-24 lists `rewrite-maven-plugin:6.50.0` and `rewrite-recipe-bom:3.41.0`. The selected Apache-licensed artifacts are pinned as follows:

| Maven artifact | Version | Purpose |
|---|---:|---|
| `org.openrewrite.recipe:rewrite-java-dependencies` | `1.64.0` | Dependency inventory and resolution diagnostics |
| `org.openrewrite.recipe:rewrite-jackson` | `1.31.0` | Jackson recipes |
| `org.openrewrite.recipe:rewrite-netty` | `0.13.2` | Netty recipes |
| `org.openrewrite.recipe:rewrite-openapi` | `0.35.0` | OpenAPI recipes |
| `org.openrewrite:rewrite-docker` | `8.93.0` | Docker/container files, BOM-aligned to plugin runtime |

The project imports both official BOMs in the OpenRewrite Maven profile: org.openrewrite:rewrite-bom:8.93.11 for engine/parser modules, and org.openrewrite.recipe:rewrite-recipe-bom:3.41.0 for recipe packs. The official Maven Quickstart says project dependencyManagement does not manage Maven plugin-scoped dependencies, so the plugin's recipe versions remain explicit. CI generates Maven's effective POM and compares these explicit coordinates to the versions managed by the imported BOMs.

## Free account, observed artifact entitlements, and license boundary

Code Genome requires authentication for downloading current artifacts. The workflow reads `CODEGENOME_USERNAME` and `CODEGENOME_TOKEN` from GitHub Actions secrets and writes a temporary, permission-restricted Maven `settings.xml`. Secrets are not committed, printed, or uploaded.

**Observed failure that changed the design:** run [40057579728](https://github.com/waelhe/app-java-v3/actions/runs/40057579728) failed in `rewrite:discover` with HTTP 403 for transitive artifacts including `rewrite-python`, `rewrite-mainframe`, `rewrite-csharp`, `rewrite-javascript`, `rewrite-migrate-java`, `rewrite-github-actions`, `rewrite-static-analysis`, and `rewrite-joda`. The top-level `rewrite-all` and `rewrite-third-party` artifacts are listed as Apache-licensed, but their resolved dependency graph attempted to download artifacts this free account could not access. They have therefore been removed from the executable profile rather than leaving a known-red workflow or claiming that the aggregate is usable.

The default `openrewrite` profile uses explicit Apache-licensed recipe packs. A separate opt-in `openrewrite-source-available` profile is now provided for a CI job that builds the Source Available dependency closure from pinned upstream tags and installs it only in that runner's local Maven repository. It does not rely on the free account downloading precompiled Source Available jars.

`rewrite-spring:6.41.0` is listed as Moderne Source Available. The official license permits an organization to apply such recipes to its own code and links to build-from-source instructions; the precompiled artifact still requires the right Code Genome entitlement. The official `rewrite-spring` source declares runtime dependencies on additional source-available packs, including `rewrite-static-analysis`, `rewrite-apache`, and `rewrite-hibernate`. **The source-built profile and workflow are wired in, but the full source-build job must pass before claiming this closure is usable.** It builds core parsers, `rewrite-mainframe`, the Spring/Java migration and static-analysis closure, the `rewrite-okhttp` recipes used by the repository's Android app, and the feature-flags catalog for review against the platform's custom flag model, then attempts the two Apache-licensed aggregate packs. It records upstream tags and commit SHAs in an artifact manifest and previews the verified Community Edition `org.openrewrite.java.spring.boot4.SpringBootProperties_4_1` recipe. If any source dependency remains unavailable, the job should fail visibly; no precompiled Source Available jar is copied into the app or uploaded as an artifact.

## Three execution paths

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

### Source-built catalog — separate manual/PR workflow

The separate `OpenRewrite source-built catalog` workflow uses pinned upstream tags and installs built jars only in the ephemeral runner's local Maven repository. Its Gradle builds receive `ORG_GRADLE_PROJECT_codegenomeUsername` and `ORG_GRADLE_PROJECT_codegenomePassword`, which the upstream Code Genome repository plugin explicitly supports, in addition to the Maven `settings.xml` used by the platform reactor. It runs the opt-in `com.marketplace.governance.OpenRewriteSpringRecommendations` composite, currently previewing the verified Community Edition `org.openrewrite.java.spring.boot4.SpringBootProperties_4_1` recipe relevant to a Spring Boot 4.1 platform. Discovery and `rewrite:dryRun` must succeed before this source-built path is considered usable; changes are never auto-applied.

## Report and failure behavior

Each run checks the two secrets and fails if either is absent; creates temporary Maven credentials; discovers recipes; executes the selected dry run; generates `openrewrite-summary.md`; renders the summary in GitHub Actions; uploads the report, recipe discovery output, tables, and patches for 14 days; and asserts that tracked source/build files remain unchanged.

Green on the standard workflow means its Apache-only recipes ran and dependency inventory passed resolution validation. Green on the source-built workflow additionally proves its configured source dependency closure compiled and published locally and that the selected Spring Boot 4.1 property recipe was discoverable and previewable. Neither result alone proves exhaustive recipe coverage or complete Spring Boot compliance. The inventory isn't a formatting gate. Promote rules to blocking only after reviewing their actual coverage and the repository's existing build/tests/API/architecture/security gates.

## Official references

- [Latest module versions and licenses](https://docs.openrewrite.org/reference/latest-versions-of-every-openrewrite-module)
- [Maven quickstart, Code Genome access, and BOM limitations](https://docs.openrewrite.org/running-recipes/getting-started)
- [Official recipe BOM repository/readme](https://github.com/openrewrite/rewrite-recipe-bom)
- [Maven plugin configuration and dryRun/discover semantics](https://docs.openrewrite.org/reference/rewrite-maven-plugin)
- [DependencyList and validateResolvable](https://docs.openrewrite.org/recipes/java/dependencies/dependencylist)
- [Apache Maven best practices](https://docs.openrewrite.org/recipes/maven/bestpractices)
- [OpenRewrite licensing](https://docs.openrewrite.org/licensing/openrewrite-licensing)


## Catalog inventory and audit boundaries

Every standard OpenRewrite run exports target/rewrite/recipe-catalog.csv from the actual rewrite:discover output. The CSV includes each unique discovered recipe ID, display name, probable source module, license attribution, availability, and platform relevance. Module attribution derived from package prefixes is marked as heuristic; unknowns remain flagged for official source/license review. The recipe count alone is not proof of full free-catalog coverage.

The module-level version/license inventory is documented in openrewrite-module-catalog-2026-10-24.md. Both the module inventory and every run's per-recipe CSV are inputs to the remaining full source/license audit.


### Plugin/runtime compatibility decision (2026-10-24)

The upstream source POM of [`rewrite-maven-plugin:6.50.0`](https://github.com/openrewrite/rewrite-maven-plugin/blob/v6.50.0/pom.xml) pins `rewrite.version` to `8.93.0`. The source-build path and Maven profile therefore use the `rewrite-bom:8.93.0` family for the core runtime and use `rewrite-recipe-bom:3.41.0` for independently aligned recipe packs. The module/version table advertises core `8.93.11` as a newer available point release; it is not silently substituted into a runner whose own released POM declares core `8.93.0`.


The only intentional non-BOM coordinate in the source-built plugin closure is `org.openrewrite:rewrite-mainframe:3.2.1`. Its upstream repository is `moderneinc/rewrite-mainframe`, separate from `openrewrite/rewrite`, so the core `rewrite-bom` does not manage that artifact. The validator does not silently skip it: it requires exactly the pinned official source tag `v3.2.1` and the CI source-build manifest records its resolved commit SHA.


### Mainframe source build correction

The official `moderneinc/rewrite-mainframe:v3.2.1` source registers ANTLR grammar generation tasks but does not make the COBOL/JCL/etc generation tasks prerequisites of `compileJava`. The CI source-build script therefore invokes the source repository's generator tasks before `publishToMavenLocal`. This is required to compile the tagged source tree reproducibly; it is not a platform source mutation.
