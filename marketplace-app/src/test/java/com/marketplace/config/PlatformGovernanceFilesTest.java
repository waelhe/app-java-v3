package com.marketplace.config;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate test for the platform-governance layer: it pins the repository's
 * governance files — the exact files the GitHub ruleset on {@code main}
 * (id 22305466, created 2026-09-05) and the release pipeline depend on.
 * Same class of latent "config that lies" defect as
 * {@code CacheTtlConfigTest}: nothing at runtime rejects a silently-widened
 * Dependabot allow-list, a CodeQL workflow that drifts to autobuild, or an
 * OpenAPI gate step that loses its service credentials — only a pinned test
 * keeps them honest.
 *
 * <p>What each pin guards:
 * <ul>
 *   <li><b>dependabot.yml</b> — the allow-list is exactly the two BOM
 *       governors ({@code org.springframework.boot*} /
 *       {@code org.springframework.modulith*}). Widening it to any other
 *       dependency would auto-bump hand-audited overrides in the root pom
 *       (jackson, prometheus, commons-*, resilience4j, springdoc…) whose
 *       versions satisfy the enforcer gates (dependencyConvergence +
 *       requireUpperBoundDeps) — Dependabot PRs would break the build, not
 *       improve security.</li>
 *   <li><b>codeql.yml</b> — Java 25 + {@code build-mode: manual}: the JDK
 *       that compiles is the JDK the extractor sees (setup-java runs first),
 *       and the manual Maven build compiles MapStruct-generated sources the
 *       buildless mode would miss.</li>
 *   <li><b>ci.yml OpenAPI gate step</b> — the gate boots the built jar with
 *       the default profile to fetch {@code /v3/api-docs}; the base
 *       {@code application.yml} defaults to user {@code marketplace} while
 *       the CI postgres service authenticates {@code test/test}. Without
 *       these step-level env overrides the boot fails the first time a
 *       baseline tag exists — the tag path had never run before the fix
 *       (zero tags existed, so the script always skipped itself).</li>
 *   <li><b>maven-publish.yml + root pom</b> — the release publishes the
 *       TAG's version (versions-maven-plugin across all modules) to the
 *       GitHub Packages repository declared under {@code distributionManagement}
 *       (server id {@code github}); the poms on main stay
 *       {@code 0.1.0-SNAPSHOT}.</li>
 * </ul>
 *
 * <p>File-location note: surefire runs with the module basedir
 * ({@code marketplace-app}) as working directory, so the repo root resolves
 * to {@code ../}; running from the repo root is handled by the fallback.
 */
class PlatformGovernanceFilesTest {

    private static final Set<String> BOM_GOVERNORS = Set.of(
            "org.springframework.boot*", "org.springframework.modulith*");

    @Test
    void dependabotAllowsOnlyTheTwoBomGovernors() throws IOException {
        Map<String, Object> cfg = new Yaml().load(read(".github/dependabot.yml"));
        assertThat(cfg).as("dependabot.yml must parse as a mapping").isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> updates = (List<Map<String, Object>>) cfg.get("updates");
        assertThat(updates).as("dependabot must declare updates").isNotEmpty();

        Map<String, Object> maven = updates.stream()
                .filter(u -> "maven".equals(u.get("package-ecosystem")))
                .findFirst().orElseThrow();
        assertThat(maven.get("target-branch")).isEqualTo("main");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> allow = (List<Map<String, Object>>) maven.get("allow");
        assertThat(allow).as("the maven allow-list is the policy — it must exist").isNotNull();
        Set<String> allowed = allow.stream()
                .map(a -> String.valueOf(a.get("dependency-name")))
                .collect(Collectors.toSet());
        // Exactly the two BOM governors: no wider (enforcer-breaking bumps),
        // no narrower (the BOM cadence would go manual again).
        assertThat(allowed).containsExactlyInAnyOrderElementsOf(BOM_GOVERNORS);

        // The allow-list is the ONLY filter — a missing github-actions
        // ecosystem would leave action versions stale, an extra ecosystem
        // (npm/docker) would scan directories this repo does not have.
        Set<String> ecosystems = updates.stream()
                .map(u -> String.valueOf(u.get("package-ecosystem")))
                .collect(Collectors.toSet());
        assertThat(ecosystems).containsExactlyInAnyOrder("maven", "github-actions");

        // Groups must batch only within the governed set.
        @SuppressWarnings("unchecked")
        Map<String, Object> groups = (Map<String, Object>) maven.get("groups");
        assertThat(groups).as("spring-bom-governors group must exist").containsKey("spring-bom-governors");
        @SuppressWarnings("unchecked")
        Map<String, Object> group = (Map<String, Object>) groups.get("spring-bom-governors");
        @SuppressWarnings("unchecked")
        List<String> patterns = (List<String>) group.get("patterns");
        assertThat(patterns).allMatch(p -> BOM_GOVERNORS.contains(p));
    }

    @Test
    void codeqlWorkflowPinsJava25ManualBuild() throws IOException {
        String yml = read(".github/workflows/codeql.yml");
        // String-level pinning (deterministic for these self-authored files):
        // the workflow 'on:' key parses as YAML boolean, so text assertions
        // are the stable contract here.
        assertThat(yml).contains("languages: java");
        assertThat(yml).contains("build-mode: manual");
        assertThat(yml).as("the same JDK that compiles must extract").contains("java-version: 25");
        assertThat(yml).as("uploading alerts needs this permission").contains("security-events: write");
        assertThat(yml).as("the check name the ruleset may require")
                .contains("name: CodeQL Analyze (Java 25)");
    }

    @Test
    void ciOpenApiGateStepBootsTheAppWithServiceCredentials() throws IOException {
        String ci = read(".github/workflows/ci.yml");
        int stepStart = ci.indexOf("OpenAPI backward compatibility gate");
        int stepEnd = ci.indexOf("run: ./.ci/check-openapi-compat.sh");
        assertThat(stepStart).as("the gate step must exist in ci.yml").isGreaterThan(-1);
        assertThat(stepEnd).as("the gate step must invoke the script").isGreaterThan(stepStart);
        String gateBlock = ci.substring(stepStart, stepEnd);

        // Base application.yml: user ${DB_USERNAME:marketplace}; CI service:
        // test/test. Without these, the gate's jar boot fails on the first
        // tag (auth) — the sleeping-gate defect this layer closed.
        assertThat(gateBlock).contains("SPRING_DATASOURCE_URL: jdbc:postgresql://localhost:5432/marketplace");
        assertThat(gateBlock).contains("SPRING_DATASOURCE_USERNAME: test");
        assertThat(gateBlock).contains("SPRING_DATASOURCE_PASSWORD: test");
        assertThat(gateBlock).contains("SPRING_DATA_REDIS_HOST: localhost");
        // The proven boot-env set of integration-test.yml's Start Application.
        assertThat(gateBlock).contains("MANAGEMENT_OTLP_METRICS_EXPORT_ENABLED: false");
        assertThat(gateBlock).contains("MANAGEMENT_HEALTH_MAIL_ENABLED: false");
        assertThat(gateBlock).contains("MANAGEMENT_TRACING_ENABLED: false");
    }

    @Test
    void mavenPublishDerivesVersionFromTagAndSkipsVerifiedTests() throws IOException {
        String wf = read(".github/workflows/maven-publish.yml");
        assertThat(wf).as("release version comes from the tag, not the pom")
                .contains("${GITHUB_REF_NAME#v}");
        assertThat(wf).as("all 16 modules must be versioned for the release")
                .contains("-DprocessAllModules");
        assertThat(wf).as("the reactor stays SNAPSHOT on main — release version is set in-job")
                .contains("versions-maven-plugin");
        assertThat(wf).as("CI already ran the full suite on this commit")
                .contains("-DskipTests deploy");
        assertThat(wf).as("settings.xml server id must match distributionManagement")
                .contains("server-id: github");
        assertThat(wf).as("git-commit-id is bound at build time — needs full history")
                .contains("fetch-depth: 0");
    }

    @Test
    void releaseDistributionManagementTargetsGithubPackages() throws IOException {
        String pom = read("pom.xml");
        assertThat(pom).as("deploy needs a distributionManagement repository")
                .contains("https://maven.pkg.github.com/waelhe/app-java-v3");
        // The id is the bridge: setup-java server-id == this repository id.
        int dm = pom.indexOf("<distributionManagement>");
        assertThat(dm).isGreaterThan(-1);
        String block = pom.substring(dm, pom.indexOf("</distributionManagement>", dm) + 24);
        assertThat(block).contains("<id>github</id>");
    }

    private static String read(String... segments) throws IOException {
        Path cwd = Paths.get("").toAbsolutePath();
        Path repoRoot = cwd.resolve("..");
        if (!Files.exists(repoRoot.resolve(".github"))) {
            repoRoot = cwd; // fallback: tests launched from the repo root
        }
        Path file = repoRoot.resolve(String.join("/", segments));
        assertThat(file).as("%s must exist", file).exists();
        return Files.readString(file);
    }
}
