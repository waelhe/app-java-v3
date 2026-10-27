package com.marketplace.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Gate test for the campaign report numbers — G1 of the defect-knowledge
 * enforcement map: "extend the numeric guards to the campaign reports".
 * The map's own drift class (E-12: numbers in prose drifting from the
 * ledger) was caught four times by hand in this closure round — the P3
 * wave cell 55 against the ledger's 59 (while the row's own family cells
 * sum to 59), the b6-a packet line claiming 28 roots while its decision
 * table lists 27 and its own ledger-line delta says 27, the b6-b
 * Query-surface split claiming 37/50/11 (sums to 98, not 90) while its
 * own INTEGRATED list names exactly 22 roots, and the completion map's
 * fb23ad4c family cell "تعيينات أنواع 4" against the ledger's 3. A
 * drifted number now fails the build instead of waiting for the next
 * reader to notice (same class as {@code DocumentationNumbersGuardTest}).
 *
 * <p><b>One authority per figure</b> — the campaign ledger
 * {@code docs/governance/campaign/saleor-458-root-inventory.csv}
 * (the campaign README's own rule 2: "Track progress from the ledger,
 * never from optimistic prose"). Every count asserted here is derived
 * from that CSV at test time; nothing is trusted from the reports. The
 * CSV is parsed with a small RFC 4180 state machine (quoted fields with
 * embedded commas, quotes and newlines — the playbook's own A0
 * invariant: "ledger CSV via csv module").
 *
 * <p>Coverage (all fail-closed):
 * <ul>
 *   <li>README ledger row: one row per exact root (458 rows, 458 unique
 *       names) and the kind totals 458 = 90 Query + 342 Mutation +
 *       26 Subscription.</li>
 *   <li>completion map: the NOT_STARTED wave table (per-wave counts and
 *       family-cell sums), the 56 fb23ad4c evidence-backed flips
 *       (P2 52 + P3 4, the four by exact root name), the audit
 *       arithmetic 294 = 238 + 56, and the P2-required decision table's
 *       named roots (each must exist and still be NOT_STARTED on P2).</li>
 *   <li>b6-a: packet count == decision-table root count == ledger-line
 *       delta, and every named root exists with a decided status (never
 *       back to NOT_STARTED).</li>
 *   <li>b6-b: the Query-surface split sentence sums to the ledger's
 *       Query total; its INTEGRATED list expands to exactly the claimed
 *       count and every named root is Integrated (terminal); its
 *       owning-module families expand to exactly the claimed
 *       NOT_STARTED count and none is Blocked; packet 15 == table ==
 *       ledger-line delta.</li>
 *   <li>b5-a/b5-b governance lines: the two variant rows and the two
 *       order rows they name stay Integrated.</li>
 * </ul>
 *
 * <p>Known residuals, documented deliberately: the P2-required table's
 * slash-compressed tokens ("assign/unassignWarehouseShippingZone") ride
 * the row-count and total checks — the 20 clean comma-separated names
 * are verified individually; historical intermediate counts inside
 * b5-a/b5-b prose are guarded only through their named rows. Surefire
 * runs with the module basedir ({@code marketplace-app}) as working
 * directory, so the repo root resolves to {@code ../}; running from the
 * repo root is handled by the fallback.
 */
class CampaignReportsGuardTest {

    private static final String CAMPAIGN_DIR = "docs/governance/campaign";
    private static final String REPORTS_DIR = CAMPAIGN_DIR + "/reports";
    private static final Pattern ROOT_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    // ---------- paths and text helpers ----------

    private static Path repoRoot() {
        Path cwd = Paths.get("").toAbsolutePath();
        Path root = cwd.resolve("..");
        return Files.exists(root.resolve(".github")) ? root : cwd;
    }

    private static String read(Path file) throws IOException {
        assertThat(file).as("%s must exist", file).exists();
        return Files.readString(file);
    }

    /** Whitespace-collapsed text with bold/backtick markers stripped. */
    private static String plain(String text) {
        return collapse(text).replace("*", "").replace("`", "");
    }

    private static String collapse(String text) {
        return text.replaceAll("\\s+", " ");
    }

    // ---------- the ledger (the one authority) ----------

    /**
     * Minimal RFC 4180 reader: honors quoted fields with embedded
     * commas, doubled quotes and newlines; fail-closed on ragged rows.
     */
    private static List<Map<String, String>> readLedger() throws IOException {
        Path csv = repoRoot().resolve(CAMPAIGN_DIR + "/saleor-458-root-inventory.csv");
        String raw = read(csv);
        List<List<String>> records = new ArrayList<>();
        List<String> field = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (inQuotes) {
                if (c == '"') {
                    if (i + 1 < raw.length() && raw.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        inQuotes = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                inQuotes = true;
            } else if (c == ',') {
                field.add(cur.toString());
                cur.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < raw.length() && raw.charAt(i + 1) == '\n') {
                    i++;
                }
                field.add(cur.toString());
                cur.setLength(0);
                if (!(field.size() == 1 && field.get(0).isEmpty())) {
                    records.add(field);
                }
                field = new ArrayList<>();
            } else {
                cur.append(c);
            }
        }
        field.add(cur.toString());
        if (!(field.size() == 1 && field.get(0).isEmpty())) {
            records.add(field);
        }
        assertThat(records).as("ledger must have a header row and 458 data rows").hasSize(459);
        List<String> header = records.get(0);
        List<Map<String, String>> rows = new ArrayList<>();
        for (int r = 1; r < records.size(); r++) {
            List<String> rec = records.get(r);
            assertThat(rec).as("ledger row %d must match the header width", r).hasSize(header.size());
            Map<String, String> map = new LinkedHashMap<>();
            for (int c = 0; c < header.size(); c++) {
                map.put(header.get(c), rec.get(c));
            }
            rows.add(map);
        }
        return rows;
    }

    private static Map<String, Map<String, String>> byName(List<Map<String, String>> ledger) {
        Map<String, Map<String, String>> index = new HashMap<>();
        for (Map<String, String> row : ledger) {
            index.put(row.get("root_name"), row);
        }
        return index;
    }

    private static long count(List<Map<String, String>> ledger, String column, String value) {
        return ledger.stream().filter(r -> value.equals(r.get(column))).count();
    }

    private static boolean fbEvidence(Map<String, String> row) {
        String ev = row.getOrDefault("evidence", "") + " " + row.getOrDefault("candidate_commit", "");
        return ev.contains("fb23ad4c");
    }

    // ---------- the reports' name notations ----------

    /**
     * Expands the reports' compact root notation: {@code payment(s)} to
     * payment and payments, {@code category(ies)} to category and
     * categories; slashes are separators.
     */
    private static List<String> expandNames(String compact) {
        List<String> out = new ArrayList<>();
        for (String token : compact.split("/")) {
            String t = token.trim();
            if (t.isEmpty()) {
                continue;
            }
            if (t.endsWith("(s)")) {
                String base = t.substring(0, t.length() - 3);
                out.add(base);
                out.add(base + "s");
            } else if (t.endsWith("(ies)")) {
                String base = t.substring(0, t.length() - 5);
                out.add(base);
                out.add(base.endsWith("y") ? base.substring(0, base.length() - 1) + "ies" : base + "ies");
            } else {
                out.add(t);
            }
        }
        return out;
    }

    /** Root names from a bare comma-separated cell ("webhook, webhookEvents"). */
    private static List<String> bareNames(String cell) {
        List<String> names = new ArrayList<>();
        for (String part : cell.split(",")) {
            String name = part.trim();
            if (ROOT_NAME.matcher(name).matches()) {
                names.add(name);
            }
        }
        return names;
    }

    /** Root names from a cell's parenthesized list (completion-map P2 families). */
    private static List<String> parenthesizedNames(String cell) {
        List<String> names = new ArrayList<>();
        Matcher m = Pattern.compile("\\(([^()]+)\\)").matcher(cell);
        while (m.find()) {
            names.addAll(bareNames(m.group(1)));
        }
        return names;
    }

    /** Markdown table rows of a section as trimmed cells; header row dropped. */
    private static List<List<String>> tableRows(String text, String sectionHeading) {
        int start = text.indexOf(sectionHeading);
        assertThat(start >= 0).as("section %s must exist", sectionHeading).isTrue();
        List<List<String>> rows = new ArrayList<>();
        boolean sawRow = false;
        for (String rawLine : text.substring(start).split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("|") && line.endsWith("|")) {
                if (line.matches("^\\|+[\\s:\\-|]*\\|+$")) {
                    if (!rows.isEmpty()) {
                        rows.remove(rows.size() - 1);
                    }
                    continue;
                }
                List<String> cells = new ArrayList<>();
                for (String cell : line.substring(1, line.length() - 1).split("\\|")) {
                    cells.add(cell.trim());
                }
                rows.add(cells);
                sawRow = true;
            } else if (sawRow && line.startsWith("#")) {
                break;
            }
        }
        return rows;
    }

    /** An "A / B / C (from D / E / F)" governance ledger-line transition. */
    private record LedgerTransition(long[] close, long[] open) {
        long integratedDelta() {
            return close[0] - open[0];
        }

        long blockedDelta() {
            return close[1] - open[1];
        }

        long notStartedDelta() {
            return close[2] - open[2];
        }
    }

    private static LedgerTransition ledgerLine(String flat) {
        Matcher m = Pattern.compile(
                "(\\d+)\\s*INTEGRATED\\s*/\\s*(\\d+)\\s*BLOCKED_BY_DECISION\\s*/\\s*(\\d+)\\s*NOT_STARTED"
                        + "\\s*\\(from\\s*(\\d+)\\s*/\\s*(\\d+)\\s*/\\s*(\\d+)\\)").matcher(flat);
        assertThat(m.find()).as("the report must state its ledger-line transition").isTrue();
        long[] close = {Long.parseLong(m.group(1)), Long.parseLong(m.group(2)), Long.parseLong(m.group(3))};
        long[] open = {Long.parseLong(m.group(4)), Long.parseLong(m.group(5)), Long.parseLong(m.group(6))};
        assertThat(close[0] + close[1] + close[2]).as("close triple sums to 458").isEqualTo(458L);
        assertThat(open[0] + open[1] + open[2]).as("open triple sums to 458").isEqualTo(458L);
        return new LedgerTransition(close, open);
    }

    // ---------- tests ----------

    @Test
    void ledgerIsCompleteAndReadmeTotalsMatch() throws IOException {
        List<Map<String, String>> ledger = readLedger();
        assertThat(ledger).as("one row per exact root: 458 rows").hasSize(458);

        Set<String> names = new LinkedHashSet<>();
        for (Map<String, String> row : ledger) {
            names.add(row.get("root_name"));
        }
        assertThat(names).as("458 unique root names").hasSize(458);

        Map<String, Long> kinds = new LinkedHashMap<>();
        for (String kind : List.of("Query", "Mutation", "Subscription")) {
            kinds.put(kind, count(ledger, "root_kind", kind));
        }

        String readme = plain(read(repoRoot().resolve(CAMPAIGN_DIR + "/README.md")));
        Matcher m = Pattern.compile(
                        "(\\d+)\\s*=\\s*(\\d+)\\s*Query\\s*\\+\\s*(\\d+)\\s*Mutation\\s*\\+\\s*(\\d+)\\s*Subscription")
                .matcher(readme);
        assertThat(m.find()).as("README must state the kind-total equation").isTrue();
        assertThat(Long.parseLong(m.group(1))).as("README total").isEqualTo((long) ledger.size());
        assertThat(Long.parseLong(m.group(2))).as("README Query total").isEqualTo(kinds.get("Query"));
        assertThat(Long.parseLong(m.group(3))).as("README Mutation total").isEqualTo(kinds.get("Mutation"));
        assertThat(Long.parseLong(m.group(4))).as("README Subscription total").isEqualTo(kinds.get("Subscription"));
    }

    @Test
    void completionMapWaveTableAndAuditArithmeticMatchLedger() throws IOException {
        List<Map<String, String>> ledger = readLedger();
        long nsTotal = count(ledger, "status", "NOT_STARTED");
        long fbTotal = ledger.stream().filter(CampaignReportsGuardTest::fbEvidence).count();

        String map = read(repoRoot().resolve(REPORTS_DIR + "/completion-map-20261024.md"));
        String flat = plain(map);

        Matcher audit = Pattern.compile("NOT_STARTED\\s*\\((\\d+)\\)").matcher(flat);
        assertThat(audit.find()).as("completion map must state the audited NOT_STARTED baseline").isTrue();
        assertThat(Long.parseLong(audit.group(1)))
                .as("audit baseline = current NOT_STARTED + fb23ad4c flips")
                .isEqualTo(nsTotal + fbTotal);

        long tableSum = 0;
        for (String line : map.split("\n")) {
            Matcher row = Pattern.compile("^\\|\\s*(P[1-6])\\s*\\|\\s*(\\d+)\\s*\\|(.*)\\|$").matcher(line.trim());
            if (!row.find()) {
                continue;
            }
            String wave = row.group(1);
            long claimed = Long.parseLong(row.group(2));
            long derived = ledger.stream()
                    .filter(r -> "NOT_STARTED".equals(r.get("status")) && wave.equals(r.get("wave")))
                    .count();
            assertThat(claimed).as("wave %s absent count", wave).isEqualTo(derived);

            String familyNoParens = row.group(3).replaceAll("\\([^)]*\\)", " ");
            long familySum = 0;
            Matcher nums = Pattern.compile("\\d+").matcher(familyNoParens);
            while (nums.find()) {
                familySum += Long.parseLong(nums.group());
            }
            assertThat(familySum).as("wave %s family cells must sum to the wave count", wave).isEqualTo(claimed);
            tableSum += claimed;
        }
        assertThat(tableSum).as("wave table total").isEqualTo(nsTotal);

        Matcher gap = Pattern.compile("(\\d+)\\s*جذر\\S*\\s*هي الفجوة").matcher(flat);
        assertThat(gap.find()).as("completion map must state the real-gap root count").isTrue();
        assertThat(Long.parseLong(gap.group(1))).as("real gap = ledger NOT_STARTED").isEqualTo(nsTotal);
    }

    @Test
    void completionMapFb23ad4cClaimsMatchLedger() throws IOException {
        List<Map<String, String>> ledger = readLedger();
        List<Map<String, String>> fb = ledger.stream().filter(CampaignReportsGuardTest::fbEvidence).toList();
        Map<String, Map<String, String>> index = byName(ledger);

        String map = plain(read(repoRoot().resolve(REPORTS_DIR + "/completion-map-20261024.md")));

        Matcher flips = Pattern.compile("(\\d+)\\s*جذر\\S*\\s*مطبقة").matcher(map);
        assertThat(flips.find()).as("completion map must state the fb23ad4c flip count").isTrue();
        assertThat(Long.parseLong(flips.group(1))).as("claimed flips = ledger fb23ad4c rows")
                .isEqualTo((long) fb.size());

        long fbP2 = fb.stream().filter(r -> "P2".equals(r.get("wave"))).count();
        long fbP3 = fb.stream().filter(r -> "P3".equals(r.get("wave"))).count();
        assertThat(fbP2 + fbP3).as("every fb23ad4c flip is P2 or P3").isEqualTo((long) fb.size());

        Matcher p3 = Pattern.compile("(\\d+)\\s*جذور\\s*P3\\s*\\(voucherCreate/Update/Delete/BulkDelete\\)").matcher(map);
        assertThat(p3.find()).as("completion map must name the four P3 voucher roots").isTrue();
        assertThat(Long.parseLong(p3.group(1))).as("the claimed P3 flips").isEqualTo(fbP3);
        Set<String> fbP3Names = new LinkedHashSet<>();
        fb.stream().filter(r -> "P3".equals(r.get("wave"))).forEach(r -> fbP3Names.add(r.get("root_name")));
        assertThat(fbP3Names).as("the fb P3 set is exactly the four voucher mutations")
                .containsExactlyInAnyOrder("voucherCreate", "voucherUpdate", "voucherDelete", "voucherBulkDelete");

        Matcher fam = Pattern.compile("كتابة\\s*P2\\s*\\((.*?)\\)\\s*\\+").matcher(map);
        assertThat(fam.find()).as("completion map must list the P2 write families").isTrue();
        long familySum = 0;
        Matcher nums = Pattern.compile("\\d+").matcher(fam.group(1));
        while (nums.find()) {
            familySum += Long.parseLong(nums.group());
        }
        assertThat(fam.group(1)).as("the two P2 reads must be named").contains("قراءتا collection/collections");
        for (String name : List.of("collection", "collections")) {
            Map<String, String> row = index.get(name);
            assertThat(row).as("%s must exist in the ledger", name).isNotNull();
            assertThat(row.get("root_kind")).as("%s is a read root", name).isEqualTo("Query");
            assertThat(fbEvidence(row)).as("%s carries fb23ad4c evidence", name).isTrue();
        }
        assertThat(familySum + 2).as("family cells + the two named reads = fb P2 flips").isEqualTo(fbP2);
    }

    @Test
    void completionMapP2RequiredDecisionRootsAreRealAndNotStarted() throws IOException {
        List<Map<String, String>> ledger = readLedger();
        Map<String, Map<String, String>> index = byName(ledger);
        long p2Ns = ledger.stream()
                .filter(r -> "NOT_STARTED".equals(r.get("status")) && "P2".equals(r.get("wave")))
                .count();

        String map = read(repoRoot().resolve(REPORTS_DIR + "/completion-map-20261024.md"));
        List<List<String>> rows = tableRows(map, "قرارات P2 المطلوبة");
        assertThat(rows).as("the P2-required table must have its six family rows").hasSize(6);

        long counted = 0;
        long individuallyVerified = 0;
        Set<String> seen = new LinkedHashSet<>();
        for (List<String> cells : rows) {
            assertThat(cells.size()).as("P2-required rows have family, count, requirement cells")
                    .isGreaterThanOrEqualTo(3);
            Matcher countCell = Pattern.compile("^(\\d+)$").matcher(cells.get(1));
            assertThat(countCell.find()).as("the count cell must be a bare number: %s", cells.get(1)).isTrue();
            counted += Long.parseLong(countCell.group(1));
            for (String name : parenthesizedNames(cells.get(0))) {
                if (seen.add(name)) {
                    individuallyVerified++;
                    Map<String, String> row = index.get(name);
                    assertThat(row).as("cited root %s must exist in the ledger", name).isNotNull();
                    assertThat(row.get("status")).as("cited root %s must still be NOT_STARTED", name)
                            .isEqualTo("NOT_STARTED");
                    assertThat(row.get("wave")).as("cited root %s is wave P2", name).isEqualTo("P2");
                }
            }
        }
        assertThat(counted).as("P2-required count column total").isEqualTo(p2Ns);
        assertThat(individuallyVerified)
                .as("individually verified clean names (slash-compressed tokens ride the count check)")
                .isGreaterThanOrEqualTo(20);
    }

    @Test
    void b6aPacketNumbersTableAndLedgerLineAgree() throws IOException {
        List<Map<String, String>> ledger = readLedger();
        Map<String, Map<String, String>> index = byName(ledger);
        String report = read(repoRoot().resolve(REPORTS_DIR + "/campaign-b6-a.md"));
        String flat = plain(report);

        Matcher packet = Pattern.compile("(\\d+)\\s*Query\\s*roots?\\s+across").matcher(flat);
        assertThat(packet.find()).as("b6-a must state its moved-root count").isTrue();
        long claimed = Long.parseLong(packet.group(1));

        Set<String> tableRoots = new LinkedHashSet<>();
        for (List<String> cells : tableRows(report, "## The decisions")) {
            assertThat(cells.size()).as("decision rows have id, roots, gap cells").isGreaterThanOrEqualTo(2);
            tableRoots.addAll(bareNames(cells.get(1)));
        }
        assertThat(tableRoots).as("the decision table must name %d roots", claimed).hasSize((int) claimed);

        for (String name : tableRoots) {
            Map<String, String> row = index.get(name);
            assertThat(row).as("b6-a root %s must exist in the ledger", name).isNotNull();
            assertThat(row.get("status")).as("b6-a root %s must never read NOT_STARTED again", name)
                    .isNotEqualTo("NOT_STARTED");
        }

        LedgerTransition t = ledgerLine(flat);
        assertThat(t.integratedDelta()).as("a docs-only packet moves no INTEGRATED row").isZero();
        assertThat(t.blockedDelta()).as("blocked delta = the packet's moved count").isEqualTo(claimed);
        assertThat(t.notStartedDelta()).as("NOT_STARTED delta = minus the moved count").isEqualTo(-claimed);
    }

    @Test
    void b6bPacketSplitSentenceMatchesLedgerQuerySurface() throws IOException {
        List<Map<String, String>> ledger = readLedger();
        Map<String, Map<String, String>> index = byName(ledger);
        long queryTotal = count(ledger, "root_kind", "Query");
        String report = read(repoRoot().resolve(REPORTS_DIR + "/campaign-b6-b.md"));
        String flat = plain(report);

        // the split sentence: "N INTEGRATED (names), N BLOCKED_BY_DECISION (...), and N NOT_STARTED"
        Matcher split = Pattern.compile(
                        "(\\d+)\\s*INTEGRATED\\s*\\((.*)\\),\\s*(\\d+)\\s*BLOCKED_BY_DECISION")
                .matcher(flat);
        assertThat(split.find()).as("b6-b must state the Query-surface split").isTrue();
        long integrated = Long.parseLong(split.group(1));
        long blocked = Long.parseLong(split.group(3));
        Matcher nsPart = Pattern.compile("(\\d+)\\s*NOT_STARTED").matcher(flat.substring(split.end()));
        assertThat(nsPart.find()).as("b6-b must state the NOT_STARTED share").isTrue();
        long ns = Long.parseLong(nsPart.group(1));
        assertThat(integrated + blocked + ns).as("the split must sum to the ledger Query total")
                .isEqualTo(queryTotal);

        // the named INTEGRATED roots expand to the claimed count and stay Integrated (terminal)
        List<String> integratedNames = expandNames(split.group(2));
        assertThat(integratedNames).as("the INTEGRATED list names exactly its claimed count")
                .hasSize((int) integrated);
        for (String name : integratedNames) {
            Map<String, String> row = index.get(name);
            assertThat(row).as("b6-b INTEGRATED root %s must exist", name).isNotNull();
            assertThat(row.get("root_kind")).as("b6-b INTEGRATED root %s is a Query root", name).isEqualTo("Query");
            assertThat(row.get("status")).as("b6-b INTEGRATED root %s stays Integrated", name)
                    .isEqualTo("INTEGRATED");
        }

        // the owning-module families expand to the claimed NOT_STARTED count; never Blocked
        Matcher owning = Pattern.compile("owning module\\s*\\((.*?)\\)\\.\\s*Those").matcher(flat);
        assertThat(owning.find()).as("b6-b must name the owning-module families").isTrue();
        List<String> nsNames = new ArrayList<>();
        for (String segment : owning.group(1).split(",")) {
            String compact = segment.trim().split("\\s+over\\s+")[0].trim();
            nsNames.addAll(expandNames(compact));
        }
        assertThat(nsNames).as("the owning-module families name exactly the claimed NOT_STARTED count")
                .hasSize((int) ns);
        for (String name : nsNames) {
            Map<String, String> row = index.get(name);
            assertThat(row).as("b6-b NOT_STARTED root %s must exist", name).isNotNull();
            assertThat(row.get("root_kind")).as("b6-b NOT_STARTED root %s is a Query root", name).isEqualTo("Query");
            assertThat(row.get("status")).as("b6-b NOT_STARTED root %s never becomes BLOCKED", name)
                    .isNotEqualTo("BLOCKED_BY_DECISION");
        }

        // the packet count, decision table and ledger line
        Matcher packet = Pattern.compile("(\\d+)\\s*Query\\s*roots?\\s+moved").matcher(flat);
        assertThat(packet.find()).as("b6-b must state its moved-root count").isTrue();
        long claimed = Long.parseLong(packet.group(1));
        Set<String> tableRoots = new LinkedHashSet<>();
        for (List<String> cells : tableRows(report, "## The decisions")) {
            tableRoots.addAll(bareNames(cells.get(1)));
        }
        assertThat(tableRoots).as("the decision table must name %d roots", claimed).hasSize((int) claimed);
        for (String name : tableRoots) {
            Map<String, String> row = index.get(name);
            assertThat(row).as("b6-b root %s must exist in the ledger", name).isNotNull();
            assertThat(row.get("status")).as("b6-b root %s must never read NOT_STARTED again", name)
                    .isNotEqualTo("NOT_STARTED");
        }

        LedgerTransition t = ledgerLine(flat);
        assertThat(t.integratedDelta()).as("a docs-only packet moves no INTEGRATED row").isZero();
        assertThat(t.blockedDelta()).as("blocked delta = the packet's moved count").isEqualTo(claimed);
        assertThat(t.notStartedDelta()).as("NOT_STARTED delta = minus the moved count").isEqualTo(-claimed);
    }

    @Test
    void b5GovernanceLinesNameRowsThatStayIntegrated() throws IOException {
        List<Map<String, String>> ledger = readLedger();
        Map<String, Map<String, String>> index = byName(ledger);

        String b5a = plain(read(repoRoot().resolve(REPORTS_DIR + "/campaign-b5-a.md")));
        assertThat(b5a).as("b5-a names its two variant rows").contains("the two variant rows");
        for (String name : List.of("productVariant", "productVariants")) {
            assertThat(index.get(name).get("status")).as("%s stays Integrated", name).isEqualTo("INTEGRATED");
        }

        String b5b = plain(read(repoRoot().resolve(REPORTS_DIR + "/campaign-b5-b.md")));
        assertThat(b5b).as("b5-b names its two order rows").contains("the two order rows");
        for (String name : List.of("order", "orders")) {
            assertThat(index.get(name).get("status")).as("%s stays Integrated", name).isEqualTo("INTEGRATED");
        }
    }
}
