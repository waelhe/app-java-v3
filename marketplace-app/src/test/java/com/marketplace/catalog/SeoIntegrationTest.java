package com.marketplace.catalog;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * L39 (realestate systems plan §5 — SEO and structured data): the
 * acceptance criteria over the REAL chain — HTTP → the security filter
 * chain (the root-path permitAll lines on the resource-server chain:
 * anonymous crawlers must meet the documents, never the form-login
 * redirect) → the controller → the REAL clean-ACTIVE query (Flyway
 * schema: provider_listings' status/expires_at/is_deleted + the seeded
 * geo tree from R__seed_geo_qudsaya) → the plain-XML builders.
 *
 * <p>Criterion 1 — the served sitemap validates against the standard's
 * own XSD (cached in the test resources, fetched from sitemaps.org on
 * 2026-09-16). Criterion 2 — the expired/paused/soft-deleted/draft
 * rows never appear: the sitemap enumerates exactly the clean ACTIVE
 * set (the L33 seam — an ACTIVE listing whose window already passed is
 * excluded even before the half-hour job pauses it). Criterion 3 — the
 * JSON-LD block on the public detail read carries the measured
 * schema.org field set, with the address level-mapped from the REAL
 * seeded tree (سوريا → ريف دمشق → قدسيا → قدسيا البلد: the attached
 * neighborhood is not a PostalAddress field — the city stays the
 * locality). Criterion 4 — the crawl surface is rate-limited (the
 * {@code seo} named instance) and bounded by the 50,000-URL
 * pagination cap.
 *
 * <p>Boot pattern follows {@code ListingCompletenessIntegrationTest}:
 * the isolated postgis container via {@code @ServiceConnection},
 * Flyway enabled, {@code ddl-auto=none}; the SEO capability is ON for
 * the whole class (a bound public origin in the test properties).
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.jpa.hibernate.ddl-auto=none",
        "marketplace.catalog.seo.public-site-base-url=https://public.example",
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@AutoConfigureMockMvc
class SeoIntegrationTest {

    @Container
    @ServiceConnection
    @SuppressWarnings({"resource", "rawtypes"}) // Lifecycle managed by @Testcontainers extension; raw type matches MarketplaceApplicationTest
    static PostgreSQLContainer postgres = new PostgreSQLContainer(
            DockerImageName.parse("postgis/postgis:18-3.6-alpine")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("marketplace");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private static final UUID OWNER_USER_ID = UUID.randomUUID();

    /** Clean ACTIVE with the real-estate block attached to the seeded neighborhood. */
    private static final UUID CLEAN_ACTIVE_ID = UUID.randomUUID();
    /** ACTIVE but its publication window already passed — never advertised. */
    private static final UUID STALE_ACTIVE_ID = UUID.randomUUID();
    /** PAUSED — not on the public surface at all. */
    private static final UUID PAUSED_ID = UUID.randomUUID();
    /** Clean ACTIVE without a property block — still a public page (no JSON-LD). */
    private static final UUID PLAIN_ACTIVE_ID = UUID.randomUUID();
    /** DRAFT — never public. */
    private static final UUID DRAFT_ID = UUID.randomUUID();
    /** ACTIVE but soft-deleted — excluded structurally by @SoftDelete. */
    private static final UUID SOFT_DELETED_ID = UUID.randomUUID();

    /** The seeded neighborhood (R__seed_geo_qudsya: قدسيا البلد, level 3). */
    private static final UUID QUDSAYYA_OLD_TOWN =
            UUID.fromString("11111111-1111-4111-8111-111111111104");

    private static final String BASE = "https://public.example";

    @BeforeEach
    void seedTheDataset() {
        cleanUp();

        jdbcTemplate.update(
                "INSERT INTO users (id, subject, email, display_name, role) VALUES (?, ?, ?, ?, 'PROVIDER')",
                OWNER_USER_ID, "l39-owner-" + OWNER_USER_ID,
                "l39-owner-" + OWNER_USER_ID + "@example.com", "L39 Owner");

        listing(CLEAN_ACTIVE_ID, "ACTIVE", Instant.now().plusSeconds(3600), false);
        listing(STALE_ACTIVE_ID, "ACTIVE", Instant.now().minusSeconds(3600), false);
        listing(PAUSED_ID, "PAUSED", Instant.now().plusSeconds(3600), false);
        listing(PLAIN_ACTIVE_ID, "ACTIVE", Instant.now().plusSeconds(3600), false);
        listing(DRAFT_ID, "DRAFT", null, false);
        listing(SOFT_DELETED_ID, "ACTIVE", Instant.now().plusSeconds(3600), true);

        property(CLEAN_ACTIVE_ID);
        property(STALE_ACTIVE_ID);
        property(PAUSED_ID);
    }

    @AfterEach
    void cleanUp() {
        jdbcTemplate.update("DELETE FROM property_details WHERE provider_id = ?", OWNER_USER_ID);
        jdbcTemplate.update("DELETE FROM provider_listings WHERE provider_id = ?", OWNER_USER_ID);
        jdbcTemplate.update("DELETE FROM users WHERE id = ?", OWNER_USER_ID);
    }

    /**
     * Criteria 1 + 2 over the real chain: an anonymous crawler receives
     * an application/xml urlset that validates against the standard's
     * own XSD and enumerates EXACTLY the clean ACTIVE set — the stale
     * ACTIVE row (window passed), the paused, the draft, and the
     * soft-deleted row never appear.
     */
    @Test
    void sitemap_anonymousServesAnXsdValidUrlsetOfExactlyTheCleanActiveSet() throws Exception {
        MvcResult result = mockMvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("application/xml"))
                .andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(body)
                .contains("<loc>" + BASE + "/listings/" + CLEAN_ACTIVE_ID + "</loc>")
                .contains("<loc>" + BASE + "/listings/" + PLAIN_ACTIVE_ID + "</loc>")
                .doesNotContain(STALE_ACTIVE_ID.toString())
                .doesNotContain(PAUSED_ID.toString())
                .doesNotContain(DRAFT_ID.toString())
                .doesNotContain(SOFT_DELETED_ID.toString());
        assertThat(body).containsPattern("<lastmod>\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z</lastmod>");
        assertValidAgainstXsd(body, "/seo/sitemap.xsd");
    }

    /** The page parameter serves the same XSD-valid urlset per page. */
    @Test
    void sitemap_pageParameterServesThatPagesUrlset() throws Exception {
        MvcResult result = mockMvc.perform(get("/sitemap.xml").queryParam("page", "1"))
                .andExpect(status().isOk())
                .andReturn();

        assertValidAgainstXsd(result.getResponse().getContentAsString(), "/seo/sitemap.xsd");
    }

    /**
     * robots.txt over the real chain: an anonymous crawler receives the
     * plain UTF-8 text/plain policy — the canonical allow-all Disallow
     * (no paths configured) plus the Sitemap line of the bound origin.
     */
    @Test
    void robots_anonymousServesTheConfiguredPolicy() throws Exception {
        mockMvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/plain"))
                .andExpect(content().string(
                        "User-agent: *\nDisallow:\nSitemap: " + BASE + "/sitemap.xml\n"));
    }

    /**
     * Criterion 3 over the real chain — the JSON-LD block on the public
     * detail read: every field a measured schema.org fact, the address
     * level-mapped from the REAL seeded tree, the Offer carrying the
     * response's own price/currency and the GoodRelations
     * businessFunction of the RENT purpose.
     */
    @Test
    void publicDetail_carriesTheSchemaOrgRealEstateListingBlock() throws Exception {
        mockMvc.perform(get("/api/v1/listings/{id}", CLEAN_ACTIVE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonLd['@context']").value("https://schema.org"))
                .andExpect(jsonPath("$.jsonLd['@type']").value("RealEstateListing"))
                .andExpect(jsonPath("$.jsonLd.name").value("L39 Listing"))
                .andExpect(jsonPath("$.jsonLd.url").value(BASE + "/listings/" + CLEAN_ACTIVE_ID))
                .andExpect(jsonPath("$.jsonLd.offers['@type']").value("Offer"))
                .andExpect(jsonPath("$.jsonLd.offers.price").value(10.00))
                .andExpect(jsonPath("$.jsonLd.offers.priceCurrency").value("SAR"))
                .andExpect(jsonPath("$.jsonLd.offers.businessFunction")
                        .value("http://purl.org/goodrelations/v1#LeaseOut"))
                .andExpect(jsonPath("$.jsonLd.address['@type']").value("PostalAddress"))
                .andExpect(jsonPath("$.jsonLd.address.addressCountry").value("سوريا"))
                .andExpect(jsonPath("$.jsonLd.address.addressRegion").value("ريف دمشق"))
                .andExpect(jsonPath("$.jsonLd.address.addressLocality").value("قدسيا"));
    }

    /** A listing without the real-estate block is not a RealEstateListing — no JSON-LD. */
    @Test
    void publicDetail_withoutPropertyBlock_hasNoJsonLd() throws Exception {
        mockMvc.perform(get("/api/v1/listings/{id}", PLAIN_ACTIVE_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonLd")
                        .value(org.hamcrest.Matchers.nullValue()));
    }

    /** The public detail contract is unchanged: non-ACTIVE answers 404. */
    @Test
    void publicDetail_stillAnswers404ForNonActiveListings() throws Exception {
        mockMvc.perform(get("/api/v1/listings/{id}", PAUSED_ID))
                .andExpect(status().isNotFound());
    }

    /** An out-of-range page has nothing to enumerate — the honest 404 (no valid empty urlset exists per the XSD). */
    @Test
    void sitemap_outOfRangePage_answers404() throws Exception {
        mockMvc.perform(get("/sitemap.xml").queryParam("page", "99"))
                .andExpect(status().isNotFound());
    }

    // ---- helpers (the ListingCompletenessIntegrationTest seeding discipline) ----

    private void listing(UUID id, String status, Instant expiresAt, boolean deleted) {
        jdbcTemplate.update(
                """
                INSERT INTO provider_listings (id, provider_id, title, description, category, price_cents, currency, status, expires_at, created_at, updated_at, version, is_deleted)
                VALUES (?, ?, 'L39 Listing', 'description', 'APARTMENT', 1000, 'SAR', ?, ?, now(), now(), 0, ?)
                """,
                id, OWNER_USER_ID, status,
                expiresAt == null ? null : java.sql.Timestamp.from(expiresAt), deleted);
    }

    private void property(UUID listingId) {
        jdbcTemplate.update(
                """
                INSERT INTO property_details (id, listing_id, provider_id, purpose, property_type, area_m2, location_id, created_at, updated_at, version, is_deleted)
                VALUES (?, ?, ?, 'RENT', 'APARTMENT', 120, ?, now(), now(), 0, false)
                """,
                UUID.randomUUID(), listingId, OWNER_USER_ID, QUDSAYYA_OLD_TOWN);
    }

    private static void assertValidAgainstXsd(String xml, String xsdPath) throws Exception {
        javax.xml.validation.SchemaFactory factory =
                javax.xml.validation.SchemaFactory.newInstance(javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI);
        try (java.io.InputStream schemaStream = SeoIntegrationTest.class.getResourceAsStream(xsdPath)) {
            assertThat(schemaStream).as("the cached standard schema %s", xsdPath).isNotNull();
            javax.xml.validation.Validator validator =
                    factory.newSchema(new javax.xml.transform.stream.StreamSource(schemaStream)).newValidator();
            validator.validate(new javax.xml.transform.stream.StreamSource(new java.io.StringReader(xml)));
        }
    }
}
