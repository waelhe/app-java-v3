package com.marketplace.catalog;

import test.config.IntegrationContainers;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0007 (plan D-07): the Arabic text-search configuration is verified
 * against the REAL engine, not assumed. The plan's own wording is the
 * contract: the presence of the {@code arabic_stem} dictionary does not
 * prove a NAMED configuration — the gate is an actual {@code pg_catalog}
 * query against the database the CI really runs, plus the proof that the
 * SHIPPED indexes and the PRODUCTION predicate resolve through that same
 * named configuration.
 *
 * <p>Boot pattern: the {@code CatalogSearchFullTextIntegrationTest} shape
 * verbatim — full application context on an ISOLATED
 * {@code postgis/postgis:18-3.6-alpine} container via {@code @ServiceConnection},
 * Flyway enabled and {@code ddl-auto=none}, so the catalog is exactly what
 * the V1..V176 migration chain produces (the indexes under verification are
 * V106's and V168's, and the assertions read them post-migration).
 *
 * <p>Official basis (PostgreSQL documentation, Chapter 12 / catalogs):
 * {@code pg_ts_config} (the named configurations), {@code pg_ts_config_map}
 * (the token-type → dictionary mappings), {@code pg_ts_dict} (the
 * dictionaries), and {@code pg_indexes.indexdef} (the stored index
 * expression — which carries the configuration name inside
 * {@code to_tsvector(...)} verbatim).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class ArabicSearchConfigurationVerificationIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches CatalogSearchFullTextIntegrationTest (this testcontainers version ships a non-generic PostgreSQLContainer)
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /**
     * The named configuration exists and resolves through the Arabic
     * stemmer — the exact fact the plan says a dictionary alone cannot
     * prove: one row per backing dictionary, so the assertion picks the
     * {@code arabic_stem} row and requires real mappings behind it.
     */
    @Test
    void theNamedArabicConfigurationExistsAndResolvesThroughItsStemmer() {
        record ConfigRow(String cfgName, String parserName, String dictName, long mappings) {
        }
        java.util.List<ConfigRow> rows = jdbcTemplate.query(
                """
                SELECT c.cfgname, p.prsname, d.dictname, count(m.mapseqno) AS mappings
                FROM pg_catalog.pg_ts_config c
                JOIN pg_catalog.pg_ts_parser p ON p.oid = c.cfgparser
                JOIN pg_catalog.pg_ts_config_map m ON m.mapcfg = c.oid
                JOIN pg_catalog.pg_ts_dict d ON d.oid = m.mapdict
                WHERE c.cfgname = 'arabic'
                GROUP BY c.cfgname, p.prsname, d.dictname
                """,
                (rs, i) -> new ConfigRow(rs.getString("cfgname"), rs.getString("prsname"),
                        rs.getString("dictname"), rs.getLong("mappings")));
        assertThat(rows)
                .as("pg_ts_config must carry the named 'arabic' configuration (plan D-07: "
                        + "a dictionary alone proves nothing — the NAMED configuration is the fact)")
                .isNotEmpty();
        assertThat(rows)
                .as("the 'arabic' configuration must resolve token types through the "
                        + "arabic_stem snowball dictionary with real mappings")
                .anySatisfy(row -> {
                    assertThat(row.cfgName()).isEqualTo("arabic");
                    assertThat(row.parserName()).isEqualTo("default");
                    assertThat(row.dictName()).isEqualTo("arabic_stem");
                    assertThat(row.mappings()).isPositive();
                });
    }

    /**
     * The shipped Arabic FTS indexes — V106's listings index and V168's
     * community-posts index — are built on the SAME named configuration:
     * their stored {@code indexdef} carries {@code to_tsvector('arabic', …)}
     * verbatim. A configuration the indexes do not name is a configuration
     * the queries can never be served by.
     */
    @Test
    void theShippedArabicIndexesAreBuiltOnTheNamedConfiguration() {
        java.util.List<String> defs = jdbcTemplate.queryForList(
                """
                SELECT indexdef FROM pg_indexes
                WHERE indexname IN ('idx_listing_search_title', 'idx_neighborhood_posts_search_fts')
                """, String.class);
        assertThat(defs)
                .as("both shipped Arabic FTS indexes must exist post-migration (V106, V168)")
                .hasSize(2);
        for (String indexdef : defs) {
            assertThat(indexdef)
                    .as("the index must embed the NAMED configuration: " + indexdef)
                    .contains("to_tsvector('arabic'");
        }
    }

    /**
     * The production predicate resolves and self-matches on THIS database —
     * the configuration the code names is the configuration this engine
     * resolves (the runtime resolution Chapter 12 defines: an unknown
     * configuration name fails the query at parse time, so a green run here
     * IS the engine-side proof).
     */
    @Test
    void theProductionPredicateResolvesThroughTheNamedConfiguration() {
        Boolean selfMatch = jdbcTemplate.queryForObject(
                """
                SELECT to_tsvector('arabic', 'سيارات') @@ to_tsquery('arabic', 'سيارات')
                """, Boolean.class);
        assertThat(selfMatch)
                .as("the named configuration must resolve the production @@ predicate")
                .isTrue();

        Long matches = jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM provider_listings
                WHERE to_tsvector('arabic', coalesce(title,'') || ' ' || coalesce(description,''))
                      @@ websearch_to_tsquery('arabic', 'شقة للإيجار')
                  AND is_deleted = false AND status = 'ACTIVE'
                """, Long.class);
        assertThat(matches)
                .as("the production searchFullText predicate shape must execute against "
                        + "the migrated schema naming the SAME configuration")
                .isNotNull();
    }
}
