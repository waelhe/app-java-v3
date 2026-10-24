# OpenRewrite catalog and licence coverage

**Verified against the official OpenRewrite catalog on 2026-10-24.** This file is a versioned inventory, not a claim that every recipe has been executed against this repository.

## Current pinned toolchain

| Component | Version | Upstream license / access | Platform use |
|---|---:|---|---|
| `org.openrewrite.maven:rewrite-maven-plugin` | `6.50.0` | Apache-2.0; artifact repository authentication required | Root Maven reactor plugin |
| `org.openrewrite.recipe:rewrite-recipe-bom` | `3.41.0` | Apache-2.0 | Aligns recipe/module versions instead of hand-pinning each artifact |
| `org.openrewrite.recipe:rewrite-java-dependencies` | BOM-managed; catalog `1.64.1` | Apache-2.0 | Maven dependency alignment and change recipes |
| `org.openrewrite.recipe:rewrite-jackson` | BOM-managed; catalog `1.31.1` | Apache-2.0 | Jackson migrations relevant to the Java backend |
| `org.openrewrite.recipe:rewrite-netty` | BOM-managed; catalog `0.13.2` | Apache-2.0 | Netty dependency migrations when applicable |
| `org.openrewrite.recipe:rewrite-openapi` | BOM-managed; catalog `0.35.0` | Apache-2.0 | OpenAPI contract/tooling migrations |
| `org.openrewrite.recipe:rewrite-third-party` | BOM-managed; catalog `0.49.0` | Apache-2.0 | Third-party maintained migration recipes |
| `org.openrewrite.recipe:rewrite-all` | BOM-managed; catalog `1.30.2` | Apache-2.0 artifact; brings a broad set of language modules | Optional `openrewrite-all-catalog` profile; excludes C#, JavaScript, Python and mainframe parser modules because they are outside the Java/Maven backend scope and some are Moderne Source Available |
| `org.openrewrite.recipe:rewrite-spring` | `6.41.0` | Moderne Source Available; precompiled access is subscription-gated | Not fetched as a subscription binary. The source-build route is pinned in `bash scripts/openrewrite/build-spring-recipes.sh` and installed to the local Maven repository when source-build dependencies are accessible |

The versions shown as “catalog” values come from the official [latest-module list](https://docs.openrewrite.org/reference/latest-versions-of-every-openrewrite-module). In the POM, BOM-managed recipe dependencies deliberately omit their own `<version>` element.

## Profiles

- `mvn -Popenrewrite org.openrewrite.maven:rewrite-maven-plugin:6.50.0:discover -Ddetail=true` resolves the baseline Apache-licensed Java/Maven recipe set and prints recipe availability.
- `mvn -Popenrewrite org.openrewrite.maven:rewrite-maven-plugin:6.50.0:dryRunNoFork` generates a dry-run diff. The first rollout is report-only because existing repository diffs have not yet been measured and remediated; the workflow uploads the diff as an artifact. Do not describe this as a blocking source-conformance gate until that baseline is clean and `failOnDryRunResults` is enabled.
- `mvn -Popenrewrite,openrewrite-all-catalog org.openrewrite.maven:rewrite-maven-plugin:6.50.0:discover -Ddetail=true` adds the optional `rewrite-all` recipe aggregator with out-of-scope source-available language parsers excluded.
- Run `bash scripts/openrewrite/build-spring-recipes.sh` first, then use `mvn -Popenrewrite,openrewrite-spring-source org.openrewrite.maven:rewrite-maven-plugin:6.50.0:discover -Ddetail=true` to probe the source-built Spring recipes.

## Authentication and no-subscription boundary

OpenRewrite's current docs say artifact downloads from the Code Genome Project require authenticated repository access, and access to precompiled source-available recipes requires a Moderne subscription. The workflow therefore reads `CODEGENOME_USERNAME` and `CODEGENOME_TOKEN` from GitHub Actions secrets; credentials are never committed to the POM. The baseline profile contains Apache-licensed modules. The Spring profile is intentionally built from the pinned public source tag rather than silently attempting a subscription-only binary download.

The Spring source build is pinned to tag `v6.41.0`, commit `bb5d5af152d1f92c3cc49f8a55ddbaf2b35247fc`. Upstream's Gradle build uses `latest.release` for some build plugins and may require authenticated access to OpenRewrite build artifacts; therefore, this path must be proven by the `OpenRewrite source-build probe` workflow, not assumed from the existence of the source repository alone.

## What this does and does not claim

Implemented: BOM alignment; profile-isolated dependencies; Apache-licensed Java/Maven recipe coverage; explicit inventory and licence boundaries; discovery/dry-run reporting; a pinned source-build probe for Spring recipes.

Not yet proven: successful artifact resolution using this repository's actual GitHub secrets; clean dry-run baseline; full Spring source-build success in CI; or a blocking dry-run gate. The workflow is fail-closed for missing repository credentials and publishes dry-run artifacts, but remains report-only for code diffs until the baseline is reviewed. OpenRewrite recipe availability is not equivalent to automatic execution: recipes run only when explicitly activated.
