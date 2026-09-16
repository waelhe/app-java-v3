package com.marketplace.catalog;

import com.marketplace.shared.api.ResourceNotFoundException;
import com.marketplace.shared.api.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * L39 (realestate systems plan §5 — SEO): the sitemap document's unit
 * guards — the three honest states (503 capability-off, 404
 * nothing-to-enumerate, the document itself), the sitemaps.org 0.90
 * validity of BOTH document shapes (urlset and sitemap index) against
 * the standard's own XSDs (the plan's criterion 1), and the L32
 * deterministic total order.
 *
 * <p><b>XSD provenance:</b> {@code /seo/sitemap.xsd} and
 * {@code /seo/siteindex.xsd} are the standard's published schemas,
 * fetched from sitemaps.org/schemas/sitemap/0.9 on 2026-09-16 and held
 * in the test resources verbatim — the validator is the JDK's own
 * {@link javax.xml.validation.Validator}, no new dependency.
 */
class SitemapServiceTest {

    private static final Instant NOW = Instant.parse("2026-09-16T10:15:30Z");
    private static final String BASE = "https://public.example";

    private ProviderListingRepository repository;
    private SitemapService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProviderListingRepository.class);
        service = new SitemapService(repository, Clock.fixed(NOW, ZoneOffset.UTC),
                properties(BASE, "/listings/{id}", List.of()));
    }

    // ---- the three honest states ----

    @Test
    void sitemap_blankPublicSiteBaseUrl_answers503NotAFabricatedDocument() {
        service = new SitemapService(repository, Clock.fixed(NOW, ZoneOffset.UTC),
                properties("", "/listings/{id}", List.of()));
        assertThatThrownBy(() -> service.sitemap(null))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("capability is OFF");
    }

    @Test
    void sitemap_listingPathWithoutPlaceholder_answers503NotAnInvariableDocument() {
        service = new SitemapService(repository, Clock.fixed(NOW, ZoneOffset.UTC),
                properties(BASE, "/listings", List.of()));
        assertThatThrownBy(() -> service.sitemap(null))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("capability is OFF");
    }

    @Test
    void sitemap_emptyCatalog_answers404_noValidEmptySitemapExistsPerTheXsds() {
        stubPage(List.of(), 0);
        assertThatThrownBy(() -> service.sitemap(null))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void sitemap_outOfRangePage_answers404() {
        stubPage(List.of(), 100_001);
        assertThatThrownBy(() -> service.sitemap(3))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---- the documents (criterion 1: XSD-valid) ----

    @Test
    void sitemap_singlePage_servesAnXsdValidUrlsetWithLocAndLastmod() throws Exception {
        UUID id = UUID.randomUUID();
        Instant updated = Instant.parse("2026-09-14T08:00:00Z");
        stubPage(List.of(new SitemapEntry(id, updated)), 1);

        SitemapService.SeoDocument document = service.sitemap(null);

        assertThat(document.mediaType()).isEqualTo(org.springframework.http.MediaType.APPLICATION_XML);
        assertThat(document.body())
                .contains("<loc>" + BASE + "/listings/" + id + "</loc>")
                .contains("<lastmod>2026-09-14T08:00:00Z</lastmod>");
        assertValidAgainstXsd(document.body(), "/seo/sitemap.xsd");
    }

    @Test
    void sitemap_multiPageRoot_servesAnXsdValidIndexWithOneChildPerPage() throws Exception {
        // 100_001 qualifying listings over the 50_000 cap = 3 pages; the
        // mocked repository answers the root request's page-1 fetch.
        stubPage(List.of(new SitemapEntry(UUID.randomUUID(), NOW)), 100_001);

        SitemapService.SeoDocument document = service.sitemap(null);

        assertThat(document.body())
                .contains("<sitemap><loc>" + BASE + "/sitemap.xml?page=1</loc></sitemap>")
                .contains("<sitemap><loc>" + BASE + "/sitemap.xml?page=2</loc></sitemap>")
                .contains("<sitemap><loc>" + BASE + "/sitemap.xml?page=3</loc></sitemap>");
        assertValidAgainstXsd(document.body(), "/seo/siteindex.xsd");
    }

    @Test
    void sitemap_explicitPageOfAMultiPageSet_servesThatPagesUrlset() throws Exception {
        stubPage(List.of(new SitemapEntry(UUID.randomUUID(), NOW)), 100_001);

        SitemapService.SeoDocument document = service.sitemap(2);

        assertThat(document.body()).startsWith("<?xml").contains("<urlset");
        assertValidAgainstXsd(document.body(), "/seo/sitemap.xsd");
    }

    @Test
    void sitemap_baseUrlTrailingSlash_isNormalizedAway() {
        service = new SitemapService(repository, Clock.fixed(NOW, ZoneOffset.UTC),
                properties(BASE + "/", "/listings/{id}", List.of()));
        UUID id = UUID.randomUUID();
        stubPage(List.of(new SitemapEntry(id, NOW)), 1);

        assertThat(service.sitemap(null).body())
                .contains("<loc>" + BASE + "/listings/" + id + "</loc>");
    }

    // ---- the L32 total-order rule ----

    @Test
    void sitemap_enumeratesInDeterministicIdOrderAtTheStandardCap() {
        stubPage(List.of(new SitemapEntry(UUID.randomUUID(), NOW)), 1);

        service.sitemap(null);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findSitemapEntries(eq(ListingStatus.ACTIVE), eq(NOW), captor.capture());
        assertThat(captor.getValue().getPageSize()).isEqualTo(SitemapService.SITEMAP_PAGE_SIZE);
        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getSort())
                .isEqualTo(Sort.by(Sort.Direction.ASC, "id"));
    }

    @Test
    void sitemap_pageParam_isOneBasedAtTheRepositoryBoundary() {
        stubPage(List.of(new SitemapEntry(UUID.randomUUID(), NOW)), 100_001);

        service.sitemap(2);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findSitemapEntries(eq(ListingStatus.ACTIVE), eq(NOW), captor.capture());
        assertThat(captor.getValue().getPageNumber()).isEqualTo(1);
    }

    // ---- helpers ----

    private void stubPage(List<SitemapEntry> content, long total) {
        Page<SitemapEntry> page = new PageImpl<>(content,
                PageRequest.of(0, SitemapService.SITEMAP_PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id")),
                total);
        when(repository.findSitemapEntries(eq(ListingStatus.ACTIVE), any(Instant.class), any(Pageable.class)))
                .thenReturn(page);
    }

    private static CatalogProperties properties(String base, String listingPath, List<String> disallow) {
        return new CatalogProperties(null,
                new CatalogProperties.Seo(base, listingPath, disallow));
    }

    private static void assertValidAgainstXsd(String xml, String xsdPath) throws Exception {
        javax.xml.validation.SchemaFactory factory =
                javax.xml.validation.SchemaFactory.newInstance(javax.xml.XMLConstants.W3C_XML_SCHEMA_NS_URI);
        try (java.io.InputStream schemaStream = SitemapServiceTest.class.getResourceAsStream(xsdPath)) {
            assertThat(schemaStream).as("the cached standard schema %s", xsdPath).isNotNull();
            javax.xml.validation.Validator validator =
                    factory.newSchema(new javax.xml.transform.stream.StreamSource(schemaStream)).newValidator();
            validator.validate(new javax.xml.transform.stream.StreamSource(new java.io.StringReader(xml)));
        }
    }
}
