package com.marketplace.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The SDL root-coverage guard (TST-02's second half, W1-B4) — the
 * adversarial scan measured 40 roots (37 on the execution head) with
 * ZERO coverage on any layer, and the review's mandate was explicit:
 * a supply table alone closes today's gap; a COUNT GUARD keeps it
 * closed — "اختبار يفك جذور SDL آلياً ويمسح شجرة الاختبارات ويفشل عند
 * ظهور جذر جديد بصفر لمسة — يمنع عودة الصنف كله".
 *
 * <p><b>The measurement (deliberately conservative):</b> a root is
 * "touched" when its bare name appears anywhere in any module's test
 * tree — a DGS document string, a {@code DgsConstants} reference, a
 * test method name, a comment naming the root. The guard cannot prove
 * semantic coverage (that is the supply table's own job), but it
 * guarantees the CLASS of finding — a root declared on the SDL with
 * no test anywhere in the reactor — can never return silently: adding
 * a new root without a single test reference fails this guard.</p>
 *
 * <p>The scan mirrors the review's own methodology (ripgrep over the
 * whole test tree + programmatic SDL root extraction), so the same
 * measurement that produced "40/181" now produces 0 by construction
 * and stays 0 by force.</p>
 */
class GraphQlRootCoverageGuardTest {

    /** A root declaration line inside a Query/Mutation block: {@code name(args): Type}. */
    private static final Pattern ROOT_FIELD = Pattern.compile("^([a-zA-Z][a-zA-Z0-9_]*)\\s*[(:]");

    @Test
    void everySdlRootIsTouchedByAtLeastOneTest() throws IOException {
        Path repoRoot = repoRoot();
        Map<String, String> roots = sdlRoots(repoRoot);
        assertThat(roots.size())
                .as("the SDL root extraction must stay meaningful (the review measured 181)")
                .isGreaterThan(150);

        String testBlob = wholeTestTree(repoRoot);
        List<String> untouched = new ArrayList<>();
        for (String root : roots.keySet()) {
            // The 2026-11-04 #807 round (Major · Test Quality): the pattern
            // is built PER ROOT — the previous literal "\\b%s\\b" never
            // substituted the root name, so the same literal-%s search ran
            // for every root and the guard measured nothing (any formatted
            // string in the test tree satisfied all roots at once).
            Matcher matcher = Pattern.compile("\\b" + Pattern.quote(root) + "\\b")
                    .matcher(testBlob);
            if (!matcher.find()) {
                untouched.add(root);
            }
        }
        assertThat(untouched)
                .as("every SDL-declared root must be referenced by at least one test in the "
                        + "reactor (TST-02's class of finding — a root with zero coverage on "
                        + "any layer — must never return; the supply table covers today's "
                        + "roots, this guard covers tomorrow's)")
                .isEmpty();
    }

    // ------------------------------------------------------------------
    // the measurement
    // ------------------------------------------------------------------

    /** Extracts every Query/Mutation root (name -> kind) from the four SDL files. */
    private static Map<String, String> sdlRoots(Path repoRoot) throws IOException {
        Map<String, String> roots = new LinkedHashMap<>();
        try (Stream<Path> sdlFiles = Files.list(repoRoot.resolve(
                "marketplace-app/src/main/resources/graphql"))) {
            for (Path sdl : sdlFiles.filter(p -> p.toString().endsWith(".graphqls")).toList()) {
                boolean inQuery = false;
                boolean inMutation = false;
                for (String rawLine : Files.readAllLines(sdl)) {
                    String line = rawLine.strip();
                    if (line.startsWith("#")) {
                        continue;
                    }
                    if (line.matches("(extend\\s+)?type\\s+Query.*\\{?")) {
                        inQuery = true;
                        inMutation = false;
                        continue;
                    }
                    if (line.matches("(extend\\s+)?type\\s+Mutation.*\\{?")) {
                        inMutation = true;
                        inQuery = false;
                        continue;
                    }
                    if ((inQuery || inMutation) && line.equals("}")) {
                        inQuery = false;
                        inMutation = false;
                        continue;
                    }
                    if (inQuery || inMutation) {
                        Matcher field = ROOT_FIELD.matcher(line);
                        if (field.lookingAt()) {
                            roots.putIfAbsent(field.group(1), inQuery ? "query" : "mutation");
                        }
                    }
                }
            }
        }
        return roots;
    }

    /** Concatenates every test source file in the reactor. */
    private static String wholeTestTree(Path repoRoot) throws IOException {
        StringBuilder blob = new StringBuilder();
        try (Stream<Path> modules = Files.list(repoRoot)) {
            for (Path module : modules.filter(p -> p.getFileName().toString()
                    .startsWith("marketplace-")).toList()) {
                Path testJava = module.resolve("src/test/java");
                if (!Files.isDirectory(testJava)) {
                    continue;
                }
                try (Stream<Path> files = Files.walk(testJava)) {
                    for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                        blob.append(Files.readString(file)).append('\n');
                    }
                }
            }
        }
        assertThat(blob.length()).as("the reactor's test tree must be non-empty").isPositive();
        return blob.toString();
    }

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path parent = cwd.resolve("..");
        if (Files.exists(parent.resolve(".github"))) {
            return parent.normalize();
        }
        assertThat(cwd.resolve(".github")).as("the guard must run from the repo (module dir or root)").exists();
        return cwd;
    }
}
