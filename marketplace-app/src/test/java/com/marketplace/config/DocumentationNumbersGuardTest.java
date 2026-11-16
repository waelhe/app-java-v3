package com.marketplace.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate test for documented repository figures — the audit-2026-09-25 finding
 * "documentation numbers lie" (priority 1), closed as code: every guarded
 * figure is <b>derived</b> here from its own source of truth at test time and
 * asserted against the canonical phrasing in the governance documents. A
 * drifted number fails the build instead of waiting for the next reader to
 * notice (same class of latent "docs that lie" defect as
 * {@code PlatformGovernanceFilesTest} guards for config files).
 *
 * <p>Derivation sources (one authority per figure, never a hand-typed
 * constant in this test):
 * <ul>
 *   <li><b>Maven module count</b> — the reactor IS the authority ("Guide to
 *       Working with Multiple Modules": the root POM's {@code <modules>}
 *       declares the build). Parsed from the root {@code pom.xml}.</li>
 *   <li><b>Migration inventory</b> — the migration folder is the authority
 *       ({@code db/migration/}, Flyway V/R naming; retired numbers such as
 *       V35 are legitimate and must not be "fixed" — the derived range
 *       phrasing handles them: count = files, range end = max version).</li>
 *   <li><b>Named cache count</b> — {@code spring.cache.cache-names} in
 *       {@code application.yml} (Spring Boot reference: the property is the
 *       canonical list of cache names to create).</li>
 *   <li><b>Integration-test selection</b> — the Failsafe {@code <includes>}
 *       patterns in the root POM ({@code **&#47;*IT.java} +
 *       {@code **&#47;*IntegrationTest.java}) applied to every module's test
 *       tree: the same selection the build actually performs.</li>
 *   <li><b>Framework versions</b> — the parent POM version and the Modulith
 *       BOM import in the root {@code pom.xml}.</li>
 * </ul>
 *
 * <p>Each figure also has exactly one canonical phrasing per document
 * (audit Top-5 #1: "a wrong interpretation would merely install a new wrong
 * number"). File-location note: surefire runs with the module basedir
 * ({@code marketplace-app}) as working directory, so the repo root resolves
 * to {@code ../}; running from the repo root is handled by the fallback.
 */
class DocumentationNumbersGuardTest {

    // ---------- authorities ----------

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path root = cwd.resolve("..");
        return Files.exists(root.resolve(".github")) ? root : cwd;
    }

    private static String read(Path file) throws IOException {
        assertThat(file).as("%s must exist", file).exists();
        return Files.readString(file);
    }

    /**
     * Prose in markdown and YAML comments wraps freely (editors re-flow lines),
     * so every prose assertion runs against whitespace-collapsed text: a phrase
     * split across a line break must still match.
     */
    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ");
    }

    /**
     * YAML comment prose additionally interleaves the {@code #} marker at every
     * wrapped line ("repository" / "# reads"), so the marker is stripped per
     * line before collapsing. Markdown must NOT go through this: its headings
     * legitimately start with {@code #}.
     */
    private static String yamlCommentProse(String yaml) {
        return collapse(yaml.lines()
                .map(line -> line.replaceFirst("^\\s*#", ""))
                .collect(java.util.stream.Collectors.joining(" ")));
    }

    private static List<String> reactorModules(String rootPom) {
        Matcher m = Pattern.compile("<module>([^<]+)</module>").matcher(rootPom);
        List<String> modules = new ArrayList<>();
        while (m.find()) {
            modules.add(m.group(1).trim());
        }
        return modules;
    }

    private static String parentVersion(String rootPom) {
        Matcher m = Pattern.compile(
                "<parent>[\\s\\S]*?<version>([^<]+)</version>[\\s\\S]*?</parent>").matcher(rootPom);
        assertThat(m.find()).as("root pom must declare a parent version").isTrue();
        return m.group(1).trim();
    }

    private static String modulithVersion(String rootPom) {
        Matcher m = Pattern.compile(
                "spring-modulith-bom</artifactId>\\s*<version>([^<]+)</version>").matcher(rootPom);
        assertThat(m.find()).as("root pom must import the spring-modulith-bom").isTrue();
        return m.group(1).trim();
    }

    private record MigrationInventory(int versionedCount, int maxVersion, int repeatableCount) {
        int totalFiles() {
            return versionedCount + repeatableCount;
        }
    }

    private static MigrationInventory migrations(Path folder) throws IOException {
        int versioned = 0;
        int maxV = 0;
        int repeatable = 0;
        try (Stream<Path> files = Files.list(folder)) {
            for (Path f : files.sorted().toList()) {
                String name = f.getFileName().toString();
                Matcher v = Pattern.compile("^V(\\d+)__.*\\.sql$").matcher(name);
                if (v.matches()) {
                    versioned++;
                    maxV = Math.max(maxV, Integer.parseInt(v.group(1)));
                    continue;
                }
                if (name.matches("^R__.*\\.sql$")) {
                    repeatable++;
                }
            }
        }
        return new MigrationInventory(versioned, maxV, repeatable);
    }

    private static List<String> cacheNames(String applicationYml) {
        Matcher m = Pattern.compile("(?m)^\\s*cache-names:\\s*(\\S.*)$").matcher(applicationYml);
        assertThat(m.find()).as("application.yml must declare spring.cache.cache-names").isTrue();
        String csv = m.group(1).trim();
        return Stream.of(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    private record IntegrationSelection(int total, int itSuffix) {}

    private static IntegrationSelection failsafeSelection(List<String> modules) throws IOException {
        int total = 0;
        int itSuffix = 0;
        for (String module : modules) {
            Path testTree = repoRoot().resolve(module + "/src/test/java");
            if (!Files.exists(testTree)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(testTree)) {
                for (Path f : files.filter(Files::isRegularFile).toList()) {
                    String name = f.getFileName().toString();
                    if (name.endsWith("IntegrationTest.java")) {
                        total++;
                    } else if (name.endsWith("IT.java")) {
                        total++;
                        itSuffix++;
                    }
                }
            }
        }
        return new IntegrationSelection(total, itSuffix);
    }

    private static int moduleIntegrationTestClasses() throws IOException {
        Path tree = repoRoot().resolve("marketplace-app/src/test/java");
        try (Stream<Path> files = Files.walk(tree)) {
            return (int) files.filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith("ModuleIntegrationTest.java"))
                    .count();
        }
    }

    private static String ordinal(int n) {
        return switch (n % 100) {
            case 11, 12, 13 -> n + "th";
            default -> switch (n % 10) {
                case 1 -> n + "st";
                case 2 -> n + "nd";
                case 3 -> n + "rd";
                default -> n + "th";
            };
        };
    }

    // ---------- guards ----------

    @Test
    void documentedModuleCountMatchesTheReactor() throws IOException {
        String rootPom = read(repoRoot().resolve("pom.xml"));
        List<String> modules = reactorModules(rootPom);
        int n = modules.size();

        // canonical phrasing, exactly one per document
        assertThat(collapse(read(repoRoot().resolve("README.md"))))
                .as("README must state the reactor's module count (%s derived from <modules>)", n)
                .contains("of " + n + " Maven modules");
        assertThat(collapse(read(repoRoot().resolve("SYSTEM.md"))))
                .as("SYSTEM.md must state the reactor's module count (%s derived from <modules>)", n)
                .contains("**" + n + "** وحدة Maven");
        String architectureRaw = read(repoRoot().resolve("docs/architecture/ARCHITECTURE.md"));
        assertThat(collapse(architectureRaw))
                .as("ARCHITECTURE.md must state the reactor's module count (%s derived from <modules>)", n)
                .contains("**" + n + " Maven modules**")
                .contains("### The " + n + " Modules");
        long tableRows = architectureRaw.lines()
                .filter(l -> l.matches("\\|\\s*\\d+\\s*\\|\\s*`marketplace-[^`]+`\\s*\\|.*"))
                .count();
        assertThat(tableRows)
                .as("ARCHITECTURE.md module table must list every reactor module (%s rows for %s modules)",
                        tableRows, n)
                .isEqualTo(n);
        // the edge BFF is the module every forgotten breakdown missed (audit Top-5 #1)
        assertThat(modules).contains("marketplace-edge");
    }

    @Test
    void documentedMigrationInventoryMatchesTheMigrationFolder() throws IOException {
        MigrationInventory inv = migrations(
                repoRoot().resolve("marketplace-app/src/main/resources/db/migration"));
        String system = collapse(read(repoRoot().resolve("SYSTEM.md")));
        assertThat(system)
                .as("SYSTEM.md must state the derived versioned-migration count and range "
                        + "(%s files, V1..V%s; retired numbers are legitimate and excluded from the count)",
                        inv.versionedCount(), inv.maxVersion())
                .contains(inv.versionedCount() + " ترحيلة نسخية `V1..V" + inv.maxVersion() + "`")
                .as("SYSTEM.md must state the derived total file count (%s = %s versioned + %s repeatable)",
                        inv.totalFiles(), inv.versionedCount(), inv.repeatableCount())
                .contains("**" + inv.totalFiles() + " ملفًا في الشجرة**");
    }

    @Test
    void documentedCacheCountMatchesCacheNames() throws IOException {
        Path ymlPath = repoRoot().resolve("marketplace-app/src/main/resources/application.yml");
        String ymlRaw = read(ymlPath);
        int n = cacheNames(ymlRaw).size();

        assertThat(collapse(read(repoRoot().resolve("SYSTEM.md"))))
                .as("SYSTEM.md must state the derived named-cache count (%s from spring.cache.cache-names)", n)
                .contains("+ " + n + " مخبأة مسماة");
        assertThat(yamlCommentProse(ymlRaw))
                .as("application.yml's own comments must carry the derived cache count")
                .contains("all " + n + " named caches hold entries FOREVER")
                .contains("all " + n + " caches are rebuildable repository reads")
                .contains("is the " + ordinal(n) + " named cache");
    }

    @Test
    void documentedIntegrationSelectionMatchesFailsafeIncludes() throws IOException {
        String rootPom = read(repoRoot().resolve("pom.xml"));
        IntegrationSelection sel = failsafeSelection(reactorModules(rootPom));

        assertThat(collapse(read(repoRoot().resolve("SYSTEM.md"))))
                .as("SYSTEM.md must state the derived Failsafe selection (%s files across the reactor's test trees)",
                        sel.total())
                .contains("**" + sel.total() + " ملفًا** تختارها أنماط التضمين")
                .contains(sel.itSuffix() + " منها بلاحقة `*IT`");
    }

    @Test
    void documentedModuleIntegrationTestClassCountMatches() throws IOException {
        int n = moduleIntegrationTestClasses();
        assertThat(collapse(read(repoRoot().resolve("SYSTEM.md"))))
                .as("SYSTEM.md must state the derived ModuleIntegrationTest class count (%s)", n)
                .contains(n + " صنف `ModuleIntegrationTest`");
    }

    @Test
    void documentedFrameworkVersionsMatchThePom() throws IOException {
        String rootPom = read(repoRoot().resolve("pom.xml"));
        String boot = parentVersion(rootPom);
        String modulith = modulithVersion(rootPom);

        assertThat(collapse(read(repoRoot().resolve("docs/architecture/ARCHITECTURE.md"))))
                .as("ARCHITECTURE.md must state the versions the POM actually pins "
                        + "(parent %s, modulith-bom %s)", boot, modulith)
                .contains("Spring Boot " + boot)
                .contains("Spring Modulith " + modulith);
    }
}
