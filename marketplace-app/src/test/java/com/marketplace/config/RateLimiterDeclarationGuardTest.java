package com.marketplace.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B14 (TST-08 — the 2026-11-03 adversarial review, review-pr-807): the
 * {@code @RateLimiter} declaration guard — the platform's own
 * "configuration that lies" pattern aimed at the documented silent
 * fallback.
 *
 * <p><b>The defect class the guard closes</b> (the base profile's own
 * comment at {@code application.yml}'s ratelimiter block records the
 * measured instance): a fetcher annotated {@code @RateLimiter(name = X)}
 * where {@code X} was never declared under
 * {@code resilience4j.ratelimiter.instances} does NOT fail at startup —
 * Resilience4j silently instantiates it from the shared
 * <b>default</b> config (100/min, 5s wait), so the surface rides limits
 * its owner never chose. The 2026-11-01 shard review caught exactly this
 * shape live (three names annotated across 70 call sites riding the
 * default), and the test profile's {@code limit-refresh-period: 1s}
 * windows (deliberate, for suite speed) mean no behavioral test can
 * honestly separate a declared budget from the silent default — the
 * honest regression proof is this static guard: every annotated name
 * must be declared in the base profile, so the fallback class can never
 * re-enter the tree silently.</p>
 *
 * <p><b>Trial evidence (the acceptance criterion's "red when the defect
 * is reintroduced"):</b> the guard's exact scan was run against the tree
 * before landing — the current head is a clean 56-annotated/56-declared
 * bijection, and removing any declared instance key (reintroducing the
 * defect) leaves its annotation names undeclared, which this guard
 * fails with the offending names listed.</p>
 *
 * <p>File-location note: surefire runs with the module basedir
 * ({@code marketplace-app}) as working directory, so the repo root
 * resolves to {@code ../}; running from the repo root is handled by the
 * fallback (the {@code DocumentationNumbersGuardTest} precedent).</p>
 */
class RateLimiterDeclarationGuardTest {

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

    // ---------- the guard ----------

    @Test
    void everyAnnotatedRateLimiterInstanceIsDeclaredInTheBaseProfile() throws IOException {
        Set<String> annotated = annotatedInstanceNames();
        Set<String> declared = declaredInstanceNames();

        assertThat(annotated)
                .as("the scan must find @RateLimiter annotations across the reactor's main trees")
                .isNotEmpty();
        assertThat(declared)
                .as("the base profile must declare resilience4j.ratelimiter.instances")
                .isNotEmpty();

        Set<String> undeclared = new TreeSet<>(annotated);
        undeclared.removeAll(declared);
        assertThat(undeclared)
                .as("every @RateLimiter(name = ...) must be declared under "
                        + "resilience4j.ratelimiter.instances in the base application.yml — "
                        + "an undeclared name silently rides the DEFAULT instance's budget "
                        + "(100/min, 5s wait; the documented config-that-lies class). "
                        + "Undeclared names found: %s", undeclared)
                .isEmpty();
    }

    // ---------- derivations ----------

    /**
     * Every {@code @RateLimiter(name = "...")} annotation across every
     * reactor module's main source tree — the exact names the runtime
     * will resolve (the annotation's declared {@code name} is the
     * instance key Resilience4j looks up).
     */
    private static Set<String> annotatedInstanceNames() throws IOException {
        List<String> modules = reactorModules(read(repoRoot().resolve("pom.xml")));
        Pattern annotation = Pattern.compile("@RateLimiter\\(\\s*name\\s*=\\s*\"([^\"]+)\"");
        Set<String> names = new TreeSet<>();
        for (String module : modules) {
            Path tree = repoRoot().resolve(module + "/src/main/java");
            if (!Files.exists(tree)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(tree)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    Matcher matcher = annotation.matcher(Files.readString(file));
                    while (matcher.find()) {
                        names.add(matcher.group(1));
                    }
                }
            }
        }
        return names;
    }

    /**
     * The instance keys declared under {@code resilience4j.ratelimiter.instances}
     * in the BASE profile ({@code marketplace-app/src/main/resources/application.yml})
     * — the authoritative declaration surface (profile documents may
     * override windows, but only the base profile's declarations count as
     * the owner's declaration of the instance).
     *
     * <p>The extraction is level-exact because the same YAML nests a
     * sibling {@code resilience4j.retry.instances} block: the parse walks
     * top-level {@code resilience4j:} → the 2-indent {@code ratelimiter:}
     * section → its 4-indent {@code instances:} section → the 6-indent
     * instance keys, stopping at the first key at indent 4 or shallower.</p>
     */
    private static Set<String> declaredInstanceNames() throws IOException {
        String yml = read(repoRoot().resolve("marketplace-app/src/main/resources/application.yml"));
        Matcher resilience = Pattern.compile("(?m)^resilience4j:\\n").matcher(yml);
        assertThat(resilience).as("application.yml must carry the resilience4j block").find();
        String afterResilience = yml.substring(resilience.end());

        Matcher ratelimiter = Pattern.compile("(?m)^  ratelimiter:\\n").matcher(afterResilience);
        assertThat(ratelimiter).as("the resilience4j block must carry the ratelimiter section").find();
        String ratelimiterSection = afterResilience.substring(ratelimiter.end());
        Matcher ratelimiterEnd = Pattern.compile("(?m)^ {0,2}\\S").matcher(ratelimiterSection);
        if (ratelimiterEnd.find()) {
            ratelimiterSection = ratelimiterSection.substring(0, ratelimiterEnd.start());
        }

        Matcher instances = Pattern.compile("(?m)^    instances:\\n").matcher(ratelimiterSection);
        assertThat(instances).as("the ratelimiter section must carry the instances block").find();
        String instancesSection = ratelimiterSection.substring(instances.end());
        Matcher instancesEnd = Pattern.compile("(?m)^ {0,4}\\S").matcher(instancesSection);
        if (instancesEnd.find()) {
            instancesSection = instancesSection.substring(0, instancesEnd.start());
        }

        Set<String> keys = new TreeSet<>();
        Matcher key = Pattern.compile("(?m)^      ([A-Za-z0-9_-]+):").matcher(instancesSection);
        while (key.find()) {
            keys.add(key.group(1));
        }
        return keys;
    }

    private static List<String> reactorModules(String rootPom) {
        Matcher m = Pattern.compile("<module>([^<]+)</module>").matcher(rootPom);
        List<String> modules = new java.util.ArrayList<>();
        while (m.find()) {
            modules.add(m.group(1).trim());
        }
        assertThat(modules).as("the root pom must declare its reactor modules").isNotEmpty();
        return modules;
    }
}
