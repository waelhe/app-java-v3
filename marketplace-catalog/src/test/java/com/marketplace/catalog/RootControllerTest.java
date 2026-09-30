package com.marketplace.catalog;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * Module-local controller unit tests — the house pattern of
 * {@code CatalogControllerTest}: plain Mockito, no Spring context. The
 * web-layer wiring (the real security chain the root rides, serialization)
 * stays covered by the app-side {@code RootControllerWebMvcTest}; THESE
 * tests pin the controller's own composition contract — the landing's
 * two-answer decision (the owner-measured 2026-09-30 defect: an
 * authenticated {@code GET /} must never again answer the raw
 * no-static-resource 404):
 * <ul>
 *   <li>capability bound → 302 with the public site's home as the
 *       Location, no body;</li>
 *   <li>capability OFF → 200 with the honest service document naming the
 *       deployed surfaces, never a fabricated URL;</li>
 *   <li>the trailing-slash normalization is the REAL record code under
 *       test (only the outer accessor is stubbed — the
 *       SeoControllerWebMvcTest precedent).</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class RootControllerTest {

    @Mock
    private CatalogProperties catalogProperties;

    @InjectMocks
    private RootController controller;

    @Test
    void root_capabilityBound_answers302_toThePublicSiteHome() {
        when(catalogProperties.seo()).thenReturn(
                new CatalogProperties.Seo("https://public.example", "/listings/{id}", List.of()));

        ResponseEntity<Map<String, String>> response = controller.root();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(response.getHeaders().getLocation()).hasToString("https://public.example/");
        assertThat(response.getBody()).isNull();
    }

    @Test
    void root_trailingSlashInBase_normalizesBeforeTheHomePath() {
        when(catalogProperties.seo()).thenReturn(
                new CatalogProperties.Seo("https://public.example/", "/listings/{id}", List.of()));

        ResponseEntity<Map<String, String>> response = controller.root();

        // one slash of separation — never the double-slash of a raw
        // concatenation
        assertThat(response.getHeaders().getLocation()).hasToString("https://public.example/");
    }

    @Test
    void root_capabilityOff_answers200_withTheHonestServiceDocument() {
        when(catalogProperties.seo()).thenReturn(
                new CatalogProperties.Seo("", "/listings/{id}", List.of()));

        ResponseEntity<Map<String, String>> response = controller.root();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(response.getBody()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "service", "marketplace",
                "api", "/api/v1",
                "docs", "/v3/api-docs",
                "health", "/actuator/health"));
    }

    @Test
    void root_capabilityOff_neverRedirects_neverGuessesAnOrigin() {
        when(catalogProperties.seo()).thenReturn(
                new CatalogProperties.Seo("", "/listings/{id}", List.of()));

        ResponseEntity<Map<String, String>> response = controller.root();

        // the gate rule every Seo accessor follows: OFF answers honest
        // data, never a fabricated Location
        assertThat(response.getHeaders().getLocation()).isNull();
        assertThat(response.getStatusCode().is3xxRedirection()).isFalse();
    }
}
