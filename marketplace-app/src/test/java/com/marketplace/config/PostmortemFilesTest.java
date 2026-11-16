package com.marketplace.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate test for the postmortem layer (plan item 3.3): pins the blameless
 * postmortem template to the official Google SRE sources and enforces that
 * every incident record under {@code docs/observability/postmortems/} follows
 * the template's mandatory structure. Same class of latent "config that lies"
 * defect as {@code ProductionWatchdogFilesTest} and {@code RunbooksFilesTest}:
 * a template that quietly loses its official anchors, or a record that quietly
 * drops a mandatory section (impact numbers, root cause, action-item owners)
 * or abandons its action items without closure, means the next incident
 * recurs - only a pinned test keeps the learning process honest.
 *
 * <p>Official sources (fetched and archived when item 3.3 was opened, per the
 * governing plan's §10 ritual):
 * <ul>
 *   <li>Google SRE Book, Ch.15 "Postmortem Culture: Learning from Failure" -
 *       archived at {@code scripts/doc-verify/sre-postmortem-culture.html}.
 *       Definition (verbatim): "A postmortem is a written record of an
 *       incident, its impact, the actions taken to mitigate or resolve it,
 *       the root cause(s), and the follow-up actions to prevent the incident
 *       from recurring."</li>
 *   <li>Google SRE Workbook, Ch.10 "Postmortem Culture" - archived at
 *       {@code scripts/doc-verify/sre-workbook-postmortem-culture.html}.
 *       Action-item closure: "we can monitor the closure of action items
 *       from each postmortem... ensure that action items don't slip through
 *       the cracks."</li>
 * </ul>
 *
 * <p>File-location note: surefire runs with the module basedir
 * ({@code marketplace-app}) as working directory, so the repo root resolves
 * to {@code ../}; running from the repo root is handled by the fallback.
 */
class PostmortemFilesTest {

    private static final String TEMPLATE = "docs/observability/postmortem-template.md";
    private static final String RECORDS_DIR = "docs/observability/postmortems";

    private static final Pattern RECORD_NAME = Pattern.compile("\\d{4}-\\d{2}-\\d{2}-.+\\.md");

    /** The eight mandatory sections of every record, in template order. */
    private static final List<String> MANDATORY_SECTIONS = List.of(
            "الملخص (Summary)",
            "الأثر (Impact)",
            "الخط الزمني (Timeline)",
            "السبب الجذري (Root Cause",
            "المعالجة والاحتواء (Mitigation & Resolution)",
            "ما سار جيداً",
            "الدروس (Lessons Learned)",
            "بنود العمل (Action Items)");

    @Test
    void templateCarriesTheOfficialDefinitionAndBlamelessContract() throws IOException {
        String md = read(TEMPLATE);

        assertThat(md).as("the official SRE-Book definition of a postmortem must stay "
                        + "verbatim - it defines the five components every record carries")
                .contains("A postmortem is a written record of an incident, its impact, "
                        + "the actions taken to mitigate or resolve it, the root cause(s), "
                        + "and the follow-up actions to prevent the incident from recurring.");
        assertThat(md).as("the blameless tenet must stay verbatim - dropping it turns the "
                        + "process into blame allocation, which the official chapter says "
                        + "stops people from bringing issues to light")
                .contains("For a postmortem to be truly blameless, it must focus on "
                        + "identifying the contributing causes of the incident without "
                        + "indicting any individual or team for bad or inappropriate behavior.");
        assertThat(md).as("the closure-tracking rule from the SRE Workbook - action items "
                        + "that are written but never closed make recurrence more likely")
                .contains("ensure that action items don't slip through the cracks");
        assertThat(md).as("the archived official sources must be cited where they were saved")
                .contains("scripts/doc-verify/sre-postmortem-culture.html")
                .contains("scripts/doc-verify/sre-workbook-postmortem-culture.html");

        for (String section : MANDATORY_SECTIONS) {
            assertThat(md).as("the template must keep its mandatory section: %s", section)
                    .contains(section);
        }
        assertThat(md).as("the official triggers list defines WHEN a record is required - "
                        + "the criteria must exist before an incident, not after")
                .contains("متى يُكتب سجل");
    }

    @Test
    void everyRecordFollowsTheTemplateStructure() throws IOException {
        for (Path record : records()) {
            String md = Files.readString(record);

            assertThat(RECORD_NAME.matcher(record.getFileName().toString()).matches())
                    .as("%s: record files are date-prefixed (YYYY-MM-DD-<slug>.md) so the "
                            + "timeline of postmortems is sortable at a glance",
                            record)
                    .isTrue();
            for (String section : MANDATORY_SECTIONS) {
                assertThat(md).as("%s must carry mandatory section: %s", record, section)
                        .contains(section);
            }
            assertThat(md).as("%s: the record header table must classify the record "
                            + "(draft / under review / adopted)", record)
                    .contains("الحالة")
                    .contains("الخطورة")
                    .contains("المحفز الرسمي");

            // Action items: every row must be tracked to closure - an owner
            // and a lifecycle status (open/done) per row, per the Workbook rule.
            List<String> actionRows = actionItemRows(md);
            assertThat(actionRows)
                    .as("%s: section 8 must carry at least one tracked action item - "
                            + "a postmortem without preventive actions is a diary entry, "
                            + "not the official artifact", record)
                    .isNotEmpty();
            for (String row : actionRows) {
                String status = actionItemStatus(row);
                assertThat(status)
                        .as("%s: action row '%s' must carry a tracked status "
                                + "(مفتوح/منجز) - untracked items slip through the cracks",
                                record, row)
                        .isIn("مفتوح", "منجز");
            }

            // The closure rule: an adopted record cannot still carry open items.
            if (md.contains("| معتمد")) {
                for (String row : actionRows) {
                    assertThat(actionItemStatus(row))
                            .as("%s: record is adopted (معتمد) but action '%s' is still "
                                    + "open - adopt only after closure (or declare the "
                                    + "debt and its closing point in the record)", record, row)
                            .isEqualTo("منجز");
                }
            }
        }
    }

    /**
     * Data rows (skipping header and separator) of the action-items table in
     * section 8. The template's column order is:
     * البند | المسؤول | الأولوية | الحالة | المرجع.
     */
    private static List<String> actionItemRows(String md) {
        int idx = md.indexOf("بنود العمل (Action Items)");
        assertThat(idx).as("the record must contain the action items section").isGreaterThan(0);
        return md.substring(idx).lines()
                .dropWhile(l -> !l.trim().startsWith("|"))
                .filter(l -> l.trim().startsWith("|"))
                .filter(l -> !l.contains("المسؤول"))
                .filter(l -> !l.matches("\\|[-: |]+\\|?"))
                .toList();
    }

    /** The 4th column (الحالة) of an action-item data row. */
    private static String actionItemStatus(String row) {
        String[] cells = row.split("\\|");
        // split() on "|...|...|...|...|...|" yields a leading empty cell, so
        // the 5 template columns land at indexes 1..5 and الحالة is index 4.
        assertThat(cells.length)
                .as("action rows carry 5 columns per the template: %s", row)
                .isGreaterThanOrEqualTo(6);
        return cells[4].trim();
    }

    private static List<Path> records() throws IOException {
        Path dir = repoRoot().resolve(RECORDS_DIR);
        assertThat(dir).as("the postmortems directory must exist once the first record "
                + "does (the plan's trial record for S10 ships with item 3.3)").exists();
        try (Stream<Path> files = Files.list(dir)) {
            List<Path> records = files.filter(p -> p.toString().endsWith(".md")).toList();
            assertThat(records).as("at least one postmortem record must exist").isNotEmpty();
            return records;
        }
    }

    private static String read(String... segments) throws IOException {
        Path file = repoRoot().resolve(String.join("/", segments));
        assertThat(file).as("%s must exist", file).exists();
        return Files.readString(file);
    }

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path repoRoot = cwd.resolve("..");
        if (!Files.exists(repoRoot.resolve(".github"))) {
            repoRoot = cwd; // fallback: tests launched from the repo root
        }
        return repoRoot;
    }
}
