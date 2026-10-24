# OpenRewrite pilot

**Status:** Experimental, non-blocking, non-mutating pilot. This is not yet an approved compliance rule.

## Why this configuration

- The repository is a Java 25, Maven multi-module Spring Boot application. OpenRewrite's Maven plugin is a credible tool for large-scale, recipe-based refactoring, and its official plugin supports `discover`, `dryRun`, and `run`.
- The current OpenRewrite release stream is published through the authenticated Code Genome Project repository. The most recent version listed on Maven Central in this assessment is `org.openrewrite.maven:rewrite-maven-plugin:6.46.1`; that version is Apache-2.0 licensed and includes Java 25 parser support in its published dependency set.
- To keep this first experiment reproducible without introducing new repository credentials, it uses the Maven-Central-hosted plugin and its built-in `org.openrewrite.java.OrderImports` recipe. No separate recipe artifact, Code Genome repository, or secret is required for this pilot.
- The recipe is a mechanics/baseline test: it identifies import-order changes. It does **not** prove Spring Boot, Spring Framework, Spring Security, or architecture compliance. Do not mislabel its output as a complete framework guard.

## How to run

Locally, with JDK 25 and the repository's Maven Wrapper:

```shell
./mvnw --batch-mode -Popenrewrite-pilot -Drewrite.failOnInvalidActiveRecipes=true rewrite:discover
./mvnw --batch-mode -Popenrewrite-pilot -Drewrite.failOnInvalidActiveRecipes=true rewrite:dryRun
```

The dedicated GitHub Actions workflow runs on manual dispatch and on PRs that change Java, Maven POM, YAML workflow, or this pilot policy. It uploads dry-run reports when produced. The workflow is intentionally not a required branch-protection status and it does not change files.

## Why not run `rewrite:run` automatically?

The official plugin documents `rewrite:run` as a source-mutating goal and `rewrite:dryRun` as a report-producing, non-mutating preview. The pilot must first establish the baseline and allow reviewers to inspect diffs. Only after a recipe is explicitly approved should a separate migration PR apply it and run the complete Maven build, integration tests, architecture checks, and API compatibility gates.

## Spring-specific recipes: separate decision required

OpenRewrite's catalog documents recipes such as `org.openrewrite.java.spring.boot4.SpringBootProperties_4_1`. However, current OpenRewrite documentation says its newer artifacts are distributed through Code Genome, which requires authentication, and that source-available recipes may require a Moderne subscription. This repository has not yet configured or verified Code Genome credentials and license eligibility. Therefore, this pilot does not silently introduce a proprietary/source-available Spring recipe.

Before adopting a Spring-specific recipe:
1. Confirm the exact recipe and version in the official recipe catalog and source repository.
2. Verify its license and required account entitlement.
3. Provide Code Genome credentials via GitHub Actions secrets and Maven `settings.xml` generation; never store credentials in the POM or repository.
4. Run the recipe in dry-run mode on a separate branch and review the generated patch.
5. Apply accepted changes in a separate PR and require the full existing CI suite.

## Acceptance criteria for this pilot

- `rewrite:discover` resolves and reports `org.openrewrite.java.OrderImports`.
- `rewrite:dryRun` runs across the Maven reactor and produces a report or a clean no-change result.
- No tracked source/build files are changed by the dry-run workflow.
- Reports are uploaded for reviewer inspection.
- The pilot does not block normal builds or merges until its baseline and future policy rules are explicitly approved.

## Official references

- Maven plugin and goal semantics: https://docs.openrewrite.org/reference/rewrite-maven-plugin
- Official quickstart and repository/authentication model: https://docs.openrewrite.org/running-recipes/getting-started
- Maven Central-hosted plugin metadata: https://central.sonatype.com/artifact/org.openrewrite.maven/rewrite-maven-plugin
- Spring Boot 4.x recipe catalog: https://docs.openrewrite.org/recipes/java/spring/boot4
- OpenRewrite Maven plugin source: https://github.com/openrewrite/rewrite-maven-plugin
