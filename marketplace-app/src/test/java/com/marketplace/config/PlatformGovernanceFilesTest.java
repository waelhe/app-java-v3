package com.marketplace.config;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
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
 *   <li><b>container-scan.yml + .trivyignore.yaml + root pom tomcat pin
 *       (removed — automatic parent management again) + Dockerfile runtime
 *       stage</b> —
 *       the container-image CVE gate (roadmap G-ENG-1): the workflow must keep
 *       scanning the REAL production artifact (docker build of the repo
 *       Dockerfile, image mode), must fail on HIGH/CRITICAL fixed findings
 *       (exit-code 1), and must honor the yaml ignore file whose entries are
 *       time-bounded ({@code expired_at}) — an ignore entry that silently
 *       loses its expiry would weaken the gate forever. The ignore list is
 *       EMPTY by design: the runtime stage's {@code apk upgrade --no-cache}
 *       closes every fixable alpine finding at build time (the version-bump
 *       response the gate policy prescribes), so an entry exists only while
 *       a fix is not yet published for the v3.24 branch. The root pom's
 *       {@code <tomcat.version>} pin closed the three CRITICAL Tomcat 11.0.24
 *       auth bypasses this layer caught, then followed the same-line patches
 *       to 11.0.25 and 11.0.26 (three IMPORTANT CVEs in 11.0.25: HTTP/2
 *       header mix-up, AJP DoS, WebSocket close busy-wait); the pin made its
 *       removal a deliberate, visible act — the Boot 4.1.2 platform wave
 *       (2026-10-23) executed that removal once the parent managed 11.0.27
 *       (measured from spring-boot-dependencies-4.1.2.pom, past the 11.0.26
 *       floor; version-policy §6: no house pin for a BOM-managed version),
 *       and the guard now inverts against manual re-introduction. The
 *       Dockerfile pin is the same contract for
 *       the OS layer: silently dropping the upgrade line returns the shipped
 *       image to stale alpine packages until the weekly scan catches it.</li>
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

    @Test
    void containerScanWorkflowPinsTheCveGate() throws IOException {
        String yml = read(".github/workflows/container-scan.yml");
        // The check name the ruleset requires (required_status_checks context).
        assertThat(yml).as("the check name the ruleset may require")
                .contains("name: Container Image Scan (Docker)");
        // The scan target is the production artifact: the same Dockerfile
        // Railway builds (serviceManifest), driven through BuildKit (the
        // Dockerfile needs RUN --mount=type=cache).
        assertThat(yml).as("scans the image built from the repo Dockerfile")
                .contains("uses: docker/build-push-action@v7")
                .contains("load: true")
                .contains("scan-type: image");
        // Pinned tooling — no floating @master (drift = different gate).
        assertThat(yml).as("pinned trivy-action + trivy version")
                .contains("aquasecurity/trivy-action@v0.36.0")
                .contains("version: v0.74.0");
        // The gate itself: fail on HIGH/CRITICAL fixable findings, honoring
        // the yaml ignore policy (the only sanctioned exceptions).
        assertThat(yml).as("gate policy: HIGH/CRITICAL + fail + ignores")
                .contains("severity: CRITICAL,HIGH")
                .contains("exit-code: '1'")
                .contains("ignore-unfixed: true")
                .contains("trivyignores: .trivyignore.yaml");
        // SARIF upload needs this permission (same shape as codeql.yml).
        assertThat(yml).as("uploading SARIF needs this permission")
                .contains("security-events: write");
        // New CVEs land without code changes — the weekly rescan is the
        // visible signal until the OTEL/MAIL alerting gates open (roadmap A1).
        assertThat(yml).as("main is rescanned on a schedule").contains("schedule:");
        // The gate must certify a FRESH build, or it certifies a stale OS
        // surface. Docker's cache rule (docs.docker.com/build/cache/optimize):
        // "a layer is reused from the build cache if the instruction and the
        // files it depends on hasn't changed since it was previously built" —
        // `apk upgrade --no-cache` is an unchanged instruction whose --no-cache
        // is an APK flag, not a Docker one, so under cache-from: type=gha the
        // upgrade layer was replayed and froze the OS packages. Measured
        // 2026-09-24: expat 2.8.5-r0 published in the v3.24 index (APKINDEX
        // t:1790203100 = 2026-09-23T22:37Z) yet the 18:50Z scan still reported
        // 2.8.4-r0; bumping the Dockerfile date token re-ran the upgrade and the
        // gate went green. The official inputs that make every scan fresh:
        // no-cache ("Do not use cache when building the image", default false)
        // and pull ("Always attempt to pull all referenced images", default
        // false) — docker/build-push-action README. The runtime stage is
        // unnamed, so the surgical `no-cache-filters` cannot target it;
        // no-cache is the only lever for the shipped layer.
        assertThat(yml).as("the gate build must not replay a cached OS layer")
                .contains("no-cache: true");
        assertThat(yml).as("the gate build must re-resolve the base image")
                .contains("pull: true");
        // Dead-config guard (CodeRabbit round 1, adopted from the root):
        // cache mounts never travel with the GHA cache anyway — Docker docs,
        // build/ci/github-actions/cache, "Cache mounts": "BuildKit doesn't
        // preserve cache mounts in the GitHub Actions cache by default" —
        // and this gate is the repo's only gha-cache consumer while no-cache
        // disables lookup for good, so a cache-from/cache-to pair would be
        // config that looks alive and does nothing: the exact
        // stale-certification illusion this gate exists to kill.
        assertThat(yml).as("no dead cache import (lookup is permanently disabled)")
                .doesNotContain("cache-from:");
        assertThat(yml).as("no dead cache export (nothing ever reads it)")
                .doesNotContain("cache-to:");
    }

    @Test
    void containerScanIgnoresAreBoundedEntries() throws IOException {
        String ignore = read(".trivyignore.yaml");
        // Every entry must be time-bounded: expired_at is enforced by trivy
        // itself (verified live: past date => the finding returns). An entry
        // without it would silence the gate indefinitely.
        int entries = countIgnoreEntries(ignore);
        if (entries > 0) {
            assertThat(ignore).as("any ignore entry must carry an expiry")
                    .contains("expired_at:");
            // And justified: the statement documents why the finding is accepted.
            assertThat(ignore).as("any ignore entry must carry a justification")
                    .contains("statement:");
        }
        // Zero entries today. Counting "- id:" keeps the file honest: a
        // silently widened list (someone pasting more CVEs) trips this pin
        // and forces a deliberate, review-visible change.
        assertThat(entries)
                .as("zero ignore entries — the runtime stage's OS package upgrade "
                        + "(Dockerfile: RUN apk upgrade --no-cache against the v3.24 "
                        + "stable branch) closes every fixable alpine finding at "
                        + "image build time (2026-09-09: libexpat 2.8.4-r0 for "
                        + "CVE-2026-76956/76957 and openssl 3.5.8-r0 for "
                        + "CVE-2026-14456 — the entry this file carried while its "
                        + "fix awaited an eclipse-temurin rebuild that never "
                        + "shipped). An ignore entry is only for findings with no "
                        + "fix published for the branch; adding one is a deliberate "
                        + "act that must update this pin")
                .isZero();
    }

    @Test
    void dockerfileRuntimeStageUpgradesOsPackages() throws IOException {
        String dockerfile = read("Dockerfile");
        // The runtime stage is the shipped OS layer — the only surface the
        // image-mode gate scans for alpine packages. Its last FROM is the
        // runtime base (the trainer stage uses the same base but never
        // ships); the upgrade line must run there, as root, before USER app
        // (apk requires root).
        int runtimeFrom = dockerfile.lastIndexOf("FROM eclipse-temurin:25-jre-alpine");
        assertThat(runtimeFrom)
                .as("the runtime stage base must stay the jre-alpine official image")
                .isGreaterThan(-1);
        int upgrade = osUpgradeInstructionIndex(dockerfile, runtimeFrom);
        assertThat(upgrade)
                .as("the runtime stage runs the official in-branch package upgrade "
                        + "(apk-upgrade(8): apk upgrade upgrades installed packages "
                        + "to the latest version available from configured package "
                        + "repositories) — the version-bump response the gate "
                        + "prescribes for fixable findings. Removal returns the "
                        + "shipped image to stale alpine packages (base tag last "
                        + "pushed 2026-08-21 while both fixes already shipped in "
                        + "v3.24-main — measured 2026-09-09)")
                .isGreaterThan(runtimeFrom);
        // Line-start match: the Dockerfile comment above the upgrade line
        // mentions "USER app" in prose — the instruction is what must be found.
        int user = dockerfile.indexOf("\nUSER app", runtimeFrom);
        assertThat(user)
                .as("the runtime stage must still switch to the non-root user")
                .isGreaterThan(-1);
        assertThat(upgrade)
                .as("the upgrade runs as root (before USER app)")
                .isLessThan(user);
    }

    @Test
    void osUpgradeGuardMatchesOnlyRealInstructions() {
        // Mutation coverage for the line anchor (CodeRabbit r1, PR #273): the
        // guard must be satisfied by an actual RUN instruction — a
        // commented-out line or prose mention is the "config that lies"
        // class this guard family exists to catch.
        String base = "FROM eclipse-temurin:25-jre-alpine";
        int from = base.lastIndexOf("FROM eclipse-temurin:25-jre-alpine");

        String commented = base + "\n# RUN apk upgrade --no-cache\nUSER app\n";
        assertThat(osUpgradeInstructionIndex(commented, from))
                .as("a commented-out upgrade line is not an instruction")
                .isEqualTo(-1);

        String prose = base + "\n# the stage runs `apk upgrade --no-cache` here\nUSER app\n";
        assertThat(osUpgradeInstructionIndex(prose, from))
                .as("prose does not satisfy the guard")
                .isEqualTo(-1);

        String real = base + "\nRUN apk upgrade --no-cache # token\nUSER app\n";
        assertThat(osUpgradeInstructionIndex(real, from))
                .as("the real instruction matches (trailing comment included)")
                .isGreaterThan(-1);
    }

    @Test
    void rootPomKeepsTomcatOnAutomaticBootManagement() throws IOException {
        String pom = read("pom.xml");
        // The version-property override this test used to pin
        // (tomcat.version=11.0.26 — closing the three CRITICAL Tomcat
        // 11.0.24 auth bypasses CVE-2026-65182 / CVE-2026-65905 /
        // CVE-2026-68525, then the three IMPORTANT CVEs of the 11.0.25 line
        // CVE-2026-86350 HTTP/2 request header mix-up / CVE-2026-78383 AJP
        // DoS via missing request body / CVE-2026-77791 WebSocket close
        // busy-wait DoS) was REMOVED in the 2026-10-23 Boot 4.1.2 platform
        // wave exactly as its own removal condition prescribed: the Boot
        // parent now manages tomcat 11.0.27 (measured from
        // spring-boot-dependencies-4.1.2.pom), >= the 11.0.26 security pin —
        // every CVE range above stays closed under automatic parent
        // management (version-policy §6: no house pin where the Boot BOM
        // already holds the fixed line). The guard now inverts: a
        // reintroduced <tomcat.version> property is a manual intervention
        // the policy forbids unless a NEW documented exception with
        // measured evidence exists.
        assertThat(pom).as("no manual tomcat override — automatic Boot parent management only")
                .doesNotContainPattern("<\\s*tomcat\\.version\\s*>");
        // The removal evidence travels in the pom comment — a future reader
        // must find the measured Boot-managed line without git archaeology.
        assertThat(pom).as("the pom cites the measured Boot-managed tomcat line")
                .contains("Override REMOVED 2026-10-23")
                .contains("tomcat.version=11.0.27");
    }

    @Test
    void integrationTestsOwnTheirDatabaseContainer() {
        // AGENTS.md (Testing) requires every integration test to carry
        // @Testcontainers/@Container. The rule exists because a container is
        // retained across test classes (Spring Boot 4.1.1 reference,
        // "Testcontainers": "A single test container instance can, and often
        // is, retained across execution of tests from multiple test classes"),
        // so a class that borrows the workflow's shared PostgreSQL service
        // reads and writes the auth tables (auth_users, auth_authorities,
        // oauth2_registered_client, oauth2_authorization_consent) left behind
        // by whichever class ran before it. Maven does not pin class order
        // (no <runOrder> in the root pom), so the suite passed on main at
        // 02:47 and failed on the same commit at 12:34 and 16:51 with
        // AuthorizationServerLoginGateIntegrationTest: a leftover admin row
        // from PublicPkceClientGateIntegrationTest made the last-admin gate
        // answer 200 instead of 409, and the consent fixtures no longer
        // matched. Measured, not inferred — see the run-order diff in the
        // incident record. Isolation, not ordering, is the fix.
        //
        // The check is STRUCTURAL, not textual: the first edition of this
        // guard matched annotation TEXT and let 18 classes pass that carry
        // @Testcontainers with no @Container field at all — they were still
        // on the workflow's shared PostgreSQL through the test profile's
        // jdbc:postgresql://localhost:5432 default (17 @ApplicationModuleTest
        // slices + ReviewsTwoWayIntegrationTest, a full @SpringBootTest).
        // ArchUnit inspects the COMPILED classes here, so a regression can
        // only pass by actually declaring a class-level @Testcontainers AND
        // a @Container @ServiceConnection PostgreSQLContainer field — the
        // Spring Boot reference's service-connection contract ("Testing:
        // Testcontainers": the container field contributes the
        // ConnectionDetails consumed by auto-configuration), which is what
        // replaces the shared-service datasource for the context.
        // @ApplicationModuleTest is a @SpringBootTest replacement on the same
        // TestContext machinery (Modulith reference, "Testing"), so the
        // service-connection customizer applies to the slices too.
        JavaClasses imported = new ClassFileImporter().importPackages("com.marketplace");
        ArchCondition<JavaClass> ownsServiceConnectedPostgres =
                new ArchCondition<>("own a @Container @ServiceConnection PostgreSQLContainer field under a @Testcontainers class") {
                    @Override
                    public void check(JavaClass clazz, ConditionEvents events) {
                        boolean annotated = clazz.isAnnotatedWith(Testcontainers.class);
                        boolean ownsPostgres = clazz.getFields().stream().anyMatch(field ->
                                field.isAnnotatedWith(Container.class)
                                        && field.isAnnotatedWith(ServiceConnection.class)
                                        && field.getRawType().isAssignableTo(PostgreSQLContainer.class));
                        if (annotated && ownsPostgres) {
                            return;
                        }
                        events.add(SimpleConditionEvent.violated(clazz, String.format(
                                "%s is on the workflow's shared PostgreSQL instead of owning one: "
                                        + "AGENTS.md (Testing) + the 2026-09-24 isolation incident require "
                                        + "@Testcontainers on the class and a @Container @ServiceConnection "
                                        + "PostgreSQLContainer field — the class inherits the rows of "
                                        + "whichever class ran before it, and the suite's result depends on "
                                        + "an order Maven does not pin",
                                clazz.getName())));
                    }
                };
        classes().that().haveSimpleNameEndingWith("IntegrationTest")
                .should(ownsServiceConnectedPostgres)
                .check(imported);
    }

    @Test
    void openRewriteCatalogIsBomAlignedOptInAndAudited() throws IOException {
        String pom = read("pom.xml");
        assertThat(pom)
                .as("OpenRewrite recipe and Maven plugin versions must follow the verified official catalog")
                .contains("<openrewrite.recipe-bom.version>3.41.0</openrewrite.recipe-bom.version>")
                .contains("<openrewrite.maven-plugin.version>6.50.0</openrewrite.maven-plugin.version>")
                .contains("<artifactId>rewrite-recipe-bom</artifactId>")
                .contains("<id>openrewrite</id>")
                .contains("<artifactId>rewrite-third-party</artifactId>")
                .contains("<id>openrewrite-third-party-free-catalog</id>")
                .contains("<artifactId>rewrite-migrate-java</artifactId>")
                .contains("<artifactId>rewrite-github-actions</artifactId>")
                .contains("<artifactId>rewrite-static-analysis</artifactId>")
                .contains("<artifactId>rewrite-all</artifactId>")
                .contains("<artifactId>rewrite-spring</artifactId>")
                .contains("<failOnInvalidActiveRecipes>true</failOnInvalidActiveRecipes>");

        int openRewriteProfileStart = pom.indexOf("<id>openrewrite</id>");
        int openRewriteProfileEnd = pom.indexOf("</profile>", openRewriteProfileStart);
        assertThat(openRewriteProfileStart).isGreaterThanOrEqualTo(0);
        assertThat(openRewriteProfileEnd).isGreaterThan(openRewriteProfileStart);
        assertThat(pom.substring(openRewriteProfileStart, openRewriteProfileEnd))
                .as("the baseline no-subscription profile must not import the catalog whose transitive recipes returned HTTP 403")
                .doesNotContain("<artifactId>rewrite-third-party</artifactId>");

        String catalog = read("docs", "governance", "openrewrite-catalog.md");
        assertThat(catalog)
                .as("catalog must record the version, licensing and execution boundary")
                .contains("rewrite-all")
                .contains("rewrite-third-party")
                .contains("rewrite-spring")
                .contains("Not yet proven")
                .contains("failOnDryRunResults");

        String workflow = read(".github", "workflows", "openrewrite-catalog.yml");
        assertThat(workflow)
                .as("OpenRewrite catalog audit must not silently skip credential or discovery checks")
                .contains("CODEGENOME_USERNAME")
                .contains("CODEGENOME_TOKEN")
                .contains("server-username-env-var: CODEGENOME_USERNAME")
                .contains("server-password-env-var: CODEGENOME_TOKEN")
                .contains("Verify Code Genome artifact authentication")
                .contains("--location")
                .contains("A 302 redirect is normal")
                .contains("rewrite-maven-plugin-6.50.0.jar")
                .contains("Write explicit Maven settings for Code Genome")
                .contains("--settings \"${RUNNER_TEMP}/openrewrite-settings.xml\"")
                .contains("rewrite-maven-plugin:6.50.0:discover")
                .contains("rewrite-maven-plugin:6.50.0:dryRunNoFork");
    }

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path repoRoot = cwd.resolve("..");
        return Files.exists(repoRoot.resolve(".github")) ? repoRoot : cwd;
    }

    private static int osUpgradeInstructionIndex(String dockerfile, int runtimeFrom) {
        // Line-anchored: RUN must START the line (an actual instruction) —
        // "RUN apk upgrade --no-cache" inside a comment or prose must not
        // satisfy the guard (same contract as the USER-app anchor above).
        return dockerfile.indexOf("\nRUN apk upgrade --no-cache", runtimeFrom);
    }

    private static int countIgnoreEntries(String ignoreFile) {
        // "- id:" at line start marks one vulnerability entry in the yaml
        // ignore policy (vulnerabilities: list items).
        return ignoreFile.split("(?m)^\\s*-\\s*id:", -1).length - 1;
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
