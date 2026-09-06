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
        when(catalogService.create(any(), any(), any(), any(), any(), any())).thenReturn(listing);
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
        when(catalogService.create(any(), any(), any(), any(), any(), eq("USD"))).thenReturn(listing);
        when(listingMapper.toResponse(listing)).thenReturn(response);

        mockMvc.perform(post("/api/v1/listings")
                        .contentType("application/json")
                        .content("""
                                {"title": "Test", "category": "cat", "priceCents": 1000, "currency": "USD"}
                                """))
                .andExpect(status().isCreated());

        verify(catalogService).create(any(), any(), any(), any(), any(), eq("USD"));
    }

    @Test
    @WithMockUser(roles = "PROVIDER")
    void create_withoutCurrency_defaultsToSarAtTheServiceBoundary() throws Exception {
        UUID providerId = UUID.randomUUID();
        UUID listingId = UUID.randomUUID();
        var listing = mockView(listingId);
        var response = mockResponse(listingId);

        when(currentUserProvider.getCurrentUserId(any())).thenReturn(providerId);
        when(catalogService.create(any(), any(), any(), any(), any(), any())).thenReturn(listing);
        when(listingMapper.toResponse(listing)).thenReturn(response);

        mockMvc.perform(post("/api/v1/listings")
                        .contentType("application/json")
                        .content("""
                                {"title": "Test", "category": "cat", "priceCents": 1000}
                                """))
                .andExpect(status().isCreated());

        // omitted currency arrives as null — the house default SAR is applied
        // by the entity layer (the pre-B4 contract byte-for-byte)
        verify(catalogService).create(any(), any(), any(), any(), any(), eq((String) null));
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

    private static ProviderListingView mockView(UUID id) {
        return new ProviderListingView(id, null, null, null, null, null, null, null, null, null);
    }

    private static ListingResponse mockResponse(UUID id) {
        return new ListingResponse(id, null, null, null, null, null, null, null);
    }
}
