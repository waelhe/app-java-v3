# Spring and Maven framework guard

**Purpose:** keep changes aligned with the project's declared Spring Boot/Maven baseline and ensure the existing architecture and test guards remain present in the required CI path. This guard uses the framework, Maven plugins, and test libraries already in the project; it introduces no commercial service, paid recipe pack, or runtime dependency.

## Why this is the chosen no-subscription design

The current OpenRewrite Spring Boot 4 recipe catalog contains migrations whose artifact access and licenses differ by recipe. The official quickstart says Code Genome authentication is required for current artifacts and that source-available/proprietary recipe downloads require a Moderne subscription. Since the project has explicitly rejected a commercial subscription, this PR removes the previous OpenRewrite pilot rather than presenting an older, narrow recipe as current, complete Spring compliance.

A formatting or migration recipe is not a complete framework guard. The repository already has more direct enforcement layers: Maven Enforcer, Spring Modulith module verification, ArchUnit rules, Surefire/Failsafe test execution, JaCoCo coverage, and the existing required CI workflow.

## Enforced layers

| Layer | Enforcer / test | What it actually guarantees |
|---|---|---|
| Toolchain | Maven Enforcer | Maven is at least 3.9.16 and the build JDK is exactly 25; it now agrees with the wrapper and current version policy |
| Dependency graph | Maven Enforcer | dependency convergence, upper-bound dependency versions, and no duplicate dependency declarations |
| Module architecture | ArchUnit `ArchitectureRulesTest` | repository-defined package/module rules and cycle rule |
| Spring application modules | Spring Modulith `ModulithVerificationTest` | no module cycles and no forbidden access to module internals under the declared module model |
| Guard readiness | `ModulithGuardReadinessTest` | fails instead of allowing the architectural verification to silently disappear on an unsupported JDK |
| Quality and behavior | Surefire, Failsafe, JaCoCo | unit/integration behavior and the existing 70% instruction-coverage gate |
| Security/supply chain | existing CI workflows | CodeQL, container scanning, secret scanning, and other already configured gates |

## Policy drift prevention

`scripts/verify-framework-guard-policy.py` checks that the root POM, Maven Wrapper, version policy, CI JDK, Enforcer rules, and core architecture test files still agree. The required CI workflow invokes it in the build job before the Maven reactor installation, so a mismatch fails the same aggregate Build & Test gate already required by branch protection. The script uses only the Python standard library and has no external account or paid service prerequisite.

The exact Java range is `[25,26)` because the existing Spring Modulith/ArchUnit pairing explicitly rejects silent verification on JDK 26+, and the approved project baseline is Java 25. A future toolchain upgrade must first update the Boot/Modulith/ArchUnit compatibility and remove the existing readiness skip deliberately; relaxing this range by itself is not sufficient.

## Operating protocol

1. Run the required CI gates for every pull request; do not replace them with a recipe report.
2. Keep framework and dependency versions managed by the Spring Boot parent/BOM where it manages them. Any exception must document the official compatibility or vulnerability evidence in the POM and version policy.
3. Add or change ArchUnit and Modulith constraints only when the intended architecture is explicit and current code is measured against the rule.
4. Apply automated source migrations only in a dedicated reviewed PR after identifying the exact recipe, version, license, supported framework range, and expected diff. Never run source-mutating transformations automatically as a side effect of an ordinary code change.
5. Revisit the pinned framework baseline through a separate version-policy PR and verify official Spring release notes, Maven compatibility, CI, and the full existing test suite.

## Official references

- Spring Boot reference and testing: https://docs.spring.io/spring-boot/reference/
- Spring Modulith module verification: https://docs.spring.io/spring-modulith/reference/verification.html
- Apache Maven Enforcer dependency convergence: https://maven.apache.org/enforcer/enforcer-rules/dependencyConvergence.html
- OpenRewrite current artifact access and recipe licensing: https://docs.openrewrite.org/running-recipes/getting-started and https://docs.openrewrite.org/licensing/openrewrite-licensing
