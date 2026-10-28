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
 * <p>Defect: thirteen carried-in sources (nine marketplace-delivery, two
 * marketplace-ledger main plus one ledger test, one marketplace-provider)
 * arrived with the package statement, imports, and type declarations packed
 * onto single lines — twelve found by the round-26 code search, the
 * thirteenth ({@code DeliveryModule.java}, whose {@code "; import"} spacing
 * escaped the search patterns) caught by this guard's own first CI run. The approved OpenRewrite baseline recipe
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
 *
 * <p>Review rounds 1-3 (CodeRabbit, 2026-10-28) widened the measured
 * shape to what Java actually admits: leading whitespace and JLS 3.8
 * identifiers (round 1-2), Java 25 module imports, pre-semicolon
 * whitespace, and declarations preceded by same-line comments — the scan
 * judges the CODE-ONLY projection of each line (comments and
 * string-literal contents stripped by the {@link CodeOnlyStripper} state
 * machine), so statements inside comments or strings are data, never
 * declarations. Measured repo-wide before each adoption: zero offenders.
 */
class SourceFormatGuardTest {

    /**
     * CodeRabbit round (2026-10-28): the patterns accept LEADING
     * whitespace — a packed line indented by spaces or a tab (the shapes
     * generated sources actually arrive in) escaped the anchored forms,
     * and OpenRewrite would rewrite the line while the guard stayed
     * silent. Measured repo-wide before adoption: zero offenders, so the
     * widened shapes flag nothing the tree already carries.
     */
    // The name token is [^;\s]+ — anchored on what actually terminates a
    // package/import statement (the semicolon) and what separates tokens
    // (whitespace). A \w class would miss legal Java identifiers carrying
    // '$' or Unicode letters (JLS 3.8): 'package com.$generated; class
    // Example {}' is packed and must be flagged (CodeRabbit round 2,
    // 2026-10-28). The static-import keyword group is unaffected: 'static'
    // cannot appear in the package form and the group stays optional.
    private static final Pattern PACKED_PACKAGE = Pattern.compile("^\\s*package\\s+[^;\\s]+\\s*;\\s*\\S");
    private static final Pattern PACKED_IMPORT =
            Pattern.compile("^\\s*import\\s+(?:(?:static|module)\\s+)?[^;\\s]+\\s*;\\s*\\S");

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

    /**
     * The shapes themselves, pinned (CodeRabbit round 2026-10-28): the
     * indented packed line — the exact finding's example — must be FLAGGED,
     * and the honest single-statement lines (bare or indented, including a
     * javadoc-quoted import whose {@code ;} ends the line) must stay
     * UNflagged.
     */
    @Test
    void indentedPackedStatementsAreFlaggedAndHonestLinesAreNot() {
        assertThat(PACKED_IMPORT.matcher("  import java.util.*; class Example { List<?> value; }").find())
                .as("an import packed after leading spaces is the failure shape")
                .isTrue();
        assertThat(PACKED_IMPORT.matcher("\timport java.util.List; class Example {}").find())
                .as("an import packed after a leading tab is the failure shape")
                .isTrue();
        assertThat(PACKED_PACKAGE.matcher("  package com.example; class Foo {}").find())
                .as("a package statement packed after leading spaces is the failure shape")
                .isTrue();
        assertThat(PACKED_IMPORT.matcher("import java.util.List;").find())
                .as("a bare import alone on its line is honest")
                .isFalse();
        assertThat(PACKED_PACKAGE.matcher("package com.example;").find())
                .as("a bare package statement alone on its line is honest")
                .isFalse();
        assertThat(PACKED_IMPORT.matcher(" * import java.util.List;").find())
                .as("a javadoc-quoted import whose statement ends the line is documentation, not a packed source")
                .isFalse();
        // JLS 3.8 identifiers: '$' and Unicode letters are legal — the shapes
        // must be flagged exactly like their ASCII peers, and their honest
        // single-statement forms must stay unflagged (round 2's finding).
        assertThat(PACKED_PACKAGE.matcher("package com.$generated; class Example {}").find())
                .as("a '$' identifier packed with a declaration is the failure shape")
                .isTrue();
        assertThat(PACKED_IMPORT.matcher("import com.$generated.Inner; class Example {}").find())
                .as("a '$' import packed with a declaration is the failure shape")
                .isTrue();
        assertThat(PACKED_PACKAGE.matcher("package com.пример; class Example {}").find())
                .as("a Unicode identifier packed with a declaration is the failure shape")
                .isTrue();
        assertThat(PACKED_IMPORT.matcher("import com.пример.Класс;").find())
                .as("a bare Unicode import alone on its line is honest")
                .isFalse();
        assertThat(PACKED_IMPORT.matcher("import static com.$generated.Constants.NAME;").find())
                .as("a bare static '$' import alone on its line is honest")
                .isFalse();
        // Round 3, finding 1 — Java 25 module imports (JLS 7.7, compact
        // source files): 'module' is a keyword slot the import head carries
        // before the module name, exactly like 'static'.
        assertThat(PACKED_IMPORT.matcher("import module java.base; class Example {}").find())
                .as("a module import packed with a declaration is the failure shape")
                .isTrue();
        assertThat(PACKED_IMPORT.matcher("import module java.base;").find())
                .as("a bare module import alone on its line is honest")
                .isFalse();
        // Round 3, finding 3 — optional whitespace between the name and the
        // semicolon is legal Java; the packed tail decides, not the spacing.
        assertThat(PACKED_IMPORT.matcher("import java.util.List ; class Example {}").find())
                .as("an import with pre-semicolon whitespace packed with a declaration is the failure shape")
                .isTrue();
        assertThat(PACKED_PACKAGE.matcher("package com.example ; class Example {}").find())
                .as("a package statement with pre-semicolon whitespace packed with a declaration is the failure shape")
                .isTrue();
        assertThat(PACKED_IMPORT.matcher("import java.util.List ;").find())
                .as("a bare import with pre-semicolon whitespace alone on its line is honest")
                .isFalse();
    }

    /**
     * Round 3, finding 2 — declarations preceded by comments on the same
     * line. The scan strips comments and string-literal contents before
     * matching (the {@link CodeOnlyStripper} state machine below), so the
     * packed shape is judged on CODE only: a comment before the statement
     * hides nothing, a statement inside a comment or a string is data, not
     * a declaration, and javadoc/text-block state carries across lines.
     */
    @Test
    void commentPrefixedPackedStatementsAreFlaggedAndCommentedOnesAreNot() {
        CodeOnlyStripper stripper = new CodeOnlyStripper();
        assertThat(isPacked(stripper.strip("/* generated */ package p; class Example {}")))
                .as("a block comment before the statement hides nothing")
                .isTrue();
        assertThat(isPacked(stripper.strip("// package p; class Example {}")))
                .as("a line comment swallows the whole line")
                .isFalse();
        assertThat(isPacked(stripper.strip("/* package p; */ class Example {}")))
                .as("a declaration inside a comment is documentation, not a statement")
                .isFalse();
        assertThat(isPacked(stripper.strip("log(\"import a.b; class X {}\");")))
                .as("a packed shape quoted inside a string literal is data, not a statement")
                .isFalse();

        CodeOnlyStripper javadoc = new CodeOnlyStripper();
        javadoc.strip("/** the guard's own javadoc opens here");
        assertThat(isPacked(javadoc.strip(" * import a.b; class X {}")))
                .as("a javadoc continuation line stays comment state across lines")
                .isFalse();
        javadoc.strip(" */");
        assertThat(isPacked(javadoc.strip("package p;")))
                .as("after the javadoc closes, code resumes — an honest statement is not flagged")
                .isFalse();

        CodeOnlyStripper textBlock = new CodeOnlyStripper();
        textBlock.strip("String s = \"\"\"");
        assertThat(isPacked(textBlock.strip("import a.b; class X {}")))
                .as("an unterminated string carries its state to the next line (text blocks)")
                .isFalse();
        textBlock.strip("\"\"\";");
        assertThat(isPacked(textBlock.strip("import a.b;")))
                .as("after the text block closes, an honest statement is not flagged")
                .isFalse();
    }

    private static boolean isPacked(String codeOnlyLine) {
        return PACKED_PACKAGE.matcher(codeOnlyLine).find()
                || PACKED_IMPORT.matcher(codeOnlyLine).find();
    }

    private static void scan(Path root, Path file, List<String> offenders) {
        try {
            List<String> lines = Files.readAllLines(file);
            CodeOnlyStripper stripper = new CodeOnlyStripper();
            for (int i = 0; i < lines.size(); i++) {
                if (isPacked(stripper.strip(lines.get(i)))) {
                    offenders.add(root.relativize(file) + ":" + (i + 1));
                }
            }
        } catch (IOException unreadable) {
            offenders.add(root.relativize(file) + ":unreadable");
        }
    }

    /**
     * One character-level pass per line with two cross-line states: inside
     * a block comment ({@code /* ... *&#47;}, javadoc included) and inside a
     * string literal (an unterminated {@code "} carries to the next line —
     * a text block's content stays string state until its closing quotes).
     * Block-comment text and string-literal contents vanish; the code that
     * remains is what the packed-statement patterns judge. A line comment
     * ({@code //}) ends the line's code. Char literals are left as-is: a
     * single character can never carry a statement.
     */
    private static final class CodeOnlyStripper {

        private boolean inBlockComment;
        private boolean inString;

        String strip(String line) {
            StringBuilder code = new StringBuilder(line.length());
            int i = 0;
            while (i < line.length()) {
                char c = line.charAt(i);
                char next = i + 1 < line.length() ? line.charAt(i + 1) : '\0';
                if (inBlockComment) {
                    if (c == '*' && next == '/') {
                        inBlockComment = false;
                        i += 2;
                    } else {
                        i++;
                    }
                } else if (inString) {
                    if (c == '\\') {
                        i += 2;
                    } else if (c == '"') {
                        inString = false;
                        code.append('"');
                        i++;
                    } else {
                        i++;
                    }
                } else if (c == '"') {
                    inString = true;
                    code.append('"');
                    i++;
                } else if (c == '/' && next == '*') {
                    inBlockComment = true;
                    i += 2;
                } else if (c == '/' && next == '/') {
                    break;
                } else {
                    code.append(c);
                    i++;
                }
            }
            return code.toString();
        }
    }

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path root = cwd.resolve("..");
        return Files.exists(root.resolve(".github")) ? root : cwd;
    }
}
