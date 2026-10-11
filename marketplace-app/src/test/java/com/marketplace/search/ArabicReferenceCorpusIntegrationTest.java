package com.marketplace.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import test.config.IntegrationContainers;

import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 5's CI-side measurement (the plan §5.2-1 + D-07): the pinned Arabic
 * reference corpus RE-MEASURED on the real PostgreSQL 18 engine, with the
 * EXACT production SQL shapes the community repository runs —
 * {@code to_tsvector('arabic', …) @@ websearch_to_tsquery('arabic', :q)}
 * (the {@code searchVisibleFullText} predicate) and the pg_trgm word
 * -similarity fallback {@code :q <% (title || ' ' || body)} at the default
 * 0.6 threshold (the {@code searchVisibleSimilar} predicate). Any engine
 * drift from the recorded pins fails here, explicitly, with the document
 * and query ids.
 *
 * <p><b>D-07, the named-configuration gate (measured, not assumed):</b>
 * the corpus was measured against the {@code arabic} configuration — this
 * test first verifies THROUGH {@code pg_catalog} that PostgreSQL 18 actually
 * ships a NAMED configuration called {@code arabic} (a stem dictionary
 * existing in the install proves nothing; the configuration is what the
 * production SQL names) and that it folds the corpus's recorded
 * normalization prelude (أ/إ/آ → ا) exactly as the pins assume.
 *
 * <p><b>The house shape:</b> this class owns its container — {@code
 * @Testcontainers} on the class and a {@code @Container @ServiceConnection
 * PostgreSQLContainer} field (AGENTS.md Testing + the 2026-09-24 isolation
 * rule, enforced structurally by PlatformGovernanceFilesTest on every
 * {@code *IntegrationTest}). The measurement drives plain JDBC against the
 * container's own URL (a corpus harness, not an application slice — no app
 * context, no module fixtures), and {@code disabledWithoutDocker} keeps the
 * local no-Docker run honest (the unit-side corpus test covers the file's
 * structural contract everywhere else).
 */
@Testcontainers(disabledWithoutDocker = true)
class ArabicReferenceCorpusIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers; raw type matches the established container pattern.
    static PostgreSQLContainer postgres = IntegrationContainers.postgres();

    private static Connection connection;
    private static JsonNode corpus;

    @BeforeAll
    static void startEngineAndLoadCorpus() throws Exception {
        try (InputStream in = ArabicReferenceCorpusIntegrationTest.class
                .getResourceAsStream("/arabic/arabic-reference-corpus.json")) {
            assertThat(in).as("the corpus file must ship with the test resources").isNotNull();
            corpus = new ObjectMapper().readTree(in);
        }
        connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        // pg_trgm for the fallback arm — the production repository's shape
        // rides the same extension (V9/V166's own CREATE EXTENSION precedent).
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        }
    }

    @AfterAll
    static void releaseEngine() throws Exception {
        if (connection != null) {
            connection.close();
        }
    }

    // ------------------------------------------------------------------
    // D-07 — the named arabic configuration, verified through pg_catalog
    // ------------------------------------------------------------------

    @Test
    void arabicConfigurationIsActuallyNamedInTheEngineCatalog() throws Exception {
        List<String> configs = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT cfgname FROM pg_catalog.pg_ts_config WHERE cfgname = 'arabic'")) {
            while (rs.next()) {
                configs.add(rs.getString(1));
            }
        }
        assertThat(configs)
                .as("D-07: PostgreSQL 18 must ship a NAMED 'arabic' text-search "
                        + "configuration — the production SQL to_tsvector('arabic', …) "
                        + "resolves through pg_catalog.pg_ts_config, and a stem "
                        + "dictionary without the named configuration proves nothing")
                .containsExactly("arabic");
    }

    @Test
    void arabicNormalizationFoldsTheRecordedHamzaPrelude() throws Exception {
        // Corpus row N01 measured on this engine family: أ/إ/آ fold to ا,
        // so «أحمد» normalizes to the lexeme «احمد» — the fold the variant
        // pins depend on. Re-measured live, not assumed.
        String tsv;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT to_tsvector('arabic', ?)::text")) {
            ps.setString(1, "أحمد");
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                tsv = rs.getString(1);
            }
        }
        assertThat(tsv).as("the hamza fold N01 records").contains("'احمد'");
    }

    // ------------------------------------------------------------------
    // The corpus re-measurement — the pins against the live engine
    // ------------------------------------------------------------------

    @Test
    void everyPinnedQueryVerdictReproducesOnTheLiveEngine() throws Exception {
        try (Statement st = connection.createStatement()) {
            st.execute("""
                    CREATE TABLE ref_corpus_doc (
                        id   text PRIMARY KEY,
                        title text NOT NULL,
                        body  text NOT NULL)
                    """);
        }
        try {
            JsonNode documents = corpus.path("documents");
            insertDocuments(documents);

            List<String> drifts = new ArrayList<>();
            int pins = 0;
            for (JsonNode doc : documents) {
                String docId = doc.path("id").asText();
                for (JsonNode query : doc.path("queries")) {
                    String q = query.path("q").asText();
                    pins++;
                    boolean ftsPinned = query.path("fts").asBoolean();
                    boolean trigramPinned = query.path("trigram").asBoolean();

                    boolean ftsMeasured = ftsMatches(docId, q);
                    boolean trigramMeasured = trigramMatches(docId, q);

                    if (ftsMeasured != ftsPinned) {
                        drifts.add(docId + " ← '" + q + "' : FTS pin " + ftsPinned
                                + " but engine measured " + ftsMeasured);
                    }
                    if (trigramMeasured != trigramPinned) {
                        drifts.add(docId + " ← '" + q + "' : trigram pin " + trigramPinned
                                + " but engine measured " + trigramMeasured);
                    }
                }
            }
            assertThat(pins)
                    .as("the measured pin count the unit test guards").isGreaterThanOrEqualTo(45);
            assertThat(drifts)
                    .as("every recorded verdict reproduces on PostgreSQL 18 — "
                            + "an engine drift is a documented-pin failure, listed "
                            + "by document and query id")
                    .isEmpty();
        } finally {
            try (Statement st = connection.createStatement()) {
                st.execute("DROP TABLE ref_corpus_doc");
            }
        }
    }

    // ------------------------------------------------------------------
    // The EXACT production predicates (searchVisibleFullText /
    // searchVisibleSimilar shapes, the coalesce-concatenated body included)
    // ------------------------------------------------------------------

    private void insertDocuments(JsonNode documents) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO ref_corpus_doc (id, title, body) VALUES (?, ?, ?)")) {
            for (JsonNode doc : documents) {
                ps.setString(1, doc.path("id").asText());
                ps.setString(2, doc.path("title").asText());
                ps.setString(3, doc.path("body").asText());
                ps.addBatch();
            }
            ps.executeBatch();
        }
    }

    private boolean ftsMatches(String docId, String query) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT COUNT(*) FROM ref_corpus_doc
                WHERE id = ?
                  AND to_tsvector('arabic', coalesce(title, '') || ' ' || coalesce(body, ''))
                      @@ websearch_to_tsquery('arabic', ?)
                """)) {
            ps.setString(1, docId);
            ps.setString(2, query);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        }
    }

    private boolean trigramMatches(String docId, String query) throws Exception {
        try (PreparedStatement ps = connection.prepareStatement("""
                SELECT COUNT(*) FROM ref_corpus_doc
                WHERE id = ?
                  AND ? <% (coalesce(title, '') || ' ' || coalesce(body, ''))
                """)) {
            ps.setString(1, docId);
            ps.setString(2, query);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1) > 0;
            }
        }
    }
}
