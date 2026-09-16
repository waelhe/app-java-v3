package com.marketplace.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

/**
 * L39 (realestate systems plan §5 — SEO): the two crawler surfaces'
 * wire-format guards — the robots.txt body shape (RFC 9309: the
 * {@code User-agent: *} group, the configured Disallow lines or the
 * canonical empty one, the sitemaps.org {@code Sitemap:} line only when
 * the public origin is bound) and the sitemap's delegation contract
 * (200 application/xml passthrough, 503 when the service's capability
 * gate throws, 400 for a zero page). The documents' own validity guards
 * live in {@code SitemapServiceTest}; the real security chain (the
 * root-path permitAll lines against the form-login default chain) and
 * the real data are the integration test's.
 */
@WebMvcTest(controllers = SeoController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class
    })
class SeoControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SitemapService sitemapService;

    // The real record (a Mockito stub of the outer accessor only — the
    // Seo section's own normalization/conformance helpers stay REAL
    // code under test; mocking the whole record would mock them away).
    @MockitoBean
    private CatalogProperties catalogProperties;

    @BeforeEach
    void stubSeoSection() {
        when(catalogProperties.seo()).thenReturn(
                new CatalogProperties.Seo("https://public.example", "/listings/{id}", List.of()));
    }

    @Test
    void robots_defaultPolicy_isTheCanonicalAllowAll() throws Exception {
        mockMvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("User-agent: *\nDisallow:\nSitemap: https://public.example/sitemap.xml\n"));
    }

    @Test
    void robots_configuredPaths_emitOneDisallowLineEach_plusTheSitemapLine() throws Exception {
        when(catalogProperties.seo()).thenReturn(
                new CatalogProperties.Seo("https://public.example/", "/listings/{id}", List.of("/api/", "/admin")));

        mockMvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        "User-agent: *\nDisallow: /api/\nDisallow: /admin\nSitemap: https://public.example/sitemap.xml\n"));
    }

    @Test
    void robots_capabilityOff_omitsTheSitemapLine_neverGuessesAnOrigin() throws Exception {
        when(catalogProperties.seo()).thenReturn(
                new CatalogProperties.Seo("", "/listings/{id}", List.of()));

        mockMvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string("User-agent: *\nDisallow:\n"));
    }

    @Test
    void robots_nonConformingPaths_areSkippedNotEmitted() throws Exception {
        when(catalogProperties.seo()).thenReturn(
                new CatalogProperties.Seo("https://public.example", "/listings/{id}",
                        List.of("api", "/ok")));

        mockMvc.perform(get("/robots.txt"))
                .andExpect(status().isOk())
                .andExpect(content().string(
                        "User-agent: *\nDisallow: /ok\nSitemap: https://public.example/sitemap.xml\n"));
    }

    @Test
    void sitemap_servesTheDocumentsXmlPassthrough() throws Exception {
        when(sitemapService.sitemap(any())).thenReturn(
                new SitemapService.SeoDocument("<urlset/>", MediaType.APPLICATION_XML));

        mockMvc.perform(get("/sitemap.xml"))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_XML))
                .andExpect(content().string("<urlset/>"));
    }

    @Test
    void sitemap_pageOfOne_isDelegatedUntouched() throws Exception {
        when(sitemapService.sitemap(1)).thenReturn(
                new SitemapService.SeoDocument("<urlset/>", MediaType.APPLICATION_XML));

        mockMvc.perform(get("/sitemap.xml").queryParam("page", "1"))
                .andExpect(status().isOk())
                .andExpect(content().string("<urlset/>"));
    }

    @Test
    void sitemap_zeroPage_is400_atTheBoundary() throws Exception {
        mockMvc.perform(get("/sitemap.xml").queryParam("page", "0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void sitemap_nonNumericPage_is400_byTypeMismatch() throws Exception {
        mockMvc.perform(get("/sitemap.xml").queryParam("page", "abc"))
                .andExpect(status().isBadRequest());
    }

    /** The capability gate's wire contract: 503 problem+json — OFF, not broken. */
    @Test
    void sitemap_capabilityOff_answers503() throws Exception {
        when(sitemapService.sitemap(any())).thenThrow(
                new com.marketplace.shared.api.ServiceUnavailableException(
                        "The sitemap capability is OFF"));

        mockMvc.perform(get("/sitemap.xml"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType("application/problem+json"));
    }

    @TestConfiguration
    static class MethodSecurityConfig {
    }
}
