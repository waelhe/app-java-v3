package com.marketplace.catalog;

import com.marketplace.shared.api.ProviderListingView;
import com.marketplace.shared.security.CurrentUserProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerAutoConfiguration;

@WebMvcTest(controllers = CatalogController.class,
    excludeAutoConfiguration = {
        OAuth2ResourceServerAutoConfiguration.class
    })
class CatalogControllerWebMvcTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CatalogService catalogService;

    @MockitoBean
    private CurrentUserProvider currentUserProvider;

    @MockitoBean
    private ListingMapper listingMapper;

    // L31: the property embed port (realestate implements it at runtime;
    // the slice mocks the contract — no property data in this slice).
    @MockitoBean
    private com.marketplace.shared.api.PropertyDetailsPort propertyDetailsPort;

    // L38: the completeness photo-count port (media implements it at
    // runtime; the slice mocks the contract).
    @MockitoBean
    private com.marketplace.shared.api.MediaLookupPort mediaLookupPort;

    // L39: the JSON-LD composition leaf (geo tree + config at runtime;
    // the slice mocks the contract — Mockito's default answer for the
    // Optional return is empty, so listings without a property block
    // pass through unchanged).
    @MockitoBean
    private ListingSeoService listingSeoService;

    @TestConfiguration
    @EnableMethodSecurity
    static class MethodSecurityConfig {
    }

    @Test
    void listActive_returnsOk() throws Exception {
        when(catalogService.listActive(any())).thenReturn(org.springframework.data.domain.Page.empty());

        mockMvc.perform(get("/api/v1/listings"))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void create_returnsCreated() throws Exception {
        UUID providerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        var listing = mockView(listingId);
        var response = mockResponse(listingId);

        when(currentUserProvider.getCurrentUserId(any())).thenReturn(providerId);
        when(catalogService.create(any(), any(), any(), any(), any(), any(), any())).thenReturn(listing);
        when(listingMapper.toResponse(listing)).thenReturn(response);

        mockMvc.perform(post("/api/v1/listings")
                        .contentType("application/json")
                        .content("""
                                {"title": "Test", "category": "cat", "priceCents": 1000}
                                """))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void create_withCurrency_passesIsoCodeToService() throws Exception {
        UUID providerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        var listing = mockView(listingId);
        var response = mockResponse(listingId);

        when(currentUserProvider.getCurrentUserId(any())).thenReturn(providerId);
        when(catalogService.create(any(), any(), any(), any(), any(), eq("USD"), any())).thenReturn(listing);
        when(listingMapper.toResponse(listing)).thenReturn(response);

        mockMvc.perform(post("/api/v1/listings")
                        .contentType("application/json")
                        .content("""
                                {"title": "Test", "category": "cat", "priceCents": 1000, "currency": "USD"}
                                """))
                .andExpect(status().isCreated());

        verify(catalogService).create(any(), any(), any(), any(), any(), eq("USD"), any());
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void create_withoutCurrency_defaultsToSarAtTheServiceBoundary() throws Exception {
        UUID providerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        var listing = mockView(listingId);
        var response = mockResponse(listingId);

        when(currentUserProvider.getCurrentUserId(any())).thenReturn(providerId);
        when(catalogService.create(any(), any(), any(), any(), any(), any(), any())).thenReturn(listing);
        when(listingMapper.toResponse(listing)).thenReturn(response);

        mockMvc.perform(post("/api/v1/listings")
                        .contentType("application/json")
                        .content("""
                                {"title": "Test", "category": "cat", "priceCents": 1000}
                                """))
                .andExpect(status().isCreated());

        // omitted currency arrives as null — the house default SAR is applied
        // by the entity layer (the pre-B4 contract byte-for-byte); omitted
        // maxGuests arrives as null too (capacity undeclared — I6)
        verify(catalogService).create(any(), any(), any(), any(), any(), eq((String) null), eq((Integer) null));
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void create_withMaxGuests_passesCapacityToService() throws Exception {
        UUID providerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        var listing = mockView(listingId);
        var response = mockResponse(listingId);

        when(currentUserProvider.getCurrentUserId(any())).thenReturn(providerId);
        when(catalogService.create(any(), any(), any(), any(), any(), any(), eq(4))).thenReturn(listing);
        when(listingMapper.toResponse(listing)).thenReturn(response);

        mockMvc.perform(post("/api/v1/listings")
                        .contentType("application/json")
                        .content("""
                                {"title": "Test", "category": "cat", "priceCents": 1000, "maxGuests": 4}
                                """))
                .andExpect(status().isCreated());

        verify(catalogService).create(any(), any(), any(), any(), any(), any(), eq(4));
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void create_withNonPositiveMaxGuests_answers400AtBeanValidation() throws Exception {
        // The write-side gate layer 1: @Positive on the request record — a
        // zero/negative capacity is a 400 before the service is ever
        // called (the I6 type-gate philosophy, mirrored from SearchCriteria).
        mockMvc.perform(post("/api/v1/listings")
                        .contentType("application/json")
                        .content("""
                                {"title": "Test", "category": "cat", "priceCents": 1000, "maxGuests": 0}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/listings")
                        .contentType("application/json")
                        .content("""
                                {"title": "Test", "category": "cat", "priceCents": 1000, "maxGuests": -3}
                                """))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void create_withInvalidCurrency_answers400Val001() throws Exception {
        mockMvc.perform(post("/api/v1/listings")
                        .contentType("application/json")
                        .content("""
                                {"title": "Test", "category": "cat", "priceCents": 1000, "currency": "XYZ"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(status().isBadRequest());
    }

    @Test
    void getById_returnsOk() throws Exception {
        UUID id = UUID.randomUUID();
        var listing = mockView(id);
        var response = mockResponse(id);

        when(catalogService.getActiveById(id)).thenReturn(listing);
        when(listingMapper.toResponse(listing)).thenReturn(response);

        mockMvc.perform(get("/api/v1/listings/{id}", id))
                .andExpect(status().isOk());
    }

    /**
     * L39: the public detail read composes the JSON-LD block through the
     * leaf service — the block rides the response verbatim when the
     * listing carries a real-estate property block (the composition
     * contract; the field mapping guards live in ListingSeoServiceTest,
     * the real chain in SeoIntegrationTest).
     */
    @Test
    void getById_composesJsonLdBlockThroughTheLeafService() throws Exception {
        UUID id = UUID.randomUUID();
        var listing = mockView(id);
        var withProperty = new ListingResponse(id, null, null, null, null, null, null, null, null)
                .withProperty(new com.marketplace.shared.api.PropertyDetailsPort.PropertyView(
                        id, null, null, null, null, null, null, null, null,
                        null, java.util.List.of(), null, null, null, null));
        var block = new RealEstateListingJsonLd("https://schema.org", "RealEstateListing",
                "Test", null, null, null, null, null, null);
        when(catalogService.getActiveById(id)).thenReturn(listing);
        when(listingMapper.toResponse(listing)).thenReturn(withProperty);
        when(listingSeoService.jsonLdFor(withProperty)).thenReturn(java.util.Optional.of(block));

        mockMvc.perform(get("/api/v1/listings/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonLd['@context']").value("https://schema.org"))
                .andExpect(jsonPath("$.jsonLd['@type']").value("RealEstateListing"))
                .andExpect(jsonPath("$.jsonLd.name").value("Test"));
    }

    /**
     * L38: the completeness endpoint's line format and composition — the
     * controller composes the owned listing (service) + the photo count
     * (media port) + the property block (realestate port) into the record's
     * documented equation. The five-field score body serializes straight
     * from the record; the ownership/freshness contracts are the
     * integration test's (the real chain).
     */
    @Test
    @WithMockUser(roles = "PROVIDER")
    void completeness_composesThePortsIntoTheScoreLineFormat() throws Exception {
        UUID id = UUID.randomUUID();
        ProviderListing owned = ProviderListing.create(
                UUID.randomUUID(), "Villa", "sea view", "APARTMENT", 1000L);
        when(catalogService.getOwnedListing(eq(id), any())).thenReturn(owned);
        when(mediaLookupPort.countUploadedByListing(id)).thenReturn(1L);
        when(propertyDetailsPort.findByListingId(id)).thenReturn(java.util.Optional.empty());

        mockMvc.perform(get("/api/v1/listings/{id}/completeness", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.percent").value(50))
                .andExpect(jsonPath("$.coreFieldsPresent").value(true))
                .andExpect(jsonPath("$.photosPresent").value(true))
                .andExpect(jsonPath("$.propertyDetailsPresent").value(false))
                .andExpect(jsonPath("$.locationPresent").value(false));

        verify(catalogService).getOwnedListing(eq(id), any());
        verify(mediaLookupPort).countUploadedByListing(id);
        verify(propertyDetailsPort).findByListingId(id);
    }

    private static ProviderListingView mockView(UUID id) {
        return new ProviderListingView(id, null, null, null, null, null, null, null, null, null, null);
    }

    private static ListingResponse mockResponse(UUID id) {
        return new ListingResponse(id, null, null, null, null, null, null, null, null);
    }
}
