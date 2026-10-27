package com.marketplace.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate test for the defect-knowledge system's <b>own</b> consistency — the
 * round-17 review finding (PR #746, CodeRabbit 2026-10-27): a system whose
 * first rule is "numbers are derived or dated" (A6) must not itself ship
 * hand-maintained counts, dangling rule references, undeclared status tags,
 * or invalid phase/section pointers. Every check here <b>derives</b> the
 * truth from the enumerations in {@code docs/governance/defect-knowledge/}
 * and asserts the prose claims against it — the same authority-per-figure
 * discipline as {@code DocumentationNumbersGuardTest}, applied by the system
 * to itself (rule A6/D2 self-application; closes the narrowest form of gap
 * G1 for these files).
 *
 * <p>Derived authorities (never a hand-typed constant in this test):
 * <ul>
 *   <li><b>Rule enumeration</b> — {@code rulebook.md} headings
 *       ({@code ### A1 — ...}) and D-category list items
 *       ({@code - **D1** ...}) are the only source of rule IDs and counts.</li>
 *   <li><b>Pattern enumeration</b> — {@code pattern-catalog.md} headings
 *       ({@code ## P1 · ...}).</li>
 *   <li><b>Case enumeration &amp; tag schema</b> — {@code evidence-ledger.md}
 *       headings ({@code ## E-01 · ...}) and the declared label set on its
 *       header line.</li>
 *   <li><b>Phase/step structure</b> — {@code change-protocol.md} section
 *       headings and numbered steps.</li>
 *   <li><b>Section structure</b> — {@code enforcement-map.md} headings
 *       ({@code ## §1 · ...}); checklist length — the adversarial-checklist
 *       section of {@code review-protocol.md}.</li>
 * </ul>
 *
 * <p>Regression intent: the original PR shipped "25 rules" against a 26-item
 * enumeration, an E-22 heading with undeclared {@code [VERIFIED-Churn]} /
 * {@code [HYPOTHESIS-Cause]} pseudo-tags, and six ambiguous
 * {@code Phase X.Y} references — each of those exact shapes now fails this
 * build.
 */
class DefectKnowledgeDocsGuardTest {

    private static final String DK_DIR = "docs/governance/defect-knowledge";

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path root = cwd.resolve("..");
        return Files.exists(root.resolve(".github")) ? root : cwd;
    }

    private static String read(String relative) throws IOException {
        Path file = repoRoot().resolve(relative);
        assertThat(file).as("%s must exist", relative).exists();
        return Files.readString(file);
    }

    private static String dkRead(String name) throws IOException {
        return read(DK_DIR + "/" + name);
    }

    /** All seven artifacts of the system, read once per test that needs them. */
    private static List<String> allDkDocs() throws IOException {
        List<String> docs = new ArrayList<>();
        for (String name : new String[] {"README.md", "evidence-ledger.md", "pattern-catalog.md",
                "rulebook.md", "change-protocol.md", "review-protocol.md", "enforcement-map.md"}) {
            docs.add(dkRead(name));
        }
        return docs;
    }

    // ---------- authorities: derived enumerations ----------

    /** Category -> ordered rule numbers, derived from the rulebook itself. */
    private static Map<Character, List<Integer>> ruleEnumeration(String rulebook) {
        Map<Character, List<Integer>> byCategory = new LinkedHashMap<>();
        for (char c : new char[] {'A', 'B', 'C', 'D'}) {
            byCategory.put(c, new ArrayList<>());
        }
        Matcher headings = Pattern.compile("^### ([ABCD])(\\d+) \\u2014 ", Pattern.MULTILINE)
                .matcher(rulebook);
        while (headings.find()) {
            byCategory.get(headings.group(1).charAt(0)).add(Integer.parseInt(headings.group(2)));
        }
        Matcher listItems = Pattern.compile("^- \\*\\*([ABCD])(\\d+)\\*\\* ", Pattern.MULTILINE)
                .matcher(rulebook);
        while (listItems.find()) {
            byCategory.get(listItems.group(1).charAt(0)).add(Integer.parseInt(listItems.group(2)));
        }
        return byCategory;
    }

    private static Set<String> ruleIdSet(Map<Character, List<Integer>> enumeration) {
        Set<String> ids = new TreeSet<>();
        enumeration.forEach((category, numbers) ->
                numbers.forEach(n -> ids.add("" + category + n)));
        return ids;
    }

    private static Set<Integer> patternEnumeration(String catalog) {
        Set<Integer> patterns = new TreeSet<>();
        Matcher m = Pattern.compile("^## P(\\d+) \\u00b7", Pattern.MULTILINE).matcher(catalog);
        while (m.find()) {
            patterns.add(Integer.parseInt(m.group(1)));
        }
        return patterns;
    }

    // ---------- 1. rulebook enumeration is complete and sequential ----------

    @Test
    void rulebookEnumerationIsCompleteAndSequential() throws IOException {
        Map<Character, List<Integer>> enumeration = ruleEnumeration(dkRead("rulebook.md"));
        assertThat(enumeration.get('A')).as("A rules sequential from 1").isNotEmpty();
        for (Map.Entry<Character, List<Integer>> entry : enumeration.entrySet()) {
            List<Integer> numbers = entry.getValue();
            List<Integer> expected = new ArrayList<>();
            for (int i = 1; i <= numbers.size(); i++) {
                expected.add(i);
            }
            assertThat(numbers).as("category %s numbered 1..%d without gaps",
                    entry.getKey(), numbers.size()).isEqualTo(expected);
        }
    }

    // ---------- 2. enforcement map binds every declared rule ----------

    @Test
    void enforcementMapBindsEveryDeclaredRule() throws IOException {
        Set<String> declared = ruleIdSet(ruleEnumeration(dkRead("rulebook.md")));
        Set<String> bound = new TreeSet<>();
        Matcher rows = Pattern.compile("^\\| ([ABCD])(\\d+) ", Pattern.MULTILINE)
                .matcher(dkRead("enforcement-map.md"));
        while (rows.find()) {
            bound.add(rows.group(1) + rows.group(2));
        }
        assertThat(bound).as("enforcement-map §1 binds every rulebook ID").containsAll(declared);
        assertThat(declared).as("enforcement-map §1 has no unknown rule IDs").containsAll(bound);
    }

    // ---------- 3. proportionality counts match the derived enumeration ----------

    @Test
    void proportionalityCountsMatchDerivedEnumeration() throws IOException {
        Map<Character, List<Integer>> enumeration = ruleEnumeration(dkRead("rulebook.md"));
        int abcRules = enumeration.get('A').size() + enumeration.get('B').size()
                + enumeration.get('C').size();
        int dBindings = enumeration.get('D').size();
        int patterns = patternEnumeration(dkRead("pattern-catalog.md")).size();

        Matcher claim = Pattern.compile("(\\d+) patterns and (\\d+) A/B/C rules plus (\\d+) automated D bindings")
                .matcher(dkRead("enforcement-map.md"));
        assertThat(claim.find()).as("enforcement-map §4 must state the derived sizes").isTrue();
        assertThat(Integer.parseInt(claim.group(1))).as("pattern count claim").isEqualTo(patterns);
        assertThat(Integer.parseInt(claim.group(2))).as("A/B/C rule count claim").isEqualTo(abcRules);
        assertThat(Integer.parseInt(claim.group(3))).as("D binding count claim").isEqualTo(dBindings);
    }

    // ---------- 4. status tags follow the declared schema ----------

    @Test
    void statusTagsFollowDeclaredSchema() throws IOException {
        String ledger = dkRead("evidence-ledger.md");
        String header = ledger.lines().limit(6).collect(java.util.stream.Collectors.joining("\n"));
        Set<String> declared = new TreeSet<>();
        Matcher declaredMatcher = Pattern.compile("\\*\\*\\[([A-Z]+)\\]\\*\\*").matcher(header);
        while (declaredMatcher.find()) {
            declared.add(declaredMatcher.group(1));
        }
        assertThat(declared).as("ledger header declares the four status labels")
                .containsExactlyInAnyOrder("VERIFIED", "CAUSAL", "HYPOTHESIS", "OPEN");

        // Pure bracket tags (an uppercase-initial word of letters/hyphens, at least
        // 4 chars) must be declared — in any context, bold or not; qualified prose
        // forms such as "[HYPOTHESIS, explicitly unverified]" carry a comma and
        // their own qualification, and are intentionally not matched by this pattern.
        Pattern tagUse = Pattern.compile("\\[([A-Z][A-Za-z-]{3,})\\]");
        for (String doc : allDkDocs()) {
            Set<String> used = new TreeSet<>();
            Matcher m = tagUse.matcher(doc);
            while (m.find()) {
                used.add(m.group(1));
            }
            assertThat(declared).as("every pure status tag is declared (no [VERIFIED-Churn]-style pseudo-tags)")
                    .containsAll(used);
        }
    }

    // ---------- 5. ledger cases are sequential and referenced honestly ----------

    @Test
    void ledgerCasesSequentialAndReferenced() throws IOException {
        String ledger = dkRead("evidence-ledger.md");
        List<Integer> cases = new ArrayList<>();
        Matcher m = Pattern.compile("^## E-(\\d+) \\u00b7", Pattern.MULTILINE).matcher(ledger);
        while (m.find()) {
            cases.add(Integer.parseInt(m.group(1)));
        }
        List<Integer> expected = new ArrayList<>();
        for (int i = 1; i <= cases.size(); i++) {
            expected.add(i);
        }
        assertThat(cases).as("cases numbered E-01.. without gaps").isEqualTo(expected);

        String pointer = read("docs/agent-protocol.md");
        Matcher range = Pattern.compile("E-01\\u2026E-(\\d+)").matcher(pointer);
        assertThat(range.find()).as("agent-protocol §9 pointer states the case range").isTrue();
        assertThat(Integer.parseInt(range.group(1))).as("agent-protocol range endpoint matches the ledger")
                .isEqualTo(cases.size());
    }

    // ---------- 6. pattern enumeration matches README and pointer ranges ----------

    @Test
    void patternEnumerationMatchesReferences() throws IOException {
        int count = patternEnumeration(dkRead("pattern-catalog.md")).size();
        for (String docName : new String[] {DK_DIR + "/README.md", "docs/agent-protocol.md"}) {
            Matcher range = Pattern.compile("P1[\\u2013-]P(\\d+)").matcher(read(docName));
            assertThat(range.find()).as("%s states the pattern range", docName).isTrue();
            assertThat(Integer.parseInt(range.group(1)))
                    .as("%s pattern range endpoint matches the catalog", docName)
                    .isEqualTo(count);
        }
    }

    // ---------- 7. phase/step references resolve; ambiguous form is gone ----------

    @Test
    void phaseStepReferencesResolve() throws IOException {
        Map<Integer, Integer> stepsPerPhase = new LinkedHashMap<>();
        String changeProtocol = dkRead("change-protocol.md");
        int currentPhase = -1;
        for (String line : changeProtocol.split("\n", -1)) {
            Matcher phase = Pattern.compile("^## Phase (\\d+) \\u2014 ").matcher(line);
            if (phase.find()) {
                currentPhase = Integer.parseInt(phase.group(1));
                stepsPerPhase.put(currentPhase, 0);
                continue;
            }
            if (currentPhase >= 0 && Pattern.compile("^\\d+\\. ").matcher(line).find()) {
                stepsPerPhase.merge(currentPhase, 1, Integer::sum);
            }
        }
        assertThat(stepsPerPhase).as("change-protocol defines phases 0, 1, 2")
                .containsOnlyKeys(0, 1, 2);

        StringBuilder combined = new StringBuilder();
        for (String doc : allDkDocs()) {
            combined.append(doc).append('\n');
        }
        combined.append(read("docs/agent-protocol.md"));

        Matcher ref = Pattern.compile("Phase (\\d+), step (\\d+)").matcher(combined);
        int checked = 0;
        while (ref.find()) {
            int phase = Integer.parseInt(ref.group(1));
            int step = Integer.parseInt(ref.group(2));
            assertThat(stepsPerPhase).as("Phase %d exists", phase).containsKey(phase);
            assertThat(step).as("Phase %d has at least %d steps", phase, step)
                    .isBetween(1, stepsPerPhase.get(phase));
            checked++;
        }
        assertThat(checked).as("the docs actually use the explicit 'Phase N, step M' form").isPositive();

        Matcher ambiguous = Pattern.compile("Phase \\d+\\.\\d+").matcher(combined);
        assertThat(ambiguous.find()).as("the ambiguous 'Phase X.Y' form must not return")
                .isFalse();
    }

    // ---------- 8. enforcement-map section references resolve ----------

    @Test
    void enforcementMapSectionReferencesResolve() throws IOException {
        Set<Integer> sections = new TreeSet<>();
        Matcher m = Pattern.compile("^## \\u00a7(\\d+) \\u00b7", Pattern.MULTILINE)
                .matcher(dkRead("enforcement-map.md"));
        while (m.find()) {
            sections.add(Integer.parseInt(m.group(1)));
        }
        assertThat(sections).as("enforcement-map has sections §1..§5").containsExactly(1, 2, 3, 4, 5);

        Pattern reference = Pattern.compile("enforcement-map(?:\\.md)? \\u00a7(\\d+)");
        StringBuilder combined = new StringBuilder();
        for (String doc : allDkDocs()) {
            combined.append(doc).append('\n');
        }
        Matcher ref = reference.matcher(combined);
        int checked = 0;
        while (ref.find()) {
            assertThat(sections).as("referenced section §%s exists", ref.group(1))
                    .contains(Integer.parseInt(ref.group(1)));
            checked++;
        }
        assertThat(checked).as("the docs actually reference enforcement-map sections").isPositive();
    }

    // ---------- 9. review-protocol pass references resolve ----------

    @Test
    void reviewProtocolPassReferencesResolve() throws IOException {
        String reviewProtocol = dkRead("review-protocol.md");
        int checklistStart = reviewProtocol.indexOf("## The adversarial checklist");
        assertThat(checklistStart).as("review-protocol has the adversarial checklist").isNotNegative();
        int checklistEnd = reviewProtocol.indexOf("\n## ", checklistStart + 1);
        String checklist = reviewProtocol.substring(checklistStart,
                checklistEnd < 0 ? reviewProtocol.length() : checklistEnd);
        int items = 0;
        Matcher m = Pattern.compile("^(\\d+)\\. \\*\\*", Pattern.MULTILINE).matcher(checklist);
        Set<Integer> numbers = new LinkedHashSet<>();
        while (m.find()) {
            numbers.add(Integer.parseInt(m.group(1)));
            items++;
        }
        assertThat(items).as("adversarial checklist has 12 searches").isEqualTo(12);
        assertThat(numbers).as("checklist numbered 1..12").containsExactlyInAnyOrderElementsOf(
                java.util.stream.IntStream.rangeClosed(1, 12).boxed().toList());

        StringBuilder combined = new StringBuilder();
        for (String doc : allDkDocs()) {
            combined.append(doc).append('\n');
        }
        Matcher pass = Pattern.compile("pass (\\d+)").matcher(combined);
        int checked = 0;
        while (pass.find()) {
            assertThat(Integer.parseInt(pass.group(1)))
                    .as("review-protocol pass %s exists", pass.group(1)).isBetween(1, items);
            checked++;
        }
        assertThat(checked).as("the docs actually use 'pass N' references").isPositive();
    }

    // ---------- 10. rule references resolve; artifacts exist ----------

    @Test
    void ruleReferencesResolve() throws IOException {
        Set<String> declared = ruleIdSet(ruleEnumeration(dkRead("rulebook.md")));
        Pattern token = Pattern.compile("\\b([ABCD])(\\d{1,2})\\b");
        for (String doc : allDkDocs()) {
            Set<String> referenced = new TreeSet<>();
            Matcher m = token.matcher(doc);
            while (m.find()) {
                referenced.add(m.group(1) + m.group(2));
            }
            assertThat(declared).as("every [ABCD]<n> token in the system resolves to a rulebook ID")
                    .containsAll(referenced);
        }
        for (String name : new String[] {"README.md", "evidence-ledger.md", "pattern-catalog.md",
                "rulebook.md", "change-protocol.md", "review-protocol.md", "enforcement-map.md"}) {
            assertThat(repoRoot().resolve(DK_DIR + "/" + name))
                    .as("artifact %s exists", name).exists();
        }
    }
}
