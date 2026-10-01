package com.marketplace.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.aMapWithSize;
import static org.hamcrest.Matchers.hasEntry;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The service-root landing's wire contract — the fix for the owner-measured
 * post-login defect (2026-09-30): an authenticated {@code GET /} answered
 * the raw RFC 7807 no-static-resource 404 in the browser after a
 * saved-request-less {@code POST /login}. The guards here pin both halves of
 * the controller's contract: the redirect target when the SEO capability is
 * bound (302 to the public site's home, trailing-slash normalization from
 * the REAL record code), and the honest service document when it is not
 * (200 application/json naming the deployed surfaces — never a fabricated
 * URL and never the raw 404). The real security chain (the form-login
 * default chain that keeps anonymous {@code /} on the 302-to-login path)
 * is the integration suite's, the same split as SeoControllerWebMvcTest.
 */
@WebMvcTest(controllers = RootController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class
    })
class RootControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    // The real record (the SeoControllerWebMvcTest precedent): only the
    // outer accessor is stubbed — the Seo section's normalization and
    // capability gate run as REAL code under test.
    @MockitoBean
    private CatalogProperties catalogProperties;

    @BeforeEach
    void stubSeoSection() {
        when(catalogProperties.seo()).thenReturn(
                new CatalogProperties.Seo("https://public.example", "/listings/{id}", List.of()));
    }

    @Test
    void root_capabilityBound_redirects302_toThePublicSiteHome() throws Exception {
        mockMvc.perform(get("/"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://public.example/"));
    }

    @Test
    void root_trailingSlashInBase_isNormalizedAway_thenHomePathApplied() throws Exception {
        when(catalogProperties.seo()).thenReturn(
                new CatalogProperties.Seo("https://public.example/", "/listings/{id}", List.of()));

        mockMvc.perform(get("/"))
                .andExpect(status().isFound())
                // one slash of separation — never the double-slash the raw
                // concatenation of an un-normalized base would emit
                .andExpect(header().string("Location", "https://public.example/"));
    }

    @Test
    void root_capabilityOff_answersTheHonestServiceDocument_neverAFabricatedUrl() throws Exception {
        when(catalogProperties.seo()).thenReturn(
                new CatalogProperties.Seo("", "/listings/{id}", List.of()));

        mockMvc.perform(get("/"))
                .andExpect(status().isOk())
                // three same-origin truths — the health surface is deliberately
                // absent (greptile r2, adopted): production binds management on
                // its own port, so a same-origin /actuator/health pointer would
                // be a fabricated URL
                .andExpect(jsonPath("$", aMapWithSize(3)))
                .andExpect(jsonPath("$", hasEntry("service", "marketplace")))
                .andExpect(jsonPath("$", hasEntry("api", "/api/v1")))
                .andExpect(jsonPath("$", hasEntry("docs", "/v3/api-docs")));
    }

    /**
     * Greptile r1 (adopted): a pure {@code Accept: text/html} client must not
     * be answered 406 on a landing route — the mapping carries no produces
     * constraint and the document sets its own content type.
     */
    @Test
    void root_capabilityOff_htmlOnlyAccept_isNotRejected() throws Exception {
        when(catalogProperties.seo()).thenReturn(
                new CatalogProperties.Seo("", "/listings/{id}", List.of()));

        mockMvc.perform(get("/").accept(MediaType.TEXT_HTML))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasEntry("service", "marketplace")));
    }

    @TestConfiguration
    static class MethodSecurityConfig {
    }
}
