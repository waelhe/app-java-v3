package com.marketplace.catalog;

import test.config.IntegrationContainers;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cache.CacheManager;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0008 (plan D-06): the search-engine baseline harness — PostgreSQL IS
 * the baseline, OpenSearch stays behind a proven measurement. The harness
 * measures the EXACT production SQL (the {@code ProviderListingRepository}
 * query shapes verbatim, literals inlined) on a REAL Flyway-migrated
 * PostgreSQL 18, across the plan's three lexical behaviors and their
 * composition:
 *
 * <ol>
 *   <li><b>full-text</b> — {@code to_tsvector('arabic', …) @@ websearch_to_tsquery('arabic', …)}
 *       ordered by the production boost/{@code ts_rank} clause;</li>
 *   <li><b>trigram</b> — the {@code word_similarity}/&lt;% typo fallback;</li>
 *   <li><b>substring</b> — the naive {@code ILIKE '%…%'} scan (the baseline
 *       row that shows WHY the two GIN indexes exist — sequential by
 *       construction);</li>
 *   <li><b>filters + ranking</b> — the production composition (category +
 *       price range on top of FTS) and the {@code ts_rank_cd} ordering
 *       variant (the community surface's ordering).</li>
 * </ol>
 *
 * <p><b>Methodology (fixed, official):</b> every measured path runs
 * {@code EXPLAIN (ANALYZE, BUFFERS)} — PostgreSQL reference, Chapter 14
 * ("Performance Tips", measuring with EXPLAIN ANALYZE and controlling its
 * noise): six executions, the first discarded (cache-warm), the MINIMUM of
 * the remainder reported as the steady state. Structural index-usage
 * assertions ride the repo's own D-I3 pattern
 * ({@code RadiusSearchIntegrationTest}: {@code SET enable_seqscan = off} and
 * the EXPLAIN on the SAME connection, RESET after — no session state leaks)
 * to prove the GIN indexes ARE the serving paths; the MEASURED plans never
 * touch the planner. Absolute latencies are recorded, never asserted (a
 * latency threshold in a test is a flake generator, not a gate).
 *
 * <p><b>The report is the D-06 artifact</b> ("تقرير قياس"): the harness
 * writes {@code target/search-baseline-report.md} — methodology, the
 * measured table, and the serving lines — the standing instrument that
 * produces the engine-comparison evidence on demand. Until the owner/ops
 * accept such a report, PostgreSQL remains the only search engine; no
 * parallel engine, no speculative index.
 *
 * <p>Boot pattern: {@code CatalogSearchFullTextIntegrationTest} verbatim —
 * isolated owned container, Flyway on, {@code ddl-auto=none}.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class SearchBaselineMeasurementIntegrationTest {

    /** The representative dataset's size — large enough to exercise the paths, small enough for CI. */
    private static final int SEED_ROWS = 2_000;

    private static final String REPORT_PROPERTY = "search-baseline.report";
    private static final Path REPORT_PATH = Path.of(
            System.getProperty(REPORT_PROPERTY, "target/search-baseline-report.md"));

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches CatalogSearchFullTextIntegrationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private ProviderListingRepository listingRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectProvider<CacheManager> cacheManagerProvider;

    /** FK parent (V2: provider_listings.provider_id references users(id)). */
    private static final UUID PROVIDER_USER_ID = UUID.randomUUID();

    private static final String[] ARABIC_TITLES = {
            "شقة مفروشة للإيجار", "شقة غرفتين حي النرجس", "بيت للإيجار مع حديقة", "استوديو للايجار"};
    private static final String[] ARABIC_DESCRIPTIONS = {
            "شقة نظيفة بحي هادئ قريبة من المسجد والمدارس مع حديقة صغيرة",
            "غرفتان وصالة مطلة على الشارع الرئيسي مواقف خاصة ومصعد",
            "بيت واسع مع حديقة خلفية ومدخل مستقل جاهز للسكن",
            "استوديو صغير مناسب لطالب أو موظف قريب من الجامعة"};
    private static final String[] ENGLISH_TITLES = {
            "Garden View Apartment", "City View Loft", "Cozy House", "Mechanical Keyboard"};
    private static final String[] ENGLISH_DESCRIPTIONS = {
            "Apartment overlooking a large garden with a nice view",
            "Modern loft with a skyline panorama and big windows",
            "A house with a large garden and a quiet street view",
            "Clicky switches, tenkeyless, hot swappable keyboard"};

    private record BaselineRow(String path, String query, double minMs, String serving) {
    }

    private record Plan(String planText, double executionMs) {
    }

    @BeforeEach
    void seedRepresentativeDataset() {
        cacheManagerProvider.ifAvailable(cm ->
                cm.getCacheNames().forEach(name -> {
                    var cache = cm.getCache(name);
                    if (cache != null) {
                        cache.clear();
                    }
                }));
        // Idempotent across re-runs within the class' container: the seed
        // only fills when the representative row count is not there yet.
        Long existing = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM provider_listings WHERE provider_id = ?", Long.class, PROVIDER_USER_ID);
        if (existing != null && existing >= SEED_ROWS) {
            return;
        }
        jdbcTemplate.update(
                """
                INSERT INTO users (id, subject, email, display_name, role)
                VALUES (?, ?, ?, ?, 'PROVIDER')
                ON CONFLICT (id) DO NOTHING
                """,
                PROVIDER_USER_ID, "search-baseline@example.com", "search-baseline@example.com",
                "Search Baseline Provider");

        List<ProviderListing> rows = new ArrayList<>(SEED_ROWS);
        for (int i = 0; i < SEED_ROWS; i++) {
            int slot = i % 4;
            boolean arabic = i < SEED_ROWS / 2;
            String title = (arabic ? ARABIC_TITLES[slot] : ENGLISH_TITLES[slot]) + " " + (i + 1);
            String description = arabic ? ARABIC_DESCRIPTIONS[slot] : ENGLISH_DESCRIPTIONS[slot];
            ProviderListing listing = ProviderListing.create(
                    PROVIDER_USER_ID, title, description, "home", 100_00L + (i % 900) * 25L);
            listing.activate();
            rows.add(listing);
        }
        listingRepository.saveAll(rows);
    }

    @Test
    void theBaselineIsMeasuredAndReported() throws IOException {
        // -- the production shapes, literals inlined (the methodology's own
        // choice: the measured SQL is the exact production SQL, the literals
        // are this test's own constants — no parameter-planning variance).
        String ftsArabic = """
                SELECT * FROM provider_listings
                WHERE is_deleted = false AND status = 'ACTIVE'
                  AND to_tsvector('arabic', coalesce(title,'') || ' ' || coalesce(description,''))
                      @@ websearch_to_tsquery('arabic', 'شقة للإيجار')
                ORDER BY ((promoted_until IS NOT NULL AND promoted_until > '2026-01-01 00:00:00+00')
                  OR EXISTS (SELECT 1 FROM ad_campaigns campaign
                             WHERE campaign.listing_id = provider_listings.id
                               AND campaign.is_deleted = false
                               AND campaign.status = 'ACTIVE'
                               AND campaign.consumed_cents < campaign.budget_cents
                               AND (campaign.ends_at IS NULL OR campaign.ends_at > '2026-01-01 00:00:00+00'))) DESC,
                    ts_rank(
                        to_tsvector('arabic', coalesce(title,'') || ' ' || coalesce(description,'')),
                        websearch_to_tsquery('arabic', 'شقة للإيجار')
                    ) DESC, id
                LIMIT 20
                """;
        String ftsEnglish = ftsArabic.replace("شقة للإيجار", "garden view");
        String trigram = """
                SELECT * FROM provider_listings
                WHERE is_deleted = false AND status = 'ACTIVE'
                  AND 'شقه' <% (coalesce(title,'') || ' ' || coalesce(description,''))
                ORDER BY ((promoted_until IS NOT NULL AND promoted_until > '2026-01-01 00:00:00+00')
                  OR EXISTS (SELECT 1 FROM ad_campaigns campaign
                             WHERE campaign.listing_id = provider_listings.id
                               AND campaign.is_deleted = false
                               AND campaign.status = 'ACTIVE'
                               AND campaign.consumed_cents < campaign.budget_cents
                               AND (campaign.ends_at IS NULL OR campaign.ends_at > '2026-01-01 00:00:00+00'))) DESC,
                    word_similarity('شقه', coalesce(title,'') || ' ' || coalesce(description,'')) DESC, id
                LIMIT 20
                """;
        String trigramEnglish = trigram.replace("شقه", "gardn");
        String substring = """
                SELECT * FROM provider_listings
                WHERE is_deleted = false AND status = 'ACTIVE'
                  AND (coalesce(title,'') || ' ' || coalesce(description,'')) ILIKE '%شقة%'
                ORDER BY id
                LIMIT 20
                """;
        String ftsFiltered = ftsArabic
                .replace("      @@ websearch_to_tsquery('arabic', 'شقة للإيجار')",
                        "      @@ websearch_to_tsquery('arabic', 'شقة للإيجار')\n"
                                + "                  AND category = 'home'\n"
                                + "                  AND price_cents >= 10000 AND price_cents <= 30000");
        String tsRankCd = ftsArabic.replace("ts_rank(", "ts_rank_cd(");

        List<BaselineRow> rows = new ArrayList<>();
        // The production reality (the planner free) — measured and reported.
        rows.add(measured("full-text", "arabic multi-word (production ranking)", ftsArabic));
        rows.add(measured("full-text", "english multi-word (production ranking)", ftsEnglish));
        rows.add(measured("trigram", "arabic one-edit typo", trigram));
        rows.add(measured("trigram", "english one-edit typo", trigramEnglish));
        rows.add(measured("substring", "ILIKE naive scan", substring));
        rows.add(measured("full-text + filters", "arabic multi-word + category + price", ftsFiltered));
        rows.add(measured("full-text", "ts_rank_cd ordering variant", tsRankCd));

        // -- the structural gates (the D-I3 pattern) — the GIN indexes ARE
        // the serving paths; the substring scan is honest about having none.
        assertThat(planWithSeqscanDisabled(ftsArabic))
                .as("the production FTS query must serve from its Arabic GIN index (V106)")
                .contains("Bitmap Index Scan on idx_listing_search_title");
        assertThat(planWithSeqscanDisabled(trigram))
                .as("the production trigram query must serve from its trigram GIN index (V34/V106 family)")
                .contains("Bitmap Index Scan on idx_listing_search_trgm");
        assertThat(planWithSeqscanDisabled(substring))
                .as("the naive ILIKE scan has NO index by construction — the honest baseline row")
                .contains("Seq Scan");
        assertThat(rows).allSatisfy(row ->
                assertThat(row.minMs()).as("measured steady state for " + row.path()).isPositive());

        // -- the D-06 artifact: the measurement report ("تقرير قياس").
        writeReport(rows);
        assertThat(REPORT_PATH)
                .as("the baseline report must be written (pass -Dsearch-baseline.report=... to relocate)")
                .exists();
    }

    /**
     * The production paths return through the REAL repository — a smoke the
     * report cites (the measured EXPLAIN times are the engine-side steady
     * state; these calls prove the same SQL is what production executes).
     */
    @Test
    void theProductionRepositoryPathsReturnOnTheSeededBaseline() {
        var fts = listingRepository.searchFullText("شقة للإيجار", null, null, null, null,
                java.time.Instant.now(), org.springframework.data.domain.Pageable.ofSize(20));
        assertThat(fts.getTotalElements()).as("the Arabic FTS path matches the seeded rows").isPositive();
        var similar = listingRepository.searchSimilar("شقه", null, null, null, null,
                java.time.Instant.now(), org.springframework.data.domain.Pageable.ofSize(20));
        assertThat(similar.getTotalElements()).as("the trigram fallback matches the typo").isPositive();
    }

    // -- the harness internals -------------------------------------------

    /**
     * Six executions, the first discarded (cache-warm), the minimum of the
     * rest reported — the noise control the reference's own EXPLAIN
     * discussion implies; the serving line cites the plan's scan choice.
     */
    private BaselineRow measured(String path, String query, String sql) {
        double minMs = Double.MAX_VALUE;
        String serving = "planner choice (no index scan in the plan)";
        for (int i = 0; i < 6; i++) {
            Plan plan = explain(sql);
            if (i == 0) {
                continue; // the warm-up run, discarded
            }
            minMs = Math.min(minMs, plan.executionMs());
            if (plan.planText().contains("Bitmap Index Scan on idx_listing_search_title")
                    || plan.planText().contains("Bitmap Index Scan on idx_listing_search_trgm")) {
                serving = "GIN index";
            } else if (plan.planText().contains("Seq Scan")) {
                serving = "sequential scan";
            }
        }
        return new BaselineRow(path, query, minMs, serving);
    }

    private Plan explain(String sql) {
        return jdbcTemplate.execute((Connection con) -> {
            try (Statement statement = con.createStatement()) {
                ResultSet rs = statement.executeQuery("EXPLAIN (ANALYZE, BUFFERS) " + sql);
                StringBuilder plan = new StringBuilder();
                while (rs.next()) {
                    plan.append(rs.getString(1)).append('\n');
                }
                return new Plan(plan.toString(), parseExecutionTime(plan.toString()));
            }
        });
    }

    /** The D-I3 pattern verbatim: SET, the EXPLAIN, and the RESET ride the SAME connection. */
    private String planWithSeqscanDisabled(String sql) {
        return jdbcTemplate.execute((Connection con) -> {
            try (Statement statement = con.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                ResultSet rs = statement.executeQuery("EXPLAIN (ANALYZE, BUFFERS) " + sql);
                StringBuilder plan = new StringBuilder();
                while (rs.next()) {
                    plan.append(rs.getString(1)).append('\n');
                }
                statement.execute("RESET enable_seqscan");
                return plan.toString();
            }
        });
    }

    private static double parseExecutionTime(String planText) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("Execution Time: ([0-9.]+) ms").matcher(planText);
        assertThat(matcher.find())
                .as("EXPLAIN ANALYZE must report its Execution Time line")
                .isTrue();
        return Double.parseDouble(matcher.group(1));
    }

    private void writeReport(List<BaselineRow> rows) throws IOException {
        StringBuilder report = new StringBuilder();
        report.append("# Search baseline measurement (ADR-0008, plan D-06)\n\n");
        report.append("Produced by `SearchBaselineMeasurementIntegrationTest` on the real\n");
        report.append("Flyway-migrated PostgreSQL (the CI's own engine), the production SQL\n");
        report.append("verbatim with literals inlined, `EXPLAIN (ANALYZE, BUFFERS)` — six\n");
        report.append("executions per path, first discarded (cache-warm), minimum of the rest\n");
        report.append("reported as the steady state (PostgreSQL reference, Chapter 14).\n\n");
        report.append("| path | query | steady state (ms) | serving |\n");
        report.append("|---|---|---|---|\n");
        for (BaselineRow row : rows) {
            report.append("| ").append(row.path()).append(" | ").append(row.query())
                    .append(" | ").append(String.format(java.util.Locale.ROOT, "%.3f", row.minMs()))
                    .append(" | ").append(row.serving()).append(" |\n");
        }
        report.append("\nThe engine decision (plan D-06) rides THIS instrument: PostgreSQL is the\n");
        report.append("baseline; an OpenSearch adoption is refused until a report produced on a\n");
        report.append("representative benchmark demonstrates the measured need and the owner/ops\n");
        report.append("accept it. No parallel engine, no speculative index.\n");
        Files.createDirectories(REPORT_PATH.toAbsolutePath().getParent());
        Files.writeString(REPORT_PATH, report.toString(), StandardCharsets.UTF_8);
    }
}
