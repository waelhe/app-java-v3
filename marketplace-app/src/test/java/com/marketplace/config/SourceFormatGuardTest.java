package com.marketplace.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * D9 — source-format compatibility guard (closes E-23's class).
 *
 * <p>Defect: twelve carried-in sources (eight marketplace-delivery, two
 * marketplace-ledger main plus one ledger test, one marketplace-provider)
 * arrived with the package statement, imports, and type declarations packed
 * onto single lines. The approved OpenRewrite baseline recipe
 * ({@code RemoveUnusedImports}) replaces partially-used wildcard imports
 * with explicit ones — and in a single-line source every such change
 * rewrites the declaration line too, so the publication patch carries
 * non-import source changes and the fail-closed publication guard refuses
 * the whole batch (three red main pushes: runs 40285298597, 40479098312,
 * 40626928859 — see evidence-ledger E-23 and pattern P10).
 *
 * <p>Invariant: a Java line carrying a {@code package} or {@code import}
 * statement carries nothing else. That is the exact shape the repo's
 * line-based machinery (recipe publication, line-anchored review) requires.
 *
 * <p>Deliberately narrow, honoring the regression list's warning against
 * defensive bloat: annotations sharing a line with a type declaration are
 * not flagged — only the measured failure shape (package/import packing)
 * is rejected.
 */
class SourceFormatGuardTest {

    private static final Pattern PACKED_PACKAGE = Pattern.compile("^package\\s+[\\w.]+;\\s*\\S");
    private static final Pattern PACKED_IMPORT = Pattern.compile("^import\\s+(?:static\\s+)?[\\w.*]+;\\s*\\S");

    @Test
    void packageAndImportStatementsAreAloneOnTheirLine() throws IOException {
        Path root = repoRoot();
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(root)) {
            paths.filter(p -> {
                        String s = p.toString().replace('\\', '/');
                        return s.endsWith(".java")
                                && !s.contains("/.git/")
                                && (s.contains("/src/main/java/") || s.contains("/src/test/java/"));
                    })
                    .forEach(p -> scan(root, p, offenders));
        }
        assertThat(offenders)
                .as("E-23 / D9: a package or import statement must be alone on its line — "
                        + "packed lines defeat the OpenRewrite publication guard. Reformat the file "
                        + "to standard layout: package line alone, one import per line.")
                .isEmpty();
    }

    private static void scan(Path root, Path file, List<String> offenders) {
        try {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (PACKED_PACKAGE.matcher(line).find() || PACKED_IMPORT.matcher(line).find()) {
                    offenders.add(root.relativize(file) + ":" + (i + 1));
                }
            }
        } catch (IOException unreadable) {
            offenders.add(root.relativize(file) + ":unreadable");
        }
    }

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path root = cwd.resolve("..");
        return Files.exists(root.resolve(".github")) ? root : cwd;
    }
}
