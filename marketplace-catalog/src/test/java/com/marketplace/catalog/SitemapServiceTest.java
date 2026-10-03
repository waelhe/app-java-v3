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
    private CategoryRepository categoryRepository;
    private SitemapService service;

    @BeforeEach
    void setUp() {
        repository = mock(ProviderListingRepository.class);
        categoryRepository = mock(CategoryRepository.class);
        when(categoryRepository.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of());
        service = new SitemapService(repository, categoryRepository, Clock.fixed(NOW, ZoneOffset.UTC),
                properties(BASE, "/listings/{id}", List.of()));
    }

    // ---- the three honest states ----

    @Test
    void sitemap_blankPublicSiteBaseUrl_answers503NotAFabricatedDocument() {
        service = new SitemapService(repository, categoryRepository, Clock.fixed(NOW, ZoneOffset.UTC),
                properties("", "/listings/{id}", List.of()));
        assertThatThrownBy(() -> service.sitemap(null))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("capability is OFF");
    }

    @Test
    void sitemap_listingPathWithoutPlaceholder_answers503NotAnInvariableDocument() {
        service = new SitemapService(repository, categoryRepository, Clock.fixed(NOW, ZoneOffset.UTC),
                properties(BASE, "/listings", List.of()));
        assertThatThrownBy(() -> service.sitemap(null))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("capability is OFF");
    }

    @Test
    void sitemap_emptyCatalog_answers404_noValidEmptySitemapExistsPerTheXsds() {
        stubCount(0);
        assertThatThrownBy(() -> service.sitemap(null))
                .isInstanceOf(ResourceNotFoundException.class);
        // The empty root never fetches rows — the count alone answers.
        verify(repository, org.mockito.Mockito.never()).findSitemapEntries(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void sitemap_outOfRangePage_answers404() {
        stubPage(List.of(), 100_001);
        assertThatThrownBy(() -> service.sitemap(3))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * The standard's index cardinality cap (CodeRabbit round 2 adoption):
     * "Sitemap index files may not list more than 50,000 Sitemaps" — a
     * count beyond the single-index capacity (2.5 billion clean-ACTIVE
     * listings) answers 503 with an explicit scale message, never an
     * invalid document and never silent truncation. The multi-level
     * index redesign is the documented escalation path, not machinery
     * built for an unreachable threshold.
     */
    @Test
    void sitemap_beyondSingleIndexCapacity_answers503_neverAnInvalidIndex() {
        stubCount(2_500_000_001L);
        assertThatThrownBy(() -> service.sitemap(null))
                .isInstanceOf(ServiceUnavailableException.class)
                .hasMessageContaining("single sitemap index capacity");
        verify(repository, org.mockito.Mockito.never()).findSitemapEntries(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    // ---- the documents (criterion 1: XSD-valid) ----

    @Test
    void sitemap_singlePage_servesAnXsdValidUrlsetWithLocAndLastmod() throws Exception {
        UUID id = UUID.randomUUID();
        Instant updated = Instant.parse("2026-09-14T08:00:00Z");
        stubCount(1);
        stubPage(List.of(new SitemapEntry(id, updated)), 1);

        SitemapService.SeoDocument document = service.sitemap(null);

        assertThat(document.mediaType()).isEqualTo(org.springframework.http.MediaType.APPLICATION_XML);
        assertThat(document.body())
                .contains("<loc>" + BASE + "/listings/" + id + "</loc>")
                .contains("<lastmod>2026-09-14T08:00:00Z</lastmod>");
        assertValidAgainstXsd(document.body(), "/seo/sitemap.xsd");
    }

    @Test
    void sitemap_multiPageRoot_servesAnXsdValidIndexWithoutFetchingAnyRow() throws Exception {
        // 100_001 qualifying listings over the 50_000 cap = 3 pages; the
        // root's page-count decision rides the COUNT only (CodeRabbit
        // round 1 adoption) — the 50,000-row page is never materialized.
        stubCount(100_001);

        SitemapService.SeoDocument document = service.sitemap(null);

        assertThat(document.body())
                .contains("<sitemap><loc>" + BASE + "/sitemap.xml?page=1</loc></sitemap>")
                .contains("<sitemap><loc>" + BASE + "/sitemap.xml?page=2</loc></sitemap>")
                .contains("<sitemap><loc>" + BASE + "/sitemap.xml?page=3</loc></sitemap>");
        assertValidAgainstXsd(document.body(), "/seo/siteindex.xsd");
        verify(repository, org.mockito.Mockito.never()).findSitemapEntries(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
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
        service = new SitemapService(repository, categoryRepository, Clock.fixed(NOW, ZoneOffset.UTC),
                properties(BASE + "/", "/listings/{id}", List.of()));
        UUID id = UUID.randomUUID();
        stubCount(1);
        stubPage(List.of(new SitemapEntry(id, NOW)), 1);

        assertThat(service.sitemap(null).body())
                .contains("<loc>" + BASE + "/listings/" + id + "</loc>");
    }

    // ---- the L32 total-order rule ----

    @Test
    void sitemap_enumeratesInDeterministicIdOrderAtTheStandardCap() {
        stubCount(1);
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


    // ---- W2 (G23): the public category pages enter the sitemap ----

    /**
     * One live registry row as the sitemap enumerates it (the Category
     * entity's own shape). The audit timestamps are the framework's own
     * fields (set at persist by the auditing listener) — the fixture
     * stamps them through reflection, the only access an unpersisted
     * entity offers.
     */
    private Category category(String code, Instant updated) {
        Category seeded = Category.create(code, "label-" + code, "تسمية-" + code, 1);
        try {
            java.lang.reflect.Field field =
                    com.marketplace.shared.jpa.BaseEntity.class.getDeclaredField("updatedAt");
            field.setAccessible(true);
            field.set(seeded, updated);
        } catch (ReflectiveOperationException impossible) {
            throw new IllegalStateException("fixture stamp failed", impossible);
        }
        return seeded;
    }

    @Test
    void sitemap_singlePage_prependsTheCategoryPagesBeforeTheListings() throws Exception {
        UUID id = UUID.randomUUID();
        stubCount(1);
        stubPage(List.of(new SitemapEntry(id, NOW)), 1);
        when(categoryRepository.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(category("stay", NOW)));

        SitemapService.SeoDocument document = service.sitemap(null);

        // The category URL rides FIRST (the registry's stable order), the
        // listing follows — both under the standard's urlset shape.
        int categoryAt = document.body().indexOf("<loc>" + BASE + "/categories/stay</loc>");
        int listingAt = document.body().indexOf("<loc>" + BASE + "/listings/" + id + "</loc>");
        assertThat(categoryAt).isGreaterThan(0);
        assertThat(listingAt).isGreaterThan(categoryAt);
        assertValidAgainstXsd(document.body(), "/seo/sitemap.xsd");
    }

    @Test
    void sitemap_categoryPagesAlone_answerTheDocument_notA404() throws Exception {
        // An empty catalog with a live registry still has public pages to
        // advertise — the XSDs forbid an EMPTY urlset, not a
        // listings-less one.
        stubCount(0);
        stubPage(List.of(), 0);
        when(categoryRepository.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(category("stay", NOW)));

        SitemapService.SeoDocument document = service.sitemap(null);

        assertThat(document.body()).contains("<loc>" + BASE + "/categories/stay</loc>");
        assertValidAgainstXsd(document.body(), "/seo/sitemap.xsd");
    }

    @Test
    void sitemap_categoryPagesRideTheFirstPageAndShiftTheListingWindow() {
        // The W2 shift math: the first served page's listing capacity
        // shrinks by the category count; page 2 begins where page 1's
        // reduced window ended — the pagination never duplicates or
        // omits a listing.
        stubCount(100_001);
        stubPage(List.of(new SitemapEntry(UUID.randomUUID(), NOW)), 100_001);
        when(categoryRepository.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(category("stay", NOW), category("maid", NOW)));

        service.sitemap(2);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findSitemapEntries(eq(ListingStatus.ACTIVE), eq(NOW), captor.capture());
        assertThat(captor.getValue().getOffset())
                .isEqualTo(SitemapService.SITEMAP_PAGE_SIZE - 2L);
        assertThat(captor.getValue().getPageSize()).isEqualTo(SitemapService.SITEMAP_PAGE_SIZE);
    }

    @Test
    void sitemap_firstPageWithCategories_firesTheReducedListingLimit() {
        stubCount(2);
        stubPage(List.of(new SitemapEntry(UUID.randomUUID(), NOW)), 2);
        when(categoryRepository.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(category("stay", NOW), category("maid", NOW)));

        service.sitemap(1);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findSitemapEntries(eq(ListingStatus.ACTIVE), eq(NOW), captor.capture());
        assertThat(captor.getValue().getOffset()).isZero();
        assertThat(captor.getValue().getPageSize())
                .isEqualTo(SitemapService.SITEMAP_PAGE_SIZE - 2);
    }

    /**
     * The category block's OWN capability gate (CodeRabbit/greptile W2
     * round, adopted from the root): a site origin and a valid LISTING
     * path with a category path that cannot vary per category — the
     * sitemap serves the listing-only document (the pre-W2 behavior,
     * byte-identical), never a 500 on categoryUrl's empty Optional. The
     * registry is never even consulted.
     */
    @Test
    void sitemap_categoryPathWithoutPlaceholder_servesTheListingsAlone_neverA500() throws Exception {
        service = new SitemapService(repository, categoryRepository, Clock.fixed(NOW, ZoneOffset.UTC),
                properties(BASE, "/listings/{id}", "/categories", List.of()));
        UUID id = UUID.randomUUID();
        stubCount(1);
        stubPage(List.of(new SitemapEntry(id, NOW)), 1);
        when(categoryRepository.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(category("stay", NOW)));

        SitemapService.SeoDocument document = service.sitemap(null);

        assertThat(document.body()).contains("<loc>" + BASE + "/listings/" + id + "</loc>");
        assertThat(document.body()).doesNotContain("/categories/");
        assertValidAgainstXsd(document.body(), "/seo/sitemap.xsd");
        // The OFF capability never consults the registry — the gate stands
        // before the load, not after it.
        verify(categoryRepository, org.mockito.Mockito.never())
                .findAll(any(org.springframework.data.domain.Sort.class));
    }

    /**
     * The zero-limit first page (greptile W2 round, adopted): exactly
     * 50,000 live categories fill the whole first page's capacity — the
     * listing query is SKIPPED (a LIMIT 0 fetch answers nothing), and the
     * page serves the categories alone.
     */
    @Test
    void sitemap_firstPageFilledByCategoriesAlone_skipsTheZeroLimitListingQuery() throws Exception {
        List<Category> full = java.util.stream.IntStream.rangeClosed(1, SitemapService.SITEMAP_PAGE_SIZE)
                .mapToObj(i -> category("cat-" + i, NOW))
                .toList();
        stubCount(1);
        when(categoryRepository.findAll(any(org.springframework.data.domain.Sort.class)))
                .thenReturn(full);

        // The root answers the index (50,001 total > one page); page 1 is
        // the categories' own page.
        assertThat(service.sitemap(null).body()).contains("<sitemapindex");
        SitemapService.SeoDocument firstPage = service.sitemap(1);

        assertThat(firstPage.body()).contains("<loc>" + BASE + "/categories/cat-1</loc>");
        assertThat(firstPage.body()).doesNotContain("/listings/");
        // No row was EVER fetched: the root answered the index before any
        // fetch, and the categories-only first page skips the zero-limit
        // listing query (the greptile-adopted skip — never a LIMIT 0 run).
        verify(repository, org.mockito.Mockito.never()).findSitemapEntries(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any());
    }

    // ---- helpers ----

    private void stubPage(List<SitemapEntry> content, long total) {
        Page<SitemapEntry> page = new PageImpl<>(content,
                PageRequest.of(0, SitemapService.SITEMAP_PAGE_SIZE, Sort.by(Sort.Direction.ASC, "id")),
                total);
        when(repository.findSitemapEntries(eq(ListingStatus.ACTIVE), any(Instant.class), any(Pageable.class)))
                .thenReturn(page);
    }

    private void stubCount(long total) {
        when(repository.countSitemapEntries(eq(ListingStatus.ACTIVE), any(Instant.class)))
                .thenReturn(total);
    }

    private static CatalogProperties properties(String base, String listingPath, List<String> disallow) {
        return new CatalogProperties(null,
                new CatalogProperties.Seo(base, listingPath, "/categories/{code}", disallow),
                new CatalogProperties.Views("test-key", java.time.Duration.ofDays(1)), new CatalogProperties.Ads(java.time.Duration.ofDays(1)));
    }

    private static CatalogProperties properties(String base, String listingPath,
                                                 String categoryPath, List<String> disallow) {
        return new CatalogProperties(null,
                new CatalogProperties.Seo(base, listingPath, categoryPath, disallow),
                new CatalogProperties.Views("test-key", java.time.Duration.ofDays(1)), new CatalogProperties.Ads(java.time.Duration.ofDays(1)));
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
