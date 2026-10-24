# Spring/Maven guard and OpenRewrite baseline

## Purpose

This design combines a current OpenRewrite execution path with the repository's existing Spring architecture and Maven checks. OpenRewrite is used as a versioned recipe engine for reviewable Java/POM improvements, not as an unsupported claim that one recipe can prove all Spring Boot invariants.

## Modern OpenRewrite, no commercial subscription

- Maven plugin: `org.openrewrite.maven:rewrite-maven-plugin:6.50.0`, pinned to the current version published in the official OpenRewrite module table on 2026-10-24.
- Download source: the official Code Genome Maven repository. Current releases require authentication. A free Code Genome account/download token is sufficient for Apache-licensed recipes; no Moderne commercial subscription is configured or required for this recipe set.
- Authentication: local development uses a `codegenome` server entry in `~/.m2/settings.xml`; CI uses GitHub Actions secrets `CODEGENOME_USERNAME` and `CODEGENOME_TOKEN`. Credentials must never be committed to the repository or embedded in the POM.
- Recipe policy: use only recipes explicitly licensed Apache License 2.0. The current Spring Boot 4 recipe module is classified Moderne Source Available, and the Boot 4 “Community Edition” recipe pages still identify that license. It is therefore intentionally excluded from this no-commercial-subscription recipe set. The project is already on Spring Boot 4.1.2, so the Boot 4 migration umbrella is not an appropriate repeated migration.
- Recipes selected:
  - `org.openrewrite.java.format.AutoFormat` — comprehensive Java formatting.
  - `org.openrewrite.java.RemoveUnusedImports` — safe unused-import cleanup; the official recipe avoids removal where unknown types are present.
  - `org.openrewrite.maven.BestPractices` — official Apache-licensed Maven POM hygiene composite.

Recipe licensing references are authoritative at recipe/module level. A name containing “Community Edition” is not evidence of an Apache license. No `rewrite-spring`, `rewrite-static-analysis`, `rewrite-migrate-java`, or other Source Available/proprietary recipe bundle is pulled into this build profile.

## Execution model

The Maven profile `openrewrite` contains the repositories, plugin version, and recipe configuration. Normal builds are unchanged unless `-Popenrewrite` is specified.

Run locally after setting the free Code Genome credentials in `~/.m2/settings.xml`:

```shell
./mvnw --batch-mode --no-transfer-progress -Popenrewrite rewrite:discover
./mvnw --batch-mode --no-transfer-progress -Popenrewrite rewrite:dryRun
```

The dedicated GitHub Actions workflow scans pull requests that touch Java, Maven, YAML, or properties configuration. It generates recipe reports/patches, verifies that tracked source files remain untouched, and uploads the output as an artifact. On a PR, it visibly skips recipe execution if the Code Genome secrets are absent; manually dispatching the workflow without credentials fails with an actionable message. Once the free account credentials are set, each matching PR runs the full scan automatically.

`rewrite:dryRun` is deliberate: the official plugin documents it as a non-mutating preview. `rewrite:run` writes source files, so it must be run only on a dedicated migration branch after the patch has been reviewed:

```shell
./mvnw --batch-mode --no-transfer-progress -Popenrewrite rewrite:run
git diff
```

The CI baseline initially does not fail just because existing style/POM suggestions are present. Findings are attached as reports for review before the recipe set is promoted to a required, fail-on-diff gate. Invalid recipe configuration still fails, and the workflow fails if a supposedly non-mutating dry run changes tracked files. This avoids either silently pretending the baseline is clean or breaking every PR on pre-existing suggestions without a measured baseline.

## Existing guard layers retained

The required CI workflow continues to run the policy-alignment script and existing build/test aggregate. Maven Enforcer independently enforces Java `[25,26)`, Maven `[3.9.16,)`, dependency convergence, upper-bound dependency versions, and duplicate dependency declaration rules. ArchUnit rules and Spring Modulith verification remain the source of truth for the repository's actual module boundaries; the existing readiness test fails if those checks would be silently skipped on an unsupported JDK. Surefire, Failsafe, JaCoCo (70% instruction threshold), CodeQL, container scanning, and secret scanning remain in their current CI paths.

The policy-alignment script also checks that the OpenRewrite profile, Apache-only recipe manifest, and report workflow have not been accidentally removed or changed to a different plugin baseline.

## Official references

- OpenRewrite latest versions and licenses: https://docs.openrewrite.org/reference/latest-versions-of-every-openrewrite-module
- OpenRewrite Maven plugin, goals, `configLocation`, reports, and `dryRun`: https://docs.openrewrite.org/reference/rewrite-maven-plugin
- Official free-account access model and Maven repository setup: https://docs.openrewrite.org/running-recipes/getting-started
- Apache-licensed Java formatting recipe: https://docs.openrewrite.org/recipes/java/format/autoformat
- Apache-licensed unused-import recipe: https://docs.openrewrite.org/recipes/java/removeunusedimports
- Apache-licensed Maven best-practices recipe: https://docs.openrewrite.org/recipes/maven/bestpractices
- OpenRewrite licensing distinctions: https://docs.openrewrite.org/licensing/openrewrite-licensing
- Spring Modulith verification: https://docs.spring.io/spring-modulith/reference/verification.html
- Maven Enforcer dependency convergence: https://maven.apache.org/enforcer/enforcer-rules/dependencyConvergence.html
