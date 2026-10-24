# OpenRewrite pilot

**Status:** Experimental, opt-in, non-blocking, and non-mutating. This pilot is not a complete Spring Boot compliance gate.

## Free, Maven Central-only recipe selection

This pilot deliberately avoids a commercial Moderne subscription and does not configure the authenticated Code Genome repository.

- Maven plugin: `org.openrewrite.maven:rewrite-maven-plugin:6.46.1`, published to Maven Central under Apache License 2.0.
- Spring recipes: `org.openrewrite.recipe:rewrite-spring:5.23.0`, an older release whose Maven Central POM declares Apache License 2.0. The pinned source for `org.openrewrite.java.spring.NoAutowiredOnConstructor` is also Apache-licensed.
- Active recipes: `org.openrewrite.java.OrderImports` and `org.openrewrite.java.spring.NoAutowiredOnConstructor`.

The Spring rule removes redundant `@Autowired` on a bean's only constructor, while retaining cases the recipe explicitly guards (including multiple constructors, Lombok constructor annotations, and `@ConfigurationProperties`). It is a general Spring convention, **not** a Spring Boot 4 migration or a complete framework-conformance rule.

The current Spring recipe stream contains newer recipes distributed via Code Genome and recipes under the Moderne Source Available License. They are intentionally excluded from this no-commercial-subscription pilot. In particular, this pilot does not claim to validate Spring Boot 4 property migrations, Spring Security policy, or all Spring APIs.

## Run

Use the repository's Maven Wrapper with JDK 25:

```shell
./mvnw --batch-mode --no-transfer-progress -Popenrewrite-pilot -Drewrite.failOnInvalidActiveRecipes=true rewrite:discover
./mvnw --batch-mode --no-transfer-progress -Popenrewrite-pilot -Drewrite.failOnInvalidActiveRecipes=true rewrite:dryRun
```

The dedicated GitHub Actions workflow runs on manual dispatch and on relevant pull requests. It asserts tracked files remain unchanged and uploads available reports. It does not run `rewrite:run`, does not write source changes, and is not a required branch-protection check.

## Safety and acceptance criteria

- Maven resolves the pinned plugin and recipe artifacts through Maven Central only; no Code Genome credentials or commercial subscription are expected.
- `rewrite:discover` resolves both active recipe IDs.
- `rewrite:dryRun` completes on the Java 25 Maven reactor and emits reports or a clean no-change result.
- Tracked files remain unchanged.
- Reports are reviewed before deciding whether to fix existing findings or promote any rule into a blocking check.
- A separate migration PR applies any approved changes and runs the repository's regular build, tests, architecture checks, and API compatibility gates.

## Evidence and limits

This repository is a Java 25, Maven multi-module project on Spring Boot 4.1.2. The OpenRewrite dry-run is an additional tool, not a replacement for the Maven Enforcer rules, ArchUnit, Spring Modulith verification, tests, or existing CI security checks.

The Apache-licensed `rewrite-spring:5.23.0` predates Spring Boot 4. It is selected for one general Spring coding convention that remains relevant across versions. We will not represent it as Boot 4-specific coverage. The latest recipe catalog must be assessed recipe-by-recipe for licensing and artifact access before considering a future free or paid option.

## Official references

- Maven Central metadata and Apache license for the plugin: https://central.sonatype.com/artifact/org.openrewrite.maven/rewrite-maven-plugin/6.46.1
- Maven Central metadata and Apache license for `rewrite-spring:5.23.0`: https://central.sonatype.com/artifact/org.openrewrite.recipe/rewrite-spring/5.23.0/jar
- Pinned Apache-licensed Spring recipe source: https://github.com/openrewrite/rewrite-spring/blob/v5.23.0/src/main/java/org/openrewrite/java/spring/NoAutowiredOnConstructor.java
- OpenRewrite licensing distinctions: https://docs.openrewrite.org/licensing/openrewrite-licensing
- OpenRewrite Maven plugin reference and goal semantics: https://docs.openrewrite.org/reference/rewrite-maven-plugin
