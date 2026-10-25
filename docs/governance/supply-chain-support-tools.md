# Supply-chain support tools: decisions and operating boundaries

**Assessment date:** 2026-10-24  
**Scope:** OpenRewrite, CycloneDX Maven Plugin, and GitHub Dependency Review only.

This record documents why each tool is adopted or deferred. An SBOM or a passing pull-request review does not prove that the full deployed system is vulnerability-free.

## 1. CycloneDX Maven Plugin — adopt

**Decision:** Pin `org.cyclonedx:cyclonedx-maven-plugin` to `2.9.3` in the root POM and generate one aggregate application SBOM during the root reactor's `verify` phase.

Evidence:
- The root POM defines a Maven multi-module reactor.
- Existing CodeQL, secret scanning, and container-image scanning do not themselves leave a standard inventory of the Maven application's direct and transitive dependencies attached to each CI run.
- The official CycloneDX plugin documents `makeAggregateBom` for multi-module builds, CycloneDX 1.6 support in the 2.9.x line, JSON/XML output, and the Java 25 support fix in release 2.9.2.

Configuration boundary:
- The root reactor emits `target/bom.json` and `target/bom.xml`.
- The BOM is marked as an application and includes compile, provided, runtime, and system scopes; test scope is excluded. It describes application dependencies, not a complete inventory of CI runner software or Maven build-plugin dependencies.
- CI uploads both files as an artifact: 14-day retention for PR runs and 90 days for pushes. The BOM is not committed to source control or attached to the Maven publication.
- Generating an SBOM is inventory/provenance, not vulnerability scanning. The build fails if the expected BOM files are missing.

Official sources:
- https://github.com/CycloneDX/cyclonedx-maven-plugin
- https://github.com/CycloneDX/cyclonedx-maven-plugin/releases

## 2. GitHub Dependency Review Action — adopt for pull requests

**Decision:** Run the official `actions/dependency-review-action` at the verified v5.0.0 commit SHA `a1d282b36b6f3519aa1f3fc636f609c47dddb294` for PRs.

Enforcement:
- Fail the PR on newly introduced High or Critical dependency advisories.
- Check runtime, development, and unknown scopes, rather than silently ignoring unclassified dependency changes.
- Retry dependency-snapshot warnings for up to 120 seconds to reduce false negatives caused by a snapshot arriving after the action starts.
- The job result is included in the existing required `Build & Test (JDK 25)` aggregate status. This makes review failure block the existing required check without adding a separately required ruleset context.
- The action has read-only repository permissions and does not post a PR comment.

License-policy boundary:
- License checking is explicitly disabled for now. The repository does not yet have a confirmed SPDX allowlist/deny policy, so this change does not invent one. License enforcement should be a separate follow-up after the project owner approves a policy and its exceptions.

Limit:
- Dependency Review compares dependencies introduced or changed by a PR. It is not a substitute for the dependency graph, Dependabot alerts, or periodic review of already-present dependencies.

Official sources:
- https://github.com/actions/dependency-review-action
- https://docs.github.com/en/code-security/how-tos/secure-your-supply-chain/manage-your-dependency-security/configure-dependency-review-action

## 3. OpenRewrite — evaluated, defer installation

**Decision:** Do not add the OpenRewrite Maven plugin or recipe dependencies to the build in this change.

Reasons:
- OpenRewrite is a source-transformation tool, not a required per-PR dependency scanner. Its `run` goal changes files; `dryRun` previews changes. No concrete migration/refactoring recipe is required to close the supply-chain gaps addressed above.
- The current official documentation says OpenRewrite plugin and recipe artifacts are moving from Maven Central to the Code Genome Project repository and documents authenticated access. Introducing it now would add repository/credential coupling without an agreed recipe or acceptance test.
- No source transformation is needed to produce the SBOM or review dependency deltas.

Reconsider OpenRewrite when a concrete migration is approved. Select exact recipe coordinates and versions, verify artifact access in CI without committing credentials, run only `dryRun` on a separate branch, review the diff, then apply the recipe in a dedicated PR and rerun the full build. Do not run source-mutating recipes implicitly in normal CI.

Official sources:
- https://docs.openrewrite.org/reference/rewrite-maven-plugin
- https://docs.openrewrite.org/running-recipes/getting-started

## Acceptance checks

1. The root `verify` lifecycle creates both `target/bom.json` and `target/bom.xml`.
2. The CI build uploads those files and fails when either is absent.
3. On PRs, Dependency Review fails on newly introduced High/Critical advisories in the configured scopes, and the existing required aggregate check propagates its failure.
4. Push/manual runs do not attempt a PR dependency diff.
5. CycloneDX version updates are offered in a separate reviewed Dependabot group, not merged automatically.
6. No OpenRewrite plugin, authenticated repository, recipe, or source mutation is introduced by this change.
