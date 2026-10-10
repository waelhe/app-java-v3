package com.marketplace.search;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pinned Arabic reference corpus is the reviewable measurement basis
 * of phase 5 (the plan §5.2-1: «مجموعة اختبار عربية ممثلة قابلة للمراجعة»)
 * — this test is its executable specification and runs WITHOUT any
 * container (the plain-JUnit form the house unit tests speak), so the
 * corpus's structure and its measurement pins are validated on every
 * local build, not only on CI.
 *
 * <p><b>What this test deliberately does NOT do:</b> measure PostgreSQL.
 * The engine measurement runs on the real PG18 container in
 * {@code ArabicReferenceCorpusIntegrationTest} (Testcontainers — CI).
 * Splitting the two keeps the corpus honest: the pins recorded here were
 * MEASURED on PostgreSQL 18.2 (2026-10-10) with the exact production SQL
 * shapes the corpus file documents, and the integration test re-measures
 * them on the CI image — any engine drift fails there, explicitly, with
 * the document/query ids.
 *
 * <p><b>The six coverage axes</b> (the plan's own list): Arabic/Latin
 * mixing, dialects, hamza + alef/ya variants (أ/إ/آ/ى/ي), diacritics and
 * tatweel, common misspellings, and local business/place names — across
 * both text-searched domains (LISTING and COMMUNITY_POST), each pin
 * carrying the measured FTS and trigram verdict plus the honest note
 * (the documented gaps are pins too, not omissions).
 */
class ArabicReferenceCorpusTest {

    private static final Set<String> VARIANTS = Set.of(
            "MIXED_ARABIC_LATIN", "DIALECT", "HAMZA_ALEF_YA_VARIANTS",
            "DIACRITICS_TATWEEL", "COMMON_TYPOS", "BUSINESS_AND_PLACES");
    private static final Set<String> DOMAINS = Set.of("LISTING", "COMMUNITY_POST");

    private static JsonNode corpus;

    @BeforeAll
    static void loadPinnedCorpus() throws Exception {
        try (InputStream in = ArabicReferenceCorpusTest.class
                .getResourceAsStream("/arabic/arabic-reference-corpus.json")) {
            assertThat(in).as("the corpus file must ship with the test resources").isNotNull();
            corpus = new ObjectMapper().readTree(in);
        }
    }

    @Test
    void corpusIsVersionPinnedAndMeasuredOnTheManagedEngine() {
        assertThat(corpus.path("corpus").asText()).isEqualTo("arabic-search-reference-corpus");
        assertThat(corpus.path("version").asText()).isEqualTo("1.0.0");
        // The §5.2 discipline: the baseline is measured, not assumed — the
        // file names the engine family its pins were measured against.
        assertThat(corpus.path("measuredAgainst").asText()).contains("PostgreSQL 18");
        assertThat(corpus.path("sqlShapes").path("fts").asText()).contains("to_tsvector('arabic'");
        assertThat(corpus.path("sqlShapes").path("trigram").asText()).contains("<%");
    }

    @Test
    void everyDocumentIsCompleteUniqueAndTyped() {
        JsonNode documents = corpus.path("documents");
        assertThat(documents.size()).as("the represented corpus size").isGreaterThanOrEqualTo(12);

        Set<String> ids = new HashSet<>();
        Set<String> variantsSeen = new HashSet<>();
        Set<String> domainsSeen = new HashSet<>();
        for (JsonNode doc : documents) {
            String id = doc.path("id").asText();
            assertThat(id).as("document id present").isNotBlank();
            assertThat(ids.add(id)).as("document id unique: %s", id).isTrue();
            assertThat(DOMAINS).as("%s domain", id).contains(doc.path("domain").asText());
            assertThat(VARIANTS).as("%s variant", id).contains(doc.path("variant").asText());
            assertThat(doc.path("title").asText()).as("%s title", id).isNotBlank();
            assertThat(doc.path("body").asText()).as("%s body", id).isNotBlank();
            variantsSeen.add(doc.path("variant").asText());
            domainsSeen.add(doc.path("domain").asText());
        }
        // The six axes of §5.2-1 are ALL covered, and both searched domains.
        assertThat(variantsSeen).as("all six variants covered").isEqualTo(VARIANTS);
        assertThat(domainsSeen).as("both searched domains covered").isEqualTo(DOMAINS);
    }

    @Test
    void everyQueryPinIsCompleteAndGapPinsCarryTheirHonestNote() {
        int pins = 0;
        for (JsonNode doc : corpus.path("documents")) {
            JsonNode queries = doc.path("queries");
            assertThat(queries.size()).as("%s carries queries", doc.path("id").asText())
                    .isGreaterThanOrEqualTo(2);
            for (JsonNode query : queries) {
                pins++;
                String label = doc.path("id").asText() + " ← " + query.path("q").asText();
                assertThat(query.path("q").asText()).as("%s non-blank query", label).isNotBlank();
                JsonNode fts = query.path("fts");
                JsonNode trigram = query.path("trigram");
                assertThat(fts.isBoolean()).as("%s fts pin is boolean", label).isTrue();
                assertThat(trigram.isBoolean()).as("%s trigram pin is boolean", label).isTrue();
                // A measured gap is a documented finding, never a silent one:
                // every false pin names why in its note (the reviewable basis).
                if (!fts.asBoolean() || !trigram.asBoolean()) {
                    assertThat(query.path("note").asText())
                            .as("%s documents its measured gap", label).isNotBlank();
                }
            }
        }
        assertThat(pins).as("the measured pin count").isGreaterThanOrEqualTo(45);
    }

    @Test
    void variantCoverageCarriesItsMeasurementContract() {
        for (JsonNode doc : corpus.path("documents")) {
            String variant = doc.path("variant").asText();
            boolean anyFtsHit = false;
            boolean anyTrigramHit = false;
            boolean anyGap = false;
            boolean anyLatinQuery = false;
            for (JsonNode query : doc.path("queries")) {
                anyFtsHit |= query.path("fts").asBoolean();
                anyTrigramHit |= query.path("trigram").asBoolean();
                anyGap |= !query.path("fts").asBoolean() || !query.path("trigram").asBoolean();
                anyLatinQuery |= query.path("q").asText().chars().anyMatch(c -> c < 128);
            }
            switch (variant) {
                case "MIXED_ARABIC_LATIN" -> {
                    assertThat(anyLatinQuery).as("%s carries a Latin-side query", doc.path("id").asText()).isTrue();
                    assertThat(anyFtsHit).as("%s Latin query answers under the arabic config", doc.path("id").asText()).isTrue();
                }
                case "HAMZA_ALEF_YA_VARIANTS", "DIACRITICS_TATWEEL" ->
                    // The engine's documented strength: folding variants match FTS-wise.
                    assertThat(anyFtsHit).as("%s folding variant matches FTS", doc.path("id").asText()).isTrue();
                case "DIALECT", "COMMON_TYPOS" ->
                    // The engine's documented limits: dialect synonymy and some
                    // typos are measured gaps — pinned as false, not hidden.
                    assertThat(anyGap).as("%s pins at least one measured gap", doc.path("id").asText()).isTrue();
                case "BUSINESS_AND_PLACES" ->
                    assertThat(anyFtsHit).as("%s local name is findable", doc.path("id").asText()).isTrue();
                default -> throw new IllegalStateException("uncovered variant " + variant);
            }
            // Every document carries measured verdicts on both engine arms
            // (per-doc the truth varies; per-corpus both arms are exercised —
            // asserted by the next test).
            assertThat(anyFtsHit || anyGap).as("%s carries measured verdicts", doc.path("id").asText()).isTrue();
            assertThat(anyTrigramHit || anyGap).as("%s carries measured verdicts", doc.path("id").asText()).isTrue();
        }
    }

    @Test
    void bothEngineArmsAreExercisedAcrossTheCorpus() {
        boolean anyFtsHit = false;
        boolean anyFtsGap = false;
        boolean anyTrigramHit = false;
        boolean anyTrigramGap = false;
        for (JsonNode doc : corpus.path("documents")) {
            for (JsonNode query : doc.path("queries")) {
                anyFtsHit |= query.path("fts").asBoolean();
                anyFtsGap |= !query.path("fts").asBoolean();
                anyTrigramHit |= query.path("trigram").asBoolean();
                anyTrigramGap |= !query.path("trigram").asBoolean();
            }
        }
        // The corpus is a two-arm measurement basis: FTS hits AND misses, the
        // trigram arm's recoveries AND its limits — both directions pinned.
        assertThat(anyFtsHit).as("FTS successes pinned").isTrue();
        assertThat(anyFtsGap).as("FTS gaps pinned").isTrue();
        assertThat(anyTrigramHit).as("trigram recoveries pinned").isTrue();
        assertThat(anyTrigramGap).as("trigram limits pinned").isTrue();
    }

    @Test
    void normalizationEvidenceIsPinnedWithMeasuredLexemes() {
        JsonNode evidence = corpus.path("normalizationEvidence");
        assertThat(evidence.size()).as("the measured normalization table")
                .isGreaterThanOrEqualTo(9);
        Set<String> ids = new HashSet<>();
        for (JsonNode row : evidence) {
            String id = row.path("id").asText();
            assertThat(ids.add(id)).as("evidence id unique: %s", id).isTrue();
            assertThat(row.path("input").asText()).as("%s input", id).isNotBlank();
            assertThat(row.path("lexemes").size()).as("%s measured lexemes", id)
                    .isGreaterThanOrEqualTo(1);
            for (JsonNode lexeme : row.path("lexemes")) {
                assertThat(lexeme.asText()).as("%s lexeme", id).isNotBlank();
            }
        }
        // N01: the hamza fold — the documented أ/إ/آ → ا prelude of arabic_stem.
        assertThat(corpus.path("normalizationEvidence").get(0).path("lexemes").get(0).asText())
                .isEqualTo("احمد");
    }
}
